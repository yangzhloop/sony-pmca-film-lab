#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Exercise the real recipe/bridge transactions against API-shaped Sony host fixtures."""
import argparse
from pathlib import Path
import subprocess
import tempfile
from camera_toolchain import CACHE, ensure_tools

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=CACHE)
    args = parser.parse_args()
    tools = ensure_tools(args.cache, offline=True)
    fixtures = sorted((ROOT / "camera/tests/engine").rglob("*.java"))
    production = [ROOT / "camera/java/com/filmlab/app" / name for name in
                  ["Recipe.java", "RecipeStore.java", "RecipeMath.java", "ShootingBridge.java"]]
    with tempfile.TemporaryDirectory(prefix="film-lab-engine-") as temporary:
        compiler = ["java", "-jar", str(tools["ecj"]), "-1.6", "-proc:none", "-encoding", "UTF-8",
                    "-bootclasspath", str(tools["android"]), "-d", temporary]
        subprocess.run(compiler + [str(path) for path in production + fixtures], check=True)
        subprocess.run(["java", "-cp", temporary, "ShootingBridgeTest"], check=True)


if __name__ == "__main__":
    main()
