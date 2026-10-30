#!/usr/bin/env python3
"""Export static model/rig data and a file-openable preview; never launch a runtime.

Reads the selected standard exports, preserves the old art/source/{species}
baseline, writes art/source/standard-runtime and packaged yukkuri/*.json assets.
House retargeting is defined here, separately from the standard shape recipe.
"""
from pathlib import Path
import base64
import copy
import hashlib
import json
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / 'standards/yukkuri-library'
HOUSE = LIB / 'ecology/house-yukkuri/assembled'
OUT = ROOT / 'art/source/standard-runtime'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
sys.path[:0] = [str(LIB / 'ecology/house-yukkuri'), str(LIB)]
from mesh_geometry import vertices
from runtime_animation import fit_grounded_hair
from runtime_house_interactions import add_house_interactions
from runtime_waddle import fit_waddle
from runtime_house_face import fit as fit_house_face


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def save(path, value, *, pretty=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2 if pretty else None,
                               separators=None if pretty else (',', ':'), allow_nan=False), encoding='utf-8')


def fingerprint(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative(path):
    return path.relative_to(ROOT).as_posix()


def house_rig(standard, original):
    rig = copy.deepcopy(standard)
    # Rest-space proportions: 4.32 x 3.82 x 2.93 versus the standard body.
    factors = [4.32 / 12, 3.82 / 8.48, 2.93 / 13.2]
    for bone in rig['bones']:
        bone['pivot'] = [v * s for v, s in zip(bone['pivot'], factors)]
        bone['pivot'][2] -= 1.6351562 * factors[2]
        if bone['name'] == 'world_motion':
            bone['pivot'] = [0, 0, 0]
    for clip in rig['clips'].values():
        for channels in clip['tracks'].values():
            for t, value in channels.get('position', {}).items():
                channels['position'][t] = [v * s for v, s in zip(value, factors)]
    # Dedicated house attachments keep their fitted rest pivots and exact travel.
    original_bones = {b['name']: b for b in original['bones']}
    for bone in rig['bones']:
        if bone['name'] in ('anatomy_mamu', 'anatomy_peni', 'hand_left', 'hand_right'):
            bone['pivot'] = original_bones[bone['name']]['pivot'][:]
    rig['clips'].update({k: copy.deepcopy(v) for k, v in original['clips'].items() if k.startswith('anatomy_')})
    # No separate cheek balloons exist on the house shape. Retarget their swell
    # to body width/depth, keeping the source puff timing instead of a no-op.
    swell = rig['clips']['puff']['tracks']['puff_left']['scale']
    rig['clips']['puff']['tracks']['root']['scale'] = {
        t: [1 + max(0, v[0] - 1) * .6, 1, 1 + max(0, v[0] - 1) * .3] for t, v in swell.items()
    }
    return rig


def binding(y, names):
    # Shared body vertices receive identical two-bone weights across triangles.
    # Continuous blending avoids seams at the original 32 slice boundaries.
    q = min(31, max(0, y / 3.82 * 32 - .5))
    lo = int(q)
    hi = min(31, lo + 1)
    return [names[f'soft_head_slice_{lo}_bend'], names[f'soft_head_slice_{hi}_bend'], round(q - lo, 7)]


def fit_braid_chain(rig, bb):
    """Pivots of Marisa's braid chain on a refitted braid (house): each joint midway between the two lobes it joins,
    the tail joint at the tie's lower edge. Returns the chain bone names top to tail and a function giving the chain
    bone for a height on the braid (the house core is sliced along its length, not cut at the joints)."""
    names = [b['name'] for b in rig['bones']]
    chain = ['hand_right']+[f'hand_right_braid_{k}' for k in range(1, 99) if f'hand_right_braid_{k}' in names]+['hand_right_tip']
    if len(chain) < 3 or chain[-1] not in names:
        return None, None
    box = {}
    for element in bb['elements']:
        name = re.sub(r'_house_fit_\d+$', '', element['name']); vv = vertices(element, bb['resolution']['width'])
        lo, hi = [min(vv[k::8]) for k in range(3)], [max(vv[k::8]) for k in range(3)]
        old = box.get(name); box[name] = (lo, hi) if old is None else ([min(a, b) for a, b in zip(old[0], lo)], [max(a, b) for a, b in zip(old[1], hi)])
    centre = lambda n: [(a+b)/2 for a, b in zip(*box[n])]
    bones = {b['name']: b for b in rig['bones']}
    for k, name in enumerate(chain[1:-1], 1):
        upper, lower = f'braid_lock_lobe_{k-1}', f'braid_lock_lobe_{k}'
        x, _, z = [(a+b)/2 for a, b in zip(centre(upper), centre(lower))]
        bones[name]['pivot'] = [round(x, 7), round((box[upper][0][1]+box[lower][1][1])/2, 7), round(z, 7)]
    x, _, z = centre('braid_lock_ribbon_band'); bones[chain[-1]]['pivot'] = [round(x, 7), round(box['braid_lock_ribbon_band'][0][1], 7), round(z, 7)]
    joints = [bones[n]['pivot'][1] for n in chain[1:]]
    return chain, lambda y: chain[sum(y < j for j in joints)]


def export(species, is_house):
    ident = ('house_' if is_house else '') + species
    directory = HOUSE if is_house else LIB / 'assembled' / species
    bbpath = directory / (ident + '.bbmodel')
    rigpath = directory / (ident + '.rig.json' if is_house else 'animation-rig.json')
    bb, original = read(bbpath), read(rigpath)
    standard_bb = read(LIB / 'assembled' / species / (species + '.bbmodel'))
    standard = read(LIB / 'assembled' / species / 'animation-rig.json')
    rig = house_rig(standard, original) if is_house else copy.deepcopy(original)
    braid, along = fit_braid_chain(rig, bb) if is_house else (None, None)
    names = {b['name']: i for i, b in enumerate(rig['bones'])}
    source_groups = {e['name']: g for e, g in zip(standard_bb['elements'], standard['groups'])}
    # Server navigation owns planar travel and heading during flight.
    for action, clip in rig['clips'].items():
        world = clip['tracks'].get('world_motion', {})
        for value in world.get('position', {}).values():
            value[0] = value[2] = 0
        if action == 'flee':
            world.pop('rotation', None)
    flat, weights, parts = [], [], []
    for element, group in zip(bb['elements'], original['groups']):
        vv = vertices(element, bb['resolution']['width'])
        # Legacy cube authoring triangles use the opposite winding from meshes.
        # Normalize winding to the authored outward normal for Minecraft lighting.
        for i in range(0, len(vv), 24):
            a = [vv[i + 8 + k] - vv[i + k] for k in range(3)]
            b = [vv[i + 16 + k] - vv[i + k] for k in range(3)]
            n = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
            if sum(n[k] * vv[i + 5 + k] for k in range(3)) < 0:
                vv[i+8:i+24] = vv[i+16:i+24] + vv[i+8:i+16]
        start = len(weights)
        if is_house:
            name = re.sub(r'_house_fit_\d+$', '', element['name'])
            target = source_groups.get(name)
            if braid and name.startswith('braid_lock_core'):
                target = along(sum(vv[1::8])/(len(vv)//8))  # its own chain segment
            # Every fitted scalp piece follows the same continuous body field.
            # Mixing a skinned wrap with rigid bangs/crown tears their overlap
            # apart during side bends. Hats and side locks keep their own rigs.
            flexible = element['name'].startswith((
                'house_body_surface', 'house_hair_', 'house_temple_',
                'house_bang_', 'house_rump_'))
            if flexible:
                bind = [binding(vv[i + 1], names) for i in range(0, len(vv), 8)]
            else:
                fallback = {'head': 'hair', 'face': 'face', 'body': 'body_stretch', 'rump': 'rump'}
                target = target or fallback.get(group, group)
                bind = [[names[target], names[target], 0] for _ in range(len(vv) // 8)]
        else:
            bind = [[names[group], names[group], 0] for _ in range(len(vv) // 8)]
        flat.extend(round(v, 7) for v in vv)
        weights.extend(bind)
        parts.append({'name': element['name'], 'start': start, 'count': len(bind),
                      'anatomy': group.removeprefix('anatomy_') if group.startswith('anatomy_') else ''})
    walking = fit_waddle(rig, is_house)
    retargeting = fit_grounded_hair(rig, flat, weights) if species == 'patchouli' and not is_house else {}
    if is_house:
        retargeting = add_house_interactions(rig, species)
    retargeting['walking'] = walking
    names = {b['name']: i for i, b in enumerate(rig['bones'])}
    bones = [{'name': b['name'], 'parent': -1 if b['parent'] is None else names[b['parent']], 'pivot': b['pivot']} for b in rig['bones']]
    clips = {}
    for key, clip in {**rig['clips'], **rig.get('behavior_clips', {})}.items():
        clips[key] = {'duration': clip['duration'], 'loop': clip['loop'], 'tracks': [
            {'bone': names[name], 'channel': channel,
             'keys': [[float(t), *value] for t, value in sorted(keys.items(), key=lambda item: float(item[0]))]}
            for name, channels in clip['tracks'].items() for channel, keys in channels.items()]}
    model = {'format': 1, 'id': ident, 'units_per_block': 16, 'house': is_house,
             'vertices': flat, 'weights': weights, 'parts': parts, 'bones': bones, 'clips': clips}
    if is_house:
        model['behavior_clips'] = {key: clips.pop(key) for key in rig['behavior_clips']}
        model['grip'] = {**rig['grip'], 'bones': [names[n] for n in rig['grip']['bones']]}
        # Retarget facial walking motion only after the actual face geometry is known.
        recipe = read(OUT/'face-motion/recipe.json')
        retargeting['face_motion'] = fit_house_face(model, recipe)
        for record in retargeting['face_motion']:
            name = record['anchor']
            rig['clips']['waddle']['tracks'][name] = {
                t['channel']:{str(key[0]):key[1:] for key in t['keys']}
                for t in model['clips']['waddle']['tracks'] if t['bone']==names[name]}
    rig['groups'] = [rig['bones'][weights[p['start']][0]]['name'] for p in parts]
    rig['element_vertex_counts'] = [p['count'] for p in parts]
    rig['vertex_weights'] = weights
    rig['binding_mode'] = 'two_bone_linear_skinning'
    target = OUT / ident
    target.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(bbpath, target / (ident + '.bbmodel'))
    save(target / 'animation-rig.json', rig)
    save(target / 'runtime.model.json', model)
    runtime_path = ASSETS / 'yukkuri' / (ident + '.json')
    save(runtime_path, model)
    textures, texture_records = {}, {}
    for expr in read(LIB / 'ecology/house-yukkuri/config.json')['expressions']:
        exprpath = LIB / 'assembled' / species / 'textures' / (expr + '.png')
        source = HOUSE / (ident + '_' + expr + '.png') if is_house else exprpath
        output = ASSETS / 'textures/entity' / ident / (expr + '.png')
        output.parent.mkdir(parents=True, exist_ok=True)
        (target / 'textures').mkdir(exist_ok=True)
        shutil.copyfile(source, output)
        shutil.copyfile(source, target / 'textures' / (expr + '.png'))
        textures[expr] = 'data:image/png;base64,' + base64.b64encode(source.read_bytes()).decode()
        texture_records[expr] = {'source': relative(source), 'runtime': relative(output), 'sha256': fingerprint(output)}
    provenance = {'selected_model': relative(bbpath), 'selected_model_sha256': fingerprint(bbpath),
                  'selected_rig': relative(rigpath), 'selected_rig_sha256': fingerprint(rigpath),
                  'standard_motion_source': relative(LIB / 'assembled' / species / 'animation-rig.json'),
                  'standard_motion_sha256': fingerprint(LIB / 'assembled' / species / 'animation-rig.json'),
                  'snapshot': relative(target / (ident + '.bbmodel')), 'rig': relative(target / 'animation-rig.json'),
                  'runtime': relative(runtime_path), 'runtime_sha256': fingerprint(runtime_path),
                  'geometry_identifier': ident, 'bone_count': len(bones), 'clips': list(clips), 'textures': texture_records,
                  'shape_status': 'pending_confirmation' if is_house else 'selected_standard_export',
                  'game_acceptance': 'not_executed', 'retargeting': retargeting}
    return ident, model, textures, provenance


def main():
    house_only = '--house-only' in sys.argv
    if (ROOT/'art/source/rounded-runtime/manifest.json').exists() and not house_only:
        raise SystemExit('Rounded selection is active. Use export_rounded_runtime.py; this legacy seven-model exporter would overwrite selected geometry and snapshots.')
    catalog, records = {}, {}
    if house_only:
        previous = (OUT/'preview-data.js').read_text(encoding='utf-8').removeprefix('const MODELS=').rstrip(';\n')
        catalog = json.loads(previous)
        records = read(OUT/'provenance.json')['entities']
    for house in ((True,) if house_only else (False, True)):
        for species in (('reimu', 'marisa') if house else ('reimu', 'marisa', 'chen', 'alice', 'patchouli')):
            ident, model, textures, record = export(species, house)
            catalog[ident] = {'model': model, 'textures': textures}
            records[ident] = record
            print(ident, len(model['weights']), 'vertices;', len(model['clips']), 'clips')
    dependencies = [ROOT / 'scripts/runtime_animation.py', ROOT / 'scripts/runtime_house_interactions.py', OUT / 'carry-motion/recipe.json', ROOT / 'scripts/runtime_waddle.py', LIB / 'geometry.py', LIB / 'ecology/house-yukkuri/mesh_geometry.py', LIB / 'ecology/house-yukkuri/config.json']
    provenance_record = read(OUT/'provenance.json') if house_only else {}
    save(OUT / 'provenance.json', {**provenance_record,
                                  'exporter': relative(Path(__file__)), 'exporter_sha256': fingerprint(Path(__file__)),
                                  'export_dependencies': {relative(p): fingerprint(p) for p in dependencies},
                                  'entities': records}, pretty=True)
    old = read(ROOT / 'art/runtime-sources.json')
    save(ROOT / 'art/runtime-sources.json', {
        **old,
        'schema_version': 2, 'path_base': 'repository_root', 'automatic_promotion': False,
        'standard_authoring_library': 'standards/yukkuri-library',
        'purpose': 'Seven selected entity models and retargeted numeric animation rigs',
        'models': {**old.get('models', {}), **records},
        'preview': relative(OUT / 'preview.html'), 'exporter': relative(Path(__file__))}, pretty=True)
    (OUT / 'preview-data.js').write_text('const MODELS=' + json.dumps(catalog, separators=(',', ':')) + ';', encoding='utf-8')


if __name__ == '__main__':
    main()
