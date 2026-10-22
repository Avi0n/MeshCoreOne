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

### Actual authorized language pair and bounded API31 cleanup recovery

Executed source is exactly `b2d9a00368432c04ccc40676417b2576bf472218`,
Android tree `4798be366ae48a517a80a4e3b49ef99262d6fb78`, source-tree digest
`8ef72bc7f51b77fdf8dec38169d41b6597a08c91483c3db37a9f7fe65014ee17`,
trusted main `85871f40b1520a89ced6b9bec74aa8eec2f4f4de`, reservation **21**.
Product/fixture/producer and the two original method identities remain unchanged.
Both standard strict debug assemblies completed using the existing local
identity; App/Test public certificates matched
`000be798aca5806230ec26d9feee36cbd0acadc857cf22a4ba711f43a4a2c8b7`.

| API | Discovered / started / matching outcomes / passed | Failed / errors / skipped | Instrumentation seconds | Final captured original state |
| --- | --- | --- | --- | --- |
| 37 | 2 / 2 / 2 / 2 | 0 / 0 / 0 | 12.092 | Original App APK digest/profile verified; introduced Test removed; normal release |
| 31 | 2 / 2 / 2 / 2 | 0 / 0 / 0 | 9.251 | Original global App/Test absence and exact captured profile verified after separate bounded recovery; release verified |

[Exact-source native summary](device-current-language-pair.json) binds actual
APK/certificate hashes, original captured profiles, operation owners, immutable
private receipt hashes and unmodified [API31](device-current-language-api31.txt)/
[API37](device-current-language-api37.txt) transcripts. Both runners completed
normally with process exit0 and terminal instrumentation code-1. This is native
language evidence, not a duplicate reproducible hosted-CI result bundle, full-WP
acceptance, physical TalkBack/radio verification or a passing-cohort replay.

API31's first cleanup correctly exited2 and retained the same native lane:
all package checks succeeded, but original unset font was now1.0; every other
captured original field matched. The original failed operations receipt remains
unchanged. Restricted setting diagnostics attributed the new1.0 to `pkg:android`,
`defaultSystemSet:true`; update/delete history matched the owned restoration,
and actual effective global configuration had settled to1.0. The pinned
[Android12 observer/persistence path](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/services/core/java/com/android/server/wm/ActivityTaskManagerService.java)
and [Settings writer](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/provider/Settings.java)
explain materialization after deleting2.0, rather than changing the definition
of original absence.

Within that unchanged source/owner/reservation, recovery journaled exactly one
additional deletion through the existing process-device component. It never
wrote1.0 or issued locale-setting mutations. Two spaced full captures equaled
the original profile, including genuinely unset font/animator, owner0 and
original display/IME/motion; global App/Test absence and guest quiescence were
verified before the supported same-owner native-lane release. No tests were
replayed and no unresolved lane was released.

After release, the consumer gained a bounded automatic correction for that
observed API31 case. It correlates actual new framework update/delete history
with pre-deletion history, checks effective configuration and other original
state, journals at most one extra deletion and verifies stable exact absence.
Unknown/foreign/malformed state or settlement timeout retains explicit
effective-profile uncertainty, even when the raw setting reads unset. Receipt
failure does not prevent independent cleanup but remains a reported error.
Declared audit discovery now passes **89 discovered/run/passed,0
failures/errors/skips**; these new cases are synthetic software regressions,
not execution of this later cleanup code on a device.

The committedb2d9 Windows controller rerun passed **376/376,0 failed/errors/
skips**. Its preceding precommit run was375/376 with one immutable-input error
against the old HEAD; that failed precommit result is not relabeled as success.
Native evidence creation and byte-preserving attributes subsequently evolved
the same owner through returned revisions **21→22→23**; the actual native
outcomes remain bound tor21, not rebound to current scope/source. Hosted exact
job/logs remain authoritative for each candidate; no earlier verdict transfers.

### Review corrections for effective-font and history proof

Bounded review5593657671 identified two gaps in5ed90aef's automatic cleanup.
Already-original raw font could bypass effective configuration verification,
and a history containing only two events could satisfy a vacuous suffix
comparison. The corrected consumer always verifies effective/stable original
font for a recorded API31 unset-font mutation, including rawNone, without
writing an already absent setting. Timeout or an unattributed materialization
sets retained-profile uncertainty and cannot release the lane.

A second deletion now requires nonempty overlapping history, two strictly newer
ordered update/delete events and exact prior-history anchoring. Lost, unchanged,
same-timestamp-ambiguous or malformed history fails closed. Actual-adapter tests
exercise rawNone/effective2 through the real lane guard, lost/unchanged histories
through the real parser, stable rawNone/effective1 without writes, and the
original observed correlated positive history. Maximum one additional deletion,
receipt-failure independent cleanup and the rawNone-versus1.0 distinction remain.
Declared audit discovery passes **91 discovered/run/passed,0 failures/errors/
skips**. No new device run or replay is claimed.

Actual deployed PR258 main `c9ed1f9b78bcc322fb1ec281d211f99852cfec3b`
(parents85871f40/27947068) is reconciled through the same supported owner scope,
returning idempotent revision **23→23** for four already admitted disjoint
support paths. Identity/authorization/history and nativeb2/r21 bindings remain
unchanged. The historicalb2 hosted run39520769161 terminated **CANCELLED**:
Linux118964258034 succeeded (App865/865,0 failures/errors/skips), while
SwiftData118973686724 was cancelled and aggregate118974617616 failed.
Neither the overall run nor this successor is relabeled successful, and no
second hosted result bundle is retained. Local controller discovery on committed
5ed90aef passed376/376,0 failures/errors/skips; it is not a successor verdict.

### Software-only current-source diagnostics consumer preparation

The next bounded harness increment, based on reviewed35ee3215/current main
c9ed1f9b and actual owner scope23, generalizes the existing immutable family
specification, typed binding/request and single process lifecycle to precisely
the five original API37 diagnostics methods. No parallel copied runner,
arbitrary class/method selector, historical default actor or historical-main
invocation is added. Language still defaults to exactly two original methods
on each API31/37; diagnostics accepts API37 only.

The specification binds the original fixture blob
`e31edcf50c08532465ad3254919ff13eefbd8d7f` and shared audit
`3b81cb7808bebee6bcbb2e15eadd5cb7a77e58b5` atff424766, complete production
diagnostics subtree `c9f4bfeb12dd02729dbf080e6bb5e513d170e73b` at8a8c6348,
common assertions `efc83d617bd7fe50fb6e5350e6a507aaf6f7bc1c` atf7474bb1,
and device harness `8768ee9c2b25fc1a7d43d0442980ff4deabb6d81` at7f24b57c.
Each current/original Git object and producer ancestry is verified; full source
identity, current published head/main/policy/reservation and truthful replacement
attribution retain their existing guards.

Declared software-only discovery passes **103 discovered/run/passed,0
failures/errors/skips**. Tests exercise actual committed source pins, wrong
family/API/identity/order/count, original/current fixture/helper/production drift,
stale publication/policy/scope, strict five-method outcome accounting, missing/
duplicate/foreign outcomes and forced termination. Synthetic transports run the
shared install/package/profile/uncertainty/lane lifecycle with exactly five
selectors, without invoking the historical selector or any real device/build.
The four normal source validators and whitespace check pass. The separate
committed35ee Windows controller run completed379/379,0 failures/errors/skips;
that result does not transfer to this local increment.

No Gradle/APK build, ADB/install/instrumentation or native-lane operation occurred
for this increment. Current reviewed35ee publication is preserved while its exact
hosted verification is nonterminal. Diagnostics5 is part of original missing27;
preparation clears none of27, and the separate historicalb2/r21 language2+2
subtracts zero. All actual failed/recovery/native evidence remains untouched.

The prepared six-file increment was retained as local302474d1 while reviewed
35ee hosted verification continued. Its committed Windows controller run
passed379/379,0 failures/errors/skips, separately from103/103 audit regressions.
Then actual reviewed PR259 main
`dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c` (parentsc9ed1f9b/475ffefa)
was reconciled locally through the supported same-owner scope API, returning
actual **23→24** for six incoming support paths. Manifest977b9e91 remains
unchanged; exact deployed policy remains false/off/false. An initial pre-merge
diagnostic reporteda657babf rather than the suppliede8afe067; that failed
historical diagnostic and its integration receipt are retained, not current
authority. Fresh exact-Git JSON plus literal canonical hashing, loaded
`controller.gates.policy_revision` and `schema.digest` subsequently agree on
actual semantic revision
`e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`
(payload10516 bytes, policy-semantics digest0780e523). Both loaded hashing
functions' bytecode exactly matches freshly compiled source, and both sources
match immutable trusted main. At that stage the earlier discrepancy had not
been reproduced; the subsequent decoding investigation below establishes its
cause. Raw policy SHA256 and Git blob identifiers
are distinct, not competing semantic revisions. A separate correction receipt
supersedes the initial fingerprint without changing its original bytes.

Actual read-only source preparation at local608ee222/r24 succeeds for precisely
the five diagnostics identities on API37 with the verifiede8afe067 policy:
`python android\accessibility-tests\language_binding.py --head 608ee222fb75ab601e7c2ac7dbe099a6944e0a21 --family diagnostics`.
It reports `authority=false`, `native_starts=0`, `apks_built=false`, exact original
source pins and current canonical/replacement attribution. This is not published
host readiness or native admission. No original authorization, nativeb2/r21
outcome or captured fixture/helper/production evidence is rebound to the new
policy/scope.

### Confirmed Windows Git-decoding correction before publication

The existing current consumer's `language_binding.git` used `text=True` without
an encoding. On a CP1252-default Windows process, the exact immutabledbb policy
bytes decode to different yet parseable JSON: actual semantic revisiona657babf
instead of correct UTF-8e8afe067. This reproduces the earlier mismatch without
changing a policy, hashing rule, source pin or authority. The unknown-cause
diagnostic/correction receipts remain historical bytes; this reproducible root
cause supersedes that uncertainty. Earlier actualb2/r21 native commands explicitly
set per-process UTF-8 and their retained outcomes are not reinterpreted.

The bounded fix makes the current consumer Git helper use explicit
`encoding="utf-8", errors="strict"` for all of its source/policy reads. The only
other directly used packaging source-identity Git path returns bytes and decodes
with Python's strict UTF-8 default; no analogous locale-dependent Git decoding
was found in that direct consumer path. Historical runner/default entrypoints,
generic cleanup, global environment and producer-owned modules are unchanged.

Declared software-only discovery passes **106 discovered/run/passed,0
failures/errors/skips**. New regressions pass real em-dash/CJK UTF-8 bytes through
the actual helper and real Python output subprocesses under a mocked CP1252
default; trusted-policy consumption equals literal canonical hashing/e8afe067,
while explicit CP1252 decoding reproducesa657babf. Malformed UTF-8 raises in the
helper/policy path and produces a visible CLI failure, with no replacement or
fallback. The103/379 results remain scoped to302, and actual integrated608
Windows controller discovery separately passed385/385,0 failures/errors/skips.

The previous reviewed35ee hosted run39526195999 is now terminal **SUCCESS**,
including its Linux/SwiftData/aggregate jobs. This clears only the planned
serialization hold before ordinary feature-branch publication; none of its
hosted/native evidence transfers to the diagnostics/decoding successor, and no
CI result bundle or successful native cohort is duplicated.

### Local Nodes7 software-only selection successor to publishedf3

Base/source boundary: published
`f3d3346c2e91f71ba69b6a1153fa1d80568f50bf`, integrated trusted main
`dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`, unchanged manifest977b9e91,
semantic policye8afe067 and canonical scope24. Publishedf3/exact automatic
run39529850037 is held immutable while verification runs; this preparation is
local only and is not its CI/native verdict. The successor is identified by
the commit containing this section and its immutable SHA in the handoff.

Trusted capability admission confirmed all six edited Python/test/README/WP503
evidence paths already belong to the held owner's `wp-owned` capability and
modify/test/record-evidence operations. Scope remains24; no transfer, new actor,
authorization rewrite, release/reacquire or raw ledger edit was needed.

Actual immutable Git objects establish exactly seven ordered original methods
at initial registration `fc9188ab1dc1549be8788cbc3c180c895d5d5def`, retained by
current fixture producer `9101b45f3939bdb82af5c93cc77149c47bf34996`. The fixed
`nodes` family pins:

| Path under repository root | Producer | Exact object |
| --- | --- | --- |
| `android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeNodesFlowAccessibilityTest.kt` | `9101b45f3939bdb82af5c93cc77149c47bf34996` | `3f50d6c80dab380e0e7883016295a16510604921` |
| `android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NodesAccessibilityAudit.kt` | `9101b45f3939bdb82af5c93cc77149c47bf34996` | `29a0f83dd33c57e1304e822b5a394cfc05db3b3e` |
| `android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/OfflineNodesAudit.kt` | `41bdbfac45b4d3690f29a5363da02047642e2716` | `5fd9340c847780b0a4cb1df4b0fc4dd7fae36f45` |
| `android/feature/nodes/src/main` (complete tree including manifest) | `fd9fa8e16c8a74043387010a192c3a9e36abd783` | `d478d0ecc746c76e384dbdd62c3b17990a760601` |
| `android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityAssertions.kt` | `f7474bb1bd06c6df93a71c8664e9dacade7c8749` | `efc83d617bd7fe50fb6e5350e6a507aaf6f7bc1c` |
| `android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/DeviceAccessibilityHarness.kt` | `7f24b57c1ab06437435b8938d166a5254fec0894` | `8768ee9c2b25fc1a7d43d0442980ff4deabb6d81` |

The binding checks original/current objects and producer ancestry, exact ordered
fixture methods, API37 only and truthful `CANONICAL:nodes:UUID` attribution.
Selection uses the same typed request and real shared process/preservation
lifecycle, not historical default selection or another runner. Purpose is
`nodes-api37-7-original-methods`. Language2/API31+37 and diagnostics5/API37,
their pins/defaults, strict UTF-8 decoding and original native evidence are
unchanged. No production Kotlin, fixture or shared audit helper was edited.

`python -m unittest discover -s android\accessibility-tests -p test_*.py -v`
passes **119 discovered/run/passed,0 failures/errors/skips**, including13 new
Nodes tests. Synthetic process transports exercise original ordered selection,
positive seven discovery/outcomes, exact restoration/release, wrong family/API/
producer/count/identity/order, each original/current pin and ancestor, stale
source/policy/scope/publication, failure/skip/missing/duplicate outcomes, forced
host termination and unresolved effective profile despite equal raw profile.
The first119-test pass attempt had118 passes/one test expectation error:
the existing lane correctly wrapped retained-profile uncertainty in an
ExceptionGroup rather than a bare RuntimeError. The test now asserts that
actual grouped failure and preserved inner error; the guard was not weakened.

The four declared validators
`python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\controller\workflows.py`, and
`python tools\android-port\portmap.py` each pass/exit0.
`git diff --check` passes; staged whitespace is also checked before commit.
These are local software results, not a hosted successor verdict.

No real Gradle/APK/build/ADB/install/instrumentation/native-lane operation
occurred. This is not Nodes execution, golden evidence or WP acceptance.
All original missing27 remain unexecuted in the current-source missing-only
cohort; historical separate language2+2 subtracts zero. Actualb2/r21 native
outcomes, original failed cleanup receipt and separate bounded recovery remain
byte-preserved and source-bound, with no replay or rebinding.

### Local original comparator11 software-only successor to reviewedad

This increment is based on reviewed local Nodes source
`ad165035d189f5f823bd7de6e7712d355488b31a`, above unchanged published
`f3d3346c2e91f71ba69b6a1153fa1d80568f50bf`. Actual trusted main remainsdbb37206,
manifest977b9e91/semantic policye8afe067/same canonical scope24 are unchanged.
The five changed binding/test/README/evidence paths are within the same six-path
trusted `wp-owned` admission already held for modify/test/record-evidence.
No reservation evolution, identity change or authorization rewrite was needed.

Actual existing Git inventory establishes exactly eleven ordered original
`NativeLocalizedCompareParityTest` methods, unchanged from fixture producer
`5bbef0b2f29b9a96dd88b5e5a5d4584a6739c8c5`. The fixed comparator specification
checks both current and original objects plus producer ancestry:

| Path under repository root | Producer | Exact object |
| --- | --- | --- |
| `android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeLocalizedCompareParityTest.kt` | `5bbef0b2f29b9a96dd88b5e5a5d4584a6739c8c5` | `79e7e12b950f1459f006bc72942539ecf07375e0` |
| `android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/diagnostics/text` (complete subtree) | `8a8c6348af1c98b3f11f21769800308cf302b8f9` | `b1fde2e38d54bb945364bbf27abd3c0c66f012b8` |
| `android/feature/tools/src/test/kotlin/com/meshcoreone/android/feature/tools/diagnostics/text/SwiftTextParityTest.kt` | `8a8c6348af1c98b3f11f21769800308cf302b8f9` | `58af4467a85422c8d53cee7001a159cf56f31642` |
| `docs/android/evidence/WP-316/oracle.swift.txt` | `d4cc34496decfa3b57c588cf208a9dc160ddf317` | `bbccc686e1414561fa7f9f7d8e11cd1846e4fcf5` |
| `docs/android/evidence/WP-316/oracle-output.txt` | `d4cc34496decfa3b57c588cf208a9dc160ddf317` | `0064656ced61ca93fc541c3e3b070c3f2076263d` |

The complete production subtree includes `LocalizedCompare.kt` blob
`213866562c54c12d1f82f9c397b5cdcac7a4907c` and its existing Swift text/number
helpers. The native fixture explicitly uses `Locale.US`, invokes the installed
target class and retains its own pair/scalar/ErrorCollector helpers. It is a
standalone fixture, not a DeviceAccessibilityHarness/Compose audit; no unrelated
UI-helper dependency is invented. Supplemental tr/az2 fixture and producer cases
remain separate and unselected, not substituted for the independent retained US
oracle or counted as eleven methods. No oracle was generated or executed here.

Only an immutable `comparator` family/API37/ordered11 and fixed
`CANONICAL:comparator:UUID` owner are added to the current typed binding/request.
The existing runner already derives purpose/identities/stem/events from that
fixed request; **no runner implementation change** was needed. Its purpose is
`comparator-api37-11-original-methods`. Language2/API31+37, diagnostics5/API37,
Nodes7/API37, strict UTF-8, published source/main/policy/scope checks, normal
positive completion and package/profile/uncertainty fences remain intact.

`python -m unittest discover -s android\accessibility-tests -p test_*.py -v`
passes **132 discovered/run/passed,0 failures/errors/skips**, including13
comparator regressions. They verify real original/current Git pins and eleven
identities, reject every pin/ancestor/count/order/family/API/identity/attribution
boundary, and use real shared selection/lifecycle with synthetic transports for
positive11, wrong/supplemental/missing/duplicate/failure/skip/count outcomes,
stale source/policy/scope/publication, forced termination and retained effective
profile uncertainty despite raw equality. No host/parameter-family proxy is
counted as an original native method or acceptance.

`python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\controller\workflows.py`, and
`python tools\android-port\portmap.py` each pass/exit0; whitespace passes.
The exact local successor is the commit containing this section and its
immutable SHA in the handoff. Publishedf3 and its healthy automatic39529850037
verification remain unchanged while local comparison proceeds; there is no
cancel, rerun, dispatch or copied CI-result bundle.

All Kotlin fixtures/production/shared helpers, retained original oracle bytes
and genuineb2/r21 native/failed-cleanup/separate-recovery evidence are unchanged.
No actual Gradle/APK/build/ADB/install/instrumentation/native-lane operation
occurred. Neither prepared family support nor historical language2+2 subtracts
anything: all27 remain unexecuted in the current-source missing-only cohort.
This is not native comparator/Nodes/diagnostics execution, goldens, source/native
acceptance or whole-WP completion.

### Local supplemental tr/az2 software-only successor to reviewed2064

