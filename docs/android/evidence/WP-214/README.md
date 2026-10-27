# WP-214 evidence

## Current strict activity-timestamp oracle repair

The separately allocated `SM-SYNC-ACTIVITY-ORDER` repair restores the original
timestamp predicate without changing production clock/range policy. Its
pre-edit reviewed proposal, deterministic fixture limits, native separation
controls and pending execution are in
[activity-order-oracle.md](activity-order-oracle.md).
The merged #349 and #355 results remain proof only for their own exact bytes.

## Current contact-prune oracle repair

The separately assigned `SM-CONTACT-PRUNE-ORACLE` batch, its eleven original
families, actual-service/Room mapping and pending execution state are recorded in
[contact-prune-oracle.md](contact-prune-oracle.md). It starts after merged #349;
the historical channel/suppression sections below do not claim new-batch acceptance.

## Inherited WP-201 compile correction (local, unverified)

WP-201 reported an authenticated `:core:runtime:compileTestKotlin` failure in
the inherited `DeviceConnectionMethodPersistenceTest.kt` expected-list
assertions, not a passing runtime suite. Exact producer commit
`0384d1a8cb13b21f60a5d42e1b2a14ee624a0716` supplies two explicit
`listOf<ConnectionMethod>` type arguments without casts, assertion weakening or
production changes. WP-214's inherited old Git blob and the producer parent
both equal `bbf84a0a572230f8712abd508254e35161ca81b9`; reconciliation yields
the exact producer new blob `46c18f7788602ed70244ee49da183c845bec1bdc`.
Only those two producer-owned lines are integrated, retaining WP-201 headers
and attribution, not the producer's unrelated DATA10 ancestry or evidence edits.

Generic `modify` was refused. Actual admission of
`merge-integration` / `merge-disjoint-semantic-edits` passed; its trusted rule
was checked unchanged at freshly fetched actual main
`3a292b6498e8dcef0c374022a93c8e30c68f5bd5`.
The same reservation evolved from revision 8 to 9 for this exact test path,
without rewriting authorization prose or modifying a private ledger.
No helper-driven main merge/rebase, frozen remote update or heavy job occurs.
The union successor needs its own full verification at the supported admission
boundary; exact blob equality is not compilation or test execution evidence.
WP-201 remains the primary producer and verified/landed publication prerequisite.

## Subsequent suppression and smoke-oracle batch (local, unverified)

The coordinator assigned `SM-SYNC-SUPPRESSION-ORACLE` and
`SM-SYNC-SMOKE-DISCRIMINATION` as the next bounded same-owner batch. This work
starts after immutable queued candidate
`99593d9b09786ebda0dcb3e56983f02006c15385`, tree
`70357649bfefe0ec62984326955f8d5c2d5574a8`. That candidate's remote checkout,
17-module affected plan and pending acceptance are not modified by this batch.
The grouping input is the same pinned-digest artifact recorded below, exact
`root_cause_groups` IDs and SERVICES-MEDIA workstreams 19 and 21. Its old
judgments are context, not a reproduction or execution result.

Current-source inspection confirmed the gaps before edits. Original suppression
families now set the actual WP-215 `NotificationService` true, assert that
precondition, invoke coordinator disconnect/full sync and assert false afterward.
The module-local suppression bridge forwards only these two properties; it does
not claim to be the production App adapter. Two additional App tests exercise
the actual `SyncNotificationsAdapter` over the production Room-backed graph:
disconnect resets suppression and state; a gated full-sync poll observes true
while suspended, then releases the actual poll and verifies false/Synced with
exactly one poll. Intermediate ordering is a new native oracle, not an assertion
attributed to the original Swift case.

The original disconnect state family now begins Synced rather than Idle.
The original two handler/monitor smoke cases remain under their original names.
New native cases deliver DM/channel messages through the installed handlers and
assert conversation persistence/notifications, and deliver discovery events
through the actual coordinator monitor. The latter captures monitor jobs,
joins replacement/cancellation, verifies subscriber release and rejects stale
or cancelled-source delivery. The original already-sorted DM input remains;
a separate reversed-timestamp input across a six-second gap distinguishes
cluster-local reordering from an incorrect global sender-timestamp sort.
The source resync case is unchanged: begin plus disconnect expects **no** end
callback. This is not double-end proof; existing retry-owner bracket balancing
coverage and production activity ordering are unchanged.

