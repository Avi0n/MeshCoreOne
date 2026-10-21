# WP-501 advertisement path recency: two original persistence destinations

## Local candidate boundary

This is ONE LOCAL, UNPUBLISHED successor to
`9ab524d567f8480792768c2f2f21337343c307c1` on the existing WP-501 branch/PR165.
It adds only a parity test and this owned evidence/deviation. No product,
incumbent test/helper, build, policy, manifest, source reference or acceptance
threshold changes. Kotlin is NOT COMPILED/RUN/DISCOVERED; source-safe checks
cannot prove either destination executes. The already verified seven-method
reporter/factory repair is unchanged; no CI-result copy or replay is created.

The missing family is **real persistence execution**, not missing method names:
`AdvertisementServiceStoreTest.originalCases` already mirrors both named source
bodies, but `AdvertHarness.makeStore()` substitutes `AdvertFakeStore`.
The complete frozen originals construct real in-memory `PersistenceStore`.
The new destinations replace that absent Room/service path, not duplicate the
two originals in accounting. Existing mirrors remain intact. No whole-file,
whole-WP or fourth channel-counter original acceptance is proposed.

## Exact frozen originals and input/consumer comparison

Authoritative source remains `db14559b39d32322b06477c6ae676112f583db50`.
Source: `MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift`.
Destination class:
`com.meshcoreone.android.parity.AdvertisementPathRecencyOriginalPredicateTest`.

| Complete original at frozen pin | Actual proposed primary method | Preserved actions and predicates |
|---|---|---|
| `pathResponse stamps lastHeard and preserves radio lastModified()` L2360-2402 | `originalDirectPathResponseCommitsPhoneRecencyWithoutChangingTheRadioWatermark` | Real store/device; seed A5 incremental 32-byte key, `PathPeer`, chat/flags0/direct empty path, advert1700000000, radio lastModified1700000100, latitude/longitude0; registered service monitor; six-byte prefix, direct/empty outbound AND inbound response; wait for completed save/touch and public response; fetch persisted contact; lastModified unchanged and lastHeard positive; stop monitoring. |
| `pathUpdate does not stamp lastHeard()` L2405-2434 | `originalPathUpdateCompletesBothRealDeltaRoundsWithoutStampingPhoneRecency` | Real store/device; same frame defaults with A6 incremental key/`PathUpdatePeer`/lastModified1700000100; install genuine service delta callback matching frozen HandlerRecorder's default SYNCED/no queued writes; registered monitor; full-key PathUpdate; actual callback reached; persisted contact still lastHeard0; stop monitoring. |

Read complete frozen bodies, full L1-233 helpers/setup, actual
`DeviceDTO+Testing.swift`, and the entire incumbent StoreTest bodies/support.
Neither selected original declares parameters, injected errors or mid-operation
cancellation. They do not authorize converting errors into success. Native
AdvertisementService rethrows caller cancellation, logs contained save/touch
failures, and emits its response only after the contact path returns. The fixture
captures the real log sink and rejects ERROR diagnostics; it does not manufacture
typed failures, replies or cancellation verdicts. Teardown retains the primary
body/cancellation object, suppresses later failures and attempts every cleanup.

Frozen service `handlePathDiscoveryResponse` resolves the radio-qualified prefix,
saves a frame preserving the radio watermark, then stamps phone receive time.
Native `AdvertisementService.kt` L603-640 follows that path into actual
`ContactRepository.fetchContactByPrefix/saveContact/touchContactHeard` and Room
`ContactDao` radio-qualified SQL/transactions. Timestamp stamping also updates
Discover in the SAME real repository transaction. `RoomRepositoryContext`
preserves cancellation and wraps SQLite/value failures with typed causes;
the test does not replace that context/store or implement its own repository.

For PathUpdate, both frozen/native services record a path key rather than an
advert receive time. Actual delta-round code snapshots lastModified, invokes
the installed handler, finds the unchanged watermark and escalates once. The
new negative assertion waits through BOTH real rounds (`false,true`) and both
public ContactUpdated events, then checks again after service stop and joined
owned jobs. It is not an early lastHeard read before later work can stamp it.
The callback's SYNCED/no-write outcome is exactly the original helper's behavior,
not a claim that any real GET_CONTACTS exchange occurred.

