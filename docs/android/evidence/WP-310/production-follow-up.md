# WP-310 production follow-up

Repository: `cbattlegear/MeshCoreOne-Android`.
Owner: `chats-ui-engineer`.
Managed branch: `cbattlegear-verbose-tribble`.
Session: `981b6533-0bfa-4dbf-b45b-9cb3a5e24a2b` (CLI
`0776ce12-4a55-4bf0-b6ef-4599032aa7a3`).
Base/current main at implementation: `0a7a84e388393557e4b66ac7fd322248e28611fd`.
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest SHA-256: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Trusted policy revision: `70f65b1205f1581c0f1a86107ccdc20f794421a6cf9b172e17d8a3e544569a66`.
The successor PR identifies its exact candidate head. Hosted results/logs, not a second committed
CI-result bundle, are authoritative for that head.

## Implemented behavior and inputs

`WP-310-behavior`, `WP-310-boundaries`: fix the production pending-reload lifetime, coalescing,
generation invalidation, cancellation ownership and serialized follow-up loads. Preserve ordered,
deduplicated rows and existing read/notification side effects. Post/retry invalidate older fetches;
Send Again posts original text through the existing service without consuming a draft or sending twice.

Restore mounted room action presentation, Copy, preference-sensitive Reply/Mention and composer
focus, radio-scoped Send DM selection and actual chat navigation, Send Again and room metadata.
Original permission/direction/author availability remains unchanged. No room reactions/delete/path
or Translate action is introduced. App support is confined to the room route's existing preference
and navigation wiring; WP-309 action files are untouched.

Pinned inputs inspected: `RoomConversationView.swift`, `RoomConversationViewModel.swift`,
`RoomMessageActionAvailability.swift`, `RoomMessageAction.swift`, `RoomMessageActionsSheet.swift`,
`RoomMessageDTO+Status.swift`, `SendDMSheet.swift`, existing original ordering cases and native tests.
The inspected room source diffs against the frozen pin are empty. Existing shared `MessageAction`,
`MentionUtilities`, `SenderContactMatcher`, link dispatcher, room service/data seams, localization,
App consumer and reactive preference contract are reused.

`WP-310-source-test-parity`: existing ordering and accessibility original families are retained.
New regressions are supplemental behavioral cases, not a claim that all original WP cases are ported:
10 deterministic scheduler/send methods, one exhaustive availability parameter-family method,
and 9 mounted room action methods configured for API 31/37, including Chinese resource coverage.
These are authored cases, not discovered/passed counts. The standard localized Details click action
leaves existing Retry/link custom-action lists, their order/dedup/cap and original speech unchanged.

## Admission and execution limitations

Trusted `reserve.py admit` accepted primary room production/test/evidence paths as `wp-owned`.
It accepted the necessary `AppChatsContent.kt` room wiring and App room regression paths as
`app-build-launcher`, using `update-launcher-or-composition` / `add-direct-support-wiring`.
Those rules require pinned source/traceability, one WP/branch/PR, process/connection lifetimes,
no unrelated activation, and affected compilation/tests plus manifest/navigation checks.

Initial command:
`python tools\android-port\local\reserve.py claim --wp WP-310 --session-id 981b6533-0bfa-4dbf-b45b-9cb3a5e24a2b --authorization "Start the fixes ya ding dong" --base 0a7a84e388393557e4b66ac7fd322248e28611fd`
returned **BLOCKED: Existing WP lease is bound to different source/base/manifest/backend**.
The historical record is session/attempt `5ca13f75-8466-4dd5-b630-02b30841a5d0`, revision 5,
PR #209, stored base `d958471ab4835813881a5e7af36e8249a8b273a4`. GitHub confirms #209 merged
as `d8181418614a5acaa46f4acde54b741b492ac4b0`, first parent
`726f00dd45bf5d1a55e29a3775ea474a2e5787bf`. There is no retained terminal receipt.
The coordinator routed supported historical reconciliation to WP-003 and explicitly corrected that
advisory file intents are not source-edit locks. No predecessor impersonation, release/recreate,
authorization rewrite, manual ledger mutation or unsupported capability is used.

`python tools\android-port\local\check.py --commit HEAD --stages preflight`
returned **BLOCKED: Malformed installed reservation runtime manifest** before execution.
The existing WP-503 native resource remained held. No runtime installation/replacement, Gradle,
emulator, hardware, device, signing or release execution was attempted around that guard.

## Checks and pending verification

- `git diff --check`: passed.
- `python tools\android-port\controller\validate.py`: passed; 1,866 frozen reference files,
  64 WPs, 178 dependency edges.
- `python tools\android-port\portmap.py`: passed after correcting new mixed-disposition headers;
  1,815 traced Kotlin implementations. Headers are not behavior acceptance.
- `python tools\android-port\controller\verification_config.py --check`: passed;
  feature verification remains `configured: false`.
- `python tools\android-port\l10n_convert.py --check`: passed; 63 generated files, 12 locales,
  no unsupported inputs. No localization/resource change.

Publication of initial head `4e7eb81a49a63396cc9eb7005f3031596c5461fe` ran the already-installed
automatic pre-push hook. Its source/scaffold/accounting checks passed (15 scaffold and 108 accounting
Python methods). Its actual `python -m unittest discover -s tools/android-port/local -p '*test*.py' -v`
ran 43 tooling methods: 42 passed, one failed, zero errors/skips. The disposable-entry test expected
an immutable-base mismatch but received the WSL guard
`Disposable lifecycle cannot emulate the canonical Windows ledger from WSL`.
The hook stopped before Kotlin, reported **UNVERIFIED CANDIDATE**, and correctly allowed publication.
This is a tooling dependency failure, not a Kotlin result or permission to replace the runtime.
The successor also preserves the original exact Retry/link custom-action lists by exposing Details
as the standard labelled click action rather than inserting into those lists.

Required targeted commands below are **NOT RUN locally**, because supported readiness/admission is
blocked. No discovered/passed/failed/skipped Kotlin count is claimed:

```powershell
.\android\gradlew.bat -p android :feature:chats:compileDebugKotlin :app:compileDebugKotlin --console=plain
.\android\gradlew.bat -p android :feature:chats:testDebugUnitTest --tests 'com.meshcoreone.android.feature.chats.rooms.*' --console=plain
.\android\gradlew.bat -p android :app:testDebugUnitTest --tests 'com.meshcoreone.android.app.rooms.*' --console=plain
```

This is an explicitly **locally unverified candidate**, not full WP acceptance. Exact-head hosted
compilation/test results must be read from its PR checks. API/device/TalkBack, physical radio,
full-WP original-case completeness and protected/human acceptance are not claimed.
