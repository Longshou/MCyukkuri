"""R colony_migrate_babies-36: leaf display hides habitat grass for 401 ticks.

F controls retain actual loss detection; historical priority displays cannot
establish conservation. The mouth collision and lifecycle checks remain usable.
"""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import smoke_checks as checks
import timeline
from cases import record, state, log


class InventoryAuthority(unittest.TestCase):
    def test_one_leaf_store_can_contain_several_stacks(self):
        leaves = [{'item': 'minecraft:oak_leaves', 'n': 1}, {'item': 'minecraft:birch_leaves', 'n': 2}]
        rows = [state(t, hold={'mouth': [copy.deepcopy(leaves)]}) for t in (0, 1)]
        self.assertFalse(checks.ledger([], timeline.states(rows)))
        rows[1]['st']['hold']['mouth'][0][0]['n'] = 0
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger([], timeline.states(rows))))

    def test_leaf_priority_is_not_a_resource_transfer(self):
        grass = {'item': 'minecraft:short_grass', 'n': 1}
        leaf = {'item': 'minecraft:oak_leaves', 'n': 1}
        receipt = record('ev', 1, cat='family', msg='leaf swaddle leaf harvested item=minecraft:oak_leaves')
        clean = [state(0, hold={'mouth': [grass]}),
                 state(1, hold={'mouth': [grass, leaf]}),
                 state(402, hold={'mouth': [grass, leaf]})]
        found = checks.ledger([receipt], timeline.states(clean))
        self.assertFalse(any(f['verdict'] == 'fail' for f in found))
        # Competing physical mouth stores are still visible as a separate fault.
        self.assertTrue(any(f['detail'] == 'two non-empty mouth stores' for f in found))
        lost = copy.deepcopy(clean)
        lost[1]['st']['hold']['mouth'] = [leaf]
        lost[2]['st']['hold']['mouth'] = [leaf]
        self.assertTrue(any(f['verdict'] == 'fail' and f.get('items', {}).get('minecraft:short_grass') == -1
                            for f in checks.ledger([receipt], timeline.states(lost))))
        # This is the old recorded display: grass, then leaf, then grass.
        missing = copy.deepcopy(lost)
        missing[2]['st']['hold']['mouth'] = [grass]
        for row in missing:
            del row['st']['hold']['mouthAuthority']
        found = checks.ledger([receipt], timeline.states(missing))
        self.assertTrue(any(f['verdict'] == 'no-evidence' for f in found))
        self.assertFalse(any(f['verdict'] == 'fail' for f in found))

    def test_authority_does_not_hide_missing_or_malformed_data(self):
        rows = [state(0), state(1)]
        del rows[1]['st']['hold']['mouthAuthority']
        self.assertTrue(any(f['verdict'] == 'no-evidence' for f in checks.ledger([], timeline.states(rows))))
        rows.append(state(2, hold={'mouth': [{'item': 'minecraft:apple', 'n': 1}]}))
        self.assertFalse(any(f['verdict'] == 'fail' for f in checks.ledger([], timeline.states(rows))))
        rows[1]['st']['hold']['mouthAuthority'] = True
        _, issues = timeline.parse([log(rows)])
        self.assertTrue(issues)


if __name__ == '__main__':
    unittest.main()
