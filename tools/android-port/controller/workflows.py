"""AndroidOnly: WP-003 Parse YAML and reject candidate/privileged trust-boundary drift."""

import argparse
import re
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.errors import PortError

ACTION_PINS = {
    "actions/cache": "5a3ec84eff668545956fd18022155c47e93e2684",
    "actions/checkout": "11bd71901bbe5b1630ceea73d27597364c9af683",
    "actions/setup-python": "a26af69be951a213d495a4c3e4e4022e16d87065",
    "actions/upload-artifact": "ea165f8d65b6e75b540449e92b4886f43607fa02",
    "actions/download-artifact": "d3f86a106a0bac45b974a628896c90dbdf5c8093",
}
TRUSTED_WORKFLOWS = (
    "android-port-dispatch.yml",
    "android-pr-shepherd.yml", "android-gate-integrity.yml",
)


def parse_yaml(text: str):
    import yaml

    class UniqueLoader(yaml.BaseLoader):
        def construct_mapping(self, node, deep=False):
            result = {}
            for key_node, value_node in node.value:
                key = self.construct_object(key_node, deep=deep)
                if not isinstance(key, str) or key in result:
                    raise PortError("Duplicate/invalid workflow YAML key")
                result[key] = self.construct_object(value_node, deep=deep)
            return result

    try:
        value = yaml.load(text, Loader=UniqueLoader)
    except yaml.YAMLError as error:
        raise PortError(f"Malformed workflow YAML: {error}") from error
    if not isinstance(value, dict):
        raise PortError("Workflow YAML must be a mapping")
    return value


def validate_boundary(value: dict, text: str):
    if value.get("permissions") != {"contents": "read"}:
        raise PortError("Workflow token must have contents:read only")
    if any(word in text for word in ("secrets.", "pull_request_target", "workflow_run", "self-hosted", "--allow-all", "--live")):
        raise PortError("Workflow crosses candidate/privileged execution boundary")
    for job in value.get("jobs", {}).values():
        if job.get("permissions", {"contents": "read"}) != {"contents": "read"}:
            raise PortError("Job token permissions were elevated")
        if job.get("environment") or job.get("container") or job.get("services"):
            raise PortError("Undeclared environment/shared-service build capability")
        for step in job.get("steps", []):
            if step.get("continue-on-error") == "true":
                raise PortError("Mandatory failure cannot be converted into success")
            if "uses" in step:
                name, separator, pin = step["uses"].partition("@")
                if not separator or ACTION_PINS.get(name) != pin or re.fullmatch(r"[0-9a-f]{40}", pin) is None:
                    raise PortError("Unreviewed/unpinned workflow action")
                if name == "actions/checkout" and step.get("with", {}).get("persist-credentials") != "false":
                    raise PortError("Candidate checkout credentials would persist")


