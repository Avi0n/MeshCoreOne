import base64
import copy
import io
import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import Mock

from fixtures import (
    BASE, HEAD, MERGE, NOW, REPO, FakeApi, bundle, policy, settings, test_manifest,
)
from controller.authority import GATE_MARKER, GitHubGateAuthority
from controller.backends import (
    CloudBackend, GitHubApi, LocalHostBackend, MergeBackend, NativeHost, Observation,
)
from controller.errors import ApiError, PortError
from controller.ledger import Identity
from controller.render import cloud_payload, issue_payload, local_payload, marker, render
from controller.settings import Budget, Usage


def cloud_fixture():
    manifest = test_manifest("WP-101")
    rules = policy("cloud")
    value = settings(Path("unused.sqlite"), "cloud")
    prefix = f"/repos/{rules['repository']}"
    tasks = f"/agents/repos/{rules['repository']}"
    owner = manifest.wp("WP-101")["owner"]
    content = (REPO / ".github" / "agents" / f"{owner}.agent.md").read_bytes()
    issue = {"number": 7, "title": "[WP-101] fixture", "body": marker("WP-101"), "state": "open", "assignees": []}
    routes = {
        ("GET", "/user"): {"login": value.user_login, "type": "User"},
        ("GET", prefix): {"full_name": rules["repository"], "default_branch": "main", "permissions": {"push": True}},
        ("GET", prefix + "/git/ref/heads/main"): {"object": {"sha": BASE}},
        ("POST", "/graphql"): {"data": {"repository": {"suggestedActors": {"nodes": [{"login": "copilot-swe-agent"}]}}}},
        ("GET", prefix + f"/contents/.github/agents/{owner}.agent.md?ref={BASE}"): {
            "name": f"{owner}.agent.md", "encoding": "base64", "content": base64.b64encode(content).decode(),
        },
        ("GET", prefix + "/issues?state=all&per_page=100&page=1"): [issue],
        ("GET", prefix + "/pulls?state=all&per_page=100&page=1"): [],
        ("GET", tasks + "/tasks?per_page=100&page=1"): {"tasks": []},
        ("GET", tasks + "/tasks?is_archived=true&per_page=100&page=1"): {"tasks": []},
        ("POST", prefix + "/issues/7/assignees"): {
            **issue, "assignees": [{"login": "copilot-swe-agent[bot]"}],
        },
    }
    api = FakeApi(routes)
    return CloudBackend(api, manifest, rules, value), api, prefix, tasks


