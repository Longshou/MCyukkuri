#!/usr/bin/env python3
"""Offline regression tests for current entity selections; writes only temporary fixtures."""
import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import validate_entity_assets as validator


class EntityAssetTests(unittest.TestCase):
    def setUp(self):
        work = Path(__file__).resolve().parents[1] / '.local/entity-validator'
        work.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=work)
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.assets = self.root / 'src/main/resources/assets/mcyukkuri'
        for name, value in [('ROOT', self.root), ('ASSETS', self.assets)]:
            context = patch.object(validator, name, value)
            context.start()
            self.addCleanup(context.stop)

    def write(self, path, data):
        path = self.root / path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return hashlib.sha256(data).hexdigest()

    def fixture(self, rounded=True, ident='reimu'):
        root = ('art/source/rounded-runtime/' if rounded else 'art/source/standard-runtime/') + ident
        selected = root + ('/selected' if rounded else '')
        source = 'standards/assembled/' + ident
        model = {'id': ident, 'format': 1, 'units_per_block': 16, 'house': False,
                 'bones': [{'name': 'root', 'parent': -1}], 'vertices': [0.] * 24,
                 'weights': [[0, 0, 1.]] * 3,
                 'parts': [{'name': 'body', 'start': 0, 'count': 3, 'anatomy': ''}],
                 'clips': {'idle': {'duration': 1, 'loop': True, 'tracks': [
                     {'bone': 0, 'channel': 'position', 'keys': [[0, 0, 0, 0], [1, 0, 0, 0]]}]}}}
        if rounded:
            model.update(body_profile='rounded_standard_v1', texture_size=256)
        record = {'geometry_identifier': ident, 'runtime': f'src/main/resources/assets/mcyukkuri/yukkuri/{ident}.json',
                  'selected_model': source + '/' + ident + '.bbmodel',
                  'selected_rig': source + '/animation-rig.json',
                  'snapshot': selected + '/' + ident + '.bbmodel',
                  'rig': root + '/animation-rig.json', 'textures': {}}
        payload = json.dumps(model).encode() + b'\n'
        record['runtime_sha256'] = self.write(record['runtime'], payload)
        self.write(root + '/runtime.model.json', payload)
        record['selected_model_sha256'] = self.write(record['selected_model'], b'{"elements": []}\n')
        self.write(record['snapshot'], b'{"elements": []}\n')
        record['selected_rig_sha256'] = self.write(record['selected_rig'], b'{"bones": []}\n')
        self.write(record['rig'], b'{"adapted": true}\n')
        if rounded:
            record['selected_runtime_sha256'] = self.write(source + '/runtime.model.json', b'{"source": true}\n')
            self.write(selected + '/runtime.model.json', b'{"source": true}\n')
            self.write(selected + '/animation-rig.json', b'{"bones": []}\n')
        else:
            record['standard_motion_source'] = source + '/motion-rig.json'
            record['standard_motion_sha256'] = self.write(record['standard_motion_source'], b'{"motion": true}\n')
        size = 256 if rounded else 128
        expressions = validator.ROUNDED_EXPRESSIONS if rounded else validator.BASE_EXPRESSIONS
        for expression in expressions:
            data = b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' + struct.pack('>II', size, size)
            output = f'src/main/resources/assets/mcyukkuri/textures/entity/{ident}/{expression}.png'
            texture = {'source': f'{source}/textures/{expression}.png', 'runtime': output,
                       'sha256': self.write(output, data)}
            self.write(texture['source'], data)
            if rounded:
                texture.update(source_sha256=texture['sha256'], snapshot=f'{root}/textures/{expression}.png')
                self.write(texture['snapshot'], data)
            record['textures'][expression] = texture
        self.write(f'src/main/resources/assets/mcyukkuri/models/item/{ident}_spawn_egg.json',
                   b'{"parent":"minecraft:item/template_spawn_egg"}')
        for locale in ('en_us', 'zh_cn'):
            self.write(f'src/main/resources/assets/mcyukkuri/lang/{locale}.json',
                       json.dumps({f'entity.mcyukkuri.{ident}': ident, f'item.mcyukkuri.{ident}_spawn_egg': ident}).encode())
        return record, model

    def test_current_rounded_and_legacy_selections(self):
        for rounded in (False, True):
            with self.subTest(rounded=rounded):
                record, _ = self.fixture(rounded)
                validator.validate('reimu', record, {'idle': (1, True)})

    def test_ledger_allows_forms_but_requires_every_base_variant(self):
        record, _ = self.fixture()
        selection = {'models': {'reimu': record}}
        self.write('art/source/rounded-runtime/manifest.json', json.dumps(selection).encode())
        ledger = {'models': {name: {} for name in validator.IDS},
                  'rounded_runtime': {'manifest': 'art/source/rounded-runtime/manifest.json'}}
        ledger['models'].update(reimu=record, reimu_deibu={})
        validator.validate_manifest(ledger)
        del ledger['models']['house_reimu']
        with self.assertRaisesRegex(ValueError, 'all nine base variants'):
            validator.validate_manifest(ledger)

    def test_rounded_selection_mismatch_is_rejected(self):
        record, _ = self.fixture()
        self.write('rounded.json', json.dumps({'models': {'reimu': record}}).encode())
        ledger = {'models': {name: {} for name in validator.IDS},
                  'rounded_runtime': {'manifest': 'rounded.json'}}
        ledger['models']['reimu'] = copy.deepcopy(record)
        ledger['models']['reimu']['runtime_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'differs from rounded selection'):
            validator.validate_manifest(ledger)

    def test_lf_crlf_sources_are_accepted_but_content_drift_is_rejected(self):
        record, _ = self.fixture()
        for path in self.root.rglob('*'):
            if path.is_file() and path.suffix in ('.json', '.bbmodel'):
                path.write_bytes(path.read_bytes().replace(b'\n', b'\r\n'))
        validator.validate_sources('reimu', record, True)
        source = self.root / record['selected_model']
        source.write_bytes(source.read_bytes().replace(b'[]', b'[1]'))
        with self.assertRaisesRegex(ValueError, 'selected model drift'):
            validator.validate_sources('reimu', record, True)

    def test_registered_fingerprints_and_snapshots_are_not_optional(self):
        for field, suffix, message in [
                ('runtime', None, 'runtime differs'),
                ('selected_rig', None, 'selected rig drift'),
                ('snapshot', None, 'editable snapshot differs'),
                ('selected_model', 'runtime.model.json', 'source runtime drift'),
                ('snapshot', 'runtime.model.json', 'runtime snapshot drift'),
                ('snapshot', 'animation-rig.json', 'rig snapshot drift')]:
            with self.subTest(field=field, suffix=suffix):
                record, _ = self.fixture()
                path = self.root / record[field]
                if suffix:
                    path = path.with_name(suffix)
                path.write_bytes(path.read_bytes() + b' ')
                with self.assertRaisesRegex(ValueError, message):
                    validator.validate_sources('reimu', record, True)

    def test_matching_modified_runtime_and_snapshot_still_fail_fingerprint(self):
        record, _ = self.fixture()
        runtime = self.root / record['runtime']
        changed = runtime.read_bytes() + b' '
        runtime.write_bytes(changed)
        (self.root / record['rig']).with_name('runtime.model.json').write_bytes(changed)
        with self.assertRaisesRegex(ValueError, 'runtime fingerprint drift'):
            validator.validate_sources('reimu', record, True)

    def test_original_motion_fingerprint_remains_required(self):
        record, _ = self.fixture(False)
        self.write(record['standard_motion_source'], b'{"motion": false}\n')
        with self.assertRaisesRegex(ValueError, 'standard motion drift'):
            validator.validate_sources('reimu', record, False)
        del record['standard_motion_source']
        with self.assertRaises(KeyError):
            validator.validate_sources('reimu', record, False)

    def test_texture_sources_snapshots_and_png_size_are_checked(self):
        for field in ('runtime', 'source', 'snapshot'):
            with self.subTest(field=field):
                record, model = self.fixture()
                texture = record['textures']['neutral']
                path = self.root / texture[field]
                path.write_bytes(path.read_bytes() + b'drift')
                with self.assertRaisesRegex(ValueError, 'texture drift'):
                    validator.validate_textures('reimu', record, model)
        record, model = self.fixture()
        path = self.root / record['textures']['neutral']['runtime']
        path.write_bytes(path.read_bytes()[:16] + struct.pack('>II', 128, 128))
        with self.assertRaisesRegex(ValueError, 'invalid texture atlas'):
            validator.validate_textures('reimu', record, model)

    def test_missing_expression_is_rejected(self):
        record, model = self.fixture()
        del record['textures']['smug']
        with self.assertRaisesRegex(ValueError, 'expression atlas selection'):
            validator.validate_textures('reimu', record, model)

    def test_geometry_and_action_checks_remain_strict(self):
        mutations = [
            (lambda m: m['vertices'].append(0), 'vertex stride'),
            (lambda m: m['vertices'].__setitem__(0, float('nan')), 'non-finite geometry'),
            (lambda m: m['weights'].__setitem__(0, [1, 0, .5]), 'invalid skinning'),
            (lambda m: m['parts'][0].update(start=1), 'non-contiguous'),
            (lambda m: m['clips']['idle'].update(duration=2), 'timing differs')]
        for mutate, message in mutations:
            with self.subTest(message=message):
                record, model = self.fixture()
                mutate(model)
                self.write(record['runtime'], json.dumps(model).encode())
                with patch.object(validator, 'validate_sources'), patch.object(validator, 'validate_textures'):
                    with self.assertRaisesRegex(ValueError, message):
                        validator.validate('reimu', record, {'idle': (1, True)})


if __name__ == '__main__':
    unittest.main()