def validate_candidate(value: dict, text: str):
    validate_boundary(value, text)
    events = value.get("on", {})
    if not {"pull_request", "merge_group", "push", "workflow_dispatch"}.issubset(events):
        raise PortError("Required PR/merge-group/push/manual event is missing")
    jobs = value.get("jobs", {})
    if set(jobs) != {"scope", "controller", "build", "external-oracle", "backup-oracle", "android-ci"}:
        raise PortError("Missing/unknown mandatory CI job")
    gate = jobs["android-ci"]
    if (gate.get("name") != "android-ci" or gate.get("if") != "${{ always() }}"
            or gate.get("needs") != ["scope", "controller", "build", "external-oracle", "backup-oracle"]):
        raise PortError("android-ci must always report every selected build outcome")
    build = jobs["build"]
    if any(jobs[name].get("runs-on") != "macos-26-intel" for name in ("external-oracle", "backup-oracle")):
        raise PortError("Reference oracles require the supported macOS26 Intel host; do not downgrade runtime or skip oracles")
    if (build.get("runs-on") != "ubuntu-24.04" or "strategy" in build
            or build.get("needs") != ["scope"]
            or "needs.scope.outputs" not in build.get("if", "")):
        raise PortError("The selected Linux execution host must be mandatory, dependency-gated and unmatrixed")
    steps = build["steps"]
    stage_steps = [step for step in steps if step.get("id") == "gradle"]
    if len(stage_steps) != 1:
        raise PortError("Candidate workflow must use one consolidated scaffold Gradle invocation")
    stage_run = stage_steps[0].get("run", "")
    if (
        stage_steps[0].get("env", {}).get("RUN_CONTROLLER") != "${{ needs.scope.outputs.controller }}"
        or "$affected = @()" not in stage_run
        or "if ($env:RUN_CONTROLLER -ne 'true') { $affected = @('--affected-candidate') }" not in stage_run
        or "ci.py run --stage scaffold @affected @scopes" not in stage_run
    ):
        raise PortError("Candidate Gradle invocation must use exact affected planning outside controller changes")
    inspections = [
        index for index, step in enumerate(steps)
        if "ci.py inspect" in step.get("run", "")
    ]
    apk_uploads = [
        index for index, step in enumerate(steps)
        if step.get("name") == "Upload inspected debug APK for human testing"
    ]
    if len(inspections) != 1 or len(apk_uploads) != 1:
        raise PortError("Candidate requires one inspected debug APK handoff")
    inspection, upload = inspections[0], apk_uploads[0]
    stage = steps.index(stage_steps[0])
    localization_checks = [
        (index, step) for index, step in enumerate(steps)
        if step.get("name") == "Validate localization discovery"
    ]
    if (
        steps[inspection].get("run") != "python tools/android-port/controller/ci.py inspect"
        or steps[inspection].get("if") != "${{ steps.gradle.outputs.full_scaffold == 'true' }}"
        or steps[inspection].get("continue-on-error")
        or upload != inspection + 1
        or stage >= inspection or "if" in steps[stage] or steps[stage].get("continue-on-error")
        or len(localization_checks) != 1
        or localization_checks[0][1].get("if") != "${{ steps.gradle.outputs.localization == 'true' }}"
        or not stage < localization_checks[0][0] < inspection
    ):
        raise PortError("Debug APK upload must immediately follow successful scaffold APK inspection")
    if steps[upload] != {
        "name": "Upload inspected debug APK for human testing",
        "if": "${{ success() && steps.gradle.outputs.full_scaffold == 'true' }}",
        "uses": "actions/upload-artifact@" + ACTION_PINS["actions/upload-artifact"],
        "with": {
            "name": (
                "debug-apk-${{ github.event.pull_request.head.sha || github.sha }}"
                "-${{ github.run_id }}-${{ github.run_attempt }}"
            ),
            "path": "android/app/build/outputs/apk/debug/app-debug.apk",
            "if-no-files-found": "error",
            "retention-days": "7",
        },
    }:
        raise PortError("Debug APK handoff must be successful, scaffold-only, exact-candidate/run-bound and fail on absence")
    gate_runs = "\n".join(s.get("run", "") for s in gate["steps"])
    gate_environment = {
        key: value for step in gate["steps"] for key, value in step.get("env", {}).items()
    }
    if (
        gate_environment.get("BUILD_RESULT") != "${{ needs.build.result }}"
        or "aggregate --artifacts" in gate_runs
        or any(step.get("uses", "").startswith(("actions/upload-artifact@", "actions/download-artifact@"))
               for step in gate["steps"])
    ):
        raise PortError("Android CI must use direct fail-closed job results without duplicate report artifacts")
    all_runs = "\n".join(
        step.get("run", "") for job in jobs.values() for step in job.get("steps", [])
    )
    if all_runs.count("controller/test_runner.py") != 2:
        raise PortError("Controller requires one scope-only selector and exactly one strict test invocation")
    event_arguments = (
        '--candidate-event-name "${{ github.event_name }}" '
        '--candidate-event-path "${{ github.event_path }}"'
    )
    runner_command = (
        "python tools/android-port/controller/test_runner.py --include-tests tools/android-port/local "
        + event_arguments
    )
    parity_runner_command = runner_command.replace(
        " --candidate-event-name", " --include-tests android/parity-tests --candidate-event-name", 1,
    )
    selector_command = (
        "python tools/android-port/controller/test_runner.py " + event_arguments + ' --scope-output "$GITHUB_OUTPUT"'
    )
    selectors = [step for step in jobs["scope"]["steps"] if "controller/test_runner.py" in step.get("run", "")]
    if (
        len(selectors) != 1 or "if" in selectors[0] or selectors[0].get("continue-on-error")
        or selector_command not in selectors[0].get("run", "").splitlines()
    ):
        raise PortError("Candidate scope must select the committed native family without conditional or ignored failure")
    runners = [
        step for step in jobs["controller"]["steps"] if "controller/test_runner.py" in step.get("run", "")
    ]
    if (
        len(runners) != 1 or runners[0].get("if") != "${{ needs.scope.outputs.controller == 'true' }}"
        or runners[0].get("continue-on-error")
        or [
            line for line in runners[0].get("run", "").splitlines()
            if "controller/test_runner.py" in line
        ] not in ([runner_command], [parity_runner_command])
    ):
        raise PortError("Controller CI must discover the real native accessibility runner regressions once and fail closed")
    for name in ("build", "external-oracle", "backup-oracle"):
        job = jobs[name]
        if job.get("if") == "${{ always() }}" or "scope" not in job.get("needs", []):
            raise PortError("Expensive jobs must be scope-selected and use default fail-closed dependencies")
    if jobs["external-oracle"].get("needs") != ["scope"]:
        raise PortError("The independent Swift codec oracle must not wait for controller tests")


