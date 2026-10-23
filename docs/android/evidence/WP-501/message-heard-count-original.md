# Original incoming heard-count predicate family

WP-501 / test-parity-engineer, existing issue #134.
Base: actual merged main `951369b25e2eabe6f9a09305742e9ee968f080e2`.
Frozen read-only specification: `db14559b39d32322b06477c6ae676112f583db50`.
Primary WP-307/WP-213 ownership and the delivered footer/bubble families remain
unchanged. This is one original parameter family, four inputs, one JUnit method.

## Actual gap and source comparison

The full frozen `MessageBubblePredicateTests.swift`, its `makeMessage` helper,
`MessageBubbleTestData.swift`, and both production `MessageFragmentBuilder`
footer builders were read. The selected original is lines27-46:
`showHeardCount: gated by flag AND incoming AND heardRepeats`.
The source helper constructs the real builder item and checks
`bundle.item.footer.showHeardCount == expected`.

Current `RenderingModelsTest` checks enabled/incoming/positive repeats and
outgoing suppression with different channel fixtures, but never disables this
flag with positive repeats or supplies zero incoming repeats to this predicate.
Other native repeat-count cases concern message actions, hashes, status or backup
preferences, not these missing footer assertions. The archived WP-307 family has
no native proposal; it is corroboration, not the reason to claim a behavior gap.
The current explicit proposals also have no join for this family.

The new native method
`MessageHeardCountOriginalPredicateTest.allFourOriginalHeardCountRowsUseTheProductionBuilder`
retains every literal source tuple in order:

| show flag | direction | heard repeats | expected |
| --- | --- | --- | --- |
| false | incoming | 2 | false |
| true | incoming | 2 | true |
| true | incoming | 0 | false |
| true | outgoing | 2 | false |

Both previously missing gates are isolated; the remaining two rows preserve the
complete original family rather than inventing four new families. Expectations
are source literals, not a second Kotlin implementation of the predicate.
Each row reads the actual `MessageFragmentBuilder.makeItem(...).footer`.
No product defect is presumed: the existing native producer already implements
the source flag/incoming/positive-repeat conjunction. No product edit is needed.

## Inputs, adaptations and execution boundary

Fresh message/radio UUIDs, nil contact/channel, Test text, timestamp0, delivered
plain/read DM, pathLength2, A3/7F bytes, nil raw sender, nil route/region, zero
retry counters and all original DTO defaults remain. Swift64-bit Int repeats use
Long. Irrelevant ambient `createdAt` becomes `Instant.EPOCH`; the selected
predicate reads neither creation time nor clock.

Builder inputs match the helper: idle previews, nil URL/preview/images/formatted
text/path, correct per-direction base color, Unknown/unresolved sender, sender
grouping enabled and other grouping flags disabled. Its environment keeps the
helper's previews-enabled/currentUserName Me and other native source-equivalent
defaults. Translation offers are explicitly off because translation is removed,
not a new feature or future requirement. No image resolver is needed because the
original assertion reads only the real footer.

This synchronous JVM/JUnit4 test uses the existing App parity source set. It is
not a mounted chat, Room, screen-reader, device/native, independent Swift,
protocol-oracle, backup-compatibility or whole-WP acceptance assertion. There is
no new evaluator, fixture framework, consumer repair, build dependency, deadline
or runner. Existing footer/bubble tests, primary owners and concurrent WP-502
Room/WP-503 accessibility work are untouched.

The same typed reservation evolves124->125 for the new owned test/evidence and
existing proposal/deviation paths. Immutable authorization, stored session and
attempt are retained; there is no release/recreate or runtime/hook/lock edit.

Declared focused selector, not executed locally:
`:app:testDebugUnitTest --tests com.meshcoreone.android.parity.MessageHeardCountOriginalPredicateTest`.
The method must be positively discovered and pass in its own exact-head App
suite. Source mappings and controller fixtures are not Kotlin execution.
Current direct preflight is BLOCKED, exit2, because explicit
`--state/ANDROID_CI_STATE` is absent. Normal publication may remain locally
UNVERIFIED; fresh automatic exact-head hosted checks are authoritative.
No duplicate CI-result bundle is retained or revalidated. Independent source
review and actual discovery/execution are not claimed by this source snapshot.
