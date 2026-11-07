"""Offline CLI boundary checks; no game, browser or capture process."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PreviewBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.out = self.base/'previews'
        self.topic = self.out/'boundary'
        self.topic.mkdir(parents=True)
        (self.topic/'index.html').write_text('current page', encoding='utf-8')

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(ROOT/'scripts/preview.py'), *args],
                              env={**os.environ, 'MCYUKKURI_PREVIEWS': str(self.out), 'PYTHONUTF8': '1'},
                              text=True, encoding='utf-8', capture_output=True)

    def assert_rejected(self, result, message):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(message, result.stderr)
        self.assertEqual((self.topic/'index.html').read_text(), 'current page')
        self.assertEqual(sorted(p.name for p in self.topic.iterdir()), ['index.html'])
        self.assertFalse((self.out/'library.json').exists())

    def test_missing_frame_keeps_current_page(self):
        source = self.base/'capture'
        source.mkdir()
        (source/'frames.json').write_text(json.dumps([{'tick': 1, 'file': 'absent.png'}]))
        self.assert_rejected(self.run_cli('capture', 'boundary', '--source', str(source)), 'Missing frame:')

    def test_bad_beat_time_keeps_current_page(self):
        beats = self.base/'beats.txt'
        beats.write_text('panel-1 lift\n')
        self.assert_rejected(self.run_cli('strip', 'boundary', '--model', 'reimu', '--beats', str(beats)), 'Bad beat time:')

    def test_beat_beyond_clip_duration_is_rejected(self):
        self.assert_rejected(self.run_cli('strip', 'boundary', '--model', 'reimu', '--times', '999999s'), 'Beat time exceeds clip duration')

    def test_unknown_clip_keeps_current_page(self):
        self.assert_rejected(self.run_cli('strip', 'boundary', '--model', 'reimu', '--clip', 'no_such_clip'), 'Unknown clip reimu/no_such_clip')

    def test_inbox_import_add_remove_and_hub(self):
        target = self.out/'inbox'
        target.mkdir()
        (target/'index.html').write_text('<ul><li><a href="../life-review/index.html">Existing title</a>'
                                        '<small>要你决定：Existing decision</small></li></ul>', encoding='utf-8')
        result = self.run_cli('inbox', 'add', 'boundary', '--title', 'New title', '--url', '../boundary/index.html', '--decision', 'New decision')
        self.assertEqual(result.returncode, 0, result.stderr)
        items = json.loads((target/'items.json').read_text(encoding='utf-8'))
        self.assertEqual(items[0], dict(id='life-review', title='Existing title', decision='Existing decision', url='../life-review/index.html'))
        result = self.run_cli('inbox', 'remove', 'boundary')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(json.loads((target/'items.json').read_text(encoding='utf-8'))), 1)
        library = json.loads((self.out/'library.json').read_text(encoding='utf-8'))
        self.assertIn('page:boundary', [entry['id'] for entry in library['entries']])


if __name__ == '__main__':
    unittest.main(verbosity=2)
