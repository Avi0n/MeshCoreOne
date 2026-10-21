# WP-501: complete original identity-reconciliation store fixtures

## Identity and actually missing inputs

- Frozen reference: `db14559b39d32322b06477c6ae676112f583db50`.
- Actual execution parent: `442de1fefaee0a3db4ed5af5ce7538519aea73b2`.
- Actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Existing issue134/PR165; same stored attempt/session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation82->83.
- New test:
  `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/IdentityReconciliationOriginalPredicateTest.kt`;
  actual executed blob `3ba63ce60c3cab3c74596300b0400849913b06a9`.

The actual WP202 family ledger inventories all three
IdentityReconciliationIntegrationTests bodies. Read those complete bodies,
createStore/randomKey, full source DeviceDTO.testDevice/default constructor,
source demote/reconcile/contact insertion, entire native destinations and
their device/frame helpers, native DeviceRepository/ContactRepository and
actual model defaults/process fixture.

Existing native integration tests already preserve the important ghost,
radio-link and orphaning operations. They use the generic native frame
helper, however: outPathLength2/path0102, advert/modified1 and nonzero
coordinates versus the original Bob/PostPair **path0/empty/all-zero** frames.
Their native device helper also omits literal TestMfg/buildDate fields.
The unchanged-key original shares that different device fixture.
These are three genuinely missing complete original fixture variants,
not three newly discovered production bugs. Preserve old positive coverage
without replay or relabeling it as the exact source fixtures.

## Complete source defaults and explicit incidental substitutions

Every original device has fresh separate device/radio UUIDs,
TestDevice, firmware8/v1.11.0, TestMfg, buildDate`01 Jan 2025`,
maxContacts100/maxChannels8, frequency915000/bandwidth250000,
SF10/CR5/tx20/maxTx20, latitude0/longitude0/blePin0,
manualAddfalse/multiAcks0, telemetry2/0/0, advert policy0,
lastContactSync0/isActivetrue. Constructor-only source defaults are also
preserved: clientRepeatfalse, pathHash0, autoAddConfig0/maxHops0,
nil default flood scope/pre-repeat four-tuple/OCV/customOCV/applied preset,
empty connection methods and known regions.

Original randomKey samples 32 arbitrary UInt8 values, without a prescribed
cryptographic relationship or assertion about their distribution.
Use deterministic 32-byte input representatives 00...1F,40...5F,80...9F
for the original/current/contact roles in source call order. These are
explicit incidental sample substitutions, not independent crypto goldens,
an exhaustive byte-parameter test or a reproduction of an unknown random
Swift sample. Fresh UUIDs remain fresh and separately constructed.
Incidental lastConnected Date.now is fixed at Unix1704067200;
no original assertion depends on the ambient wall-clock instant.

Both source contact frames retain CHAT/raw1, flags0/pathLength0/empty path,
literal Bob or PostPair, advert0/latitude0/longitude0/modified0.
Expected actual ContactDTO additionally requires source model defaults
heardTimestamp0, nil nickname/lastdate/OCV/customOCV/avatar,
blocked/muted/favoritefalse and unread/mentions0.
The public save result supplies the newly allocated contact ID, not
candidate-derived expected values for any source field.
Require isNew and exact independently constructed input/default DTO
readback before the reconciliation operation.

## All three full original bodies and predicates

| Frozen original | New method | Complete original sequence and required outputs |
| --- | --- | --- |
| `Remove + erase/flash + re-pair + import config re-links orphaned children()` | `originalBobFrameRelinksAfterGhostDemotionAndRePair` | Actual source device save, exact Bob frame save, demote original device, save separate new device/radio/key; before reconcile require new contacts empty and original contacts count1/full Bob. Reconcile current ID with original public key -> original radio. Require actual updated radio/key/full paired DTO with only radio/key changed, deleted original ID, linked contacts count1/firstBob/full Bob and per-ID readback. Also require generated ghost ID deleted. |
| `No reconciliation when radio's publicKey didn't change after re-pair()` | `originalUnchangedPublicKeyKeepsTheCompleteSourceDevice` | Save sole full source device; reconcile its own ID with same public key -> nil. Require fetched original radio/public key, full DTO unchanged and exactly sole device retained. |
| `Reconciliation does not migrate post-pair child rows to the reconciled radioID()` | `originalPostPairZeroFrameStaysUnderTheTemporaryRadio` | Save original, actual demote, save separate new device/radio/key, save exact PostPair zero frame under temporary radio. Require initial full actual seed and empty old partition. Reconcile with original key -> old radio; temporary partition still count1/firstPostPair/full original DTO/per-ID, old partition empty, updated device old radio/original key/full other fields preserved. Also require actual ghost ID deleted. |

