import unittest
from capture_readout import select_frames, beat_at, geometry_report


class CaptureReadoutTest(unittest.TestCase):
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
