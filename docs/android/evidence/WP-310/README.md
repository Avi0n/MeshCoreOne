# WP-310 implementation evidence

- Repository: `cbattlegear/MeshCoreOne-Android`
- Work package: `WP-310`
- Owner: `chats-ui-engineer`
- Base: `ffb7ff6092fc07c70ea0f31020cf68907d6c4836`
- Frozen Swift source: `db14559b39d32322b06477c6ae676112f583db50`
- Issue: `#118`

## Implemented acceptance

- `WP-310-behavior`: room login/contact lookup, reconnect, message sync/read side effects, ordered live
  arrivals, post/retry and guest/member states; channel option/create/private/public/hashtag/link join,
  share URI/QR callback and region propagation.
- `WP-310-boundaries`: typed failures, exact 16-byte secrets, 31-byte UTF-8 names, slot-zero reservation,
  available-slot ordering, hashtag full-name passphrase, cancellation propagation, deterministic room
  ordering/grouping and Material 3 sheet/accessibility behavior.
- `WP-310-source-test-parity`: frozen room ordering and channel validation/confirmation families are bound
  to 26 Android assertions. Message translation is user-excluded from active scope and future builds.

## Verification

Pinned session-local JDK 21.0.12.1 and Android SDK 37.2/build-tools 37.0.0 were used.

1. `.\android\gradlew.bat -p android :feature:chats:compileDebugKotlin --console=plain`
   - Result: passed.
2. `.\android\gradlew.bat -p android :feature:chats:testDebugUnitTest --tests 'com.meshcoreone.android.feature.chats.rooms.*' --tests 'com.meshcoreone.android.feature.chats.channels.*' --console=plain`
   - Result: passed.
   - Discovered: 26; passed: 26; failed: 0; errors: 0; skipped: 0.
3. `.\android\gradlew.bat -p android :feature:chats:testDebugUnitTest --console=plain`
   - Result: passed.
   - Discovered: 158; passed: 158; failed: 0; errors: 0; skipped: 0.

No Android device, camera, physical radio, hardware, signing or release verification is claimed.

## Bounded issue #207 follow-up

The entries above describe the initial contribution, not acceptance of the current four-defect
repair or the full WP. The follow-up is limited to A503-01..04 (incoming author context, outgoing
room status, named link actions and Remember Password semantics). It leaves WP-501 parity/accounting
and WP-503 audit ownership unchanged.

- Base: `d958471ab4835813881a5e7af36e8249a8b273a4`.
- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
- Manifest: `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda`.
- Policy revision: `3fc0afa19ad31f0210a5d427f655ef90b50a8e64e818fe3420b188303b99fb20`.
- Reservation session/attempt: `5ca13f75-8466-4dd5-b630-02b30841a5d0`; host-verified alias
  `c2cf31d5-3af6-4536-8bb1-5afcd36781bb` identifies the same worker, not a second session.
  Initial revision 1 evolved through exact same-attempt CAS to revision 4, preserving identity and
  immutable authorization. Owned room/docs edits use `wp-owned`; the bounded App consumer/test
  support uses `app-build-launcher`. Shared advisory intents are not pre-edit locks.
- Prerequisites: PR #203 actually merged at the base above; PR #50 actually merged at
  `738b10092237a48f6d0a38590fbacea7e8ddfe5f`, verified as an ancestor. Their existing evidence
  was read; this repair does not upgrade their overall acceptance.

Frozen inputs read: complete `RoomMessageBubble.swift`, all six complete
`RoomMessageBubbleA11yLabelTests.swift` cases, `RoomMessageDTO+Status.swift`,
`RoomAuthenticationSheet.swift`, `NodeAuthenticationSheet.swift`, and the English Chats/RemoteNodes
resources. Existing formatter, link accessibility naming/ordering/cap, MessageBody, dispatcher/router,
state holder and actual App route consumers were inspected. Frozen bubble/tests/auth blobs match the
pin. No frozen source or localization resource was edited.

`RoomAccessibilityComposeTest` retains the six original predicate families and adds all six room
statuses, merged/unmerged production semantics, real state-holder retry and join results, shared
mention/hashtag/map routing, link order/dedup/cap, an unconsumed action result and Spanish localization.
These are deterministic Robolectric tests on API 31/37, not device/TalkBack observations.

Readiness command:
`python .\tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit d958471ab4835813881a5e7af36e8249a8b273a4 --stages preflight`
passed using the supported Git-bundle/ext4 snapshot and installed pinned Linux toolchain.
Candidate native regression results will be reported on the exact commit in the PR/issue.
No duplicate hosted CI-result bundle is retained here. Formal WP-310 verification is still
`configured: false`; full-WP/device/physical-radio/human acceptance is not claimed.
