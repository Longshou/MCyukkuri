"""Offline comparison preview of the pet shop across all 5 village styles (old vs new).

Renders per-style top-down cutaway plans (with care props, stock, doors, family marker and numbered goods frames)
and 3/4 street frontage views (village_shop lot with care props, stock and goods frames visible through the glass),
plus a goods-wall / display-rooms cutaway comparison, a master comparison sheet (sheet.png) and index.html in
.local/previews/shop-rooms/ (or MCYUKKURI_PREVIEWS/shop-rooms). Preserves Assets/render/font/rotation/FACES for
sibling preview_*.py scripts. Requires Pillow, NumPy, Node and the Minecraft 1.21.1 client jar. No game or browser.
"""
from pathlib import Path
import argparse, functools, html, io, json, math, os, shutil, subprocess, sys, types, zipfile
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
import build
from palette import PALETTES, STYLES

ASSETS = ROOT / 'src/main/resources/assets'
FACES = {
    'north': ([0, 0, -1], [0, 3, 2, 1]), 'south': ([0, 0, 1], [5, 6, 7, 4]),
    'west': ([-1, 0, 0], [4, 7, 3, 0]), 'east': ([1, 0, 0], [1, 2, 6, 5]),
    'up': ([0, 1, 0], [3, 7, 6, 2]), 'down': ([0, -1, 0], [4, 0, 1, 5]),
}
# Box corners (0 x0y0z0, 1 x1y0z0, 2 x1y1z0, 3 x0y1z0, 4 x0y0z1, 5 x1y0z1, 6 x1y1z1, 7 x0y1z1) in Minecraft's FaceInfo vertex order.
MC_CORNERS = {'down': [4, 0, 1, 5], 'up': [3, 7, 6, 2], 'north': [2, 1, 0, 3], 'south': [7, 4, 5, 6], 'west': [3, 0, 4, 7], 'east': [6, 5, 1, 2]}
DIRS = ('north', 'east', 'south', 'west')
YROT = {'south': 0, 'west': 90, 'north': 180, 'east': 270}
OPAQUE = ('planks', 'cobblestone', 'stone_bricks', 'log', 'wood', 'wool', 'terracotta', 'concrete', 'quartz',
          'andesite', 'diorite', 'sandstone', 'sand', 'gravel', 'grass_block', 'dirt', 'mud_bricks', 'packed_mud',
          'barrel', 'hay_block', 'bookshelf', 'mossy_cobblestone', 'snow_block')
STYLE_ZH = {'plains': '平原', 'desert': '沙漠', 'savanna': '热带草原', 'snowy': '雪原', 'taiga': '针叶林'}
SPECIES_ZH = {'reimu': '灵', 'marisa': '魔', 'alice': '爱', 'chen': '橙', 'patchouli': '帕', 'remilia': '蕾', 'flandre': '芙'}


