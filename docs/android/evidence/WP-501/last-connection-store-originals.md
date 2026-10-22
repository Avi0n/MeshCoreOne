# WP-501: original last-connection cases through the real App preference adapter

## Identity and actually missing consumer boundary

- Frozen reference: `db14559b39d32322b06477c6ae676112f583db50`.
- Actual execution parent: `3595951bbee65ed3a9b0b327f8929a38e57cbdfb`.
- Actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Existing issue134/PR165, same stored attempt/session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation83->84.
- New test:
  `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/LastConnectionStoreOriginalPredicateTest.kt`;
  executed blob `a119928e149de530f15803fa30e9e19f61d211b2`.

Read all nine complete frozen LastConnectionStoreTests bodies and their
isolated-defaults helper, full source LastConnectionStore, native
SourceRuntimeValuesTest bodies, full ConnectionRuntimePreferenceIntegrationTest
and its private adapter, actual production LastConnectionStore and
DataStoreConnectionPreferences, MeshCoreStorage lifetime and PreferenceStore.

The nine existing runtime originals use in-memory TestPreferences.
The separate datastore integration uses its own test-written PreferenceAdapter,
including fractional-seconds date encoding; that is not the actual App
adapter. Existing App DataStoreAdaptersTest exercises primitive value-kind
roundtrip/removal, not the complete original holder/slot/format predicates.
Therefore the real **LastConnectionStore -> App DataStoreConnectionPreferences
-> PreferenceStore/file DataStore** boundary for this complete nine-original
family is genuinely unrepresented. It is not a finding that all earlier
runtime or integration tests were useless, and they are not replayed.

## All nine complete source bodies and new destinations

| Frozen original | New method | Full original input/action/output predicates |
| --- | --- | --- |
| `persist then read round-trips deviceID, radioID, and deviceName()` | `originalTestRadioRoundTripUsesActualStoredKeysAndReopen` | Fresh separate device/radio, literal Test Radio, real persist, actual device/radio equality and raw persisted name exactly Test Radio. Also require both canonical stored UUID strings, close process store/open same file/new real adapter and repeat all three reads. |
| `clear removes all three persisted values for the holder()` | `originalHolderClearRemovesAllThreeActualPersistedValues` | Persist fresh device/radio/Test Radio, require actual seed, clear holder, independent nil device/radio/raw stored name. Also require both raw UUID keys absent. |
| `clear for a non-holder leaves last-connection keys intact()` | `originalNonHolderClearPreservesHolderRadioAndName` | Persist fresh holder/radio/Holder Radio, clear different fresh ID, require holder retained and exact raw name Holder Radio. Full pre/post preference snapshot equality and radio equality supplement source predicates. |
| `empty defaults read as nil()` | `originalEmptyDefaultsExposeThreeIndependentNilReads` | Real initially empty store, three separate nil reads for device/radio/diagnostic, still no persisted keys. |
| `malformed UUID strings read as nil()` | `originalMalformedUuidStringsStayStoredButReadAsNil` | Direct actual PreferenceStore writes exact not-a-uuid and also-not-a-uuid under original two keys; both runtime UUID projections nil. Both raw strings must remain stored, not silently removed/replaced. |
| `bond verification round-trips for the stamped device and is nil for others()` | `originalBondStampHasBothSourceTimeBoundsAndNoOtherHolder` | Fresh device initially has nil stamp, capture before, actual persistBondVerification, require nonnil stamp with original stamp>=before and stamp<=after; different fresh ID nil. Also exact clock equality, actual raw canonical slot ID and integer epoch-millisecond date. |
| `a later bond verification for another device replaces the slot()` | `originalSecondBondVerificationReplacesTheActualSingleSlot` | Two fresh device IDs, actual first stamp required, persist second; first nil/second nonnil, actual raw slot now second. No extra last-connection write. |
| `clear removes the bond verification only for the bond-slot holder()` | `originalBondHolderAndNonHolderClearRemainIndependent` | Holder stamp actually present; clear different ID -> holder stamp remains; clear holder -> nil, both actual raw bond keys absent. Last-connection slot remains empty as in source. |
| `persistDisconnectDiagnostic prefixes a parseable ISO8601 timestamp()` | `originalDisconnectDiagnosticHasParseablePrefixAndExactSuffix` | Exact summary source=unitTest, reason=verifyFormat; require real stored diagnostic/first space, parse prefix using Instant.parse without catch/fallback and require exact suffix after first space. Also exact injected clock and persisted raw diagnostic equality. |

