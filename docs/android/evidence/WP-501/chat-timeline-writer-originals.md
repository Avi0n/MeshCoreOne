# WP-501 complete writer-original comparison and observable stale-write witnesses

One LOCAL direct successor to `1083916392e2fe9fa040d33454883d10277cbec0`,
base/main `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`, ready PR165/issue134.
Frozen reference: `db14559b39d32322b06477c6ae676112f583db50`.
New tests are NOT COMPILED/RUN/DISCOVERED. Source reasoning is not execution.

## Complete source-file comparison

Selected `MC1Services/Tests/MC1ServicesTests/ChatTimelineWriterTests.swift`
from the actual historical worklist: nine `single` originals, empty explicit
mappings, no historical review-proposals group and no prior WP-501 family
comparison. Read the entire224-line file, WriterOwner and makeMessage, full
frozen writer plus invoked coordinator ownership/mutation/reload/rebuild bodies,
the corresponding native implementations and complete nine native case bodies.
All nine original predicates already have native equivalents; retain them.
This family is not missing nine tests.

Existing destination: `com.meshcoreone.android.core.services.rendering.ChatTimelineWriterTest`,
`chatTimelineWriterTests()` factory. Its helper registers the exact
`ChatTimelineWriterTests::<name>()` display identities below. Registration
source equality is NOT positive runner discovery.

| Exact original name | Complete frozen body | Existing preserved predicates and fixture |
| --- | --- | --- |
| interactive bind always succeeds and revokes the prior writer | 41-52 | Require A then B interactive writers; A initially current, B current, A revoked. |
| releaseWriter vacates the slot for a later prime bind | 55-68 | Interactive blocks prime; actual release allows current prime and revokes prior writer. |
| releaseWriter from a superseded owner does not evict the successor | 71-84 | Release A after B takes ownership; prime remains denied, B stays current. |
| stale writer mutations all no-op | 87-124 | Seed full hello DTO; bind B; retain all14 listed stale calls, false append, unchanged generation/messages/full render state/hello lookup. This original has inert sub-inputs described below; it remains intact. |
| current writer mutations apply | 127-141 | Actual append true/count1; full writer update changes lookup text to edited; remove empties messages. |
| prime bind is denied while an interactive owner is alive | 144-152 | Require interactive, deny prime, interactive remains current. Existing native reachability fence preserves the live-owner premise. |
| prime bind succeeds over a vacant slot, a prior prime, and a deallocated owner | 155-178 | Vacant prime succeeds; next prime revokes first; transient interactive succeeds; next prime succeeds after owner dies. Existing native weak probe and bounded50 GC requests adapt Swift ARC; no GC execution or reliability is newly certified. |
| interactive bind swaps hooks atomically with write ownership | 181-202 | Require prime and interactive binds with separate hooks; invoke installed rebuilder; exactly interactive recorded. |
| stale writer cannot schedule a full rebuild | 205-223 | Seed via prime then supersede with interactive; stale rebuild leaves renderStateID unchanged and build task null. |

Original makeMessage is incoming/delivered hello, timestamp1, random independent
message/radio/contact UUIDs, current createdAt, nil sortDate -> createdAt,
plain text, null optionals, zero path/repeats/retry counts and sendCount1.
Native default mappings match the full frozen DTO initializer498-578; all
original bodies and helpers remain byte-identical. Original tests inject no
transport/database error or cancellation; none is invented as original credit.

## Demonstrated assertion/fixture gap and bounded implementation

The original `stale writer mutations all no-op` and its mirror faithfully use:

- `replaceAll` before stale beginLoading/markLoaded, so phase is already LOADED
  and either underlying operation is independently a no-op.
- No built item plus an identity update transform; underlying update/remove
  cannot alter render state even if writer gating is bypassed.
- DELIVERED -> FAILED with default userInitiated=false; the independent
  monotonic-status guard already rejects this operation.
- enqueueReload followed by synchronous assertions, without asserting task
  absence, running its scheduler or joining the consumer. Scheduling can escape
  those immediate snapshots. Other coordinator tests cover direct mutations
  and reloads, but do not combine these witnesses with the stale writer gate.

These are source-established masking conditions, NOT observed product failures
or an executed mutation experiment. No speculative production repair is made.
Added `ChatTimelineWriterObservablePredicateTest` under owned parity-tests:

