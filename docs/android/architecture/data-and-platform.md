# Data, concurrency and native adaptation invariants

**Status:** Proposed; WP-001 supervised dependent draft, pending human review.
Read-only source: `db14559b39d32322b06477c6ae676112f583db50`.

## Backup and persistent identity

The actual [AppBackupEnvelope and parser](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Services/AppBackupEnvelope.swift)
use envelope **v1**, JSON date strategies **secondsSince1970**, fractional seconds,
Codable Data/base64 and UUID text, sorted object keys and **zlib**, not ZIP/GZIP.
Preserve optional/omitted fields and raw enums. The twelve arrays are devices,
contacts, channels, messages, messageRepeats, reactions, roomMessages,
remoteNodeSessions, savedTracePaths, blockedChannelSenders, nodeStatusSnapshots
and discoveredNodes. Legacy missing discoveredNodes/count defaults to empty/zero;
other required arrays/counts are not silently synthesized.

Accept at most **50 MiB compressed (52,428,800 bytes)** and **512 MiB expanded
(536,870,912 bytes)**. Enforce checked bounded reads/inflate before untrusted
allocation, including truncated streams and output-overflow failure. Decode and
validate manifest counts before writes. The source version guard rejects versions
**greater than 1**, not every value unequal to 1; do not silently introduce a new
lower-version rule without reviewed source/test evidence.

[AppBackupService](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Services/AppBackupService.swift)
and [database import](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Services/PersistenceStore%2BBackupImport.swift)
match devices by public key, remap child radio IDs and dependent contact/channel/
session/message references, reconcile channel secrets/slots, and insert parents
before children in one atomic commit. Preserve inserted/merged/skipped/**dropped**
counts, per-radio dedup, unread/last-message metadata and discovered-node caps.
Preserve source export redaction and stripping of legacy Bluetooth connection
methods; a restored iOS peripheral handle is not an Android pairing.

Cancellation before commit rolls back import only, not prior process-store writes.
After commit, finish the narrow write-if-missing preference completion even if
cancelled; never tell the user the already-committed database rolled back. Android's
separate DataStore side effect needs an explicit committed-with-preference-error
outcome/recovery if it fails. Existing preferences are not overwritten. This is a
WP-203/204 adaptation requiring assertions, not license to suppress cancellation
or failures elsewhere. Android starts its own Room schema: never copy SwiftData DB files.

Keep all seven [AppBackupError](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Errors/AppBackupError.swift)
families and their actual/max size, version and underlying-cause metadata.
WP-004/203 require a **real Swift/macOS oracle in both directions**, including
decoded DTO/restore semantics, malformed/legacy cases, sub-second dates, rollback
and post-commit cancellation. Compare meaning, not compressed byte identity.
Candidate-generated round trips alone cannot prove iOS compatibility.

[DeviceIdentity](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Utilities/DeviceIdentity.swift)
derives UUID bytes from the first 16 SHA-256 public-key bytes without rewriting
version bits. This is not permission to replace every stored radioID with that
derived value: ConnectionManager preserves an existing/backup radioID and can
assign one for a new pairing. [DeduplicationKey](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Utilities/DeduplicationKey.swift)
uses UTF-8 SHA-256, uppercase four-byte hash text and source UUID formatting.
No Java nameUUID, locale/default charset, object hash or Bluetooth-address identity.

## Concurrency and wire invariants

One saved/injected serial dispatcher view confines each actor-like object; it
allows interleaving while suspended and does not promise thread affinity.
Whole GATT operations and uncorrelated firmware request/response exchanges need
an operation queue through completion/timeout. Do not hold a mutex while waiting
for a callback that needs the same mutex. Continuations complete once, validate
generation and unregister on cancellation/timeout.

[sendAndMatch](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Sources/MeshCore/Session/MeshCoreSession%2BEventWaiting.swift)
subscribes **before send** and serializes the full exchange.
[MessageService's DM path](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Services/MessageService%2BSendDM.swift)
registers the predicted ACK before sending and merges firmware ACK metadata;
late delivery must beat failed/sent bookkeeping. Do not conflate raw bytes,
ACKs or transition edges in StateFlow or use `receiveAsFlow()` as multicast.
SharedFlow has no close; preserve explicit subscriber termination, order and
registration barriers. Source lossless/unbounded event streams are not permission
for an undocumented DROP_OLDEST/tryEmit loss policy. Any resource bound needs an
explicit typed failure/backpressure policy and reviewed assertions.

Keep source SHA-256 derivation, truncated HMAC, AES-ECB block/padding behavior and
X25519/Ed25519 encodings. Local Keystore AES-GCM is separate, never a wire-crypto
replacement. Payload limits count UTF-8 bytes with metadata overhead, not UTF-16
length or Swift grapheme count; test high-bit/endian/overflow and emoji/RTL inputs.
Preserve glossary/domain units (RSSI dBm, SNR dB, battery mV, airtime seconds),
LPP/TC/ACL/DM meanings and OCV preset raw names, not localized wire values.

## Native adaptation table

| Source concern | Android contract / evidence owner |
| --- | --- |
| SwiftUI tabs/split view | Five Nav3 tab stacks, native predictive Back/IME, current-window rail/list-detail at >=600dp; WP-302 preserves selection/drafts on resize |
| MainActor/observable screen state | ViewModel/Main.immediate and immutable StateFlow state; composition performs no sends/connect/import actions; WP-303 and owning UI WPs |
| CoreBluetooth/pairing | One GATT owner and operation queue, CDM plus direct-scan fallback; association does not connect GATT or grant all FGS exemptions; WP-205/206 |
| Permissions/background radio | API31 `BLUETOOTH_CONNECT` and non-CDM `BLUETOOTH_SCAN`/`neverForLocation`; notification permission API33+, type-specific FGS permission API34+; denial/revocation is typed; WP-206/407 |
| WiFi/local network | Network-bound sockets and API37 `ACCESS_LOCAL_NETWORK`; LAN denial does not disable BLE/cached data; no internet/GMS requirement; WP-108/206/407 |
| SwiftData/UserDefaults/Keychain | Process Room/DataStore and explicit locked/lost-key/migration errors; no destructive recreation; WP-201 through WP-204 |
| Notification/ActivityKit | Optional delivery plus ongoing connection notification/widget/tile; ambient status is not automatically a qualifying promoted Live Update; WP-401/402 |
| AppIntents/deep links | Stable shortcuts/share targets and typed confirmed routes; cold-start/readiness/current-radio guards, consume once; WP-404/405 |
| Translation/NaturalLanguage | User-approved message-translation exclusion, not a deferred or future requirement; retain original text, mixed messaging/rendering and app localization |
| Maps/assets/themes | Shared licensed maps/provider attribution/offline rights; ten unlocked themes with forced-scheme rules and approved icons; WP-301/312 |

Core mesh messaging, persistence and navigation remain usable without GMS or
internet. No periodic WorkManager loop substitutes for a live connection owner;
do not promise reconnect after force-stop, pre-unlock or OEM restrictions.
Actual API/device/background, 16 KB native, backup oracle and HIL proofs remain
future gates, not conclusions drawn from these signatures or controller fixtures.