Based on reviewed local
`2064b52517a70b4e6fd4df29660cf21ca5611ff6`, above reviewedad and unchanged
publishedf3. Actual main remainsdbb37206; source referencedb14559/manifest977b9e91/
semantic policye8afe067/canonical scope24 are unchanged. Trusted `wp-owned`
modify/test/record-evidence admission already covers the five changed Python/
tests/README/WP503 evidence paths. No new scope, actor, authorization prose,
reservation release/reacquire or ledger editing was required.

Original and current Git inventory agrees on exactly two ordered methods:
`turkishProducerCasesPreserveLocaleTailoringOnAndroid`, then
`azeriProducerCasesPreserveLocaleTailoringOnAndroid`. The fixed `traz` family
pins current/original objects and producer ancestry:

| Path under repository root | Producer | Exact object |
| --- | --- | --- |
| `android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeLocalizedCompareLocalePreservationTest.kt` | `6c81bad084f27c1a6501e1296eec93f160fb517e` | `41a1e4046f96aeb0c725a1344e7f420dfd142be4` |
| `android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/diagnostics/text` (complete subtree) | `8a8c6348af1c98b3f11f21769800308cf302b8f9` | `b1fde2e38d54bb945364bbf27abd3c0c66f012b8` |
| `android/feature/tools/src/test/kotlin/com/meshcoreone/android/feature/tools/diagnostics/text/SwiftTextParityTest.kt` | `8a8c6348af1c98b3f11f21769800308cf302b8f9` | `58af4467a85422c8d53cee7001a159cf56f31642` |

The fixture hardcodes only `tr-TR` and `az-AZ`, calls the installed production
class and retains its original scalar/reflection/ErrorCollector/pair helpers,
four equivalent and three unequal pairs per locale, both comparison directions
and stable ordering for both equivalent-pair input orders. These vector families
come from the unchanged producer parity test, not an independent Foundation
Turkish/Azeri oracle. The original fixture explicitly reports
`independentFoundationOracle=false`; no US oracle substitution or new oracle is
made. Pinning the whole producer parity file does not select its US tests.

Only immutable family/API37/original ordered2 metadata is added to the existing
typed binding/request. No runner implementation change is necessary:
`CANONICAL:traz:UUID`, purpose `traz-api37-2-original-methods` and the same real
shared selection/process/preservation lifecycle are reused. Language2/API31+37,
diagnostics5/Nodes7/comparator11 API37, all prior pins/defaults/strict UTF-8,
source/main/policy/scope/publication and cleanup/uncertainty fences are retained.
There is no arbitrary locale/class/method argument or historical actor/default
selection.

`python -m unittest discover -s android\accessibility-tests -p test_*.py -v`
passes **144 discovered/run/passed,0 failures/errors/skips**, including12 new
supplemental regressions. Real Git tests establish original/current pins, exact
two identities/order and producer-only vector qualification. Negatives cover
each pin/ancestor, family/API/producer/attribution/count/identity/order, stale
source/policy/scope/publication, wrong same-count language or US fixture,
missing/duplicate/failure/skip/terminal outcomes and forced completion. Synthetic
real shared lifecycle tests cover positive2 with originally present AND absent
packages, exact cleanup/release, wrong outcomes retaining uncertainty/lane,
forced host cleanup and effective-profile uncertainty despite equal raw values.
Both CLIs reject an arbitrary locale argument.

`python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\controller\workflows.py`, and
`python tools\android-port\portmap.py` each pass/exit0; whitespace passes.
The containing commit and immutable handoff SHA identify the local successor.
Publishedf3/39529850037 remains unchanged while healthy verification runs and
this increment awaits comparison; no cancel/rerun/dispatch/CI bundle is created.

No Kotlin/production/shared helper/vector/oracle/native evidence bytes change.
No real Gradle/APK/build/ADB/install/instrumentation/native-lane operation or
new native authorization occurs. Historical US/tr/az andb2/r21 language/failed
cleanup/separate recovery retain their exact bindings. All27 remain unexecuted
in the current missing-only cohort; neither prepared family support nor separate
language2+2 clears anything. No native/golden/whole-WP acceptance is inferred.

### Local original LoS1 software-only successor to reviewed45ca

Prepared on reviewed
`45ca12f44b2eb0c220db6f42fd12cc3023c8dacc`, same canonical scope24 and trusted
main dbb37206/manifest977b9e91/policye8afe067. Existing trusted `wp-owned`
modify/test/record-evidence admission covers the five changed Python/test/
README/WP503 evidence paths without scope/identity/auth changes.

Original registration `e4c1aa7aa3b2c759524f198f5cfa09024143c062` and retained
final fixture `3f39a777376e322e2bc533f1d1caf92a87a44ed8` establish the original
missing-only identity:
`com.meshcoreone.android.accessibility.NativeLineOfSightAccessibilityTest#savedRepeatersExposeNamedNativeActionsForNonGestureEndpointSelectionAtFont200`.
The full current/final fixture also retains
`frameworkNativeAccessibilityTreeFindsAndInvokesTheActualNamedButton`, a separate
platform control that is not selected, replayed or credited to LoS1.

| Path under repository root | Producer | Exact object |
| --- | --- | --- |
| `android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeLineOfSightAccessibilityTest.kt` | `3f39a777376e322e2bc533f1d1caf92a87a44ed8` | `f8c2cab5dc964548e938f1e023f72912874fc2a8` |
| `android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/los` (complete subtree) | `c6cdac39e89081647ea6475e6bcae800f2531109` | `46d415e10269ea22e520cbf35f82467c778956a8` |
| `android/app/src/test/kotlin/com/meshcoreone/android/app/los/SavedEndpointSemanticsTest.kt` | `c6cdac39e89081647ea6475e6bcae800f2531109` | `e6bac5fc92755ca3a104928e6969586af3c2709b` |
| `android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityAssertions.kt` | `f7474bb1bd06c6df93a71c8664e9dacade7c8749` | `efc83d617bd7fe50fb6e5350e6a507aaf6f7bc1c` |
| `android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/DeviceAccessibilityHarness.kt` | `7f24b57c1ab06437435b8938d166a5254fec0894` | `8768ee9c2b25fc1a7d43d0442980ff4deabb6d81` |

Current/original objects and producer ancestry match. The current saved-endpoint
screen and producer semantics-test blobs also match the unchanged actual final
LoS source-import record; no producer pin is silently advanced. The pinned full
fixture contains all native tree/ancestor/endpoint/layout helpers. Its actual
fixed inputs remain radio/contact UUIDs301/302/303; public keys32x55/32x66; East
37.8/-122.4 and CJK West37.7/-122.3; elevation100m; frequency906000kHz. Dependencies
require one catalog fetch/two ordered endpoint elevation fetches and reject
terrain/path analysis. Native named clicks/show-on-screen, exact single spoken
name/action, localized selected/checked state and actual rendered name/role
GetTextLayoutResult/font/paint assertions are unchanged.

`FamilySpec` now optionally records the full immutable fixture method inventory,
separate from its fixed selected identities. LoS pins both methods/order but
selects only the original1. Existing families retain their full-inventory
defaults. Binding rejects altered inventory, empty/duplicate/nonexistent or
misordered selection; no selector is exposed to caller input. Existing typed
requests and unchanged shared runner derive unique `CANONICAL:los:UUID`,
purpose `los-api37-1-original-methods`, stem/events and exact positive1.
All previous family/default/UTF-8/source/policy/scope/publication and cleanup/
uncertainty contracts remain intact.

`python -m unittest discover -s android\accessibility-tests -p test_*.py -v`
passes **156 discovered/run/passed,0 failures/errors/skips**, including12 LoS
regressions. Tests cover actual registration/current/original objects and
fixed inputs, every pin/ancestor, fixture inventory/order/selection, wrong
family/API/producer/attribution/source/policy/scope/publication, control-only or
whole-class substitution, missing/duplicate/failure/skip/terminal outcomes,
forced termination and unknown effective profile despite raw equality. Real
shared selection/lifecycle uses synthetic transports and preserves packages/
uncertainty/lane behavior; this is not a native LoS result or new oracle.

The four declared verification_config/validate/workflows/portmap Python commands
recorded above each pass/exit0; whitespace passes. The containing commit and
handoff SHA identify this local successor. Previous publishedf3 automatic
CI39529850037 is now **COMPLETED/SUCCESS**; it does not include or accept this
local source. Publication is no longer held by that old run; this increment is
local for bounded source comparison and coherent clean normal publication.

No actual host readiness/Gradle/APK/ADB/install/instrumentation/native-lane action
or new native authorization occurs. All Kotlin/production/helpers/vectors/oracles
and historical genuine native/failed cleanup/separate recovery remain unchanged.
All27 remain unexecuted in the current missing-only cohort, with zero credit
from prepared families or language2+2. Cached-chat1 remains a separate software
family; no physical TalkBack/goldens/native/whole-WP acceptance is inferred.

### Local original cached-chat1 software-only successor to reviewed43c8

Prepared on reviewed `43c8eb25c8f8d3b060153814930d01a48f481a0d`, same actual
canonical owner/scope24 and current trusted main dbb37206/manifest977b9e91/
policye8afe067. All five changed Python/tests/README/WP503 evidence paths remain
inside existing trusted `wp-owned` modify/test/record-evidence admission;
no reservation, actor, authorization prose or policy is changed.

The original registration `4f89d2168e03786588e760a2d8310fbe9edc89d0` precedes
retained final fixture `e8fa6b2994177e09931925800ed7696222a65af7`.
The selected identity is exactly
`com.meshcoreone.android.accessibility.NativeLauncherFocusAccessibilityTest#realCachedChatDraftAndScrolledMessageSurviveCurrentWindowRecreationAtFont200`.
`fixture_methods` pins the complete ten-method source inventory/order; only this
one is selected. IME, framework, motion, appearance, navigation and intentional
cleanup-fault cases remain intact, unselected and without missing-only credit.

| Path (beneath repository root) | Actual producer | Exact current/producer object |
| --- | --- | --- |
| `android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeLauncherFocusAccessibilityTest.kt` | `e8fa6b2994177e09931925800ed7696222a65af7` | `698d649b230b7d7945a5d901d37f748427508986` |
| `android/feature/chats/src/main` (complete subtree) | `4b6629ac2bf215577ec1522530a77de61ed577fe` | `fbf18c35b67de68a6a1a19f6180ea7f2ea92fa06` |
| `android/feature/chats/src/test/kotlin/com/meshcoreone/android/feature/chats/timeline` (complete subtree) | `2396ac64f9d3bcee7fa3a7e4f33e87956508d31d` | `331c0835addbb7102db51ea502233ca02134fd84` |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt` | `03306ff125655f977d88c5b517ad4cab26d9e771` | `6e191c79de3a5cebf3f8e0603956e180f97bd014` |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt` | `03306ff125655f977d88c5b517ad4cab26d9e771` | `99ff02f5c15359e6260316d6411a5bac7a62518e` |
| `android/app/src/test/kotlin/com/meshcoreone/android/app/platform/NotificationDraftRestorationTest.kt` | `03306ff125655f977d88c5b517ad4cab26d9e771` | `f076dceef89d8f14efe9d3dad6af260e90133d98` |
| `android/app/src/test/kotlin/com/meshcoreone/android/app/platform/ChatTimelineRestorationTest.kt` | `4b6629ac2bf215577ec1522530a77de61ed577fe` | `187e1a7e6fcd43c2494e0b036706d5f25cd9015f` |
| `android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/rendering` (complete subtree) | `70a48774cbd2eb9f4280f917c0cd126e881de679` | `0171a46786d18130414490b0e919752e5315f6a2` |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatConversationScreen.kt` | `e9f0febc7e53b09adb00ac56928e7073366023f6` | `59bb797248d7ccf247dd69c3ee93fd8af5412ff9` |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/NativeNavigationShell.kt` | `80ab59b14e23092080063664b234bb2a3166063d` | `c9a881fcdbf605784cbf2593d77c808b41c75083` |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/NavigationCoordinator.kt` | `80ab59b14e23092080063664b234bb2a3166063d` | `26181519d9a0f8cb690e1ae1b38c08f2e05c45b9` |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/NavigationState.kt` | `80ab59b14e23092080063664b234bb2a3166063d` | `f6b37d7f1cdef4b49378703ae5ced2d9ea607a7b` |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/NavigationSavedState.kt` | `446607773ae4be3780f9029c0730e5e531eef945` | `d72e7573e37b5c9491aacd5e60a5f7b97643b5aa` |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppMessageContent.kt` | `a4e83853b5f9b5f2fa43c282b52fbc82b8a31761` | `699a6f9b48d70e86808dc132efbf2c0c23c4178f` |
| `android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityAssertions.kt` | `f7474bb1bd06c6df93a71c8664e9dacade7c8749` | `efc83d617bd7fe50fb6e5350e6a507aaf6f7bc1c` |
| `android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/DeviceAccessibilityHarness.kt` | `7f24b57c1ab06437435b8938d166a5254fec0894` | `8768ee9c2b25fc1a7d43d0442980ff4deabb6d81` |

Every listed original/current object and producer ancestry is checked through
the existing strict binding. The complete relevant chats and rendering subtrees
pin delegated composer/timeline/ordinary draft/text helpers rather than a chat
proxy. Exact source/Android-tree binding retains the surrounding process graph.

**Reported and resolved current-input drift, not historical equality:** retained
`3f39a777376e322e2bc533f1d1caf92a87a44ed8` has Content
`2b5ccd083c536c981169543377919d3c8c1f1a95` and Feature
`ac0301b1b167a4f0f2a97fb2b522b51e68b67ced`, distinct from the current033 objects
above. The coordinator independently reviewed the complete historical-to-current
delta, complete five-case regression source and actual draft delegate, confirming
033 trusted-main ancestry and equality at current43c8 and actualdbb. These are
separately identified current software inputs, not an advance of frozen Swift,
any prior family pin or any retained native proof. Tests preserve both historical
objects explicitly and reject substituting them for current pins.

Current restoration keys on composer and actual notification-source identity.
The delegate returns without draft mutation for absent/wrong-radio service,
stale source identity or absent pending reply; only a matching direct-contact
pending draft is consumed. Its original producer regression source covers absent/
empty pending reply, replacement source/no-pending retained typing, recomposition
and radio/channel isolation. These Kotlin tests were read and pinned, **not run**
as part of this software-only increment.

Original native fixture inputs/predicates remain unchanged: radio/device503001,
contact503002, 32x50/32x51 public keys, CJK peer and CJK/Arabic draft, 36 incoming/
delivered/read messages at Unix1700000000+index/UUID503100+index, target15, real
process store and ordinary draft, disconnected state, actual placed/attached/
finite row geometry and unique visible-message set. It observes12px then24px
without changing that set and requires the same actual within-row offset,
draft and navigation entry/stacks/selection after900x900 then360x900 recreation.
Original cleanup restores window override, navigation, ordinary draft, onboarding
flag and seeded store rows. No fixture/production/helper/oracle byte is changed.

`cached-chat` reuses immutable `FamilySpec`, typed request, unique
`CANONICAL:cached-chat:UUID`, purpose `cached-chat-api37-1-original-methods` and
unchanged explicit shared process/device preservation lifecycle. All preceding
families/defaults/UTF-8/source/main/policy/scope/publication/profile/package/
uncertainty guards remain intact. No arbitrary selector or copied runner exists.

The declared
`python -m unittest discover -s android\accessibility-tests -p test_*.py -v`
passes **168 discovered/run/passed,0 failures/errors/skips**, including12 new
cached-chat binding/selection/outcome/lifecycle regressions with synthetic
transports. They exercise exact full-ten/selected-one inventory, every original/
current pin and ancestor, explicit historical/current distinction, API/family/
producer/attribution and stale source/policy/scope/publication fences, every
unselected launcher method/whole class, missing/duplicate/failure/skip/incomplete
outcomes, forced termination and unknown effective profile after raw equality.
Package restoration covers originally present and originally absent states.

The four declared commands
`python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\controller\workflows.py` and
`python tools\android-port\portmap.py` each pass/exit0; whitespace passes.
The containing commit and immutable handoff SHA identify the exact local source.

This local successor awaits bounded source comparison before coherent normal
publication. Publishedf3/39529850037 is terminal SUCCESS but does not verify this
source; there is no old-run hold or verdict transfer. No real host readiness,
ADB/Gradle/APK/build/install/instrumentation/native-lane operation, new actor,
native authorization, oracle or CI-result bundle occurs. All six original
missing-only families now have current software selection support; **all27
remain unexecuted** in that cohort. Language2+2, controls and preparation subtract
zero. Historical genuineb2/r21 native outcomes/failed cleanup/separate recovery
remain byte-for-byte unchanged; no native/golden/whole-WP acceptance is inferred.

### Cached-chat direct timeout-helper pin correction

Complete source comparison of a0f735ef found no bounded blocker. Before publication,
the direct `withTouchExploration` failure-path delegate was additionally pinned:
`android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityServiceSnapshot.kt`,
producer `cc360e7bd8797bdb0847faadec5187862c093f8e`, exact producer/current/retained3f
blob `3dee4f40c5aec6fb0adb1bb406a233fb223b43a4`. This raises the explicit cached-chat
fixture/helper/production object inventory from16 to17 without changing the
binding algorithm, selected identity/count, fixture, helper or native evidence.
The existing every-pin/original/current/ancestor drift regressions now cover this
delegate; an added assertion also preserves its historical object and explicit
snapshot failure. The original failed1000ms deadline remains failed; diagnostic
collection is not native acceptance. No replay, live readiness or native action.
The same declared software suite passes168/168,0F/E/S; all four exact validator
commands above pass/exit0 and whitespace passes for this correction.

### API37 original effective-font release correction (software only)

Direct parent: `7afa4a38e1d0309052c66b98df3748996498f2ca`, still the published
PR205 candidate. The authorized comparator11 preparation exposed a concrete
pre-release defect: API37 restoration compared only raw settings/profile state,
unlike the API31 unset-font branch's effective/stability proof. Configuration-only
readiness passed on7afa with current main `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`,
manifest `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`,
policy `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3` and
same canonical reservation24. A later bounded public-host read-only probe was
stopped without a result; it establishes no fresh device/profile authentication.
No resource lane was acquired and no build, install or instrumentation started.

The coordinator directed a narrow local safety correction, not a runtime-only
subclass, monkeypatch, external adapter, provider/permission gate or CI wait.
Existing `wp-owned` modify/test/record-evidence capability rules cover these same
four Python/tests/README/evidence paths under the same canonical reservation24;
no evolution, actor change or authorization-prose edit was necessary.

The existing `ProcessDevice.capture_profile` now includes API37's finite positive
effective font scale from the actual held-lane `mGlobalConfiguration` observation,
before packaging or temporary setup. It retains the font component, not volatile
Activity configuration sequence counters. There is no inferred1.0, raw-equality
proxy or assumed original2.0. Native profile validation additionally requires
effective200%. Temporary preparation and restoration set effective-profile
uncertainty. Restoration independently reverses the existing journaled mutations,
then requires a valid original effective baseline and three exact whole-profile
observations: immediate, after0.1s, after a further0.2s. Each capture checks guest
instrumentation quiescence; each observation is journaled before its comparison.
Only error-free completion clears uncertainty. Missing/wrong/unstable effective
state, raw drift, failed reads/writes/journaling or active instrumentation retain
uncertainty and the lane. The unchanged supported release guard also recaptures
the profile, including effective font, catching drift after the stability proof.

`python -m unittest discover -s android\accessibility-tests -p test_*.py -v`:
**180 discovered/run/passed,0 failures/errors/skips**, including12 new API37
regressions through real profile methods and the real shared lane context with
synthetic transports. Cases cover actual nondefault/unset baselines, harmless
configuration sequence changes, three spaced quiescent proofs before clearing
and release, wrong/missing/invalid/unstable effective state despite raw equality,
raw drift, active instrumentation, independent restoration after probe/receipt
failure, final release-time recheck and raw200% with wrong effective font.
Existing API31 materialization/history/one-extra-deletion/deadline/diagnostic
and recovery regressions remain passing; that implementation is unchanged.

