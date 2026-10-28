# WP-216 evidence

## R118 issue #394 reconciliation

This documentation-only reconciliation starts at main
`00cce08ea1ed36a52dac704c201838efe205484e`, in session
`4bed84c3-a500-4b2e-845b-5d151574b27d`, owned by `services-porter`.
The source pin remains `db14559b39d32322b06477c6ae676112f583db50`.
It accounts for the five retained accepted concerns and 34 original families in
[#394](https://github.com/cbattlegear/MeshCoreOne-Android/issues/394), not a new
implementation, a new full-WP acceptance result or a reopening of every
historical finding.

The three repair merge commits below are ancestors of this reconciliation
base. The current reactions production/test directory, rendering
`ReactionWireFormat.kt`, `ReactionGraphemeClassificationTest.kt` and
`HeardRepeatRoomOracleTest.kt` are byte-identical to corrected grapheme
candidate `85410ca4577d615c0a1afa982a25dfc099eb9735`.
No parser, Unicode data, hash, cache, database or consumer behavior changes here.

| Retained concern | Original families | Current disposition and evidence boundary |
| --- | ---: | --- |
| `SM-REPEAT-STORE-ORACLE` | 22 | [#354](https://github.com/cbattlegear/MeshCoreOne-Android/pull/354), merge `ab78bf9086da7c99c73e03d519d996a543b25029`, adds ten missing real-Room family counterparts and four native transaction/concurrency checks. The [22-family correspondence table](../../deviations/WP-216.md#sm-repeat-store-oracle-original-family-reconciliation) retains eight parser-only predicates and four existing Room-backed family equivalents. The 19 mandatory App methods are 14 `HeardRepeatRoomOracleTest`, four `IncomingRepeatSyncTest` and one `HeardRepeatStoreAcceptanceTest`; they are not 19 additional original families. Concurrent replay covers already committed RX identities, not atomic concurrent first-arrival service deduplication. |
| `SM-REACTION-GRAPHEME` | 2 | [#365](https://github.com/cbattlegear/MeshCoreOne-Android/pull/365), merge `cf1d4cd316190c46c29eaf85d37701729e521729`, preserves the source one-Character classification at actual inbound/send consumers and delegates rendering classification to the canonical parser. Original family-ZWJ and emoji-in-sender predicates remain positive controls. The [bounded derivation](grapheme-classification.md) distinguishes doubled ZWJ, trailing joiners and standalone-modifier wire orders. Its final FAILED/preparation status belongs to predecessor `be13fc70`, not corrected `85410ca4`; see the authoritative successor result below. No fresh Swift/Android ICU or full-Unicode parity is inferred. |
| `SM-REACTION-EQUAL-CLOCK` | 1 | [#343](https://github.com/cbattlegear/MeshCoreOne-Android/pull/343), merge `f28e8779cb21e7f77d05dff8ee704b3a89de2246`, keeps the first equal-clock candidate for channel and DM lookup, matching pinned Swift `max(by: <)`. Fixed identities exercise both insertion orders and channel/contact isolation; the original strictly newer/distinct-clock family remains intact. |
| `SM-REACTION-CLEAR-LIFECYCLE` | 2 | The original channel and DM clear families in `ReactionServiceTest` call the real `clearPendingReactions`, then index their targets and require no pending matches. The method clears both pending queues/order, **not** `MessageLRUCache`. Neither the frozen production Swift call sites nor current Android production call sites directly invoke that method. Android instead constructs a new `ReactionService()` in each `RadioSessionContainer`; teardown stops consumers/jobs and the next generation owns a different graph. Existing `SessionLifecycleTest.reconnectCyclesKeepOneLiveGraphAndNeverGrowSubscribersOrJobs` proves graph replacement/teardown, not a reaction-specific clear callback, cache erasure or garbage collection. No disconnect leak was demonstrated and no new cache-clear policy is introduced. |
| `SM-REACTION-HASH-ORACLE` | 7 | #343 preserves the seven original determinism/range/round-trip/wire-shape predicates while adding eight separately named `ReactionHashIndependentVectorsTest` methods. The [independent Python oracle](reaction_hash_oracle.py) checks pinned source blobs, literal V1 packets and complete Pocket channel/DM wire strings using independent arithmetic and standard-library encodings/hash primitives. One extra method covers UTF-8/unsigned-LE32 boundaries. This is independent computational evidence, not fresh Swift/Dart execution or proof of every emoji mapping. |

### Authoritative landed-candidate results

These are the original hosted jobs, read directly from GitHub, not copied,
uploaded, hashed or revalidated result bundles. Each run reports its own exact
source head and successful conclusion. Named method inventories were read from
the original job logs; each selected identity appears once.

| Repair | Exact tested head | Authoritative hosted job | Relevant positive discovery |
| --- | --- | --- | --- |
| Equal-clock/hash, #343 | `adea5c50262aed8c30f84e07642016a069b87593` | [run 40397439521, Linux job 121836228466](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/40397439521/job/121836228466) | App 1,547 discovered/run/passed; the method inventory includes `ReactionServiceTest` 19, `ReactionServiceBehaviorTest` 13, `MeshCoreOpenReactionParserTest` 51 and independent hash vectors 8. |
| Real Room, #354 | `2af01a0bc12687e3b18f1868099ce3f5198402f7` | [run 40437822437, Linux job 121971958093](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/40437822437/job/121971958093) | App 1,608 discovered/run/passed, including all 19 mandatory real-store/consumer methods. |
| Corrected grapheme, #365 | `85410ca4577d615c0a1afa982a25dfc099eb9735` | [run 40456825108, Linux job 122032969398](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/40456825108/job/122032969398) | App 1,617 discovered/run/passed. The inventory includes parser 53, rendering agreement 4 and mounted classification 9, plus the retained Swift-vector and sync-parsing controls. |

All three App suite reports have zero failures, errors or skips. Their services
method inventories include the original pending-clear and distinct-clock cases;
the original weaker assertions are not relabeled as independent oracles.
The source-authored local guard counts in the repair PR bodies remain attributed
to those guards, not substituted for the hosted counts above.

The old failed grapheme run remains failed: its supplementary mention-first
standalone-modifier expectation was wrong. The separately tested correction
retains that modifier and checks order-specific rejection/acceptance without
changing production or original-family expectations. The detailed preparation
record remains untouched as historical evidence.

### Scope, admission and remaining gates

WP-208 and WP-209 implementations were merged through #43 and #49 before these
repairs. The newer R118 WP-208 recovery repair (#453 at reconciliation time) is
not claimed merged or required retroactively for the unchanged reaction bytes.
No prerequisite is inferred from issue closure.

Trusted `wp-owned/modify` admission accepts this README. Automatic reservation
acquisition under this session reported the existing WP-216
source/base/manifest/backend mismatch; the historical advisory owner
`bcb17a74-5fa6-47d0-a4be-b6b595e20559`, revision 6, is preserved.
No foreign reservation, authorization prose, runtime, hook or ledger was edited.
This is the supported admitted documentation surface, not a transfer of the
other owner's implementation paths.

The raw WP-216 manifest still has `configured=false` and no commands. This
documentation correction does not invent a Gradle task or claim a fresh native
run; any future changed implementation requires its approved verification
binding. The documented environment-readiness command,
`python tools\android-port\controller\ci.py preflight`, was BLOCKED by missing
explicit `--state`/`ANDROID_CI_STATE` before any native execution.
Exact-candidate hosted checks and independent review remain required for this
documentation PR. Hardware, installed-device/API-specific behavior, signing,
legal acceptance and whole-WP completion are not asserted.

Source-only checks for this reconciliation passed:
`python docs\android\evidence\WP-216\reaction_hash_oracle.py`
(five pinned inputs and all eight generated regression definitions/literals),
`python tools\android-port\controller\validate.py`
(1,866 tracked reference files, 64 WPs and 178 edges),
`python tools\android-port\controller\verification_config.py --check`,
and `git diff --check`. These checks do not execute Kotlin tests.
Original hosted logs also contain the exact two pending-clear family names,
the original distinct-clock family and the graph-reconnect method named above.

## Historical external contribution

**External contribution:** this was prepared outside the dispatch controller, with
no lease or receipt. This document lists the commands that actually ran and their
observed results.

- **Reference:** `db14559b39d32322b06477c6ae676112f583db50`
- **Base:** WP-213 branch (#52), itself on `main` @ `7237727f`
- **Host:** macOS 26.5.1, JDK 21.0.12.1, Gradle 9.8.0 via the wrapper, Swift 6.3.2 (Command Line Tools)

| Command | Observed |
| --- | --- |
| `./gradlew :core:services:test validateModuleGraph --rerun`, three forced runs | `BUILD SUCCESSFUL` each time; **493 tests, 0 failures**, of which **194** are in `reactions/` |
| Compile `:core:services` main with `-Xjdk-release=17` | succeeds |
| `python tools/android-port/portmap.py` | exit 0 |
| `python tools/android-port/controller/validate.py` | exit 0 |

All **136** source case ids that `docs/android/test-cases.json` assigns to
WP-216's test files run under their exact ids. None are missing. The breakdown is
ReactionParser 44, MeshCoreOpenReactionParser 51, ReactionService 19 and
HeardRepeatsService 22. The other 58 reactions cases are native (`WP-216::`).

- **Swift oracle:** parser outputs, hashes and normalisation come from the frozen Swift sources run under `swiftc`.
- **Agreement:** WP-213's `ReactionWireFormat` agrees with `ReactionParser` on parse, DM parse and hash. `adoptInboundHop` agrees with `core:data`'s logic on a 2,744-input grid.
- **Mutation checks:** twelve deliberate breaks were each caught. They include Crockford `L`→`1` decoding, hash endianness, queue eviction order, identical-path extras, the U+200B whitespace member, fullwidth hex, a failed duplicate check, swallowed cancellation, canonical summary grouping, empty v1 pieces, the same-instant tie-break and second collection of the event stream.
- **Review:** an independent review found no critical or high issues. Its three medium findings were fixed with tests: single-shot event streams, the same-instant tie-break, and decrypted text in logs. The two low ones are documented: the adversarial grapheme gap and the store-only test.
