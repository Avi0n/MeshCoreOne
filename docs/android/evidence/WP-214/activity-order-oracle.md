# WP-214 strict activity-timestamp oracle

## Binding and pre-edit review

Concern: `SM-SYNC-ACTIVITY-ORDER`; original single family
`SyncCoordinatorTests::Sync activity ends before messages phase()`.
Source: `MC1Services/Tests/MC1ServicesTests/SyncCoordinatorTests.swift` at
`db14559b39d32322b06477c6ae676112f583db50`.
The retained grouping input SHA-256 is
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`,
exact group above, workstream 20, ordinal 546. Historical judgments are context,
not executed evidence or authority for a product change.

New-task clean main base: `de20d1a6f3ff97068076204504ca7c854044bb77`,
tree `c25711fbeea94659ef012d605b0aaaebad2bf4b5`. The managed branch
fast-forwarded only after fresh actual merged confirmation of #355.
Owner: `services-porter`, DEFAULT runtime with the tracked profile applied.
Stored reservation `d3a4cd0b-7a84-44c4-bb5a-8c2066c90afd`, runtime session
`31b665f3-3d88-44e3-8e33-96cf26309f18`, revision 43. All six actual test/docs
paths were admitted through trusted `wp-owned` / `modify`; the existing same-owner
directory capabilities already cover them, so no additional evolution or
authorization-prose rewrite was necessary.

Fresh API reads confirmed WP-208/209/210/211/212/215/216 prerequisites actually
merged, retaining the merge identities recorded in the preceding pruning
evidence. The new source-only proposal was frozen before any repository edit:
`wp214-activity-order-proposal-001.json`, SHA-256
`6a9b9098090d1a105b6edaecd031707782dc112237df51cad86202d1f72f3b4e`.
Oversight actually read and hash-verified it, approved the source-preserving
route, and requested the additional inverse rollback control. No equivalence
between causal ordering and strict wall dates was approved.

Before editing, the declared `ci.py preflight --state
/ai_cache/meshcoreone/toolchains/a8c624bc-20261026/environment.json --output
/ai_cache/meshcoreone/repairs/31b665f3/wp214-activity-order-de20d1a6/output/base-readiness
--local` ran on the exact clean Linux baseline with pinned Python 3.12.4:
PASS, exit 0, explicit installed environment ready. It did not run Gradle or
establish acceptance of subsequently authored tests.

## Actual source and repair

The pinned actor records `Date()` into two optional fields at actual callback
and polling entry. Missing either returns false; otherwise the predicate is
strict `ended < poll`. Current native `SyncMocks.kt` accepted a `SyncClock`
but ignored it and compared first event-list positions instead. This reproduced
an oracle replacement gap, not a production ordering bug.

The repaired helper records `clock.now()` and its field assignment in one
non-suspending synchronized segment, overwrites the corresponding last value,
and compares a synchronized snapshot of both optional timestamps. It contains
no causal guard, timestamp adjustment or post-hoc manufacturing.

The original exact-named case retains its assertion and now passes one
`ReadAdvancingSyncClock` to both its actual coordinator and tracker. Every
`now()` returns delegated virtual wall time plus a generic increasing one-
nanosecond read counter. The fixture knows no event names or expected outcomes;
delegated monotonic elapsed time and virtual sleep are unchanged. Only this
case opts in; the global harness and production clocks are not modified.

This deterministic positive fixture models distinct fine-resolution readings.
It does not prove real wall-clock resolution or monotonicity, nor equivalence
of native `Instant` nanosecond representation and Swift `Date` storage precision.
Real observations can repeat or move backward, so causal producer order alone
cannot satisfy the original strict date assertion.

## Mandatory native controls

`SyncActivityOrderTests.timestampPredicate` adds separately named native cases:

| Clock observations and calls | Expected pure timestamp result |
| --- | --- |
| Neither observation | false |
| End only | false |
| Poll only | false |
| End then poll, equal wall dates | false |
| End then poll, decreasing wall dates | false |
| Poll then end, increasing wall dates | false |
| End then poll, strictly increasing wall dates | true |
| End, poll, later end replacing the first | true before replacement; false afterward |
| Poll first at later date, then end after wall rollback | true, despite reversed causal order |

Clock inputs for these controls are read sequences, not event-aware
timestamp assignments. A further native case checks generic per-read advance
and unchanged elapsed/sleep delegation.

`SyncActivityOrderTests.causalCompletion` separately runs actual full sync with
a frozen wall clock and a suspended ended callback. It requires zero polls
before callback completion, one end, one poll, and ordered completion/poll
events. Equal recorded dates must still make the timestamp predicate false.
That causal evidence is deliberately not credited as strict wall-date parity.

## Verification state and limits

Actual lightweight commands on the authored repair:

| Command | Outcome |
| --- | --- |
| `git diff --check` | PASS |
| `python -B tools\android-port\controller\validate.py` | PASS; 1,866 pinned inputs, 1,786 owned, 80 excluded |
| `python -B tools\android-port\portmap.py` | PASS; 1,924 mappings |
| `python -B tools\android-port\controller\verification_config.py --check` | PASS |
| `python -B tools\android-port\test_inventory.py --check` | PASS; 468 pinned paths, 5,133 source declarations, not executed tests |

These are authored assertions, not a passing execution claim. The original
family and all eleven new native cases require positive discovery, no failures,
errors or skips, and the complete actual declared affected consumer closure.
The supported runner is `tools/android-port/local/check.py --commit <exact-head>
--affected-base <trusted-base> --parallel-jvm`; no heavy run or publication
grant has been received for these new bytes.

Existing end-once, retry, cancellation, notification and monitor assertions are
retained unchanged. Production ordering, clocks, timestamp range/error policy
and all other groups are untouched. No real sleeps, new actor, iOS/device,
hardware, full-WP, signing or legal acceptance is claimed. Prior #349/#355
evidence remains separate; no duplicate CI-result bundle is generated.
