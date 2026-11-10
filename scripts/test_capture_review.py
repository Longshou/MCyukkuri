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

    def test_absent_clip_reports_each_missing_beat_without_borrowing_idle(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1]/'.local/tmp') as temp:
            root = Path(temp)
            view = root/'sleep_face'
            view.mkdir()
            (root/'plan.json').write_text(json.dumps([dict(id='sleep_face', settings={})]), encoding='utf-8')
            frames = [dict(tick=10, gameTime=10, poseFrame=2, file='frame.png')]
            for name in ('frames.json', 'selected-frames.json'):
                (view/name).write_text(json.dumps(frames), encoding='utf-8')
            (view/'geometry.json').write_text(json.dumps(dict(complete=True, findings=[])), encoding='utf-8')
            beats = root/'beats.txt'
            beats.write_text('0s start\n1s peak\n', encoding='utf-8')
            argv = ['capture_review.py', 'sleep', '--reuse', str(root), '--out', str(root/'review'),
                    '--beats', str(beats), '--beat-clip', 'wake']
            with patch('sys.argv', argv), patch.object(review, 'read_pose', return_value=[]), \
                 patch.object(review.storyboard, 'vision', return_value=dict(readability='clear', issues=[], seen='idle')):
                self.assertEqual(2, review.main())
            report = json.loads((root/'review/review.json').read_text())
            self.assertEqual(2, report['summary']['geometry_no_evidence_samples'])
            self.assertEqual('', report['frames'][0]['beat'])

    def test_unready_camera_excluded_including_reference_and_all_missing(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            frames = [dict(tick=i, poseFrame=i, cameraReady=bool(i), file=f'{i}.png') for i in range(2)]
            (root/'selected-frames.json').write_text(json.dumps(frames), encoding='utf-8')
            geometry = {}
            selected, _ = review.review_frames(root, '', None, geometry)
            self.assertEqual([frames[1]], selected)
            self.assertEqual('camera', geometry['no_evidence'][0]['metric'])
            self.assertEqual(0, geometry['no_evidence'][0]['f'])
            (root/'selected-frames.json').write_text(json.dumps(frames[:1]), encoding='utf-8')
            self.assertEqual([], review.review_frames(root, '', None, geometry)[0])
            self.assertEqual(1, len(geometry['no_evidence']))

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

    def test_cli_sends_nearest_beat_and_never_unready_current_or_reference(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1]/'.local/tmp') as temp:
            root = Path(temp)
            view = root/'sleep_face'
            view.mkdir()
            (root/'plan.json').write_text(json.dumps([dict(id=view.name, settings={})]), encoding='utf-8')
            frames = [dict(tick=i, gameTime=i, poseFrame=i, cameraReady=i != 0, file=f'{i}.png')
                      for i in range(12)]
            (view/'frames.json').write_text(json.dumps(frames), encoding='utf-8')
            (view/'selected-frames.json').write_text(json.dumps([frames[i] for i in (0, 1, 11)]), encoding='utf-8')
            (view/'geometry.json').write_text(json.dumps(dict(complete=True, findings=[], no_evidence=[])), encoding='utf-8')
            poses = [dict(k='pose', u='a', f=i, t=i, pt=0, clip='wake', ct=i*.25,
                          phase='wake', markers=[], m=[]) for i in range(12)]
            beats = root/'beats.txt'
            beats.write_text('1.85s peak\n', encoding='utf-8')
            argv = ['capture_review.py', 'sleep', '--reuse', str(root), '--out', str(root/'review'),
                    '--beats', str(beats), '--beat-clip', 'wake']
            with patch('sys.argv', argv), patch.object(review, 'read_pose', return_value=poses), \
                    patch.object(review.storyboard, 'vision', return_value=dict(readability='clear', issues=[], seen='fixture')) as ai:
                self.assertEqual(2, review.main())  # Retained camera coverage gap.
            images = [call.args[0] for call in ai.call_args_list]
            self.assertIn(str(view/'7.png'), [pair[0] for pair in images])
            self.assertTrue(all(str(view/'0.png') not in pair for pair in images))
            self.assertTrue(all(pair[1] == str(view/'1.png') for pair in images))
            report = json.loads((root/'review/review.json').read_text())
            self.assertEqual(1, report['summary']['geometry_no_evidence_samples'])
            self.assertEqual(0, report['geometry'][view.name]['no_evidence'][0]['f'])

    def test_default_review_dir_never_reuses_a_second_or_a_name(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1]/'.local/tmp') as temp:
            root = Path(temp)
            with patch.object(review, 'ROOT', root), patch.object(review.time, 'strftime', return_value='20261110-093423'):
                first = review.unique_out('carry_home', root/'.worktrees/wp-ef')
                second = review.unique_out('carry_home', root/'.worktrees/wp-ef')
                other = review.unique_out('carry_home', root/'.worktrees/wp-et-tool-gaps')
            self.assertEqual(first.name, 'carry_home-20261110-093423-wp-ef')
            self.assertEqual(second.name, 'carry_home-20261110-093423-wp-ef-2')
            self.assertEqual(other.name, 'carry_home-20261110-093423-wp-et-tool-gaps')
            self.assertEqual([first.is_dir(), second.is_dir(), other.is_dir()], [True, True, True])

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
                 patch.object(review.storyboard, 'vision', return_value=dict(readability='clear', issues=[], seen='visible')):
                self.assertEqual(review.main(), 0)
            self.assertIn('capture_museum.py', ' '.join(streamed.call_args.args[0]))
            self.assertTrue((root/'review/review.json').exists())


if __name__ == '__main__':
    unittest.main()
