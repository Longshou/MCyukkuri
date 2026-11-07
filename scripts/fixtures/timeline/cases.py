"""Labelled F evidence from observation-layer §1–2. Each fixture is a complete in-memory log.

UUIDs and ticks are fixed to make injected failures reviewable; no Minecraft or random simulation.
Run test_timeline.py to exercise the fixtures, including clean motion and inventory controls.
"""
import copy
import json

ACTOR = '00000000-0000-0000-0000-000000000001'
HOST = '00000000-0000-0000-0000-000000000002'
BASE = dict(p=[0, 0, 0], v=[0, 0, 0], yaw=0, by=0, g=True, w=False, wr=False,
            iw=False, iwr=False, hp=10, att=dict(kind='ground', host=None, place='ground'),
            veh=False, act={}, int={}, goals=[], phase='',
            hold=dict(braid=[], mouth=[], kit={}, hat={}, carry=[]),
            dep=False, distress=False, stage='adult', parents=[], home=[0, 0, 0],
            need=dict(nut=10, en=10, wet=0), scale=1, alive=True, tags=['scn_actor'])


def record(k, t, u=ACTOR, **values):
    return dict(v=1, run='fixture', seq=0, t=t, dim='minecraft:overworld', win='fixture', u=u,
                k=k, src='server', **values)


def state(t, **values):
    return record('st', t, st=copy.deepcopy(BASE) | values)


def log(rows):
    rows = sorted(rows, key=lambda r: r['t'])
    return '\n'.join('[trace] ' + json.dumps(dict(r, seq=i)) for i, r in enumerate(rows))


def fixture(check, kind):
    """Return text, END clauses, expected report band. Missing cases remove real required evidence."""
    if kind == 'missing':
        return log([record('win', 0, None, scene='fixture', field='land')]), (), 'no-evidence'
    positive = kind == 'positive'
    rows = [state(0), state(1), state(100)]
    end = ()
    if check == 'FATE':
        text = '[body] marisa #1 uuid=' + ACTOR + ' t=1 dim=minecraft:overworld pos=0, 0, 0 '
        text += 'death settled into remains; cause=fall' if positive else 'healthy observation'
        return text, (), 'fail' if positive else 'pass'
    if check == 'LOOP':
        rows = [state(t, int={'id': 'travel'}) for t in range(27)]
        rows += [record('ev', t, cat='action', msg='end id=face outcome=BLOCKED reason=contact', pos=[0, 0, 0], sup=0)
                 for t in range(1, 26)] if positive else [record('ev', 1, cat='action', msg='end id=face outcome=OK', pos=[0, 0, 0])]
    elif check == 'PROGRESS':
        rows = [state(t, int={'id': 'travel'}, p=[0 if positive else t * .01, 0, 0]) for t in range(1202)]
    elif check == 'JUMP':
        rows = [state(0), state(1, p=[3 if positive else .2, 0, 0])]
    elif check == 'LEDGER':
        rows = [state(0), state(1, hold=copy.deepcopy(BASE['hold']) | {'mouth': [{'item': 'minecraft:apple', 'n': 1}]})]
        if not positive:
            rows.append(record('ev', 1, cat='interaction', msg='pickup item=minecraft:apple', pos=[0, 0, 0]))
    elif check == 'ORPHAN':
        rows = [state(t, dep=True, distress=positive, parents=[HOST]) for t in (0, 1, 100)]
    elif check == 'END':
        end = (('actor', 'place=cabin and hp>=5'),)
        rows = [state(t, att={'place': 'ground' if positive else 'cabin', 'host': HOST}) for t in range(101)]
    elif check == 'LAG':
        rows = [record('lag', t, None, ms=600 if positive else 30, rec_ms=.2) for t in range(3)]
    elif check in ('SPAWN', 'VANTAGE'):
        rows = [record('win', 0, None, scene='fixture', field='land'), state(1), state(20)]
        rows += [record('geo', t, clear=not (positive and check == 'SPAWN'), drop=0, player=True,
                        vis=2 if positive and check == 'VANTAGE' else 9, scr=.2) for t in (1, 20)]
    return log(rows), end, ('suspect' if check == 'VANTAGE' else 'fail') if positive else 'pass'
