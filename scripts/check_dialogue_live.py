"""Find dialogue events that no Java code can cue any more (read-only).

Every event in the generated catalog must be reachable from `src/main/java`: either the exact string literal appears
outside `NativeDialogue.java` (whose priority table alone does not cue anything), or the event extends a literal that the
code builds ids from — a literal followed by `+` (`"narrate_" + kind`) or a `static final String` constant (`count_food`
then `_3`). Dead events rot: delete their lines and voices through the dialogue exporters, or wire the cue back.

Run: python scripts/check_dialogue_live.py --check
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'src/main/resources/assets/mcyukkuri/dialogue/catalog.json'
JAVA = ROOT / 'src/main/java'
PRIORITY_TABLE = 'NativeDialogue.java'
LITERAL = re.compile(r'"([a-z][a-z0-9_]*)"')
CONCAT = re.compile(r'"([a-z][a-z0-9_]*)"\s*\+')
CONSTANT = re.compile(r'static\s+final\s+String\s+[^;]*?=\s*"([a-z][a-z0-9_]*)"')
CONSTANT_LIST = re.compile(r'static\s+final\s+String\s+([^;]+);')
SPEAKING = re.compile(r'\b(speak\w*|say\w*|showReaction\w*|act|cue\w*|line)\s*\(', re.IGNORECASE)


def catalog_events():
    events = set()
    for entry in json.loads(CATALOG.read_text(encoding='utf-8')):
        value = entry.get('events') or entry.get('event') or []
        events.update([value] if isinstance(value, str) else value)
    return events


def java_literals():
    exact, prefixes = set(), set()
    for path in JAVA.rglob('*.java'):
        if path.name == PRIORITY_TABLE:
            continue
        text = path.read_text(encoding='utf-8')
        exact.update(LITERAL.findall(text))
        # Built ids count only where speech is built: a concatenation on a speaking line, or a constant in the dialogue package
        # (log tags such as "family_" + kind would otherwise keep every family_* event alive).
        for line in text.splitlines():
            if SPEAKING.search(line):
                prefixes.update(CONCAT.findall(line))
        if path.parent.name == 'dialogue':
            prefixes.update(CONSTANT.findall(text))
            for declaration in CONSTANT_LIST.findall(text):  # `String FOOD = "count_food", STOCK = "count_stock"`
                prefixes.update(LITERAL.findall(declaration))
    return exact, prefixes


def dead_events():
    exact, prefixes = java_literals()
    def live(event):
        return event in exact or any(
            (p.endswith('_') and event.startswith(p)) or event.startswith(p + '_') for p in prefixes)
    return sorted(e for e in catalog_events() if not live(e))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--check', action='store_true', help='exit non-zero when a dead event exists')
    args = parser.parse_args()
    dead = dead_events()
    for event in dead:
        print(f'dead dialogue event: {event} (no cue in src/main/java outside {PRIORITY_TABLE})')
    if not dead:
        print('dialogue events: all reachable')
    return 1 if args.check and dead else 0


if __name__ == '__main__':
    sys.exit(main())
