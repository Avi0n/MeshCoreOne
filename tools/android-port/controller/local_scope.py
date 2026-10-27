"""AndroidOnly: WP-003 Immutable, CI-derived local affected-scope requests."""

import json
import re
import subprocess
import sys
from pathlib import Path

from .errors import PortError
from .model import SHA
from .paths import git_path

TASKS = {
    "scaffold": [
        "verifyScaffoldTests", "verifyRoomSchema", "validateModuleGraph",
        "runtimeDependencyInventory", "resolveScaffoldDependencies",
        ":app:assembleDebug", ":app:compileDebugAndroidTestKotlin", "lintScaffold",
    ],
    "prepare": ["resolveScaffoldDependencies"],
    "protocol": [":core:protocol:test"],
    "benchmark": [":benchmark:verifyBenchmarkHarness", "validateModuleGraph"],
}
SCOPED_TASKS = {
    "scaffold": TASKS["scaffold"],
    "protocol": TASKS["protocol"],
    "backup": [
        ":core:data:verifyBackupTests", ":core:data:verifyPersistenceRepositoryTests",
        ":core:database:testDebugUnitTest",
    ],
    "external-oracle": [":core:testing:testDebugUnitTest", "validateModuleGraph"],
}
MODULE = re.compile(r"(?:app|(?:core|feature|platform)/[a-z][a-z0-9-]*|tools/meshcli|scaffold/room-verification)")
PROJECT = re.compile(r'\bproject\s*\(\s*"(:[a-z][a-z0-9:-]*)"\s*\)')
BACKUP = (
    "android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/backup/",
    "android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/backup/",
    "android/core/database/",
)


def git(repo, *arguments):
    return subprocess.check_output(
        ["git", "-C", str(repo), *arguments], encoding="utf-8", errors="strict"
    ).strip()


def default_base(repo):
    reference = git(repo, "symbolic-ref", "--quiet", "refs/remotes/origin/HEAD")
    if not reference.startswith("refs/remotes/origin/"):
        raise PortError("Affected scope requires the trusted origin default-branch reference")
    return git(repo, "rev-parse", "--verify", reference + "^{commit}")


def dependency_blocks(text):
    blocks = []
    for start in re.finditer(r"\bdependencies\s*\{", text):
        depth = 1
        for token in re.finditer(r'"(?:\\.|[^"\\])*"|[{}]', text[start.end():]):
            value = token.group()
            depth += (value == "{") - (value == "}")
            if depth == 0:
                blocks.append(text[start.end():start.end() + token.start()])
                break
        else:
            raise PortError("Unterminated committed dependency declaration")
    return "\n".join(blocks)


def module_inventory(repo, revision):
    paths = [
        git_path(path) for path in git(repo, "ls-tree", "-rz", "--name-only", revision, "--", "android").split("\0")
        if path
    ]
    modules = {}
    for path in paths:
        if not path.endswith("/build.gradle.kts"):
            continue
        module = path[len("android/"):-len("/build.gradle.kts")]
        if MODULE.fullmatch(module) is None:
            continue
        text = git(repo, "show", revision + ":" + path)
        text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.DOTALL)
        dependencies = dependency_blocks(text)
        references = PROJECT.findall(dependencies)
        if len(references) != len(re.findall(r"\bproject\s*\(", dependencies)):
            raise PortError("Unclassified project dependency expression: " + path)
        if len(re.findall(r"(?<![\w.])project\s*\(", text)) != len(
            re.findall(r"(?<![\w.])project\s*\(", dependencies)
        ):
            raise PortError("Project dependency outside a classified declaration: " + path)
        if re.search(r"\b(?:projects|findProject|subprojects|allprojects|ProjectDependency)\b", text):
            raise PortError("Dynamic project dependency declaration: " + path)
        jvm = 'id("mesh.jvm.library")' in text
        android = any(f'id("mesh.android.{plugin}")' in text for plugin in (
            "application", "library", "feature", "room",
        ))
        if jvm == android:
            raise PortError("Unknown module task convention: " + path)
        modules[module] = {
            "runner": "test" if jvm else "testDebugUnitTest",
            "lint": not jvm,
            "tests": any(
                name.startswith(f"android/{module}/src/{source}/")
                and name.endswith((".kt", ".java"))
                for source in ("test", "testDebug") for name in paths
            ),
            "dependencies": sorted({name.lstrip(":").replace(":", "/") for name in references}),
        }
    if any(dependency not in modules for value in modules.values() for dependency in value["dependencies"]):
        raise PortError("Unclassified module dependency requires full scaffold")
    return modules


