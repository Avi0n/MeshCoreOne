import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshcore_open/helpers/mcoimg_types.dart';
import 'package:meshcore_open/helpers/mcoimg_v3_codec.dart';
import 'package:meshcore_open/helpers/mcoimg_v4_codec.dart';
import 'package:meshcore_open/helpers/mcoimg_v4_model.dart';

void main() {
  test('v3 raster versus v4 mixed complete transport cost', () {
    final output = <Map<String, Object?>>[];
    for (final dense in [false, true]) {
      const side = 16;
      final pixels = List<int>.generate(side * side, (i) {
        final x = i % side, y = i ~/ side;
        return dense ? ((x * 3 + y * 5) % 8) : (x == y || x + y == side - 1 ? 3 : 0);
      });
      final image = MCOImage(width: side, height: side, paletteProfile: PaletteProfile.master8,
        pixels: pixels, encodingVersion: MCOImageEncodingVersion.v3);
      final raster = MCOImageV3Codec().encode(image);
      final v3Body = MCOImageV3Codec.refreshPacketNonce(raster.body, nonce: 42);
      final document = MCOImageV4Document(width: side, height: side,
        paletteProfile: PaletteProfile.master8, palette: [0, 2], backgroundColor: 0,
        figures: [MCOImageV4RasterLayer(x: 0, y: 0, width: side, height: side, pixels: pixels)]);
      final mixed = const MCOImageV4Codec().encode(document, nonce: 42);
      final v3Text = MCOImageV3Codec.textFromBody(v3Body);
      final v4Text = const MCOImageV4Codec().textFromBody(mixed.body);
      for (final name in ['Radio', '救援隊']) {
        final nameBytes = utf8.encode(name).length;
        output.add({'art': dense ? 'dense16' : 'cross16', 'senderName':name, 'senderNameBytes':nameBytes,
          'v3BodyBytes':v3Body.length, 'v4MixedBodyBytes':mixed.body.length,
          'v3ChannelDataBytes':1+nameBytes+1+v3Body.length,
          'v4ChannelDataBytes':1+nameBytes+1+mixed.body.length,
          'channelDataBudget':165, 'channelTextBudget':139-nameBytes-2,
          'v3TextBytes':utf8.encode(v3Text).length,'v4TextBytes':utf8.encode(v4Text).length,
          'v3ComposedChannelTextBytes':nameBytes+2+utf8.encode(v3Text).length,
          'v4ComposedChannelTextBytes':nameBytes+2+utf8.encode(v4Text).length,
          'v3BinaryHostCommandBytes':5+1+nameBytes+1+v3Body.length,
          'v4BinaryHostCommandBytes':5+1+nameBytes+1+mixed.body.length,
          'v3ChannelTextHostCommandBytes':7+utf8.encode(v3Text).length,
          'v4ChannelTextHostCommandBytes':7+utf8.encode(v4Text).length,
          'v3DirectTextHostCommandBytes':13+utf8.encode(v3Text).length,
          'v4DirectTextHostCommandBytes':13+utf8.encode(v4Text).length,
          'directTextBudget':150});
      }
    }
    File(Platform.environment['MCOIMG_COST_OUTPUT'] ?? 'mcoimg-wire-cost.json')
      .writeAsStringSync(const JsonEncoder.withIndent('  ').convert(output));
  });
}
