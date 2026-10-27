# DATA-19 lifecycle correspondence and D069 staged-failure oracle

Repository: `cbattlegear/MeshCoreOne-Android`; owner:
`data-persistence-engineer`; immutable reference:
`db14559b39d32322b06477c6ae676112f583db50`.
Source-only baseline: `5ad392c21e53d667468b279d467d81bba124d9ac`,
tree `5cb70c7aa6c04ca067c7dc8944c2f2c2d984c679`.
No checkout, fetch, rebase or default-reference/runtime change is part of
this repair. No production code, schema, global test helper or original
case identity changes.

## Retained record and independent technical disposition

The retained consolidated registry has SHA-256
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`.
DATA-19 points to `/workstreams/18` in its DATA-BACKUP result, SHA-256
`8a1897c1d38888e7ccc263892b8b732f738c69352c7d0ece3bdd28931a63ea69`.
The exact three rows are joined by `family_id`, not array order:
D069 `/families/175`, D083 `/families/189`, D148 `/families/254`.

The nested unresolved decision is retained verbatim:
"Approve the explicit native storage/lifecycle adaptation without waiving
atomicity, rollback or consumer refresh."
The outer `product_or_human_decisions: []` was not an approval.

Oversight independently reviewed the exact read-only packet and actual
pinned bodies/DTO mapping/import lifecycle/native transaction boundaries.
Its affirmative bounded technical disposition accepts D069/D148's
observable Room lifecycle counterparts and disproves D083's returned-DTO
mutation premise. D069 still needs the stronger fault oracle below.
This delegated trusted technical review resolves that retained adaptation
review condition; it is not actual human approval, new platform policy,
WP-001 architecture approval or a waiver of configured `needs-human`,
release, legal or signing gates. The architecture draft's proposed status
does not supply authority. No generic mounted-UI or bidirectional proof
follows from this disposition.

## Exact original and native correspondence

All three originals are in
`MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift`,
Git blob `5f8d9ed7837537e5945f6097c4e43ac19a8e3fd2` at the pin.

| Family | Actual pinned assertion family | Native primary and disposition |
| --- | --- | --- |
| D069, lines 2417-2449 | Autosave true; stage device; exact injected failure; restored autosave/no pending/zero devices; save and fetch one 32-byte C1-key Recovered Contact | `BackupTransactionTest.failedRestoreLeavesNoStagedRowsAndNextStoreWriteWorks`: real staged Room failure/rollback/end/subsequent persistence. Strengthen only this oracle; do not invent an autosave property. |
| D083, lines 2497-2553 | Warm 32-byte 51-key Alice snapshot; import Field Ops/blocked/7 unread/date 1700000000; skipped1/merged1; refetch and assert four fields | Unchanged `BackupMetadataIntegrationTest.processStoreReadsSeeImportedMutations` preserves those predicates. The alleged requirement for an old returned DTO to mutate is disproved. |
| D148, lines 2350-2371 | Successful public import inserts one device; autosave restored/no pending/device count1 | Unchanged `BackupTransactionTest.successfulRestoreFinishesRealTransactionAndStoreRemainsWritable` checks inserted1, real transaction end, count1, plus existing same-store future-write supplement. Accepted bounded native observable equivalent. |

Pinned `PersistenceStore+Contacts.swift`, blob
`96f33ac980a5de8e72685a30d8b9d287805050ea`, lines 47-55, constructs
`ContactDTO(from:)` on fetch. `Contact.swift` lines 294-299 declares the
Sendable struct snapshot. D083 itself refetches: it neither mutates the
warmed value nor subscribes to an observable stream.

Pinned `PersistenceStore+BackupImport.swift`, blob
`7be09ac9a249efeb53ea5d2bd09d41ecb0c40ef7`, lines 114-137/348-359,
flushes previous changes, disables autosave, executes the precommit fault
hook and save, and defers rollback/restoration when not committed.
The current `AppBackupService` uses the actual process store's
`withBackupRestore`; `RoomRepositoryContext.restoreBackup` serializes and
flushes prior RX work separately before `committedTransaction` executes
real `database.withTransaction`. Ordinary later writes use the same
process-owned context. No fake persistence or parallel restore map is used.

## D069 strengthening

An earlier validation failure could previously satisfy the generic
`expectImportFailure` helper, leaving an empty destination without ever
reaching the intended staged fault. That is a test-oracle weakness, not a
reproduced production rollback defect.

The primary now records exactly one `beforeCommit` call, checks
`db.inTransaction()`, and requires the one staged device's radio ID,
public key and node name to match its explicit input. It throws a retained
IOException sentinel; the result must be `AppBackupError.ImportFailed`
with that exact object at the terminal cause. Terminal identity permits
ordinary coroutine exception-recovery copies without a lenient message
match or changed error policy.

After failure, no device remains, the transaction is inactive, and the same
store persists and refetches exactly one Recovered Contact with full
32-byte C1 key. The global helper and other methods are unchanged.

## Actual consumer refresh and limits

`ContactDao.observe` is a real Room invalidation Flow.
`RoomPersistenceStore.observeContacts` and `RoomRepositoryContext.observe`
project fresh immutable DTO snapshots; the existing repository observation
control checks partitioning and process-close termination for ordinary
writes. It does not assert an ongoing subscription across backup import.
Such a supplement is not a missing assertion of the original D083.

The actual App uses a different, explicit chain:
`AppBackupFeature` returns committed results through its restored callback;
`AndroidAppContainerFactory` bumps contacts/conversations versions and
calls `AppState.notifyDataRestored`. Those StateFlows feed
`AppChatsFeature.reloadRequests`; a started `ChatListStateHolder` refetches
process-store rows and replaces its immutable visible snapshot.
Existing `BackupRegistrationTest` and `SessionStateTest` support process
effects/version/registry behavior, not a mounted D083 nickname merge through
the production factory. No old DTO mutation, generic refresh acceptance or
new execution credit is inferred.

Existing WP-201 immutable-value/identity/lifetime and WP-202 transaction/
pending-overlay adaptations remain in place. WP-203's admitted restore
bridge retains prior-work isolation and typed failures. Known App assembly
and Settings producers remain WP-407/WP-318; this repair edits neither.

## Bounded current-main comparison and ownership

Authenticated read-only current main was
`9bae2a91313508a89f352e202f9c31f60d081f65`, tree
`e87038aa30455e1b6d8925c04d330256b7e112c4`.
A nontruncated tree comparison of 52 bounded CoreData/backup/lifecycle
consumer paths against frozen 5ad found one changed path:
`AndroidAppContainerFactory.kt`, local blob
`b530ac6f324d241dc7b9e28e20763fe1385ce4e1`, main blob
`e29738534fb5f823f701d34d876d90fbff76addc`.
Its actual diff only imports `flow.first` and adds `AppAdvertLocation`
dependency wiring. The backup callback is unchanged; the GPS change is
disjoint and is neither copied nor edited. This is not a global drift
certificate or a claim that current main has passed native checks.

Supported `reserve.py admit` accepted all three repair paths as `wp-owned`
for `modify`. Supported same-owner evolution advanced db06 revision 7 to 8
without changing identity, historical binding or authorization prose.
The advisory status showed broad historical WP-000/WP-311 intents, not
another exact D069 producer intent or proof of a real semantic collision.
Only the owned test, this document and WP-203 deviation are changed.
Own fresh local-input/preflight readiness reported
`environment_ready: true`; it is source-preparation readiness, not a heavy
execution allocation, current remote-runner proof or a passing test.

## Source checks and future execution boundary

The declared lightweight source checks passed locally before freeze:

| Exact command | Actual result |
| --- | --- |
| `git diff --check` | Passed; no whitespace errors |
| `python -B docs\android\evidence\WP-203\collect_evidence.py --inventory-only` | 171 originals, 171 bound, 221 native declarations, zero unbound; static inventory only |
| `python -B tools\android-port\controller\validate.py` | Valid: 64 WPs, 178 dependency edges; manifest SHA-256 `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` |
| `python -B tools\android-port\controller\verification_config.py --check` | Valid; not human/dependency acceptance or native execution |
| `python -B tools\android-port\test_inventory.py --check` | Checked: 468 paths, 5133 frozen source declarations; no execution credit |

No compilation, Gradle/JVM, Swift or device run is authorized by this
source grant. These source checks do not prove the changed assertion
passes at runtime.

The future exact-candidate run must positively discover and pass these
five fully qualified methods without skipping or filtering away the
complete selected suites:

1. `com.meshcoreone.android.core.data.backup.BackupTransactionTest.failedRestoreLeavesNoStagedRowsAndNextStoreWriteWorks` (D069).
2. `com.meshcoreone.android.core.data.backup.BackupMetadataIntegrationTest.processStoreReadsSeeImportedMutations` (D083; unchanged).
3. `com.meshcoreone.android.core.data.backup.BackupTransactionTest.successfulRestoreFinishesRealTransactionAndStoreRemainsWritable` (D148; unchanged).
4. `com.meshcoreone.android.core.data.backup.BackupTransactionTest.preCommitFailureRollsBackRowsAndMetadataTogether` (unchanged support only).
5. `com.meshcoreone.android.core.data.repository.NativeRepositoryBoundaryTest.observationIsPartitionedAndCompletesWhenTheRepositoryCloses` (unchanged support only).

The two support controls receive no extra original-family credit.
Then-installed trusted planning, fresh inputs/admissions and a separate
heavy grant remain required; no old task list or DATA-18/171-binding PASS
is transferred. Publication needs its own distinct exact-head grant.
No DATA-05/DATA-18 reopening, translation, source-pin advance, whole-WP
acceptance, fresh Swift/iOS/bidirectional/device/legal/signing proof or new
actors are part of this source-only repair.
