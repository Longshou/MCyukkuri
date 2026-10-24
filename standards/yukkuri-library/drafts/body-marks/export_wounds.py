"""Bake the existing body-mark study into cached skin decal textures.

The study remains the authoring source: no material formula is duplicated in
Java and no procedural pixels are evaluated during rendering. Scorch samples
the existing char material inside a local wound footprint; sole signs keep
their own coverage rules. Run with --check for a read-only output comparison.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
from functools import partial

import numpy as np
from PIL import Image

import preview as source

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SNAPSHOT = ROOT / 'art/source/body-wound-materials'
RUNTIME = ROOT / 'src/main/resources/assets/mcyukkuri/textures/props/body_wounds'
SIZE = 256
RADIUS = 2.0
MATERIALS = {
    'scratch': (source.scratch, 4.7, 2.9),
    'dent': (source.dent, 4.6, 2.8),
    'tear': (source.tear, 4.65, 2.9),
    'scorch': (source.char, 0.0, 0.35),
    'scar': (source.scar, 4.6, 2.8),
    **{f'dirt_{level}': (partial(source.dirt, level=level), 4.8, 1.9) for level in range(1, 4)},
    **{f'mold_{level}': (partial(source.mold, level=level), 4.7, 3.3) for level in range(1, 5)},
    'char': (source.char, 0.0, 0.0),
}


class Tile:
    """The study's Surface interface with transparent premultiplied layers."""
    def __init__(self, center_u, center_v, kind=''):
        self.kind = kind
        self.center_u, self.center_v = center_u, center_v
        grid = (np.arange(SIZE, dtype=np.float32) + .5) / SIZE
        self.x, self.y = np.meshgrid((grid * 2 - 1) * RADIUS, (1 - grid * 2) * RADIUS)
        self.u, self.v = self.x + center_u, self.y + center_v
        if kind == 'char':
            # The authored sole is a continuous band, not six repeated stamps.
            self.u, self.v = np.meshgrid(((np.arange(1024)+.5)/1024*2-1)*np.pi*6.4, 2-grid*2.5)
        self.color = np.zeros((*self.u.shape, 3), dtype=np.float32)
        self.alpha = np.zeros(self.u.shape, dtype=np.float32)

    def xy(self, u, v, size=1, tilt=0):
        if self.kind.startswith(('mold_', 'dirt_')) and (u != self.center_u or v != self.center_v):
            # Sample one authored colony/smear. Runtime owns stable body sites;
            # adjacent preview-only placements must not leak into this tile.
            return np.full_like(self.u, 1e6), np.full_like(self.v, 1e6)
        x, y = (self.u-u)/size, (self.v-v)/size
        c, s = np.cos(tilt), np.sin(tilt)
        return c*x+s*y, -s*x+c*y

    def put(self, key, amount):
        alpha = np.clip(amount, 0, 1)
        color = source.PALETTE[key] if isinstance(key, str) else np.array(key)
        self.color = self.color * (1-alpha[..., None]) + color * alpha[..., None]
        self.alpha = self.alpha * (1-alpha) + alpha

    def finish(self, kind):
        color = self.color / np.maximum(self.alpha[..., None], 1e-8)
        alpha = self.alpha
        if kind == 'scorch':
            # Local burns use the same authored toast/char/crack layers, clipped
            # to a skin patch instead of repeating the full bottom-edge band.
            alpha = alpha * (1-source.smooth(.75, 1.1, np.hypot(self.x, self.y/.8)))
        elif kind.startswith(('mold_', 'dirt_')):
            # Fade only the sampling window, keeping authored pigment unchanged.
            alpha = alpha * (1-source.smooth(1.75, RADIUS, np.maximum(np.abs(self.x), np.abs(self.y))))
        if not np.isfinite(color).all() or not np.isfinite(alpha).all():
            raise ValueError(f'Non-finite material: {kind}')
        rgba = np.dstack((np.clip(color, 0, 255), np.clip(alpha*255, 0, 255))).round().astype(np.uint8)
        rgba[rgba[..., 3] == 0, :3] = 0
        out = io.BytesIO()
        Image.fromarray(rgba).save(out, format='PNG')
        return out.getvalue()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def text_bytes(path):
    # Git uses LF in the tree and may check out CRLF on Windows.
    return path.read_text(encoding='utf-8').encode('utf-8')


