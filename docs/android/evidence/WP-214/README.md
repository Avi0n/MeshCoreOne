# WP-214 evidence

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
factory-input regression are authored. The sole `ConnectionManager` constructor
call remains deliberately untouched until the WP-201 producer handoff, so this
partial candidate is not build-complete or accepted.

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
