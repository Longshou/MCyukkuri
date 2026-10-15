"""Compile selected motion overrides, the v7 cheek puff and the world-setting clips (with Marisa's braid-and-hat clips); base models stay untouched.

Reads the contact-safe recipe and all selected runtime models with the placement
fit spliced in (scripts/placement_fit.py), the meshes the game shows. Writes the motion
resource, derived cheek-puff face atlases and the source manifest. --check is
read-only. Never launches a game or UI.
"""
from pathlib import Path
import argparse, hashlib, io, json, math, runpy
from PIL import Image
import placement_fit

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'standards/yukkuri-library/motions/variants/contact-safe'
LORE = ROOT/'standards/yukkuri-library/motions/variants/lore'
VESSEL = ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json'
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
OUTPUT = ASSETS/'yukkuri_presentation/motion_corrections.json'
MANIFEST = ROOT/'art/source/motion-corrections/manifest.json'


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def rel(path): return path.relative_to(ROOT).as_posix()


def part(model, name):
    index = [p['name'] for p in model['parts']].index(name)
    return index, model['parts'][index]


def part_vertices(model, name, patch=None):
    index, p = part(model, name)
    if patch and patch['index'] == index:
        return patch['vertices']
    return model['vertices'][p['start']*8:(p['start']+p['count'])*8]


def uv_box(vertices, size):
    front = [vertices[k:k+8] for k in range(0, len(vertices), 8) if vertices[k+7] < -.2]
    return [round(min(v[3] for v in front)*size), round(min(v[4] for v in front)*size),
            round(max(v[3] for v in front)*size), round(max(v[4] for v in front)*size)]


def ink_points(vertices, image):
    """Model-space (x, y) of every opaque texel corner on the decal's front faces."""
    size = image.width; px = image.load(); points = []
    for k in range(0, len(vertices), 24):
        tri = [vertices[k+j*8:k+j*8+8] for j in range(3)]
        if tri[0][7] >= -.2:
            continue
        a, b, c = tri
        den = (b[4]-c[4])*(a[3]-c[3])+(c[3]-b[3])*(a[4]-c[4])
        if abs(den) < 1e-12:
            continue
        for y in range(max(0, int(min(v[4] for v in tri)*size)), min(size, math.ceil(max(v[4] for v in tri)*size))):
            for x in range(max(0, int(min(v[3] for v in tri)*size)), min(size, math.ceil(max(v[3] for v in tri)*size))):
                if px[x, y][3] < 128:
                    continue
                for dx, dy in [(0, 0), (1, 0), (0, 1), (1, 1), (.5, .5)]:
                    u, v = (x+dx)/size, (y+dy)/size
                    wa = ((b[4]-c[4])*(u-c[3])+(c[3]-b[3])*(v-c[4]))/den
                    wb = ((c[4]-a[4])*(u-c[3])+(a[3]-c[3])*(v-c[4]))/den
                    if min(wa, wb, 1-wa-wb) < -1e-5:
                        continue
                    points.append([round(wa*a[j]+wb*b[j]+(1-wa-wb)*c[j], 5) for j in range(2)])
    return points


def front_box(vertices):
    front = [vertices[k:k+8] for k in range(0, len(vertices), 8) if vertices[k+7] < -.2]
    xs = [v[0] for v in front]; ys = [v[1] for v in front]
    return min(xs), max(xs), min(ys), max(ys)


def mouth_transform(reference):
    """Relative placement of the standard puff mouth inside the neutral mouth box."""
    index, _ = part(reference, 'face_mouth')
    patch = next(q for q in reference['expression_patches']['puff'] if q['index'] == index)
    x0, x1, y0, y1 = front_box(part_vertices(reference, 'face_mouth'))
    p0, p1, q0, q1 = front_box(patch['vertices'])
    w, h = x1-x0, y1-y0
    return {'sx': (p1-p0)/w, 'sy': (q1-q0)/h, 'dx': ((p0+p1)-(x0+x1))/2/w, 'dy': ((q0+q1)-(y0+y1))/2/h}


