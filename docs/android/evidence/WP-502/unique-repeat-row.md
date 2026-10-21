# Unique short-prefix repeat row: local missing-name assertion

WP-502, `cbattlegear/MeshCoreOne-Android`, issue135/existing DRAFT249.
Direct input `5cde794fbe26b0101ce7e2e0867ff06e75642d1e`;
main/base `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`;
frozen reference `db14559b39d32322b06477c6ae676112f583db50`.
Bounded WP-502-behavior/source-test-parity software witness, NOT acceptance.

## Complete three-original comparison

Read complete RepeatRowViewTests including createRepeater, RepeatRowView,
RepeatDetailsContent, RepeaterResolver/NeighborNameResolver and relevant
ContactDTO/RepeaterResolvable helpers. Read complete MessagePathModelsTest,
ActionTestFixtures, ReactionsAndStateTest and complete mounted reaction
tests/setup/fixture; trace actual native route/holder/resolver/private row.
RepeatRow test primary owner stays WP309; WP502 is its manifest test
cross-reference. No OriginalCase/accounting/primary-owner edit or new credit.

| Entire frozen original / parameters / predicates | Actual incumbent comparison |
| --- | --- |
| unique short prefix is exact: hash3F; Ridge key3F01+thirty00, advert10, outPathLength0, lastHeardnil, zero coordinates; discovered[]/locationnil; displayName Ridge AND matchKind exact | MessagePathModelsTest repeat-resolution method asserts ONLY EXACT for this branch. Native helper instead defaults advert0/outPathLength255/lastHeard0. It does NOT assert Ridge. ONE new owned witness restores original values and both predicates through the real loaded directory/row. |
| colliding short prefix is fallback: hash3F; Ridge3F01/Valley3F02 and same complete helper values; discovered[]/locationnil; matchKind fallback | Existing primary branch checks FALLBACK but supplies Coordinate(37,-122), not original nil, and the altered helper defaults. No exact-parameter equivalence or duplicate collision credit claimed. |
| unmatched hash is unresolved: hashAA; sole Ridge3F01, discovered[]/locationnil; matchKind unresolved | Existing primary branch explicitly checks UNRESOLVED with nil location and same altered helper defaults. No added definition or new credit. |

The existing state-holder details test uses AA/Ridge and checks list sizes,
not this exact original's 3F/Ridge name. Existing mounted reaction fixture
has heardRepeats0/empty canonical path and forbids contacts/discovery/repeat
reads, so its real route never reaches PathDetails/RepeatRow. There was no
other mounted actions/path test found by actual consumer/resolver searches.

Collision native RepeatRow currently has no fallback indicator while
PathHopList does show possible-match copy. That separate consumer boundary,
the nil-location collision parameter and the full original family remain
open; no synthetic indicator, product repair or blanket equivalence here.

## One actual consumer and original predicates

ONE new AndroidOnly definition:
MessagePathComposeTest#uniqueShortPrefixRetainsRidgeInTheRealRepeatRowAcrossRecomposition.

Real MessageActionsRoute -> lifecycle-collected MessageActionsStateHolder
-> real loadDetails -> test-only data-source contacts/discovered/repeats
-> actual immutable directory -> real PathDetails/private RepeatRow.
The repeat read is suspended at a deterministic CompletableDeferred:
assert actual loading/no repeats/no failure and no Ridge text BEFORE
completion; release ONLY the actual single original-hash repeat afterward.
No fake composable, cloned resolver, callback shortcut or expected-data
generation by candidate Kotlin. Source expected name/kind are literal
Ridge/EXACT from the frozen original.

All original contact/helper fields are retained; only source-random UUIDs
become fixed deterministic IDs. No nickname/location/recency substitution.
Rendering-only fixture: one incoming delivered direct-conversation message
with heardRepeats1 and canonical zero-hop empty path; one repeat at the
same message ID, pathNodes3F/pathLength1, SNR7.0/RSSI-80. Default canonical
arrival stays selected, so the observed `"3F  Ridge"` is the private
RepeatRow, NOT a selected PathHopList rendering of the same name.

The exact returned contact/repeat/list identities and hash3F are asserted.
Public PathNodeResolver.repeatResolution is asserted with those ACTUAL
loaded inputs, discovered[]/locationnil, yielding Ridge AND EXACT.
MatchKind is NOT exported by private RepeatRow; this is a real resolver
assertion plus a real rendered name, not an invented UI confidence value.

Actual row's full spoken content is
`Ridge. Excellent. SNR 7.0 dB. RSSI -80 dBm`.
The unchanged strict helper checks full literals `"3F  Ridge"` (nine UTF16),
`"1 Hop"`, `"Excellent"`, `"SNR 7.0 dB"` and `"RSSI -80 dBm"` in that SAME
row, literal2f/LTR, all UTF16/raw caret/vertical/native ink EXACT0/nonblank/
probe and owning-window/scroll viewport constraints. SNR/quality strings
are independently traced to native MessageSignalQuality/MessageRepeatDTO;
this is not new SNR-original credit. Valley/unknown text must remain absent.

Repeat after an actually observed route-tag recomposition: SAME holder
snapshot/contact/repeat/list, exactly one contacts/discovered/repeats read
with exact radio/message parameters, no extra read/action/mute/block/reaction.
Unexpected ports record and throw AssertionError, not swallowed success.
Scope is explicitly cancelled on host teardown; no runtime cancellation
verdict or service/process-lifetime proof is inferred from that cleanup.

Native SDK31 360x1400dp actual host/profile matches existing reaction
fixture dimensions, not an enlarged incumbent. Actual resources font2
before setContent, English/US/UTC/LTR, default LIGHT/no high contrast/
motion0; restore locale/timezone on teardown. No dialog/fake context/map/
network/physical device/TalkBack claim. Existing App source registration
includes the new owned test; no dependency/task/selector/build edit.

