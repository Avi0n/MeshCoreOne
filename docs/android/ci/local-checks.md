# WP-003 affected local verification

Source work for `LOCAL-CHANGE-SCOPED-VERIFICATION` remains separate from trusted
runtime deployment and native/Gradle lane recovery. An installed legacy runtime
or retained native owner is not permission to invoke Gradle directly. The existing
supported full scaffold remains available after those actual gates are recovered;
this optimization is not a prerequisite for other complete product batches.

The controller selector binds an immutable trusted default-base commit, candidate
commit, tree and actual NUL-delimited, no-renames Git diff. Deletions and both
sides of moves remain inputs. Explicit scopes can add obligations, never omit
changed modules, consumers or required checks. Stale endpoints and malformed
requests fail; empty diffs, unknown graph expressions, build/dependency/toolchain
changes and unclassified product inputs widen to the full scaffold.
Every full-scaffold selection retains all four existing stages: Python,
preflight, scaffold and `inspect` (including APK/alignment inspection). A plan
that drops inspection is rejected; only narrow no-APK scopes omit that stage.

Existing CI task lists are shared unchanged with the local selector. Ordinary
module source changes additionally select complete declared module unit tasks
and reverse consumers from committed project dependencies, not caller-provided
Gradle commands or test-method filters. Android consumers receive compile/lint
checks; App receives its complete unit runner and AndroidTest compilation.
Graph/runtime dependency inventory remains mandatory for every Gradle selection.
The actual resolved production graph is checked against the selected consumer
closure too: a consumer missed by static declaration inspection fails the
affected run and explicitly requires full scaffold, never a passing partial result.
Localization changes retain conversion and actual Android JUnit verification.
The complete services/runtime/App suites include typed errors, cancellation and
shared consumer tests rather than replacing them with selector fixtures.

Selected previous reports are removed before execution. Missing, malformed,
zero-test, failed/error/skipped required suites cannot be satisfied by old reports
or a skipped Gradle task. Current task-graph, JUnit, lint and graph/runtime parsers
remain authoritative. Affected results are explicitly local-only and must not be
accepted as the existing full-result schema, hosted CI, Android/native/hardware
acceptance, signing or a human/protected gate.

Lightweight selector regression command:

```powershell
$env:PYTHONPATH = "$PWD\tools\android-port"
python -m unittest discover -s .\tools\android-port\tests -p test_local_scope.py -v
```

The declared strict controller/local suites remain:

```powershell
python .\tools\android-port\controller\test_runner.py --quiet
python .\tools\android-port\controller\test_runner.py --tests .\tools\android-port\local --quiet
```

These Python suites establish source behavior only. Serialize their full runs
under the coordinator's current host capacity decision. Do not persist a second
CI-result bundle: the exact-commit hosted job and log remain final automated
candidate authority.
