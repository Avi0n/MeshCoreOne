# WP-003 isolated scaffold CI

**Supervised dependent draft, not accepted WP-003 or activation.** The build
workflow runs real scaffold commands. Dispatch, shepherd and gate-audit workflows
remain manual previews and deliberately return `BLOCKED` for absent fleet/merge
integrations. The manual review-publication workflow now has a supported native
GitHub transport, described below; deploying it is not fleet activation.

## Candidate execution contract

`android-ci.yml` runs for every pull request, including dependent drafts, plus
merge groups, `main` pushes and manual validation. A small scope job classifies
whether Android build inputs changed; metadata-only changes validate the
controller inputs without running the scaffold build. `ubuntu-24.04` x64 is the sole mandatory host; the
prior `windows-2025` leg was removed (no shipped Windows build, local Windows
development is covered by WSL instead).

The exact-commit GitHub job result and log are the authoritative reproducible
CI record. The build job validates complete JUnit and lint XML in place,
compares actual cases/outcomes with discovery and graph/runtime outputs, and
inspects the built APK before it can succeed. Zero discovery, failures, errors,
skips, malformed output or a failed command fail the job. The always-running
`android-ci` job directly requires success when the scope selected the build
and requires a skipped build otherwise. CI does not upload, download, hash or
revalidate a second result bundle.

| Stage | Actual executable tasks / mandatory evidence |
| --- | --- |
| Python | Frozen overlay/manifest/traceability/notices, controller regressions and all scaffold Python cases; nonzero discovery and no failed/error/skipped case |
| Preflight | Exact Python/JDK, publisher-pinned wrapper, installed SDK37.2/rev1 and build-tools37.0.0, explicit private caches, unchanged credential allowlist |
| `scaffold` | One Gradle invocation runs `verifyScaffoldTests verifyRoomSchema validateModuleGraph runtimeDependencyInventory resolveScaffoldDependencies :app:assembleDebug lintScaffold`; strict dependency verification remains mandatory, build-logic tests are collected once through the composite, and all 24 lint targets must produce readable XML with no errors |
| Inspect | Actual APK min31/target37/debug package, launcher, notices, permissions and test-fixture absence; `zipalign -P 16` and ELF PT_LOAD alignment |

Four Kotlin suites retain at least conventions31, contracts4, app10 and Room2
actual cases. The executor independently compares XML testcase/outcome nodes
with the scaffold TSV. These are scaffold assertions, **not original feature
test parity**. The 30-module boundary graph, Room/KSP schema check, runtime
POM/license inputs and exact debug APK are validated in the executing job.
Each host's debug key and APK are ephemeral. Static 16KB alignment is not
real-device/MapLibre/HIL evidence.

The live report validation additionally requires every committed active unit-test source
set under `core`, `feature` and `platform` to have complete nonzero raw JUnit
evidence. Original scaffold suites and the separately required protocol/testing
workflows are not double-counted. Expected modules and all their main/unit/build
input blob identities come from the exact candidate Git tree. An active module
missing a runner/report, having ambiguous JVM/Android report directories, or
containing failed/skipped/malformed cases fails.

Module owners must connect their actual `test` or `testDebugUnitTest` task to
`verifyScaffoldTests` in their module build script, following the existing
localization hook. Missing wiring is an explicit CI failure, not an empty-success
fallback. The localization-specific source/resource/family verifier still runs
against its live report. This additive unit-run proof is not original-case
parity, instrumentation, radio behavior or a protected WP acceptance receipt.

The WP-201 `verifyDomainRoomTests` hook validates current immutable model,
contracts and Room inputs, the frozen original 114 families/215 native identity
minimum, and the initial v1 schema. It allows additional executed cases and
unrelated committed native-module successors. Its historical writer-scope
audit runs only when explicitly requested with an exact base, never implicitly
in the evergreen root task. Historical `local-evidence.json` is unchanged;
current execution/run metadata and additive cases are separate. The collector
and its temporary-Git/raw-XML regressions are mandatory committed runtime
inputs. This is not a new source-parity or WP-201 acceptance receipt.

No detekt, ktlint, Kover, localization synchronization, full source-case catalog,
instrumentation, Swift/macOS oracle or hardware task is invented here. Their
actual tooling, output contracts and acceptance remain WP-004/005 and the owning
feature/quality WPs. Passing this workflow never configures those future WPs.

## Provisioning and credential boundary

`controller/toolchain-pins.json` pins Temurin21.0.12.1+1 archives separately for
Windows/Linux, exact Python3.12.4, Gradle9.8.0 distribution/JAR, and Google's actual
`platforms;android-37.2`, `build-tools;37.0.0`, `cmdline-tools;23.0` archives.
SDK SHA-256 values were independently computed from exact first-party downloads
and checked against Google's size/SHA-1 metadata. JDK SHA-256 values come from the
publisher release asset digests. Metadata comes from `repository2-3.xml`; the
older repository index and assumed `platforms;android-37` are not used.

