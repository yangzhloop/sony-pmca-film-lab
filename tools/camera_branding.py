#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Replace only pinned Film Lab name values and app icon bitmap payloads.

The resource IDs, configurations, keys, type tables, and every other value stay
unchanged. New PNGs are original raster exports of FilmLabUi.FilmIcon geometry;
no inherited bitmap is edited and no imaging/font dependency is required.
"""
from __future__ import annotations

import hashlib
import struct
import zlib

NAME_IDS = {
    "STRID_FUNC_EFFECT_MASTER": 0x7f090028,
    "STRID_FUNC_EFFECT_MASTER_2L": 0x7f090029,
}
ICON_IDS = {
    "p_16_aa_parts_pe_appicon": 0x7f02001d,
    "p_16_dd_parts_pe_appicon": 0x7f02004d,
    "p_16_dd_parts_pe_launchericon": 0x7f02005c,
    "p_16_dd_parts_pe_launchericon_s": 0x7f02005d,
}
ICON_SIZES = {
    "p_16_aa_parts_pe_appicon": (58, 46),
    "p_16_dd_parts_pe_appicon": (70, 55),
    "p_16_dd_parts_pe_launchericon": (72, 96),
    "p_16_dd_parts_pe_launchericon_s": (54, 72),
}


def resource_entries(data):
    # Shared checked Android string-pool codec; imported lazily to keep the build
    # entry point usable both as a CLI and as a module in regression tests.
    from build_diagnostic import StringPool, chunks
    kind, header, size = struct.unpack_from("<HHI", data)
    if kind != 2 or header != 12 or size != len(data):
        raise ValueError("Unexpected resource-table header")
    parts = list(chunks(data, header))
    pools = [part for kind, part in parts if kind == 1]
    if len(pools) != 1:
        raise ValueError("Expected exactly one resource value string pool")
    pool = StringPool(pools[0])
    result, package_offset = [], header
    for kind, package in parts:
        if kind == 0x200:
            package_header = struct.unpack_from("<H", package, 2)[0]
            if package_header not in (284, 288):
                raise ValueError("Unexpected resource package header")
            package_id = struct.unpack_from("<I", package, 8)[0]
            type_offset, _, key_offset = struct.unpack_from("<III", package, 268)
            type_ids = struct.unpack_from("<I", package, 284)[0] if package_header == 288 else 0
            keys = StringPool(package[key_offset:])
            types = StringPool(package[type_offset:])
            chunk_offset = package_header
            for chunk_kind, chunk in chunks(package, package_header):
                if chunk_kind == 0x201:
                    type_header = struct.unpack_from("<H", chunk, 2)[0]
                    type_id, flags = chunk[8:10]
                    count, start = struct.unpack_from("<II", chunk, 12)
                    if flags or type_header + 4 * count > start:
                        raise ValueError("Unsupported sparse/compact resource type")
                    config = chunk[20:type_header].hex()
                    for index in range(count):
                        offset = struct.unpack_from("<I", chunk, type_header + 4 * index)[0]
                        if offset == 0xffffffff:
                            continue
                        entry = start + offset
                        entry_size, entry_flags, key = struct.unpack_from("<HHI", chunk, entry)
                        if entry_flags & 1:
                            # Complex values have a map count at offset 12.
                            length = entry_size + 12 * struct.unpack_from("<I", chunk, entry + 12)[0]
                            value_type, value = None, None
                        else:
                            length = entry_size + 8
                            value_type = chunk[entry + entry_size + 3]
                            value = struct.unpack_from("<I", chunk, entry + entry_size + 4)[0]
                        if entry + length > len(chunk):
                            raise ValueError("Truncated resource value")
                        result.append({
                            "id": package_id << 24 | (type_id + type_ids) << 16 | index,
                            "name": keys.strings[key], "type": types.strings[type_id - 1],
                            "config": config, "value_type": value_type, "value": value,
                            "text": pool.strings[value] if value_type == 3 else None,
                            "value_offset": package_offset + chunk_offset + entry + entry_size + 4,
                            "raw": chunk[entry:entry + length],
                        })
                chunk_offset += len(chunk)
        package_offset += len(package)
    return result, pool


def patch_names(data):
    from build_diagnostic import chunks
    entries, pool = resource_entries(data)
    names = [entry for entry in entries if entry["name"] in NAME_IDS]
    counts = {name: 0 for name in NAME_IDS}
    replacement = pool.index("Film Lab")
    patched = bytearray(data)
    for entry in names:
        if (entry["id"] != NAME_IDS[entry["name"]] or entry["type"] != "string"
                or entry["value_type"] != 3 or entry["text"] != "胶片工坊"):
            raise ValueError("Pinned boot/app name resource changed")
        struct.pack_into("<I", patched, entry["value_offset"], replacement)
        counts[entry["name"]] += 1
    if counts != {name: 33 for name in NAME_IDS}:
        raise ValueError("Pinned app-name resource configuration count changed")
    # Appending preserves every original index, including styled-string indices.
    parts = [pool.encode() if kind == 1 else chunk for kind, chunk in chunks(bytes(patched), 12)]
    header = bytearray(data[:12])
    struct.pack_into("<I", header, 4, 12 + sum(map(len, parts)))
    output = bytes(header) + b"".join(parts)
    after, _ = resource_entries(output)
    if len(after) != len(entries):
        raise ValueError("Resource count changed during branding")
    for before, current in zip(entries, after):
        if any(before[key] != current[key] for key in ("id", "name", "type", "config")):
            raise ValueError("Resource identity/configuration changed during branding")
        if before["name"] in NAME_IDS:
            if current["text"] != "Film Lab":
                raise ValueError("Boot/app name verification failed")
        elif before["raw"] != current["raw"] or before["text"] != current["text"]:
            raise ValueError("Unrelated resource value changed during branding")
    return output, {"name": "Film Lab", "nameResourceIds": [hex(value) for value in NAME_IDS.values()],
                    "nameConfigurationsReplaced": counts, "resourceIdsPreserved": True}


def _png_chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)


def film_icon(width, height):
    """Deterministic 4x antialiased RGBA PNG of the existing cartridge vectors."""
    if width < 32 or height < 32 or width > 128 or height > 128:
        raise ValueError("Unsupported icon size")
    rectangles = [(10, 9, 28, 39), (8, 6, 30, 10), (8, 38, 30, 41), (29, 15, 43, 33)]
    lines = [(x, 17, x, 20) for x in range(32, 43, 4)] + [(x, 28, x, 31) for x in range(32, 43, 4)]
    for x1, y1, x2, y2 in rectangles:
        lines.extend([(x1, y1, x2, y1), (x2, y1, x2, y2), (x1, y2, x2, y2), (x1, y1, x1, y2)])
    unit = min((width - 6) / 38.0, (height - 6) / 38.0)
    # Centre the actual geometry (including stroke), rather than the 48px view.
    origin_x, origin_y = width / 2.0 - 25.5 * unit, height / 2.0 - 23.5 * unit
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # PNG filter None: simple, deterministic and legacy-safe.
        for x in range(width):
            covered = 0
            for sy in range(4):
                vy = (y + (sy + .5) / 4 - origin_y) / unit
                for sx in range(4):
                    vx = (x + (sx + .5) / 4 - origin_x) / unit
                    if any((abs(vx - x1) <= .75 and y1 - .75 <= vy <= y2 + .75) if x1 == x2
                           else (abs(vy - y1) <= .75 and x1 - .75 <= vx <= x2 + .75)
                           for x1, y1, x2, y2 in lines):
                        covered += 1
            raw.extend((255, 174, 66, (covered * 255 + 8) // 16) if covered else (0, 0, 0, 0))
    return (b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + _png_chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + _png_chunk(b"IEND", b""))


def replace_icons(archive, resources):
    entries, _ = resource_entries(resources)
    icons = [entry for entry in entries if entry["name"] in ICON_IDS]
    if len(icons) != 7:
        raise ValueError("Pinned app/launcher icon configuration count changed")
    changed, receipt, rendered = {}, [], {}
    for entry in icons:
        if entry["id"] != ICON_IDS[entry["name"]] or entry["type"] != "drawable" or entry["value_type"] != 3:
            raise ValueError("Pinned app/launcher icon resource changed")
        path = entry["text"]
        if not path.startswith("res/drawable") or not path.endswith("/" + entry["name"] + ".png") or path in changed:
            raise ValueError("Unexpected app icon resource path")
        before = archive.read(path)
        if before[:8] != b"\x89PNG\r\n\x1a\n":
            raise ValueError("App icon resource is not PNG")
        size = ICON_SIZES[entry["name"]]
        old_size = struct.unpack_from(">II", before, 16)
        if old_size not in (size, (1, 1)):
            raise ValueError("Pinned app icon dimensions changed")
        if size not in rendered:
            rendered[size] = film_icon(*size)
        changed[path] = rendered[size]
        receipt.append({"path": path, "resourceId": hex(entry["id"]), "previousSize": old_size,
                        "size": size, "sha256": hashlib.sha256(changed[path]).hexdigest()})
    expected_paths = {name for name in archive.namelist() if name.endswith(".png")
                      and name.rsplit("/", 1)[-1][:-4] in ICON_IDS}
    if set(changed) != expected_paths:
        raise ValueError("An app icon variant would remain unbranded")
    return changed, {"icons": receipt, "iconSource": "FilmLabUi.FilmIcon original cartridge geometry",
                     "iconColor": "#ffae42", "pngFormat": "RGBA8, non-interlaced"}
