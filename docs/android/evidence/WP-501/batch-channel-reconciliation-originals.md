# WP-501: complete original batch channel reconciliation inputs and fetches

## Identity and bounded family

- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
- Actual execution parent: `fec5493836b7acdec99bac7a03d640a66f0374f4`;
  actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
  trusted UTF-8 semantic policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Same owner/attempt/stored session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation76->77 new owned
  paths,77->78 actual current-main integration; existing issue134/PR165.
- New test:
  `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/BatchChannelReconciliationOriginalPredicateTest.kt`,
  executed blob `e1eb5e6db8b9b68744eb5fa68ebb75cd5ba1b75c`.

Read all16 complete frozen `PersistenceStoreBatchSyncTests` bodies and
their helpers, the full existing native destinations, source device/store
helpers and actual native channel/delegation consumers. This increment is
the coherent first **seven channel reconciliation originals**, not the
later five history/rollback or four contact originals. Six require new
full source-vector/persisted-consumer witnesses; the complete empty/no-work
predicate already has an existing native witness and is not replayed.
Six runner cases are not claimed as seven executions or full-file acceptance.

Existing native upsert/non-contiguous methods inspect returned DTO fields
where the source independently fetches actual stored rows. Its combined
prune method deletes slot2 and keeps slot3 rather than preserving both
source skipped slots2/3; its stale/capacity vectors also change names and
secret bytes. The other-radio method uses slot10/secret10 and explicitly
unconfigures10, unlike the source's same-slot1/secret55 partition witness.
Those unchanged compound tests remain useful but are not these complete
original inputs and fetch predicates.

## Complete original-case/parameter accounting

Every source call below uses fresh radio UUIDs, pruneBeyond8 and a real
test store preseeded with the original device helper. Channel secrets are
16 repetitions of the indicated literal byte, including00/99/AA high-bit
vectors;16 is pinned `ProtocolLimits.channelSecretSize`, not an expected
secret derived from candidate code. Configured and unconfigured lists,
names, order and every independently fetched source row are retained.

| Complete frozen original | Native destination | Full inputs and output predicates |
| --- | --- | --- |
| `batchSaveChannels inserts new and updates existing in one pass()` | New `batchUpsertRequiresOriginalUpdatedAndInsertedPersistedRows` | Seed slot1/Old/11; configured1/New/99 and2/Two/22, unconfigured empty; returned indices exactly[1,2]; independently required stored1 nameNew/secret99 and stored2 nameTwo. |
| `batchSaveChannels deletes stale rows at unconfigured indices()` | New `unconfiguredOriginalDeletesOnlyConfirmedStaleSlot` | Seed1/Keep/11 and2/Gone/22; configure1/Keep/11, unconfigure[2]; returned[1], actual slot2 absent. |
| `batchSaveChannels prunes orphans beyond device capacity()` | New `capacityOriginalPrunesSlotTenAndPreservesInRange` | Seed1/InRange/11 and10/Orphan/AA; configure1/InRange/11, unconfigured empty; returned[1], actual10 absent. |
| `batchSaveChannels leaves circuit-breaker-skipped slots untouched()` | New `circuitBreakerOriginalKeepsBothUnqueriedSlotsTwoAndThree` | Seed1/One/11,2/Two/22,3/Three/33; configure only1/OneUpdated/11, unconfigured empty; returned[1,2,3], actual namesOneUpdated/Two/Three at1/2/3. Neither skipped slot is reported empty. |
| `batchSaveChannels handles non-contiguous configured indices()` | New `nonContiguousOriginalUsesAllSourceSlotsSecretsAndRequiredFetch` | Initially no channels; configure0/Public/00,2/Two/22,7/Seven/77, unconfigure[1,3,4,5,6]; returned[0,2,7], independently fetched7 nameSeven. |
| `batchSaveChannels on empty store with no work returns empty()` | Existing `BatchSyncPersistenceTest.emptyAndNonContiguousPassesHaveExactSortedResults`, initial call only | Empty channels, configured/unconfigured empty, prune8; actual public batch result is empty before later unrelated setup. Existing real native Room fixture omits the source helper's device, but both production batch implementations use explicit prune8 and never read a device for this no-work predicate. Reused body comparison, not a new run/claim of literal device-helper identity. |
| `batchSaveChannels scopes to radioID()` | New `radioScopeOriginalRetainsOtherRadioAtSameSlotOne` | Seed fresh radioB slot1/OtherRadio/55; selected radioA configure1/Mine/11, unconfigured empty; independent actualB slot1 remainsOtherRadio. No other-radio device parent is invented. |

Additional guards require every seeded row before the batch and every
kept/inserted row afterward with exact radio/index/name/secret. Result
index lists also require the actual selected radio. Thus a missing seed,
wrong source secret, returned-only DTO or wrong-radio survivor cannot fake
the original predicates. These are stronger guards within the same six
cases, not extra original counts.

## Real consumer and helper adaptation

The unchanged API31/native-SQLite RoomProcessTest owns the real in-memory
database, production RoomPersistenceStore and teardown. Native public
saveChannel/batchSaveChannels/fetchChannel delegate to ChannelRepository:
real per-radio DAO fetches, staged upserts, explicit unconfigured/capacity
deletion, production save transaction and ordered final DAO fetch.
The tests do not implement reconciliation, manipulate the DAO or replace
the transaction with a fake repository.

