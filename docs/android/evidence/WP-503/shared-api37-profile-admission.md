# Bounded shared API37 profile admission

This supersedes the procedural CPU/IME blocker, not any historical observation or
acceptance verdict. The user explicitly authorizes bounded runner/profile support
for the already assigned missing API37 cases. No successful API31 case is replayed.

## Provenance and unchanged requirements

The old session `wp503-mainobserver-native.py` requests `-cores 2` and calls
`wp503-prepare-native.py --expected-cpus 2`. The latter accepts **2 or 4**, compares
the requested count with actual `getconf _NPROCESSORS_ONLN`, and labels that
observation `diagnosticProfileOnly=true`. This verifies launch/resource identity;
it is not an assertion in the original cases. The approved plan/manifest, these
case bodies and their shared assertion/harness consumers impose no two-CPU
behavioral precondition. Prior four-CPU comparison results remain separately
qualified diagnostic evidence; they are not credit for this new run.

The preparer unconditionally writes `show_ime_with_hard_keyboard=1` for its broad
fresh-device profile. The selected cases have the following narrower requirements:

| Missing selection | Actual input/IME requirement |
| --- | --- |
| Independent US comparator11 and supplemental tr/az2 | Production target-class invocation and actual Android ICU/API. No Activity, editor, keyboard, CPU-count assertion or timing window. |
| Diagnostics5 | Disconnected CLI has no active terminal input; other cases inspect noise-floor data/scroll and RX filter/status. None requests IME visibility. One original fixture resizes 360x600 and restores 360x900. |
| Nodes7 | Search uses actual semantic text replacement; nickname clicks its real editor and inputs text. All original focus, full-line, scroll, cancellation and target assertions remain. Neither asserts visible IME/insets. Record the actual unmodified IME setting; do not claim keyboard-open coverage. |
| Saved LoS endpoint1 | Actual named actions, role/name layouts, state and callbacks; no editable input or IME visibility assertion. |
| Cached-chat exact-offset/recreation1 | Native editor ACTION_FOCUS and ACTION_SET_TEXT, real saved draft, 12px then24px offset within0.5px, full observed visible-set, actual900/360 recreation. No keyboard-show call, visible-IME assertion or inset predicate. |

The **separate**, unselected
`realCachedChatImeKeepsNativeComposerVisibleAndBackPreservesDraft` explicitly
requires hidden-to-visible IME after a native click, accepting-text state, nonzero
IME inset, composer above the keyboard, and Back hiding it without losing the
draft/navigation. Do not conflate that method with cached-chat restoration. It is
not authorized for replay here. No selected missing case requires a guest setting
write; the new profile truthfully records **IME0**, not IME1 coverage.

The native harness still requires actual API37/font2/RTL/motion0. Every original
48dp, full-line/clipping/ink, callback/selection/status predicate and independent
collation vector remains unchanged. Service activation/restoration and native
window readiness retain1000ms, and instrumentation retains600s. Four CPUs never
extend or excuse those deadlines.

## Source-bound adapter and isolation

`android/accessibility-tests/shared_device_runner.py` admits only existing normal
SDK adb, localhost5037, authorized `emulator-5556`, actual Pixel10/API37/boot1/
bootanim-stopped/4CPUs, actual360x900/mdpi160/font2/motion0/IME0. It never starts,
restarts or stops ADB/emulators; changes auth/keys/settings; clears shared logcat;
or launches an external chooser. It acquires the existing exclusive
`native-execution` hard lock and rejects another actual instrumentation consumer.

Reuse verified retained final composite **3f39a777376e322e2bc533f1d1caf92a87a44ed8**,
Android tree **7a9dd08e005e1e9cad79405dafecf325158d8b0c**, and its original App/Test
APK hashes. Check immutable fixture/product continuity, actual installed APK
digests and runtime profile before each cohort. This is explicit immutable
execution-snapshot binding, **not bypassing current-source acceptance**: the
published owned-only production tree is intentionally different and receives no
native credit from the composite.

Only missing US11/traz2/diagnostics5/Nodes7/LoS1/chat1 are selected, in separate
invocations with exact start/outcome identities; zero, duplicate or mismatched
discovery fails. No new assertions, oracle changes, successful cohort replay or
combined full-suite verdict. The separate historical Nodes search/favorite
negative remains distinct.

Preserve any pre-existing target APK before temporary same-version `install -r`,
verify its bytes and the unchanged exported Room schema identity, never wipe
target data, and restore/read back the original target APK in cleanup. Refuse to
overwrite any existing test package; remove only this adapter's newly installed
test package. Verify existing and candidate App/Test public signing certificates
**before installing**; a mismatch requires a compatible source-bound artifact,
not key access, re-signing, uninstall or a security bypass. Fixture-created data and original temporary window changes retain
their existing cleanup. Surface primary and cleanup failures together.

No CPU changes or IME setting changes are admitted or needed by this adapter.
If a future separately authorized original visible-keyboard case genuinely needs
IME1, snapshot the exact secure key value/presence on this verified endpoint under
the same exclusive lane, set only that key, and in `finally` compare-and-restore
its actual previous value (or delete only this originally absent key), then read
back restoration. Report a concurrent change rather than overwriting it.
That procedure is not executed or validated by this missing-only run.

## Commands

Adapter-only discovery tests (not Android acceptance):

```powershell
python -m unittest discover -s android\accessibility-tests -p test_shared_device_runner.py
```

Read-only immutable source/APK/selector check, with retained APK directory:

```powershell
python android\accessibility-tests\shared_device_runner.py --apks <retained-apks> --output <new-owned-capture-directory>
```

The same command with `--execute` runs only the admitted missing27 once. The
output directory must be new; retained positive captures cannot be overwritten.
Results, profile and cleanup must be observed, not inferred from this admission.
WP503 acceptance remains unconfigured; physical TalkBack and protected/human
gates remain separate. Exact f75 hosted800/4FAILED and its two unavailable failure
identities remain unchanged, not rerun to repair missing hosted evidence.

