#!/usr/bin/env python
"""Actor fate check of a smoke window: no yukkuri may die, drown, suffocate in a block, starve or loop on one refusal
unless its scene declares that outcome (``smoke['allow_fate']``). Every pattern is a line the mod prints through
``status/GameplayLog``; ``python scripts/smoke_fate.py [--scene ID] LOG ...`` re-checks saved scene logs."""
import argparse
import hashlib
import re
import sys
import json
import math
import subprocess
from collections import defaultdict, Counter
from pathlib import Path

import timeline

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


def fate_main():
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
        try:
            window = log.read_text(encoding='utf-8')
        except (OSError, UnicodeError) as error:
            failed = True
            print(f'{log}: FAIL unreadable log: {error}', file=sys.stderr)
            continue
        if not window.strip():
            failed = True
            print(f'{log}: FAIL empty log')
            continue
        problems = fates(window, allow)
        failed |= bool(problems)
        print(f'{log}: {len(problems)} fate problem(s)')
        for _, text in problems:
            print('  ' + text)
    return 1 if failed else 0


# Hypothesis bands below are the reviewed observation-layer design §2. They are report-only.
# Frozen tolerances (spawn drop, ledger offset, fate) retain the design's exact values.
CHECKS = ('FATE', 'LOOP', 'PROGRESS', 'JUMP', 'LEDGER', 'ORPHAN', 'END', 'LAG', 'SPAWN', 'VANTAGE')
RANK = {'pass': 0, 'no-evidence': 1, 'suspect': 2, 'fail': 3}


def distance(a, b):
    return math.dist(a, b)


def band(value, suspect, fail):
    return 'fail' if value > fail else 'suspect' if value >= suspect else 'pass'


def result(check, verdict, detail, row=None, **values):
    return dict(check=check, verdict=verdict, detail=detail, actor=row['u'] if row else None,
                t=row['t'] if row else None, **values)


def known(state, *fields):
    return all(field in state and state[field] is not None for field in fields)


def changed(a, b):
    """Observed progress, never merely an action-id change (which could itself be a loop)."""
    if distance(a['p'], b['p']) >= .5:
        return True
    if any(a[k] != b[k] for k in ('int', 'goals', 'phase', 'hold')):
        return True
    intent = str(b['int']).lower()
    for key, verbs in (('nut', ('eat', 'meal', 'feed')), ('en', ('sleep', 'rest'))):
        if any(verb in intent for verb in verbs):
            if a['need'].get(key) is not None and b['need'].get(key) is not None and b['need'][key] > a['need'][key]:
                return True
    return False


def progress_known(s):
    return known(s, 'p', 'goals', 'phase', 'hold', 'need') and 'int' in s


def is_prop(s):
    return 'item' in s or 'riders' in s


PROGRESS_FIELDS = ('p', 'int', 'goals', 'phase', 'hold', 'need')


