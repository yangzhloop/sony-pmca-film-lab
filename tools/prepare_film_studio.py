#!/usr/bin/env python3
"""Prepare a pinned Film Studio photo APK using its published compiled profiles.

This is not a fresh Fujifilm LUT fit or a byte-identical upstream release build.
Downloads and generated coefficients, keys and APKs remain in the local cache.
Original upstream code/licenses remain in the separate pinned checkout.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET


COMMIT = "ea766496cb40ec6a21beeb88da762679cf1786c8"
REPOSITORY = "https://github.com/ukiki0718-netizen/sony-a5100-film-studio.git"
HOOK_COMMIT = "7c565898562c73c5073c54dfc831c8c3df9c24cf"
BASE_SHA = "80cb4a541f5f3dd49e8f53ffb1905048097fec17209fc9cb595a00681e65e8ea"
RELEASE_SHA = "14d8558687b62f379d60119f2b363443e6d57acdff9a1c19ecf9e64dfb7dcaf1"
HOOK_SHA = "2db88c8e42311c587ca8304c2c8ed7d70592ee988ee154327ed5503b107723ae"
APKTOOL_SHA = "66cf4524a4a45a7f56567d08b2c9b6ec237bcdd78cee69fd4a59c8a0243aeafa"
ASSET_ROOT = "https://github.com/ukiki0718-netizen/sony-a5100-film-studio/releases/download/v0.3.0-alpha/"
PINS = {
    "base": ("inputs/ricoh-v1.1.4-base.apk", "https://github.com/bonyback1/sony-pmca-ricoh-mod/releases/download/v1.1.4/PictureEffectPlus_Ricoh.apk", BASE_SHA),
    "hook": ("inputs/pinned-RicohHook.smali", f"https://raw.githubusercontent.com/bonyback1/sony-pmca-ricoh-mod/{HOOK_COMMIT}/src/smali/RicohHook.smali", HOOK_SHA),
    "release": ("inputs/FilmStudio-0.3.0-alpha-movie.apk", ASSET_ROOT + "FilmStudio-0.3.0-alpha-movie.apk", RELEASE_SHA),
    "checksums": ("inputs/SHA256SUMS-v0.3.0-alpha.txt", ASSET_ROOT + "SHA256SUMS.txt", "548994731d848edec15d8783ded11d9dd69a425973f84880fda24196e225b947"),
    "verification": ("inputs/RELEASE-VERIFICATION-v0.3.0-alpha.json", ASSET_ROOT + "RELEASE-VERIFICATION.json", "06a00d500182030248f474e89b18de83bade02dbf84ca3cc3763e9d83cfca4b3"),
    "apktool": ("tools/apktool_2.12.1.jar", "https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar", APKTOOL_SHA),
}
PROFILE_IDS = [
    "pop-color", "fuji-velvia", "fuji-astia", "fuji-chrome", "fuji-reala",
    "fuji-proneg", "fuji-negative", "fuji-eterna", "fuji-bleach", "fuji-acros",
    "ricoh-positive", "ricoh-negative", "ricoh-hcbw", "ricoh-daido", "ricoh-cross",
]
HOOK_PATH = "smali/com/yuki/imaging/app/pictureeffectplus/shooting/camera/RicohHook.smali"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(command: list[str], cwd: Path | None = None, env=None, capture=False):
    print("+ " + " ".join(map(str, command)), flush=True)
    return subprocess.run(command, cwd=cwd, env=env, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def fetch(cache: Path, key: str, offline: bool) -> Path:
    relative, url, expected = PINS[key]
    destination = cache / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        require(not offline, f"Missing cached {key}: {destination}")
        print(f"Downloading {key}: {url}", flush=True)
        temporary = None
        try:
            with urllib.request.urlopen(url, timeout=90) as response, tempfile.NamedTemporaryFile(
                dir=destination.parent, prefix=".download-", delete=False
            ) as output:
                temporary = Path(output.name)
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            require(sha256(temporary) == expected, f"Downloaded {key} SHA-256 mismatch")
            os.link(temporary, destination)  # Never overwrite an existing input.
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    require(sha256(destination) == expected, f"Cached {key} SHA-256 mismatch: {destination}")
    return destination


def write_generated(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        require(path.read_bytes() == data, f"Refusing to overwrite different existing file: {path}")
    else:
        with path.open("xb") as stream:
            stream.write(data)


def source_checkout(cache: Path, offline: bool) -> Path:
    source = cache / "upstream/film-studio"
    if not source.exists():
        require(not offline, "Pinned source checkout is not cached")
        source.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--no-checkout", REPOSITORY, str(source)])
        run(["git", "checkout", "--detach", COMMIT], cwd=source)
    head = run(["git", "rev-parse", "HEAD"], cwd=source, capture=True).strip()
    require(head == COMMIT, f"Source must be pinned to {COMMIT}; found {head}")
    dirty = run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=source, capture=True)
    require(not dirty.strip(), "Pinned source has tracked changes; use a fresh cache")
    return source


def decode(apk: Path, output: Path, jar: Path, framework: Path, env) -> None:
    if output.exists():
        receipt = output.with_name(output.name + ".input-sha256")
        require(receipt.exists() and receipt.read_text().strip() == sha256(apk),
                f"Existing decode lacks matching provenance: {output}")
        return
    run(["java", "-jar", str(jar), "d", "-r", "-p", str(framework),
         str(apk), "-o", str(output)], env=env)
    write_generated(output.with_name(output.name + ".input-sha256"), (sha256(apk) + "\n").encode())


def arrays_from(decoded: Path, checker):
    return checker.compiled_profile_arrays(decoded, (decoded / HOOK_PATH).read_text())


def prepare(cache: Path, offline=False, prepare_only=False) -> dict:
    require(__debug__, "Run without -O/PYTHONOPTIMIZE; upstream validators require assertions")
    cache.mkdir(parents=True, exist_ok=True)
    inputs = {key: fetch(cache, key, offline) for key in PINS}
    listed = inputs["checksums"].read_text().split()
    require(listed == [RELEASE_SHA, inputs["release"].name], "Published checksum file disagrees with release pin")
    source = source_checkout(cache, offline)
    sys.path.insert(0, str(source / "tools"))
    checker = importlib.import_module("check_combined")
    profiles_module = importlib.import_module("film_profiles")
    fit = importlib.import_module("fit_luts")
    icon_module = importlib.import_module("filter_icons")
    import numpy
    require(numpy.__version__ == "2.3.5", "Use pinned numpy==2.3.5 for upstream tools")
    framework = cache / "tools/apktool-framework"
    java_home = cache / "tools/java-user-home"
    java_home.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    # Apktool's own nested Java commands also keep their cache under /workspace.
    env["JAVA_TOOL_OPTIONS"] = env.get("JAVA_TOOL_OPTIONS", "") + f" -Duser.home={java_home}"
    decoded = cache / "upstream/film-studio-release-decoded"
    decode(inputs["release"], decoded, inputs["apktool"], framework, env)
    arrays = arrays_from(decoded, checker)
    menu = ET.parse(decoded / "assets/MenuData.xml")
    top = next(node for node in menu.iter() if node.get("ItemId") == "ApplicationTop")
    require([node.get("ItemId") for node in top] == PROFILE_IDS, "Published preset ordering changed")
    require([entry[1] for entry in fit.FILMS] == PROFILE_IDS[:10], "Pinned Fuji source IDs changed")
    recovered = []
    for index, (token, identifier, label) in enumerate(fit.FILMS):
        matrix = arrays[f"sFujimatrix{index}_100"]
        raw = arrays[f"sFujigamma{index}_100"]
        gamma = [raw[i] | raw[i + 1] << 8 for i in range(0, 2048, 2)]
        require(len(matrix) == 9 and min(matrix) >= -2048 and max(matrix) <= 3072, "Invalid matrix")
        require(all(sum(matrix[row:row + 3]) == 1024 for row in (0, 3, 6)), "Fuji neutral rows changed")
        require(len(gamma) == 1024 and 0 <= min(gamma) <= max(gamma) <= 1023,
                "Invalid 10-bit curve")
        require(all(a <= b for a, b in zip(gamma, gamma[1:])), "Curve is not monotonic")
        recovered.append(dict(id=identifier, name=label, official_film=token,
                              matrix=[matrix[i:i + 3] for i in (0, 3, 6)], gamma=gamma))
    ricoh = profiles_module.ricoh_profiles(inputs["hook"])
    require([profile["id"] for profile in ricoh] == PROFILE_IDS[10:], "Pinned Ricoh IDs changed")
    for index, profile in enumerate(ricoh, 10):
        require(arrays[f"sFujimatrix{index}_100"] == sum(profile["matrix"], []), "Ricoh matrix differs from pinned hook")
        expected = [byte for value in profile["gamma"] for byte in (value & 255, value >> 8)]
        require(arrays[f"sFujigamma{index}_100"] == expected, "Ricoh curve differs from pinned hook")
    provenance = dict(method="extracted-published-compiled-profile-holders", source_apk_sha256=RELEASE_SHA,
                      source_revision=COMMIT, fresh_lut_fit=False, original_lut_hashes_available=False)
    data = dict(schema=1, version="0.3.0-alpha-extracted", source_url=PINS["release"][1],
                recovery=provenance, presets=recovered)
    profile_path = source / "profiles/fuji_official_approx.json"
    write_generated(profile_path, (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode())
    # Recreate only desktop approximation exports required by check_build.py.
    with tempfile.TemporaryDirectory(dir=cache, prefix="profile-exports-") as temporary:
        for profile in recovered:
            name = f'SonyProxy_{profile["official_film"].replace(".", "")}.cube'
            path = Path(temporary) / name
            fit.write_cube(path, numpy.array(profile["matrix"]) / 1024,
                           numpy.array(profile["gamma"]) / 1023, profile["official_film"])
            write_generated(source / "output" / name, path.read_bytes())
    receipt = dict(schema=1, source_commit=COMMIT, hook_commit=HOOK_COMMIT,
                   inputs={key: dict(path=str(path), sha256=PINS[key][2], source_url=PINS[key][1])
                           for key, path in inputs.items()},
                   profile_provenance=provenance, profile_json_sha256=sha256(profile_path),
                   extracted_fuji_profiles=10, matched_pinned_ricoh_profiles=5,
                   original_compiled_arrays=120, movie_enabled=False, camera_tested=False)
    receipt_path = cache / "upstream/film-studio-photo-receipt.json"
    if prepare_only:
        return receipt
    output = source / "output/FilmStudio-0.3.0-alpha-photo.apk"
    if output.exists():
        require(receipt_path.exists(), f"Existing APK has no build receipt: {output}")
        previous = json.loads(receipt_path.read_text())
        require(previous.get("output_sha256") == sha256(output), "Existing photo APK changed")
    else:
        build_parent = Path(tempfile.mkdtemp(dir=cache / "upstream", prefix="photo-build-"))
        run([sys.executable, "tools/build_apk.py", "--input", str(inputs["base"]),
             "--apktool", str(inputs["apktool"]), "--upstream-hook", str(inputs["hook"]),
             "--work", str(build_parent / "decoded")], cwd=source, env=env)
    run([sys.executable, "tools/check_strength.py"], cwd=source, env=env)
    run([sys.executable, "tools/check_build.py"], cwd=source, env=env)
    verified = cache / "upstream/film-studio-photo-verified"
    decode(output, verified, inputs["apktool"], framework, env)
    photo_arrays = arrays_from(verified, checker)
    require(photo_arrays == arrays, "Photo build's 120 arrays differ from published release")
    require(icon_module.verify_icons(verified, profiles_module.combined_profiles(recovered, inputs["hook"])) == 15,
            "Photo icon/resource verification failed")
    app = verified / "smali/com/yuki/imaging/app/pictureeffectplus/PictureEffectPlus.smali"
    rec_mode = re.search(r"\.method public getSupportingRecMode\(\)I\n(.*?)\.end method", app.read_text(), re.S)
    require(rec_mode is not None and "const/4 v0, 0x1" in rec_mode[1], "Photo app is not still-only rec-mode 1")
    photo_menu = ET.parse(verified / "assets/MenuData.xml")
    require(not any(node.get("ItemId") in ("FujiMovieFormat", "FujiMovieQuality") for node in photo_menu.iter()),
            "Photo build unexpectedly contains added movie controls")
    run([sys.executable, "tools/check_live_preview.py", str(verified)], cwd=source, env=env)
    receipt.update(output_path=str(output), output_sha256=sha256(output),
                   compiled_arrays_equal_to_published=120, verified_icons=15,
                   supporting_rec_mode=1, checks=["strength-endpoints", "archive-and-signature",
                   "compiled-profile-equality", "icons", "still-only-rec-mode", "preview-control-flow"])
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, default=Path("/workspace/.cache/film-lab"))
    parser.add_argument("--offline", action="store_true", help="Use only already verified cached downloads/source")
    parser.add_argument("--prepare-only", action="store_true", help="Acquire/decode/extract inputs without building")
    args = parser.parse_args()
    try:
        print(json.dumps(prepare(args.cache_dir.resolve(), args.offline, args.prepare_only), indent=2))
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
