"""The PK-04 verifier distinguishes a timely reply and a saved refusal."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from museum_scenarios import (_park_carton_core, _park_former, _park_local_log as LOCAL, _park_waiter_log as WAITER,
                              _park_aid_vigil_readout, _verify_park_aid)
from cases import log, record


BOX = 'BlockPos{x=10, y=64, z=-4}'
REFUSAL = '\n'.join([
    f'[scenario] reimu uuid={WAITER} t=1 vary rolled={{attachment=90}}',
    f'[kindness] reimu uuid={LOCAL} t=15 bite handed peer={WAITER} clip=offer_bite',
    f'[meal] reimu uuid={WAITER} t=59 swallowed item=minecraft:apple count=1 feeder={LOCAL}',
    f'[kindness] reimu uuid={LOCAL} t=69 carton offered peer={WAITER} shelter={BOX} clip=look_around',
    f'[kindness] reimu uuid={WAITER} t=73 carton decision=stay local={LOCAL} shelter={BOX} clip=droop',
])


class ParkAidTests(unittest.TestCase):
    def test_forage_must_wait_until_after_the_reply(self):
        calm = [record('st', 73, WAITER, st={'animation': 'droop'}),
                record('st', 151, WAITER, st={'animation': 'waddle', 'goals': ['ForageGoal@4']})]
        self.assertIsNone(_verify_park_aid(REFUSAL, log(calm).splitlines()))
        interrupted = [record('st', 71, WAITER, st={'goals': ['ForageGoal@4']}), *calm]
        self.assertIn('took MOVE', _verify_park_aid(REFUSAL, log(interrupted).splitlines()))

    def test_refusal_readout_requires_active_vigil_and_exact_site(self):
        def readout(values):
            return '\n'.join(f'The waiter has the following entity data: {value}' for value in values)

        start = ['1b', _park_former, '70', '20']
        answer = f'[{{Dimension: "minecraft:overworld", Pos: {_park_carton_core}L, Need: 0.0d, Bond: 0.025d}}]'
        self.assertIsNone(_park_aid_vigil_readout(readout([*start, answer])))
        self.assertIsNotNone(_park_aid_vigil_readout(readout(start)))
        self.assertIsNotNone(_park_aid_vigil_readout(readout([*start, answer.replace('overworld', 'the_nether')])))
        self.assertIsNotNone(_park_aid_vigil_readout(readout([*start, answer.replace(f'{_park_carton_core}L', '0L')])))
        self.assertIsNone(_park_aid_vigil_readout(readout(['0b', _park_former, '59', '6'])))
