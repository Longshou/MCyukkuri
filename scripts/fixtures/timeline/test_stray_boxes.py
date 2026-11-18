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
SETTLED = f'found settled shelter={BOX} kind=CARDBOARD occupants='
CALL = f'found beckon child={CHILD} distance=3 patience=200'
HEARD = f'child heard parent call parent={MOTHER} distance=3 beckon=true'
ANSWER = f'found beckon answered child={CHILD} after=2'


def verify(events, stance='FLEE'):
    cry = 'wounded' if stance == 'FLEE' else 'hurt_freeze'
    window = (f'[threat] reimu uuid={CHILD} t=5 dim=test struck stance={stance} exit=true duration=45 dialogue=cry\n'
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
        events = [(60, MOTHER, APPROACH), (62, MOTHER, CALL), (63, CHILD, HEARD), (64, MOTHER, ANSWER),
                  (65, MOTHER, SETTLED + '1'), (70, CHILD, SETTLED + '2')]
        for stance in ('FLEE', 'FREEZE'):
            self.assertIsNone(verify(events, stance))
        for missing in (1, 2, 3, 5):
            with self.subTest(missing=events[missing]):
                self.assertIsNotNone(verify(events[:missing] + events[missing + 1:]))
        self.assertIsNotNone(verify([*events[:3], (64, MOTHER, ANSWER.replace('answered', 'unanswered')), *events[4:]]))

    def test_child_must_already_be_in_that_carton(self):
        child = (60, CHILD, SETTLED + '1')
        approach = (65, MOTHER, APPROACH)
        together = (70, MOTHER, SETTLED + '2')
        for stance in ('FLEE', 'FREEZE'):
            self.assertIsNone(verify([child, approach, together], stance))
        self.assertIsNone(verify([(65, CHILD, SETTLED + '1'), approach, together]))
        broken = {
            'approach only': [(60, CHILD, APPROACH), approach, together],
            'no mother approach': [child, together],
            'mother already approaching': [approach, (66, CHILD, SETTLED + '1'), together],
            'later in the approach tick': [approach, (65, CHILD, SETTLED + '1'), together],
            'other carton': [child, (65, MOTHER, APPROACH.replace(BOX, OTHER_BOX)), together],
            'child left': [child, (62, CHILD, f'found exited shelter={BOX}'), approach, together],
            'other child': [(60, 'other', SETTLED + '1'), approach, together],
            'other second occupant': [child, approach, (70, 'other', SETTLED + '2')],
            'same settler twice': [child, approach, (70, CHILD, SETTLED + '2')],
            'one occupant': [child, approach, (70, MOTHER, SETTLED + '1')],
        }
        for name, events in broken.items():
            with self.subTest(name=name):
                self.assertIsNotNone(verify(events))
        self.assertIsNotNone(verify([(30, CHILD, SETTLED + '1'), approach, together], 'FREEZE'))
