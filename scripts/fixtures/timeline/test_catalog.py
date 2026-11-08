"""F evidence for research-failures A1–A28; these are injected faults, not old runs.

Historical logs cannot reconstruct absent positions, inventories or tick timing.
Each row states the observable failure class and has an independent clean and
missing-data control. The report separately links actual H/R witnesses.
"""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import smoke_checks as checks
import timeline
from cases import ACTOR, HOST, fixture, record, state, log


CATALOG = {
    1: ('FATE', 'drown damage'), 2: ('FATE', 'soaking death'),
    3: ('LOOP', 'shore hop refusal'), 4: ('LOOP', 'full hull refusal'),
    5: ('LOOP', 'newborn contact refusal'), 6: ('LOOP', 'slave contact refusal'),
    7: ('FATE', 'thrown child drowned; does not reproduce the historical 3/5 rate'),
    8: ('PROGRESS', 'forage return stalled; no historical stock reconstruction'),
    9: ('PROGRESS', 'paddle stop/go; does not judge current drift design'),
    10: ('LOOP', 'hungry child no_progress'), 11: ('FATE', 'inWall cry'),
    12: ('LAG', 'measured slow tick; historical 265-tick backlog is not a duration'),
    13: ('LAG', '7423 ms search tick'), 14: ('LAG', 'watchdog stall with stack'),
    15: ('JUMP', 'child free-space teleport'), 16: ('JUMP', 'hat placement jump'),
    17: ('LEDGER', 'server child appeared without lifecycle entry'),
    18: ('LEDGER', 'stick missing during rescue then reappears'),
    19: ('LOOP', 'stationary completed face churn'),
    20: ('LOOP', 'same child attachment A-B-A churn'),
    21: ('PROGRESS', 'mother stalled for 36 seconds; nest-rim geometry is not reconstructed'),
    22: ('END', 'brim is not cabin'),
    23: ('END', 'parent departs from child; safe cabin alone is not reunion'),
    24: ('LOOP', 'stem catcher known_impossible'),
    25: ('PROGRESS', 'stationary rescue yaw reversals'),
    26: ('LOOP', 'reunite LOST repetitions'),
    27: ('LOOP', 'migration urgent_need refusal'),
    28: ('END', 'assignment refusal never becomes a lift'),
}


