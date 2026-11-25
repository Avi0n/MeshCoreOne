#!/usr/bin/env python3
"""Run independent v4 command tests against the released Dart implementation.

Usage: export_v4_reference.py UPSTREAM OUTPUT_DIRECTORY COMMAND_TEST_SOURCE
Then run the printed flutter test command in UPSTREAM.
Decoder: HDDen/meshcore-open tag 9.5.1-mod-1.9.0, commit
fe375741226588440e268002b4975349b2e95ffe. Command test assembly source:
d388ee8444078635d74dd98ab288269c3a8a9162. The tests postdate the release.
"""
import pathlib
import sys

upstream = pathlib.Path(sys.argv[1]).resolve()
destination = pathlib.Path(sys.argv[2]).resolve()
test_source = pathlib.Path(sys.argv[3])
source = test_source.read_text()
source = source.replace("import 'dart:math'", "import 'dart:convert';\nimport 'dart:io';\nimport 'dart:math'", 1)
source = source.replace("_codec.decodeBody(", "_recordDecode(")
helper = r'''
final _recorded = <String, Map<String, Object?>>{};
Object? _color(MCOImageV4Document d, int? value) => value == null ? null : d.palette[value];
Map<String, Object?> _style(MCOImageV4Document d, MCOImageV4Style s) => {
  'fillColor': _color(d, s.fillColor), 'strokeColor': _color(d, s.strokeColor), 'strokeWidth': s.strokeWidth,
};
List<int> _point(MCOImageV4Point p) => [p.x, p.y];
Map<String, Object?> _figure(MCOImageV4Document d, MCOImageV4Figure f) {
  final result = <String, Object?>{'style': _style(d, f.style)};
  switch (f) {
    case MCOImageV4Dot(:final point): result.addAll({'kind':'dot','point':_point(point)});
    case MCOImageV4Line(:final start,:final end): result.addAll({'kind':'line','start':_point(start),'end':_point(end)});
    case MCOImageV4Rect(:final first,:final second,:final third): result.addAll({'kind':'rectangle','first':_point(first),'second':_point(second),'third':_point(third)});
    case MCOImageV4Ellipse(:final first,:final second,:final third): result.addAll({'kind':'ellipse','first':_point(first),'second':_point(second),'third':_point(third)});
    case MCOImageV4Path(:final points,:final closed): result.addAll({'kind':'path','points':points.map(_point).toList(),'closed':closed});
    case MCOImageV4Wave(:final start,:final end,:final depth,:final closed): result.addAll({'kind':'wave','start':_point(start),'end':_point(end),'depth':depth,'closed':closed});
    case MCOImageV4Text(:final origin,:final fontSize,:final align,:final text): result.addAll({'kind':'text','origin':_point(origin),'width':f.resolvedWidth(d.width),'fontSize':fontSize,'alignment':align.index,'text':text});
    case MCOImageV4Group(:final figures): result.addAll({'kind':'group','figures':figures.map((child)=>_figure(d,child)).toList()});
    case MCOImageV4RasterLayer(:final x,:final y,:final width,:final height,:final pixels,:final transparentColor): result.addAll({'kind':'raster','origin':[x,y],'width':width,'height':height,'profile':d.paletteProfile.index,'pixels':pixels,'transparentColor':transparentColor});
  }
  return result;
}
DecodedMCOImageV4 _recordDecode(Uint8List body) {
  final key = base64.encode(body);
  try {
    final decoded = _codec.decodeBody(body);
    final d = decoded.document;
    _recorded[key] = {'bodyBase64':key,'document':{
      'width':d.width,'height':d.height,'profile':d.paletteProfile.index,
      'backgroundColor':_color(d,d.backgroundColor),'figures':d.figures.map((f)=>_figure(d,f)).toList(),
      'targetName':decoded.targetName,'replyTimestamp':decoded.replyTimestamp,
    }};
    return decoded;
  } catch(error) {
    _recorded[key] = {'bodyBase64':key,'error':error is MCOImageUnsupportedFormatException ? 'unsupported':'damaged'};
    rethrow;
  }
}
'''
metadata = "'decoderTag':'9.5.1-mod-1.9.0','decoderCommit':'fe375741226588440e268002b4975349b2e95ffe','assemblyCommit':'d388ee8444078635d74dd98ab288269c3a8a9162','source':'independent command-test bit assembly','fixtures':_recorded.values.toList()"
source = source.replace("void main() {", "void main() {\n  tearDownAll(() {\n    File(" + repr(str(destination / "MCOimgV4Fixtures.json")) + ").writeAsStringSync(const JsonEncoder.withIndent('  ').convert({" + metadata + "}));\n  });", 1)
source += helper
output = upstream / "test/helpers/mcoimg_v4_export_test.dart"
output.write_text(source)
print("flutter test test/helpers/mcoimg_v4_export_test.dart")
