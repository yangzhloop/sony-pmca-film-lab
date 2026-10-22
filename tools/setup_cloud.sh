#!/bin/sh
# Prepare the native build in the existing isolated cloud checkout.
set -eu
cd /workspace/sony-pmca-film-lab
for film_lab_command in python3 java keytool git openssl; do
    command -v "$film_lab_command" >/dev/null
done
# Keep any required Python package install in the writable cache.
export PYTHONPATH="/workspace/.cache/film-lab/python${PYTHONPATH:+:$PYTHONPATH}"
if ! python3 -c 'import numpy; assert numpy.__version__ == "2.3.5"'; then
    python3 -m pip install --target /workspace/.cache/film-lab/python 'numpy==2.3.5'
fi
python3 tools/camera_toolchain.py
python3 tools/prepare_film_studio.py
python3 tools/prepare_camera_font.py
python3 tools/prepare_pinyin.py
