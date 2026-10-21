# Exact Custom Error status-pill parameter: local source witness

Repository `cbattlegear/MeshCoreOne-Android`, existing issue135/DRAFT249,
WP-502; direct input `e50a099d005f2469e2e9b2d3f3fd248aaa4c4c58`.
Frozen reference `db14559b39d32322b06477c6ae676112f583db50`;
main/base `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
Scope: WP-502-behavior/source-test-parity software assertions ONLY.

## Complete original comparison, not duplicate primary credit

Read all eight frozen tests, their complete state/view implementations,
the complete primary SyncingPillTest and SourceCaseProof wrapper, and the
relevant full mounted methods/setup. Original tests have no shared setup.
WP304 owns the view/test; WP303 owns StatusPillState. WP502 is a manifest
cross-reference of SyncingPillViewTests, not a new primary owner.

| Entire frozen case / predicates | Existing native assertion or precise gap |
| --- | --- |
| Connecting: localized text, sync symbol, !failure, primary text | SyncingPillTest.connecting asserts all four through resource/meaning/color-role adapters, plus primary icon role. No new definition. |
| Syncing: localized text, sync symbol, !failure, primary text | syncing asserts all four through the same adapters. No new definition. |
| Ready: localized text, ready symbol, !failure, primary text | ready asserts all four and green icon role. No new definition. |
| Disconnected: localized text, warning symbol, !failure, orange text | disconnected asserts all four and orange icon role; existing native-warning-foreground-and-readable-surface adaptation. No new definition. |
| Disconnected handler: false before manually invoking stored closure, true afterward | retainsTap asserts both and nonnull callback. SharedUiComposeTest.overlayShowsTheIncomingStateOnTheFirstVisibleFrameAndRemovesHiddenActions additionally clicks the real disconnected pill and checks one callback/hidden removal. No new definition. |
| Failed `"Sync Failed"`: original literal, failure symbol, isFailure, red text | failed asserts all four and red icon role; existing material-error-container-with-readable-foreground adaptation. No new definition. |
| Failed `"Custom Error"`: exact literal AND isFailure | preservesFailure asserts ONLY the literal. The type getter implies failure, but that is not a second assertion in this original parameter. Existing mounted colors use `"Fixture failure"`, not `"Custom Error"`. ONE owned mounted witness supplies the exact pair and observes the real failure consumer. |
| Hidden: empty text, absent symbol, !failure, primary text | hidden asserts all four; null is the native absent-symbol representation. No new definition. |

Symbols are MeshSymbol/SF-meaning adaptations, not extracted SF artwork.
SharedSurfaceColors fits source red/orange seeds to the actual Material
surface. SharedColorComposeTest's complete
sectionErrorsAndActualPillTextClearThePaintedSurfaceOnEveryEffectiveScheme
method checks five nonhidden states across its effective scheme/contrast
loops, including actual emitted text/icon roles and surface contrast.
Those source assertions are NOT a claim that this successor executed them.
No aggregate eight-case pass/equivalence approval is manufactured here.

## One real production consumer

New AndroidOnly SyncingPillComposeTest has ONE definition:
customErrorRemainsVerbatimInTheProductionFailureBranchAcrossRecomposition.
It passes actual StatusPillState.Failed(UiText.Verbatim("Custom Error")) to
the actual public SyncingPillView. No cloned evaluator/renderer, custom
failure UI, fake service, callbacks invoked by the test or production edit.

Both original predicates are explicit. The mounted result additionally
requires the SAME original message object, all twelve UTF16 units/full
literal at literal2f/LTR, actual Bold TextLayout, actual theme errorContainer
on the real pill, actual emitted foreground equal to observed TextLayout
color, matching emitted failure icon color, exact real stateDescription,
48dp allocation floor, and NO clickable node/invoked disconnected callback.
Expected errorContainer comes from the actual enclosing MaterialTheme,
not the pill's exported actual color. Original red is the incumbent native
readable-foreground adaptation, not a claim of byte-identical Swift color.

The existing strict helper performs everyUTF16/raw caret/vertical/native
ink EXACT0/nonblank/probe and full text/owning-window viewport checks.
It is called before AND after a tag-observed recomposition of the SAME
failure/message. There is no false disconnected retry, state replacement,
overlay animation, service connection, real radio or TalkBack claim.

The real native SDK31 host uses 360x800dp English resources, actual
resources fontScale2 before setContent (existing Rooms host mechanism),
explicit LTR and default LIGHT/highContrast=false/motionScale0 theme.
The real pill sits in a 360x300dp allocation; no altered incumbent window,
font, fixture or control. This is not API37/native-device coverage.
Existing App external source registration admits this test without a
build/dependency/task/selector/primary-accounting modification.

## Exact source bindings at input e50

Git identifiers below are immutable source inputs, not generated outputs.

| Input | Git blob |
| --- | --- |
| MC1Tests/Views/SyncingPillViewTests.swift | 15a45113ef453fba1131c6f388ec5361d744c4cc |
| MC1/State/StatusPillState.swift | d50864899f5331c63323fcdd5187616afdec77b8 |
| MC1/Views/Components/SyncingPillView.swift | c10729eef824706adc0ed3dbb508b209ab55080a |
| ConnectionStatusContent.kt | 3ac3017c554543632f454135448f76131513532c |
| SharedSurfaceColors.kt | d3345cfa1cc7ddab52d3729649072da3023de1aa |
| UiText.kt | ee3d0bd3dc2c81755776e543cf8526e57817f4ac |
| SharedFeedback.kt | 3031822c0059772e0065b9c10ae0ca6c495adc23 |
| SyncingPillTest.kt | 70830c8b43aec4b38f74552715afed1e06480f87 |
| SourceCaseProof.kt | 6008fb7b8590d2e8041e42d2fd9fc24778c4aaaf |
| SharedUiComposeTest.kt | 03bdd859622e622a430d9b92a2b765c65368e63a |
| SharedColorComposeTest.kt | 9fddec80c40eff67a16751da3cb79f6e7f470614 |
| MeshCoreTheme.kt | 20568675e4222c6f3c40951d65559d0925e70306 |
| android/app/build.gradle.kts | b2abaa5ff7814e64eb9af868b184b53706e969e3 |
| RoomsComposeTest.kt | 47a6f01bc299345bc520e4642758c9d3c5174e3c |
| MountedTextAssertions.kt | 939c7260c3e0c4ff916292020a17116f5ef7efb7 |
| MountedNativePaintObservations.kt | a4b910c803998aec90c4ca1514bc31407ae77220 |
| MountedTextAssertionsTest.kt | d5320f536417c6c6cad1a6ee889b628aca472586 |

Canonical owner/attempt `3bc8c1b8-20ec-4647-88ae-db3775268489`.
Trusted wp-owned/modify evolution returned actual revision51 from50 for
the new test, this receipt and WP502 deviation. No release/recreate or
native/device lease/lane change. Manifest raw582e/policy rawb77c unchanged.
New test blob `cb99ee15baee1ccb3dc3eed9cad06866144977f3`, 117 lines.

## Source-only outcome and hold

`python .\tools\android-port\portmap.py`: PASS, 1738 actual nonempty mapping
records, new test present exactly once as AndroidOnly WP502.
`python .\tools\android-port\controller\validate.py`: PASS,
1866 pinned/1786 owned/80 excluded/64 WPs/178 dependency edges;
semantic manifest977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8.
All17 declared blobs independently match input e50 AND physical canonical
bytes; all3 frozen inputs match db145, all10 primary/production/build
native inputs match mainDBB. Entire41-body Rooms SHA256 remains
7fb196c3404115fe4e738c784a70fc0224e148d6b3f6087a45029f297d60697f;
entire12control/oracle/paint inputs are byte-identical.

`git diff --cached --check`: PASS. Staged whole-parent/tree reversal: PASS.
Delete only the new test/receipt and remove ONLY the inserted21-line
deviation section: ENTIRE5280-entry e50 tree/modes are recovered; ALL5279
other incumbent entries are identical. Entire e50 deviation SHA256:
7a42f9ff1c1c1fa2f7de0dc23bc1e3360dd72b401d5ef91058dd69d467d837f5.
CanonicalLF new-test SHA256:
f77c4727ed8c564d5ccad3123cb7f0b664d4ac2ee3d17b423fa90d289a0da48e;
physicalCRLF SHA256:
d9a719a9088b2ae5e4788aac15e978b8d891ed980ac92e5b8e053dfb348920c8.
Initial source-probe assumptions of a file-start section and identical
LF/CRLF hashes were corrected against actual insertion/normalization;
no implementation or incumbent bytes changed to satisfy those probes.
Expected new discovery identity (NOT executed):
com.meshcoreone.android.uitests.SyncingPillComposeTest#customErrorRemainsVerbatimInTheProductionFailureBranchAcrossRecomposition.
No compilation/discovery/new-definition/text/ink/render execution occurred.
Coordinator subsequently supplied e50 own39649728376/attempt1 ALL SIX
SUCCESS and guarded/read-back execution review5604587276:
https://github.com/cbattlegear/MeshCoreOne-Android/pull/249#pullrequestreview-5604587276
That outcome stays exacte50. No query/replay/result-bundle/verdict transfer
to this unrun successor. Parent-outcome prerequisite is discharged;
publication remains held for exact successor source comparison.
Existing DRAFT249 remains public e50; full-WP/native/protected gates remain.
Fresh live readback confirmed actor cbattlegear and OPEN/DRAFT249/e50/baseDBB,
current mainDBB, without querying any CI job or changing PR/body/settings.
