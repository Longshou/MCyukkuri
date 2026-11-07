"""Run: <py> scripts/fixtures/timeline/test_timeline.py (stdlib, no game/server)."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import math
import gzip
import subprocess

SCRIPTS = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(SCRIPTS))
import timeline
import smoke_checks as checks
import smoke_fate
from cases import ACTOR, HOST, BASE, fixture, log, record, state


class Checks(unittest.TestCase):
    def test_labelled_fixtures(self):
        for check in checks.CHECKS:
            for kind in ('positive', 'clean', 'missing'):
                with self.subTest(check=check, fixture=kind):
                    text, end, expected = fixture(check, kind)
                    records, issues = timeline.parse([text])
                    report = checks.observations(records, issues, text, end=end, seed=123, rerun='fixture', evidence='F')
                    actual = next(r['verdict'] for r in report['summary'] if r['check'] == check)
                    self.assertEqual(expected, actual)
                    print(f'{check}/{kind}: {actual}')

    def test_delta_null_window_and_lifecycle(self):
        rows = [state(0), record('st', 1, st={'p': [1, 0, 0]}), record('st', 2, st={'hp': None}),
                record('life', 3, out='unloaded'), record('st', 4, st={'p': [4, 0, 0]})]
        samples = timeline.states(rows)
        self.assertEqual(samples[1]['state']['hp'], 10)
        self.assertIsNone(samples[2]['state']['hp'])
        self.assertNotIn('hp', samples[3]['state'])

    def test_rotation_duplicates_gap_and_conflict(self):
        text = log([state(0), state(1), state(2)])
        rows, issues = timeline.parse([text, text])
        self.assertEqual(len(rows), 3)
        self.assertFalse(issues)
        gapped = '\n'.join([text.splitlines()[0], text.splitlines()[2]])
        rows, issues = timeline.parse([gapped])
        self.assertTrue(any('gap' in i for i in issues))
        report = checks.observations(rows, issues, gapped)
        self.assertFalse(any(r['verdict'] == 'pass' for r in report['summary']))
        _, issues = timeline.parse([text, text.replace('"hp": 10', '"hp": 9')])
        self.assertTrue(any('conflicting' in i for i in issues))

    def test_export_world_and_actors(self):
        rows, issues = timeline.parse([log([state(0), record('lag', 0, None, ms=20)])])
        with tempfile.TemporaryDirectory() as folder:
            index = timeline.export(rows, folder, issues)
            self.assertEqual(len(index['streams']), 2)
            self.assertTrue(any(s['path'].endswith('world.jsonl') for s in index['streams']))
            for stream in index['streams']:
                self.assertTrue((Path(folder) / stream['path']).is_file())

    def test_free_motion_controls(self):
        for name, rows in {
            'acceleration': [state(0, v=[.2, 0, 0]), state(1, p=[.3, 0, 0])],
            'jump': [state(0, v=[0, .5, 0], g=False), state(1, p=[0, .5, 0], g=False)],
            'step': [state(0), state(1, p=[0, .6, 0])],
            'mount': [state(0), state(1, p=[.1, 0, 0], att={'host': HOST})],
            'handover': [state(0, att={'host': HOST}), state(1, p=[.1, 0, 0], att={'host': ACTOR})],
        }.items():
            with self.subTest(control=name):
                self.assertEqual(checks.jumps(timeline.states(rows)), [])

    def test_rotating_translating_hat(self):
        frame_a = dict(origin=[0, 0, 0], unit=1, yaw=0, pitch=0, roll=0)
        frame_b = dict(origin=[2, 0, 0], unit=1, yaw=math.pi / 2, pitch=0, roll=0)
        rows = [state(0, p=[1, 0, 0], att={'host': HOST, 'frame': frame_a}),
                state(1, p=[2, 0, -1], att={'host': HOST, 'frame': frame_b})]
        self.assertEqual(checks.jumps(timeline.states(rows)), [])

    def test_inventory_controls(self):
        apple = copy.deepcopy(BASE['hold']) | {'mouth': [{'item': 'minecraft:apple', 'n': 1}]}
        for action in ('eat', 'place', 'pickup', 'unloaded'):
            rows = [state(0, hold=apple), state(1)]
            rows.append(record('life', 1, out=action) if action == 'unloaded' else record('ev', 1, cat='meal', msg=action, pos=[0, 0, 0]))
            self.assertFalse(any(r['verdict'] in ('fail', 'suspect') for r in checks.ledger(rows, timeline.states(rows))))

    def test_legacy_fate_gate_parity(self):
        prefix = f'marisa #1 uuid={ACTOR} t=1 dim=minecraft:overworld pos=0, 0, 0 '
        lines = ['[body] ' + prefix + 'death settled into remains; cause=fall',
                 '[body] ' + prefix + 'core hurt (fall), dying for 20',
                 '[water] ' + prefix + 'damage cause=drown',
                 '[body] ' + prefix + 'in wall damage',
                 '[needs] ' + prefix + 'starvation damage accepted=true',
                 '[action] ' + prefix + 'refused reason=contact (suppressed=399)']
        text = '\n'.join(lines)
        self.assertEqual(len(checks.fates(text)), 5)
        allow = ({'kind': 'death', 'why': 'fixture', 'who': ACTOR, 'match': 'fall'},)
        problems = smoke_fate.fates(text, allow)
        self.assertEqual(len(problems), 4)
        self.assertFalse(any(reason.startswith('fate death') for reason, _ in problems))
        self.assertEqual(checks.LOOP_BOUND, 400)
        self.assertEqual(len(checks.fates(lines[-1].replace('399', '398'))), 0)
        self.assertEqual(len(checks.fates(lines[-1])), 1)

    def test_repeated_scene_windows_are_separate(self):
        text = log([record('win', 0, None), state(1), record('win', 100, None),
                    record('st', 101, st={'p': [0, 0, 0]})])
        rows, issues = timeline.parse([text])
        self.assertFalse(issues)
        self.assertNotEqual(timeline.scope(rows[0]), timeline.scope(rows[2]))
        self.assertNotIn('hp', timeline.states(rows)[-1]['state'])

    def test_actual_recorder_null_action_vehicle_and_parent_map(self):
        rows = [state(t, act=None, int=None, veh=None, parents={'mother': None, 'father': None}) for t in (0, 1)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        self.assertFalse(checks.orphans(timeline.states(rows)))

    def test_deadline_and_eating_are_progress(self):
        for mode in ('deadline', 'eating'):
            rows = [state(t, int={'id': 'eat'}, act={'deadline': 2000} if mode == 'deadline' else {},
                          need={'nut': t if mode == 'eating' else 0}) for t in range(1202)]
            self.assertFalse(any(r['verdict'] == 'fail' for r in checks.progress(timeline.states(rows))))

    def test_stutter_and_stall(self):
        rows = [state(t, p=[(t // 2) * .1, 0, 0]) for t in range(40)]
        self.assertTrue(any(r['detail'] == 'dense stutter cycles' and r['verdict'] == 'fail' for r in checks.progress(timeline.states(rows))))
        text = log([record('lag', 0, None, stall_ms=6000, stack=['fixture.Block.wait'])])
        records, issues = timeline.parse([text])
        report = checks.observations(records, issues, text)
        self.assertTrue(any(f['check'] == 'LAG' and f.get('stack') for f in report['findings']))

    def test_unknown_inventory_and_geometry(self):
        rows = [state(t, hold=None) for t in (0, 1)]
        self.assertTrue(any(r['verdict'] == 'no-evidence' for r in checks.ledger(rows, timeline.states(rows))))
        text, _, _ = fixture('SPAWN', 'clean')
        records, issues = timeline.parse([text])
        for r in records:
            if r['k'] == 'geo':
                r['drop'] = None
        report = checks.observations(records, issues, text)
        self.assertEqual(next(r['verdict'] for r in report['summary'] if r['check'] == 'SPAWN'), 'no-evidence')

    def test_ledger_actor_entry_and_dip(self):
        new = state(5)
        new['u'] = HOST
        rows = [state(0), new]
        self.assertTrue(any('actor appeared' in r['detail'] for r in checks.ledger(rows, timeline.states(rows))))
        rows.append(record('life', 4, HOST, **{'in': 'birth'}))
        self.assertFalse(any('actor appeared' in r['detail'] for r in checks.ledger(rows, timeline.states(rows))))
        left = state(0)
        left['u'] = HOST
        vanished = [left, state(0), state(1)]
        self.assertTrue(any('disappeared' in r['detail'] for r in checks.ledger(vanished, timeline.states(vanished), dense=True)))
        vanished.append(record('life', 1, HOST, out='unloaded'))
        self.assertFalse(any('disappeared' in r['detail'] for r in checks.ledger(vanished, timeline.states(vanished), dense=True)))
        apple = copy.deepcopy(BASE['hold']) | {'mouth': [{'item': 'minecraft:apple', 'n': 1}]}
        for duration, verdict in ((4, 'suspect'), (21, 'fail')):
            rows = [state(0, hold=apple), state(1), state(1 + duration, hold=apple)]
            self.assertTrue(any(r['verdict'] == verdict for r in checks.ledger(rows, timeline.states(rows))))

    def test_light_end_time_weights_and_seen(self):
        rows = [state(t, att={'place': 'cabin'}) for t in range(0, 201, 40)]
        self.assertEqual(checks.endings(rows, timeline.states(rows), [('actor', 'place=cabin')])[0]['verdict'], 'pass')
        rows += [record('ev', 150, cat='family', msg='real lift')]
        self.assertEqual(checks.endings(rows, timeline.states(rows), [('actor', 'seen:family:real lift')])[0]['verdict'], 'pass')
        dead = [state(0), record('life', 1, out='killed'), record('lag', 100, None, ms=20)]
        self.assertEqual(checks.endings(dead, timeline.states(dead), [('actor', 'alive=false')])[0]['verdict'], 'pass')

    def test_invalid_external_records(self):
        for payload in ('[]', '{', json.dumps(dict(state(1), st=[])), json.dumps(dict(state(1), st={'p': [1]}))):
            rows, issues = timeline.parse(['[trace] ' + payload])
            self.assertFalse(rows)
            self.assertTrue(issues)

    def test_cli_report_only_and_legacy_exit_codes(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'failure.log.gz'
            text, _, _ = fixture('JUMP', 'positive')
            with gzip.open(path, 'wt', encoding='utf-8') as f:
                f.write(text)
            output = Path(folder) / 'checks.json'
            run = subprocess.run([sys.executable, str(SCRIPTS / 'smoke_checks.py'), str(path), '--output', str(output)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertTrue(json.loads(output.read_text(encoding='utf-8'))[0]['report_only'])
            death = Path(folder) / 'death.log'
            death.write_text(fixture('FATE', 'positive')[0], encoding='utf-8')
            run = subprocess.run([sys.executable, str(SCRIPTS / 'smoke_fate.py'), str(death)], capture_output=True)
            self.assertEqual(run.returncode, 1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
