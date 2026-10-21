# WP-501: complete blocked-channel-sender store original inputs

## Identity and bounded gap

- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
- Actual execution parent: `7f5871d833652a0a3bec996abfe219dc7a7b9cca`;
  actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Existing issue134/PR165, same stored attempt/session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation81->82.
- New test:
  `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/BlockedSenderStoreOriginalPredicateTest.kt`;
  executed source `61651bf5ea1bdfa2cf5bb8fd23a02840edd70438`.

Read all eight complete frozen BlockedChannelSenderPersistenceTests bodies,
createTestStore, DTO/model constructors, source save/delete/fetch, complete
existing BlockedSenderPersistenceTest destinations, native production
ContactRepository and exact-name/radio/date-ordered DAO operations.
This family concerns stored block entries, not the separate six incoming
message/reaction-deletion originals already recorded elsewhere.

Six original input/predicate variants are genuinely missing despite existing
compound annotations: Date.distantPast versus ten seconds earlier; BadGuy
versus Alice deletion; exactly one Alice/one Bob versus an extra ALICE on
radioA; first/only Alice versus any Alice after Spammer; separate distantPast/
now case entries versus equal dates; literal million-second sort dates versus
nanosecond offsets. Preserve the existing useful stronger/different native
tests; do not replace or replay them to claim the original vectors.

## All eight complete original bodies and destinations

Each source store is initially empty without a device row. The new actual
Room fixture preserves that. Each test radio and DTO ID is fresh; second
radio and duplicate-name DTOs have separate fresh IDs.
Incidental Date.now is fixed at Unix1704067200 for determinism, not sampled
from a clock or inferred from candidate output. No source predicate depends
on the wall-clock instant or canonical UUID text.

| Full frozen original | Actual destination | Inputs and complete source predicate |
| --- | --- | --- |
| `Save and fetch round-trip returns the blocked sender()` | Existing `roundTripPreservesNameCaseRadioAndIdentity`, first phase only | Save sole Spammer, full single DTO equality implies original count1/nameSpammer/radio. Its later Alice phase does not justify standalone Alice equivalence. Existing fixed radio/date are explicit incidental substitutions; reused without execution. |
| `Re-saving same name updates dateBlocked instead of creating duplicate()` | New `originalDistantPastUpsertChangesTheDateWithoutDuplicatingTroll` | Save Troll at actual Swift Date.distantPast; save separate-ID Troll at fixed now. Actual initial full DTO required; result count1/date==later/full original ID with only date changed. |
| `Delete removes the blocked sender entry()` | New `originalBadGuyDeletionRequiresItsActualSeedAndEmptyResult` | Save exact BadGuy, require actual full seed, delete exact BadGuy, require empty fetch. |
| `Fetch returns only senders blocked for the specified device()` | New `originalRadioPartitionRequiresExactlyOneAliceAndOneBob` | Exactly Alice at fresh radioA and Bob at fresh radioB, no extra case variant. Each actual partition count1/first original name and complete original DTO. |
| `Name preserves original casing for display()` | New `originalStandaloneAliceIsTheFirstDisplayNameNotMerelyAnyMatch` | Sole Alice in an otherwise empty store; first name exactly Alice and sole full original DTO, not an any-match predicate. |
| `Saving same name with different case creates separate entries()` | New `originalDifferentCaseEntriesKeepBothDistantPastAndCurrentDtos` | Alice at distantPast, ALICE at fixed now; require actual first seed, result count2 and both exact DTOs. No normalization/case-folding or invented tie-order assertion. |
| `Delete requires exact case match()` | Existing `deleteRequiresExactCaseAndCommits`, first phase only | Save Alice, delete ALICE, require exactly original Alice DTO. This implies count1/nameAlice. Existing fixed radio/date are explicit incidental substitutions; reused without execution. Later exact-case deletion is stronger extra coverage, not the source BadGuy fixture. |
| `Fetch returns senders sorted by most recently blocked first()` | New `originalMillionSecondDatesSortSecondThirdFirst` | Insert first/Unix1000000, second/Unix3000000, third/Unix2000000 in that order. Require exactly names[second,third,first] and all three full original DTOs in the same order. |

