# WP-501 original region-ambiguity footer and label cases

## Frozen originals and complete predicates

Reference remains `db14559b39d32322b06477c6ae676112f583db50`.
`MC1Tests/Views/Chats/Components/MessageBubblePredicateTests.swift:197-256`
contains three separate standalone originals, not a parameter matrix:

| Original | Exact input differences from helper defaults | All original assertions |
| --- | --- | --- |
| `footer bakes multi-match chip label and ambiguous flag()` (197-212) | FLOOD, sticky scope Germany, ordered matches `[de-hh,de-by]`, regiontrue | Actual footer chip `de-by / de-hh`; ambiguoustrue; exact ordered names `[de-by,de-hh]` |
| `footer bakes legacy scope-only as unique without ambiguous flag()` (215-226) | FLOOD, Germany, explicitly empty matches, regiontrue | Actual footer Germany; ambiguousfalse; exact singleton names `[Germany]` |
| `accessibilityMessageLabel lists all candidates when ambiguous()` (229-256) | FLOOD, nil sticky scope, ordered matches `[de-hh,de-by]`, regiontrue | Actual footer ambiguous; footer names contain de-by and de-hh separately; actual bubble label contains de-by and de-hh separately |

Each original has a distinct `@OriginalCase` and JUnit method in the existing
`MessageBubbleAccessibilityAcceptanceTest`:

- `multiMatchOriginalFooterOverridesStickyScopeAndBakesCanonicalNames`
- `legacyScopeOriginalFooterIsUniqueWithoutMatches`
- `ambiguousOriginalIncomingDmLabelListsEveryCandidate`

These are three original cases/three method declarations, not three passing
discoveries. Existing coremodel coalescing tests, alpha/beta channel-footer
coverage and NORTH/SOUTH supplemental label coverage do not retain this
incoming-DM source helper, sticky-Germany precedence and complete original
consumer predicate set. The delivered DIRECT/FLOOD region-label originals and
eight-row route/preference/scope family are retained, not credited again.

## Complete helper and real consumer mapping

The complete frozen `makeMessage` and `MessageBubbleTestData.messageItem`
helpers, Swift bubble label/direct-message configuration, shared region label,
footer builder/semantics and native counterparts were read before edits.
Eight selected source/helper/consumer Git blobs equal the frozen pin; normal
Windows checkout CRLF is not source drift.

`predicateIncomingDm` is minimally extended to accept nullable scope and an
immutable `SnapshotList<String>` matches argument defaulting empty. The two
previous DIRECT/FLOOD callers and their inputs are unchanged; their implicit
empty DTO matches now receive the same explicit empty helper default.
All other helper inputs remain fresh message/radio UUIDs, nil contact/channel,
Test text, timestamp0, incoming/delivered/plain/read, pathLength2/A37F,
nil sender/key/ack/SNR/reply/RTT, heard0/retry0/max0/sendCount1 and the original
remaining DTO defaults. Only irrelevant ambient `Date()` maps to deterministic
`Instant.EPOCH`. The candidate order remains de-hh then de-by; expected canonical
order is independently written de-by then de-hh.

Existing `sourceItem` is byte-unchanged: real public
`MessageFragmentBuilder.makeItem`, idle/nil preview/URL/images/formatted text/
path, incoming base color, Unknown/unresolved sender, sender-groupingtrue,
other grouping flagsfalse, previewsEnabledtrue/currentUserNameMe and
source-equivalent environment defaults. Only region is enabled for these cases;
hop remains false. Removed translation offers remain explicitly false.

The first two tests assert the actual builder-produced footer, not DTO routing
or a lookalike coalescing result. Native `MessageFooter.regionIsAmbiguous` derives
from actual `regionMatchNames`; list equality includes exact order and members.
Expected strings/lists are frozen literals, never candidate sorting, coalescing
or formatting output.

The third real item also feeds actual production
`messageBubbleAccessibilityLabel(message, item, false, resources)`. The false
argument preserves `.directMessage.showSenderName == false`, distinct from
helper groupingtrue. Source `contactName: Alice` is unused on this label path;
no fake Alice sender resolution is introduced. Both literal candidate names
must appear independently in the footer and resource-backed real label.
The existing en-US/API31 Robolectric host supplies Android resources/ICU,
corresponding to Swift's localized ListFormatter/region-label consumer. No
candidate-generated expected join or new full-label/locale-join condition
replaces the original containment predicates.

The live App uses this same builder/label boundary through conversation bubble
semantics. These additions do not newly mount a screen, construct a fake label,
run TalkBack or exercise a native radio/device scenario. Production source
matches the expected branch behavior; no product repair or runtime success is
inferred from source review alone.

## Actual base, ownership and preservation

Implementation parent/base is actual main
`b8c6c610d5d4f5fe729ecd86ea89117aaebb9393`, ordered parents
`155a2ce70baa341fc9559f111454e8529dd7e47e` and
`bf0d9fcf877b6f26020e74ca33ff01b62987464f`, tree
`603aabbb471841d7ff4deb9999dc1602c462e5de`.
The managed branch cleanly fast-forwarded bf0d9 to its actual merge with
identical trees and zero incoming file changes before edits.

Same stored attempt/session `d6056690-3a5d-453e-b67e-36866c676ac5`
evolved receipt132->133 through trusted `wp-owned/modify` for exactly four owned
paths before edits: existing test, this evidence, additive proposal and owned
deviation. Only this new evidence path extended the existing path set.
Identity, immutable historical binding/authorization, prior capabilities and
attribution, branch/worktree and complete protected-lock records are preserved.
No manual ledger edit, release/recreate, foreign ownership transfer or product
capability expansion occurred.

All ten incumbent class method bodies, other helpers and imports are unchanged;
only `predicateIncomingDm` gains equivalent-default scope/matches support.
All 196 existing proposal groups and their text remain unchanged, with one
additive three-original group. Product, App source-set/build/runner, tools,
native fixtures and generated accounting archives are not edited.

## Declared local verification and limits

| Exact command | Actual local result |
| --- | --- |
| `python tools\android-port\controller\validate.py` | PASS: source/ownership/dependencies valid; canonical manifest SHA256 `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` |
| `python tools\android-port\portmap.py` | PASS: 1878 provenance entries, not behavioral acceptance |
| `python android\parity-tests\semantic.py --check` | PASS: read-only source/native assertion/runner joins, including three new original mappings; not approval or execution |
| `python tools\android-port\controller\test_runner.py --tests android\parity-tests --quiet` | PASS: 108 discovered/run/passed, zero failures/errors/skips, 7.214s; controller fixtures only |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED, exit2: explicit `--state/ANDROID_CI_STATE` absent; no state invented or bypassed |
| `git diff --check` | PASS |

Current trusted policy revision remains
`46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8`;
the immutable historical receipt binding is not this candidate's current base
or digests. Historical implementation-time UNVERIFIED notes and subsequent
hosted successes remain history, not reopened software gaps or borrowed results.

**LOCAL ONLY; PUBLICATION HELD for independent coordinator source review.**
Candidate Kotlin compilation and actual three-method discovery/execution remain
UNVERIFIED. The required class is
`com.meshcoreone.android.parity.MessageBubbleAccessibilityAcceptanceTest`
in the existing App JUnit4 test source set. No new Gradle/native/device/resource/
allowance action, successful official-log reread, second CI-result bundle,
dispatch/rerun, mount/TalkBack/hardware/iOS/oracle or whole-WP claim is made.
This bounded work addresses only `WP-501-behavior`, `WP-501-boundaries` and
`WP-501-source-test-parity`; WP-501 and issue134 remain incomplete.
