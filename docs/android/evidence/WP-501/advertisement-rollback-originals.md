# WP-501 advertisement rollback: real Room consumer originals

LOCAL, UNPUBLISHED direct successor to
`3a1e19d1a58b5450993fc1f32c9ac459f07f382c`, NOT COMPILED/RUN/DISCOVERED.
Repository `cbattlegear/MeshCoreOne-Android`, existing issue134/ready PR165,
owner test-parity-engineer; WP-501-behavior/boundaries/source-test-parity.
Own parent3a run39636354999 was pending at source-work intake. Neither that
run nor5d5's proof executes this new class or supplies its acceptance.

## Four existing originals, one missing transactional consumer family

Frozen `MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift`
at `db14559b39d32322b06477c6ae676112f583db50` is the specification.
WP004 inventory lists all four as single originals, not parameter families.
Complete setup L1-233, all bodies below, and invoked HandlerHold/CommitMarker
L3261-3295 were read, not reconstructed from names or assertion counts.

Destination class: `com.meshcoreone.android.parity.AdvertisementRollbackOriginalPredicateTest`.

| Complete frozen original, including @Test | Actual proposed primary method | Retained actions and predicates |
|---|---|---|
| L971-1019 `contactDeleted during commit rolls back the resurrected contact()` | `originalSuccessfulCommitRemovesTheRealResurrectedRowWithoutDiscoverOrNotification` | 4B key; held handler saves Ghost with original frame defaults then SYNCED; 0x80, wait held, 0x8F before release; actual update observed; resurrected contact nil, Discover empty, zero new-contact notifications; stop/finish/listener join. |
| L1021-1084 `rollback keeps contact the radio deleted then re-added mid-round()` | `originalReAdvertClearsTheDeleteAndKeepsTheReaddedRoomContactAndNewHistory` | 93 advert key, 94 SlotVictim; held handler saves ReAdded/SYNCED; actual 0x8F deletion before re-advert94; re-advert fully handled before release; rounds finish; bare ReAdded survives and reconcile is not skipped. Old DM really cascades; new history is added ONLY after original bare-row/reconcile proof. |
| L1086-1124 `contactDeleted during failed commit rolls back and does not re-queue()` | `originalFailedCommitRollsBackItsRealEarlyWriteAndCannotRequeueTheDeletedKey` | 4C key; held handler really commits Ghost before FAILED; delete handled before release; genuine round completion; committed row nil; deleted key cannot cause another handler when the real foreground consumer re-arms. |
| L1126-1171 `rollback still runs when teardown cancels the sync mid-commit()` | `originalCancelledTeardownStillRollsBackTheRealCommitLandingAfterStop` | 4D Known pre-save; held Ghost/SYNCED handler; deletion observed before stop; actual production round cancelled and still incomplete before release; real post-stop commit positively captured; round joined with CancellationException; contact rolled back. Seeded old DM actually deleted; prior Discover row remains unchanged. |

Incumbent `AdvertisementServiceRoundTest.originalCases` retains all four
mirrors at L507-636. Its `AdvertHarness.makeStore()` returns AdvertFakeStore,
unlike frozen real in-memory PersistenceStore. No existing App/parity real-Room
service rollback counterpart was found. Existing
`PersistenceOriginalPredicateTest.aDistinctBareContactIsRemovedWithoutDeletingReferencedHistory`
already proves the direct repository guard with actual Room; it remains intact,
not duplicated or relabeled as service-consumer evidence. These counterparts
add NO original declarations/family credit or passing execution counts.

## Real data, boundaries and observable original predicates

FirmwareRadio feeds independent literal0x80/0x8F frames through the public
MeshCoreSession reader/parser/dispatcher. ObservedEvents delegates that REAL
session, emits the exact event into the REAL service, and records it ONLY AFTER
the downstream monitor finishes handling it. It never produces an event,
changes an outcome, implements the service or acknowledges before completion.
All full decoded objects/order are checked. This deterministic completion
boundary replaces40/60ms settle sleeps, not transaction behavior.

