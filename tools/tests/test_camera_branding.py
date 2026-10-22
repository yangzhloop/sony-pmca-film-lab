# SPDX-License-Identifier: MIT
"""Check real resource IDs/configurations and visible legacy-compatible PNGs."""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_diagnostic as build
import camera_branding as branding
import camera_toolchain as toolchain


def decode_rgba(png):
    if png[:8] != b"\x89PNG\r\n\x1a\n":
        raise AssertionError("Invalid PNG signature")
    offset, compressed, size = 8, bytearray(), None
    while offset < len(png):
        length = struct.unpack_from(">I", png, offset)[0]
        kind, payload = png[offset + 4:offset + 8], png[offset + 8:offset + 8 + length]
        crc = struct.unpack_from(">I", png, offset + 8 + length)[0]
        if crc != zlib.crc32(kind + payload) & 0xffffffff:
            raise AssertionError("Invalid PNG checksum")
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if (depth, color, compression, filtering, interlace) != (8, 6, 0, 0, 0):
                raise AssertionError("PNG must use unfiltered RGBA8 without interlace")
            size = width, height
        elif kind == b"IDAT":
            compressed += payload
        offset += 12 + length
    width, height = size
    raw = zlib.decompress(compressed)
    if len(raw) != height * (1 + 4 * width):
        raise AssertionError("PNG scanline length is wrong")
    points = []
    for y in range(height):
        line = raw[y * (1 + 4 * width):(y + 1) * (1 + 4 * width)]
        if line[0] != 0:
            raise AssertionError("Expected filter None")
        for x in range(width):
            r, g, b, alpha = line[1 + x * 4:5 + x * 4]
            if alpha:
                if max(r, g, b) < 128:
                    raise AssertionError("Visible pixels are too dark for the camera's black background")
                points.append((x, y))
    return size, points


class IconTest(unittest.TestCase):
    def test_original_cartridge_exports_are_visible_and_bounded(self):
        for size in ((58, 46), (70, 55), (72, 96), (54, 72)):
            png = branding.film_icon(*size)
            decoded, points = decode_rgba(png)
            self.assertEqual(decoded, size)
            self.assertEqual(png, branding.film_icon(*size))
            self.assertGreater(len(points), 100)
            xs, ys = zip(*points)
            self.assertGreater(max(xs) - min(xs), size[0] * .45)
            self.assertGreater(max(ys) - min(ys), size[1] * .45)
            self.assertGreater(min(xs), 0)
            self.assertGreater(min(ys), 0)
            self.assertLess(max(xs), size[0] - 1)
            self.assertLess(max(ys), size[1] - 1)


@unittest.skipUnless((toolchain.CACHE / "inputs" / "FilmStudio-0.3.0-alpha-movie.apk").exists(),
                     "prepare the pinned upstream input first")
class ResourceBrandingTest(unittest.TestCase):
    def setUp(self):
        self.archive = zipfile.ZipFile(toolchain.CACHE / "inputs" / "FilmStudio-0.3.0-alpha-movie.apk")
        self.addCleanup(self.archive.close)
        self.resources = self.archive.read("resources.arsc")

    def test_resource_ids_configs_and_unrelated_values_are_preserved(self):
        patched, receipt = branding.patch_names(self.resources)
        before, before_pool = branding.resource_entries(self.resources)
        after, after_pool = branding.resource_entries(patched)
        self.assertEqual(before_pool.strings, after_pool.strings[:len(before_pool.strings)])
        identity = lambda e: (e["id"], e["config"], e["name"], e["type"])
        self.assertEqual(list(map(identity, before)), list(map(identity, after)))
        targets = {0x7f090028, 0x7f090029}
        changed = 0
        for original, current in zip(before, after):
            if original["id"] in targets:
                self.assertEqual(original["text"], "胶片工坊")
                self.assertEqual(current["text"], "Film Lab")
                changed += 1
            else:
                self.assertEqual(original["raw"], current["raw"])
                self.assertEqual(original["text"], current["text"])
        self.assertEqual(changed, 66)
        self.assertTrue(receipt["resourceIdsPreserved"])
        with self.assertRaisesRegex(ValueError, "Pinned boot/app name"):
            branding.patch_names(patched)

    def test_all_launcher_app_and_evf_variants_replace_placeholders(self):
        changed, receipt = branding.replace_icons(self.archive, self.resources)
        self.assertEqual(len(changed), 7)
        self.assertEqual({entry["resourceId"] for entry in receipt["icons"]},
                         {"0x7f02001d", "0x7f02004d", "0x7f02005c", "0x7f02005d"})
        self.assertEqual({entry["path"].split("/")[1] for entry in receipt["icons"]},
                         {"drawable-long-nodpi", "drawable-notlong-nodpi", "drawable-nodpi"})
        for path, png in changed.items():
            size, points = decode_rgba(png)
            self.assertGreater(min(size), 1)
            self.assertGreater(len(points), 100)
            self.assertNotEqual(png, self.archive.read(path))

    def test_independent_decoder_sees_film_lab_in_every_language(self):
        try:
            tools = toolchain.ensure_tools(offline=True)
        except (FileNotFoundError, ValueError) as error:
            self.skipTest(str(error))
        resources, _ = branding.patch_names(self.resources)
        with tempfile.TemporaryDirectory(prefix="film-lab-branding-test-") as folder:
            root = Path(folder)
            apk, decoded = root / "branded.apk", root / "decoded"
            with zipfile.ZipFile(apk, "w") as output:
                for name in self.archive.namelist():
                    if name.upper().startswith("META-INF/"):
                        continue
                    output.writestr(name, resources if name == "resources.arsc" else self.archive.read(name))
            process = subprocess.run(["java", "-jar", str(tools["apktool"]), "d", "-s", "-p",
                str(toolchain.CACHE / "framework"), str(apk), "-o", str(decoded)],
                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            self.assertEqual(process.returncode, 0, process.stdout)
            count = 0
            for path in (decoded / "res").glob("values*/strings.xml"):
                for element in ET.parse(path).getroot():
                    if element.get("name") in branding.NAME_IDS:
                        self.assertEqual(element.text, "Film Lab", str(path))
                        count += 1
            self.assertEqual(count, 66)
            public = {element.get("name"): element.get("id")
                      for element in ET.parse(decoded / "res" / "values" / "public.xml").getroot()}
            for name, resource_id in {**branding.NAME_IDS, **branding.ICON_IDS}.items():
                self.assertEqual(public[name], "0x%08x" % resource_id)


if __name__ == "__main__":
    unittest.main()
