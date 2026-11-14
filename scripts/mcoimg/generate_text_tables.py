#!/usr/bin/env python3
"""Generate Swift MCOtxt tables from meshcore-open tag 9.5.1-mod-1.9.0.

The source commit is fe375741226588440e268002b4975349b2e95ffe.
Usage: generate_text_tables.py UPSTREAM
"""
import pathlib
import re
import sys

upstream = pathlib.Path(sys.argv[1])
output = pathlib.Path(__file__).resolve().parents[2] / "MC1Services/Sources/MC1Services/Utilities/MCOimg"

for language in ["en", "ru", "fr", "de", "it", "uk", "be"]:
    source = (upstream / f"lib/MCOtxt/models/generated/v1/model_{language}.dart").read_text()
    prefix = "mcotxt" + language.capitalize()

    def array(suffix):
        pattern = r"const List<int> " + prefix + suffix + r" = <int>\[(.*?)\];"
        match = re.search(pattern, source, re.S)
        body = re.sub(r"//[^\n]*", "", match.group(1))
        return [int(value, 0) for value in re.findall(r"0x[0-9A-Fa-f]+|\d+", body)]

    def lines(values):
        return ",\n      ".join(
            ", ".join(str(value) for value in values[index:index + 16])
            for index in range(0, len(values), 16)
        )

    match = re.search(r"const Map<int, int> " + prefix + r"UppercaseToLowercase = <int, int>\{(.*?)\};", source, re.S)
    pairs = re.findall(r"(0x[\dA-Fa-f]+): (0x[\dA-Fa-f]+)", match.group(1))
    uppercase = {int(lower, 0): int(upper, 0) for upper, lower in pairs}
    entries = [f"{lower}: {upper}" for lower, upper in uppercase.items()]
    upper_lines = ",\n      ".join(
        ", ".join(entries[index:index + 4]) for index in range(0, len(entries), 4)
    )
    primary = array("PrimarySymbols")
    extensions = array("ExtensionSymbols")
    predictions = array("Top4Indexes")
    assert len(predictions) == (len(primary) + len(extensions)) * 4
    swift = f"""// Frozen MCOtxt generation 0 wire tables.
extension MCOTextModels {{
  static let {language} = MCOTextModel(
    primary: [
      {lines(primary)}
    ],
    extensionSymbols: [
      {lines(extensions)}
    ],
    start: [{lines(array('StartTop4Indexes'))}],
    punctuationStart: [{lines(array('PunctStartTop4Indexes'))}],
    top4: [
      {lines(predictions)}
    ],
    uppercase: [
      {upper_lines}
    ]
  )
}}
"""
    (output / f"MCOTextModels+{language.upper()}.swift").write_text(swift)
