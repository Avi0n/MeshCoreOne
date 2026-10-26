# WP-000 host-scoped JVM source capability

## Authorization and ownership

This separately authorized support amendment follows merged PR #334, on trusted
base `a8c624bcb095f81a30e5fee671099fa519f608b7`. It does not rewrite that batch's
authorization or evidence. The source pin remains
`db14559b39d32322b06477c6ae676112f583db50`.

The existing WP-000 policy producer authors this capability, admission regressions
and exact reversible projection. The existing WP-003 producer authors the runner
implementation; no new worker, session, schedule, settings or runtime installation
is authorized by this rule. The tracked port-orchestrator/common WP instructions
apply; custom profile injection is not claimed.

## Exact purpose and paths

`wp003-host-scoped-jvm-parallelism` admits only WP-003 operation
`implement-host-scoped-jvm-lanes` at these existing paths:

- `tools/android-port/local/check.py`
- `tools/android-port/local/fast.py`
- `tools/android-port/local/run.sh`
- `tools/android-port/local/hook_test.py`
- `tools/android-port/local/advisory.py`

The runner producer confirmed all five are necessary. `advisory.py` must carry
optional real host/slot binding through the existing inherited fd8 pidfd
supervisor; the default six-key serial admission remains unchanged. `reserve.py`
is not needed. Controller ledger/lane code and controller tests already have
WP-owned authority; there is no local-subtree or generic modify expansion.
The old `wp003-scoped-local-verification` rule and its no-lock-change invariant
are byte-for-byte unchanged.

The rule requires one canonical shared durable ledger/repository, real machine
identity, atomic allocation using existing `hard_locks`, and bounded slots based
on measured safe CPU/RAM capacity. Initial two-slot throughput is not a permanent
policy ceiling; additional safely measured slots need no separate permission
ceremony. Unknown identity/capacity fails closed and the default remains serial.
No new database schema/registry, private clone ledger, caller nonce host labels,
historical 166-row rewrite, or lease release/recreation is admitted.

The runner must preserve native/install/disposable/serial exclusions in both
acquisition directions, pidfd owned-process cleanup, per-slot bundle/repository/
report/private/Gradle-cache isolation, stripped credentials, immutable candidate
and task-consumer closure, and strict nonzero/failure/error/skip report validation.
This source capability neither activates lanes nor proves remote parallel execution.
Consumer edits require trusted-main admission after this policy lands.

## Projection and regression contract

The single exact rule precedes the unchanged disposable rule; reversal removes
it before the existing WP503, consumer-support and older inverse chain.
Complete object equality and raw Git-blob equality against the actual a8 baseline
are asserted without weakening any older historical-object/byte assertion.
Positive admission covers all five exact paths. Negative cases cover every
other WP, generic and cross-purpose operations, old operation aliases, sibling
paths, widening/mutation, duplicates, reordering, unknown IDs and overlap.
Policy fixtures are not source-family or real runtime acceptance.

## Local checks and publication limits

Relevant commands for this bounded policy amendment:

```powershell
$env:PYTHONPATH = "$PWD\tools\android-port;$PWD\tools\android-port\tests"
python -m unittest -q test_consumer_support_capabilities test_scope_amendment test_capabilities test_disposable.DisposableGuestTests.test_resource_and_capability_inverse_restore_entire_actual_ad40165_policy
python .\tools\android-port\controller\validate.py
python .\tools\android-port\controller\verification_config.py --check
python .\tools\android-port\portmap.py
git diff --check
```

Actual results and the exact candidate are reported in the PR and hosted job,
not a second retained reproducible CI bundle. Preliminary focused execution
discovered 43 tests, with one fixture-vs-actual-policy comparison failure;
the regression now compares the actual unchanged policy object, not the
deliberately local-mode test fixture. No assertions were removed or weakened.

Full all-stage fast/Gradle, native execution, iOS/Android/hardware/signing/legal
checks are NOT RUN / UNVERIFIED here. Hosted required checks and parent exact-head
review/normal merge remain mandatory. The separately authorized publication
exception requires parent review, actual targeted nonzero PASS, fresh unchanged
head/tree/scope, inspection of the whole installed hook chain, exact-candidate
canonical pending evidence and an immediately validated own empty non-reparse
hooks directory used only by one normal authenticated nonforce push. Any other
mandatory hook blocks that exception; no persistent hook/config change,
`--no-verify`, force push, manual CI dispatch/rerun or execution-gate bypass.
