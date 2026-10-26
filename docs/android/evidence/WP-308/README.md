# WP-308 evidence

## CHAT-CHANNEL-COMPOSER-PROMPT: isolated unverified candidate

Repository `cbattlegear/MeshCoreOne-Android`; owner `chats-ui-engineer`;
actual session `aa4b9d95-ca8d-4023-bb04-049e969e6d6c`, managed branch
`cbattlegear-literate-waffle`, base `61fc863172e9248f3ba913acefeb6fd2354d2580`.
Swift stays pinned to `db14559b39d32322b06477c6ae676112f583db50`.
The successor PR identifies the candidate head; historical PR280 is not this repair.
Trusted policy revision: `46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8`.
Raw Git manifest SHA-256: `8376af85d2032ad45628f82489c71dec218ee330cbc65a33c9100deb564b7c3f`;
raw Git policy SHA-256: `4b37bb10971637b8bc2c1aaa56d4da2be918028a0fb7e6cbad31330800e2c2e6`.

The assigned grouping packet
`semantic-grouping-global-consolidated-r118-candidate-001.json` was verified at
3,944,674 bytes / SHA-256
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`.
Its exact three families are `ChatConversationTypeTests::Public channel (index 0) isPublicStyleChannel is true()`,
`ChatConversationTypeTests::Hash-prefixed channel isPublicStyleChannel is true()`, and
`ChatConversationTypeTests::Private channel isPublicStyleChannel is false()`, all singleton
families in `MC1Tests/Views/Chats/ChatConversationTypeTests.swift`.
Their existing predicate assertions remain untouched. Frozen
`MC1/Views/Chats/ChatConversationInputBar.swift:15-44` consumes that classification
to select the DM, public-channel or private-channel localized prompt.
The actual native App caller instead always supplied the DM prompt.

The bounded repair selects the existing localized resource at that caller using
the existing channel encryption/style predicate. It changes no model, encryption,
public/private flags, routing, draft, byte limit, send pipeline, connection lifetime,
theme or localization data. `ComposerPromptComposeTest` mounts **AppChatsContent**
over the existing AppContainer/Room/fake-radio harness, rather than testing a new
standalone prompt helper. Four methods cover index0/Public, index5/#general,
index3/Secret and a DM control on APIs31/37 (eight expected executions, **not
discovered or run locally**). Assertions require the actual visible prompt, absence
of the other prompts, unchanged editor security semantics, empty draft, no error,
one radio session and no message-send command. No new original-family credit.

### Admission and actual bookkeeping

Trusted `app-build-launcher` admits
`android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt`
with `update-launcher-or-composition`, and
`android/app/src/test/kotlin/com/meshcoreone/android/app/navigation/ComposerPromptComposeTest.kt`
with `add-direct-support-wiring`. Required invariants are no unrelated feature
activation, process/connection lifetimes, GMS-independent core and unchanged
min/target/applicationId; required validation is affected compile/tests and
manifest/navigation checks. Both WP-308 evidence/deviation paths admit
`record-evidence` under `wp-owned`.

The actual claim using this session and base61fc failed:
`Existing WP lease is bound to different source/base/manifest/backend`.
The historical WP-308 advisory row remains revision4, attempt/session
`2681153a-3093-4a60-b45d-79037d0925ae`, issue208/PR213, another branch/worktree;
the earlier truthful evolution attempt failed
`Capability evolution caller differs from the stored session/branch/worktree`.
There is **no acquired receipt or manufactured successor identity**. No row,
authorization or historical binding was edited, released or recreated.
`AUTOMATION.md`'s Current local coordination contract permits assigned,
capability-admitted isolated authoring after preserving this actual failure:
historical binding mismatches are not pre-edit locks. Existing advisory intents
on the caller are not proof of conflicting live writes. The working tree was clean;
this repair is restricted to prompt selection, not the separately assigned spinner.

### Actual commands and limits

| Command | Actual outcome |
| --- | --- |
| `python tools\android-port\local\reserve.py claim --wp WP-308 --session-id aa4b9d95-ca8d-4023-bb04-049e969e6d6c --base 61fc863172e9248f3ba913acefeb6fd2354d2580 --authorization 'Existing user-assigned CHAT-CHANNEL-COMPOSER-PROMPT three-family repair; advisory bookkeeping attempted without changing historical identities.' --path android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt --operation update-launcher-or-composition` | BLOCKED, historical binding mismatch above; no acquired receipt |
| `python tools\android-port\local\reserve.py admit --wp WP-308 --path android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt --operation update-launcher-or-composition` | PASS, `app-build-launcher` |
| `python tools\android-port\local\reserve.py admit --wp WP-308 --path android/app/src/test/kotlin/com/meshcoreone/android/app/navigation/ComposerPromptComposeTest.kt --operation add-direct-support-wiring` | PASS, `app-build-launcher` |
| `python tools\android-port\local\reserve.py admit --wp WP-308 --path docs/android/evidence/WP-308/README.md --operation record-evidence` | PASS, `wp-owned` |
| `python tools\android-port\local\reserve.py admit --wp WP-308 --path docs/android/deviations/WP-308.md --operation record-evidence` | PASS, `wp-owned` |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED, explicit state / `ANDROID_CI_STATE` required; Gradle not started |
| `python tools\android-port\local\check.py --commit HEAD --stages preflight` | BLOCKED before execution: `Malformed installed reservation runtime manifest` |
| `python tools\android-port\portmap.py` | PASS after correcting a mixed AndroidOnly/PortedFrom header; provenance only, not behavior |
| `python tools\android-port\controller\validate.py` | PASS, 64 WPs / 178 edges / 1866 reference files; not behavior |
| `git diff --check` | PASS |

WP-308's trusted manifest still has `verification.configured=false`, `commands=[]`.
The existing App harness uses declared task `:app:testDebugUnitTest`; the targeted
consumer selector is `com.meshcoreone.android.app.navigation.ComposerPromptComposeTest`.
It was **not executed**, with zero discovered tests for this candidate locally.
No compile, lint, UI, device, hardware, signing, legal or release pass is claimed.
The malformed runtime was not bypassed or replaced; the protected native-execution
lock was not touched. This is an **unverified feature candidate**, not verified
acceptance of `WP-308-behavior`, `WP-308-boundaries` or `WP-308-source-test-parity`.
Exact-candidate hosted logs remain authoritative; no duplicate CI bundle is retained.

## Bounded mention-picker successor: unverified candidate

### Actual hosted failure and bounded layout correction

Initial head `e05535e818a9d81e9dfa810a3dfff9503abb64bc` failed authoritative
run `39814923682`, Linux job `119950976620`: 1233 App tests, five failures.
`gh run view 39814923682 --repo cbattlegear/MeshCoreOne-Android --job 119950976620 --log-failed`
was read directly. Four failures were Done / localized Done not displayed in the
two long-list methods on APIs 31/37; the fifth is the separate WP-502 Room label.
This is actual negative UI evidence, superseding the earlier not-run statement for
that head, not a timeout or a queued-runnable explanation.

The successor production change sets `rememberModalBottomSheetState(skipPartiallyExpanded = true)`
so the long sheet's footer cannot start below the visible partially-expanded viewport.
All mounted tests/assertions remain unchanged. The stack 285 identity/order stays
intact; upper WP-502 owner adopts the new lower head normally. No individual merge,
CI dispatch/rerun or native execution is authorized by this correction.

Repository `cbattlegear/MeshCoreOne-Android`; WP-308 / chats-ui-engineer.
Base `0a7a84e388393557e4b66ac7fd322248e28611fd`; source remains
`db14559b39d32322b06477c6ae676112f583db50`. Managed branch `cbattlegear-literate-waffle`;
session `74d2e6c3-7fd6-4619-adb8-7c2ece0adc9f`. The PR identifies the exact candidate head.
Manifest SHA-256 `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
unchanged trusted policy revision `70f65b1205f1581c0f1a86107ccdc20f794421a6cf9b172e17d8a3e544569a66`.
WP-307 PR203 and WP-218 PR25 were confirmed merged; no new dependency activation.

