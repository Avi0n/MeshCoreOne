# WP-000 backup post-commit delivery support

## Assignment, ownership and observed refusal

This new bounded support amendment follows merged PRs #334 and #338; their
authorization/evidence is not rewritten or inherited as a publication exception.
The existing coordinator assigned the existing WP-000 policy producer to unblock
WP-203 DATA-05/D149. The clean managed branch fast-forwarded to trusted main
`610f606baaaf38eb2a2b14cb054b18fedba58a1f` before source edits.
The source pin remains `db14559b39d32322b06477c6ae676112f583db50`.
Tracked port-orchestrator/common WP instructions apply; custom profile injection
is not claimed. No new actor, runtime installation, job, resource drain or settings
change is part of this batch.

At that base, actual `reserve.py admit --wp WP-203 --path <path> --operation modify`
calls exited 1 for both `AppBackupFeature.kt` and `BackupRegistrationTest.kt`:
no trusted path+operation rule. Generic App wiring is not this repair purpose.
The same-owner WP-000 claim attempt reported an existing source/base/manifest/
backend binding mismatch. That history is retained, not released/recreated,
impersonated or manually rewritten. Every edited policy/test/doc path separately
passed existing `wp-owned` admission.

The existing WP-203 worker `45d45717-de7e-4453-a346-d92d9db74900` retains its
stored identity `db06cf69-de56-4b94-8f6c-38c951925c1b` and owns all product edits.
WP-203 primary backup-side tests continue under its existing authority; this
support batch supplies no original-family acceptance or consumer implementation.

## Source contract and minimal exact capability

Pinned `MC1Services/Sources/MC1Services/Services/AppBackupService.swift` explicitly
ignores cancellation after `importBackupDatabase` commits, completing write-if-
missing preferences and returning the committed result. Current Android service
implements a non-cancellable preference completion boundary. The App adapter
then calls `ensureActive` after successful service/process effects, converting
that committed success to an exception/receipt.

The existing worker identified two directly necessary downstream seams, confirmed
by reading committed source: `AppBackupStateHolder.performImport` catches
cancellation across engine return and later UI effects, replacing Success with
Cancelled/"nothing changed"; `BackupPorts` documents rollback for all cancelled/
failed restores, including already-committed outcomes. All regression execution
can stay in the existing exact App registration test.

`wp203-backup-post-commit-delivery` admits only WP-203 operation
`repair-backup-post-commit-delivery` at these four existing paths:

- `android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppBackupFeature.kt`
- `android/app/src/test/kotlin/com/meshcoreone/android/app/platform/BackupRegistrationTest.kt`
- `android/feature/settings/src/main/kotlin/com/meshcoreone/android/feature/settings/app/backup/AppBackupStateHolder.kt`
- `android/feature/settings/src/main/kotlin/com/meshcoreone/android/feature/settings/app/backup/BackupPorts.kt`

The last path admits only documentation of this exact commit boundary, not API
or unrelated feature redesign. The state holder must retain Success after engine
return when later UI effects cancel, while still rethrowing cancellation.
Pre-return/pre-commit cancellation continues to propagate with atomic rollback.
Successful process effects occur once. Real storage/refresh failures must remain
explicit; typed `CommittedBackupPreferenceException` keeps the committed result,
snapshot and original `StorageFailure`. Recovery retries preferences only and
cannot reimport rows, duplicate effects or bypass acknowledgement guards.

The real Room/service-to-state-holder regression must prove committed rows,
preferences, Success and once-only effects; distinguish pre-commit cancellation;
and preserve typed preference failure/retry behavior. Source's original test has
no preferences: adding that Android integration proof is not replayed Swift
coverage. Envelope v1, Unix seconds, Codable bytes/UUIDs, source compression and
50MiB compressed/512MiB expanded limits stay unchanged. Backup secrets and
original data are not logged. WP-203/WP-318/WP-407 primary provenance stays intact.

There is no generic modify, App/settings subtree, test-tree, helper, schema,
runtime or source-ownership expansion. Consumers must reconcile trusted main and
use the named operation only after protected policy landing.

## Exact projection and validation

One new rule sits between the unchanged generated-resources and persistence-
schemas rules. Its exact inverse runs before the existing host-JVM, WP503,
consumer-support and older inverse chain. Both existing raw-text inverse helpers
first assert complete actual-base object and Git-blob equality, then retain
every previous historical assertion. The older JVM round-trip fixture explicitly
removes the new delta first; its old exact object equality is unchanged.

Five new test methods cover all four actual admissions; all other manifest WPs;
generic/cross-purpose/sibling negatives; widened/unknown/overlapping/duplicate/
moved/malformed rule rejection; complete baseline equality/round trip; all old
rules unchanged; and typed boundary/identity/secrets/compatibility fences.

Declared relevant bounded local commands:

```powershell
$env:PYTHONPATH = "$PWD\tools\android-port;$PWD\tools\android-port\tests"
python -m unittest -q test_consumer_support_capabilities test_scope_amendment test_capabilities test_disposable.DisposableGuestTests.test_resource_and_capability_inverse_restore_entire_actual_ad40165_policy
python .\tools\android-port\controller\validate.py
python .\tools\android-port\controller\verification_config.py --check
python .\tools\android-port\portmap.py
git diff --check
```

Initial complete focused execution: 93 tests run/passed, zero failures/errors/
skips, exit 0 in 14.798 seconds. Final exact frozen-candidate command results
belong in the PR and authoritative hosted job, not a second reproducible CI bundle.
Policy/admission tests are not backup, Android or bidirectional iOS compatibility
evidence. Full controller/local suites, all-stage fast/Gradle and native/hardware/
signing/legal checks are NOT RUN / UNVERIFIED for this amendment.

Documented read-only `dispatch.py --dry-run status` succeeded and reported
dispatch off/paused, no activation, and unconfigured live prerequisites. It does
not authorize dispatch/merge. An initial guessed `doctor.py` probe failed because
no such tool exists; it is not readiness evidence. No Android readiness/runtime
execution was attempted. Parent oversight retains protected exact-head review
and normal merge; earlier publication exceptions are not silently reused.
