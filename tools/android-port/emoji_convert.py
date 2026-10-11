"""WP-407: byte-preserving admission/check of the source-selected iOS emoji resource and notices."""

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "android/app/src/main/assets"
INPUTS = {
    "emojibase.json": ("emoji/emojibase.json", "b69315f939d4a2c4332e09b70c8278e0a9e0e7465d98c71f95e50b7ad76a4cfb"),
    "Emojibase-Bindings-Apache-2.0.txt": ("licenses/Emojibase-Bindings-Apache-2.0.txt", "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
    "Emojibase-Data-MIT.txt": ("licenses/Emojibase-Data-MIT.txt", "5c4e1582dcae9429a5d00ec0a64185c3a711f9757b2308fdf275d90b120f6149"),
    "Unicode-License.txt": ("licenses/Unicode-License.txt", "e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96"),
}
COUNTS = {"people": 555, "nature": 159, "foods": 131, "activity": 85,
          "places": 218, "objects": 264, "symbols": 250, "flags": 270}


def verified_inputs(directory: Path, installed=False):
    result = {}
    for name, (relative, expected) in INPUTS.items():
        data = (directory / (relative if installed else name)).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"Changed pinned emoji input: {name}")
        result[relative] = data
    catalog = json.loads(result["emoji/emojibase.json"])
    if {key: len(value) for key, value in catalog.items()} != COUNTS:
        raise ValueError("Incomplete source emoji categories")
    for entries in catalog.values():
        for entry in entries:
            for key in ("hexcode", "unicode", "label"):
                if not isinstance(entry[key], str) or not entry[key]:
                    raise ValueError(f"Invalid emoji {key}")
            for key in ("shortcodes", "tags"):
                values = entry.get(key) or []
                if not isinstance(values, list) or not all(isinstance(value, str) for value in values):
                    raise ValueError(f"Invalid emoji {key}")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if (args.source_dir is None) == (not args.check):
        parser.error("Choose exactly one of --source-dir or --check")
    inputs = verified_inputs(ASSETS if args.check else args.source_dir, installed=args.check)
    if not args.check:
        for relative, data in inputs.items():
            path = ASSETS / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    print(json.dumps({"result": "passed", "source_binding": "emojibase-bindings@1.5.0",
                      "records": sum(COUNTS.values()), "categories": COUNTS, "notices": 3}))


if __name__ == "__main__":
    main()
