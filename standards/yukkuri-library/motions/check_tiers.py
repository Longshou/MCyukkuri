#!/usr/bin/env python3
"""Validate motions/tiers.json against motion sources, Java actions, moments, and wiki source tables."""

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[3]
CATALOGUE = ROOT / "standards/yukkuri-library/motions/tiers.json"
TIERS = {"base", "composite", "special"}
STATUSES = {"exists", "composable_from_existing_base", "base_missing", "needs_special"}
SOURCES = {"base", "composite", "lore", "procedural", "marisa_tsumuri", "anatomy"}
CLASSIFICATIONS = {"really_base", "really_composite", "near_duplicate_of", "special"}
REQUIRED_GAPS = {
    "run", "lunge_ram", "low_crouch", "look_back", "nod", "head_shake", "bounce_in_place",
    "lean_forward_press", "cling", "roll", "reject_or_spit", "emotional_hair", "chen_tail_signal",
    "cough_mukyu", "crouch_watch", "glass_ram", "farewell", "greeting_return", "hop_chain", "snuggle",
}
CUSTOM_JAVA_CALLERS = {
    "hop": {"Gesture.HOP_SMALL"},
    "newborn_crawl": {"Gesture.ROLL_IN"},
    "stretch_shape": {"Gesture.SQUASH"},
    "side_bend": {"Gesture.LEAN"},
    "hop_leg": {"Hop.CLIP"},
    "water_hat_climb": {"Climb.CLIP"},
    "water_hat_right": {"Right.CLIP"},
    "boat_cargo_fetch": {"Pick.CLIP"},
    "braid_head_take": {"Grab", "ParentCarry"},
    "braid_cradle_lift": {"Grab"},
    "braid_rescue_reach": {"BraidFetch.REACH"},
    "braid_rescue_hold": {"BraidFetch.HOLD"},
    "braid_rescue_settle": {"BraidFetch.SETTLE"},
    "braid_reach_low": {"Grab"},
    "braid_brim_lift": {"ParentCarry.BRIM_LIFT"},
    "braid_brim_lower": {"ParentCarry"},
    "braid_head_set": {"ParentCarry"},
    "mouth_release": {"ParentCarry"},
    "braid_item_hold": {"YukkuriModel.heldClip"},
    "boat_paddle": {"Paddle.CLIP"},
    "boat_paddle_braid": {"Paddle.BRAID_CLIP"},
    "boat_drift": {"Paddle.pose"},
    "bank_beckon": {"Beckon.clip"},
    "water_beckon": {"Beckon.clip"},
    "water_swim": {"Swim.pose"},
}
RENDERER_CALLERS = {"idle", "waddle", "boat_be_carried", "be_cradled", "anatomy_mamu_extend"}


def load_json(path, unique=False):
    def no_duplicates(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key {key!r}")
            result[key] = value
        return result

    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=no_duplicates if unique else None)


def expected_clips():
    motions = ROOT / "standards/yukkuri-library/motions"
    index = load_json(motions / "index.json")
    expected = {clip: "base" for clip in index["base"]}
    expected.update({clip: "composite" for clip in index["composite"]})
    lore = load_json(motions / "variants/lore/recipe.json")["clips"]
    expected.update({clip: "lore" for clip in lore})
    procedural = load_json(motions / "procedural/index.json")["actions"]
    expected.update({row["id"]: "procedural" for row in procedural})
    expected.update({path.stem: "marisa_tsumuri" for path in (motions / "variants/marisa-tsumuri").glob("*.json")})
    action_text = (ROOT / "src/main/java/io/github/longshou/mcyukkuri/entity/YukkuriAction.java").read_text(encoding="utf-8")
    actions = re.findall(r'^\s*[A-Z][A-Z0-9_]*\("([^"]+)"', action_text, re.MULTILINE)
    if len(actions) != len(set(actions)):
        raise ValueError("duplicate action id in YukkuriAction.java")
    anatomy = set(load_json(ROOT / "standards/yukkuri-library/behaviors/anatomy.json")["exported_actions"])
    for action in actions:
        if action in anatomy:
            expected.setdefault(action, "anatomy")
        elif action not in expected:
            raise ValueError(f"YukkuriAction {action!r} is neither an authored clip nor an anatomy action")
    return expected, set(actions)


