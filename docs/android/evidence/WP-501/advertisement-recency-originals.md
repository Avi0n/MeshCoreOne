# WP-501 advertisement phone recency: real persistence originals

## Local source boundary

Repository `cbattlegear/MeshCoreOne-Android`; owner `test-parity-engineer`;
existing issue134/ready PR165. ONE LOCAL, UNPUBLISHED direct successor to
`5d5a1c9c658f0a5c8c33b96e584cad93dec3a0a7`, NOT COMPILED/RUN/DISCOVERED.
Acceptance IDs: WP-501-behavior, WP-501-boundaries and
WP-501-source-test-parity. This source proposal is not their acceptance.

The coordinator independently observed exact5d5 own39625208210 all-six
SUCCESS, including both path-recency original identities once, and posted
source/execution COMMENTED5602573229 separately from source5602060401.
That is parent software evidence, not execution of this new class, a claim
that f60's timeout cause was localized/fixed, or native/protected acceptance.
Existing e6 compiler failure, f60 outer timeout and9ab software success
remain separately bound in their existing evidence. No CI-result bundle,
passing-run replay or new diagnostic/timing repair is created here.

## Genuinely absent consumer, not two newly invented originals

Frozen WP004 inventory identifies the two complete single originals below.
Current `AdvertisementServiceRoundTest.originalCases` and
`AdvertisementServiceStoreTest.originalCases` already mirror their assertions.
However, BOTH call `AdvertHarness.makeStore()`, whose actual return is
`AdvertFakeStore`, and use `AdvertFakeSession.yieldEvent`. The frozen originals'
`makeStore()` constructs real in-memory PersistenceStore and saves a device.
The missing family is therefore the real transactional advertisement consumer,
not missing declarations or two additional original-family credits.

Existing `PersistenceContactClockAndBadgeOriginalPredicateTest` exercises real
repository touch in isolation, not public 0x80 ingestion, delta completion,
post-insert stamping and stale-prune decision. Existing verified path-recency
tests ingest 0x8D/0x81, not 0x80. Model boundary tests do not execute persistence.
The incumbents and all their source/runtime evidence remain untouched.

Destination class:
`com.meshcoreone.android.parity.AdvertisementRecencyOriginalPredicateTest`.

| Complete frozen original | Actual proposed primary method | All original actions/predicates retained |
|---|---|---|
| `known contact advert bumps lastHeard without getContact()` L332-366 including @Test | `originalKnownAdvertCommitsContactAndDiscoverRecencyWithoutAnyContactQuery` | Real device/store; B1 incremental32-byte key, Known, chat/flags0/direct empty path, advert1700000000/radioModified1700000100/latlon0; pre-save contact AND Discover row; default SYNCED/no-write handler; before=phone-now-minus1; monitored Advertisement; wait positive contact phone stamp; require full contact, timestamp >= before; require matching Discover row and lastHeard >= before; wait handler >=1; no getContact; stop monitor. |
| `unknown advert contact stamped lastHeard survives stale-node prune()` L2285-2335 including @Test | `originalUnknownAdvertStampsTheRealDeltaInsertBeforeStaleNodePruning` | Real store/device; 5C incremental key, JustHeard, both radio stamps1000000 and corresponding oldDate; actual handler saves source frame and reports SYNCED; beforeAdvert=phone-now-minus1; monitored Advertisement; wait post-insert positive lastHeard; require contact; cutoff phone-now-minus30*86400; lastModified==oldStamp and <cutoff; lastHeard and recency >=beforeAdvert; not matchesStaleNodePrune; oldDate.timeIntervalSince1970 <Double(cutoff); stop monitor. |

The entire frozen helpers/setup L1-233, selected bodies and actual
DeviceDTO+Testing defaults were read. Neither original declares parameters,
injected errors or mid-operation cancellation. They do not authorize swallowing
actual errors: repository failures remain typed, service ERROR diagnostics fail
the fixture, and primary body/cancellation identity is retained and rethrown
after every cleanup attempt with distinct later failures suppressed.

## Actual production path and deterministic adaptations

The existing FirmwareRadio answers only the public MeshCoreSession startup.
Independent literal 0x80 frames pass through its real reader, parser,
dispatcher and service monitor; no private handler, copied service, store fake,
yieldEvent injection, generation reset or expected-callback-only assertion.
The known handler really runs without writes; the unknown handler performs
real Room save/fetch before returning SYNCED, exactly the original
HandlerRecorder behavior. No GET_CONTACTS radio exchange is claimed.

Native `AdvertisementService.handleAdvertEvent` captures phone receive time,
calls real `touchContactHeard` and records pending work. For a known row,
`ContactRepository.touchContactHeard` commits the max contact stamp and same
radio's Discover lastHeard. For an unknown row the initial touch returns false;
the real round snapshots radio-scoped keys, invokes the actual insert callback,
then `completeSyncedRound` stamps the retained receive time before reconcile.
The test captures the actual newly inserted DTO with lastHeard0 INSIDE that
callback and compares the later entire DTO to only its exact phone-stamp copy.
`ContactDTO.recencyTimestamp/matchesStaleNodePrune` are the production stale
removal predicate, not a copied calculation used instead of the real predicate.