def select_paths(paths, requested=None, *, modules=None, graph_error=None):
    from .scaffold_scope import EXACT, classify as full_scaffold

    paths = sorted({git_path(path) for path in paths})
    modules = modules or {}
    allowed = {*SCOPED_TASKS, *("module:" + module for module in modules)}
    requested = None if requested is None else list(requested)
    if requested is not None and (
        not requested or len(requested) != len(set(requested))
        or any(scope not in allowed for scope in requested)
    ):
        raise PortError("Unknown, empty or duplicate affected scope")
    changed = set()
    changed_tests = set()
    unclassified = []
    for path in paths:
        matches = [module for module in modules if path.startswith(f"android/{module}/src/")]
        if matches:
            changed.update(matches)
            changed_tests.update(module for module in matches if any(
                path.startswith(f"android/{module}/src/{source}/")
                for source in ("test", "testDebug", "androidTest")
            ))
        elif path.startswith(("android/parity-tests/src/", "android/ui-tests/src/",
                              "android/screenshot-tests/src/", "android/accessibility-tests/src/")):
            if "app" in modules:
                changed.add("app")
                changed_tests.add("app")
            else:
                unclassified.append(path)
        elif not path.startswith(("docs/", "tools/android-port/controller/", "tools/android-port/local/",
                                  "tools/android-port/tests/")) and not (
            path.startswith(("android/core/protocol/", *BACKUP))
        ):
            unclassified.append(path)
    broad = any(
        path.startswith(("android/gradle/", "android/build-logic/"))
        or path.endswith((".gradle", ".gradle.kts", ".lockfile"))
        or path in ("android/gradle.properties", "android/gradlew", "android/gradlew.bat")
        for path in paths
    )
    full = broad or bool(unclassified) or not paths or graph_error is not None or any(
        path in EXACT or path == "tools/android-port/controller/local_scope.py"
        or path in {f"tools/android-port/local/{name}" for name in ("check.py", "fast.py", "run.sh")}
        for path in paths
    )
    if not modules:
        full = full or full_scaffold(paths)
    reason = ("full-scaffold-fallback" + (": " + graph_error if graph_error else "")) if full else "actual-diff-and-consumer-closure"
    if full:
        required = set(SCOPED_TASKS)
    else:
        affected = set(changed)
        while True:
            consumers = {
                module for module, value in modules.items()
                if set(value["dependencies"]) & affected
            }
            expanded = affected | consumers
            if expanded == affected:
                break
            affected = expanded
        required = {"module:" + module for module in affected}
        if any(path.startswith("android/core/protocol/") for path in paths):
            required.add("protocol")
        if any(path.startswith(BACKUP) for path in paths):
            required.add("backup")
        if "core/testing" in affected:
            required.add("external-oracle")
    if requested is not None and not required.issubset(requested):
        raise PortError("Incomplete affected scopes; required: " + ", ".join(sorted(required)))
    selected = required if requested is None else set(requested)
    scopes = [scope for scope in SCOPED_TASKS if scope in selected] + sorted(selected - set(SCOPED_TASKS))
    tasks = [task for scope in scopes if scope in SCOPED_TASKS for task in SCOPED_TASKS[scope]]
    for scope in scopes:
        if scope.startswith("module:"):
            module = scope[len("module:"):]
            prefix = ":" + module.replace("/", ":") + ":"
            if modules[module]["tests"] or module == "app":
                tasks.append(prefix + modules[module]["runner"])
            if module != "app":
                tasks.append(prefix + "check")
            if modules[module]["lint"]:
                tasks.extend([prefix + "compileDebugKotlin", prefix + "lintDebug"])
            if module == "app":
                tasks.append(":app:compileDebugAndroidTestKotlin")
            if module == "core/l10n":
                tasks.append(":core:l10n:verifyL10nTests")
    tasks = list(dict.fromkeys(tasks))
    if tasks:
        tasks = list(dict.fromkeys([*tasks, "validateModuleGraph", "runtimeDependencyInventory"]))
    if "scaffold" not in scopes:
        priority = {module: 0 if module in changed_tests else 1 for module in changed}
        tasks.sort(key=lambda task: priority.get(task.rpartition(":")[0].lstrip(":").replace(":", "/"), 2))
    stages = ["python", "preflight", "scaffold"] if tasks else ["python"]
    if "scaffold" in scopes:
        stages.append("inspect")
    return {
        "scopes": scopes, "tasks": tasks, "full_scaffold": "scaffold" in scopes, "reason": reason,
        "stages": stages,
    }