All modified service test/support paths were admitted under `wp-owned`.
The new App test was admitted under `app-build-launcher` /
`add-direct-support-wiring`; the existing reservation evolved from revision 7
to 8 without changing authorization audit prose or recreating identity.
No production code, Room prune behavior, timestamp-range adaptation or WP-212/
chat behavior is changed.

Actual lightweight results: `git diff --check` PASS;
`python .\tools\android-port\controller\validate.py` PASS (1,866 pinned inputs);
`python .\tools\android-port\portmap.py` PASS (1,911 mappings);
`python .\tools\android-port\test_inventory.py --check` PASS (468 pinned
paths, 5,133 source declarations). Source inventory is not runtime discovery.
All seven selected original family names remain exactly once; five native
oracles are added (two App adapter, two handler/monitor and one timestamp case).
The actual declared acceptance runners remain complete
`:core:services:test` and `:app:testDebugUnitTest` plus the trusted
candidate-derived consumer/compile/lint/graph closure. These were **NOT RUN**
for this new batch: no extra heavy/native execution was authorized or started.
No earlier candidate result applies to these bytes. WP-201 verified/landed
completion still gates standalone publication and exclusion of inherited
WP-201 changes from the WP-214 PR.

## Current channel-sync repair batch (local, not published)

Assignment: `WP214-CHANNEL-SYNC`, `services-porter`, default runtime with the
tracked services-porter instructions applied. Session
`d3a4cd0b-7a84-44c4-bb5a-8c2066c90afd`, branch
`cbattlegear-studious-journey`, initial current-main base
`263d30332940f09082709b9d38ee8877dfb9fedd`; source remains
`db14559b39d32322b06477c6ae676112f583db50`.

