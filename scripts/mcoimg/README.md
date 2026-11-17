# MCOimg compatibility checks

The codec target is HDDen/meshcore-open tag `9.5.1-mod-1.9.0`, commit
`fe375741226588440e268002b4975349b2e95ffe`. The Dart codec, palette tables,
model, MCOtxt generation-0 tables, and Flutter painter at that commit are the
normative sources. The upstream code is GPL-3.0, as is MeshCore One.

The Swift v3 decoder fixtures are copied from the upstream JavaScript fixture
exchange. `verify-v3.swift` runs every fixture and writes Swift encoder output
under `tmp/`. The v4 raster verifier writes its dump there too. Neither file
is checked in. The Swift encoder deliberately searches a smaller candidate set
than Dart. Decoded pixels, rather than identical compressed bytes, establish
compatibility.

V4 has no published cross-runtime fixture exchange. Its fixture JSON records
streams independently assembled by the later upstream command tests, decoded
by the tagged Dart codec. `export_v4_reference.py` instruments those tests to
export document dumps and rejection classes. The assembly source revision and
export command must accompany regenerated fixture evidence. Text and raster
layers are compared as decoded values; platform fonts and antialiasing are
client choices.

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
  tmp/mcoimg-v3-swift-encoded.json
```

This checks all 39 upstream fixtures and generates 73 Swift bodies: those same
images, a painted 16×16 image, a dense 16×16 image, and all 16 palette profiles
with nonce values 0 and 255. It also verifies Swift Base91 decoding against the
upstream text fixtures. The generated text and app payload fields always refer
to the generated body.

From the tagged upstream Flutter package, run the independent verifier using
absolute paths to this worktree:

```sh
MCOIMG_FIXTURES=/absolute/worktree/tmp/mcoimg-v3-swift-encoded.json \
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
the JSON path given as its second argument. Build it with the same v3 sources
above plus `MCOImageV4*.swift`, `MCOImageCodec.swift`, `MCOImageContent.swift`,
`MCOImageFigure.swift`, and `MCOText*.swift`, and pass
`-o tmp/mcoimg-v4-raster-verify`. The input is the v3 file written above.
Both files stay under `tmp/`.

```sh
tmp/mcoimg-v4-raster-verify tmp/mcoimg-v3-swift-encoded.json \
  tmp/mcoimg-v4-swift-raster.json
```

Then run the unchanged tagged Dart codec from its Flutter package:

```sh
MCOIMG_FIXTURES=/absolute/worktree/tmp/mcoimg-v4-swift-raster.json \
  flutter test --no-pub /absolute/worktree/scripts/mcoimg/verify-v4-raster.dart
```

Dart compares every raster pixel, dimension, palette, transparency, origin, mixed
mode and nonce for both body and text decoding. Transparent rasters preserve a
nil document background. The seven white-raster dimension cases also reproduce
the tagged encoder's canonical bytes. These are locally generated
cross-runtime checks, not a published upstream v4 fixture exchange. The wrapper
retains Swift's selected v3 compression; it does not claim the Dart optimizer
would choose identical compressed bytes.