Actual seed/full DTO guards prevent missing rows, altered frame helpers,
wrong radio identity, same-ID device substitution, lost device metadata
or fake absence from satisfying these source predicates.
Ghost guard proves real old-ID removal, fresh ghost ID, original radio/key/
all metadata preserved with only id/active changed and empty methods.
No extra connection methods are invented to test a different branch.

## Actual native consumer and execution

All operations use unchanged public RoomPersistenceStore and production
DeviceRepository/ContactRepository over real native SQLite/in-memory Room,
Robolectric API31. Demote deletes/reinserts a fresh inactive hidden ghost.
Reconcile finds the inactive matching key, updates current radio/key,
deletes the ghost and commits. It does not move Contact rows: the first
original's old partition becomes reachable by current device identity;
the third original's temporary partition stays orphaned.
The source and native non-Bluetooth method-copy loop has no members in
these original empty-method fixtures; no coverage of a nonempty loop is
inferred. Store teardown closes process persistence, cancels its owner
scope and closes the actual database.

This is a persistence-level config-import boundary simulation exactly as
the Swift originals, not an actual erase/flash, BLE re-pair, radio import,
physical-device or mounted UI test. No production/test-hook mutation,
fake repository, manual DAO transfer or native/device action occurs.

Readiness `python android\scaffold\check_environment.py` **PASS**;
established allowlisted JDK21/private caches/installed SDK, no install.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.IdentityReconciliationOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS3/3, zero failures/errors/skips**, all three exact new method
identities above. Started `2026-10-21T02:56:49.028066Z`,
finished `02:58:53.062681Z`; fresh XML timestamp `02:58:47.162Z`,
suite4.309s. Actual442/baseDBB, executed3ba63ce6 unchanged before/after.
XML:
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.IdentityReconciliationOriginalPredicateTest.xml`.
Primary actual local log under session27f3c368 files:
`wp501-identity-originals-79342551be914fe08897c4f1035ca139.log`.

Actual shared locks empty before acquisition; same stored owner acquired
and released exclusive Gradle suffix
`identity-originals:79342551be914fe08897c4f1035ca139`,
reservation83/exact442/dbb/3ba63/selector/977b/e8afe payload.
Final records empty. No three old successful integration methods,
blocked/window/slot-history/other healthy cohort replay,
foreign release, new actor, CI rerun or cancellation.
Published88 terminal39531691829SUCCESS remains88-only.

## Complete read source and unchanged consumer identities

| File | Git blob |
| --- | --- |
| Frozen IdentityReconciliationIntegrationTests.swift | `a3974c3a7d48d798f5f774451f38cb7f647165a4` |
| Frozen Helpers/DeviceDTO+Testing.swift | `ffbeca180d8cbb1833d6cea4984b7fbddd194d69` |
| Frozen Models/Device.swift | `823bee3ced673712a51bfa5332dbba81cd4dfed5` |
| Frozen Models/Contact.swift | `7b6ecd2e224c1b1651edeba8a0019426881e7db4` |
| Frozen Services/PersistenceStore+Devices.swift | `c09ee87def28a5bd6080743ab121adc3a27634bd` |
| Frozen Services/PersistenceStore+Contacts.swift | `96f33ac980a5de8e72685a30d8b9d287805050ea` |
| Existing IdentityReconciliationPersistenceTest.kt | `9db09064b5694a5a6ff6f9b19493d6e37578133f` |
| Existing RepositoryTestValues.kt | `ca3c37ab4bfa149f63bdd687469b8cf9e9f15446` |
| Production DeviceRepository.kt | `9e069c60b5f696a970716ff9c51a1fb99bc27252` |
| Production ContactRepository.kt | `8da5513b403198a44f673addf443c5411b58a80c` |
| Actual ContainerHarness.kt/RoomProcessTest | `c6fb962fca19d1ff87c48eaa05868514a08d614a` |
| Native DeviceDTO.kt | `9d4b471f7f28209f4c8c77cae7131f9ef7546d97` |
| Native ContactDTO.kt | `9d102668ec30d1f0a4075b9ea9dedcaeae3fe32b` |

All six frozen files equal db145; all seven native files equal actual
mainDBB and remain unchanged.

| Actual read-only command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

Three complete destination/execution records do not establish independent
review, hosted/current-head/full-WP acceptance, physical/API37/process-death
evidence or bidirectional backup interoperability.
