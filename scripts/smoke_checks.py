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
from bisect import bisect_left, bisect_right
from collections import defaultdict, Counter, deque
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
REFUSAL = re.compile(r'(?<![=\w])(?:refused|rejected|unavailable|blocked|failed|gave up|abandoned)\b(?![=])'
                     r'|\boutcome=(?:BLOCKED|INCAPABLE|LOST)\b|\bresult=(?:EXHAUSTED|LOST)\b')
SUPPRESSED = re.compile(r' \(suppressed=(\d+)\)')
# Same entity, same refusal and reason, counted with its suppressed repeats. In 1615 saved runs of the smoke scenes
# (.local/smoke, 2026-11) the largest such count of a run was <= 29 in 75% of runs and below 400 in 93%; the runs at or
# above it re-asked one refusal every tick or faster ("hop shore stand refused reason=hop_arc" up to 18840 times,
# "contact stand unavailable" up to 1322): an actor that did not change its condition. 400 is 20 s of one refusal a tick.
LOOP_BOUND = 400
CHECKER = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()[:12]


def is_refusal(category, message):
    # Status deltas describe stored state, not another attempted action. In particular,
    # failed=[] and decision=unavailable are not refusals, nor is rejected=null.
    # Waters.stick enumerates candidates before an action is selected. Rejecting
    # one wet/unreachable item is not a failed attempt by the running action.
    candidate = category == 'aquatic' and message.startswith('stick rejected id=')
    return category != 'status' and not candidate and REFUSAL.search(message)


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
        if is_refusal(event['cat'], event['msg']):
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
    return work_changed(a, b)


def action_clock_advanced(a, b):
    aa, ab = a.get('act') or {}, b.get('act') or {}
    return (aa.get('id') in ('launch', 'await', 'gesture') and aa.get('id') == ab.get('id')
            and aa.get('owner') == ab.get('owner') and known(aa, 'metric') and known(ab, 'metric')
            and 0 <= ab['metric'] < aa['metric'])


def work_changed(a, b):
    """Completed work/context changes, excluding displacement and action-id churn."""
    if action_clock_advanced(a, b):
        # These actions expose ticks remaining, not distance. A real bounded
        # flight/wait advancing is work; a reset or stalled timer is not.
        return True
    if (known(a, 'dailyGreetingWait') and known(b, 'dailyGreetingWait')
            and 0 <= b['dailyGreetingWait'] < a['dailyGreetingWait']):
        # Daily contact starts with an actual greeting before contactTicks.
        # This clock only decreases while touching; a frozen/reset clock or
        # subsequent failed approach is still checked.
        return True
    if (not a.get('int') and not b.get('int') and not a.get('act') and not b.get('act')
            and known(b, 'waitElapsed') and (not known(a, 'waitElapsed') or b['waitElapsed'] > a['waitElapsed'])):
        # A choreography may hold one role while another runs a side job.
        # Its phase clock does not prove that the active body's job advanced.
        return True
    for key in ('contactProgress', 'careChewTicks', 'dailyContactTicks', 'thermalRestTicks'):
        if known(a, key) and known(b, key) and b[key] > a[key]:
            return True
    if 'socialGreeting' in a and b.get('socialGreeting') is not None and a['socialGreeting'] != b['socialGreeting']:
        return True
    if known(a, 'behavior') and known(b, 'behavior') and a['behavior'] != b['behavior']:
        return True
    if any(a[k] != b[k] for k in ('int', 'goals', 'phase', 'hold')):
        return True
    # LifeGoal serves meals/rest without an IntentRunner id. Real recovery is progress
    # regardless of which driver owns it; the interval closes when recovery stops.
    for key in ('nut', 'en'):
        if a['need'].get(key) is not None and b['need'].get(key) is not None and b['need'][key] > a['need'][key]:
            return True
    return False


def progress_known(s):
    return known(s, 'p', 'goals', 'phase', 'hold', 'need') and 'int' in s


def is_prop(s):
    return 'item' in s or 'riders' in s


PROGRESS_FIELDS = ('p', 'int', 'goals', 'phase', 'hold', 'need')


def active_work(state):
    # A passive illness presentation can mask a resting duty's idle behavior.
    # Read that duty's executed phase; never infer rest from its visible label.
    resting_pit = state.get('pitPhase') in ('NONE', 'STAY')
    return bool(state['int']) or bool(state['act']) or (state.get('behavior') != 'idle' and any(
        str(goal).split('@')[0].lower() not in ('idle', 'look', 'random_look', 'randomlookaroundgoal', 'lookatplayergoal')
        and not (resting_pit and str(goal).split('@')[0] == 'PitSlaveGoal')
        for goal in state['goals']))


def receiver_tracks_parent(a, b, parents):
    parent = a['state'].get('careParent')
    if not parent or parent != b['state'].get('careParent') or distance(a['state']['p'], b['state']['p']) > 1e-9:
        return False
    pa, pb = parents.get((parent, a['t'])), parents.get((parent, b['t']))
    if pa is None or pb is None or distance(pa['p'], pb['p']) <= 1e-9:
        return False
    # FEED faces the caregiver; REAR faces away. Use the existing one-degree
    # yaw deadband, and require the same actual facing side in both samples.
    errors = []
    for row, target in ((a, pa), (b, pb)):
        s = row['state']
        bearing = math.degrees(math.atan2(s['p'][0] - target['p'][0], target['p'][2] - s['p'][2]))
        errors.append((s['yaw'] - bearing + 180) % 360 - 180)
    return any(all(abs((error - offset + 180) % 360 - 180) <= 1 for error in errors) for offset in (0, 180))


