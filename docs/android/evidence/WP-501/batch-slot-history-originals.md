# WP-501: complete original batch slot-history and post-staging rollback family

## Identity and complete source comparison

- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
- Actual execution parent: `88a06d157292218a436affeae626a19b92f98925`;
  actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Existing issue134/PR165; same stored attempt/session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation79->80.
- New test:
  `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/BatchSlotHistoryOriginalPredicateTest.kt`;
  final executed blob `5581e238eae4432e207a86a064da6703dce51ac2`.

Read all five complete slot-history bodies, seedSlotHistory/secret/channelInfo,
MessageDTO.testChannelMessage, device/store defaults, source channel operations
including the precise fault location, ReactionDTO initialization, full existing
native destinations and actual public store/channel/message/cascade/Room
transaction consumers. All six source/helper files in the byte table below
exactly match the frozen pin. Native production consumers equal actual main.

The existing compound native fixture changes secrets33/55 to03/05, seeds
outgoing rather than incoming histories, and replaces reaction raw text with
`reaction`. Its combined rename/replacement method does not independently
require the source rename name. Its BEFORE UPDATE fault fires at the first
configured upsert, before slot3 is wiped. Those methods remain unchanged;
neither their names/annotations nor their positive totals substitute for this
complete family.

## All five original bodies and parameter members

Every case uses a fresh radio UUID and real preseeded source test-device
fields: ID=radio, publicKey32x01, TestDevice, firmware8/v1.0.0,
TestMfg/01 Jan 2025, capacities100/8, multiAcks0, sync0 and active.
All other device defaults match the source helper. Date.now is fixed at
Unix1704067200; UUIDs remain fresh.

Every seedSlotHistory call creates the actual source SlotN/channel secret16x
the specified byte, three messages unless count1 is explicit, and increments
the real channel unread counter once per message. Messages have text
`msg 0`/`msg 1`/`msg 2`, **INCOMING/raw0, SENT/raw2**; the helper's status
is not changed to delivered. All channel-message nil/zero/false/plain fields,
sendCount1 and absent direct-contact identity are preserved. Each actual
reaction has fresh ID, the actual message ID, thumbs-up, sender`peer`,
hash`hN`, raw text`thumbs-up hN`, the same slot/radio, nil contact and fixed
receivedAt. Exact full message/reaction DTO readback is required before
the operation, not merely counts or fabricated missing-row checks.

| Complete frozen original | New native method | Complete source inputs and predicates |
| --- | --- | --- |
| `deleteChannel removes the slot's messages and reactions, sparing other slots()` | `deleteOriginalIncomingSlotAndReactionsSparesTheOtherOriginalSlot` | Seed3/33/count3 and5/55/count1; fetch doomed IDs with limit10; delete actual channel3 ID using required radio-scoped EntityKey; require no channel3/messages/reactions for every doomed ID and count1 at5. Full slot5 row/message/reaction remain unchanged. |
| `saveChannel with a new secret wipes the slot and resets derived counters()` | `replacementSecretClearsTheOriginalIncomingHistoryAndCounters` | Seed3/33/count3; save3/Replacement/99; require actual row/nameReplacement/unread0/lastMessageDate nil and empty messages limit10. Require exact unchanged identity/other row fields with new secret99, absent original message IDs and reactions. |
| `saveChannel with the same secret keeps history on rename()` | `sameSecretRenameKeepsAllThreeOriginalIncomingMessagesAndReactions` | Separate seed3/33/count3; save3/Renamed/33; require actual row/nameRenamed/unread3 and message count3 limit10. Exact original messages/reactions and remaining channel fields persist, not a combined replacement scenario. |
| `batchSaveChannels wipes secret-changed, unconfigured, and pruned slots and keeps the rest()` | `batchPreservesTheOriginalIncomingOccupantAndWipesAllThreeRemovalKinds` | Seed1/11,2/22,3/33,10/AA, each count3; actual no-channel4 leftover is **OUTGOING/raw1, SENT/raw2**, text`leftover`, matching the unoverridden source helper. Configure1/Renamed/11 and2/Taken over/99, unconfigure[3,4], prune8. Require count3/full history at1; messages empty at2,3,4,10 and actual row2 unread0. Also require exact row2 replacement, absent3/10 rows, erased tracked reactions/IDs and actual remaining sorted channel indices[1,2]. |
| `batchSaveChannels failed save restores the wiped messages with the rows()` | `actualPostStagingFailureRestoresBothOriginalRowsMessagesAndReactions` | Seed2/22 and3/33, each count3; configure2/Taken over/99, unconfigure[3], prune8. Real native SQL failure is conditional on both complete wipes and updated2/99/unread0/absent3 already being staged. Require typed SaveFailed, then original2 secret22/count3, required3/count3 and exact complete original rows/messages/all six reactions. |