def candidate_plan(repo, base, head, requested=None, *, trusted_base=None, checkout=False):
    """Bind selection to real endpoints; caller scopes can only add obligations."""
    for label, value in (("base", base), ("head", head)):
        if not isinstance(value, str) or SHA.fullmatch(value) is None:
            raise PortError(f"Affected scope requires an immutable {label} commit SHA")
        try:
            resolved = git(repo, "rev-parse", "--verify", value + "^{commit}")
        except subprocess.CalledProcessError as error:
            raise PortError(f"Affected {label} commit is unavailable; complete history is required") from error
        if resolved != value:
            raise PortError(f"Affected {label} is not a commit")
    authority = default_base(repo) if trusted_base is None else trusted_base
    if base != authority:
        raise PortError("Stale affected base; trusted default-branch revision differs")
    if checkout and git(repo, "rev-parse", "HEAD") != head:
        raise PortError("Stale affected head; checkout differs from the exact candidate")
    if checkout:
        if subprocess.run(["git", "-C", str(repo), "diff", "--quiet", head, "--"]).returncode:
            raise PortError("Affected checkout differs from the immutable candidate inputs")
        if git(repo, "ls-files", "--others", "--exclude-standard", "-z", "--", "android", "tools/android-port"):
            raise PortError("Uncommitted affected build/tool inputs cannot supply candidate evidence")
    grafts = git(repo, "rev-parse", "--git-path", "info/grafts")
    grafts = Path(grafts)
    if not grafts.is_absolute():
        grafts = repo / grafts
    if (
        git(repo, "rev-parse", "--is-shallow-repository") != "false"
        or git(repo, "for-each-ref", "--format=%(refname)", "refs/replace/")
        or grafts.exists()
    ):
        raise PortError("Affected lineage requires complete, unreplaced Git history")
    try:
        ancestors = git(repo, "merge-base", "--all", base, head).splitlines()
    except subprocess.CalledProcessError as error:
        raise PortError("Affected lineage has unknown or unrelated common history") from error
    if len(ancestors) != 1 or SHA.fullmatch(ancestors[0]) is None:
        raise PortError("Affected lineage has ambiguous common history")
    ancestor = ancestors[0]
    if ancestor == head and head != base:
        raise PortError("Affected candidate is an ancestor of the trusted target, not a branch candidate")
    paths = set()
    # Keep both branch changes already present in the target and target-only
    # differences absent from the immutable candidate in the obligation set.
    for start in dict.fromkeys((base, ancestor)):
        raw = subprocess.check_output([
            "git", "-C", str(repo), "diff", "--name-only", "--no-renames", "-z", start, head, "--",
        ])
        paths.update(git_path(path.decode("utf-8")) for path in raw.split(b"\0") if path)
    paths = sorted(paths)
    graph_error = None
    try:
        modules = module_inventory(repo, head)
        if not modules:
            raise PortError("No committed module task inventory")
    except PortError as error:
        # Unknown graph syntax must widen execution, not turn into omitted tests.
        modules = None
        graph_error = str(error)
    return {
        "schema_version": 1, "kind": "local-affected-only", "base": base, "head": head,
        "tree": git(repo, "rev-parse", head + "^{tree}"), "paths": paths,
        **select_paths(paths, requested, modules=modules, graph_error=graph_error),
    }


def validate_plan(repo, plan, *, trusted_base=None):
    if not isinstance(plan, dict) or set(plan) != {
        "schema_version", "kind", "base", "head", "tree", "paths",
        "scopes", "tasks", "full_scaffold", "stages", "reason",
    }:
        raise PortError("Malformed affected candidate plan")
    if (
        type(plan["schema_version"]) is not int or plan["schema_version"] != 1
        or plan["kind"] != "local-affected-only" or type(plan["full_scaffold"]) is not bool
        or not isinstance(plan["reason"], str)
        or any(not isinstance(plan[field], list) or not all(isinstance(item, str) for item in plan[field])
               for field in ("paths", "scopes", "tasks", "stages"))
    ):
        raise PortError("Malformed affected candidate plan fields")
    # The parent launcher establishes the trusted base before transport. Recompute
    # all selection from the imported immutable objects, not caller path lists.
    actual = candidate_plan(
        repo, plan["base"], plan["head"], plan["scopes"] or None,
        trusted_base=trusted_base, checkout=True,
    )
    if actual != plan:
        raise PortError("Affected plan differs from actual candidate diff or required obligations")
    return actual