The only assigned root-cause groups are `SM-ACTIVE-LINK-POLICY` and
`SM-CHANNEL-TYPED-ERROR`. The grouping artifact was read at SHA-256
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`;
its historical judgments are not current acceptance. Original policy-family
coverage remains in `DevicePlatformSyncThrottlingTests`; new App graph coverage
adds BLE/WiFi ESP32/nRF52/unknown-model forwarding and reconnect replacement.
Two native cooldown cases additionally cover 24 clean/attempted timestamp,
platform and transport combinations immediately below and at the 30-second
boundary through actual coordinator phases.
The original typed-failure case and cancellation assertions remain at the real
`ChannelServiceProtocol` consumer seam.

Prerequisites are actual merged ancestor commits: WP-208 #43, WP-209 #49,
WP-210 #50, WP-211 #42, WP-212 #48, WP-215 #57 and WP-216 #53.
Typed advisory bookkeeping was acquired for this session and evolved without
releasing historical rows. App support uses `app-build-launcher`. Trusted
prerequisite PR #334 landed at `f2f481d2e7c2a9029808943529c98c98ef879299`;
fresh admission of the three exact runtime paths under
`wp214-active-link-channel-sync-input` /
`add-active-link-channel-sync-input` passed and evolved this same reservation
from revision 4 to 7. The disjoint required input, App consumer and runtime
factory-input regression are authored. WP-201 handed off exact local commit
`f9bf75e0f285959e449a19b3fecf9c2a56d3ee2d`, whose parent is that trusted
prerequisite. Deterministic merge `0a1b95ba56e24d2ff70c4c7c148f19f7e1becb28`
retains its endpoint-method producer, three-family persistence regression and
WP-201 attribution without copying patches. Only after that handoff did WP-214
add `transportType = link.type` at the sole service-factory call. The WP-201
methods/DTO block remains unchanged. This local source reconciliation does not
prove merged dependency completion or test acceptance.

| Local command | Current outcome |
| --- | --- |
| `python .\tools\android-port\controller\validate.py` | PASS; 1,866 pinned inputs, 1,786 owned and 80 excluded |
| `python .\tools\android-port\controller\verification_config.py --check` | PASS; complete protected overlay |
| `python .\tools\android-port\portmap.py` | PASS; 1,907 mappings after native App-test provenance correction |
| `ci.py local-inputs --root <own-artifacts>/wp-readiness --jdk <declared-JDK> --sdk <declared-SDK>`; `ci.py preflight --state <own-artifacts>/wp-readiness/environment.json --output <own-artifacts>/wp-readiness/result --local` | PASS in WSL using the declared Python/JDK/SDK and converted own Git pointers |
| Affected Gradle tests/build/lint | NOT RUN: retained native execution ownership/runtime migration blocks execution; zero test-discovery acceptance claimed |

No push or PR is permitted for this batch until its affected local tests,
build/lint and validators pass with positive discovery. No Android-device,
iOS, hardware, signing, legal or whole-WP parity acceptance is claimed.
Reproducible hosted results, once published, remain authoritative at the exact
candidate job/log; no duplicate CI-result bundle is retained.

## Historical initial contribution (not current repair acceptance)

**External contribution:** prepared outside the dispatch controller, with no lease
or receipt. This lists the commands that actually ran and their observed results.

- **Reference:** `db14559b39d32322b06477c6ae676112f583db50`
- **Base:** `main` @ `7237727f`
- **Host:** macOS 26, JDK 21 (Homebrew `openjdk@21`), Gradle 9.8.0 via the wrapper

| Command | Observed |
| --- | --- |
| `./gradlew :core:services:test validateModuleGraph --rerun` | `BUILD SUCCESSFUL`; `:core:services` **202 tests, 0 failures, 0 errors, 0 skipped** |
| `./gradlew :core:services:test --rerun` (3 forced runs) | 202 / 202 each time |
| `./gradlew -I jdk17-release.init.gradle :core:services:compileKotlin --rerun-tasks` | `BUILD SUCCESSFUL` (`-Xjdk-release=17`) |
| `python tools/android-port/portmap.py` | exit 0 |
| `python tools/android-port/controller/validate.py` | exit 0 |
| `sh swift-oracle/run.sh` | outputs in `swift-oracle/*.out` |

All **155** source case ids that `docs/android/test-cases.json` assigns to WP-214's
seven test files run under their exact ids. None are missing or duplicated. The one
parameterized family (`performAdvertContactSync with zero watermark does not
sync(fullRefetch : Bool)`) runs once and iterates both Swift arguments.

| Swift file | Ids | Kotlin test class |
| --- | ---: | --- |
| `SyncCoordinatorTests.swift` | 57 | `SyncCoordinatorTests`, `SyncCoordinatorContactTests` |
| `SyncCoordinatorTimestampTests.swift` | 35 | `SyncCoordinatorTimestampTests` |
| `SyncCoordinatorMessageHandlerTests.swift` | 26 | `SyncCoordinatorMessageHandlerTests` |
| `SyncCoordinatorChannelSkipTests.swift` | 13 | `SyncCoordinatorChannelSkipTests` |
| `DevicePlatformSyncThrottlingTests.swift` | 13 | `DevicePlatformSyncThrottlingTests` |
| `ConnectionManagerResyncLoopTests.swift` | 9 | `ConnectionManagerResyncLoopTests` |
| `SyncCoordinatorDataEventTests.swift` | 2 | `SyncCoordinatorDataEventTests` |
| native `WP-214::` cases | 47 | `SyncNativeLifecycleTests`, `SyncNativeIngestionTests` |

- **Timing:** every throttle, watchdog, retry and backoff test runs on a virtual clock
  driven by a single-thread dispatcher. There are no wall-clock sleeps. The suite
  fails on deadlock and on uncaught service-scope exceptions.
- **Independent review:** a read-only review of the port against the Swift found no
  behavioral divergence. Its cancellation-robustness findings were fixed and have native
  regressions; the remaining notes are documented in `deviations/WP-214.md` A-08.
- **Swift oracle:** `swift-oracle/` compiles three tiny programs against Foundation:
  `CharacterSet.whitespaces` enumerated over all scalars, `split(maxSplits:)` counting,
  and `parseChannelMessage` edge cases. The Kotlin native case pins the same outputs.
- **Mutation checks:** each behavior below was broken on purpose and the suite was
  re-run with `--rerun`. The named tests failed, then the source was restored.
  The script and the full output are in `mutations/`. All 15 mutations were detected.
  - Channel-only retry backoff fixed at 2 s; the ESP32 skip window set to zero; skip
    ignoring the last *attempted* sync.
  - Watermark: no one-second overlap; the invalid-watermark recovery latch removed.
  - Timestamp: the future tolerance made inclusive.
  - Resync loop: no max-attempt exit; no service-identity fence; no catch-all bracket close.
  - Message dedup lookup broken.
  - Sync-claim generation check removed; full sync no longer waits for the advert claim.
  - A foreign `CancellationException` treated as caller cancellation; `onDisconnected`
    made cancellable; the channel-retry cancel path leaving the bracket open.
- **Not covered here:** the real WP-208/209/210/212/215/216 services. Fakes stand in
  for them through the ports; see `deviations/WP-214.md` A-09 and the WP-303 wiring
  list. There is no Android, radio, UI or release claim.
