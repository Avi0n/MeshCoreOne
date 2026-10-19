import math
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from .errors import PortError
from .schema import fields, load_json, positive_integer


def utc(value: str) -> float:
    if not isinstance(value, str):
        raise PortError("Expected an RFC3339 UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise PortError("Malformed RFC3339 timestamp") from error
    if parsed.tzinfo is None:
        raise PortError("Timestamp requires a timezone")
    return parsed.astimezone(timezone.utc).timestamp()


def boolean(value: str, label: str) -> bool:
    if value not in ("true", "false"):
        raise PortError(f"{label} must be true or false")
    return value == "true"


def positive(value, label: str) -> float:
    if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
        raise PortError(f"{label} must be finite and positive")
    return float(value)


@dataclass(frozen=True)
class Budget:
    unit: str
    limit: float
    reserve_per_launch: float
    window_start: float

    @classmethod
    def parse(cls, value: dict):
        fields(value, {"unit", "limit", "reserve_per_launch", "window_start"}, label="usage budget")
        if value["unit"] not in ("ai_credits", "premium_requests"):
            raise PortError("Unsupported usage unit; launch counts are not a spend policy")
        return cls(
            value["unit"], positive(value["limit"], "Usage limit"),
            positive(value["reserve_per_launch"], "Per-execution usage reservation"),
            utc(value["window_start"]),
        )


@dataclass(frozen=True)
class Usage:
    unit: str
    used: float
    observed_at: float
    complete: bool
    active_ids: tuple[str, ...]
    covered_backends: tuple[str, ...] = ()

    def validate(self, budget: Budget, now: float):
        if self.unit != budget.unit or type(self.used) not in (int, float):
            raise PortError("Usage unit/measurement mismatch")
        if not math.isfinite(self.used) or self.used < 0 or type(self.complete) is not bool or not self.complete:
            raise PortError("Incomplete/invalid backend usage measurement")
        if not math.isfinite(self.observed_at) or self.observed_at > now or now - self.observed_at > 300:
            raise PortError("Stale backend usage measurement")
        if len(set(self.active_ids)) != len(self.active_ids) or any(not v for v in self.active_ids):
            raise PortError("Ambiguous active backend identities")
        if not self.covered_backends or not set(self.covered_backends).issubset({"cloud", "local"}):
            raise PortError("Usage measurement must declare its actually measured backend scope")
        if budget.window_start > now:
            raise PortError("Usage budget window is in the future")


@dataclass(frozen=True)
class Settings:
    mode: str
    paused: bool
    max_inflight: int | None
    budget: Budget | None
    ledger_path: Path | None
    auth_kind: str | None
    user_login: str | None
    token: str | None
    lease_seconds: int

    @classmethod
    def from_env(cls, environment: dict[str, str], policy: dict):
        mode = environment.get("ANDROID_PORT_DISPATCH_MODE", policy["dispatch_mode"])
        if mode not in ("off", "cloud", "local"):
            raise PortError("ANDROID_PORT_DISPATCH_MODE must be off, cloud or local")
        # Environment variables cannot unpause a still-paused trusted bootstrap policy.
        requested_pause = boolean(
            environment.get("ANDROID_PORT_PAUSED", "true"), "ANDROID_PORT_PAUSED"
        )
        paused = policy["paused"] or requested_pause
        inflight = environment.get("ANDROID_PORT_MAX_INFLIGHT")
        lease = environment.get("ANDROID_PORT_LEASE_SECONDS", "1800")
        try:
            max_inflight = positive_integer(int(inflight), "Concurrency limit") if inflight is not None else None
            lease_seconds = positive_integer(int(lease), "Lease duration")
        except ValueError as error:
            raise PortError("Concurrency/lease settings must be integers") from error
        budget_path = environment.get("ANDROID_PORT_USAGE_POLICY_FILE")
        budget = Budget.parse(load_json(Path(budget_path))) if budget_path else None
        ledger_path = environment.get("ANDROID_PORT_LEDGER")
        if ledger_path and not Path(ledger_path).is_absolute():
            raise PortError("ANDROID_PORT_LEDGER requires an explicit absolute durable/shared path")
        return cls(
            mode, paused, max_inflight, budget, Path(ledger_path) if ledger_path else None,
            environment.get("ANDROID_PORT_AUTH_KIND"),
            environment.get("ANDROID_PORT_USER_LOGIN"),
            environment.get("ANDROID_PORT_GITHUB_TOKEN"),
            lease_seconds,
        )

    def blockers(self, policy: dict, backend: str, needs_cloud_auth=True) -> list[str]:
        reasons = []
        if self.mode == "off" or policy["dispatch_mode"] == "off":
            reasons.append("dispatch is off")
        if self.paused or policy["paused"]:
            reasons.append("dispatch/repairs/merges are paused")
        if not policy["activation_approved"]:
            reasons.append("separate human activation is absent")
        if self.mode != backend or policy["dispatch_mode"] != backend:
            reasons.append("requested backend does not match the configured mode")
        if self.max_inflight is None:
            reasons.append("explicit concurrency limit is missing")
        if self.budget is None:
            reasons.append("supported usage/budget policy is missing")
        if self.ledger_path is None:
            reasons.append("durable shared lease ledger is missing")
        if not policy["trusted_check_app_ids"] or not policy["trusted_workflow_ids"]:
            reasons.append("trusted required-check application/workflow identities are unconfigured")
        if not policy["branch_rules_proven"]:
            reasons.append("strict no-bypass branch rules have not been proven at WP-003")
        if needs_cloud_auth:
            if self.auth_kind not in ("personal_access_token", "oauth_user", "github_app_user"):
                reasons.append("user-to-server authentication kind is missing or unsupported")
            if not self.token or not self.user_login:
                reasons.append("explicit user-to-server credential/login is missing")
        return reasons

    def require_live(self, policy: dict, backend: str, needs_cloud_auth=True):
        reasons = self.blockers(policy, backend, needs_cloud_auth)
        if reasons:
            raise PortError("; ".join(reasons))