| Proposed App method | Supplementary observable predicate and live-positive control |
| --- | --- |
| staleLoadingMutationsCannotHideBehindAnAlreadyLoadedPhase | Begin from genuine UNINITIALIZED: stale begin leaves full state/ID intact, same live call reaches LOADING; stale mark cannot settle that LOADING state, same live call reaches LOADED. Exact generation increments, empty messages/lookup retained. |
| staleStatusAndRenderMutationsCannotHideBehindEmptyOrPolicyRejectedInputs | Build and append a real nonempty MessageItem. A nonidentity timestamp-group transform and real removal are denied stale but apply live. Keep incoming/DELIVERED/timestamp1/hello; set userInitiated=true ONLY in supplementary stale/live status controls so the permitted FAILED transition cannot hide behind the monotonic guard. Compare full DTO/list/lookup/render snapshots, explicit envelope hasFailed/status and footer status, exact increments, and real Room row remains unchanged by timeline-only writes. |
| staleReloadCannotScheduleEitherOverloadWhileTheCurrentWriterReadsTheRealRoomCommit | Persist a different full DTO in real Room while coordinator retains old DTO. Stale single-ID and set overload cannot create a drain; run scheduler and retain full memory/state/ID with no callback/reset/build. Same live overloads each create and join a real distinct completed noncancelled consumer Job, read actual successive Room commits and update full DTO/lookup; exact rebuild callback order, generation increments and unchanged render-state snapshot asserted. |

Assembly uses the PUBLIC production registry -> coordinator -> generation-stamped
writers, inherited real in-memory Room/RoomPersistenceStore, actual production
builder and callbacks. No fake MessagePersisting, copied gate/reload algorithm,
reflection, private setter, replacement transaction or success acknowledgement.
Full snapshots and explicit expected field copies are independent expected
consequences; candidate mutation/reload code does not manufacture expectations.
Builder output supplies only a real nonempty input item, not expected gate behavior.

Supplementary IDs/time are fixed (`1000...0001` radio, `2000...0002` contact,
`3000...0003` message, Unix1704067200 created/sortDate), replacing original random/
wall-clock fixture inputs without changing any original. Different committed
texts, nonidentity grouping, live-positive loading states, real reload scheduling
and explicit userInitiated flag are disclosed witness inputs, not misreported
original parameters. No added OriginalCase IDs or duplicate original credit.
The existing App test source set already includes parity-tests; no build changes.

Owners stay strongly reachable through cleanup fences. StandardTestDispatcher
shares the runTest scheduler, including builder dispatch; no sleeps/polling or
deadline change. Normal positive drains are joined; finally captures actual
maintenance Jobs, clears registry/cancels and joins in NonCancellable before
inherited Room close. NonCancellable is cleanup only, not suppressed test/body
cancellation. No fresh cancellation/error scenario is claimed.

Consumer boundary: the actual core writer forwards to real coordinator
state/reload machinery. Existing ChatPrewarmTest's StorePrimeTimeline invokes
real writer commits but is a TEST implementation of PrimeTimeline.
Production ChatTimelinePrimer consumes that interface; this evidence does NOT
turn its test fixture into a mounted product UI consumer or claim UI parity.
The unadmitted ChatTimeline accessibility operation and queue fourth-counter
gap are unrelated and unchanged.

## Exact frozen/native input bindings

All frozen entries equal current checkout canonical Git blobs; no source drift
or pin advance. Entire frozen test SHA256:
`881468dd269ce0da2a4d97ebe25015c1aecf11191be85bd76f48862d96c1a5ac`.
Native inputs below are unchanged parent1083916 Git blobs.
Abbreviations: `S` = `MC1Services/Sources/MC1Services/`;
`R` = `android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/rendering/`;
`T` = same services package under `src/test/`;
`M` = `android/core/model/src/main/kotlin/com/meshcoreone/android/core/model/`;
`D` = `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/`;
`A` = `android/app/src/test/kotlin/com/meshcoreone/android/app/container/`.
Whole-file binding does not expand comparison beyond the invoked functions stated above.

