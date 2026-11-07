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
            summary = intake.observation_summary(source, dest, {}, 'fixture')
            reports = json.loads((dest / 'observations/checks.json').read_text(encoding='utf-8'))
            self.assertEqual(reports[0]['seed'], 123)
            index = json.loads((dest / 'observations/timelines/index.json').read_text(encoding='utf-8'))
            self.assertEqual(len(index['streams']), 2)
            self.assertTrue(any('END=no-evidence' in row for row in summary))

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


if __name__ == '__main__':
    unittest.main(verbosity=2)