Source comparison against the pin found no drift in `MentionPickerSheet.swift`,
`ContactMatchRow.swift` or `ContactMatchRowTests.swift`. The omission and native adaptation are
described in the bounded successor section of [WP-308.md](../../deviations/WP-308.md).
Acceptance IDs: `WP-308-behavior`, `WP-308-boundaries`, `WP-308-source-test-parity` remain pending
exact-candidate UI verification, not established by source headers or these notes.

Commands executed from the repository root on Windows:

| Command | Outcome |
| --- | --- |
| `python tools\android-port\local\reserve.py admit --wp WP-308 --path android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/composer/ui/MentionSuggestions.kt --operation modify` | PASS, `wp-owned` |
| `python tools\android-port\local\reserve.py admit --wp WP-308 --path android/app/src/test/kotlin/com/meshcoreone/android/MentionPickerSheetComposeTest.kt --operation add-direct-support-wiring` | PASS, `app-build-launcher` |
| `python tools\android-port\local\reserve.py admit --wp WP-308 --path docs/android/deviations/WP-308.md --operation record-evidence` | PASS, `wp-owned` |
| `python tools\android-port\local\reserve.py admit --wp WP-308 --path docs/android/evidence/WP-308/README.md --operation record-evidence` | PASS, `wp-owned` |
| `python tools\android-port\local\reserve.py claim --wp WP-308 --session-id 74d2e6c3-7fd6-4619-adb8-7c2ece0adc9f --authorization 'User authorized remaining bounded WP308 mention-picker production omission and mounted regressions' --base 0a7a84e388393557e4b66ac7fd322248e28611fd` | BLOCKED, historical source/base/manifest/backend binding mismatch; no historical record changed |
| Same claim with `--path android/app/src/test/kotlin/com/meshcoreone/android/MentionPickerSheetComposeTest.kt --operation add-direct-support-wiring` | BLOCKED, same historical binding mismatch |
| `python tools\android-port\local\reserve.py admit --wp WP-308 --path android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests/RoomsComposeTest.kt --operation add-regression-fixture-or-test` | BLOCKED, no trusted path+operation rule; WP-502 producer must update old suggestion-label expectation |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED, explicit state / `ANDROID_CI_STATE` required |
| `python tools\android-port\local\check.py --commit HEAD --stages preflight` | BLOCKED before execution, malformed installed reservation runtime manifest |
| `python tools\android-port\portmap.py` | PASS, exit 0; provenance only |
| `python tools\android-port\controller\validate.py` | PASS, exit 0; 64 WPs, 178 dependency edges, 1866 tracked reference files |
| `python tools\android-port\controller\ci.py python --output C:\Users\camer\.copilot\session-state\aa4b9d95-ca8d-4023-bb04-049e969e6d6c\files\wp308-source-checks` | PASS, exit 0; 15 scaffold and 108 parity-accounting tests discovered/run/passed, zero failures/errors/skips; manifest/provenance/notices/parity/semantic checks passed |
| `git diff --check` | PASS |