def validate_setup(value: dict, text: str):
    validate_boundary(value, text)
    if set(value.get("jobs", {})) != {"copilot-setup-steps"}:
        raise PortError("Cloud setup requires exactly one copilot-setup-steps job")
    job = value["jobs"]["copilot-setup-steps"]
    if set(job) - {"steps", "permissions", "runs-on", "services", "snapshot", "timeout-minutes"}:
        raise PortError("Unsupported cloud setup job property would be ignored")
    if job.get("runs-on") != "ubuntu-24.04" or not 1 <= int(job.get("timeout-minutes", "0")) <= 59:
        raise PortError("Cloud setup needs supported Ubuntu x64 and a <=59 minute budget")
    runs = "\n".join(step.get("run", "") for step in job["steps"])
    if any(command not in runs for command in ("ci.py provision", "ci.py preflight", "ci.py run --stage prepare")):
        raise PortError("Cloud setup must install, check readiness and prepare real strict dependencies")


def validate_trusted(value: dict, text: str):
    validate_boundary(value, text)
    if set(value.get("on", {})) != {"workflow_dispatch"}:
        raise PortError("Privileged-duty wrappers must remain manual/default-branch-only")
    for job in value["jobs"].values():
        if job.get("if") != "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)":
            raise PortError("Trusted duty must execute deployed default-branch code")
        if job.get("name") in ("parity-review", "gate-integrity", "android-ci"):
            raise PortError("Preview cannot impersonate a required gate")
        for step in job["steps"]:
            if step.get("uses", "").startswith("actions/checkout@") and step.get("with", {}).get("ref") != "${{ github.sha }}":
                raise PortError("Trusted duty checks out candidate code")
    if value.get("concurrency", {}).get("cancel-in-progress") != "false":
        raise PortError("Trusted controller operations require a serialized noncancelling lane")


