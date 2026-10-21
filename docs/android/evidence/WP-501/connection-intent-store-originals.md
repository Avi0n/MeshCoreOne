# WP-501: original connection-intent persistence through real App DataStore

## Identity and full-family investigation

- Frozen reference: `db14559b39d32322b06477c6ae676112f583db50`.
- Actual execution parent: `14a42a7949821cb8bb1b391c996aa956f7afb964`.
- Actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Existing issue134/PR165, same stored attempt/session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation84->85.
- New test:
  `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/ConnectionIntentStoreOriginalPredicateTest.kt`;
  executed blob `1a96b926b0b2733e4153b525b96b43414e48a95f`.

Read all fourteen complete frozen ConnectionIntentTests.swift bodies,
the per-suite isolated UserDefaults constructor, full source enum and
persistence methods, all fourteen native SourceRuntimeValuesTest
destinations, existing real-data integration and its private adapter,
actual native enum/runtime store/App adapter/preference-store lifetime.

Ten pure-value originals already have complete native witnesses below.
Their input/assertion bodies are reused without execution.
Four persistence originals previously ran through TestPreferences, not
the actual App adapter/file-backed DataStore. Existing separate storage
integration uses a private test-written PreferenceAdapter and
WantsConnection(true), not original default WantsConnection(false);
it does not supply all four original ordered assertions, particularly
the required restored-UserDisconnected predicate before clearing.
These four complete real-consumer witnesses are the bounded new family,
not fourteen new tests or four additional original IDs.

## All four persistence originals and actual new methods

| Complete original in ConnectionIntentPersistenceTests | New method | Inputs/actions and required original predicates |
| --- | --- | --- |
| `userDisconnected persists and restores()` | `originalUserDisconnectedPersistsAndRestoresThroughActualAppAdapter` | Initially empty actual store; persist UserDisconnected, restored==UserDisconnected. Also require actual raw boolean true, close/open same owned file/new actual App adapter and require restored and raw true again. |
| `none clears persisted userDisconnected()` | `originalNoneClearRequiresThePreviouslyRestoredUserDisconnected` | Persist UserDisconnected; require original restored==UserDisconnected and actual raw true BEFORE persisting None; then restored==None and actual key absent, not persisted false/default. |
| `wantsConnection clears persisted userDisconnected()` | `originalDefaultWantsConnectionClearsThePreviouslyRestoredUserDisconnected` | Persist UserDisconnected; require original restored==UserDisconnected/raw true. Construct actual default WantsConnection(), require forceFullSyncfalse, persist it; restored==None/key absent. No forced-true input substitution. |
| `restored returns .none when nothing persisted()` | `originalUnpersistedIntentRestoresNoneWithoutWritingADefault` | Initially empty actual file store, restored==None and no keys written. |

All operations are the unchanged production LastConnectionStore ->
DataStoreConnectionPreferences -> PreferenceStore/file DataStore, not
an independent test-written persistence algorithm. Required affirmative
pre-clear state prevents a no-op initial save plus default None from
falsely satisfying either original clearing case.

## Ten complete pure-value originals reused without replay

Existing SourceRuntimeValuesTest.sourceCases has one complete matching
native body for each exact source name in this table. No new execution
or current-head source approval is inferred from those old destinations.

| Complete source name in ConnectionIntentTests | Matching native predicates |
| --- | --- |
| `wantsConnection returns true for .wantsConnection()` | Default WantsConnection().wantsConnectiontrue. |
| `wantsConnection returns true for .wantsConnection(forceFullSync: true)()` | Forced true variant wantsConnectiontrue. |
| `wantsConnection returns false for .none()` | None.wantsConnectionfalse. |
| `wantsConnection returns false for .userDisconnected()` | UserDisconnected.wantsConnectionfalse. |
| `isUserDisconnected returns true only for .userDisconnected()` | UserDisconnected persisted-user flagtrue, None/default WantsConnectionfalse. |
| `wantsConnection default is equatable()` | Default equals explicit false. |
| `wantsConnection with different forceFullSync are not equal()` | True differs from false. |
| `wantsConnection replaces shouldBeConnected = true()` | Default intent wantsConnectiontrue/persisted-user flagfalse. |
| `userDisconnected replaces setUserDisconnected + shouldBeConnected = false()` | UserDisconnected wantsConnectionfalse/persisted-user flagtrue. |
| `forceFullSync can be consumed and reset()` | Initial typed WantsConnection(true).forceFullSynctrue, assign default WantsConnection(), forceFullSyncfalse. Native static subtype guarantees source's final case check rather than allowing another enum case silently. |