## Exact input blobs at direct parent5cde

| Input | Git blob |
| --- | --- |
| MC1Tests/Views/Chats/Components/RepeatRowViewTests.swift | 73fa35ddc1ab97c2be2baa2033061c77aa53119a |
| MC1/Views/Chats/Components/RepeatRowView.swift | 19ba4a34848899354bba75dcefbb5bd9f6eaf026 |
| MC1/Views/Chats/Components/RepeatDetailsContent.swift | cd59ef01a7991e404491d2b1cbf0aa960fed1242 |
| MC1/Utilities/RepeaterResolver.swift | 8c2f3217b69b745118174eef148c5e835e528431 |
| MC1Services/Sources/MC1Services/Models/Contact.swift | 7b6ecd2e224c1b1651edeba8a0019426881e7db4 |
| MC1Services/Sources/MC1Services/Protocols/RepeaterResolvable.swift | d0089ae5066ff62e3a7fd25d7188ec7d47607c0a |
| MessagePathModels.kt | a79d9c65a17306f84b4fbf3f7c3fa195d8f5787a |
| MessageActionsUi.kt | 675960ac2359c88b593fff47a92ec985d91d62dc |
| MessageActionsState.kt | 20a3b0d64dfef7e27791f306f266b4f78f3be810 |
| MessageActionModels.kt | ba137531d4019ea71092116e4eee62e76c1537d1 |
| MessagePathModelsTest.kt | a876e2d77db038d11b2f016703a81e0335fbbc5a |
| ActionTestFixtures.kt | 25e5e417af2fb70c3a3a8a801f571f317ddee253 |
| ReactionsAndStateTest.kt | fa514c52875299ccd05fd37abedbcb8eb73fa211 |
| ContactDTO.kt | 9d102668ec30d1f0a4075b9ea9dedcaeae3fe32b |
| MessageRepeatDTO.kt | 6b7b4fcb9ffb5bff1c50ecf0e063e3902a93c58c |
| MessageDTO.kt | 73e9e78d5c6ac11560a84521e49e07f509e54099 |
| android/core/l10n/src/main/res/values/l10n_strings.xml | 0a290c0f1a4a583abc2f52663215a4a9756f5e7b |
| ReactionDetailsComposeTest.kt | ff3add425a6026eaa48d92d08704be60fc3ccaca |
| SyncingPillComposeTest.kt | cb99ee15baee1ccb3dc3eed9cad06866144977f3 |
| RoomsComposeTest.kt | 47a6f01bc299345bc520e4642758c9d3c5174e3c |
| MountedTextAssertions.kt | 939c7260c3e0c4ff916292020a17116f5ef7efb7 |
| MountedNativePaintObservations.kt | a4b910c803998aec90c4ca1514bc31407ae77220 |
| MountedTextAssertionsTest.kt | d5320f536417c6c6cad1a6ee889b628aca472586 |
| CoverageMatrix.kt | b3c588efdcb6184a2974e8fd08928c33a4685818 |
| MeshCoreTheme.kt | 20568675e4222c6f3c40951d65559d0925e70306 |
| SharedFeedback.kt | 3031822c0059772e0065b9c10ae0ca6c495adc23 |
| android/app/build.gradle.kts | b2abaa5ff7814e64eb9af868b184b53706e969e3 |

Canonical owner `3bc8c1b8-20ec-4647-88ae-db3775268489`; trusted wp-owned/
modify evolution returned actual52 from51 for the new test, this receipt
and deviation. No release/recreate or native lease/lane operation.
Manifest raw582e/policy rawb77c unchanged; actual WP407 ancestor retained.
New199-line test blob `e432b556d132154c037c1fe4979d190fdfd46762`;
canonicalLF SHA256 a877ab5aa7079a0ade8783095d98497ba18623b10f84e65f24b1777340a2b044.

## Source-only checks and publication hold

`python .\tools\android-port\portmap.py`: PASS, 1739 actual nonempty mappings;
new owned AndroidOnly WP502 test maps exactly once.
`python .\tools\android-port\controller\validate.py`: PASS,
1866 pinned/1786 owned/80 excluded/64 WPs/178 dependency edges;
semantic manifest977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8.
All27 declared input blobs AND physical canonical bytes match parent5cde;
all6 frozen inputs match db145, all14 primary/production/build native
inputs match mainDBB. Source definitions/pins are NOT compile/test discovery.
ALL41Rooms/12controls/helper/paint/production/entire existing evidence and
5cde CustomError body/configuration are byte-identical. Remove ONLY the
new test/receipt and the new deviation section to recover the ENTIRE parent.
`git diff --cached --check`: PASS. Exact staged3-path whole-tree reversal:
PASS, remove only new2files/inserted21-line deviation section to recover
ALL5282 parent entries/modes; ALL5281 other incumbent entries identical.
Entire parent deviation SHA256:
eda9b9901eb5d7ebcfc93ba01b155844b8131904d175760967055480613391d0.
Parent tree61b5c272ba32c633d352a7ff8ba12ef46273bff7 is recovered exactly,
not just the selected bodies/imports/fixture or a partial reversal.
LOCAL/uncompiled/unrun. Public DRAFT249 stays exact5cde/source5604788278.
Parent own39657067704 has no independently reviewed terminal result in this
handoff; publication requires that outcome AND exact successor comparison.
No local Gradle/WSL/native/device execution, guest/resource/native-lease/
recovery/deployment/CI query/dispatch/rerun/new worker or verdict transfer.
Full-WP gates remain.
