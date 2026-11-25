import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:meshcore_open/helpers/mcoimg_types.dart';
import 'package:meshcore_open/helpers/mcoimg_v3_codec.dart';

void main() {
  test('tagged Dart codec decodes Swift-generated MCOimg v3 bodies', () async {
    final path = Platform.environment['MCOIMG_FIXTURES'];
    expect(path, isNotNull, reason: 'Set MCOIMG_FIXTURES to v3-swift-encoded.json');
    final document = jsonDecode(await File(path!).readAsString()) as Map<String, dynamic>;
    final fixtures = document['fixtures'] as List;
    final codec = MCOImageV3Codec();
    for (final raw in fixtures) {
      final fixture = Map<String, dynamic>.from(raw as Map);
      final name = fixture['name'] as String;
      final body = Uint8List.fromList(base64Decode(fixture['bodyBase64'] as String));
      final app = Uint8List.fromList(base64Decode(fixture['appPayloadBase64'] as String));
      final expectedProfile = PaletteProfile.values.firstWhere((profile) => profile.name == fixture['paletteProfile']);
      for (final image in [codec.decodeBody(body), codec.decodeAppPayloadWithoutSender(app), codec.decodeText(fixture['text'] as String)]) {
        expect(image.width, fixture['width'], reason: name);
        expect(image.height, fixture['height'], reason: name);
        expect(image.paletteProfile, expectedProfile, reason: name);
        expect(image.transparentColor, fixture['transparentColor'], reason: name);
        expect(image.pixels, fixture['pixels'], reason: name);
      }
      expect(MCOImageV3Codec.bodyFromText(fixture['text'] as String), body, reason: name);
    }
    // Printed only by this reproducible development verifier, never by application code.
    // ignore: avoid_print
    print('Tagged Dart verified ${fixtures.length} Swift v3 fixtures (body, app payload, text)');
  });
}
