#!/usr/bin/env python3
"""Select the rare forms into the game: models, textures, wound surfaces, presentation data and props.

    python standards/yukkuri-library/subspecies/rare-forms/build.py
    python scripts/export_rare_forms.py [--check]

Reads standards/yukkuri-library/subspecies/rare-forms/assembled/<form>/ and the packaged data of each form's base.
A derived form keeps its base's vertex count and part order, so every base entry keyed by asset id is carried
over under the form's id with the form's own model hash; only data of parts the recipe changed is rebuilt.
Run it after any exporter that rewrites the shared presentation files (it only touches its own keys).
Writes the snapshot art/source/rare-forms-runtime/, yukkuri_presentation/hat_seat.json and the 'rare_forms' record in
art/runtime-sources.json.
No game, compiler or browser is started.
"""
import argparse
import copy
import gzip
import hashlib
import io
import json
import math
import shutil
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / 'standards/yukkuri-library/subspecies/rare-forms'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
SNAPSHOT = ROOT / 'art/source/rare-forms-runtime'
PRESENTATION = ASSETS / 'yukkuri_presentation'
PROPS = ASSETS / 'yukkuri_props'
OPERATION_PROPS = ('iron_enclosure_{}', 'restraint_frame_{}', 'restraint_frame_{}_bare', 'wrap_sling_{}', 'wrap_sling_{}_bare')
# Base variants get their own living entry (the species list); appearance subspecies go to subspecies.json.
SPECIES_FORMS = {'myon'}
# Accessory items of the new forms, built from the form's accessory parts.
ACCESSORY_ITEMS = {'myon': 'myon_hairband', 'marisa_kotatsu': 'marisa_kotatsu'}


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def text(value, pretty=True):
    return (json.dumps(value, ensure_ascii=False, indent=2 if pretty else None,
                       separators=None if pretty else (',', ':'), allow_nan=False) + ('\n' if pretty else '')).encode('utf-8')


STYLES = [dict(indent=2), dict(indent=1), dict(indent=None, separators=(',', ':')), dict(indent=None), dict(indent=4)]


def styled(path, value, container=(), keys=()):
    """Serialise in the shared file's existing style so a merge only shows the added keys. Pretty files are
    re-dumped in their own indent; compact machine exports (whose number formatting Python does not reproduce)
    get the added entries spliced in as text at the end of their container, which must be the last key."""
    old = Path(path).read_bytes()
    original = json.loads(old)
    for ascii_only in (False, True):
        for style in STYLES:
            for newline in (chr(10), ''):
                if (json.dumps(original, ensure_ascii=ascii_only, **style) + newline).encode('utf-8') == old:
                    return (json.dumps(value, ensure_ascii=ascii_only, **style) + newline).encode('utf-8')
    body = old.decode('utf-8')
    tail = body[len(body.rstrip()):]
    body = body.rstrip()
    node = original
    for key in container:
        assert list(node)[-1] == key, f'{path}: {key} is not the last key'
        node = node[key]
    closing = len(container) + 1
    assert body.endswith('}' * closing), path
    inner = body[:-closing]
    # Drop this exporter's previous entries (always appended last), then append the current ones.
    present = [k for k in keys if k in node]
    if present:
        cut = min(inner.rfind(',' + json.dumps(k) + ':') for k in present)
        assert cut > 0, path
        inner = inner[:cut]
    target = value
    for key in container:
        target = target[key]
    added = ''.join(',' + json.dumps(k, ensure_ascii=False) + ':' + json.dumps(target[k], ensure_ascii=False, separators=(',', ':'))
                    for k in keys)
    result = (inner + added + '}' * closing + tail).encode('utf-8')
    assert json.loads(result) == value, f'{path}: splice mismatch'
    return result


def sha(data):
    return hashlib.sha256(data).hexdigest()


def root_base(recipe, form):
    spec = recipe['forms'][form]
    return root_base(recipe, spec['base_form']) if 'base_form' in spec else spec['base']


