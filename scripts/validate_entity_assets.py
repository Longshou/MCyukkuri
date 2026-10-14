#!/usr/bin/env python3
"""Read-only packaged asset contract checks for CI/designated validation hosts.

Does not launch Minecraft. Do not run locally when project execution rules forbid
validation scripts. JSON validity does not establish visual or game acceptance.
"""
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import sys

from runtime_model_fingerprint import matches_model_fingerprint

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
IDS = {'reimu', 'marisa', 'house_reimu', 'house_marisa', 'chen', 'alice', 'patchouli', 'remilia', 'flandre'}
BASE_EXPRESSIONS = {'neutral', 'happy', 'cry', 'teary', 'drool', 'confused', 'yawn',
                    'sleep', 'angry', 'surprise', 'sleepy'}
ROUNDED_EXPRESSIONS = BASE_EXPRESSIONS | {'closed_smile', 'chewing', 'chewing_closed',
                                         'satisfied', 'open_smile', 'smug', 'puff'}


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_text_hash(path, expected, message):
    require(matches_model_fingerprint(path.read_bytes(), expected), message)


def same_text(first, second):
    # Match the client fingerprint rule: only LF/CRLF conversion is tolerated.
    return first.read_bytes().replace(b'\r\n', b'\n') == second.read_bytes().replace(b'\r\n', b'\n')


def validate_manifest(manifest):
    # The ledger also registers subspecies and forms, which are not enum variants.
    require(IDS <= manifest['models'].keys(), 'manifest must register all nine base variants')
    selected = read(ROOT / manifest['rounded_runtime']['manifest'])
    for ident in IDS & selected['models'].keys():
        record, rounded = manifest['models'][ident], selected['models'][ident]
        for key in ('geometry_identifier', 'selected_model', 'selected_model_sha256',
                    'selected_rig', 'selected_rig_sha256', 'selected_runtime_sha256',
                    'snapshot', 'rig', 'runtime', 'runtime_sha256', 'textures'):
            require(record[key] == rounded[key], f'{ident}: {key} differs from rounded selection')


def validate_sources(ident, record, rounded):
    path = ASSETS / 'yukkuri' / (ident + '.json')
    require(ROOT / record['runtime'] == path and record['geometry_identifier'] == ident,
            'selected runtime path or identity differs')
    snapshot = (ROOT / record['rig']).with_name('runtime.model.json')
    require(same_text(path, snapshot), 'runtime differs from selected snapshot')
    require_text_hash(path, record['runtime_sha256'], 'runtime fingerprint drift')
    require_text_hash(ROOT / record['selected_model'], record['selected_model_sha256'], 'selected model drift; re-export explicitly')
    require_text_hash(ROOT / record['selected_rig'], record['selected_rig_sha256'], 'selected rig drift')
    require_text_hash(ROOT / record['snapshot'], record['selected_model_sha256'], 'editable snapshot differs from source')
    if rounded:
        source = (ROOT / record['selected_model']).with_name('runtime.model.json')
        selected = (ROOT / record['snapshot']).parent
        require_text_hash(source, record['selected_runtime_sha256'], 'selected source runtime drift')
        require_text_hash(selected / 'runtime.model.json', record['selected_runtime_sha256'], 'selected runtime snapshot drift')
        require_text_hash(selected / 'animation-rig.json', record['selected_rig_sha256'], 'selected rig snapshot drift')
    else:
        require_text_hash(ROOT / record['standard_motion_source'], record['standard_motion_sha256'], 'standard motion drift')


def validate_textures(ident, record, model):
    rounded = model.get('body_profile') == 'rounded_standard_v1'
    predator = ident in ('remilia', 'flandre')
    expected = ROUNDED_EXPRESSIONS if rounded else BASE_EXPRESSIONS | ({'biting'} if predator else set())
    require(set(record['textures']) == expected, 'unexpected expression atlas selection')
    size = 256 if rounded or predator else 128
    require(model.get('texture_size', size) == size, 'model texture size differs from selection')
    for expression, texture in record['textures'].items():
        output = ROOT / texture['runtime']
        require(output == ASSETS / 'textures/entity' / ident / (expression + '.png'),
                f'{expression}: texture runtime path differs')
        data = output.read_bytes()
        require(len(data) >= 24 and data[:8] == b'\x89PNG\r\n\x1a\n' and
                struct.unpack('>II', data[16:24]) == (size, size), 'invalid texture atlas')
        require(sha(output) == texture['sha256'], f'{expression}: texture drift')
        require(sha(ROOT / texture['source']) == texture.get('source_sha256', texture['sha256']),
                f'{expression}: source texture drift')
        if rounded or 'snapshot' in texture:
            require(sha(ROOT / texture['snapshot']) == texture['sha256'], f'{expression}: snapshot texture drift')


