# MCOimg compatibility checks

The codec target is HDDen/meshcore-open tag `9.5.1-mod-1.9.0`, commit
`fe375741226588440e268002b4975349b2e95ffe`. The Dart codec, palette tables,
model, MCOtxt generation-0 tables, and Flutter painter at that commit are the
normative sources. The upstream code is GPL-3.0, as is MeshCore One.

The Swift v3 decoder fixtures are copied from the upstream JavaScript fixture
exchange. `verify-v3.swift` runs every fixture and writes Swift encoder output.
The Swift encoder deliberately searches a smaller candidate set than Dart;
decoded pixels, rather than identical compressed bytes, establish compatibility.

V4 has no published cross-runtime fixture exchange. Its fixture JSON records
streams independently assembled by the later upstream command tests, decoded
by the tagged Dart codec. `export_v4_reference.py` instruments those tests to
export document dumps and rejection classes. The assembly source revision and
export command must accompany regenerated fixture evidence. Text and raster
layers are compared as decoded values; platform fonts and antialiasing are
client choices.

`measure_wire_cost_test.dart` measures v3 raster and v4 mixed encodings of the
same pixels. Copy it into the reference package's `test/helpers/` and run
`flutter test test/helpers/measure_wire_cost_test.dart` with
`MCOIMG_COST_OUTPUT` set to the desired JSON path. It includes the UTF-8 sender
envelope for GROUP_DATA and the complete Base91 wrappers and sender prefix for
text transport. A mixed raster still uses v3 internally. DMs send v3 text;
channels send only v4 mixed GROUP_DATA (`0x0120`, subtype/version `0x14`).
Channels never fall back to text, including on older firmware. A radio that
rejects GROUP_DATA follows the existing failed-send path.

A new image Send uses a fresh nonce before measuring its destination envelope.
Send Again refreshes and persists the prepared content before queue capture.
Automatic retries retain the nonce, matching the tagged client's
`_preparedOutboundTexts` and deferred `binaryOutbound` behavior. DM ACK prediction
uses its exact text. Binary channel images have no sender timestamp and do not
enter the text reaction index.

To reproduce v3 verification from the MeshCore One worktree:

```sh
mkdir -p tmp/mcoimg-module-cache
swiftc -parse-as-library -module-cache-path tmp/mcoimg-module-cache \
  MC1Services/Sources/MC1Services/Utilities/MCOimg/MCOImageV3*.swift \
  MC1Services/Sources/MC1Services/Utilities/MCOimg/MCOImageRaster.swift \
  MC1Services/Sources/MC1Services/Utilities/MCOimg/MCOImagePalette*.swift \
  MC1Services/Sources/MC1Services/Utilities/MCOimg/MCOImageBase91.swift \
  MC1Services/Sources/MC1Services/Utilities/MCOimg/MCOImageBit*.swift \
  MC1Services/Sources/MC1Services/Utilities/MCOimg/MCOImageCodecError.swift \
  scripts/mcoimg/verify-v3.swift -o tmp/mcoimg-v3-verify
tmp/mcoimg-v3-verify scripts/mcoimg/fixtures/v3-upstream-decoder.json \
  scripts/mcoimg/fixtures/v3-swift-encoded.json
```

This checks all 39 upstream fixtures and generates 73 Swift bodies: those same
images, a painted 16×16 image, a dense 16×16 image, and all 16 palette profiles
with nonce values 0 and 255. It also verifies Swift Base91 decoding against the
upstream text fixtures. The generated text and app payload fields always refer
to the generated body.

From the tagged upstream Flutter package, run the independent verifier using
absolute paths to this worktree:

```sh
MCOIMG_FIXTURES=/absolute/worktree/scripts/mcoimg/fixtures/v3-swift-encoded.json \
  flutter test --no-pub /absolute/worktree/scripts/mcoimg/verify-v3.dart
```

The verifier invokes the tagged Dart `MCOImageV3Codec` for body, app payload,
and text decoding and compares every pixel, palette profile, dimension, and
transparent color. It runs under Flutter because the original palette code
imports `dart:ui`. A minimal verification pubspec needs `flutter`, `crypto`,
and `flutter_test`; do not substitute codec or palette implementations.
`MCOImageV3CodecTests` additionally covers malformed dimensions, unknown
algorithm IDs, nonzero padding, every fixture truncation, trailing bytes,
invalid color references, nonce changes, and exact palette anchors.


## Swift v4 mixed raster verification

`verify-v4-raster.swift` wraps all 73 Swift v3 fixtures and seven dimension-mode
boundaries as v4 mixed documents. It checks Swift decode equality and writes
`fixtures/v4-swift-raster.json`. Build it with the same v3 sources above plus
`MCOImageV4*.swift`, `MCOImageCodec.swift`, `MCOImageContent.swift`,
`MCOImageFigure.swift`, and `MCOText*.swift`, substituting the verifier filename.
Then run the unchanged tagged Dart codec from its Flutter package:

```sh
MCOIMG_FIXTURES=/absolute/worktree/scripts/mcoimg/fixtures/v4-swift-raster.json \
  flutter test --no-pub /absolute/worktree/scripts/mcoimg/verify-v4-raster.dart
```

Dart compares every raster pixel, dimension, palette, transparency, origin, mixed
mode and nonce for both body and text decoding. Transparent rasters preserve a
nil document background. The seven white-raster dimension cases also reproduce
the tagged encoder's canonical bytes. These are locally generated
cross-runtime checks, not a published upstream v4 fixture exchange. The wrapper
retains Swift's selected v3 compression; it does not claim the Dart optimizer
would choose identical compressed bytes.

## Scope and retained state

