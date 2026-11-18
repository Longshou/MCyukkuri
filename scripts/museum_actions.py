"""Read-only action catalogue shared by the museum map generator and the in-game viewer."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
ACTION_SOURCE = ROOT / 'src/main/java/io/github/longshou/mcyukkuri/entity/YukkuriAction.java'
ACTION_META = {a: (float(d), loop == 'true') for a, d, loop in re.findall(
    r'^\s+[A-Z_0-9]+\("([a-z_0-9]+)", ([\d.]+), (true|false)\)', ACTION_SOURCE.read_text(), re.M)}
CATALOGUE = json.loads((ASSETS / 'yukkuri_presentation/viewer_catalog.json').read_text(encoding='utf-8'))
CATEGORIES = [(family['title'], [(clip, name) for clip, name in family['clips']]) for family in CATALOGUE['families']]
NAMES = dict(pair for _, entries in CATEGORIES for pair in entries)
assert set(NAMES) == set(ACTION_META), 'Update the catalogue when registered actions change'
MODELS = CATALOGUE['models']
assert set(MODELS) == set(json.loads((ASSETS / 'yukkuri_presentation/face_library.json').read_text(encoding='utf-8'))['models'])

