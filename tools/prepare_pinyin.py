#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Prepare a broad, font-covered Pinyin character dictionary for wheel input."""
import argparse
import json
from pathlib import Path
import re
import unicodedata
import zipfile

from camera_toolchain import CACHE, fetch, sha256
from prepare_camera_font import mapped_characters, FONT_SHA256

ROOT = Path(__file__).resolve().parents[1]
WHEEL_URL = "https://files.pythonhosted.org/packages/b9/7b/4cabc76fcc21c3c7d5c671d8783984d30ac9d3bb387c4ba784fca3cdfa3a/pypinyin-0.55.0-py2.py3-none-any.whl"
WHEEL_SHA256 = "d53b1e8ad2cdb815fb2cb604ed3123372f5a28c6f447571244aca36fc62a286f"
# Prefer useful common naming characters, then deterministic code point order.
# This is a character picker, not a predictive sentence input method.
PREFERRED = "的一是不了在人有我他这中大来上国个到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见新色光影雨后街角城市夜黑白暖冷春夏秋冬胶片电影人像风景自然经典柔和负正反转海山林花金银晨夕雾雪蓝绿红黄紫阳月明暗高低调收藏旅行旧复古温柔周末森林咖啡记忆清新"


def syllable(value):
    decomposed = unicodedata.normalize("NFD", value.lower())
    decomposed = decomposed.replace("u\u0308", "v")
    return "".join(c for c in decomposed if unicodedata.category(c) != "Mn")


def prepare(cache, output, offline):
    wheel = fetch(cache / "inputs" / "pypinyin-0.55.0.whl", WHEEL_URL, WHEEL_SHA256, offline)
    font = cache / "fonts" / "DroidSansFallback.ttf"
    if not font.is_file() or sha256(font) != FONT_SHA256:
        raise ValueError("Prepare the verified camera font before the Pinyin dictionary")
    coverage = mapped_characters(font.read_bytes())
    readings = {}
    with zipfile.ZipFile(wheel) as archive:
        source = json.loads(archive.read("pypinyin/pinyin_dict.json"))
        license_text = archive.read("pypinyin-0.55.0.dist-info/licenses/LICENSE.txt")
    for number, pronunciations in source.items():
        point = int(number)
        if not (0x3400 <= point <= 0x9FFF and point in coverage):
            continue
        for pronunciation in pronunciations.split(","):
            key = syllable(pronunciation)
            if re.fullmatch("[a-z]{1,7}", key):
                readings.setdefault(key, set()).add(chr(point))
    rank = {c: n for n, c in reversed(list(enumerate(PREFERRED)))}
    lines = []
    for key, chars in sorted(readings.items()):
        ordered = sorted(chars, key=lambda c: (rank.get(c, len(PREFERRED)), ord(c)))
        lines.append(key + "\t" + "".join(ordered))
    payload = ("\n".join(lines) + "\n").encode("utf-8")
    output.mkdir(parents=True, exist_ok=True)
    (output / "pinyin.tsv").write_bytes(payload)
    legal = output / "legal"
    legal.mkdir(exist_ok=True)
    (legal / "LICENSE-pypinyin-MIT.txt").write_bytes(license_text)
    provenance = {"source": WHEEL_URL, "version": "0.55.0", "sha256": WHEEL_SHA256,
                  "method": "single-character readings; tone marks removed; u-diaeresis entered as v; filtered to bundled font cmap",
                  "fontSha256": FONT_SHA256, "syllables": len(readings),
                  "characters": len(set().union(*readings.values())), "bytes": len(payload)}
    (legal / "PINYIN-PROVENANCE.json").write_text(json.dumps(provenance, indent=2) + "\n")
    return provenance


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=CACHE)
    parser.add_argument("--output", type=Path, default=ROOT / "camera" / "assets")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    print(json.dumps(prepare(args.cache, args.output, args.offline), indent=2))
