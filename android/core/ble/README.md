# Native NUS transport

`BleTransport` implements the actual protocol module's `MeshTransport`.
`AndroidGattFacade` supplies real Android `BluetoothGatt` and
`BluetoothGattCallback` operations; `GattFacade` is the injectable test seam.
There is no production fake, Nordic dependency, scanner, permission dialog,
CompanionDeviceManager association, foreground service or ViewModel connection.
The component is GPLv3 app-side code; it does not move app error prose into the
MIT protocol module.

## Ownership and setup

The process connection owner supplies a `BleDeviceHandle` from an authorized
selection and owns transport/session teardown. An address is only an ephemeral
connection handle, not persistent radio identity. Addresses are canonicalized
to uppercase with `Locale.ROOT` before actual Android device lookup. Construct a new target's
transport after awaiting the old transport's disconnect. The host must declare
and grant `BLUETOOTH_CONNECT`; permission/power denial is typed recovery rather
than a successful empty transport. WP-206/207 own pairing/PIN UI, CDM, scanning,
reconnect intent, background permissions/execution and persistent state.

Create `BleTransport(AndroidGattFacade(context, handle), configuration)`.
The default facade uses one Android main-looper handler for both platform calls
and callbacks; the host can supply its owned callback handler. Explicit connect
uses `autoConnect=false`. API37 additionally disables automatic MTU negotiation
so one queued, checked MTU request owns that operation.

`connect()` is idempotent. `connect(BleConnectMode.Reconnect)` is an explicit
owner request, not a background loop. Defaults are 10 seconds for link setup,
40 seconds for the entire initial discovery/MTU/subscription chain, 15 seconds
for explicit reconnect discovery and 5 seconds for writes/RSSI callbacks after
their queue admission. Queued RSSI work does not consume its callback deadline.
Deadlines and write pacing use an injectable monotonic `BleClock`.

The maintainer-approved R118 contract retains this single-attempt, fresh-GATT
safety boundary, not CoreBluetooth standing reconnect or same-link restoration.
Automatic retry/background hosting and durable bond-refresh consumers remain
WP-207 integration work in [issue #390](https://github.com/cbattlegear/MeshCoreOne-Android/issues/390).
RSSI is owner-requested and cannot infer a persistent radio identity from an
address. The [R118 reconciliation](../../../docs/android/evidence/WP-205/r118-contract-reconciliation.md)
separates native adapter assertions, approved differences and outstanding consumer/device proof.

## Bytes and capability safety

NUS service/TX-write/RX-notify UUIDs match the pinned source. Discovery validates
one matching service, both characteristic objects/properties and the RX CCCD.
Connection readiness requires an actual successful MTU callback, local
notification routing and a successful CCCD write of `01 00`.

The requested MTU is not the negotiated MTU or firmware reassembly capacity.
Every command is exactly one write, never split, truncated or padded. Before
independent peer capability evidence is supplied, bootstrap commands are
limited to the conservative 20-byte ATT baseline. Larger commands fail with
`FirmwareCapabilityUnverified`, even after MTU517. This is intentionally not
claimed as an integrated MeshCore startup handshake.

After actual peer evidence is available, the owner supplies
`updateFirmwareCapabilities(currentGeneration, capabilities)`. The effective
maximum is the minimum of firmware command capacity, actual MTU minus three and
the 512-byte attribute limit. Capabilities are immutable within a generation
and must be re-established after reconnect. `writeWithoutResponse` and
`pipelinedReads` require separate peer opt-ins plus actual TX properties.
Without-response otherwise falls back to acknowledged send. Both paths share
the full-operation queue. Android's write-command callback means local stack
completion, not firmware delivery or a message ACK.

## Lifetimes and errors

Obtain `receivedData()` for each connection generation. It is a lossless
unbounded `Channel<Bytes>` queue, with exactly one active ingestion collector;
it is not a broadcaster, replay/conflation stream or a bounded total memory
claim. Attach the process/session ingestion drain continuously, not a screen
collector. Equal, empty and unknown notifications are retained. Cancellation
of a collector alone does not disconnect the physical link.

Disconnect finishes old Flow handles after queued packets drain. Unexpected
failures also terminate the Flow with the typed cause. Reconnect creates a
fresh queue and GATT. Every operation captures a generation and sequence
before triggering; callbacks validate the owning GATT and exact attribute
objects. A submitted operation's timeout/cancellation invalidates its GATT
because Android does not provide a per-write callback sequence. If its deadline
expires before submission, the unsent request fails and its waiter/timer are
removed without invalidating an otherwise ready link. Incomplete connection
setup still closes on any timeout. Old callbacks cannot complete
new-generation work. Native cleanup is requested once and its completion is
awaited before another GATT opens. Acknowledged pacing observes connection termination, so an old generation's
sleep cannot delay a new connect. Cleanup failures are surfaced or attached
as suppressed metadata; cancellation is not converted to success.

`diagnostics` is the current-state projection, not an event/ACK queue.
`BleTransportException` preserves typed source/native errors, operation/status,
cause, raw status domain and `BleRecovery`; localized UI belongs to WP-304.
Connection-state/HCI status8 is a connection timeout even during a pending
write; ATT procedure status8 is insufficient authorization. Neither the numeric
code alone nor the pending operation chooses its domain.

## Bond verification and RSSI

`readRssi()` is an explicit, serialized native operation. Completed nonzero RSSI
status throws `RssiReadFailed` without inventing a broken bond. Missing callback
or cancellation after submission invalidates the ambiguous GATT like other
operations. No periodic Android keepalive is started behind the execution
owner's back.

The source's refresh-only bond bookkeeping is preserved through
`recordBondVerification`, `clearBondVerification`, `setAppSessionLive` and
`setBondRefreshedHandler`. Radio UUIDs must come from the real identity/handshake
owner, never an address hash. RSSI refreshes only an existing verification for
the current live generation/radio. Disconnect clears session-live. Clear/reseed
changes the verification epoch; `isBondRefreshCurrent` rejects obsolete hints.
It is only a current-state check, not an atomic DataStore transaction or
permission to re-persist forgotten keys. WP-207/204 own the final persistence
epoch/transaction and the source's retry/bond-grace policy.

## Validation

With the provisioned private pinned environment, the actual task is:

```powershell
& .\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m `
  -GradleArguments @(':core:ble:testDebugUnitTest', '--dependency-verification', `
    'strict', '--no-build-cache', '--quiet')
```

Root `verifyScaffoldTests` depends on that task, following the existing locale
module pattern. Reports are the runner's complete
`core\ble\build\test-results\testDebugUnitTest\TEST-*.xml`; coordinator-owned CI
collection/replay binds their raw counters and source inputs. Required framework
scenarios target actual Robolectric SDK31/32/33/37 sandboxes. They are not
physical Android/OEM/radio certification.

See [evidence](../../../docs/android/evidence/WP-205/README.md),
[source accounting](../../../docs/android/evidence/WP-205/source-cases.json),
and [adaptations](../../../docs/android/deviations/WP-205.md).
All four framework JARs use isolated nontransitive test configurations, exact
independently verified SHA-256 metadata and offline loading. The BLE test JVM
exports `java.base/jdk.internal.access` only for Robolectric4.17's API37
shared-memory bootstrap on the pinned JDK21. No such flag reaches the APK.
Dependency admission, raw actual execution and historical failures are recorded
separately; no planned case is counted as passed.
