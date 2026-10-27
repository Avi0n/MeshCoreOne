"""Reproduce the two Unicode 15.0 properties used by the reaction-local GB11 check.

Inputs are the official UCD files, not outputs of either reaction parser. Unicode
15.0 is the existing JDK 21 segmentation baseline, not a new text/emoji policy.
Download the exact files separately; this generator has no network access.
"""

import argparse
import hashlib
from pathlib import Path


INPUTS = (
    (
        "https://www.unicode.org/Public/15.0.0/ucd/auxiliary/GraphemeBreakProperty.txt",
        "5a0f8748575432f8ff95e1dd5bfaa27bda1a844809e17d6939ee912bba6568a1",
        "Extend",
    ),
    (
        "https://www.unicode.org/Public/15.0.0/ucd/emoji/emoji-data.txt",
        "29071dba22c72c27783a73016afb8ffaeb025866740791f9c2d0b55cc45a3470",
        "Extended_Pictographic",
    ),
)
OUTPUT = Path(
    "android/core/services/src/main/kotlin/com/meshcoreone/android/core/"
    "services/reactions/ReactionGraphemeProperties.kt"
)
PACKAGED_NOTICE = Path("android/app/src/main/assets/licenses/Unicode-License.txt")
NOTICE_SHA256 = "e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96"


def read_ranges(path, digest, property_name):
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError(f"Unexpected Unicode input SHA-256: {path}")
    ranges = []
    for line in raw.decode("utf-8").splitlines():
        declaration = line.split("#", 1)[0].strip()
        if not declaration:
            continue
        bounds, name = (part.strip() for part in declaration.split(";"))
        if name != property_name:
            continue
        endpoints = [int(value, 16) for value in bounds.split("..")]
        ranges.append((endpoints[0], endpoints[-1]))
    merged = []
    for first, last in sorted(ranges):
        if merged and first <= merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], max(last, merged[-1][1]))
        else:
            merged.append((first, last))
    if not merged:
        raise ValueError(f"No {property_name} ranges in {path}")
    return merged


def generate(paths):
    tables = [
        read_ranges(path, digest, name)
        for path, (_, digest, name) in zip(paths, INPUTS, strict=True)
    ]
    lines = [
        "// AndroidOnly: WP-216 Generated Unicode properties for reaction-local UAX #29 GB11 validation.",
        "// Generator: docs/android/evidence/WP-216/generate-grapheme-properties.py",
        "// Unicode 15.0.0 (the existing JDK 21 grapheme baseline); not a Swift execution oracle.",
        "// \u00a9 2022 Unicode\u00ae, Inc.",
        "// Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.",
        "// For terms of use, see https://www.unicode.org/terms_of_use.html",
        "// Packaged notice: android/app/src/main/assets/licenses/Unicode-License.txt",
    ]
    for url, digest, _ in INPUTS:
        lines += [f"// Input: {url}", f"// SHA-256: {digest}"]
    lines += [
        "package com.meshcoreone.android.core.services.reactions",
        "",
        "internal object ReactionGraphemeProperties {",
        "    fun isExtend(codePoint: Int): Boolean = contains(extend, codePoint)",
        "    fun isExtendedPictographic(codePoint: Int): Boolean = contains(extendedPictographic, codePoint)",
        "",
        "    private fun contains(ranges: IntArray, codePoint: Int): Boolean {",
        "        var low = 0",
        "        var high = ranges.size / 2 - 1",
        "        while (low <= high) {",
        "            val middle = (low + high) ushr 1",
        "            when {",
        "                codePoint < ranges[middle * 2] -> high = middle - 1",
        "                codePoint > ranges[middle * 2 + 1] -> low = middle + 1",
        "                else -> return true",
        "            }",
        "        }",
        "        return false",
        "    }",
    ]
    for name, ranges in zip(("extend", "extendedPictographic"), tables, strict=True):
        lines += ["", f"    private val {name} = intArrayOf("]
        pairs = [f"0x{first:X}, 0x{last:X}," for first, last in ranges]
        lines += [
            "        " + " ".join(pairs[index:index + 4])
            for index in range(0, len(pairs), 4)
        ]
        lines += ["    )"]
    lines += ["}", ""]
    return "\n".join(lines), tables


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("grapheme_break_property", type=Path)
    parser.add_argument("emoji_data", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    text, tables = generate((args.grapheme_break_property, args.emoji_data))
    root = Path(__file__).resolve().parents[4]
    if hashlib.sha256((root / PACKAGED_NOTICE).read_bytes()).hexdigest() != NOTICE_SHA256:
        raise ValueError(f"Changed or missing packaged Unicode notice: {PACKAGED_NOTICE}")
    output = root / OUTPUT
    if args.check:
        if output.read_bytes() != text.encode("utf-8"):
            raise ValueError(f"Generated Unicode table is stale: {output}")
    else:
        output.write_text(text, encoding="utf-8", newline="\n")
    for (_, _, name), ranges in zip(INPUTS, tables, strict=True):
        print(f"{name}: {len(ranges)} ranges, {sum(b - a + 1 for a, b in ranges)} scalars")
    print(f"{'Verified' if args.check else 'Generated'} {OUTPUT.as_posix()}")


if __name__ == "__main__":
    main()