DMs and channels render v3 and v4 text. Channels also receive the registered
`0x0120` binary envelope, including during initial sync. Rooms render both text
versions through the shared renderer; their separate composer has no paint
entry. Outgoing DM drawings use v3 text; channel drawings use v4 mixed binary
GROUP_DATA. Both retain the existing service-owned queue and stored text content.
The channel `im4:` value is an internal persistence representation; it is never
sent as channel text. No queue or persisted payload field was added. Legacy v3
queued channel images are deterministically wrapped as v4 at transmission,
preserving their compressed raster bytes and nonce. User retry upgrades and
persists v4 before capturing its queue envelope.

The fixed 16×16 Master 8 editor supports painting and erasing with palette
colors. Send on the cover admits the current pixels. Cancel discards them.
Neither changes composer text. The conversation input owns unsent pixels in
memory until admission, cancellation, or conversation closure; closing the app
also discards those pixels. Encoding runs off the main actor from an immutable
snapshot. Painting and Clear do nothing during admission, and Cancel stays
enabled until admission begins. Disconnection and encoding or admission failure
keep the pixels on the cover. Closing the conversation retires the drawing
before admission. After a successful admission, the service queue owns the send
and continues after the cover closes. Existing text remains a separate Draft.

V4 rendering clips overscan geometry, preserves paint order, uses cell-center
vector anchors and cell-edge raster origins, non-zero fills, square stroke caps,
miter joins with the reference painter's limit, and circular dots. Open paths
close for fill only. Text uses system fonts and client line metrics, which are
not specified as pixel-identical across platforms. Raster-only v4 mode and
unknown extensions show the unsupported placeholder; damaged documents show
the damaged placeholder without partially painting.

V4 capability limits are 4,096 expanded figures and 64 nested groups. Limits
are checked before allocating repeat expansion, including zero-delta runs;
exceeding them is unsupported, rather than damaged.

## Measured cost and validation

The tagged Dart encoders measured the same 16×16 cross (nonce 42) as follows.
The sender is `Radio` (5 UTF-8 bytes). Values include all application envelopes.

| Representation | V3 raster | V4 mixed |
| --- | ---: | ---: |
| Body | 28 | 34 |
| Binary channel application payload | 35 | 41 |
| Binary flood host command | 40 | 46 |
| Complete text wrapper | 40 | 46 |
| Channel text including `Radio: ` | 47 | 53 |
| Channel text host command | 47 | 53 |
| DM text host command | 53 | 59 |

The binary app budget is 165 bytes. The text budget is 150 UTF-8 bytes for a
DM, and `139 - senderNameUTF8Bytes - 2` for a channel (132 for `Radio`, 128 for
`救援隊`). The editor measures the final random-nonce v3 text for a DM, or the
complete v4 binary sender envelope for a channel. The service revalidates the
current sender name at drain. A stored Base91 string can exceed the channel
text budget while its binary envelope fits. V4 mixed adds document fields around
the compressed v3 raster; v4 raster-only mode remains reserved. V3 is the smaller
DM pixel format; channels use v4 binary so clients without MCOimg do not display
encoded text.
Host command sizes include command/header fields and the binary data type;
RF routing, encryption padding and airtime vary with the actual radio path.

On 2026-10-26:

- All 39 published upstream v3 decoder fixtures matched exactly.
- Tagged Dart independently decoded 73 Swift-generated images through body,
  app payload and text paths, including every palette profile and nonce variants.
- Swift matched 126 v4 tagged-decoder document/error fixtures and 17 MCOtxt
  vectors covering the seven frozen generation-0 language tables. The independent
  command assembly's canonical encoder assertions also passed under tagged Dart.
- 2,370 MC1Services tests passed serially after the binary channel change,
  including backup, receive, send queue, pool backoff, retry and disconnect suites.
- All 538 MeshCore tests passed, including text/binary scope serialization and
  cancellation/ACK ordering.
- Tagged Dart independently decoded 80 Swift v4 mixed rasters. Seven white-raster
  dimension cases matched its canonical encoder bytes exactly.
- 65 focused app regressions passed after the binary change on iPhone 17e/iOS
  27.0. Presentation coverage is the full-screen cover, the dismissal guard,
  Cancel discarding pixels, Send admitting once, one cover alert when admission
  fails, and hardware Return sending only typed text.
- SwiftLint and whitespace checks passed. SwiftGen was regenerated and stable.
  The final simulator build passed with three warnings in unchanged concurrency
  code (`MessageBubbleSecondaryClick` and `MessagePathPreviewSnapshot`).

The normal pinned simulator was occupied by another chat, so app verification
used an isolated iPhone 17e on the same iOS 27.0 runtime. No physical-radio
exchange or published v4 cross-runtime fixture set was available. New strings
use L10n in all twelve locales; non-English additions are marked English
translation placeholders, following the repository localization workflow.

## Binary channel change preflight

| Check | Result |
| --- | --- |
| SwiftLint | Passed, zero violations |
| Project generation | `make test-app` regenerated the project; project.yml unchanged |
| SwiftGen | Regenerated, output unchanged |
| UI copy and state naming | No new strings; L10n and errorMessage retained |
| Persistence/backup | No stored-field or DTO schema changes; full backup suite passed |
| Build | iPhone 17e/iOS 27.0 passed; three pre-existing concurrency warnings |
| Whitespace | `git diff --check` passed |

Binary tests cover inline/pending/resend, legacy v3 replay across disconnect,
TABLE_FULL backoff and durable replay, stable automatic nonces, prepared user
resends, malformed/unsupported rejection without text fallback, and UTF-8
165/166-byte boundaries at admission and drain. The two presentation tests passed
on the isolated simulator after its accessibility service was initialized with
MobileBuildMCP `snapshot_ui`; an inactive service returns an empty in-process
SwiftUI accessibility tree. No physical-radio exchange was available.
