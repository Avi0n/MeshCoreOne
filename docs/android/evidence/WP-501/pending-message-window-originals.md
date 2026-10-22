# WP-501: complete original PENDING direct-message window family

## Identity and actual missing source-input boundary

- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
- Actual execution parent: `0fd631f82184775832b86526bd526b19ddbe4ff7`;
  actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Existing issue134/PR165; same actual stored attempt/session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation80->81.
- New test:
  `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/PendingMessageWindowOriginalPredicateTest.kt`,
  executed source`4bd90851d719376ac175789ae1be809031b840f0`.

The actual WP202 family ledger inventories all five
PersistenceStoreMessageWindowTests bodies. Read all five complete frozen
bodies/persistDirectMessages, both full MessageDTO testing constructors,
all existing native destination bodies/RepositoryTestValues.message,
source window/floor/count/probe/sort/reorder implementation and native
public-store/query/window DTO consumers.

All four original direct variants use testDirectMessage's **OUTGOING/raw1,
PENDING/raw0** default. Every existing native direct variant instead uses
the repository helper's SENT/raw2 default. Their timestamp/hasMore assertions
are already useful, but are not complete original pending-state inputs or
proof that actual returned/persisted pending DTOs remain unchanged.
These four missing full original input variants are the bounded new family.
The complete fifth channel/SENT variant is already represented, as detailed
below; it is reused without replay rather than adding a gratuitous fifth run.

## All five originals: four new methods and one existing witness

Each source store is initially empty, without a seeded device/contact row;
the actual in-memory Room fixture preserves that. Both radio and contact
UUIDs are fresh. Every direct message has fresh ID, text`mN`, UInt timestampN,
createdAt exactly UnixN, outgoing/pending/plain, nil channel/ack/SNR/pathNodes/
sender prefix/sender name/reply/roundTripTime, pathLength0, readfalse,
repeats0/sendCount1/retry0/maxRetry0/selfMentionfalse.
The exact source-createdAt values are preserved, not replaced by a sampled
current time. The actual sortDate is also required by full DTO seed readback.

| Full frozen original | Native destination | Complete original input and predicates |
| --- | --- | --- |
| `nil anchor applies the floor and reports hasMore()` | New `pendingNilAnchorPreservesFullSourceFloorAndHasMore` | Original pending DM timestamps1...10, nil anchor, floor3 -> exact ascending[8,9,10], hasMoretrue, all three actual original full pending DTOs. |
| `anchor widening beats the floor()` | New `pendingAnchorWideningPreservesFullSourceRows` | Separate original pending1...10, anchorUnix4/floor3 -> exact[4,5,6,7,8,9,10], hasMoretrue and the actual seven source DTOs unchanged. |
| `tie rows at the anchor are included()` | New `pendingAnchorIncludesBothDistinctOriginalTieRows` | Pending[3,5,5,7], distinct fresh IDs for both5 rows, anchorUnix5/floor1. Exactly two actual sortDate==anchor, no timestamp3, hasMoretrue. Require full[5,5,7] source DTO membership without inventing an order between identical timestamp/date ties. |
| `probe row is dropped and hasMore is exact at the boundary()` | New `pendingProbeAndExactBoundaryPreserveSourceHasMore` | Same original pending1...4 store: nil anchor/floor3 -> exact[2,3,4]/hasMoretrue; then nil/floor4 -> exact[1,2,3,4]/hasMorefalse. Both actual full returned/persisted windows are required. |
| `channel window matches the contact variant()` | Existing `MessageWindowPersistenceTest.channelWindowHasTheSameAnchorRules` | All original channel2/outgoing/SENT/plain/nil-direct-contact messages1...5/textcN/createdAtUnixN; anchorUnix3/floor2 -> exact[3,4,5]/hasMoretrue. Existing helper defaults match this source channel constructor, unlike the four direct variants. Its fixed radio UUID is an explicit deterministic identity substitution; the original exercises one radio only, no UUID-format/global-identity predicate. Full source/native body comparison only, not a fifth new execution. |