def collect_targeted_reports(repo, scopes):
    from .ci_evidence import suite_counts

    suites = {}
    locations = {
        "protocol": (("core/protocol", "test", 84),),
        "backup": (("core/data", "testDebugUnitTest", 1), ("core/database", "testDebugUnitTest", 1)),
        "external-oracle": (("core/testing", "testDebugUnitTest", 1),),
    }
    if not isinstance(scopes, list) or not all(isinstance(scope, str) for scope in scopes):
        raise PortError("Malformed report scopes")
    modules = module_inventory(repo, git(repo, "rev-parse", "HEAD")) if any(
        scope.startswith("module:") for scope in scopes
    ) else {}
    for scope in scopes:
        if scope.startswith("module:"):
            module = scope[len("module:"):]
            if module not in modules:
                raise PortError("Unknown module report scope: " + scope)
            if modules[module]["tests"] or module == "app":
                directory = repo / "android" / module / "build/test-results" / modules[module]["runner"]
                suites[module] = suite_counts(directory, 1)
            continue
        if scope not in ("scaffold", *locations):
            raise PortError("Unknown report scope: " + str(scope))
        for module, runner, minimum in locations.get(scope, ()):
            suites[module] = suite_counts(repo / "android" / module / "build/test-results" / runner, minimum)
    return suites


def report_inputs(repo, plan):
    from .ci_evidence import LINT_TARGETS, SUITES
    from .module_junit import REPORT_LOCATIONS, module_sources

    modules = module_inventory(repo, plan["head"]) if any(
        scope.startswith("module:") for scope in plan["scopes"]
    ) else {}
    directories, lint = set(), set()
    if plan["full_scaffold"]:
        directories.update(repo / "android" / relative for relative, _ in SUITES.values())
        for module in module_sources(repo, plan["head"]):
            directories.update(repo / "android" / module / relative for relative in REPORT_LOCATIONS)
        lint.update(LINT_TARGETS)
    for scope in plan["scopes"]:
        if scope.startswith("module:"):
            module = scope[len("module:"):]
            if modules[module]["tests"] or module == "app":
                directories.add(repo / "android" / module / "build/test-results" / modules[module]["runner"])
            if modules[module]["lint"]:
                lint.add(module)
        for selected, targets in (
            ("protocol", ("core/protocol",)), ("backup", ("core/data", "core/database")),
            ("external-oracle", ("core/testing",)),
        ):
            if scope == selected:
                for module in targets:
                    runner = "test" if module == "core/protocol" else "testDebugUnitTest"
                    directories.add(repo / "android" / module / "build/test-results" / runner)
    return sorted(directories), sorted(lint)


def collect_named_cases(repo, directories):
    from .ci_evidence import read_xml
    from .module_junit import bounded_directory

    result = []
    for directory in directories:
        bounded_directory(directory, repo)
        for path in sorted(directory.glob("TEST-*.xml")):
            suite = read_xml(path)
            cases = suite.findall("testcase")
            try:
                declared = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            except (KeyError, ValueError) as error:
                raise PortError("Missing/malformed named JUnit counts") from error
            if suite.tag != "testsuite" or not cases or declared["tests"] != len(cases):
                raise PortError("Malformed/zero named JUnit suite")
            observed = dict.fromkeys(("failures", "errors", "skipped"), 0)
            for index, case in enumerate(cases):
                classname, method = case.get("classname"), case.get("name")
                outcomes = [node.tag for node in case if node.tag in ("failure", "error", "skipped")]
                if not classname or not method or len(outcomes) > 1:
                    raise PortError("Missing named JUnit identity or ambiguous outcome")
                outcome = outcomes[0] if outcomes else "passed"
                if outcomes:
                    observed[{"failure": "failures", "error": "errors", "skipped": "skipped"}[outcome]] += 1
                result.append({
                    "report": path.relative_to(repo).as_posix(), "index": index,
                    "class": classname, "method": method, "outcome": outcome,
                })
            if any(declared[key] != observed[key] for key in observed):
                raise PortError("Named JUnit declared/actual outcomes disagree")
    if not result:
        raise PortError("Missing/zero named JUnit evidence")
    return result


