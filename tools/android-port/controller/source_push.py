"""AndroidOnly: WP-003 Invocation-local immutable trusted pre-push guard; never a gate publisher."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], encoding="utf-8", timeout=30).strip()


def blob(repo, revision, path):
    name = revision + ":" + path
    size = git(repo, "cat-file", "-s", name)
    if not size.isdecimal() or int(size) > 4 * 1024 * 1024:
        raise ValueError("Trusted source file exceeds staging bound")
    return subprocess.check_output(["git", "-C", str(repo), "show", name], timeout=30)


def binding(repo, base, head, remote, url):
    if any(re.fullmatch(r"[0-9a-f]{40}", value) is None for value in (base, head)):
        raise ValueError("Publication requires immutable trusted base and candidate")
    if re.fullmatch(r"[A-Za-z0-9_.-]+", remote) is None or remote.startswith("-"):
        raise ValueError("Publication requires a configured remote name")
    if any(key.startswith("GIT_CONFIG") or key in {
        "GIT_DIR", "GIT_COMMON_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "ANDROID_PORT_LEDGER",
    } for key in os.environ):
        raise ValueError("Publication refuses Git configuration/location or alternate-ledger overrides")
    default = git(repo, "symbolic-ref", "refs/remotes/origin/HEAD")
    if default != "refs/remotes/origin/main" or git(repo, "rev-parse", default) != base:
        raise ValueError("Trusted default-base changed")
    live = git(repo, "ls-remote", "--symref", "--exit-code", "origin", "HEAD", "refs/heads/main").splitlines()
    if sorted(live) != sorted([
        "ref: refs/heads/main\tHEAD", base + "\tHEAD", base + "\trefs/heads/main",
    ]):
        raise ValueError("Live trusted default-base changed or is ambiguous")
    policy = json.loads(blob(repo, base, "docs/android/automation-policy.json"))
    if policy["paused"] or policy["repository"] != "cbattlegear/MeshCoreOne-Android":
        raise ValueError("Publication paused or repository identity differs")
    if git(repo, "rev-parse", "HEAD") != head:
        raise ValueError("Candidate differs from actual HEAD")
    branch = git(repo, "symbolic-ref", "--short", "HEAD")
    if branch == policy["default_branch"]:
        raise ValueError("Feature publication cannot target the default branch")
    if git(repo, "remote", "get-url", "--push", "--all", remote).splitlines() != [url]:
        raise ValueError("Push remote URL changed or is ambiguous")
    return {
        "base": base, "head": head, "tree": git(repo, "rev-parse", head + "^{tree}"),
        "branch": branch, "remote": remote, "url": url,
        "common": git(repo, "rev-parse", "--path-format=absolute", "--git-common-dir"),
    }


def trusted_files(repo, base):
    paths = git(repo, "ls-tree", "-r", "--name-only", base, "--", "tools/android-port").splitlines()
    if not paths or len(paths) > 2000:
        raise ValueError("Missing or oversized trusted guard tree")
    files, size = {}, 0
    for path in paths:
        parts = Path(path).parts
        if Path(path).is_absolute() or ".." in parts or "\\" in path:
            raise ValueError("Unsafe trusted source path")
        data = blob(repo, base, path)
        size += len(data)
        if size > 32 * 1024 * 1024:
            raise ValueError("Trusted guard tree exceeds staging bound")
        files[path] = data.replace(b"\r\n", b"\n")
    return files


def stage(repo, base, root):
    files = trusted_files(repo, base)
    for path, data in files.items():
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    return {path: hashlib.sha256(data).hexdigest() for path, data in files.items()}


def hook(repo, root, request):
    inherited = os.environ.get("GIT_CONFIG_PARAMETERS")
    if inherited is not None:
        if shlex.split(inherited) != ["core.hooksPath=" + str(root / "hooks")]:
            raise ValueError("Hook inherited unexpected Git configuration")
        del os.environ["GIT_CONFIG_PARAMETERS"]
    expected = json.loads(request.read_text(encoding="utf-8"))
    if not isinstance(expected, dict) or set(expected) != {
        "base", "head", "tree", "branch", "remote", "url", "common", "files",
    }:
        raise ValueError("Malformed publication request")
    actual = binding(repo, expected["base"], expected["head"], expected["remote"], expected["url"])
    if any(actual[key] != expected[key] for key in actual):
        raise ValueError("Publication binding changed before hook")
    immutable = trusted_files(repo, actual["base"])
    hashes = {path: hashlib.sha256(data).hexdigest() for path, data in immutable.items()}
    if expected["files"] != hashes:
        raise ValueError("Requested guard manifest differs from complete immutable base")
    staged = root / "tools"
    actual_paths = set()
    if staged.is_symlink():
        raise ValueError("Unsafe staged guard tree")
    for target in staged.rglob("*"):
        if target.is_symlink():
            raise ValueError("Unsafe staged guard tree")
        if target.is_file():
            actual_paths.add(target.relative_to(root).as_posix())
        elif not target.is_dir():
            raise ValueError("Nonregular staged guard tree")
    if actual_paths != set(hashes):
        raise ValueError("Staged guard file set differs from complete immutable base")
    for path, digest in hashes.items():
        target = root / path
        if target.is_symlink() or hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise ValueError("Staged trusted guard changed")
    if sys.argv[-2:] != [expected["remote"], expected["url"]]:
        raise ValueError("Hook remote differs from publication binding")
    incoming = sys.stdin.read()
    rows = incoming.splitlines()
    if len(rows) != 1:
        raise ValueError("Guard requires exactly one actual feature update")
    fields = rows[0].split()
    if len(fields) != 4 or fields[1] != expected["head"] or fields[2] != "refs/heads/" + expected["branch"]:
        raise ValueError("Hook update differs from exact candidate/target")
    guard = root / "tools/android-port/local/check.py"
    result = subprocess.run(
        [sys.executable, "-I", str(guard), "--pre-push", expected["remote"], expected["url"]],
        cwd=repo, input=incoming, encoding="utf-8", timeout=240,
    )
    if result.returncode:
        raise ValueError("Trusted pre-push guard failed")
    (root / "guard-executed").write_text(expected["head"], encoding="ascii")


def publish(repo, base, head, remote, url):
    expected = binding(repo, base, head, remote, url)
    relative = "tools/android-port/controller/source_push.py"
    if Path(__file__).read_bytes().replace(b"\r\n", b"\n") != blob(repo, base, relative).replace(b"\r\n", b"\n"):
        raise ValueError("Publication entrypoint must itself be reviewed immutable default-base code")
    with tempfile.TemporaryDirectory(prefix="meshcore-trusted-push-") as directory:
        root = Path(directory)
        expected["files"] = stage(repo, base, root)
        request = root / "request.json"
        request.write_text(json.dumps(expected), encoding="utf-8")
        hooks = root / "hooks"
        hooks.mkdir()
        command = [
            sys.executable.replace("\\", "/"), "-I",
            str(root / relative).replace("\\", "/"), "--hook",
            str(repo).replace("\\", "/"), str(root).replace("\\", "/"),
            str(request).replace("\\", "/"),
        ]
        script = hooks / "pre-push"
        script.write_text("#!/bin/sh\nexec " + shlex.join(command) + ' "$@"\n', encoding="utf-8", newline="\n")
        script.chmod(0o700)
        if binding(repo, base, head, remote, url) != {k: v for k, v in expected.items() if k != "files"}:
            raise ValueError("Publication binding changed before push")
        subprocess.run([
            "git", "-C", str(repo), "-c", "core.hooksPath=" + str(hooks),
            "push", "--no-follow-tags", "--recurse-submodules=no", remote,
            head + ":refs/heads/" + expected["branch"],
        ], check=True, timeout=300)
        if not (root / "guard-executed").is_file():
            raise ValueError("No executed trusted guard observed; no verification claim")
        print("Feature publication used the complete immutable pre-push guard; not merge acceptance.")


def main():
    try:
        if not sys.flags.isolated:
            raise ValueError("Trusted publication requires python -I isolated imports")
        if len(sys.argv) > 1 and sys.argv[1] == "--hook":
            if len(sys.argv) != 7:
                raise ValueError("Malformed internal hook invocation")
            hook(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]))
            return
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("--repo", type=Path, required=True)
        parser.add_argument("--trusted-base", required=True)
        parser.add_argument("--head", required=True)
        parser.add_argument("--remote", required=True)
        parser.add_argument("--remote-url", required=True)
        args = parser.parse_args()
        publish(args.repo.resolve(), args.trusted_base, args.head, args.remote, args.remote_url)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