| Input | Git blob |
| --- | --- |
| `MC1Services/Tests/MC1ServicesTests/ChatTimelineWriterTests.swift` (frozen) | `173dae80e1722d8a450daae0df514ee7ad041530` |
| `S/Services/ChatTimelineWriter.swift` (frozen) | `03827ec266934bb176e8c87eeec680e94f435406` |
| `S/Services/ChatCoordinator.swift` (frozen) | `794a074913ac8363410c021ca874af601a6d3d50` |
| `S/Services/ChatCoordinator+Mutations.swift` (frozen) | `ab22934f22ab6ed883214d71d3ffdbc41619987d` |
| `S/Services/ChatCoordinator+Reload.swift` (frozen) | `91f308c41594a81d0895c3db6c1cdac83aef8915` |
| `S/Services/ChatCoordinator+Rebuild.swift` (frozen) | `b186f6abdd751fa499a9f803ba26d2c31712811c` |
| `S/Models/Message.swift` (frozen) | `27caa780e5d8ee1dcf4fd5dab661ef9945a5fe26` |
| `T/ChatTimelineWriterTest.kt` | `69b2573fc86406d87a4649c898d9b641455809a5` |
| `T/RenderingFixtures.kt` | `99726ad6b0e7da676cc5dd2609626199a52db942` |
| `T/RenderingInMemoryMessageStore.kt` | `3c5465617d9e8d465257aaaec0ea7a50f1b635b6` |
| `T/ChatCoordinatorBehaviorTest.kt` | `f73ca4ba019738d1f27c0e4e22951b172a171304` |
| `R/ChatTimelineWriter.kt` | `70c795ec1e7f3068d04d247ddcca75308319b491` |
| `R/ChatCoordinator.kt` | `943284ec8236c9f9c299df15645867b68e07c39e` |
| `R/ChatCoordinatorRegistry.kt` | `3bd1e71824376a4faf1d1929ae19a6343ac4ad33` |
| `R/ChatRenderState.kt` | `2353c46bf030a990c9447fa6eed88f94cb1c0d7f` |
| `R/MessageItem.kt` | `fc1999b07754f323791511dec3a3535ef05b9ecf` |
| `R/MessageBuildInputs.kt` | `accaac9f3106aa4f641e26befe1e067fb1e5d6cf` |
| `R/MessageFragmentBuilder.kt` | `be87b30094cd755d94b71fcf7bc7e607d9688a9f` |
| `R/MessageFooter.kt` | `9114554a0a37a90c33b086cbc851b20ed388222a` |
| `R/EnvInputs.kt` | `4ee5a29d1646b911a33fe3497904e4093cd1b94c` |
| `M/MessageDTO.kt` | `73e9e78d5c6ac11560a84521e49e07f509e54099` |
| `M/ConversationValues.kt` | `944340547bbdfd3f1fd53ae8d91b59ddfa80c75e` |
| `D/RoomPersistenceStore.kt` | `de1c03fe01659660ee4628bd804a7b9b565f2d3c` |
| `D/MessageRepository.kt` | `937380545fe91d5b77e4a1552be0180f34b8612f` |
| `A/ContainerHarness.kt` | `82e720b353265a2c3706e20da4e89da2f6637e53` |
| `A/ChatPrewarmTest.kt` | `831b45c93ca07f56da57f154f2ca31fba4aa6979` |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/state/ChatTimelinePrimer.kt` | `97d47e7fc995db5ad0315be09195af847cfca81d` |
| `android/app/build.gradle.kts` | `630195588afbe6aca03c8f3f216a28dc91b1eb61` |

## Admission, checks and boundary

Before edits, same stored owner/attempt/session
`d6056690-3a5d-453e-b67e-36866c676ac5` evolved99->100 through Ledger.evolve_scope
CAS: twelve `wp-owned` admissions for `create`, `modify`, `test`,
`record-evidence` over only new test/evidence plus additive WP-501 deviation.
Only revision/paths/capabilities changed; identity/authorization unchanged.
Actual WP000 advisory053db8 has neither docs path; semantic edits disjoint.
Fresh actor916234, exact clean local1083916/publicebf6/ready165/base-mainDBB
and unpaused trusted inputs held. Canonical manifest
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`,
policy `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.

Source-only validation:

| Exact command | Actual result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS exit0 |
| `python tools\android-port\controller\validate.py` | PASS exit0 |
| `python tools\android-port\portmap.py` | Initially BLOCKED exit2 for conflicting PortedFrom/AndroidOnly dispositions in the new test; changed only the supplementary comment to Adaptation, then PASS exit0 |
| `python android\parity-tests\audit.py --check` | PASS exit0 |
| `python android\parity-tests\semantic.py --check` | PASS exit0 |
| `git diff --check` | PASS exit0 |

Nine exact existing registration IDs and entire frozen file/defaults/consumer bindings matched.
No production/incumbent/helper/build/diagnostic changes, archive regeneration,
result bundle, local Gradle/WSL/native/device/lease/recovery or CI dispatch/rerun.
No publication. Parent outcome and exact source review remain publication
prerequisites; own hosted positive3 identities plus complete mandatory success
would still be needed for software execution claims. All protected/full-WP/
native/human/backup gates remain outstanding; no original-family count inflation.