One injected phone clock drives BOTH AdvertisementClock and the existing
public RoomPersistenceStore clock constructor. It seeds rows at1704067140,
then moves ONCE to execution Instant1704067200 before session/monitor start
and stays fixed. The known Discover before-row is therefore genuinely older
than beforeAdvert; its full-copy equality cannot pass without a real bump.
This setup-only step does not advance coroutine/session deadlines.
The real API31/NATIVE
RoomProcessTest database/executors are reused; its inherited process repository
also reads the committed contact for cross-repository verification. A
clock-injected real repository over the SAME database follows existing
NodeSnapshot/queue prior art; it is explicitly closed before inherited teardown.
This is not fresh-process reopen, device, SwiftData-engine or hardware evidence.

Existing publicSessionClock/test scheduler drives startup and zero debounce/
minimum/busy intervals. The bounded five-real-second/2ms predicate drain follows
existing Room fixture prior art and never advances session timeouts. Registered
public service/protocol streams and real event completion establish consumption
without exposing internal test-only state. The fixture starts the monitor before
the per-case handler installation, but no incoming frame is pushed until after
installation; original first-advert ownership/order is unchanged.

Supplemental native assertions compare complete persisted contact/node DTOs,
stable identity/metadata and exact phone stamps, one false handler flag, exact
protocol Advertisement and entire public event order: known ContactUpdated
twice; unknown NewContactDiscovered(JustHeard,actual-contact-ID,chat) then
ContactUpdated. Same-key foreign-radio contact/node and both device rows survive
unchanged. After stop/join, the exact transcript is APP_START only: no getContact,
GET_CONTACTS or extra sends. Session stop finishes its public stream with real
connects1/closes1/readers0; service listener/owned children are joined.

## Independent inputs and frozen reference drift

Frozen parser declares 32-byte public key after Advertisement code80.
Keys are source `Data((0..<32).map { (index + seed) &255 })`; no Kotlin
builder/parser generates expected bytes.

| Independent input | Literal |
|---|---|
| Known B1 key | `b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0` |
| Known 0x80 | `80b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0` |
| Unknown 5C key | `5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b` |
| Unknown 0x80 | `805c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b` |
| Only outbound APP_START | `01032020202020204d65736843` |

Source-only independent Python transcriptions verified both full key/frame
literals and fixed cutoff1701475200, not Kotlin execution. Full selected frozen
body SHA256s including @Test are
`9d63b822d023eab4757c2e1e0e78efc3ab9228f51e601372fc35bef7793c052c`
and `4b806e7f042bd91a33df464188adfc10832cbc82ccca28d47345a2bc11a79e2e`;
entire helpers/setup SHA256
`7768aa95211b303d4f40c68bf5efa6bd9a986ddeb74249936efd734a283df58e`.
Each complete selected region equals current checkout content.

Whole-file reference drift is NOT silently adopted: frozen test blob55b86af
differs from checkout/trustedDBB/parent cd764c0 by ONLY two already disclosed
unrelated synchronization hunks: rollback-cascade40ms sleep -> observed deletion
handling, and handler-reinstallation40ms sleep -> observed pending registration.
Full diff and selected-region equality were checked; db145 remains the pin.

## Typed admission and exact input bindings

