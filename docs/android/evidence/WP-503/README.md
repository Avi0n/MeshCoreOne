# WP-503 attempt evidence

**Native Map List224 and LoS endpoint225 block acceptance; PR205/WP503 remain incomplete.**
Native suites execute through the existing App unit and AndroidJUnitRunner tasks.
The baseline ran 24, passed 6, failed 18; later focused cohorts ran 26 (18 passed/8 failed)
and 10 (8 passed/2 failed), with zero errors/skips, at their distinct candidates below.
No screenshot golden comparison, physical TalkBack observation or independent review of this
WP occurred. Bounded COMMENTED reviews of genuine producer repairs are recorded below.

## Native populated telemetry and neutral RTL calibration

Exact `5e5b23eb92a04b048cfbd650c1a9c2a7bdee8d8f` passes declared strict
`:app:assembleDebug :app:assembleDebugAndroidTest` through the installed serialized
snapshot/preflight adapter. [APK/source binding](device-populated-telemetry-inputs.json)
records the unchanged main d818 production APK and exact test APK.

```text
adb -s SERIAL shell am instrument -w -r -e class com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest -e expectedApi API com.meshcoreone.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

**API31:13 discovered/13 passed,0 failures/errors/skips**
([actual transcript/metrics](device-populated-telemetry-api31.txt)).
**API37:13 discovered/13 passed,0 failures/errors/skips**,28.138s
([actual transcript/metrics](device-populated-telemetry-api37.txt)).
These26 executions re-run the whole remote class, not painted760 or the global Map List.
The earlier scoped calibration/populated/pending selection also passes3/3 per API;
it overlaps this class and is not six additional unique cases.

New literal LPP `016700ff016700e101740181018805c395ed51fe00106802686f` independently
encodes channel1 temperatures25.5/22.5C, voltage3.85V, GPS37.7749/-122.4194/42m and
channel2 humidity55.5%. Actual US-locale output77.9F/72.5F/3.850V/55.5% and
`37.77490, -122.41940 @ 42.0m` is verified with localized labels, channel heading
order and native scroll. GPS304x75px occupies two complete reachable lines. Native
satellite-unavailable text304x126px occupies three; real Standard recovery recreates
MapLibre, Labels/Clusters toggle, and returning preserves GPS data. Pending service-port
telemetry disables real Reload, exposes progress and actual Done cancels its coroutine
and clears handlers once. No fake successful auth/radio/terrain or replacement map.

Prior exact14b scoped populated/pending pair discovers2 per API:1PASS/1FAIL at
neutral-RTL humidity metadata ([API31](device-populated-telemetry-before-oracle-api31.txt),
[API37](device-populated-telemetry-before-oracle-api37.txt),
[input binding](device-populated-telemetry-before-oracle-inputs.json)).
Humidity Text76x33px retains304px paragraph allocation with line228..304. Existing
complete-native-ink equality proves all22 raster rows present; real50px ancestor
clipping loses ink and is rejected. Source5e reuses that stronger zero-tolerance
helper without changing shared assertions/floors or filing a humidity product defect.

Actual profiles remain fresh/read-only AOSP31/4KiB/no GMS and Play Store37.2/16KiB/GMS,
font2/RTL/360x900/mdpi/animation0. No screenshot golden, physical TalkBack, real
persistence/radio or default-motion/full remaining-matrix acceptance is inferred.

## Native LoS endpoint platform actions

Exact `adb17b747e6e985c73854822bde3abff1ff5f6ac` passes the same declared strict App/
debug-test assembly. [Input binding](device-los-actions-inputs.json) records unchanged
main d818 production and exact test APK. This candidate executes:

```text
adb -s SERIAL shell am instrument -w -r -e class com.meshcoreone.android.accessibility.NativeLineOfSightAccessibilityTest -e expectedApi API com.meshcoreone.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

**API31:2 discovered/1 passed/1 failed,0 errors/skips**,3.840s
([actual transcript/platform tree](device-los-actions-api31.txt)).
**API37:2 discovered/1 passed/1 failed,0 errors/skips**,4.960s
([actual transcript/platform tree](device-los-actions-api37.txt)).
The independent Material control passes: actual native child Text and clickable parent,
platform ACTION_CLICK and exactly one callback; measured360x92dp target. By-text lookup
returns0 even for that genuine Compose button; native child traversal finds it.