The declared App `:app:testDebugUnitTest` task already owns the App Compose harness; the added class is
`com.meshcoreone.android.MentionPickerSheetComposeTest`. Four methods target APIs 31/37, expected
eight executions, but **zero Android UI tests were run or discovered locally** because the execution/readiness
gates above block Gradle. Compile/lint/UI/native results are unknown, not passing. In particular,
the old WP-502 Room consumer assertion is a known necessary source-label update, not a test exclusion.
No additional dependencies, golden updates, source-case remapping or CI-result bundle were added.
Exact-commit hosted job logs remain authoritative. No CI dispatch/rerun, budget change, emulator,
hardware, signing, release, license or physical accessibility verification occurred.

## Historical implementation evidence (not this successor)

External contribution, no lease. Local macOS runs only, not CI or acceptance receipts. Frozen source:
`db14559b39d32322b06477c6ae676112f583db50`. Base: `b8c15cc5` (origin/main). Logic, Compose UI and the native
adaptations are described in [WP-308.md](../../deviations/WP-308.md).

## Commands

Environment: JDK 21, Android command-line SDK, private Gradle/Android homes, `--dependency-verification lenient`.
The `-x :core:ui:verifySharedUiInputs -x :core:services:prepareContentInvocation` exclusions from earlier WPs are
omitted: those tasks do not exist on this base and Gradle rejects the exclusion.

```
./gradlew :feature:chats:testDebugUnitTest :feature:chats:lintDebug :app:assembleDebug validateModuleGraph
python3.13 tools/android-port/portmap.py
python3.13 tools/android-port/controller/validate.py
```

## Observed results