`python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\controller\workflows.py` and
`python tools\android-port\portmap.py`: each passed/exit0. Whitespace passed.
This is local source/software evidence only, not a hosted or native verdict.
Published7afa and automatic39536160679 are untouched; no push, dispatch, replay,
real host mutation/build/install/instrumentation or new native authorization
occurred during correction. Comparator11 awaits source comparison and fresh
admission. All27 remain unexecuted; language2+2/control/preparation subtract zero.
Original Kotlin/fixtures/oracles/family pins/selections, package/schema/certificate
guards and historical genuineb2/r21 evidence remain unchanged.

### Future mutation admission and failed-proof exit retention (LOCAL only)

Direct parent: `473f3860829fc0e33305c39517aef650d88b2d57`. The coordinator
authorized one local runner-only safety successor after actual473 execution
discovered25 original API37 methods:23 passed,2 failed,0 errors/skips. LoS1 and
cached-chat1 did not start. Both original Nodes assertions remain unchanged.
Original473 operations, bindings and raw artifacts are not edited or rebound.
This successor does not recover or release retained native operation
`1666502a-0420-46ef-930f-e78651ba8b9f:nodes:66bd32eca2a449ac969a53a90b384c9c`.

Trusted `wp-owned` modify/test/record-evidence admission was evaluated for the
six runner/test/README/evidence paths before editing, through the supported
same-owner `Ledger.evolve_scope` transaction. It was idempotent: canonical
reservation24 already covered these paths and operations. Canonical owner,
historical owner context, source/policy bindings and authorization prose were
preserved; actual replacement attribution remains separate. No native resource
lease operation was performed. Policy remains
`e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`,
manifest `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.

The current real `Device.require_fixture_recovery` raises an explicit error:
genuine pre-mutation fixture/preferences/window originals and a supported
snapshot-based recovery operation are unavailable. It is intentionally closed
for the current implementation, with no availability flag, new provider,
default/original reconstruction or operator override. Current-source execution
checks this before readiness, packaging, resource acquisition, profile setup,
installation or instrumentation. Direct forward pair installation and native
selection, and the historical execution CLI, also reject before their mutations.
Existing cleanup/release code is unchanged; this is not a recovery implementation.
Only the in-memory test device admits downstream synthetic lifecycle regressions.

After a future admitted instrumentation wait returns, its actual integer exit is
written immediately into the existing recovery receipt as
`host_instrumentation_process_exit`, before transcript parsing/all-positive proof.
The receipt still declares application state uncertain until genuine completion
proof succeeds. Write failures remain explicit and do not skip independent
owned cleanup. CLI exits, terminal instrumentation status and host process exits
are not interchangeable. The original473 failed-proof path did not retain its
host exit: that historical value remains unknown, not backfilled from CLI2 or
terminal instrumentation-1.

`python -m unittest discover -s android\accessibility-tests -p test_*.py -v`:
**186 discovered/run/passed,0 failures/errors/skips**, including six new focused
regressions. They prove real device admission rejects every fixed family/API
before readiness/packaging/lane/profile/install activity, standalone install and
selection reject without mutations/spawn, the legacy CLI rejects before binding
or lane work, actual host0/failed-test and host7/positive-test exits exist on disk
before proof parsing, and an exit-receipt write failure still performs independent
owned cleanup and retains uncertainty/lane. All transports/processes are
temporary in-memory fakes; no native evidence is claimed.

The four existing declared source validators each passed/exit0:
`python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\controller\workflows.py`,
`python tools\android-port\portmap.py`. No actual device/toolchain/readiness,
native/Gradle/resource-lease probe or action, publication, dispatch or new result
bundle occurred. This is a local source/software candidate for source review,
not native acceptance. Genuine-history-backed fixture recovery/unknown-state
disposition remains an explicit maintainer blocker.

### REJECTED native harness IME-inset proposal (LOCAL history only)

The following records the rejected local experiment, not an established
correction. The pinned-library finding and forward restoration below supersede
its missing-IME inference and proposed fixture pins.

Local comparison base: `8b9ecad8b37e431613eb4f042c7b9c7a511ca64e`. At that source,
`NativeNavigationShell.kt:252-263` (blob
`c9a881fcdbf605784cbf2593d77c808b41c75083`) applies outer
`fillMaxSize().imePadding()` before the inner Scaffold's `WindowInsets.safeDrawing`.
`DeviceAccessibilityHarness.kt:65-69` (original blob
`8768ee9c2b25fc1a7d43d0442980ff4deabb6d81`) omitted that explicit outer IME
padding. The native `AuditSurface` now applies `imePadding()` before its existing
safe-drawing padding, matching that inset-consumption order without copying the
app shell or changing production UI.

Original473 showed the focused nickname editor's IME reducing the viewport to
y52..475 while Save occupied y485..539. This source-contract correction does not
prove the sole failure cause, a changed measured viewport, or a native pass.
Actual IME visibility, long CJK text, font200, RTL/window measurements, Save/Cancel
scroll/visibility/geometry assertions and production Nodes files are unchanged.
The separate clipboard failure remains unresolved and unmodified.

Before edits, supported same-owner `Ledger.evolve_scope` admitted only the harness
and this evidence path through trusted `wp-owned` modify/test/record-evidence
rules, reservation24 to25. Historical identity/authorization context is preserved.
No native resource operation, provisioning, build, compile, installation, device
probe, publication or original473 evidence rebinding occurred. Published8b9 and
retained native66bd remain untouched.

The initial precommit Python run passed186/186 while source-pin tests still read
old committed8b9; that is not successor proof. Committed harness producer
`ad9a4486fddcf8a7f3b68f7c0c4bf306b8635e4b` genuinely failed four existing
current-HEAD pin assertions:182 passed,4 failed,0 errors/skips. Their expected
old harness blob no longer matched the changed fixture. No assertion was removed.

The local follow-up changes only the four diagnostics/Nodes/LoS/cached-chat
harness pins to that actual producer and its actual blob
`cd687f679c6b4dde56624f35d72e5e1344aa02af`. The original producer/blob remains in
the immutable8b9 history. This requires two local commits, not a fictitious
self-referential producer hash or an amended published commit. Before the binding
edit, its trusted `wp-owned` admission used supported idempotent25-to25 evolution.
Original family identities/order, other producer/fixture/production pins and all
test assertions are unchanged; original473 results are not rebound.

With the new real producer pins, `python -B -m unittest discover -s
android\accessibility-tests -p test_*.py -v` passed186/186,0 failures/errors/skips.
`python -B tools\android-port\controller\verification_config.py --check`,
`python -B tools\android-port\controller\validate.py`,
`python -B tools\android-port\controller\workflows.py`,
`python -B tools\android-port\portmap.py` and `git diff --check` each passed/exit0.
These checks do not compile or execute Kotlin.

No provenance guard was weakened. This entire local candidate, including its new
fixture producer pins, awaits source review before publication or native
admission. Native validation awaits the separately owned WP-003 disposable-resource
contract; reconstructing the old guest's snapshots is not this delivery path.

### Pinned-contract rejection and forward restoration (LOCAL only)

Source review rejected local `ad9a4486`/`b29713f2` as an established repair. The
coordinator independently read the actual pinned foundation-layout-android1.10.6
sources in memory: `WindowInsets.android.kt:378` defines safeDrawing as
`systemBars.union(ime).union(displayCutout)`; `WindowInsetsPadding.android.kt:143-144`
delegates imePadding to windowInsetsPadding(ime); common
`WindowInsetsPadding.kt:467-503` consumes ancestor insets and pads only the
remaining difference. Therefore safeDrawing already includes IME, and adding
outer imePadding does not establish a missing inset or changed final size.
The syntactic app/harness difference was insufficient evidence of a defect.

An ordinary forward commit restores only this worker's redundant modifier/import
and four coupled harness pins, byte-identical to published8b9. Rejected commits
and actual failed/passing software logs remain historical evidence; there is no
reset, amendment or publication. Same-owner trusted scope admission was
idempotent25-to25. No different layout/scroll/clipboard fix is inferred: both
native causes remain unresolved, original473 outcomes/native66bd are untouched,
and WP-003's independently owned disposable contract remains the prerequisite.

The coordinator reports exact8b9 automatic CI39630823752 completed all six jobs
SUCCESS, but its official controller/Linux logs did not run the186 Python runner
tests. Those local results are not hosted proof. WP-003 owns that CI integration
gap. Committed checks of this forward candidate are recorded separately; no
compile, native/Gradle/device action or new native acceptance accompanies it.

### LOCAL disposable consumer of the immutable WP-003 interface

Parent `cb7b75453e52b4e5ee381f2c4ac23f77403a07ef`; producer input only
`3490786a8b7ed099a42dbb0bbc4393dc61754ac3` (direct `6ca5e7b4`, direct `ad401`
ancestry). Candidate producer code was read through immutable Git objects, never
imported, copied into runtime or treated as trusted main. Exact reviewed inputs:

| Producer path | Git blob |
|---|---|
| `tools/android-port/controller/disposable.py` | `fa859d030831882a7f3eb54d726c5fdf6751521b` |
| `tools/android-port/controller/windows_guest.py` | `621612bb67e16432692e07a5a1b1056426747777` |
| `tools/android-port/controller/ledger.py` | `6048cf50b275b85c9028781fd80a473ef06ca6f2` |
| `tools/android-port/local/debug_packaging.py` | `7f8e5e9769c0f8d70a42678738280bf7209c17e9` |

Before edits, supported same-owner trusted `wp-owned` scope evolution25-to26
admitted only the consumer, its tests, README and this evidence. It did not admit
live disposable execution, alter authorization history or release/recreate any
reservation. `language_runner.py`, `shared_device_runner.py`, fixed family
bindings, Kotlin/production UI and original assertions remain unchanged.

The new consumer requires reviewed producer objects in actual trusted main and
matching checkout/module origins before import/allocation. Actual
`trusted_guest(repo,parent,owner,revision,session_id,attempt)` remains the authority
for canonical claim identity/state, pause/capabilities, new private allocation and
both disposable/globalGradle receipts. Packaging receives the actual guest and
`installed_apk=None` before `guest.start`; commands use only `guest.adb`.
Concrete type, canonical ledger, exact source/owner/session/reservation/private
root, package absence, user0 and inherited profile/source checks cannot be
replaced by a receipt dictionary, label, subclass, endpoint or availability flag.

The fixed original requests use an explicit180s instrumentation transport bound
supported by the producer; the old shared600s path is untouched. Actual host
exit and raw transcript are retained before terminal parsing/all-positive proof.
Evidence failures do not prevent independent disposal; primary execution and
cleanup/retention failures remain visible. Verified new-guest disposal is not
shared-state restoration, and all results retain `acceptance=false`.

Unit regressions run the actual consumer and inherited ProcessDevice methods
against explicitly bounded concrete protocol models, with the trust loader
patched only in tests. Trust-rejection tests exercise the real loader before any
import/factory/config work. These models do not acquire real receipts, import
candidate producer code, allocate a real guest, execute SDK tools or verify
backend/process isolation. All186 incumbent runner regressions are preserved.
Exact local discovery/source-check results accompany the committed successor.

Precommit `python -B -m unittest discover -s android\accessibility-tests -p
test_*.py -v` passed224/224 (186 incumbent +38 consumer),0 failures/errors/skips.
The consumer regressions include all fixed original request shapes, fail-closed
trust/type/identity checks, user0/freshness/packaging guards, actual host exits
versus a failed pre-instrumentation user-probe exit, negative/malformed proof,
bounded boot timeout, cancellation, independent receipt/raw-output retention and
owned disposal failures. The four declared source commands each passed/exit0:
`python -B tools\android-port\controller\verification_config.py --check`,
`python -B tools\android-port\controller\validate.py`,
`python -B tools\android-port\controller\workflows.py`,
`python -B tools\android-port\portmap.py`; `git diff --check` passed.
Exact committed checks are recorded separately, not inferred from this precommit
run or from published8b9's automatic CI.

Current main lacks the trusted producer. Its real factory additionally requires
an eligible existing claim state and evolved disposable-operation receipt.
Neither live prerequisite was fabricated or exercised. No native/Gradle/WSL,
provisioning, recovery, resource-lock action, deployment, publication or dispatch
occurred. Published8b9/its CI binding, rejected IME history and retained473/66bd
evidence remain untouched: original25 started are23PASS/2FAIL/0E/S, two unstarted,
old Nodes host exit unknown. Clipboard/Save causes remain unresolved. Historical
snapshot reconstruction is not the disposable delivery path.

### LOCAL consumer output-ownership and failure-stream correction

Direct parent `d482bfbbca62e97c7c5c565f6043d1b836192704` and its actual
committed224/224 result remain historical. Source review identified two missed
consumer behaviors, not producer or native failures: cleanup could overwrite a
raced output directory that this attempt never created, and instrumentation
exceptions discarded available stderr or timeout stdout/stderr.

Seven new deterministic unit witnesses against unchanged d482 consumer code
discovered231 tests:224 passed,3 failed and4 errored. The race witnesses observed
two foreign receipt writes; the stream witnesses observed missing byte files
and missing grouped retention failure. That negative log remains separate.

The forward correction sets output ownership only after exclusive `mkdir`
returns successfully, checks it before every receipt write, and conditions
cleanup receipt writes on that ownership. A failed/raced creation preserves the
original exception and foreign receipt bytes while independently disposing only
the actual owned guest. Cleanup failure remains grouped with the creation error;
no alternate evidence directory is adopted.

Only exceptions from the actual producer `guest.adb` instrumentation command
enter stream retention. `CalledProcessError` retains its actual exit and both
available byte streams; `TimeoutExpired` retains available partial stdout/output
and stderr with host exitNone. Raw stdout uses the existing transcript path;
stderr uses `device-FAMILYAPI-stderr.txt`. Absent streams create no file, and
available bytes are never decoded/replaced/combined into a fabricated transcript.
Receipt and stream writes are independently attempted, preserving the original
exception with any retention failure. Failure paths never parse a completed
cohort or claim acceptance. Current-user proof still runs outside that callback:
its own stdout/stderr cannot become instrumentation evidence.

The same four paths remain inside existing same-owner revision26 `wp-owned`
modify/test/record-evidence scope; no live scope evolution or resource action
was performed. All four reviewed349 producer API/runtime pins are unchanged.
Shared600s/fence, consumer180s, factory/transport/disposal calls, fixed original
requests/assertions, historical473/66bd and published8b9 are unchanged.

Precommit declared discovery passed231/231 (all224 existing +7 new),0 failures/
errors/skips. The new witnesses cover foreign-output races with successful and
failed disposal, nonzero stdout+stderr, timeout partial stdout+stderr, stderr-only
timeout, and independent receipt/stream failures preserving the original error
and remaining bytes. The existing no-stream timeout and pre-instrumentation
user-failure tests now also require no invented transcript/stderr file.

`python -B -m unittest discover -s android\accessibility-tests -p test_*.py -v`,
`python -B tools\android-port\controller\verification_config.py --check`,
`python -B tools\android-port\controller\validate.py`,
`python -B tools\android-port\controller\workflows.py`,
`python -B tools\android-port\portmap.py` and `git diff --check` each passed/exit0.
Exact committed checks accompany the forward candidate separately. These are
temporary consumer models only, not Linux/Windows backend or native proof.
Publication remains held for source comparison; trusted-main producer/deployed
receipt/eligible claim remain unresolved live prerequisites. No native/Gradle/
guest/lease/recovery/deployment/dispatch/rerun operation occurred.

### LOCAL clipboard observation synchronization (source only)

Direct input `e60eba372f688fc6ff4ab63162b9e4ad4e0beeca` already contains the
231 local Python regressions, including all45 consumer models; this is a new
fixture-only successor, not another consumer/model family. Before editing, the
actual CLI/app session resolved to `b531305a-0513-4731-896f-2b470f8879cd` in the
replacement worktree/branch. The stored app alias `df52b3d0` resolves to that
same active session; it is not a second actor. Supported same-canonical-owner
`wp-owned` source evolution26-to27 admitted this fixture and evidence only,
preserving the original reservation identity/branch/worktree audit context.
The real replacement branch differs from PR205's historical head ref; no
retarget/publication is performed or inferred.

At e60, `NodesListScreen.kt:492-495` calls `holder.copyPublicKey()` before
`clipboard.setPrimaryClip`. `ContactQrShareStateHolder.kt:60-62` triggers copy
feedback before returning the key; its feedback coroutine reaches the fixture's
clock sleep independently of proof of a clipboard result. Therefore the existing
`waits == 1` assertion is feedback evidence, not a clipboard-completion barrier.
This ordering does not establish the cause of the historical native failure:
focus and external clipboard replacement remain unresolved.

The shared Nodes fixture keeps that original feedback wait, then uses the
existing Compose `waitUntil` with an explicit5000ms bound to observe one actual
Android primary clip containing BOTH the original contact display name
U+4E2D and the exact uppercase32-byte `AA` key. Each observation reads the actual
clipboard, with no fixture write, injected payload, sleep, focus change or
suppression of another writer. After the wait returns, both original exact
assertions use a separate FRESH primary-clip snapshot, not the transient matching
observation. A timeout or changed final snapshot remains a failure. Original
feedback/reset/share-intent assertions, all method identities, selections,
inline Save/font200 behavior and production Nodes code are unchanged.

The existing JVM `NativeNodesAccessibilityTest` already invokes this shared
fixture, but no new focused JVM seam/helper/test family is introduced for this
direct framework observation. Kotlin compilation, that JVM case and native
execution are NOT RUN under this source-only assignment. Only the four existing
declared Python source validators and committed whitespace checks are used;
their exact results accompany the local commit, not a clipboard-behavior claim.
The earlier231/231 result remains e60-only, not this successor's test result.

The strict Nodes runner still pins the historical shared fixture at
`9101b45f`/`29a0f83d`; those immutable bindings are deliberately not weakened or
rebound to an unreviewed self-referential commit. A future admitted run must bind
the actual reviewed successor through a separate provenance update before it
can pass that unchanged source guard. This does not block this local fixture
commit or constitute native authority. The four349 disposable producer pins
remain unchanged; trusted-main/runtime/eligible-claim/typed live receipts remain
independent future gates. Historical473/66bd,25 starts/23PASS/2FAIL/2unstarted and
unknown old Nodes host exit are unchanged. No source publication, local Gradle/
WSL/native/guest/device-resource/recovery/deployment/dispatch/rerun/merge action
or snapshot/restart/wipe request accompanies this candidate.

### Merged disposable prerequisite and actual installed-image incompatibility

Trusted PR260 merged as `ea67cd867e7fc178046ee2829ff059b1734ef1b3`.
Ordinary merge `bd0f65031af32bdb8fad196b92bf29524e75b5b6` retains reviewed
clipboard producer144 unchanged. Supported same-owner source admission27-to28
covered the31 incoming paths under `merge-integration`; the subsequent owned
binding/support admission was idempotent28-to28. Stored identity/authorization
history and original473/66bd were preserved.

The Nodes shared-fixture pin now identifies the actual reviewed144 producer and
blob469f, while its production source, native launcher, seven original methods,
assertions and all other family/helper pins are unchanged. All four349
disposable API blobs exactly match merged trusted main; no producer repin was
needed. The coordinator explicitly directed the existing seven-case Nodes
family, rather than adding a two-method selector. No selector was implemented.

Before allocation/build/guest access, actual `DisposableConfig.validate(37)`
against the retained `wp503-current-local-inputs/environment.json`, installed
emulator/ADB, existing default debug-home metadata and installed
`system-images/android-37.0/google_apis_playstore/x86_64` failed with:
`PortError: Installed image API differs from the immutable selected family request`
at the original consumer line115. Actual source.properties declares
`AndroidVersion.ApiLevel=37.0`, revision6. The other installed image declares
37.2; it is not substituted. Trusted producer `disposable.py:226` independently
accepts only literal31/37, so it would reject this genuine base image too.

The owned consumer now accepts only the selected integer API or its exact `.0`
base spelling. Existing regressions additionally reject37.2,37.00, malformed,
empty and wrong-API declarations; its existing seven-case model uses37.0.
No tests/methods/families were added. This is source/model evidence, not a
producer/backend/native verdict. The exact producer correction was routed to
the existing WP003 owner; no image metadata is rewritten and no producer gate
is bypassed. The concrete remedy is supported base37.0 image admission in that
trusted implementation, retaining its provenance/private-process checks.

The first integrated231-test run had230PASS/1FAIL: the former missing/foreign
checkout regression assumed the real checkout lacked producer files. Merged
main legitimately supplies them, so the test reached a later synthetic policy
error instead. That existing test now uses explicit temporary missing/foreign
checkout bytes, rather than relying on ambient absence or weakening the guard.
Its original failure log is retained separately from the corrected run.

Existing user `.android/debug.keystore` was confirmed by metadata only, without
reading/exporting/copying its bytes. No new key, vendor bridge, snapshot,
restart or wipe is required. Exact checks and subsequent source/publication
results accompany the successor. No fresh allocation, build, boot or native
start occurred at this configuration failure; both historical Nodes failures,
25 original outcomes, two unstarted flows and retained66bd remain unchanged.

### Published4936 actual disposable build and pre-launch image-format failure

Exact `4936fbbd21e75cc70fac88bed5e7ceede2614013`, directbd0/main-baseea67,
was ordinarily published to the existing ready PR205 head ref. Its corrected
LOCAL source suite passed231/231,0F/E/S; four declared source validators and
whitespace passed. The earlier230PASS/1FAIL integration result above remains
separate. Source publication usedr28; no producer pin or original family changed.

Supported trusted `disposable-native-guest` admission for
`tools/android-port/controller/disposable.py` /
`execute-private-disposable-avd` evolved the SAME canonical owner28-to29.
Supported `Ledger.transition` moved that existing claim from leased to running.
Identity, authorization context and audit history were preserved. The actual
factory, not a consumer double, independently allocated the private guest and
disposable/global-Gradle receipts for unique owner
`1666502a-0420-46ef-930f-e78651ba8b9f:nodes:769ddaa08443430dbf5046695726a117`.
No original guest/66bd action or claim of restoration accompanied this.

The actual declared `disposable_runner.py --head 4936fbbd21e75cc70fac88bed5e7ceede2614013
--api 37 --family nodes --execute` invocation used the existing environment,
default debug-home, installed emulator/ADB/base37.0 image and exclusively new
external output. Exact absolute arguments, owned process/receipt records and
logs remain private under `wp503-4936-nodes37-disposable-a1`.
Owned Windows `:app:assembleDebug :app:assembleDebugAndroidTest
--dependency-verification strict --no-daemon
-Pkotlin.compiler.execution.strategy=in-process --max-workers=1 --console=plain`
completed **BUILD SUCCESSFUL**,2m23s,446 tasks:9 executed/437 up-to-date.
The source-bound App/Test packages verified with the existing public certificate
`000be798aca5806230ec26d9feee36cbd0acadc857cf22a4ba711f43a4a2c8b7`;
App SHA256 `d3490e09a2defed5ef6c0190bf1ca3b924e0617e7e2eb4f0f1725a272e0e84ae`,
new Test `20f20334f3785fa48b31d23666917a0f14f3e3c8d403a0fc33b4e5f1cd029672`.
The identical App digest does not transfer473 native acceptance: the actual
new fixture compiled into the independently checked Test APK.

CLI exit2 occurred at ACTUAL `guest.start`, producer `disposable.py:221`:
`PortError: Installed system image lacks regular initial userdata/provenance`.
The installed base37.0/revision6 package has no top-level `userdata.img`.
Instead its regular107-byte `data/empty_data_disk` states:
`This special file tells the host side to keep the data partition empty.`
and references `b/326479561`. This is a concrete modern installed-image format,
not permission to copy existing guest data or fabricate an image/default.
The existing WP003 owner received this exact producer input alongside its
separate literal37.0 correction. No local producer implementation was changed.

Independent finally cleanup verified disposal of only the new Job/guest and
its receipts: `disposal_verified=true`, no disposal error, instrumentation host
exit null/UNKNOWN, no cohort,0 emulator/ADB/instrumentation starts or acceptance.
The original Nodes failures/unknown exit,25 original outcomes, two unstarted
flows and retained473/66bd remain unchanged. No snapshot/restart/wipe request.

### Owned cross-platform signer import correction after4936 hosted failure

Own automatic run39683578750 completedFAILURE for exact4936. Its official
controller119512015394 log discovered506:502PASS/4 importERROR,0F/S.
All four errors arose while importing `shared_device_runner.py:22` from
`test_disposable_runner`, `test_language_binding`, `test_language_runner`
and `test_shared_device_runner`. A Windows ADB literal becomes one PosixPath
component on Linux, so `Path(ADB).parents[1]` raised `IndexError: 1`.
Android/codec/SwiftData were dependency-skipped; aggregate failed. The official
log was read in memory, not copied into another CI-result bundle.

The surgical owned correction derives the historical Windows signer using
`PureWindowsPath`, preserving its exact existing Windows location and argv.
It does not claim a Linux SDK, add a tool fallback, suppress imports, skip a
family or alter any native assertion/selection/deadline. Source/test/evidence
admission was idempotent29-to29; no controller, policy or producer pin changed.
One focused test in the existing suite imports the actual module with Posix
interpretation of that Windows literal, checks no subprocess at import, and
checks exact original Windows signer argv separately with a bounded model.
It genuinely fails with1ERROR/IndexError against immutable4936 production bytes
(negative-v2 traceback binds the Git object, not the changed local linecache).
The corrected complete declared Python suite passed232/232,0F/E/S,17.195s:
all231 incumbent regressions plus this one import regression. This is LOCAL
source/command-construction evidence, not Linux SDK or native case credit.
The four declared source commands (`verification_config.py --check`,
`validate.py`, `workflows.py` and `portmap.py`, each via `python -B` at the
existing documented path) and `git diff --check` also passed, exit0.

### Binding actual merged producer262 for the next original Nodes7 attempt

PR262 actually merged reviewed producer
`b8eaf22c1ee6a0a467b9dbfc7d5504dcf3ca9b1b` into trusted main
`35265173f2ae1765469c751d6d0eb66a82055c00`.
Ordinary merge `90e0962cd3a24014bdc9a2c7e7b746a349b8b3d2` integrates only
its three actual incoming paths under supported idempotent29-to29
`merge-integration` admission. The entire accessibility subtree was unchanged
by that merge; reviewed144 fixture,59f portable signer correction, all original
assertions and seven-case Nodes default remain intact.

The actual b8 producer changes only the disposable runtime input to
`e5875aadd94c81aedd359aed87bcf3fed8b088cb`. Its Windows guest, canonical ledger
and packaging blobs remain respectively `621612bb67e16432692e07a5a1b1056426747777`,
`6048cf50b275b85c9028781fd80a473ef06ca6f2` and
`7f8e5e9769c0f8d70a42678738280bf7209c17e9`, directly verified from actual objects.
The owned consumer pins only that changed runtime blob plus its actual producer
revision; no untrusted branch code, alternate ledger, availability flag or
local producer implementation is substituted.

The reviewed producer accepts exact base37.0 while retaining37.2/malformed
rejection. Its fresh private output can now be initialized by the emulator from
the installed package's explicit regular `data/empty_data_disk` layout; legacy
regular `userdata.img` copying remains separately supported. Both paths require
new exclusive data, not a copied old guest, fabricated userdata or wipe.
Merged source is a genuine executable prerequisite, not a native pass.
The next owned attempt must still pass all actual source/publication/runtime/
claim/receipt/process/transport/profile/install/instrumentation/disposal checks.
Historical4936 build/start failure and verified disposal remain4936-only;
473/66bd,25 original outcomes/two failures/two unstarted flows remain untouched.

### Actual6deb continuation: modern initialization passed, private ADB listen failed

Exact published `6deb1a6a7aabe3d38726a1588955bcabeaa188bd`, direct90e0962c,
contains the actual reviewed b8 binding above and main352. Its declared source
suite passed232/232,0F/E/S,15.730s; four source validators and whitespace passed.
Independent readbacks confirmed existing PR205 OPEN/ready at6deb/base352.
Own automatic39687254360 is distinct from every earlier result.

The original seven-case Nodes CLI actually ran through the supported fresh
factory under new exclusive owner
`1666502a-0420-46ef-930f-e78651ba8b9f:nodes:37236ec861104e409173da402557eec0`.
Its actual Windows debug pair again built/verified: BUILD SUCCESSFUL2m1s,
446 tasks:5 executed/441 up-to-date. The reviewed37.0 and modern empty-data
initialization checks passed, with no old data or SDK-image modification.

Actual private ADB-server launch then exited before emulator spawn:
`PortError: Owned Windows transport process exited`, producer `_owns_listener`
line377. Its actual private `process.log` reports:
`F adb.exe : main.cpp:168 could not install *smartsocket* listener: listening on specified hostname currently unsupported`.
The producer uses `adb -L tcp:127.0.0.1:<private-port> nodaemon server`;
this installed native Windows ADB rejects that hostname-bearing LISTEN syntax.
The concrete bounded producer correction was routed to existing WP003: use
supported hostless loopback-default listen syntax while retaining separate
client endpoint/owned-PID validation. No local producer override or unchanged
retry was used. Exact CLI/logs/receipts remain private under
`wp503-6deb-nodes37-disposable-a1`.

Independent finally disposal VERIFIED true, no disposal error. One private
ADB-server start failed;0 emulator/instrumentation starts, host instrumentation
exit null/UNKNOWN, no cohort/acceptance. Original473/66bd remains untouched.
Separately, historical59f own39685529526 completedCANCELLED after newer source
publication: controller726/OK and codecSUCCESS, Linux/SwiftDataCANCELLED,
aggregateFAILURE; no manual cancel/rerun or overall59f success is claimed.

### Actual merged263 listener binding and current-main continuation

Reviewed producer `85d35dc3fc47e5fda5a0e08687ff7c9dfb84bd3c` actually merged
as trusted main `e121fb8989a77f1c05d5bb5b767b4abd5b271d60`.
Ordinary integration `6f05d57b42bc75cd1b3255451867e9715db2fe12` includes
that producer and independently merged UI249/f3a3fa65, preserving its actual
Rooms/RepeatRow changes. Supported `merge-integration` admission29-to30 covered
the40 actual incoming paths and retained identity/authorization history.
The owned6deb failure chronology draft was disjoint and preserved, not reverted.
The complete accessibility subtree was unchanged by the main merge.

The consumer advances only its actual producer revision and disposable runtime
blob to `b16cb30d11e2e8fd0546164d471bdc53abfa0b77`. The other three API input
blobs remain exactly `621612bb67e16432692e07a5a1b1056426747777`,
`6048cf50b275b85c9028781fd80a473ef06ca6f2` and
`7f8e5e9769c0f8d70a42678738280bf7209c17e9`, verified from actual85d objects.
The trusted producer's sole production change is the server's supported
hostless `-L tcp:PORT`; it keeps the explicit127.0.0.1 client, no `-a`, and all
actual Job/listener/identity/transport/disposal checks. No local backend override
or unchanged startup retry. The original Nodes7/default/144 assertions and59f
portable signer remain unchanged; no language replay or old473/66bd operation.
Each next source/pair/start/native result must bind its own new exact candidate.

The first post-main232 check found231PASS/1FAIL at the existing cached-chat
whole-Feature-tree provenance assertion, not a Nodes/native failure: approved
UI249 genuinely changes `MessageActionsUi.kt` (RepeatRow fallback affordance)
and `RoomConversationScreen.kt` (incoming avatar/bookend layout). Their complete
actual83-line production delta was read before updating the owned binding.
The cached-chat current production tree is now independently bound to actual
reviewed producer `32bb28c6ea08f697ee69222f775237c190460883` /
`ea4fbdfcd7100b5c48b48b6a5b5e5bbcd795134e`, not inferred from HEAD.
Its original fixture producer/blob, all ten fixture methods, selected original
one method, timeline/rendering/helper pins and separate033 current delegates are
unchanged. The existing provenance test now also preserves the historical4b/
fbf tree and verifies the exact two-file reviewed production delta. No assertion
is removed or weakened, no native case is replayed and no old result is rebound.
The actual failing first log remains separate from the corrected check.

### Actual7b3 startup: host capacity and selected-transport registration

Published `7b3c0087718e9e23eb7f35cdd41740b8b3dbf004`, direct
`6f05d57b42bc75cd1b3255451867e9715db2fe12`, retained the corrected232/232
source check and original Nodes7 selection. Its own automatic39689748278
official Controller119533384175 selected accessibility and ran732 tests/OK;
that includes consumer/source models, not Windows backend or native acceptance.
Historical6deb own39687254360 completedCANCELLED, not success.

The first actual7b3 fresh guest, owner suffix
`nodes:1daf4d4b6e434b84b380bdba2064f354`, reached emulator launch but failed
its first selected `shell getprop sys.boot_completed`. Actual private ADB bytes
were `adb.exe: device 'emulator-5558' not found\r\n`. Its process log established
a concrete host-capacity failure: SDK increased RAM to4096MiB; committed
70978.667969MiB/limit73491.687500MiB left2513.019531MiB, below4096MiB needed.
Independent disposal verified true/no disposal error. No instrumentation,
boot observations, cohort or known host instrumentation exit.

The coordinator reclaimed only independently verified idle owned CLI processes,
not preserved worktrees, old guests, shared applications or OS/pagefile settings.
The wait decision was coordinator-selected on the user's behalf under the
existing autonomous assignment, not a new direct-user permission event.
An immediate live observation found6442.06MiB commit headroom before a genuinely
new7b3 attempt; it was not a resource reservation or guarantee.

The new owner suffix `nodes:c01a793f396f433dae75b28470bb2e2b`, private root
`private-avd-0_wvm9gf`, actually built/verified the current-source App/Test pair:
BUILD SUCCESSFUL2m,446 tasks/5 executed/441 up-to-date. Public APK SHA256 values:
App `2fe596b391edbfd82b9dff0aade204bc0b88ba5fdaedec13a95dc0ac33a9280f`,
Test `20f20334f3785fa48b31d23666917a0f14f3e3c8d403a0fc33b4e5f1cd029672`.
These are7b3-only build results, not successor proof.

Its first selected getprop again exited1 with the same exact ADB bytes.
This time the retained emulator log had no insufficient-RAM/error/fatal entry;
it ended during initial emulator/WHPX startup. Source inspection identifies
the real readiness mismatch: producer `start` establishes owned listeners,
not selected ADB registration, while the consumer immediately queries getprop.
Producer `adb` marks a nonzero command uncertain; swallowing/retrying that
failure would violate its contract. This does not prove eventual Android boot
or explain either historical clipboard/Save failure.

Second-attempt independent disposal also verified true/no disposal error,
zero instrumentation starts, no boot observations/cohort, unknown host
instrumentation exit. Actual CLI, packaging, operations and private process
logs remain separately under `wp503-7b3c-nodes37-disposable-a1` and `-a2`.
Original473/66bd and its25 outcomes/23 positives/two failures, unknown Nodes host
exit and two unstarted flows remain untouched.

The directly coupled consumer correction waits for exact selected
`["wait-for-device"]` through actual `guest.adb`, using only the remainder of
the existing120s deadline before the unchanged strict framework probes.
It checks the real command result and propagates failure; no catch/retry,
new sleep, extended boot/instrumentation deadline or shared600s fence change.
Only a genuinely reviewed merged producer supporting that exact command may
be pinned before live use; source preparation is not runtime admission.

Two focused methods in the existing consumer suite cover transport-before-
getprop order/budget and nonzero/timeout propagation with independent disposal,
no profile/install/cohort/transcripts and unknown instrumentation exit.
Against unchanged7b3 consumer production, the declared234-method suite failed
with two failing subtests and one error in15.778s. After the consumer correction,
the same declared command passed234/234 in16.137s:
`python -B -m unittest discover -s android\accessibility-tests -p test_*.py -v`.
The original232 and all earlier224/231 negatives remain historical evidence.
These additional checks use bounded consumer doubles, never native/backend
proof. The four declared source validators and `git diff --check` also passed.

Actual reviewed producer `759f2d83b783834f802687ff0a42a8a57b1056e3` then
merged as trusted main `2e0a1979b7d6f92e3de70095cee32e97a55aa58b`.
Same-owner supported integration remained revision30 and admitted exactly
the three incoming producer/test/deviation paths; all disjoint owned edits
were preserved. The consumer now pins actual disposable blob
`858f12778cb16da35fd52dadab5d1700bb399a2c`; the other three actual API inputs
remain `621612bb67e16432692e07a5a1b1056426747777`,
`6048cf50b275b85c9028781fd80a473ef06ca6f2` and
`7f8e5e9769c0f8d70a42678738280bf7209c17e9`.
The producer admits only exact selected `wait-for-device`, preserving its
actual private endpoint/serial, ownership, finite<=180s bound and uncertainty.
Its own39692949518 official controller503/OK is producer-model evidence,
not native readiness. The original Nodes7/144 delegates, assertions,120s
total boot budget,180s instrumentation and shared600s fence are unchanged.
The consumer regression also measures119s for registration followed by only
15s/14s remaining for its two framework probes; it cannot restart the budget.
Final integrated source passed234/234 in16.304s with the declared Python
command above. Also PASS:
`python -B tools\android-port\controller\verification_config.py --check`,
`python -B tools\android-port\controller\validate.py`,
`python -B tools\android-port\controller\workflows.py`,
`python -B tools\android-port\portmap.py`, and `git diff --check`.

### Actual81b: selected transport and Android boot succeeded, animation contract failed

Ordinary published `81bacae938a8a41a47122dea68b8cc21fcb7076e`, direct
`71835ac6028066c4b0d23e7b509808cce274922b`, binds the actual merged759/858f
producer and same-owner revision30. The actual committed declared Python
suite passed234/234 in18.524s, separately from its precommit16.304s result.
Own39693898918 official Controller119546576286 selected accessibility and
ran737/OK in101.208s, with both new registration regressions explicitly passing.
This is234 consumer/source models plus controller coverage, not backend/native
acceptance. No overall CI success or parent build result is inferred.

After an immediate actual5279.20MiB commit-headroom observation, a genuinely new
private attempt used owner suffix `nodes:0d0cc9bd101548d0b6ff76512c0ad2ca`
and root `private-avd-q96afixr`. Its own source-bound Windows pair was built/
verified before startup. The real selected `wait-for-device` completed normally.
Actual emulator log reports `Boot completed in 108371 ms`; all39 retained
framework observations were `sys.boot_completed=1` and `init.svc.bootanim=""`.
The unchanged120s deadline expired because completion still required the
normal `stopped` animation service, not because Android never booted.

The actual producer command contained `-no-boot-anim`; its process log
explicitly set `androidboot.debug.sf.nobootanimation=1`. That flag suppresses
the service which both `wait_boot` and inherited `ProcessDevice.capture_profile`
require to have stopped. The surgical producer correction is removal of that
flag, not accepting an invented/stopped value, relaxing either guard, starting
the service manually, swallowing failure, retrying the disposed guest or
extending120s. The selected registration correction itself genuinely worked.

Actual hardware config recorded two CPUs and4096MiB RAM. Four CPUs belong to
the fenced historical Pixel10 endpoint identity, not a current disposable
requirement: the current private profile requires positive observed CPUs,
and the original selected cases contain no CPU-count assertion. The earlier
suggestion to set four was an inference from old observation, not an approved
current contract. No CPU/config change is needed or claimed.

Independent disposal verified true/no disposal error; zero instrumentation
starts, profile/install mutations or cohort, and host instrumentation exit
remains unknown. Exact actual logs/operations remain under
`wp503-81ba-nodes37-disposable-a1`, with sanitized retained boot observations
in `wp503-81ba-disabled-bootanim-evidence.json`. Original Nodes7/144 assertions,
the unresolved clipboard/Save causes and old473/66bd remain unchanged.

Reviewed producer `b67f3351843232032ba6809fb2cf8ff846f4de1b` actually
merged as main `1be2e311457fcbb0abc0e1fdf5426b207b63a962`.
Supported integration stayed at the existing revision30 and preserved every
disjoint owned draft byte. Consumer code changes only its producer/input pins:
actual disposable blob `5a85d26686fa6b151a91da5bc46f8c6b7e0d3cf4`;
the other three API blobs are unchanged. The producer removes only the
animation-suppression argument; CPU/config, source/transport/receipt/disposal
guards and the original strict consumer predicates/deadlines are unchanged.
Own producer39695174630 controller504/OK is source-model evidence, not native
boot/flow proof. Local preparation explicitly rejected the unmerged input
against old trusted main before factory/allocation; merged source must still
pass all actual live admission and fresh-capacity checks.
The pin-only integrated successor passed the same declared234-test suite
in16.679s, all four declared source validators, and `git diff --check`.

### Actualc9: current-source pair succeeded, selected registration timed out

Published `c9aa8bfc29aed3c81a74e883fc72b8d2db54d814`, direct
`b37afd8ef35c4c7bc325d3c0e3cedfd4107ed745`, used the actually merged
`1be2e311457fcbb0abc0e1fdf5426b207b63a962` authority and reviewed
b67/5a85 producer inputs above. Immediately observed host commit headroom
was4980.22MiB, above the actual SDK4096MiB minimum; this was an observation,
not a capacity reservation or guarantee. Exact main authority was serialized
against another pending merge for this attempt, then released after disposal.
Runtime adoption did not occur and was not assumed; preserved old66bd and
both old guests remained untouched.

The genuinely new owner suffix `nodes:e8e3b8b40db9468fbf48e879a0612089`
used root `private-avd-w7jsudfo` and exclusive output
`wp503-c9aa-nodes37-disposable-a1`. Its own Windows build actually succeeded
in2m6s with446 tasks,5 executed/441 up-to-date. Actual App/Test pairing,
source/schema checks and matching public debug signer observations preceded
startup; no parent build/native proof was transferred.

Selected `wait-for-device` then raised
`TimeoutExpired(['owned-process', '42848'], 120.0)` within the unchanged
original boot deadline. There were zero framework boot observations,
profile/install mutations or instrumentation starts; original Nodes cases
started0/7, no cohort exists and host instrumentation exit remains unknown.
CLI exit2 is not an instrumentation exit. Independent `guest.dispose` and
`verify_disposed` actually verified true with no disposal error.

The actual151-line host startup log records successful compatibility checks,
WHPX operational and netsim startup, but no Android boot-completion line,
explicit RAM failure or fatal error. Actual config remains two CPUs,
4096MiB RAM and SDK-expanded6GiB data. Animation suppression is absent.
The selected ADB log is empty. Retained crashpad metadata contains no crash
dump, and the bounded matching Windows Application crash-event query found
none; neither absence proves that the guest booted or identifies a cause.
Without guest-kernel startup output, kernel progress and transport
registration cannot be distinguished from these observations.

Exact operations, current-source build/pairing and private host logs remain
retained in the two named session artifact directories. This failure is
separate from7b3's explicit RAM failure, its early non-waiting getprop failure,
and81b's actual108371ms boot with suppressed animation. No unchanged retry,
deadline extension, post-timeout guest probe, assertion/layout change or
invented configuration repair was performed. A narrowly scoped request for
producer-owned startup diagnostics was routed to the existing coordinator;
it does not claim a demonstrated new producer defect or authorize a new worker.

### Merged514: current cached-chat production, original fixture unchanged

After verifiedc9 disposal released the exact-main serialization, WP-501
normally merged as `514c451653531da9e3cc47139a9e4e44fb9d6cf0`, with
parents1be2 and reviewed `4f3889fb81c0dae33cc4e2ddaf6ad0edf9b317ba`.
Supported capability admission evolved the same canonical reservation30 to31,
retaining authorization context. Integration `ab31fd10013eb4311c0ebec81a37348988ccee33`
preserved the entire accessibility source tree and the committedc9 failure
record byte-for-byte. Its single textual App Gradle conflict was disjoint
test-source registration: both the existing WP-503 test/androidTest/shared
directories and incoming WP-501 test-only parity directory were retained.
No production implementation, fixture, assertion or native deadline was edited
to resolve it.

Before updating owned source bindings, the actual declared Python suite ran234
tests in15.798s and failed exactly three subtests on the changed current
cached-chat production inputs. The merged complete chats tree is
`49528b80d06929a403597b389fafb0e8a090d6e6`; current AppChatsFeature is
`11d18815632db5298a0552636ac86a7c73a657d4` and AppMessageContent is
`dc2633bc24ee95addc79c3a9869257934cf402a1`. Those three bindings now
point to the actual514 merge, not PR165's standalone tree, which differs from
the disjoint Rooms/message-actions composition. Other existing source pins
remain unchanged. The provenance regression preserves its earlier4b/32bb and
retained/033 checks and separately verifies the exact merged current inputs.
The original fixture producer remains e8fa6b29/blob698d649b, with all ten
original/control methods and exactly one selected cached-chat case. This
source reconciliation creates no native execution credit.
Corrected integrated source passed
`python -B -m unittest discover -s android\accessibility-tests -p test_*.py -v`:
234/234 in16.666s. Also PASS:
`python -B tools\android-port\controller\verification_config.py --check`,
`python -B tools\android-port\controller\validate.py`,
`python -B tools\android-port\controller\workflows.py`,
`python -B tools\android-port\portmap.py`, and `git diff --check`.
Neither this integration nor its source models have a current-head Windows
pair, guest boot or original native result yet.

### Prepared93: real unmerged-source admission still fails closed

While reviewed PR266 remained unmerged, the consumer prepared exactly two
literal updates to producer `93ae678595ee3efc874f5e9eb223a715563ac601`
and disposable blob `b2695d378ef06aafe5d741c3d736ed16ee14cbad`.
The other three API inputs were independently compared with actual immutable93
objects and remain unchanged. The producer's only execution change is supported
`-show-kernel` output through the existing owned process log; no boot/config,
CPU, selected command, deadline or cleanup rule changes.

Supported owned admission stayed31 to31 with unchanged authorization context.
Invoking the actual prepared `trusted_producer` against real main514 explicitly
raised the missing/different trusted-main contract error for disposable.py
before any producer import or factory call. Observed producer imports0,
factory false and native starts0; no execution CLI, allocation or new guest
was used for this negative witness. The declared full consumer suite with
these prepared pins passed234/234 in44.820s. These source models and negative
admission do not supply runtime availability or new native proof. Publication
and the next changed diagnostic execution remain conditioned on actual trusted
integration, current-source pairing and all existing live guards.

### Actual423: reviewed kernel diagnostics integrated, not native proof

PR266 actually normally merged as
`423b29b568a096c00374d241b2d8d408ec1e27ee`, with independently verified
parents514 and93 and matching live-main readback. Supported integration
`3ddc38db350eb7faa24177146319b62d3f2d5f1b` remained31 to31 and
preserved all five owned consumer/binding/test/README/evidence files byte-for-byte.
Only the three actual producer paths arrived. Disposable is now actualb269;
the other three immutable API inputs remain unchanged. The original strict
120s registration/boot deadline, CPU configuration, Nodes7/144 assertions and
independent disposal are unchanged.

The source review and genuine trusted aggregate are distinct from an
individual job-metadata discrepancy. Official aggregate119561646437 explicitly
requires and records Actions-engine `CONTROLLER_RESULT=success`; official
controller119560631499 log actually ran619/OK in110.870s with the new kernel
retention regression positive. Its raw job/check-run metadata had still said
in_progress. An additional individual-metadata-finality hold was inferred,
not a configured requirement, and was retracted after reading the actual
trusted aggregate contract. No check/status rewrite, rerun, dispatch or
privileged bypass was performed. These identities reference the authoritative
hosted result, not a retained second CI-result bundle or native evidence.

Actual current-source pairing, fresh guarded guest execution and original
native cases remain separate, unverified steps for this integrated candidate.
The actual integrated source passed
`python -B -m unittest discover -s android\accessibility-tests -p test_*.py -v`:
234/234 in16.717s. The four declared verification-config, traceability,
workflow and portmap checks above, plus `git diff --check`, all passed.
The advisory local Gradle fast check was not run; publication remains an
explicit unverified candidate pending its own exact-head hosted verification.

### Actual7949a1: assembly positive, packaging descendant guard failed

The coupled candidate actually published normally to existing PR205 as
`7949bd956601ea11a957aa8d218a92a5e2767d96`. Its own automatic candidate
run39701515172 was in_progress at admission, not borrowed c9 CI proof.
Fresh 2026-10-22T00:45:28.7318508Z admission observed 7044.12MiB commit headroom and
5621MiB physical availability; actual source/main423/policy/pause/identity,
published-head and reservation31 guards passed before the genuine new
private factory. Exact source binding was Android tree55b2f08af240006d562455e5229b2b1bd19f91bb
and digest04f0e921e633928ae37312937c485b9f780860ff98437ff54cd9ba991204360e.
Actual owner was
`1666502a-0420-46ef-930f-e78651ba8b9f:nodes:df3e77034bb54b1397b96d93f376b47f`;
fresh retained root was private-avd-ywidu9bc, not an old guest.

Actual declared App/Test assembly succeeded in 3m52s, 446 tasks
(15 executed,431 up-to-date). But exiting the real packaging lane raised
`PortError('Packaging descendants remain; retain Gradle and private guest ownership')`.
No admitted pair receipt or emulator process.log exists. Therefore there
was no emulator start, kernel observation, registration/boot observation,
profile/install mutation, instrumentation or original Nodes case:0/7.
Host instrumentation process exit remains UNKNOWN; CLI2 is not that exit.
The new kernel flag was not exercised. This is a different, earlier failure
than c9's genuine selected-transport120s timeout, not a diagnostic boot result.

Independent finally disposal actually verified true without disposal error.
Supported ledger readback found no remaining hard lock for this new owner
and confirmed the protected66bd lock still retained. Main serialization was
released only after that actual disposal signal. Old473/66bd were untouched.
The same attempt's operations.json, packaging/debug-build.log, four owned
command outputs and CLI traceback remain in the existing session artifacts;
no credentials or hosted CI-result bundle are copied here.

Bounded source inspection finds the packaging exit checks actual Windows
Job active membership once, immediately after the normal yielded operations;
run_build/capture wait for their individual root handles. The actual check
observed positive membership, but no PID/count/quiescence-duration snapshot
was retained before independent cleanup. Persistent descendant versus
termination-accounting timing is unresolved; neither is manufactured as
the cause. No guard was relaxed, producer-owned code duplicated, stale
guest probed or unchanged native retry launched. Further native families
remain blocked on this actual packaging postcondition, not on CPU/config,
strict120s boot semantics or the original native assertions.

An existing-reservation consumer-only regression now models this precise
packaging descendant exception after normal packaging operations. It checks
the same primary exception survives, only package/dispose events occur,
instrumentation exit stays unknown, no pair/cohort/native mutations are
credited, and independent disposal verifies. The full declared consumer
suite actually passed 235/235 in16.591s, including that case explicitly ok.
This is a downstream protocol model, not producer quiescence or native proof.
The existing producer owner is separately repairing bounded natural owned
quiescence with actual emptiness required; no producer-owned implementation,
native retry or isolated publication is made by this consumer change.
The same four declared source validators and `git diff --check` also passed.

### Actuald708: normal quiescence-producer integration for a new candidate

PR267 actually normally merged as
`d708b2724d28ba675017514e3f125db982070f6f`, independently refreshed with
parents423 and06bf598d7a2eabbd8eebcfd31f0e3df3eaff36d2.
Supported merge7c3166b0f89d457253d83e15b39b3cd6b78b96ce admitted exactly
the same three producer paths through merge-integration31 to31, with original
authorization context unchanged and all six owned consumer/regression/binding/
README/evidence files byte-preserved. Localbeb regression and retained actual7949
failure remain part of this coupled candidate; the235 model result is not
public7949 source proof.

Only two executable literals advance: reviewed producer06bf and disposable
8a538d47b22507765418d30ea546da100066254e. The other three API inputs,
original Nodes7/144, cached e8/698/current514 bindings, CPU configuration,
strict boot120s and instrumentation180s remain unchanged. Producer quiescence
observes natural emptiness within2s/20ms polls, requires a unique retained
JSON report, records actual Windows count/PIDs unknown or full Linux owned
PIDs/reaping, and retains primary/independent failures. No kill-to-pass,
descendant exclusion, native-cause inference or old-guest action is added.
Official producer controller119581638193 actually ran626/OK in113.148s;
trusted aggregate119582563378 records CONTROLLER_RESULTsuccess. These are
producer software results, not this successor's CI/pair/native evidence.

Public7949's own39701515172 subsequently completed FAILURE: official
Linux119572387812 full declared Gradle command timed out after
1799.9999866549997s at01:18:23Z; aggregate119582571605 failed and
SwiftData119582572378 skipped. Controller853/79.919s and Swift codec success
remain distinct. Bounded in-memory inspection of the official full log found
the command timeout, not a reported assertion/heap/daemon failure; cause is
not inferred. No hosted result bundle is copied, rehashed or revalidated,
and no rerun/dispatch/deadline change is made.
Actual post-integration consumer suite passed235/235 in17.034s, including the
packaging exception preservation case; the same four declared source checks
and whitespace check passed. Advisory local Gradle fast verification remains
NOT RUN/UNVERIFIED. The successor needs its own exact-head hosted result and
fresh source-bound App/Test pair/native attempt, not inherited7949/c9 proof.

### Actual114616a1: real kernel boot progress, selected registration timeout

Actual normal public candidate114616d1e38480062447d06c3187906579b46a2d,
direct7c3166b0f89d457253d83e15b39b3cd6b78b96ce, bound actualmainD708/
reviewed06bf/disposable8a538d and reservation31. The exact Android tree was
b58ae6043f3c2c7d079fcb09f39430c7a8f62188, source digest
7e0276aac6469923e44cbaeaab1fc688c4962df8a97e43f788b8bbe372177282.
Fresh 2026-10-22T01:28:02.2392568Z admission observed7384.02MiB commit
headroom/5909MiB physical availability; current published source, trusted
main/policy/pause, actor and original seven-case binding guards passed.
Real new owner was
1666502a-0420-46ef-930f-e78651ba8b9f:nodes:dc0dd4e88b87405b84c5ce432c33e29b,
rootprivate-avd-qlml8d6n. No003/504 wake or old-guest action occurred.

The actual source-bound App/Test build succeeded in1m56s/446 tasks
(5 executed/441 up-to-date), and the matching pair receipt exists.
Unique packaging-quiescence-12a4a79fedf84974afd16ee45973dd30.json actually
reports windows-job-accounting/empty, one count0 sample, PIDsnull,
elapsed0.0. It does not establish that the grace interval repaired7949:
there was no positive-to-zero transition in this attempt.

The new emulator genuinely launched with kernel output. Exact selected
wait-for-device ownedPID51280 raised TimeoutExpired120.0 before any getprop,
profile, install, instrumentation or cohort: original Nodes0/7 and unknown
host instrumentation exit. CLI2 is not instrumentation exit.
Independent finally disposal verified true/no disposal_error; supported
ledger readback found no lock for this owner and retained protected66bd.
Only then was the mainD708 serialization hold released.

Unlike earlier source-bound failures, the retained2795-line process.log
actually shows sys.boot_completed=1 at kernel115.799974, ranchu-adb-start
at116.687624/status0, and emulator "Boot completed in122434 ms".
It also shows ranchu-adb-setup13.154096/status0 and adbd stopped at
kernel16.035216 and115.746246. These are concrete boot-progress/completion
messages, not strict getprop/profile acceptance or a no-boot claim.
The selected adb-af8641ff5a894c5684adcd60cf997ff5.log is0 bytes.
Actual source already sets both private-server environment variables and
the explicit selected endpoint; no missing-environment repair is established.

Retained filesystem UTC bounds (not monotonic command/per-line timestamps):
process.log created01:30:28.963888Z/last-write01:32:32.041673Z;
selected ADB file created/last-written01:30:32.310399Z;
operations final write01:32:32.371605Z; quiescence created01:30:25.633246Z;
pair receipt created01:30:25.655839Z. No structured host UTC/monotonic
startup/command/deadline/timeout receipt or per-line host timestamp exists.
Therefore alignment of guest/kernel/SDK/host clocks and the transport cause
remain UNKNOWN; filesystem metadata is not substituted for those clocks.

All originals remain under this session's files directory:
wp503-114616-nodes37-disposable-a1/operations.json and packaging/
debug-invocation.json, debug-build.log, debug-packaging.json; sibling
wp503-114616-nodes37-disposable-a1-cli.txt; private-avd-qlml8d6n/
process.log, selected ADB output and unique quiescence report. Its retained
avds/fb065c2b00534f8580889c8bdea13cf7.avd/emu-launch-params.txt is the actual
emulator command artifact. hardware-qemu.ini records2CPU/4096MiB and
initial320x480/density160, not proof of the unreached target profile.
Exact absolute paths were handed to the coordinator for independent read.
No key file, CI-result bundle, live ADB/guest probe, unchanged retry,
CPU/config/deadline/assertion change or duplicated producer edit was made.
The demonstrated observability gap was routed to the existing producer;
it is not a claim that timing or transport caused the failure.

Own automatic39705770892 is separate from the native attempt. Actual
controller119585391709 succeeded with861 tests/97.572s/OK and codec
119586187628 succeeded; Linux119586187654 was still in_progress at the
bounded read. No overall/SwiftData/native result is inferred.
The exact compact PR body draft preserves245 historical/template rows and
changes only9 current rows; native CAS submission was transferred back
to the coordinator because the available updater takes a full literal body,
not a file path. No shell/REST edit or false submission claim was made.

### Actual2764: merged owned transport timing diagnostics

Actual normal PR268 merge2764cdae80e8ed1eeb6fb9b9900cc04ba78821c9
has independently read parentsD708 and reviewed96642cf3c90effe4f2c90ef4202ca9d5d7764075.
Its disposable input is9e3aec3a58ca9f2246d09b0744346d020a6d1a9f;
the Windows, ledger and debug-packaging inputs remain unchanged.
Supported same-owner typed integration6d7f5cd9335d4f9c4f5233448c8a23369211e120
retained canonical reservation31/31 and immutable identity/authorization context.
All1020 tracked owned consumer/evidence files were byte-identical across
the three-path producer merge, including LOCAL81e08's actual114616 failure.
The consumer advances only the two reviewed producer/disposable literals;
original Nodes7/fixture144, cached selected1/ten and current514 bindings,
native source/tests/AppGradle, deadlines, CPU/configuration and assertions
remain unchanged.

The new source retains unique startup/selected-command timing JSONL with
host UTC/nanoseconds and monotonic observations, actual startup deadline
where available, configured timeout and pre-call budget references explicitly
not backend deadlines, private endpoint/selected serial and observed PID/null.
The reviewed correction also preserves actual returned nonzero failures
alongside independent post-wait timing/raw-output retention failures.
Raw kernel lines still lack per-line host timestamps: a transport repair or
precise guest/kernel clock alignment is not inferred from this source change.
Coordinator source5608380341/execution5608397363 are COMMENTED, not acceptance;
producer own39708280030 selected SUCCESS and official634/112.330s/OK are
producer software evidence only, not a consumer/native result or copied bundle.

The exact65367-byte current114616 PR body was subsequently submitted by the
coordinator through native CAS55f8d2bea469 to f53d9bc25318. Independent live
readback matched the unchanged prepared artifact exactly, preserving245
historical/template rows and open/ready114616 identity. This does not publish
LOCAL81e08 or prove the next candidate, whose own CI/native states start
unverified. Publication and the new original Nodes7 attempt require fresh
actual main/source/policy/pause/identity/capacity/resource guards and a
genuinely new source-bound App/Test pair, with independent disposal.

Actual integrated declared consumer suite passed235/235 in15.673s with
positive discovery, including the retained packaging-failure/unknown-exit/
independent-disposal regression. verification_config.py --check, validate.py,
workflows.py, portmap.py and git diff --check passed. Advisory local Gradle
fast verification remains NOT RUN/UNVERIFIED; automatic hosted verification
must bind to the actual published successor, independently of114616 and
the producer's selected result.

### Actualea599a1: structured selected-registration timeout

Ordinary nonforce publication advanced actualPR205 from114616 to
ea59915eb0f3c3747439f00e4dbce4d0f05c702f, direct integration6d7f5cd93,
actualmain2764/reviewed96642cf/disposable9e3aec and reservation31.
The six-path coupled delta preserves the original native source/tests/
AppGradle and Nodes7/144/cached selected1/ten/current514 bindings.
New Android tree7d06a2e708190469c9c4ed45d3920d5a02f1183b has source digest
5e64a1483dd43796e0ee36092b4eb519defca99d3acf30a63fe3aa6da578df86.
Immediate02:04:22.371823Z admission observed6375.58MiB commit headroom/
5651.51MiB physical availability and passed current published head, actual
main/policy/pause, actor, source, canonical identity/r31 and original7 guards.
The genuine factory allocated owner
1666502a-0420-46ef-930f-e78651ba8b9f:nodes:d07dc4a97cdd4caeb16e489ca4de5385,
rootprivate-avd-xfhr9nt9. Fresh App/Test assembly succeeded2m18s,
446tasks/5executed/441up-to-date, with the actual pair receipt.
The unique packaging-quiescence-0b585058ca7144049aab309f8d91bcdf.json
records empty/one Windows count0/elapsed0.0/PIDsnull, not a positive-to-zero
transition or proof that the earlier grace interval fixed a native cause.

Startup retained8 structured observations. Emulator launch-before was
hostUTC02:07:04.3554538Z/monotonic129286.140; startup returned at
02:07:07.3300668Z/129289.109 with emulatorPID14764/serverPID77808 and
selected127.0.0.1:63762/emulator-5558. The selected wait retained4 records:
wait-begin02:07:07.3431087Z/129289.125, then TimeoutExpired for ownedPID75784
at02:09:07.3511941Z/129409.140. Actual observed elapsed time was120.0080854s
UTC and120.015s monotonic, with configured120.0s timeout. The pre-call
budget reference129409.125 is explicitly NOT the backend deadline; the exact
backend wait-call deadline remains UNKNOWN. No deadline or CPU change occurred.

The original Nodes0/7 never reached getprop/profile/install/instrumentation
or a cohort; host instrumentation exit remains UNKNOWN, separate from CLI2.
Independent disposal verified true/noerror. Independent supported ledger
readback found no new-owner hard lock and retained protected66bd. The
coordinator was sent actual end/disposal and main2764 hold release immediately.
No old guest, post-disposal ADB/guest probe or unchanged retry was performed.

Unlike114616, this2390-line/221262-byte process.log contains no observed
Boot completed/sys.boot_completed/ranchu-adb-start message. Last retained
kernel output is17.899915; that is not proof the guest stopped there.
ranchu-adb-setup exited0 atkernel12.945240; adbd-stopped action is15.912468.
One guest audit timestamp1792634846.540 appears with kernel17.221814:
this is guest clock data, not verified host/per-line clock alignment.
Filesystem bounds only: process.log creation02:07:04.291817Z and
last-write02:07:27.950098Z; selected ADB output creation/last-write
02:07:07.336610Z and0bytes; operations final-write02:09:07.395252Z.
Cause, precise guest/host alignment and natural emulator/server terminal
state at timeout remain UNKNOWN. Actual OwnedProcess.poll() is available,
but no pre-disposal owned-root liveness/exit snapshot is retained; Windows
cleanup terminates the owned job before its final polls. This concrete
observability boundary was routed to the coordinator before any source edit,
not presented as a transport fix or a reason to weaken the budget.

Exact originals remain under this session's files directory:
wp503-ea59915-nodes37-disposable-a1/operations.json and packaging/
debug-packaging.json, debug-build.log, debug-invocation.json; sibling CLI log;
private-avd-xfhr9nt9/process.log and unique quiescence report;
transport-timing-3301439bc8aa45dc8a6e93e50fca27fd.jsonl (startup),
transport-timing-48e496e645ef4c87920227bc67f8107b.jsonl (selected wait),
adb-e86b6e292ee6429488843f5d96c63474.log; and
avds/6e2d9c0cbe484b49b1492da1564caf39.avd/emu-launch-params.txt.
No key files were read/copied; emitted public-key lines are omitted from
public summaries. No hosted CI-result bundle was retained.

Own new automatic39709184445 controller119596372380 succeeded with
official869/117.408s/OK; codec119597343836 succeeded. Linux119597343829
remained in_progress at the bounded read, with no overall/SwiftData/native
verdict inferred. Separately, actual historical114616 own39705770892 ended
CANCELLED: controller/Linux/codec SUCCESS, SwiftData119595362308 CANCELLED
and aggregate119596279680 FAILURE, consuming BACKUP_RESULT=cancelled.
Historical Linux's20 module summaries passed6462/6462 with zero errors/
failures/skips; backup429+45 are subsets, not extra tests. Neither the
SwiftData cancellation cause nor an assertion failure is invented.
This actualea599 failure chronology is LOCAL until a future ordinary
coupled publication, not a doc-only source push or new native acceptance.
Coordinator independent retained-artifact read and negative review5608483724
are COMMENTED, not approval or acceptance; actual main2764 hold is released.
Retained hardware-qemu.ini independently confirms2CPU/4096MiB and initial
320x480/density160. The intended360x900/font200 profile was never reached.
The coordinator routed the concrete pre-cleanup root-state diagnostic gap
to the same existing producer; no consumer producer edit or worker wake occurred.

### Actual295: merged owned-root status observations

Actual normal PR269 merge295ebf37de8db1e6b295e6e74dae3efafe71c178 has
independently read parents2764 and reviewededb9d14a93e37d27de7fae431437b5d03b291206.
Its disposable input is643200e7b389c62f5bdd2aec4e9ea00c75356091;
the other three API inputs remain unchanged. Same-owner supported typed
integration78eb6c586b07ad028986ecc81643a9039c67b7be retained revision31/31,
immutable identities/authorization context and all1020 tracked owned
consumer/evidence files byte-for-byte, including LOCALa353's actualea599
failure chronology. Only the two executable producer/disposable literals
advance; original Nodes7/144, cached selected1/ten/current514, native source/
tests/AppGradle, CPU/configuration, strict120/180 budgets and assertions
remain unchanged.

The reviewed source retains unique owned-status JSON on operation failure
and before cleanup, using existing owned-handle poll/Job active APIs only.
Root status is explicitly not whole-guest/descendant exit proof; PID membership
remains unavailable/null. Actual primary errors and independent observation/
retention/cleanup failures stay visible; there is no extra guest command,
transport fix, historical-state proof or weakened disposal requirement.
Producer source5608543693/execution5608549658 are COMMENTED, not acceptance.
Producer own39710791051 selected SUCCESS/official638/75.387s/OK/aggregate
119602051501 are producer software-only evidence, not consumer/native proof;
other platform scopes were genuinely unselected and no CI bundle is copied.

The actualea599 PR body was updated through the native full-literal tool,
CAS8091d23df815, and independently read back exactly65367UTF8bytes equal
to the prepared draft, with all245 historical/template rows unchanged.
This successor still starts with its own unverified CI/native states and
requires a genuinely new source-bound pair plus immediate fresh admission.
Actual integrated declared consumer suite passed235/235 in34.263s with
positive discovery; verification_config.py --check, validate.py, workflows.py,
portmap.py and git diff --check passed. Local Gradle fast remains explicitly
NOT RUN/UNVERIFIED; own exact-successor hosted verification is still required.

### Actual8f/a1: owned roots remained unexited at registration timeout

Ordinary nonforce publication advanced PR205 fromea599 to
8f4838f0f5ba3beaeb3c321438c7785e974cab62, direct78eb6c586, actualmain295/
reviewedEDB/disposable643200 and canonical reservation31. The six-path
coupling preserves LOCALa353's actualea599 evidence and the235-case model;
only two executable consumer producer/disposable literals advance.
Android tree47fda632d67167d3d453a098453004f61d98bd00 has source digest
1daed479e0dc4291124c91731be22c8e8b8c42337ee299f33492a75b2a7f35cd.
Fresh02:27:07.796064Z admission observed5917.91MiB commit headroom/
6053.75MiB physical availability, with current published source, actual
main/policy/pause, actor, canonical identity/r31 and original7 guards positive.
Genuine new owner was
1666502a-0420-46ef-930f-e78651ba8b9f:nodes:046e4bb430ab4955ba438c1ffee2c18d,
rootprivate-avd-p8wtxvo7. The fresh App/Test pair build succeeded3m3s/
446tasks/5executed/441up-to-date. Unique quiescence report records empty/
one count0/elapsed0.0/PIDsnull, not an attributed grace-interval repair.

Retained emulator launch-before was02:30:47.0020989Z/mono130708.781;
startup returned02:30:52.5205185Z/130714.296. Selected commandPID43412
at127.0.0.1:57540/emulator-5558 recorded wait-begin02:30:52.5348005Z/
130714.312, then TimeoutExpired120.0 at02:32:52.5434575Z/130834.328.
The observed interval is120.008657s UTC/120.016s monotonic and includes
failure observation/retention. Pre-call reference130834.312 is explicitly
not the backend deadline; exact backend wait-call deadline remains UNKNOWN.
Original Nodes0/7 reached no getprop/profile/install/instrumentation/cohort;
host instrumentation exit remains UNKNOWN, separate from CLI2.

The actual operation-failure snapshot at02:32:52.5424503Z/mono130834.328
records serverPID28492, emulatorPID77308 and selectedPID43412 all not-exited/
returncodenull; real owned Job count9, PIDsnull, errors[].
Before-cleanup at02:32:52.5479569Z/130834.328 again records server/emulator
not-exited, selected-command unavailable as designed, Job9/errors[].
These are existing owned-handle observations, not whole-guest/descendant
activity, successful transport, historical-state proof or a CPU/transport cause.
Independent finally disposal verified true/noerror, and independent supported
ledger readback found new-owner locksNONE while preserving protected66bd.
Actual end/disposal was sent immediately; the coordinator independently read
the retained receipt and released main295 serialization.

The2630-line/262800-byte log reaches kernel117.157676 with system_server/
WiFi initialization, but no Boot completed/sys.boot_completed/ranchu-adb-start
message was observed. ranchu-adb-setup exited0 at16.140761; adbd-stopped
action is19.130958. Guest auditUnix1792636370.392/kernel116.009699 is
guest-clock data, not a verified host/per-line clock alignment. Absence of
a boot marker is not proof the guest stopped. Filesystem bounds only:
process.log creation02:30:46.939439Z/last-write02:32:52.312259Z; selected
ADB output created/last-written02:30:52.526789Z/0bytes; operations final-write
02:32:52.588052Z. Retained hardware-qemu.ini confirms2CPU/4096MiB and initial
320x480/density160; intended360x900/font200 profile was never reached.

Originals under this session's files: wp503-8f4838-nodes37-disposable-a1/
operations.json and packaging/debug-packaging.json, debug-build.log,
debug-invocation.json; sibling CLI log; private-avd-p8wtxvo7/process.log;
transport-timing-4b8b13de00be419b9303f4d80ecedfb0.jsonl (startup),
transport-timing-956ccb3687f24c5a85bc71061a8a22d4.jsonl (selected wait),
owned-status-2d6e319f373e4debbd92559aa48ea656.json (operation failure),
owned-status-51d8359610a44f0ba77b26aa6502765c.json (before cleanup),
packaging-quiescence-ffb80ed5b81c41619781e3bdd90ef38f.json,
adb-f272ad9269004bfa8b2b2cc61328f174.log and
avds/e6af633fd4d34ae183473269f722dbbd.avd/emu-launch-params.txt.
No key file was read/copied and emitted public-key lines are excluded from
public summaries. There was no live/post-disposal ADB/guest probe, unchanged
retry, CPU/config/deadline/assertion change or duplicate producer edit.

Own automatic39711397373 controller119603537216 succeeded with official
873/120.100s/OK and codec119604475880 succeeded; Linux119604475922 remains
in_progress at the bounded read, without an overall/SwiftData/native verdict.
Separately historicalea599 own39709184445 ended CANCELLED: controller869/
codecSUCCESS, Linux119597343829 CANCELLED, SwiftData119603444535 CANCELLED,
aggregate119603443438 FAILURE. No cancellation or assertion cause is inferred,
no CI dispatch/rerun occurred and no hosted result bundle is retained.

Cause and precise host/guest alignment remain UNKNOWN; no new producer
source bug or CPU cause is established. The coordinator is taking a genuine
execution-profile decision, possibly a reviewed4-vCPU diagnostic preserving
the original deadlines/assertions, to the user. No CPU/config change, factory,
retry or003/504 wake is authorized before that decision/admission. This actual
failure chronology stays LOCAL for ordinary future coupling, without a
doc-only publication. Original Nodes7/144/cached1of10/current514 and full-WP
acceptance boundaries remain unchanged.

### Merged270 coupling: explicit four-core diagnostic prepared, not launched

Actual trusted main4c33f4fe8f2b115ecec7aa74491b6cede94af0ec is the normal
merge of295ebf37de8db1e6b295e6e74dae3efafe71c178 and reviewed producer
c9af5bf041ac84fc470c1ab75e309b5a89cc4f0d. Independent actual-parent/delta
inspection found exactly three incoming paths/+128/-2: disposable.py,
its existing tests and WP003 deviations. The disposable object is now
c1fbc9356d7576cc714c4757cc8ac91f45c53a46; windows_guest621612bb,
ledger6048cf50 and debug_packaging7f8e5e97 remain unchanged.
Source review5608715986/execution5608730533 are COMMENTED, not acceptance.
Producer own39713661690/official640/70.183s/OK/selected aggregate SUCCESS
are producer software evidence only, not consumer or native proof.

Supported same-owner/r31 capability admission used trusted merge-integration
operations reconcile-current-base/merge-disjoint-semantic-edits and the
stored canonical session/branch/worktree owner context. Evolution remained
31-to31; identity, binding, immutable authorization context and audit history
were preserved. Local integration5d3ca3d1f3399c110e33bea100b04e79b78853f1
preserved all1023 tracked WP503-owned path bytes, including LOCAL4096d11ad's
actual8f failure chronology. Generated source receipt is
wp503-4c33-main-integration.json under the current session files.
This is not a second hosted CI-result bundle or live native admission.

The consumer advances only the producer/disposable bindings and wires the
reviewed optional CPU request. Default None preserves original emulator
configuration; explicit --cpu-cores 4 maps to start(...,cpu_cores=4).
All other values fail before factory allocation. Operations retain
requested_cpu_cores separately from actual initial_private_profile.cpus;
an actual mismatch fails before profile mutation/install/instrumentation,
retaining unknown instrumentation exit and independently verified disposal.
The producer's existing timing/status requested_cpu_cores field records a
request, not an observed CPU count. No image, memory, GPU, strict boot
predicate, original120/180 budgets, original family/7-of144 Nodes fixture,
cached1-of10/current514 provenance, native source/AppGradle, or old66bd fence
is changed. The fixed consumer models remain models, not backend execution.

Declared focused verification on this integrated source:
python -B -m unittest discover -s android\accessibility-tests -p test_*.py -v
discovered/ran/passed240 tests in17.723s, with zero failures/errors/skips.
All235 incumbent tests and five new methods pass: optional/strict CLI CPU
parsing, invalid direct requests before allocation, unchanged default with
observed2CPU, explicit4 forwarded with original seven identities, and actual
2-versus-requested4 rejection before mutations with independent disposal.
python -B tools\android-port\controller\verification_config.py --check,
python -B tools\android-port\controller\validate.py,
python -B tools\android-port\controller\workflows.py,
python -B tools\android-port\portmap.py and git diff --check all PASS.
Local Gradle fast verification remains NOT RUN/UNVERIFIED for this new
source; no prior hosted/build/native result is transferred.

The coordinator explicitly superseded only its earlier execution-profile
hold for ONE reviewed four-vCPU diagnostic under renewed autonomous work.
This was a coordinator decision, not a user-selected answer or CPU-fix
finding, and supersedes no real capability/protected gate. Native execution
still requires actual current source/policy/owner/capacity/factory admission
and coordinator main serialization. No factory, guest probe, native launch,
producer edit/wake, old-guest action, source publication, CI dispatch or
rerun occurred in this preparation. Public8f/ordinary39711397373 remains
untouched while its genuine run finishes; no successor publication before
that actual terminal outcome. Full-WP and original native acceptance remain
incomplete; this is not an unchanged8f retry or a transport/CPU fix claim.

Own8f ordinary39711397373 subsequently reached its genuine terminal outcome:
FAILURE, not cancellation or a pass. Official Linux119604475922 reports at
2026-10-22T03:01:54.608059Z that the full declared Gradle command timed out
after1799.999986114s, then exit1. The tail contains completed localization
evidence but does not identify a specific failing assertion, task cause,
heap/CPU cause or native verdict. Controller873/120.100s/OK and codec remain
SUCCESS; SwiftData119613641756 is SKIPPED, aggregate119613641032 FAILURE.
The actual aggregate log consumes CONTROLLER_RESULT=success,
BUILD_RESULT=failure, EXTERNAL_RESULT=success and BACKUP_RESULT=skipped.
Official exact-job logs were read in memory only: no second CI-result bundle,
dispatch, rerun, cancellation or deadline change. This terminal result removes
only the prior preserve-running8f publication hold; it is not merge/native
acceptance, does not transfer to the new source and grants no live admission.

### Actual2249 admission abort: insufficient host capacity, no factory start

Ordinary nonforce publication advanced the existing PR205 ref from8f to
2249a66c6163f09828e8028710d0df73b72adc4b, directb6eb38a65, actualmain4c33.
The seven-path coupled delta includes the actual merged producer, consumer
CPU option/five regressions and retained LOCAL4096 actual8f chronology plus
its terminal CI correction. Executable/tests/native/binding bytes equal
reviewedb6; source review5608791279 is COMMENTED, not execution acceptance.
Supported record_pending explicitly records the exact new candidate as local
Gradle fast NOT RUN/UNVERIFIED; no historical build/CI result is transferred.
Own automatic39714816975 initially has classifier119614291644 SUCCESS and
controller119614360879 IN_PROGRESS, without an aggregate/native verdict.

The coordinator granted main4c33 serialization for one freshly admitted
four-CPU diagnostic, explicitly not reserved capacity. Actual fresh guards
verified published2249/PR205 OPEN/ready/base4c33, trusted main and all four
producer API objects, unchanged frozen Swift/manifest/policy with pausefalse,
authenticated actorcbattlegear, actual canonical runningr31/current session,
original seven Nodes identities, eligible installed tools/image/debug-home
and absent global Gradle/disposable resource locks. Oldnative66bd stayed
present and untouched.

At2026-10-22T03:05:37.116318Z the real Windows GetPerformanceInfo observation
was1833.96MiB commit headroom and3131.17MiB available physical memory, below
the existing4096MiB admission minimum. The attempt failed closed with exact
AssertionError: Insufficient actual commit/physical headroom; no factory/guest
launch. Source-bound wp503-2249-4c33-native-admission.json retains admittedfalse,
requested_cpu_cores4/observed_cpu_coresnull and factory_not_yet_calledtrue.
There was no factory call, unique guest owner/allocation, build/pair, native
start, original case/cohort or host instrumentation exit. Four requested
cores are not an observed configuration. No CLI invocation/output was created.

Independent supported hard_lock_records readback found only unchanged
protectednative66bd, no disposable/globalGradle/new-owner locks; both intended
wp503-2249a66-nodes37-fourcpu-disposable-a1 output and sibling CLI log were
absent. Disposal is N/A because no guest was allocated, not a fabricated
successful cleanup. Admission-abort/no-new-resource proof was sent promptly
to the coordinator to release main4c33 serialization. No weaker capacity,
RAM/profile/CPU/deadline/assertion guard, helper kill, producer wake, original
guest action, repeated capacity/native probe or dispatch occurred. This
observation stays LOCAL for a future ordinary coupling, not a doc-only push.
The concrete blocker is actual host capacity; it establishes no historical
CPU/transport cause, native repair or full-WP acceptance.

### Actual2249 first four-CPU diagnostic: registration timeout, independent disposal

After a concrete coordinator-owned finished-worker resource change, fresh
main4c33 serialization was granted for the still-unstarted2249 diagnostic.
The prior capacity abort created no guest and is not relabeled a native run.
At2026-10-22T03:16:48.784583Z actual fresh admission verified exact public2249,
OPEN/ready PR205, main4c33 and reviewed producer/API objects, pausefalse,
manifest/policy, actorcbattlegear, canonical runningr31, original Nodes7,
installed tools/image/debug identity and no disposable/globalGradle locks.
Actual commit headroom4846.67MiB and physical5294.48MiB exceeded the unchanged
4096MiB minimum. wp503-2249-4c33-native-admission-a2.json retains this distinct
positive pre-factory boundary, not reserved capacity or observed guest CPU.

The single actual command selected public2249/API37/family nodes with
--execute --cpu-cores 4 and the original image/memory/GPU/boot120/
instrument180/assertions. Same-worktree literal HEAD2249 was selected because
the existing packaging source_identity requires exact HEAD, while the managed
branch retained localafa2512e unchanged. The genuine factory allocated owner
1666502a-0420-46ef-930f-e78651ba8b9f:nodes:5a05da9b4186470eab1b02136ccda847,
root private-avd-rbls_3lv. Actual disposable/Gradle locks were independently
read during live packaging. The exact-source App/Test build succeeded in
2m2s,446 tasks/5 executed/441 up-to-date, with matching existing debug
certificates. App SHA2561eb334ceb728f41cef002f526580c763eba8b3bd481dfaefbfcb9e8f2038e162
and Test20f20334f3785fa48b31d23666917a0f14f3e3c8d403a0fc33b4e5f1cd029672
are bound in the actual packaging receipt. No credential/key copying occurred.
Pre-start packaging quiescence reports empty/count0/elapsed0.0/PIDsnull.

Actual startup began03:19:14Z; private server31996 and emulator55256 listener
waits returned, startup returned03:19:17Z. The selected original transport
127.0.0.1:63781/emulator-5558 wait-for-device PID66068 then timed out at the
unchanged120s bound,03:21:17Z. CLI exited2 with primary
TimeoutExpired(['owned-process','66068'],120.0). No getprop/profile capture,
profile mutation, install, instrumentation or cohort occurred: original
Nodes0/7 started; host instrumentation exit remains unknown/null, not CLI2.
The generated AVD config actually contains hw.cpu.ncore=4, and retained
kernel output says "smp: Brought up 1 node, 4 CPUs". These actual config/kernel
observations are distinct from the unreached measured-profile CPU guard.
The last retained kernel-prefixed line is16.661165; no Boot completed,
sys.boot_completed or ranchu-adb-start marker was found. This does not prove
a CPU cause, transport cause, whole-guest state or guest/host clock alignment.

Failure owned-status-2b731c52fcfe4292b9adaf36e4cce10c.json observes server,
emulator and selected root handles not-exited, returncodenull, Job active8,
errors[]. Before-cleanup owned-status-6ff794435dab4aefbc590c9df530e470.json
observes server/emulator still not-exited, selected unavailable as designed,
Job8/errors[]. These are minimal root-handle/accounting observations, not
whole-guest/descendant activity or causal proof. Both existing timing files,
status files, kernel log and original operations/CLI remain retained locally;
no post-disposal guest probe or producer instrumentation was added.

The primary timeout survived independent guest.dispose and verify_disposed;
operations.json records disposal_verifiedtrue/no disposal_error. Independent
supported hard_lock_records readback found no5a05 disposable/Gradle/new-owner
locks and only unchanged protectednative66bd. Additional fresh CIM observation
found all three retained root PIDs31996/55256/66068 absent. ActualEND/disposal/
lock proof was sent to release main4c33 serialization. Only then was the
managed branch restored at preservedafa, public2249 unchanged. This actual
failed attempt stays LOCAL for ordinary future coupling; no doc-only push,
retry, producer wake, old-guest action, weaker guard or CI dispatch occurred.

At the subsequent bounded metadata read, own2249 automatic39714816975 remains
IN_PROGRESS: classifier/controller/codecSUCCESS, Linux119615287800 IN_PROGRESS;
no aggregate/native verdict. Independently reported controller880/117.175s/OK
and five new CPU tests do not establish native acceptance. Historical8f
terminalFAILURE, EA/114616 cancellations and all original native assertions
remain distinct. Missing native acceptance is unresolved; no further attempt
or speculative producer cycle is authorized by this negative diagnostic.

Own2249 automatic39714816975 subsequently reached terminal FAILURE. A fresh
official metadata/log read on2026-10-22 confirms Linux119615287800's entire
declared Gradle invocation timed out after1799.99998936s at
2026-10-22T03:38:56.6817674Z, followed by exit1. Aggregate119624640717 failed;
SwiftData119624642129 was skipped; classifier/controller/codec succeeded.
This supersedes only the preceding pending observation. It establishes no
particular assertion failure, CPU cause, completed SwiftData proof or native
acceptance. Official logs were consumed in memory, without a second result
bundle, dispatch, rerun, cancellation or changed deadline.

### Authorized post-reboot2249 Nodes7 attempt: registered, then device offline

The renewed user request authorized actual needed API37 tests. The existing
running5556 shared route failed its genuine-original recovery guard before
mutation; it was left untouched. The coordinator explicitly selected ONE
supported disposable attempt with reviewed --cpu-cores 4 and held actual main
8e05c754b6298e789886d3a8006b6275cd1d3146. At13:44:09.405064Z fresh admission
verified exact public2249, OPEN/ready PR205, main/API objects/policy/pausefalse,
actorcbattlegear, runningr31, original7, tools/image and no conflicting new
locks. Actual commit8494.23MiB/physical11443.05MiB exceeded4096. The exclusive
wp503-2249-8e05-postreboot-admission.json retains these actual observations.

Literal HEAD2249 was temporarily checked out in the SAME worktree; managed
branche1662011a remained preserved. Genuine factory owner
1666502a-0420-46ef-930f-e78651ba8b9f:nodes:c509fefdd05b40a38784f2611f098706
allocated private-avd-8vifityr and held actual disposable/globalGradle locks.
The genuine source-bound App/Test build succeeded in1m54s,446 tasks/5 executed/
441 up-to-date. Both APK hashes and matching public debug certificates equal
the preceding exact2249 pair, verified by this attempt's packaging receipt.
No image, memory, GPU, profile,120/180 deadlines or original assertions changed.

Startup began13:46:28Z; private server5944/emulator6940 listeners returned.
Selected wait-for-device PID4636 on127.0.0.1:53719/emulator-5554 actually
SUCCEEDED at13:47:52Z, exit0 after80.39s. The first selected shell getprop
sys.boot_completed returned1/exit0. The next getprop init.svc.bootanim PID36876
failed exit1 with retained stderr "adb.exe: device offline". This is NOT the
previous registration timeout. CLI exited2; no complete boot observation,
captured initial profile, profile setup, install, instrumentation or cohort
occurred. Original Nodes0/7 started; instrumentation host exitnull is not CLI2.
No CPU/transport cause or native acceptance is established.

The first property value is actual retained literal "1\n" in
adb-3428c516121744868bea8a17fe2d5666.log, not inferred from exit0. Failure
adb-e1892d29bec44e4ab9b07f1348ec8192.log retains literal
"adb.exe: device offline\n". Failure snapshot f3949ec41f154895947870ab8614e011
observes server/emulator roots not-exited, selected36876 exited1, Job7/errors[].
Before-cleanup snapshot f35d2890df2f452a809755928a3c5c5c observes server/emulator
not-exited, selected unavailable as designed, Job7/errors[]. Root snapshots
are not whole-guest state or causal proof. Empty boot_observations is expected
because wait_boot saves an observation only after BOTH property commands
succeed; it does not mean the first command did not run.

Independent dispose and verify_disposed succeeded, operations records
disposal_verifiedtrue/no disposal_error. Supported ledger readback found
c509 disposable/Gradle locksNONE and ONLY unchanged protectednative66bd.
Fresh specific CIM read found server5944/emulator6940/selected4636/23548/36876
absent. ActualEND/disposal/newlocks proof was sent to release main8e05 hold.
Only then was the managed branche166 restored. Actual operations/packaging,
timings/status and CLI remain under session files in
wp503-2249-nodes37-postreboot-fourcpu-a1 and private-avd-8vifityr. Existing
running5556/protected66bd were not adopted/probed/stopped. No second retry,
publication, dispatch or new worker occurred. This chronology remains LOCAL;
own2249 hostedFAILURE remains distinct from this negative native result.

### Local consumer preparation for the reviewed scoped boot-offline interface

The retained actual boot1/offline result exposed a consumer readiness
interruption, not a proven emulator/transport cause. A consumer-only catch was
initially blocked because c1fbc makes every nonzero command uncertain and
correctly rejects subsequent normal commands. No phase reset, raw ADB call,
ownership bypass or fake recovery was introduced. The coordinator assigned
the existing WP003 producer owner; its reviewed published PR273 source
d93813cb393780ed4de15f80c5688c1afba4077e defines the concrete supported
boot_readiness/BootReadinessOffline/reattach_boot interface. At this preparation
checkpoint that producer is NOT merged or pinned; no publication/live action
is authorized until its actual merged handoff and new admission.

The consumer enters the supported scope immediately after start, using ONE
original120s deadline. Only the producer's exact typed boot-property offline
failure is caught. Actual property/code/available output/stderr and partial
properties are recorded through existing operations before reattachment;
malformed/decode/receipt errors fail explicitly. A selected reattachment uses
only remaining time, then discards the entire partial pair and freshly reads
BOTH properties. A previous boot1 cannot become current readiness. Arbitrary
nonzero/timeout/ownership failures still propagate with independent disposal.
No producer implementation, lifecycle phase or native assertion is changed.

Declared discovery
`python -B -m unittest discover -s android\accessibility-tests -p test_*.py -q`
passes246/246,0 failures/errors/skips,14.951s: unchanged240 plus six focused
consumer methods. Initial five-method preparation passed245/245,15.069s.
The added cases cover both retained Windows merged bytes and Linux separate
stderr, exact original deadline/order, stale boot1 rejection, repeatedoffline
deadline exhaustion before profile/install/instrumentation, arbitrary failures,
malformed/decode outcomes, evidence-write failure with independent disposal,
and failed/malformed reattachment. These are consumer protocol models only;
real producer phase/SQLite/ownership tests remain the producer's evidence.
The four declared verification_config --check/validate/workflows/portmap
commands and whitespace check pass. Native result remains0/7; no new factory,
guest, build, install, instrumentation, publication or dispatch accompanies
this local preparation.

### Actual merged PR273 coupling and ordinary successor preparation

PR273 is now actually merged: independently fetched main
2836839744f3e20743aeeea80824ec7e1f5165d2 has parents8e05c754b6298e789886d3a8006b6275cd1d3146
and d93813cb393780ed4de15f80c5688c1afba4077e. The consumer pins that actual
producer and disposable API object b3d42756e679bfcd285f48eca5529ebb64b79b0e.
The other three API inputs remain exactly windows_guest621612bb67e16432692e07a5a1b1056426747777,
ledger6048cf50b275b85c9028781fd80a473ef06ca6f2 and
debug_packaging7f8e5e9769c0f8d70a42678738280bf7209c17e9.

Trusted capability admission independently confirms every incoming path and
both merge-integration operations already exist in canonical runningr31.
No reservation evolution or identity/context rewrite is needed. The preserved
original branch/worktree audit context is not replaced with this worker's
context. The normal same-branch merge integrates the actual producer,
producer tests/deviation and disjoint Rooms test/deviation bytes. Original
native src, language_binding/test_language_binding and AppGradle compare
byte-identically with published2249; original7/144/current514 remain unchanged.
Locald6b7 actual native evidence and bf7130 exact Linux diagnostic correction
remain honest ancestry, not a new native result.

Integrated declared discovery passes246/246,0 failures/errors/skips,14.974s.
Each actual verification_config --check, validate, workflows and portmap
command exits0; staged/unstaged whitespace and original-native/binding
byte comparisons pass. These are local consumer/source checks only. The
producer's actual scoped39792206068/controller645 result belongs to its own
source; it is not this successor's CI/native verdict. Local full fastGradle is
explicitly NOT RUN/UNVERIFIED. Ordinary publication targets only existing
PR205's historical head ref, with no force, rerun, dispatch or new PR.
No native hold/admission/attempt is created by this source integration.

### Actual876 source-repaired attempt: raw Windows CRLF classification boundary

After exact876 source review5615748738 and main283 serialization, ONE new
supported attempt was admitted at2026-10-22T14:26:39.834380Z. Fresh guards bound
public876/main283, actual d938/b3d producer and unchanged other3API objects,
actorcbattlegear/pausefalse/runningr31/original7/tools/image. Actual commit
8600.94MiB/physical16258.76MiB exceeded the unchanged4096 minimum. Admission
wp503-876-283-scoped-boot-admission.json is a distinct actual observation,
not reuse of the preceding2249 admission.

The genuine factory allocated canonical owner
1666502a-0420-46ef-930f-e78651ba8b9f:nodes:069491aaf1734a9ca278a987061f11a1
and root private-avd-73gsr96j. Held disposable/Gradle receipts were independently
read during responsive packaging. This attempt's genuine876-bound App/Test
build succeeded in1m50s,446 tasks/5 executed/441 up-to-date. The verified App/
Test SHA256/public certificate equal the preceding pair, without borrowing its
source identity or native result. Requested CPU4/image/memory/GPU/original
assertions and120/180 deadlines were unchanged.

Startup began14:28:51Z and returned14:28:54Z. Selected wait-for-device50380
returned0 at14:30:10Z after76.141 monotonic seconds. FIRST sys.boot_completed
query19944 then exited1; no property value was obtained. The actual raw
adb-8c45b33ee6d2478ab255d1b63e797b2a.log bytes are
b'adb.exe: device offline\r\n'. CLI2 retained primary CalledProcessError,
not BootReadinessOffline: no boot observation/reattachment/profile capture/
profile mutation/install/instrumentation/cohort occurred. Original Nodes0/7
started; host instrumentation exitnull remains distinct from CLI2.

The exact source boundary is producer disposable.py694 raising the actual
returned_failure, then the narrow typed-offline predicate requiring Windows
diagnostic b'adb.exe: device offline\n'. Raw CRLF differs, so the producer
retains ordinary uncertain failure and the consumer correctly does not catch/
reattach an arbitrary CalledProcessError. This is an evidenced classification
defect in the producer-owned boundary, not an emulator/offline cause. There
is no BaseExceptionGroup or secondary timing/retention error in the traceback.
The failure status observes rootsserver11280/emulator47280 not-exited and
selected19944 exited1, Job7/errors[]; before-cleanup roots remain not-exited,
selected unavailable as designed, Job7/errors[]. These root observations do
not prove whole-guest state or transport cause.

Correction to earlier normalized-text quotations: read_bytes on original2249
adb-e1892d29bec44e4ab9b07f1348ec8192.log ALSO yields
b'adb.exe: device offline\r\n', and original successful property log
adb-3428c516121744868bea8a17fe2d5666.log yields b'1\r\n'.
The earlier read_text values quoted LF after universal-newline conversion;
they are text observations, not raw LF-only evidence. Both original files
remain intact. No broad strip/matching relaxation, phase rewrite or raw ADB
bypass was made. The concrete predicate/byte correction was reported to the
coordinator and existing producer owner; this worker does not duplicate that
producer repair.

Independent dispose/verify_disposed succeeded: disposal_verifiedtrue and no
disposal_error. Supported ledger readback found0694 newlocksNONE and ONLY
unchanged protectednative66bd. Fresh specific CIM read found11280/47280/50380/
19944 absent. ActualEND/disposal/newlocks proof released main283 serialization.
Existing5556/protected66bd remained untouched. Operations/packaging/CLI/
timings/status/raw command results remain under session files
wp503-876-nodes37-scoped-boot-fourcpu-a1 and private-avd-73gsr96j. No unchanged
retry, doc-only publication, dispatch or new worker occurred. This actual
negative chronology stays LOCAL; whole-WP/native acceptance remains incomplete.

### Actual merged CRLF producer coupling after the negative876 attempt

The preceding LOCAL-only status records the retention epoch9167dcf202bf5a52cd50341b57bde236734eccf8.
It is preserved without rewriting the actual negative execution or normalized
text correction, and is included in this normal coupled source successor.
Actual main0a7a84e388393557e4b66ac7fd322248e28611fd normally merged PR274,
with parents2836839744f3e20743aeeea80824ec7e1f5165d2 and
7c8e8b21a1e52a75a23ca54d063e42dd3839bd16. A normal nonconflicting
merge into the existing managed branch retains9167 and the original chronology.
Incoming changes are exactly the existing producer, its tests and WP003
deviation; all three paths and both merge-integration operations were already
admitted by the canonical runningr31 reservation. No ledger rewrite/evolution
or canonical-context substitution was necessary.

Consumer producer pin is now7c8e8b21a1e52a75a23ca54d063e42dd3839bd16,
and disposable API blobdd40e5677bc7ba41832485595ee328a2aeb2e9ee.
The other three API objects remain621612bb67e16432692e07a5a1b1056426747777,
6048cf50b275b85c9028781fd80a473ef06ca6f2 and
7f8e5e9769c0f8d70a42678738280bf7209c17e9. Producer-owned classification
accepts only exact Windows LF/CRLF output with stderrNone and preserves the
unchanged Linux separate stderr diagnostic. The existing consumer model now
includes actual raw Windows CRLF alongside LF and Linux; it still requires
exact retained streams, discarded partial properties and fresh BOTH properties
within the same original120s deadline. It adds no test method/family.

The initial source guard correctly stopped on GitHub PR REST base still
reporting283 while independent actual main ref was already0a7; no source or
runtime effects preceded that stop. Independent remote read confirmed exact
public876/OPENready and main0a7 before normal integration. REST base metadata
is not substituted for the actual main object or used as a native proof.
No new native attempt, hold, profile/install/instrumentation, guest adoption,
CPU/GPU/image change, deadline extension or assertion change accompanies this
source coupling. Original native src, language binding/tests and AppGradle
remain byte-identical to876/2249. Actual0of7 and disposal of0694 remain
negative evidence, not reclassified by a producer source correction.

Actual integrated declared checks before the coupled commit:
- `python -B -m unittest discover -s android\accessibility-tests -p test_*.py -q`:
  246 discovered/run/PASS,0failures/errors/skips,15.691s. All existing methods
  remain; the additional CRLF stream is a subtest of the existing method.
- `python -B tools\android-port\controller\verification_config.py --check`:
  PASS/exit0.
- `python -B tools\android-port\controller\validate.py`: PASS/exit0.
- `python -B tools\android-port\controller\workflows.py`: PASS/exit0.
- `python -B tools\android-port\portmap.py`: PASS/exit0.
- `git diff --check` and `git diff --cached --check`: PASS/exit0.

These are local source/consumer-model checks, not native/backend acceptance.
No complete local fast Android/Gradle verification is claimed for this source
successor. Its own exact-candidate hosted result remains authoritative;876 CI
or the separately reviewed producer CI is not transferred to this candidate.
No dispatch, rerun, cancellation or duplicate CI-result bundle is authorized.

### Actual325 original-seven execution and raw CRLF outcome-parser boundary

Published325138b4ecc8436437c0914ab9fd488d72eb005c normally integrates
main0a7a84e388393557e4b66ac7fd322248e28611fd and preserves local9167.
Parent exact-source review5616297183 was COMMENTED, not acceptance. Parent
held exact main0a7 for ONE fresh original-seven attempt, then released it after
actualEND and independent disposal/new-lock readback. The preceding ownCI
queued observation was not a native or merge verdict.

Fresh admission2026-10-22T15:06:33.656673Z verified publishedhead/main,
actual7c8e/dd40 and all four API objects, canonical runningr31, actorcbattlegear,
pausefalse, frozen source/manifest/policy, original seven identities, installed
SDK/image inputs and commit8471.2421875MiB/physical16519.74609375MiB.
Both exceeded the unchanged4096MiB guard. New genuine owner was
1666502a-0420-46ef-930f-e78651ba8b9f:nodes:ee7375d31a464bec888af65ddf131fc9,
rootprivate-avd-yh2khgcp/token9c0b09adcd2846b3b7a8362fc07bdb69.
Independent read during the responsive own build verified disposable+Gradle
receipts for that owner; only other lock was protectednative66bd.

Own exact-source App/Test build SUCCESS1m52s,446tasks(5executed441up-to-date).
Existing certificate000be798aca5806230ec26d9feee36cbd0acadc857cf22a4ba711f43a4a2c8b7
verified for App1eb334ceb728f41cef002f526580c763eba8b3bd481dfaefbfcb9e8f2038e162
and Test20f20334f3785fa48b31d23666917a0f14f3e3c8d403a0fc33b4e5f1cd029672.
Source binding Androidtree60ffc81d8b12690804d2aa2a31d99af1c17d40d2 /
digest80cf462cde1c20f89f01f3a6e9d397c1ca5ef823d25b7c53d0700f4b279d320b
belongs to325; equal APK bytes do not transfer older native results.

Startup15:08:48Z/returned15:08:51Z; private endpoint127.0.0.1:65460/
emulator-5554, server37084/emulator15424. Selectedwait44112 completedexit0
15:10:08Z after76.157s from wait-begin. First BOTH boot property reads exit0:
rawbytes b'1\r\n' and b'stopped\r\n', retained respectively in
adb-8951a004ffe2453bb4d668b52c1a6c05.log and
adb-be1e969b78024093bddb7884104358e0.log. Actual boot_observations has the
fresh pair1/stopped; no offline transition/reattach was exercised. CPU4,
font2.0/effective2.0, size override360x900/density160/motion0/IME0 and API37/
release17 were verified. Real target/test installs and installed digest checks
completed in the new guest, not an existing shared device.

Instrumentation19596 launched15:10:41Z under unchanged180s bound and returned
hostexit0 at15:11:34Z. Actual original transcript reports numtests7, seven
unique original starts and seven matching status0 outcomes, Time43.83 and
OK(7tests), with terminal INSTRUMENTATION_CODE:-1. A retained-only manual
line-record tally checked exact original identity-set equality; it is not a
rewritten operation receipt or official parser acceptance.

Actual CLI nevertheless exited2: RuntimeError('Missing or unsuccessful terminal
instrumentation completion') at shared_device_runner.py93. Strictdecoded raw
transcript is5688bytes with107CRLF/0bareLF; terminal bytes are exactly
b'INSTRUMENTATION_CODE: -1\r\n'. The existing anchored regex
^INSTRUMENTATION_CODE: (-?\d+)$ returns[] rather than['-1'] on this actual
input, BEFORE method-field extraction (which likewise retains trailing CR).
Original device-nodes37.txt is byte-identical to producer command log
adb-1ceb6eb2d5734fd5bc1ff4b3eefa42cd.log; retained stderr is empty.
Operations correctly preserves actualhostexit0/no cohort result/acceptancefalse,
distinct from all seven genuinely executed/reporting success. No silent
normalization, receipt rewrite, native retry or acceptance claim occurred.

Independent disposal/verify_disposed succeeded/no disposal_error; new-owner
locksNONE, only unchanged66bd remains. Specific fresh CIM read found37084/
15424/44112/19596 absent. Before-cleanup root snapshot had server/emulator
not-exited, selectedunavailable, Job7/errors[]; this was not whole-guest proof.
Existing5556/protected66bd were untouched. Actual operations/CLI/packaging/
transcripts remain under sessionfiles/wp503-325-nodes37-crlf-fourcpu-a1,
with original timing/raw/process evidence in private-avd-yh2khgcp.

Trusted ownership read corrected a provisional broad parser-owner inference:
THIS parse_outcomes is in WP503-owned android/accessibility-tests/
shared_device_runner.py and explicitly reserved at runningr31; trusted modify
admission is wp-owned. WP003's current reservation does not include that target,
and its trusted modify admission rejects the path. No ownership transfer or
parser edit was performed pending coordinator routing. This actual chronology
is retained locally without a doc-only push; native/whole-WP acceptance remains
incomplete despite the seven successful original case reports.

### Minimal owned CRLF record-boundary correction and pure retrospective accounting

Coordinator routed THIS parser correction to the existing WP503 worker after
the trusted ownership read, without changing ownership or authorization prose.
The existing wp-owned path/operation rule admitted the regression fixture
android/accessibility-tests/fixtures/nodes37-325-instrumentation.json; supported
same-owner scope evolution31-to32 preserved canonical identity/binding/context/
branch/worktree and running state. Original actual execution remains bound to
r31, not retroactively rewritten to32.

Production delta is ONLY `text = text.replace("\r\n", "\n")` at parse_outcomes
entry, before terminal AND class/test/count field parsing. No strip/splitlines,
new terminal fallback, identity/count bypass or producer/runtime change was
introduced. Existing LF, unique terminal-1, exact discovery/identity/status,
no duplicate/zero/unknown, failure/error/skip and actual host-exit checks remain.
Bare CR, doubled CR and unrelated Unicode separators are explicitly rejected.

The fixture stores the original raw stdout as JSON-escaped UTF8, not a lossy
text-mode read. Its decoded bytes are exactly5688 and SHA256
adcff0fe61e35d8847505ba1d51102f3b7a4d39888d4f4ce795e8517464ba3f3,
equal to original device-nodes37.txt and producer raw log. The existing test
module adds two methods for actual original-seven CRLF/LF identical accounting
and rejection of unrelated separators. Existing negative methods now exercise
BOTH LF/CRLF for missing/duplicate/wrong/unknown outcomes, wrong discovered
count, failed/error/skipped statuses, crash/abort/missing or duplicate terminal,
wrong terminal code and wrong JUnit count. All incumbent methods are retained.

Declared actual negative witness before the production correction:
`python -B -m unittest discover -s android\accessibility-tests -p test_*.py -q`
ran248 methods/15.842s, FAILED(errors5): four CRLF failure/error/skip subtests
and the actual original raw CRLF positive were rejected by the old terminal
predicate. The unchanged LF positive remained successful. After the one-line
correction the SAME command ran248/248PASS,0F/E/S,14.842s. Four declared
validators (`verification_config.py --check`, `validate.py`, `workflows.py`,
`portmap.py` under tools/android-port/controller except portmap at parent)
all exited0; `git diff --check` passed. No local full Gradle/native replay ran.

Corrected PURE account_outcomes on unchanged original325 raw bytes and its
actual r31 binding/owner/hostexit0 produced7discovered/started/matching/PASS,
0failed/errors/skipped,43.83s/terminal-1 and acceptancefalse. Parser object is
a6317895cfb91ff2c1025ef1e40e82002d3c9e2f; separately labelled retrospective
accounting binds that parser object and its successor commit to original325,
not a new native execution. Actual original operations SHA256
01f3702339469b1c3638f886e0847d071f3440c7fce74f2405ff36ad8ff70c75
is unchanged, retaining primary CLI2/parser exception, no cohort result and
acceptancefalse. No source correction rewrites that actual execution outcome.
Native inputs, original assertions,120/180 deadlines and existing guests remain
unchanged. Further native execution requires a new explicit source handoff.

### Genuine corrected-source728 original-seven execution and merged CI budget repair

The coordinator authorized ONE fresh supported execution of published
`7289785c115703316177714b9b3facd4b86473a8`, reviewed by source comment
5616662441, against actual main
`0a7a84e388393557e4b66ac7fd322248e28611fd`. This is a new native execution
of the corrected parser, not retrospective adoption of325. Canonical reservation
remained runningr32; actual lifecycle producer was
`7c8e8b21a1e52a75a23ca54d063e42dd3839bd16`/disposable blob
`dd40e5677bc7ba41832485595ee328a2aeb2e9ee`. Parser object was
`a6317895cfb91ff2c1025ef1e40e82002d3c9e2f`; Android tree was
`89723e5a2d63af44388ecd73e1aff25a65712e53`, source-tree SHA256
`f0af6537279948d1e7fbf5e0a40ab6bef8e3035fcf299233d137be016c72927d`.
Frozen Swift, original235+5 methods/seven selected Nodes cases/144/current514,
native assertions, AppGradle and120s boot/180s case bounds were unchanged.

Fresh supported admission at2026-10-22T15:49:58.571301Z verified exact public
head/main/API objects/actor/pause/r32/ownership/tools/image/source bytes and
actual commit4743.80078125MiB/physical14324.44140625MiB, both above4096.
The one new guest owner was
`1666502a-0420-46ef-930f-e78651ba8b9f:nodes:e43ad608a3a74da2a1eda90ee3385719`,
private root `private-avd-nxgchx65`, token9099ba645d5549eeb13ac992f692040b.
Actual allocation and owned locks were independently read before reporting START.
Own source-bound App/Test build succeeded in2m5s/446tasks,5executed441uptodate;
AppSHA2561eb334ceb728f41cef002f526580c763eba8b3bd481dfaefbfcb9e8f2038e162,
TestSHA25620f20334f3785fa48b31d23666917a0f14f3e3c8d403a0fc33b4e5f1cd029672,
publiccert000be798aca5806230ec26d9feee36cbd0acadc857cf22a4ba711f43a4a2c8b7.

Startup15:52:27Z/returned15:52:30Z. Selected wait35860 returnedexit0 at
15:53:48Z after77.281s; fresh BOTH properties were1/stopped. No offline
transition/reattach was exercised. Actual profile was API37/release17/user0/
CPU4/font2.0/effective2.0/size360x900/density160/motion0/IME0. Real App/Test
installations and installed digest checks completed before instrumentation.
Instrumentation41708 ran15:54:27Z to15:55:19Z, hostexit0. The genuine
current-source runner accepted seven original identities:
**7discovered/7started/7matching/7PASS,0FAIL/ERROR/SKIP,43.002s,
runner_completedtrue,terminal-1,CLI0**. Operations still has acceptancefalse:
bounded cohort success is not whole-WP or merge acceptance.

All retained paths below are under
`C:\Users\camer\.copilot\session-state\b531305a-0513-4731-896f-2b470f8879cd\files`.
Authoritative actual receipt is
`wp503-728-nodes37-parser-fourcpu-a1\operations.json`;
original transcript is the same folder's `device-nodes37.txt`,
5689bytes/107CRLF/SHA256
`1ecf023cd7bd0c33cfe8891f93c8b9b86989d0c3d1f6ef846fcccb828e767b76`;
`device-nodes37-stderr.txt` is empty. Actual CLI output remains
`wp503-728-nodes37-parser-fourcpu-a1-cli.txt`; command/timing evidence remains
in `private-avd-nxgchx65`, including original raw
`adb-3d0d41677427466580d2bbf5d7ef5758.log` and instrumentation timing
`transport-timing-8f97a978d3004f668898975bf5b34c17.jsonl`.
The executed command was
`python -B android\accessibility-tests\disposable_runner.py --head 7289785c115703316177714b9b3facd4b86473a8 --api 37 --family nodes --execute --cpu-cores 4 --parent <session-files> --state <session-files>\wp503-current-local-inputs\environment.json --debug-home C:\Users\camer\.android --output <session-files>\wp503-728-nodes37-parser-fourcpu-a1 --emulator C:\Users\camer\AppData\Local\Android\Sdk\emulator\emulator.exe --adb C:\Users\camer\AppData\Local\Android\Sdk\platform-tools\adb.exe --image C:\Users\camer\AppData\Local\Android\Sdk\system-images\android-37.0\google_apis_playstore\x86_64`.
Here `<session-files>` denotes the exact absolute root above.

Independent owned disposal/verify_disposed succeeded with no execution/disposal
error. New-owner locks were NONE; only protected historical66bd remained.
Specific fresh post-cleanup CIM read found5872/26056/35860/41708 absent.
Actual END and independent cleanup were reported before the main hold released.
Existing5556/protected66bd were untouched. Original325 operations/raw hashes
01f3702339469b1c3638f886e0847d071f3440c7fce74f2405ff36ad8ff70c75/
adcff0fe61e35d8847505ba1d51102f3b7a4d39888d4f4ce795e8517464ba3f3
and separately labelled PURE retrospective accounting remain unchanged.
Original325 CLI2/cohortABSENT is not replaced by this genuine728 CLI0 result.

Separately, exact728 hosted run39804153237 completedFAILURE. Controller,
classification and Swift codec passed; Linux selected Gradle invocation timed
out after1799.9999864240003seconds and aggregate119932376343 failed.
The authoritative job119915092568 log reports that actual timeout; it does not
isolate a test/assertion root cause. No old job dispatch/rerun/cancellation or
duplicate CI-result bundle was created. Its failure is not erased by native728.

The coordinator then authorized normal current-main integration of ACTUALLY
merged PR275: main `f81d2f56d897b022b6f6c4cf38551448691eae02`, parents
`0a7a84e388393557e4b66ac7fd322248e28611fd` and
`e138ac0fe27c501604a98ba4c6dfadefc686d584`. Incoming five producer paths
remain byte-identical to trusted main: selected scaffold/protocol Gradle bound
3600s and Linux job75minutes, with unchanged default/benchmark1800, preflight60,
native120/180, strict tasks/graph/exit/diagnostic gates and regression coverage.
Existing merge-integration capability admitted this normal disjoint merge;
same canonical reservation remainedr32 with identity/binding/history unchanged.
No native source, assertion, fixture, lifecycle API, new native action or66bd
state mutation accompanies integration. Native success stays bound to728/0a7,
not the integration successor; its own hosted result must be checked separately.

On this integrated source, declared
`python -B -m unittest discover -s android\accessibility-tests -p test_*.py -q`
passed248/248,0failures/errors/skips,in16.903s. With repository
tools/android-port and tools/android-port/tests on PYTHONPATH,
`python -B -m unittest test_ci test_workflows -q` passed40/40,0F/E/S,in6.312s,
including the incoming bounded-batch/workflow regressions.
`python -B tools\android-port\controller\verification_config.py --check`,
`python -B tools\android-port\controller\validate.py`,
`python -B tools\android-port\controller\workflows.py` and
`python -B tools\android-port\portmap.py` each passed/exit0.
`git diff --check` and `git diff --cached --check` passed.
Full local Gradle remains explicitly UNVERIFIED; feature publication does not
claim a hosted verdict. This integration executes no native attempt, old-cap
CI rerun, device action or historical66bd transition.

### Exact67ae LoS admission stopped before allocation; normal401 integration

After source review5617847092 of exact
`67ae680c24c36751db940c56de585cc07b4470ee`, the coordinator authorized at
most one original LoS API37 observation, then only after positive END/cleanup
one original cached-chat observation, with fresh guards and unchanged CPU4/
120s boot/180s selected bounds. Actual main had advanced to
`4013368b7f47fdd51e291059bf690ced0dc4c434`, the normal PR283 merge with
parents `f81d2f56d897b022b6f6c4cf38551448691eae02` and
`1c0219ee3a6c6d59f1931eca3cae29a758e931e5`.

Read-only admission verified exact published67ae/OPENready/actorcbattlegear,
canonical runningr32, immutable LoS original single identity, current policy/
manifest/pause and the four reviewed lifecycle API objects. It then found a
real additional producer source predicate: trusted_guest compares EVERY
local.reserve.runtime_sources module against immutable trusted default-base
before allocation. Only tools/android-port/controller/authority.py differed:
candidate blob073876aef2ba15306998538c5973b7da6a07f87c versus trusted-main
aec8ed9c6755b62fa445f578b7c4dacaedfabca1. That existing predicate would
raise "Disposable worker code differs from immutable trusted default-base:
tools/android-port/controller/authority.py". This was direct byte/predicate
verification, not a claimed caught factory exception.

Factory was NOT called: no guest, build, allocation, native START or new
resource existed. LoS did not run; cached-chat was not attempted after the
first admission failed. Memory/device readiness and native outcomes were
not manufactured from source checks. NO START and the exact source prerequisite
were reported; coordinator explicitly released main serialization. Existing
5556/protected66bd, original325 negative receipt, PURE accounting and genuine728
native receipt/transcript remained unchanged.

Coordinator authorized normal current-main integration, not an exception to
the all-runtime guard, runtime installation, changed historical reservation or
an unchanged67ae CI/native rerun. Existing merge-integration capability admitted
the three producer paths, retaining canonical runningr32 identity/binding/history;
incoming authority.py, test_backends.py and WP003 deviation remain byte-identical
to actual401. This inherited fix proves authenticated historical landing-base
ancestry without relaxing worker terminality/receipt/identity/protected gates.
All Android product/audit/binding/AppGradle/assertion/lifecycle API bytes remain
equal to67ae/728. The containing merge successor needs its own source review and
explicit fresh source handoff before native START. Neither this integration nor
pending hosted67ae evidence turns the no-start into a native result.

On the integrated source,
`python -B -m unittest discover -s android\accessibility-tests -p test_*.py -q`
passed248/248,0F/E/S,in15.411s. With tools/android-port and its tests on
PYTHONPATH, `python -B -m unittest test_backends test_state test_capabilities -q`
passed108/108,0F/E/S,in11.758s. The declared verification_config.py --check,
validate.py, workflows.py and portmap.py commands each passed/exit0; unstaged
and staged whitespace checks passed. Full local Gradle remains UNVERIFIED.
No factory/device/host-process/native operation accompanied these source checks.

### Actual b899 LoS negative and bounded fixture-readiness source repair

The reviewed b8994aa75c055e31b0436bc6ee22d7a6e67cfe37/main401 handoff admitted
exactly the original saved-repeater LoS method on one fresh API37 guest. Fresh
commit headroom4306.30859375MiB and physical availability7318.48046875MiB
both exceeded4096MiB; all-runtime source, API, actor, pause, policy, manifest,
canonicalr32, tool and image checks passed. The profile was CPU4/font2/effective2/
360x900/density160/motion0/IME0, with fresh boot1/stopped and no offline recovery.
The source-bound installed App/Test digests and public certificate matched the
retained pair; native boot120s and selected instrumentation180s were unchanged.

The actual method was discovered, started and completed once:0PASS/1FAIL/0E/S,
16.609s, host exit0, terminal-1, runner_completed=true, CLI2, acceptance=false.
The raw failure was `ComposeTimeoutException` at the then-current fixture
line115, `compose.waitUntil { catalogReads == 1 }`, within its default1000ms.
The actual counter value was not captured, so neither zero nor duplicate reads
is inferred. This happened before native-tree, endpoint-selection or elevation
assertions; none of those downstream checks is claimed to have failed or passed.

ActualEND was2026-10-22T17:17:42.384276Z. Owned disposal was verified, and the
independent17:19:27.540628Z read found new-owner locksNONE and exact server,
emulator and instrumentation PIDs56056/47128/32868 absent. The sole protected
historical66bd lock remained unchanged. Main serialization was released.
Cached-chat was NOT STARTED under the first-execution-failure stop rule.
There was no retry. Original325 and728 evidence remains unchanged.

The unchanged operations, raw3250B transcript, logcat and independent cleanup
remain in session files `wp503-b899-los37-fourcpu-a1`. Exact paths, hashes,
source, ownership and observed counts are recorded in
`los-fixture-readiness.json`; it is negative native evidence, not a new CI bundle.

The bounded follow-up changes only this LoS fixture's readiness boundary:
its actual current `LineOfSightEntry` receives a test tag, Compose semantics
must observe that mounted tag, and `waitForIdle` synchronizes composition/effects
before the original unchanged1000ms/count==1 predicate. A LoS-only JUnit failure
watcher records actual catalog/elevation counters and whether current fixture
semantics were observed, plus a depth5/8192-character Compose tree.
All original identities, dependencies, counter/elevation/native-action/selection/
offscreen/48dp assertions and helper implementations are retained. Neither the
global harness nor production code, native deadlines, CPU count or profiles change.
The binding retains the original fixture object separately from the exact repaired
current object; all original producer/helper/subtree pins and ancestry remain strict.

This repairs an unsynchronized fixture boundary. It does not establish the
observed timeout's cause or show that the repaired case passes. No native
execution of this changed fixture is authorized or claimed by this source update;
the exact published successor requires review and a separate fresh handoff.