SPECIES_ONLY = {'shell_retract': 'marisa_tsumuri'}


def validate(ident, record, actions):
    path = ASSETS / 'yukkuri' / (ident + '.json')
    model = read(path)
    validate_sources(ident, record, model.get('body_profile') == 'rounded_standard_v1')
    # The client applies selected motion revisions after loading base geometry.
    corrections = ASSETS / 'yukkuri_presentation/motion_corrections.json'
    revisions = read(corrections)['models'] if corrections.exists() else {}
    if ident in revisions:
        require(revisions[ident]['bone_names'] == [b['name'] for b in model['bones']], 'motion skeleton differs')
        model['clips'].update(revisions[ident]['clips'])
    require(model['id'] == ident and model['format'] == 1 and model['units_per_block'] == 16, 'invalid model identity/format')
    require(model['house'] == ident.startswith('house_'), 'variant mismatch')
    bones, verts, weights = model['bones'], model['vertices'], model['weights']
    require(len({b['name'] for b in bones}) == len(bones), 'duplicate bones')
    require(all(-1 <= b['parent'] < i for i, b in enumerate(bones)), 'bones not ordered parent-first')
    require(len(verts) == len(weights) * 8 and len(weights) % 3 == 0, 'vertex stride/range')
    require(all(math.isfinite(v) for v in verts), 'non-finite geometry')
    require(all(0 <= a < len(bones) and 0 <= b < len(bones) and 0 <= q <= 1 for a, b, q in weights), 'invalid skinning weights')
    offset = 0
    for part in model['parts']:
        require(part['start'] == offset and part['count'] % 3 == 0, 'non-contiguous triangle parts')
        require(part['anatomy'] in ('', 'mamu', 'peni'), 'invalid anatomy visibility gate')
        offset += part['count']
    require(offset == len(weights), 'part ranges do not cover geometry')
    if model['house']:
        body = next(p for p in model['parts'] if p['name'] == 'house_body_surface')
        require(body['count'] == 188 * 3, 'house body mesh topology changed')
        shared = {}
        for i in range(body['start'], body['start'] + body['count']):
            point = tuple(verts[i*8:i*8+3])
            require(shared.setdefault(point, weights[i]) == weights[i], 'shared body vertex has inconsistent binding')
        require(len(shared) == 96, 'house shared vertex count changed')
        require(any(0 < w[2] < 1 for w in weights), 'house lost blended body binding')
    # Species-only actions fall back to idle elsewhere; the world-setting clips arrive with the corrections.
    expected = {name for name in actions if SPECIES_ONLY.get(name, ident) == ident}
    require(set(model['clips']) == expected, 'server action names differ from packaged clips')
    behavior_actions = {'house_pickup': (1.3, False), 'house_carry': (1, True),
                        'house_deposit': (1.3, False), 'house_crawl': (.6, True)} if model['house'] else {}
    predator=ident in ('remilia','flandre')
    if predator:behavior_actions={'predator_fly':(.6,True),'predator_dive':(.6,True),'predator_land':(.8,False),'predator_bite':(.8,False)}
    require(set(model.get('behavior_clips', {})) == set(behavior_actions), 'behavior clips differ')
    if model['house']:
        crawl_source = (ROOT / 'src/main/java/io/github/longshou/mcyukkuri/house/HouseCrawlMotion.java').read_text(encoding='utf-8')
        crawl = record['retargeting']['crawl']
        for key in ('period_ticks', 'reach_tick', 'gather_tick', 'settle_tick', 'stroke_units', 'travel_units', 'drag_share'):
            match = re.search(r'\b'+key.upper()+r'\s*=\s*([.\d]+)\s*;', crawl_source)
            require(match is not None and math.isclose(float(match[1]), crawl[key]), 'crawl clock or stroke differs between server and model')
        grip = model['grip']
        # Two short locks, or Marisa's single braid named twice (the item follows its tip).
        require([bones[i]['name'] for i in grip['bones']] in (['hand_left', 'hand_right'], ['hand_right', 'hand_right']), 'invalid grip bones')
        require(len(grip['points']) == 2 and all(len(p) == 3 for p in grip['points']), 'invalid grip points')
        require(grip['contact_seconds'] == grip['release_seconds'] == .65, 'contact timing drift')
    for name, clip in {**model['clips'], **model.get('behavior_clips', {})}.items():
        duration, loop = {**actions, **behavior_actions}[name]
        require(math.isclose(clip['duration'], duration, abs_tol=1e-9) and clip['loop'] == loop, f'{name}: action timing differs from animation contract')
        for track in clip['tracks']:
            require(0 <= track['bone'] < len(bones), f'{name}: unknown bone')
            require(track['channel'] in ('position', 'rotation', 'scale'), f'{name}: unsupported channel')
            keys = track['keys']
            require(len(keys) > 0 and all(len(k) == 4 and all(math.isfinite(x) for x in k) for k in keys), f'{name}: invalid keyframe')
            require(all(a[0] < b[0] for a, b in zip(keys, keys[1:])), f'{name}: unordered keys')
            require(keys[0][0] >= 0 and keys[-1][0] <= duration + 1e-7, f'{name}: keys outside clip')
    validate_textures(ident, record, model)
    egg = ident + '_spawn_egg'
    require(read(ASSETS / 'models/item' / (egg + '.json'))['parent'] == 'minecraft:item/template_spawn_egg', 'spawn egg model')
    for locale in ('en_us', 'zh_cn'):
        translations = read(ASSETS / 'lang' / (locale + '.json'))
        require('entity.mcyukkuri.' + ident in translations and 'item.mcyukkuri.' + egg in translations, f'{locale}: missing entity/egg name')


