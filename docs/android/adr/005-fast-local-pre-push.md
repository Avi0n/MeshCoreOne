# ADR-005: Fast local checks do not gate feature-branch publication

## Decision

Feature branches and pull requests may be published from every processor and host.
Automatic pre-push checks are removed by maintainer direction. `git push` must
not start local verification, remote transport or a shared Gradle-lane wait.
The former `--install-hook` command fails explicitly instead of reinstalling
the hook. Run the relevant declared local tests explicitly; missing local
verification remains UNVERIFIED, not a passing receipt or a publication block.

Existing installations must remove the MeshCore One `pre-push` entrypoint from
the effective Git hooks directory. For linked worktrees the default entrypoint
is shared in the common Git directory. Preserve unrelated hooks, manual-check
helpers, execution ownership and any already-running checks.

The exact-commit hosted job result and log are the authoritative record for
reproducible automated checks. The repository does not retain or aggregate a
second copy of CI results, discovery reports, cache state, or artifact hashes.
Hardware/device, signing, release, human and legal evidence remains retained
because it cannot be reproduced by an ordinary CI rerun.

Merge-to-main remains fail closed, but verification is change-scoped. Full
repository verification is required only for broad or unclassifiable Android
changes, dependency/toolchain/build-graph changes, release candidates, or an
explicit maintainer request. Other candidates run the relevant declared jobs and
must report positive discovery and zero failures/errors/skips for selected work.
A later commit invalidates earlier evidence.

Path/job scoping, normal task reuse, parallel workers, and verified caches are
allowed. Strict Gradle dependency locks, checksums, and
`--dependency-verification strict` remain mandatory. Cache warmth is not
correctness evidence; a cold-cache dependency audit targets dependency/toolchain
changes rather than every ordinary pull request.

## Native adapters

Contributors may provide an explicit JSON toolchain state with an adapter command.
The adapter can use a native macOS ARM64 toolchain; it need not use archives
downloaded by the x64 provisioner. It executes the exact committed candidate
and reports local diagnostics only. Candidate runner changes are ordinary
reviewed branch changes and cannot authorize themselves or replace the
exact-commit hosted job result.

Hosted CI, an x64 relay, Docker, Rosetta, and remote devices are not prerequisites
for publishing a branch. None substitutes for merge verification or protected
human gates.