The actual service owns its real monitor/round Jobs. The fixture retains the
initial monitor Job set, joins the actual held round, and waits for every
remaining service-owned round to finish; no copied delta algorithm, reflection,
friend-path change, generation reset or hidden-state mutator is added.
Original private tombstone/pending predicates receive consumer-level proof:
the bare re-added contact AND complete Discover DTO must survive actual
reconcile, and real handleReturnToForeground must not start any extra handler.
Zero original debounce/minimum/busy intervals and a fixed phone clock make
that re-arm immediate. Expected flags are [false] or [false,true] for the
legitimate re-advert/escalation pair, not a waived extra round.

Every handler save/fetch uses the actual RoomPersistenceStore over the existing
API31/NATIVE SQLite RoomProcessTest database. Full fresh committed DTOs are
checked against independent original frame/default fields (only actual random
ID is carried); deletion is checked by public key AND actual ID. Production
ContactRepository/deleteContactIfUnreferenced uses its real guardedWrite and
RepositoryCascades; no store delegation override, fake write, direct DAO,
replacement transaction, injected failed COMMIT or expected-callback proof.
The FAILED original is a real early committed row followed by the original
FAILED enum outcome, NOT a claim that SQLite itself failed to commit.

Cancellation has one necessary narrow adaptation: Swift's checked continuation
and already-entered synchronous ModelActor save are not aborted by Task
cancellation. The teardown case keeps its held handler/real Room commit in
NonCancellable so the original post-stop commit can actually land. The genuine
production round is nevertheless cancelled by the UNCHANGED public stop;
isCancelled/!isCompleted are asserted BEFORE release, the full actual commit
is captured afterward, and the completed round's cause must be a typed
CancellationException. No cancellation is turned into success, fake service
reply or invented error. The three uncancelled commits retain normal context.

No original injects a thrown store/session failure: outcomes are SYNCED/FAILED
and actual teardown cancellation. Unexpected typed repository errors still
throw, service ERRORs fail the fixture, primary Throwable identity is rethrown,
and distinct cleanup failures are suppressed rather than silently discarded.
Cleanup always attempts stop, sticky gate release, owner cancel/join,
event finish/listener join, survivor checks, session stop and repository close.
The inherited process repository independently reads the same committed rows.
Same-radio unrelated and same-key foreign-radio contacts/full messages plus
both device DTOs survive unchanged. Original keys/names/chat/flags0/path0/empty/
latlon0/advert1700000000/modified1700000100 remain exact. Extra message fixtures
use incoming/delivered timestamp1700000400 and fixed created/sort1704067200.
The sole outbound transcript is literal APP_START01032020202020204d65736843:
no getContact/GET_CONTACTS exchange or radio result is manufactured.

## Exact source and input bindings

Entire frozen selected-region SHA256s, all equal current selected content:

| Region | SHA256 |
|---|---|
| setup1-233 | `7768aa95211b303d4f40c68bf5efa6bd9a986ddeb74249936efd734a283df58e` |
| successful971-1019 | `f17f298245fd6605732763ee9327355fe5ae956e69f7d17e6a64525410bb4923` |
| re-added1021-1084 | `528a60738e5af2e21c69a60ca51f8dcc6a44af58809054d53ac780b8e66826e5` |
| failed1086-1124 | `8f6efdddef07a28aa0cb1427d8eba56c17c23172e03ee3d8630c7d3bec8d32bc` |
| cancelled1126-1171 | `406b9f8ca7bf396e0b7ac2d0d7b497f443600ac90055c024c3011910d6fa36dc` |
| marker/hold3261-3295 | `352b01d509cc85e6466b17cfbf07cd4b5771215773c20cb5060c6bf835513ac2` |

Frozen whole-test blob55b86af differs from current cd764c0 ONLY by the two
previously disclosed unrelated synchronization hunks at frozen264 and1763.
Full diff inspected; no selected body/helper drift or reference-pin advance.
Independent Python transcription of source `(index+seed)&255` verified all
4B/4C/4D/93/94 full32-byte keys; code80/8F plus the appropriate key are the
literal inbound frames, not bytes generated by Kotlin candidate code.

Critical read-only blobs: frozen paths resolve at db145; native paths at
parent3a and unchanged checkout:

| Source/input | Git blob |
|---|---|
| `MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift` | `55b86af659e3445566f060dd38dfb5c4bdf0d409` |
| `MC1Services/Tests/MC1ServicesTests/Helpers/DeviceDTO+Testing.swift` | `ffbeca180d8cbb1833d6cea4984b7fbddd194d69` |
| `MC1Services/Sources/MC1Services/Services/AdvertisementService+DeltaSync.swift` | `2e69735b5d76c04239a5433c36d2c86cb01a4dea` |
| `MC1Services/Sources/MC1Services/Services/PersistenceStore+Contacts.swift` | `96f33ac980a5de8e72685a30d8b9d287805050ea` |
| `MeshCore/Sources/MeshCore/Protocol/Parsers+Contacts.swift` | `de083148e75bf31af02f8c3356d7622c4626c60e` |
| `android/core/services/src/test/kotlin/com/meshcoreone/android/core/services/contacts/AdvertisementServiceRoundTest.kt` | `6c5c7ea4eb4a52bdd65a76427f608d1c85a0b19d` |
| `android/core/services/src/test/kotlin/com/meshcoreone/android/core/services/contacts/AdvertTestSupport.kt` | `ee28fba2b1574cf9dd1d5038c734df50e647ee1a` |
| `android/core/services/src/test/kotlin/com/meshcoreone/android/core/services/contacts/AdvertFakeStore.kt` | `c0391e63f1ac62f53cc70935cdc9feda8ff766d7` |
| `android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/contacts/AdvertisementService.kt` | `19feb470905d04e973771b9ed222145090d2626c` |
| `android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/contacts/AdvertisementDeltaSync.kt` | `aead465d9d5a7aaa00cfc989b637a1f997aa325a` |
| `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/ContactRepository.kt` | `8da5513b403198a44f673addf443c5411b58a80c` |
| `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/RepositoryCascades.kt` | `0cf0462fec5c2a33ee2ba330c00db4bcb134d47f` |
| `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/RoomRepositoryContext.kt` | `85d5593ac6f45783acbefc5efeabb58a24f111d4` |

## Admission, checks and stop boundary

Fresh actor916234/ready165/head3a/base-main
`dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`, clean local3a and unpaused
trusted manifest/policy were checked. Canonical manifest977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8,
policy e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3;
raw manifest582edce11e1cb7761bc1b8f2de7e5c1caaeae27a/policyb77c6dc59ec2c626ea560484063808785233e948
equal trustedDBB/parent/checkout. BEFORE edits same actual owner/attempt/session
d6056690-3a5d-453e-b67e-36866c676ac5 evolved97->98 through typed CAS,
12 wp-owned admissions: only this new test/evidence and existing deviation,
create/modify/test/record-evidence. Only revision/capabilities/paths changed;
immutable authorization, identity and historical bindings are untouched.
Retained WP000 docs advisory intent reconciled at actual053db8 producer tip:
neither new evidence nor deviation exists there; no semantic collision.

Declared source-safe checks passed (no compilation/discovery/execution):

| Exact command | Actual result |
|---|---|
| `python tools\android-port\controller\verification_config.py --check` | PASS; configuration/source only |
| `python tools\android-port\controller\validate.py` | PASS; manifest/source/ownership only |
| `python tools\android-port\portmap.py` | PASS; source traceability only |
| `python android\parity-tests\audit.py --check` | PASS; archive/source consistency, not new-case execution |
| `python android\parity-tests\semantic.py --check` | PASS; historical archive consistency, not new-case acceptance |
| `git diff --check` | PASS; whitespace only |
| `git diff --cached --check` | PASS; exactly three staged owned paths, whitespace only |

No compiler, Gradle, WSL/native,
device, readiness, lease, recovery, new worker, settings, dispatch or rerun.
No product/incumbent/helper/diagnostic/build/policy/reference edits, broad
archive regeneration, snapshot reconstruction or CI-result bundle.
ONE clean LOCAL direct3a successor stops for exact source review and parent3a's
OWN result before any publication. All four new exact execution identities
and complete clean/nonempty mandatory hosted reports remain required later;
no full-WP/native/human/protected/merge acceptance or timeout-cause claim.