class BackendTests(unittest.TestCase):
    def test_cloud_auth_profile_and_availability_preflight_is_read_only(self):
        cloud, api, _, _ = cloud_fixture()
        cloud.preflight("WP-101", BASE)
        self.assertTrue(any(call[1] == "/graphql" and call[3] for call in api.calls))
        self.assertFalse(any(call[0] in ("PATCH", "PUT") or call[1].endswith("/assignees") for call in api.calls))

    def test_cloud_unavailable_http404_profile_drift_and_installation_auth_fail_without_assignment(self):
        for kind in ("404", "actor", "user", "installation", "profile", "base"):
            cloud, api, prefix, _ = cloud_fixture()
            if kind == "404":
                api.routes[("POST", "/graphql")] = ApiError("POST", "/graphql", 404)
            elif kind == "actor":
                api.routes[("POST", "/graphql")] = {"data": {"repository": {"suggestedActors": {"nodes": []}}}}
            elif kind == "user":
                api.routes[("GET", "/user")] = {"type": "Bot", "login": "fixture-user"}
            elif kind == "installation":
                cloud.settings = replace(cloud.settings, auth_kind="installation")
            elif kind == "profile":
                owner = cloud.manifest.wp("WP-101")["owner"]
                api.routes[("GET", prefix + f"/contents/.github/agents/{owner}.agent.md?ref={BASE}")]["content"] = base64.b64encode(b"wrong profile").decode()
            else:
                api.routes[("GET", prefix + "/git/ref/heads/main")] = {"object": {"sha": HEAD}}
            with self.subTest(kind=kind), self.assertRaises(PortError):
                cloud.preflight("WP-101", BASE)
            self.assertFalse(any(call[1].endswith("/assignees") for call in api.calls))

    def test_cloud_assignment_uses_documented_issues_rest_payload_no_model_override(self):
        cloud, api, prefix, _ = cloud_fixture()
        receipt = cloud.launch("WP-101", "attempt-1")
        mutation = next(c for c in api.calls if c[0] == "POST")
        self.assertEqual(mutation[1], prefix + "/issues/7/assignees")
        self.assertEqual(mutation[2]["assignees"], ["copilot-swe-agent[bot]"])
        assignment = mutation[2]["agent_assignment"]
        self.assertEqual(assignment["custom_agent"], "protocol-porter")
        self.assertEqual(assignment["target_repo"], "cbattlegear/MeshCoreOne-Android")
        self.assertEqual(assignment["base_branch"], "main")
        self.assertNotIn("model", assignment)
        self.assertIn("attempt-1", assignment["custom_instructions"])
        self.assertEqual(receipt.worker_state, "assignment_accepted")
        self.assertIsNone(receipt.identity.task_id)
        self.assertFalse(any(c[0] == "POST" and "/agents/" in c[1] for c in api.calls))

    def test_closed_assigned_missing_or_duplicate_issue_blocks_launch(self):
        for kind in ("closed", "assigned", "missing", "duplicate", "unmarked"):
            cloud, api, prefix, _ = cloud_fixture()
            path = ("GET", prefix + "/issues?state=all&per_page=100&page=1")
            issues = api.routes[path]
            if kind == "closed":
                issues[0]["state"] = "closed"
            elif kind == "assigned":
                issues[0]["assignees"] = [{"login": "copilot-swe-agent[bot]"}]
            elif kind == "missing":
                api.routes[path] = []
            elif kind == "duplicate":
                issues.append({**issues[0], "number": 8})
            else:
                issues[0]["body"] = ""
            with self.subTest(kind=kind), self.assertRaises(PortError):
                cloud.launch("WP-101", "attempt-1")
            self.assertFalse(any(c[0] == "POST" for c in api.calls))

    def test_ignored_assignment_response_is_not_success(self):
        cloud, api, prefix, _ = cloud_fixture()
        api.routes[("POST", prefix + "/issues/7/assignees")]["assignees"] = []
        with self.assertRaisesRegex(PortError, "did not confirm"):
            cloud.launch("WP-101", "attempt-1")

    def test_cloud_reconciles_actual_task_pr_and_owner_and_does_not_infer_idle_completion(self):
        cloud, api, prefix, tasks = cloud_fixture()
        api.routes[("GET", prefix + "/pulls?state=all&per_page=100&page=1")] = [{
            "id": 456, "number": 123, "body": marker("WP-101"), "title": "[WP-101] fixture", "state": "open",
        }]
        task = {
            "id": "task-1", "state": "idle", "custom_agent": {"id": "protocol-porter"},
            "artifacts": [{"provider": "github", "type": "pull", "data": {"id": 456}}],
        }
        api.routes[("GET", tasks + "/tasks?per_page=100&page=1")] = {"tasks": [task]}
        result = cloud.reconcile("WP-101", Identity())
        self.assertEqual(result.identity, Identity(issue_number=7, task_id="task-1", pr_number=123))
        self.assertEqual(result.worker_state, "idle")
        self.assertFalse(any(c[0] == "POST" for c in api.calls))
        for kind in ("duplicate", "owner", "unknown-state"):
            value = copy.deepcopy(task)
            if kind == "duplicate":
                rows = [task, {**task, "id": "task-2"}]
            elif kind == "owner":
                value["custom_agent"]["id"] = "wrong-agent"
                rows = [value]
            else:
                value["state"] = "finished-looking"
                rows = [value]
            api.routes[("GET", tasks + "/tasks?per_page=100&page=1")] = {"tasks": rows}
            with self.subTest(kind=kind), self.assertRaises(PortError):
                cloud.reconcile("WP-101", Identity())

    def test_prior_assignment_without_actual_task_receipt_is_ambiguous(self):
        cloud, api, prefix, _ = cloud_fixture()
        api.routes[("GET", prefix + "/issues?state=all&per_page=100&page=1")][0]["assignees"] = [{"login": "copilot-swe-agent[bot]"}]
        result = cloud.reconcile("WP-101", Identity(issue_number=7))
        self.assertTrue(result.ambiguous)
        self.assertEqual(result.worker_state, "unknown")

    def test_archived_known_task_is_fetched_by_identity_not_forgotten(self):
        cloud, api, _, tasks = cloud_fixture()
        api.routes[("GET", tasks + "/tasks/task-archived")] = {
            "id": "task-archived", "state": "completed", "custom_agent": {"id": "protocol-porter"},
        }
        result = cloud.reconcile("WP-101", Identity(task_id="task-archived"))
        self.assertEqual(result.identity.task_id, "task-archived")
        self.assertEqual(result.worker_state, "completed")

    def test_cloud_usage_includes_archived_and_active_sessions_and_missing_usage_blocks(self):
        cloud, api, _, tasks = cloud_fixture()
        active = {"id": "task-1", "state": "in_progress", "created_at": "1970-01-01T00:00:00Z"}
        archived = {"id": "task-2", "state": "completed", "created_at": "1970-01-01T00:00:00Z"}
        api.routes[("GET", tasks + "/tasks?per_page=100&page=1")] = {"tasks": [active]}
        api.routes[("GET", tasks + "/tasks?is_archived=true&per_page=100&page=1")] = {"tasks": [archived]}
        for task_id, amount in (("task-1", 2), ("task-2", 3)):
            api.routes[("GET", tasks + "/tasks/" + task_id)] = {
                "id": task_id, "sessions": [{
                    "id": task_id + "-session", "state": "in_progress" if task_id == "task-1" else "completed",
                    "created_at": "1970-01-01T00:00:00Z",
                    "usage": {"type": "ai_credits", "amount": amount},
                }],
            }
        budget = Budget("ai_credits", 100, 5, 0)
        result = cloud.usage(budget, NOW)
        self.assertEqual(result.used, 5)
        self.assertEqual(result.active_ids, ("task-1",))
        api.routes[("GET", tasks + "/tasks/task-1")]["sessions"][0].pop("usage")
        with self.assertRaisesRegex(PortError, "usage"):
            cloud.usage(budget, NOW)

    def test_old_task_with_recent_repair_session_is_in_current_budget(self):
        cloud, api, _, tasks = cloud_fixture()
        api.routes[("GET", tasks + "/tasks?per_page=100&page=1")] = {"tasks": [{
            "id": "old-task", "state": "completed", "created_at": "1970-01-01T00:00:00Z",
        }]}
        api.routes[("GET", tasks + "/tasks/old-task")] = {
            "id": "old-task", "sessions": [
                {"id": "old-session", "state": "completed", "created_at": "1970-01-01T00:00:00Z",
                 "completed_at": "1970-01-01T00:00:01Z", "usage": {"type": "ai_credits", "amount": 3}},
                {"id": "recent-repair", "state": "completed", "created_at": "1970-01-01T00:00:10Z",
                 "completed_at": "1970-01-01T00:00:11Z", "usage": {"type": "ai_credits", "amount": 7}},
            ],
        }
        self.assertEqual(cloud.usage(Budget("ai_credits", 100, 5, 5), NOW).used, 7)

    def test_repair_targets_existing_pr_and_requires_receipt(self):
        cloud, api, prefix, _ = cloud_fixture()
        api.routes[("POST", prefix + "/issues/123/comments")] = {
            "id": 88, "body": "@copilot fix bounded case",
            "issue_url": "https://api.github.com" + prefix + "/issues/123",
        }
        receipt = cloud.repair(Identity(task_id="task-1", pr_number=123), "fix bounded case")
        self.assertEqual(receipt["task_id"], "task-1")
        self.assertEqual(api.calls[-1][2]["body"], "@copilot fix bounded case")
        with self.assertRaises(PortError):
            cloud.repair(Identity(), "fix")

    def test_native_host_payload_receipt_and_model_inheritance(self):
        manifest = test_manifest()
        rules = policy()
        owner = manifest.wp("WP-101")["owner"]
        approved = next(a["sha256"] for a in manifest.data["agents"] if a["name"] == owner)
        capabilities = {
            "repository": rules["repository"], "default_branch_sha": BASE, "profiles": {owner: approved},
            "branch": "fixture-branch", "worktree": "C:\\fixture-worktree",
            **{k: True for k in (
                "isolated_worktrees", "shared_ledger", "advisory_reservations",
                "read_only_reference", "credential_isolation", "authenticated",
            )},
        }
        requests = []

        def create(request):
            requests.append(request)
            return {"session_id": "native-session"}

        actual = {
            "session_id": "native-session", "repository": rules["repository"], "work_package": "WP-101",
            "attempt": "attempt-1", "base_sha": BASE, "worker_state": "in_progress", "pr_open": False,
        }
        host = NativeHost(
            lambda: capabilities, lambda wp, identity: Observation(identity, "idle", True, True),
            create, lambda identity: actual, lambda identity, prompt: {"session_id": identity, "delivered": True},
            lambda budget, now: Usage(budget.unit, 0, now, True, (), ("local",)),
        )
        backend = LocalHostBackend(host, manifest, rules)
        backend.preflight("WP-101", BASE)
        result = backend.launch("WP-101", "attempt-1")
        self.assertEqual(result.identity.session_id, "native-session")
        self.assertEqual(requests[0]["workspace_type"], "worktree")
        self.assertEqual(requests[0]["kickoff"]["agent"], "protocol-porter")
        self.assertNotIn("model", requests[0]["kickoff"])
        self.assertNotIn("base_branch", requests[0])
        self.assertEqual(backend.reconcile("WP-101", result.identity).worker_state, "idle")
        actual["attempt"] = "wrong"
        backend.preflight("WP-101", BASE)
        with self.assertRaises(PortError):
            backend.launch("WP-101", "attempt-1")
        capabilities["advisory_reservations"] = False
        with self.assertRaises(PortError):
            backend.preflight("WP-101", BASE)

    def test_no_native_host_callback_is_a_python_create_session_api(self):
        payload = local_payload(test_manifest(), policy(), "WP-101", "attempt")
        self.assertNotIn("command", payload)
        self.assertNotIn("--allow-all", json.dumps(payload))
        self.assertEqual(payload["notify_on_idle"], "always")

    def test_http_client_blocks_side_effects_redirect_style_urls_and_uses_explicit_token(self):
        api = GitHubApi("fixture-not-a-credential")
        for method, path in (("POST", "/repos/x/y/issues"), ("PUT", "/repos/x/y/pulls/1/merge"),
                             ("GET", "//evil.example/"), ("GET", "/repos/x\n/y")):
            with self.subTest(method=method, path=path), self.assertRaises(PortError):
                api.request(method, path, {})
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b'{"id":"fixture"}'
        api.opener.open = Mock(return_value=response)
        self.assertEqual(api.request("GET", "/agents/repos/x/y/tasks/fixture"), {"id": "fixture"})
        request = api.opener.open.call_args.args[0]
        self.assertEqual(request.get_header("X-github-api-version"), "2026-03-10")
        self.assertTrue(request.get_header("Authorization").startswith("Bearer "))

    def test_merge_backend_requires_live_strict_rules_current_base_head_and_actual_success(self):
        rules = policy()
        prefix = f"/repos/{rules['repository']}"
        routes = {
            ("GET", prefix + "/branches/main/protection"): {
                "required_status_checks": {"strict": True, "contexts": rules["required_checks"]},
                "enforce_admins": {"enabled": True},
            },
            ("GET", prefix + "/git/ref/heads/main"): {"object": {"sha": BASE}},
            ("GET", prefix + "/pulls/123"): {
                "head": {"sha": HEAD}, "base": {"sha": BASE}, "state": "open", "draft": False,
            },
            ("PUT", prefix + "/pulls/123/merge"): {"merged": True, "sha": MERGE},
        }
        api = FakeApi(copy.deepcopy(routes))
        backend = MergeBackend(api, rules)
        self.assertEqual(backend.merge_exact(123, HEAD, BASE)["sha"], MERGE)
        self.assertEqual(api.calls[-1][2], {"sha": HEAD, "merge_method": "squash"})
        for kind in ("strict", "admin", "checks", "base", "head", "draft", "response"):
            api = FakeApi(copy.deepcopy(routes))
            if kind in ("strict", "checks"):
                api.routes[("GET", prefix + "/branches/main/protection")]["required_status_checks"][kind if kind == "strict" else "contexts"] = False if kind == "strict" else []
            elif kind == "admin":
                api.routes[("GET", prefix + "/branches/main/protection")]["enforce_admins"]["enabled"] = False
            elif kind == "base":
                api.routes[("GET", prefix + "/git/ref/heads/main")]["object"]["sha"] = HEAD
            elif kind == "head":
                api.routes[("GET", prefix + "/pulls/123")]["head"]["sha"] = BASE
            elif kind == "draft":
                api.routes[("GET", prefix + "/pulls/123")]["draft"] = True
            else:
                api.routes[("PUT", prefix + "/pulls/123/merge")]["merged"] = False
            with self.subTest(kind=kind), self.assertRaises(PortError):
                MergeBackend(api, rules).merge_exact(123, HEAD, BASE)


