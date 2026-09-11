#!/usr/bin/env python3
"""Validate the GeckoLib entity resources that ship with the mod.

This intentionally checks pack-level contracts only: source geometry, texture
dimensions, expected animation names, and bone references. It has no external
dependencies and can run before a full NeoForge build.
"""

from __future__ import annotations

import json
import struct
import sys
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
RESOURCE_ROOT = REPOSITORY_ROOT / "src/main/resources/assets/mcyukkuri"
ENTITIES = {
    "reimu": {
        "geometry_identifier": "geometry.yukkuri_reimu",
        "animations": (
            "animation.mcyukkuri.reimu.idle",
            "animation.mcyukkuri.reimu.walk",
        ),
    },
    "marisa": {
        "geometry_identifier": "geometry.yukkuri_marisa",
        "animations": (
            "animation.mcyukkuri.marisa.idle",
            "animation.mcyukkuri.marisa.walk",
        ),
    },
}


def read_json(path: Path) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read JSON: {error}") from error


def png_dimensions(path: Path) -> tuple[int, int]:
    try:
        data = path.read_bytes()
    except OSError as error:
        raise ValueError(f"cannot read PNG: {error}") from error
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("not a PNG with an IHDR header")
    return struct.unpack(">II", data[16:24])


def validate_entity(name: str, expected: dict[str, object]) -> list[str]:
    errors: list[str] = []
    geometry_path = RESOURCE_ROOT / "geo/entity" / f"{name}.geo.json"
    texture_path = RESOURCE_ROOT / "textures/entity" / f"{name}.png"
    animation_path = RESOURCE_ROOT / "animations/entity" / f"{name}.animation.json"
    source_geometry_path = REPOSITORY_ROOT / "art/source" / name / f"{name}.geo.json"
    source_texture_path = REPOSITORY_ROOT / "art/source" / name / f"{name}.png"

    for source_path, runtime_path in (
        (source_geometry_path, geometry_path),
        (source_texture_path, texture_path),
    ):
        try:
            if source_path.read_bytes() != runtime_path.read_bytes():
                errors.append(f"{runtime_path}: differs from selected source asset {source_path}")
        except OSError as error:
            errors.append(f"{runtime_path}: cannot compare to selected source asset: {error}")

    try:
        geometry = read_json(geometry_path)
        geometries = geometry["minecraft:geometry"]
        if not isinstance(geometries, list) or len(geometries) != 1:
            raise ValueError("must contain exactly one minecraft:geometry entry")
        model = geometries[0]
        description = model["description"]
        if description["identifier"] != expected["geometry_identifier"]:
            errors.append(f"{geometry_path}: unexpected geometry identifier")
        bones = model["bones"]
        bone_names = {bone["name"] for bone in bones}
        if "root" not in bone_names:
            errors.append(f"{geometry_path}: missing root bone")
        if len(bone_names) != len(bones):
            errors.append(f"{geometry_path}: duplicate bone names")
        texture_size = (description["texture_width"], description["texture_height"])
    except (KeyError, TypeError, ValueError) as error:
        errors.append(f"{geometry_path}: {error}")
        return errors

    try:
        if png_dimensions(texture_path) != texture_size:
            errors.append(
                f"{texture_path}: dimensions do not match geometry texture size {texture_size}"
            )
    except ValueError as error:
        errors.append(f"{texture_path}: {error}")

    try:
        animation_document = read_json(animation_path)
        if animation_document["format_version"] != "1.8.0":
            errors.append(f"{animation_path}: expected GeckoLib animation format 1.8.0")
        animations = animation_document["animations"]
        if set(animations) != set(expected["animations"]):
            errors.append(f"{animation_path}: missing or unexpected animation identifiers")
        for animation_name in expected["animations"]:
            animation = animations.get(animation_name)
            if not isinstance(animation, dict):
                continue
            if animation.get("loop") is not True:
                errors.append(f"{animation_path}: {animation_name} must loop")
            animation_length = animation.get("animation_length")
            if not isinstance(animation_length, (int, float)) or animation_length <= 0:
                errors.append(f"{animation_path}: {animation_name} must have a positive length")
            referenced_bones = set(animation.get("bones", {}))
            if not referenced_bones:
                errors.append(f"{animation_path}: {animation_name} has no animated bones")
            if "root" not in referenced_bones:
                errors.append(f"{animation_path}: {animation_name} must animate root")
            unknown_bones = referenced_bones - bone_names
            if unknown_bones:
                errors.append(
                    f"{animation_path}: {animation_name} references unknown bones "
                    f"{', '.join(sorted(unknown_bones))}"
                )
    except (KeyError, TypeError, ValueError) as error:
        errors.append(f"{animation_path}: {error}")

    return errors


def main() -> int:
    errors = [error for name, expected in ENTITIES.items() for error in validate_entity(name, expected)]
    if errors:
        print("Entity asset validation failed:", file=sys.stderr)
        print(*errors, sep="\n", file=sys.stderr)
        return 1
    print("Entity assets are valid: reimu, marisa")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
