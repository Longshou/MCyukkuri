"""FG-08 must account for the child and mother in the same box after the strike."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from museum_scenarios import _stray_child_flight, _stray_child_log as CHILD, _stray_mother_log as MOTHER
from cases import log, record

BOX = 'BlockPos{x=1, y=64, z=2}'
OTHER_BOX = 'BlockPos{x=3, y=64, z=2}'
APPROACH = f'found approach shelter={BOX} kind=CARDBOARD'
SNIFF = f'found sniff shelter={BOX}'
DIRECT = f'found no beckon shelter={BOX}'
SETTLED = f'found settled shelter={BOX} kind=CARDBOARD occupants='
CALL = f'found beckon child={CHILD} distance=3 patience=200'
HEARD = f'child heard parent call parent={MOTHER} distance=3 beckon=true'
ANSWER = f'found beckon answered child={CHILD} after=2'


def verify(events, stance='FLEE'):
    cry = 'wounded' if stance == 'FLEE' else 'hurt_freeze'
    window = (f'[threat] reimu uuid={CHILD} t=5 dim=test struck stance={stance} exit=true identity=stray duration=45 dialogue=cry\n'
              f'[speech] reimu uuid={CHILD} t=5 dim=test line event={cry} key=cry')
    if stance == 'FLEE':
        rows = [record('st', t, actor, st={'p': [x, 0, 0], 'behavior': 'flee'}) for t, child_x in ((6, 0), (7, 1))
                for actor, x in ((CHILD, child_x), (MOTHER, 2))]
    else:
        rows = [record('st', 6, CHILD, st={'behavior': 'struck', 'animation': 'brace'}),
                record('st', 50, CHILD, st={'behavior': 'idle', 'animation': 'idle'})]
    rows.extend(record('ev', t, actor, cat='family' if msg.startswith('child heard') else 'habitat', msg=msg)
                for t, actor, msg in events)
    return _stray_child_flight(window, log(rows).splitlines())


class CartonArrivalTests(unittest.TestCase):
    def test_outside_child_must_hear_and_answer(self):
        events = [(60, MOTHER, APPROACH), (61, MOTHER, SNIFF), (62, MOTHER, CALL), (63, CHILD, HEARD),
                  (64, MOTHER, ANSWER), (65, MOTHER, SETTLED + '1'), (70, CHILD, SETTLED + '2')]
        for stance in ('FLEE', 'FREEZE'):
            self.assertIsNone(verify(events, stance))
        for missing in (1, 2, 3, 4, 5):
            with self.subTest(missing=events[missing]):
                self.assertIsNotNone(verify(events[:missing] + events[missing + 1:]))
        self.assertIsNotNone(verify([*events[:4], (64, MOTHER, ANSWER.replace('answered', 'unanswered')), *events[5:]]))

    def test_child_must_have_settled_before_the_mother_decides(self):
        child = (60, CHILD, SETTLED + '1')
        approach = (65, MOTHER, APPROACH)
        mouth = (66, MOTHER, SNIFF)
        decision = (86, MOTHER, DIRECT)
        together = (90, MOTHER, SETTLED + '2')
        for stance in ('FLEE', 'FREEZE'):
            self.assertIsNone(verify([child, approach, mouth, decision, together], stance))
            self.assertIsNone(verify([(60, MOTHER, APPROACH), (62, CHILD, SETTLED + '1'), mouth, decision, together], stance))
            self.assertIsNone(verify([approach, mouth, (67, CHILD, SETTLED + '1'), decision, together], stance))
        self.assertIsNone(verify([approach, mouth, (86, CHILD, SETTLED + '1'), decision, together]))
        broken = {
            'approach only': [(60, CHILD, APPROACH), approach, mouth, decision, together],
            'no mother mouth': [child, approach, decision, together],
            'no decision': [child, approach, mouth, together],
            'settled after decision': [approach, mouth, decision, (86, CHILD, SETTLED + '1'), together],
            'other carton': [child, approach, mouth, (86, MOTHER, DIRECT.replace(BOX, OTHER_BOX)), together],
            'child left': [child, approach, mouth, decision, (88, CHILD, f'found exited shelter={BOX}'), together],
            'other child': [(60, 'other', SETTLED + '1'), approach, mouth, decision, together],
            'other second occupant': [child, approach, mouth, decision, (90, 'other', SETTLED + '2')],
            'same settler twice': [child, approach, mouth, decision, (90, CHILD, SETTLED + '2')],
            'one occupant': [child, approach, mouth, decision, (90, MOTHER, SETTLED + '1')],
        }
        for name, events in broken.items():
            with self.subTest(name=name):
                self.assertIsNotNone(verify(events))
        self.assertIsNotNone(verify([(30, CHILD, SETTLED + '1'), approach, mouth, decision, together], 'FREEZE'))
