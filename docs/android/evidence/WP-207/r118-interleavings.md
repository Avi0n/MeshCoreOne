# R118 RADIO-R118-G15: reconnect, scan and promotion witnesses

Issue: #390. Owner: `services-porter`, WP-207. Session:
`dfc55eb6-1869-499a-b194-2cfd43d296ed`. Implementation base:
`5e6ffb716fa12ddbbbe35ebd64c1b81e3638c317`. Frozen Swift:
`db14559b39d32322b06477c6ae676112f583db50`; no reference amendment.
The PR's actual commits identify the candidate; its exact-head hosted checks
and logs are authoritative execution evidence, not this source register.

## Repair

The existing runtime suite sometimes exercised an easier branch than the
assigned original: overlapping health checks completed successfully instead of
failing inside rebuild; the stuck-transport test rejected the registry before
reaching the transport decision; the watchdog exited because the connection was
READY rather than because intent no longer wanted connection. These cases now
establish and assert their distinct entry conditions, release explicit barriers
and check the actual result, failure, teardown and consumer notification.
Adoption cases now complete through the installed link callback into a real
session handshake and service graph, including forced-sync propagation.
Cancelled adopted attempts are asserted as cancellation, not ignored results.

Eight supplemental `ReconnectInterleavingTest` cases use the real
`ConnectionManager` and `MeshCoreSession`, factory-owned test services and
independently encoded wire replies. Three pin reconnection failure during
service teardown or preserve-exhaustion diagnostic persistence. A newer manual
connection or user stop must invalidate that old failure's loss notification
and watchdog authority. Production now rechecks revision, retained-owner and
disconnected-state ownership after each of those suspensions. Cleanup still
finishes; actual typed failures and teardown reports remain observable.

The other supplemental cases pin successful and failed initial sync, retirement
inside sync, a suspended WiFi reconnect, and physical-close completion before
fresh rebuild. They separately witness entry before asserting absence of READY,
extra connects, stale callbacks, duplicate notifications or monitor generations.
These are not new original-family credits.

## Exact 30-family register

Suite and case names below are the original stable keys without the trailing
`()`/`single` suffix. Unchanged producer tests remain attributed to their
existing WP; no family is reassigned or counted twice. Native class paths are
under their existing `android/core/{runtime,connectivity,ble}/src/test/` trees.

