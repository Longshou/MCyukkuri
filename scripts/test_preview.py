"""Offline CLI boundary checks; no game, browser or capture process."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from PIL import Image
from preview import ffmpeg_path

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

    def test_video_preserves_long_tick_gaps(self):
        source = self.base/'capture'
        source.mkdir()
        rows = []
        for tick, color in [(1, 'red'), (40, 'green'), (200, 'blue'), (2600, 'white')]:
            name = f'{tick}.png'
            Image.new('RGB', (32, 32), color).save(source/name)
            rows.append(dict(tick=tick, file=name))
        (source/'frames.json').write_text(json.dumps(rows))
        result = self.run_cli('capture', 'boundary', '--source', str(source))
        self.assertEqual(result.returncode, 0, result.stderr)
        # Inspect the encoded container, rather than trusting our duration manifest.
        probe = subprocess.run([str(ffmpeg_path()), '-hide_banner', '-i', str(self.topic/'capture.mp4')],
                               capture_output=True, text=True)
        self.assertIn('Duration: 00:02:10.00', probe.stderr)

    def test_repeated_scene_tick_keeps_render_order_and_timing(self):
        source = self.base/'capture'
        source.mkdir()
        rows, ticks = [], [1, 40, 42, 34, 46]
        for i, tick in enumerate(ticks, start=1):
            name = f'{i}.png'
            Image.new('RGB', (32, 32), 'red').save(source/name)
            rows.append(dict(tick=tick, seconds=i/20, file=name))
        (source/'frames.json').write_text(json.dumps(rows))
        result = self.run_cli('capture', 'boundary', '--source', str(source))
        self.assertEqual(result.returncode, 0, result.stderr)
        frames = json.loads((self.topic/'frames.json').read_text(encoding='utf-8'))['frames']
        # The client clock is corrected backwards to the server's; frames keep their render order.
        self.assertEqual([f['tick'] for f in frames], ticks)
        self.assertAlmostEqual(sum(f['duration'] for f in frames), (39+2+1+12+1)/20)

    def test_frames_out_of_render_order_are_rejected(self):
        source = self.base/'capture'
        source.mkdir()
        rows = []
        for i, seconds in ((1, .5), (2, .4)):
            name = f'{i}.png'
            Image.new('RGB', (32, 32), 'red').save(source/name)
            rows.append(dict(tick=i*10, seconds=seconds, file=name))
        (source/'frames.json').write_text(json.dumps(rows))
        self.assert_rejected(self.run_cli('capture', 'boundary', '--source', str(source)), 'Capture frames must be in render order:')

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
