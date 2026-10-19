"""AndroidOnly: WP-003 Trusted manual integration boundary; missing capabilities fail closed."""

import argparse
import json
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.ci_environment import write_json
from controller.errors import PortError
from controller.model import load_manifest
from controller.schema import load_json
from controller.staging import stage_review


def capabilities(policy: dict, *, author="cbattlegear"):
    blockers = []
    if policy["paused"] or policy["dispatch_mode"] == "off" or policy["activation_approved"] is not True:
        blockers.append("Dispatch/repair/merge activation remains off, paused and human-unapproved.")
    if not policy["trusted_gate_publisher_app_id"]:
        blockers.append("Native default-branch review publisher deployment/live publication is unproven.")
    if not policy["trusted_check_app_ids"] or not policy["trusted_workflow_ids"]:
        blockers.append("Required-check application/workflow identities are not approved.")
    if not policy["branch_rules_proven"]:
        blockers.append("Exact-head/current-base no-bypass branch rules are unproven.")
    blockers += [
        "Isolated Copilot CLI reviewer authentication/native-host/live-usage/shared-ledger integration is unproven.",
        "Protected historical receipt revalidation/import remains blocked.",
        "User-selected concurrency/budget and separate activation are still required.",
    ]
    return {
        "schema_version": 1, "state": "BLOCKED", "authoritative": False, "blockers": blockers,
        "manual_review_transport": "authenticated-coordinator-review + default-branch GitHub Actions Checks",
        "custom_app_or_native_host_required_for_manual_review": False,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("capabilities", "stage"))
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--wp")
    parser.add_argument("--trusted-base")
    parser.add_argument("--candidate")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "capabilities":
            load_manifest(args.repo)
            print(json.dumps(capabilities(load_json(args.repo / "docs" / "android" / "automation-policy.json")), indent=2))
            return 2
        if not all((args.wp, args.trusted_base, args.candidate, args.output)):
            raise PortError("Complete exact WP/base/head/output staging inputs are required")
        value = stage_review(args.repo, args.wp, args.trusted_base, args.candidate)
        write_json(args.output, value)
        print(json.dumps({"result": "staged-data-only", "binding": value["binding"], "authoritative": False}, indent=2))
        return 0
    except (PortError, OSError, ValueError, KeyError, UnicodeError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
