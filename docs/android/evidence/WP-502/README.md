# WP-502 Compose flow and rendering evidence

**Implementation in progress; full WP-502 acceptance is not claimed.**
The initial docs-only candidate is superseded by owned Kotlin assertions and
capability-admitted App test source registration. Missing formal verification
configuration or device observations are acceptance prerequisites, not evidence
that the installed Linux implementation lane is unavailable.

## Identity and ownership

Repository: `cbattlegear/MeshCoreOne-Android`; issue [#135](https://github.com/cbattlegear/MeshCoreOne-Android/issues/135);
draft [PR #206](https://github.com/cbattlegear/MeshCoreOne-Android/pull/206).
Owner: `test-parity-engineer`, canonical project/reservation session
`3bc8c1b8-20ec-4647-88ae-db3775268489`; runtime alias
`fb112832-0a71-4f6d-abc0-f07f80eaeb8b`.
Branch: `cbattlegear-issue-135-wp-502-compose-flows-and-deterministic-534de7`.

Base: `d958471ab4835813881a5e7af36e8249a8b273a4`.
Immutable reference: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest: `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda`.
Policy: `3fc0afa19ad31f0210a5d427f655ef90b50a8e64e818fe3420b188303b99fb20`.
The existing typed reservation was evolved to revision 5, not replaced.
`android/app/build.gradle.kts` is admitted through
`dependency-resolution-metadata` / `update-directly-required-build-wiring`.
The shared ledger remains authoritative; no copied receipt is committed.
Revision 4 admits the coordinator-assigned #215 app send assembly at
`AppChatsFeature.kt` / `add-direct-support-wiring`; revision 5 admits integration
of published composer producer `b644c9a1d2522ae1519d9359e21b37988dbead68`.
That three-file correction is integrated as `2518046f5`; unrelated unintegrated
contrast claims were not imported during the disjoint documentation merge.

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
| `ConnectedConversationComposeTest` | Actual AppContainer/Room/service graph and existing protocol-speaking firmware harness. Reference `RESP_CODE_ERR=1`, `ErrorCode.notFound=2` rejects a real send; original text/FAILED persistence, visible failure/retry, consumed draft, one connection owner and actual send command are asserted. Expected error bytes are independent of the Kotlin encoder. |
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

## Open consumer regressions

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
core `notify=false` or injecting fake UI state. A further queue-shaped composer
port alignment is routed to its producer: existing low-level timestamp flags
and wire-return receipts cannot silently become ignored arguments or fabricated
durable attempt counts. The exact `ComposerPorts.kt` +
`add-direct-support-wiring` query rejects admission because no trusted rule
matches; that interface is not edited here.

The hardware regression follows the frozen `handleHardwareSend` contract:
disabled Return returns false for normal multiline editing, with no send
callback; enabled Return sends once; Shift preserves newline editing.
#212 is serialized to the existing WP-308 composer producer. WP-502 changes
its owned mounted test fixture/runners and integrates the actual published
producer correction, rather than duplicating a product handler implementation.

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
