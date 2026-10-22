#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Toolchain integrity and real API/binary-manifest regression checks.

No network is used. Tests needing optional pinned cache inputs skip when absent.
"""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import camera_toolchain as toolchain
import build_diagnostic as build
import camera_branding as branding


def replace_label(data, tag, value_type, value):
    """Mutate one real binary attribute for negative compatibility checks."""
    pool = build.StringPool(next(chunk for kind, chunk in build.chunks(data) if kind == 1))
    result, chunk_offset = bytearray(data), 8
    for kind, chunk in build.chunks(data):
        if kind == 0x102 and pool.strings[struct.unpack_from("<I", chunk, 20)[0]] == tag:
            for offset in build.attributes(chunk):
                if pool.strings[struct.unpack_from("<I", chunk, offset+4)[0]] == "label":
                    struct.pack_into("<I", result, chunk_offset+offset+8, value if value_type == 3 else build.NONE)
                    struct.pack_into("<BI", result, chunk_offset+offset+15, value_type, value)
                    return bytes(result)
        chunk_offset += len(chunk)
    raise AssertionError("Expected label attribute is absent")


class IntegrityTests(unittest.TestCase):
    def test_offline_download_never_accepts_corruption(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/"tool.jar"
            path.write_bytes(b"corrupt")
            with self.assertRaises(ValueError):
                toolchain.fetch(path, "https://example.invalid/tool.jar", "0"*64, offline=True)
            self.assertEqual(path.read_bytes(), b"corrupt")

    def test_uncached_offline_fails_without_network(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(FileNotFoundError):
                toolchain.fetch(Path(folder)/"missing.jar", "https://example.invalid/tool.jar", "0"*64, offline=True)

    def test_private_signer_must_stay_outside_checkout(self):
        with self.assertRaises(ValueError):
            build.ensure_key(build.ROOT/"not-a-key-cache")
        self.assertFalse((build.ROOT/"not-a-key-cache").exists())


class CachedToolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            cls.tools = toolchain.ensure_tools(offline=True)
        except (FileNotFoundError, ValueError) as error:
            raise unittest.SkipTest(str(error))

    def test_api10_compiler_and_dex035(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, work = root/"source", root/"work"
            source.mkdir(); work.mkdir()
            (source/"Example.java").write_text("public class Example extends android.app.Activity { public String value() { return android.os.Build.MODEL; } }")
            dex = build.compile_module(source, work, self.tools)
            self.assertEqual(dex.read_bytes()[:8], b"dex\n035\0")
            self.assertEqual(struct.unpack_from(">H", (work/"classes"/"Example.class").read_bytes(), 6)[0], 50)

    def test_new_android_api_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, work = root/"source", root/"work"
            source.mkdir(); work.mkdir()
            (source/"TooNew.java").write_text("public class TooNew { public String value() { return android.os.Build.getSerial(); } }")
            with self.assertRaises(subprocess.CalledProcessError) as context:
                build.compile_module(source, work, self.tools)
            self.assertIn("getSerial() is undefined", context.exception.stdout)

    def test_real_binary_manifest_and_resource_package(self):
        original = toolchain.CACHE/"inputs"/"FilmStudio-0.3.0-alpha-movie.apk"
        if not original.exists(): self.skipTest("Pinned release is not cached")
        self.assertEqual(toolchain.sha256(original), "14d8558687b62f379d60119f2b363443e6d57acdff9a1c19ecf9e64dfb7dcaf1")
        with tempfile.TemporaryDirectory() as folder, zipfile.ZipFile(original) as archive:
            work = Path(folder)
            manifest, details = build.patch_manifest(archive.read("AndroidManifest.xml"))
            self.assertEqual(details["originalActivity"], "com.yuki.imaging.app.pictureeffectplus.PictureEffectPlus")
            self.assertEqual(details["applicationLabel"], {"type": 1, "value": 0x7f090028})
            self.assertEqual(details["activityLabels"][build.ACTIVITY], {"type": 1, "value": 0x7f090029})
            before = archive.read("resources.arsc")
            resources = build.patch_resource_package(before, details["originalPackage"])
            self.assertEqual(len(before), len(resources))
            self.assertEqual(sum(a != b for a,b in zip(before, resources)), 4)
            resources, _ = branding.patch_names(resources)
            patched = work/"patched.apk"
            with zipfile.ZipFile(patched, "w") as output:
                output.writestr("AndroidManifest.xml", manifest)
                output.writestr("resources.arsc", resources)
            result = build.verify_manifest(patched, work, self.tools, toolchain.CACHE)
            self.assertTrue(result["originalApplicationRetained"])
            self.assertEqual(result["launchers"], [build.ACTIVITY])
            self.assertTrue(result["diagnosticActivityNotExported"])
            self.assertEqual(result["versionCode"], 5)
            self.assertEqual(result["versionName"], "0.2.3-alpha")
            self.assertEqual(result["labelEncoding"], "TYPE_REFERENCE")
            self.assertEqual(result["labelConfigurationsVerified"], {"0x7f090028": 33, "0x7f090029": 33})
            with self.assertRaises(ValueError): build.patch_manifest(manifest)

    def test_literal_and_wrong_resource_labels_are_rejected(self):
        original = toolchain.CACHE/"inputs"/"FilmStudio-0.3.0-alpha-movie.apk"
        if not original.exists(): self.skipTest("Pinned release is not cached")
        with zipfile.ZipFile(original) as archive:
            source = archive.read("AndroidManifest.xml")
            pool = build.StringPool(next(chunk for kind, chunk in build.chunks(source) if kind == 1))
            for tag in ("application", "activity"):
                for value_type, value in ((3, pool.strings.index("0.3a")), (1, 0x7f09002a)):
                    invalid = replace_label(source, tag, value_type, value)
                    with self.subTest(tag=tag, value_type=value_type):
                        with self.assertRaisesRegex(ValueError, "label resource references changed"):
                            build.patch_manifest(invalid)
            manifest, details = build.patch_manifest(source)
            resources = build.patch_resource_package(archive.read("resources.arsc"), details["originalPackage"])
            resources, _ = branding.patch_names(resources)
            pool = build.StringPool(next(chunk for kind, chunk in build.chunks(manifest) if kind == 1))
            for tag in ("application", "activity"):
                invalid = replace_label(manifest, tag, 3, pool.strings.index("Film Lab Diagnostics"))
                with self.assertRaisesRegex(ValueError, "labels must retain their native resource references"):
                    build.verify_label_resources(invalid, resources)

    def test_label_validation_rejects_one_unbranded_locale(self):
        original = toolchain.CACHE/"inputs"/"FilmStudio-0.3.0-alpha-movie.apk"
        if not original.exists(): self.skipTest("Pinned release is not cached")
        with zipfile.ZipFile(original) as archive:
            manifest, details = build.patch_manifest(archive.read("AndroidManifest.xml"))
            resources = build.patch_resource_package(archive.read("resources.arsc"), details["originalPackage"])
            resources, _ = branding.patch_names(resources)
            entries, pool = branding.resource_entries(resources)
            for label_id in (build.APPLICATION_LABEL_ID, build.LAUNCHER_LABEL_ID):
                target = next(entry for entry in reversed(entries) if entry["id"] == label_id)
                invalid = bytearray(resources)
                struct.pack_into("<I", invalid, target["value_offset"], pool.strings.index("胶片工坊"))
                with self.subTest(label_id=hex(label_id)):
                    with self.assertRaisesRegex(ValueError, "Film Lab in every configuration"):
                        build.verify_label_resources(manifest, bytes(invalid))


if __name__ == "__main__":
    unittest.main()
