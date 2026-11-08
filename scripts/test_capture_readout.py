import unittest
import json
import tempfile
from pathlib import Path
from unittest.mock import patch
from capture_readout import select_frames, beat_at, geometry_report, postprocess


class CaptureReadoutTest(unittest.TestCase):
    def test_real_paddle_phase_without_blade_contract_is_missing_evidence(self):
        from pose_client import request
        # boat_paddle in the sail baseline declares power markers, not stroke.depth.
        fixture = json.loads((Path(__file__).parent/'fixtures/capture_readout/paddle.json').read_text())
        frame = fixture['frame']
        result = request(dict(frames=[frame], metric=['wet']))['results'][0]
        self.assertEqual('error', result['verdict'])
        self.assertIn('power stroke depth and declared blade tip', result['where'])
        frame['ct'] = 1.1
        self.assertEqual('skipped', request(dict(frames=[frame], metric=['wet']))['results'][0]['verdict'])

    def test_common_body_turn_is_not_a_bone_jump_but_relative_jump_is(self):
        import math
        from pose_client import request
        def turn(degrees):
            c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
            return [c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1]
        before = dict(k='pose', v=1, run='test', win='turn', u='a', t=0, pt=0,
                      clip='idle', transition=False, pose_basis=turn(0), bones=dict(head=turn(0)))
        after = dict(before, t=1, pose_basis=turn(90), bones=dict(head=turn(90)))
        def verdict(frame):
            return request(dict(frames=[before, frame], metric=['posejump']))['results'][-1]['verdict']
        self.assertEqual('pass', verdict(after))
        self.assertEqual('fail', verdict(dict(after, bones=dict(head=turn(180)))))
        self.assertEqual('error', verdict(dict(after, pose_basis=[0] * 16)))
        self.assertEqual('error', verdict({k: v for k, v in after.items() if k != 'pose_basis'}))

    def test_labelled_authored_yaw_and_deliberate_heading_fault(self):
        from pose_client import request
        fixture = json.loads((Path(__file__).parent/'fixtures/capture_readout/yaw.json').read_text())
        for label in fixture['labels']:
            frame = dict(label['frame'], k='pose', v=1)
            old = {k: v for k, v in frame.items() if k != 'body_yaw'}
            self.assertEqual('suspect', request(dict(frames=[old], metric=['yaw']))['results'][0]['verdict'])
            self.assertEqual('pass', request(dict(frames=[frame], metric=['yaw']))['results'][0]['verdict'])
            broken = [dict(frame, t=frame['t']+i, body_yaw=180,
                           server=dict(t=frame['t']+i, by=90)) for i in range(6)]
            self.assertEqual('fail', request(dict(frames=broken, metric=['yaw']))['results'][-1]['verdict'])

    def test_yaw_requires_exact_actor_dimension_tick_and_milestones_map_by_time(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); view=root/'scene'; view.mkdir()
            rows=[dict(k='pose', u='actor', run='client', win='scene', dim='overworld', t=t, pt=0, f=t,
                       yawsrc='body', yaw=35, body_yaw=0, markers=[], m=[]) for t in (10,11)]
            (view/'pose.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows), encoding='utf-8')
            server=[dict(k='st',u='other',dim='overworld',t=10,st=dict(by=90)),
                    dict(k='st',u='actor',dim='nether',t=10,st=dict(by=90)),
                    dict(k='st',u='actor',dim='overworld',t=9,st=dict(by=90)),
                    dict(k='st',u='actor',dim='overworld',t=11,st=dict(by=3)),
                    dict(k='ev',u='actor',dim='overworld',t=11,cat='family',msg='reunited')]
            (root/'stdout.log').write_text(''.join('[trace] '+json.dumps(r)+'\n' for r in server), encoding='utf-8')
            (root/'plan.json').write_text(json.dumps([dict(id='scene',milestones=[dict(steps=[['actor','seen:family:reunited']])])]),encoding='utf-8')
            frames=[dict(gameTime=10,tick=0,poseFrame=10),dict(gameTime=11,tick=1,poseFrame=11)]
            def evaluate(request):
                a,b=request['frames']; self.assertNotIn('server',a); self.assertEqual(dict(t=11,by=3),b['server'])
                self.assertEqual(0, b['body_yaw'])
                self.assertEqual(35, b['yaw'])
                return dict(results=[dict(metric='yaw',verdict='error'),dict(metric='yaw',verdict='pass')])
            with patch('pose_client.request',side_effect=evaluate):
                result=postprocess(view,frames)
            self.assertEqual('error',result[0]['m'][0]['verdict'])
            self.assertEqual('reunited',frames[1]['milestones'][0]['msg'])
            self.assertTrue((view/'pose-analysis.jsonl').is_file())

    def test_short_marker_and_metric_survive_dense_early_frames(self):
        ticks = list(range(30)) + [200, 600]
        frames = [dict(gameTime=t, poseFrame=i) for i, t in enumerate(ticks)]
        poses = [dict(k='pose', f=i, u='actor', clip='idle', phase='rest', markers=[], m=[])
                 for i in range(len(ticks))]
        poses[11]['markers'] = ['contact']
        poses[20]['m'] = [dict(metric='tilt', where='root', verdict='fail')]
        selected = select_frames(frames, poses)
        self.assertEqual(10, len(selected))
        for i in (0, 11, 20, len(frames)-1):
            self.assertIn(frames[i], selected)
        self.assertIn(frames[-2], selected)

    def test_missing_pose_is_not_a_pass(self):
        frames = [dict(gameTime=0, poseFrame=0)]
        self.assertEqual(frames, select_frames(frames, []))
        report = geometry_report([], frames)
        self.assertFalse(report['complete'])
        self.assertEqual('no recorded pose actor in this view', report['no_evidence'][0]['reason'])

    def test_disappearing_actor_malformed_frame_and_unknown_metric(self):
        frames = [dict(gameTime=i, poseFrame=i) for i in range(3)]
        pose = dict(k='pose', u='a', t=0, pt=0, f=0,
                    m=[dict(metric='held', verdict='pass', where='grip')])
        report = geometry_report([pose, dict(pose, f=2, t=2)], frames)
        self.assertEqual([1], [r['f'] for r in report['no_evidence']])
        self.assertEqual('a', report['no_evidence'][0]['u'])
        frames[1]['cameraReady'] = False
        report = geometry_report([pose, dict(pose, f=2, t=2)], frames)
        self.assertEqual('camera', report['no_evidence'][0]['metric'])
        with self.assertRaises(KeyError):
            geometry_report([dict(pose, m=[dict(metric='held')])], frames)
        with self.assertRaisesRegex(ValueError, 'unknown metric'):
            geometry_report([dict(pose, m=[dict(metric='invented')])], frames)

    def test_empty_view_does_not_abort_other_view_review(self):
        import capture_review
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/'stdout.log').write_text('', encoding='utf-8')
            plans = [dict(id=name, settings={}) for name in ('scene_empty', 'scene_other')]
            (root/'plan.json').write_text(json.dumps(plans), encoding='utf-8')
            frames = [dict(gameTime=10, tick=0, poseFrame=0, file='frame.png')]
            for plan in plans:
                view = root/plan['id']; view.mkdir()
                poses = postprocess(view, frames)
                (view/'selected-frames.json').write_text(json.dumps(select_frames(frames, poses)), encoding='utf-8')
                (view/'geometry.json').write_text(json.dumps(geometry_report(poses, frames)), encoding='utf-8')
            with patch('sys.argv', ['capture_review.py', 'scene', '--reuse', str(root), '--out', str(root/'report')]), \
                    patch.object(capture_review.storyboard, 'kimi', return_value=dict(readability='clear', issues=[], seen='fixture')):
                self.assertEqual(2, capture_review.main())
            report = json.loads((root/'report/review.json').read_text())
            self.assertEqual(2, report['summary']['frames'])
            self.assertEqual(['scene_empty', 'scene_other'], report['summary']['no_evidence_views'])

    def test_postprocessed_hit_between_shots_selects_nearest_without_claiming_exact_image(self):
        frames=[dict(gameTime=i,poseFrame=i) for i in range(0,101,10)]
        pose=dict(k='pose',f=31,u='actor',clip='idle',phase='rest',markers=[],
                  m=[dict(metric='yaw',where='same-tick yaw',verdict='fail')])
        selected=select_frames(frames,[pose],limit=3)
        self.assertEqual([0,30,100],[f['poseFrame'] for f in selected])
        self.assertEqual([dict(f=31,u='actor')],selected[1]['nearby_metric_frames'])

    def test_errors_and_unselected_faults_remain_in_report(self):
        pose = dict(k='pose', u='a', t=12, pt=.5, f=70,
                    m=[dict(metric='pass', verdict='error', where='missing mesh'),
                       dict(metric='tilt', verdict='fail', where='root')])
        report = geometry_report([pose])
        self.assertFalse(report['complete'])
        self.assertEqual(2, len(report['findings']))
        self.assertEqual(70, report['findings'][0]['f'])

    def test_missing_observation_is_local_and_never_a_pass(self):
        reasons = ['missing context: wet_tip', 'missing context: server',
                   'specialized wound/hat/delivery surface', 'partner: damaged placed hat',
                   'partner: partner: specialized wound/hat/delivery surface',
                   'no support triangle under lowest skin point']
        poses = [dict(k='pose', u='a', t=i, pt=0, f=i,
                      m=[dict(metric='gap', verdict='error', where=reason)])
                 for i, reason in enumerate(reasons)]
        report = geometry_report(poses)
        self.assertTrue(report['complete'])  # Report processing, not geometry coverage.
        self.assertEqual({'gap': {'no-evidence': len(reasons)}}, report['counts'])
        self.assertEqual(reasons, [r['reason'] for r in report['no_evidence']])
        self.assertEqual(list(range(len(reasons))), [r['f'] for r in report['no_evidence']])
        self.assertTrue(all(r['verdict'] == 'no-evidence' for r in report['no_evidence']))
        self.assertTrue(all(p['m'][0]['verdict'] == 'error' for p in poses))
        # Deliberate invalid numeric input must still fail report processing.
        poses[0]['m'][0]['where'] = 'non-finite input'
        report = geometry_report(poses)
        self.assertFalse(report['complete'])
        self.assertEqual('error', report['findings'][0]['verdict'])

    def test_beats_use_time_and_reject_panel_numbers(self):
        self.assertEqual('touch', beat_at('0s start\n0.5s touch\n20t release', 15))
        self.assertEqual('release', beat_at('0s start\n0.5s touch\n20t release', 20))
        with self.assertRaises(ValueError):
            beat_at('panel 2 touch', 2)


if __name__ == '__main__':
    unittest.main()
