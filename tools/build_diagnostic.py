#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Build the integrated API-10 Film Lab UI on a verified Film Studio APK.

No aapt resource rebuild is performed. Original class namespaces, native libs,
resource IDs and unrelated assets are retained; app branding is replaced and the
full licensed CJK font is added.
The original native Activity owns shooting and hosts Film Lab's menu overlay.
The existing local v1 signing identity is reused for installed-app upgrades.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

from camera_toolchain import CACHE, ensure_tools, sha256
from prepare_camera_font import prepare_font

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.flab.imaging.app.pictureeffectplus"
ACTIVITY = "com.yuki.imaging.app.pictureeffectplus.PictureEffectPlus"
DIAGNOSTIC_ACTIVITY = "com.filmlab.diagnostics.DiagnosticActivity"
NONE = 0xffffffff
ANDROID_NS = "http://schemas.android.com/apk/res/android"
VERSION = "0.2.3-alpha"
VERSION_CODE = 5
APPLICATION_LABEL_ID = 0x7f090028
LAUNCHER_LABEL_ID = 0x7f090029


def run(command, *, env=None) -> str:
    print("+", " ".join(map(str, command)), flush=True)
    process = subprocess.run(list(map(str, command)), check=True, text=True,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
    print(process.stdout, end="", flush=True)
    return process.stdout


def chunks(data: bytes, offset=8):
    while offset < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, offset)
        if size < header or size < 8 or offset + size > len(data):
            raise ValueError("Malformed Android binary chunk")
        yield kind, data[offset:offset+size]
        offset += size
    if offset != len(data):
        raise ValueError("Truncated Android binary chunk")


