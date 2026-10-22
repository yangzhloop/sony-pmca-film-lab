#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Run native recipe, report, Pinyin and key logic checks with pinned API 10."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

from camera_toolchain import CACHE, ensure_tools
from build_diagnostic import generate_build_info

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=CACHE)
    args = parser.parse_args()
    tools = ensure_tools(args.cache, offline=True)
    with tempfile.TemporaryDirectory(prefix="film-lab-host-") as folder:
        work = Path(folder)
        main_classes, sony_classes, clock_classes = work / "main", work / "sony", work / "clock"
        main_classes.mkdir()
        sony_classes.mkdir()
        clock_classes.mkdir()
        generated = generate_build_info(work, "0" * 64, "0" * 64,
                                        {"source_commit": "0" * 40,
                                         "inputs": {"release": {"sha256": "0" * 64}}})
        compiler = ["java", "-jar", str(tools["ecj"]), "-1.6", "-proc:none",
                    "-encoding", "UTF-8", "-bootclasspath", str(tools["android"])]
        sources = [
            "camera/java/com/filmlab/diagnostics/ReportStore.java",
            "camera/java/com/filmlab/diagnostics/CameraKeys.java",
            "camera/java/com/filmlab/diagnostics/DiagnosticsBridge.java",
            "camera/java/com/filmlab/app/Recipe.java",
            "camera/java/com/filmlab/app/RecipeStore.java",
            "camera/java/com/filmlab/app/RecipeMath.java",
            "camera/java/com/filmlab/app/PinyinDictionary.java",
            "camera/java/com/filmlab/app/NameKeyboard.java",
            "camera/tests/ReportStoreTest.java",
            "camera/tests/CameraKeysTest.java",
            "camera/tests/RecipeStoreTest.java",
            "camera/tests/PinyinDictionaryTest.java",
            "camera/tests/NameKeyboardTest.java",
            "camera/tests/DiagnosticsBridgeTimeTest.java",
            "camera/tests/fixtures/android/view/KeyEvent.java",
            str(generated),
        ]
        subprocess.run(compiler + ["-d", str(main_classes)] + sources, cwd=ROOT, check=True)
        subprocess.run(compiler + ["-d", str(sony_classes),
                       "camera/tests/fixtures/sony/com/sony/scalar/sysutil/ScalarInput.java"],
                       cwd=ROOT, check=True)
        subprocess.run(compiler + ["-d", str(clock_classes),
                       "camera/tests/fixtures/clock/com/sony/scalar/sysutil/TimeUtil.java"],
                       cwd=ROOT, check=True)
        subprocess.run(["java", "-cp", str(main_classes), "ReportStoreTest"], check=True)
        subprocess.run(["java", "-cp", str(main_classes), "RecipeStoreTest"], check=True)
        subprocess.run(["java", "-cp", os.pathsep.join([str(main_classes), str(tools["android"])]),
                        "PinyinDictionaryTest", str(ROOT / "camera/assets/pinyin.tsv")], check=True)
        subprocess.run(["java", "-cp", os.pathsep.join([str(main_classes), str(tools["android"])]),
                        "com.filmlab.app.NameKeyboardTest", str(ROOT / "camera/assets/pinyin.tsv")], check=True)
        clock_base = os.pathsep.join([str(main_classes), str(tools["android"])])
        subprocess.run(["java", "-cp", clock_base,
                        "com.filmlab.diagnostics.DiagnosticsBridgeTimeTest", "missing"], check=True)
        subprocess.run(["java", "-cp", clock_base + os.pathsep + str(clock_classes),
                        "com.filmlab.diagnostics.DiagnosticsBridgeTimeTest"], check=True)
        for mode, classpath in [("fallback", str(main_classes)),
                                ("reflected", os.pathsep.join(map(str, [main_classes, sony_classes])))]:
            subprocess.run(["java", "-cp", classpath,
                            "com.filmlab.diagnostics.CameraKeysTest", mode], check=True)


if __name__ == "__main__":
    main()