Provisioning requires a new absolute ephemeral directory, verifies bytes before
extracting, rejects archive traversal and validates exact package revisions,
hosts and license text. The explicit `--accept-sdk-license` option records only
the pinned SDK terms for this isolated installation; it is not app/dependency
legal approval. Changed SDK terms require human provisioning review.
The command-line-tools archive is installed but its CLI bootstrap is not executed:
an actual Windows probe showed it automatically fetching a separate helper.
Preflight instead reads real installed package metadata and Gradle exercises
the exact platform/build tools. Any separately approved future CLI invocation
must use `--no-metrics`; no CLI login, updater or device operation is a CI step.
Both-host installer regressions forbid every process invocation and every
download beyond the exact four locked archives plus first-party SDK metadata.
Provisioning provenance explicitly records archive-only installation and no
separate CLI-helper download/execution.
No SDK/JDK/cache/debug key or private installation path is committed.
`runtime_inputs.py` checks the immutable committed Git tree and exact checkout
bytes (with Git text CRLF/LF normalization, never binary normalization) for every
required pin/requirement/reader/workflow/build input before
provisioning or verification. A developer-only ignored file cannot satisfy it.

All Actions are pinned to full reviewed commit SHAs. Checkout has
`persist-credentials: false`; workflow/job tokens have only `contents: read`.
No signing, dispatch, merge, Copilot credential, shared runner, service/container
or cross-PR cache is provided. Candidate Gradle receives only the **unchanged**
`android/scaffold/environment-allowlist.json` names, with explicit `JAVA_HOME`,
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`, `ANDROID_USER_HOME`.
The Windows PowerShell child strips startup-added variables and repeats the
independent parent preflight before invoking the batch wrapper.

Hosted builds use one worker, in-process Kotlin, a 2GiB build heap and 512MiB
test heap; these are build-process budgets, not app runtime flags or AI budgets.
The separate local `--local` mode retains measured shared-Windows constraints.
PyYAML6.0.3 is hash-pinned MIT-licensed CI parsing tooling, not an Android-linked
dependency. Tool/SDK terms and existing GPLv3/MIT/Apache notices remain distinct
from pending human dependency/license admission.

## Cloud setup and worker readiness

`copilot-setup-steps.yml` has exactly one supported Ubuntu x64
`copilot-setup-steps` job, only supported job properties, and a 59-minute limit.
It provisions the same exact archives and resolves the real scaffold
dependencies strictly. PR/manual execution validates setup code, **not cloud
account assignability**. The file takes effect for Copilot only after a real
default-branch merge; no cloud agent is invoked by this draft.

Setup failure may still allow Copilot to start. Before writing, a worker must
run the explicit preflight and actual strict dependency preparation, not infer
readiness from the setup workflow name or an existing directory.

At the repository root, with `ANDROID_CI_STATE` pointing to the private
provisioner's `environment.json` and `ANDROID_CI_OUTPUT` to a private absolute
evidence directory:

```powershell
python .\tools\android-port\controller\ci.py preflight
python .\tools\android-port\controller\ci.py run --stage prepare
python .\tools\android-port\controller\ci.py python
python .\tools\android-port\controller\ci.py run --stage scaffold
python .\tools\android-port\controller\ci.py inspect
```

Use `--local` for the documented constrained shared host. Local records explicitly
lack hosted run authority. Missing state, SDK, checksum, command, test or output
fails; no fallback installation/cache/pin rewrite occurs.

## Protected verification amendment

Only WP-002/003 executable verification configurations change. Every WP ID,
owner, dependency, human/supervised flag, acceptance, source ownership and pending
feature state remains unchanged. WP-001 stays unconfigured; architecture/license
approval cannot be replaced by a static build command.

`verification_config.py --check` reconstructs the frozen WP-000 generator output,
requires its original semantic digest, applies the two exact controller-owned
overlays, and compares the complete canonical manifest/exclusions. The original
`bootstrap.py`, `inventory_rules.py`, `wp_metadata.py`, plan and schemas are not
edited. **Bare `bootstrap.py` check mode now deliberately rejects the amended
manifest**; the protected overlay command is the coherent new check, not a claim
that the old generator remained unchanged and magically accepted new fields.

```powershell
python .\tools\android-port\controller\verification_config.py --check
python .\tools\android-port\controller\validate.py
python .\tools\android-port\controller\workflows.py
python .\tools\android-port\controller\test_runner.py --quiet
```

## Trusted gates and remaining external proof

The read/search-only reviewer profile and all skills/policy/manifest inputs are
read from immutable **trusted-base Git objects**. Candidate files/instructions
are separately labeled untrusted data; no candidate checkout/script is executed
by trusted-duty workflows. Staging is complete, digest-bound and bounded; excess
files/bytes fail rather than dropping cases. A staged JSON object is not a verdict.

The authority reconciles real PR/check/run/review facts, exact Android Actions
check-suite membership, isolated publisher binding/run/attempt external IDs,
current/historical base, source/manifest/policy and any actual bound human
acceptance records. A rebased head/base, wrong publisher/workflow/repository/attempt,
skipped check, malformed verdict, absent original-case family or model-written
approval cannot complete a WP. Repairs retain the existing worker/open PR and
bounded attempt counter; a cloud comment receipt must match that PR and feedback.

Publisher encoding retains the **complete** bundle/catalog and rejects summary
or text above 65,535 characters **or UTF-8 bytes before publication**. No unchecked
`details_url`, partial catalog or truncation escape exists. Large future catalogs
require a protected immutable-artifact reader/publisher amendment.
[Historical revalidation](historical-revalidation.md) is a versioned **blocked
migration interface**, not a fabricated bootstrap Android check or import.

Still external: authenticated isolated publisher/reviewer/native host,
complete live usage/shared ledger, genuine branch-rule/check identities,
protected historical receipt approval, user-selected limits and separate
activation. Ordinary dependent drafts are not a registered native stack;
later native-stack landing requires its asynchronous native API, not
`MergeBackend`. No settings, auto-merge, stack registration, fleet, signing,
release or downstream WP is authorized by this implementation.

### User-directed WP-003 review-authority amendment

This amendment supersedes the repository-created separate-person/formal GitHub
`APPROVED` requirement for code review, including the code-review portions of the
supervised foundation gates and ADR-006's protected-path approval wording. It does
not rewrite original plan/ADR acceptance history or activate automation.

The authenticated trusted publisher's exact-bound read-only review is the code
review authority for ordinary and protected code changes. An authenticated
authorized coordinator may arrange the same isolated review even when the
coordinator is the PR author. A name alone, candidate-provided PASS, staged input
file, or locally constructed bundle is not authority. Findings, complete
acceptance/case validation, actual required checks, publisher provenance and
exact repository/base/head/source/manifest/policy bindings remain mandatory.
Current-head changes requested by an authorized reviewer still block.

`review_policy` separates this code review from retained human acceptance.
WP-001's architecture/license decisions, WP-505's physical-device outcomes,
WP-506's signing/release acceptance, protected release/signing/legal paths and
explicit `needs-human` labels still require actual human acceptance. Generic
protected code paths do not require an additional person's GitHub review.
Ordinary unprotected WPs acquire no new approval requirement. Activation,
budgets, credentials, branch rules, hardware/legal/signing evidence and historical
receipt import keep their existing separate controls.

A new acceptance record uses `kind: human-acceptance` with the existing immutable
binding fields and a real maintainer review in `COMMENTED` or `APPROVED` state.
Its actual GitHub body must contain
`<!-- android-port-acceptance:<binding.key> -->`; reviewer, commit, state and
latest decisive review must match server facts. The sole maintainer may record
a truthful acceptance comment on their own PR; this is not a GitHub self-approval.
It cannot manufacture hardware/legal/signing outcomes or waive acceptance
evidence. Historical untyped `APPROVED` records and their original stamps remain
readable, unchanged. Neither format can substitute for authenticated code review.

### Native authenticated review publication (bounded WP-003 repair)

The manual path no longer requires a custom GitHub App, injected Copilot app-host
APIs, a second human, or a formal GitHub `APPROVED` review. An authorized coordinator
performs/arranges the trusted-profile read/search-only review and explicitly records
its complete acceptance decision as an authenticated `COMMENTED` PR review. This
is an accountable coordinator attestation, **not a claim that GitHub independently
measured the reviewer's tool isolation**. The producer must not mint their own
trusted verdict. Plain source feedback, a green build, PR prose and staged input
are not acceptance. Existing comments on feature PRs remain source feedback only.

`android-parity-review.yml` is manual, default-branch-only, ephemeral, serialized
with the controller lane, and uses GitHub's built-in ephemeral Actions token.
It has read-only contents/actions/PR access and **Checks write only**. No custom
credential, signing/dispatch/merge secret, privileged cache or new service is
installed. Candidate jobs remain entirely read-only. The publisher fetches candidate
Git objects as data, never checks out or executes candidate scripts/instructions.
All publishing code/profile/skills/manifest/policy come from the exact deployed
default-branch SHA. A feature-branch dispatch is not an installation or acceptance
test. Check output contains only receipt/binding/run references, not a second
CI-result bundle or copied original-case catalog.

Before the review, fetch the immutable candidate into the reviewer's own worktree,
then use the existing staging command with the actual WP and current base/head:

```powershell
python .\tools\android-port\controller\gate_runtime.py stage --wp WP-xxx --trusted-base <40-hex-base> --candidate <40-hex-head> --output <private-staged-input.json>
```

The review body must start exactly with
`<!-- android-port-native-review-v1 -->` followed by a newline and one JSON object:

```json
{
  "schema_version": 1,
  "binding": "<the complete staged binding object, not this placeholder>",
  "method": "authenticated-coordinator-read-only",
  "staging_sha256": "<controller.schema.digest of the complete staged JSON>",
  "review": "<the existing complete parity-review schema object>",
  "evidence": "<hosted acceptance schema v2 object described below>",
  "approvals": []
}
```

The placeholders above are explanatory, invalid inputs, not a ready-made PASS.
Record a real review using GitHub's supported REST API
`POST /repos/{owner}/{repo}/pulls/{number}/reviews` with `event: COMMENT`,
`commit_id` equal to the exact candidate head, and that body. Do not automate
fabrication of the verdict. The authenticated reviewer must be a human GitHub
`User` in the trusted policy's `authorized_coordinators`; the coordinator can also
be the PR author. The receipt must fit the complete 65,535-byte/character bound.
Oversized receipts block; there is no unverified attachment or truncation fallback.

Hosted acceptance schema v2 has `schema_version: 2`, all seven binding fields,
`changed_paths`, `acceptance` and `source_tests` with their existing complete
schemas, plus:

```json
{
  "runs": [{"check": "android-ci", "run_id": 123, "run_attempt": 1}],
  "retained_artifacts": []
}
```

Those numbers are shape examples, **not evidence**. Every acceptance/case evidence
reference must name the actual authoritative URL
`https://github.com/<repository>/actions/runs/<run_id>/attempts/<attempt>` or a
retained non-reproducible evidence file `{path, sha256}`. CI counts, results,
checksums and output copies are deliberately not accepted in this run-reference
schema. The actual CI implementation still fails on missing/malformed/zero/skipped/
failed tests. The publisher reconciles successful Actions run and latest Android
check, workflow path/ID, Actions application/check-suite, PR and exact base/head.
Original cases/families are validated against `docs/android/test-cases.json` read
from the trusted base, not a catalog supplied by the producer. Retained evidence
hashes are compared with actual candidate Git blobs; they do not manufacture
physical-device, legal, signing or human outcomes.

