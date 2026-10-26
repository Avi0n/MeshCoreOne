"""AndroidOnly: WP-003 Architecture-neutral local verification and nonblocking pre-push hook."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import uuid


SHA = re.compile(r"[0-9a-f]{40}")
ZERO = "0" * 40
ALL_STAGES = ["python", "preflight", "scaffold", "inspect"]
OPTIONAL_STAGES = {"benchmark", "benchmark-dependencies"}
REQUIRED_TOOLCHAIN = {"python", "jdk", "android_sdk", "gradle"}
ADVISORY_QUEUE_SECONDS = 10
ADVISORY_FAST_SECONDS = 180


def execution_runtime(repo):
    common = Path(capture([
        "git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-common-dir",
    ]))
    target = common / "meshcore-reservations"
    if not (target / "runtime-manifest.json").is_file():
        raise ValueError("Missing installed execution-interlock runtime; reviewed installation required")
    source = Path(__file__).resolve().parent
    modules = source if source.name == "local" else target
    if not (modules / "reserve.py").is_file() or not (target / "controller/ledger.py").is_file():
        raise ValueError("Incomplete installed execution-interlock runtime")
    sys.path.insert(0, str(modules))
    try:
        import reserve
        from controller.ledger import Ledger
        from controller import ledger as ledger_module
        from controller.errors import PortError
    except ImportError as error:
        raise ValueError("Incomplete installed execution-interlock imports; reviewed installation required") from error

    if (getattr(ledger_module, "EXECUTION_INTERLOCK_VERSION", None) != 2
            or Path(reserve.__file__).resolve() != modules / "reserve.py"):
        raise ValueError("Unavailable exact execution-interlock runtime; reviewed installation required")
    try:
        manifest = reserve.verify_runtime(target)
    except PortError as error:
        raise ValueError(str(error)) from error
    # A self-consistent old manifest is not evidence of current two-sided arbitration.
    sources = reserve.runtime_sources()
    for name, path in sources.items():
        expected = hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
        if manifest["files"].get(name) != expected:
            raise ValueError(f"Installed execution runtime is old or mismatched: {name}")
    if (Path(ledger_module.__file__).read_bytes().replace(b"\r\n", b"\n")
            != (target / "controller/ledger.py").read_bytes()):
        raise ValueError("Loaded Ledger differs from the installed execution interlock")
    for name, _ in runtime_files():
        if ((source / name).read_bytes().replace(b"\r\n", b"\n")
                != (target / name).read_bytes()):
            raise ValueError(f"Local launcher differs from installed execution runtime: {name}")
    try:
        policy = reserve.load_json(repo / "docs/android/automation-policy.json")
        repository = reserve.repository_identity(manifest, "Installed runtime")
        if reserve.repository_identity(policy, "Candidate policy") != repository:
            raise ValueError("Candidate repository differs from trusted installed runtime identity")
        return Ledger(reserve.shared_ledger(repo), repository)
    except PortError as error:
        raise ValueError(str(error)) from error


def cleaned_execution(path, token):
    if not path.is_file() or path.is_symlink():
        return False
    try:
        proof = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    return (
        isinstance(proof, dict) and set(proof) == {"schema_version", "token", "status", "returncode"}
        and type(proof["schema_version"]) is int and proof["schema_version"] == 1 and proof["token"] == token
        and proof["status"] == "owned-children-reaped" and type(proof["returncode"]) is int
    )


def affected_request(repo, commit, base, scopes):
    from controller.errors import PortError
    from controller.local_scope import candidate_plan

    try:
        return candidate_plan(repo, base, commit, scopes)
    except PortError as error:
        raise ValueError(str(error)) from error


def parallel_ledger(repo):
    configured = os.environ.get("ANDROID_PORT_LEDGER")
    if not configured:
        raise ValueError("Parallel JVM execution requires the explicit existing canonical shared ledger")
    path = Path(configured)
    if not path.is_absolute() or not path.is_file():
        raise ValueError("Parallel JVM execution requires an existing absolute shared ledger")
    path = path.resolve()
    common = Path(capture([
        "git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-common-dir",
    ])).resolve()
    if path.is_relative_to(repo.resolve()) or path.is_relative_to(common):
        raise ValueError("A private checkout/Git-common ledger cannot supply parallel JVM authority")
    return path


def run_owned(repo, revision, distribution, stages, worktree, *, advisory=False, adapter=None,
              affected_base=None, scopes=None, parallel_jvm=False):
    if worktree:
        raise ValueError("Working-tree overlays are not supported by the installed merge verifier")
    if (
        not isinstance(stages, list) or not stages or not all(isinstance(stage, str) for stage in stages)
        or len(stages) != len(set(stages)) or set(stages) - (set(ALL_STAGES) | OPTIONAL_STAGES)
        or adapter is not None and (advisory or stages != ALL_STAGES)
        or scopes is not None and affected_base is None
        or affected_base is not None and (advisory or adapter is not None or stages != ALL_STAGES)
        or parallel_jvm and (affected_base is None or not sys.platform.startswith("linux"))
    ):
        raise ValueError("Invalid exact committed execution request")
    commit, tree = binding(repo, revision)
    shared = parallel_ledger(repo) if parallel_jvm else None
    # Never invoke transport, bundle construction or an adapter before atomic admission.
    ledger = execution_runtime(repo)
    if shared is not None and ledger.path.resolve() != shared:
        raise ValueError("Parallel JVM runtime differs from the canonical shared ledger")
    from controller.errors import PortError
    token = uuid.uuid4().hex
    owner = f"local-check:{token}"
    directory = Path(tempfile.mkdtemp(prefix="meshcore-execution-"))
    completion = directory / "cleanup.json"
    admission = directory / "admission.json"
    payload = {"commit": commit, "tree": tree, "pid": os.getpid(), "token": token,
               "completion": str(completion), "mode": "advisory" if advisory else "manual"}
    if affected_base is not None:
        payload["affected_base"] = affected_base
    resource = "gradle-execution"
    try:
        if parallel_jvm:
            lock = ledger.acquire_jvm_lane(owner, payload, time.time())
            resource, payload = lock["resource"], lock["payload"]
        else:
            ledger.acquire_hard_lock(resource, owner, payload, time.time())
    except (PortError, sqlite3.Error) as error:
        directory.rmdir()
        raise ValueError(str(error)) from error
    execution = {"started": False}
    try:
        # Revalidate under ownership: installation and execution cannot cross here.
        current = execution_runtime(repo)
        if parallel_jvm and (
            parallel_ledger(repo) != shared or current.path.resolve() != shared
            or current.repository != ledger.repository
        ):
            raise ValueError("Canonical execution authority changed under JVM ownership")
        plan = affected_request(repo, commit, affected_base, scopes) if affected_base is not None else None
        if adapter and not sys.platform.startswith("linux"):
            raise ValueError("Full/manual adapter cleanup requires the supported Linux pidfd supervisor")
        admission.write_text(json.dumps({
            "schema_version": 1, "interlock_version": 2, "owner": owner, "token": token,
            "commit": commit, "tree": tree,
            **({"jvm_lane": payload["jvm_lane"]} if parallel_jvm else {}),
        }) + "\n", encoding="utf-8")
        if adapter:
            result = run_adapter(repo, commit, adapter, completion=completion, token=token,
                                 admission=admission, execution=execution)
        else:
            prefix = transport(distribution, execution=execution)
            result = run_candidate(repo, commit, prefix, stages, worktree, advisory=advisory,
                                   completion=completion, token=token, admission=admission,
                                   execution=execution, **({"affected_plan": plan} if plan is not None else {}))
        if not cleaned_execution(completion, token):
            raise ValueError("Execution returned without proven owned-child cleanup; ownership retained")
        if plan is not None:
            from controller.local_scope import default_base
            try:
                current_base = default_base(repo)
            except PortError as error:
                raise ValueError(str(error)) from error
            if current_base != plan["base"]:
                raise ValueError("Stale affected base after execution; no current-candidate success")
    finally:
        if not execution["started"] or cleaned_execution(completion, token):
            try:
                ledger.release_hard_lock(resource, owner)
            except PortError as error:
                raise ValueError("Own execution release failed; retain recovery inputs") from error
            shutil.rmtree(directory)
        else:
            print(f"BLOCKED: execution cleanup unproven; retained {owner}, recovery input {directory}",
                  file=sys.stderr)
    if plan is not None:
        print(f"AFFECTED LOCAL CHECK PASSED: base {plan['base']}, head {commit}, tree {tree}. "
              "Not a full-result receipt or hosted/native/human acceptance.", flush=True)
    return result


def runtime_files():
    return (
        ("check.py", "check.py"), ("fast.py", "fast.py"),
        ("run.sh", "run.sh"),
        ("advisory.py", "advisory.py"),
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


def transport(distribution, *, execution=None):
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
    if execution is None:
        raise ValueError("Transport requires actual shared execution admission")
    execution["started"] = True
    subprocess.run([*prefix, "bash", "-c", "command -v git && command -v flock"], check=True)
    return prefix


def linux_path(path, prefix):
    if not prefix:
        return str(path)
    return capture([*prefix, "wslpath", "-u", str(path).replace("\\", "/")])


def run_candidate(repo, revision, prefix, stages, worktree, *, advisory=False, completion=None,
                  token=None, admission=None, execution=None, affected_plan=None):
    if worktree:
        raise ValueError("Working-tree overlays are not supported by the installed merge verifier")
    if completion is None or token is None or admission is None or execution is None:
        raise ValueError("Local execution requires actual shared ownership and cleanup binding")
    revision, tree = binding(repo, revision)
    if affected_plan is not None and (
        advisory or affected_plan["head"] != revision or affected_plan["tree"] != tree
    ):
        raise ValueError("Affected request differs from exact owned candidate")
    key = hashlib.sha256(str(repo).encode("utf-8")).hexdigest()[:16]
    tool = Path(__file__).resolve().parent
    control = completion.parent / "control"
    control.mkdir()
    for source_name, installed_name in runtime_files():
        source = tool / source_name
        if source.is_file():
            (control / installed_name).write_bytes(source.read_bytes().replace(b"\r\n", b"\n"))
    from controller import jvm_lanes
    (control / "jvm_lanes.py").write_bytes(
        Path(jvm_lanes.__file__).read_bytes().replace(b"\r\n", b"\n"))
    bundle = completion.parent / "candidate.bundle"
    request = None
    if affected_plan is not None:
        request = completion.parent / "affected-plan.json"
        request.write_text(json.dumps(affected_plan) + "\n", encoding="utf-8")
        stages = affected_plan["stages"]
    execution["started"] = True
    subprocess.run(["git", "-C", str(repo), "bundle", "create", str(bundle), *bundle_revisions(revision)], check=True)
    command = [
        *prefix, "bash", linux_path(control / "run.sh", prefix),
        linux_path(bundle, prefix), revision, key, linux_path(control, prefix),
        ",".join(stages), "", "advisory" if advisory else "manual",
        str(ADVISORY_QUEUE_SECONDS), str(ADVISORY_FAST_SECONDS),
        linux_path(completion, prefix), token, linux_path(admission, prefix),
        *([linux_path(request, prefix), affected_plan["base"]] if request is not None else []),
    ]
    result = subprocess.run(command)
    if not cleaned_execution(completion, token):
        raise ValueError("Local runner exited without proven owned-child cleanup; ownership retained")
    if advisory and result.returncode in (75, 124):
        reason = (f"shared local Gradle lane queued beyond {ADVISORY_QUEUE_SECONDS}s"
                  if result.returncode == 75 else
                  f"advisory fast check exceeded {ADVISORY_FAST_SECONDS}s; owned work cleaned")
        raise ValueError(reason + "; exact full/manual merge verification still required")
    result.check_returncode()


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


def run_adapter(repo, revision, state_path, *, completion=None, token=None, admission=None, execution=None):
    state = json.loads(Path(state_path).read_text(encoding="utf-8"))
    if set(state) != {"schema_version", "adapter"} or state["schema_version"] != 1:
        raise ValueError("Malformed contributor toolchain adapter state")
    adapter = state["adapter"]
    if not isinstance(adapter, list) or not adapter or not all(isinstance(item, str) and item for item in adapter):
        raise ValueError("Toolchain adapter command must be a non-empty argument list")
    commit, tree = binding(repo, revision)
    if completion is None or token is None or admission is None or execution is None:
        raise ValueError("Adapter execution requires actual shared ownership and cleanup binding")
    result = completion.parent / "adapter-result.json"
    execution["started"] = True
    subprocess.run([
        sys.executable, str(Path(__file__).with_name("advisory.py")),
        "--completion", str(completion), "--token", token, "--preserve-daemons",
        "--admission", str(admission), "--commit", commit, "--",
        *adapter, "--repo", str(repo), "--commit", commit, "--tree", tree,
        "--stages", ",".join(ALL_STAGES), "--output", str(result),
    ], check=True)
    return validate_result(result, commit, tree)


def install(repo, prefix, distribution):
    ledger = execution_runtime(repo)
    from controller.errors import PortError

    try:
        with ledger.hard_lock("installed-git-common-runtime", f"hook-install:{uuid.uuid4().hex}",
                              {"hook_source": str(Path(__file__).resolve())}, time.time()):
            install_hook_files(repo, distribution)
    except PortError as error:
        raise ValueError(str(error)) from error


def install_hook_files(repo, distribution):
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
    parser.add_argument("--affected-base", help="Immutable current trusted default-base SHA; explicit local-only scope")
    parser.add_argument("--scope", action="append", help="Explicit CI/module scope superset; cannot omit actual changes")
    parser.add_argument("--parallel-jvm", action="store_true",
                        help="Use a measured real-host JVM slot with explicit affected verification (Linux only)")
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
        if args.scope is not None and args.affected_base is None:
            raise ValueError("--scope requires an explicit --affected-base")
        if args.parallel_jvm and args.affected_base is None:
            raise ValueError("--parallel-jvm requires the exact --affected-base contract")
        if args.affected_base is not None and (
            not SHA.fullmatch(args.affected_base) or args.pre_push or args.install_hook
            or args.validate_result or args.toolchain_state or args.worktree or args.stages != "all"
        ):
            raise ValueError("Affected verification requires an immutable base and no prepush/adapter/stage/result override")
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
            install(repo, [], args.distribution)
            return 0
        for commit in commits:
            try:
                if args.toolchain_state:
                    if stages != ALL_STAGES or args.worktree:
                        raise ValueError("Adapters only produce exact committed declared-stage results")
                    if args.pre_push:
                        raise ValueError(
                            "Bounded advisory cleanup is unavailable for contributor adapters; "
                            "run the exact full/manual adapter verification before merge"
                        )
                    run_owned(repo, commit, args.distribution, stages, args.worktree,
                              adapter=args.toolchain_state)
                else:
                    run_owned(repo, commit, args.distribution, stages, args.worktree,
                              advisory=args.pre_push,
                              **({"affected_base": args.affected_base, "scopes": args.scope}
                                 if args.affected_base is not None else {}),
                              **({"parallel_jvm": True} if args.parallel_jvm else {}))
            except (OSError, ValueError, KeyError, sqlite3.Error, subprocess.SubprocessError) as error:
                if not args.pre_push:
                    raise
                record_pending(repo, commit, error)
        return 0
    except (OSError, ValueError, KeyError, sqlite3.Error, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
