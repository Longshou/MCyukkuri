"""Capture review CLI time alignment and live output boundaries; no game or model calls."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import capture_review as review


class CaptureReviewTests(unittest.TestCase):
    def setUp(self):
        (Path(__file__).resolve().parents[1]/'.local/tmp').mkdir(parents=True, exist_ok=True)

    def test_absent_selected_clip_rejects_before_ai_review(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1]/'.local/tmp') as temp:
            root = Path(temp)
            view = root/'sleep_face'
            view.mkdir()
            (root/'plan.json').write_text(json.dumps([dict(id='sleep_face', settings={})]), encoding='utf-8')
            (view/'selected-frames.json').write_text(json.dumps([dict(tick=10, poseFrame=2, file='frame.png')]), encoding='utf-8')
            (view/'geometry.json').write_text('{}', encoding='utf-8')
            beats = root/'beats.txt'
            beats.write_text('0s start\n', encoding='utf-8')
            argv = ['capture_review.py', 'sleep', '--reuse', str(root), '--out', str(root/'review'),
                    '--beats', str(beats), '--beat-clip', 'wake']
            with patch('sys.argv', argv), patch.object(review, 'read_pose', return_value=[]), \
                 patch.object(review.storyboard, 'kimi') as ai:
                with self.assertRaisesRegex(ValueError, 'no selected frame contains clip wake'):
                    review.main()
                ai.assert_not_called()

    def test_clip_time_uses_exact_render_frame_and_actor(self):
        beats = '0s start\n20t peak\n2s release\n'
        frame = dict(tick=1400, poseFrame=50)
        poses = [dict(k='pose', f=50, u='a', clip='wake', ct=1.2),
                 dict(k='pose', f=50, u='b', clip='wake', ct=.5),
                 dict(k='pose', f=49, u='a', clip='wake', ct=2),
                 dict(k='pose', f=50, u='c', clip='idle', ct=2)]
        self.assertEqual(review.frame_beat(beats, frame, poses), 'release')
        self.assertEqual(review.frame_beat(beats, frame, poses, 'wake'),
                         'a wake at 1.2s: peak\nb wake at 0.5s: start')
        self.assertEqual(review.frame_beat(beats, dict(frame, poseFrame=51), poses, 'wake'), '')
        self.assertEqual(review.frame_beat(beats, frame, poses, 'absent'), '')

    def test_capture_relays_queue_before_wait_and_retains_failure(self):
        process = MagicMock()
        process.__enter__.return_value = process
        output = io.StringIO()
        def lines():
            yield 'Waiting for slot\n'
            self.assertEqual(output.getvalue(), 'Waiting for slot\n')
            yield 'Capture output: F:/capture\n'
        process.stdout = lines()
        process.wait.return_value = 7
        with patch.object(review.subprocess, 'Popen', return_value=process), contextlib.redirect_stdout(output):
            code, text = review.capture_output(['capture'])
        self.assertEqual(code, 7)
        self.assertEqual(text, 'Waiting for slot\nCapture output: F:/capture\n')
        self.assertEqual(output.getvalue(), text)

    def test_capture_uses_the_streamed_runner(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1]/'.local/tmp') as temp:
            root = Path(temp)
            view = root/'sleep_face'
            view.mkdir()
            (root/'plan.json').write_text(json.dumps([dict(id='sleep_face', settings={})]), encoding='utf-8')
            (view/'selected-frames.json').write_text(json.dumps([dict(tick=10, poseFrame=2, file='frame.png')]), encoding='utf-8')
            (view/'geometry.json').write_text(json.dumps(dict(complete=True, findings=[], no_evidence=[])), encoding='utf-8')
            argv = ['capture_review.py', 'sleep', '--out', str(root/'review')]
            with patch('sys.argv', argv), \
                 patch.object(review, 'capture_output', return_value=(0, f'Capture output: {root}\n')) as streamed, \
                 patch.object(review.subprocess, 'run', side_effect=AssertionError('capture must stream, never buffer')), \
                 patch.object(review.storyboard, 'kimi', return_value=dict(readability='clear', issues=[], seen='visible')):
                self.assertEqual(review.main(), 0)
            self.assertIn('capture_museum.py', ' '.join(streamed.call_args.args[0]))
            self.assertTrue((root/'review/review.json').exists())


if __name__ == '__main__':
    unittest.main()