Actual production LoS fails the unchanged named non-gesture endpoint requirement:
East matches0/clickable0/catalogReads1/elevationReads0 on both APIs. The real refreshed
feature tree contains generic map/heading, A/B Not selected and long-press instructions,
not named saved endpoints. WP315/tools-ui-engineer owns actual
[#225](https://github.com/cbattlegear/MeshCoreOne-Android/issues/225). Its consumer's
rendered-marker pointer callback is not a named platform accessibility action.
West/callback/elevation/selected-summary assertions after East fail remain unexecuted.

Early exacte4 source had API31 name absence and API37 immediate null-window error
([API31](device-los-before-calibration-api31.txt),
[API37](device-los-before-calibration-api37.txt),
[inputs](device-los-before-calibration-inputs.json)); that is not both-API product proof.
Later bare/by-text controls also failed. Actual Compose frame/native-event readiness
and refreshed native child traversal now pass calibration, with no increased runner
timeout, fake hierarchy/analysis or relaxed named-action requirement. Platform
semantics/actions are not physical TalkBack speech/traversal.

## Exact hosted status

Published `0e91535760ab1bda242269761662d5a198adfa0a`
[run38314435872](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38314435872)
independently passed all six jobs, including SwiftData restore/export and final guard.
It is not credited to later5e/adb test commits. Predecessor3bf38312307308 is CANCELLED;
38312680522/38313350981 are Required checklist-only jobs, not full candidate CI.
Historical23438303960053 remains FAILED/App812/8. Hosted logs remain authoritative;
no second result bundle is committed, hashed or revalidated.

## Current native guest/status execution

At exact code/test commit `39d87b60af67486ad2ce824110d841e229617114`, declared strict
`:app:assembleDebug :app:assembleDebugAndroidTest` **passes** through the existing
serialized snapshot/preflight adapter. The installed production APK is unchanged main d818,
SHA256 `6a18619e0eacb9522c28348e605218d4d5bff00e79f72c3d6fdea3e93a95f92f`.
[Native input binding](device-remote-status-inputs.json) records the exact test APK too.

The new public saved-guest flow runs the actual management/status holders with explicit
deterministic service ports. It verifies textual guest permission/no admin tabs; actual
voltage, RSSI, SNR and noise units; empty sensor/location explanations without an invented
chart or map; a real injected I/O failure with live-region text, reload and recovery;
48dp targets; and exactly one status read, three telemetry attempts, three handler
registrations and one status-only teardown. No password read or fabricated radio login.

```text
adb -s SERIAL shell am instrument -w -r -e class com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest -e expectedApi API com.meshcoreone.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

**API31:10 discovered/10 passed,0 failures/errors/skips**,15.968s
([actual runner/metrics](device-remote-status-api31.txt)).
**API37:10 discovered/10 passed,0 failures/errors/skips**,34.725s
([actual runner/metrics](device-remote-status-api37.txt)).
This class includes all nine existing auth/history/calibration cases and the new guest case.
A preceding API31 `-e class ...#guestStatusAndEmptyTelemetryExposeRealUnitsRecoveryAndHandlerTeardownAtFont200`
probe passed1/1 in5.687s; it overlaps the ten-case class, not another unique case.

Both fresh owned read-only emulator profiles verify actual font2/RTL/360x900/mdpi/
reduced motion: AOSP31 x86_64/4KiB/no GMS and Play Store37.2 x86_64/16KiB/GMS.
Guest Mode measures150x42px; voltage169x33px, RSSI105x33px, SNR79x33px and noise121x33px
on both APIs, with every actual line contained/reachable by native scroll. Visible
enabled guest controls measure at least54dp tall; heading targets are191x108dp and
191x66dp. Helpers are stopped; saved AVD data/snapshots are untouched.

These20 executions are a current remote-class regression, not a full runtime/JVM/theme
matrix or physical TalkBack/radio pass. Map defect224's distinct negative cases remain
normative. Earlier c4 painted760/92-case and abb hosted results are not relabeled as
39d executions. Hosted results remain authoritative at their own exact commits; no
second CI-result bundle is retained.

## Current populated-map product finding

At actual fixture `8eb2d610b7a954dedfefcba6a973c497c41ce8f1` and unchanged main d818
production, both installed emulators execute:

```text
adb -s SERIAL shell am instrument -w -r -e class com.meshcoreone.android.accessibility.NativeMapRuntimeAccessibilityTest#populatedNativeMapListSelectionKeepsCoordinatesAndFavoriteFilterReachableAtFont200 -e expectedApi API com.meshcoreone.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

**API31:1 discovered/0 passed/1 failed,0 errors/skips** ([actual transcript](device-map-list-api31.txt),3.575s).
**API37:1 discovered/0 passed/1 failed,0 errors/skips** ([actual transcript](device-map-list-api37.txt),10.176s).
Declared strict App/debug-test assembly passes at that fixture commit; no compilation or
zero-test pass substitutes for these failures. [APK input/source binding](device-map-list-inputs.json)
is retained only for these non-reproducible device executions.

The profile is the same verified actual OS font2/RTL/360x900/mdpi/reduced motion on fresh,
isolated, read-only AOSP31 x86_64/4KiB/no GMS and Play Store37.2 x86_64/16KiB/GMS.
Both List controls measure0x144px/empty visible bounds. API31 touch bounds(-8,75)-(40,219)
versus viewport(0,63)-(360,774); API37(-8,64)-(40,208) versus(0,52)-(360,876).
The target extends8px outside the viewport; actual `assertIsDisplayed` fails first.
Marker selection, coordinate callout and favorite filtering assertions after it are
unexecuted, not credited as passes.

The real first `MapControls` Row at `MapEntry.kt:290-304` exhausts horizontal allocation.
Production file blob907f3060dc3c533a84c6175ca97b4106119a2485 and App APK hash
6a18619e0eacb9522c28348e605218d4d5bff00e79f72c3d6fdea3e93a95f92f are unchanged.
Actual [defect224](https://github.com/cbattlegear/MeshCoreOne-Android/issues/224) is open,
WP312/nodes-map-ui-engineer product ownership. The existing registered producer and
coordinator confirm active repair on `wp312-map-list-font200`; exact published SHA and
unchanged native re-audit are awaited. No product edit, new worker,
lowered assertion or physical TalkBack claim. Emulator helpers are stopped.

The earlier c4 positive92 complementary cases below did not include this new case.
They are retained accurately, not relabeled as a complete current populated-map execution.

## Current runner repair and actual Android evidence

Authoritative published `234473213cc2555987d8a177cb4f6468749b35da`
[run38303960053](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38303960053)
is **FAILED**, not canceled: App812 discovered/8 failed, classifier/controller/Swift codec
passed, SwiftData skipped and final guard failed. No second CI-result bundle is retained.
Its local owned selection discovers92/passes84/fails8, zero errors/skips.

Further SDK31 secure-control selectors use the unchanged declared command
`:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest.frameworkSecure*`
with the common strict options below:

| Actual source | Discovered / result |
| --- | --- |
| 48e431e02 | 2,1 passed/1 SDK31 idling failure;227662 placements, one280x84 geometry. |
| 50a6637c4 | 2,1 passed/1 SDK31 idling failure after public global font/RTL configuration;185463 placements, same geometry. |
| 2fa5e4251 | 2,0 passed/2 failed: instrumentation-style looper requires activity creation on the main looper. |
| 36fcd5199 | 0 discovery, rejected; exact Gradle error: no tests found for the secure selector. Expression-bodied teardown violated JUnit void. |
| 374f3857d | 2,1 passed/1 SDK31 idling failure after void teardown repair/main-thread dispatch;230439 placements, one unchanged geometry. |

The queued-main-looper hint is not established as root cause. Unsuccessful configuration/
looper changes are removed. All nine remote cases and both Map/LoS cases now run through
the existing AndroidJUnitRunner on both actual APIs, with shared unchanged native oracles.
An independent actual Android31 secure control at6436ceee2 passes1/1. No ignored case,
removed SDK, inflated timeout/heap, expected-green failure or substituted production UI.

Declared assembly, through the same serialized snapshot/preflight/candidate environment:

```text
:app:assembleDebug :app:assembleDebugAndroidTest --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m
```

**Passed** at6436ceee2, d4ef01b49 and
`c4c5b94000077c1a3cbe5e7a60efbb89a2cc15ce`; assembly is not test discovery.
The last commit's APK inputs are retained with [source/hash binding](device-inputs.json).

Actual isolated emulator setup uses fresh owned `-data` images, `-read-only`,
`-no-snapshot-load -no-snapshot-save -no-window -no-audio -no-metrics`,
and the installed AVDs. Saved AVD app data/snapshots are untouched. Initial reuse of a
read-only saved-data image failed installation with signature mismatch; no uninstall or
saved-data wipe was performed. A fresh isolated image resolved that concrete installation
failure. Actual profiles: API31 AOSP/x86_64/4096-byte pages/no GMS; API37.2 Play Store/
x86_64/16384-byte pages/GMS. Both use actual OS font2, RTL,360x900/mdpi and animation0.

The following commands ran on each owned emulator (`API` is31 or37, `SERIAL` its endpoint):

```text
adb -s SERIAL shell wm size 360x900
adb -s SERIAL shell wm density 160
adb -s SERIAL shell settings put global debug.force_rtl 1
adb -s SERIAL shell setprop debug.force_rtl true
adb -s SERIAL shell settings put system font_scale 2.0
adb -s SERIAL shell settings put global animator_duration_scale 0
adb -s SERIAL shell settings put global transition_animation_scale 0
adb -s SERIAL shell settings put global window_animation_scale 0
adb -s SERIAL install -r -t <exact-app-apk>
adb -s SERIAL install -r -t <exact-test-apk>
adb -s SERIAL shell am instrument -w -r -e package com.meshcoreone.android.accessibility -e expectedApi API com.meshcoreone.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Executable: `C:\android-sdk\platform-tools\adb.exe`. Both installs/profile settings pass.
Tests assert the selected actual API, real system/activity font2 and animator0;
native TextLayoutResult additionally asserts actual font2/RTL.
ADB exit alone is not accepted: all eleven named cases must finish with status0,
`OK (11 tests)`, and no failed/error/skipped statuses.

| Source / actual runner | Discovered / result |
| --- | --- |
| d4ef01b49 / Android31 package selection | 11,9 passed/2 failed. LoS label incorrectly queried as Compose semantics; actual progress bar offscreen. Neither is a product defect. |
| c4c5b9400 / Android31 package selection | **11/11 passed**,0 failures/errors/skips; [actual transcript](device-api31.txt),13.618s. |
| c4c5b9400 / Android37 package selection | **11/11 passed**,0 failures/errors/skips; [actual transcript](device-api37.txt),47.276s. |
| c4c5b9400 / `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.*` plus common strict options | **70/70 passed**,0 failures/errors/skips. Exactly760 painted records/0 below unchanged floors/min4.517812074094317:1. |

These92 complementary source-bound cases are not one JUnit invocation or complete WP
acceptance. The migrated22 runtime cases are mandatory on both Android APIs, not absent
tests credited from the smaller JVM count. Espresso now queries the actual labelled
MapLibre AndroidView. A single measured12px native ScrollBy reaches progress's actual4px
visual bar with zero clipping: API31(24,605)-(296,609) in viewport(24,88)-(296,609);
API37(24,718)-(296,722) in viewport(24,88)-(296,722). Both require disabled duplicates,
actual30-second countdown and real cancellation. Cancel104x54dp is wholly contained.
Native secure controls idle after3/API31 and4/API37 placements, one geometry each.

These are actual automated emulator/UI/JNI executions, not physical TalkBack traversal,
radio delivery, populated terrain/map acceptance or default-motion verification.
Remaining matrix states remain unverified. Owned emulator helpers are stopped afterward.

## Latest bounded executable results

All commands below use the installed serialized Git-bundle/ext4 adapter, actual pinned
environment preflight and credential-stripped candidate environment. They invoke the existing
App task, not new acceptance tasks. Common exact options:
`--console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`.
Native graphics, SDK31/37; owned audits use real Resources font2/RTL. The producer-owned
keyboard cohort's different native window/default font is explicitly recorded below.
The following older JVM executions make no device/TalkBack claim.

| Actual committed inputs / command (plus common options) | Discovered / result |
| --- | --- |
| Product06fceec24f920e87a4a267a55499236ca3c25959 + audit39fcaf873b53616720545f36f4121bb1e9037442; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.rendered* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.direct* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.audit*` | Failed before native discovery. Localization converter74 cases/one error: trusted portmap rejects mixed AndroidOnly/PortedFrom dispositions on AppChatsFeature. Original producer corrected headers; no task bypass/zero-test credit. |
| Productd2b64b584892f0b63545f0e02c9c9e119b3d8b06 + same audit39fc; same contrast/direct/guard command | **8/8 passed**,0 failures/errors/skips; exactly760 painted records/0 below floors/min4.517812074094317. High-contrast identity38 records/0fail/min7.027937730337613. |
| Producte18ab1dac6186dfcdbef9debd0b2990e84797b4f + audit39fc/checked required-onLink patch; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.room* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.remember*` | 14 discovered,12 passed,2 failed,0 errors/skips. Author/status/link callback/Remember/no-sent-retry pass. Only cleared-child Retry displayed-text oracle fails; diagnosed against actual consumer and frozen custom-action/pointer-button contract, not called a product defect. |
| Same producte18 + audit80376e8f3e3d0adda276a78f791a9c2085b606dd; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.roomFailed*` | Test-only compile failure: missing installed `click` extension import.0 discovery rejected; corrected at01f430710. |
| Same producte18 + audit01f43071015d433ae2e54f66e3a63d7a922a0291; same roomFailed command | **2/2 passed**,0 failures/errors/skips. Exact named custom action and real pointer click independently invoke correct message ID. Effective pointer target86x54dp, bounds(16,196)-(102,250), fully within row(16,115)-(344,250). Body138x33px has nonempty realfont2/RTL lines/zero clipping. |
| Same producte18 + same audit01f430/checked required-onLink patch; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest` | **22/22 passed**,0 failures/errors/skips. All six frozen room distinctions/Remember/direct retry/live-region controls and all38 contrast frames. Exactly760 measured painted spans/0 below unchanged4.5/7 floors/min4.517812074094317, not a sum of earlier candidate results. |
| OWN83455603c14f95aa758e1a0bbea5c3dfc933a985; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest` | 6 discovered,2 passed,4 failed,0 errors/skips. Connecting text/disabled duplicate-connect/cancel passes. Same-copy Continue selector is ambiguous; direct initial-root focus assertion fails before editor flow. No product findings inferred. |
| OWN69819afacfbce7fee5abefd042287b43b54852b4; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest.denied* --tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest.wifiNative*` | 4 discovered/4 failed,0 errors/skips. Separate request/Continue callbacks and54dp targets pass; entire638px paragraph cannot fit in561px card viewport. Correct oracle must check actual scroll-reachable lines. Additional root focus notification does not resolve initial attachment timing. Neither is filed as a product defect. |
| OWNdea8d072ff7498e70450f47b21015910b2dd5dac; same denied/wifiNative selectors plus `--tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest.wifiInitial*` | **6/6 passed**,0 failures/errors/skips. Both APIs perform every one of40 permission lines via actual native ScrollBy; exact request/settings/Continue callbacks, native editor focus/Next/Done, actual coordinator failure/retry/cancel and controlled framework/WiFi autofocus pass. |
| OWN4d86cc00b6eaecdc7eb043d8b357557523a5121b after actual main663 integration; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest` | **8/8 passed**,0 failures/errors/skips. Full onboarding class, including actual pending-connect textual progress/disabled actions, in one actual adopted-main execution. |
| OWN33f9dea0d2a56c30e23505caa8b25e4e65fedc48; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest.disconnectedCli* --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest.disconnectedRx*` | 4 discovered/run,2 passed/2 failed,0 errors/skips. Actual Rx offline/recovery/filter selection/48dp/font checks pass on both APIs. Both CLI cases fail source description visibility: one enabled editable input, no localized disconnected explanation. Assigned defect220; unchanged normative regression retained. |
| Actual producer03753f129984598436b57218ee7f4b82ab99395e, its own existing test source/no owned fixture or App registration; `:app:testDebugUnitTest --tests com.meshcoreone.android.wp308diagnostic.HardwareReturnDiagnosticTest` | **8/8 passed**,0 failures/errors/skips. Actual1080x1000dp/default-font native window. Four complete methods cover five disabled-send gates, Enter/NumPadEnter, KeyDown-only valid send, connected/disconnected Shift newline and both Unicode selection directions/caret. Synthetic native keys/callbacks, not physical keyboard/IME/queue delivery or font200. |
| OWNd92e951b337b8998194177977233a09270cba091; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeBackupAccessibilityTest` | 8 discovered/4 passed/4 failed,0 errors/skips. Gating and typed parse recovery pass; centered paragraph line-box geometry fails. Actual native modal painting, independent centered framework control and clipping negative controls subsequently distinguish allocation/raster alignment from missing text; no product defect inferred. |
| OWNb7fd03199b46b597b90157067c3fa88452ddb695; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeBackupAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeFontAuditRegressionTest` | **16/16 passed**,0 failures/errors/skips. Backup8 and font controls8. Actual modal targets: Dismiss117x54dp, Import/Done328x54dp, Cancel90x92dp; complete native-window containment. The centered allocation counterexample preserves every raster row's full paragraph ink, with zero tolerance; verified100dp ancestor clipping and original32x4dp clipping are rejected. |
| Product9103863d004ecf9a035383e10281de01a012ad4d + auditb7fd/checked required-onLink patch; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest` | **22/22 passed**,0 failures/errors/skips. Independently executed current room product, all source distinctions and760 painted records/zero failing floors; bounded COMMENTED review5494555941. The earlier e18 pass is not transferred. |
| OWNa330f328a27a5063e8c9af9113f08751318dee51 after actual maincd436 and genuine room910 integration; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeBackupAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeFontAuditRegressionTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest` | **38/38 passed**,0 failures/errors/skips in one execution: backup8/font8/messaging22. Room callback/actual URL assertions now persist in owned source; snapshot patch removed. No producer-source substitutes or weakened thresholds. |
| OWNa48f6608877d771e6bba791612920b0e103c3bfa; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeNodesAccessibilityTest` | **8/8 passed**,0 failures/errors/skips. Public NodesEntry detail/list: long CJK heading, native nickname input/cancel, real destructive confirmation/cancel, typed unavailable-service recovery without false Back; existing offline cache/routing/Sync retained. Realfont2/RTL/native graphics SDK31/37,48dp controls. No persistence-write/device claim. |
| Product270c9ea423637753b4f8475a3d6d5c58d4861e69 + unchanged auditb7fd03199b46b597b90157067c3fa88452ddb695; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest.disconnectedCli* --tests com.meshcoreone.android.wp316diagnostic.DisconnectedCliTest` | **6/6 passed**,0 failures/errors/skips: owned2/producer4. Source unavailable description312x84px/two unclipped font2/RTL lines; editableInputs0 offline; reconnect focus/history retained across both generations and exactly one advert callback per submission. COMMENTED review5494686999, not protected approval. |
| OWN7890dda8e90bd30fa43ded65431df66d2c9d4f47; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.*` | **70/70 passed**,0 failures/errors/skips, in one integrated execution: diagnostics10/backup8/shell4/onboarding8/appearance2/font8/messaging22/Nodes8. Authentic room910/maincd436/CLI270 integrated. |
| OWN3fc596065ecaef0102cc89f36d5b2d06025e91be; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest` | 6 discovered/4 passed/2 failed,0 errors/skips. Public catalog recovery/empty history pass;44px Slider semantics rectangle incorrectly treated as the entire pointer hit region. No product bug inferred before native calibration. |
| OWNcffa18eab37ce37263abb9b11262081ee30fd5f8; same remote command | Failed test compilation before discovery: unavailable touch-scope `size` accessor. Zero discovery rejected, corrected to actual measured node dimensions. |
| OWN4252780a16457ec6a6a4553551b23302ca2871f7; same remote command | 8 discovered/6 passed/2 failed,0 errors/skips. Actual3.9V/3.7V SetProgress and fully contained42px numeric text already pass; independent framework Slider252x44px receives both native boundary clicks2px beyond the semantic box. Remaining two assertions still use the disproved44px proxy. |
| OWNd468c185db7766389c1582fb9db99b53c423e0a0; same remote command | **8/8 passed**,0 failures/errors/skips. Actual public catalog error/refresh, saved/empty history and native font2/RTL SDK31/37. Real history Slider292x44px receives all four native pointer corners of a48x48dp square within its scrolled viewport, each selects3.7V; non-gesture progress and zero-clipped actual numeric alternative retained. No floor changed/product bug fabricated. |
| Same OWNd468c185; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.*` | **78/78 passed**,0 failures/errors/skips, in a separate entire-cohort execution at this actual commit: messaging22/diagnostics10/backup8/onboarding8/Nodes8/remote8/font8/shell4/appearance2. Exactly760 painted records/0 below unchanged floors/min4.517812074094317. Not a sum of earlier runs or device certification. |
| OWN6bfa6b763e75a8b00ec3be57beeead14e9dd350f; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeOnboardingAccessibilityTest` | 22 discovered/16 passed/6 failed,0 errors/skips. Original remote8/onboarding8 pass; new auth6 fail counter synchronization. Shared line-by-line scroll oracle extraction preserves onboarding8. |
| OWN2cd55ba8d08d315595e64914eebb8626ef2b436b; same22 command | 16 passed/6 failed,0 errors/skips. SDK31 auth idling; SDK37 plaintext wait was incorrect for protected EditableText. Corrected to actual twelve masked bullets plus Password semantics, not a product defect. |
| OWN1be3e95adff05836f96bc08781270caceab35700; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest.authenticationPrefills* --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest.frameworkSecure*` | 4 discovered/2 passed(SDK37)/2 failed(SDK31),0 errors/skips. Minimal independent Material secure dialog reproduces the60-second idling failure. |
| OWN36b3c57ddd5ea0588028ca74d68a0a7ac7ae68e7; `JAVA_TOOL_OPTIONS=-Drobolectric.enabledSdks=37` and `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest.authentication* --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest.pendingAuthentication* --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest.frameworkSecure*` | Explicit SDK37-only diagnostic:4 discovered/3 passed/1 failed,0 errors/skips. Protected prefill/toggles/cancel, exact15-character warning/truncation/typed failure/editing recovery and framework control pass. Pending authentication fails `OutOfMemoryError: Java heap space` at unchanged512m. Not both-API acceptance. |
| OWNe2514783e2edc4b0ff6c25ea2a963242696f7e40; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest.pendingAuthentication* --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest.frameworkSecure*` | 4 discovered/1 passed/3 failed,0 errors/skips. Real Settings.Global animator scale0 asserted before activity creation. SDK31 framework/product each fail `AppNotIdleException` after60s (175541/192963 attempts); SDK37 pending fails512m OOM. Actual countdown312x76px/two lines is scroll-contained in(24,88)-(336,764) with zero clipping before failure. No success inferred from the earlier filtered invocation's missing output. |
| OWN82f2777937500c884803693a37544abb182c6128; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeMapRuntimeAccessibilityTest` | 4 discovered/0 passed/4 failed,0 errors/skips. Unsupported-runtime assumption disproved: SDK31/37 both actually report process64=true/product supported=true. No product defect inferred; oracle corrected to mount the real supported branch. |
| OWN8de58a8102913b1826fcc879c211cfe760779741; same map command | 4 discovered/0 passed/4 failed,0 errors/skips. Public MapEntry fails actual `TileServerOptions.mapLibreConfiguration()` JNI; public LineOfSightEntry fails actual `NativeConnectivityListener.initialize()` JNI on both APIs. No architecture/JNI guard bypass or copied renderer. Intended font/target/point assertions cannot execute after mount. |
| OWN9e3b8692a0fec85372412084254ccd487d0b16d2; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.*` | 90 discovered/81 passed/9 failed,0 errors/skips. Messaging22/diagnostics10/backup8/onboarding8/Nodes8/font8/shell4/appearance2 all pass; remote16 passes11/fails5 and Map4 fails4. Every original78 case passes in this single exact-candidate execution; failures are SDK31 auth/control4, SDK37 pending OOM1, Map JNI4. Not a later-fix result. |
| OWN ec0dd1146c8b6b75b51433a2d35e318afb460a98; `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest.pendingAuthentication*` | 2 discovered/0 passed/2 failed,0 errors/skips. SDK31 idling; SDK37 no longer OOM with bounded ScrollBy but an overstrong24px semantics containment assertion fails. Actual viewport bottom764 versus semantics y750/height24 suggests expansion, not a product defect before calibration. |
| OWN db8367163da2c044171ffafef194e4b07d3e6640; same pending selector plus `--tests com.meshcoreone.android.accessibility.NativeRemoteNodesAccessibilityTest.frameworkLinear*` | 4 discovered/3 passed/1 failed,0 errors/skips. Both API controls prove actual measured/public layout312x4px versus312x24px expanded semantics. Real SDK37 product visual bar(24,760)-(336,764) is wholly within actual viewport(24,88)-(336,764), zero clipping. Countdown312x76/two lines, disabled duplicates and actual cancellation pass; Cancel104x54dp fully inside360x866 dialog. Only SDK31 product idling remains. |

All later attempts above contain the genuinely adopted live main
`d8181418614a5acaa46f4acde54b741b492ac4b0` (merge95ad80c13).
The shared scroll helper preserves actual native line reachability and unchanged zero
clipping thresholds. Android animator scale0 is now an explicit reduced-motion profile;
it does not verify the default-motion profile or solve secure-dialog/progress idling.
SDK31 remains declared; neither it nor the failed cancellation assertions are silently removed.
The subsequent db836 control proves that the historical SDK37 OOM came from repeatedly
scrolling an expanded noninteractive semantic box into a viewport that already fully contains
its visual bar. The corrected oracle reads the real product's public layout coordinates,
not only the calibration control or a source height constant. Progress visibility/full
visual containment and actual cancellation remain asserted. No native clock/heap/timeout
change is used; SDK37 pending is now positive, SDK31 idling remains negative.

Authoritative published33 [run38294672952](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38294672952)
binds `33f355059ab9510fad736f386fc6299ca54777b9`, completed SUCCESS in all six jobs.
The completed Linux job114963383530 reports App798 and all20 module suites positive,
zero failures/errors/skips; SwiftData restore/export also succeeded. This historical
result is not current authentication/map evidence. No duplicate CI result bundle is retained.

The existing [issue218 handoff](https://github.com/cbattlegear/MeshCoreOne-Android/issues/218#issuecomment-6131126281)
records the inspected ownership gap: no verified active repair producer/PR, and old merged
PR161 closes121 rather than218. Missing runtime session tools prevent a broader activity
claim; no new worker is authorized or launched.

The room oracle now preserves **both** the source named accessibility action and the actual
native pointer button. Cleared child speech is not native visibility. It measures the
real button and injects a real pointer click rather than substituting a generic row action.
The six frozen distinctions and all thresholds remain; producer bodies are not edited.
The required new room callback was applied only to owned snapshot fixtures while
the earlier products above were unadopted. After genuine room910 adoption, that callback
and the actual URL assertion are persistent in owned source and the snapshot patch is removed.
The actual Room/App wiring comes from the producer lineage, not an audit substitute.

Backup mounts public BackupRestoreScreen and its real AppBackupStateHolder. Explicit ports
return typed UI inputs, not a fabricated compatible backup: contacts17/messages23/channels3/
discovered5 preview; inserted17/skipped23/dropped8 result; cancellation suspends the actual
import coroutine. Expected effects execute once only for changed results. Dialog assertions
use its own202px error window or900px full sheet; scroll reaches result counts/footer/Done.
Initial spatial-mask comparisons were too strong for native raster alignment. Complete
ink-per-row equality is used only for the independently demonstrated centered allocation
counterexample; ordinary nonempty/no-truncation/ellipsis/line extents remain zero-tolerance.
The100dp ancestor clipping control verifies its real width before requiring missing-ink
rejection; this is not a generic pixel-presence or relaxed-golden test.

Onboarding assertions mount real public content and the real WiFiConnectionCoordinator
with explicit pending/failure ports; no claimed successful transport or platform permission
dialog. Paragraph reachability checks all80 actual lines over both APIs with zero line
clipping, not simultaneous paragraph fit. Native-control and then WiFi subtree autofocus
both report focused=true; initial direct-root attachment timing remains a qualification,
not a product fix or a physical keyboard/IME observation.

Both bounded producer reviews were posted as **COMMENTED**, not APPROVED/protected gates:
[PR209 review5494169311](https://github.com/cbattlegear/MeshCoreOne-Android/pull/209#pullrequestreview-5494169311)
at exacte18 source/native22-case result, and
[PR213 review5494169481](https://github.com/cbattlegear/MeshCoreOne-Android/pull/213#pullrequestreview-5494169481)
at exactd2 painted8-case result.
They explicitly exclude business queue-event order, whole-WP and physical/device acceptance.

The current keyboard review at exact037 records only the independently executed native
eight-case result; the earlier d2 painted result is not transferred to037.
Assigned [issue220](https://github.com/cbattlegear/MeshCoreOne-Android/issues/220) contains
the real CLI head/consumer/source/measurements and exact failed command. Production stays
with the existing WP-316 coordinator; the regression is neither waived nor expected-passing.
It now passes unchanged against exact270 above. Actual PR223/#220 subsequently merged as
main726f00dd45bf5d1a55e29a3775ea474a2e5787bf, adopted at OWN67e3daf31d0717bbea6f764f7abd0a97404c97ad.
#220 is retired as active; no additional CLI re-audit/publication cycle is required.
The78-case execution already uses270 product and remains bound to d468, not relabeled
as a test of this later merge commit.

Remote-node fixtures provide explicit typed read-only catalog/history ports and stable
time. Public RemoteNodesEntry drives the real history holder, cached sample selection,
numeric native scroll and empty/error UI. They do not prove database persistence,
remote authentication, live radio status/telemetry or location/map behavior. The Slider
oracle is calibrated against a native Material control, then proves actual48dp pointer
acceptance through real product state changes; neither a source modifier nor a44px
semantic allocation substitutes for the measured pointer region.

Actual reviewed main663fa73fab99356f7097534288e820a126890195 is integrated at
OWN4d86cc00b6eaecdc7eb043d8b357557523a5121b. The single disjoint App build conflict preserves
both audit source-set registration and all main WP-504 release/R8/benchmark/profile settings;
relative to main the App diff is only the two-line audit registration.

Authoritative historical OWN0e44dcfc34961737d18fe80f10c131d71c1a973e hosted
[run38282515710](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38282515710)
**FAILED**:720 App tests discovered/12 failed against legacy room/contrast production.
The emitted outgoing source-delivered label and incoming-author assertions fail, not compile.
No suppressed-looper warning is assumed to be the cause. Those assertions pass unchanged
on the genuinely integrated producer e18 above. Codec oracle succeeded; restore skipped.
The exact hosted log is authoritative; no second CI-result bundle is retained.

Authoritative exact publishedf1cbbf4f7c046bb3515722c6f344148d60d6567e
[run38287295723](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38287295723)
also **FAILED**:732 App tests completed/16 failures. Its emitted room author/source-delivered
assertions still exercise legacy production; those assertions pass unchanged after genuine
room adoption above. Swift codec oracle succeeded and restore skipped. This is a historical
hosted result, not a result for the newly integrated candidate or a second result bundle.

## Identity and revisions

| Field | Actual value |
| --- | --- |
| Repository / issue | `cbattlegear/MeshCoreOne-Android` / [#136](https://github.com/cbattlegear/MeshCoreOne-Android/issues/136), open and assigned to `cbattlegear` |
| WP / profile | `WP-503` / `design-system-engineer` |
| Workspace creation handle / CLI session | `1666502a-0420-46ef-930f-e78651ba8b9f` / `d2980f28-620c-408b-9947-067e2da8eedf` |
| Managed branch | `cbattlegear-issue-136-wp-503-accessibility-audit-807238` |
| Inspected base/head before audit documentation | `d958471ab4835813881a5e7af36e8249a8b273a4` |
| Documentation candidate head | The Git commit containing this document; exact published head belongs in PR/issue handoff, not a self-referential file hash |
| Frozen source | `db14559b39d32322b06477c6ae676112f583db50` |
| Manifest SHA-256 | `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda` |
| Policy revision | `3fc0afa19ad31f0210a5d427f655ef90b50a8e64e818fe3420b188303b99fb20` |
| Typed reservation | Same canonical attempt, revision 3; `wp-owned` create/modify on the four WP-503 roots plus `dependency-resolution-metadata` / `update-directly-required-build-wiring` on `android/app/build.gradle.kts`; no release/recreate |

The advisory receipt correctly preserves canonical project handle
`1666502a-0420-46ef-930f-e78651ba8b9f` as `session_id`. The coordinator independently
resolved BOTH it and CLI/runtime alias `d2980f28-620c-408b-9947-067e2da8eedf` to this same
worker, branch/worktree, creation timestamp and PR205. The earlier hypothetical identity
mismatch is withdrawn; no guard requiring replacement was encountered. No second claim,
immutable identity replacement, release/recreate or manual ledger edit occurred.
Issue assignment and PR205 are independently observable GitHub facts, not invented ledger
bindings. No hosted publisher task or acceptance receipt is claimed.

WP-503 depends only on WP-407. GitHub reports PR164 merged on `2026-10-11T16:22:30Z`
as `250dd50ed259c5d9fe1006d4f45a637e4aea0aff`, and that commit is an ancestor of the
inspected head. The coordinator additionally identified WP-407's
`advisory-terminal-merged` receipt: session `524fad60`, PR164, head
`e9f0febc7e53b09adb00ac56928e7073366023f6`, merge as above. Lease/completion fields are
not that terminal receipt and are not a pre-edit blocker. No device/source acceptance is
inferred from it, and no ledger was manually repaired.
WP-501 is not a dependency and its existing repair/parity surfaces were not edited.

The only reference-tree differences reported by Git are the two test-only paths enumerated
in `reference-amendments.json` (correlation serializer ordering and advertisement waits).
The source pin is not advanced. Manifest validation passes against those explicit exceptions.

## Actual commands and outcomes

Commands ran from this worktree on Windows on 2026-10-12.

| Exact command | Result / discovery | Claim boundary |
| --- | --- | --- |
| `gh issue view 136 --repo cbattlegear/MeshCoreOne-Android --json number,title,body,comments,assignees,labels,state,url` | Passed; original issue had no assignees and no comments | Required assignment context, not verification |
| `gh issue edit 136 --repo cbattlegear/MeshCoreOne-Android --add-assignee cbattlegear` | Passed; subsequent view confirmed assignee | Account owns tracking; dedicated worker identified in comment |
| `gh pr view 164 --repo cbattlegear/MeshCoreOne-Android --json state,mergedAt,mergeCommit,headRefOid,url` | Passed; state MERGED, exact merge recorded above | Not a WP acceptance attestation |
| `git merge-base --is-ancestor 250dd50ed259c5d9fe1006d4f45a637e4aea0aff HEAD` | Exit 0 | Actual merge ancestry only |
| `python .\tools\android-port\controller\ci.py preflight` | Exit 2, BLOCKED: `Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness` | Initial invocation lacked explicit state; not proof of absent tools |
| `python .\tools\android-port\controller\ci.py run --stage prepare` | Exit 2, same explicit-state blocker | No strict dependency preparation occurred |
| `python .\tools\android-port\controller\validate.py` | Exit 0 | Manifest/source/ownership validation, not feature acceptance |
| `python .\tools\android-port\portmap.py` | Exit 0 | Traceability only |
| `python .\tools\android-port\theme_convert.py --check --self-test` | Exit 0; discovered/run/passed 22, failed/error/skipped 0; 10 themes, 44 color assets, 89 primary inputs, 23 generated outputs, 12 recovery locales | Python frozen converter/resource checks, **not Kotlin contrast or rendered feature tests** |

No test counts are inferred from filenames, source declaration counts or historical results.
Reproducible hosted checks remain authoritative at their exact candidate commits; no second
CI-result bundle is persisted here.

## Follow-up readiness and admission investigation

The initial missing-state errors above do not prove no toolchain is installed. A follow-up
WSL probe with `bash -lc` confirmed both the installed local adapter's declared
`$HOME/meshcoreone-work/toolchain/environment.json` and pinned Python executable exist.
An earlier quoted-variable probe was inconclusive and is not evidence of absent tools.

The documented `ci.py preflight --state <installed environment.json> --output <private
WP-503 directory> --local`, invoked through the installed Python in WSL, exits 2:
`Git command failed: rev-parse HEAD`. A direct `git rev-parse HEAD` in that WSL-mounted
worktree explains the failure: its Windows `.git` pointer is not a Linux Git path.
No `.git` pointer, shared checkout or global Git configuration was rewritten. The existing
Git-bundle/ext4 snapshot adapter is the appropriate execution route; its optional full
pre-push attempt previously remained queued on the shared Gradle lane and did not run.
This is not an absent-SDK claim or a native-test failure.

The trusted admission helper also confirms `android/app/build.gradle.kts` is admitted for
`update-directly-required-build-wiring` (`dependency-resolution-metadata`) and
`update-build-or-manifest` (`app-build-launcher`). A generic `modify` probe fails because
it is not the typed support operation. Therefore App runner support is **not** an unknown
path capability, and does not require new authorization prose. Same-attempt CAS evolution
revision 2 -> 3 admitted additive registration before the App edit.

The hosted result for initial documentation head
`ea4300e0e457dd9ad63767c244d908722f1e6984` is
[run38261041154](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38261041154):
scope/controller/android-ci succeeded; Linux native build and both Swift oracle jobs were
skipped for the classified documentation change. These are not native accessibility passes.
Only that hosted result/log is referenced; no CI bundle is retained.

## Acceptance and resumption gates

| Acceptance | Current evidence | Remaining requirement |
| --- | --- | --- |
| `WP-503-behavior` | Actual feature-consumer source review; four WP-310 findings; reusable tests identified | Native target/contrast/font/semantics measurements across actual flows and device focus/announcements |
| `WP-503-boundaries` | Textual alternatives and missing message semantics documented; no golden changes | Data-bound graph/status assertions, approved deterministic screen evidence and distinct device observations |
| `WP-503-source-test-parity` | All four WP-503 original test cross-references read, 23 source declarations inventoried | Executed acceptance, not a duplicate of WP-501's parity accounting |

## Actual native execution

The session-local targeted launcher uses the installed `local/run.sh` serialized
bundle/ext4 snapshot, pinned state and `candidate_environment`, then invokes existing
Gradle tasks. It is not a new canonical manifest runner. The installed adapter does not
reject the verified project-handle/CLI alias; no identity guard blocked execution.

| Exact task / candidate | Actual result |
| --- | --- |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.* --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `b42c9a41e8ccc62b3c82f255dca7dc7065a2c472` | Native preflight succeeded; compilation failed on test-only `CustomActions` key location; zero discovery explicitly rejected, not success. Fixed to `SemanticsActions.CustomActions`. |
| Same task/options, `86993ee65ca43fb5f7da26f537b3857e1cd4c1a9` | Compiled; discovered/run 24, passed 6, failed 18, errors/skips 0, SDK31/37. Passing families: disconnected diagnostics explanation, sent-room no retry, mixed CJK/Hebrew direct retry/font/targets. |
| Same task/options, `4f8b6a5d324d2d13dcbbce2502c200992c7e325a` | Discovered/run 24, passed 6, failed 18, errors/skips 0. Added paragraph/viewport diagnostics; partial grid-target artifact is not promoted to an intrinsic control defect. |
| `:core:designsystem:testDebugUnitTest :app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeAppearanceAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.direct* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.rendered* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.audit* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.roomFollowUp* --tests com.meshcoreone.android.core.designsystem.ThemeContrastTest --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `22309ff1eb9a1ccddecdd3375ec2de1aee77a812` | Discovered/run 26, passed 18, failed 8, errors/skips 0. Existing theme-role families 10/10 and actual-timeline guard controls 4/4 pass. Remaining failures: legacy room follow-up author, painted-span contrast, appearance allocation flags and populated diagnostics. |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeAppearanceAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeFontAuditRegressionTest --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `1fad28d89f750675285c354c28f91239bc04890e` | Discovered/run 10, passed 8, failed 2, errors/skips 0. Appearance 2/2, font regressions 4/4 and disconnected diagnostics 2/2 pass; populated diagnostics 0/2. |
| Same focused task/options, `abc86cb0dfd885d4dabd14a07f637ea031ad9b29` | Same 10 run/8 passed/2 failed, zero errors/skips. Rejects empty line measurements and records all three graph widgets' zero heights before failing. |
| Same focused task/options, `d825ea3d2ec82a270012890767097c77a3aa95c9` | Same 10 run/8 passed/2 failed, zero errors/skips. Compiles native scroll-reachability support; current non-scrolling product still has all three numeric widgets at zero height. No normative failure reclassified. |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeShellAccessibilityTest --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `8f2c73d0c25afa383af229809ecef9b3b18c7a12` | Discovered/run/passed 4, failures/errors/skips 0. Actual compact 360dp and expanded 1080dp native shell, Resources font 2.0/RTL, five localized Tab roles/selection/heading/unread, effective targets, tab-stack draft retention and real Activity Back. Content is an explicit shell-only editor focus probe, not feature-content acceptance or TalkBack traversal. Initial a1db797 test compile failed on missing assertion import/chained Unit; zero discovery was rejected and fixed before this run. |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeNodesAccessibilityTest` with the same strict options; `da40e8bed9951119ba70dbd686bdd57d411e5359` | Discovered/run/passed 4, failures/errors/skips 0, SDK31/37. Actual non-null-dependency `NodesEntry` loads a cached CJK node while disconnected, exposes selected Contacts/textual blocked status, measures real font 2.0/RTL/targets and invokes the correct contact route. Actual Sync action shows localized recovery dialog; scaled text/dismissal retains cached content. Read-only deterministic store; unexpected transports/mutations/codecs fail explicitly, not successful stand-ins. No physical announcement, all-name-length or full Nodes acceptance. |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest` with the same strict options; OWN merged-input candidate `1f341947246b0a67f7abd0185ca9cabf91a5afa4` | Discovered/run/passed 6, failures/errors/skips 0. Actual main `b621ed186b32f024dc4c7ddbdc60d18a449343f9` (PR216 merge) adopted through a normal Git merge; affected 600/900dp/disconnected native cases rerun on this branch, not merely producer snapshot proof. |

## Independent genuine-product candidate re-audits

The existing serialized managed snapshot mounts committed owned audit inputs against each
exact producer commit, importing both revisions. Only WP-owned audit sources and already
admitted additive App test registration are applied in that disposable snapshot. All producer
build additions are preserved; no product byte is edited, invented, reverted or merged here.
These are actual local native candidate executions, not borrowed producer results, hosted
acceptance, review approval or passes of this branch's unchanged baseline product.

All commands below use the same declared App task options shown above.

| Exact task selectors / product and audit inputs | Actual result |
| --- | --- |
| `--tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest`; product `29c9b530d9da9312dc1306c3d84c8d783cf4fc05`, owned audit `fff04369c106fcda15372297eec6f72c4739bfd4` | Discovered/run/passed 4, failures/errors/skips 0, SDK31/37. Minimum/Maximum/Average become 253x42/260x42/259x42 with contained nonempty lines; summary/count/current data and disconnected explanation pass at Resources font 2.0/RTL. At 900dp metrics fit; this first result alone does not prove offscreen scroll movement. |
| Same selector/product; owned audit `8f2c73d0c25afa383af229809ecef9b3b18c7a12` | Discovered/run/passed 6, failures/errors/skips 0. Adds a real 360x600dp case: all three widgets initially have zero visible bounds but actual height 42; native `performScrollTo` moves Minimum y611 -> 558, then Maximum/Average y600 -> 558. Each complete text box becomes visible with bottom 600, zero-tolerance line bounds, no truncation/ellipsis. Actual scroll movement is asserted, not simultaneous fit. |
| `--tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.rendered* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.direct* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.audit*`; product `149581edb8067126d2845f971c3854fe89cba2c1`, owned audit `39fcaf873b53616720545f36f4121bb1e9037442` | Discovered/run/passed 8, failures/errors/skips 0, SDK31/37. Unchanged painted-feature assertions produce exactly 760 records, **0 failing records**, minimum **4.517812074094317:1**, Marine/light/high-contrast/outgoing hashtag (4.5 floor). All high-contrast identity floors remain 7. Actual timeline guard controls and mixed-direction retry/font/targets also pass. |
| `--tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.room* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.remember*`; product `83e9579176b1930c3e682d6f080e8598a3a1b608`, owned audit `39fcaf873b53616720545f36f4121bb1e9037442` plus its checked `fixtures/room-link-api.patch` | **FAILED before discovery**, not zero-test success: production `RoomConversationScreen.kt:272:21`, `No parameter with name 'backgroundColor' found`. Actual contextual room caller depends on WP-308's genuine MessageBody API absent from this standalone product commit. Serialized producer/coordinator integration is required; no room acceptance claimed. |

The committed room fixture patch changes only owned audit calls to the genuine required
`onLink` API using named arguments and verifies a native custom action invokes the exact
URL callback. It preserves all six room distinctions and the associated switch test. It is
snapshot-only until actual compatible product bytes are adopted on this branch, then should
be applied to the owned fixture and removed. No compatibility overload, fake production
wiring or old compilation against new APIs is asserted.

The room executable build overlap is handed off in
[comment6128524649](https://github.com/cbattlegear/MeshCoreOne-Android/issues/207#issuecomment-6128524649).
Actual graph movement is handed off in
[comment6128449712](https://github.com/cbattlegear/MeshCoreOne-Android/issues/214#issuecomment-6128449712).

PR216/#214 is now genuinely merged as main `b621ed186b32f024dc4c7ddbdc60d18a449343f9`
(parents d958/29c9), not inferred from closure. Bounded independent source/native review
`5493735210` is **COMMENTED**, not a fabricated protected approval. Actual two-file diff
and frozen NoiseFloorView were read; no substantive remaining defect in this bounded
numeric-reachability repair was found. Authoritative hosted
[run38277362005](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38277362005)
is successful at exact29c9. Historical negative rows remain; #214 is retired as an active
defect. Its device qualification is not relabeled as device/full-WP evidence.

New genuine published room `9892e7c7f0d6a0466dec2abf84e87a34adef57ea` was independently
retried with the checked owned required-onLink fixture. It **still failed** production
compilation on the missing backgroundColor parameter, with zero discovery rejected.
This is actual new-head execution, not transfer of the earlier83 failure.

New genuine composer `948951f27a95149136c221e0a94ac6a698219683` was independently retried
with the same unchanged contrast/direct/guard selectors. It **failed before discovery**:
`AppChatsFeature.kt:232` unresolved `resendChannel`; line341 anonymous adapter lacks required
abstract members; lines347/349/351/358/359/361 old pending/send/resend direct/channel
overrides no longer match the queue-acceptance API. Real producer/App integration is
required and reported in #208. The earlier149 8/8/760 pass does not apply to948.
No product substitutes or duplicate edits were made.

Hosted [run38276221835](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38276221835)
at exact published `fff04369c106fcda15372297eec6f72c4739bfd4` **FAILED**:
App reported 710 tests completed, 14 failed, including normative legacy room/contrast
assertions. Swift codec oracle succeeded; restore oracle was skipped. This is the hosted
result, not an iOS/device/whole-WP pass, and is not transferred to later audit/product inputs.
No duplicate CI-result bundle is retained.

Both executing candidates measured **38 effective frames x 2 directions x 5 emitted ranges
x 2 SDKs = 760** actual foreground/background records. **162 fail** unchanged thresholds,
covering **81 distinct parameter segments**, including **22** high-contrast identity
records below 7:1. Lowest ratio: **1.0435430264922168:1**, Fern/dark/standard/outgoing
hashtag. Default/light/standard/outgoing hashtag: **1.285454842066801:1**. Each background
comes from native window pixels and is verified against the actual emitted bubble role;
effective overlapping base/link/span colors and mention fills come from `TextLayoutResult`.
No source-palette proxy or sampled text foreground is substituted.

Enabled targets on passing flows measured at least 48dp; observed examples include
70x48, 114x48, 165x48, 86x54 and 110x54dp. This is not all-feature target acceptance.
Real Resources font scale 2.0 was asserted; mixed-direction direct text was unclipped.
Short-label width-overflow flags were disproved as clipping: e.g. Default's native widget is
84x30 but paragraph allocation 312x30; actual line left/right 0..84, top/bottom 0..30 and
896 non-background pixels. All 20 theme/API labels have visible ink and contained lines.
The oracle now requires nonempty lines, no truncation/ellipsis and zero-tolerance actual
line bounds. SDK31/37 regression controls demonstrate valid shrink-wrap and reject genuine
32x4dp clipping; no threshold/golden change or font product finding.

Populated noise-floor Minimum/Maximum/Average widgets are 253x0/260x0/259x0, with zero
visible bounds on both SDKs inside a real 360x900 viewport. Count and all three summary
values/current reading are data-bound and pass. Unlike the font artifact, the actual
widgets have height zero. Source's unweighted/non-scrolling Column and fillMaxSize chart
consume the available height before statistics. Assigned
[#214](https://github.com/cbattlegear/MeshCoreOne-Android/issues/214) is claimed in the existing
coordinator's WP-316 repair scope; no production edits or device observation here. A legitimate native scroll ancestor
may reveal metrics for measurement; all metrics need not fit simultaneously.

The confirmed contrast finding was filed/assigned as
[#208](https://github.com/cbattlegear/MeshCoreOne-Android/issues/208) at coordinator direction.
Four room findings remain assigned to #207. No product or WP-501 binding edit occurred.

The coordinator identified a test assertion weakness: checking only the artificial
`audit-root` does not prove actual timelines lack a broad live region. It is corrected in
owned tests to inspect actual ancestors/subtrees of two real rows. A negative control puts
a live region on the real `ChatTimeline`; a positive control permits a small failure-only
region. These are harness regressions, not product bugs or expected-passing room defects.
Actual guard controls and source/Material-role families passed at exact22309ff1e above.
Corrected all-theme name/font/target enumeration passed at exactabc86cb0d. Unchanged
normative room cases await genuinely repaired bytes, including their new required link API;
these passing selected cohorts do not turn legacy room/contrast failures into success.

Hosted [run38264623000](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38264623000)
for exact b42c9a41e failed at that test compilation error. Its authoritative result is not
transferred to later repaired heads. No automated result/JUnit/image bundle is committed,
uploaded, hashed or revalidated here.

**Identity blocker withdrawn:** the coordinator verified the valid alias relationship.
Implementation resumed with canonical identity/history unchanged. A session-local targeted
launcher uses the existing `local/run.sh` bundle/ext4 snapshot adapter, shared Gradle lock,
installed state and `candidate_environment` credential stripping. It invokes the declared
`:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.*` task with strict
dependency verification. It checks actual JUnit discovery and rejects missing/zero/skipped
tests. It is local diagnostic execution, not a manifest acceptance runner or hosted CI result.
An initial launcher call exposed CRLF in the mounted shell script; the adapter is copied
with LF into the private control directory, just as `check.py` already does. Product/tool
source files were not changed to work around host paths.

Separately admit WP-503's real runner/output acceptance contract: its manifest verification
still has `configured=false` and no commands. Existing App task registration executes owned
sources without claiming that the manifest acceptance contract has become configured.
No Gradle task was invented.

The coordinator routed A503-01 through A503-04 to the dedicated WP-310 producer in issue
#207, worker `5ca13f75-8466-4dd5-b630-02b30841a5d0`. Re-audit actual repaired bytes once
published/merged. There is no protected product decision requesting new colors or
relaxed thresholds. No nested worker, signing/release action or repository setting was changed.

The audit and execution contract are in [accessibility documentation](../../accessibility/README.md).
The earlier hypothetical ownership/execution blocker was posted to
[issue136](https://github.com/cbattlegear/MeshCoreOne-Android/issues/136#issuecomment-6125830322).
It is withdrawn; the resumed measured handoff is
[comment6126652467](https://github.com/cbattlegear/MeshCoreOne-Android/issues/136#issuecomment-6126652467).
The native cross-session messaging tool is not exposed in this worker; this issue comment
and the draft PR are the persistent coordinator handoff, not a fabricated message receipt.