def progress(rows):
    out = []
    for actor, samples in actor_samples(rows).items():
        if is_prop(samples[0]['state']):
            continue
        valid = [r for r in samples if progress_known(r['state']) and 'act' in r['state']]
        if len(valid) != len(samples) or len(valid) < 2:
            out.append(result('PROGRESS', 'no-evidence', 'need position, intent, goals, action, phase, holders and needs', samples[-1]))
            continue
        anchor = valid[0]
        for row in valid[1:]:
            s, a = row['state'], anchor['state']
            action = s['act'] or {}
            active = bool(s['int']) or any(str(g).lower() not in ('idle', 'look', 'random_look') for g in s['goals'])
            if not active or changed(a, s) or (action.get('deadline') is not None and row['t'] <= action['deadline']):
                anchor = row
                continue
            window = 5 * action['win'] if action.get('win') else 600
            age = row['t'] - anchor['t']
            if age >= window:
                out.append(result('PROGRESS', band(age, window, 2 * window), 'no observed progress', row, duration=age, window=window))
        # Dense stop/go and yaw reversal cycles, in rolling 100-tick windows (5 seconds).
        transitions, reversals, prev_moving, prev_turn = [], [], None, 0
        for a, b in zip(valid, valid[1:]):
            if b['t'] - a['t'] != 1:
                prev_moving, prev_turn = None, 0
                continue
            moving = distance(a['state']['p'], b['state']['p']) > .01 + 1e-9
            if prev_moving is not None and moving != prev_moving:
                transitions.append(b['t'])
            prev_moving = moving
            if known(a['state'], 'yaw') and known(b['state'], 'yaw'):
                turn = (b['state']['yaw'] - a['state']['yaw'] + 180) % 360 - 180
                if turn * prev_turn < 0:
                    reversals.append(b['t'])
                if abs(turn) > 1:
                    prev_turn = turn
            cycles = max(sum(t > b['t'] - 100 for t in transitions) // 2,
                         sum(t > b['t'] - 100 for t in reversals) // 2)
            if cycles >= 3:
                out.append(result('PROGRESS', band(cycles, 3, 5), 'dense stutter cycles', b, cycles=cycles))
    return out


def actor_samples(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[row['u']].append(row)
    return groups


def loops(records, samples):
    out, buckets = [], defaultdict(list)
    actors = actor_samples(samples)
    for row in records:
        if row['k'] != 'ev':
            continue
        msg = row.get('msg', '')
        if not (REFUSAL.search(msg) or row.get('cat') == 'action' and msg.startswith('end ')):
            continue
        if not known(row, 'pos', 'u'):
            out.append(result('LOOP', 'no-evidence', 'event has no actor or block position', row))
            continue
        key = row['u'], row.get('cat'), signature(msg)
        bucket = buckets[key]
        # Count only repeats at the same block ±1 with no observed progress in between.
        prior = bucket[-1][0] if bucket else None
        between = [s for s in actors[row['u']] if prior and prior['t'] <= s['t'] <= row['t']]
        enough = len(between) >= 2 and all(progress_known(s['state']) for s in between)
        if prior and (distance(prior['pos'], row['pos']) > math.sqrt(3) or enough and changed(between[0]['state'], between[-1]['state'])):
            bucket.clear()
        bucket.append((row, 1 + row.get('sup', 0)))
        count = sum(n for r, n in bucket if r['t'] > row['t'] - 1200)
        if count >= 5:
            out.append(result('LOOP', band(count, 5, 20) if enough else 'no-evidence',
                              'repeated event without observed progress' if enough else 'repeats lack progress samples', row, repeats=count))
    for actor, rows in actors.items():
        flips = []
        values = []
        for row in rows:
            s = row['state']
            if not known(s, 'att', 'hold') or 'veh' not in s:
                continue
            value = (s['att'], s['veh'], s['hold'].get('carry'))
            if not values or values[-1][1] != value:
                values.append((row, value))
                if len(values) >= 3 and values[-1][1] == values[-3][1]:
                    flips.append(row['t'])
                    count = sum(t > row['t'] - 1200 for t in flips)
                    if count >= 3:
                        out.append(result('LOOP', band(count, 3, 5), 'attachment/holder A-B-A flips', row, flips=count))
    return out


def local_point(p, frame):
    """Inverse host frame: translate, inverse yaw, pitch, roll; units convert spot to blocks."""
    x, y, z = [p[i] - frame['origin'][i] for i in range(3)]
    yaw = frame['yaw']  # HostFrame yaw is radians; roll/pitch are degrees.
    pitch, roll = [math.radians(frame[k]) for k in ('pitch', 'roll')]
    x, z = math.cos(yaw) * x - math.sin(yaw) * z, math.sin(yaw) * x + math.cos(yaw) * z
    x, y = math.cos(roll) * x + math.sin(roll) * y, -math.sin(roll) * x + math.cos(roll) * y
    y, z = math.cos(pitch) * y + math.sin(pitch) * z, -math.sin(pitch) * y + math.cos(pitch) * z
    return [v / frame['unit'] for v in (x, y, z)]


def jumps(samples):
    out = []
    by_tick = {(r['u'], r['t']): r['state'] for r in samples}
    for rows in actor_samples(samples).values():
        pairs = 0
        for a, b in zip(rows, rows[1:]):
            sa, sb = a['state'], b['state']
            if b['t'] - a['t'] != 1 or not known(sa, 'p', 'v', 'att', 'g') or not known(sb, 'p', 'att', 'g'):
                continue
            pairs += 1
            aa, ab = sa['att'], sb['att']
            host = aa.get('host')
            if host and host == ab.get('host'):
                if aa.get('frame') and ab.get('frame'):
                    r = distance(local_point(sa['p'], aa['frame']), local_point(sb['p'], ab['frame'])) * ab['frame']['unit']
                elif (host, a['t']) in by_tick and (host, b['t']) in by_tick:
                    pa, pb = by_tick[host, a['t']]['p'], by_tick[host, b['t']]['p']
                    r = distance([sa['p'][i] - pa[i] for i in range(3)], [sb['p'][i] - pb[i] for i in range(3)])
                else:
                    out.append(result('JUMP', 'no-evidence', 'host frame/carrier positions missing', b))
                    continue
            else:
                residual = [sb['p'][i] - sa['p'][i] - sa['v'][i] for i in range(3)]
                if sa['g'] or sb['g']:
                    residual[1] = math.copysign(max(0, abs(residual[1]) - .6), residual[1])
                r = math.sqrt(sum(v * v for v in residual))
            if r > .25:
                out.append(result('JUMP', band(r, .25, 1), 'motion residual (blocks)', b, residual=r))
        if not pairs:
            out.append(result('JUMP', 'no-evidence', 'no adjacent samples with velocity/attachment', rows[-1]))
    return out


def inventory(s):
    total = Counter()
    def add(value):
        if isinstance(value, list):
            for child in value:
                add(child)
        elif isinstance(value, dict):
            if value.get('item') and value.get('n'):
                total[value['item']] += value['n']
            else:
                for child in value.values():
                    add(child)
    if 'item' in s:
        add(s)
    elif 'hold' in s:
        if s['hold'] is None:
            return None
        # Hat ownership state is not a second copy of the physical hat's inventory.
        for key in ('braid', 'mouth', 'kit'):
            add(s['hold'].get(key))
    elif 'kit' in s:
        add(s['kit'])
    else:
        return None
    return total


def ledger(records, samples, dense=False):
    out, ticks, current = [], defaultdict(list), {}
    explanations = [r for r in records if r['k'] == 'ev' and re.search(r'\b(eat|ate|swallow|consum|place|pickup|interaction)', r.get('msg', ''), re.I)
                    or r['k'] == 'life' and r.get('out') in ('unloaded', 'scope_exit', 'changed_dimension')]
    for row in records:
        if row['k'] == 'life':
            ticks[row['t']].append(row)
    for row in samples:
        ticks[row['t']].append(row)
    initial_tick = min((r['t'] for r in samples), default=None)
    final_tick = max((r['t'] for r in samples), default=None)
    for rows in actor_samples(samples).values():
        first = rows[0]
        last = rows[-1]
        if dense and last['t'] < final_tick and 'stage' in last['state']:
            exits = [r for r in records if r['k'] == 'life' and r['u'] == last['u'] and r.get('out')
                     and 0 <= r['t'] - last['t'] <= 2]
            if not exits:
                out.append(result('LEDGER', 'fail', 'dense actor disappeared without lifecycle exit', last))
        if first['t'] != initial_tick and 'stage' in first['state']:
            entries = [r for r in records if r['k'] == 'life' and r['u'] == first['u']
                       and r.get('in') in ('spawn', 'birth', 'chunk_load', 'scope_enter') and 0 <= first['t'] - r['t'] <= 2]
            if not entries:
                out.append(result('LEDGER', 'fail', 'actor appeared without lifecycle entry', first))
    previous, dip = None, None
    for tick, rows in sorted(ticks.items()):
        for row in rows:
            if row['k'] == 'life' and row.get('out'):
                current.pop(row['u'], None)
            elif row['k'] == 'st':
                current[row['u']] = inventory(row['state'])
                mouth = (row['state'].get('hold') or {}).get('mouth') or []
                if sum(bool(store) for store in mouth) > 1:
                    out.append(result('LEDGER', 'suspect', 'two non-empty mouth stores', row))
        if not current or any(v is None for v in current.values()):
            out.append(result('LEDGER', 'no-evidence', 'inventory authority missing', rows[-1]))
            continue
        total = sum(current.values(), Counter())
        explained = any(abs(r['t'] - tick) <= 2 for r in explanations)
        if previous is not None and total != previous and not explained and dip is None:
            dip = (tick, previous, rows[-1])
        if dip and total == dip[1]:
            duration = tick - dip[0]
            if duration >= 3:
                out.append(result('LEDGER', 'fail' if duration > 20 else 'suspect', 'unexplained inventory dip/appearance', dip[2], duration=duration))
            dip = None
        previous = total
    if dip:
        out.append(result('LEDGER', 'fail', 'unexplained permanent item total change', dip[2]))
    if len(ticks) < 2:
        out.append(result('LEDGER', 'no-evidence', 'need at least two inventory snapshots'))
    return out


def orphans(samples):
    out, all_ticks = [], defaultdict(dict)
    for r in samples:
        all_ticks[r['t']][r['u']] = r['state']
    for rows in actor_samples(samples).values():
        unsafe_since = falling_since = None
        if is_prop(rows[0]['state']):
            continue
        for r in rows:
            s = r['state']
            if not known(s, 'dep'):
                out.append(result('ORPHAN', 'no-evidence', 'dependent status missing', r))
                continue
            if not s['dep']:
                continue
            if not known(s, 'p', 'parents', 'distress', 'g', 'att', 'iwr', 'v') or 'veh' not in s:
                out.append(result('ORPHAN', 'no-evidence', 'unsafe state or parent identity missing', r))
                continue
            falling = not s['g'] and not s['att'].get('host') and not s['veh'] and s['v'][1] < -.3
            falling_since = (r['t'] if falling_since is None else falling_since) if falling else None
            unsafe = s['distress'] or s['iwr'] or falling and r['t'] - falling_since >= 20
            parents = s['parents'].values() if isinstance(s['parents'], dict) else s['parents']
            carers = [a for u, a in all_ticks[r['t']].items() if u in parents or
                      any(c['child'] == r['u'] for c in a.get('hold', {}).get('carry', []))]
            if any(not known(a, 'p') for a in carers):
                out.append(result('ORPHAN', 'no-evidence', 'carer position missing', r))
                continue
            dist = min((distance(s['p'], a['p']) for a in carers), default=math.inf)
            abandoned = unsafe and dist >= 4
            unsafe_since = (r['t'] if unsafe_since is None else unsafe_since) if abandoned else None
            if unsafe_since is not None:
                age = r['t'] - unsafe_since
                if age >= 200:
                    out.append(result('ORPHAN', 'fail' if age > 600 and dist > 16 else 'suspect', 'unsafe dependent without nearby carer', r, duration=age))
            if r is rows[-1] and unsafe:
                out.append(result('ORPHAN', 'fail', 'dependent unsafe at window end', r))
    return out


def predicate(s, text):
    for clause in text.split(' and '):
        match = re.fullmatch(r'([\w.]+)\s*(==|!=|>=|<=|=|>|<)\s*(.+)', clause)
        if not match:
            raise ValueError(f'Invalid END clause: {clause}')
        field, op, raw = match.groups()
        value = s
        if field == 'place':
            value = s.get('att', {}).get('place')
        elif field == 'veh' and 'veh' in s:
            value = bool(s['veh'])
        elif field == 'alive' and 'hp' in s:
            value = s.get('alive', s['hp'] > 0)
        else:
            for part in field.split('.'):
                value = value.get(part) if isinstance(value, dict) else None
        if value is None:
            return None
        try:
            expected = json.loads(raw)
        except ValueError:
            expected = raw
        if not {'=': lambda: value == expected, '==': lambda: value == expected, '!=': lambda: value != expected,
                '>': lambda: value > expected, '<': lambda: value < expected, '>=': lambda: value >= expected,
                '<=': lambda: value <= expected}[op]():
            return False
    return True


def endings(records, samples, end):
    if not end:
        return [result('END', 'no-evidence', 'scene has no end declaration')]
    out = []
    names = defaultdict(set)
    for r in records:
        if r['k'] == 'geo':
            for tag in r.get('tags', []):
                names[tag].add(r['u'])
    for subject, pred in end:
        named = names[f'scn_{subject}'] | {r['u'] for r in samples if f'scn_{subject}' in r['state'].get('tags', []) or r['state'].get('name') == subject}
        if subject.startswith('child_of:'):
            parents = names[f'scn_{subject[9:]}'] | {r['u'] for r in samples if f'scn_{subject[9:]}' in r['state'].get('tags', []) or r['state'].get('name') == subject[9:]}
            named = {r['u'] for r in samples if parents.intersection(
                r['state']['parents'].values() if isinstance(r['state'].get('parents'), dict) else r['state'].get('parents', []))}
        rows = [r for r in samples if r['u'] in named]
        if not rows:
            out.append(result('END', 'no-evidence', f'no samples for subject {subject}'))
            continue
        for actor, group in actor_samples(rows).items():
            last = group[-1]
            if pred.startswith('seen:'):
                _, cat, pattern = pred.split(':', 2)
                hits = any(r['u'] == actor and r['k'] == 'ev' and r.get('cat') == cat and re.search(pattern, r.get('msg', '')) for r in records)
                out.append(result('END', 'pass' if hits else 'fail', pred, last))
                continue
            boundary = last['t'] - 99
            preceding = [r for r in group if r['t'] <= boundary]
            tail = (preceding[-1:] if preceding else []) + [r for r in group if r['t'] > boundary]
            values = [predicate(r['state'], pred) for r in tail]
            departed = any(r['k'] == 'life' and r['u'] == actor and r.get('out') and r['t'] >= last['t'] for r in records)
            death = next((r for r in records if r['k'] == 'life' and r['u'] == actor and r.get('out') == 'killed' and r['t'] >= last['t']), None)
            final_tick = max(r['t'] for r in records)
            explicitly_dead = pred.replace(' ', '') in ('alive=false', 'alive==false')
            if death and explicitly_dead:
                verdict = 'pass' if final_tick - death['t'] >= 79 else 'fail'
            elif departed:
                verdict = 'fail'
            elif None in values or not preceding:
                verdict = 'no-evidence'
            else:
                # Time-weight the last 100 ticks, not the number of light-mode samples.
                weights = [b['t'] - max(boundary, a['t']) for a, b in zip(tail, tail[1:])] + [1]
                share = sum(w for w, v in zip(weights, values) if v) / sum(weights)
                verdict = 'pass' if values[-1] and share >= .8 else 'fail'
            out.append(result('END', verdict, f'{subject}: {pred}', last))
    return out


def observations(records, issues=(), raw='', allow=(), end=(), seed=None, rerun=None, evidence='F', dense=False):
    """One scope only. New checks NEVER determine the smoke exit code; fates() alone keeps that gate."""
    samples = timeline.states(records)
    findings = []
    for reason, text in fates(raw, allow):
        event = EVENT.search(text)
        actor = dict(u=event['uuid'], t=int(re.search(r'\bt=(\d+)', text)[1])) if event else None
        findings.append(result('FATE' if not reason.startswith('fate loop') else 'LOOP', 'fail', text, actor))
    if not any(EVENT.search(line) for line in raw.splitlines()):
        findings.append(result('FATE', 'no-evidence', 'no legacy fate log supplied'))
    for r in records:
        if r['k'] == 'ev' and re.search(r'damage .*cause=', r.get('msg', '')):
            cause = re.search(r'cause=(\w+)', r['msg'])[1]
            if cause not in ('drown', 'inWall'):
                findings.append(result('FATE', 'fail' if cause in ('fall', 'soaking', 'starve') else 'suspect', r['msg'], r))
    findings += progress(samples) + loops(records, samples) + jumps(samples) + ledger(records, samples, dense) + orphans(samples)
    findings += endings(records, samples, end)
    lag = [r for r in records if r['k'] == 'lag']
    for r in lag:
        if 'stall_ms' in r and r['stall_ms'] > 5000:
            findings.append(result('LAG', 'fail', 'server stall', r, stack=r.get('stack'), ms=r['stall_ms']))
        elif 'ms' in r and r['ms'] > 50:
            findings.append(result('LAG', band(r['ms'], 50, 500), 'tick work (ms)', r, ms=r['ms'], recorder_ms=r.get('rec_ms')))
    geo = [r for r in records if r['k'] == 'geo']
    starts = [r['t'] for r in records if r['k'] == 'win']
    start = min(starts) if starts else None
    for r in geo:
        if r.get('player'):
            # SceneGeometry encodes a centre outside the frustum as scr=0.
            if not known(r, 'vis', 'scr'):
                findings.append(result('VANTAGE', 'no-evidence', 'visibility/screen share missing', r))
            elif r['vis'] < 5 or r['scr'] < .08:
                findings.append(result('VANTAGE', 'suspect', 'subject poorly visible from vantage', r))
        if start is not None and r['t'] - start in (1, 20):
            if not known(r, 'clear', 'drop'):
                findings.append(result('SPAWN', 'no-evidence', 'geometry clear/drop missing', r))
            elif not r['clear'] or r['drop'] > .0625:
                findings.append(result('SPAWN', 'fail', 'spawn obstructed/unsupported', r, drop=r['drop']))
    if start is None or not all(any(r['t'] == start + t for r in geo) for t in (1, 20)):
        findings.append(result('SPAWN', 'no-evidence', 'need window start and geometry at +1/+20 ticks'))
    if not any(r.get('player') and known(r, 'vis', 'scr') for r in geo):
        findings.append(result('VANTAGE', 'no-evidence', 'no vantage geometry'))
    first = [group[0] for group in actor_samples(samples).values() if not is_prop(group[0]['state'])]
    if not first or any(not known(r['state'], 'iwr') for r in first):
        findings.append(result('SPAWN', 'no-evidence', 'first actor raw wall read missing'))
    for r in first:
        if r['state'].get('iwr'):
            findings.append(result('SPAWN', 'fail', 'first actor sample inside wall', r))
    for r in records:
        if start is not None and r['k'] == 'ev' and 0 <= r['t'] - start <= 40 and REFUSAL.search(r.get('msg', '')):
            findings.append(result('SPAWN', 'suspect', 'early scene refusal', r))
    if not lag or any(not known(r, 'ms') and 'stall_ms' not in r for r in lag):
        findings.append(result('LAG', 'no-evidence', 'tick timing missing'))
    if not geo:
        findings.extend(result(k, 'no-evidence', 'geometry records missing') for k in ('SPAWN', 'VANTAGE'))
    if not samples:
        findings.extend(result(k, 'no-evidence', 'state samples missing') for k in ('LOOP', 'PROGRESS', 'JUMP', 'ORPHAN'))
    # Incomplete fields must not silently certify an actor. The recorder emits full snapshots in dense mode.
    for k, fields in (('LOOP', ('att', 'hold', 'p', 'goals', 'phase', 'need')), ('ORPHAN', ('dep',))):
        if any(not known(r['state'], *fields) for r in samples if not is_prop(r['state'])):
            findings.append(result(k, 'no-evidence', 'required state fields missing'))
    if issues:
        # Do not manufacture a finding by joining state across a lost record. The legacy raw-log gate is independent.
        findings = [f for f in findings if f['detail'].startswith('fate ')]
        findings.extend(result(k, 'no-evidence', 'trace integrity: ' + '; '.join(issues)) for k in CHECKS)
    # Keep the worst occurrence per actor/signal/reason; full raw evidence is in exported timelines.
    compact = {}
    for f in findings:
        key = f['check'], f['actor'], f['detail']
        previous = compact.get(key)
        if previous is None or RANK[f['verdict']] > RANK[previous['verdict']]:
            compact[key] = f
    findings = list(compact.values())
    summary = []
    for check in CHECKS:
        relevant = [f for f in findings if f['check'] == check]
        verdict = max((f['verdict'] for f in relevant), key=RANK.get, default='pass')
        summary.append(dict(check=check, verdict=verdict))
    for f in findings:
        f.update(evidence=evidence, seed=seed, rerun=rerun,
                 timeline=[r for r in records if f['t'] is not None and abs(r['t'] - f['t']) <= 100
                           and (r['u'] == f['actor'] or r['u'] is None)])
    return dict(report_only=True, evidence=evidence, seed=seed, rerun=rerun, summary=summary, findings=findings)


def main():
    ap = argparse.ArgumentParser(description='Report-only server observations; --fate-only preserves the legacy gate CLI.')
    ap.add_argument('logs', nargs='+', type=Path)
    ap.add_argument('--scene')
    ap.add_argument('--output', type=Path)
    ap.add_argument('--seed', type=int)
    ap.add_argument('--rerun')
    ap.add_argument('--evidence', choices=('H', 'C', 'F', 'R'), default='H')
    ap.add_argument('--fate-only', action='store_true')
    ap.add_argument('--dense', action='store_true', help='Input covers every actor every tick (enables actor-disappearance check)')
    args = ap.parse_args()
    if args.fate_only:
        sys.argv = [sys.argv[0]] + ([ '--scene', args.scene] if args.scene else []) + [str(p) for p in args.logs]
        return fate_main()
    records, issues = timeline.read(args.logs)
    raw = '\n'.join(timeline.read_text(p) for p in args.logs)
    if args.seed is None:
        seeds = set(re.findall(r'\[seed\].*?\brun=(-?\d+)\b', raw))
        args.seed = int(next(iter(seeds))) if len(seeds) == 1 else None
    if args.rerun is None:
        args.rerun = subprocess.list2cmdline([sys.executable, str(Path(__file__).resolve()),
                                           *[str(p.resolve()) for p in args.logs]])
    smoke = {}
    if args.scene:
        import museum_scenarios
        smoke = next(s.smoke for s in museum_scenarios.S if s.id == args.scene) or {}
    reports = []
    for key in dict.fromkeys(timeline.scope(r) for r in records):
        scoped = [r for r in records if timeline.scope(r) == key]
        reports.append(dict(scope=key, **observations(scoped, issues, raw, smoke.get('allow_fate', ()),
                            smoke.get('end', ()), args.seed, args.rerun, args.evidence, args.dense)))
    if not reports:
        reports.append(observations([], issues, raw, seed=args.seed, rerun=args.rerun, evidence=args.evidence))
    text = json.dumps(reports, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(text + '\n', encoding='utf-8')
    else:
        print(text)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
