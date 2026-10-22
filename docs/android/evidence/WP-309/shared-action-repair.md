# WP-309 shared-action repair candidate

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `chats-ui-engineer`.
Original repair base: `0a7a84e388393557e4b66ac7fd322248e28611fd`.
Integrated trusted main: `f81d2f56d897b022b6f6c4cf38551448691eae02` (WP-003 PR #275).
Source: `db14559b39d32322b06477c6ae676112f583db50`.
Candidate head is the commit containing this file (reported exactly in the successor PR).
Manifest SHA-256: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Policy revision: `70f65b1205f1581c0f1a86107ccdc20f794421a6cf9b172e17d8a3e544569a66`.
Session/attempt: `c2707711-5b8b-4bfb-8d51-ada7caaf12a4`;
managed branch: `cbattlegear-laughing-barnacle`. Existing tracking issue: #117.

## Implemented scope and source mapping

- `MessageActionsUi.ActionButtons`: removes the active Translate entry, preserving original message text,
  localized retained actions and callback availability. The inert enum/consumer contract stays unchanged.
- Frozen `ReactionDetailsSheet.swift`: restores pending versus successful empty state, empty title and
  description, sender-relative named times and ordered sender presentation. Adds explicit Android
  failure/retry and cancellation handling rather than presenting diagnostic failures as empty success.
- `MessageActionsStateHolder`: reaction-specific loading/failure/cancellation state cannot be confused with
  concurrent path-detail failure. Superseded reads are cancelled and cannot replace current results.
  Restored open-dialog state triggers a new read; display-clock refresh never fetches or sends.
- `ReactionsAndStateTest`: two additional deterministic tests cover delayed pending/empty, failure/retry,
  cancellation and late previous-message results. All incumbent assertions remain.
- `MessageActionsRepairComposeTest`: five additional mounted tests call the real public route/holder/
  data-source/dialog, with deferred reads and fixed clock. They assert English/Chinese active Translate
  absence plus retained dispatch, outgoing availability, pending-to-empty transition, localized error
  and real retry-to-content, named relative time and a 31-sender list at 200% font. No fake renderer.

Acceptance touched: `WP-309-behavior`, `WP-309-boundaries`, `WP-309-source-test-parity`.
These additions are supplemental regressions, not seven new original Swift cases or whole-WP acceptance.
See [native adaptations and separate picker producer](../../deviations/WP-309.md).

## Reservation and historical support admission

The actual trusted reservation CLI acquired `wp-owned` revision 1 for this branch/worktree/session.
Same-owner evolution to revision 2 admitted only
`android/app/src/test/kotlin/com/meshcoreone/android/app/actions/MessageActionsRepairComposeTest.kt`
under `app-build-launcher`, operation `add-direct-support-wiring`. No authorization text was rewritten,
reservation recreated, ledger hand-edited, foreign identity borrowed or candidate policy used.

Actual command:

```powershell
python tools\android-port\local\reserve.py admit --wp WP-309 --path android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/composer/ui/MentionSuggestions.kt --operation repair-shared-mention-picker
```

Historical result: **BLOCKED**, no trusted WP-309 path+operation rule. Primary source ownership is WP-308;
the shared repair capability admits only WP-502. Picker source and all Rooms/channel paths remain
untouched. The repair is now assigned to the actual WP-308 producer in
[#280](https://github.com/cbattlegear/MeshCoreOne-Android/pull/280),
head `e05535e818a9d81e9dfa810a3dfff9503abb64bc`, with WP-502 assigned the dependent consumer assertion.
No new WP-309 authorization is needed or requested; no cross-owner edits or acceptance claims are made.

## Actual verification and limits

| Exact command/check | Actual outcome |
| --- | --- |
| `git diff db14559b39d32322b06477c6ae676112f583db50 -- MC1\Views\Chats\Reactions\ReactionDetailsSheet.swift MC1\Views\Chats\Mentions\MentionPickerSheet.swift MC1\Views\Chats\ContactMatchRow.swift` | Passed: no source drift for these inspected inputs. |
| `python tools\android-port\controller\validate.py` | Passed: valid pinned manifest/source/ownership, 64 WPs, 178 edges, 1,786 owned and 80 excluded reference files. This does not run Kotlin tests. |
| `git diff --check` | Passed before publication. |
| `python android\scaffold\check_environment.py` | BLOCKED (exit 2): inherited non-allowlisted environment; direct shell is not an admitted isolated build environment. No install/restore attempted. |
| `python tools\android-port\local\reserve.py status` | Observed WP-503 native execution ownership and, initially, its private guest host Gradle guard. Later guest/Gradle guards disappeared but the native receipt remained. No inference of native cleanup or lock bypass. |

Kotlin/JVM and mounted Compose tests are **not executed locally**: 0 discovered/run for this candidate,
not passing or zero-test success. The declared smallest relevant tasks are
`:feature:chats:testDebugUnitTest` and the existing App `:app:testDebugUnitTest` with the new mounted
class plus incumbent `ReactionDetailsComposeTest` selected. The observed native ownership was historical;
the coordinator subsequently reported genuine WP-503 cleanup, not authority to adopt another native lane.
Supported exact-commit local admission and automatic push-hook outcomes are recorded in the PR.
No full repository, emulator/device, hardware, iOS, TalkBack, screenshot/golden, signing, release,
performance or license verification is claimed.

Publication is an **explicitly unverified candidate**, not a merge or completion receipt. Hosted
exact-head job/log remains authoritative; no second CI bundle is retained. WP-003 CI timeout repair is
outside this change. Merge acceptance remains pending actual exact-candidate checks and review;
the separate picker producer is not a missing WP-309 authorization blocker.

## Integrated CI-budget repair successor

The authoritative prior candidate run
[39810035170](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39810035170)
for head `dd67ad3dc0a024f40a821684c41e653d58fb7aea` failed. Its actual Linux job
[119933702439](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39810035170/job/119933702439)
records the selected Gradle command timing out after **1799.999986816 seconds**.
This is not verified Kotlin/Compose results or evidence that zero tests executed on the hosted runner.
No duplicate log/result bundle is retained.

Actual inspection command:
`gh run view 39810035170 --job 119933702439 --log-failed`.
Result: command succeeded; official failure log reports the timeout above.

`git merge --no-edit origin/main` integrated actual trusted main
`f81d2f56d897b022b6f6c4cf38551448691eae02` without conflicts. The imported WP-003 change provides
the bounded one-hour selected-command budget; its implementation/tests remain attributed to WP-003.
WP-309 product and test bytes remain unchanged from `dd67ad3dc0a024f40a821684c41e653d58fb7aea`.
This is a changed integration candidate, not an unchanged rerun or a claim the timeout repair passed
this feature's tests. Next exact-head hosted results remain pending publication.