## Actual attempted execution

Committed adapter17eb attempted this admitted profile once. Resource/readiness,
immutable source/fixture/APK and exclusive-lane checks passed. Android
PackageManager refused the **first App install**, before any instrumentation:
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` for the existing target's different signing
identity. The exact error and original observation are retained in
[device-shared37-integration.json](device-shared37-integration.json); this is
one setup failure, not27 failed/skipped tests. All six cohorts have0 actual
discovery/start/outcome/pass/fail/error/skip.

Read-only APK signature verification identifies existing public certificate
`000be798aca5806230ec26d9feee36cbd0acadc857cf22a4ba711f43a4a2c8b7`
versus retained App/Test
`891462f47d1f534cfcc0e755f7a938510282d35de2ff933542b643f338a9f21d`.
The original target APK SHA256
`6d98b22de0d99a0e3a017f579bc068011bcba34c0ab527392cc63bfb1b613748`
and complete original profile were read back unchanged; no target replacement,
test-package installation, settings/key/auth/data/lifecycle action or native
test occurred. The native lock was released. A follow-up adapter correction
checks those public certificates before installation and recognizes the actual
streamed-install success format;5 adapter unit tests pass, not Android tests.

The actionable provider requirement is an unchanged-source/fixture-bound
compatible debug App+Test pair for that existing public identity, through an
authorized native build/artifact capability. External signing-key access,
re-signing retained artifacts, user-app uninstall/wipe and a new emulator are
not admitted by this WP. This is a concrete install-identity blocker, **not**
the obsolete two-CPU/IME/default-device-authorization blocker.

The coordinator subsequently confirms the user's approval covers **ordinary
standard Gradle DEBUG packaging with the already-configured local identity**.
That is not release signing. It must build a new truthful source/toolchain/App/
Test binding, not silently re-sign or reuse the old c2b/af38 hash claims.
No agent may read/export/copy private key bytes or change the existing key.

The existing typed `app-build-launcher` rule covers `android/app/**` and
manifest/container wiring, with operations `add-direct-support-wiring`,
`update-build-or-manifest`, `update-launcher-or-composition`. It does not admit
the required supporting adapter operation. Actual `CapabilityEngine.admit` for
WP503, proposed support path `tools/android-port/local/debug_packaging.py`,
operation `build-debug-test-apks-with-existing-local-identity` returns:

```text
Capability admission blocked: WP WP-503, path tools/android-port/local/debug_packaging.py, operation build-debug-test-apks-with-existing-local-identity; no trusted path+operation rule
```

No external key operation was attempted. Existing WP003 owner06d49 is receiving
this bounded support amendment through the coordinator. Its trusted adapter
should constrain existing-key consumption to standard debug Gradle assembly
without private-key export/copy/change, require the public certificate to match
installed000be798, retain exact original3f source/fixture/Room/package continuity,
and produce new source/build/APK bindings. Unknown support admission, not a
broad release-signing/unauthorized-device claim, is the current executable gate.

## Pre-execution source-review safety corrections

The coordinator withheld source review of dd259 pending three bounded adapter
corrections. These alter no Kotlin fixture, product, threshold or old capture:

- Persist each install attempt **before** calling the installer. Cleanup
  reconciles actual target/test presence and digests, restoring the preserved
  target even after successful installation followed by timeout/rejected output,
  and removing only this run's originally absent, exact-bound test package.
  Foreign package identities are never overwritten or removed.
- Require one actual terminal `INSTRUMENTATION_CODE: -1`, result stream/time and
  matching JUnit success summary for all-positive outcomes. Complete method
  statuses followed by runner crash/failure/shortMsg or missing completion fail.
- A timed-out host client is not proof the guest finished. Before restoring any
  APK, finish only this empty-before-run lane's exact owned instrumentation,
  with matching target/test digests, and verify actual guest quiescence. Never
  stop a foreign instrumentation component. If quiescence cannot be established,
  retain the exclusive native lane and surface both primary and cleanup failures
  rather than replacing APKs or releasing the lane under an active guest. Also
  retain the lane if the preserved target/test package state is unresolved.

Adapter failure-injection tests cover uncertain App and partial Test installs,
foreign packages/instrumentation, terminal accounting, successful owned-guest
cleanup after host timeout, and retained-lane primary-plus-cleanup failures.
These are host unit tests only. No API31 replay, additional API37 install,
instrumentation, key operation or successful cohort replay accompanies this
source correction. The original first-install failure and unchanged-user-target
observation remain immutable.

Validation: `python -m unittest discover -s android\accessibility-tests -p
test_shared_device_runner.py` discovers **14 tests,14PASS/0FAIL/0ERROR/0SKIP**.
Offline parsing of the existing final LoS1 and Nodes7 reports accepts their
actual terminal `INSTRUMENTATION_CODE: -1`; this is parser compatibility only,
not a device replay or new native credit. Controller validator is valid and
`git diff --check` passes. Independent source review is still required.

### Subsequent application-state cleanup finding

This subsection preserves the original worker's unexecuted preparation state.
The replacement-worker verification below supersedes its host-test/tool-outage
status, not any original device observation.

The coordinator's follow-up review identifies that force-stopping owned guest
instrumentation can skip the selected chat fixture's Kotlin `finally`, leaving
fixture device/contact/message rows, onboarding/draft preferences or a resized
window behind. Restoring the APK and matching package hashes cannot prove those
user-state changes were undone.

The subsequent **local, not yet validated or published** correction marks an
incomplete/force-terminated invocation as uncertain application state, writes a
source/cohort/endpoint-bound recovery receipt before stopping the owned guest,
and retains the native lane even if the guest stops and both APK identities are
restored. A receipt does not invent original preference/row snapshots: it
explicitly records their unavailability. Only an independently verified,
supported exact-fixture/preferences/window recovery using genuine original state
can resolve the lane; no generic data wipe or foreign-record deletion is added.
The adapter contains no automatic recovery or success-shaped override for this
state.

Each native lock acquisition also uses an operation-unique owner token while
retaining the canonical session identity in its payload/receipt. A subsequent
invocation of this same worker cannot use the ledger's same-owner re-entry to
overwrite a prior retained uncertain-state lane. The corresponding collision
regression is added locally, also unexecuted under the tool outage.

A revised host failure-injection test seeds chat rows, changed draft/onboarding
preferences and a resized window, times out the guest, and asserts that APK
restoration still leaves those fixture effects uncertain with a recovery receipt
and retained lane. These new edits **have not been executed**: current process-tool
startup probes time out for both `pwsh.exe` and `powershell.exe` before the requested
command runs. The previous14-test result belongs to published7a5, not this local
correction. No additional native/install/key/settings/user-data/lifecycle
operation or APK result is claimed.

Further source-only review corrections isolate receipt I/O from bounded owned
host/guest termination attempts, aggregate both failure paths, and establish
in-memory uncertainty before instrumentation spawn. Transcript read/decode errors
and pre-proof interruptions can no longer rely on an already-quiescent guest to
release the lane. Only normal all-positive terminal proof without forced stop
clears this conservative state. New receipt-write and transcript-read failure
injections remain unexecuted.

The native lane now includes `payload.wp=WP-503` alongside the unchanged
canonical identity and unique operation owner. The coordinator identifies this
as required by proposed PR243 standard-debug support. Any future deployed
`assemble_debug_pair` call must receive `device.native_lane_owner` under that
same held lane, not canonical `OWNER` or a second lock. This change imports or
executes no undeployed adapter; its metadata regression is also unexecuted.

### Replacement-worker runner verification

The authorized replacement recovered the two actual runner/test files and
three modified WP503 narratives byte-for-byte from the stopped worker's
read-only checkout at `7a5caa7b9adc208234fd97c4a6958c3192852dd2`.
The ordered source-only snapshots remain preserved; actual working bytes,
not an assumed apply-ready patch, supplied recovery. The original checkout
and all original non-reproducible evidence remain unchanged.

Trusted policy was refreshed from main
`574d440b5b05ba6609f60d7ba0913c2d8bdaea0d`; its source pin is still
`db14559b39d32322b06477c6ae676112f583db50`. The branch's older publication
policy differs, so capability admission used the refreshed trusted policy.
The controller capability/transfer implementation matches that trusted base.
Typed `CapabilityEngine.transfer_intent` receipts bind each recovered path,
the canonical origin `1666502a-0420-46ef-930f-e78651ba8b9f` and replacement
app session `df52b3d0-81d4-49fb-b88f-a5926ed31fa5` (CLI
`b531305a-0513-4731-896f-2b470f8879cd`). Canonical claim revision8,
identity, bindings, authorization prose and history were not rewritten or
released/reacquired. The attributed handoff and byte hashes are retained in
the replacement session's `files/wp503-replacement-handoff.json`, not a
duplicate hosted-CI result bundle.

Direct `python.exe` execution in the replacement's isolated worktree succeeds
through a documented session-local Node extension with `shell:false`, despite
unavailable PowerShell. The recovered suite first passed18 tests. Expanded
positive and failure-path coverage then passed **23 tests, zero failures,
errors or skips** using the existing focused command:

```powershell
python -m unittest discover -s android\accessibility-tests -p test_shared_device_runner.py -v
python tools\android-port\controller\validate.py
git diff --check
```

The validator reports `valid` (64 WPs,178 dependency edges); whitespace
validation exits0. The23 host tests include operation-owner UUID shape,
retained-owner collision, receipt-write failure with independent bounded
host/verified-owned guest stop, host-kill failure with subsequent bounded wait,
transcript read/decode errors, spawn/pre-proof interruption, nonpositive or
forced completion, prior-uncertainty rejection, and normal all-positive
completion releasing only the actual operation owner.

These are Python host regressions, not API37 results, Android compilation,
physical TalkBack, signing compatibility or whole-WP acceptance. No successful
native cohort was replayed, no current endpoint admission was inferred from
the historical5037/emulator-5556 record, and no device/package/key/user-data
operation occurred. Missing-only27 still requires a genuine trusted compatible
exact-source App/Test pair and admitted native-host capability. Packaging
adapter/controller changes remain with their WP003 producer.

### Current-main integration after runner publication

The replacement reconciled exact current main
`574d440b5b05ba6609f60d7ba0913c2d8bdaea0d` into published runner successor
`ff80c2751ab88ed28024a0ebe03641910502c538` through merge commit
`1e4c4acbd35343d13cc7af3115e7e41da15c3efd`. All31 incoming paths are
existing WP003 controller/policy/workflow changes; no producer product repair
or unmerged packaging adapter is imported. Git's automatic merge found no
conflict. The Android tree remains
`34eb9408b3ce052fefe6c7fcfc5db4c069b64923`; all runner, fixture and historical
native evidence bytes are unchanged by that merge.

Supported `Ledger.evolve_scope` admitted the trusted `merge-integration`
operations `reconcile-current-base` and `merge-disjoint-semantic-edits`,
advancing canonical reservation revision8 to9. Actual canonical identity,
attempt, source/base/policy bindings, immutable authorization, branch/worktree
audit context and repair history remain unchanged. The replacement's actual
app/CLI identities remain separately attributed, not substituted for the
canonical identity. Incoming paths, original producing commits and exact
revision transition are retained in the replacement session's
`files/wp503-current-main-integration.json`; no raw ledger mutation,
release/reacquire or fabricated execution identity was used.

The first controller test attempt ran before the merge commit and correctly
rejected two newly required runtime inputs absent from the then-current
committed HEAD (325 discovered,320 passed,3 failures,2 errors). After committing
the complete merge, the same declared commands pass without changing any
controller test, fixture or assertion:

| Exact command | Result on committed integration |
| --- | --- |
| `python -m unittest discover -s android\accessibility-tests -p test_shared_device_runner.py -v` | 23 discovered/23 passed;0 failures/errors/skips |
| `python tools\android-port\controller\test_runner.py --quiet` | 325 discovered/325 passed;0 failures/errors/skips |
| `python tools\android-port\controller\verification_config.py --check` | PASS, exit0 |
| `python tools\android-port\controller\validate.py` | PASS, exit0 |
| `python tools\android-port\controller\workflows.py` | PASS, exit0 |
| `python tools\android-port\portmap.py` | PASS, exit0 |
| `git diff --check` and `git diff --cached --check` | PASS, exit0 |

These scoped host/controller checks are not a local Android build, API37
execution, native acceptance or a hosted successor verdict. The automatic
run39367961680 remains bound only to ff80; discover the new successor's
automatic run rather than dispatching another or copying a result bundle.
Native packaging/admission and all protected/acceptance gates remain separate.

### Integration after producer PR233 and PR243 merged

PR233 (`81d8919ad4bfd0a0985acf79449c661a9281ecce`) and PR243
(`69e7b850c7cda8ecf28e8766765bc8090ae72753`) merged on2026-10-20.
Main subsequently advanced through merged PR227/235/239 to
`7ef75f0d6c936e1ad56358124f3a12d11862bd32`. The replacement integrated
that exact current main in `02f2275822aaf9b40e2dce12cd4e2b21527f6f4a`,
retaining each producer's commits rather than duplicating product fixes.
The resulting Android tree is `9689a3edf30974594e0c13bb62774e94846aac39`.
Historical native outcomes remain bound to their original composites,
fixtures and APKs; this integration transfers no native credit.

The trusted current-main `CapabilityEngine` was staged from immutable Git
objects because its WP-scoped packaging-rule validation differs from the
older branch. Supported canonical `Ledger.evolve_scope` advanced revision9
to10 for51 admitted incoming paths. Canonical identity, bindings, authorization
and repair history remain unchanged; actual replacement attribution remains
separate. The session receipt `files/wp503-producer-merge-integration.json`
retains paths, revisions, original producer commits and reconciliation details.

Multiple historical merge bases exposed the old publication's producer-copy
reverts: LoS screen/deviation conflicts and a silently omitted LoS workflow
test. All three prior WP503 path values were verified identical to prior
main574d440, with no active WP503 intent. Reconciliation restores the exact
merged WP315 producer bytes, including its saved-repeater selection test;
no new product implementation or assertion change was made. Every original
WP503 fixture, runner and historical native evidence file is unchanged.

The machine reboot restored ordinary PowerShell execution, independently
proved in this worktree. Subsequent checks use that supported CLI, not the
historical unavailable-shell assumption:

| Exact command | Result on committed producer integration |
| --- | --- |
| `python -m unittest discover -s android\accessibility-tests -p test_shared_device_runner.py -v` | 23 discovered/23 passed;0 failures/errors/skips |
| `python tools\android-port\controller\test_runner.py --quiet` | 352 discovered/352 passed;0 failures/errors/skips |
| `python tools\android-port\controller\verification_config.py --check` | PASS, exit0 |
| `python tools\android-port\controller\validate.py` | PASS, exit0 |
| `python tools\android-port\controller\workflows.py` | PASS, exit0 |
| `python tools\android-port\portmap.py` | PASS, exit0 |
| `git diff --check` and `git diff --cached --check` | PASS, exit0 |

The exact5062 hosted failure remains historical: run39369093064 reports
App800/4FAILED; full original hosted log exposes the two known Nodes headings
only, with two other identities unavailable. Its verdict does not transfer.
The merged producer and improved diagnostics now permit an independently
classified successor through automatic hosted CI; no blind rerun, duplicate
dispatch or second CI-result bundle is created. Native host admission,
packaging execution, install/signing, settings and all acceptance gates remain
separate, with no such actions performed during integration.

### Integration after producer PR206, PR229 and PR232 merged

The automatic exact-head [run39438223240](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39438223240)
passed for `932fa8c090d735bf54f7a8e95318357904dbfd64`. Its hosted
job results and logs remain authoritative; no second result bundle is retained.
While that run executed, merged PR206/229/232 advanced main to
`0183a2b206b9ba655d7fea5c281b8bfbfecf8847`. That later source is
integrated separately in `1bff15e942d6743f7dade66c745ab5eb25eaf53e`,
with Android tree `e5600983e1228b6194a9d6d246f4f0873c07ed43`.
The earlier passing result does not verify this new candidate.

Supported canonical scope evolution advanced revision10 to11 for26 incoming
paths using the same merge-integration operations, without changing identity,
authorization, historical bindings or repair history. The separately attributed
replacement receipt `files/wp503-latest-main-integration.json` records the
actual paths and producer commits.

The App source-set conflict was disjoint: retain both the original WP503
accessibility registrations and merged WP502 flow/screenshot registrations.
Removing only the unchanged six-line WP503 registration from the resolved App
build file produces exact current-main bytes. Historical virtual-base reverts
also conflicted on WP312 narratives and silently omitted the Map List repair.
All three previous path values equal prior main7ef75f0; restore their exact
merged producer bytes rather than inventing another repair. Every other
incoming path equals current main, and the Android delta from current main is
confined to owned accessibility files and the existing App registration.
All original WP503 fixtures, runner and native evidence bytes are preserved.

The declared checks on the committed integration pass:

| Exact command | Result |
| --- | --- |
| `python -m unittest discover -s android\accessibility-tests -p test_shared_device_runner.py -v` | 23 discovered/23 passed;0 failures/errors/skips |
| `python tools\android-port\controller\test_runner.py --quiet` | 352 discovered/352 passed;0 failures/errors/skips |
| `python tools\android-port\controller\verification_config.py --check` | PASS, exit0 |
| `python tools\android-port\controller\validate.py` | PASS, exit0 |
| `python tools\android-port\controller\workflows.py` | PASS, exit0 |
| `python tools\android-port\portmap.py` | PASS, exit0 |
| `git diff --check` and `git diff --cached --check` | PASS, exit0 |

Publication remains a normal fast-forward to PR205, with a new automatic
exact-head hosted result required; no duplicate dispatch or successful native
cohort replay. Native packaging/admission, missing-only27, physical and
protected acceptance gates remain separate. The already merged QR Copy repair
does not close issue237: its exact-published-repair API31+37 re-audit contract
still requires actual admitted evidence, not the historical fd9 API31 result.

### Integration after versioned software amendment PR246 merged

Merged producer PR246 advances exact main to
`9c430cb0f1666faacc443794899b68c01128a87a`. The replacement reconciled
that main in `c3bc617b9571b8e1cecb479ec41fccce7eb8166e`, retaining the
producer's software-command amendment, Room evidence provenance correction
and Swift oracle phase/timeout diagnostics unchanged. All15 incoming paths
equal exact main. No Android product, audit fixture, runner or original native
evidence byte changes; Android tree remains
`e5600983e1228b6194a9d6d246f4f0873c07ed43`.

Supported scope evolution advances canonical revision11 to12 with the same
merge-integration operations. Identity, historical binding, authorization
context, branch/worktree audit context and repairs remain unchanged.
`files/wp503-software-main-integration.json` records the actual replacement
attribution and admitted paths; no lease release/recreation or raw ledger
rewrite occurs.

The actual merged controller's `current_revisions` and software-amendment
`project` APIs validate the new exact catalog and its historical preimage:

- Amendment: `WP-003-software-verification-v1`.
- Current manifest:
  `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Current policy revision:
  `7f757bb6dbde98ab8af6da189f6e9273d4bc45ddb8e903703bee8b43c9b372f6`.
- Exact projected historical manifest:
  `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda`.

The WP503 entry itself is unchanged. The amendment declares existing WP502
and WP504 software commands, not full verification or native acceptance.
Historical canonical bindings are preserved rather than rebound to the new
manifest or policy. Frozen Swift remains
`db14559b39d32322b06477c6ae676112f583db50`.

Declared checks on the committed integration:

| Exact command | Result |
| --- | --- |
| `python -m unittest discover -s android\accessibility-tests -p test_shared_device_runner.py -v` | 23 discovered/23 passed;0 failures/errors/skips |
| `python tools\android-port\controller\test_runner.py --quiet` | 358 discovered/358 passed;0 failures/errors/skips |
| `python tools\android-port\controller\verification_config.py --check` | PASS, exit0 |
| `python tools\android-port\controller\validate.py` | PASS, exit0 |
| `python tools\android-port\controller\workflows.py` | PASS, exit0 |
| `python tools\android-port\portmap.py` | PASS, exit0 |
| `git diff --check` and `git diff --cached --check` | PASS, exit0 |

Prior automatic [run39444539184](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39444539184)
passed only for6e2d151c at0183a2b; neither unchanged Android bytes nor a
conflict-free merge transfers its verdict to this successor. Discover only
the automatic exact-candidate run; do not dispatch a duplicate or retain a
second CI-result bundle. Missing native admission/API37 observations, QR237
re-audit, protected review and acceptance remain separate. No native launch,
replay, install/signing, merge or issue closure is performed.

### Exact language repair source preparation for issue238

The current replacement owner prepared the genuine WP407 repair from published
PR247 head `59a47ac40bd2f84e7188c87a5521af9d0f16e75e` against exact
main `9c430cb0f1666faacc443794899b68c01128a87a`. At preparation,
PR247 is open, not merged. Its original commit and all four changed paths
are retained unchanged in combined source
`07bccfea10cbe8f48eaea164be625790333739c7`. This is an explicitly
attributed execution-source preparation, not producer merge approval or
native acceptance. No unpublished mixed tree is claimed as an accepted source.

Supported canonical scope evolution advances revision12 to13 under
`merge-integration` operations `transfer-current-producer` and
`merge-disjoint-semantic-edits`. Canonical identity, historical bindings,
authorization context and repairs remain unchanged, with actual replacement
attribution in `files/wp503-language-repair-preparation.json`. The retired
d2980f28 worker is not resumed and its old3f39 runner is not invoked.

Actual deployed `debug_packaging.source_identity` accepts this exact clean
committed Android source, and the trusted `CapabilityEngine` admits the
existing `debug-test-packaging` helper operation:

| Input | Exact binding |
| --- | --- |
| Combined Android Git tree | `087659de770f735d2f4715cd4e2320a08f3b9ba2` |
| Helper's Android source-tree SHA256 | `2b98f8cd80a43aa5857b5cb0f4cd348a9c4393ef82cda08b7d97faf7ca12ae1c` |
| `AppSettingsContent.kt` producer blob | `421fc446443a5f301085507603701d7da04d40ef` |
| Unchanged strict `NativeAppLanguageAccessibilityTest.kt` blob | `99175354ba29a9230b7a2b03a7c17ea4153dd394` |
| Selected native methods per API | Exactly2: naming/selection/contained-font200 actions, and Chinese/System lifecycle |

The fixture matches published d38d33e04 byte-for-byte; all other audit fixtures
and every historical native record are unchanged. The combined source contains
PR247's real selectable-row repair, unlike d38d33e04's old Android tree.
Final publication adds only this narrative, not Android changes. A future
helper invocation must bind its actual then-current full source revision;
no earlier source revision is substituted for that check.

All six previously declared scoped commands pass on the committed preparation:
runner23/23 and controller358/358, zero failures/errors/skips;
`verification_config.py --check`, `validate.py`, `workflows.py`, `portmap.py`
and both Git whitespace checks exit0. These are host/helper checks, not an
execution of the producer's new App tests or of the native language fixture.
The new publication requires its own automatic hosted verdict.

**Correction: the static integration diagnostic is not native admission.**
`gate_runtime.capabilities` returns exit2 and `authoritative=false`, and
unconditionally appends unproven native-host/live-usage/shared-ledger integration.
Its output does not evaluate the actual scoped Android-device boundary and
cannot establish a live rejection or justify activating the dispatch/merge
fleet. The policy pause and device-preservation guards remain applicable;
neither this diagnostic nor an empty native ledger grants permission to act.

The deployed `backends.NativeHost` provides session-dispatch callbacks
(`capabilities`, `reconcile`, `create_session`, `get_session`,
`send_session_message`, `usage`), not current-owner/source/device admission.
No deployed current-source language execution consumer or Android-device
admission provider was found. The precise missing host interface is
`admit_language_device(LanguageRequest)`, supplied by a trusted app host and
returning its guarded preservation/native-lane scope. This is a missing
interface/provider, not a request to enable global automation or rewrite
authorization. The old shared runner's historical source, retired alias,
endpoint and missing-only27 selection cannot substitute for it.

### Current-owner language binding and guard regressions

Supported same-owner scope evolution advanced canonical reservation revision13
to14, admitting `language_binding.py`, its tests and directly related audit
documentation. Canonical identity/history and original authorization remain
unchanged; current replacement app/CLI attribution is separate.

The pure adapter uses actual clean committed source through the deployed
debug-packaging source-identity helper. It binds the exact producer repair,
unchanged strict fixture, two real method identities in source order, Android
tree/digest, manifest/policy revisions, current reservation and a unique owner
per API31/37 request. Before calling an injected provider it rechecks current
inputs and the exact published PR205 head. It does not implement the provider,
enter its scope, acquire lanes, build/install, or start instrumentation.
Transcript accounting reuses the existing strict parser and never grants
acceptance, clears uncertainty or releases an actual lane. The historical
shared runner and native fixtures are unchanged.

Executed software verification:

```powershell
python -m unittest discover -s android\accessibility-tests -p test_*.py -v
python tools\android-port\controller\verification_config.py --check
python tools\android-port\controller\validate.py
python tools\android-port\controller\workflows.py
python tools\android-port\portmap.py
git diff --check
```

The suite discovers **45 tests:45PASS/0FAIL/0ERROR/0SKIP**, including14 new
language binding/guard/accounting tests. Each declared validator exits0;
whitespace validation passes. Provider fixtures/mocks are pure tests, not a
live host or admission evidence. Missing/JSON/session-dispatch providers,
stale claims/policy/source, unpublished inputs, changed fixture/method
inventory and nonpositive/forced completion fail closed.

No device probe, old endpoint reuse, key access, packaging build, install,
native start, profile write or replay occurred. Therefore no compatible
App/Test pair, installed public-certificate match, API31/API37 outcome or
fixture cleanup is claimed. Once genuinely admitted, use the existing local
debug identity and actual unique held operation owner, preserving the device
and comparing App/Test/installed public certificates before any separately
guarded replacement. Issue238 remains open; this affected2+2 selection is not
the separate API37 missing-only27 inventory or full successful-cohort replay.

### Reconciliation after the language producer and three peers merged

Current main `aae3b10dd78f3d3314f51291687932f42f7d0ac9` was reconciled into
published `61fca316d5aeeee18e88e85bb74252da60e32d15`. PR247's original
`59a47ac40bd2f84e7188c87a5521af9d0f16e75e` is now genuinely merged, alongside
PR234's region accessibility, PR245's synthetic benchmark reader and PR248's
notification-draft restoration. Earlier unmerged-source preparation remains
historical, not rewritten into merged or native acceptance.

Actual supported scope evolution advanced canonical revision14 to15 with
`merge-integration` reconciliation operations. All16 incoming producer paths
match actual main byte-for-byte. Identity, original authorization/bindings,
audit history, manifest/policy, strict language fixture `99175354ba29a9230b7a2b03a7c17ea4153dd394`
and product blob `421fc446443a5f301085507603701d7da04d40ef` remain unchanged.
The existing adapter, retired runner and all historical native evidence are
preserved. The attributed transaction receipt is retained in the replacement
session's `files/wp503-consolidated-main-integration.json`.

On the reconciled producer source, the declared audit command above again
passes **45/45**, and the merged producer's declared
`python .\tools\android-port\controller\test_runner.py --tests .\android\benchmark\tests`
passes **22/22**, both with zero failures/errors/skips. All four declared
validators and staged whitespace checks pass. Benchmark inputs are synthetic,
not device/performance evidence. No complete local Android build is claimed.
The preparation CLI must bind the resulting clean committed successor;
61f's binding and hosted run do not verify that successor.

This source consolidation does not supply the missing trusted
`admit_language_device(LanguageRequest)` provider or live lifecycle consumer.
No device, lane, build, key, install or native start is authorized or performed
by this reconciliation. The unchanged selection remains two methods per
API31/37, with no successful-cohort replay or acceptance/issue closure.

### WP502 mounted-test source dependency after PR244 merged

Main `680246f7914a5b4e5bf5167a916b620f83a79dee` was reconciled into published
`3f773d94dc03db22bfe70582a2fea0090f085557`. All nine incoming WP502 paths
(five mounted tests/four documents) match both actual main and original producer
`e317ee57e84061559a201838e30e9821d2ea5299` byte-for-byte. Supported same-owner
scope evolution advances revision15 to16, preserving immutable canonical
identity, authorization, bindings and history. Manifest/policy, original
language producer/product/strict fixture lineage, adapter and historical native
evidence remain unchanged. The transaction receipt is retained in
`files/wp503-wp502-main-integration.json` in the replacement session.

On the reconciled source, the declared audit suite again passes45/45 with zero
failures/errors/skips; all four declared validators and staged whitespace
checks pass. The mounted Kotlin changes require their own exact-successor
hosted verification; old3f/run39479575207/App847 and its Android-tree binding
remain historical. Recompute preparation on the clean committed successor.
The missing trusted Android-device admission provider/preservation/lane scope
remains a live capability blocker. No native action, replay or acceptance is
performed or inferred by this source reconciliation.

### WP305 localized exhaustion guidance after PR251 merged

Main `ec0a41e80ac8637baad524b777f774c10cf99f5b` was reconciled into published
`919a373acc235cf1a745008078acbf0610c127bb`. Both incoming paths (the preset
screen and WP305 note) match actual main and canonical producer
`54aaab70bc01725376f0af712dab0ca26c361a5b` exactly. Actual same-owner scope
evolution advances revision16 to17 while preserving immutable canonical
identity, authorization, bindings/history, policy/frozen pin and all original
native evidence. The receipt is retained in
`files/wp503-wp305-main-integration.json` in the replacement session.

The declared pure audit suite passes45/45 with zero failures/errors/skips;
all four validators and staged whitespace checks pass. Old919/run39486741165/
App865 is historical, not an ec0 verdict. Preparation must bind the clean
committed successor. The strict language fixture/product lineage and two
methods per API31/37 are unchanged. No native/provider authority is created,
and no device, lane, key, install, start or successful-cohort replay occurs.

### Direct-process language lifecycle replaces the invented provider dependency

The user-directed correction removes `admission_scope(None, ...)` and the
always-failing `--require-native-admission` placeholder. Earlier statements
above describing a missing `admit_language_device` provider as an external host
prerequisite were an implementation mistake, not a discovered vendor API or
required installation. The separate extension/bridge proposal is superseded;
no such source, settings, policy dependency or actor is introduced.

Supported `Ledger.evolve_scope` advances the same canonical reservation17 to18
for the current-source consumer and tests, shared helper extraction, binding
correction and directly related documentation. Immutable identity, original
authorization/bindings, branch/worktree context and repair history are verified
unchanged. The generated transaction receipt remains in the replacement
session's `files/wp503-direct-language-consumer.json`.

`language_runner.py` consumes the actual process boundary and deployed
`assemble_debug_pair`. It passes the unique held `canonical:language:UUID`
operation owner, preserves the original target, verifies current source and
public APK hashes/certificates, and reuses the existing installer, strict
discovery, uncertainty, bounded host/guest cleanup and restoration helpers.
It observes an explicit current transport/API/profile and System app locale,
including the real API31 preference/API37 LocaleManager and pending-migration
contracts. It never resets user locale or changes fixture/product bytes.
Cancellation kills/waits independently; receipt/metrics/proof failures retain
uncertainty. Unresolved original profile or package state retains the lane.
The historical shared runner's HEAD/tree/actor/default selectors remain
historical and are not used as current-source inputs.

The declared pure command
`python -m unittest discover -s android\accessibility-tests -p test_*.py -v`
passes **67 discovered/67 passed/0 failed/0 errors/0 skipped**, including20 new
synthetic direct-process lifecycle tests, the existing23 shared-runner safety
tests,16 binding/accounting tests and8 cleanup tests. The first63-test attempt
had one synthetic CLI fixture error (a Git lookup intercepted by the mocked
process); that test isolation was corrected, not a native assertion changed.
The final command covers actual API31/37 argument selection, current owner/
source, pause and occupied lanes, explicit endpoint/configuration, original
locale/storage preconditions, packaging/schema/byte failures, partial install,
receipt failure, cancellation, timeout, positive discovery and retained lanes.
The four existing validators and whitespace checks pass; no local Android
build or device outcome is claimed.

The real readiness mode checks source/configuration without device probing.
The final source hardening verifies actual current main and rejects candidate/
worktree policy or manifest changes, consuming the immutable trusted-main
policy object rather than a candidate pause override. Two added regressions
cover that boundary. Initial published13ac17261 passed65 tests; the final
source successor includes these two additional checks without rewriting it.
Execution mode still enforces the actual repository pause, authentication,
held-lane, preservation and current-source guards; the removed method does not
grant authority. This continuation performs no device probe, lane acquisition,
key access, packaging build, install or native start. Its exact clean committed
successor requires a fresh source binding and automatic hosted verdict.
Published275a0596c/run39495005720 succeeded (App865/865, all7 head-bound checks);
that historical result does not verify this implementation successor.
Issue238 and the full audit remain open; two methods per API31/37 are separate
from missing-only27 and from whole-WP acceptance.

### Authorized current-main execution and real preflight corrections

Reviewed PR254 is actually merged as main
`5714ba3be50618b8d946a21e0db15feb67f90eb6`: `paused=false`,
`dispatch_mode=off`, `activation_approved=false`. Reconciliation through the
same owner's supported scope API preserved the original identity/context.
The packaging operation was separately admitted by the existing trusted
`debug-test-packaging` rule, advancing19 to20. The five incoming policy/producer
paths were already reserved, so their later reconciliation correctly retained
actual revision20 rather than inventing another revision. Published
`ca4e4039e61870539f488e9ff46b12dabe0e1e2b` contains the actual reviewed main;
no local pause override or fleet activation occurred.

Fresh authenticated observations found API31 on `emulator-5554` genuinely
without the target or test package, with unset font/animator settings, IME1 and
physical1080x2400/density420. API37 on `emulator-5556` retained its target and
compact font200/IME0/motion0 profile. Actual attempted current-source execution
stopped before packaging/install/instrumentation: API31 exposed the unset-font
precondition; API37 returned `Locales for ... for user -2 are []` for the public
`--user current` argument. Each pre-mutation lane was released after verified
quiescence. These are real negative preflight observations, not native passes
or a missing provider/installation prerequisite.

The consumer resolves and binds actual owner user0 before private/package access
on both APIs and rejects a later user switch through cleanup. LocaleManager's
response must match that numeric user exactly; non-owner users are rejected,
not advertised as multi-user support. Present packages retain original APK
identity/data. Genuine absence is additionally checked against retained package
registration/data; unknown/failed probes never become absence. The explicit
absence packaging path must supply current-built verified App/Test identities,
not a fabricated original APK. Only those exact newly introduced packages may
be removed to restore original absence.

Temporary font/IME/motion and display preparation records original values and
each attempted mutation before writing. Missing settings are confirmed absent
by their named inventory entry and restored with deletion, not a synthesized
1.0. Original absent display overrides use reset. Restoration independently
attempts each recorded change despite receipt errors and refuses to overwrite
foreign values. Exact original profile/package restoration remains a lane
release condition; native uncertainty and original strict fixtures are not
weakened.

The declared Python discovery now passes **79 discovered/run/passed,0
failures/errors/skips**, including owner-user rejection, bound commands,
explicit absence versus failed/retained-data probes, both original-package
modes, partial-install cleanup, foreign-package protection, unset-profile
restoration, receipt failure and independent cleanup. These remain synthetic
host results. Actual build/instrumentation counts at this source checkpoint
are **0**; the unchanged two-method/API execution and full audit are not yet
accepted. Producer `59a47ac40`, product `421fc446` and strict fixture `99175354`
remain byte-bound to their original Git objects.

### Recovery failure and post-packaging absence hardening

Independent review of6271a8e5 identified two coupled preservation gaps before
the next native attempt. Original-APK recovery now treats a still-missing target
as a collected require failure, never a `None` indexing error that could skip
independent temporary-profile restoration or the final error receipt. The native
lane remains held when the original target cannot be recovered. The real shared
cleanup path is exercised through `execute_language`, including failed backup
reinstall, a still-absent target, independent profile restoration and persisted
package/cleanup errors.

The consumer also rechecks complete original test registration absence, and
target registration absence when originally empty, after packaging and before
any temporary profile/package mutation. Newly retained registration/data cannot
be mistaken for an empty `pm path`. Parameterized App/Test regressions introduce
that drift during packaging and prove zero profile setup, install, uninstall or
instrumentation and no deletion of foreign registration.

Declared discovery passes **81 discovered/run/passed,0 failures/errors/skips**.
These are synthetic host regressions, not a native result. Strict fixture and
product blobs are unchanged. The genuine6271a8e5 API37 attempt stopped before
Gradle/install/instrumentation at the packaging helper's SDK37 public signer
format parser: successful `apksigner verify --print-certs` returned the exact
`V2 Signer: certificate SHA-256 digest:` form, not `Signer #N`. Its public
certificate was `000be798aca5806230ec26d9feee36cbd0acadc857cf22a4ba711f43a4a2c8b7`;
the package was correct, original target/profile remained untouched and the
actual lane was released. That concrete packaging source repair belongs to the
existing WP003 producer, not a new provider, key or user-installation gate.

### All-users registration correction before native retry

The earlier6271a8e5 read-only API31 observation did **not** establish global
registration/data absence. Its `pm list packages -u PACKAGE` inventory defaulted
to user0 on the pinned Android12 shell implementation; the retained original
observation and its overbroad field remain historical, not authority for a new
run. The [pinned public PackageManager shell source](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)
adds known-package matching for explicit `--user all`.

The repaired consumer uses `pm list packages --user all -u PACKAGE` for both
App/Test, retaining strict parsing and failure handling. Initial registration
checks precede private/profile capture and build; the same all-users checks run
again after packaging, before any temporary profile or package mutation.
Owner0 remains mandatory for private/package execution and cleanup; this is
public registration inventory, not general multi-user access.

The fake transport now distinguishes user0-visible packages from registration
visible only with `--user all`, invoking the real consumer inventory method.
App/Test regressions prove initial global registration blocks private/profile
capture, build, installs and instrumentation, and packaging-time registration
drift blocks profile/package mutations without deleting foreign state.
Declared discovery passes **82 discovered/run/passed,0 failures/errors/skips**.
The separate Windows controller command completed **375 discovered/run/passed,0
failures/errors/skips** onb30f218a; it is not a new-head or native verdict.

The actualb30f218a assembly passed public signer verification and reached
`:app:mergeExtDexDebug`, then failed with D8/daemon Java heap exhaustion under
the helper's768MiB local fast-check profile. The process exited2 normally;
original API37 target/profile checks passed and both lanes released, with zero
installs/instrumentation. The existing WP003 producer owns the per-invocation
full-assembly heap correction; no SDK/key replacement, global settings change,
fallback APK or claimed native pass is introduced.

The independently reviewed PR257 is now deployed as actual main
`85871f40b1520a89ced6b9bec74aa8eec2f4f4de` (parents `bfd7551565de3c5b1e5a71f3f16df1b72c7af00f`
and `ce908230972a5504e913591c72f65e427735dae0`). Its six disjoint support paths
were reconciled with this consumer repair through the same canonical owner's
supported scope API, returning actual revision **21** from20; immutable
identity/authorization/history were retained. Local debug packaging now selects
the full-assembly2048MiB child heap while preserving actual local provenance,
the allowlist, existing debug identity, fixed strict tasks and exclusive lanes.
Trusted policy remains `paused=false`, `dispatch_mode=off`,
`activation_approved=false`. No previous hosted or native verdict transfers to
this integrated candidate.
