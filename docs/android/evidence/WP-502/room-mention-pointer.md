# WP-502: real Room mention pointer-input path

**LOCAL / UNPUBLISHED / UNCOMPILED / UNRUN**, direct parent
`0978c3f6fc4b988e22b7ca4536641ec46bb0bdea`. Existing
[DRAFT249](https://github.com/cbattlegear/MeshCoreOne-Android/pull/249)
remains exactee7b, base/mainDBB. Frozen source
`db14559b39d32322b06477c6ae676112f583db50`, manifest977b/raw582e and
policye8/rawb77c remain unchanged; paused=false/dispatch=off/activation=false.
Canonical owner/attempt `3bc8c1b8-20ec-4647-88ae-db3775268489` evolves
49 -> returned50 through trusted `wp-owned`/`modify` for Rooms test,
WP-502 deviation and this receipt. No production or native-lane edit.

## One additional native input path, not another original

Reuses the COMPLETE frozen non-self zero-match parameter/setup:
rawNameCharlie/[Alice]/connectedMe -> empty/not-self picker, nameCharlie.
The existing custom-action method already covers that family; no new
OriginalCase annotation, primary accounting or ninth-original credit.
Read frozen eight cases/setup/evaluator/handler/sheet and actual
MessageBody/normalizer/URI/Room dispatcher/status-sheet/fixture consumers.

New definition:
`RoomsComposeTest#pointerOnRenderedCharlieLinkOpensTheProductionNotSavedSheetWithoutChangingTheRoom`.
Literal stored `@[Charlie]`, parent `Sender: @[Charlie]`, measured
`@Charlie` units[64,67,104,97,114,108,105,101] and actual Clickable
range0..8/tag `meshcoreone://mention/Charlie` remain source-faithful.
Original ContactDTO fields/defaults are explicit; deterministic UUID(0,391)/
32x0x11 replaces random setup identity. Original360dp/200%/LTR/host stays.

Repo-wide Kotlin search found no existing performTouchInput or
performMouseInput usage. Pinned Compose public prior art matters:
performFirstLinkClick invokes semantic OnClick, so it is deliberately NOT
used. getFirstLinkBounds observes actual text/link layout; performTouchInput
injects down/move/up as Android MotionEvents through owning View dispatch.

Require one actual measured line and text layout.size EXACTLY equal to the
mounted text node. Obtain the exact observed link's public bounds in that
node's coordinates; require finite/nonempty/full allocation containment.
Its center is the derived pointer, not a guessed offset or caret origin.
Require that point inside the measured range's selection-path bounds and
getOffsetForPosition inside the original link. Independently require EXACTLY
ONE actual clickable child under that text; verify text/link child full
viewport/window containment and the root-space pointer inside its actual
bounds. Assert text position equals visible origin before using injection's
local-to-root contract. No coordinate clamp/epsilon/sort/model choice,
synthetic click target or changed production semantics. Selection-path
bounds are hit geometry, NOT glyph ink or a substitute for strict ink proof.

Only `renderedText.performTouchInput { click(point) }` activates the mention.
Production combinedClickable -> LinkAnnotation listener -> MessageBody
onLink -> real Room dispatcher/evaluator -> actual MentionPickerSheet
must show the independently pinned Charlie title/not-saved/subtitle/Done.
No link/custom-action callback, evaluator or sheet is invoked/constructed
directly. Require no self/matching/Alice row or accessible action, original
directory list/DTO identity and exactly one eager read of each provider.
Real App navigation state must remain exactly its original Room state.

Strict fulltext/everyUTF16/raw caret/exact vertical/line/style/viewport/
inset/native-ink outsideEXACT0/nonblank/maxlines/probe-edge guards remain
unconditional for the actual body and sheet. Send/Done retain48dp checks.
Recomposition cannot resolve again, real pointer input dismisses via Done,
Room DTO/row flags/draft/load effects remain, no post/join/retry/errors/
external routing/navigation, and subscriber1->0/cancelled event immutability
are required. No physical device, pointer-hardware, TalkBack or whole-shell
acceptance is inferred from this unexecuted software definition.

## Source contracts and preservation

ALL34 baseline +25 frozen/native mention pins and five additional inputs in
the existing software-verification/bidi/whitespace receipts remain unchanged.
Pinned production MessageBody175192 and Room rendererf7b3d26 are the real
consumers, not added test-local lookalikes. Only the already-admitted Room
renderer differs from main; this successor changes no production bytes.

Public Maven source jars1.10.6 read IN MEMORY from
`https://dl.google.com/dl/android/maven2/androidx/compose/`:
foundation/foundation-android and ui/ui-test-android,
each `/1.10.6/<artifact>-1.10.6-sources.jar`. No install/runtime action.

| Source entry | SHA256 | Exact relevant contract |
| --- | --- | --- |
| commonMain/androidx/compose/foundation/text/BasicText.kt | `92df0f194316e605dca53913ee2fd05d274441648550715ac966893513e0895c` | 649-708: actual styled text, link children and native layout callback. |
| commonMain/androidx/compose/foundation/text/TextLinkScope.kt | `e42991b88f1004796244f718f1f89201383f1295d5f04caa541b808a66d94b7e` | 109-181/215-236/285-298: measured link child/path, combinedClickable, actual listener. |
| commonMain/androidx/compose/foundation/text/modifiers/TextAnnotatedStringNode.kt | `aec6edcdefd3f2506140c2b5e6b08f54d27047b60f8ff0305b272f93e268f944` | 344-370: annotated semantics retains native layout/paragraph; no guessed paint origin. |
| commonMain/androidx/compose/ui/test/BoundsAssertions.kt | `0d81ec7709b01685ebb0fcfb1064399009678184343b79bb208fe1ce2029243f` | 184-247: getFirstLinkBounds from actual matching annotated layout, text-node coordinates. |
| commonMain/androidx/compose/ui/test/Actions.kt | `fa0ff1cefd5f0ba6d1f312470c917c22d157ce10dca6455ce88fa9b15a60888b` | 398-410: pointer injection; 717-743: first-link helper invokes callback, not used. |
| commonMain/androidx/compose/ui/test/TouchInjectionScope.kt | `48b622860a4f8ff5321e575cead182aa2ea6ad14e40ac29d20bafc2d3ca11ca4` | 309-324/372-375: local positions; click enqueues down/move/up. |
| commonMain/androidx/compose/ui/test/MultiModalInjectionScope.kt | `6e2d8fd8d38b6811f31e1c95c62d0f0a1c24b0a21bf2c7cd7bb88280daaf0305` | 85-109: visible bounds origin transforms local pointer to root. |
| androidMain/androidx/compose/ui/test/AndroidInputDispatcher.android.kt | `eb70ce57ec56187157282aab291b36a24d83319c787aa3ea246ef1d1fe81e1fb` | 58-86/113-145/622-680: Android View touch dispatch and event cleanup. |

Remove ONLY the157-line new method/block to recover ENTIRE0978 Rooms
blob `5500d5e5b069db23e037c45c91f4e5e5a2f2bc75`, canonical-LF SHA256
`b0c129d6dfd1581a690f10429dcc78acc7a3d18c48bc8e8a25b5289d08450730`.
Candidate Rooms blob `47a6f01bc299345bc520e4642758c9d3c5174e3c`, SHA256
`7fb196c3404115fe4e738c784a70fc0224e148d6b3f6087a45029f297d60697f`.
All40 incumbent full bodies/imports/fixture/mount/frame remain exact;
ENTIREee7b observera4b910c8,12-controlsd5320f53, stricthelper939c and App
lockc4d5 remain. Stashc4d9d8f82142e9f8d8667984a8ad328eded707f1 retained,
including untracked original receipt. No existing receipt is rewritten.

## Source-only verification and stop

| Source-only command/check | Result |
| --- | --- |
| `python tools\android-port\portmap.py` | PASS,1737 nonempty mappings parsed; headers are not parity. |
| `python tools\android-port\controller\validate.py` | PASS,1866 pinned/1786 owned/80 excluded,64 WPs/178 edges, manifest977b. |
| `git diff --check` | PASS. |
| Entire157-line Rooms/deviation reversal; ee7b observer/12controls/strict helper/App lock preservation | PASS, all40 incumbent bodies/configuration unchanged;41 source definitions are NOT discovery. |
| ALL34 historical +25 mention +5 additional source bindings | PASS, actual frozen/main/parent/physical bytes matched. |
| ALL8 upstream entry hashes; exact three owned paths/modes; original stash | PASS, pinned source matched IN MEMORY; no existing mode change; stash intact. |

Coordinator supplied ee7b Linux119368390354 SUCCESS with complete mandatory
38/4/App853/Room2+20 positive module families/0FES, including39 Rooms and12
controls. That evidence remains exactee7b only; overall39642010467/SwiftData
are still pending per handoff. No CI result/log/bundle is copied or queried.
Neither parent0978 whitespace nor this pointer definition has execution
evidence. LOCAL/uncompiled/unrun; no publication before overall parent
outcome AND exact source review. No localGradle/WSL/native/device/readiness/
lease/recovery/dispatch/rerun/new worker/gate/settings action. Existing
product-support/native/protected/human/full-WP gates remain open.