Full seed guards and DTO equality prevent missing inserts, fabricated empty
results, changed identity/radio/name/date, extra rows or reduced projections
from satisfying the originals. Six new method executions plus two reused
complete body phases are eight destinations, not eight new executions.

## Independent negative date and actual consumer

Use the already-admitted independent first-party facts in
`docs/android/evidence/WP-202/independent-vector-provenance.json`:
Swift value-type Foundation.Date at immutable
`a1c611f6ecc0164b5a489f23013e5c637e9e5cd1`
(`swift-6.2-RELEASE`, Sources/FoundationEssentials/Date.swift) defines
distantPast relative seconds -63114076800 with Unix offset978307200,
therefore Unix **-62135769600**. The differing NSDate class constant is
explicitly not substituted. These published independent source facts
are not an executed Apple/macOS runtime oracle or a new backup golden.

New methods call unchanged public RoomPersistenceStore save/delete/fetch
through real ContactRepository and native SQLite at Robolectric API31.
Production save matches exact name and radio; duplicate saves update only
date, retaining stored ID. Delete matches exact case and radio; fetch sorts
stored Unix seconds then nanos descending and maps actual full DTOs.
This exercises negative-date storage, not a candidate-generated expected
date or fake repository. No fake persistence, direct DAO seed, product
test hook, device row, caller-side sorting or production mutation.
RoomProcessTest owns one real in-memory database and process store per
case; teardown closes the store, cancels its scope and closes the database.

## Exact newly affected declared execution

Readiness `python android\scaffold\check_environment.py` **PASS**.
Used established allowlisted JDK21/private caches/installed SDK and wrapper;
no dependency/tool installation.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.BlockedSenderStoreOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS6/6, zero failures/errors/skips**, all six exact new method identities
in the table. Started `2026-10-21T02:48:52.699715Z`,
finished `02:50:59.418689Z`; fresh XML timestamp `02:50:53.453Z`,
suite4.262s. Source61651bf5 unchanged before/after, actualparent7f/baseDBB,
not an execution at published88 or a combined run with slot-history/window.
XML:
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.BlockedSenderStoreOriginalPredicateTest.xml`.
Primary local log under session27f3c368 files:
`wp501-blocked-sender-store-originals-5ac0930b041d4ba2a8f9eccb3730b7d2.log`.

Actual shared locks empty before acquisition. Same stored owner acquired
the existing exclusive Gradle lane suffix
`blocked-sender-store-originals:5ac0930b041d4ba2a8f9eccb3730b7d2`,
revision82, exact7f/dbb/61651/selector/977b/e8afe payload.
Owned context released normally and final records empty.
No older successful cohort, two existing phases, slot-history repair,
window, incoming message-deletion or pending-send replay.
No native/device/packaging action, foreign release, new actor or CI rerun.
Published88/39531691829 terminalSUCCESS remains bound only to88.

## Source bytes, checks and limits

| Complete read source or actual consumer | Git blob |
| --- | --- |
| Frozen BlockedChannelSenderPersistenceTests.swift | `cf50af2b9f16aaf572a39214ab133b579a417134` |
| Frozen Models/BlockedChannelSender.swift | `cbc20e9432872700359d5a7274e50638a721f816` |
| Frozen Services/PersistenceStore+Channels.swift | `f08da95f74ce0950a4dfdb70d339d8a16b26acf7` |
| Existing BlockedSenderPersistenceTest.kt | `8a49c34a4999a0ae37f3921a1dc8c6cd1c1d613a` |
| Production ContactRepository.kt | `8da5513b403198a44f673addf443c5411b58a80c` |
| Production RadioDaos.kt | `af37fd8c74d3a2ec515eba23eb39b9afef8017ac` |
| Actual ContainerHarness.kt/RoomProcessTest | `c6fb962fca19d1ff87c48eaa05868514a08d614a` |
| Existing independent-vector-provenance.json | `6b07bec6af78f89ff97688bb22545b0f66fad497` |

All three frozen inputs equal db145; all five native/provenance files
equal actual mainDBB and remain unchanged.

| Actual read-only command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

This is complete original input/destination accounting with six actual
new executions, not independent acceptance, current-head hosted success,
full-WP acceptance, API37/device/process-death/physical evidence or new
bidirectional backup interoperability proof.