Fresh actor cbattlegear/916234, clean local/public5d5, ready OPEN165, actual
base/main `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`, frozen
`db14559b39d32322b06477c6ae676112f583db50`, unpaused trusted policy and merged
WP109/203/407 prerequisites were checked. Canonical manifest
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
policy `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
Raw trustedDBB policy/manifest/capability/ledger helper blobs match parent and
checkout. No main/policy/runtime/resource operation is performed.

BEFORE edits, SAME actual owner/attempt/session
`d6056690-3a5d-453e-b67e-36866c676ac5` evolved96->97 through typed
Ledger.evolve_scope CAS: wp-owned, create/modify/test/record-evidence,
12 admissions, only this new test/evidence and existing WP501 deviation.
Only revision/capabilities/paths changed; ALL other record fields, historical
authorization_context/bindings, actual identity, expiry/budget/state remain.
CLI27f3 is not relabeled. Retained WP000 broad docs/android intent was
reconciled read-only: actual053db8 producer tip has neither owned docs path;
there is no overlapping implementation or semantic collision. No permission
prose rewrite, release/recreate, manual ledger edit or shared support edit.

Frozen Git paths below resolve at db145; native paths at parent5d5 and checkout.
All native bindings except the two explicitly marked support files also equal
actualDBB; the existing publicSessionClock helper is absent on DBB.

| Read-only source/input | Git blob |
|---|---|
| Frozen AdvertisementServiceTests | `55b86af659e3445566f060dd38dfb5c4bdf0d409` |
| Frozen DeviceDTO+Testing | `ffbeca180d8cbb1833d6cea4984b7fbddd194d69` |
| Frozen AdvertisementService | `c95879289a6cb9706495dbb0fbf6e430cb211894` |
| Frozen AdvertisementService+DeltaSync | `2e69735b5d76c04239a5433c36d2c86cb01a4dea` |
| Frozen PersistenceStore+Contacts | `96f33ac980a5de8e72685a30d8b9d287805050ea` |
| Frozen Contact model | `7b6ecd2e224c1b1651edeba8a0019426881e7db4` |
| Frozen Parsers+Contacts | `de083148e75bf31af02f8c3356d7622c4626c60e` |
| Frozen PacketCodes | `31e38b41ad34aaf31a2d855a4f5cc8ff7ebccf6e` |
| Incumbent AdvertisementServiceRoundTest | `6c5c7ea4eb4a52bdd65a76427f608d1c85a0b19d` |
| Incumbent AdvertisementServiceStoreTest | `be16dca1b8f2fbaecb20da3796d360f5b0d591f3` |
| Incumbent AdvertTestSupport | `ee28fba2b1574cf9dd1d5038c734df50e647ee1a` |
| Incumbent AdvertFakeStore | `c0391e63f1ac62f53cc70935cdc9feda8ff766d7` |
| Native AdvertisementService | `19feb470905d04e973771b9ed222145090d2626c` |
| Native AdvertisementDeltaSync | `aead465d9d5a7aaa00cfc989b637a1f997aa325a` |
| Native ContactRepository | `8da5513b403198a44f673addf443c5411b58a80c` |
| Native DiscoveredNodeRepository | `35d146d87b6a129a654b9a138bdc9d853360a390` |
| Native RoomPersistenceStore | `de1c03fe01659660ee4628bd804a7b9b565f2d3c` |
| Native RoomRepositoryContext | `85d5593ac6f45783acbefc5efeabb58a24f111d4` |
| Native ContactDTO | `9d102668ec30d1f0a4075b9ea9dedcaeae3fe32b` |
| Native DiscoveredNodeDTO | `4ed971b914e584a7a49c7ee4b1c34200ce8856af` |
| Existing ContainerHarness/RoomProcessTest, parent-only support version | `82e720b353265a2c3706e20da4e89da2f6637e53` |
| Existing ContainerTestSupport/FirmwareRadio | `2ffc6cdf5870f412f7f8770ae97230c3f7f7eef4` |
| Existing App build, parent-only support version | `630195588afbe6aca03c8f3f216a28dc91b1eb61` |
| Existing SessionResponseAcceptanceTest/publicSessionClock, absentDBB | `9f1ac6fe9cae87def964b83bd97220929a5e6726` |

## Validation and remaining boundary

No Kotlin/Gradle compilation, discovery or execution occurred. Final source-safe
checks, including the genuine older seed/complete post-event bump, passed:

| Exact declared command | Actual result |
|---|---|
| `python tools\android-port\controller\verification_config.py --check` | PASS; source configuration only |
| `python tools\android-port\controller\validate.py` | PASS; source/manifest/ownership only |
| `python tools\android-port\portmap.py` | PASS; source traceability only |
| `python android\parity-tests\audit.py --check` | PASS; archive/source consistency, not new execution |
| `python android\parity-tests\semantic.py --check` | PASS; historical58-group archive consistency, not live coverage |
| `git diff --check` | PASS; whitespace only |
| `git diff --cached --check` | PASS; exact three staged paths, whitespace only |

Full prior deviation text is restored byte-for-byte by removing ONLY this
family's inserted subsection. The only other changes are the new test and this
new evidence; ALL incumbent tests, production, helpers, frozen inputs, build
listeners, harness/diagnostics, policy and manifest remain byte-identical to5d5.
Both expected event lists have explicit public supertype arguments; no cast,
Any, erasure or weakened equality is used. Independent full literals and
cutoff1701475200 were rechecked. All body/cleanup attempts and final original
Throwable rethrow are retained. These comparisons are NOT compilation.

No new tools,
dependencies, local Gradle/WSL/native/device/readiness/lease/recovery/replay,
dispatch/rerun, publication, new agent/WP or result bundle.

Both exact new methods must later execute once, with clean/nonempty App,
complete mandatory selected reports and OWN exact candidate hosted success.
Existing5d5 software proof stays parent-bound. Missing or malformed/zero/skipped
evidence fails closed. Native/human/backup-compatibility/protected/whole-WP
acceptance, fourth channel-counter and remaining original accounting are not
claimed. Unadmitted ChatTimeline accessibility remains an explicit separate gap,
not silently excluded or relabeled.