def validate_operation_props(ident):
    index = read(ASSETS / 'yukkuri_props/operations-index.json')
    names = [f'iron_enclosure_{ident}']
    names += [f'{kind}_{ident}{suffix}' for kind in ('restraint_frame', 'wrap_sling') for suffix in ('', '_bare')]
    for name in names:
        mesh = read(ASSETS / 'yukkuri_props' / (name + '.json'))
        require(name in index, f'{name}: absent from operation index')
        require(mesh['vertices'] and len(mesh['vertices']) % 24 == 0 and all(math.isfinite(v) for v in mesh['vertices']), f'{name}: invalid geometry')
        require((ASSETS / 'textures/props' / (name + '.png')).is_file(), f'{name}: missing texture')
        selected = ROOT / 'art/props/operation-tools/selected' / name / (name + '.mesh.json')
        require(same_text(selected, ASSETS / 'yukkuri_props' / (name + '.json')), f'{name}: selected mesh drift')
        if name.startswith('restraint_frame_'):
            require(math.isfinite(mesh['body_lift']) and mesh['body_lift'] > 0, f'{name}: invalid support height')
        if name.startswith('wrap_sling_'):
            require(len(mesh['socket']) == 3 and all(math.isfinite(v) for v in mesh['socket']), f'{name}: invalid sling socket')


def main():
    errors = []
    manifest = read(ROOT / 'art/runtime-sources.json')
    validate_manifest(manifest)
    for name in ('living', 'family'):
        presentation = read(ASSETS / 'yukkuri_presentation' / (name + '.json'))
        require(set(presentation['variants']) == IDS, f'{name}: missing variant presentation')
    require(set(re.findall(r'\("([a-z_]+)",\s*(?:true|false)\)',
            (ROOT / 'src/main/java/io/github/longshou/mcyukkuri/entity/YukkuriVariant.java').read_text())) == IDS,
            'variant enum differs from packaged resources')
    source = (ROOT / 'src/main/java/io/github/longshou/mcyukkuri/entity/YukkuriAction.java').read_text()
    actions = {name: (float(duration), loop == 'true') for name, duration, loop in
               re.findall(r'\("([a-z_]+)",\s*([\d.]+),\s*(true|false)\)', source)}
    require(len(actions) == 35, 'expected 35 server actions (19 standard, 15 world-setting, shell retract)')
    living = read(ASSETS / 'yukkuri_presentation/living.json')['variants']
    for ident, bone_name in (('chen', 'chen_hat'), ('alice', 'alice_headband'), ('patchouli', 'patchouli_bonnet')):
        model = read(ASSETS / 'yukkuri' / (ident + '.json'))
        attached = {p['name'] for p in model['parts'] if model['bones'][model['weights'][p['start']][0]]['name'] == bone_name}
        hidden = {p['name'] for p in model['parts'] if p['name'].startswith(tuple(living[ident]['accessory_prefixes']))}
        require(attached == hidden, f'{ident}: accessory removal leaves parts behind or hides hair')
        prop = read(ASSETS / 'yukkuri_props' / (bone_name + '.json'))
        require({p['name'] for p in prop['parts']} == attached, f'{ident}: dropped accessory differs from worn shape')
    for ident in sorted(IDS):
        try:
            validate(ident, manifest['models'][ident], actions)
            validate_operation_props(ident)
        except (OSError, ValueError, KeyError, TypeError, StopIteration) as error:
            errors.append(f'{ident}: {error}')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('Nine packaged models, bindings, 34 shared actions plus behavior clips, textures and source fingerprints are consistent.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