Conditional human acceptance remains separate: retain actual bound maintainer
reviews and the `android-port-acceptance:<binding.key>` stamp in `approvals`.
Architecture/license, hardware, signing/release and `needs-human` still block
without their genuine acceptance. Unresolved inline threads, current formal
changes-requested reviews, or later authorized source feedback invalidate the
source receipt. The publisher evaluates twice before success; subsequent edits,
new findings, reruns and base/head movement are rejected again by the authority
when acceptance/completion/merge is read. Renames retain both path identities.

A coordinator can inspect without any write operation using their existing
authenticated GitHub user credential in `ANDROID_PORT_GITHUB_TOKEN`:

```powershell
python .\tools\android-port\controller\native_review.py inspect --trusted-base <base> --wp WP-xxx --pr <number> --review-id <actual-review-id>
```

After **supervised review and merge of this repair**, dispatch the deployed workflow:

```powershell
gh workflow run android-parity-review.yml --ref main -f work_package=WP-xxx -f pull_request=<number> -f review_id=<actual-review-id>
```

Only this workflow may publish `parity-review` and `gate-integrity`; local
`publish` fails before mutation. Publication creates in-progress checks first and
records failures explicitly. Interrupted publication remains pending/failed, never
success. A successful check is authoritative only after its real default-branch
publisher run also completes successfully. App IDs/workflow IDs are obtained from
authenticated GitHub server facts, never invented or taken from candidate JSON.
Legacy custom-publisher bundles remain readable when that publisher is configured.
`GitHubGateAuthority` and the existing completion/accounting path revalidate native
receipts rather than bypassing them with a new success flag.

**Project acceptance is not GitHub enforcement, producer completion or permission
to merge.** A source-reviewed open PR cannot complete a dependency. Current-base
serialization, paused/off activation, budgets, actual worker reconciliation,
protected historical foundation import and branch-rule/no-bypass proof still gate
the existing landing controller. This repair does not merge anything, change
repository settings, install credentials, clear failed feature CI, or enable a
schedule/fleet. GitHub supports an authenticated author's normal merge without
formal self-approval, but an admin merge is not evidence that project gates or
no-bypass protection were satisfied. At dispatch, main had no legacy branch
protection (HTTP 404); branch/ruleset proof remains explicitly unverified.
