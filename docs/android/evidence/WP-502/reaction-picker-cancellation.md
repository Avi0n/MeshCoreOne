# WP-502 App reaction-picker cancellation/reopen

Base: `31557a16ca4492bbd669a7ad0358e702c5c54031`; immutable Swift specification:
`db14559b39d32322b06477c6ae676112f583db50`. The exact candidate head is bound by
its PR/automatic hosted run, not by a self-referential source checksum.

## Source and consumer

- Frozen `MC1/Views/Chats/Reactions/Sections/ActionsEmojiSection.swift`, blob
  `c973508b61297cf9f5f308ed0420f6dac38d1ea7`: nil selection on child cancellation
  does not invoke the parent action; a selection is consumed and cleared after
  child dismissal.
- Actual `AppMessageActions.kt` already mounts MoreEmojis/full EmojiPickerSheet,
  clears the child on cancellation/pick, and runs feature.react, recordReaction
  and parent dismissal through the real effect. No product edit is needed here.
- Existing `EmojiReactionRegistrationTest` covers a successful pick only.
  Its complete file and all ReactionDetails/NewChat/Rooms cases remain unchanged.
- New owned `ReactionPickerCancellationComposeTest` mounts that actual App
  navigation/consumer with the existing ContainerHarness and real in-memory
  Room/DataStore adapters; it does not substitute a sheet/evaluator/action handler.

## Assertions

The child searches for grinning face without choosing, then invokes the actual
child sheet's Dismiss semantics (not an OS Back/TalkBack claim). Parent retention
and the next real MoreEmojis click are required. Before cancellation, after
cancellation, and after reopening/searching rocket, complete persisted conversation
rows/reactions, pending sends, DM/channel sends, frequent/recent lists and observed
preference transitions are unchanged. The live connection token/navigation entry
are preserved and messaging diagnostics remain empty.

Only the fresh rocket selection may send: exactly one DM frame and one additional
delivered outbound Room row for the correct radio/contact; both sheets disappear.
Expected wire text is the selected emoji plus literal hash `d11q0688`, independently
derived with Python hashlib SHA-256 over UTF-8 `Synthetic reactable message` plus
UInt32LE(1), first five digest bytes encoded as Crockford Base32. The candidate
reaction builder does not generate its own expected value. The real preference
observers require exactly the seeded state and one final durable MRU transition,
including preservation/order of the two seeded choices and no abandoned grinning
selection. No assertion pretends that idempotent preference writes expose method
invocation counts.

## Admission and verification boundary

Same canonical session/attempt `3bc8c1b8-20ec-4647-88ae-db3775268489`, same branch
and worktree; reservation56 ->57 through trusted wp-owned/create/modify. Only the
new owned test, this evidence and the WP502 deviation change; no release/recreate,
product-path admission, primary original-case credit, shared test/runner/build
wiring, dependencies or expected-outcome policy changes.

Declared `python .\tools\android-port\controller\ci.py preflight` exits2:
`Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness`.
Source checks `python .\tools\android-port\portmap.py`,
`python .\tools\android-port\controller\validate.py`,
`python .\tools\android-port\controller\verification_config.py --check` and
`git diff --check` passed (exit0). These do not compile or discover the new case.
No local manual Gradle/Kotlin/native execution is performed. Source validation
and the full immutable-main pre-push guard are separate from App test execution;
an unavailable fast check records the genuine exact-candidate UNVERIFIED state.
At authoring, the new case is not yet compiled/discovered/run. Its own automatic
hosted run must establish those outcomes; no old candidate/lower verdict transfers
and no second CI-result bundle is saved, hashed or uploaded.

This adds one Android-only source-derived flow, not new original-family credit,
API31/37 emulator evidence, approved visual goldens, physical/TalkBack/radio or
whole-WP acceptance. Those boundaries are not changed by this bounded increment.