def validate_publisher(value: dict, text: str):
    if set(value) != {"name", "on", "permissions", "concurrency", "jobs"}:
        raise PortError("Unexpected publisher execution property")
    if value.get("permissions") != {
        "contents": "read", "pull-requests": "read", "checks": "write", "actions": "read", "issues": "read",
    }:
        raise PortError("Native publisher requires only read facts and Checks write, never merge/dispatch authority")
    if set(value.get("on", {})) != {"workflow_dispatch"} or set(value.get("jobs", {})) != {"publish"}:
        raise PortError("Native publisher must be manual and contain only the trusted publication job")
    job = value["jobs"]["publish"]
    if (
        job.get("name") != "Native review ${{ inputs.work_package }} PR ${{ inputs.pull_request }} receipt ${{ inputs.review_id }}"
        or job.get("if") != "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)"
        or job.get("runs-on") != "ubuntu-24.04" or job.get("timeout-minutes") != "15"
        or value.get("concurrency") != {"group": "android-port-controller", "cancel-in-progress": "false"}
        or set(job) != {"name", "if", "runs-on", "timeout-minutes", "env", "steps"}
    ):
        raise PortError("Publisher must use a bounded ephemeral default-branch serialized lane")
    if any(word in text for word in ("secrets.", "pull_request_target", "workflow_run", "self-hosted",
                                     "actions/cache", "actions/upload-artifact", "actions/download-artifact")):
        raise PortError("Publisher cannot consume privileged secrets, caches or candidate artifact/code execution")
    steps = job.get("steps", [])
    if len(steps) != 4:
        raise PortError("Unexpected trusted publisher execution step")
    if (
        [set(step) for step in steps] != [
            {"uses", "with"}, {"uses", "with"}, {"name", "run"}, {"name", "env", "run"},
        ]
        or steps[0].get("uses") != "actions/checkout@" + ACTION_PINS["actions/checkout"]
        or steps[1].get("uses") != "actions/setup-python@" + ACTION_PINS["actions/setup-python"]
    ):
        raise PortError("Unexpected publisher step execution property")
    for step in steps:
        if step.get("continue-on-error") or step.get("if"):
            raise PortError("Publisher steps cannot skip or ignore verification failures")
        if "uses" in step:
            name, _, pin = step["uses"].partition("@")
            if name not in ("actions/checkout", "actions/setup-python") or ACTION_PINS[name] != pin:
                raise PortError("Unexpected/unpinned publisher action")
    if steps[0].get("with") != {"ref": "${{ github.sha }}", "fetch-depth": "0", "persist-credentials": "false"}:
        raise PortError("Publisher must check out exact trusted code without persisted credentials")
    if steps[1].get("with") != {"python-version": "3.12.4"}:
        raise PortError("Publisher Python pin drift")
    if steps[2].get("run") != (
        '[[ "$PR_NUMBER" =~ ^[1-9][0-9]*$ ]] || exit 2\n'
        'git fetch --no-tags origin "refs/pull/$PR_NUMBER/head"\n'
    ):
        raise PortError("Candidate may be fetched only as Git data, never checked out or executed")
    if steps[3].get("env") != {"ANDROID_PORT_GITHUB_TOKEN": "${{ github.token }}"} or steps[3].get("run") != (
        'python tools/android-port/controller/native_review.py publish '
        '--trusted-base "$GITHUB_SHA" --wp "$WORK_PACKAGE" --pr "$PR_NUMBER" --review-id "$REVIEW_ID"'
    ):
        raise PortError("Only the trusted native publisher may receive the ephemeral Checks token")
    if job["env"] != {
        "WORK_PACKAGE": "${{ inputs.work_package }}", "PR_NUMBER": "${{ inputs.pull_request }}",
        "REVIEW_ID": "${{ inputs.review_id }}",
    }:
        raise PortError("Publisher inputs must be quoted environment data")


def validate_workflows(repo: Path):
    directory = repo / ".github" / "workflows"
    for name, validator in (
        ("android-ci.yml", validate_candidate), ("copilot-setup-steps.yml", validate_setup),
        ("android-parity-review.yml", validate_publisher),
        *((name, validate_trusted) for name in TRUSTED_WORKFLOWS),
    ):
        text = (directory / name).read_text(encoding="utf-8")
        validator(parse_yaml(text), text)
    return {"result": "valid", "scope": "YAML/trust-boundary assertions; no live publisher/cloud/branch-rule claim"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args(argv)
    try:
        print(__import__("json").dumps(validate_workflows(args.repo), indent=2))
        return 0
    except (PortError, OSError, ValueError, ImportError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