Each body requires a distinct initially empty store. No combined-phase
name substitution, optional seed, in-memory preference role or caller-side
algorithm can substitute for these original actual stored values.
Nine original destinations execute as nine new methods, not a sum with
old nine factory cases or existing App/datastore integration totals.

## Native storage/clock adaptation and actual wiring

The source creates and removes a unique UserDefaults suite per case.
The new fixture similarly provides a unique TemporaryFolder/noBackupFilesDir
through a ContextWrapper whose applicationContext is itself, then calls
unchanged public MeshCoreStorage.get. No store logic is implemented by
the wrapper: actual PreferenceDataStoreFactory, serializer, PreferenceStore,
App adapter and runtime class do all reads/atomic edits.
Every setup requires actual storedValues empty. Teardown awaits process
store close/cancellation before TemporaryFolder removes its own files.
Roundtrip explicitly reopens that same owned directory after close;
this is not operating-system process-death evidence.

AndroidAppContainerFactory supplies the same production
DataStoreConnectionPreferences(storage.preferences) to AppContainer.
The new cases invoke its actual public runtime consumer, not the distinct
private test-written fractional-seconds adapter.
Native canonical UUID strings preserve the source UUID.uuidString policy.
Coroutine APIs are awaited in runBlocking rather than returning before edits.

Actual App adapter explicitly documents **epoch-millisecond** encoding for
these non-backup runtime bond-date keys. Use fixed RuntimeClock instant
Unix1704067200.125, exactly representable at that native boundary, rather
than manufacturing nanosecond precision. Both source before/after ordering
predicates remain and the actual raw millisecond integer is asserted.
This deterministic ambient-clock substitution is not proof that arbitrary
sub-millisecond Foundation Date values roundtrip losslessly, a clock-jump
test, a backup-date adaptation or actual BLE bond verification.
Diagnostic prefix remains actual ISO8601/Instant-parseable and exact suffix;
no exact spelling requirement absent from source is invented.

## Exact newly affected declared execution

Readiness `python android\scaffold\check_environment.py` **PASS**;
established allowlisted JDK21/private caches/installed SDK, no install.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.LastConnectionStoreOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS9/9, zero failures/errors/skips**, all nine exact new method
identities in the table. Started `2026-10-21T03:04:52.627019Z`,
finished `03:06:45.143360Z`; fresh XML timestamp `03:06:41.415Z`,
suite2.469s. Actual359/baseDBB, sourcea119928e unchanged before/after.
XML:
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.LastConnectionStoreOriginalPredicateTest.xml`.
Primary local log under session27f3c368 files:
`wp501-last-connection-originals-5a9fa68bef5f4b6e8991ca9eeab03e5f.log`.

Actual shared locks empty before acquisition; same stored owner acquired
and released exclusive Gradle suffix
`last-connection-originals:5a9fa68bef5f4b6e8991ca9eeab03e5f`,
reservation84/exact359/dbb/a119/selector/977b/e8afe payload.
Final actual records empty. No earlier runtime/datastore/App/source-family
positive replay, product mutation, foreign release, native/device/packaging
action, new actor or automatic CI cancellation/manual rerun.
Published88/39531691829 terminalSUCCESS remains88-only.

## Complete original/consumer identities and limits

| File | Git blob |
| --- | --- |
| Frozen Connection/LastConnectionStoreTests.swift | `d9c2addebcf0631a0fc92f25746105f9c42f0291` |
| Frozen Connection/LastConnectionStore.swift | `bea3fefb58a5c5a4151bdc3e349665261ec308ea` |
| Existing SourceRuntimeValuesTest.kt | `3e1f42a75f82035943cb72c6dd833293a8c0ccb5` |
| Existing ConnectionRuntimePreferenceIntegrationTest.kt | `0e303cb8f31a48907f7e32510a3e5d74d18d4ae0` |
| Actual runtime LastConnectionStore.kt | `8087deb8ea05570348bdfdc9cbb1f375178ea03c` |
| Actual App DataStoreConnectionPreferences.kt | `9d0bb5a2cb0296ce73527ab531736a3c36aa078a` |
| Actual MeshCoreStorage.kt | `67280841f866474069e79f1a874248260cb44914` |
| Actual PreferenceStore.kt | `79a6bcd8e3f70e5d82964839124b2ab306c60189` |
| Existing App DataStoreAdaptersTest.kt | `4f0dc0cb844c97e67db71a991bbe6922c433271f` |

Both frozen files equal db145; all seven native files equal actual mainDBB
and remain unchanged.

| Actual read-only command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

API31 Robolectric/file-backed DataStore is not API37/device/physical bond,
full application process death, UI restoration, KeyStore cryptography,
backup interoperability, independent/current-head hosted/full-WP acceptance.
