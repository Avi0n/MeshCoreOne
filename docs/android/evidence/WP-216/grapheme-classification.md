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

## Real consumer obligations and current integration boundary

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

The remaining production integration path is
`android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/rendering/ReactionWireFormat.kt`.
An actual WP-216 `modify` admission at the starting trusted policy returned
`no trusted path+operation rule`. It is untouched. Its WP-213 stand-in must
delegate/equivalently reconcile channel parse, DM parse and hash with the
canonical parser, retaining the existing agreement tests and outgoing failure
semantics. The coordinator has the exact producer/narrow-rule packet. No shared
`SwiftTextSemantics.kt` or broader rendering permission is requested.

## Prepared mandatory identities (not executed discovery)

The source-derived mandatory inventory is 84 distinct identities. This is a
lower-bound identity obligation inside the eventual full declared consumer
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

**Status:** source work and source-safe checks are not Kotlin acceptance. No
grapheme heavy job, publication, fresh Swift runtime, real Android ICU/device,
legal or full-Unicode acceptance has occurred. The final candidate, exact
supported-trusted-base consumer closure and mandatory XML identities must be
frozen only after that affected support gate is resolved.
