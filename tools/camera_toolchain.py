#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Fetch the pinned, external legacy Android toolchain over verified HTTPS.

Tools remain outside the source tree. Java, keytool and Python 3 are required;
no system SDK installation or execution of downloaded shell scripts is needed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import ssl
import tempfile
import urllib.request
import zipfile

CACHE = Path(os.environ.get("FILM_LAB_CACHE", "/workspace/.cache/film-lab"))
TOOLS = {
    "ecj": ("ecj-4.6.1.jar", "https://repo.maven.apache.org/maven2/org/eclipse/jdt/core/compiler/ecj/4.6.1/ecj-4.6.1.jar", "9cddda75f4a1b4469e73f44e7b61a3e897d0f657df4797f9106ffe88c4eeade0"),
    "dx": ("dx-1.7.jar", "https://repo.maven.apache.org/maven2/com/google/android/tools/dx/1.7/dx-1.7.jar", "923302e666d76e126e4cdf7129318532b258b1a66e435b92ccc29e91a60357bf"),
    "apktool": ("apktool_2.12.1.jar", "https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar", "66cf4524a4a45a7f56567d08b2c9b6ec237bcdd78cee69fd4a59c8a0243aeafa"),
    "signer": ("uber-apk-signer-1.3.0.jar", "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar", "e1299fd6fcf4da527dd53735b56127e8ea922a321128123b9c32d619bba1d835"),
    "platform": ("android-2.3.3_r02.zip", "https://dl.google.com/android/repository/android-2.3.3_r02.zip", "54bdb0f1ca06ba5747061ddeea20f431af72c448334fd4d3d7f84ea2ccd29fea"),
}
ANDROID_MEMBER = "android-2.3.3_r02-linux/android.jar"
ANDROID_SHA256 = "0160896aafd1da1d3fdd9cf3a3dd0d6c9ab7ac98fffeaba8ebf44ccdc2e37356"


def sha256(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def verified(path: Path, digest: str) -> Path:
    if not path.is_file() or sha256(path) != digest:
        raise ValueError(f"SHA-256 mismatch or missing file: {path}")
    return path


def fetch(path: Path, url: str, digest: str, offline: bool = False) -> Path:
    if path.exists():
        return verified(path, digest)
    if offline:
        raise FileNotFoundError(f"Not cached (offline): {path}")
    if not url.startswith("https://"):
        raise ValueError("Tool downloads require HTTPS")
    path.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "FilmLab-build/0.1"})
    temporary = None
    try:
        with urllib.request.urlopen(request, context=ssl.create_default_context(), timeout=60) as response:
            if not response.url.startswith("https://"):
                raise ValueError("Refusing HTTPS downgrade")
            with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as target:
                temporary = Path(target.name)
                while block := response.read(1024 * 1024):
                    target.write(block)
        verified(temporary, digest)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return path


def ensure_tools(cache: Path = CACHE, offline: bool = False) -> dict[str, Path]:
    folder = cache / "tools"
    result = {key: fetch(folder / name, url, digest, offline)
              for key, (name, url, digest) in TOOLS.items()}
    android = folder / "android-api10.jar"
    if not android.exists():
        with zipfile.ZipFile(result["platform"]) as archive:
            data = archive.read(ANDROID_MEMBER)
        if hashlib.sha256(data).hexdigest() != ANDROID_SHA256:
            raise ValueError("Official API 10 android.jar digest mismatch")
        android.write_bytes(data)
    result["android"] = verified(android, ANDROID_SHA256)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=CACHE)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    tools = ensure_tools(args.cache, args.offline)
    print(json.dumps({key: {"path": str(path), "sha256": sha256(path)}
                      for key, path in tools.items()}, indent=2))


if __name__ == "__main__":
    main()