def font(size):
    for p in ('C:/Windows/Fonts/msyh.ttc', '/System/Library/Fonts/STHeiti Light.ttc', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'):
        if Path(p).exists(): return ImageFont.truetype(p, size)
    raise RuntimeError('A Chinese font is required')


def rotation(axis, angle):
    a = math.radians(angle); c, s = math.cos(a), math.sin(a)
    return np.array({'x': [[1, 0, 0], [0, c, -s], [0, s, c]], 'y': [[c, 0, s], [0, 1, 0], [-s, 0, c]], 'z': [[c, -s, 0], [s, c, 0], [0, 0, 1]]}[axis])


class Assets:
    def __init__(self, jar):
        self.jar = zipfile.ZipFile(jar); self.atlas = Image.new('RGBA', (2048, 2048)); self.slots = {}; self.missing = set()

    def read(self, name, category, suffix):
        ns, key = name.split(':', 1) if ':' in name else ('minecraft', name)
        path = f'{ns}/{category}/{key}.{suffix}'; local = ASSETS / path
        if local.exists(): return local.read_bytes()
        return self.jar.read('assets/' + path)

    @functools.lru_cache(None)
    def model(self, name):
        raw = json.loads(self.read(name, 'models', 'json')); parent = raw.get('parent', '')
        base = {} if not parent or 'builtin' in parent else self.model(parent)
        return {**base, **raw, 'textures': {**base.get('textures', {}), **raw.get('textures', {})}}

    def tex(self, name):
        if name not in self.slots:
            im = Image.open(io.BytesIO(self.read(name, 'textures', 'png'))).convert('RGBA')
            im = im.crop((0, 0, im.width, min(im.width, im.height))).resize((32, 32), Image.Resampling.NEAREST)
            if any(s in name for s in ('leaves', 'grass_block_top')):
                a = np.array(im); a[:, :, :3] = (a[:, :, :3] * np.array([.55, .85, .36])).astype('uint8'); im = Image.fromarray(a)
            i = len(self.slots); assert i < 1024; self.slots[name] = i
            self.atlas.paste(im, ((i % 64) * 32, (i // 64) * 32))
        i = self.slots[name]
        return np.array([i % 64, i // 64]) / 64

    def faces(self, model, offset=(0, 0, 0), xrot=0, yrot=0):
        data = self.model(model); textures = data.get('textures', {}); out = []
        r = rotation('y', -yrot) @ rotation('x', -xrot)
        for e in data.get('elements', []):
            x0, y0, z0 = e['from']; x1, y1, z1 = e['to']
            corners = np.array([[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0],
                                [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]], float)
            er = np.eye(3)
            if 'rotation' in e:
                spec = e['rotation']; origin = np.array(spec['origin']); er = rotation(spec['axis'], spec['angle'])
                corners = (corners - origin) @ er.T + origin
            corners = ((corners - 8) @ r.T + 8) / 16 + offset
            defaults = {'north': [16 - x1, 16 - y1, 16 - x0, 16 - y0], 'south': [x0, 16 - y1, x1, 16 - y0],
                        'west': [z0, 16 - y1, z1, 16 - y0], 'east': [16 - z1, 16 - y1, 16 - z0, 16 - y0],
                        'up': [x0, z0, x1, z1], 'down': [x0, 16 - z1, x1, 16 - z0]}
            for direction, f in e['faces'].items():
                texture = f['texture']; seen = set()
                while texture.startswith('#'):
                    if texture in seen: raise ValueError(texture)
                    seen.add(texture); texture = textures[texture[1:]]
                origin = self.tex(texture); u0, v0, u1, v1 = f.get('uv', defaults[direction])
                normal, ix = FACES[direction]; normal = r @ er @ normal
                corner = [(u0, v0), (u0, v1), (u1, v1), (u1, v0)]; shift = int(f.get('rotation', 0) / 90)
                uv = np.array([corner[(MC_CORNERS[direction].index(c) + shift) % 4] for c in ix], float) / 1024 + origin
                quad = corners[ix]
                for tri in ((0, 1, 2), (0, 2, 3)):
                    for i in tri: out.append([*quad[i], *uv[i], *normal])
        return np.array(out).reshape(-1, 8)

    @staticmethod
    def matches(when, props):
        if 'OR' in when: return any(Assets.matches(x, props) for x in when['OR'])
        if 'AND' in when: return all(Assets.matches(x, props) for x in when['AND'])
        return all(str(props.get(k, 'false')) in str(v).split('|') for k, v in when.items())

    @functools.lru_cache(None)
    def block(self, name, props_tuple):
        if name in ('minecraft:air', 'minecraft:light', 'minecraft:barrier'): return np.empty((0, 8))
        props = dict(props_tuple)
        try:
            state = json.loads(self.read(name, 'blockstates', 'json')); models = []
            if 'variants' in state:
                for key, value in state['variants'].items():
                    when = dict(pair.split('=', 1) for pair in key.split(',') if pair)
                    if self.matches(when, props): models.append(value); break
            else:
                models = [part['apply'] for part in state['multipart'] if self.matches(part.get('when', {}), props)]
            out = []
            for m in models:
                if isinstance(m, list): m = m[0]
                v = self.faces(m['model'], xrot=m.get('x', 0), yrot=m.get('y', 0))
                if len(v): out.append(v)
            return np.concatenate(out) if out else np.empty((0, 8))
        except (KeyError, FileNotFoundError):
            self.missing.add(name); return np.empty((0, 8))


def render(a, vertices, out, name, width, height, az, el, span, center):
    work = out / '_work'; work.mkdir(exist_ok=True)
    vertices = np.asarray(vertices, dtype='<f4').reshape(-1, 8)
    (work / f'{name}.bin').write_bytes(vertices.tobytes()); (work / 'atlas.rgba').write_bytes(a.atlas.tobytes())
    runner = "const fs=require('fs');\n" + (ROOT / 'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf8')
    runner += (f"\nconst d={json.dumps(str(work))};const b=fs.readFileSync(d+'/{name}.bin');"
               f"const v=new Float32Array(b.buffer,b.byteOffset,b.length/4);const t=fs.readFileSync(d+'/atlas.rgba');"
               f"fs.writeFileSync(d+'/{name}.rgba',renderModel(v,t,{width},{height},{az},{el},{span},{{centerY:{center},textureSize:2048}}));")
    (work / 'render.cjs').write_text(runner, encoding='utf8')
    subprocess.run(['node', str(work / 'render.cjs')], check=True)
    im = Image.frombytes('RGBA', (width, height), (work / f'{name}.rgba').read_bytes())
    bg = Image.new('RGBA', im.size, '#edf1eb'); bg.alpha_composite(im); bg.convert('RGB').save(out / f'{name}.png')
    return bg


def label(image, title, subtitle):
    result = Image.new('RGB', (image.width, image.height + 100), '#edf1eb'); result.paste(image, (0, 100))
    d = ImageDraw.Draw(result); d.text((30, 16), title, font=font(30), fill='#273e37'); d.text((30, 62), subtitle, font=font(17), fill='#596f65')
    return result


class SceneProps:
    """Packs yukkuri_props (care blocks, floor toys, 3D goods) and stock yukkuri bodies into unused rows of Assets.atlas."""
    def __init__(self, a):
        self.a = a
        self.prop_slots = {}
        self.prop_json = {}
        self.bodies = {}
        lore = ROOT / 'standards/yukkuri-library/motions/variants/lore'
        if str(lore) not in sys.path: sys.path.insert(0, str(lore))
        sys.modules.pop('preview', None)
        from preview import Posed
        corrections = json.loads((ASSETS / 'mcyukkuri/yukkuri_presentation/motion_corrections.json').read_text(encoding='utf8'))
        for idx, ident in enumerate(SPECIES_ZH):
            posed = Posed(ident, corrections); tri, uv, n = posed.frame('idle', 0)
            unit = posed.model['units_per_block']
            ux, uy = idx * 256, 1280
            tex = Image.fromarray(posed.texture('neutral')).resize((256, 256), Image.Resampling.NEAREST)
            a.atlas.paste(tex, (ux, uy))
            uv = uv.reshape(-1, 2) * (256 / 2048) + np.array([ux, uy]) / 2048
            self.bodies[ident] = np.c_[tri.reshape(-1, 3) / unit, uv, np.repeat(n, 3, axis=0)]

    def prop_tex(self, tex_id):
        if tex_id not in self.prop_slots:
            idx = len(self.prop_slots); assert idx < 48
            ux, uy = (idx % 16) * 128, 512 + (idx // 16) * 128
            im = Image.open(ASSETS / f'mcyukkuri/textures/props/{tex_id}.png').convert('RGBA').resize((128, 128), Image.Resampling.NEAREST)
            self.a.atlas.paste(im, (ux, uy))
            self.prop_slots[tex_id] = np.array([ux, uy], float) / 2048
        return self.prop_slots[tex_id]

    def prop_data(self, mesh_id):
        if mesh_id not in self.prop_json:
            self.prop_json[mesh_id] = json.loads((ASSETS / f'mcyukkuri/yukkuri_props/{mesh_id}.json').read_text(encoding='utf8'))
        return self.prop_json[mesh_id]

    def prop_mesh(self, mesh_id, tex_id=None, units=None, yaw=0.0, scale=1.0, center_bounds=False):
        data = self.prop_data(mesh_id); raw = np.array(data['vertices'], float).reshape(-1, 8)
        if units is not None and 'parts' in data:
            rows = [raw[p['start']:p['start'] + p['count']] for p in data['parts'] if p.get('unit', 0) <= units]
            raw = np.concatenate(rows) if rows else np.empty((0, 8))
        if not len(raw): return np.empty((0, 8))
        v = raw.copy()
        unit = float(data.get('units_per_block', 16))
        xyz = v[:, :3] / unit
        if center_bounds and 'bounds' in data:
            b = data['bounds']
            xyz = xyz - np.array([(b[0][0] + b[0][1]) / 2, (b[1][0] + b[1][1]) / 2, (b[2][0] + b[2][1]) / 2]) / unit
        turn = rotation('y', yaw)
        v[:, :3] = (xyz * scale) @ turn.T
        v[:, 3:5] = v[:, 3:5] * (128 / 2048) + self.prop_tex(tex_id or mesh_id)
        v[:, 5:8] = v[:, 5:8] @ turn.T
        return v

    def care_block(self, name, props, nbt):
        key = name.split(':', 1)[1]
        if key == 'yukkuri_nest':
            facing = props.get('facing', 'north'); i0 = DIRS.index(facing)
            mask = sum((1 << bit) for bit in range(4) if props.get(DIRS[(i0 + bit) % 4]) == 'true')
            mesh_id = 'yukkuri_nest' if mask == 0 else f'pet_nest_fit_{mask}'
            v = self.prop_mesh(mesh_id, 'yukkuri_nest', yaw=180 - YROT[facing])
            v[:, :3] += [.5, 0, .5]
            return v
        if key == 'predator_nest':
            v = self.prop_mesh('predator_nest'); v[:, :3] += [.5, 0, .5]; return v
        if key == 'yukkuri_toilet':
            v = self.prop_mesh('yukkuri_toilet'); v[:, :3] += [.5, 0, .5]; return v
        if key == 'food_bowl':
            parts = [self.prop_mesh('food_bowl')]
            items = nbt['Items'][1][1] if nbt and 'Items' in nbt else []
            if items: parts.append(self.prop_mesh('food_bowl_regular', units=min(8, items[0]['count'][1])))
            v = np.concatenate([p for p in parts if len(p)]); v[:, :3] += [.5, 0, .5]; return v
        if key == 'waterer':
            parts = [self.prop_mesh('waterer')]
            water = nbt['Water'][1] if nbt and 'Water' in nbt else 0
            if water > 0: parts.append(self.prop_mesh('waterer_water', units=min(8, water)))
            v = np.concatenate([p for p in parts if len(p)]); v[:, :3] += [.5, 0, .5]; return v
        return np.empty((0, 8))

    def stock_at(self, ident, x, y, z, age=None, yaw=0.0):
        scale = 1.0 if age is None else (.36 if age < 72_000 else .56 if age < 216_000 else .80)
        v = self.bodies[ident].copy()
        turn = rotation('y', yaw)
        v[:, :3] = (v[:, :3] * scale) @ turn.T + [x, y, z]
        v[:, 5:8] = v[:, 5:8] @ turn.T
        return v

    def frame_at(self, b, fx, fy, fz, item_id):
        bx, by, bz = int(fx // 1), int(fy // 1), int(fz // 1)
        wall_dir = 'south'
        for d_name, (dx, _, dz) in (('south', (0, 0, 1)), ('north', (0, 0, -1)), ('east', (1, 0, 0)), ('west', (-1, 0, 0))):
            nb = b.blocks.get((bx + dx, by, bz + dz), ('minecraft:air', {}, None))[0]
            if nb != 'minecraft:air' and not nb.endswith(('_door', 'glass', 'iron_bars')):
                wall_dir = d_name; break
        # Frame faces away from the supporting wall.
        face_yaw = {'south': 0, 'north': 180, 'east': 90, 'west': -90}[wall_dir]
        turn = rotation('y', face_yaw)
        # Backing board (birch + dark oak border feel using oak_planks &item_frame texture).
        board_uv = self.a.tex('minecraft:block/item_frame')
        s = 1 / 64; h = .38; z_back = .49; z_front = .44
        out = []
        quad = [turn @ np.array(c) + [bx + .5, by + .5, bz + .5] for c in ([-h, h, z_back], [h, h, z_back], [h, -h, z_back], [-h, -h, z_back])]
        uvs = [board_uv + [0, 0], board_uv + [s, 0], board_uv + [s, s], board_uv + [0, s]]
        normal = turn @ np.array([0., 0., -1.])
        for tri in ((0, 1, 2), (0, 2, 3)):
            for k in tri: out.append([*quad[k], *uvs[k], *normal])
        parts = [np.array(out, float).reshape(-1, 8)]
        ns, key = item_id.split(':', 1)
        if (ASSETS / f'mcyukkuri/yukkuri_props/{key}.json').exists():
            data = self.prop_data(key); bounds = data.get('bounds', [[-6, 6], [0, 10], [-6, 6]])
            span = max(max(b1 - b0 for b0, b1 in bounds) / float(data.get('units_per_block', 16)), .25)
            pm = self.prop_mesh(key, scale=min(.55, .48 / span), yaw=face_yaw + 180, center_bounds=True)
            if len(pm):
                pm[:, :3] += turn @ np.array([0., 0., .38]) + [bx + .5, by + .5, bz + .5]
                parts.append(pm)
        else:
            try:
                tex = self.a.model(f'{ns}:item/{key}').get('textures', {}).get('layer0', f'{ns}:item/{key}')
                io_uv = self.a.tex(tex); ih = .28; q2 = []
                quad2 = [turn @ np.array(c) + [bx + .5, by + .5, bz + .5] for c in ([-ih, ih, z_front], [ih, ih, z_front], [ih, -ih, z_front], [-ih, -ih, z_front])]
                uvs2 = [io_uv + [0, 0], io_uv + [s, 0], io_uv + [s, s], io_uv + [0, s]]
                for tri in ((0, 1, 2), (0, 2, 3)):
                    for k in tri: q2.append([*quad2[k], *uvs2[k], *normal])
                parts.append(np.array(q2, float).reshape(-1, 8))
            except (KeyError, FileNotFoundError):
                pass
        return np.concatenate(parts)


def opaque(name):
    return any(s in name for s in OPAQUE) and not name.endswith(('_slab', '_stairs', '_fence', '_wall', '_pane', '_trapdoor', '_door'))


def scene_mesh(a, sp, b, origin=(0, 0, 0), box=None, with_entities=True):
    keep = {p: v for p, v in b.blocks.items() if box is None or all(box[0][i] <= p[i] <= box[1][i] for i in range(3))}
    out = []; ox, oy, oz = origin
    care_keys = {'mcyukkuri:yukkuri_nest', 'mcyukkuri:predator_nest', 'mcyukkuri:food_bowl', 'mcyukkuri:waterer', 'mcyukkuri:yukkuri_toilet'}
    for pos, (name, props, nbt) in keep.items():
        if name in ('minecraft:jigsaw', 'minecraft:air', 'minecraft:light', 'minecraft:barrier'): continue
        if opaque(name) and all(opaque(keep.get(tuple(pos[i] + d[i] for i in range(3)), ('air', {}, None))[0]) for d, _ in FACES.values()):
            continue
        v = sp.care_block(name, props, nbt) if name in care_keys else a.block(name, tuple(sorted(props.items()))).copy()
        if len(v):
            v[:, :3] += np.array(pos) - np.array([ox, oy, oz])
            out.append(v)
    if with_entities:
        for ent in b.entities:
            ex, ey, ez = ent['pos'][1][1]
            if box is not None and not (box[0][0] <= ex <= box[1][0] + 1 and box[0][1] <= ey <= box[1][1] + 1.5 and box[0][2] <= ez <= box[1][2] + 1):
                continue
            nbt = ent['nbt'][1]; kind = nbt['id'][1].split(':', 1)[1]
            if kind in SPECIES_ZH:
                age = nbt.get('YukkuriFamily', (None, {}))[1].get('Age', (None, None))[1]
                # Face the street window in front rooms, south/center in the yard.
                yaw = 180.0 if ez < b.size[2] - 20 else 20.0
                v = sp.stock_at(kind, ex - ox, ey - oy, ez - oz, age=age, yaw=yaw)
                if len(v): out.append(v)
            elif kind == 'item':
                item_id = nbt['Item'][1]['id'][1].split(':', 1)[1]
                if (ASSETS / f'mcyukkuri/yukkuri_props/{item_id}.json').exists():
                    v = sp.prop_mesh(item_id, scale=.75); v[:, :3] += [ex - ox, ey - oy, ez - oz]; out.append(v)
            elif kind == 'marker' and 'mcy_shop_frame' in nbt['Tags'][1][1]:
                v = sp.frame_at(b, ex, ey, ez, nbt['data'][1]['Item'][1]['id'][1])
                if len(v): v[:, :3] -= [ox, oy, oz]; out.append(v)
    return np.concatenate(out) if out else np.empty((0, 8))


def top_plan(a, sp, out, tag, b, x0=0, x1=25, z0=2, z1=31, cut=4, px=26, goods_out=None):
    w, h = (x1 - x0) * px, (z1 - z0) * px
    cx, cz = (x0 + x1) / 2, (z0 + z1) / 2
    verts = scene_mesh(a, sp, b, origin=(cx, 0, cz), box=((x0, 0, z0), (x1 - 1, cut, z1 - 1)), with_entities=False)
    im = render(a, verts, out, tag, w, h, math.pi, math.pi / 2, h / (2 * px), 0).convert('RGBA')
    d = ImageDraw.Draw(im, 'RGBA')
    at = lambda x, z: ((x - x0) * px, (z - z0) * px)

    def pill(x, z, text, fill='#273e37dd', fg='#ffffff', sz=11):
        u, v = at(x, z); f = font(sz); bx = d.textbbox((u, v), text, font=f, anchor='mm')
        d.rounded_rectangle((bx[0] - 4, bx[1] - 2, bx[2] + 4, bx[3] + 2), 5, fill=fill, outline='#1d1d1d', width=1)
        d.text((u, v), text, font=f, fill=fg, anchor='mm')

    for (bx, by, bz), (name, props, _) in b.blocks.items():
        if not (x0 <= bx < x1 and z0 <= bz < z1 and by <= cut): continue
        if name.endswith('_door') and props.get('half') == 'lower':
            (u0, v0), (u1, v1) = at(bx + .08, bz + .08), at(bx + .92, bz + .92)
            d.rectangle((u0, v0, u1, v1), outline='#2ee6ff' if 'iron' in name else '#ffffff', width=3)
        elif name == 'mcyukkuri:waterer':
            u, v = at(bx + .82, bz + .18); d.ellipse((u - 5, v - 5, u + 5, v + 5), fill='#2da8ff', outline='#0d3a66', width=1)
        elif name == 'mcyukkuri:food_bowl':
            u, v = at(bx + .82, bz + .18); d.ellipse((u - 5, v - 5, u + 5, v + 5), fill='#f2a93b', outline='#5c3800', width=1)
        elif name == 'mcyukkuri:yukkuri_toilet':
            u, v = at(bx + .82, bz + .18); d.ellipse((u - 5, v - 5, u + 5, v + 5), fill='#b89c67', outline='#473a20', width=1)

    frames = {}
    for ent in b.entities:
        ex, ey, ez = ent['pos'][1][1]
        if not (x0 <= ex <= x1 and z0 <= ez <= z1): continue
        nbt = ent['nbt'][1]; kind = nbt['id'][1].split(':', 1)[1]
        if kind in SPECIES_ZH:
            age = nbt.get('YukkuriFamily', (None, {}))[1].get('Age', (None, None))[1]
            litter = nbt.get('NeoForgeData', (None, {}))[1].get('HumanCommerce', (None, {}))[1].get('Litter', (None, None))[1]
            stage = '成' if age is None else ('赤' if age < 72_000 else '子' if age < 216_000 else '亚')
            r = 9 if stage == '赤' else (11 if stage == '子' else 13)
            u, v = at(ex, ez)
            fill = '#d9487d' if litter is None else '#e85d2a'
            d.ellipse((u - r, v - r, u + r, v + r), fill=fill, outline='#ffffff' if litter else '#1d1d1d', width=2)
            d.text((u, v), SPECIES_ZH[kind], font=font(max(10, r)), fill='#ffffff', anchor='mm')
        elif kind in ('caretaker_brother', 'caretaker_sister', 'oniisan'):
            u, v = at(ex, ez); d.ellipse((u - 11, v - 11, u + 11, v + 11), fill='#2f353b', outline='#ffffff', width=2)
            d.text((u, v), '员', font=font(12), fill='#ffffff', anchor='mm')
        elif kind == 'item':
            u, v = at(ex, ez); d.rectangle((u - 4, v - 4, u + 4, v + 4), fill='#fff59d', outline='#4a4000', width=1)
        elif kind == 'marker':
            tags = nbt['Tags'][1][1]
            if 'mcy_shop_frame' in tags:
                item_key = nbt['data'][1]['Item'][1]['id'][1].split(':', 1)[1]
                if goods_out is not None:
                    goods_out.append(item_key)
                    idx = len(goods_out)
                else:
                    idx = sum(len(v) for v in frames.values()) + 1
                frames.setdefault((round(ex, 1), round(ez, 1)), []).append(str(idx))
            elif 'mcy_shop_family' in tags:
                u, v = at(ex, ez)
                d.rounded_rectangle((u - 15, v - 15, u + 15, v + 15), 5, outline='#ffb300', width=3)
                pill(ex, ez - .82, '家庭窝', fill='#b85c00ee', sz=11)

    for (fx, fz), nums in frames.items():
        pill(fx, fz, '/'.join(nums), fill='#e08e26ee', sz=10)
    return im.convert('RGB')


def side_by_side(left, right, title, sub_left, sub_right):
    pad = 24; top_h = 98
    w = left.width + right.width + pad * 3; h = max(left.height, right.height) + top_h + pad
    canvas = Image.new('RGB', (w, h), '#edf1eb'); d = ImageDraw.Draw(canvas)
    d.text((pad, 14), title, font=font(28), fill='#273e37')
    for x, sub, limit, color in ((pad, sub_left, left.width, '#6b4c3b'), (pad * 2 + left.width, sub_right, right.width, '#1f5c48')):
        f = next((font(sz) for sz in (17, 15, 14, 13, 12) if d.textlength(sub, font=font(sz)) <= limit), font(11))
        d.text((x, 58), sub, font=f, fill=color)
    canvas.paste(left, (pad, top_h))
    canvas.paste(right, (pad * 2 + left.width, top_h))
    return canvas


def default_jar():
    common = subprocess.check_output(['git', 'rev-parse', '--git-common-dir'], cwd=ROOT, text=True).strip()
    shared = Path(common).resolve().parent
    for root in (ROOT, shared):
        cand = root / '.local/gradle-home/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar'
        if cand.is_file(): return cand
    return None


def default_out():
    if 'MCYUKKURI_PREVIEWS' in os.environ:
        return Path(os.environ['MCYUKKURI_PREVIEWS']) / 'shop-rooms'
    common = subprocess.check_output(['git', 'rev-parse', '--git-common-dir'], cwd=ROOT, text=True).strip()
    return Path(common).resolve().parent / '.local/previews/shop-rooms'


def default_before():
    out = subprocess.check_output(['git', 'log', '-G', 'mcy_shop_family', '--format=%H', '--', 'art/structures/human-world/build.py'],
                                  cwd=ROOT, text=True).strip().splitlines()
    return f'{out[-1]}~1' if out else 'HEAD~1'


def load_old_build(ref):
    raw = subprocess.check_output(['git', 'show', f'{ref}:art/structures/human-world/build.py'], cwd=ROOT)
    mod = types.ModuleType('build_before')
    mod.__file__ = str(HERE / 'build.py')
    exec(compile(raw, f'{ref}:art/structures/human-world/build.py', 'exec'), mod.__dict__)
    return mod


def legend_card(width):
    items = [
        ('#2ee6ff', '', '铁门（单房单扇门 x3/7/17/21，后院/后屋双开门）'),
        ('#ffb300', '家', 'mcy_shop_family（后院不贴墙圆草窝 10,3,19）'),
        ('#2da8ff', '', '饮水器 waterer（满水 8 份，距窝站立格 ≤3 格）'),
        ('#f2a93b', '', '食盆 food_bowl（普通油粮 4 颗，距窝站立格 ≤3 格）'),
        ('#b89c67', '', '油库里厕所 yukkuri_toilet（距窝站立格 ≤3 格）'),
        ('#d9487d', '爱', '单房在售个体（小圆赤油 / 中圆子油 / 大圆亚成）'),
        ('#e85d2a', '灵', '后院同窝三姊妹（HumanCommerce.Litter = 1）'),
        ('#e08e26', '1', 'mcy_shop_frame 商品展示框（共 32 件 = ShopCatalog.GOODS）'),
    ]
    rows = (len(items) + 1) // 2
    im = Image.new('RGB', (width, 32 + rows * 34), '#f7f6f0'); d = ImageDraw.Draw(im)
    for i, (color, mark, text) in enumerate(items):
        x = 24 + (i % 2) * (width // 2); y = 16 + (i // 2) * 34
        d.ellipse((x, y, x + 22, y + 22), fill=color, outline='#1d1d1d', width=2)
        if mark: d.text((x + 11, y + 11), mark, font=font(11), fill='#ffffff', anchor='mm')
        d.text((x + 32, y + 11), text, font=font(16), fill='#273e37', anchor='lm')
    return im


PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>宠物店单房化 · 家庭院 · 饮水器与 32 展示框预览</title>
<style>body{font-family:"Microsoft YaHei",sans-serif;background:#eff2eb;color:#273e37;margin:0 auto;max-width:1580px;padding:24px}
h1{margin:0 0 8px}h2{margin:28px 0 8px}.note{background:#fffdf7;border-left:6px solid #1f5c48;padding:12px 16px;line-height:1.65}
img{max-width:100%;border-radius:8px;display:block;margin:10px 0}ul.g{columns:4;line-height:1.7}nav a{margin-right:14px;font-weight:bold;color:#1f5c48}</style>
<h1>宠物店房间与展品修订 · 五种村庄新旧对比</h1>
<div class="note"><b>本次改动要点：</b>① 临街展舱与捕食种房各由整面玻璃（x5、x19）一分为二成四间单房 <code>w1</code>/<code>w2</code>/<code>p1</code>/<code>p2</code>，每房单扇铁门夹去皮白桦立柱，房内仅养一只并配齐窝、食盆、满水饮水器、厕所（均在窝站立格 3 格内）与一件玩具；② 后院改为血缘家庭栏，檐廊下设一个不贴墙圆草窝（<code>10,3,19</code>，<code>mcy_shop_family</code>）配食盆、饮水器、厕所，放养同窝三只赤油灵梦（<code>Litter=1</code>，含勇敢严厉 / 胆小 / 温柔分化）；③ 用品展示框扩至 32 个（补齐 <code>waterer</code>、<code>straw_nest</code>、<code>velvet_nest</code>），与 <code>ShopCatalog.GOODS</code> 完全一致。</div>
<nav><a href="#sheet">总览对比表</a><a href="#closeup">展房与商品墙特写</a>{{NAV}}<a href="#goods">32 件展示框核对表</a></nav>
<h2 id="sheet">五种村庄 · 俯视与街面总览对比表</h2>
<img src="sheet.png"><img src="legend.png">
<h2 id="closeup">展房近景、商品墙与全店俯视对比（平原）</h2>
<img src="windows-closeup.png"><img src="goods-interior-compare.png"><img src="full-shop-top-compare.png">
{{STYLES}}
<h2 id="goods">32 个展示框商品核对（编号对应新版俯视图）</h2>
<ul class="g">{{GOODS}}</ul>
</html>
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--minecraft-resources', type=Path, default=default_jar())
    ap.add_argument('--styles', nargs='*', default=list(STYLES))
    ap.add_argument('--before', default=None, help='Git ref for the pre-change build.py (defaults to parent of mcy_shop_family commit)')
    ap.add_argument('--out', type=Path, default=default_out())
    args = ap.parse_args()
    if args.minecraft_resources is None or not args.minecraft_resources.is_file():
        raise SystemExit('Minecraft client jar not found; pass --minecraft-resources <path>')
    before_ref = args.before or default_before()
    old_build = load_old_build(before_ref)
    out = args.out; out.mkdir(parents=True, exist_ok=True)

    a = Assets(args.minecraft_resources)
    sp = SceneProps(a)
    zh_lang = json.loads((ASSETS / 'mcyukkuri/lang/zh_cn.json').read_text(encoding='utf-8'))
    zh_item = lambda k: zh_lang.get(f'item.mcyukkuri.{k}') or zh_lang.get(f'block.mcyukkuri.{k}') or {'cookie': '曲奇'}.get(k, k)

    top_pairs = []; street_pairs = []; sections = []; nav = []; new_goods = []
    for i, style in enumerate(args.styles):
        pal = PALETTES[style]; zh = STYLE_ZH[style]
        old_shop = old_build.shop(pal); new_shop = build.shop(pal)
        old_lot = old_build.village_shop(old_shop); new_lot = build.village_shop(new_shop)

        old_top = top_plan(a, sp, out, f'{style}-top-old', old_shop)
        new_top = top_plan(a, sp, out, f'{style}-top-new', new_shop, goods_out=new_goods if i == 0 else None)
        for p in (out / f'{style}-top-old.png', out / f'{style}-top-new.png'): p.unlink(missing_ok=True)
        top_cmp = side_by_side(
            old_top, new_top,
            f'{zh}村庄宠物店 · 销售半边与后院俯视剖面（y≤4）',
            '旧版：2 间混养展舱（3 窝 / 2 窝，无饮水器）+ 后院 4 窝非亲缘 + 29 展示框',
            '新版：4 间单宠展房 w1/w2/p1/p2（各 1 窝+盆+饮水器+厕）+ 后院家庭圆窝（同窝 3 赤灵）+ 32 展示框',
        )
        top_cmp.save(out / f'{style}-top-compare.png')

        # Street view focused on the sales building & village street frontage (lot z=0..40, front windows at z=11).
        old_sv = render(a, scene_mesh(a, sp, old_lot, origin=(13.5, 0, 14.0)), out, f'{style}-street-old', 920, 620, -.26, .34, 13.8, 4.8).convert('RGB')
        new_sv = render(a, scene_mesh(a, sp, new_lot, origin=(13.5, 0, 14.0)), out, f'{style}-street-new', 920, 620, -.26, .34, 13.8, 4.8).convert('RGB')
        for p in (out / f'{style}-street-old.png', out / f'{style}-street-new.png'): p.unlink(missing_ok=True)
        street_cmp = side_by_side(
            old_sv, new_sv,
            f'{zh}村庄宠物店 · 临街橱窗与立面对比（village/{style}/pet_shop）',
            '旧版街面：左右各一通舱，舱内多窝并排、无饮水器',
            '新版街面：x5 / x19 落地玻璃隔出四间单房，去皮原木门框单扇铁门，房内单宠与饮水器平视可见',
        )
        street_cmp.save(out / f'{style}-street-compare.png')

        top_pairs.append((zh, old_top, new_top))
        street_pairs.append((zh, old_sv, new_sv))
        nav.append(f'<a href="#{style}">{zh}</a>')
        sections.append(f'<h2 id="{style}">{zh}村庄（{style}）</h2><img src="{style}-street-compare.png"><img src="{style}-top-compare.png">')
        print('rendered style:', style, flush=True)

    # Plains close-ups: goods wall interior cutaway (old vs new), street window rooms + family yard close-up, and full 41x33 plan.
    old_plains = old_build.shop(PALETTES['plains']); new_plains = build.shop(PALETTES['plains'])
    old_goods_v = render(a, scene_mesh(a, sp, old_plains, origin=(12.5, 0, 11.5), box=((1, 0, 3), (23, 6, 17))),
                         out, '_goods-old', 920, 620, -.18, .62, 10.2, 3.8).convert('RGB')
    new_goods_v = render(a, scene_mesh(a, sp, new_plains, origin=(12.5, 0, 11.5), box=((1, 0, 3), (23, 6, 17))),
                         out, '_goods-new', 920, 620, -.18, .62, 10.2, 3.8).convert('RGB')
    for p in (out / '_goods-old.png', out / '_goods-new.png'): p.unlink(missing_ok=True)
    side_by_side(
        old_goods_v, new_goods_v,
        '前厅、四间单宠展房与 32 件商品展示墙（去顶剖视 y≤6 · 平原）',
        '旧版：柜台后墙 17 框（共 29 框，缺饮水器 / 草窝 / 软窝），展舱双开铁门',
        '新版：柜台后墙 2×10 满排 20 框 + 西墙 3 + 东墙 9 = 32 框，四间单房单扇铁门与去皮白桦立柱',
    ).save(out / 'goods-interior-compare.png')

    win_close = render(a, scene_mesh(a, sp, new_plains, origin=(12.5, 0, 6.2), box=((1, 0, 3), (23, 6, 10))),
                       out, '_win-close', 920, 580, .0, .44, 9.2, 4.0).convert('RGB')
    yard_close = render(a, scene_mesh(a, sp, new_plains, origin=(9.5, 0, 21.5), box=((1, 0, 17), (17, 6, 30))),
                        out, '_yard-close', 920, 580, 2.75, .52, 8.2, 3.5).convert('RGB')
    for p in (out / '_win-close.png', out / '_yard-close.png'): p.unlink(missing_ok=True)
    side_by_side(
        win_close, yard_close,
        '新版展房内部与后院家庭栏近景（真实道具网格与个体体型比例）',
        '临街四间单房：w1 金章赤爱 / w2 子橙 / p1 银章赤蕾 / p2 亚成芙兰（各配 1 窝+食盆+满水饮水器+厕所+玩具）',
        '后院血缘家庭栏：檐下不贴墙圆草窝 (10,3,19) + 食盆 + 饮水器 + 厕所 + 同窝三只赤油灵梦（Litter=1）',
    ).save(out / 'windows-closeup.png')

    old_full = top_plan(a, sp, out, '_full-old', old_plains, x0=0, x1=41, z0=1, z1=32, cut=4, px=18)
    new_full = top_plan(a, sp, out, '_full-new', new_plains, x0=0, x1=41, z0=1, z1=32, cut=4, px=18)
    for p in (out / '_full-old.png', out / '_full-new.png'): p.unlink(missing_ok=True)
    side_by_side(
        old_full, new_full,
        '宠物店全店平面对比（41×33 格 · 含东侧考场与后屋员工通道）',
        '旧版全店平面（考场 x24–38 不变）',
        '新版全店平面（考场与后屋双开门保留，仅修订销售展房、商品墙与后院家庭栏）',
    ).save(out / 'full-shop-top-compare.png')

    leg = legend_card(1480); leg.save(out / 'legend.png')

    # Master contact sheet: all 5 styles (Old Top | New Top | Old Street | New Street) in one crisp comparison board.
    row_h = 370; col_ws = (310, 310, 440, 440); gap = 16; pad = 24; head_h = 112
    sheet_w = pad * 2 + sum(col_ws) + gap * 3 + 74
    sheet_h = head_h + len(top_pairs) * (row_h + gap) + pad
    sheet = Image.new('RGB', (sheet_w, sheet_h), '#eff2eb'); sd = ImageDraw.Draw(sheet)
    sd.text((pad, 16), '宠物店五种村庄 · 俯视平面与临街外观新旧对比总表', font=font(30), fill='#273e37')
    headers = ('旧版俯视 (y≤4)', '新版俯视 (4单房+家庭院+32框)', '旧版街面', '新版街面 (落地隔断+饮水器)')
    hx = pad + 74
    for cw, htxt in zip(col_ws, headers):
        sd.text((hx, 68), htxt, font=font(18), fill='#273e37'); hx += cw + gap
    for r, ((zh, ot, nt), (_, os_im, ns_im)) in enumerate(zip(top_pairs, street_pairs)):
        y = head_h + r * (row_h + gap)
        sd.rounded_rectangle((pad, y, sheet_w - pad, y + row_h), 10, fill='#fffdf7')
        sd.text((pad + 36, y + row_h // 2), zh, font=font(22), fill='#273e37', anchor='mm')
        hx = pad + 74
        for cw, src in zip(col_ws, (ot, nt, os_im, ns_im)):
            thumb = ImageOps.contain(src, (cw - 8, row_h - 16), Image.Resampling.LANCZOS)
            sheet.paste(thumb, (hx + (cw - thumb.width) // 2, y + (row_h - thumb.height) // 2))
            hx += cw + gap
    sheet.save(out / 'sheet.png')

    shutil.rmtree(out / '_work', ignore_errors=True)
    goods_html = ''.join(f'<li><b>{idx}</b> {html.escape(zh_item(k))} <code>{html.escape(k)}</code></li>' for idx, k in enumerate(new_goods, 1))
    page = PAGE.replace('{{NAV}}', ''.join(nav)).replace('{{STYLES}}', '\n'.join(sections)).replace('{{GOODS}}', goods_html)
    (out / 'index.html').write_bytes(page.encode('utf-8'))
    print('Rendered pet-shop comparison sheet and 5-style views:', out)
    print('Special block renderers omitted:', ', '.join(sorted(a.missing)) or 'none')


if __name__ == '__main__':
    main()