The source seed never sets lastMessageDate, so its nil-reset predicate is
preserved honestly; this family does not invent a non-nil date seed or claim
that it proves every date-reset transition. Message order is not an original
predicate here: exact sets plus size and per-ID full readback avoid imposing
an incidental ordering on simultaneous fixed timestamps.

## Real post-staging native failure, not the old early-upsert proxy

The frozen Swift hook runs after all configured/unconfigured/pruned
mutations and before modelContext.save, inside the rollback catch.
For its actual inputs, slots2 and3 are the only channels; prune8 has no
eligible remaining rows.

The new fixture installs a test-only AFTER DELETE trigger on channels.
It can raise SQLite `RAISE(FAIL, 'test')` only when:

- the final removed row is slot3 and no slot3 row remains;
- both slots2/3 have no remaining messages or reactions;
- actual slot2 already has Taken over, all sixteen99 secret bytes and unread0.

Thus slot2 has been updated/wiped and slot3 has been wiped/deleted before
failure, the same actual staged mutation state as the source pre-save hook.
The subsequent empty prune loop has no additional target. An incomplete
earlier write/deletion cannot satisfy the trigger; without the expected
failure, assertFailsWith fails rather than silently accepting a no-op.
No fake algorithm, external transaction wrapper, product hook, direct
production-row rewrite or Room DAO stub is introduced.

RoomRepositoryContext rolls the real transaction back and reports the actual
SQLiteConstraintException as PersistenceStoreError.SaveFailed with operation
reason`batchSaveChannels`. The cause retains the literal`test` diagnostic;
the source hook's typed saveFailed("test") is not falsely asserted to have
an identical platform exception representation. Both original rows and all
six messages/reactions must then match their independent original fixtures.
The trigger is removed before those public fetches and in finally on failure.
RoomProcessTest closes the actual store/database and cancels its owned scope.

### Rejected first native fault adaptation, retained as failure

The initial batch on blob`847c316b699d94df7fba490339cd90a9629dd8ad`
discovered5 cases: **4 passed, 1 failed**, errors0/skips0. A test-only deferred
foreign-key violation reached native COMMIT failure, but the next public
fetch failed with FetchFailed / SQLiteException
`cannot start a transaction within a transaction`. This did **not** prove
successful original-state restoration and is not counted as a green suite
or diagnosed as a failure in the four positive originals.

That extra deferred-constraint/table/PRAGMA mechanism was removed completely.
The repair uses the existing native post-final-delete failure mechanism,
with explicit complete staged-state guards, corresponding to the source
hook's **before-COMMIT** position. No fault was moved back to the first
upsert, no original input/predicate was weakened and no production consumer
was changed to accommodate the fixture.

Only that repaired method was rerun. The other four bodies and all shared
seed/assertion helpers were unchanged, so their actual positive execution
is retained without gratuitous replay. Final accounting is **four unchanged
positive originals from the first batch plus one repaired original PASS**,
not a fabricated fresh5/5 run.

## Actual declared selector executions and lane release

