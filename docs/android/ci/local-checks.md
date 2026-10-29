# Local verification and readiness recovery

## Session startup and failure diagnosis

Run commands from the current session's repository root, not another worktree.
First identify where the command actually executes: native Windows, WSL or a remote
Linux worker. Tool installations, state files and environment variables belong to
that execution host. A hosted setup job, an installed Android Studio or a running
agent does not establish local readiness.

The controller requires the Python version in
`tools/android-port/controller/toolchain-pins.json`, a same-host `environment.json`
supplied by `--state` or `ANDROID_CI_STATE`, and an absolute per-run output directory
supplied by `--output`, `ANDROID_CI_WORK` or `ANDROID_CI_OUTPUT`. Prefer explicit
arguments in automation. Reuse valid existing state before provisioning anything.
Do not hand-edit state, pins, package metadata or checks to turn a failure green.

| Failure | Required action |
| --- | --- |
| `Explicit --state/ANDROID_CI_STATE is required` | Locate the provisioned same-host state and pass it explicitly, or declare verified existing installations with `local-inputs` below. |
| `Explicit absolute --output/ANDROID_CI_WORK directory is required` | Allocate a fresh absolute output directory for this run; do not share one between workers. |
| Exact Python, Java or SDK revision mismatch | Select the pinned interpreter and actual matching installations. If tools are missing/outdated, use the reviewed provisioner below rather than changing the pins. |
| Missing, old or mismatched installed execution runtime | Report the exact runtime blocker for separately authorized, quiescent deployment. Do not install/upgrade it as an incidental test repair. |
| Native/Gradle ownership collision or unproven child cleanup | Retain the incumbent receipt and recovery inputs; the actual owner must prove cleanup and release through the supported path. Never force-release or infer idleness. |

The suffix `failed cloud setup is not readiness` explains the missing-state guard;
it does not establish that a cloud setup job failed. A readiness refusal means the
requested product tests did not run, not that those tests failed. Report the exact
blocker and unverified candidate honestly; it does not prohibit feature-branch
publication or replace exact-candidate hosted merge verification.

## Windows-native readiness setup

These commands configure native Windows controller readiness, not WSL builds.
They create private tooling outside the checkout and leave Android Studio's SDK
unchanged. Do not run installation commands automatically merely because this
guide exists: installing tools requires the user's setup authorization, and SDK
license acceptance requires their explicit acceptance of the pinned terms.

### 1. Select the pinned Python and install isolated verification dependencies

Run in PowerShell from the current worktree root. If the version check fails,
install/select the required Python first; do not continue with a different version.

```powershell
$pins = Get-Content .\tools\android-port\controller\toolchain-pins.json -Raw |
  ConvertFrom-Json
$actualPython = python -c "import platform; print(platform.python_version())"
if ($LASTEXITCODE -ne 0 -or $actualPython -ne $pins.python) {
  throw "Select Python $($pins.python) before continuing."
}

$venv = Join-Path $env:LOCALAPPDATA 'MeshCoreOne\verification-venv'
python -m venv $venv
if ($LASTEXITCODE -ne 0) { throw 'Python environment creation failed' }
$py = Join-Path $venv 'Scripts\python.exe'
$venvPython = & $py -c "import platform; print(platform.python_version())"
if ($LASTEXITCODE -ne 0 -or $venvPython -ne $pins.python) {
  throw "The verification environment must use Python $($pins.python)."
}

& $py -m pip install --require-hashes --no-deps --only-binary=:all: `
  -r .\tools\android-port\controller\requirements-ci.txt
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
```

### 2. Select or provision a matching Windows toolchain

If a valid Windows state already exists, set `$state` to its absolute path and skip
provisioning. If exact matching JDK/SDK installations exist but have no state, use
`ci.py local-inputs --root <absolute-private-root> --jdk <absolute-JDK-home>
--sdk <absolute-SDK-home>`. The resulting `<absolute-private-root>\environment.json`
only declares those installations; it does not install or validate them.

Otherwise, after explicit user acceptance of the pinned Android SDK license,
run the following. The provisioner requires a new absolute directory, verifies
first-party archives and installs the currently pinned JDK and SDK packages.
It does not deploy the shared execution runtime or start Gradle/native work.
Do not keep reprovisioning on network, checksum, metadata or license drift:
preserve the failure and report the concrete blocker.

```powershell
$root = Join-Path $env:LOCALAPPDATA (
  'MeshCoreOne\toolchains\' + [guid]::NewGuid().ToString('N')
)
& $py .\tools\android-port\controller\ci.py provision `
  --root $root `
  --accept-sdk-license