def outputs():
    files = {}
    manifest = {
        'source': str(HERE.relative_to(ROOT)).replace('\\', '/'),
        'material_source': 'preview.py',
        'recipe': 'recipe.json',
        'runtime_status': 'pending_gameplay_visual_review',
        'scope': 'existing scratch, dent, tear, local scorch, healed scar, three dirt stages, four mould stages and continuous baked sole; core geometry exported separately by export_cavity.cjs',
        'texture_size': SIZE,
        'local_radius': RADIUS,
        'sole_projection': {'width': 1024, 'height': SIZE, 'u': [-float(np.pi*6.4), float(np.pi*6.4)], 'v': [-0.5, 2.0], 'body_height': 9.6, 'top_is_v_max': True},
        'text_fingerprint_newlines': 'LF',
        'source_sha256': {name: digest(text_bytes(HERE/name)) for name in ('preview.py', 'recipe.json', 'export_wounds.py')},
        'materials': {},
    }
    for kind, (author, u, v) in MATERIALS.items():
        tile = Tile(u, v, kind)
        author(tile)
        data = tile.finish(kind)
        selected, runtime = SNAPSHOT/f'{kind}.png', RUNTIME/f'{kind}.png'
        files[selected] = files[runtime] = data
        manifest['materials'][kind] = {
            'source_function': author.func.__name__ if isinstance(author, partial) else author.__name__,
            'stage': author.keywords['level'] if isinstance(author, partial) else None,
            'snapshot': selected.relative_to(ROOT).as_posix(),
            'runtime': runtime.relative_to(ROOT).as_posix(),
            'sha256': digest(data),
        }
    files[SNAPSHOT/'recipe.json'] = text_bytes(HERE/'recipe.json')
    files[SNAPSHOT/'manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2)+'\n').encode('utf-8')
    return files


def preview(node):
    """Sample the exported PNGs on the existing offline model surface."""
    buffers = source.render_buffers(node, only={'reimu'})
    rgba, buf = buffers['reimu']
    cards = []
    titles = {'scratch': '擦伤', 'dent': '压伤', 'tear': '裂伤', 'scorch': '局部灼伤', 'scar': '旧疤', 'char': '连续足缘焦化',
              **{f'dirt_{i}': f'脏污 {i}' for i in range(1, 4)}, **{f'mold_{i}': f'油霉 {i}' for i in range(1, 5)}}
    for kind, (_, center_u, center_v) in MATERIALS.items():
        surface = source.Surface(rgba, buf)
        if kind == 'scorch':
            center_u, center_v = 4.6, 2.8
        x, y = surface.u-center_u, surface.v-center_v
        px = (x/RADIUS+1)*.5*SIZE-.5
        py = (1-y/RADIUS)*.5*SIZE-.5
        valid = (px >= 0) & (py >= 0) & (px <= SIZE-1) & (py <= SIZE-1)
        texture = np.asarray(Image.open(RUNTIME/f'{kind}.png').convert('RGBA'), dtype=np.float32)
        if kind == 'char':
            px = (surface.u/(np.pi*6.4)+1)*.5*1024-.5
            py = (2-surface.v)/2.5*SIZE-.5
            valid = (py >= 0) & (py <= SIZE-1)
        width = texture.shape[1]
        ix, iy = np.clip(px.round().astype(int), 0, width-1), np.clip(py.round().astype(int), 0, SIZE-1)
        sample = texture[iy, ix]
        alpha = sample[:, 3:4]/255*valid[:, None]
        surface.color = surface.color*(1-alpha)+sample[:, :3]*surface.light*alpha
        cards.append((titles[kind], '已导出贴图，沿当前角色皮面离线展示', surface.finish()))
    output = ROOT/'.local/previews/body-marks-runtime'
    output.mkdir(parents=True, exist_ok=True)
    source.sheet('当前选用身体印记与伤口材质', '复用此前制作的表面材质；保留游戏中的实际阶段、伤口位置和局部变形。', cards, 4,
                 footer='离线贴图预览 / 无游戏或浏览器截图 / 动态贴合、透明排序与距离效果待用户试玩').save(output/'current.png')
    (output/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>当前选用伤口材质</title>'
                                  '<body style="margin:0;background:#f4f0e6"><img style="width:100%" src="current.png" alt="当前伤口材质">', encoding='utf-8')
    print(output/'current.png')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--preview', action='store_true')
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    files = outputs()
    stale = []
    for path, data in files.items():
        if args.check:
            current = None if not path.exists() else text_bytes(path) if path.suffix == '.json' else path.read_bytes()
            if current != data:
                stale.append(path.relative_to(ROOT).as_posix())
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if stale:
        raise SystemExit('Stale wound materials: '+', '.join(stale))
    print(f'{"Verified" if args.check else "Exported"} {len(MATERIALS)} existing wound materials ({len(files)} files)')
    if args.preview:
        preview(args.node)


if __name__ == '__main__':
    main()