## Native adaptations and independent literals

The inherited `RoomProcessTest` uses API31 Robolectric/NATIVE SQLite and the
production RoomPersistenceStore. This is software integration, NOT device/native
acceptance. Existing `FirmwareRadio` answers only the actual public
MeshCoreSession startup. Tests then push independent literal frames through the
real transport reader/parser/dispatcher; no direct `yieldEvent`, private event
handler, copied consumer, service/store fake or expected-callback-only assertion.

Frozen `PacketCodes` + `Parsers+Binary.PathDiscoveryResponse` declare response8D,
reserved byte, six-byte prefix, outbound length/path and inbound length/path.
Frozen `Parsers+Contacts.PathUpdate` declares the full32-byte key.
No candidate encoder/parser generates expectations:

| Input | Independent literal |
|---|---|
| A5 key, source `(index + 0xA5) & 255` | `a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4` |
| Direct PathResponse | `8d00a5a6a7a8a9aa0000` (code/reserved/prefix/out0/in0) |
| A6 key, source `(index + 0xA6) & 255` | `a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5` |
| PathUpdate | `81a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5` |
| Only outbound command, APP_START | `01032020202020204d65736843` |

Phone Date() is fixed at Unix1704067200 using the production injected
AdvertisementClock; scheduler drives zero debounce/minimum/busy intervals.
The five-real-second/2ms Room-I/O drain follows existing ContainerHarness prior
art without advancing any session deadline. Complete frozen device defaults
are retained, with fresh radio/UUID identity and fixed lastConnected. An added
same-key foreign-radio control remains byte-for-byte DTO-equal.

Assertions compare complete persisted contact DTOs, including generated identity,
all unchanged metadata and exact lastHeard1704067200 versus0. They also assert
radio-scoped prefix resolution, exact protocol and service event objects,
delta flags, unchanged device/foreign rows, exact no-extra wire transcript,
completed streams and actual transport connects1/closes1/readers0. Independent
service and session owners keep session shutdown ordered after service cleanup;
no generation reset, quarantine bypass or new product hook.

## Reference drift disclosed, not adopted

The initial blanket equality guard correctly rejected the checkout test blob.
Frozen original blob is `55b86af659e3445566f060dd38dfb5c4bdf0d409`;
trusted DBB/parent/worktree blob is
`cd764c0531bb27cb591b014ee6e4e577ee91d41b`.
The COMPLETE existing diff has only two unrelated synchronization adaptations:
the rollback-cascade test replaces a40ms sleep with observed delete handling;
handler uninstall/reinstall replaces a40ms sleep with pending-key registration.
Neither is a selected destination. Both complete selected bodies and the ENTIRE
helpers/setup prefix were separately compared to actual frozen Git content and
are identical; frozen line numbers above are authoritative. No reference file
is edited, no later pin or modified original expectation is used.

## Trusted admission and source receipt

