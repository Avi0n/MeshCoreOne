import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:meshcore_open/helpers/mcoimg_types.dart';
import 'package:meshcore_open/helpers/mcoimg_v4_codec.dart';
import 'package:meshcore_open/helpers/mcoimg_v4_model.dart';

void main() {
  test('tagged Dart decodes Swift v4 mixed raster payloads', () async {
    final path = Platform.environment['MCOIMG_FIXTURES']!;
    final fixtures = (jsonDecode(await File(path).readAsString()) as Map)['fixtures'] as List;
    const codec = MCOImageV4Codec();
    for (final raw in fixtures) {
      final fixture = Map<String, dynamic>.from(raw as Map);
      final body = Uint8List.fromList(base64Decode(fixture['bodyBase64'] as String));
      final app = base64Decode(fixture['appPayloadBase64'] as String);
      final name = fixture['name'] as String;
      expect(app, [0x14, ...body], reason: name);
      expect(codec.bodyFromText(fixture['text'] as String), body, reason: name);
      for (final decoded in [codec.decodeBody(body), codec.decodeText(fixture['text'] as String)]) {
        final document = decoded.document;
        expect(decoded.nonce, fixture['nonce'], reason: name);
        expect(document.mode, MCOImageV4Mode.mixed, reason: name);
        expect(document.width, fixture['width'], reason: name);
        expect(document.height, fixture['height'], reason: name);
        expect(document.paletteProfile, PaletteProfile.values[fixture['profile'] as int], reason: name);
        expect(document.figures.length, 1, reason: name);
        final layer = document.figures.single as MCOImageV4RasterLayer;
        if (fixture['transparentColor'] != null) {
          expect(document.backgroundColor, isNull, reason: name);
        }
        expect([layer.x, layer.y, layer.width, layer.height],
          [0, 0, fixture['width'], fixture['height']], reason: name);
        expect(layer.transparentColor, fixture['transparentColor'], reason: name);
        expect(layer.pixels, fixture['pixels'], reason: name);
        expect(decoded.targetName, isNull, reason: name);
        expect(decoded.replyTimestamp, isNull, reason: name);
        if (name.startsWith('dimensions-')) {
          expect(codec.encode(document, nonce: decoded.nonce).body, body, reason: name);
        }
      }
    }
    // ignore: avoid_print
    print('Tagged Dart verified ${fixtures.length} Swift v4 mixed rasters');
  });
}