| # | Frozen suite / case | Actual native witness and boundary |
| --- | --- | --- |
| 1 | `BondLossPairingRecoveryTests` / awaited forget clears bond before encryption-timeout budget classifies as bondSuspect | Unchanged connectivity `BondLossPairingRecoveryTest`: verified map is nonempty, awaited real pairing-coordinator removal clears the same link policy map, then the full failure budget produces typed authentication/BondSuspect. Pure native policy equivalent; not an actual Android GATT retry-loop claim. |
| 2 | `ConnectionManagerAutoReconnectEntryTests` / entry during in-flight manual connect for the same device adopts the flow | Runtime `SourceReconnectLifecycleTest`: AppStart frame submitted, manual call suspended, cycle claimed, no physical close, original caller explicitly cancelled, no stale service graph. |
| 3 | Same suite / entry stands down when a manual connect is in flight for a different device | Same native class: actual successor transport connect entered and held; retired callback recorded as stale; complete snapshot preserved; successor reaches READY. |
| 4 | Same suite / entering auto-reconnect while wanting connection notifies the loss once | Same native class: zero notifications/teardowns beforehand, one loss, one teardown, stopped reader and retained physical link afterward. |
| 5 | Same suite / entering auto-reconnect after the user disconnected does not notify the loss | Same native class: actual user disconnect closes once; late callback is independently recorded as stale, no cycle or watchdog. |
| 6 | Same suite / manual-connect stand-down for a different device does not notify the loss | Same native class: pending successor witnessed before the callback; stale entry produces no auto-loss; successor completes READY. |
| 7 | `ConnectionManagerBLEHealthTests` / returns early when shouldBeConnected is false | Unchanged connectivity `ConnectionManagerBLEHealthTest`: persisted device with `ConnectionIntent.None`, no link allocation or system-link query. Reachable native no-intent equivalent, not a forged operational graph without intent. |
| 8 | `ConnectionManagerBLEScanningTests` / startBLEScanning starts scan and forwards discoveries | Unchanged connectivity `ConnectionManagerBLEScanningTest`: waits for active scan before independent id/address/name/RSSI discovery; exact discovery and one start; explicit stop. |
| 9 | Same suite / terminated scan stream does not leave scanning active | Same native class: scan active before cancellation, consumer joined, physical gateway stopped and coordinator inactive. The adjacent old/new-stream case also preserves the successor scan. |
| 10 | `ConnectionManagerDisconnectDiagnosticsTests` / health check adopts system-connected last device when adoption can start | Runtime `SourceReconnectLifecycleTest`: adopted entry witnessed at CONNECTING, no competing connect; installed callback performs fresh handshake and exactly one monitor graph; diagnostic retained. |
| 11 | Same suite / manual connect adopts system-connected last device instead of throwing deviceConnectedToOtherApp | Same native class: forced-sync/manual adoption witnessed; no competing connect; registered callback completes READY and passes force-full-sync to the new services. |
| 12 | `ConnectionManagerPromotionTests` / promoteToReady sets .ready and fires onDeviceSynced on successful sync | Supplemental runtime successful-sync barrier plus unchanged connectivity `ConnectionManagerPromotionTest`: SYNCING and no notification before release, READY/current token/Bluetooth/one synced callback and listener afterward. |
| 13 | Same suite / promoteToReady skips onDeviceSynced when sync failed | Supplemental runtime failed-sync barrier plus unchanged connectivity case: actual typed initial-sync failure, SYNCING with issue, no synced callback or listener-success path. |
| 14 | Same suite / promoteToReady suppressed when additionalGuard returns false | Supplemental runtime retirement inside entered sync plus unchanged connectivity generation-guard equivalent: explicit cancellation, no current token/READY/synced callback, one service teardown and no reader. Runtime uses generation currency rather than an arbitrary production test closure. |
| 15 | `ConnectionManagerReconnectAbandonmentTests` / forceReconnect connect while a session rebuild is in flight for the device defers instead of breaking glass | Runtime `SourceReconnectLifecycleTest`: real replacement AppStart held, reconnect claim and frame independently observed; forced tap adds no close/attempt; released rebuild reaches READY. |
| 16 | Same suite / forceReconnect connect while transport is stuck auto-reconnecting to the same device breaks glass | Same native class: disconnected state, same-device transport auto-reconnect and no coordinator claim; restoration route callback closure witnessed; fresh connect entered and throws typed authentication failure, not an earlier registry rejection. |
| 17 | Same suite / overlapping health checks on a connected missing stack rebuild once | Same native class: retained-link/missing-graph precondition; real replacement sync entered and suspended; second health call opens no third link; injected initialization failure is logged and cleans the graph while preserving the bounded native link/watchdog policy. |
| 18 | Same suite / reconnection failure after user disconnect severs a held link and leaves the watchdog stopped | Same native class asserts user stop already closed the actual link before the subsequent failure callback. Supplemental held-link teardown/user-stop race proves that cancellation of an in-progress failure also closes exactly once and cannot notify/rearm afterward. Native user stop closes immediately rather than seeding an impossible retained graph after stop. |
| 19 | Same suite / recordConnectionSuccess refills the preserve budget | Same native class: exhaust all four failures and observe physical close before reset; reset works even with an already-closed breaker/link; subsequent failure advances from zero, not the exhausted count. |
| 20 | Same suite / user disconnect does not advance the preserve budget | Same native class: actual READY then explicit disconnect, subsequent failure does not consume any preserve budget. The supplemental suspended-teardown case separately witnesses a held link at the user-stop edge. |
| 21 | Same suite / watchdog natural exit nils the task and preserve re-arms | Same native class: real retained link and armed watchdog; stored user-disconnect intent restored through process activation without cancelling/replacing the task; original 30-second tick exits naturally with unchanged watchdog generation; subsequent genuine connection failure re-arms. |
| 22 | `ConnectionManagerSessionTests` / checkWiFiConnectionHealth returns early when reconnect in progress | Runtime `SourceConnectionManagerTest` and supplemental WiFi barrier: reconnect physically entered and suspended; health creates no additional transport; released reconnect reaches a fresh READY token and one monitor graph. |
| 23 | Same suite / connect to same device returns early while session rebuild is in progress | Runtime `SourceConnectionManagerTest`: submitted AppStart and incomplete rebuild before same-device call; unchanged snapshot and allocation count; released rebuild reaches READY. |
| 24 | Same suite / setConnectedDevice sets device | Same native class: assert different old/new node names, invoke actual identity-guarded device setter, assert exact edited DTO and unchanged live session token. |
| 25 | `PairingWhileConnectedTests` / failed switchDevice fires onConnectionLost so observers tear down | Existing runtime `SwitchFailureIntegrationTest` and connectivity `PairingWhileConnectedTest`: real switch failure, teardown ordering and one notification; throwing/reentrant observers preserve failure/successor. Retained unchanged. |
| 26 | `ReconnectRebuildLifecycleTests` / rebuild after a stopped predecessor completes its handshake over a refreshed stream | Runtime `SourceRebuildLifecycleTest` plus supplemental close barrier: old reader stopped before close; replacement cannot allocate until close finishes; real second handshake and stable radio identity/fresh generation. Existing A-02 requires physical renewal instead of retained Swift stream renewal. |
| 27 | Same suite / reconnect rebuild surfaces connected while the sync window is still open | Runtime `SourceRebuildLifecycleTest`: replacement AppStart held after actual physical connect; CONNECTED, old reader absent/new reader present; release yields READY. |
| 28 | Same suite / renewDataStream declines when the machine is not connected | Runtime `SourceRebuildLifecycleTest`: real JVM session refuses retained-link admission with typed `RetainedTransport`, no second handshake/reader/close. This is the existing A-02 safety equivalent, not an implementation of Apple's idle stream-renewal API. |
| 29 | `BLEStateMachineBondSuspectRecoveryTests` / disconnect in the discovery-complete window settles fully instead of forking | Existing BLE `BleDiscoveryTest` post-subscribe/pre-adoption disconnect and `BleOperationFailureTest` terminal/duplicate callback matrix: idle/not-connected, typed failure, exactly one physical close, no successful adoption or implicit reconnect loop. Existing bounded Android transport adaptation; not literal Apple phase execution. |
| 30 | `BLEStateMachineRestorationAndTeardownTests` / advancing the connection generation clears a stale discoveryComplete teardown error | Existing BLE `BleOperationFailureTest` timeout/replacement matrix: old operation actually expires, next physical generation connects, late old completion is independently rejected and cannot poison or complete the new generation. Supplemental runtime retired-stream callbacks additionally preserve its new READY snapshot. Native generation isolation equivalent, not direct access to Apple's private error slot. |