- Gradle exit 0 (`BUILD SUCCESSFUL`), including `:app:assembleDebug`, `validateModuleGraph` and
  `:core:l10n:verifyL10nConversion` (its self-test runs the traceability validator over the new headers).
- JUnit XML (`:feature:chats:testDebugUnitTest`): 20 suites, 304 tests, 0 failures, 0 errors, 0 skipped.
  WP-308 `composer` suites: 9 suites, 172 tests, 0 failures (`MentionUtilitiesTest` 53, `MessageTextTest` 37,
  `MessageLinkTokenizerTest` 23, `ComposerStateHolderTest` 17, `LinkLogicTest` 15, `ComposerUiSourceScanTest` 10,
  `ComposerShareTest` 8, `EmojiPickerTest` 5, `ComposerTextTest` 4). The other 132 are WP-306/WP-307.
- `lintDebug`: exit 0; no findings in `composer/`. The only warnings are the pre-existing WP-306 files
  (`ConversationListRow`, `NewChatSheet`, `ResourceChatListStrings`).
- `portmap.py` and `controller/validate.py` exit 0.
- Mutation checks (applied, observed failing, restored; tree clean afterwards): UTF-8 length replaced by UTF-16
  length -> 4 tests fail (`ComposerTextTest` oracle and lone-surrogate, both byte-limit tests in
  `ComposerStateHolderTest`); overlap resolution ordered by start offset instead of kind priority -> the two
  hashtag-inside-meshcore-link tests fail; send without the cooldown -> `send clears the field, sends the
  trimmed text once and re-enables after one second` fails.

## Source id coverage

121 owned ids in `docs/android/test-cases.json` (10 test files). Per-case class, method, disposition and JUnit
result are in [`source-cases.json`](source-cases.json).

| Source suite | Ids | Android test class |
| --- | --- | --- |
| `MentionUtilitiesTests` | 48 | `MentionUtilitiesTest` |
| `MentionInsertionTests` | 4 | `MentionUtilitiesTest` |
| `MessageTextTests` | 16 | `MessageTextTest` |
| `MessageTextContactShareTests` | 11 | `MessageTextTest` |
| `ChatCoordinateDetectorTests` | 9 | `MessageTextTest` |
| `MessageLinkTokenizerTests` | 14 | `MessageLinkTokenizerTest` |
| `MessageLinkAccessibilityTests` | 7 | `MessageLinkTokenizerTest` |
| `ChatShareMenuTests` | 6 | `ComposerShareTest` |
| `TiledViewInputBarChromeTests` | 4 | `ComposerUiSourceScanTest` (platform-adaptation scan) |
| `ChatInputBarThemedChromeTests` | 2 | `ComposerUiSourceScanTest` (platform-adaptation scan) |

| Status | Count | Notes |
| --- | --- | --- |
| Ported and passing | 115 | `source-behavior` |
| Platform-adaptation, passing | 6 | source scans of the Compose layer, not layout/rendering measurements (WP-308.md D-1) |
| Deferred | 0 | |
| Missing | 0 | |

Native (not source-bound) tests: 51 = `ComposerStateHolderTest` 17, `LinkLogicTest` 15, `EmojiPickerTest` 5,
`ComposerTextTest` 4, `ComposerUiSourceScanTest` 4, `ComposerShareTest` 2, `MessageLinkTokenizerTest` 2,
`MentionUtilitiesTest` 1, `MessageTextTest` 1.

## Oracle

`oracle/wp308_oracle.swift.txt` (swiftc 6.3.2) produced `oracle/wp308_oracle.output.txt`: `utf8.count`/`count`,
`trimmingCharacters(.whitespacesAndNewlines)`, `Character.isWhitespace`, `addingPercentEncoding` with
`urlPathAllowed` minus `/`, `removingPercentEncoding`, the coordinate regex, the hashtag regex, `NSDataDetector`
link matches, and the strippable-scalar properties. `oracle/wp308_oracle2.*` pins `Double("３７.５") == nil`.
The results are pinned in `ComposerTextTest`, `MessageTextTest`, `LinkLogicTest` and `MentionUtilitiesTest`.

## Not covered

Compose rendering, gestures, IME composition, hardware keyboard, TalkBack and insets are not exercised (no
compose-ui-test/Robolectric on the locked classpath and no device). No network preview fetch or image decode ran.
