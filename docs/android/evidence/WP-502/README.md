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
The existing typed reservation was evolved to revision 3, not replaced.
`android/app/build.gradle.kts` is admitted through
`dependency-resolution-metadata` / `update-directly-required-build-wiring`.
The shared ledger remains authoritative; no copied receipt is committed.

WP-407 is the only dependency. Its PR #164 merge
`250dd50ed259c5d9fe1006d4f45a637e4aea0aff`, merged
`2026-10-11T16:22:30Z`, is an ancestor of this base.
WP-501's parity/accounting/binding surfaces are not changed or duplicated.
Disjoint WP-501 test-only and WP-504 release/R8 build additions must survive
coordinator integration.

## Implemented assertions and input families

| Owned surface | Assertions and provenance |
| --- | --- |
| `ConversationScreenCases` | Production `ChatTimeline`/`ComposerBar`, fixed synthetic identities/time, original CJK/Hebrew/ZWJ text; focus/draft retention, accessible retry, offline send rejection, exact UTF-8 boundary, distinct loading/empty/error recovery and 48dp send target. Consumer assertions informed by frozen `ChatInputBar.swift` and `ChatConversationMessagesContent.swift`; not a claim to port every Swift test. |
| `ConnectedConversationComposeTest` | Actual AppContainer/Room/service graph and existing protocol-speaking firmware harness. Reference `RESP_CODE_ERR=1`, `ErrorCode.notFound=2` rejects a real send; original text/FAILED persistence, visible failure/retry, consumed draft, one connection owner and actual send command are asserted. Expected error bytes are independent of the Kotlin encoder. |
| `CoverageMatrix` | Independently specified Ember forced-dark rule, all ten unlocked themes/19 effective frames, 20 SYSTEM requests. 57 theme/window/font/direction rows cover each effective frame at 360/744/834dp and both 100%/200% font scales/directions. 48 state rows cover all 176 pairs across 12 locales, four delivery/connection states, three widths, two fonts and two directions. Exact pair-set assertions reject omissions. |
| `LocalContentCases` | Self-authored schematic raster and cached metadata, not external map/terrain data: production inline-image failure reporting/retry/viewer, manual/loaded/malware preview actions, offline map loading/failure/retry and coordinate callback independence. Injectable local sources never fetch the network. |
| `ConversationComposeTest` | Native Robolectric API31 execution of the production-content flows and matrix; expected 127 distinct rendering inputs including local image/map content, two identical settled draws per capture, nonblank pixels and unclipped visible message text. Native host evidence is not emulator or approved golden evidence. |
| `ConversationDeviceTest` | Actual API31/37 wrappers, real current window rather than a pretend expanded phone, system-bar accounting, hardware-window PixelCopy, every effective frame at both font scales plus SYSTEM requests. Defined, not yet credited as executed. |
| `PlatformApiBoundaryDeviceTest` | Actual API33/34/36-only wrappers: denied notification remains advisory, pre37 LAN independence, connectedDevice manifest/normal permission, API36 public CDM removal availability. Queries only; never remove a bond, start a radio service or reconfigure a radio. Defined, not yet credited as executed. |

The matrix is deliberately layered, not the full Cartesian product and not an
assertion of all theme/locale combinations. Local image/map fixtures are defined
but not yet credited as executed; broader feature flows, folding observations
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
  one failed**: persisted FAILED timeline semantics existed but failed the
  displayed assertion. The 87 earlier rendering inputs passed. The failure
  remains under investigation; no pass-shaped fallback was introduced.
- Expanded matrix, recovery/boundary assertions and instrumentation compilation
  are pending; no device test pass is claimed.
- `python tools\android-port\controller\validate.py` **passed** the frozen
  inventory: 1,866 tracked reference files, 64 WPs, 178 dependency edges.
  This proves traceability validation, not behavior parity.

On 2026-10-12 an isolated session-only API31 AOSP x86_64 AVD actually booted
under the trusted `native-execution` lock. Scoped adb observed
`sys.boot_completed=1`, `ro.build.version.sdk=31`, 1080x2400px, density420,
and no `com.google.android.gms` package. It was shut down with scoped
`adb -s emulator-5556 emu kill`; no app assertions had run.
An earlier launch correctly rejected another owner's hard lock; it was not bypassed.
The installed API37 AVD image is Play Store/16KiB, not a no-GMS claim.
Installed image directories for API33/34/36 are absent; directory inventory
is not substituted for any required actual platform observation.

The earlier Windows launcher failure is historical and host-specific:
`platforms\android-37.2\package.xml` is absent in the default Windows SDK.
The installed `android-37.0` was not substituted. No license acceptance,
SDK provisioning, signing or release action occurred.

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