class StringPool:
    def __init__(self, data):
        _, self.header, _ = struct.unpack_from("<HHI", data)
        count, styles, self.flags, start, style_start = struct.unpack_from("<IIIII", data, 8)
        self.extra = data[28:self.header]
        self.style_offsets = data[self.header+4*count:self.header+4*(count+styles)]
        self.style_data = data[style_start:] if style_start else b""
        self.strings = []
        for i in range(count):
            offset = start + struct.unpack_from("<I", data, self.header+4*i)[0]
            if self.flags & 0x100:
                _, offset = self.length(data, offset, 1)
                size, offset = self.length(data, offset, 1)
                self.strings.append(data[offset:offset+size].decode("utf-8"))
            else:
                size, offset = self.length(data, offset, 2)
                self.strings.append(data[offset:offset+2*size].decode("utf-16le"))

    @staticmethod
    def length(data, offset, unit):
        fmt, mask = ("<B", 0x80) if unit == 1 else ("<H", 0x8000)
        first = struct.unpack_from(fmt, data, offset)[0]
        offset += unit
        if first & mask:
            second = struct.unpack_from(fmt, data, offset)[0]
            return ((first & (mask-1)) << (unit*8)) | second, offset+unit
        return first, offset

    @staticmethod
    def encode_length(size, unit):
        limit = 0x80 if unit == 1 else 0x8000
        fmt = "<B" if unit == 1 else "<H"
        if size < limit:
            return struct.pack(fmt, size)
        return struct.pack(fmt, (size >> (8*unit)) | limit) + struct.pack(fmt, size & (2*limit-1))

    def index(self, text):
        if text not in self.strings:
            self.strings.append(text)
        return self.strings.index(text)

    def encode(self):
        offsets, data = [], bytearray()
        for text in self.strings:
            offsets.append(len(data))
            utf16_length = len(text.encode("utf-16le")) // 2
            if self.flags & 0x100:
                encoded = text.encode("utf-8")
                data += self.encode_length(utf16_length, 1) + self.encode_length(len(encoded), 1) + encoded + b"\0"
            else:
                data += self.encode_length(utf16_length, 2) + text.encode("utf-16le") + b"\0\0"
        data += b"\0" * (-len(data) % 4)
        start = self.header + 4*len(offsets) + len(self.style_offsets)
        style_start = start+len(data) if self.style_data else 0
        size = start+len(data)+len(self.style_data)
        # New appended values are not lexically sorted.
        header = struct.pack("<HHIIIIII", 1, self.header, size, len(offsets),
                             len(self.style_offsets)//4, self.flags & ~1, start, style_start)
        return header+self.extra+struct.pack("<"+"I"*len(offsets), *offsets)+self.style_offsets+data+self.style_data


def attributes(chunk):
    start, size, count = struct.unpack_from("<HHH", chunk, 24)
    if size != 20:
        raise ValueError("Unsupported binary XML attribute size")
    return [16+start+i*size for i in range(count)]


def manifest_details(data):
    parts = list(chunks(data))
    pool = StringPool(next(chunk for kind, chunk in parts if kind == 1))
    result = {"package": None, "activities": [], "launchers": [], "versionCode": None, "versionName": None,
              "applicationLabel": None, "activityLabels": {}}
    stack, current = [], None
    for kind, chunk in parts:
        if kind == 0x102:
            tag = pool.strings[struct.unpack_from("<I", chunk, 20)[0]]
            attrs, typed_attrs = {}, {}
            for offset in attributes(chunk):
                _, name, raw, _, _, value_type, value = struct.unpack_from("<IIIHBBI", chunk, offset)
                attrs[pool.strings[name]] = pool.strings[value] if value_type == 3 else value
                typed_attrs[pool.strings[name]] = {"type": value_type, "value": attrs[pool.strings[name]]}
            stack.append(tag)
            if tag == "manifest":
                result["package"] = attrs.get("package")
                result["versionCode"] = attrs.get("versionCode")
                result["versionName"] = attrs.get("versionName")
            if tag == "activity":
                current = attrs.get("name")
                result["activities"].append(current)
                result["activityLabels"][current] = typed_attrs.get("label")
            if tag == "application":
                result["applicationLabel"] = typed_attrs.get("label")
            if tag == "category" and attrs.get("name") == "android.intent.category.LAUNCHER":
                result["launchers"].append(current)
        elif kind == 0x103:
            if stack.pop() == "activity": current = None
    return result


def patch_manifest(data, package=PACKAGE, activity=DIAGNOSTIC_ACTIVITY):
    parts = list(chunks(data))
    pool = StringPool(next(chunk for kind, chunk in parts if kind == 1))
    resources = list(struct.unpack("<"+"I"*((len(c)-8)//4), c[8:])) if (c := next((c for k,c in parts if k == 0x180), None)) else []
    old = manifest_details(data)
    if old["package"] not in ("com.yuki.imaging.app.pictureeffectplus", "com.sony.imaging.app.pictureeffectplus"):
        raise ValueError("Unrecognized input package")
    if len(old["launchers"]) != 1:
        raise ValueError("Expected exactly one original launcher")
    if (old["applicationLabel"] != {"type": 1, "value": APPLICATION_LABEL_ID}
            or old["activityLabels"].get(old["launchers"][0]) != {"type": 1, "value": LAUNCHER_LABEL_ID}):
        raise ValueError("Pinned application/launcher label resource references changed")
    if activity in old["activities"]:
        raise ValueError("Diagnostic activity already exists")
    ns = pool.index(ANDROID_NS)

    def attr(name, value, value_type=3, android=True):
        name_index = pool.index(name)
        if android:
            ids = {"name": 0x01010003, "label": 0x01010001, "exported": 0x01010010,
                   "screenOrientation": 0x0101001e, "versionCode": 0x0101021b, "versionName": 0x0101021c}
            while len(resources) <= name_index: resources.append(0)
            resources[name_index] = ids[name]
        index = pool.index(value) if value_type == 3 else value
        return struct.pack("<IIIHBBI", ns if android else NONE, name_index,
                           index if value_type == 3 else NONE, 8, 0, value_type, index)

    def start(name, attrs=b""):
        return struct.pack("<HHIIIIIHHHHHH", 0x102, 16, 36+len(attrs), 0, NONE,
                           NONE, pool.index(name), 20, 20, len(attrs)//20, 0, 0, 0)+attrs

    def end(name):
        return struct.pack("<HHIIIII", 0x103, 16, 24, 0, NONE, NONE, pool.index(name))

    addition = (start("activity", attr("label", "Film Lab Diagnostics")+attr("name", activity)
                      +attr("exported", 0, 0x12)+attr("screenOrientation", 0, 0x10)) + end("activity"))
    output, inserted, version_fields = [], 0, 0
    for kind, chunk in parts:
        if kind in (1, 0x180):
            output.append((kind, b"")); continue
        if kind == 0x102:
            tag = pool.strings[struct.unpack_from("<I", chunk, 20)[0]]
            if tag in ("manifest", "application", "activity"):
                mutable = bytearray(chunk)
                for offset in attributes(chunk):
                    name = pool.strings[struct.unpack_from("<I", chunk, offset+4)[0]]
                    if tag == "manifest" and name == "package":
                        mutable[offset:offset+20] = attr("package", package, android=False)
                    if tag == "manifest" and name == "versionCode":
                        mutable[offset:offset+20] = attr("versionCode", VERSION_CODE, 0x10)
                        version_fields += 1
                    if tag == "manifest" and name == "versionName":
                        mutable[offset:offset+20] = attr("versionName", VERSION)
                        version_fields += 1
                    # Preserve Sony's TYPE_REFERENCE labels. Literal strings
                    # leave Android labelRes zero, which Sony's selector may
                    # render as the Activity class name instead of its title.
                    # camera_branding patches the referenced values in every
                    # locale without changing these native resource IDs.
                chunk = bytes(mutable)
            # Relative component names resolve against the manifest package.
            # Expand them before changing the package, preserving the actual
            # original classes (including Application) rather than renaming DEX.
            if tag in ("application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation"):
                mutable = bytearray(chunk)
                for offset in attributes(chunk):
                    name_index, raw = struct.unpack_from("<II", chunk, offset+4)
                    value_type, value = struct.unpack_from("<BI", chunk, offset+15)
                    if pool.strings[name_index] in ("name", "targetActivity", "parentActivityName") and value_type == 3:
                        text = pool.strings[value]
                        if text.startswith(".") or "." not in text:
                            expanded = old["package"]+("" if text.startswith(".") else ".")+text
                            index = pool.index(expanded)
                            struct.pack_into("<I", mutable, offset+8, index)
                            struct.pack_into("<I", mutable, offset+16, index)
                chunk = bytes(mutable)
        elif kind == 0x103:
            if pool.strings[struct.unpack_from("<I", chunk, 20)[0]] == "application":
                output.append((0, addition)); inserted += 1
        output.append((kind, chunk))
    if (inserted, version_fields) != (1, 2):
        raise ValueError("Manifest application/version shape changed")
    encoded = []
    for kind, chunk in output:
        if kind == 1: chunk = pool.encode()
        if kind == 0x180: chunk = struct.pack("<HHI", 0x180, 8, 8+4*len(resources))+struct.pack("<"+"I"*len(resources), *resources)
        encoded.append(chunk)
    result = struct.pack("<HHI", 3, 8, 8+sum(map(len, encoded)))+b"".join(encoded)
    details = manifest_details(result)
    original_activity = old["launchers"][0]
    if original_activity.startswith(".") or "." not in original_activity:
        original_activity = old["package"]+("" if original_activity.startswith(".") else ".")+original_activity
    if (details["package"] != package or details["launchers"] != [original_activity]
            or details["versionCode"] != VERSION_CODE or details["versionName"] != VERSION
            or details["applicationLabel"] != {"type": 1, "value": APPLICATION_LABEL_ID}
            or details["activityLabels"].get(original_activity) != {"type": 1, "value": LAUNCHER_LABEL_ID}):
        raise AssertionError("Patched manifest validation failed")
    return result, {"originalPackage": old["package"], "originalActivity": original_activity, **details}


def patch_resource_package(data, old, new=PACKAGE):
    result, found = bytearray(data), 0
    for kind, chunk in chunks(data, struct.unpack_from("<H", data, 2)[0]):
        if kind != 0x200: continue
        current = chunk[12:268].decode("utf-16le").split("\0", 1)[0]
        if current == old:
            offset = data.index(chunk)
            encoded = new.encode("utf-16le")
            if len(encoded) > 254: raise ValueError("Resource package name too long")
            result[offset+12:offset+268] = encoded+b"\0"*(256-len(encoded)); found += 1
    if found != 1: raise ValueError("Expected one matching resource package header")
    return bytes(result)


def ensure_key(cache):
    private = (cache / "private").resolve()
    if private.is_relative_to(ROOT.resolve()):
        raise ValueError("Signing identity must stay outside the Git checkout")
    private.mkdir(parents=True, exist_ok=True, mode=0o700)
    private.chmod(0o700)
    key, password = private/"diagnostics.jks", private/"diagnostics-password.txt"
    if key.exists() and not password.exists():
        raise ValueError("Signing password is missing; refusing to replace identity")
    if not password.exists():
        descriptor = os.open(password, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w") as target: target.write(secrets.token_urlsafe(32))
    password.chmod(0o600)
    env = dict(os.environ, FILM_LAB_KEY_PASSWORD=password.read_text().strip())
    if not key.exists():
        run(["keytool", "-genkeypair", "-keystore", key, "-alias", "filmlab", "-keyalg", "RSA",
             "-keysize", "2048", "-validity", "10000", "-storetype", "JKS",
             "-storepass:env", "FILM_LAB_KEY_PASSWORD", "-keypass:env", "FILM_LAB_KEY_PASSWORD",
             "-dname", "CN=Film Lab local diagnostics,O=Local development,C=ZZ"], env=env)
    key.chmod(0o600)
    return key, env


def compile_module(source, work, tools, generated=None):
    files = sorted(source.rglob("*.java"))
    if not files: raise ValueError(f"No Java sources in {source}")
    if generated: files.append(generated)
    classes = work/"classes"; classes.mkdir()
    run(["java", "-jar", tools["ecj"], "-1.6", "-proc:none", "-encoding", "UTF-8",
         "-bootclasspath", tools["android"], "-d", classes, *files])
    for compiled in classes.rglob("*.class"):
        if struct.unpack_from(">H", compiled.read_bytes(), 6)[0] != 50:
            raise ValueError(f"Expected Java 6 bytecode: {compiled}")
    dex = work/"module.dex"
    run(["java", "-cp", tools["dx"], "com.android.dx.command.Main", "--dex", f"--output={dex}", classes])
    if dex.read_bytes()[:8] != b"dex\n035\0": raise ValueError("Module DEX must be version 035")
    return dex


def prepared_input(args):
    from prepare_film_studio import COMMIT, PINS
    receipt_path = args.receipt or args.cache/"upstream"/"film-studio-photo-receipt.json"
    receipt = json.loads(receipt_path.read_text())
    if receipt.get("source_commit") != COMMIT:
        raise ValueError("Prepared input source revision is not pinned Film Studio")
    for key, (_, _, expected) in PINS.items():
        value = receipt.get("inputs", {}).get(key, {})
        if value.get("sha256") != expected or sha256(Path(value["path"])) != expected:
            raise ValueError(f"Prepared input {key} pin/file mismatch")
    if (receipt.get("movie_enabled") is not False or receipt.get("supporting_rec_mode") != 1
            or receipt.get("compiled_arrays_equal_to_published") != 120
            or receipt.get("verified_icons") != 15):
        raise ValueError("Prepared input lacks verified still-only/profile/icon checks")
    path = Path(receipt["output_path"])
    digest = receipt["output_sha256"]
    if args.input is not None and args.input.resolve() != path.resolve():
        raise ValueError("--input differs from prepared input receipt")
    if args.input_sha256 is not None and args.input_sha256 != digest:
        raise ValueError("--input-sha256 differs from prepared input receipt")
    if sha256(path) != digest:
        raise ValueError("Prepared APK digest no longer matches its receipt")
    args.input, args.input_sha256 = path, digest
    return receipt_path, receipt


def source_identity(source):
    inputs = sorted(source.rglob("*.java")) + [Path(__file__), ROOT/"tools"/"camera_toolchain.py",
             ROOT/"tools"/"diagnostic_hooks.py", ROOT/"tools"/"recipe_hooks.py", ROOT/"tools"/"prepare_camera_font.py",
             ROOT/"tools"/"prepare_pinyin.py", ROOT/"tools"/"camera_branding.py", ROOT/"LICENSE"]
    inputs += sorted(path for path in (ROOT/"camera"/"assets").rglob("*") if path.is_file())
    digest = hashlib.sha256()
    entries = {}
    for path in inputs:
        name = str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path.relative_to(source))
        data = path.read_bytes()
        digest.update(name.encode("utf-8")+b"\0"+len(data).to_bytes(8, "big")+data)
        entries[name] = hashlib.sha256(data).hexdigest()
    return digest.hexdigest(), entries


def generate_build_info(work, identity, input_sha, prepared):
    values = {"VERSION": VERSION, "BUILD_ID": identity, "BASE_APK_SHA256": input_sha,
              "UPSTREAM_COMMIT": prepared["source_commit"],
              "SOURCE_RELEASE_SHA256": prepared["inputs"]["release"]["sha256"],
              "NATIVE_ACTIVITY": "com.yuki.imaging.app.pictureeffectplus.PictureEffectPlus", "PACKAGE_NAME": PACKAGE}
    path = work/"generated"/"com"/"filmlab"/"diagnostics"/"BuildInfo.java"
    path.parent.mkdir(parents=True)
    path.write_text("// Generated; SPDX-License-Identifier: MIT\npackage com.filmlab.diagnostics;\n"
                    +"public final class BuildInfo {\n"+"".join(
                        f" public static final String {key} = {json.dumps(value)};\n" for key,value in values.items())
                    +" private BuildInfo() {}\n}\n")
    return path


def verify_label_resources(manifest, resources):
    """Check selector-facing resource IDs and their text in every configuration."""
    from camera_branding import NAME_IDS, resource_entries
    details = manifest_details(manifest)
    if (details["applicationLabel"] != {"type": 1, "value": APPLICATION_LABEL_ID}
            or details["activityLabels"].get(ACTIVITY) != {"type": 1, "value": LAUNCHER_LABEL_ID}):
        raise ValueError("Application/launcher labels must retain their native resource references")
    entries, _ = resource_entries(resources)
    counts = {}
    for name, resource_id in NAME_IDS.items():
        values = [entry for entry in entries if entry["id"] == resource_id]
        if (len(values) != 33 or len({entry["config"] for entry in values}) != 33
                or any(entry["name"] != name or entry["type"] != "string" or entry["value_type"] != 3
                       or entry["text"] != "Film Lab" for entry in values)):
            raise ValueError("Application/launcher label resource does not resolve to Film Lab in every configuration")
        counts[hex(resource_id)] = len(values)
    return {"labelEncoding": "TYPE_REFERENCE", "applicationLabelResource": hex(APPLICATION_LABEL_ID),
            "launcherLabelResource": hex(LAUNCHER_LABEL_ID), "labelConfigurationsVerified": counts}


def verify_manifest(apk, work, tools, cache):
    with zipfile.ZipFile(apk) as archive:
        label_verification = verify_label_resources(archive.read("AndroidManifest.xml"), archive.read("resources.arsc"))
    directory = work/"verified-manifest"
    run(["java", "-jar", tools["apktool"], "d", "--only-manifest", "-s", "-p", cache/"framework",
         apk, "-o", directory])
    document = ET.parse(directory/"AndroidManifest.xml").getroot()
    ns = "{"+ANDROID_NS+"}"
    if document.get("package") != PACKAGE: raise ValueError("Independent manifest package verification failed")
    # Apktool extracts version attributes into apktool.yml, intentionally
    # omitting them from the decoded AndroidManifest.xml.
    metadata = (directory/"apktool.yml").read_text()
    decoded_codes = re.findall(r"^  versionCode: ['\"]?([^'\"\n]+)['\"]?$", metadata, re.MULTILINE)
    decoded_names = re.findall(r"^  versionName: ['\"]?([^'\"\n]+)['\"]?$", metadata, re.MULTILINE)
    if decoded_codes != [str(VERSION_CODE)] or decoded_names != [VERSION]:
        raise ValueError("Independent manifest upgrade version verification failed")
    application = document.find("application")
    if application is None or application.get(ns+"name") != "com.yuki.imaging.app.pictureeffectplus.AppContext":
        raise ValueError("Original Application class was not retained")
    if application.get(ns+"label") != "@"+str(APPLICATION_LABEL_ID):
        raise ValueError("Independent application label resource verification failed")
    launchers = []
    original_activity, diagnostic_private = False, False
    for activity in application.findall("activity"):
        if activity.get(ns+"name") == ACTIVITY:
            original_activity = True
            if activity.get(ns+"label") != "@"+str(LAUNCHER_LABEL_ID):
                raise ValueError("Independent launcher label resource verification failed")
        if activity.get(ns+"name") == DIAGNOSTIC_ACTIVITY:
            diagnostic_private = activity.get(ns+"exported") == "false"
        for intent in activity.findall("intent-filter"):
            if any(c.get(ns+"name") == "android.intent.category.LAUNCHER" for c in intent.findall("category")):
                launchers.append(activity.get(ns+"name"))
    if not original_activity or not diagnostic_private or launchers != [ACTIVITY]:
        raise ValueError("Independent component/launcher verification failed")
    return {"decoder": "apktool --only-manifest", "package": PACKAGE, "launchers": launchers,
            "originalApplicationRetained": True, "originalActivityRetained": True,
            "diagnosticActivityNotExported": True, "versionCode": VERSION_CODE, "versionName": VERSION,
            **label_verification}


def build(args):
    from camera_branding import patch_names, replace_icons
    args.cache = args.cache.resolve()
    args.source = args.source.resolve()
    args.work = args.work.resolve()
    args.output = args.output.resolve()
    tools = ensure_tools(args.cache, args.offline)
    receipt_path, prepared = prepared_input(args)
    if args.work.exists() and any(args.work.iterdir()):
        raise ValueError("Work directory must be empty; existing files are never deleted")
    args.work.mkdir(parents=True, exist_ok=True)
    identity, source_hashes = source_identity(args.source)
    font_assets, font_receipt = prepare_font(args.cache, args.source, args.offline)
    generated = generate_build_info(args.work, identity, args.input_sha256, prepared)
    module = compile_module(args.source, args.work, tools, generated)
    with zipfile.ZipFile(args.input) as original:
        if original.testzip(): raise ValueError("Corrupt input APK")
        manifest, details = patch_manifest(original.read("AndroidManifest.xml"))
        resources = patch_resource_package(original.read("resources.arsc"), details["originalPackage"])
        resources, branding_receipt = patch_names(resources)
        branded_icons, icon_receipt = replace_icons(original, resources)
        branding_receipt.update(icon_receipt)
        mini = args.work/"module.apk"
        with zipfile.ZipFile(mini, "w") as archive:
            archive.writestr("AndroidManifest.xml", original.read("AndroidManifest.xml"))
            archive.writestr("resources.arsc", original.read("resources.arsc"))
            archive.writestr("classes.dex", module.read_bytes())
    decoded, module_decoded = args.work/"base", args.work/"module"
    framework = args.cache/"framework"
    run(["java", "-jar", tools["apktool"], "d", "-p", framework, "-r", args.input, "-o", decoded])
    run(["java", "-jar", tools["apktool"], "d", "-p", framework, "-r", mini, "-o", module_decoded])
    copied = []
    for source in sorted((module_decoded/"smali").rglob("*.smali")):
        relative = source.relative_to(module_decoded/"smali")
        target = decoded/"smali"/relative
        if target.exists(): raise ValueError(f"Diagnostic class collides with input: {relative}")
        target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, target)
        copied.append(str(relative))
    spec = importlib.util.spec_from_file_location("filmlab_diagnostic_hooks", ROOT/"tools"/"diagnostic_hooks.py")
    hooks = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(hooks)
    hook_receipt = hooks.patch(decoded)
    spec = importlib.util.spec_from_file_location("filmlab_recipe_hooks", ROOT/"tools"/"recipe_hooks.py")
    recipe_hooks = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(recipe_hooks)
    recipe_hook_receipt = recipe_hooks.patch(decoded)
    rebuilt = args.work/"assembled.apk"
    run(["java", "-jar", tools["apktool"], "b", "-p", framework, "-j", "1", decoded, "-o", rebuilt])
    with zipfile.ZipFile(rebuilt) as archive: dex = archive.read("classes.dex")
    if dex[:8] != b"dex\n035\0": raise ValueError("Merged DEX must be version 035")
    method_refs = struct.unpack_from("<I", dex, 88)[0]
    if method_refs >= 65536:
        raise ValueError("Merged DEX exceeds the legacy single-DEX method-reference limit")
    if source_identity(args.source)[0] != identity:
        raise ValueError("Diagnostic sources changed during the build; retry in a fresh work directory")
    unsigned = args.work/"unsigned.apk"
    changed = {"AndroidManifest.xml": manifest, "resources.arsc": resources, "classes.dex": dex, **branded_icons}
    added = {"assets/legal/LICENSE-FilmLab-MIT.txt": (ROOT/"LICENSE").read_bytes(), **font_assets}
    for asset in sorted((ROOT/"camera"/"assets").rglob("*")):
        if asset.is_file():
            name = "assets/" + asset.relative_to(ROOT/"camera"/"assets").as_posix()
            if name in added:
                raise ValueError("Duplicate added asset: " + name)
            added[name] = asset.read_bytes()
    with zipfile.ZipFile(args.input) as original, zipfile.ZipFile(unsigned, "w") as archive:
        for info in original.infolist():
            if info.filename.upper().startswith("META-INF/"): continue
            archive.writestr(info, changed.get(info.filename, original.read(info.filename)))
        for name, data in added.items():
            if name in original.namelist(): raise ValueError("Added asset collides with upstream: " + name)
            info = zipfile.ZipInfo(name, (2000, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    key, env = ensure_key(args.cache)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    signer = ["java", "-cp", tools["signer"], "com.android.apksigner.ApkSignerTool"]
    run([*signer, "sign", "--ks", key, "--ks-key-alias", "filmlab", "--ks-pass", "env:FILM_LAB_KEY_PASSWORD",
         "--key-pass", "env:FILM_LAB_KEY_PASSWORD", "--min-sdk-version", "10", "--max-sdk-version", "10",
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "false", "--v3-signing-enabled", "false",
         "--v4-signing-enabled", "false", "--out", args.output, unsigned], env=env)
    verification = run([*signer, "verify", "--min-sdk-version", "10", "--max-sdk-version", "10",
                        "--verbose", "--print-certs", args.output])
    if "Verified using v1 scheme (JAR signing): true" not in verification:
        raise ValueError("API-10 JAR signature verification did not pass")
    manifest_verification = verify_manifest(args.output, args.work, tools, args.cache)
    preserved = 0
    with zipfile.ZipFile(args.input) as before, zipfile.ZipFile(args.output) as after:
        if after.testzip(): raise ValueError("Output APK integrity failed")
        dex_entries = [name for name in after.namelist() if name.startswith("classes") and name.endswith(".dex") and "/" not in name]
        if dex_entries != ["classes.dex"]: raise ValueError("Legacy camera build must contain exactly one DEX")
        for name in before.namelist():
            if name.upper().startswith("META-INF/") or name in changed: continue
            if before.read(name) != after.read(name): raise ValueError(f"Unexpected content change: {name}")
            preserved += 1
        for name, data in added.items():
            if after.read(name) != data:
                raise ValueError("Added asset changed during packaging: " + name)
        for name, data in changed.items():
            if after.read(name) != data:
                raise ValueError("Patched resource changed during packaging: " + name)
        if after.read("classes.dex")[:8] != b"dex\n035\0": raise ValueError("Invalid final DEX")
    receipt = {"schemaVersion": 1, "artifact": str(args.output), "sha256": sha256(args.output),
               "inputSha256": args.input_sha256, "manifest": details, "dexVersion": "035", "javaTarget": "1.6",
               "dexFiles": 1, "dexMethodReferences": method_refs, "manifestSdkDeclaration": "inherited-absent; implicit minimum 1",
               "compileApi": 10, "signatureApiChecked": 10, "signatureScheme": "v1", "cameraTested": False,
               "resourceHandling": "binary package header and app-name values; original icon paths; no aapt", "preservedZipEntries": preserved,
               "originalClassNamespacesPreserved": True, "addedClasses": copied, "addedAssets": list(added),
               "version": VERSION, "buildId": identity, "sourceSha256": source_hashes, "hooks": hook_receipt,
               "versionCode": VERSION_CODE, "recipeHooks": recipe_hook_receipt, "font": font_receipt,
               "branding": branding_receipt,
               "preparedInputReceiptSha256": sha256(receipt_path), "preparedInput": prepared,
               "tools": {k: sha256(v) for k,v in tools.items()}, "signatureVerification": verification,
               "manifestVerification": manifest_verification}
    args.output.with_suffix(".build.json").write_text(json.dumps(receipt, indent=2)+"\n")
    print("Built", args.output, sha256(args.output))
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="Optional confirmation of the prepared receipt's APK path")
    parser.add_argument("--input-sha256", help="Optional confirmation of the prepared receipt's digest")
    parser.add_argument("--receipt", type=Path, help="Defaults to CACHE/upstream/film-studio-photo-receipt.json")
    parser.add_argument("--source", type=Path, default=ROOT/"camera"/"java")
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT/"dist"/"FilmLab-0.2.3-alpha.apk")
    parser.add_argument("--cache", type=Path, default=CACHE)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    try: build(args)
    except (ValueError, FileNotFoundError, subprocess.CalledProcessError) as error:
        if isinstance(error, subprocess.CalledProcessError): print(error.stdout or "")
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