def runtime_clips():
    result = set()

    def walk(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key in {"clips", "behavior_clips"} and isinstance(child, dict):
                    result.update(child)
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    for path in (ROOT / "src/main/resources/assets/mcyukkuri").rglob("*.json"):
        walk(load_json(path))
    return result


def wiki_source_ids():
    paths = [
        ROOT / "wiki/hakkei-reference/sources-fandom.md",
        ROOT / "wiki/hakkei-reference/sources-lore.md",
        ROOT / "wiki/hakkei-reference/sources-events.md",
        ROOT / "wiki/ecology-daily-life.md",
    ]
    result = set()
    for path in paths:
        result.update(re.findall(r"^\|\s*([A-Z]+\d+)\s*\|", path.read_text(encoding="utf-8"), re.MULTILINE))
    return result


def moments_rows():
    moments = load_json(ROOT / "standards/yukkuri-library/presentation/moments.json")["moments"]
    return {
        moment: [step["clip"] for step in row.get("steps", []) if "clip" in step]
        for moment, row in moments.items()
    }


def java_texts():
    return {
        path: path.read_text(encoding="utf-8")
        for path in (ROOT / "src/main/java").rglob("*.java")
    }


def invocation_arguments(text, marker):
    """Yield top-level arguments for each literal Java invocation of marker."""
    start = 0
    while True:
        start = text.find(marker, start)
        if start < 0:
            return
        opening = start + len(marker) - 1
        depth = 1
        quote = None
        escaped = False
        cursor = opening + 1
        args = [""]
        while cursor < len(text) and depth:
            char = text[cursor]
            if quote:
                args[-1] += char
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == quote:
                    quote = None
            elif char in {'"', "'"}:
                quote = char
                args[-1] += char
            elif char == "(":
                depth += 1
                args[-1] += char
            elif char == ")":
                depth -= 1
                if depth:
                    args[-1] += char
            elif char == "," and depth == 1:
                args.append("")
            else:
                args[-1] += char
            cursor += 1
        if depth == 0:
            yield args
        start = cursor


def literal_strings(argument):
    return set(re.findall(r'"([^"\\]*)"', argument))


def direct_java_callers(java, clip_ids):
    """Return clips selected by literal playAction/wrapper, marker, or MotionPose calls."""
    result = {clip: set() for clip in clip_ids}
    methods = {"playAction(": 0, "PetScenes.act(": 1, "perform(": 0, "ClipMarkers.of(": 0, "MotionPose(": 0}
    for text in java.values():
        for marker, argument_index in methods.items():
            for args in invocation_arguments(text, marker):
                if len(args) <= argument_index:
                    continue
                for clip in literal_strings(args[argument_index]) & clip_ids:
                    result[clip].add(marker.removesuffix("("))
    return result


def validate():
    data = load_json(CATALOGUE, unique=True)
    errors = []
    clips = data["clips"]
    behaviours = data["wiki_behaviours"]
    gaps = data["gaps"]
    expected, action_ids = expected_clips()
    gap_ids = {row["id"] for row in gaps}
    known_clips = set(clips) | runtime_clips()
    source_ids = wiki_source_ids()
    moments = moments_rows()
    java = java_texts()
    behaviour_constants = {}
    action_constants = {}
    behavior_path = ROOT / "src/main/java/io/github/longshou/mcyukkuri/entity/Behaviors.java"
    action_path = ROOT / "src/main/java/io/github/longshou/mcyukkuri/entity/YukkuriAction.java"
    for constant, clip in re.findall(r'\b([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"', java[behavior_path]):
        behaviour_constants.setdefault(clip, set()).add(constant)
    for constant, clip in re.findall(r'^\s*([A-Z][A-Z0-9_]*)\("([^"]+)"', java[action_path], re.MULTILINE):
        action_constants[clip] = constant
    direct_callers = direct_java_callers(java, set(clips))
    used_actions = {clip: set() for clip in clips}
    used_behaviours = {clip: set() for clip in clips}
    for path, text in java.items():
        if path != action_path:
            for clip, constant in action_constants.items():
                if f"YukkuriAction.{constant}" in text:
                    used_actions[clip].add(constant)
        if path != behavior_path:
            for clip, constants in behaviour_constants.items():
                if clip not in used_behaviours:
                    continue
                for constant in constants:
                    if f"Behaviors.{constant}" in text:
                        used_behaviours[clip].add(constant)

    if set(clips) != set(expected):
        errors.append(f"clip coverage mismatch: missing={sorted(set(expected) - set(clips))}, extra={sorted(set(clips) - set(expected))}")
    if len(gap_ids) != len(gaps):
        errors.append("duplicate gap id")
    behaviour_ids = [row["id"] for row in behaviours]
    if len(set(behaviour_ids)) != len(behaviour_ids):
        errors.append("duplicate wiki behaviour id")
    missing_required = REQUIRED_GAPS - gap_ids
    if missing_required:
        errors.append(f"missing required gaps: {sorted(missing_required)}")
    families = set(data["families"])

    for clip, row in clips.items():
        where = f"clip {clip}"
        if row.get("source") != expected.get(clip):
            errors.append(f"{where}: source {row.get('source')!r}, expected {expected.get(clip)!r}")
        if row.get("source") not in SOURCES:
            errors.append(f"{where}: invalid source {row.get('source')!r}")
        if row.get("tier") not in TIERS:
            errors.append(f"{where}: invalid tier {row.get('tier')!r}")
        if row.get("family") not in families:
            errors.append(f"{where}: invalid family {row.get('family')!r}")
        source = row.get("source")
        if source == "base" and row.get("tier") != "base":
            errors.append(f"{where}: base source must remain base tier")
        if source == "composite" and row.get("tier") != "composite":
            errors.append(f"{where}: composite source must remain composite tier")
        if source in {"procedural", "marisa_tsumuri", "anatomy"} and row.get("tier") != "special":
            errors.append(f"{where}: {source} must be special")
        if source != "lore" and "classification" in row:
            errors.append(f"{where}: classification is only for lore clips")
        classification = row.get("classification")
        if source == "lore":
            if not classification or classification.get("kind") not in CLASSIFICATIONS:
                errors.append(f"{where}: missing or invalid lore classification")
                continue
            kind = classification["kind"]
            if kind == "really_base" and row.get("tier") != "base":
                errors.append(f"{where}: really_base must have base tier")
            if kind == "really_composite":
                if row.get("tier") != "composite":
                    errors.append(f"{where}: really_composite must have composite tier")
                ingredients = classification.get("base_motions", [])
                if len(ingredients) < 2:
                    errors.append(f"{where}: really_composite needs at least two base_motions")
                for ingredient in ingredients:
                    target = clips.get(ingredient)
                    gap = next((gap for gap in gaps if gap["id"] == ingredient), None)
                    if target is None and gap is None:
                        errors.append(f"{where}: unknown base motion {ingredient}")
                    elif target is not None and target["tier"] != "base" or gap is not None and gap["tier"] != "base":
                        errors.append(f"{where}: composite ingredient {ingredient} is not base tier")
            if kind == "near_duplicate_of" and classification.get("target") not in clips:
                errors.append(f"{where}: unknown near-duplicate target {classification.get('target')!r}")
            if kind == "special":
                if row.get("tier") != "special":
                    errors.append(f"{where}: special classification must have special tier")
                if not classification.get("reason"):
                    errors.append(f"{where}: special classification needs a reason")
        for ingredient in row.get("base_motions", []):
            if ingredient not in clips and ingredient not in gap_ids:
                errors.append(f"{where}: unknown composite ingredient {ingredient}")
            if ingredient == clip:
                errors.append(f"{where}: composite references itself")
        callers = row.get("callers")
        if not isinstance(callers, list) or not callers:
            errors.append(f"{where}: callers must be a nonempty list or ['no caller']")
            continue
        if "no caller" in callers and callers != ["no caller"]:
            errors.append(f"{where}: no caller cannot be combined with callers")
        if len(callers) != len(set(callers)):
            errors.append(f"{where}: duplicate caller entry")
        for caller in callers:
            if caller == "no caller":
                continue
            if caller.startswith("moments:"):
                moment = caller.removeprefix("moments:")
                if moment not in moments:
                    errors.append(f"{where}: unknown moment {moment}")
                elif clip not in moments[moment]:
                    errors.append(f"{where}: moment {moment} does not use this clip")
            elif caller.startswith("java:Behaviors."):
                constant = caller.rsplit(".", 1)[-1]
                if constant not in behaviour_constants.get(clip, set()):
                    errors.append(f"{where}: Behaviors.{constant} does not name this clip")
                elif constant not in used_behaviours[clip]:
                    errors.append(f"{where}: Behaviors.{constant} has no use outside its declaration")
            elif caller.startswith("java:YukkuriAction."):
                constant = caller.rsplit(".", 1)[-1]
                if action_constants.get(clip) != constant:
                    errors.append(f"{where}: YukkuriAction.{constant} does not name this clip")
                elif constant not in used_actions[clip]:
                    errors.append(f"{where}: YukkuriAction.{constant} has no use outside its declaration")
            elif caller == f"java:playAction.{clip}":
                if not direct_callers[clip] & {"playAction", "PetScenes.act", "perform"}:
                    errors.append(f"{where}: no literal Java playAction/wrapper use found")
            elif caller == f"java:renderer.{clip}":
                needle = f'"{clip}"'
                if clip not in RENDERER_CALLERS or not any("client" in path.parts and needle in text for path, text in java.items()):
                    errors.append(f"{where}: no approved client renderer selection/sampling found")
            elif caller.startswith("java:"):
                custom = caller.removeprefix("java:")
                if custom not in CUSTOM_JAVA_CALLERS.get(clip, set()):
                    errors.append(f"{where}: unrecognized Java caller {caller!r}")
                    continue
                class_name, _, member = custom.partition(".")
                candidates = [(path, text) for path, text in java.items() if path.stem == class_name]
                if not candidates:
                    errors.append(f"{where}: Java class {class_name} not found")
                    continue
                for path, text in candidates:
                    if member and member not in text:
                        errors.append(f"{where}: {class_name}.{member} not found")
                    action_reference = clip in action_constants and f"YukkuriAction.{action_constants[clip]}" in text
                    behaviour_reference = any(f"Behaviors.{constant}" in text for constant in behaviour_constants.get(clip, set()))
                    if f'"{clip}"' not in text and not action_reference and not behaviour_reference:
                        errors.append(f"{where}: {class_name} has no reference to this clip")
            else:
                errors.append(f"{where}: invalid caller {caller!r}")
        java_evidence = (
            bool(direct_callers[clip]) or bool(used_actions[clip]) or bool(used_behaviours[clip])
            or bool(CUSTOM_JAVA_CALLERS.get(clip)) or clip in RENDERER_CALLERS
        )
        if callers == ["no caller"] and (java_evidence or any(clip in used for used in moments.values())):
            errors.append(f"{where}: no caller contradicts Java or moments evidence")

        expected_moments = {f"moments:{moment}" for moment, used in moments.items() if clip in used}
        missing_moments = expected_moments - set(callers)
        if missing_moments:
            errors.append(f"{where}: missing moments callers {sorted(missing_moments)}")
        if direct_callers[clip] & {"playAction", "PetScenes.act", "perform"} and f"java:playAction.{clip}" not in callers:
            errors.append(f"{where}: missing java:playAction.{clip} for {sorted(direct_callers[clip])}")
        for constant in used_actions[clip]:
            if f"java:YukkuriAction.{constant}" not in callers:
                errors.append(f"{where}: missing java:YukkuriAction.{constant}")
        for constant in used_behaviours[clip]:
            if f"java:Behaviors.{constant}" not in callers:
                errors.append(f"{where}: missing java:Behaviors.{constant}")
        for custom in CUSTOM_JAVA_CALLERS.get(clip, set()):
            if f"java:{custom}" not in callers:
                errors.append(f"{where}: missing java:{custom}")
        if clip in RENDERER_CALLERS and f"java:renderer.{clip}" not in callers:
            errors.append(f"{where}: missing java:renderer.{clip}")

    for row in behaviours:
        where = f"wiki behaviour {row.get('id')}"
        if row.get("tier") not in TIERS:
            errors.append(f"{where}: invalid tier {row.get('tier')!r}")
        status = row.get("status")
        if status not in STATUSES:
            errors.append(f"{where}: invalid status {status!r}")
        if not row.get("behaviour") or not row.get("source_ids"):
            errors.append(f"{where}: behaviour and source_ids are required")
        for source in row.get("source_ids", []):
            if source not in source_ids:
                errors.append(f"{where}: unknown source id {source}")
        for clip in row.get("clips", []):
            if clip not in known_clips:
                errors.append(f"{where}: unknown clip {clip}")
        for gap in row.get("gaps", []):
            if gap not in gap_ids:
                errors.append(f"{where}: unknown gap {gap}")
        if status in {"exists", "composable_from_existing_base"} and not row.get("clips"):
            errors.append(f"{where}: {status} needs existing clips")
        if status == "base_missing" and not row.get("gaps"):
            errors.append(f"{where}: base_missing needs gap references")
        if status == "needs_special" and not row.get("missing"):
            errors.append(f"{where}: needs_special needs a missing description")

    for row in gaps:
        where = f"gap {row.get('id')}"
        if row.get("tier") not in {"base", "composite"}:
            errors.append(f"{where}: gap tier must be base or composite")
        if not row.get("name_zh") or not row.get("missing"):
            errors.append(f"{where}: name_zh and missing are required")
        for clip in row.get("existing", []):
            if clip not in known_clips and clip not in gap_ids:
                errors.append(f"{where}: unknown existing ingredient {clip}")
            if clip == row.get("id"):
                errors.append(f"{where}: gap references itself")

    tier_counts = Counter(row["tier"] for row in clips.values())
    status_counts = Counter(row["status"] for row in behaviours)
    source_counts = Counter(row["source"] for row in clips.values())
    class_counts = Counter(row["classification"]["kind"] for row in clips.values() if row["source"] == "lore")
    no_callers = [clip for clip, row in clips.items() if row["callers"] == ["no caller"]]
    if errors:
        for error in errors:
            print(f"FAIL motion tiers: {error}")
        print(f"FAIL motion tiers: {len(errors)} error(s)")
        return 1
    print(
        f"PASS motion tiers: {len(clips)} clips "
        f"(base={tier_counts['base']}, composite={tier_counts['composite']}, special={tier_counts['special']}); "
        f"sources base={source_counts['base']}, composite={source_counts['composite']}, lore={source_counts['lore']}, "
        f"procedural={source_counts['procedural']}, marisa_tsumuri={source_counts['marisa_tsumuri']}, anatomy={source_counts['anatomy']}"
    )
    print(
        f"PASS motion tiers: {len(behaviours)} wiki behaviours "
        f"(exists={status_counts['exists']}, composable_from_existing_base={status_counts['composable_from_existing_base']}, "
        f"base_missing={status_counts['base_missing']}, needs_special={status_counts['needs_special']}); "
        f"{len(gaps)} gaps (base={sum(row['tier'] == 'base' for row in gaps)}, composite={sum(row['tier'] == 'composite' for row in gaps)})"
    )
    print(
        f"PASS motion tiers: {len(action_ids)} YukkuriAction entries covered; lore classifications "
        f"really_base={class_counts['really_base']}, really_composite={class_counts['really_composite']}, "
        f"near_duplicate_of={class_counts['near_duplicate_of']}, special={class_counts['special']}; "
        f"no Java/moments caller={len(no_callers)}"
    )
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="validate without writing any files")
    args = parser.parse_args()
    if not args.check:
        parser.error("use --check")
    try:
        return validate()
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"FAIL motion tiers: {exc}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
