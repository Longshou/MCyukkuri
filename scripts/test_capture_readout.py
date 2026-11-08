import unittest
import json
import tempfile
from pathlib import Path
from unittest.mock import patch
from capture_readout import select_frames, beat_at, geometry_report, postprocess


class CaptureReadoutTest(unittest.TestCase):
    def test_yaw_requires_exact_actor_dimension_tick_and_milestones_map_by_time(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); view=root/'scene'; view.mkdir()
            rows=[dict(k='pose', u='actor', run='client', win='scene', dim='overworld', t=t, pt=0, f=t,
                       yawsrc='body', yaw=0, markers=[], m=[]) for t in (10,11)]
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
        with self.assertRaises(ValueError):
            select_frames([dict(gameTime=0, poseFrame=0)], [])
        self.assertFalse(geometry_report([])['complete'])

    def test_errors_and_unselected_faults_remain_in_report(self):
        pose = dict(k='pose', u='a', t=12, pt=.5, f=70,
                    m=[dict(metric='pass', verdict='error', where='missing mesh'),
                       dict(metric='tilt', verdict='fail', where='root')])
        report = geometry_report([pose])
        self.assertFalse(report['complete'])
        self.assertEqual(2, len(report['findings']))
        self.assertEqual(70, report['findings'][0]['f'])

    def test_beats_use_time_and_reject_panel_numbers(self):
        self.assertEqual('touch', beat_at('0s start\n0.5s touch\n20t release', 15))
        self.assertEqual('release', beat_at('0s start\n0.5s touch\n20t release', 20))
        with self.assertRaises(ValueError):
            beat_at('panel 2 touch', 2)


if __name__ == '__main__':
    unittest.main()
