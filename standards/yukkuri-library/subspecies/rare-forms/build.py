"""Derive the rare forms from the selected game runtime of their base species.

    python standards/yukkuri-library/subspecies/rare-forms/build.py [--check]

Inputs (read only): recipe.json, art/source/rounded-runtime/<base>/runtime.model.json, the packaged
expression textures of the base and the base's placement fit (to seat added parts on the fitted band).
Outputs: assembled/<form>/runtime.model.json, assembled/<form>/textures/*.png, assembled/<form>/derived.json.
The base keeps its vertex count and part order; only texels, the listed part geometry and appended parts
change, so wound, badge, fit and motion data can be carried over by scripts/export_rare_forms.py.
No game, compiler or browser is started.
"""
import argparse
import hashlib
import io
import json
import math
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
RUNTIME = ROOT / 'art/source/rounded-runtime'
OUT = HERE / 'assembled'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def dump(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def luminance(c):
    return .299 * c[0] + .587 * c[1] + .114 * c[2]


def part(model, name):
    return next(p for p in model['parts'] if p['name'] == name)


def texels(model, prefixes, exclude=()):
    """Atlas texels sampled by the named parts (by UV bounding rectangle of every triangle)."""
    size, v, out = model.get('texture_size', 128), model['vertices'], set()
    for p in model['parts']:
        if not p['name'].startswith(tuple(prefixes)) or p['name'].startswith(tuple(exclude)):
            continue
        for t in range(p['start'], p['start'] + p['count'], 3):
            us = [v[(t + k) * 8 + 3] * size for k in range(3)]
            vs = [v[(t + k) * 8 + 4] * size for k in range(3)]
            for x in range(math.floor(min(us) + 1e-4), max(math.ceil(max(us) - 1e-4), math.floor(min(us) + 1e-4) + 1)):
                for y in range(math.floor(min(vs) + 1e-4), max(math.ceil(max(vs) - 1e-4), math.floor(min(vs) + 1e-4) + 1)):
                    if 0 <= x < size and 0 <= y < size:
                        out.add((x, y))
    return out


def tint(pixel, source, target):
    """Keep the authored streaks: scale the target colour by the pixel's brightness relative to the source base."""
    if pixel[3] == 0:
        return pixel
    k = luminance(pixel) / max(1, luminance(source))
    return tuple(max(0, min(255, round(t * k))) for t in target) + (pixel[3],)


def cuboid(lo, hi, uv_rect, size, rotate=None):
    """36 vertices (x y z u v nx ny nz); counter-clockwise outward winding, matching the runtime exporters.
    uv_rect is an atlas texel rectangle (x0, y0, x1, y1); rotate is (degrees about Z, centre)."""
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    faces = [  # normal, four corners in order
        ((0, 0, -1), [(x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0)]),
        ((0, 0, 1), [(x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (x0, y0, z1)]),
        ((-1, 0, 0), [(x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (x0, y0, z0)]),
        ((1, 0, 0), [(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)]),
        ((0, 1, 0), [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)]),
        ((0, -1, 0), [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)]),
    ]
    u0, v0, u1, v1 = [c / size for c in uv_rect]
    corner_uv = [(u0, v1), (u0, v0), (u1, v0), (u1, v1)]
    out = []
    for normal, corners in faces:
        if rotate:
            corners = [rotated(c, *rotate) for c in corners]
            normal = rotated(normal, rotate[0], (0, 0, 0))
        for tri in ((0, 1, 2), (0, 2, 3)):
            pts = [corners[i] for i in tri]
            a = [pts[1][k] - pts[0][k] for k in range(3)]
            b = [pts[2][k] - pts[0][k] for k in range(3)]
            n = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
            order = list(tri) if sum(n[k] * normal[k] for k in range(3)) > 0 else [tri[0], tri[2], tri[1]]
            for i in order:
                c = corners[i]
                out += [round(c[0], 6), round(c[1], 6), round(c[2], 6), round(corner_uv[i][0], 7), round(corner_uv[i][1], 7),
                        round(normal[0], 6), round(normal[1], 6), round(normal[2], 6)]
    return out


def rotated(p, degrees, centre):
    a = math.radians(degrees)
    x, y = p[0] - centre[0], p[1] - centre[1]
    return (centre[0] + x * math.cos(a) - y * math.sin(a), centre[1] + x * math.sin(a) + y * math.cos(a), p[2])


def free_blocks(model, textures, count):
    """8x8 atlas blocks no part samples and no expression texture paints."""
    size = model.get('texture_size', 128)
    used = texels(model, [''])
    found = []
    for by in range(size // 2, size, 8):
        for bx in range(0, size, 8):
            block = [(x, y) for x in range(bx, bx + 8) for y in range(by, by + 8)]
            if any(p in used for p in block):
                continue
            if any(im.getpixel(p)[3] for im in textures.values() for p in block):
                continue
            found.append((bx, by))
            if len(found) == count:
                return found
    raise SystemExit('No free atlas blocks')


def base_inputs(base):
    model_path = RUNTIME / base / 'runtime.model.json'
    raw = model_path.read_bytes()
    packaged = (ASSETS / 'yukkuri' / (base + '.json')).read_bytes()
    if raw != packaged:
        raise SystemExit(f'{base}: selected runtime snapshot differs from the packaged model; re-export the base first')
    # Expression atlases only: remains_* forms are drawn per base body (rare forms keep the sleep face, see remains_art).
    textures = {p.stem: Image.open(p).convert('RGBA') for p in sorted((ASSETS / 'textures/entity' / base).glob('*.png'))
                if not p.stem.startswith('remains_')}
    return json.loads(raw), textures, sha(raw)


def placement(base):
    return read(ASSETS / 'yukkuri_presentation/placement_fit.json')['models'].get(base)


def build_myon(spec, model, textures):
    roles = {}
    for rule in spec['recolor']:
        cells = texels(model, rule['parts'], rule.get('exclude', ()))
        roles[rule['role']] = cells
        for im in textures.values():
            for xy in cells:
                im.putpixel(xy, tint(im.getpixel(xy), rule['source'], rule['target']))
    collapsed = collapse(model, spec.get('collapse_parts', ()))
    bow = spec['bow']
    anchor = part(model, bow['anchor_part'])
    fit = placement(spec['base'])
    fitted = next((p for p in (fit or {}).get('parts', []) if p['name'] == bow['anchor_part']), None)
    if fitted:  # seat on the band as the game actually draws it
        xs, ys, zs = fitted['vertices'][0::5], fitted['vertices'][1::5], fitted['vertices'][2::5]
    else:
        v = model['vertices'][anchor['start'] * 8:(anchor['start'] + anchor['count']) * 8]
        xs, ys, zs = v[0::8], v[1::8], v[2::8]
    centre = ((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2 + bow['lift'], min(zs) - bow['front_gap'])
    block = sorted(roles[bow['texel_role']])
    bx, by = min(x for x, _ in block), min(y for _, y in block)
    uv = (bx + .5, by + .5, bx + 3.5, by + 3.5)
    weight = model['weights'][anchor['start']]
    added = []
    for name, (lo, hi) in bow['boxes'].items():
        lo = [centre[i] + lo[i] for i in range(3)]
        hi = [centre[i] + hi[i] for i in range(3)]
        verts = cuboid(lo, hi, uv, model.get('texture_size', 128), (bow['tilt_degrees'], centre))
        start = len(model['vertices']) // 8
        model['vertices'] += verts
        model['weights'] += [list(weight) for _ in range(len(verts) // 8)]
        model['parts'].append({'name': name, 'start': start, 'count': len(verts) // 8, 'anatomy': ''})
        added.append(name)
    return {'recoloured_texels': {k: len(v) for k, v in roles.items()}, 'added_parts': added, 'collapsed_parts': collapsed,
            'bow_centre': [round(c, 4) for c in centre]}


def collapse(model, prefixes):
    """Fold the listed parts into one point inside their own bounds: index layout stays, nothing is drawn."""
    names = []
    v = model['vertices']
    for p in model['parts']:
        if not p['name'].startswith(tuple(prefixes)):
            continue
        idx = range(p['start'], p['start'] + p['count'])
        centre = [sum(v[i * 8 + c] for i in idx) / p['count'] for c in range(3)]
        for i in idx:
            v[i * 8:i * 8 + 3] = [round(c, 6) for c in centre]
        names.append(p['name'])
    return names


def build_redeye(spec, model, textures):
    cells = texels(model, spec['eye_parts'])
    iris = spec['iris']
    changed = 0
    for im in textures.values():
        for xy in cells:
            p = im.getpixel(xy)
            if p[3] and luminance(p) < iris['max_luminance'] and p[0] - p[2] >= iris['min_warmth']:
                k = max(.35, luminance(p) / 70)
                im.putpixel(xy, tuple(max(0, min(255, round(c * k))) for c in iris['target']) + (p[3],))
                changed += 1
    return {'recoloured_eye_pixels': changed}


def build_tail(spec, model, textures):
    bones = model['bones']
    v = model['vertices']
    moved = 0
    for p in model['parts']:
        if not p['name'].startswith(tuple(spec['tail_parts'])):
            continue
        pivot = bones[model['weights'][p['start']][0]]['pivot']
        if 'tail_ball' in spec:
            # A bobbed tail: every segment slides toward the root and swells, so the seven pieces overlap as one tuft.
            ball = spec['tail_ball']
            idx = range(p['start'], p['start'] + p['count'])
            centre = [sum(v[i * 8 + c] for i in idx) / p['count'] for c in range(3)]
            target = [pivot[c] + (centre[c] - pivot[c]) * ball['pull'] for c in range(3)]
            target[1] += ball.get('lift', 0)
            for i in idx:
                for c in range(3):
                    v[i * 8 + c] = round(target[c] + (v[i * 8 + c] - centre[c]) * ball['grow'], 6)
                moved += 1
            continue
        for i in range(p['start'], p['start'] + p['count']):
            k = i * 8
            d = [v[k + c] - pivot[c] for c in range(3)]
            along = max(0.0, d[2])
            x = pivot[0] * (1 - spec.get('tail_center_pull', 0)) + d[0] * spec['tail_scale'][0]
            y = pivot[1] + d[1] * spec['tail_scale'][1] - spec.get('tail_droop', 0) * along
            z = pivot[2] + d[2] * spec['tail_scale'][2]
            v[k], v[k + 1], v[k + 2] = round(x, 6), round(y, 6), round(z, 6)
            moved += 1
    return {'moved_tail_vertices': moved}


def build_kotatsu(spec, model, textures):
    size = model.get('texture_size', 128)
    blocks = free_blocks(model, textures, len(spec['palette']))
    rects = {}
    for (name, colour), (bx, by) in zip(spec['palette'].items(), blocks):
        shading = spec.get('shading', {}).get(name)
        for im in textures.values():
            for x in range(bx, bx + 8):
                for y in range(by, by + 8):
                    if shading:  # authored tones: columns run across every face, rows from its top edge down
                        shade = shading.get('columns', [1] * 8)[x - bx] * shading.get('rows', [1] * 8)[y - by]
                    else:
                        shade = 1 + (((x * 7 + y * 13) % 5) - 2) * .018  # faint weave, no hard pattern
                    im.putpixel((x, y), tuple(max(0, min(255, round(c * shade))) for c in colour) + (255,))
        rects[name] = (bx + .5, by + .5, bx + 7.5, by + 7.5)
    bone = next(i for i, b in enumerate(model['bones']) if b['name'] == spec['accessory_bone'])
    names = {p['name']: p for p in model['parts']}
    reshaped = []
    for name, (material, lo, hi, *extra) in spec['boxes'].items():
        p = names[name]
        verts = cuboid(lo, hi, rects[material], size)
        if extra:  # a sloped edge: turn the box about the X axis through pivot (y, z)
            a, (py, pz) = math.radians(extra[0]['rotate_x']), extra[0]['pivot']
            for i in range(0, len(verts), 8):
                dy, dz, ny, nz = verts[i + 1] - py, verts[i + 2] - pz, verts[i + 6], verts[i + 7]
                verts[i + 1] = round(py + dy * math.cos(a) - dz * math.sin(a), 6)
                verts[i + 2] = round(pz + dy * math.sin(a) + dz * math.cos(a), 6)
                verts[i + 6] = round(ny * math.cos(a) - nz * math.sin(a), 6)
                verts[i + 7] = round(ny * math.sin(a) + nz * math.cos(a), 6)
        if len(verts) // 8 != p['count']:
            raise SystemExit(f'{name}: expected {p["count"]} vertices, authored {len(verts) // 8}')
        model['vertices'][p['start'] * 8:(p['start'] + p['count']) * 8] = verts
        for i in range(p['start'], p['start'] + p['count']):
            model['weights'][i] = [bone, bone, 0]
        reshaped.append(name)
    leftover = [p['name'] for p in model['parts'] if p['name'].startswith(('brim_', 'hat_', 'bow_')) and p['name'] not in reshaped]
    if leftover:
        raise SystemExit('Unshaped Marisa hat parts: ' + ', '.join(leftover))
    return {'palette_blocks': {k: list(map(int, r[:2])) for k, r in rects.items()}, 'reshaped_parts': reshaped}


BUILDERS = {'myon': build_myon, 'myon_redeye': build_redeye, 'chen_longtail': build_tail, 'chen_roundtail': build_tail,
            'marisa_kotatsu': build_kotatsu}


def build(recipe):
    files, built = {}, {}
    for form, spec in recipe['forms'].items():
        if 'base_form' in spec:
            model, textures, base_hash = (json.loads(json.dumps(built[spec['base_form']][0])),
                                          {k: v.copy() for k, v in built[spec['base_form']][1].items()}, None)
            base = built[spec['base_form']][2]
        else:
            base = spec['base']
            model, textures, base_hash = base_inputs(base)
        record = BUILDERS[form](spec, model, textures)
        model['id'] = form
        built[form] = (model, textures, base)
        data = dump(model)
        target = OUT / form
        files[target / 'runtime.model.json'] = data
        texture_hashes = {}
        for name, im in textures.items():
            buffer = io.BytesIO()
            im.save(buffer, 'PNG', optimize=True)
            files[target / 'textures' / (name + '.png')] = buffer.getvalue()
            texture_hashes[name] = sha(buffer.getvalue())
        files[target / 'derived.json'] = (json.dumps({
            'form': form, 'label': spec['label'], 'base_model': base, 'base_form': spec.get('base_form'),
            'base_runtime_sha256': base_hash or (sha(dump(built[spec['base_form']][0])) if spec.get('base_form') else None),
            'runtime_sha256': sha(data), 'vertices': len(model['vertices']) // 8, 'parts': len(model['parts']),
            'status': recipe['status'], 'basis': recipe['basis'][form], 'operations': record, 'textures': texture_hashes,
        }, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='verify the assembled outputs are current; write nothing')
    args = parser.parse_args()
    files = build(read(HERE / 'recipe.json'))
    stale = []
    for path, data in files.items():
        if args.check:
            if not path.is_file() or path.read_bytes() != data:
                stale.append(path.relative_to(ROOT).as_posix())
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if stale:
        raise SystemExit('Out of date:\n' + '\n'.join(stale))
    print(f'{len(files)} files ' + ('checked' if args.check else 'written'))


if __name__ == '__main__':
    sys.exit(main())
