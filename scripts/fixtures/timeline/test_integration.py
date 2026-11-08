"""Integration boundary tests for streamed logs, seed/JFR metadata and intake artifacts (no game)."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SCRIPTS = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(SCRIPTS))
import headless_server as server
import feedback_intake as intake
import smoke_checks
import smoke_museum
import timeline
from cases import state, record, log
from cases import ACTOR


class Integration(unittest.TestCase):
    def test_intake_writes_windows_and_results(self):
        with tempfile.TemporaryDirectory() as folder:
            dest = Path(folder)
            source = dest / 'session.log'
            source.write_text('[seed] run=123 covered=fixture\n' +
                              log([record('win', 0, None), state(1), state(2)]), encoding='utf-8')
            summary = intake.observation_summary(source, dest, {})
            reports = json.loads((dest / 'observations/checks.json').read_text(encoding='utf-8'))
            self.assertEqual(reports[0]['seed'], 123)
            index = json.loads((dest / 'observations/timelines/index.json').read_text(encoding='utf-8'))
            self.assertEqual(len(index['streams']), 2)
            self.assertTrue(any('END=no-evidence' in row for row in summary))

    def test_only_reports_the_requested_scene_window(self):
        # A run log also holds the unwound tail of the previous scene's window; its complete copy is that scene's own run.
        tail = [record('st', 1, st=dict(p=[0, 0, 0], v=[0, 0, 0], yaw=0, g=True, hp=10, att={}, veh=False, act={},
                                      int={}, goals=[], phase='', hold=None, dep=False, distress=False,
                                      iwr=False, parents=[])),
                record('lag', 1, None, ms=1, rec_ms=.1)]
        def window(win, t):
            return [dict(record('win', t, None, scene=win, field='land'), win=win)] + \
                   [dict(row, t=row['t'] + t, win=win) for row in tail]
        text = log(window('earlier', 0) + window('later', 9))
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'window.log'
            source.write_text(text, encoding='utf-8')
            reports = smoke_checks.report_log(source, Path(folder) / 'out', {'later': {}}, only='later')
            self.assertEqual([row['scene'] for row in reports], ['later'])
            kept = json.loads((Path(folder) / 'out/checks.json').read_text(encoding='utf-8'))
            self.assertEqual([row['scene'] for row in kept], ['later'])
            all_windows = smoke_checks.report_log(source, Path(folder) / 'all', {'later': {}})
            self.assertEqual([row['scene'] for row in all_windows], ['earlier', 'later'])

    def test_stream_interval_and_incremental_poll(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'logs').mkdir()
            source = root / 'logs/latest.log'
            # A dense-sized prefix; the cursor must not read it again after each unsuccessful poll.
            with source.open('wb') as output:
                for _ in range(1024):
                    output.write(b'x' * 65535 + b'\n')
            size = source.stat().st_size
            st = dict(rundir=str(root), pid=1, started=2)
            with source.open('ab') as output:
                output.write(b'first\nsecond\n')
            self.assertEqual(list(server.log_lines(st, size, size + 6)), ['first\n'])
            reads, polls = [], []
            original_open = Path.open

            class Reader:
                def __init__(self, file):
                    self.file = file
                def __enter__(self):
                    return self
                def __exit__(self, *args):
                    self.file.close()
                def seek(self, offset):
                    return self.file.seek(offset)
                def read(self, n):
                    value = self.file.read(n)
                    reads.append(len(value))
                    return value

            def opened(path, *args, **kwargs):
                file = original_open(path, *args, **kwargs)
                return Reader(file) if path == source and args == ('rb',) else file

            def advance(_):
                polls.append(True)
                if len(polls) == 2:
                    with original_open(source, 'ab') as output:
                        output.write(b'MCY_TEST_READY\n')

            with patch.object(Path, 'open', opened), patch.object(server, 'process_started', return_value=2), patch.object(server.time, 'sleep', advance):
                self.assertIn('MCY_TEST_READY', server.wait_log(st, 'MCY_TEST_READY'))
            self.assertLess(sum(reads), size + 1024)
            self.assertEqual(len(polls), 2)

    def test_jfr_server_method_summary(self):
        frame = {'method': {'type': {'name': 'test/Busy'}, 'name': 'work'}}
        events = [{'values': {'sampledThread': {'javaName': name}, 'stackTrace': {'frames': [frame]}}}
                  for name in ('Server thread', 'Render thread', 'Server thread')]
        self.assertEqual(server.sample_methods(events), [{'method': 'test.Busy.work', 'samples': 2}])

    def test_namespaced_accepted_damage_is_reported(self):
        records, issues = timeline.parse([log([record('ev', 1, cat='body',
                          msg='accepted damage cause=mcyukkuri.soaking health=10 -> 9 loss=1')])])
        report = smoke_checks.observations(records, issues)
        self.assertTrue(any(r['check'] == 'FATE' and r['verdict'] == 'fail' for r in report['findings']))

    def test_structured_starvation_honors_existing_allowance(self):
        prefix = f'marisa #1 uuid={ACTOR} t=1 dim=minecraft:overworld pos=0, 0, 0 '
        text = '[needs] ' + prefix + 'starvation damage accepted=true\n'
        text += '[body] ' + prefix + 'accepted damage cause=starve health=2 -> 1 loss=1\n'
        records, issues = timeline.parse([log([record('ev', 1, cat='body', msg='accepted damage cause=starve health=2 -> 1 loss=1')])])
        allow = ({'kind': 'starve', 'who': ACTOR, 'match': 'starve|starvation', 'why': 'intentional fixture'},)
        report = smoke_checks.observations(records, issues, raw=text, allow=allow)
        self.assertFalse(any(r['check'] == 'FATE' and r['verdict'] == 'fail' for r in report['findings']))
        report = smoke_checks.observations(records, issues, raw=text)
        self.assertTrue(any(r['check'] == 'FATE' and r['verdict'] == 'fail' for r in report['findings']))

    def test_placed_hat_own_motion_uses_its_recorded_schema(self):
        hat = dict(p=[0, 0, 0], v=[.1, 0, 0], frame={}, riders=[], kit=None)
        for x, expected in ((.1, False), (3, True)):
            rows = [record('st', 0, st=hat), record('st', 1, st=hat | {'p': [x, 0, 0]})]
            findings = smoke_checks.jumps(timeline.states(rows))
            self.assertEqual(any(r['verdict'] == 'fail' for r in findings), expected)


class GatedChecks(unittest.TestCase):
    """Which report verdicts decide the smoke run (see GATING_CHECKS in smoke_museum)."""

    @staticmethod
    def report(*findings):
        return dict(scene='probe', findings=list(findings))

    def test_gating_check_fail_is_a_scene_problem(self):
        orphan = smoke_checks.result('ORPHAN', 'fail', 'dependent orphaned in hazard', dict(u=ACTOR, t=40))
        self.assertEqual(smoke_museum.gated_problems([self.report(orphan)]),
                         [('ORPHAN', f'ORPHAN dependent orphaned in hazard (actor={ACTOR} t=40)')])
        spawn = smoke_checks.result('SPAWN', 'fail', 'spawn obstructed/unsupported', None, drop=.2)
        self.assertEqual(smoke_museum.gated_problems([self.report(spawn)]),
                         [('SPAWN', 'SPAWN spawn obstructed/unsupported (actor=None t=None)')])

    def test_report_only_checks_and_softer_verdicts_never_gate(self):
        for finding in (smoke_checks.result('ORPHAN', 'suspect', 'early scene refusal', dict(u=ACTOR, t=40)),
                        smoke_checks.result('SPAWN', 'no-evidence', 'need window start and geometry at +1/+20 ticks'),
                        smoke_checks.result('LAG', 'fail', 'tick work (ms)', None, ms=80),
                        smoke_checks.result('VANTAGE', 'suspect', 'subject poorly visible from vantage', dict(u=ACTOR, t=20)),
                        smoke_checks.result('FATE', 'fail', 'accepted damage cause=fall', dict(u=ACTOR, t=50))):
            self.assertEqual(smoke_museum.gated_problems([self.report(finding)]), [], finding)

    def test_one_gated_failure_inside_a_mixed_report_still_decides_it(self):
        report = self.report(smoke_checks.result('LOOP', 'fail', 'stalled worker', dict(u=ACTOR, t=10)),
                             smoke_checks.result('SPAWN', 'fail', 'first actor sample inside wall', dict(u=ACTOR, t=1)))
        self.assertEqual(smoke_museum.gated_problems([report]),
                         [('SPAWN', f'SPAWN first actor sample inside wall (actor={ACTOR} t=1)')])


if __name__ == '__main__':
    unittest.main(verbosity=2)
