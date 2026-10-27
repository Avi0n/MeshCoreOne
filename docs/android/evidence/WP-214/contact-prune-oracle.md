# WP-214 contact-prune execution oracles

## Binding and scope

Assigned group: `SM-CONTACT-PRUNE-ORACLE`, eleven original families.
Owner: `services-porter`, default runtime with the tracked profile applied.
Managed branch: `cbattlegear-studious-journey`; stored same-owner reservation
`d3a4cd0b-7a84-44c4-bb5a-8c2066c90afd`, runtime session
`31b665f3-3d88-44e3-8e33-96cf26309f18`.
New-concern clean start: verified `main`
`610f606baaaf38eb2a2b14cb054b18fedba58a1f`,
tree `ced3cdbdc42cd7d22dc0bd33aef7b3c43e01eb6f`.
The managed branch fast-forwarded from merged PR #349; no rewrite or new actor.

Source pin remains `db14559b39d32322b06477c6ae676112f583db50`.
The retained grouping input was read with SHA-256
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`,
exact `root_cause_groups` ID above, SERVICES-MEDIA workstream 17.
Historical review judgments are not current reproduction or test execution.

Fresh API reads confirmed the actual merged prerequisites:

| WP | Existing PR | Merge commit |
| --- | --- | --- |
| WP-208 | #43 | `4773fffd83830229957f1cbb774c8d56c58f3391` |
| WP-209 | #49 | `82735dc4a09afcc1791f430a0c87446abee50564` |
| WP-210 | #50 | `738b10092237a48f6d0a38590fbacea7e8ddfe5f` |
| WP-211 | #42 | `933010a95bcfd698aadb4e7e4d4b8057965b7b68` |
| WP-212 | #48 | `7d56dc8281ab2dad7b25209e644c39092f943206` |
| WP-215 | #57 | `350d9e3e1182bd650881a063f34789c389aeaf19` |
| WP-216 | #53 | `fc2f36d6f98f609295ac20806eba1f315d9e4452` |

Receipt revision 42 was read before edits. Trusted admission of the new App
support test under `app-build-launcher` / `add-direct-support-wiring` evolved
the same reservation to revision 43. Existing owned service-test paths use
`wp-owned` / `modify`. No authorization prose, private ledger, feature activation,
database implementation or another producer's test surface is changed.

## Current evidence gap and repair

The current original pruning cases still constructed `ReferenceContactService`,
which copied the production batch/prune algorithm. The actual `ContactService`
already enforced complete reported totals and valid self-key/V-contact guards;
the finding was therefore reproduced as a missing real-execution oracle, not
classified as a destructive production bug.

The original eleven names now call the actual `ContactService`, and its copied
reference implementation is deleted. A narrow `ContactSessionOps` fake supplies
firmware responses and records `since`; it implements no pruning algorithm.
The existing Room harness supplies the actual `RoomPersistenceStore` for the
additional App tests. The source's optional cleanup coordinator is null, matching
the eleven originals; direct-message deletion/preservation is checked through
the real repository rather than credited to fake cleanup callbacks.

All rows below refer to `SyncCoordinatorTests::<source name>()` from pinned
`MC1Services/Tests/MC1ServicesTests/SyncCoordinatorTests.swift`.
All App methods belong to `ContactPruneOracleTest`.

| Original source family | Actual Room method |
| --- | --- |
| At-capacity connect forces full contact sync and prunes the missing non-favourite | `atCapacityConnectPrunesEvictedContactAndItsRoomMessages` |
| Below-capacity connect keeps incremental contact sync and does not prune | `belowCapacityConnectPreservesUnseenContactAndRoomMessages` |
| Full sync prune preserves the local V-contact omitted from a complete stream | `completeSnapshotPreservesVContactButDeletesRealOrphan` |
| Full sync prune removes a favourite absent from a complete device set | `completeSnapshotDeletesAbsentFavouriteAndOnlyItsMessages` |
| Full sync prune removes orphans when the device genuinely shrank below half | `completeOneOfFourSnapshotPrunesAllThreeStaleRowsAndMessages` |
| Full sync prune skips a truncated stream the ratio floor would have pruned | `reportedFourReceivedThreePreservesFavouriteAndRoomMessages` |
| Full sync prune skips when the device sent no contact total | `missingTotalPreservesUnseenContactAndMessages` |
| Full sync prune skips when the self public key is the wrong length | `malformedEightByteSelfKeyPreservesUnseenContactAndMessages` |
| V-contact in the last slot keeps the connect incremental, not at capacity | `virtualThirdSlotDoesNotForceDestructiveFullSync` |
| advert invalid watermark recovery is prune-free and keeps local-only contacts | `invalidWatermarkRecoveryKeepsLocalOnlyContactAndMessages` |
| performAdvertContactSync fullRefetch uses epoch zero and skips pruning | `explicitFullRefetchKeepsOrphanUntilActualNullSinceControl` |

Original null/non-null and exact `watermark-1`/epoch-zero predicates are retained.
The V-contact last-slot source only requires non-null `since`; this repair does
not invent an exact timestamp assertion for that source family. Added message
cascade controls, returned watermark and cancellation/error checks are explicit
native strengthening, not retroactive source assertion credit.

Two native methods additionally require actual typed failure to preserve its
session cause and pre-fetch data/watermark, and actual caller cancellation to
preserve local history and release the advert claim for a prune-free retry:
`failedFetchKeepsTypedCauseAndCannotPruneOrAdvanceWatermark` and
`cancelledAdvertFetchCannotPruneAndReleasesClaimForPruneFreeRetry`.

## Verification state

Actual lightweight commands on the authored repair:

| Command | Outcome |
| --- | --- |
| `git diff --check` | PASS |
| `python -B tools\android-port\controller\validate.py` | PASS; 1,866 pinned inputs, 1,786 owned, 80 excluded |
| `python -B tools\android-port\portmap.py` | PASS; 1,922 mappings |
| `python -B tools\android-port\controller\verification_config.py --check` | PASS; complete protected overlay |
| `python -B tools\android-port\test_inventory.py --check` | PASS; 468 pinned paths, 5,133 source declarations, not executed tests |

Static inspection matches all eleven grouping IDs to the App annotations and
thirteen declared App methods. This is mapping, not positive runner discovery.

Authored assertions are not execution acceptance. Kotlin/Room compilation and
all thirteen new App methods remain **UNVERIFIED** until the coordinator admits
the frozen candidate and its complete trusted derived affected plan.
The declared runner is `tools/android-port/local/check.py --commit <exact-head>
--affected-base <trusted-base> --parallel-jvm`; its actual derived closure,
positive mandatory discovery and final owned cleanup decide acceptance, not
arbitrary filtered Gradle invocations.

The merged PR #349 result (8,234 selected tests; 155 original IDs, 53 runner
identities and nine App obligations) remains historical proof for its exact
head. It is not a PASS for these new bytes. No duplicate CI-result bundle,
physical-device/radio, iOS, signing, release or legal evidence is created.
Parent oversight still owns source review, a distinct exact-head publication
grant and normal merge.