def mouth_patch(model, transform):
    """Own standard puff mouth geometry, or the same relative placement derived."""
    index, p = part(model, 'face_mouth')
    for q in model.get('expression_patches', {}).get('puff', []):
        if q['index'] == index:
            return {'index': index, 'vertices': q['vertices']}, 'standard_puff_patch'
    source = part_vertices(model, 'face_mouth'); x0, x1, y0, y1 = front_box(source)
    w, h = x1-x0, y1-y0; cx, cy = (x0+x1)/2, (y0+y1)/2; t = transform; out = list(source)
    for k in range(0, len(out), 8):
        out[k] = round(cx+t['dx']*w+(source[k]-cx)*t['sx'], 7)
        out[k+1] = round(cy+t['dy']*h+(source[k+1]-cy)*t['sy'], 7)
    return {'index': index, 'vertices': out}, 'derived_from_standard_puff'


def cheek_texture(model, tex, reference_mouth, cfg, patch):
    """Open neutral eyes and brows, the standard pursed puff mouth, a warmer blush."""
    neutral = Image.open(tex/'neutral.png').convert('RGBA'); size = neutral.width; out = neutral.copy()
    box = uv_box(patch['vertices'], size)
    own = tex/'puff.png'
    mouth = Image.open(own).convert('RGBA').crop(box) if own.exists() else \
        reference_mouth.resize((box[2]-box[0], box[3]-box[1]), Image.NEAREST)
    out.paste(mouth, box[:2])
    tint, weight = cfg['blush_tint'], cfg['blush_tint_weight']
    for name in ('face_blush_left', 'face_blush_right'):
        b = uv_box(part_vertices(model, name), size); region = neutral.crop(b); px = region.load()
        for y in range(region.height):
            for x in range(region.width):
                r, g, bb, a = px[x, y]
                if a >= 128:
                    px[x, y] = tuple(round(c*(1-weight)+t*weight) for c, t in zip((r, g, bb), tint))+(a,)
        out.paste(region, b[:2])
    data = io.BytesIO(); out.save(data, format='PNG'); return data.getvalue(), out


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true')
    parser.add_argument('--only', help='comma-separated model ids; alone it writes nothing, use with --trial')
    parser.add_argument('--trial', help='write a trial resource and textures to this directory, never the runtime')
    parser.add_argument('--fit', help='placement fit resource to author against (default: the runtime one)')
    args = parser.parse_args()
    source_files = [SOURCE/'recipe.json', SOURCE/'author.py', LORE/'recipe.json', LORE/'author.py', LORE/'hat.py', VESSEL, Path(__file__)]
    source_hashes = {path: sha(path) for path in source_files}
    cfg = json.loads((SOURCE/'recipe.json').read_text(encoding='utf-8'))
    compile_revision = runpy.run_path(str(SOURCE/'author.py'))['compile_revision']
    reference = json.loads((ASSETS/'yukkuri'/(cfg['derived_mouth_from']+'.json')).read_text(encoding='utf-8'))
    transform = mouth_transform(reference)
    ref_patch, _ = mouth_patch(reference, transform)
    ref_puff = Image.open(ASSETS/'textures/entity'/cfg['derived_mouth_from']/'puff.png').convert('RGBA')
    reference_mouth = ref_puff.crop(uv_box(ref_patch['vertices'], ref_puff.width))
    only = set(args.only.split(',')) if args.only else None
    models, inputs, textures = {}, {}, {}
    # Cheeks, side-lock swings and obstacles are authored on the placement-fitted meshes the game shows.
    fit_path = Path(args.fit) if args.fit else placement_fit.RESOURCE
    fits = placement_fit.load(fit_path)
    for path in sorted((ASSETS/'yukkuri').glob('*.json')):
        model = json.loads(path.read_text(encoding='utf-8'))
        if not all(k in model for k in ['bones', 'clips', 'weights']):
            continue
        model = placement_fit.fitted(path, fits)
        ident = model['id']
        if only and ident not in only:
            continue
        tex = ASSETS/'textures/entity'/ident
        patch, mouth_source = mouth_patch(model, transform)
        png, image = cheek_texture(model, tex, reference_mouth, cfg, patch)
        neutral = Image.open(tex/'neutral.png').convert('RGBA')
        face = {'eyes': ink_points(part_vertices(model, 'face_eye_left'), neutral)+ink_points(part_vertices(model, 'face_eye_right'), neutral),
                'mouth': ink_points(patch['vertices'], image), 'mouth_patch': patch}
        models[ident] = compile_revision(model, cfg, face)
        textures[ident] = png
        inputs[ident] = {'model': rel(path), 'sha256': sha(path), 'placement_fit': ident in fits,
                         'expression_sources': {'eyes_brows_blush': rel(tex/'neutral.png'),
                                                'mouth': rel(tex/'puff.png') if (tex/'puff.png').exists() else rel(ASSETS/'textures/entity'/cfg['derived_mouth_from']/'puff.png')},
                         'expression_source_sha256': {'neutral': sha(tex/'neutral.png'), **({'puff': sha(tex/'puff.png')} if (tex/'puff.png').exists() else {})},
                         'mouth_geometry': mouth_source,
                         'texture': rel(tex/'cheek_puff.png'), 'texture_sha256': hashlib.sha256(png).hexdigest(),
                         'lock_degrees': models[ident]['cheek']['locks']}
        print(ident, 'skin vertices', len(models[ident]['puff_surface']['vertices'])//8,
              'triangles', len(models[ident]['puff_surface']['indices'])//3, 'size', models[ident]['cheek']['size'],
              'pressed', models[ident]['cheek']['pressed_vertices'], 'depth', models[ident]['cheek']['press_depth'],
              'locks', {k: v['degrees'] for k, v in models[ident]['cheek']['locks'].items()})
    assert all(sha(path) == fingerprint for path, fingerprint in source_hashes.items()), 'Motion source changed during export; rerun from the current source'
    payload = json.dumps({'format': 2, 'revision': 'aquatic-life-20261016-v11', 'models': models}, separators=(',', ':'), ensure_ascii=False)+'\n'
    if args.trial:
        trial = Path(args.trial); (trial/'textures').mkdir(parents=True, exist_ok=True)
        (trial/'motion_corrections.json').write_text(payload, encoding='utf-8', newline='\n')
        for ident, png in textures.items():
            (trial/'textures'/ident).mkdir(parents=True, exist_ok=True); (trial/'textures'/ident/'cheek_puff.png').write_bytes(png)
        return
    if only:
        return
    manifest = {'status': 'pending_user_review', 'geometry_modified': False, 'runtime_morph_geometry_modified': True,
                'geometry_note': 'Base selected models and their fingerprints are unchanged; the refined puff skin, blush carriers, side-lock swing and mouth patch live in the motion resource.',
                'recipe': rel(SOURCE/'recipe.json'), 'recipe_sha256': sha(SOURCE/'recipe.json'),
                'author': rel(SOURCE/'author.py'), 'author_sha256': sha(SOURCE/'author.py'),
                'lore_recipe': rel(LORE/'recipe.json'), 'lore_recipe_sha256': sha(LORE/'recipe.json'),
                'lore_author': rel(LORE/'author.py'), 'lore_author_sha256': sha(LORE/'author.py'),
                'lore_hat': rel(LORE/'hat.py'), 'lore_hat_sha256': sha(LORE/'hat.py'),
                'hat_vessel': rel(VESSEL), 'hat_vessel_sha256': sha(VESSEL),
                'exporter': rel(Path(__file__)), 'exporter_sha256': sha(Path(__file__)),
                'placement_fit': rel(fit_path), 'placement_fit_sha256': sha(fit_path),
                'runtime': rel(OUTPUT), 'runtime_sha256': hashlib.sha256(payload.encode()).hexdigest(), 'models': inputs}
    records = [(OUTPUT, payload), (MANIFEST, json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')]
    for ident, png in textures.items():
        target = ASSETS/'textures/entity'/ident/'cheek_puff.png'
        if args.check:
            assert target.read_bytes() == png, f'Stale cheek texture: {ident}'
        else:
            target.write_bytes(png)
    for path, text in records:
        if args.check:
            assert path.read_text(encoding='utf-8') == text, f'Stale export: {rel(path)}'
        else:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text, encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
