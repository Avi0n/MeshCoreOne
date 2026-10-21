# WP-501: complete original batch contact count, favorite and avatar family

## Identity and full-family comparison

- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
- Actual local execution parent:
  `79adc722e35e017329aa375479eb8b5d12cb6c29`;
  actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
  actual trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Existing issue134/PR165; same attempt/stored session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation78->79.
- New test:
  `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/BatchContactOriginalPredicateTest.kt`,
  executed blob `40ca637931d520fd1436b6ad762beab09e5cab8d`.

Read all four complete contact bodies in frozen PersistenceStoreBatchSyncTests,
contactFrame/publicKey helpers, ContactDTO.testContact, device/store helpers,
source DTO/model save/update/defaults, full old native destinations and actual
native public-store/delegation/repository/model/Room mapping consumers.
This is the next coherent contact family after the locally completed six
channel cases; healthy published835 automatic CI was left immutable.

The existing compound insert method checks the returned count and name set,
but not the source actual stored count3, and uses its generic different
path/coordinate/advert/modified frame. Its favorite/avatar compound method
uses a favorited contact plus an added avatar rather than the source's
standalone favorite case and separate non-favorite avatar DTO case; it also
omits the favorite source stored-count1 predicate. All remain unchanged and
unplayed. Header/name mappings do not substitute for these complete cases.

## All four originals: three new cases, one reused empty witness

Every frame has key32x indicated byte, CHAT/raw1, flags0 unless specified,
pathLength0/empty path, advert0/coords0/modified0. Device/store helper is the
same real preseeded original device as the channel family, including
ID=radio UUID, key32x01, firmware8/v1.0.0, capacities100/8, multiAcks0,
TestDevice/TestMfg/01 Jan 2025, sync0 and active. UUIDs remain fresh.

| Complete frozen original | Native destination | Preserved full inputs and predicates |
| --- | --- | --- |
| `batchSaveContacts inserts all and returns count()` | New `batchInsertRequiresCountThreeAndAllThreeActualOriginalContacts` | Initially no contacts; frames key01/Alice,02/Bob,03/Carol with all original zero fields; returned3, actual stored count3 and exact name setAlice/Bob/Carol. |
| `batchSaveContacts updates existing rows and preserves favorite bit()` | New `favoriteOriginalRequiresOneStoredRenamedRowWithoutAvatar` | Seed only framekey01/Original/flags01, no avatar; re-sync key01/Renamed/flags00; returned1, actual stored count1, required first row namedRenamed and favorite. |
| `batchSaveContacts preserves existing avatarImageData()` | New `nonFavoriteAvatarOriginalPreservesFullThirtyTwoByteImageByKeyFetch` | Seed actual ContactDTO.testContact inputs: fresh ID/radio, key32x01, Original, CHAT, flags0/notFavorite, path255/empty, advert0/coords0/modified0, nil heard/nickname/lastMessage, false blocked/muted, unread0/mentions0, avatar32xAB. Re-sync original framekey01/Renamed/flags0/path0/all zeros; actual public-key fetch required, nameRenamed, same full32xAB avatar. |
| `batchSaveContacts with empty frames returns zero()` | Existing initial phase of `BatchSyncPersistenceTest.contactBatchPersistsEveryFrameAndEmptyBatchIsEmpty` | Empty frame list ->0 and actual contact fetch empty before later insert setup. Existing real Room fixture omits the unused source device helper; both batch consumers return0 before any device query and fetch contacts, not devices. Reused body comparison only, not a fourth new run/literal helper-identity claim. |

New cases also require actual frame/radio/key fields, non-favorite inserts,
required favorite seed without avatar, preserved favorite identity and no
avatar, and exact actual avatar seed readback before updating. The avatar
case explicitly requires non-favorite afterward and source frame fields.
A wrong combined favorite+avatar seed, absent seed, duplicated stored row,
wrong-frame defaults or truncated/substituted image cannot fake the originals.
These are stronger guards, not extra counted cases.