New guards require each actual full stored message before window queries,
exact initial count/membership and exact original returned DTOs, not just
timestamp labels. Both identical5 rows must independently exist. Returned
rows still require raw direction1/status0, exact contact/radio, nil channel
and actual per-ID public fetch equality. A SENT substitution, missing seed,
duplicate/collapsed tie, omitted original body or rewritten status cannot
fake this family. Additional guards are not extra counted originals.

## Actual production consumer and native identity adaptation

Uses unchanged RoomProcessTest/API31/native SQLite and production
RoomPersistenceStore/MessageRepository. All data enters through actual
saveMessage; no fake repository, direct test DAO upsert, product mutation,
seeded queue row or pending-send service is introduced.

Source contact windows identify the single fresh contact UUID. Native
windows require EntityKey(radio, contact), so the exact original radio/contact
pair is explicitly supplied. With the source's one-radio/one-contact fixture,
this preserves its behavior; no equivalence for a different global cross-radio
query is claimed. The source message-window contract itself is unchanged.

Source computes max(floor, count sortDate>=anchor), fetches one probe,
drops the last/oldest probe, reverses and reorders same-sender clusters.
Native uses the actual Room count/newest queries and equivalent window/
display projection. Exact source floor/anchor/tie/probe members and all
original output predicates exercise that real boundary.
Fixture teardown closes the process store/database and cancels its scope.

## Exact newly affected declared execution

Readiness `python android\scaffold\check_environment.py` **PASS**, explicit
allowlisted/private caches/JDK21.0.12.1/SDK37.2/build-tools37.0.0.
No dependency/tool installation.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.PendingMessageWindowOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS4/4, zero failures/errors/skips**; all four exact identities in the table.
Started`2026-10-21T02:39:29.382561Z`, finished`02:41:32.118364Z`;
fresh XML timestamp`02:41:26.077Z`, suite4.412s.
Executed source4bd90851 unchanged before/after; actual parent0fd/basedbb,
not published88 or a combined execution with the five slot-history cases.
XML:
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.PendingMessageWindowOriginalPredicateTest.xml`.
Primary actual local log under session27f3c368 files:
`wp501-pending-window-originals-1cd5d7460f29471b8a04e3204529aad5.log`.

Observed actual shared locks empty, acquired/released this same stored
owner's existing exclusive Gradle suffix
`pending-window-originals:1cd5d7460f29471b8a04e3204529aad5`,
revision81, exact0fd/dbb/4bd908/selector/977b/e8afe payload.
Final actual records empty. No older positive original/helper/cohort,
existing channel-window or repaired slot-history replay; no native/device/
packaging/foreign release/new actor/CI cancellation/rerun.
Healthy remote88 was preserved through its actual terminal39531691829SUCCESS;
that verdict does not transfer to this local source.

## Source bytes and limits

| Complete read source/consumer | Git blob |
| --- | --- |
| Frozen PersistenceStoreMessageWindowTests.swift | `203081e2ae09d98a574e460b20af9815ab35b83a` |
| Frozen MessageDTO+Testing.swift | `9428f0c258f381d24720e61398fcff1418e07acd` |
| Frozen PersistenceStore+Messages.swift | `02c50a9a0fda2aaf6b719a5fa410a0c5a0413c7f` |
| Existing MessageWindowPersistenceTest.kt | `e68571ac8a58a5b02c5aec4fc981385b7a1cd7dc` |
| Existing RepositoryTestValues.kt | `ca3c37ab4bfa149f63bdd687469b8cf9e9f15446` |
| Production MessageRepository.kt | `937380545fe91d5b77e4a1552be0180f34b8612f` |
| Production MessageDaos.kt | `082ea7f2a58a3ea02fbbb7f7c1863ec4c841cac5` |

All three frozen files equal the pin; all four native files equal actual
mainDBB and remain unchanged.

| Actual read-only command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

This is full five-body destination accounting by four new
executions plus one reused complete native witness, not five new runs,
independent source acceptance, hosted/current-head/full-WP approval,
API37/device/process-death/physical or backup interoperability evidence.