def progress(rows, records=()):
    out = []
    care_parents = {r['state']['careParent'] for r in rows if r['state'].get('careParent')}
    parents = {(r['u'], r['t']): r['state'] for r in rows if r['u'] in care_parents and known(r['state'], 'p')}
    contact_ticks = defaultdict(list)
    stationary_waits = {}
    for record in records:
        if record['k'] == 'ev' and record.get('cat') == 'wild-event':
            wait = re.fullmatch(r'stationary wait scene=(\S+) phase=(\S+) elapsed=(\d+)', record.get('msg', ''))
            if wait:
                stationary_waits[record['u'], record['t']] = (wait[1], wait[2], int(wait[3]))
        if (record['k'] == 'ev' and record.get('cat') == 'social'
                and record.get('msg', '').startswith('self defense strike ')):
            hit = re.search(r'accepted=true health=([\d.]+) -> ([\d.]+)', record['msg'])
            if hit and float(hit[2]) < float(hit[1]):
                contact_ticks[record['u']].append(record['t'])
    def wait_advanced(a, b):
        # This receipt is emitted only after an executed stationary decision,
        # not merely because an event owns the actor or its deadline advances.
        wa, wb = stationary_waits.get((a['u'], a['t'])), stationary_waits.get((b['u'], b['t']))
        return (wa is not None and wb is not None and wa[:2] == wb[:2] and wb[2] > wa[2]
                and not any(r['state'].get(k) for r in (a, b) for k in ('int', 'act')))
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
            if s.get('alive') is False or s.get('hp', 1) <= 0:
                anchor = row
                continue
            action = s['act'] or {}
            hits = contact_ticks[actor]
            contacted = bisect_right(hits, row['t']) > bisect_right(hits, anchor['t'])
            if not active_work(s) or contacted or changed(a, s) or wait_advanced(anchor, row) or (action.get('deadline') is not None and row['t'] <= action['deadline']):
                anchor = row
                continue
            # A newly entered short action cannot retroactively shorten the
            # interval that belonged to its preceding goal/action.
            anchor_action = a['act'] or {}
            window = max(5 * action['win'] if action.get('win') else 600,
                         5 * anchor_action['win'] if anchor_action.get('win') else 600)
            age = row['t'] - anchor['t']
            if age >= window:
                out.append(result('PROGRESS', band(age, window, 2 * window), 'no observed progress', row, duration=age, window=window))
        # Dense stop/go and yaw reversal cycles, in rolling 100-tick windows (5 seconds).
        # A successful strike has an actual bounded recovery clock. Failed hits
        # and frozen/reset clocks do not earn this interval; read its duration
        # at the accepted strike instead of duplicating the gameplay constant.
        hits = contact_ticks[actor]
        hit_ticks = set(hits)
        strike_waits = {r['t']: r['state']['defenseWait'] for r in valid
                        if r['t'] in hit_ticks and known(r['state'], 'defenseWait')}
        transitions, reversals, positions = deque(), deque(), deque()
        prev_moving, prev_turn = None, 0
        for a, b in zip(valid, valid[1:]):
            prior_hit = bisect_right(hits, a['t']) - 1
            recovering = (prior_hit >= 0 and hits[prior_hit] in strike_waits
                          and b['t'] <= hits[prior_hit] + strike_waits[hits[prior_hit]]
                          and known(a['state'], 'defenseWait') and known(b['state'], 'defenseWait')
                          and 0 <= b['state']['defenseWait'] < a['state']['defenseWait'])
            if (b['t'] - a['t'] != 1 or not active_work(b['state']) or work_changed(a['state'], b['state'])
                    or recovering or wait_advanced(a, b)
                    or a['state'].get('veh') != b['state'].get('veh')
                    or bisect_right(contact_ticks[actor], b['t']) > bisect_right(contact_ticks[actor], a['t'])
                    or b['state'].get('alive') is False or b['state'].get('hp', 1) <= 0):
                prev_moving, prev_turn = None, 0
                transitions.clear()
                reversals.clear()
                positions.clear()
                continue
            if not positions:
                positions.append(a)
            positions.append(b)
            while positions[0]['t'] < b['t'] - 100:
                positions.popleft()
            step = distance(a['state']['p'], b['state']['p'])
            # Crossing the .01 start threshold while still translating is speed
            # modulation, not a stop. Close a movement only at an actual stop
            # (the existing floating-point tolerance), then count a new start.
            moving = step > 1e-9 if prev_moving else step > .01 + 1e-9
            if prev_moving is not None and moving != prev_moving:
                transitions.append(b['t'])
            prev_moving = moving
            if known(a['state'], 'yaw') and known(b['state'], 'yaw'):
                turn = (b['state']['yaw'] - a['state']['yaw'] + 180) % 360 - 180
                if receiver_tracks_parent(a, b, parents):
                    prev_turn = 0
                    reversals.clear()
                elif abs(turn) > 1:
                    # Apply the same deadband to counting and remembering a turn. Otherwise
                    # every small opposite step repeats the last significant reversal.
                    if turn * prev_turn < 0:
                        reversals.append(b['t'])
                    prev_turn = turn
            for events in (transitions, reversals):
                while events and events[0] <= b['t'] - 100:
                    events.popleft()
            # A small child's normal path can span several body lengths inside
            # half a block (R birth_boat-153). Scale the adult in-place footprint
            # by the recorded body size; legacy logs without size keep .5.
            # This changes only yaw stutter, not the .5 stagnation threshold.
            scale = b['state']['scale'] if known(b['state'], 'scale') else 1
            in_place = distance(positions[0]['state']['p'], b['state']['p']) < .5 * scale
            cycles = max(len(transitions) // 2, len(reversals) // 2 if in_place else 0)
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
    actor_ticks = {actor: [r['t'] for r in rows] for actor, rows in actors.items()}
    action_starts = {}
    completed_intents = {(r['u'], r['t']) for r in records if r['k'] == 'ev' and r.get('cat') == 'intent'
                         and re.match(r'end id=\S+ result=DONE\b', r.get('msg', ''))}
    for row in records:
        if row['k'] != 'ev':
            continue
        msg = row.get('msg', '')
        if row.get('cat') == 'action' and msg.startswith('start id='):
            action_starts[row['u']] = row['t']
        completed = msg.startswith('end ') and (' outcome=DONE ' in msg or (
            ' outcome=INTERRUPTED why=owner_inactive' in msg
            and (row['u'], row['t']) in completed_intents)
        )
        if row.get('cat') == 'action' and completed:
            start = action_starts.pop(row['u'], None)
            ticks = actor_ticks.get(row['u'], [])
            during = actors[row['u']][bisect_left(ticks, start - 1):bisect_right(ticks, row['t'])] if start is not None else []
            moved = False
            if msg.startswith('end id=walk ') and during and during[0]['t'] == start - 1 and during[-1]['t'] == row['t']:
                a, b = during[0]['state'], during[-1]['state']
                aa, ab = a.get('att') or {}, b.get('att') or {}
                if aa.get('host') and aa.get('host') == ab.get('host') and known(aa, 'spot') and known(ab, 'spot'):
                    moved = distance(aa['spot'], ab['spot']) > 1e-9
                elif not aa.get('host') and not ab.get('host') and not a.get('veh') and not b.get('veh') and known(a, 'p') and known(b, 'p'):
                    moved = distance(a['p'], b['p']) > 1e-9
            # Completed work can be a short walk or an advancing clock.
            # Holder drift alone, immediate condition churn, frozen clocks and
            # timed-out/blocked actions still count. The .5 unfinished-work
            # threshold and the independent 400-refusal gate are unchanged.
            if moved or any(action_clock_advanced(a['state'], b['state']) for a, b in zip(during, during[1:])):
                # Real completed work also closes prior repeats of other actions
                # (e.g. face -> short walk -> face). Merely skipping this walk's
                # own ending would leave the earlier face bucket accumulating.
                for key in list(buckets):
                    if key[0] == row['u']:
                        del buckets[key]
                continue
        if not (is_refusal(row.get('cat'), msg) or row.get('cat') == 'action' and msg.startswith('end ')):
            continue
        if not known(row, 'pos', 'u'):
            out.append(result('LOOP', 'no-evidence', 'event has no actor or block position', row))
            continue
        key = row['u'], row.get('cat'), signature(msg)
        bucket = buckets[key]
        # Count only repeats at the same block ±1 with no observed progress in between.
        # Progress is cumulative from this repeat interval's first event. Using
        # only the preceding event mislabels steady sub-half-block advances as
        # an unchanged loop forever (e.g. completed short paddle segments).
        prior = bucket[0][0] if bucket else None
        ticks = actor_ticks.get(row['u'], [])
        between = actors[row['u']][bisect_left(ticks, prior['t']):bisect_right(ticks, row['t'])] if prior else []
        enough = len(between) >= 2 and all(progress_known(s['state']) for s in between)
        repeats = 1 + row.get('sup', 0)
        if prior and (distance(prior['pos'], row['pos']) > math.sqrt(3) or enough and changed(between[0]['state'], between[-1]['state'])):
            bucket.clear()
            # Suppressed attempts occurred since the previous printed event,
            # across the progress just observed. They cannot start the new
            # unchanged interval; only this attempt belongs to that interval.
            enough = False
            repeats = 1
        bucket.append((row, repeats))
        count = sum(n for r, n in bucket if r['t'] > row['t'] - 1200)
        if count >= 5:
            out.append(result('LOOP', band(count, 5, 20) if enough else 'no-evidence',
                              'repeated event without observed progress' if enough else 'repeats lack progress samples', row, repeats=count))
    for actor, rows in actors.items():
        flips = defaultdict(list)
        values = []
        for row in rows:
            s = row['state']
            if not known(s, 'att', 'hold') or 'veh' not in s:
                continue
            if values and known(s, 'p') and known(values[-1][0]['state'], 'p') and distance(s['p'], values[-1][0]['state']['p']) >= .5:
                # Carrying a child to another place is completed transport, not
                # repeated ownership churn at the same place.
                values.clear()
                flips.clear()
            # Frame/spot motion is geometry, not a change of attachment ownership.
            attachment = tuple(s['att'].get(k) for k in ('kind', 'host', 'place')) if s['att'].get('host') else None
            value = (attachment, s['veh'], s['hold'].get('carry'))
            if not values or values[-1][1] != value:
                values.append((row, value))
                if len(values) >= 3 and values[-1][1] == values[-3][1]:
                    # Empty -> child A -> empty and empty -> child B -> empty
                    # are different transfers, not repeats of one A-B-A cycle.
                    pair = tuple(sorted((repr(value), repr(values[-2][1]))))
                    flips[pair].append(row['t'])
                    count = sum(t > row['t'] - 1200 for t in flips[pair])
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


def jumps(samples, records=()):
    out = []
    placements = {}
    host_travel = {}
    travel_velocity, hop_velocity, local_flight = {}, {}, {}
    for row in records:
        if row['k'] == 'ev' and row.get('cat') == 'move':
            msg = row.get('msg', '')
            match = re.fullmatch(r'travel action=none at=\([^)]+\) velocity=\(([^)]+)\)', msg)
            if match:
                travel_velocity[row['u'], row['t']] = [float(x) for x in match[1].split(',')]
            match = re.fullmatch(r'travel action=hop at=\([^)]+\) velocity=\(([^)]+)\)', msg)
            if match:
                # A dismount can run two consumed Hop flight segments in one
                # tick. Each velocity is its executed Motor.move input; neither
                # endpoint alone represents the sum. Extra relocation still
                # leaves a residual against these independently logged inputs.
                key = row['u'], row['t']
                prior = hop_velocity.get(key, [0, 0, 0])
                hop_velocity[key] = [a + float(b) for a, b in zip(prior, match[1].split(','))]
            match = re.fullmatch(r'hop flight host=([\w-]+) localDelta=\(([^)]+)\)', msg)
            if match:
                local_flight[row['u'], row['t']] = (match[1], [float(x) for x in match[2].split(',')])
        if row['k'] == 'ev' and row.get('cat') == 'hat':
            match = re.match(r'travel at=\([^)]+\) delta=\(([^)]+)\) velocity=', row.get('msg', ''))
            if match:
                # Hull contact can displace a hat without retaining that push
                # in velocity. This is the existing Motor.move result, not a
                # guessed force or an exemption for all host movement.
                host_travel[row['u'], row['t']] = [float(x) for x in match[1].split(',')]
        if row['k'] == 'ev' and row.get('cat') == 'body':
            match = re.fullmatch(r'relocate cause=scenario_initial_seat from=\(([^)]+)\) to=\(([^)]+)\)', row.get('msg', ''))
            if match:
                before, after = ([float(x) for x in match[i].split(',')] for i in (1, 2))
                placements[row['u'], row['t']] = [b - a for a, b in zip(before, after)]
    by_tick = {(r['u'], r['t']): r['state'] for r in samples}
    for rows in actor_samples(samples).values():
        pairs = 0
        for a, b in zip(rows, rows[1:]):
            sa, sb = a['state'], b['state']
            hat_pair = known(sa, 'p', 'v', 'frame', 'riders') and known(sb, 'p', 'frame', 'riders')
            body_pair = known(sa, 'p', 'v', 'att', 'g') and known(sb, 'p', 'att', 'g')
            if b['t'] - a['t'] != 1 or not (hat_pair or body_pair):
                continue
            pairs += 1
            # Placed hats are free-moving hosts; their schema has frame/riders, not a body's att/g.
            aa, ab = (sa.get('att', {}), sb.get('att', {}))
            host = aa.get('host')
            if host and host == ab.get('host'):
                if aa.get('frame') and ab.get('frame'):
                    pa, pb = local_point(sa['p'], aa['frame']), local_point(sb['p'], ab['frame'])
                    delta = [(y - x) * ab['frame']['unit'] for x, y in zip(pa, pb)]
                    flight = local_flight.get((b['u'], b['t']))
                    # A host-local flight has zero retained world velocity. Use
                    # its executed ballistic segment, not the actual travel or
                    # the distance to its aim: extra relocation stays visible.
                    r = distance(delta, [0, 0, 0])
                    if flight and flight[0] == host:
                        r = min(r, distance(delta, flight[1]))
                elif (host, a['t']) in by_tick and (host, b['t']) in by_tick:
                    ca, cb = by_tick[host, a['t']], by_tick[host, b['t']]
                    if not known(ca, 'p', 'by') or not known(cb, 'p', 'by'):
                        out.append(result('JUMP', 'no-evidence', 'carrier position/body yaw missing', b))
                        continue
                    # ParentCarry.world rotates its model by 180 - bodyYaw. A child
                    # held at one point does not jump when its carrier turns.
                    fa, fb = (dict(origin=s['p'], unit=1, yaw=math.radians(180 - s['by']), pitch=0, roll=0)
                              for s in (ca, cb))
                    r = distance(local_point(sa['p'], fa), local_point(sb['p'], fb))
                else:
                    out.append(result('JUMP', 'no-evidence', 'host frame/carrier positions missing', b))
                    continue
            else:
                # Subtract only the explicitly logged scene-setup input for this actor
                # and tick; extra motion in the same tick is still checked.
                placed = placements.get((b['u'], b['t']))
                # Samples are post-tick: an impulse applied during this tick is
                # visible in its ending velocity, not the preceding one. Ordinary
                # flight can instead match the preceding velocity before drag.
                velocities = [sa['v'], sb['v']] if known(sb, 'v') else [sa['v']]
                if (b['u'], b['t']) in travel_velocity:
                    velocities.append(travel_velocity[b['u'], b['t']])
                if (b['u'], b['t']) in hop_velocity:
                    velocities.append(hop_velocity[b['u'], b['t']])
                if hat_pair and (b['u'], b['t']) in host_travel:
                    velocities.append(host_travel[b['u'], b['t']])
                residuals = []
                for velocity in velocities:
                    residual = [sb['p'][i] - sa['p'][i] - velocity[i] for i in range(3)]
                    if placed is not None:
                        residual = [residual[i] - placed[i] for i in range(3)]
                    if sa.get('g') or sb.get('g'):
                        # Read actual upward step capability; legacy logs retain .6.
                        slack = sa['step'] if residual[1] > 0 and known(sa, 'step') else .6
                        residual[1] = math.copysign(max(0, abs(residual[1]) - slack), residual[1])
                    residuals.append(math.sqrt(sum(v * v for v in residual)))
                # A retained velocity can be blocked by contact or an active
                # motor hold. Missing expected travel is not a position jump:
                # the unexplained jump cannot exceed the actual displacement.
                r = min(*residuals, distance(sa['p'], sb['p']))
            if r > .25:
                out.append(result('JUMP', band(r, .25, 1), 'motion residual (blocks)', b, residual=r))
        if not pairs:
            out.append(result('JUMP', 'no-evidence', 'no adjacent samples with velocity/attachment', rows[-1]))
    return out


def inventory(s):
    total = Counter()
    # The corpse retains synchronized mouth/carry display fields until vanilla
    # removal. Death has already handed real resources to their new holders.
    # Removing this holder still needs a logged transfer/consumption below.
    if 'hold' in s and (s.get('alive') is False or s.get('hp', 1) <= 0):
        return total
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


def ledger(records, samples, dense=False, raw=''):
    out, ticks, current = [], defaultdict(list), {}
    # An untagged actor can drop an item into the traced field. Its GameplayLog
    # line exists even though it has no actor stream; accept only an explicit
    # drop/source UUID that belongs to this scope, never unrelated transactions.
    observed = {r['u'] for r in samples}
    boundary = []
    for line in raw.splitlines():
        event = EVENT.search(line)
        drop = re.search(r'\b(?:drop|source)=([0-9a-f-]{36})\b', event['msg']) if event else None
        if drop and drop[1] in observed:
            boundary.append(dict(k='ev',u=event['uuid'],t=int(re.search(r'\bt=(\d+)',line)[1]),
                                 cat=event['cat'],msg=event['msg']))
    explanations = [r for r in [*records, *boundary] if r['k'] == 'ev' and re.search(r'\b(eat|ate|swallow|consum|place|pickup|interaction|interrupted item=|drop item=|poop stays a loose item|received (?:loose accessory )?item=|gathered item=|shed stem delivered|deposit home=|granary deposit|feed portion reserved|feed reserved portion dropped|feed reserved portion returned store=.*\bitem=|leaf swaddle (?:wrapped|leaf harvested item=)|feed plant harvested|feed mash prepared|feed handed to meal|nest material|pack store=|carried meal|stores (?:put|taken|spilled))', r.get('msg', ''), re.I)
                    or r['k'] == 'life' and r.get('out') == 'discarded'
                        and known(r, 'age', 'lifespan', 'item') and r['age'] >= r['lifespan']]
    explained_items = defaultdict(set)
    for event in explanations:
        msg = event.get('msg', '')
        item = re.search(r'\b(?:item|kind|leaves)=(?:\d+[ x])?([a-z0-9_.-]+:[a-z0-9_/.-]+)', msg)
        kinds = re.search(r'\bkinds=\{([^}]*)\}', msg)
        items = (set(re.findall(r'([a-z0-9_.-]+:[a-z0-9_/.-]+)=\d+', kinds[1])) if kinds
                 else {event.get('item') or (item[1] if item else None)})
        if event.get('cat') == 'needs' and msg.startswith('poop stays a loose item '):
            # ToiletContact logs this only after spawning the waste item.
            items = {'mcyukkuri:yukkuri_waste'}
        for offset in range(-2, 3):
            explained_items[event['t'] + offset].update(items)
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
    entries = {(r['u'], t): r['in'] for r in records if r['k'] == 'life'
               and r.get('in') in ('chunk_load', 'scope_enter', 'changed_dimension')
               for t in range(r['t'], r['t'] + 3)}
    previous, dip = None, None
    for tick, rows in sorted(ticks.items()):
        # Lifecycle and reconstructed states were collected separately above;
        # preserve their actual order within a tick, especially unload after
        # the final snapshot. Otherwise that snapshot reintroduces the holder.
        rows.sort(key=lambda row: row['seq'])
        prior_holders = dict(current)
        boundary_delta = Counter()
        boundary_holders = set()
        for row in rows:
            if row['k'] == 'life' and row.get('out'):
                if row.get('out') in ('unloaded', 'scope_exit', 'changed_dimension'):
                    boundary_delta.subtract(current.get(row['u']) or {})
                    boundary_holders.add(row['u'])
                current.pop(row['u'], None)
            elif row['k'] == 'st':
                entry = entries.get((row['u'], tick))
                # Loose items first discovered by proximity also say scope_enter,
                # including newly created drops; that label is not a receipt.
                entering = row['u'] not in current and entry is not None and (
                    entry != 'scope_enter' or 'stage' in row['state'] or 'riders' in row['state'])
                current[row['u']] = inventory(row['state'])
                if entering:
                    boundary_delta.update(current[row['u']] or {})
                    boundary_holders.add(row['u'])
                s = row['state']
                mouth = ((s.get('hold') or {}).get('mouth') or []) if s.get('alive') is not False and s.get('hp', 1) > 0 else []
                if sum(bool(store) for store in mouth) > 1:
                    out.append(result('LEDGER', 'suspect', 'two non-empty mouth stores', row))
        # A spawn event can precede the first snapshot by one tick. There is no
        # inventory comparison yet; an empty field after a known snapshot is a real zero.
        if not current and previous is None:
            continue
        if any(v is None for v in current.values()):
            out.append(result('LEDGER', 'no-evidence', 'inventory authority missing', rows[-1]))
            continue
        total = sum(current.values(), Counter())
        # Both comparison baselines follow the recorded scope flow. Otherwise
        # an unrelated same-item creation can cancel an exiting holder, or a
        # later entry can falsely close an existing unexplained dip.
        if previous is not None:
            previous.update(boundary_delta)
        if dip:
            dip[1].update(boundary_delta)
        changed_items = {item for item in total.keys() | previous.keys()
                         if total[item] != previous[item]} if previous is not None else set()
        explained = None in explained_items[tick] or changed_items <= explained_items[tick]
        if previous is not None and total != previous and not explained and dip is None:
            affected = [r for r in rows if r['u'] not in boundary_holders
                        and prior_holders.get(r['u']) != current.get(r['u'])]
            dip = (tick, previous, affected[0], {item: total[item] - previous[item] for item in changed_items})
        if dip and total == dip[1]:
            duration = tick - dip[0]
            if duration >= 3:
                out.append(result('LEDGER', 'fail' if duration > 20 else 'suspect', 'unexplained inventory dip/appearance', dip[2], duration=duration, items=dip[3]))
            dip = None
        previous = total
    if dip:
        out.append(result('LEDGER', 'fail', 'unexplained permanent item total change', dip[2], items=dip[3]))
    if len({r['t'] for r in samples}) < 2:
        out.append(result('LEDGER', 'no-evidence', 'need at least two inventory snapshots'))
    return out


def orphans(samples, records=()):
    out, all_ticks = [], defaultdict(dict)
    final_tick = max((r['t'] for r in samples), default=None)
    deaths = {}
    water_phases = defaultdict(list)
    for r in records:
        if r['k'] == 'ev' and r.get('cat') == 'water':
            match = re.match(r'phase=\S+ -> (IDLE|IMPACT|STRUGGLE|EXHAUSTED|RECOVER)\b', r.get('msg', ''))
            phase = match[1] if match else 'IMPACT' if r.get('msg', '').startswith('entered at=') else None
            if phase:
                water_phases[r['u']].append((r['t'], r['seq'], phase))
        if (r['k'] == 'life' and r.get('out') == 'killed' or
                r['k'] == 'ev' and r.get('cat') == 'body' and r.get('msg', '').startswith('death settled into remains;')):
            deaths[r['u']] = min(deaths.get(r['u'], r['t']), r['t'])
    for r in samples:
        all_ticks[r['t']][r['u']] = r['state']
    for actor, rows in actor_samples(samples).items():
        unsafe_since = falling_since = None
        phases = iter(water_phases[actor])
        change, phase = next(phases, None), None
        if is_prop(rows[0]['state']):
            continue
        for r in rows:
            s = r['state']
            while change is not None and change[:2] <= (r['t'], r['seq']):
                phase = change[2]
                change = next(phases, None)
            if r['u'] in deaths and r['t'] >= deaths[r['u']]:
                continue
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
            # WaterDistress remains active during its dry, supported recovery.
            # That presentation is not continuing danger. Prefer the actual
            # phase snapshot; old logs can prove a transition, but silence
            # never supplies one. Reentry/wall/falling hazards remain checked.
            recovering = ((s.get('waterPhase') or phase) == 'RECOVER'
                          and known(s, 'w', 'wr') and not s['w'] and not s['wr'])
            unsafe = s['distress'] and not recovering or s['iwr'] or falling and r['t'] - falling_since >= 20
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
            if r is rows[-1] and unsafe and r['u'] not in deaths:
                if r['t'] == final_tick:
                    out.append(result('ORPHAN', 'fail', 'dependent unsafe at window end', r))
                else:
                    out.append(result('ORPHAN', 'no-evidence', 'unsafe dependent not observed through window end', r))
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


def terminal_endings(records, samples, end):
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


class EndContext:
    """END-only index. Selectors name observed identities; events never create state evidence."""
    def __init__(self, records, samples, raw='', bounds=None):
        self.records = records
        self.samples = actor_samples(samples)
        self.times = {u: [r['t'] for r in rows] for u, rows in self.samples.items()}
        self.exits = defaultdict(list)
        for r in records:
            if r['k'] == 'life' and r.get('out'):
                self.exits[r['u']].append(r)
        self.events = [dict(r) for r in records if r['k'] == 'ev']
        self.end = max((r['t'] for r in records), default=0)
        # Untagged settlement residents still emit authoritative GameplayLog events. END alone may read
        # them, bounded by the scene's real plot, dimension and window; no state is invented for them.
        if raw and bounds:
            start = min(r['t'] for r in records)
            dimension = records[0]['dim']
            seen = {(r['u'], r['t'], r.get('cat'), r['msg']) for r in self.events}
            orders = {}
            for order, line in enumerate(raw.splitlines()):
                event = EVENT.search(line)
                world = re.search(r'\[([\w-]+)\] t=(\d+) dim=(\S+) (.*)', line)
                if world and world[3] == dimension:
                    orders[None, int(world[2]), world[1], world[4]] = order
                if event is None or f'dim={dimension} ' not in line:
                    continue
                tick = int(re.search(r'\bt=(\d+)\b', line)[1])
                x, y, z = map(int, re.search(r'\bpos=(-?\d+), (-?\d+), (-?\d+)', line).groups())
                key = event['uuid'], tick, event['cat'], event['msg']
                orders[key] = order
                if start <= tick <= self.end and bounds[0] <= x <= bounds[2] and bounds[1] <= z <= bounds[3] and key not in seen:
                    self.events.append(dict(k='ev', u=event['uuid'], t=tick, cat=event['cat'], msg=event['msg'],
                                            seq=order, end_legacy=True))
                    seen.add(key)
            for r in self.events:
                r['end_order'] = orders.get((r['u'], r['t'], r.get('cat'), r['msg']), r['seq'])
            self.events.sort(key=lambda r: (r['t'], r['end_order']))
        self.names = defaultdict(set)
        for r in records:
            if r['k'] == 'geo':
                for tag in r.get('tags', []):
                    self.names[tag.removeprefix('scn_')].add(r['u'])
        for r in samples:
            for tag in r['state'].get('tags', []):
                self.names[tag.removeprefix('scn_')].add(r['u'])
            if r['state'].get('name'):
                self.names[r['state']['name']].add(r['u'])

    def select(self, selector, bindings):
        if selector.startswith('$'):
            return {bindings[selector[1:]]}
        if selector == 'world':
            return {None}
        if selector == 'actors':
            return {u for u, rows in self.samples.items() if 'stage' in rows[0]['state']} | {r['u'] for r in self.events if r['u']}
        if selector.startswith(('child_of:', 'newborn_of:')):
            kind, parent = selector.split(':', 1)
            parents = self.select(parent, bindings)
            children = {u for u, rows in self.samples.items() if any(parents.intersection(
                r['state']['parents'].values() if isinstance(r['state'].get('parents'), dict)
                else r['state'].get('parents', [])) for r in rows)}
            if kind == 'newborn_of':
                children &= {r['u'] for r in self.records if r['k'] == 'life' and r.get('in') == 'birth'}
            return children
        if selector.startswith('hat_of:'):
            owners = self.select(selector[7:], bindings)
            return {m['hat'] for r in self.events if r.get('cat') == 'hat'
                    and (m := re.search(r'^placed owner=(?P<owner>\S+) hat=(?P<hat>\S+)', r['msg']))
                    and m['owner'] in owners}
        return self.names[selector]

    def state(self, actor, tick):
        # life-out invalidates an old full snapshot, including an unloaded subject.
        from bisect import bisect_right
        rows = self.samples.get(actor, [])
        index = bisect_right(self.times.get(actor, []), tick) - 1
        if index < 0:
            return None
        row = rows[index]
        exits = [r for r in self.exits[actor] if row['t'] <= r['t'] <= tick]
        if exits:
            return {'hp': 0, 'alive': False} if exits[-1]['out'] == 'killed' else None
        return row['state']

    def expand(self, pattern, bindings):
        def replace(match):
            key = match[1]
            if key in bindings:
                return re.escape(bindings[key])
            actors = self.select(key, bindings)
            if len(actors) != 1:
                raise ValueError(f'END placeholder ${key} requires one actor, got {len(actors)}')
            return re.escape(next(iter(actors)))
        return re.sub(r'\$([A-Za-z_]\w*)', replace, pattern)

    def test(self, actor, pred, tick, bindings):
        state = self.state(actor, tick)
        if state is None:
            return False
        for clause in pred.split(' and '):
            relation = re.fullmatch(r'(near|home_of|nest_of|aboard)\.([\w$]+)\s*(=|<=)\s*(.+)', clause)
            if relation:
                kind, selector, op, raw = relation.groups()
                peers = (set(v for v in state['parents'].values() if v) if selector == 'parents'
                         and isinstance(state.get('parents'), dict) else self.select(selector, bindings))
                other = [self.state(u, tick) for u in peers]
                if not peers or any(s is None for s in other):
                    return None
                if kind == 'near':
                    if 'p' not in state or any('p' not in s for s in other):
                        return None
                    value = min(distance(state['p'], s['p']) for s in other) <= float(raw)
                elif kind == 'home_of':
                    if state.get('home') is None or any(s.get('home') is None for s in other):
                        return None
                    value = any(state['home'] == s['home'] for s in other) == json.loads(raw)
                elif kind == 'aboard':
                    if 'att' not in state:
                        return None
                    hats = self.select('hat_of:' + selector, bindings)
                    host = state['att'].get('host')
                    value = (host in hats or host in peers and any(s.get('att', {}).get('host') in hats for s in other)) == json.loads(raw)
                else:
                    # Occupancy is the motor's logged result, never a guessed radius around homePos.
                    releases = [r for r in self.events if r['u'] in peers and r['t'] <= tick
                                and r.get('cat') == 'family' and re.search(r'\bchild=' + re.escape(actor) + r'\b', r['msg'])
                                and r['msg'].startswith('carry put down reason=')]
                    value = bool(releases and re.search(r'\bnest=(?:INSIDE_SUPPORTED|PARTIAL)\b', releases[-1]['msg'])
                                 and state.get('att', {}).get('kind') == 'free') == json.loads(raw)
                if not value:
                    return False
            elif clause == 'carry_empty=true':
                if 'carry' not in state.get('hold', {}):
                    return None
                if state['hold']['carry']:
                    return False
            elif (value := predicate(state, clause)) is not True:
                return value
        return True

    def clauses(self, clauses, tick, bindings, sustained):
        out = []
        for selector, pred in clauses:
            actors = self.select(selector, bindings)
            if not actors:
                out.append(result('END', 'fail', f'missing subject {selector}', dict(u=None, t=tick)))
            for actor in sorted(actors):
                times = range(tick - 99, tick + 1) if sustained else (tick,)
                values = [self.test(actor, pred, t, bindings) for t in times]
                verdict = ('no-evidence' if None in values else
                           'pass' if values[-1] and sum(values) >= .8 * len(values) else 'fail')
                out.append(result('END', verdict, f'{selector}: {pred}', dict(u=actor, t=tick)))
        return out

    def path(self, path, bindings):
        """Find a single ordered witness, backtracking over repeats instead of mixing unrelated attempts."""
        steps = path['steps']
        furthest = [0, None]
        rejected = []

        def search(index, after, bound):
            if index == len(steps):
                # Cross-entity movement settles on the next physics tick (the child may tick before its carrier).
                # Main carry_home samples: release t=140070 g=false, t=140071 g=true, unchanged attachment/position.
                post = []
                if path.get('post'):
                    if after[0] < self.end:
                        post = self.clauses(path['post'], after[0] + 1, bound, False)
                    else:
                        post = [result('END', 'no-evidence', 'window ended before postcondition sample', dict(u=None, t=after[0]))]
                rejected[:] = [r for r in post if r['verdict'] != 'pass']
                return (after, post) if all(r['verdict'] == 'pass' for r in post) else None
            emitter, seen = steps[index]
            _, cat, pattern = seen.split(':', 2)
            regex = re.compile(self.expand(pattern, bound))
            emitters = self.select(emitter, bound)
            for r in self.events:
                key = (r['t'], r.get('end_order', r['seq']))
                if key <= after or r['u'] not in emitters or r.get('cat') != cat:
                    continue
                match = regex.search(r['msg'])
                if match:
                    captured = {k: v for k, v in match.groupdict().items() if v is not None}
                    if any(k in bound and bound[k] != v for k, v in captured.items()):
                        continue
                    if index >= furthest[0]:
                        furthest[:] = [index + 1, r]
                    if witness := search(index + 1, key, bound | captured):
                        return witness
            return None

        witness = search(0, (-1, -1), bindings)
        if witness:
            (tick, seq), post = witness
            return [result('END', 'pass', 'ordered path: ' + path.get('name', str(steps)),
                           dict(u=bindings.get('subject'), t=tick)), *post]
        last = furthest[1] or dict(u=bindings.get('subject'), t=self.end)
        label = path.get('name', '') + (' subject=' + bindings['subject'] if 'subject' in bindings else '')
        return [result('END', 'no-evidence' if rejected and all(r['verdict'] == 'no-evidence' for r in rejected) else 'fail',
                       f'ordered path {label}: reached {furthest[0]}/{len(steps)}; '
                       'no complete witness with required postcondition', last,
                       next_step=steps[min(furthest[0], len(steps) - 1)], last_event=last.get('msg')), *rejected]


def endings(records, samples, end, raw=''):
    if not isinstance(end, dict):
        return terminal_endings(records, samples, end)
    if not samples:
        return [result('END', 'no-evidence', 'state samples missing')]
    context = EndContext(records, samples, raw, end.get('bounds'))
    out = context.clauses(end.get('final', ()), context.end, {}, True)
    for emitter, seen in end.get('forbid', ()):
        _, cat, pattern = seen.split(':', 2)
        actors = context.select(emitter, {})
        hits = [r for r in context.events if r['u'] in actors and r.get('cat') == cat
                and re.search(context.expand(pattern, {}), r['msg'])]
        out.append(result('END', 'fail' if hits else 'pass', 'forbidden event: ' + seen, hits[0] if hits else None))
    for path in end.get('paths', ()):
        if 'each' in path:
            actors = context.select(path['each'], {})
            if not actors:
                out.append(result('END', 'fail', 'missing path subject ' + path['each']))
            for actor in sorted(actors):
                out.extend(context.path(path, {'subject': actor}))
        else:
            out.extend(context.path(path, {}))
    return out


def observations(records, issues=(), raw='', allow=(), end=(), seed=None, rerun=None, evidence='F', dense=False, end_raw=None):
    """One scope only. New checks NEVER determine the smoke exit code; fates() alone keeps that gate."""
    samples = timeline.states(records)
    findings = []
    for reason, text in fates(raw, allow):
        event = EVENT.search(text)
        actor = dict(u=event['uuid'], t=int(re.search(r'\bt=(\d+)', text)[1])) if event else None
        findings.append(result('FATE' if not reason.startswith('fate loop') else 'LOOP', 'fail', text, actor))
    if not any(EVENT.search(line) for line in raw.splitlines()):
        findings.append(result('FATE', 'no-evidence', 'no legacy fate log supplied'))
    allowed_actors = {entry['who']: {event['uuid'] for line in raw.splitlines()
                      if re.search(entry['who'], line) and (event := EVENT.search(line))}
                      for entry in allow if entry.get('who') is not None}
    damage_lines = defaultdict(list)
    deaths = {(r['u'], r['t'], re.search(r'\bcause=(\S+)', r['msg'])[1].rsplit('.', 1)[-1])
              for r in records if r['k'] == 'ev' and r.get('msg', '').startswith('death settled into remains; cause=')}
    for line in raw.splitlines():
        if event := EVENT.search(line):
            damage_lines[event['uuid'], int(re.search(r'\bt=(\d+)', line)[1]), event['cat']].append(line)
    for r in records:
        if r['k'] == 'ev' and re.search(r'damage .*cause=', r.get('msg', '')):
            cause = re.search(r'cause=(\S+)', r['msg'])[1].rsplit('.', 1)[-1]
            kind = {'drown': 'drown', 'inWall': 'in_wall', 'starve': 'starve'}.get(cause)
            if kind is None and (r['u'], r['t'], cause) in deaths:
                kind = 'death'
            if any(entry['kind'] == kind and (entry.get('who') is None or r['u'] in allowed_actors[entry['who']])
                   and (entry.get('match') is None or any(re.search(entry['match'], line)
                        for line in damage_lines[r['u'], r['t'], r.get('cat')])) for entry in allow):
                continue
            if cause not in ('drown', 'inWall'):
                findings.append(result('FATE', 'fail' if cause in ('fall', 'soaking', 'starve') else 'suspect', r['msg'], r))
    findings += progress(samples, records) + loops(records, samples) + jumps(samples, records) + ledger(records, samples, dense, raw) + orphans(samples, records)
    findings += endings(records, samples, end, raw if end_raw is None else end_raw)
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
            # Reviewed fixed-view controls: afloat bodies at .03928, shore
            # rescue at .04989 and huddle at .06974 are readable; distant
            # birth bodies at .03372 are not. .035 separates these controls
            # without changing the collider-ray/frustum contract.
            elif r['vis'] < 5 or r['scr'] < .035:
                findings.append(result('VANTAGE', 'suspect', 'subject poorly visible from vantage', r))
        if start is not None and r['t'] - start in (1, 20):
            if not known(r, 'clear', 'drop'):
                findings.append(result('SPAWN', 'no-evidence', 'geometry clear/drop missing', r))
            elif not r['clear'] or (r['drop'] > .0625 and r.get('supported') is not True):
                # SceneGeometry.supported includes fluid/contact support. Its drop scan can
                # return the underwater floor before testing that surface; keep the frozen
                # tolerance for unsupported actors and still reject every obstruction.
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
    startup = actor_samples([r for r in samples if start is not None and 0 <= r['t'] - start <= 40])
    for r in records:
        if start is not None and r['k'] == 'ev' and 0 <= r['t'] - start <= 40 and is_refusal(r.get('cat'), r.get('msg', '')):
            following = [s for s in startup.get(r['u'], ()) if s['t'] >= r['t']]
            if len(following) < 2 or not all(progress_known(s['state']) for s in following):
                findings.append(result('SPAWN', 'no-evidence', 'early refusal lacks startup progress samples', r))
            elif not any(changed(following[0]['state'], s['state']) for s in following[1:]):
                # An optional candidate can be refused while the actor's real
                # activity starts normally. Only unresolved startup refusals
                # suggest a setup problem; geometry failures above stay strict.
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
    evidence_rows = defaultdict(list)
    for index, row in enumerate(records):
        evidence_rows[row['u']].append((row['t'], index, row))
    for rows in evidence_rows.values():
        rows.sort(key=lambda entry: (entry[0], entry[1]))
    evidence_ticks = {actor: [entry[0] for entry in rows] for actor, rows in evidence_rows.items()}
    for f in findings:
        nearby = []
        if f['t'] is not None:
            for actor in {f['actor'], None}:
                ticks = evidence_ticks.get(actor, [])
                nearby.extend(evidence_rows[actor][bisect_left(ticks, f['t'] - 100):bisect_right(ticks, f['t'] + 100)])
        nearby.sort(key=lambda entry: entry[1])
        f.update(evidence=evidence, seed=seed, rerun=rerun, timeline=[entry[2] for entry in nearby])
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
    raw = ''.join(line for line in timeline.lines(args.logs) if '[trace]' not in line)
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


def report_log(log, output, smoke=None, seed=None, rerun=None, evidence='R', dense=False, only=None):
    """Shared smoke/intake path: stream input, export full timelines, report each trace window independently.

    ``only`` keeps one scene id: a scene's run log also holds the unwound tail of the previous window, which is complete
    only in that scene's own run."""
    smoke = smoke or {}
    records, issues = timeline.read([log])
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    timeline.export(records, output / 'timelines', issues)
    raw = ''.join(line for line in timeline.lines([log]) if '[trace]' not in line)
    if seed is None:
        seeds = set(re.findall(r'\[seed\].*?\brun=(-?\d+)\b', raw))
        seed = int(next(iter(seeds))) if len(seeds) == 1 else None
    reports = []
    for key in dict.fromkeys(timeline.scope(r) for r in records):
        scoped = [r for r in records if timeline.scope(r) == key]
        scene_id = scoped[0]['win']
        if only is not None and scene_id != only:
            continue
        spec = smoke.get(scene_id, {})
        command = [sys.executable, str(Path(__file__).with_name('smoke_museum.py')), '--worktree',
                   str(Path(__file__).resolve().parent.parent)]
        if seed is not None:
            command += ['--seed', str(seed)]
        if scene_id is not None:
            command.append(scene_id)
        scene_rerun = rerun or subprocess.list2cmdline(command)
        # Legacy events carry tick/dimension/actor identity. Do not attribute other windows' fates to this one.
        first_tick, last_tick = min(r['t'] for r in scoped), max(r['t'] for r in scoped)
        actors = {r['u'] for r in scoped if r['u'] is not None}
        legacy = ''.join(line for line in raw.splitlines(keepends=True) if (event := EVENT.search(line))
                         and event['uuid'] in actors and first_tick <= int(re.search(r'\bt=(\d+)', line)[1]) <= last_tick
                         and f'dim={key[1]} ' in line)
        reports.append(dict(scope=key, scene=scene_id, **observations(scoped, issues, legacy,
                       spec.get('allow_fate', ()), spec.get('end', ()), seed, scene_rerun, evidence, dense, end_raw=raw)))
    if not reports:
        reports.append(dict(scene=only, **observations([], issues, raw, seed=seed, rerun=rerun, evidence=evidence, dense=dense)))
    (output / 'checks.json').write_text(json.dumps(reports, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return reports


if __name__ == '__main__':
    raise SystemExit(main())
