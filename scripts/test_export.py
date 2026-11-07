"""Pipeline negative controls and a real independent-package Git merge."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import export as pipeline
import export_registry as registry


class ExportTests(unittest.TestCase):
    def setUp(self):
        tmp = pipeline.ROOT/'.local/tmp'
        tmp.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=tmp, prefix='export-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def seed(self):
        pipeline.write(self.root/'art/runtime-sources.json', {'existing': {'source': 'a'}})
        pipeline.write(self.root/'art/catalog.json', {'categories': [dict(id='motions', label='动作', families=[])]})
        registry.import_registries(self.root)

    def test_dependencies_and_cycle(self):
        rows = {'a': dict(id='a', deps=['b']), 'b': dict(id='b', deps=[])}
        self.assertEqual([r['id'] for r in pipeline.ordered(rows, ['a', 'b'])], ['b', 'a'])
        rows['b']['deps'] = ['a']
        with self.assertRaisesRegex(ValueError, 'cycle'):
            pipeline.ordered(rows, ['a'])

    def test_changed_source_and_transitive_consumers(self):
        rows = {'a': dict(sources=['source/'], deps=[]), 'b': dict(sources=[], deps=['a']),
                'c': dict(sources=[], deps=['b']), 'other': dict(sources=['another/'], deps=[])}
        self.assertEqual(pipeline.affected(rows, ['source/deleted.json']), {'a', 'b', 'c'})
        self.assertFalse(pipeline.affected(rows, ['source-other/a']))

    def test_selected_producer_order_without_rebuilding_accepted_sources(self):
        rows = {'source': dict(id='source', sources=['recipe/'], deps=[]),
                'owner': dict(id='owner', sources=[], deps=[], after=['source']),
                'consumer': dict(id='consumer', sources=[], deps=['owner'])}
        self.assertEqual(pipeline.affected(rows, ['recipe/data.json']), set(rows))
        self.assertEqual([r['id'] for r in pipeline.ordered(rows, ['consumer'])], ['owner', 'consumer'])
        self.assertEqual([r['id'] for r in pipeline.ordered(rows, ['consumer', 'source'])], ['source', 'owner', 'consumer'])

    def test_real_source_selection_uses_posix_package_and_declared_inputs(self):
        rows = pipeline.table()
        selected = pipeline.affected(rows, ['standards/yukkuri-library/drafts/cold-storage/recipe.json'])
        self.assertIn('source:standards/yukkuri-library/drafts/cold-storage/build.py', selected)
        self.assertNotIn('source:standards/yukkuri-library/build.py', selected)
        selected = pipeline.affected(rows, ['standards/yukkuri-v1/profiles/growth.json'])
        self.assertIn('family-assets', selected)
        selected = pipeline.affected(rows, ['scripts/export_rounded_runtime.py'])
        self.assertTrue({'rounded', 'placement-fit', 'braid-water-rescue', 'motion-corrections'} <= selected)
        pipeline.ordered(rows, sorted(selected))  # whole declared producer graph must be acyclic

    def test_no_check_never_executes(self):
        row = pipeline.exporter('unsafe', 'missing.py', [], check=None)
        with patch.object(pipeline.subprocess, 'run', side_effect=AssertionError('must not execute')):
            self.assertEqual(pipeline.run(row, True, self.root)['status'], 'NO CHECK')

    def test_failure_is_reported(self):
        row = pipeline.exporter('failure', 'scripts/export.py', [])
        with patch.object(pipeline.subprocess, 'run', return_value=subprocess.CompletedProcess([], 7)):
            self.assertEqual(pipeline.run(row, True, self.root)['status'], 'FAIL')

    def test_failed_check_continues_independent_checks_and_exits_nonzero(self):
        rows = {key: pipeline.exporter(key, 'scripts/export.py', []) for key in ('broken', 'independent')}
        visited = []
        def check(row, *_):
            visited.append(row['id'])
            return dict(id=row['id'], status='FAIL' if row['id']=='broken' else 'PASS', seconds=0, detail='control')
        with patch.object(pipeline, 'table', return_value=rows), patch.object(pipeline, 'run', side_effect=check), \
             patch.object(registry, 'registries'), patch('sys.argv', ['export.py', '--check', '--report', str(self.root/'result.json')]):
            self.assertEqual(pipeline.main(), 1)
        self.assertEqual(visited, ['broken', 'independent'])

    def test_missing_program_is_a_failed_row(self):
        row = pipeline.exporter('node', 'scripts/export_placement_fit.cjs', [])
        with patch.object(pipeline.subprocess, 'run', side_effect=FileNotFoundError('node missing')):
            self.assertEqual(pipeline.run(row, True, self.root)['status'], 'FAIL')

    def test_declared_audio_dependencies_use_shared_checkout(self):
        row = pipeline.exporter('audio', 'scripts/build_ecology_audio.py', [])
        row['pythonpath'] = '.local/python-audio'
        with patch.object(pipeline, 'shared_checkout', return_value=self.root), \
             patch.object(pipeline.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as execute:
            self.assertEqual(pipeline.run(row, True, self.root)['status'], 'PASS')
        import os
        self.assertEqual(execute.call_args.kwargs['env']['PYTHONPATH'].split(os.pathsep)[0], str(self.root/'.local/python-audio'))

    def test_motion_check_uses_readonly_exporter_mode(self):
        import argparse
        beats = self.root/'beats.txt'
        beats.write_bytes(b'0s start\n0.15s next\n')
        args = argparse.Namespace(beats=beats, clips=['call_child'], check=True, report=self.root/'results.json')
        with patch.object(pipeline, 'run', return_value={'status': 'FAIL'}) as execute:
            self.assertEqual(pipeline.motion(args, pipeline.table()), 1)
        self.assertTrue(execute.call_args.args[1], 'motion --check must not export runtime assets')

    def test_failed_selected_source_blocks_owner_but_not_independent_export(self):
        self.seed()
        rows = {key: pipeline.exporter(key, 'scripts/export.py', []) for key in ('source', 'owner', 'independent')}
        rows['owner']['after'] = ['source']
        visited = []
        def execute(row, *_):
            visited.append(row['id'])
            return dict(id=row['id'], status='FAIL' if row['id']=='source' else 'PASS', seconds=0, detail='control')
        args = ['export.py', '--family', 'source', '--family', 'owner', '--family', 'independent',
                '--report', str(self.root/'result.json')]
        with patch.object(pipeline, 'ROOT', self.root), patch.object(pipeline, 'table', return_value=rows), \
             patch.object(pipeline, 'run', side_effect=execute), patch.object(registry, 'registries'), \
             patch.object(registry, 'import_registries'), patch('sys.argv', args):
            self.assertEqual(pipeline.main(), 1)
        self.assertEqual(visited, ['source', 'independent'])
        self.assertEqual(json.loads((self.root/'result.json').read_text())[1]['status'], 'BLOCKED')

    def test_atomic_serialization(self):
        path = self.root/'current.json'
        path.write_bytes(b'original')
        with self.assertRaises(TypeError):
            pipeline.write(path, {'broken': object()})
        self.assertEqual(path.read_bytes(), b'original')

    def test_approval_fingerprint_and_validation_before_write(self):
        self.seed()
        source = self.root/'source.txt'
        source.write_bytes(b'accepted source')
        registry.register(self.root, {'approval': dict(id='clip', decision='认可当前版本', files=['source.txt'])})
        value = registry.load(self.root/'standards/yukkuri-library/provenance/selections/clip.json')
        self.assertEqual(value['files']['source.txt'], hashlib.sha256(source.read_bytes()).hexdigest())
        with self.assertRaises(FileNotFoundError):
            registry.register(self.root, {'runtime': {'not-written': {}},
                'approval': dict(id='missing', decision='save', files=['missing.txt'])})
        self.assertFalse((self.root/'art/registry/runtime/not-written.json').exists())
        with self.assertRaises(ValueError):
            registry.register(self.root, {'runtime': {'../escape': {}}})

    def test_readonly_check_detects_stale_aggregate(self):
        self.seed()
        registry.registries(self.root, True)
        registry.register(self.root, {'runtime': {'new': {}}})
        with self.assertRaisesRegex(ValueError, 'Stale'):
            registry.registries(self.root, True)
        self.assertNotIn('new', registry.load(self.root/'art/runtime-sources.json'))
        registry.registries(self.root)
        registry.registries(self.root, True)

    def test_migration_preserves_existing_reader_data_and_priority(self):
        self.seed()
        original = {'categories': [dict(id='z-first', label='优先', families=[
            dict(id='z-specific', prefixes=['specific/']), dict(id='a-general', prefixes=['general/'])]),
            dict(id='a-last', label='其后', families=[])]}
        pipeline.write(self.root/'art/catalog.json', original)
        # Start with this fixture's source, including a deliberately nonalphabetic order.
        for path in (self.root/'art/registry/catalog').glob('*/category.json'):
            path.unlink()
        registry.import_registries(self.root)
        result = registry.assembled(self.root)
        self.assertEqual(result['catalog'], original)
        self.assertEqual(result['runtime-sources'], {'existing': {'source': 'a'}})

    def test_legacy_export_keeps_pending_registration(self):
        self.seed()
        registry.register(self.root, {'runtime': {'pending-entry': {'source': 'b'}}})
        before = {str(p.relative_to(self.root)).replace('\\', '/'): p.read_bytes()
                  for p in (self.root/'art/runtime-sources.json', self.root/'art/catalog.json')}
        pipeline.write(self.root/'art/runtime-sources.json', {'existing': {'source': 'changed'}})
        registry.import_registries(self.root, before)
        registry.registries(self.root)
        result = registry.load(self.root/'art/runtime-sources.json')
        self.assertIn('pending-entry', result)
        self.assertEqual(result['existing']['source'], 'changed')

    def test_two_package_git_merge(self):
        self.seed()
        def git(*args):
            return subprocess.check_output(['git', '-c', 'user.name=Export test', '-c',
                'user.email=export@test.invalid', *args], cwd=self.root, stderr=subprocess.STDOUT).decode()
        git('init', '-b', 'base')
        git('add', '.')
        git('commit', '-m', 'Base registries')
        for branch in ('package-a', 'package-b'):
            git('checkout', '-b', branch, 'base')
            registry.register(self.root, {'runtime': {branch: {'source': branch}, 'models': {branch: {'source': branch}}}, 'catalog': [
                dict(category='motions', family=dict(id=branch, prefixes=[branch+'/']))]})
            git('add', '.')
            git('commit', '-m', branch)
        git('checkout', 'package-a')
        result = git('merge', '--no-edit', 'package-b')
        self.assertNotIn('CONFLICT', result)
        registry.registries(self.root)
        runtime = registry.load(self.root/'art/runtime-sources.json')
        self.assertTrue({'package-a', 'package-b'} <= runtime.keys())
        self.assertEqual(set(runtime['models']), {'package-a', 'package-b'})
        families = registry.load(self.root/'art/catalog.json')['categories'][0]['families']
        self.assertEqual([f['id'] for f in families], ['package-a', 'package-b'])
        print('Two-package runtime + catalog Git merge: PASS (no shared aggregate edits)')


if __name__ == '__main__':
    unittest.main()
