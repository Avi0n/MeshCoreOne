# DATA-18 source-input and native-width correspondence

This is the bounded follow-up after DATA-05 merged in #359, not a new
5022-family investigation or a claim that nine product bugs exist.
START was one clean fast-forward to
`61fc048e1780173e2603013a8b6d0233d0d63c3b`, tree
`ce60b225afcc45aa7a706d608e57887b28bdcc9a`, on the existing managed branch.
Owner: `data-persistence-engineer`; active app session
`45d45717-de7e-4453-a346-d92d9db74900`. Stored advisory identity
`db06cf69-de56-4b94-8f6c-38c951925c1b` and its historical binding and
authorization prose are unchanged. Trusted same-owner evolution 6 -> 7
adds only this WP-owned evidence path.

## Independent source measurement

The read-only retained consolidated artifact SHA-256 is
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`.
Only DATA-18 and its nine selected family rows were read. Its linked
DATA-BACKUP result was independently checked against SHA-256
`8a1897c1d38888e7ccc263892b8b732f738c69352c7d0ece3bdd28931a63ea69`.
The original `/workstreams/17` pointer belongs to that linked result,
not the consolidated artifact root.

Actual frozen source, not a synopsis, is authoritative at
`db14559b39d32322b06477c6ae676112f583db50`:

- `MC1Services/Tests/MC1ServicesTests/AppBackupServiceTests.swift`, Git blob
  `46ad10a5f52a5cb599cbd88ac7aa10151249dc0e`.
- `MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift`, Git blob
  `5f8d9ed7837537e5945f6097c4e43ac19a8e3fd2`.

Both source blobs equal the current START checkout. The consolidated
root-cause wording overgeneralizes the source widths: seven listed channel
families explicitly use **16** bytes at the immutable pin, not 32.
They already match the native fixture width and are not newly missing work.

| Retained family | Exact source input | Existing core test | Bounded disposition |
| --- | --- | --- | --- |
| D026 compression of twenty contacts | `i = 0..<20`: 32 repeats of `i % 255 + 1`, followed by one byte `i / 255`; 33 bytes total | `compressionHasActualRatio`: previously 32-byte `key(i)` | Use the exact 33-byte inputs; retain 32-byte compression as a separately labelled supplemental test |
| D043 collision, relocation and message | B0/A0 repeated 16 times | `relocatedChannelCarriesMessageAndRewritesLeadingDedupOnly` | Already source-width exact; preserve existing relocation/message/dedup predicates |
| D044 no free slot and child-message drop | 10/11/A4 repeated 16 times | `fullRadioDropsForeignChannelAndItsMessage` | Already source-width exact; preserve drop/capacity predicates |
| D046 re-import relocation dedup | B2/A2 repeated 16 times | `relocationDedupRewriteIsIdempotent` | Already source-width exact; preserve inserted/skipped/message-uniqueness predicates |
| D047 reserve public slot 0 | B6/A6 repeated 16 times | `relocationNeverConsumesReservedPublicSlot` | Already source-width exact; preserve destination 2/public-slot reservation |
| D048 same secret, same slot | 44 repeated 16 times | `sameSecretSameSlotMergesAndPreservesMaxDate` | Already source-width exact; preserve metadata/date/association predicates |
| D049 same secret, different slots | 55 repeated 16 times | `sameSecretDifferentSlotRemapsWithoutInsert` | Already source-width exact; preserve remap/no-duplicate predicates |
| D084 preserve local different-secret channel | 01/02 repeated 16 times | `distinctSecretCollisionKeepsLocalAndRelocatesForeign` | Already source-width exact; preserve local row/separate relocated row predicates |
| D152 affected draft slots, excluding merges | C1/C4/D5/D4 repeated 32 times | `affectedSlotsIncludeOnlyInsertedAndDroppedOccupancy`: previously 16-byte helper | Use exact 32-byte inputs in the core primary; retain the old 16-byte predicate as separately labelled supplemental coverage |

The existing
`android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/FreshStoreBackupAcceptanceTest.kt`
already contains `original32ByteDraftSlotsReportOnlyFreshAndRelocatedOccupancy`.
It uses the original 32-byte secrets, real Room, exact affected set and
unchanged local rows. It is not labelled absent or newly implemented here.
It is not edited, re-owned or credited as an execution in this batch.

## Measured boundaries, not a new product adaptation

`ChannelDTO`, `ContactDTO`, `RadioBackupJson` and `BackupDatabaseRestore`
retain raw `Bytes`. The backup codec and persistence identity do not
silently convert 33-byte contact keys or 32-byte channel secrets to their
wire projections. No production/schema/cryptographic validity policy is
changed by this repair.

The production boundaries are deliberately different:

- `MeshCoreSession.getContact` and other full-key operations call
  `requireFullPublicKey`, which requires exactly 32 bytes and throws typed
  `MeshCoreException.InvalidInput` otherwise. A raw backup contact is not
  declared a valid live-wire public key here. This guard was read, not
  executed or loosened by this batch.
- `MeshCoreSession.setChannel` accepts secrets of at least 16 bytes.
  `PacketBuilder.setChannel` emits the first 16, and `WireCrypto` uses the
  first 16 while rejecting shorter secrets. It would be inaccurate to call
  every 32-byte channel secret invalid under the current API. The new
  boundary test measures full backup JSON/codec preservation separately
  from the existing 50-byte command/16-byte secret projection and typed
  short-secret crypto rejection.

D026 now reads the actual exported compressed body with an independent
JDK raw-DEFLATE decoder, bounded to 64 KiB for the twenty-row test fixture;
checks strict compressed-size reduction; compares actual JSON base64 with
independently encoded source bytes; and verifies full decoded keys and
unchanged real stored keys. It does not derive expectations from
`redactedForBackup` or the candidate compression/decompression helper.

D152 now performs an actual JSON/compressed-codec round trip before real
Room restore. Both the exact 32-byte source test and separate 16-byte native
supplement preserve inserted/skipped counts, full raw secrets, the exact
affected-slot set, exclusion of merge/source/occupied slots and unchanged
local occupants. No live-wire operation is used to silently canonicalize a
backup fixture.

## Execution and acceptance boundary

These are authored assertions and static source measurements, **not JVM
PASS**. At assignment, all three shared slots were occupied; no new
Gradle/guard/runtime installation is authorized or launched here. Any later
candidate plan must be derived from the then-installed trusted authority
and actual diff, not copied from the DATA-05 seventeen-task plan.

The following declared lightweight checks passed locally before freeze:

| Exact command | Actual result |
| --- | --- |
| `git diff --check` | Passed; no whitespace errors |
| `python docs\android\evidence\WP-203\collect_evidence.py --inventory-only` | 171 originals, 171 bound, 221 native declarations, zero unbound; static inventory only |
| `python tools\android-port\controller\validate.py` | Valid: 64 WPs, 178 dependency edges; manifest SHA-256 `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` |
| `python tools\android-port\controller\verification_config.py --check` | Valid; not human/dependency acceptance or native execution |
| `python tools\android-port\test_inventory.py --check` | Checked: 468 paths, 5133 frozen source declarations; not executed/ported-feature evidence |

No compiler/test result is inferred from these checks.

Required later evidence includes nonzero original-binding/backup discovery,
both source-width and canonical supplemental methods, complete declared
affected module checks, exact head/tree/base and supported cleanup. A
distinct exact-head publication grant is still required. No earlier
DATA-05 PASS, historical Swift execution, new bidirectional codec result,
device/hardware/legal/signing acceptance or whole-WP completion is claimed.
The immutable pin, original text, app localization, envelope v1, Unix
seconds, Codable bytes/UUIDs, 50 MiB compressed/512 MiB expanded limits and
GPL/MIT notices remain unchanged; translation is not re-admitted.
