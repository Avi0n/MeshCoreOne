# Protected historical foundation revalidation, version 1

**Migration interface only; receipt import remains BLOCKED.** Supervised
WP-000/001/002 commits predate modern Android CI, the current policy revision and
the WP-004 original-case catalog. Requiring nonexistent later checks at those
heads, or treating current draft prose as their acceptance, is circular.

`controller/historical.py` accepts an exact version1
`supervised-foundation-revalidation` proposal with:

- Original repository/WP/base/head/source/manifest/policy binding.
- Current immutable migration binding, real historical PR/merge commit.
- Original executed run IDs, attempts, workflow IDs, events and full check
  IDs/names/publisher/head/conclusions.

Validation compares the proposal with separately supplied actual server facts,
preserves original check names (for example bootstrap controller checks), rejects
open drafts, stale/current-policy changes, missing/zero/skipped executions and
invented later Android checks. The complete request receives an immutable digest.
The result is always `state: BLOCKED`, `imported: false`, `authoritative: false`.
`import_historical_receipt` refuses import; no ledger record is created.

Those pure fixtures are not the server-fact reader or approval. Before a live
migration, a separately authenticated trusted default-branch controller must
obtain historical PR/merge/run/check facts and the original trusted policy/catalog,
then independently revalidate the actual accepted behavior under the current
protected semantics. A real authorized human must accept the exact original
and current bindings and complete request digest. That approval authority and
transition/ledger schema must be introduced by a separately protected amendment;
candidate/model-written approval flags are rejected.

The existing modern completion/import API still requires genuine exact-bound
current gates and refuses stale/missing evidence. This draft does not bypass it.
All current foundation PRs are unmerged dependent drafts, so none qualifies as a
historical merged receipt now. Missing historical evidence remains a blocker,
never a success-shaped synthetic replacement.