Fresh authenticated actor `cbattlegear`/916234, public/local parent9ab, actual
main/base `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`, clean initial branch,
paused=false, canonical manifest
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`
and policy
`e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`
were checked. Whole policy/manifest/admission/ledger/reserve blobs match trusted
DBB/parent/worktree. WP109/203/407 merge ancestry is checked against actual base,
not issue-closed state. The actual WP203 merge is
`d8f9b842581496baa382be1fe54dc866354b8150`, not a shortened historical handoff.

SAME stored attempt/owner `d6056690-3a5d-453e-b67e-36866c676ac5` evolved94->95
using `Ledger.evolve_scope` CAS BEFORE edits. Current CLI27f3 is not relabeled
as that owner. Capability `wp-owned`, operations create/modify/test/record-evidence,
12 actual admissions, only these three paths:

- `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/AdvertisementPathRecencyOriginalPredicateTest.kt`
- `docs/android/evidence/WP-501/advertisement-path-recency-originals.md`
- `docs/android/deviations/WP-501.md`

No foreign path intents overlap those owned surfaces. Every other reservation
field, including immutable authorization/binding/identity/state/expiry/budget,
was compared and preserved. No support capability, policy amendment, resource
lease/reentry or owner replacement is needed.

| Read-only source/input | Frozen blob (or unchanged native DBB/parent/worktree blob) |
|---|---|
| Frozen AdvertisementServiceTests | `55b86af659e3445566f060dd38dfb5c4bdf0d409` (checkout drift disclosed above) |
| Frozen DeviceDTO+Testing | `ffbeca180d8cbb1833d6cea4984b7fbddd194d69` |
| Frozen AdvertisementService | `c95879289a6cb9706495dbb0fbf6e430cb211894` |
| Frozen AdvertisementService+DeltaSync | `2e69735b5d76c04239a5433c36d2c86cb01a4dea` |
| Frozen Parsers+Binary | `eec6a3200d0225a96619d3567152ede05b76603f` |
| Frozen Parsers+Contacts | `de083148e75bf31af02f8c3356d7622c4626c60e` |
| Frozen PacketCodes | `31e38b41ad34aaf31a2d855a4f5cc8ff7ebccf6e` |
| Frozen PacketParser | `67415b52f2657edfe192e3f08ed301d3165bfac1` |
| Native AdvertisementService | `19feb470905d04e973771b9ed222145090d2626c` |
| Native AdvertisementDeltaSync | `aead465d9d5a7aaa00cfc989b637a1f997aa325a` |
| Native AdvertisementEvent | `3c6a8d924db52e68fe1ed6bf99ed713a7098af0a` |
| Existing AdvertisementServiceStoreTest | `be16dca1b8f2fbaecb20da3796d360f5b0d591f3` |
| Existing AdvertTestSupport | `ee28fba2b1574cf9dd1d5038c734df50e647ee1a` |
| Native ContactRepository | `8da5513b403198a44f673addf443c5411b58a80c` |
| Native RoomRepositoryContext | `85d5593ac6f45783acbefc5efeabb58a24f111d4` |
| Native RadioDaos | `af37fd8c74d3a2ec515eba23eb39b9afef8017ac` |
| Native Parsers | `d328ac0717b6468d7d10cd05c069d32d1765d696` |
| Native PacketParser | `45dbd242550209176e12a252468ed8378697c9ef` |
| Native MeshCoreSession | `3e98cc221bca2af76c273a2e2f88ec005b0f2f29` |
| Existing ContainerTestSupport/FirmwareRadio | `2ffc6cdf5870f412f7f8770ae97230c3f7f7eef4` |
| Existing ContainerHarness/RoomProcessTest | `c6fb962fca19d1ff87c48eaa05868514a08d614a` |
| Existing WP501 publicSessionClock helper | parent/worktree `9f1ac6fe9cae87def964b83bd97220929a5e6726`; not present on DBB |

## Verification and remaining gates

The following declared source-only checks PASSED after the test/evidence edits.
None compiles Kotlin or establishes discovery. Archived groups/review-proposals
remain historical and are not regenerated or increased.

| Exact command | Outcome |
|---|---|
| `python tools\android-port\controller\verification_config.py --check` | PASSED |
| `python tools\android-port\controller\validate.py` | PASSED |
| `python tools\android-port\portmap.py` | PASSED |
| `python android\parity-tests\audit.py --check` | PASSED |
| `python android\parity-tests\semantic.py --check` | PASSED; historical58 groups/5133 families/1479 candidate comparisons, not passing execution |
| `git diff --check` | PASSED |

Staged whitespace and exact changed-path/blob readback are additionally required
before the local source commit; their outcomes accompany that exact handoff.

Declared focused selector for future separately permitted execution, **NOT RUN**:

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.AdvertisementPathRecencyOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

Both exact method identities must be positively discovered/executed once with
zero failures/errors/skips in the candidate's OWN automatic hosted checks,
clean/nonempty App and complete mandatory selected reports/all-six success.
Missing/malformed/zero/skipped evidence is not success. No local
Gradle/WSL/native/device/host/lease/runtime/recovery, dispatch/rerun, publication,
main/policy or merge action occurs here. The unadmitted bubble-accessibility
operation is not relabeled or excluded; fourth-counter/whole22/full-WP/native/
hardware/protected acceptance remains outstanding.
