"""AndroidOnly: WP-003 Real temporary Git publication through an immutable complete guard."""

import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from fixtures import REPO
from controller import source_push
from controller.ledger import Ledger


class SourcePushTests(unittest.TestCase):
    def test_real_guard_publishes_pending_without_using_candidate_or_legacy_launcher(self):
        with tempfile.TemporaryDirectory(prefix="trusted-push-fixture-") as directory:
            root = Path(directory)
            repo, remote = root / "repo", root / "remote.git"
            def git(*args):
                return subprocess.check_output(["git", "-C", str(repo), *args], encoding="utf-8").strip()
            subprocess.run(["git", "clone", "--quiet", "--shared", "--no-checkout", str(REPO), str(repo)], check=True)
            git("checkout", "--quiet", "-b", "fixture", "a5dd0fc6c521d09d59d28ca75169e4bdbd56a6cd")
            git("config", "user.name", "Fixture")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "commit.gpgsign", "false")
            tool = repo / "tools/android-port/controller/source_push.py"
            tool.write_bytes(Path(source_push.__file__).read_bytes())
            git("add", str(tool))
            git("commit", "--quiet", "-m", "trusted fixture entrypoint")
            base = git("rev-parse", "HEAD")
            git("update-ref", "refs/remotes/origin/main", base)
            git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
            subprocess.run(["git", "init", "--quiet", "--bare", str(remote)], check=True)
            git("remote", "add", "fixture-target", str(remote))
            git("push", "--quiet", "fixture-target", base + ":refs/heads/main")
            subprocess.run(["git", "--git-dir=" + str(remote), "symbolic-ref", "HEAD", "refs/heads/main"], check=True)
            git("remote", "set-url", "origin", str(remote))
            candidate = repo / "tools/android-port/local/check.py"
            candidate.write_text("raise RuntimeError('CANDIDATE GUARD MUST NOT EXECUTE')\n", encoding="utf-8")
            git("add", str(candidate))
            git("commit", "--quiet", "-m", "untrusted candidate launcher")
            head = git("rev-parse", "HEAD")
            common = Path(git("rev-parse", "--path-format=absolute", "--git-common-dir"))
            legacy = common / "hooks/pre-push"
            legacy.write_text("#!/bin/sh\nexit 93\n", encoding="utf-8", newline="\n")
            legacy.chmod(0o700)
            runtime = common / "meshcore-reservations"
            (runtime / "controller").mkdir(parents=True)
            (runtime / "controller/ledger.py").write_bytes(b"old retained runtime")
            (runtime / "runtime-manifest.json").write_text(json.dumps({
                "schema_version": 1, "files": {
                    "controller/ledger.py": hashlib.sha256(b"old retained runtime").hexdigest(),
                },
            }), encoding="utf-8")
            ledger = Ledger(common / "android-port-shared.sqlite3", "cbattlegear/MeshCoreOne-Android")
            ledger.acquire_hard_lock("native-execution", "retained-fixture", {"uncertain": True}, 1.0)
            before = ledger.hard_lock_records()
            files = {p: p.read_bytes() for p in runtime.rglob("*") if p.is_file()}
            hook_bytes = legacy.read_bytes()
            env = {key: value for key, value in os.environ.items()
                   if not key.startswith("GIT_") and key != "ANDROID_PORT_LEDGER"}
            result = subprocess.run([
                sys.executable, "-I", str(tool), "--repo", str(repo), "--trusted-base", base,
                "--head", head, "--remote", "fixture-target", "--remote-url", str(remote),
            ], env=env, capture_output=True, encoding="utf-8", errors="replace", timeout=60)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("UNVERIFIED CANDIDATE", result.stderr)
            self.assertIn("Malformed installed reservation runtime manifest", result.stderr)
            self.assertEqual(subprocess.check_output([
                "git", "--git-dir=" + str(remote), "rev-parse", "refs/heads/fixture",
            ], encoding="utf-8").strip(), head)
            pending = json.loads((common / "meshcore-local/verification-pending.json").read_text())
            self.assertEqual((pending["commit"], pending["tree"]), (head, git("rev-parse", "HEAD^{tree}")))
            self.assertEqual(ledger.hard_lock_records(), before)
            self.assertEqual({p: p.read_bytes() for p in runtime.rglob("*") if p.is_file()}, files)
            self.assertEqual(legacy.read_bytes(), hook_bytes)
            staging = root / "forged-stage"
            staging.mkdir()
            immutable_hashes = source_push.stage(repo, base, staging)
            with patch.dict(os.environ, env, clear=True):
                original_request = source_push.binding(repo, base, head, "fixture-target", str(remote))
            request = staging / "request.json"
            staged_checker = staging / "tools/android-port/local/check.py"
            marker = root / "PAYLOAD_EXECUTED"
            payload = ("from pathlib import Path\nPath(" + repr(str(marker)) + ").touch()\n").encode()
            hook_command = [
                sys.executable, "-I", str(staging / "tools/android-port/controller/source_push.py"),
                "--hook", str(repo), str(staging), str(request), "fixture-target", str(remote),
            ]
            for mode in ("empty", "recomputed", "omitted", "extra", "traversal", "extra-staged"):
                manifest = dict(immutable_hashes)
                staged_checker.write_bytes(payload if mode in ("empty", "recomputed") else
                                          source_push.blob(repo, base, "tools/android-port/local/check.py").replace(b"\r\n", b"\n"))
                if mode == "empty":
                    manifest = {}
                elif mode == "recomputed":
                    manifest["tools/android-port/local/check.py"] = hashlib.sha256(payload).hexdigest()
                elif mode == "omitted":
                    manifest.pop("tools/android-port/local/check.py")
                elif mode in ("extra", "traversal"):
                    manifest["../payload.py" if mode == "traversal" else "tools/payload.py"] = hashlib.sha256(payload).hexdigest()
                extra = staging / "tools/extra.py"
                if mode == "extra-staged":
                    extra.write_bytes(payload)
                request.write_text(json.dumps({**original_request, "files": manifest}), encoding="utf-8")
                refusal = subprocess.run(hook_command, env=env, capture_output=True, encoding="utf-8",
                                         errors="replace", timeout=60,
                                         input=f"{head} {head} refs/heads/fixture {'0'*40}\n")
                with self.subTest(forged=mode):
                    self.assertNotEqual(refusal.returncode, 0, refusal.stdout + refusal.stderr)
                    self.assertIn("complete immutable base", refusal.stderr)
                    self.assertFalse(marker.exists(), "Forged checker payload executed")
                if extra.exists():
                    extra.unlink()
            subprocess.run(["git", "--git-dir=" + str(remote), "update-ref", "refs/heads/main", head], check=True)
            self.assertEqual(git("rev-parse", "refs/remotes/origin/main"), base)
            advanced = subprocess.run([
                sys.executable, "-I", str(tool), "--repo", str(repo), "--trusted-base", base,
                "--head", head, "--remote", "fixture-target", "--remote-url", str(remote),
            ], env=env, capture_output=True, encoding="utf-8", errors="replace", timeout=60)
            self.assertNotEqual(advanced.returncode, 0)
            self.assertIn("Live trusted default-base changed", advanced.stderr)
            self.assertEqual(ledger.hard_lock_records(), before)
            self.assertEqual({p: p.read_bytes() for p in runtime.rglob("*") if p.is_file()}, files)
            self.assertEqual(legacy.read_bytes(), hook_bytes)

    def test_invalid_binding_and_environment_fail_before_git_or_launch(self):
        for key in ("ANDROID_PORT_LEDGER", "GIT_DIR", "GIT_CONFIG_COUNT"):
            with self.subTest(key=key), patch.dict(os.environ, {key: "untrusted"}), \
                    patch.object(source_push, "git") as git:
                with self.assertRaisesRegex(ValueError, "overrides"):
                    source_push.binding(REPO, "a" * 40, "b" * 40, "origin", "remote")
                git.assert_not_called()
        for remote in ("--force", "origin extra", ""):
            with self.subTest(remote=remote), self.assertRaises(ValueError):
                source_push.binding(REPO, "a" * 40, "b" * 40, remote, "remote")

    def test_candidate_entrypoint_cannot_authorize_itself(self):
        with patch.object(source_push, "binding", return_value={}), \
                patch.object(source_push, "blob", return_value=b"unreviewed different code"), \
                patch.object(source_push, "stage") as stage:
            with self.assertRaisesRegex(ValueError, "immutable default-base"):
                source_push.publish(REPO, "a" * 40, "b" * 40, "origin", "remote")
            stage.assert_not_called()

    def test_hook_stale_binding_wrong_remote_ref_and_staged_tamper_never_launch_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            request = root / "request.json"
            expected = {
                "base": "a" * 40, "head": "b" * 40, "branch": "feature",
                "remote": "origin", "url": "remote", "tree": "c" * 40, "common": "canonical",
            }
            path = "tools/android-port/local/check.py"
            guard = root / path
            guard.parent.mkdir(parents=True)
            guard.write_bytes(b"guard bytes")
            request.write_text(json.dumps({
                **expected, "files": {path: hashlib.sha256(guard.read_bytes()).hexdigest()},
            }))
            for mode in ("binding", "remote", "ref", "extra", "tamper"):
                actual = {**expected, "head": "d" * 40} if mode == "binding" else expected
                argv = ["tool", "origin", "wrong" if mode == "remote" else "remote"]
                row = f"{expected['head']} {expected['head']} refs/heads/{'wrong' if mode == 'ref' else 'feature'} {'0'*40}\n"
                if mode == "extra":
                    row += row
                guard.write_bytes(b"tampered" if mode == "tamper" else b"guard bytes")
                with self.subTest(mode=mode), patch.object(source_push, "binding", return_value=actual), \
                        patch.object(source_push, "trusted_files", return_value={path: b"guard bytes"}), \
                        patch.object(sys, "argv", argv), patch("sys.stdin", io.StringIO(row)), \
                        patch.object(source_push.subprocess, "run") as launch:
                    with self.assertRaises(ValueError):
                        source_push.hook(REPO, root, request)
                    launch.assert_not_called()

    def test_hook_rejects_foreign_inherited_config_without_reading_request(self):
        with patch.dict(os.environ, {"GIT_CONFIG_PARAMETERS": "'core.hooksPath'='/foreign'"}), \
                patch.object(Path, "read_text") as read:
            with self.assertRaisesRegex(ValueError, "unexpected Git configuration"):
                source_push.hook(REPO, Path("/isolated"), Path("/request"))
            read.assert_not_called()

    def test_stale_default_head_remote_and_paused_policy_fail_before_push(self):
        policy = {"paused": False, "repository": "cbattlegear/MeshCoreOne-Android", "default_branch": "main"}
        for mode in ("base", "head", "remote", "default", "paused"):
            def git(repo, *args):
                if args == ("symbolic-ref", "refs/remotes/origin/HEAD"):
                    return "refs/remotes/origin/main"
                if args == ("rev-parse", "refs/remotes/origin/main"):
                    return "d" * 40 if mode == "base" else "a" * 40
                if args == ("ls-remote", "--symref", "--exit-code", "origin", "HEAD", "refs/heads/main"):
                    return "ref: refs/heads/main\tHEAD\n" + "a" * 40 + "\tHEAD\n" + "a" * 40 + "\trefs/heads/main"
                if args == ("rev-parse", "HEAD"):
                    return "d" * 40 if mode == "head" else "b" * 40
                if args == ("symbolic-ref", "--short", "HEAD"):
                    return "main" if mode == "default" else "feature"
                if args == ("remote", "get-url", "--push", "--all", "origin"):
                    return "wrong" if mode == "remote" else "remote"
                self.fail("Unexpected call after failed guard: " + str(args))
            with self.subTest(mode=mode), patch.object(source_push, "git", side_effect=git), \
                    patch.object(source_push, "blob", return_value=json.dumps({
                        **policy, "paused": mode == "paused",
                    }).encode()):
                with self.assertRaises(ValueError):
                    source_push.binding(REPO, "a" * 40, "b" * 40, "origin", "remote")
