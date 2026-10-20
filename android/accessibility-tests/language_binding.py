"""AndroidOnly: WP-503 Current-source language selection/binding, never live host authority."""

import argparse
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid

from shared_device_runner import parse_outcomes

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "android-port"))

from controller.errors import PortError
from controller.gates import policy_revision
from controller.ledger import Ledger
from controller.model import load_manifest
from controller.schema import load_json
from local.debug_packaging import ADAPTER, OPERATION, source_identity
from local.reserve import shared_ledger
from controller.capabilities import CapabilityEngine

CANONICAL = "1666502a-0420-46ef-930f-e78651ba8b9f"
CURRENT_APP_SESSION = "df52b3d0-81d4-49fb-b88f-a5926ed31fa5"
CURRENT_CLI_SESSION = "b531305a-0513-4731-896f-2b470f8879cd"
PUBLIC_REF = "refs/heads/cbattlegear-issue-136-wp-503-accessibility-audit-807238"
PRODUCER = "59a47ac40bd2f84e7188c87a5521af9d0f16e75e"
PRODUCTION = "android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppSettingsContent.kt"
PRODUCTION_BLOB = "421fc446443a5f301085507603701d7da04d40ef"
FIXTURE = "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeAppLanguageAccessibilityTest.kt"
FIXTURE_BLOB = "99175354ba29a9230b7a2b03a7c17ea4153dd394"
CLASS = "com.meshcoreone.android.accessibility.NativeAppLanguageAccessibilityTest"
METHODS = (
    "actualLanguageChoicesExposeTheirOwnNameAndSelectedStateWithContainedFont200Actions",
    "actualNamedChineseChoiceAndSystemResetPreserveLanguageDetailAcrossNativeRecreation",
)
IDENTITIES = tuple(CLASS + "#" + method for method in METHODS)


def git(*arguments):
    return subprocess.check_output(["git", "-C", str(ROOT), *arguments], text=True).strip()


@dataclass(frozen=True)
class LanguageBinding:
    repository: str
    source_revision: str
    android_tree: str
    android_source_tree_sha256: str
    manifest_sha256: str
    policy_revision: str
    canonical_session: str
    replacement_app_session: str
    replacement_cli_session: str
    reservation_revision: int
    producer_revision: str = PRODUCER
    production_blob: str = PRODUCTION_BLOB
    fixture_blob: str = FIXTURE_BLOB


def prepare_binding(revision, claim):
    if (
        not isinstance(revision, str) or re.fullmatch(r"[0-9a-f]{40}", revision) is None
        or claim["wp"] != "WP-503"
        or claim["attempt"] != claim["identity"]["session_id"] or claim["attempt"] != CANONICAL
        or type(claim["revision"]) is not int or claim["revision"] < 1
    ):
        raise PortError("Language binding requires the actual canonical WP503 claim and exact current revision")
    source = source_identity(ROOT, revision)
    if git("merge-base", "--is-ancestor", PRODUCER, revision):
        raise PortError("Unexpected producer ancestry output")
    if (
        git("rev-parse", revision + ":" + PRODUCTION) != PRODUCTION_BLOB
        or git("rev-parse", PRODUCER + ":" + PRODUCTION) != PRODUCTION_BLOB
        or git("rev-parse", revision + ":" + FIXTURE) != FIXTURE_BLOB
    ):
        raise PortError("Exact producer language repair or unchanged strict fixture binding drifted")
    methods = tuple(re.findall(r"@Test fun (\w+)\(", git("show", revision + ":" + FIXTURE)))
    if methods != METHODS:
        raise PortError("Language cohort must retain exactly the two original method identities")
    manifest = load_manifest(ROOT)
    policy = load_json(ROOT / "docs" / "android" / "automation-policy.json")
    CapabilityEngine(policy).admit("WP-503", manifest.wp("WP-503")["write_paths"], ADAPTER, OPERATION)
    return LanguageBinding(
        policy["repository"], revision, git("rev-parse", revision + ":android"), source,
        manifest.sha256, policy_revision(manifest, policy), CANONICAL,
        CURRENT_APP_SESSION, CURRENT_CLI_SESSION, claim["revision"],
    )


@dataclass(frozen=True)
class LanguageRequest:
    binding: LanguageBinding
    api: int
    operation_owner: str

    def __post_init__(self):
        if type(self.api) is not int or self.api not in (31, 37):
            raise PortError("Only the affected API31/API37 language cohort is selected")
        prefix = self.binding.canonical_session + ":language:"
        if (
            self.binding.canonical_session != CANONICAL
            or self.binding.replacement_app_session != CURRENT_APP_SESSION
            or self.binding.replacement_cli_session != CURRENT_CLI_SESSION
            or not self.operation_owner.startswith(prefix)
            or re.fullmatch(r"[0-9a-f]{32}", self.operation_owner[len(prefix):]) is None
        ):
            raise PortError("Language operation must retain canonical identity and truthful current replacement attribution")

    @property
    def identities(self):
        return IDENTITIES


def request_for(binding, api):
    return LanguageRequest(binding, api, CANONICAL + ":language:" + uuid.uuid4().hex)


def current_binding(revision):
    policy = load_json(ROOT / "docs" / "android" / "automation-policy.json")
    claim = Ledger(shared_ledger(ROOT), policy["repository"]).get("WP-503")
    if claim is None:
        raise PortError("Missing actual canonical WP503 reservation")
    return prepare_binding(revision, claim)


def verify_current_request(request):
    if current_binding(request.binding.source_revision) != request.binding:
        raise PortError("Source, policy, current owner or reservation revision changed before execution")
    published = git("ls-remote", "origin", PUBLIC_REF).split()
    if published != [request.binding.source_revision, PUBLIC_REF]:
        raise PortError("Native request is not the exact currently published PR205 input")


def account_outcomes(transcript, request, *, process_exit, forced_termination):
    if type(process_exit) is not int or process_exit != 0 or forced_termination is not False:
        raise PortError("Normal unforced language instrumentation completion is unverified")
    result = parse_outcomes(transcript, list(request.identities))
    if result["passed"] != 2 or any(result[key] for key in ("failed", "errors", "skipped")):
        raise PortError("Language cohort is not all-positive; retain uncertainty and original negative outcomes")
    return {
        **result, "api": request.api, "source_revision": request.binding.source_revision,
        "native_lane_operation_owner": request.operation_owner,
        "scope": "transcript accounting only; real admitted execution/provenance remains required",
        "acceptance": False,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--head", required=True)
    args = parser.parse_args(argv)
    try:
        binding = current_binding(args.head)
        requests = [request_for(binding, api) for api in (31, 37)]
        print(json.dumps({
            "binding": asdict(binding), "requests": [
                {**asdict(request), "identities": request.identities} for request in requests
            ],
            "authority": False, "native_starts": 0, "apks_built": False,
        }, indent=2))
        return 0
    except (PortError, OSError, subprocess.SubprocessError, KeyError, ValueError) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
