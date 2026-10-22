#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Pin the full, static Apache-2.0 AOSP CJK font and audit UI glyph coverage.

The unmodified font is fetched into the external cache and embedded in the APK.
No local/system font, font subset, variable-font conversion, or fontTools install
is needed. The cmap parser checks actual nonzero glyph mappings.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import struct

from camera_toolchain import CACHE, fetch, sha256

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "4873a480f6df3f84bb3ca5c8647e9498aaf597a5"
BASE_URL = "https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/" + COMMIT + "/data/fonts/"
FONT_SHA256 = "8fe329bfcab968760be992f1550428821c5b3ac4b246bd60dd8c1f9b7e26e2a0"
NOTICE_SHA256 = "38751245389e1e23f73e6f5384b5cbe7fa972cc4410c5adc9c04b082a0b9561a"
ASSET_PATH = "assets/fonts/FilmLabCJK.ttf"


def mapped_characters(data: bytes) -> set[int]:
    if data[:4] != b"\0\1\0\0":
        raise ValueError("Expected a static TrueType font")
    tables = {}
    for index in range(struct.unpack_from(">H", data, 4)[0]):
        tag, _, start, length = struct.unpack_from(">4sIII", data, 12 + index * 16)
        if start + length > len(data):
            raise ValueError("Truncated font table")
        tables[tag] = data[start:start + length]
    if b"fvar" in tables or b"glyf" not in tables or b"cmap" not in tables:
        raise ValueError("Expected static TrueType outlines and a cmap")
    cmap, result = tables[b"cmap"], set()
    for index in range(struct.unpack_from(">H", cmap, 2)[0]):
        platform, encoding, start = struct.unpack_from(">HHI", cmap, 4 + index * 8)
        if not (platform == 0 or platform == 3 and encoding in (1, 10)):
            continue
        fmt = struct.unpack_from(">H", cmap, start)[0]
        if fmt == 4:
            length, _, twice = struct.unpack_from(">HHH", cmap, start + 2)
            table = cmap[start:start + length]
            count = twice // 2
            for segment in range(count):
                end = struct.unpack_from(">H", table, 14 + segment * 2)[0]
                begin = struct.unpack_from(">H", table, 16 + count * 2 + segment * 2)[0]
                delta = struct.unpack_from(">h", table, 16 + count * 4 + segment * 2)[0]
                offset_at = 16 + count * 6 + segment * 2
                offset = struct.unpack_from(">H", table, offset_at)[0]
                for code in range(begin, min(end, 0xfffe) + 1):
                    if offset:
                        glyph = struct.unpack_from(">H", table, offset_at + offset + 2 * (code - begin))[0]
                        if glyph:
                            glyph = (glyph + delta) & 0xffff
                    else:
                        glyph = (code + delta) & 0xffff
                    if glyph:
                        result.add(code)
        elif fmt == 12:
            count = struct.unpack_from(">I", cmap, start + 12)[0]
            for group in range(count):
                begin, end, glyph = struct.unpack_from(">III", cmap, start + 16 + group * 12)
                if end > 0x10ffff or begin > end:
                    raise ValueError("Invalid Unicode cmap range")
                result.update(range(begin + (glyph == 0), end + 1))
    if len(result) < 30000 or sum(0x4e00 <= code <= 0x9fff for code in result) < 20000:
        raise ValueError("Font must retain broad CJK coverage for custom recipe names")
    return result


def required_characters(source: Path) -> set[int]:
    # Include every literal non-ASCII source character, even in comments, and
    # Java escaped Unicode, to fail closed if a new localized string lacks glyphs.
    required = set(range(32, 127))
    for file in source.rglob("*.java"):
        text = file.read_text(encoding="utf-8")
        required.update(ord(c) for c in text if ord(c) >= 128)
        required.update(int(match, 16) for match in re.findall(r"\\u([0-9a-fA-F]{4})", text)
                        if int(match, 16) >= 32)
    # Custom Chinese names are entered through the bundled dictionary. Audit its
    # complete candidate set too, so dictionary updates cannot introduce tofu.
    for file in (source.parent / "assets").rglob("*.tsv"):
        required.update(ord(c) for c in file.read_text(encoding="utf-8") if ord(c) >= 32)
    return required


def prepare_font(cache: Path = CACHE, source: Path = ROOT / "camera" / "java", offline: bool = False):
    folder = cache / "fonts"
    font = fetch(folder / "DroidSansFallback.ttf", BASE_URL + "DroidSansFallback.ttf", FONT_SHA256, offline)
    notice = fetch(folder / "NOTICE", BASE_URL + "NOTICE", NOTICE_SHA256, offline)
    mapped, required = mapped_characters(font.read_bytes()), required_characters(source)
    missing = sorted(required - mapped)
    if missing:
        raise ValueError("Bundled font lacks UI glyphs: " + ", ".join("U+%04X" % c for c in missing))
    receipt = {"family": "Droid Sans Fallback", "license": "Apache-2.0", "sourceCommit": COMMIT,
               "sourceUrl": BASE_URL + "DroidSansFallback.ttf", "sha256": sha256(font),
               "noticeSha256": sha256(notice), "asset": ASSET_PATH, "bytes": font.stat().st_size,
               "staticTrueType": True, "unmodifiedFullFont": True, "mappedCharacters": len(mapped),
               "cjkUnifiedIdeographs": sum(0x4e00 <= c <= 0x9fff for c in mapped),
               "requiredCharactersChecked": len(required), "missingCharacters": []}
    assets = {ASSET_PATH: font.read_bytes(), "assets/legal/NOTICE-DroidSansFallback.txt": notice.read_bytes(),
              "assets/legal/FONT-PROVENANCE.json": (json.dumps(receipt, indent=2) + "\n").encode("utf-8")}
    return assets, receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=CACHE)
    parser.add_argument("--source", type=Path, default=ROOT / "camera" / "java")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    _, receipt = prepare_font(args.cache, args.source, args.offline)
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