def catalog_input(number, broken):
    rows, raw, end = [state(0), state(200)], '', ()
    if number in (1, 2, 7, 11):
        cat, msg = {
            1: ('water', 'damage cause=drown health=10.0 -> 9.0 loss=1.0'),
            2: ('body', 'death settled into remains; cause=mcyukkuri.soaking'),
            7: ('water', 'damage cause=drown health=1.0 -> 0.0 loss=1.0'),
            11: ('childhood', 'hurt cry start cause=inWall'),
        }[number]
        if not broken:
            msg = 'healthy observation'
        raw = f'[{cat}] marisa #1 uuid={ACTOR} t=1 dim=minecraft:overworld pos=0, 0, 0 {msg}'
    elif number in (3, 4, 5, 6, 10, 19, 24, 26, 27):
        cat, msg = {
            3: ('aquatic', 'hop shore stand refused reason=hop_arc'),
            4: ('aquatic', 'board refused reason=hull_full'),
            5: ('approach', 'contact stand unavailable peer=' + HOST),
            6: ('approach', 'contact stand unavailable peer=' + HOST),
            10: ('action', 'end id=walk outcome=BLOCKED reason=no_progress'),
            19: ('action', 'end id=face outcome=DONE detail=yaw=0'),
            24: ('action', 'end id=walk outcome=BLOCKED reason=known_impossible'),
            26: ('intent', 'end id=reunite result=LOST'),
            27: ('settlement', 'goal refused reason=urgent_need'),
        }[number]
        rows = [state(t, int={'id': 'work'}, p=[0 if broken else t * .6, 0, 0]) for t in range(27)]
        rows += [record('ev', t, cat=cat, msg=msg, pos=[0 if broken else t, 0, 0]) for t in range(1, 26)]
    elif number in (8, 21):
        rows = [state(t, goals=['ForageGoal' if number == 8 else 'BirthGoal'],
                      int={'id': 'return_home' if number == 8 else 'nest_approach'},
                      p=[0 if broken else t * .02, 0, 0]) for t in range(1202 if number == 8 else 722)]
    elif number in (9, 25):
        rows = [state(t, int={'id': 'paddle' if number == 9 else 'rescue'},
                      p=[(t // 2) * .03 if broken and number == 9 else t * .03 if not broken else 0, 0, 0],
                      yaw=(30 if t % 4 < 2 else -30) if broken and number == 25 else 0) for t in range(101)]
    elif number in (12, 13, 14):
        values = {'stall_ms': 6000 if broken else 100, 'stack': ['Server thread: Birth.tick']}
        if number != 14:
            values = {'ms': (600 if number == 12 else 7423) if broken else 30, 'rec_ms': .1}
        rows.append(record('lag', 1, None, **values))
    elif number in (15, 16):
        rows = [state(0), state(1, p=[3 if broken else .2, 0, 0])]
        if number == 16:
            for r in rows:
                r['st'].pop('stage')
                r['st']['riders'] = []
    elif number == 17:
        rows += [state(200, stage='baby', parents={'mother': ACTOR}) | {'u': HOST}]
        if not broken:
            rows.append(record('life', 200, HOST, **{'in': 'birth'}))
    elif number == 18:
        stick = {'item': 'minecraft:stick', 'n': 1}
        rows = [state(0, hold={'kit': stick}), state(1, hold={'kit': {} if broken else stick}),
                state(30, hold={'kit': stick})]
    elif number == 20:
        rows = [state(t, att={'kind': 'held' if t % 2 else 'free', 'host': HOST if t % 2 else None},
                      veh=HOST if t % 2 else None, p=[0 if broken else t * .6, 0, 0]) for t in range(15)]
    elif number == 22:
        rows = [state(t, att={'kind': 'free', 'place': 'brim' if broken else 'cabin'}) for t in range(201)]
        end = [('actor', 'place=cabin')]
    elif number == 23:
        rows = [state(t, tags=['scn_parent']) for t in (0, 200)]
        rows += [state(t, tags=['scn_child'], parents={'mother': ACTOR},
                       p=[8 if broken else 1, 0, 0]) | {'u': HOST} for t in (0, 200)]
        end = {'final': [('child', 'near.parent<=2')]}
    elif number == 28:
        end = [('actor', 'seen:family:carry pickup hold=')]
        rows.append(record('ev', 20, cat='family', msg='assignment refused target=null' if broken
                           else 'carry pickup hold=mouth child=' + HOST, pos=[0, 0, 0]))
    return rows, raw, end


def catalog_report(number, kind):
    rows, raw, end = catalog_input(number, kind == 'positive')
    if kind == 'missing':
        rows, raw = [], ''
    records, issues = timeline.parse([log(rows)])
    assert not issues, issues
    return checks.observations(records, raw=raw, end=end, evidence='F', dense=True)


# D rows are setup geometry/visibility symptoms without an actor series of their
# own; the shared SPAWN and VANTAGE controls are their committed evidence.
# B rows 29-36 are the named pose metrics fixtures, C rows 37-40 have no check.
GEOMETRY = {
    41: ('SPAWN', 'start inside the wall'),
    42: ('SPAWN', 'blocked or unsupported start'),
    43: ('SPAWN', 'fall point'),
    44: ('VANTAGE', 'approach visibility'),
}
NO_CHECK = range(37, 41)
POSE_SOURCE = Path(__file__).resolve().parents[3] / 'src/test/java/io/github/longshou/mcyukkuri/pose/PoseMetricsTest.java'


def geometry_report(number, kind):
    check, _ = GEOMETRY[number]
    text, end, expected = fixture(check, kind)
    records, issues = timeline.parse([text])
    assert not issues, issues
    return checks.observations(records, issues, text, end=end, evidence='F'), expected


class Catalog(unittest.TestCase):
    def test_all_server_catalog_rows_and_controls(self):
        for number, (check, symptom) in CATALOG.items():
            for kind in ('positive', 'clean', 'missing'):
                with self.subTest(row=number, symptom=symptom, kind=kind):
                    report = catalog_report(number, kind)
                    verdict = next(r['verdict'] for r in report['summary'] if r['check'] == check)
                    self.assertIn(verdict, ('suspect', 'fail') if kind == 'positive' else
                                  ('pass',) if kind == 'clean' else ('no-evidence',))

    def test_geometry_rows_and_controls(self):
        for number, (check, symptom) in GEOMETRY.items():
            for kind in ('positive', 'clean', 'missing'):
                with self.subTest(row=number, symptom=symptom, kind=kind):
                    report, expected = geometry_report(number, kind)
                    verdict = next(r['verdict'] for r in report['summary'] if r['check'] == check)
                    self.assertEqual(expected, verdict)

    def test_all_catalog_rows_name_their_proof(self):
        self.assertEqual(sorted(CATALOG), list(range(1, 29)))
        self.assertEqual(sorted(GEOMETRY), [41, 42, 43, 44])
        self.assertEqual(list(NO_CHECK), [37, 38, 39, 40])
        pose = POSE_SOURCE.read_text(encoding='utf8')
        self.assertEqual([n for n in range(29, 37) if f'void row{n}' not in pose], [])


if __name__ == '__main__':
    unittest.main()
