---
name: swift-to-kotlin
description: Port MeshCore Swift types, actors, streams, wire bytes, identity, text and backup codecs to Kotlin without changing observable behavior.
---

# Swift-to-Kotlin behavioral mapping

Read the actual source and tests before choosing a mapping. Swift extension files may combine into
one Kotlin class; a platform-specific Swift class may split into domain policy and Android adapters.
The source ownership/acceptance map accounts for all behavior across those boundaries.

## Types, bytes and identity

| Swift | Kotlin requirement |
|---|---|
| `struct`/Sendable DTO | Immutable data/value type; defensively copy mutable arrays/collections |
| Associated-value enum | Sealed hierarchy with the same cases/payloads and deliberate unknown handling |
| Raw-value enum | Persist/serialize explicit source raw value, never Kotlin ordinal |
| `Int` | Choose Int or Long from actual range/overflow semantics; Swift Int is 64-bit here |
| UInt8/16/32 and signed RF fields | Explicit checked/masked wire conversion and endian helpers |
| `Data` | Immutable content-equal byte wrapper or explicit contentEquals/contentHashCode |
| Data slice | Respect offset/length and copy/value semantics; validate all bounds |
| UUID / public-key identity | Match source canonical serialization and identity algorithm |
| Optional | Nullable only when absence is genuine source semantics, not a failure substitute |
| Error enum | Typed domain error/result with preserved metadata and UI mapping |

ByteArray equality/hashCode is identity-based; do not use it directly as a data-class/map identity key.
Do not use default charset, object `toString()` or locale-dependent numeric formatting on the wire.
Mask signed bytes before widening and test high-bit/negative/truncated values. Use source endianness
and exact integer representation; do not apply network byte order by habit.

Swift UUID text commonly uses uppercase and Java UUID text lowercase. Use explicit canonical policy
where text contributes to persistence/dedup hashes. Do not replace a source stable-UUID algorithm with
`nameUUIDFromBytes`, random UUIDs or a language's unstable hash without matching vectors.
Bluetooth addresses/peripheral IDs are connection handles, not persistent radioID/public-key identity.

## Actor and task semantics

Actor-like state can use one saved `Dispatchers.Default.limitedParallelism(1)` view per instance,
with every access/callback routed through it and the dispatcher injectable for tests.
This is serialization of executing sections, **not guaranteed thread affinity** or an atomic operation
across suspension. Creating a new dispatcher view for every call does not confine shared state.

Swift actors are reentrant at suspension points; preserve intended interleavings using explicit phases,
generation tokens and owned operations. A Mutex may protect a specific transition/transaction, but:

- Kotlin Mutex is non-reentrant.
- Do not hold it while waiting for a callback that must acquire it.
- Serial dispatcher views still allow other coroutines to run while one suspends.
- GATT and firmware request/response correlation may require a full-operation queue beyond confinement.

Map MainActor UI state to Main.immediate/ViewModels, not all business services.
Task/TaskGroup become owned scopes and structured coroutineScope/supervisorScope as appropriate.
Choose supervision from actual failure semantics, not as a way to suppress child errors.
No GlobalScope or unowned job that outlives radio-session teardown.

Continuation bridges use a single-completion primitive, generation checks and cancellation cleanup.
Check-then-resume alone may race; remove queued operations/listeners when cancelled or timed out.
Register an event waiter before sending, and ensure a late response cannot satisfy a new request.
Inject clocks/delays; test timeouts with coroutine test scheduling, not arbitrary sleeps.

CancellationException normally propagates. A backup restore that already committed may finish a small,
documented preference side effect non-cancellably; do not report rollback when rows already persisted.
Do not wrap all operations in a catch-all or swallow cancellation inside retry loops.

## Streams and observable state

- A single-consumer transport stream may use Channel/callbackFlow; preserve framing and close behavior.
- A broadcaster needs multicast semantics. `receiveAsFlow()` distributes messages among collectors;
  it is not broadcast.
- StateFlow represents a current value and conflates equal updates; it is not a queue for every ACK.
- SharedFlow replay/buffering/overflow must match the source. Replay 0 may lose events before subscription.
- SharedFlow does not close like AsyncStream. Model terminal/failure state or use per-connection flows
  and scoped collection so teardown actually stops consumers.
- Lifecycle-aware UI collection must not shut down a process-owned live-radio ingestion pipeline.

Do not silently drop raw transport data or correlation events. If the source permits lossy display
updates, preserve that specific policy without extending it to protocol input. Backpressure/overflow
has an explicit tested outcome.

## Text and locale

Swift String.count counts grapheme clusters; Kotlin length counts UTF-16 code units. Neither is the
mesh payload byte limit. Measure the encoded UTF-8 payload and include source metadata/format overhead.
Truncation must not split a code point/encoded sequence.
Test emoji, ZWJ sequences, combining accents, CJK, right-to-left content and malformed input.
Use Android ICU/source-equivalent text rules deliberately; word/emoji segmentation can vary by SDK.

Match case folding, normalization, trimming, hashtag/mention/reaction hashing and URL safety rules.
Use locale-neutral protocol formatting; localize user-facing dates/units separately.
NSDictionary/Dictionary iteration is not a portable ordering guarantee; sort when the source codec
or query requires determinism. Preserve list/query order explicitly.

## Backup codec: this repository's actual format

`AppBackupEnvelope` version 1 and its helper encoder/decoder explicitly use **secondsSince1970**.
Do not assume the generic Codable default 2001 epoch.
Preserve fractional date precision, Codable binary/base64 encoding, UUIDs, enum/raw-value representation,
field omission/null/defaults and legacy discovered-node behavior.
Compression is zlib, not a ZIP archive or GZIP substitute.

Existing limits: 50 MiB compressed and 512 MiB expanded. Enforce checked bounded streaming before
untrusted allocation. Validate envelope version and manifest counts before mutating the store.
Import matches devices by public key, remaps child radio IDs, inserts parents before children and
commits atomically. Source preference completion semantics remain deliberate after commit.
Android starts its own Room schema; only compatible backup data crosses platforms, not SwiftData files.

Use a real Swift/macOS codec oracle in both directions. Compare decoded/restore semantics, not exact
compressed bytes, and do not generate expected exports solely with candidate Kotlin code.

## Crypto and platform adapters

Wire crypto must match the firmware: SHA-256 derivation, source-truncated HMAC, AES-ECB block/padding
rules, X25519/Ed25519 key format and conversion. Do not replace the wire scheme with AES-GCM.
Keystore AES-GCM is for local secrets and is a distinct adapter.
Use vetted JCA/BouncyCastle lightweight operations; do not replace Android's global provider or invent
new curve code. Invalid authentication/key/packet inputs have typed failure and negative tests.

Apple frameworks map behind Android adapters: persistence, BLE/network, notifications, maps/location,
media, app language/localization and secure storage. Message translation is removed from active scope.
Preserve availability/permission/error state rather
than substituting unsupported framework calls or fake success.

## Tests and acceptance

Translate assertions and scenarios, not only syntax. Cover equality/overflow/boundaries, stream delivery,
teardown/reconnect, cancellation, malformed packets and transactional failure.
Independent golden vectors and reference-codec evidence are mandatory where assigned.
An unreachable scaffold is recorded as scaffold; a reachable placeholder is not a completed port.