Each new case preserves the source createTestDataStore helper: device ID
equals the fresh radio UUID; public key32x01; TestDevice; firmware8/v1.0.0;
TestMfg/01 Jan 2025; maxContacts100/maxChannels8; multiAcks0;
lastContactSync0/isActivetrue. Remaining shared native defaults match the
source frequency915000/bandwidth250000/SF10/CR5/powers20/coords0/PIN0,
manual-addfalse, telemetry2/0/0 and advert policy0/nil OCV fields.
The actual saved/fetched device DTO must equal the input before testing.
Only incidental lastConnected Date.now is fixed at Unix1704067200.
Fresh source UUIDs and exact channel secrets remain unchanged.

## Actual current-main integration and policy metadata

After835 publication, the coordinator serialized actual259/main deployment.
No new Gradle/native/push operation was started during that short landing
hold. On verified actualmain
`dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`, producer
`475ffefabbdd7e234ca5b7bb21dbd1631d7d7800`, the same reservation evolved78
and mergefec54938 preserved **all six incoming blobs exactly**:
automation policy, CI README, WP003 deviation, scope_amendment.py,
test_capabilities.py and test_scope_amendment.py. All152 existing
candidate-only paths, entire prior Android tree and the in-flight new test
blob remained unchanged. No candidate ownership/policy edit was authored.

Trusted `schema.load_json` plus `gates.policy_revision` and immutable Git JSON
both yielde8afe atdbb. The earlier new incoming-original evidence's50da
claim is explicitly corrected in that evidence/deviation: default Windows
Path.read_text decoded the policy's U+2014 as CP1252 mojibake before hashing.
Exactc9ed/835 bytes actually yield7f757. The analogous default-decoded
dbb diagnostic yieldsa657 and was rejected; it is not a real policy revision.
Only metadata is corrected, not historical receipts, source/policy files,
old execution or acceptance. This selected execution uses the real UTF-8
current policy; prior835 incoming6/6155 unsigned2/379-controller results
retain their actual older parents/bases and are not replayed or transferred.

## Exact newly affected execution

`python android\scaffold\check_environment.py` **PASS** with the explicit
allowlist/private caches/JDK21.0.12.1/compile37.2/build-tools37.0.0.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.BatchChannelReconciliationOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS6/6**, zero failures/errors/skips, all six exact new identities.
Started `2026-10-21T01:35:33.690381Z`, completed `01:42:02.966509Z`;
fresh suite timestamp `01:41:54.726Z`, execution6.136s.
Sourcee1eb5e6d unchanged across execution; actual parentfec54938/basedbb,
not older835/c9ed or a combined seven-case run.
XML:
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.BatchChannelReconciliationOriginalPredicateTest.xml`.
Local primary log:
`C:\Users\camer\.copilot\session-state\27f3c368-787a-4cad-8050-8c7c57b25eb4\files\wp501-batch-channel-originals-4a220009b24949b8b0bb157af38c732b.log`.

Observed actual records empty and used the existing exclusive Gradle lane
at revision78, this stored owner suffix
`batch-channel-originals:4a220009b24949b8b0bb157af38c732b`, exact selector/
source/parent/base/current-policy payload. Final actual records empty.
No device, native lock, packaging, foreign release, original-cohort replay,
deadline increase or manual hosted cancellation/rerun.

The incoming admission's documented focused command, with the repository
tools/tests import roots:

```powershell
python -m unittest test_capabilities test_scope_amendment test_domain_room_evidence -q
```

**PASS72/72**, zero failures/errors/skips,154.192s on committed760f951e
(same executed Android bytes), validates the current composite's newly
inherited capability/projection/historical-evidence consumers. Expected
argument/error-fixture stderr does not replace the runner's actual OK.
This is not a rerun of the old379 controller cohort, new Android cases,
mounted renderer evidence or grandfathered old-policy acceptance.

## Source bytes and limits

| Actual read-only command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

| Read source/consumer | Git blob |
| --- | --- |
| Frozen `PersistenceStoreBatchSyncTests.swift` | `2fb5ad79f4c0009c9d879fe6b1dc7054fdef0798` |
| Frozen `PersistenceStore+Testing.swift` | `2ea8fd27ce96ce3f6cd600df59472d205d8256ad` |
| Frozen `DeviceDTO+Testing.swift` | `ffbeca180d8cbb1833d6cea4984b7fbddd194d69` |
| Frozen `PersistenceStore+Channels.swift` | `f08da95f74ce0950a4dfdb70d339d8a16b26acf7` |
| Unchanged native `BatchSyncPersistenceTest.kt` | `7d459b84b3a6e5265a7d8ac56526d884787016f1` |
| Unchanged native `RepositoryTestValues.kt` | `ca3c37ab4bfa149f63bdd687469b8cf9e9f15446` |
| Native `ChannelRepository.kt` | `27d187f0f248774ebe673ab8260b88d5734c6c94` |
| Native `RoomPersistenceStore.kt` | `de1c03fe01659660ee4628bd804a7b9b565f2d3c` |
| Unchanged `ContainerHarness.kt` | `c6fb962fca19d1ff87c48eaa05868514a08d614a` |

This is real native in-memory persistence reconciliation, not physical
process death, API37/hardware, BLE circuit-breaker execution or later slot
history/rollback acceptance. The later nine bodies were not credited to
this channel execution. The separate [contact-family evidence](batch-contact-originals.md)
now accounts for its four originals/three new executions without replaying
these six; the later [five history/rollback originals](batch-slot-history-originals.md)
now have separate complete inputs/results and post-staging native fault evidence.
Independent full source review, exact successor hosted checks, reviewed
merge and broader assigned WP501 software/feature/resource work remain open.
