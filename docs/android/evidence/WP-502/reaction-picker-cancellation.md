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

The child searches for grinning face without choosing, then dispatches Back
down/up events to its actual Robolectric Dialog, first requiring a distinct
showing child window from the captured parent. This reuses the incumbent
MessagePathComposeTest native-dialog input pattern, not an emulator/OS/TalkBack
claim. At most two Back events target only that same still-showing child:
Material may first collapse an expanded sheet to its partial detent. The child
must actually close and the parent Dialog must stay showing. Parent retention
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

The child field is located by its real text-edit action inside the dialog, not
its empty-only placeholder: the App keeps the picker holder and its `grinning`
query when the child is cancelled. Reopening must expose that retained query
before replacing it with `rocket`. The same field predicate must be absent on
child cancellation and after selection, so a hidden placeholder cannot fake
sheet dismissal. This source-review correction supersedes the first authoring
candidate's placeholder locator without changing product behavior or weakening
the cancellation/selection assertions.

## Actual60c795 failure and input repair

Own automatic39876887409, attempt1, exact60c795d189b2e93e866d2cf60c292b8afefb5fe0,
completed FAILURE. Official Linux120165866922 discovered/ran App1264,1263 passed,
one failure, zero errors/skips. The sole App failure is this new method:
`Expected exactly '1' node but could not find any node that satisfies:
((Dismiss is defined) && (hasAnyDescendantThat(SetText is defined)))`.
The compiled/executed case reached that input; this is not a passing flow or
evidence of an App product defect. Mandatory later module reports were missing
because the declared build stopped at the App test failure; no full-suite pass
or complete failed-command evidence is inferred.

The input-only repair targets the actual child Dialog's Back event path instead
of assuming that the Material Dismiss action is an ancestor of the search field.
It additionally checks different parent/child Dialog identities and a still
showing parent after cancellation. All no-send/Room/preference/query/once-only
selection checks remain intact. The old failure remains60c795-bound; only a new
candidate's own run can verify the repaired path. No manual local build, runner
change, weakened expectation, product edit or old-run rerun/cancel accompanies it.

Own repaired d451b9ff9cf63f5437e1921003c2c9e67c682f20 run39879520068,
attempt1, also completed FAILURE. Official Linux120174222835 App1265/1264 passed/
one failure/zero errors/skips reports a 30-second ComposeTimeoutException in the
same new method, but its displayed stack omits the source phase. No test artifact
was uploaded by that failed run. The available2270/2269/1 failed-command inventory
is incomplete, with18 mandatory later reports absent. Do not infer which of the
three waits failed, successful cancellation/selection, or a product defect.

A focused diagnostic successor retains every input, timeout and assertion. Only
on that precise Compose timeout it now reports the named phase, actual synthetic
Room text/status/direction, actual current/observed frequent/recent values, radio
opcode counts and messaging diagnostics, then rethrows as an assertion with the
original timeout cause. No exception is swallowed and no success-shaped fallback
or runner/reporting-policy change is added. Another exact-candidate execution is
required to identify the unresolved condition rather than speculate or weaken it.

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