Readiness `python android\scaffold\check_environment.py` **PASS** before both
invocations, with explicitly allowlisted/private JDK21/SDK37.2/build-tools37
environment. No dependency/tool installation or native/device action.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.BatchSlotHistoryOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

First batch **FAIL5 discovered/4 passed/1 failed/0 errors/0 skips**.
Started`2026-10-21T02:22:49.997468Z`, finished`02:25:04.538154Z`;
fresh XML`02:24:57.508Z`,5.235s. Exact first four positive identities are
the four non-rollback methods in the table. The failed initial method was
named`actualLateCommitFailureRestoresBothOriginalRowsMessagesAndReactions`.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests',
    'com.meshcoreone.android.parity.BatchSlotHistoryOriginalPredicateTest.actualPostStagingFailureRestoresBothOriginalRowsMessagesAndReactions',
    '--dependency-verification=strict', '--quiet'
  )
```

Repaired original **PASS1/1, zero failures/errors/skips**.
Started`2026-10-21T02:27:06.337787Z`, finished`02:29:22.163950Z`;
fresh XML`02:29:16.013Z`, suite4.470s, exact method3.898s.
Final executed source5581e238 unchanged before/after this execution.
The current XML contains this one selected method, not all five:
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.BatchSlotHistoryOriginalPredicateTest.xml`.

Both invocations used the same stored owner's actual exclusive Gradle lock
at revision80, suffixes
`batch-slot-history-originals:62427f8f9946474f8806967de92ea918` and
`batch-slot-history-repair:bcd401fd08484d0dbe793f6f3994f2d9`.
Each payload bound actual88/dbb/source/selector/977b/e8afe. Both owned
contexts released; final actual records empty. No foreign release,
old contact/channel/incoming/retention replay, native/provider gate,
new actor, hosted cancellation/rerun or publication over healthy88.

Primary local logs under session27f3c368 files:
`wp501-batch-slot-history-originals-62427f8f9946474f8806967de92ea918.log`;
`wp501-batch-slot-history-repair-bcd401fd08484d0dbe793f6f3994f2d9.log`.
These are actual local execution logs, not a second hosted CI-result bundle.

## Frozen/native bytes and limits

| Read source/consumer | Git blob |
| --- | --- |
| Frozen PersistenceStoreBatchSyncTests.swift | `2fb5ad79f4c0009c9d879fe6b1dc7054fdef0798` |
| Frozen PersistenceStore+Testing.swift | `2ea8fd27ce96ce3f6cd600df59472d205d8256ad` |
| Frozen DeviceDTO+Testing.swift | `ffbeca180d8cbb1833d6cea4984b7fbddd194d69` |
| Frozen MessageDTO+Testing.swift | `9428f0c258f381d24720e61398fcff1418e07acd` |
| Frozen PersistenceStore+Channels.swift | `f08da95f74ce0950a4dfdb70d339d8a16b26acf7` |
| Frozen Reaction.swift | `fed9026236e4cac1d37fd4491cb9f6c80303467c` |
| Native ChannelRepository.kt | `27d187f0f248774ebe673ab8260b88d5734c6c94` |
| Native RepositoryCascades.kt | `0cf0462fec5c2a33ee2ba330c00db4bcb134d47f` |
| Native RoomRepositoryContext.kt | `85d5593ac6f45783acbefc5efeabb58a24f111d4` |
| Native ContainerHarness.kt/RoomProcessTest | `c6fb962fca19d1ff87c48eaa05868514a08d614a` |

| Actual read-only command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

The earlier [seven reconciliation originals](batch-channel-reconciliation-originals.md)
and [four contact originals](batch-contact-originals.md) retain separate
actual results and existing empty-body witnesses. This completes explicit
destinations for the16 bodies:14 new methods and two reused empty phases,
not16 fresh executions, current-head hosted or whole-WP acceptance.
This is API31 native-SQLite in-memory Room evidence, not physical process
death/API37/hardware/Swift execution/backup interoperability certification.
Independent full-source comparison and any later exact publication remain
separate; healthy published88/CI39531691829 was preserved.
