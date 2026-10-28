# WP-205 R118 native contract reconciliation

Repository: `cbattlegear/MeshCoreOne-Android`. Issue: [#383](https://github.com/cbattlegear/MeshCoreOne-Android/issues/383).
Owner: `connectivity-engineer`; implementing session:
`9ff76f27-34e3-4d6d-9fde-5e0b50d84861`.
Base: `598f8683e775599ef2d8efd587a5574cc259090c`.
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest SHA-256: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Policy revision: `79f6c46a4192aeeac807d1326126eac57c6db70b36a6f011afe00ca002a48f3b`.
The PR's immutable commit and exact-head hosted checks bind the candidate;
this document does not duplicate a CI result bundle.

## Decision and ownership

The maintainer selected "Retain the bounded native contract; track
retry/background integration under WP-207 (Recommended)". `RADIO-R118-G11`
therefore retains one owner-requested attempt, bounded discovery and physical
close on ambiguous submitted-operation timeout/cancellation. `connect(Reconnect)`
uses a fresh GATT and a 15-second total discovery budget, not 15 seconds per
stage. Initial setup remains 10 seconds for connection and 40 for discovery;
default writes/RSSI remain 5 seconds after queue admission.

No background retry, standing OS reconnect, adopted foreign GATT, same-link
stream renewal or periodic RSSI guarantee is introduced. Native explicit-close
safety is not source renewal/continuity parity. Retained `ReconnectPolicy`
classification helpers do not establish that a live adapter executes the
source's five-failure majority, two discovery extensions or six-hour grace.
No new Apple-like episode or inferred invalid-bond error is added to BLE.

The actual WP-108 prerequisite PR #434 merged as
`5935af530743662e0caf95338f0d881ab58382c0`, verified as an ancestor of this base;
historical WP-108 PR #11 also remains distinct. Reservation acquisition was
attempted and reported an existing lease bound to a different
source/base/manifest/backend. Trusted `reserve.py admit` independently admitted
the test, README, deviation and new evidence paths via `wp-owned`. Historical
advisory metadata is not a live semantic collision; no ledger row or old
identity/authorization was changed.

Automatic retry/background ownership, factory-created replacement services,
handshake identity and durable bond-refresh epoch/forget races are handed off
on [existing WP-207 #390](https://github.com/cbattlegear/MeshCoreOne-Android/issues/390#issuecomment-6452945726),
with WP-206 pairing and WP-303 graph consumers retained. That handoff is not a
new worker, dependency completion or permission to edit their code.

## Full issue-family register

The issue's 95 G11 and 17 G12 families are 112 distinct original families,
not the older component catalog's 86 declarations. The following source groups
account for all 112; filenames are under
`MC1Services/Tests/MC1ServicesTests/`. The issue retains each exact stable key
and original assertion/parameter body. Group accounting does not claim that
every family is passed or equivalent.

| Original source | Issue families | Native correspondence and remaining boundary |
| --- | ---: | --- |
| `BluetoothScanPairingServiceTests.swift` | 1 | macOS registry inertness has no Android system-registry API predicate; WP-206 pairing/association owns the actual Android consumer. |
| `BondLossPairingRecoveryTests.swift` | 1 | WP-207 durable forget-before-classification integration; BLE in-memory epoch checks cannot substitute. |
| `ConnectionManagerDisconnectDiagnosticsTests.swift` | 3 | WP-207/WP-206 actual manual/health adoption and preserved-intent diagnostics; no foreign-GATT adoption or guessed competing-app error in BLE. |
| `PairingStrandedAssociationTests.swift` | 2 | WP-206 association/selection consumer proof, not address-to-radio identity equivalence. |
| `PairingWhileConnectedTests.swift` | 2 | WP-207 pair-wait/rebuild/old-device teardown interleavings remain actual lifecycle obligations. |
| `ReconnectRebuildLifecycleTests.swift` | 4 | Approved fresh-generation/terminated-stream adaptation, not same-link renewal or RSSI continuity; WP-207/WP-303 must prove replacement handshake and drain. |
| `Transport/BLEPhaseTests.swift` | 2 | Idle null handle projects absence only; it is not equality between a UUID and an Android address. Existing `BleValuesTest` predicates remain. |
| `Transport/BLEStateMachineAutoReconnectRetryTests.swift` | 6 | Approved no-implicit-rearm adaptation. Native attempt terminates with typed failure; source foreground/background retry episodes are not claimed. |
| `Transport/BLEStateMachineBondSuspectRecoveryTests.swift` | 2 | Native timeout closes rather than guessing auth from a stall; existing discovery-complete auth teardown assertions preserve actual terminal classification. |
| `Transport/BLEStateMachineDisconnectionMappingTests.swift` | 5 | Real ATT auth codes versus connection-state status domain remain distinct. Native generic discovery failure is not inferred bond loss. |
| `Transport/BLEStateMachineEmptyGATTHoldTests.swift` | 8 | Approved fail-closed missing-service/attribute/subscription behavior, not an OS empty-GATT hold. Existing discovery tests cover absent and partial setup; callback ownership replaces timestamp fencing. |
| `Transport/BLEStateMachineFringeEncryptionGraceTests.swift` | 8 | No BLE majority/grace episode is claimed. Explicit bond loss remains definitive; runtime helper policy and its consumer obligations remain WP-207. |
| `Transport/BLEStateMachineRestorationAndTeardownTests.swift` | 6 | No parked Bluetooth/restoration API exists here; native pending-operation cancellation/close, idle no-resurrection and typed write failures are explicit equivalents/adaptations, not identical Apple predicates. |
| `Transport/BLEStateMachineTests.swift` | 12 | Idle activation has no original central-manager invocation-count assertion. Unknown UUID required only `BLEError`. Native handle validation, generation fencing and finite discovery replace UUID lookup/timestamp tolerance/watchdog extension; no source invocation count or extension is fabricated. |
| `Transport/BLEStateMachineWriteWithoutResponseTests.swift` | 7 | Android callback completion plus serialized operations replaces peripheral-ready continuation; firmware opt-in supplements characteristic capability. Both write modes retain timeout/disconnect/cancellation release. Source's 0.5-second configured backstop is exercised explicitly, not replaced by the 5-second default. |
| `Transport/BondShieldRefreshTests.swift` | 13 | Explicit live generation/radio and existing verification are required. Actual facade RSSI assertions supplement existing component tests; persistent forget/epoch transaction and zombie shielding remain WP-207/204 consumer proof. |
| `Transport/ReconnectPolicyTests.swift` | 30 | Pure helper type/status/budget/grace predicates live under WP-207; they are not proof of BLE live rearm, Apple error prose or background liveness. Preserve the full original parameter families there. |

`RADIO-R118-G12` additionally requires exact callback/API qualification:
API31/32 mutable-value writes versus API33+ immutable overloads retain bytes and
return-status failure. API37 explicit settings are tested in the real framework
sandbox. GATT/characteristic/descriptor object identity and generation fencing
reject old callbacks; Android cannot identify an OEM duplicate completion for
the same current GATT/attribute after a previous operation already completed.
No invented operation identifier conceals that physical limitation. A write
command callback proves local stack completion, not firmware delivery or ACK.
Generic write errors retain typed `WriteError`, numeric status/domain and
deterministic reason tokens rather than the source's localized description.

## Added real-facade regressions

All five methods in `AndroidGattAdapterTest` are inherited by the existing
SDK31/32/33/37 runners (20 additional test bodies, not 20 original families):

- `native reconnect bounds every discovery stage without silently rearming`:
  discovery, MTU and subscription remain pending at 14,999ms, fail at 15,000ms,
  close/unregister exactly once, join timers and issue no further work over a
  controlled day. No periodic RSSI/retry is silently enabled.
- `cancelled native writes require a fresh GATT and reject old completion callbacks`:
  both actual write types submit once; cancellation propagates, closes and
  removes timers. Replacement loses old capabilities; old write/disconnect
  callbacks cannot settle the new pending write, whose current callback is the
  positive control.
- `native acknowledged and command writes retain the half second timeout backstop`:
  both modes stay pending at 499ms, fail at 500ms with typed operation recovery,
  close once, and cannot be revived by late native completion.
- `native RSSI refresh never infers a radio identity from a reused address`:
  actual RSSI before handshake never refreshes; a supplied current radio does.
  Reusing the address after reconnect neither carries live identity nor accepts
  the old generation. A different explicitly supplied radio refreshes only its
  own record. This proves no durable store transaction.
- `native non authentication write errors preserve status and typed write recovery`:
  ATT write-not-permitted (3) and generic GATT failure (133) remain `WriteError`
  with numeric/domain/operation and fresh-connection recovery, complementing
  existing real auth-code 5/8/12/15 and HCI-timeout assertions.

## Verification and remaining gates

The current macro manifest still says `verification.configured=false`; it is
not silently rewritten. The real BLE task is independently documented and
wired by the merged module README/build: `:core:ble:testDebugUnitTest`, with
strict dependency verification and complete nonzero JUnit reports. Current
candidate execution must come from the declared supported launcher/hosted CI.

Before publication:

- `python tools\android-port\controller\validate.py`: passed, all 1,866 frozen
  inputs, 64 WPs and 178 dependency edges; source pin unchanged.
- `python tools\android-port\portmap.py` and
  `python tools\android-port\controller\verification_config.py --check`:
  passed; no source provenance or canonical verification amendment changed.
- `git diff --check`: passed. Static comparison against the base preserved
  every original/input record, added only the seventeen native method names
  described above, and confirmed all four real SDK subclasses inherit the five
  new methods. Static coverage is not executed Kotlin evidence.
- `python tools\android-port\controller\ci.py preflight --local --output <session-files>\ble-preflight`:
  BLOCKED, explicit `--state`/`ANDROID_CI_STATE` unavailable.
- Shared reservation status reports an existing hard `gradle-execution` owner.
  No lock removal, direct Gradle invocation or alternate native route is used.
  New Kotlin bodies are an explicitly unverified candidate until hosted CI.
- `python docs\android\evidence\WP-205\source-map.py --check`: failed before
  these edits with "Original/native accounting is stale or malformed".
  Inspection isolated the drift to twelve previously landed pacing method
  names missing from the static native-method index. Regenerating with the
  existing `source-map.py` adds those names and these five methods only;
  all 27 input records and 86 original-case mappings are unchanged. The check
  then passes. No historical execution results are changed or regenerated
  into purported current 112-family parity.

Physical API/OEM/radio/background behavior remains under the existing
WP-505/hardware scenarios. Cross-WP mounted consumers, real persisted epochs
and original retry/restoration episode coverage remain explicit on #390.
Neither this policy decision nor passing software assertions closes those
gates or establishes whole-WP acceptance. Issue #383 must not be automatically
closed as a side effect of this bounded repair.
