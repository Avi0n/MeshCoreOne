# SM-REACTION-GRAPHEME: source classification preservation

This is the next explicitly allocated WP-216 concern, starting from clean main
`ab78bf9086da7c99c73e03d519d996a543b25029` after #354. It does not reopen the
equal-clock/hash or 22-family repeat-store repairs. The immutable reference is
`db14559b39d32322b06477c6ae676112f583db50`.

## Contract and independent boundary evidence

The pinned `ReactionParser.swift` accepts an emoji only when `value.first` is
emoji and `value.dropFirst().isEmpty`. Its original family-ZWJ case remains
unchanged, as do the skin-tone and flag assertions. The other associated original
positive family is `SyncCoordinatorMessageHandlerTests::parseChannelMessage
handles emoji in name()`, whose `Node🔥: hello` sender/body predicates are also
unchanged. Neither original positive family is reported as a failing case.

The retained Kotlin deviation attributed two Characters for doubled ZWJ to Swift
6.3.2 but deliberately expected Kotlin to accept it. This repair does not turn
that attribution into a new Swift run. The independent classification derivation
uses [UAX #29 revision 41, Unicode 15.0](https://www.unicode.org/reports/tr29/tr29-41.html#GB11):

| Input scalar/property sequence | Boundary consequence |
| --- | --- |
| `1F44D / Extended_Pictographic` | Start of first Character |
| `200D / ZWJ`, then another `200D / ZWJ` | Both attach by GB9 |
| Second `1F44D / Extended_Pictographic` | GB11's left context `Extended_Pictographic Extend* ZWJ` does not match: the intervening ZWJ is not Extend |
| No other joining rule applies | GB999 breaks before the second pictograph |

Thus `👍 + ZWJ + ZWJ + 👍` has two Characters under that rule, not one.
The pinned one-Character predicate rejects the channel and DM wire shapes.
The same reasoning applies to additional joiners or a SpacingMark interrupting
GB11 context; it is not a particular-string blacklist. A trailing joiner, even
two trailing joiners with no following pictograph, still attaches under GB9 and
must not be rejected.

The official Unicode 15.0 `GraphemeBreakTest.txt` additionally supplies independent
`1F6D1 ZWJ 1F6D1` joining and `0061 ZWJ / 1F6D1` breaking controls, plus `ZWJ ZWJ`
and `ZWJ Extend ZWJ` GB9 attachment controls. It is not presented as an official
doubled-thumbs-up test or a complete Kotlin conformance run.

A small source-investigation probe on the already installed JDK 21.0.12.1 found
both `Pattern("\\X")` and `java.text.BreakIterator` count the doubled-ZWJ input
as one cluster. Replacing the regex with that BreakIterator is therefore not a
fix. This was not Gradle, a canonical heavy verification job, Swift or Android
ICU execution.

Inspection of the installed JDK's
`java.base/jdk/internal/util/regex/Grapheme.java` confirmed that its `gb11`
context remains set from the initial pictograph while subsequent non-Extend
scalars are consumed. The reaction-local check resets this context; it does not
replace the shared grapheme engine.

### Standalone modifier and wire-order boundaries

The pinned `ReactionService.swift:95-103` formats channel text as
`@[sender]emoji\nhash`; the parser does not promise that every arbitrary emoji
scalar round-trips through that formatter. In `ReactionParser.swift:126-131`,
the mention-first branch searches for `afterAtBracket.firstIndex(of: "]")`,
which compares Characters, not individual scalars or substrings.

The same official inputs listed below classify `1F3FB..1F3FF` as GCB=Extend,
and `1F3F8..1F407` as Emoji. Consequently U+1F3FD joins a preceding U+005D
closing bracket by GB9. That combined Character is not the literal `"]"`
Character the pinned parser requires. This is a delimiter-adjacency constraint,
not a change to the standalone scalar's Emoji property.

| Wire shape, with `M` denoting standalone U+1F3FD | Pinned contract derivation |
| --- | --- |
| `@[Node]M\nabcdefgh` | Reject: `]M` is one Character, so no closing-bracket Character is found |
| `M@[Node]\nabcdefgh` | Accept exact emoji `M`, sender `Node`, hash `abcdefgh`: the leading modifier starts its own Character and the final bracket is unchanged |
| `M\nabcdefgh` | Accept exact DM emoji `M` and hash `abcdefgh`: the leading modifier is one Character, and newline breaks before the hash |

The latter two branches also use the actual pinned one-Character emoji predicate
and `Character+EmojiDetection.swift`: the first scalar must have Emoji and be
above U+238C (or be in a multi-scalar Character). The modifier satisfies those
conditions; they do not rescue the missing delimiter in the mention-first
branch. This is static pinned-source plus UAX reasoning, not fresh Swift runtime
execution. The supplementary test retains the modifier and asserts all three
orders independently, including both exact parsed fields and positive/negative
`isReactionText` results. The existing rendering-agreement corpus includes all
three orders as well. Original flag, combined skin-tone, family-ZWJ and
emoji-in-name assertions are unchanged.

## Explicit versioned vendored-data dependency

`ReactionGraphemeProperties.kt` is new vendored property data, not a claim of
zero new dependencies. It adds no Gradle library, platform API, network access,
new emoji policy or advance of the Swift reference pin. Its fixed Unicode
15.0.0 baseline matches the existing JDK 21 segmenter's baseline. The existing
separate Emoji-property table and shared text segmentation remain unchanged.

| Official input | SHA-256 | Selected property / generated size |
| --- | --- | --- |
| `https://www.unicode.org/Public/15.0.0/ucd/auxiliary/GraphemeBreakProperty.txt` | `5a0f8748575432f8ff95e1dd5bfaa27bda1a844809e17d6939ee912bba6568a1` | `Grapheme_Cluster_Break=Extend`, 364 merged ranges / 2,130 scalars |
| `https://www.unicode.org/Public/15.0.0/ucd/emoji/emoji-data.txt` | `29071dba22c72c27783a73016afb8ffaeb025866740791f9c2d0b55cc45a3470` | `Extended_Pictographic`, 78 merged ranges / 3,537 scalars |
| `https://www.unicode.org/Public/15.0.0/ucd/auxiliary/GraphemeBreakTest.txt` | `0d2080d0def294a4b7660801cc03ddfe5866ff300c789c2cc1b50fd7802b2d97` | Independent boundary controls read above; not vendored production data |

The generator takes the first two separately downloaded, hash-bound files,
selects the exact named properties and merges adjacent ranges. It has no network
access. It emits plain integer lookups, avoiding APIs unavailable at minSdk31 or
an Android dependency in the pure-JVM service. The new tests independently compare
the entire Extended_Pictographic membership against JDK 21 and the entire Extend
membership against a digest derived directly from the official UCD file, not
from the generated Kotlin or its generator. The Extend oracle is SHA-256 of
ascending member scalars encoded as LE32: 2,130 members,
`3a8199c1373e1bcebe32e029de62eacc351ecef2a8dc5faff767d4d5619b2e38`.
Those are property-data assertions, not proof of all Unicode segmentation.

Both original input headers contain `© 2022 Unicode®, Inc.`, the Unicode/logo
trademark notice and `https://www.unicode.org/terms_of_use.html`. These original
notices are retained in the generated file. Today's official
`https://www.unicode.org/license.txt` is Unicode License V3,
`Copyright © 1991-2026 Unicode, Inc.`; it is not described as an original 2022
V3 license or substituted silently for the input headers.

The existing application asset
`android/app/src/main/assets/licenses/Unicode-License.txt` is byte-identical to
that current official notice, SHA-256
`e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96`.
It is already bound by `tools/android-port/emoji_convert.py`, resides in the
standard application assets source set and is recursively displayed by the real
`AppLicensesContent`. The generator verifies that notice's bytes; the new App
asset test checks it through the application's merged AssetManager. No notice,
settings screen, packaging rule or license validator is changed. Actual APK
extraction and legal acceptance have not occurred in this repair.

For downloaded inputs `$gbp` and `$emojiData`, reproducibility commands are:

```powershell
python docs\android\evidence\WP-216\generate-grapheme-properties.py $gbp $emojiData
python docs\android\evidence\WP-216\generate-grapheme-properties.py $gbp $emojiData --check
```

Changing an input's bytes or the existing packaged notice fails closed. These
commands are source/data reproducibility checks, not Kotlin execution.

## Real consumer obligations and admitted rendering integration

The new App test uses production Room persistence, mounted firmware polling,
`SyncReactionAdapter`/`ReactionTextParsing`, the actual
`AppChatsFeature.react` send path, real `MessageService` and captured protocol
frames. Rejected doubled-ZWJ input must become an ordinary incoming message,
with no target reaction or summary. Valid family/skin-tone input must still
become a stored reaction rather than an ordinary message. Emoji in the channel
target's sender name must survive parsing and target matching.

Outgoing ordinary text must retain its exact transmitted/persisted wire bytes
and no longer be classified or hidden as a reaction. Positive family/flag sends
retain their original reaction visibility; failed outgoing rows remain visible.
Both the canonical visibility extension and the rendering consumer's default
predicate must agree. These checks do not claim new first-arrival atomicity,
cache lifecycle behavior, hardware, a UI rendering run or on-device API31 proof.

The mounted fixture allows the real battery bootstrap to finish with its
3,700-mV firmware response and a live refresh loop before any reaction action.
It then stops that unrelated monitor, identifies the one cancelled direct child
of the injected background scope, joins it and asserts completion and inactive
monitor state. It does not merely inspect cleared monitor fields or cancel the
App graph. It stops background auto-fetch through the production polling service,
asserts the real message handlers remain wired and drives that same service's
`pollAllMessages` explicitly. This also joins its autonomous catch-up so it cannot
race the fixture's queued frames; it does not replace the consumer. Polling
asserts both complete `0a` commands; sending asserts exactly one complete
post-action frame before checking its command and exact payload.
No unexpected action frame, including a battery query, is filtered out. All nine
App methods, including these fixture assertions, passed in the original failed
`be13fc70` guard described below; that is not whole-closure acceptance or
acceptance of a subsequent candidate.

The rendering integration path is
`android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/rendering/ReactionWireFormat.kt`.
Its initial `modify` admission returned `no trusted path+operation rule`; the
path remained untouched until #357 normally landed at trusted
`047ecbfb0f8837109a3aa1ff3fd4f522ca868a18`. Fresh admission then succeeded for
`WP-216 / wp216-reaction-wire-classification /
reconcile-reaction-wire-classification` on that one existing file. The
same-identity reservation attempt retained the historical
source/base/manifest/backend mismatch; no old identity, authorization, lease or
ledger was rewritten. The exact path had no intervening main edit or unmerged
producer edit under the matching broad advisory intent.

The adapter now delegates channel parse, DM parse and hash to `ReactionParser`.
DM results retain the existing `(emoji, messageHash)` Pair shape. WP-213's two
source-provenance headers, LRU key consumers and the visibility predicate's
outgoing-only / FAILED-visible rules remain intact. Visibility still uses the
PocketMesh channel or DM parser, not the broader `isReactionText` classifier
that also recognizes MeshCoreOpen formats. The original agreement cases and
all their old corpus are retained. Shared `SwiftTextSemantics.kt` and every
other rendering production file remain unchanged. Original execution outcomes
are recorded below; source integration alone does not establish acceptance.

## Mandatory identities and original execution

The source-derived mandatory inventory is 84 distinct identities. This is a
lower-bound identity obligation inside the full declared consumer
closure, never a filtered-test substitute for it.

| Class | Mandatory identities |
| --- | --- |
| `ReactionParserTest` | All 44 unchanged original families, existing channel round-trip, 8 new GB11/property-data cases: 53 |
| `SyncCoordinatorMessageHandlerTests` | All 10 existing channel-message parsing controls, including emoji in the sender name |
| `ReactionsSwiftOracleTest` | All 8 existing `reactionParserVectors` families, with their original vectors unchanged |
| `ReactionWireFormatAgreementTest` | All 4 existing parse/DM/hash/cache cases; old corpus retained and GB11 boundary inputs appended |
| `ReactionGraphemeClassificationTest` | 8 new real sync/send boundary methods and the application-asset notice method: 9 |

Source-safe `python tools\android-port\controller\ci.py python --output <session-output>`
passed 15 scaffold and 108 parity-accounting tests, zero failures/errors/skips,
including the declared manifest, traceability, pinned-notice and parity
validators. `python tools\android-port\emoji_convert.py --check` also passed its
existing 1,932-record / 3-notice checks. Generation and strict `--check` passed
against the two official hash-bound inputs. These are precommit source/data
checks, not a Kotlin or exact-candidate guarded PASS.

### Original guarded candidate: failed, not superseded by preparation

The single authorized original guard used source
`be13fc702c26bb79549ece96fa75307cf02a82bf`, tree
`ac5a1284d59abb92adfeee9258263e27512caa06`, and the actual installed clean trusted
authority `047ecbfb0f8837109a3aa1ff3fd4f522ca868a18`. Its normal checkout-derived
selection was 40 tasks across 10 consumer modules, stages `python`, `preflight`
and `scaffold`, with `full_scaffold=false` and reason
`actual-diff-and-consumer-closure`. No path/module/task filters or fake base were
used.

From that trusted checkout, the actual command was:

```sh
/usr/bin/env -i HOME=/home/cbattagler \
 PATH=/ai_cache/meshcoreone/python/3.12.4/bin:/usr/bin:/bin \
 LANG=C.UTF-8 PYTHONDONTWRITEBYTECODE=1 \
 ANDROID_LOCAL_HOME=/ai_cache/meshcoreone/execution/single-shared \
 ANDROID_LOCAL_PYTHON=/ai_cache/meshcoreone/python/3.12.4/bin/python3.12 \
 ANDROID_PORT_LEDGER=/ai_cache/meshcoreone/control/android-port-shared.sqlite3 \
 /ai_cache/meshcoreone/python/3.12.4/bin/python3.12 -B \
 tools/android-port/local/check.py \
 --commit be13fc702c26bb79549ece96fa75307cf02a82bf \
 --affected-base 047ecbfb0f8837109a3aa1ff3fd4f522ca868a18 \
 --parallel-jvm
```

**Result: exit 1, whole selected closure FAILED.** Python/controller stages
reported 15 + 108 + 70 passing tests. Gradle failed at `:core:services:test`
after 23m51s; that duration is Gradle's report, not a claimed whole-command
duration. Consumed original source-bound XML showed:

| Selected module | Discovered | Passed | Failed | Errors / skipped |
| --- | ---: | ---: | ---: | ---: |
| `app` | 1,617 | 1,617 | 0 | 0 / 0 |
| `core/data` | 436 | 436 | 0 | 0 / 0 |
| `core/maps` | 28 | 28 | 0 | 0 / 0 |
| `core/services` | 2,701 | 2,700 | 1 | 0 / 0 |
| Total available XML | 4,782 | 4,781 | 1 | 0 / 0 |

The six other selected modules (`core/ui`, `feature/chats`, `feature/map`,
`feature/nodes`, `feature/remotenodes`, `feature/tools`) had no test XML after
the abort. Selecting 40 tasks does not mean all 40 completed.

All 84 mandatory identities were present exactly once: 83 passed and one
failed, with none missing, errored or skipped. The sole failure was
`ReactionParserTest::WP-216::flags skin tones and emoji in target names retain
exact wire fields`: its new blanket helper incorrectly expected the
mention-first standalone U+1F3FD to produce
`ParsedReaction(emoji=U+1F3FD, targetSender=Node, messageHash=abcdefgh)`, but the
actual parser returned null. No original 44-family failure occurred. The
correction above preserves that input and replaces only the supplementary
uniform-order assumption with the independently derived order-specific
predicates. Production classification, formatting, hash, visibility and all
original assertions are unchanged.

The original run's source snapshot remained clean at the exact head/tree and
all nine committed source blobs matched. Trusted HEAD, origin/main and tree
also remained unchanged and clean. Readback confirmed owned PID 471621 absent,
owned temporary execution directory absent and the exact owner's hard-lock
list empty. Original XML and the original `gradle-scaffold.log` were consumed
before slot reuse; the installed 047 failure path did not produce normalized
named-result/scaffold JSON. No replacement CI-result bundle was created,
uploaded or hashed.

The historical 610f preparation plan selected full-scaffold fallback because
its diff included later main planner changes; it was not used for this guard.

**Status:** the original 40-task closure remains FAILED. The source-coupled
test-only correction is unexecuted preparation, requiring a new exact-source
freeze, protected review and separately allocated guard. No original 84-method
acceptance transfers to it. No publication, fresh Swift runtime, real Android
ICU/device, legal or full-Unicode acceptance has occurred.
