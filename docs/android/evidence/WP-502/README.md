# WP-502 Compose flow and rendering evidence

**Implementation in progress; full WP-502 acceptance is not claimed.**
The initial docs-only candidate is superseded by owned Kotlin assertions and
capability-admitted App test source registration. Missing formal verification
configuration or device observations are acceptance prerequisites, not evidence
that the installed Linux implementation lane is unavailable.

## Identity and ownership

Repository: `cbattlegear/MeshCoreOne-Android`; issue [#135](https://github.com/cbattlegear/MeshCoreOne-Android/issues/135);
[PR #206](https://github.com/cbattlegear/MeshCoreOne-Android/pull/206), ready for review.
Owner: `test-parity-engineer`, canonical project/reservation session
`3bc8c1b8-20ec-4647-88ae-db3775268489`; runtime alias
`fb112832-0a71-4f6d-abc0-f07f80eaeb8b`.
Branch: `cbattlegear-issue-135-wp-502-compose-flows-and-deterministic-534de7`.

Base: `d958471ab4835813881a5e7af36e8249a8b273a4`.
Immutable reference: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest: `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda`.
Policy: `3fc0afa19ad31f0210a5d427f655ef90b50a8e64e818fe3420b188303b99fb20`.
The existing typed reservation was evolved to revision 13, not replaced.
`android/app/build.gradle.kts` is admitted through
`dependency-resolution-metadata` / `update-directly-required-build-wiring`.
The shared ledger remains authoritative; no copied receipt is committed.
Revision 4 admits the coordinator-assigned #215 app send assembly at
`AppChatsFeature.kt` / `add-direct-support-wiring`; revision 5 admits integration
of published composer producer `b644c9a1d2522ae1519d9359e21b37988dbead68`.
That three-file correction is integrated as `2518046f5`; unrelated unintegrated
contrast claims were not imported during the disjoint documentation merge.
Revision 6 admits the existing producer's native keyboard diagnostics; revision 7
admits its published queue-shaped API/failure callback and associated tests.
The source owner remains WP-308. Integration of `a423da64e`, `6eb6f7b8c`,
and `a1c9c88da` is `710baeea0`; the authoritative App-only consumer is
`0ae51f7e1ca9c8a4c7aa962a0dfadae59923bf8e`, actually published on PR #206.

WP-407 is the only dependency. Its PR #164 merge
`250dd50ed259c5d9fe1006d4f45a637e4aea0aff`, merged
`2026-10-11T16:22:30Z`, is an ancestor of this base.
WP-501's parity/accounting/binding surfaces are not changed or duplicated.
Disjoint WP-501 test-only and WP-504 release/R8 build additions must survive
coordinator integration.

## Implemented assertions and input families

| Owned surface | Assertions and provenance |
| --- | --- |
| `ConversationScreenCases` | Production `ChatTimeline`/`ComposerBar`, fixed synthetic identities/time, original CJK/Hebrew/ZWJ text; focus/draft retention, accessible retry, offline send rejection, exact UTF-8 boundary, distinct loading/empty/error recovery and 48dp send target. Mounted Enter/NumPadEnter with/without Shift exercises 24 connection/overflow/blank/cooldown/sending/enabled families at controlled 200% font. Consumer assertions informed by frozen `ChatInputBar.swift` and `ChatConversationMessagesContent.swift`; not a claim to port every Swift test. |
| `ConnectedConversationComposeTest` | Actual AppContainer/Room/service graph and existing protocol-speaking firmware harness. Reference `RESP_CODE_ERR=1`, direct `ErrorCode.notFound=2` and channel terminal `ErrorCode.illegalArgument=6` reject real sends. Separate mounted direct/channel cases click Retry and require the same saved message, original text/FAILED persistence, visible failure/recovery after both attempts, one terminal event per attempt, durable envelope metadata, queue cleanup, consumed draft and one connection owner. Channel notFound is intentionally retryable and is not substituted for the terminal reference error. New assertions require their own positive execution before credit. |
| `QueuedComposerIntegrationTest` | Real Room/app/service pending direct/channel, retry and Send Again queue ownership; terminal event count/cleanup, real enqueue transaction rejection and typed provider failure, unless-delivered failure/visible-thread seen state, observer cancellation and replacement-generation hydration. Newly defined assertions require positive execution before being credited. |
| `ShortcutQueueIntegrationTest` | Real direct/channel durable acceptance, typed creation/enqueue/cleanup failure stages, cancellation provenance and generation replacement, SYNCING acceptance/drain, and actual file-store close/reopen. The resumed reopen case reconnects a fresh AppContainer to the same radio, requires both durable rows to drain through the real queue, exactly one wire command per message, terminal persistence/cleanup and no duplicate messages. Newly extended drain assertions require their own positive execution. |
| `CoverageMatrix` | Independently specified Ember forced-dark rule, all ten unlocked themes/19 effective frames, 20 SYSTEM requests. 57 theme/window/font/direction rows cover each effective frame at 360/744/834dp and both 100%/200% font scales/directions. 48 state rows cover all 176 pairs across 12 locales, four delivery/connection states, three widths, two fonts and two directions. Exact pair-set assertions reject omissions. |
| `LocalContentCases` | Self-authored schematic raster and cached metadata, not external map/terrain data: production inline-image failure reporting/retry/viewer, manual/loaded/malware preview actions, offline map loading/failure/retry and coordinate callback independence. Injectable local sources never fetch the network. |
| `ConversationComposeTest` | Native Robolectric API31 execution of the production-content flows and matrix; expected 127 distinct rendering inputs including local image/map content, two identical settled draws per capture, nonblank pixels and unclipped visible message text. Native host evidence is not emulator or approved golden evidence. |
| `ConversationDeviceTest` | Actual API31/37 wrappers, real current window rather than a pretend expanded phone, system-bar accounting, hardware-window PixelCopy, every effective frame at both font scales plus SYSTEM requests. Three earlier methods executed on API31; newer wrappers and hardware-key families require their own exact-binary observation. |
| `PlatformApiBoundaryDeviceTest` | Actual API33/34/36-only wrappers: denied notification remains advisory, pre37 LAN independence, connectedDevice manifest/normal permission, API36 public CDM removal availability. Queries only; never remove a bond, start a radio service or reconfigure a radio. Defined, not yet credited as executed. |

The matrix is deliberately layered, not the full Cartesian product and not an
assertion of all theme/locale combinations. Local image/map fixtures passed
native-host assertions at `38dea8f910`; broader feature flows, folding observations
and a spoken TalkBack audit remain gaps.
Existing prerequisite tests are prior art; newly defined consumer cases do not
replace WP-501's original-case audit.

Rendering uses no approved golden. Repeatability is an assertion about two
settled native draws, not visual parity with iOS or an approved baseline.
RTL shrink-to-fit paragraph allocation can exceed the measured text width
without clipping a visible glyph; assertions check visible glyph bounds,
height overflow and ellipsis directly, without geometric tolerance.

## Executed commands and observations

The fault-boundary candidate `66102ee7fd382c91be6cfcdd5bc9e14f86b66b35`
runs the declared `:app:testDebugUnitTest` with only
`com.meshcoreone.android.app.container.ShortcutQueueIntegrationTest`:
**ten discovered, nine passed, one failed, zero errors/skips** (35s).
Both direct/channel pending INSERT failures already return a typed shortcut
error without persisting a message. The new simultaneous `pending_sends` INSERT
and message `statusRawValue` UPDATE rejection case instead observes a cleanup
`PersistenceStoreException` as the shortcut cause, replacing the original
`ChatSendQueueServiceException.PersistFailed`. This is an actual provenance
failure, not successful cleanup. The owned App adapter now logs that narrow
typed cleanup failure while retaining the original enqueue error and leaving
cancellation untouched. Its successor results are not inferred from older passes.

Current functional check at `fdbcc3472`: the installed serialized ext4 lane ran
`:app:testDebugUnitTest` with `ConversationComposeTest`,
`ConnectedConversationComposeTest`, `QueuedComposerIntegrationTest`,
`ShortcutQueueIntegrationTest` and the existing `DurableRetryIntegrationTest`.
**30 discovered, 30 passed, zero failed/errors/skipped** (1m15s). This includes
terminal timeline recovery and failure-before-seen ordering, honest paging,
24 mounted hardware key families, durable direct/channel shortcut acceptance,
real transaction rejection, typed preconditions, disconnect/different-radio
confirmation, exact cancellation provenance, SYNCING drain and file-store reopen.
At `f1c2a8fb7`, shortcut discovery was seven/five passed/two failed; at
`cbf9adaaf`, eight/seven passed/one failed. These are not relabeled as passes.
The fixes wait for a real held contact query and use a genuinely distinct
connection target for different-radio confirmation. Matching current-device
execution and exact hosted checks are separate prerequisites. Actual main
`cd436cb63c3b651241058d16db2fa576cbc6d412` is subsequently integrated at
`bad5aca388b98ac76ed482027b36150425fcfa60`, preserving the landed shared composer
and byte-identical App failure adapter. The same 30 selected cases pass there
(1m20s); exact hosted results remain authoritative, not this local observation.

### Matching native device observations at `bad5aca38`

The declared `:app:assembleDebug :app:assembleDebugAndroidTest` produced matching
target/test APKs. Target SHA256:
`7c049d30463bf7e653fdd1f188efb4eb13bc426cd2cf26a2ed31c9049aa457fb`;
test SHA256:
`be1c975557ad77d7d2099a73d01905d3d7156a558e6806803a6f214be97db52d`.
These identify actual device-installed bytes, not another reproducible CI bundle.

Scoped `adb -s emulator-5556 shell am instrument -w -e class` executed
`ConversationDeviceTest,NativeCapabilityTest,NativePlatformTest,NavigationKeyboardBackTest`
using their registered full package names and
`com.meshcoreone.android.debug.test/androidx.test.runner.AndroidJUnitRunner`.
Actual API31: **12 discovered/12 passed**, 119.52s, SDK31/4KiB pages,
1080x2400/density420, AOSP/no GMS, observed OS font2/no default network.
Actual API37: **12 discovered/12 passed**, 151.66s, full37.2/16KiB pages,
1344x2992/density480, GMS present, observed OS font2/no default network.
Each retained 58 theme/font/SYSTEM and two local-content PNGs. Paging, 24 actual
mounted Return/NumPadEnter/Shift families and strict identical-draw local image/map
assertions pass. These are current-window observations, not folding continuity,
physical TalkBack/radio evidence or approved goldens.

The same command selected only
`com.meshcoreone.android.uitests.PlatformApiBoundaryDeviceTest` on actual isolated
AOSP API33/34/36. Each **two discovered/two passed** (0.055s/0.341s/0.083s),
with denied notification advisory behavior, pre37 LAN independence, connectedDevice
registration/normal permission and the public CDM method boundary checked without
removing a bond. All observed 4KiB pages/no GMS/no default network.

Missing images were installed using the actually available Linux SDK CLI into an
owned private SDK, using existing accepted SDK licenses without accepting new ones,
telemetry disabled and no shared SDK update. Extraction moved from a stalled
mounted Windows directory to ext4; the first fast-copy ENOMEM was repaired via
bounded streaming/Windows transfer. A startup transport miss and API37 `svc data`
exit20 occurred before test execution. A later API37 attempt timed out at450s
with an unresponsive emulator and remains failed/unverified, not a pass.
Only its verified owned QEMU child was stopped; completed owned staging was cleaned,
then the unchanged assertions passed on matching APKs. Both normal successful AVD
runs were shut down through scoped adb; the API31 font setting was restored.

Non-reproducible native logs/PNGs are retained in this worker's session
`files/api31-device-bad5`, `files/api37-device-bad5` and scoped
`api*-instrumentation-bad5-private.txt` / `api33-boundary-bad5-private.txt`.
Raw emulator logs are not committed. Earlier binaries and failures below retain
their original identities. Full WP acceptance/review is not claimed.

### Matching native device observations at `49a0a5fa6`

Exact source `49a0a5fa68790faa0687ef509815e8d42c5a4e49` includes actual live
main `d8181418614a5acaa46f4acde54b741b492ac4b0`. Its authoritative hosted
[run 38299465982](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38299465982)
passes all six jobs; that scaffold does not compile/execute `androidTest`.
Separate declared `:app:assembleDebug :app:assembleDebugAndroidTest` passes (38s).
Matching target SHA256 is
`481fdbf11e17cfba30176c83afa19bb4d9b76753fdd0fbc5677b3ae9758776dd`;
test SHA256 is
`8a3cc0e0a8fd8d4e1e43b15e55182521bcaa789235ec9e40bf056c16060b18e8`.
The same scoped instrumentation commands above actually pass
**API31 12/12** (107.004s), **full API37.2 12/12** (146.309s),
**API33 2/2** (0.032s), **API34 2/2** (0.058s), and **API36 2/2** (1.534s).
API31/37 observe OS font2/no default network, with 60 retained PNGs each;
31 has no GMS/4KiB pages,37 GMS present/16KiB pages. All targeted33/34/36
have no GMS/4KiB pages;33/34 observe no default network, but36 observes
active default network100, so no API36 offline-flow result is claimed.

Source/APK-bound non-reproducible logs persist in session
`files/api*-observation-49a0a5fa6-private.txt` and individual instrumentation logs;
PNGs in `files/api31-device-49a0a5fa6` / `files/api37-device-49a0a5fa6`.
The first local functional attempt terminated with Windows `0xC00000FD` before
discovery; the bounded-output retry passes30/30 (1m25s). The first API31
installation preparation became unresponsive before tests; only its verified
owned QEMU was stopped, and the unchanged retry passes. Successful AVDs shut
down normally, and31 font setting restored. None of these results is relabeled
as a successor pass, approved golden or whole-WP/hardware acceptance.

The installed supported lane is the Git-bundle/ext4 snapshot launcher:

```powershell
python tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit HEAD --stages preflight
python tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit HEAD --stages scaffold
```

The lane preserves the actual shared Gradle flock, credential-stripped
environment and pinned Linux toolchain. Never invoke Gradle on mounted Windows
`.git` or bypass another owner's lock.

- At `3fce7297eaf47b97ccf9f5ff9359e1699ac1ab01`, installed Linux pre-push
  `python/preflight/scaffold/inspect` **passed** (673.36s), before new tests.
- At `fee7a2dfe9f65808012293da6cb4167bf7094862`, scaffold compilation and
  positive App discovery succeeded; **684 tests, two failures** in new assertions.
- At `f005faf884` (abbreviated Git commit), existing `:app:testDebugUnitTest`
  with the two owned class selectors discovered **six tests, five passed,
  one failed**: the persisted FAILED row had not yet reached the displayed
  timeline assertion. The 87 earlier rendering inputs passed.
- At `5ee3e8fb0d26b345953484bb0dbf5f8d13cf1c80`, the expanded native matrix
  again discovered **six tests, five passed, one failed**. All 125 rendering
  inputs passed. The semantic diagnostic showed the visible row was still
  SENDING while Room already held FAILED. A later explicit 30-second consumer
  wait did not fix this failure.
- Published `38dea8f910040f33eeb238584a2bc7e2533f2a73`: installed scaffold
  discovered **687 App tests: 685 passed, two failed**. The targeted existing
  `:app:testDebugUnitTest` with `--tests
  com.meshcoreone.android.uitests.ConversationComposeTest` and `--tests
  com.meshcoreone.android.app.platform.ConnectedConversationComposeTest`
  discovered **nine: seven passed, two failed** (1m12s). All 127 rendering
  inputs completed, including local image/map content. The failures were the
  unchanged connected consumer state and a no-older-page fixture incorrectly
  inheriting `hasMoreMessages=true`.
- At that exact snapshot, the installed plugin-declared
  `:app:assembleDebugAndroidTest` **passed** (25s).
  The combined PowerShell command's exit zero came from this last build;
  it does not turn the preceding failing unit run into a pass.
- The no-pagination fixture and image-disposal ordering are corrected in the
  subsequent candidate. A new mounted hardware-key regression is registered in
  both runners. These changes require new positive discovery; the expected
  owned unit total is now ten, not a relabeling of the nine-case result.
- Ordinary `git push origin HEAD` published `38dea8f910` after the installed
  hook explicitly recorded **UNVERIFIED CANDIDATE**. The queued-hook defect is
  tracked separately in #211 with the existing WP-504 producer; no bypass or
  foreign-lock change occurred. Exact hosted checks remain authoritative at
  [run 38273168422](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38273168422).
- The `70cc1c413` hardware fixture had a compilation error; no tests are credited.
  The corrected `aaf3845bdca08d8a1e8f078b6881dd3d83bce0e0` discovered ten:
  eight passed/two failed (1m13s). Paging passed; all twelve unmodified key
  families passed, while all twelve Shift families reported zero callbacks
  but missing newline. The other failure was the connected send.
- With the real producer Shift fallback integrated, `d47193cf9` discovered
  **ten: nine passed/one failed** (1m28s). All 24 mounted key families and
  the corrected paging case passed; the old app send recovery still failed.
- Ordinary `git push origin HEAD` actually published App consumer
  `0ae51f7e1ca9c8a4c7aa962a0dfadae59923bf8e`: the installed repaired hook
  queued ten seconds, recorded this exact candidate **UNVERIFIED**, then
  pushed successfully. This is publication, not a current check or merge pass.
- `python tools\android-port\controller\validate.py` **passed** the frozen
  inventory: 1,866 tracked reference files, 64 WPs, 178 dependency edges.
  This proves traceability validation, not behavior parity.

On 2026-10-12 an isolated session-only API31 AOSP x86_64 AVD executed eight
selected instrumentation cases: **eight discovered/eight passed**, 91.53s.
These observations bind the earlier `5ee3e8fb0d26b345953484bb0dbf5f8d13cf1c80`
test APK, not `38dea8f910` or the new hardware-key regression.
Scoped adb observed `sys.boot_completed=1`, SDK31, 1080x2400px, density420,
no `com.google.android.gms` package and no active default network.
The three owned cases covered offline/UTF-8 input, retained draft/retry and
58 native theme/font/SYSTEM frames; five existing cases covered actual optional
permission/camera denial, cached offline conversation/full emoji insertion,
one process graph and real IME/Back/insets. It was shut down with scoped
`adb -s emulator-5556 emu kill`.
`settings get system font_scale` returned null; controlled Compose font cases
are not evidence of an OS-wide 200% setting on that run.
An earlier launch correctly rejected another owner's hard lock; it was not bypassed.
An isolated API37 AVD has now actually booted under the same trusted lock:
SDK37/full37.2, 16384-byte pages, 1344x2992px, density480 and GMS present.
With observed OS `font_scale=2.0` and no active default network, ten selected
cases executed in 127.384s: **eight passed, two failed**. The 58 theme/font/SYSTEM
frames and the five existing platform/navigation cases passed; the local
image/map two-draw comparisons failed. This is not complete API37 acceptance.
The known faulty loading case was not selected, and new hardware-key cases were
not in this binary. The AVD was shut down normally and its actual lock released.

This run used target APK `AD767D642E33933B4364A09597A615C754935ADC08D02E428861ABD8026C2527`
from the earlier `5ee3e8fb0d` build and actual `38dea8f910` test APK
`01E9A83D314489BB569721A3AD566A4921A277E4777D3F8D264DD1EBCB6AFDB4`.
These are device binary bindings, not a second reproducible CI-result bundle.
The instrumentation command's exit zero is Android's transport outcome, not
a green JUnit result. No newer candidate is credited with these observations.
Installed image directories for API33/34/36 are absent; directory inventory
is not substituted for any required actual platform observation.

The earlier Windows launcher failure is historical and host-specific:
`platforms\android-37.2\package.xml` is absent in the default Windows SDK.
The installed `android-37.0` was not substituted. No license acceptance,
SDK provisioning, signing or release action occurred.

## Consumer repairs awaiting exact-candidate verification

The connected assertion exposes a producer-owned app assembly gap:
`AppChatsFeature.sender().sendPendingDirect` bypasses the send queue and calls
`MessageService.sendPendingDirectMessage` directly. `queuedDM` intentionally
uses `sendBoundary(id, false)`; its failure persists FAILED without publishing
the terminal failure event. `ChatSendQueueService` normally publishes it using
`notifyMessageFailed`, while the active app timeline remains SENDING here.
The event adapter and ViewModel already handle the missing failure event.
The exact app path plus `add-direct-support-wiring` is admitted by the trusted
`app-build-launcher` rule. The coordinator assigned bounded #215 to this same
worker and reservation; app queue assembly is being repaired without changing
core `notify=false` or injecting fake UI state. The producer's actual published
queue-shaped port is now integrated, removing low-level timestamp flags and
wire-return receipts rather than ignoring arguments or inventing attempt counts.
The historical direct-edit admission rejection was resolved by serialized
primary-owner production and trusted disjoint integration, not a bypass.
The App-only consumer routes pending envelopes to the captured service queue;
manual DM retry still persists once then signals only. Channel retry/Send Again
uses genuine stored text/timestamp metadata. Non-cancelled local failure persists
unless delivered, marks only the visible matching thread seen and broadcasts
the actual failure event. The new tests and mounted recovery require current
positive discovery.

The hardware regression follows the frozen `handleHardwareSend` contract:
disabled Return returns false for normal multiline editing, with no send
callback; enabled Return sends once; Shift preserves newline editing.
#212 is serialized to the existing WP-308 composer producer. WP-502 changes
its owned mounted test fixture/runners and integrates the actual published
producer correction, rather than duplicating a product handler implementation.
The disabled guard and Shift editor fallback are integrated through actual
`b644c9a1d` and `149581edb` lineage; the `d47193cf9` host cohort passes all 24
families. This remains distinct from device keyboard evidence.

The next local-content fixture explicitly suppresses transient Material ripples
for static rendering inputs, advances the injected Compose animation clock,
and captures hardware windows only after their GPU frame-commit callback.
Two draws must still be byte-identical; no pixel tolerance or golden is changed.
Image owners are disposed and awaited before recycling their bitmap.
These corrections require new host/device evidence.

## Artifact and acceptance boundaries

Native candidate images are generated under
`android/screenshot-tests/build/screens/sdk31/{themes,system,states}` in the
managed ext4 snapshot. Device images are generated in the target app's external
files directory under `wp502/sdk<actual>/themes`. Do not commit a second
reproducible CI-result/image bundle; exact-candidate hosted results/logs remain
authoritative. Retain only actual non-reproducible device/human evidence.

`verification.configured=false` and no WP-502 commands remain in the manifest.
Existing declared App/scaffold commands can execute registered real tests, but
do not constitute formal full-WP admission or acceptance.
`WP-502-behavior`, `WP-502-boundaries`, and `WP-502-source-test-parity` remain
unaccepted pending complete implemented/observed coverage, exact-candidate
checks and review. Keep #135 open/assigned; no physical radio, legal, signing,
golden approval or human gate is claimed.
