# WP-501 original incoming hop-count gate family

## Bounded missing assertions

Reference remains `db14559b39d32322b06477c6ae676112f583db50`.
The complete original family is `MessageBubblePredicateTests.showHop: gated by
both flag AND isFloodRouted`, lines 9-25 of
`MC1Tests/Views/Chats/Components/MessageBubblePredicateTests.swift`.
The local `makeMessage` and complete `MessageBubbleTestData.messageItem` helper,
Swift/native DTO routing and production footer builders were compared directly.

Incumbent `RenderingModelsTest` asserts an enabled incoming channel footer,
outgoing suppression and a legacy nil-route/path255 DM. It does not assert the
explicit-route traffic-class variants or disabled flood footer. Existing
`DomainBoundaryTest` checks TC routing on the DTO, not the builder's consumption
of that routing. The delivered seven-row `hasFooter` family has no TC variants
and its OR can mask a hop contribution in rows with path or region. The delivered
heard-count and label families assert different predicates. Existing preference,
hash, simulator and RxLog tests do not assert this footer matrix.

This is a missing complete predicate family, not a missing annotation or presumed
product bug. The archived WP-307 unresolved entry corroborates the accounting
gap but is not its proof. Archived groups/accounting/bindings are not rewritten.

## Frozen rows and actual production consumer

| Row | showIncomingHopCount | routeType | expected footer.showHop |
| --- | --- | --- | --- |
| 0 | true | flood | true |
| 1 | true | tcFlood | true |
| 2 | true | direct | false |
| 3 | true | tcDirect | false |
| 4 | false | flood | false |
| 5 | false | direct | false |

`MessageHopCountOriginalPredicateTest.allSixOriginalHopCountRowsUseTheProductionBuilder`
uses all six ordered literal rows and booleans. It reads
`MessageFragmentBuilder.makeItem(message, inputs, env).footer.showHop`, exactly
the production result consumed by the original helper. No candidate predicate
computes the expected value. Native `makeFooter` already implements the source
conjunction of preference, `message.isFloodRouted` and incoming direction;
`MessageDTO` delegates explicit routing to actual protocol `RouteType.isFlood`.
No product or consumer repair is needed.

The source helper's fresh message/radio UUIDs, nil contact/channel, Test text,
timestamp0, incoming/delivered/plain/read, pathLength2/A37F, nil sender/region,
zero repeats/retries and remaining DTO defaults are preserved. Every explicit
route is tested on a DM: a channel would force flood and invalidate direct rows.
Ambient `Date()` becomes `Instant.EPOCH`, irrelevant to this predicate.
Builder inputs retain idle/nil previews, URL/images/formatted text/path,
incoming base color, Unknown/unresolved sender, sender groupingtrue and other
grouping flagsfalse. Environment retains previewsEnabledtrue/currentUserNameMe
and source-equivalent other defaults; removed translation offers are explicitly
false. No UIKit image resolver is needed for the selected footer assertion.

## Ownership and execution boundary

Implementation base is actual main `4b22c2526618f5cb556f593be4ed169c38448615`,
which includes merged PR300/294 and the incoming Room/native-fixture work.
Same WP-501 attempt/session `d6056690-3a5d-453e-b67e-36866c676ac5` evolved
reservation125->126 through the supported trusted `wp-owned`/`modify` rule
before editing exactly the test, this evidence, proposal and deviation paths.
Historical authorization/identity remain; no release/recreate or shared product
capability is needed. Active Room/accessibility/runtime owners are untouched.

The existing App parity source set discovers this synchronous JUnit4 method.
One original family/six inputs/one method is not six original families or six
JUnit discoveries. Swift MainActor pure construction becomes a JVM assertion
through the same public builder, not mounted Compose, actual TalkBack, API37,
native/device/hardware, protocol oracle, backup interoperability or whole-WP proof.

Declared `python tools\android-port\controller\ci.py preflight` is **BLOCKED**
(exit2): explicit `--state/ANDROID_CI_STATE` is absent. No readiness state,
runtime/hook/lock repair or native/Gradle lane bypass is introduced. Own exact
candidate compilation/discovery/execution and independent review remain
**UNVERIFIED** in this implementation-time snapshot. Ordinary publication may
remain locally UNVERIFIED; only the new exact candidate's authoritative hosted
job/log can establish the method and selected-suite results. No old green result
is transferred, duplicate CI bundle retained or manual rerun/dispatch requested.