## Verification route and limitations

The current module already wires `:core:runtime:test`,
`:core:data:testDebugUnitTest` and `:core:datastore:testDebugUnitTest` into
`verifyScaffoldTests`. The existing affected launcher selects complete module
tasks plus reverse consumers; hosted candidate CI runs its committed scaffold
and strict module JUnit discovery, including the connectivity and BLE witnesses.
No new command, selector, task, dependency, runner or protected macro verification
amendment is introduced by this repair.

Local source checks: `python .\tools\android-port\controller\validate.py`,
`python .\tools\android-port\portmap.py`, and `git diff --check`.
The declared local candidate command is
`python .\tools\android-port\local\check.py --commit <actual-head> --affected-base <current-trusted-base>`.
Its actual admission/outcome is reported in the PR, never inferred from source
inspection. The initial isolated readiness check reported missing explicit
JDK/SDK environment. An existing advisory WP-207 claim has a historical
base/manifest binding; acquisition was attempted and refused without modifying
that identity/history. Trusted `wp-owned` path admission remains available for
this isolated worktree; no live semantic collision is inferred from old intent.

This bounded repair addresses the issue's oracle/interleaving concern. It does
not declare the full macro WP complete or configure its historical empty
verification entry. It does not claim Swift execution, actual radio/CDM/OEM
background behavior, physical bond recovery, hardware, signing or release proof.
The existing native fresh-link safety, timing and platform adaptations are not
silently replaced with literal Apple behavior.