Native persistedUserDisconnected names the source isUserDisconnected
property. It is not a second stored flag. Both implementations persist
only UserDisconnected=true; None and either WantsConnection variant
remove the same preference key. Native suspension awaits atomic edits.

## Actual storage lifetime and limits

As in the adjacent nine-case
[last-connection evidence](last-connection-store-originals.md),
each test uses a unique TemporaryFolder/noBackupFilesDir and a
ContextWrapper selecting only that directory/applicationContext.
Unchanged public MeshCoreStorage.get creates and owns the actual
DataStore serializers/edits. Setup requires storedValues empty;
teardown awaits close before owned folder cleanup.
This adapts the source's isolated UserDefaults suite, without sharing
state with another test or substituting a memory preference role.
The first case additionally reopens the same file after actual close.

SystemRuntimeClock is supplied because the public runtime store requires
one, but neither source nor native intent persistence reads that clock.
No invented date input, wall-time assertion, retry/connect operation,
radio/native/device action or product test hook is introduced.
This tests the actual preference consumer, not a manager auto-reconnect
flow, app restart, OS process death or a native protected-storage upgrade.

## Exact newly affected declared execution

Readiness `python android\scaffold\check_environment.py` **PASS**;
established allowlisted JDK21/private caches/installed SDK, no install.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.ConnectionIntentStoreOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS4/4, zero failures/errors/skips**, all four exact new method
identities in the first table. Started `2026-10-21T03:12:55.095548Z`,
finished `03:14:48.171470Z`; fresh XML timestamp `03:14:44.149Z`,
suite2.222s. Actual14a/baseDBB, source1a96b926 unchanged before/after.
XML:
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.ConnectionIntentStoreOriginalPredicateTest.xml`.
Primary local log under session27f3c368 files:
`wp501-connection-intent-originals-5bb4df2ea0ce4c4d9430be2d81365ee9.log`.

Actual shared locks empty before acquisition; same stored owner acquired
and released exclusive Gradle suffix
`connection-intent-originals:5bb4df2ea0ce4c4d9430be2d81365ee9`,
reservation85/exact14a/dbb/1a96/selector/977b/e8afe payload.
Final records empty. No ten pure-value originals, prior nine last-connection
cases, other old positive cohort or actual native/device work replay.
No foreign release/new actor/CI cancellation/manual rerun.
Published88/39531691829 terminalSUCCESS remains88-only.

## Actual source/consumer byte bindings

| File | Git blob |
| --- | --- |
| Frozen ConnectionIntentTests.swift | `44d288015c15f79b0dbaea48ee29823ff9995b2f` |
| Frozen Connection/ConnectionIntent.swift | `a359ec0e3ef56021d40e3ee5e158baedfb9de0ff` |
| Native ConnectionContracts.kt | `eaac312ed47f2a6218e826549b5f5a275a0c8edb` |
| Native runtime LastConnectionStore.kt | `8087deb8ea05570348bdfdc9cbb1f375178ea03c` |
| Existing SourceRuntimeValuesTest.kt | `3e1f42a75f82035943cb72c6dd833293a8c0ccb5` |
| Actual App DataStoreConnectionPreferences.kt | `9d0bb5a2cb0296ce73527ab531736a3c36aa078a` |
| Existing ConnectionRuntimePreferenceIntegrationTest.kt | `0e303cb8f31a48907f7e32510a3e5d74d18d4ae0` |
| Actual MeshCoreStorage.kt | `67280841f866474069e79f1a874248260cb44914` |
| Actual PreferenceStore.kt | `79a6bcd8e3f70e5d82964839124b2ab306c60189` |

Both frozen inputs equal db145; all seven native files equal actual
mainDBB and remain unchanged.

| Actual read-only command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

Four new real-consumer executions plus ten reused complete pure-value
destinations do not constitute fourteen new runs, independent review,
API37/device/physical/backup/current-head hosted or whole-WP acceptance.
