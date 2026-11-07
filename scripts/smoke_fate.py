#!/usr/bin/env python
"""Actor fate check of a smoke window: no yukkuri may die, drown, suffocate in a block, starve or loop on one refusal
unless its scene declares that outcome (``smoke['allow_fate']``). Every pattern is a line the mod prints through
``status/GameplayLog``; ``python scripts/smoke_fate.py [--scene ID] LOG ...`` re-checks saved scene logs."""
import argparse
import hashlib
import re
import sys
from pathlib import Path

sys.dont_write_bytecode = True

# GameplayLog.event: "[category] <variant> #<id> uuid=<uuid> t=<tick> dim=<dim> pos=<x, y, z> <message>"
EVENT = re.compile(r'\[(?P<cat>[\w-]+)\] (?P<variant>\S+) #(?P<id>\d+) uuid=(?P<uuid>[0-9a-f-]{36}) t=\d+ dim=\S+ '
                   r'pos=-?\d+, -?\d+, -?\d+ (?P<msg>[^\n]*)')
# kind -> (category, message pattern) of the lines that show it
FATES = {
    # body/BodyLife.died; body/BodyLife.startDying (the core is hurt: death follows without a way back)
    'death': (('body', r'^death settled into remains; cause=(?P<cause>\S+)'), ('body', r'^core hurt \((?P<cause>[^)]*)\), dying for ')),
    # entity/YukkuriDamage: health lost to immersion (life/YukkuriLife hurts every 20 ticks in water, 60 for aquatic bodies)
    'drown': (('water', r'^damage cause=drown\b'),),
    # entity/YukkuriDamage (first hit, then rate-limited); a newborn's cry (entity/YukkuriEntity.startHurtCry); a death by it
    'in_wall': (('body', r'^in wall damage\b'), ('childhood', r'^hurt cry start cause=inWall\b'),
                ('body', r'^death settled into remains; cause=inWall\b')),
    # life/YukkuriLife.tick: nutrition at zero for the starvation interval
    'starve': (('needs', r'^starvation damage accepted=true\b'),),
}
KINDS = (*FATES, 'loop')
# A refusal, block or failed attempt; GameplayLog.limited folds its repeats into "(suppressed=N)" on the next line.
REFUSAL = re.compile(r'\b(?:refused|rejected|unavailable|blocked|failed|gave up|abandoned)\b'
                     r'|\boutcome=(?:BLOCKED|INCAPABLE|LOST)\b|\bresult=(?:EXHAUSTED|LOST)\b')
SUPPRESSED = re.compile(r' \(suppressed=(\d+)\)')
# Same entity, same refusal and reason, counted with its suppressed repeats. In 1615 saved runs of the smoke scenes
# (.local/smoke, 2026-11) the largest such count of a run was <= 29 in 75% of runs and below 400 in 93%; the runs at or
# above it re-asked one refusal every tick or faster ("hop shore stand refused reason=hop_arc" up to 18840 times,
# "contact stand unavailable" up to 1322): an actor that did not change its condition. 400 is 20 s of one refusal a tick.
LOOP_BOUND = 400
CHECKER = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()[:12]


def signature(text):
    """Stable text of a line or reason: identities, positions and numbers replaced, the rate-limit suffix removed."""
    text = SUPPRESSED.sub('', text)
    text = re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', 'U', text)
    text = re.sub(r'BlockPos\{[^}]*\}|AABB\[[^\]]*\]|\((?:-?[\d.]+(?:E-?\d+)?, ?){2}-?[\d.]+(?:E-?\d+)?\)', 'P', text)
    return re.sub(r'-?\d+(?:\.\d+)?(?:E-?\d+)?', 'N', text)


def check_allowances(allow):
    """A scene's declarations: kind, who (a pattern of a line the allowed actor itself prints, None for any actor),
    match (a pattern the fate line must match, e.g. its cause), why."""
    for entry in allow:
        unknown = set(entry) - {'kind', 'who', 'match', 'why'}
        if entry.get('kind') not in KINDS or unknown or not entry.get('why'):
            raise ValueError(f'Bad allow_fate entry {entry}: kind one of {KINDS}, optional who/match patterns, a why')


def fates(window, allow=()):
    """[(reason key, text)] for every actor fate in the window that its scene has not declared."""
    found = {}
    loops = {}
    for line in window.splitlines():
        event = EVENT.search(line)
        if event is None:
            continue
        for kind, shapes in FATES.items():
            for category, pattern in shapes:
                match = re.search(pattern, event['msg']) if event['cat'] == category else None
                if match:
                    row = found.setdefault((kind, event['uuid']), dict(event=event, line=line, count=0,
                                                                       cause=match.groupdict().get('cause')))
                    row['count'] += 1
                    break
        if REFUSAL.search(event['msg']):
            suppressed = SUPPRESSED.search(event['msg'])
            row = loops.setdefault((event['uuid'], event['cat'], signature(event['msg'])), dict(event=event, line=line, count=0))
            row['count'] += 1 + (int(suppressed[1]) if suppressed else 0)
    for (uuid, category, text), row in loops.items():
        if row['count'] >= LOOP_BOUND:
            found[('loop', uuid, category, text)] = row
    actors = {}
    for entry in allow:
        if entry.get('who') is not None and entry['who'] not in actors:
            actors[entry['who']] = {event['uuid'] for line in window.splitlines()
                                    if re.search(entry['who'], line) and (event := EVENT.search(line))}
    problems = []
    for key, row in found.items():
        kind, event = key[0], row['event']
        if any(entry['kind'] == kind and (entry.get('who') is None or event['uuid'] in actors[entry['who']])
               and (entry.get('match') is None or re.search(entry['match'], row['line'])) for entry in allow):
            continue
        detail = (f'cause={row["cause"]}' if kind == 'death' else f'[{key[2]}] {key[3][:140]}' if kind == 'loop' else '')
        reason = f'fate {kind}: {event["variant"]} {detail}'.rstrip()
        text = row['line'][row['line'].find(f'[{event["cat"]}]'):].strip()
        problems.append((reason, f'fate {kind}: {text}' if kind == 'death' else f'fate {kind} ({row["count"]}x): {text}'))
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scene', help="Apply this scene's allow_fate declarations")
    parser.add_argument('logs', nargs='+', type=Path)
    args = parser.parse_args()
    allow = ()
    if args.scene:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import museum_scenarios
        allow = next(s for s in museum_scenarios.S if s.id == args.scene).smoke.get('allow_fate', ())
        check_allowances(allow)
    failed = False
    for log in args.logs:
        problems = fates(log.read_text(encoding='utf-8', errors='replace'), allow)
        failed |= bool(problems)
        print(f'{log}: {len(problems)} fate problem(s)')
        for _, text in problems:
            print('  ' + text)
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
