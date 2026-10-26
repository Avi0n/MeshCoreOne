# WP-003 affected local verification

Source work for `LOCAL-CHANGE-SCOPED-VERIFICATION` remains separate from trusted
runtime deployment and native/Gradle lane recovery. An installed legacy runtime
or retained native owner is not permission to invoke Gradle directly. The existing
supported full scaffold remains available after those actual gates are recovered;
this optimization is not a prerequisite for other complete product batches.

The supported interlocked launcher has an explicit local-only affected contract:

```powershell
python .\tools\android-port\local\check.py --commit <candidate-SHA> --affected-base <current-origin-default-base-SHA>
```

Raw candidate SHAs need no caller-created advertising ref. Reused managed
snapshots explicitly fetch the requested immutable commit from the input bundle.

Omit `--scope` to derive all obligations automatically. If supplied repeatedly,
the explicit scope list must be a complete superset of the derived CI and
`module:<Git-module-path>` scopes. Unknown, duplicate or incomplete lists fail.
No arbitrary Gradle command/test filter is accepted. Affected mode rejects
partial-stage, working-tree, adapter, hook-installation, prepush and full-result
validation overrides. Ordinary `--stages`, full/manual, adapter and nonblocking
prepush behavior is unchanged.

The request is derived only after canonical shared Gradle-resource acquisition,
and passes through the existing native/Gradle/runtime interlock, Linux flock,
supervisor and owned-child cleanup. Its snapshot stage list, commit and tree
must match the inherited real admission descriptor. The immutable base is
validated before transport and checked again on return. It does not install or
upgrade the runtime, clear an incumbent owner, or add another execution route.
The snapshot does not print a final affected pass: only the launcher can do so,
after current-base revalidation, proven child cleanup and successful own-resource
release. Failure at any of those steps cannot produce a scoped success receipt.

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
For narrow selections, requested tasks for directly changed test-source modules
come first, then other directly changed modules, then required consumers.
The task set, scope/report lists and strict options are unchanged; full fallbacks
retain their existing order. Gradle still schedules prerequisites, so this is
not a measured wall-clock speedup. The trusted scope planner travels with the
owned launcher so immutable older product snapshots use the same strict plan
validation and requested task order without a product rebase.
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

The existing affected scaffold report also retains `junit_cases` before the
owned snapshot can be released or reused. Each row records the repository-relative
report, zero-based testcase index, exact class/method (including parameter names)
and actual XML outcome (`passed`, `failure`, `error`, `skipped`). Rows are not
deduplicated or truncated; repeated identities across module runners remain
distinct. Existing strict checks still reject duplicate identities within a
runner and any failed/error/skipped required case, so a successful report contains
only passed cases. Its existing base/head/tree, tasks and scopes bind the named
evidence to that run; later reads of reusable-slot XML are not needed. This adds
no raw XML copy, second result artifact or hosted-CI evidence bundle.

Lightweight selector regression command:

```powershell
$env:PYTHONPATH = "$PWD\tools\android-port"
python -m unittest discover -s .\tools\android-port\tests -p test_local_scope.py -v
```

Focused source/launcher integration fixtures (no real Gradle/native/runtime
deployment) can run within the light-source capacity lane:

```powershell
$env:PYTHONPATH = "$PWD\tools\android-port;$PWD\tools\android-port\local;$PWD\tools\android-port\tests"
python -m unittest test_local_scope hook_test.AffectedRunnerTests hook_test.LocalCheckTests.test_affected_scope_cannot_replace_full_pre_push_adapter_or_result_contract hook_test.LocalCheckTests.test_explicit_affected_contract_forwards_base_and_additional_scopes_only hook_test.LocalCheckTests.test_shell_forwards_scoped_request_through_existing_supervision_only -v
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

## Optional measured host JVM lanes

On the actual Linux execution host, add `--parallel-jvm` to the exact
`--affected-base` invocation above. It requires `ANDROID_PORT_LEDGER` to identify
the existing absolute canonical shared database outside the checkout/Git-common
directory; it does not create a per-clone execution database. Without the flag,
the existing repository-wide serial lock and six-field admission stay unchanged.
Adapters, advisory prepush, partial stages and arbitrary task overrides cannot
use this path.

The existing `hard_locks` transaction allocates the first free bounded slot for
the actual `/etc/machine-id`, not a caller-supplied host label. Capacity is the
smaller of available CPUs divided by four and available memory divided by 8 GiB.
CPU affinity and the current unified cgroup plus every limiting ancestor's
CPU/memory quotas are included. Unsupported cgroup layouts or missing/invalid
identity/measurements fail closed. The 8 GiB reservation covers the existing
3 GiB Gradle heap, 1 GiB metaspace, four 512 MiB test workers and headroom.
For example, 12 available CPUs and 44 GiB available memory admit three slots,
not a permanent two-slot ceiling. All occupied slots count against the current
measurement; this deliberately conservative policy may delay refill under
memory pressure rather than promising optimal utilization.

Each allocated slot has its own inherited admission binding, actual Linux flock,
input bundle, repository snapshots, reports, stripped private environment and
Gradle user cache. The common toolchain state is read-only. Cleanup proof and
successful owner-matched release permit ready work to refill a free slot.
Global serial Gradle, native, disposable and installed-runtime ownership still
exclude all JVM slots in both directions, including an unknown retained native
owner. No runtime installation or native recovery is performed by this flag.

The focused lane fixtures are declared by
`python -m unittest test_jvm_lanes -v` with the controller/tests PYTHONPATH above.
The local hook suite also contains real Linux shell/flock/pidfd fixtures using
test-owned stub work, not Gradle; native Windows instead proves rejection before
Linux launch. Neither outcome is real remote parallel product acceptance.
