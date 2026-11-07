"""Negative reference checks; runtime files are never changed."""
import copy
import unittest
from export_moments import read, SOURCE, validate


class MomentExportTest(unittest.TestCase):
    def test_broken_rows(self):
        for key,value,message in [
            ('clip','missing_clip','unknown clip'),
            ('faces',[[0,'missing_face']],'Unselectable emitted face'),
            ('emote','missing_emote','unknown emote'),
            ('tempo',0,'invalid tempo'),
            ('intensity',float('nan'),'invalid intensity'),
            ('faces',[[.1,'neutral'],[0,'happy']],'invalid face time'),
            ('faces',[[10000,'neutral']],'invalid face time'),
            ('sound','other:noise','vanilla resource id')]:
            with self.subTest(key=key,value=value):
                data=copy.deepcopy(read(SOURCE))
                data['moments']['wet_relief']['steps'][0][key]=value
                with self.assertRaisesRegex(ValueError,message): validate(data)
                print('Rejected:',key,value)

    def test_missing_mapping(self):
        data=read(SOURCE)
        data['behaviors']['sleep']='missing_moment'
        with self.assertRaisesRegex(ValueError,'unknown moment'): validate(data)


if __name__=='__main__': unittest.main()
