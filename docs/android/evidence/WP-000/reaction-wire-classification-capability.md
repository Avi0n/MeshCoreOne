# WP-000 reaction wire classification support

## Separate assignment and source correspondence

This policy-only batch starts at clean trusted main
`de20d1a6f3ff97068076204504ca7c854044bb77`, after merged PR #356.
The existing producer follows tracked port-orchestrator/common WP instructions
on the same managed branch, without a new actor, custom profile injection or
product implementation. The immutable source pin remains
`db14559b39d32322b06477c6ae676112f583db50`.

The actual WP-213 rendering `ReactionWireFormat` privately duplicates channel
parse, DM parse and message hashing. `HiddenOutgoingReactionPredicate` uses
that duplicate directly; `MessageLRUCache` uses its hash for channel/DM keys.
The canonical WP-216 `ReactionParser` is now in the same services module.
Leaving the private copy unchanged would retain the documented ordinary
doubled-ZWJ classification gap after the consumer's local GB11 repair.

Pinned `ReactionParser.swift` accepts exactly one emoji Character for PocketMesh
channel/DM parsing, normalizes eight ASCII Crockford symbols and hashes UTF-8
text plus LE32 UInt32 timestamp using the first five SHA-256 bytes.
Pinned `MessageDTO+ReactionVisibility.swift` uses only PocketMesh channel or DM
parse, outgoing direction and non-failed status to hide a row; substituting the
broader meshcore-open `isReactionText` classifier would change that contract.
The source and actual duplicate/canonical/visibility/cache call sites were read.

Actual trusted-main WP-216 admission for the exact rendering path failed for
both `modify` and the proposed named operation: no trusted path+operation rule.
The same-role WP-213 ownership is not reassigned or impersonated. Its primary
source provenance and original-family accountability remain unchanged.

## Exact capability and retained boundaries

`wp216-reaction-wire-classification` admits only WP-216 operation
`reconcile-reaction-wire-classification`, at this single existing path:

`android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/rendering/ReactionWireFormat.kt`

Only the existing parse/parseDM/generateMessageHash implementation may delegate
to canonical `ReactionParser`, or receive minimal equivalent classification
reconciliation. Preserve the existing public/internal shapes, including DM
`Pair<String, String>`, both channel mention orders, sender/delimiter handling,
one-emoji Character validation, newline/CRLF and invalid-hash rejection,
Crockford case/alias normalization and every hash byte. Preserve channel/DM
cache keys and contact/sender/radio identities, outgoing direction, isDM routing,
FAILED visibility and ordinary-text visibility. No cache-policy change,
meshcore-open visibility broadening or unrelated feature repair is admitted.

There is no rendering subtree, global SwiftText, other production path, test
path, build/dependency, Unicode-data/notice-policy or legal-acceptance grant.
The consumer already has its own agreement and App classification test
admissions; they are not duplicated here. The coordinator reports that the
existing shipped Unicode notice and its App license loader meet the consumer's
notice need; this policy session did not reverify legal compliance and grants
no additional notice path or acceptance.

Consumers require trusted-main capability admission before edits, must retain
their same stored identity/reservation/authorization, and serialize or transfer
real semantic overlap to the current producer with original evidence. No actor,
release/recreation, historical ledger rewrite, runtime/cache/ref/default
installation, live job, resource draining, settings, dispatch or merge authority
is granted. Preserve original message bytes, secrets and license obligations.

## Admission, inverse and declared verification

One exact rule is inserted before the unchanged WP-404 shared-cache capability.
Its strict inverse runs before shared-cache/RX/backup/JVM/WP503/consumer/older
inverses. Both historical raw helpers assert complete actual-de20 policy object
and raw Git-blob equality before every retained older assertion. Every old rule
and planner bridge, including #353, #351/#352 and #356, remains unchanged.
The earlier shared-cache round-trip fixture first removes the new delta before
its unchanged complete-d2b baseline and round-trip assertions.

Six focused regression methods cover the single exact positive, all other
manifest WPs including primary WP-213, wrong/cross-purpose operations,
unchanged primary-owner modify admission, nearby production/tests/notice/build/
docs paths, traversal, mutation, duplicate/order/overlap/malformed contexts,
complete predecessor equality/round trip and source/wire/hash/visibility fences.
These fixtures prove admission only, not GB11/Unicode/parser/cache/UI behavior,
JVM/native execution or original-family acceptance.

Declared bounded commands:

```powershell
$env:PYTHONPATH = "$PWD\tools\android-port;$PWD\tools\android-port\tests"
python -m unittest -q test_consumer_support_capabilities test_scope_amendment test_capabilities test_disposable.DisposableGuestTests.test_resource_and_capability_inverse_restore_entire_actual_ad40165_policy
python .\tools\android-port\controller\validate.py
python .\tools\android-port\controller\verification_config.py --check
python .\tools\android-port\controller\workflows.py
python .\tools\android-port\portmap.py
git diff --check
```

Initial complete focused run: 110 run/passed, zero failures/errors/skips,
14.636 seconds, exit 0. `validate.py`, `verification_config.py --check`,
`workflows.py`, `portmap.py` (entire output consumed) and `git diff --check`
each passed with exit 0. Exact frozen-candidate commands/results belong in the protected review handoff/
PR and authoritative hosted job, not a second reproducible CI-result bundle.
Full controller/local suites, all-stage fast/Gradle and actual JVM/native/
hardware/iOS/Android/signing/legal verification are NOT RUN / UNVERIFIED.
Future existing agreement tests must retain independent pinned oracle and seeded
channel/DM Pair/hash/cache expectations. The already admitted
`com.meshcoreone.android.app.container.ReactionGraphemeClassificationTest` must
exercise actual ordinary-doubled-ZWJ visibility versus valid reactions,
direction/routing and FAILED rows. Run the affected declared whole closure with
exact source-bound mandatory identities and nonzero discovery, not policy-only
or compile-only acceptance.

All six support policy/test/doc paths separately passed trusted WP000/wp-owned
`modify` admission before edits. The supported own WP000 claim attempt again
returned an existing source/base/manifest/backend binding mismatch; the
historical identity was not impersonated, evolved, released/recreated or manually
rewritten. Documented read-only readiness remains off/paused, no live
execution/merge authority. Parent retains protected exact-head review and normal
merge. Previous #356 publication permission is not reused; no push/PR is
authorized before a new protected review/publication decision.

## Metadata-only successor before merge

After the one authorized publication of frozen
`31f077943a9936fa74fd54fb3651e8064128cc31`, parent directly verified the actual
consumer class/file as `ReactionGraphemeClassificationTest` /
`ReactionGraphemeClassificationTest.kt` (nine methods), not the App-prefixed
caption in that candidate. This session could not resolve the shared short
`a8fe216` revision locally; the identity correction is attributed to the parent's
direct source read, not claimed as local product execution.

The ordinary successor corrects only the validation caption in policy/constant,
the caption fence, the actual App negative-path fixture and this scoped doc.
The single production path, named operation, WP scope, all other invariants,
old rules and complete-de20 inverse baselines are unchanged. Frozen31f's final
110 PASS in 15.125s remains historical truth; successor results are distinct.
This is test-identity metadata drift, not a parser/native failure or expanded
capability. Merge/publication waits for protected successor review and a NEW
distinct grant; the consumed31f push grant cannot authorize a second push.