if ($LASTEXITCODE -ne 0) { throw 'Toolchain provisioning failed' }
$state = Join-Path $root 'environment.json'
```

### 3. Persist the state location and verify readiness

```powershell
[Environment]::SetEnvironmentVariable('ANDROID_CI_STATE', $state, 'User')
$env:ANDROID_CI_STATE = $state

$output = Join-Path $env:TEMP (
  'MeshCoreOne-preflight-' + [guid]::NewGuid().ToString('N')
)
& $py .\tools\android-port\controller\ci.py preflight `
  --local `
  --state $state `
  --output $output
if ($LASTEXITCODE -ne 0) { throw 'Windows readiness verification failed' }
```

Fully quit and relaunch the Copilot desktop app, then start/restart the relevant
sessions so they inherit the persisted state location. Already-running processes
do not automatically receive it. A PowerShell-profile-only export does not
configure an app launched outside that shell.

In later PowerShell processes, reconstruct the interpreter path with
`$py = Join-Path $env:LOCALAPPDATA 'MeshCoreOne\verification-venv\Scripts\python.exe'`
and use `$env:ANDROID_CI_STATE` or an explicit `--state` argument. Allocate a fresh
`$output` for every run. Do not persist one shared run-output directory.
Successful preflight proves current Windows input readiness only, not Android
test execution, device acceptance or hosted verification.

## WSL and remote workers are separate execution hosts

The supported `local/check.py` launcher transports Windows requests into WSL.
Its Linux runner reads `$ANDROID_LOCAL_HOME/toolchain/environment.json`, defaulting
to `$HOME/meshcoreone-work/toolchain/environment.json`, and uses
`ANDROID_LOCAL_PYTHON`, defaulting to `$HOME/meshcoreone-work/venv312/bin/python`
when `ANDROID_LOCAL_HOME` is unset. Configure actual Linux installations and
the reviewed execution runtime for that path; Windows `ANDROID_CI_STATE` cannot
satisfy it. The launcher creates its own per-run report directory.

For direct controller preflight on a remote Linux worker, select that host's
pinned Python and pass its Linux state and a fresh absolute output directory.
Configure the actual non-interactive worker/session launch environment, not just
an unrelated interactive SSH shell. Never copy Windows paths into Linux state
or assume exports in one SSH connection reach another running session.

Neither Windows readiness nor Linux configuration authorizes bypassing the
interlocked launcher with direct Gradle, deploying an execution runtime, clearing
an incumbent owner, changing repository settings or activating automation.

## Affected local verification

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

Its snapshot stage list, commit and tree
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
An unchanged candidate on a diverged published branch does not need a routine
rebase: `base` remains the current trusted target, never the older branch point.
Complete, unreplaced history must establish exactly one common ancestor.
Obligations include the union of branch-point-to-candidate and
target-to-candidate changed paths: independently converged branch changes and
target-only inputs absent from the candidate cannot disappear from selection.
Normal descendant selection is unchanged. Missing/shallow/replaced/grafted,
unrelated or ambiguous history and an old candidate that is merely an ancestor
of the target fail closed. Selection executes the exact candidate tree, not a
synthetic merge, and does not prove merge compatibility or replace merge checks.
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
no raw XML copy, second result artifact or hosted-CI evidence bundle. A selected
module without tests retains its compile/lint/graph obligations and an empty
`junit_cases` list only when the computed plan requires no JUnit directories;
missing or zero required suites still fail.

After execution has begun, a nonzero Gradle command, strict evidence failure,
OS error or interruption emits a failure-form affected report to the same run
log before rethrowing the original exception. It retains the selected
base/head/tree/scopes/tasks/stages and each available runner's exact pass,
failure, error and skipped case rows. Each expected JUnit directory is marked
`available`, `invalid`, or `missing-or-not-run`; missing XML cannot establish
which individual tests ran. Malformed/truncated/zero/duplicate reports are
explicitly invalid, never passing evidence. Valid runners are retained even if
another runner is missing or invalid. Source binding is revalidated before and
after harvest; a mismatch discards all case attribution and records the error.
Old reports were removed before the supervised command began. No failure-form
report prints success or changes the original nonzero/cancellation behavior,
and report harvesting completes before child cleanup and owned-resource release.

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