## Real consumer boundary and source/native normalization

Uses the unchanged API31/native-SQLite RoomProcessTest and production
RoomPersistenceStore/ContactRepository. Public frame save and batch resolve
existing rows by actual radio/public key, apply production ContactDTO.updating,
write through real Room transactions and fetch actual committed rows. No fake
repository, test-written upsert or direct DAO mutation is used.
The original per-case device is also saved/re-fetched through the public store.

Source Contact(dto:) explicitly normalizes nil lastHeardTimestamp to0 before
storage. Native RadioMappings.toEntity does the same; the avatar seed must
equal the original DTO with **only that source-defined normalization** after
actual fetch. This is not a silent replacement/default or whole-DTO comparison
against candidate output. The source-created nil input is preserved.
Byte wrappers compare all32 actual image bytes by content.

Device lastConnected Date.now alone is fixed at Unix1704067200.
No contact age/clock predicate is invented. Source helper defaults and fields
are reconstructed within this new fixture without changing the previously
reviewed/executed channel test bytes or replaying that cohort.
Existing fixture teardown closes store/database and cancels its owned scope.

## Exact newly affected execution

Readiness `python android\scaffold\check_environment.py` **PASS** with explicit
allowlisted/private caches, JDK21.0.12.1, compile37.2/build-tools37.0.0.
No dependency/tool installation.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.BatchContactOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS3/3**, zero failures/errors/skips; all three exact new identities above.
Started `2026-10-21T01:53:03.353908Z`, completed `01:55:26.041321Z`;
fresh XML timestamp `01:55:19.001Z`, suite execution5.088s.
Executed source40ca6379 unchanged across execution; actual parent79adc/basedbb,
not835/c9ed, a four-case execution or a combined rerun of the channel six.
XML:
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.BatchContactOriginalPredicateTest.xml`.
Local primary log:
`C:\Users\camer\.copilot\session-state\27f3c368-787a-4cad-8050-8c7c57b25eb4\files\wp501-batch-contact-originals-e39284dbe9fc41b79ed8a9f448495517.log`.

Observed actual hard-lock records empty, acquired/released existing
exclusive Gradle resource at revision79 under this stored owner's suffix
`batch-contact-originals:e39284dbe9fc41b79ed8a9f448495517`, exact selected
source/parent/base/current-policy payload. Final actual records empty.
No native lock, device, packaging, foreign release, incumbent replay,
hosted cancellation/rerun, new worker or provider gate.

## Source bytes and limits

| Actual read-only command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

| Actual source/consumer | Git blob |
| --- | --- |
| Frozen `PersistenceStoreBatchSyncTests.swift` | `2fb5ad79f4c0009c9d879fe6b1dc7054fdef0798` |
| Frozen `ContactDTO+Testing.swift` | `fff7bd2c9e81714dc47723e7223633be54965cf7` |
| Frozen `Contact.swift` | `7b6ecd2e224c1b1651edeba8a0019426881e7db4` |
| Frozen `PersistenceStore+Contacts.swift` | `96f33ac980a5de8e72685a30d8b9d287805050ea` |
| Native `ContactRepository.kt` | `8da5513b403198a44f673addf443c5411b58a80c` |
| Native `ContactDTO.kt` | `9d102668ec30d1f0a4075b9ea9dedcaeae3fe32b` |
| Native `RadioMappings.kt` | `adae49dfd32681ef7ff1801e9c08fd2572107016` |

This covers the four contact originals by three new executions/one existing
empty witness, not the whole16-case source file. The later
[five slot-history/rollback originals](batch-slot-history-originals.md)
now have separate complete inputs/results; channel6/6 and incoming6/6 retain
their actual separate local execution bindings. This is native in-memory
persistence, not physical process death/API37/hardware/Swift or backup
interoperability/full-WP acceptance. Full independent source comparison,
current-head hosted checks and reviewed merge remain separate.
