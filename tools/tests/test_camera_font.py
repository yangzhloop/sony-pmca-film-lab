#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Legacy-font coverage, license integrity and missing-glyph regression checks."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_camera_font as font


class FontTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            cls.assets, cls.receipt = font.prepare_font(offline=True)
        except FileNotFoundError as error:
            raise unittest.SkipTest(str(error))

    def test_full_static_font_covers_language_and_custom_names(self):
        mapped = font.mapped_characters(self.assets[font.ASSET_PATH])
        self.assertEqual(len(mapped), 34491)
        self.assertEqual(sum(0x4e00 <= c <= 0x9fff for c in mapped), 20902)
        for char in "语言言語简体中文我的自定义胶片预设北京广州影像ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789":
            self.assertIn(ord(char), mapped, char)
        self.assertTrue(self.receipt["staticTrueType"])
        self.assertTrue(self.receipt["unmodifiedFullFont"])

    def test_bundled_license_is_pinned_apache_notice(self):
        import hashlib
        notice = self.assets["assets/legal/NOTICE-DroidSansFallback.txt"]
        self.assertEqual(hashlib.sha256(notice).hexdigest(), font.NOTICE_SHA256)
        self.assertIn(b"Apache License", notice)
        self.assertIn(b"Version 2.0, January 2004", notice)

    def test_all_current_ui_characters_are_present(self):
        self.assertEqual(self.receipt["missingCharacters"], [])
        self.assertGreater(self.receipt["requiredCharactersChecked"], 100)

    def test_new_unsupported_character_fails_build(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / "java"
            root.mkdir()
            (root / "Missing.java").write_text('class Missing { String x = "\\u0378"; }', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "U\\+0378"):
                font.prepare_font(source=root, offline=True)

    def test_dictionary_candidates_are_audited(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, assets = root / "java", root / "assets"
            source.mkdir(); assets.mkdir()
            (assets / "pinyin.tsv").write_text("bad\t\u0378\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "U\\+0378"):
                font.prepare_font(source=source, offline=True)

    def test_non_truetype_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "static TrueType"):
            font.mapped_characters(b"OTTO" + b"\0" * 128)


if __name__ == "__main__":
    unittest.main()