def changed_parts(derived):
    ops = derived['operations']
    return set(ops.get('added_parts', [])) | set(ops.get('collapsed_parts', [])) | set(ops.get('reshaped_parts', []))


def part_box(model, name):
    p = next(p for p in model['parts'] if p['name'] == name)
    v = model['vertices'][p['start'] * 8:(p['start'] + p['count']) * 8]
    xs, ys, zs = v[0::8], v[1::8], v[2::8]
    return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))


def envelope_entry(model, name):
    lo, hi = part_box(model, name)
    return {'name': name, 'x': round(max(abs(lo[0]), abs(hi[0])), 7),
            'yz': [[round(y, 7), round(z, 7)] for y in (hi[1], lo[1]) for z in (lo[2], hi[2])]}


def gz(value):
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode='wb', mtime=0) as out:
        out.write(json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
    return buffer.getvalue()


def badge_on_face(model, part_name, point):
    """Badge mount on the first triangle of the part that contains the authored point (barycentric weights)."""
    p = next(p for p in model['parts'] if p['name'] == part_name)
    v = model['vertices']
    for t in range(p['start'], p['start'] + p['count'], 3):
        a, b, c = ([v[(t + k) * 8 + i] for i in range(3)] for k in range(3))
        n = [v[t * 8 + 5 + i] for i in range(3)]
        if n != [0, 0, -1] and n != [0.0, 0.0, -1.0]:
            continue
        # 2D barycentric in the XY plane of a north face
        det = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(det) < 1e-9:
            continue
        w0 = ((b[1] - c[1]) * (point[0] - c[0]) + (c[0] - b[0]) * (point[1] - c[1])) / det
        w1 = ((c[1] - a[1]) * (point[0] - c[0]) + (a[0] - c[0]) * (point[1] - c[1])) / det
        w2 = 1 - w0 - w1
        if min(w0, w1, w2) >= 0:
            return t, [w0, w1, w2], [a[0] * w0 + b[0] * w1 + c[0] * w2, a[1] * w0 + b[1] * w1 + c[1] * w2, a[2]]
    raise SystemExit(f'No north triangle of {part_name} contains {point}')


def accessory_mesh(model, prefixes, texture):
    """Detached accessory prop from the form's own accessory parts (rest pose, no collapsed parts)."""
    vertices, parts = [], []
    for p in model['parts']:
        if not p['name'].startswith(tuple(prefixes)):
            continue
        chunk = model['vertices'][p['start'] * 8:(p['start'] + p['count']) * 8]
        xs = chunk[0::8]
        if max(xs) - min(xs) < 1e-6:
            continue  # folded away by the recipe
        parts.append({'name': p['name'], 'start': len(vertices) // 8, 'count': p['count'], 'texture': texture})
        vertices += chunk
    xs, ys, zs = vertices[0::8], vertices[1::8], vertices[2::8]
    bounds = [[round(min(xs), 7), round(max(xs), 7)], [round(min(ys), 7), round(max(ys), 7)], [round(min(zs), 7), round(max(zs), 7)]]
    return {'format': 1, 'units_per_block': 16, 'vertices': vertices, 'parts': parts, 'bounds': bounds}


def kanroken(spec):
    """The stick sword prop: a rough shaft with a knot and a cut end, a 16x16 wood texture of its own."""
    sys.path.insert(0, str(LIB))
    from build import cuboid, rotated
    image = Image.new('RGBA', (16, 16))
    wood, grain, cut = (tuple(spec['colors'][k]) for k in ('wood', 'grain', 'cut'))
    for x in range(16):
        for y in range(16):
            shade = grain if (y in (3, 9, 13) and x % 5 != 2) or (x * 3 + y) % 11 == 0 else wood
            image.putpixel((x, y), shade + (255,) if x < 12 else cut + (255,))
    vertices, parts = [], []
    for name, rect in (('shaft', (0, 0, 12, 16)), ('knot', (0, 2, 6, 8)), ('tip', (12, 0, 16, 16))):
        lo, hi = spec[name]
        verts = cuboid(lo, hi, rect, 16)
        for i in range(0, len(verts), 8):  # yaw about Y and push forward of the lips
            x, z = verts[i], verts[i + 2]
            a = math.radians(spec['yaw_degrees'])
            verts[i], verts[i + 2] = round(x * math.cos(a) - z * math.sin(a), 6), round(x * math.sin(a) + z * math.cos(a) + spec['forward'], 6)
            nx, nz = verts[i + 5], verts[i + 7]
            verts[i + 5], verts[i + 7] = round(nx * math.cos(a) - nz * math.sin(a), 6), round(nx * math.sin(a) + nz * math.cos(a), 6)
        parts.append({'name': name, 'start': len(vertices) // 8, 'count': len(verts) // 8})
        vertices += verts
    xs, ys, zs = vertices[0::8], vertices[1::8], vertices[2::8]
    model = {'format': 1, 'units_per_block': 16, 'vertices': vertices, 'parts': parts,
             'bounds': [[round(min(xs), 6), round(max(xs), 6)], [round(min(ys), 6), round(max(ys), 6)], [round(min(zs), 6), round(max(zs), 6)]]}
    buffer = io.BytesIO()
    image.save(buffer, 'PNG')
    return model, buffer.getvalue()


def outputs():
    recipe = read(LIB / 'recipe.json')
    files = {}
    staged = {name: read(PRESENTATION / (name + '.json')) for name in
              ('body_geometry', 'family', 'hat_interactions', 'live_birth_pose', 'living', 'motion_corrections',
               'placement_fit', 'subspecies', 'toilet_contact')}
    badges = read(ASSETS / 'yukkuri/badge_mounts.json')
    operations_index = read(PROPS / 'operations-index.json')
    rounded_index = read(PROPS / 'rounded-index.json')
    item_index = read(PROPS / 'index.json')
    records = {}
    for form in recipe['forms']:
        folder = LIB / 'assembled' / form
        derived = read(folder / 'derived.json')
        data = (folder / 'runtime.model.json').read_bytes()
        assert sha(data) == derived['runtime_sha256'], form
        model = json.loads(data)
        base = root_base(recipe, form)
        changed, chain = changed_parts(derived), derived.get('base_form')
        while chain:  # a form built on another form inherits its part changes
            parent = read(LIB / 'assembled' / chain / 'derived.json')
            changed |= changed_parts(parent)
            chain = parent.get('base_form')
        moved = {p['name'] for p in model['parts'] if p['name'].startswith(tuple(recipe['forms'][form].get('tail_parts', ())))}
        digest = sha(data)
        files[ASSETS / 'yukkuri' / (form + '.json')] = data
        files[SNAPSHOT / form / 'runtime.model.json'] = data
        for png in sorted((folder / 'textures').glob('*.png')):
            files[ASSETS / 'textures/entity' / form / png.name] = png.read_bytes()
            files[SNAPSHOT / form / 'textures' / png.name] = png.read_bytes()
        # Wound surface: base skin (unchanged body), this model's hash, envelope rebuilt for the changed parts.
        wounds = json.loads(gzip.decompress((ASSETS / 'yukkuri_wounds' / (base + '.json.gz')).read_bytes()))
        wounds['id'] = form
        wounds['runtimeSourceHash'] = digest
        keep = [e for e in wounds['envelope'] if e['name'] not in changed | moved]
        wounds['envelope'] = keep + [envelope_entry(model, n) for n in sorted(changed | moved)
                                     if n not in recipe['forms'][form].get('collapse_parts', ())
                                     and not any(n.startswith(c) for c in derived['operations'].get('collapsed_parts', []))]
        files[ASSETS / 'yukkuri_wounds' / (form + '.json.gz')] = gz(wounds)
        # Presentation entries keyed by asset id.
        for name, path in (('body_geometry', 'variants'), ('hat_interactions', 'children'), ('live_birth_pose', 'variants'),
                           ('motion_corrections', 'models'), ('toilet_contact', 'models')):
            source = staged[name][path].get(base)
            if source is not None:
                staged[name][path][form] = copy.deepcopy(source)
        fit = staged['placement_fit']['models'].get(base)
        if fit is not None:
            fit = copy.deepcopy(fit)
            fit['base_sha256'] = digest
            fit['parts'] = [p for p in fit['parts'] if p['name'] not in changed]
            staged['placement_fit']['models'][form] = fit
        living = staged['living']['variants'][base] if base in staged['living']['variants'] else staged['subspecies'][base]
        living = dict(copy.deepcopy(living), selected_runtime=f'yukkuri/{form}.json')
        if form in SPECIES_FORMS:
            staged['living']['variants'][form] = living
            staged['family']['variants'][form] = copy.deepcopy(staged['family']['variants'][base])
        else:
            staged['subspecies'][form] = living
        # Badge: same mount as the base unless the recipe moved the accessory; always this model's hash.
        mount = copy.deepcopy(badges['variants'][base])
        mount['model_sha256'] = digest
        badge = recipe['forms'][form].get('badge')
        if badge:
            triangle, weights, point = badge_on_face(model, badge['part'], badge['point'])
            mount.update({'part': badge['part'], 'triangle': triangle, 'weights': weights,
                          'rest_point': point, 'rest_normal': [0.0, 0.0, -1.0]})
        badges['variants'][form] = mount
        # Restraint / sling / enclosure props share the base silhouette.
        for pattern in OPERATION_PROPS:
            source, target = pattern.format(base), pattern.format(form)
            if not (PROPS / (source + '.json')).is_file():
                continue
            files[PROPS / (target + '.json')] = (PROPS / (source + '.json')).read_bytes()
            files[ASSETS / 'textures/props' / (target + '.png')] = (ASSETS / 'textures/props' / (source + '.png')).read_bytes()
            item = ASSETS / 'models/item' / (source + '.json')
            if item.is_file():
                files[ASSETS / 'models/item' / (target + '.json')] = item.read_bytes().replace(source.encode(), target.encode())
            operations_index[target] = copy.deepcopy(operations_index[source])
        # Detached accessory as worn by this form.
        texture = f'mcyukkuri:textures/entity/{form}/neutral.png'
        prefixes = living['accessory_prefixes']
        accessory = accessory_mesh(model, prefixes, texture)
        if not changed:
            accessory = read(PROPS / f'accessory_{base}.json')
            for p in accessory['parts']:
                p['texture'] = texture
        files[PROPS / f'accessory_{form}.json'] = text(accessory, pretty=False)
        rounded_index[f'accessory_{form}'] = {'source_model': form}
        if form in ACCESSORY_ITEMS:
            item = ACCESSORY_ITEMS[form]
            files[PROPS / f'{item}.json'] = text(accessory, pretty=False)
            item_index[item] = {'bounds': accessory['bounds'], 'triangles': len(accessory['vertices']) // 24, 'owner': 'rare_forms'}
            files[ASSETS / 'models/item' / f'{item}.json'] = text({
                'parent': 'minecraft:builtin/entity', 'gui_light': 'front', 'textures': {'particle': f'mcyukkuri:entity/{form}/neutral'},
                'display': {'gui': {'rotation': [25, 225, 0], 'translation': [0, 0, 0], 'scale': [0.85, 0.85, 0.85]},
                            'ground': {'translation': [0, 3, 0], 'scale': [0.5, 0.5, 0.5]}, 'fixed': {'scale': [0.75, 0.75, 0.75]},
                            'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [0.5, 0.5, 0.5]},
                            'firstperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.6, 0.6, 0.6]}}}, pretty=False)
        records[form] = {
            'label': derived['label'], 'base_model': base, 'base_form': derived.get('base_form'),
            'source': (folder / 'runtime.model.json').relative_to(ROOT).as_posix(), 'recipe': (LIB / 'recipe.json').relative_to(ROOT).as_posix(),
            'runtime': f'src/main/resources/assets/mcyukkuri/yukkuri/{form}.json', 'runtime_sha256': digest,
            'snapshot': (SNAPSHOT / form).relative_to(ROOT).as_posix(), 'textures': sorted(derived['textures']),
            'carried_from_base': ['wounds (body skin)', 'body_geometry', 'hat_interactions.children', 'live_birth_pose',
                                  'motion_corrections', 'toilet_contact', 'placement_fit (unchanged parts)', 'badge mount', 'operation props'],
            'rebuilt': sorted(changed | moved), 'shape_status': 'pending_confirmation', 'game_acceptance': 'not_executed'}
    containers = {'body_geometry': ('variants',), 'family': ('variants',), 'hat_interactions': ('children',),
                  'live_birth_pose': ('variants',), 'living': ('variants',), 'motion_corrections': ('models',),
                  'placement_fit': ('models',), 'subspecies': (), 'toilet_contact': ('models',)}
    forms = list(recipe['forms'])
    # Seat parts of accessories that reach the ground: a small (regrowing) one is scaled about them (headwear.HatSeat).
    files[PRESENTATION / 'hat_seat.json'] = text({'format': 1, 'source': (LIB / 'recipe.json').relative_to(ROOT).as_posix(), 'models': {
        form: spec['accessory_seat'] for form, spec in recipe['forms'].items() if spec.get('accessory_seat')}})
    stick, stick_png = kanroken(recipe['props']['kanroken'])
    files[PROPS / 'kanroken.json'] = text(stick, pretty=False)
    files[ASSETS / 'textures/props/kanroken.png'] = stick_png
    item_index['kanroken'] = {'bounds': stick['bounds'], 'triangles': len(stick['vertices']) // 24, 'owner': 'rare_forms'}
    for name, value in staged.items():
        node = value
        for key in containers[name]:
            node = node[key]
        files[PRESENTATION / (name + '.json')] = styled(PRESENTATION / (name + '.json'), value, containers[name], [f for f in forms if f in node])
    files[ASSETS / 'yukkuri/badge_mounts.json'] = styled(ASSETS / 'yukkuri/badge_mounts.json', badges)
    for name, value, keys in (('operations-index', operations_index, [k for k in operations_index if any(k.endswith('_' + f) or k.endswith('_' + f + '_bare') for f in forms)]),
                              ('rounded-index', rounded_index, [f'accessory_{f}' for f in forms]),
                              ('index', item_index, list(ACCESSORY_ITEMS.values()) + ['kanroken'])):
        files[PROPS / (name + '.json')] = styled(PROPS / (name + '.json'), value, (), keys)
    sources = read(ROOT / 'art/runtime-sources.json')
    sources['rare_forms'] = {'recipe': (LIB / 'recipe.json').relative_to(ROOT).as_posix(), 'builder': (LIB / 'build.py').relative_to(ROOT).as_posix(),
                             'exporter': 'scripts/export_rare_forms.py', 'snapshot': SNAPSHOT.relative_to(ROOT).as_posix(), 'forms': records}
    files[ROOT / 'art/runtime-sources.json'] = styled(ROOT / 'art/runtime-sources.json', sources)
    return files


def same(path, data):
    if not path.is_file():
        return False
    old = path.read_bytes()
    if old == data:
        return True
    if path.suffix == '.json':
        try:
            return json.loads(old) == json.loads(data)
        except ValueError:
            return False
    if path.suffix == '.gz':
        return gzip.decompress(old) == gzip.decompress(data)
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    files = outputs()
    stale = [p for p, d in files.items() if not same(p, d)]
    if args.check:
        if stale:
            raise SystemExit('Out of date:\n' + '\n'.join(p.relative_to(ROOT).as_posix() for p in stale))
        print(f'{len(files)} files current')
        return
    for path in stale:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(files[path])
    print(f'{len(stale)} of {len(files)} files written')


if __name__ == '__main__':
    sys.exit(main())
