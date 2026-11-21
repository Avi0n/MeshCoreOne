import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:meshcore_open/MCOtxt/mcotxt.dart';

// Independent MSB-first field assembly exercises every frozen prediction entry.
class _Bits {
  final bits = <int>[];
  void put(int value, int count) {
    for (var index = count - 1; index >= 0; index--) {
      bits.add((value >> index) & 1);
    }
  }
  Uint8List get bytes {
    final result = Uint8List((bits.length + 7) ~/ 8);
    for (var index = 0; index < bits.length; index++) {
      result[index ~/ 8] |= bits[index] << (7 - index % 8);
    }
    return result;
  }
}

void main() {
  test('export frozen generation 0 decoder vectors', () {
    final fixtures = <Map<String, Object?>>[];
    void record(String name, Uint8List bytes, int length, String text) {
      expect(MCOtxtCodec.decode(bytes, bitLength: length).text, text);
      fixtures.add({'name':name,'dataBase64':base64.encode(bytes),'bitLength':length,'text':text});
    }
    for (final model in MCOtxtModelRegistry.latest.models) {
      final b = _Bits()..put(1,3)..put(0,3)..put(model.id.wireId,3)..put(7,3);
      final expected = <int>[];
      void literal(int index) {
        if (index < model.primarySymbols.length) { b.put(2,2); b.put(index,5); }
        else { b.put(14,4); b.put(index-model.primarySymbols.length,5); }
      }
      void rank(int rank) {
        const values = [0,2,6,7];
        const lengths = [2,3,4,4];
        b.put(values[rank],lengths[rank]);
      }
      for (var index=0; index<model.symbols.length; index++) {
        for(var r=0;r<4;r++) {
          literal(index); rank(r);
          expected.addAll([model.symbols[index],model.top4[index][r]]);
        }
      }
      for(var r=0;r<4;r++) {
        b.put(63,6);b.put(1,3);rank(r);
        expected.add(model.startTop4[r]);
        b.put(6,3);b.put(1,5);rank(r);
        expected.addAll([0x2e,model.punctStartTop4[r]]);
      }
      record('all predictions ${model.id.name}',b.bytes,b.bits.length,String.fromCharCodes(expected));
    }
    const strings = [
      'Hello World 123!\nWAIT for Help', 'Привет мир. ПОМОЩЬ 42',
      'Déjà reçu: Équipe à côté!', 'Straße, GRÜN und größer.',
      'Città: È già qui.', 'Привіт, Їжак і ґанок.', 'Прывітанне, Беларусь!',
      'English Русский Français Deutsch Italiano Українська Беларуская',
      '🙂 東京 Καλημέρα', '',
    ];
    for (var index=0;index<strings.length;index++) {
      final value=MCOtxtCodec.encode(strings[index]);
      record('encoded text $index',value.data,value.bitLength,value.decodedText);
    }
    final destination=Platform.environment['MCO_TEXT_FIXTURES']!;
    File(destination).writeAsStringSync(const JsonEncoder.withIndent('  ').convert({
      'reference':'9.5.1-mod-1.9.0','source':'independent field assembly and frozen generation 0 Dart model tables',
      'fixtures':fixtures,
    }));
  });
}