def validate_runtime_scopes(repo, plan):
    from .ci_evidence import report_tsv

    if plan["full_scaffold"]:
        return
    modules = module_inventory(repo, plan["head"])
    affected = {
        module for module in modules
        if any(path.startswith(f"android/{module}/src/") for path in plan["paths"])
    }
    if any(path.startswith(("android/parity-tests/src/", "android/ui-tests/src/",
                            "android/screenshot-tests/src/", "android/accessibility-tests/src/"))
           for path in plan["paths"]):
        affected.add("app")
    edges = report_tsv(
        repo / "android/build/reports/scaffold/module-graph.tsv",
        ["consumer", "producer", "configuration"],
    )
    while True:
        consumers = {
            edge["consumer"].lstrip(":").replace(":", "/") for edge in edges
            if edge["producer"].lstrip(":").replace(":", "/") in affected
        }
        expanded = affected | consumers
        if expanded == affected:
            break
        affected = expanded
    selected = {scope[len("module:"):] for scope in plan["scopes"] if scope.startswith("module:")}
    missing = affected - selected
    if missing:
        raise PortError(
            "Resolved module graph exposes omitted consumers; full scaffold required: "
            + ", ".join(sorted(missing))
        )


_PREFLIGHT_TASKS = (
    "resolveScaffoldDependencies", "validateModuleGraph", "runtimeDependencyInventory",
)


def split_preflight_tasks(tasks):
    preflight = [task for task in _PREFLIGHT_TASKS if task in tasks]
    build = [task for task in tasks if task not in _PREFLIGHT_TASKS]
    return preflight, build


def validation_preflight_options(options):
    if "--continue" in options:
        raise PortError("Scaffold preflight cannot use Gradle --continue")
    return list(options)


def run_scaffold(repo, plan, environment, output, execute, options, *, trusted_base):
    """Execute fixed affected tasks through the already-owned local supervisor."""
    started = False

    def owned_execute(*arguments):
        nonlocal started
        started = True
        return execute(*arguments)

    try:
        return _run_scaffold(repo, plan, environment, output, owned_execute, options, trusted_base=trusted_base)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, PortError, OSError, KeyboardInterrupt) as failure:
        if started:
            report = {
                "kind": "local-affected-only", "result": "failure",
                **{key: plan[key] for key in ("base", "head", "tree", "scopes", "tasks", "stages")},
                "failure": {"type": type(failure).__name__, "message": str(failure)},
                "junit_cases": [], "junit_reports": [],
            }
            if isinstance(failure, subprocess.CalledProcessError):
                report["failure"]["returncode"] = failure.returncode
            try:
                validate_plan(repo, plan, trusted_base=trusted_base)
                directories, _ = report_inputs(repo, plan)
                from .module_junit import bounded_directory

                for directory in directories:
                    record = {"directory": directory.relative_to(repo).as_posix()}
                    try:
                        bounded_directory(directory, repo)
                        if not any(directory.glob("TEST-*.xml")):
                            record["status"] = "missing-or-not-run"
                        else:
                            cases = collect_named_cases(repo, [directory])
                            identities = [(case["class"], case["method"]) for case in cases]
                            if len(set(identities)) != len(identities):
                                raise PortError("Duplicate actual test-case identity in failed-run evidence")
                            record["status"] = "available"
                            record["counts"] = {
                                outcome: sum(case["outcome"] == outcome for case in cases)
                                for outcome in ("passed", "failure", "error", "skipped")
                            }
                            report["junit_cases"].extend(cases)
                    except (PortError, OSError) as error:
                        record.update(status="invalid", error=str(error))
                    report["junit_reports"].append(record)
                validate_plan(repo, plan, trusted_base=trusted_base)
                report["source_binding"] = "valid"
            except (PortError, OSError, subprocess.SubprocessError) as error:
                report.update(source_binding="invalid", junit_cases=[], junit_reports=[], evidence_error=str(error))
            try:
                print(json.dumps(report, indent=2), flush=True)
            except OSError as error:
                failure.add_note("Failed to emit affected failure evidence: " + str(error))
        raise