class AuthorityTests(unittest.TestCase):
    def fixture(self):
        cloud, api, prefix, _ = cloud_fixture()
        manifest = test_manifest("WP-000")
        cloud.manifest = manifest
        value = bundle(manifest, cloud.policy, "WP-000")
        payload = {
            "schema_version": 1, "binding": asdict(value.binding), "evidence": value.evidence,
            "review": value.review, "approvals": value.approvals, "catalog": value.catalog,
        }
        api.routes[("GET", prefix + "/pulls/123")] = {
            "number": 123, "body": marker("WP-000"), "merged": True, "merge_commit_sha": MERGE,
            "head": {"sha": HEAD}, "base": {
                "sha": MERGE, "ref": "main", "repo": {"full_name": cloud.policy["repository"]},
            },
            "user": {"login": "fixture-worker"}, "labels": [],
        }
        api.routes[("GET", prefix + "/git/commits/" + MERGE)] = {
            "sha": MERGE, "parents": [{"sha": BASE}, {"sha": HEAD}],
        }
        checks = []
        for index, run in enumerate(value.evidence["runs"], start=1):
            checks.append({
                "id": index, "name": run["check"], "head_sha": HEAD,
                "status": "completed", "conclusion": "success", "app": {"id": 10},
                "details_url": f"https://github.com/{cloud.policy['repository']}/actions/runs/{index}",
                "check_suite": {"id": 100 + index},
                "external_id": f"{value.binding.key}:{index}:1",
                "output": {"summary": GATE_MARKER + json.dumps(payload)} if run["check"] == "gate-integrity" else {},
            })
            api.routes[("GET", prefix + f"/actions/runs/{index}")] = {
                "id": index, "check_suite_id": 100 + index,
                "repository": {"full_name": cloud.policy["repository"]}, "workflow_id": 20, "run_attempt": 1,
                "head_sha": HEAD if run["check"] == "android-ci" else BASE,
                "event": "pull_request" if run["check"] == "android-ci" else "workflow_dispatch",
                "status": "completed", "conclusion": "success",
            }
        api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")] = {"check_runs": checks}
        api.routes[("GET", prefix + "/pulls/123/files?per_page=100&page=1")] = [
            {"filename": value.pr["changed_paths"][0], "status": "modified"},
        ]
        api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")] = [{
            "id": 99, "state": "APPROVED", "commit_id": HEAD, "user": {"login": "cbattlegear"},
            "body": f"<!-- android-port-approval:{value.binding.key} -->",
        }]
        return GitHubGateAuthority(cloud, manifest, cloud.policy), api, prefix, value

    def test_real_api_facts_trusted_publisher_run_and_approval_provenance_are_reconciled(self):
        authority, api, _, value = self.fixture()
        result = authority.bundle("WP-000", Identity(pr_number=123))
        self.assertTrue(result.authoritative)
        self.assertEqual(result.binding, value.binding)
        self.assertEqual(result.pr["base_sha"], BASE)
        self.assertEqual(result.pr["changed_paths"], value.pr["changed_paths"])
        self.assertFalse(any(c[0] in ("POST", "PUT", "PATCH") for c in api.calls))

    def test_terminal_merge_receipt_is_bound_to_real_merged_pr_and_changed_paths(self):
        authority, api, prefix, value = self.fixture()
        receipt = {
            "repository": value.binding.repository, "session_id": "session-1", "pr_number": 123,
            "head_sha": HEAD, "merge_sha": MERGE, "paths": value.pr["changed_paths"],
        }
        terminal = authority.merged_terminal("WP-000", "session-1", receipt, BASE)
        self.assertTrue(terminal.authoritative)
        self.assertEqual(terminal.base_sha, BASE)
        self.assertEqual(terminal.paths, tuple(value.pr["changed_paths"]))
        self.assertFalse(any(call[0] != "GET" for call in api.calls))

        api.routes[("GET", prefix + "/git/commits/" + MERGE)]["parents"] = [{"sha": BASE}]
        self.assertTrue(authority.merged_terminal("WP-000", "session-1", receipt, BASE).authoritative)

        api.routes[("GET", prefix + "/pulls/123")]["merged"] = False
        with self.assertRaisesRegex(PortError, "exact merged"):
            authority.merged_terminal("WP-000", "session-1", receipt, BASE)

        api.routes[("GET", prefix + "/pulls/123")]["merged"] = True
        wrong = {**receipt, "paths": ["unrelated/path"]}
        with self.assertRaisesRegex(PortError, "changed paths"):
            authority.merged_terminal("WP-000", "session-1", wrong, BASE)

        api.routes[("GET", prefix + "/git/commits/" + MERGE)]["parents"][0]["sha"] = "4" * 40
        api.routes[("GET", prefix + f"/compare/{BASE}...{'4' * 40}")] = {"status": "diverged"}
        with self.assertRaisesRegex(PortError, "exact predecessor base and head"):
            authority.merged_terminal("WP-000", "session-1", receipt, BASE)

    def test_terminal_merge_accepts_advanced_landing_base_only_with_both_ancestries(self):
        landing = "4" * 40
        for invalid in (None, "diverged", "base", "merge-base", "behind", "missing-head", "wrong-parent"):
            with self.subTest(invalid=invalid):
                authority, api, prefix, value = self.fixture()
                receipt = {
                    "repository": value.binding.repository, "session_id": "session-1", "pr_number": 123,
                    "head_sha": HEAD, "merge_sha": MERGE, "paths": value.pr["changed_paths"],
                }
                api.routes[("GET", prefix + "/git/commits/" + MERGE)]["parents"][0]["sha"] = landing
                for descendant in (landing, HEAD):
                    api.routes[("GET", prefix + f"/compare/{BASE}...{descendant}")] = {
                        "status": "ahead", "base_commit": {"sha": BASE},
                        "merge_base_commit": {"sha": BASE}, "behind_by": 0,
                    }
                comparison = api.routes[("GET", prefix + f"/compare/{BASE}...{HEAD}")]
                if invalid == "diverged":
                    comparison["status"] = "diverged"
                elif invalid == "base":
                    comparison["base_commit"]["sha"] = landing
                elif invalid == "merge-base":
                    comparison["merge_base_commit"]["sha"] = landing
                elif invalid == "behind":
                    comparison["behind_by"] = 1
                elif invalid == "missing-head":
                    comparison.clear()
                elif invalid == "wrong-parent":
                    api.routes[("GET", prefix + "/git/commits/" + MERGE)]["parents"][1]["sha"] = landing
                if invalid is not None:
                    with self.assertRaises(PortError):
                        authority.merged_terminal("WP-000", "session-1", receipt, BASE)
                else:
                    terminal = authority.merged_terminal("WP-000", "session-1", receipt, BASE)
                    self.assertTrue(terminal.authoritative)
                    self.assertEqual(terminal.base_sha, BASE)
                    self.assertEqual(terminal.head_sha, HEAD)
                    self.assertEqual(terminal.merge_sha, MERGE)
                self.assertTrue(all(call[0] == "GET" for call in api.calls))

    def test_candidate_model_pass_missing_publisher_stale_run_or_fake_approval_is_not_authority(self):
        for kind in ("publisher", "summary", "run", "candidate-privilege", "approval", "stamp", "base"):
            authority, api, prefix, _ = self.fixture()
            checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
            if kind == "publisher":
                checks[-1]["app"]["id"] = 999
            elif kind == "summary":
                checks[-1]["output"]["summary"] = '{"verdict":"PASS"}'
            elif kind == "run":
                api.routes[("GET", prefix + "/actions/runs/1")]["conclusion"] = "skipped"
            elif kind == "candidate-privilege":
                api.routes[("GET", prefix + "/actions/runs/2")]["head_sha"] = HEAD
            elif kind in ("approval", "stamp"):
                review = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]
                review["state" if kind == "approval" else "body"] = "COMMENTED" if kind == "approval" else "looks good"
            else:
                api.routes[("GET", prefix + "/git/commits/" + MERGE)]["parents"][0]["sha"] = HEAD
            with self.subTest(kind=kind), self.assertRaises(PortError):
                authority.bundle("WP-000", Identity(pr_number=123))

    def test_renames_include_both_old_and_new_write_paths(self):
        authority, api, prefix, _ = self.fixture()
        api.routes[("GET", prefix + "/pulls/123/files?per_page=100&page=1")] = [{
            "filename": "tools/android-port/controller/new.py", "previous_filename": "tools/android-port/controller/old.py",
            "status": "renamed",
        }]
        result = authority.bundle("WP-000", Identity(pr_number=123))
        self.assertCountEqual(result.pr["changed_paths"], [
            "tools/android-port/controller/new.py", "tools/android-port/controller/old.py",
        ])

    def test_later_maintainer_changes_requested_invalidates_old_approval(self):
        authority, api, prefix, _ = self.fixture()
        api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")].append({
            "id": 100, "state": "CHANGES_REQUESTED", "commit_id": HEAD,
            "user": {"login": "cbattlegear"}, "body": "needs a real correction",
        })
        with self.assertRaisesRegex(PortError, "Approval"):
            authority.bundle("WP-000", Identity(pr_number=123))

    def test_sole_author_code_review_uses_authenticated_check_without_formal_review(self):
        authority, api, prefix, value = self.fixture()
        api.routes[("GET", prefix + "/pulls/123")]["user"]["login"] = "cbattlegear"
        api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")] = []
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
        payload = json.loads(checks[-1]["output"]["summary"][len(GATE_MARKER):])
        payload["approvals"] = []
        checks[-1]["output"]["summary"] = GATE_MARKER + json.dumps(payload)
        result = authority.bundle("WP-000", Identity(pr_number=123))
        self.assertTrue(result.authoritative)
        from controller.gates import verify_completion

        proof = verify_completion(result.pr, result.evidence, result.review, result.approvals,
                                  result.binding, authority.manifest, authority.policy, result.catalog)
        self.assertIsNone(proof["approval_id"])

    def test_human_acceptance_comment_is_real_bound_and_revocable_even_for_author(self):
        for kind in ("valid", "wrong-state", "wrong-kind", "wrong-author", "missing-stamp", "revoked",
                     "revoked-without-record", "comment-after-findings"):
            authority, api, prefix, value = self.fixture()
            api.routes[("GET", prefix + "/pulls/123")]["user"]["login"] = "cbattlegear"
            checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
            payload = json.loads(checks[-1]["output"]["summary"][len(GATE_MARKER):])
            payload["approvals"][0].update(kind="human-acceptance", state="COMMENTED")
            reviews = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")]
            reviews[0].update(state="COMMENTED", body=f"<!-- android-port-acceptance:{value.binding.key} -->")
            if kind == "wrong-state":
                reviews[0]["state"] = "APPROVED"
            elif kind == "wrong-kind":
                payload["approvals"][0]["kind"] = "fabricated-acceptance"
            elif kind == "wrong-author":
                reviews[0]["user"]["login"] = "unregistered"
            elif kind == "missing-stamp":
                reviews[0]["body"] = "looks good"
            elif kind in ("revoked", "revoked-without-record"):
                reviews.append({"id": 100, "state": "CHANGES_REQUESTED", "commit_id": HEAD,
                                "user": {"login": "cbattlegear"}, "body": "not accepted"})
                if kind == "revoked-without-record":
                    payload["approvals"] = []
            elif kind == "comment-after-findings":
                reviews.append({"id": 98, "state": "CHANGES_REQUESTED", "commit_id": HEAD,
                                "user": {"login": "cbattlegear"}, "body": "unresolved code finding"})
            checks[-1]["output"]["summary"] = GATE_MARKER + json.dumps(payload)
            with self.subTest(kind=kind):
                if kind == "valid":
                    self.assertTrue(authority.bundle("WP-000", Identity(pr_number=123)).authoritative)
                else:
                    with self.assertRaises(PortError):
                        authority.bundle("WP-000", Identity(pr_number=123))
