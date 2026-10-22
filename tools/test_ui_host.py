#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Exercise real FilmLabUi physical-key flows with deterministic host Android boundaries."""
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
    fixtures = sorted((ROOT / "camera/tests/ui").rglob("*.java"))
    production = [ROOT / "camera/java/com/filmlab/app" / name for name in
                  ["Recipe.java", "RecipeStore.java", "RecipeMath.java",
                   "FilmLabUi.java", "NameKeyboard.java", "PinyinDictionary.java"]]
    production.append(ROOT / "camera/java/com/filmlab/diagnostics/CameraKeys.java")
    with tempfile.TemporaryDirectory(prefix="film-lab-ui-") as temporary:
        compiler = ["java", "-jar", str(tools["ecj"]), "-1.6", "-proc:none", "-encoding", "UTF-8",
                    "-bootclasspath", str(tools["android"]), "-d", temporary]
        subprocess.run(compiler + [str(path) for path in production + fixtures], check=True)
        subprocess.run(["java", "-cp", temporary, "com.filmlab.app.FilmLabUiTest",
                        str(ROOT / "camera/assets")], check=True)


if __name__ == "__main__":
    main()
