"""AndroidOnly: WP-003 Architecture-neutral local verification and nonblocking pre-push hook."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile


SHA = re.compile(r"[0-9a-f]{40}")
ZERO = "0" * 40
ALL_STAGES = ["python", "preflight", "scaffold", "inspect"]
OPTIONAL_STAGES = {"benchmark", "benchmark-dependencies"}
REQUIRED_TOOLCHAIN = {"python", "jdk", "android_sdk", "gradle"}
def runtime_files():
    return (
        ("check.py", "check.py"), ("fast.py", "fast.py"),
        ("run.sh", "run.sh"),
    )



def bundle_revisions(revision):
    return ["--all", revision]



def capture(command, **kwargs):
    return subprocess.check_output(command, **kwargs).decode("utf-8").strip()


def push_commits(text):
    commits = []
    for line in text.splitlines():
        fields = line.split()
        if len(fields) != 4 or not SHA.fullmatch(fields[1]) or not SHA.fullmatch(fields[3]):
            raise ValueError("Malformed pre-push input")
        if fields[1] != ZERO and fields[1] not in commits:
            commits.append(fields[1])
    return commits


def binding(repo, revision):
    commit = capture(["git", "-C", str(repo), "rev-parse", "--verify", revision + "^{commit}"])
    tree = capture(["git", "-C", str(repo), "rev-parse", commit + "^{tree}"])
    return commit, tree


def transport(distribution):
    if os.name != "nt":
        if shutil.which("bash") is None:
            raise ValueError("Bash is unavailable")
        return []
    wsl = shutil.which("wsl.exe")
    if wsl is None:
        raise ValueError("WSL is unavailable")
    prefix = [wsl]
    if distribution:
        prefix.extend(["--distribution", distribution])
    prefix.append("--")
    subprocess.run([*prefix, "bash", "-c", "command -v git && command -v flock"], check=True)
    return prefix


def linux_path(path, prefix):
    if not prefix:
        return str(path)
    return capture([*prefix, "wslpath", "-u", str(path).replace("\\", "/")])


def run_candidate(repo, revision, prefix, stages, worktree):
    if worktree:
        raise ValueError("Working-tree overlays are not supported by the installed merge verifier")
    revision, _ = binding(repo, revision)
    key = hashlib.sha256(str(repo).encode("utf-8")).hexdigest()[:16]
    tool = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix="meshcore-local-") as temporary:
        directory = Path(temporary)
        control = directory / "control"
        control.mkdir()
        for source_name, installed_name in runtime_files():
            source = tool / source_name
            if source.is_file():
                (control / installed_name).write_bytes(source.read_bytes().replace(b"\r\n", b"\n"))
        bundle = directory / "candidate.bundle"
        subprocess.run(["git", "-C", str(repo), "bundle", "create", str(bundle), *bundle_revisions(revision)], check=True)
        subprocess.run([
            *prefix, "bash", linux_path(control / "run.sh", prefix),
            linux_path(bundle, prefix), revision, key, linux_path(control, prefix),
            ",".join(stages), "",
        ], check=True)


def pending_file(repo):
    common = Path(capture([
        "git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-common-dir",
    ]))
    return common / "meshcore-local" / "verification-pending.json"


def record_pending(repo, revision, reason):
    commit, tree = binding(repo, revision)
    path = pending_file(repo)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".json.next")
    temporary.write_text(json.dumps({
        "schema_version": 1,
        "status": "verification-pending",
        "commit": commit,
        "tree": tree,
        "host": {"system": platform.system(), "machine": platform.machine()},
        "reason": str(reason),
    }, indent=2) + "\n", encoding="utf-8", newline="\n")
    os.replace(temporary, path)
    print(
        f"UNVERIFIED CANDIDATE — merge verification still required: {commit} ({reason})",
        file=sys.stderr,
    )


def validate_result(path, expected_commit, expected_tree):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    required = {
        "schema_version", "result", "commit", "tree", "stages", "host",
        "toolchain", "stage_results", "outputs",
    }
    if set(data) != required or data["schema_version"] != 1 or data["result"] != "success":
        raise ValueError("Malformed or unsuccessful merge-verification result")
    if data["commit"] != expected_commit or data["tree"] != expected_tree:
        raise ValueError("Verification result does not bind the exact candidate commit and tree")
    if data["stages"] != ALL_STAGES or not isinstance(data["host"], dict) or not data["host"]:
        raise ValueError("Reduced or malformed declared verification result")
    toolchain = data["toolchain"]
    if not isinstance(toolchain, dict) or set(toolchain) != REQUIRED_TOOLCHAIN:
        raise ValueError("Missing exact toolchain metadata")
    for name, value in toolchain.items():
        if (not isinstance(value, dict) or not isinstance(value.get("version"), str)
                or not value["version"] or not isinstance(value.get("packages"), list)
                or not value["packages"]):
            raise ValueError(f"Missing {name} version or package metadata")
    results = data["stage_results"]
    if not isinstance(results, list) or [item.get("stage") for item in results] != ALL_STAGES:
        raise ValueError("Missing or reordered stage results")
    for result in results:
        if (result.get("result") != "success" or not result.get("tasks")
                or not isinstance(result.get("discovered"), int) or result["discovered"] <= 0
                or result.get("failures") != 0 or result.get("errors") != 0
                or result.get("skipped") != 0):
            raise ValueError(f"Invalid, reduced, failed, skipped or zero-test stage: {result.get('stage')}")
    outputs = data["outputs"]
    if not isinstance(outputs, dict) or not outputs or any(
        not isinstance(name, str) or not isinstance(digest, str)
        or not re.fullmatch(r"[0-9a-f]{64}", digest)
        for name, digest in outputs.items()
    ):
        raise ValueError("Missing or malformed output digests")
    return data


def run_adapter(repo, revision, state_path):
    state = json.loads(Path(state_path).read_text(encoding="utf-8"))
    if set(state) != {"schema_version", "adapter"} or state["schema_version"] != 1:
        raise ValueError("Malformed contributor toolchain adapter state")
    adapter = state["adapter"]
    if not isinstance(adapter, list) or not adapter or not all(isinstance(item, str) and item for item in adapter):
        raise ValueError("Toolchain adapter command must be a non-empty argument list")
    commit, tree = binding(repo, revision)
    with tempfile.TemporaryDirectory(prefix="meshcore-adapter-") as temporary:
        result = Path(temporary) / "result.json"
        subprocess.run([
            *adapter, "--repo", str(repo), "--commit", commit, "--tree", tree,
            "--stages", ",".join(ALL_STAGES), "--output", str(result),
        ], check=True)
        return validate_result(result, commit, tree)


def install(repo, prefix, distribution):
    hooks = Path(capture(["git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-path", "hooks"]))
    hooks.mkdir(parents=True, exist_ok=True)
    hook = hooks / "pre-push"
    marker = "# MeshCore One fast local pre-push"
    if hook.exists() and marker not in hook.read_text(encoding="utf-8"):
        raise ValueError("Existing pre-push hook must be integrated explicitly")
    installed = hooks / "meshcore-local"
    installed.mkdir(exist_ok=True)
    for source in Path(__file__).resolve().parent.iterdir():
        if source.is_file() and source.suffix in (".py", ".sh"):
            (installed / ("test_check.py" if source.name == "hook_test.py" else source.name)).write_bytes(
                source.read_bytes().replace(b"\r\n", b"\n")
            )
    (installed / "config.json").write_text(json.dumps({"distribution": distribution}), encoding="utf-8")
    python = (shutil.which("python") or sys.executable).replace("\\", "/")
    hook.write_text(
        "#!/bin/sh\n" + marker + "\n"
        'hooks=$(git rev-parse --path-format=absolute --git-path hooks) || exit 1\n'
        f'exec "{python}" "$hooks/meshcore-local/check.py" --pre-push "$@"\n',
        encoding="utf-8", newline="\n",
    )
    hook.chmod(0o755)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-hook", action="store_true")
    parser.add_argument("--pre-push", action="store_true")
    parser.add_argument("--distribution")
    parser.add_argument("--commit", default="HEAD")
    parser.add_argument("--worktree", action="store_true")
    parser.add_argument("--stages", default="all")
    parser.add_argument("--toolchain-state", type=Path)
    parser.add_argument("--validate-result", type=Path)
    parser.add_argument("remote", nargs="*")
    args = parser.parse_args(argv)
    try:
        stages = list(ALL_STAGES) if args.stages == "all" else args.stages.split(",")
        if not stages or len(stages) != len(set(stages)) or set(stages) - (set(ALL_STAGES) | OPTIONAL_STAGES):
            raise ValueError("Unknown, empty or duplicate stage")
        if args.pre_push and (args.worktree or args.stages != "all" or args.install_hook):
            raise ValueError("Pre-push accepts only exact committed declared-stage candidates")
        commits = push_commits(sys.stdin.read()) if args.pre_push else [args.commit]
        if args.pre_push and not commits:
            return 0
        repo = Path(capture(["git", "rev-parse", "--show-toplevel"])).resolve()
        if args.validate_result:
            commit, tree = binding(repo, args.commit)
            validate_result(args.validate_result, commit, tree)
            print(f"Merge verification valid for {commit} ({tree}).")
            return 0
        if args.install_hook:
            install(repo, transport(args.distribution), args.distribution)
            return 0
        for commit in commits:
            try:
                if args.toolchain_state:
                    if stages != ALL_STAGES or args.worktree:
                        raise ValueError("Adapters only produce exact committed declared-stage results")
                    run_adapter(repo, commit, args.toolchain_state)
                else:
                    run_candidate(repo, commit, transport(args.distribution), stages, args.worktree)
            except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
                if not args.pre_push:
                    raise
                record_pending(repo, commit, error)
        return 0
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
