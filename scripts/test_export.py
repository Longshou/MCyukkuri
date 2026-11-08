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

    def test_concurrent_checks_keep_all_failures_and_declared_report_order(self):
        import threading
        rows = {key: pipeline.exporter(key, 'scripts/export.py', []) for key in ('broken', 'independent')}
        together = threading.Barrier(2)
        def check(row, readonly, _):
            self.assertTrue(readonly)
            together.wait(timeout=5)
            return dict(id=row['id'], status='FAIL' if row['id']=='broken' else 'PASS', seconds=0, detail='control')
        with patch.object(pipeline, 'table', return_value=rows), patch.object(pipeline, 'run', side_effect=check), \
             patch.object(registry, 'registries'), patch('sys.argv', ['export.py', '--check', '--jobs', '2', '--report', str(self.root/'result.json')]):
            self.assertEqual(pipeline.main(), 1)
        result = registry.load(self.root/'result.json')
        self.assertEqual([r['id'] for r in result], ['broken', 'independent', 'registries'])
        self.assertEqual([r['status'] for r in result], ['FAIL', 'PASS', 'PASS'])

    def test_concurrency_rejects_writes_and_nonpositive_worker_count(self):
        import contextlib
        import io
        for args in (['--family', 'rare-forms', '--jobs', '2'], ['--check', '--jobs', '0']):
            with self.subTest(args=args), patch('sys.argv', ['export.py', *args]), \
                 contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                pipeline.main()
            self.assertEqual(error.exception.code, 2)

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

    def test_motion_models_keep_separate_evidence_and_export_once(self):
        import argparse
        beats = self.root/'beats.txt'
        beats.write_text('0s start\n20t peak\n', encoding='utf-8')
        args = argparse.Namespace(beats=beats, clips=['wake'], check=False,
            model=['reimu', 'marisa'], metrics='tilt,pass', context=None, water=None,
            report=self.root/'results.json')
        response = json.dumps({'results': [{'verdict': 'pass'}]})+'\n'
        with patch.object(pipeline, 'run', return_value={'status': 'PASS'}) as export, \
             patch.object(registry, 'prepare_export'), patch.object(registry, 'finish_export'), \
             patch.object(pipeline.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0,
                 stdout=response*2, stderr='')) as execute:
            self.assertEqual(pipeline.motion(args, pipeline.table()), 0)
        self.assertEqual(export.call_count, 1)
        self.assertEqual(export.call_args.args[0]['id'], 'life')
        calls = execute.call_args_list
        self.assertEqual([calls[i].args[0][3] for i in (0, 2)],
                         ['motion-wake-reimu', 'motion-wake-marisa'])
        for model, call in zip(args.model, (calls[1], calls[3])):
            requests = [json.loads(line) for line in call.kwargs['input'].splitlines()]
            self.assertEqual([r['model'] for r in requests], [model, model])
            self.assertEqual([r['t'] for r in requests], ['0.0', '1.0'])
            self.assertEqual(requests[0]['metric'], ['tilt', 'pass'])
            self.assertEqual((self.root/'motion/wake'/model/'metrics.jsonl').read_text(), response*2)

    def test_motion_model_error_preserves_reason_and_checks_remaining_models(self):
        import argparse
        import contextlib
        import io
        beats = self.root/'beats.txt'
        beats.write_text('0s start\n', encoding='utf-8')
        args = argparse.Namespace(beats=beats, clips=['wake'], check=False,
            model=['house_marisa', 'reimu'], metrics='tilt', context=None, water=None,
            report=self.root/'results.json')
        responses = iter(['{"error":"incompatible house_ecology for house_marisa"}\n',
                          '{"results":[{"verdict":"pass"}]}\n'])
        def run(command, **kwargs):
            return subprocess.CompletedProcess(command, 0,
                stdout=next(responses) if '--serve' in command else '', stderr='')
        output = io.StringIO()
        with patch.object(pipeline, 'run', return_value={'status': 'PASS'}), \
             patch.object(registry, 'prepare_export'), patch.object(registry, 'finish_export'), \
             patch.object(pipeline.subprocess, 'run', side_effect=run), contextlib.redirect_stdout(output):
            self.assertEqual(pipeline.motion(args, pipeline.table()), 2)
        self.assertIn('wake/house_marisa: pose service error', output.getvalue())
        self.assertIn('incompatible house_ecology for house_marisa', output.getvalue())
        self.assertTrue((self.root/'motion/wake/reimu/metrics.jsonl').exists())

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

    def test_registration_dry_run_matches_writes_without_creating_entries(self):
        import contextlib
        import io
        self.seed()
        (self.root/'source.txt').write_bytes(b'accepted source')
        entry = {'runtime': {'new-entry': {'source': 'source.txt'}},
                 'approval': dict(id='wake', decision='save wake', files=['source.txt'])}
        pipeline.write(self.root/'entry.json', entry)
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        output = io.StringIO()
        with patch.object(pipeline, 'ROOT', self.root), patch.object(pipeline, 'table', return_value={}), \
             patch('sys.argv', ['export.py', '--register', str(self.root/'entry.json'), '--dry-run']), \
             contextlib.redirect_stdout(output):
            self.assertEqual(pipeline.main(), 0)
        after = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.assertEqual(before, after)
        planned = json.loads(output.getvalue())
        self.assertEqual(planned[1]['value']['files']['source.txt'], hashlib.sha256(b'accepted source').hexdigest())
        registry.register(self.root, entry)
        for row in planned:
            self.assertEqual(registry.load(self.root/row['path']), row['value'])
        with self.assertRaises(FileNotFoundError):
            registry.register(self.root, {'approval': dict(id='missing', decision='save', files=['absent'])}, dry_run=True)

    def test_registration_dry_run_rejects_export_mode(self):
        import contextlib
        import io
        with patch('sys.argv', ['export.py', '--family', 'life', '--dry-run']), \
             contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            pipeline.main()
        self.assertEqual(error.exception.code, 2)

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
        original = {'categories': [dict(id='z-first', label='优先', families=[
            dict(id='z-specific', prefixes=['specific/']), dict(id='a-general', prefixes=['general/'])]),
            dict(id='a-last', label='其后', families=[])]}
        pipeline.write(self.root/'art/runtime-sources.json', {'existing': {'source': 'a'}})
        pipeline.write(self.root/'art/catalog.json', original)
        # Start with this fixture's source, including a deliberately nonalphabetic order.
        registry.import_registries(self.root)
        result = registry.assembled(self.root)
        self.assertEqual(result['catalog'], original)
        self.assertEqual(result['runtime-sources'], {'existing': {'source': 'a'}})

    def test_deletions_survive_rebuild_and_managed_legacy_export(self):
        self.seed()
        registry.register(self.root, {'runtime': {'models': {'removed': {'source': 'model'}}},
            'catalog': [dict(category='motions', family=dict(id='removed', prefixes=['gone/']))]})
        registry.registries(self.root)
        paths = ['runtime/existing.json', 'runtime/models/removed.json', 'catalog/motions/families/removed.json']
        for path in paths:
            (self.root/'art/registry'/path).unlink()
        # Stale aggregates deliberately still contain every deleted entry.
        with self.assertRaisesRegex(ValueError, 'shards already exist'):
            registry.import_registries(self.root)
        registry.registries(self.root)
        for path in paths:
            self.assertFalse((self.root/'art/registry'/path).exists())
        before = registry.prepare_export(self.root)
        data = registry.load(self.root/'art/runtime-sources.json')
        data['another-export'] = {'source': 'new'}
        registry.save(self.root/'art/runtime-sources.json', data)
        registry.finish_export(self.root, before)
        registry.registries(self.root, True)
        self.assertEqual(registry.load(self.root/'art/runtime-sources.json'), {'another-export': {'source': 'new'}})
        self.assertEqual(registry.load(self.root/'art/catalog.json')['categories'][0]['families'], [])
        for path in paths:
            self.assertFalse((self.root/'art/registry'/path).exists())
        print('Delete runtime key + last model + catalog family -> rebuild -> export -> still deleted: PASS')

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