def _run_scaffold(repo, plan, environment, output, execute, options, *, trusted_base):
    from . import ci
    from .ci_evidence import collect_lint, collect_suites, lint_evidence, validate_graph_runtime
    from .module_junit import bounded_directory, check_module_tests

    validate_plan(repo, plan, trusted_base=trusted_base)
    if not plan["tasks"]:
        raise PortError("Affected scaffold cannot execute a zero-task request")
    preflight_tasks, build_tasks = split_preflight_tasks(plan["tasks"])
    if not preflight_tasks or not build_tasks:
        raise PortError("Affected scaffold requires validation and build/test tasks")
    preflight_options = validation_preflight_options(options)
    directories, lint = report_inputs(repo, plan)
    preflight_graph = output / "gradle-affected-preflight-task-graph.json"
    build_graph = output / "gradle-affected-task-graph.json"
    reports = [
        repo / "android" / module / "build/reports/lint-results-debug.xml" for module in lint
    ] + [
        repo / "android/build/reports/scaffold" / name
        for name in ("module-graph.tsv", "runtime-dependencies.tsv")
    ]
    for directory in directories:
        bounded_directory(directory, repo)
        reports.extend(directory.glob("TEST-*.xml"))
    for report in reports:
        bounded_directory(report.parent, repo)
        if report.is_symlink():
            raise PortError("Linked previous report cannot become current evidence")
        if report.exists():
            report.unlink()
    for graph in (preflight_graph, build_graph):
        if graph.is_symlink():
            raise PortError("Linked resolved task graph")
        graph.unlink(missing_ok=True)
    init_script = str(repo / "tools/android-port/controller/verify_candidate_task_graph.gradle")
    graph_summaries = {}
    for phase, tasks, graph, log, gradle_options in (
        ("preflight", preflight_tasks, preflight_graph, "gradle-affected-preflight.log", preflight_options),
        ("build", build_tasks, build_graph, "gradle-scaffold.log", options),
    ):
        phase_environment = {**environment, "ANDROID_CI_TASK_GRAPH": str(graph)}
        execute([
            str(repo / "android/gradlew"), "-p", str(repo / "android"), *tasks, *gradle_options,
            "--init-script", init_script,
        ], repo, phase_environment, output / log)
        graph_summaries[phase] = ci.validate_candidate_task_graph(graph, tasks)
    report = {
        "kind": "local-affected-only", "base": plan["base"], "head": plan["head"], "tree": plan["tree"],
        "scopes": plan["scopes"], "tasks": plan["tasks"],
        "resolved_task_graph": {
            "tasks": sum(summary["tasks"] for summary in graph_summaries.values()),
            "result": "no-duplicate-ci-evidence",
            "phases": graph_summaries,
        },
        "suites": collect_targeted_reports(repo, plan["scopes"]),
        "lint": {
            module: lint_evidence(repo / "android" / module / "build/reports/lint-results-debug.xml")
            for module in lint
        },
    }
    validate_graph_runtime(repo / "android/build/reports/scaffold")
    validate_runtime_scopes(repo, plan)
    if plan["full_scaffold"]:
        report["scaffold_suites"] = collect_suites(repo / "android")
        report["module_unit_tests"] = check_module_tests(repo)
        report["lint"] = collect_lint(repo / "android")
        execute([
            sys.executable, str(repo / "android/parity-tests/semantic.py"),
            "--check", "--verify-execution",
        ], repo, environment, output / "parity-family-execution.log")
    elif "module:app" in plan["scopes"]:
        from .ci_evidence import WP501_ACCEPTANCE_CASES, require_case_identities

        app_test_results = repo / "android/app/build/test-results/testDebugUnitTest"
        report["wp501_acceptance_cases"] = require_case_identities(
            app_test_results, WP501_ACCEPTANCE_CASES,
        )
        execute([
            sys.executable, str(repo / "android/parity-tests/semantic.py"),
            "--check", "--verify-execution",
            "--runner-directory", "app/build/test-results/testDebugUnitTest",
        ], repo, environment, output / "parity-family-execution.log")
    if plan["full_scaffold"] or "module:core/l10n" in plan["scopes"]:
        execute([
            sys.executable, str(repo / "tools/android-port/l10n_convert.py"), "--check",
            "--verify-android-tests", str(repo / "android/core/l10n/build/test-results/testDebugUnitTest"),
        ], repo, environment, output / "l10n-junit.log")
    report["junit_cases"] = collect_named_cases(repo, directories) if directories else []
    validate_plan(repo, plan, trusted_base=trusted_base)
    return report
