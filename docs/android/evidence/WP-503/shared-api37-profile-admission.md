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
