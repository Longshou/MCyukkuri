"""Den-mouth screen (洞口结界, block den_barrier): one native block model and one hand-painted texture.

A wild Reimu closes her den mouth with what she can carry in the mouth and push into place (settlement/DenSeal): twigs
wedged into the soil, some upright, some leaned along the opening, some tipped inward so that twigs from opposite sides
rest against each other; a loose wad of fallen leaves pushed into the middle, a leaf or two pushed into a gap, a few
leaves and two short twigs dropped at the foot. Tops stay uneven and the upper corners open.
Nothing is tied, knotted or woven. The look is the twig and leaf material of the wild shelters (nest_boundary twigs,
wild_kit leaf litter and leaves), visibly gappy, filling the block's 14 x 16 x 14 collision (settlement/DenBarrierBlock).

The block has no facing (the mouth can be any side of the den and a wide mouth takes several cells), so every side of
the cell carries its own screen layer; whichever side faces out reads as a closed mouth. Element rotations are the vanilla
steps 0 / +-22.5 / +-45; everything stays inside the cell (y 0..16, x / z 0..16) except twig feet sunk a fraction into the ground.

Texture: 64 x 64 at 1 texel per unit, painted from the wild_kit ramps and painters (bark, cut ends, leaf litter, leaf
sprites); nothing is copied from Minecraft. Writes the runtime model, texture and blockstate and the snapshot
art/source/den-barrier/ with a manifest of fingerprints. --check is read-only.
"""
import argparse, hashlib, io, json, math, sys
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
from asset_export_checks import check_output, preserve_equivalent_png
import wild_kit as K

ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
SNAPSHOT = ROOT / 'art/source/den-barrier'
GENERATOR = 'standards/yukkuri-library/ecology/wild-shelters/den_barrier.py'
NAME = 'den_barrier'
TEX = f'mcyukkuri:block/{NAME}'
U = 4          # texture pixels per model uv unit (64 px over uv 0..16)


# ---- texture -----------------------------------------------------------------------------------------------------------
# y 0..15   bark with grain down the tile (upright twigs) | bark with grain across (lying twigs) | cut end | older bark, down
# y 16..31  leaf sprites: dry, old nibbled, green, slim dry
# y 32..47  three pushed-in leaf mats (litter with see-through gaps and a ragged top)
BARK_V, BARK_H, END, BARK_OLD = (0, 0), (16, 0), (32, 0), (48, 0)
LEAVES = {'dry': (0, 16), 'old': (16, 16), 'green': (32, 16), 'slim': (48, 16)}
MATS = [(0, 32), (16, 32), (32, 32)]


def mat_tile(key):
    """Leaf litter pushed in as a loose mat: the earth-coloured ground of the litter becomes see-through gaps in
    a few patches, and the top edge is ragged."""
    img = K.litter(key, mix=((K.DRYLEAF, 6), (K.GREEN, 2), (K.OLD, 1)), straws=3, density=34).convert('RGBA')
    px = img.load()
    soil = {K.hexrgb(c) + (255,) for c in K.SOIL}
    r = K.rng_of(key + '/gaps')
    holes = [(r.randrange(16), r.randrange(2, 16), r.uniform(1.6, 2.8)) for _ in range(5)]
    tops = [r.choice((0, 0, 1, 1, 2, 3)) for _ in range(16)]
    for y in range(16):
        for x in range(16):
            near = any((x - hx) ** 2 + (y - hy) ** 2 < hr * hr for hx, hy, hr in holes)
            if y < tops[x] or (near and px[x, y] in soil):
                px[x, y] = (0, 0, 0, 0)
    return img


SMALL_LEAF = [
    "...o...",
    "..omo..",
    ".olrmo.",
    "olvrmdo",
    "olmrvdo",
    "olvrmdo",
    ".olrdo.",
    "..oro..",
    "...r...",
]


def small_leaf(ramp, nibbled=False):
    """A fallen leaf small enough to push into a gap, drawn like wild_kit.leaf_sprite (lit left, darkest step as outline)."""
    top = len(ramp) - 1
    legend = {'o': ramp[0], 'd': ramp[1], 'm': ramp[2], 'l': ramp[min(top, 3)], 'v': ramp[min(top, 4)], 'r': ramp[min(top, 4)]}
    c = K.Canvas()
    c.rows(SMALL_LEAF, legend, dx=3, dy=2, wrap=False)
    if nibbled:
        for x, y in ((9, 6), (9, 7), (3, 7)):
            c.put(x, y, None, wrap=False)
        c.put(8, 7, ramp[0], wrap=False)
    return c.image()


def texture():
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    img.paste(K.bark('den_bark').rotate(90), BARK_V)
    img.paste(K.bark('den_bark_h'), BARK_H)
    img.paste(K.wood_end(), END)
    old = K.bark('den_bark_old').rotate(90).convert('RGBA')
    rgb = old.convert('RGB').point(lambda v: int(v * .86))   # older, greyer twigs one step darker
    img.paste(Image.merge('RGBA', (*rgb.split(), old.getchannel('A'))), BARK_OLD)
    sprites = {'dry': small_leaf(K.DRYLEAF), 'old': small_leaf(K.OLD, nibbled=True),
               'green': small_leaf(K.GREEN), 'slim': K.leaf_sprite(K.DRYLEAF, broad=False)}
    for key, at in LEAVES.items():
        img.paste(sprites[key], at)
    for i, at in enumerate(MATS):
        img.paste(mat_tile(f'den_mat_{i}'), at)
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    return buf.getvalue(), {k: sprites[k].getbbox() for k in sprites}


# ---- geometry -------------------------------------------------------------------------------------------------------
# Each side is authored in a face frame: s runs along the side (0..16), d is the depth in from the collision face
# (the face itself is 1 unit in from the cell edge), y is up.
# twigs: (s, d, width, length, lean, old bark[, fork]). lean 's+' / 's-' tips the top 22.5 degrees along the side,
# 's++' / 's--' 45 degrees (a short twig fallen against the others), 'in' tips it 22.5 degrees toward the middle of the
# cell so twigs from opposite sides rest against each other, '' stands upright. fork: (fraction up the twig, length, lean)
# of a side shoot growing from it.
# leaves: (sprite, s, y, d, tilt) pushed into a gap between the twigs, tilt in 22.5 degree steps about the side normal.
SCREEN_A = dict(
    twigs=[(2.2, .3, 1.5, 15.5, 's+', False), (5.6, .8, 1.0, 12.0, 'in', True), (9.6, .2, 1.75, 13.0, 's-', False),
           (13.8, .5, 1.25, 15.0, 's-', False, (.55, 4.0, 's+')), (11.0, 1.4, 1.0, 9.0, 's--', True)],
    leaves=[('green', 8.0, 6.5, 1.2, 0), ('dry', 6.2, 2.0, 1.6, 1)])
SCREEN_B = dict(
    twigs=[(1.6, .4, 1.25, 13.0, 's+', False, (.6, 3.5, 's++')), (4.8, .3, 1.75, 15.5, 'in', True),
           (7.4, .5, 1.0, 11.0, 's++', False), (14.4, .2, 1.5, 15.5, 's-', False), (10.4, 1.2, 1.25, 12.5, 's-', True)],
    leaves=[('dry', 8.0, 5.5, 1.0, -1), ('green', 11.0, 2.5, 1.8, 0)])
# side name: (screen, mirrored); the opposite sides carry different screens so the cell is not a repeated box
SIDES = {'north': (SCREEN_A, False), 'south': (SCREEN_B, False), 'west': (SCREEN_B, True), 'east': (SCREEN_A, True)}
# fallen leaves pushed in as a loose wad in the middle, two crossed planes: (mat, width, height, yaw)
STUFFING = [(0, 12.0, 11.5, 45.0), (1, 10.0, 9.5, -45.0)]
# leaves dropped at the foot: (sprite, x, z, yaw), and two short twigs lying on the ground: (x0, x1, z, width, yaw, along)
LITTER = [('dry', 5.8, 10.2, 22.5), ('old', 10.2, 5.8, -22.5), ('green', 10.2, 10.2, 45.0), ('dry', 4.0, 5.0, 0.0),
          ('slim', 12.2, 10.8, 0.0)]
GROUND_TWIGS = [(3.5, 10.5, 13.2, 1.25, 22.5, 'x'), (2.8, 9.0, 3.2, 1.0, -22.5, 'z')]
FACE_DIMS = {'up': ('x', 'z'), 'down': ('x', 'z'), 'north': ('x', 'y'), 'south': ('x', 'y'), 'west': ('z', 'y'), 'east': ('z', 'y')}


def r(v):
    return round(v * 1000) / 1000


def frame(side, mirrored):
    """Map (s, depth, y) of a side to world (x, y, z), plus the world axis along s, the rotation axis (the side's
    normal) and the angle sign that tips a twig toward +s."""
    def world(s, depth, y):
        s = 16 - s if mirrored else s
        if side == 'north': return (s, y, 1 + depth)
        if side == 'south': return (16 - s, y, 15 - depth)
        if side == 'west': return (1 + depth, y, s)
        return (15 - depth, y, 16 - s)
    along = 'x' if side in ('north', 'south') else 'z'
    axis = 'z' if along == 'x' else 'x'
    # +s in world: north x+, south x-, west z+, east z-; mirroring flips it. About z a positive angle tips +y toward -x,
    # about x it tips +y toward +z (right-handed, as FaceBakery).
    plus = {'north': 1, 'south': -1, 'west': 1, 'east': -1}[side] * (-1 if mirrored else 1)
    sign = (-plus) if axis == 'z' else plus
    return world, along, axis, sign


def uv_box(size, long_axis, salt, region_v, region_h):
    """Face uvs of a stick box: bark with the grain along the long axis, cut ends on the faces across it."""
    faces = {}
    for face, (du, dv) in FACE_DIMS.items():
        su, sv = size[du], size[dv]
        if long_axis not in (du, dv):
            cx, cy = END[0] + 8, END[1] + 8
            faces[face] = [r((cx - su / 2) / U), r((cy - sv / 2) / U), r((cx + su / 2) / U), r((cy + sv / 2) / U)]
            continue
        ox, oy = region_v if dv == long_axis else region_h
        span = 16 - (su if dv == long_axis else sv)
        off = int(K.rng_of(f'{salt}/{face}').random() * max(span, 0))
        if dv == long_axis:
            faces[face] = [r((ox + off) / U), r(oy / U), r((ox + off + su) / U), r((oy + sv) / U)]
        else:
            faces[face] = [r(ox / U), r((oy + off) / U), r((ox + su) / U), r((oy + off + sv) / U)]
    return faces


def stick(lo, hi, long_axis, rotation, salt, old=False):
    size = {'x': hi[0] - lo[0], 'y': hi[1] - lo[1], 'z': hi[2] - lo[2]}
    assert max(size.values()) <= 16, (salt, size)
    faces = uv_box(size, long_axis, salt, BARK_OLD if old else BARK_V, BARK_H)
    e = {'from': [r(v) for v in lo], 'to': [r(v) for v in hi],
         'faces': {k: {'uv': v, 'texture': '#screen'} for k, v in faces.items()}}
    if rotation:
        e['rotation'] = rotation
    return e


def side_elements(side, screen, mirrored):
    world, along, axis, sign = frame(side, mirrored)
    out = []
    pair = ('north', 'south') if along == 'x' else ('west', 'east')
    # tipping toward the middle turns about the axis along the side; +depth is north +z, south -z, west +x, east -x
    inward = {'north': ('x', 1), 'south': ('x', -1), 'west': ('z', -1), 'east': ('z', 1)}[side]
    def rotation(lean, origin):
        if lean == 'in':
            return {'angle': 22.5 * inward[1], 'axis': inward[0], 'origin': origin}
        if not lean:
            return None
        steps = (1 if lean[1] == '+' else -1) * (len(lean) - 1)
        return {'angle': 22.5 * steps * sign, 'axis': axis, 'origin': origin}
    for n, (s, d, w, length, lean, old, *fork) in enumerate(screen['twigs']):
        c = world(s, d + w / 2, 0)
        origin = [r(c[0]), 0, r(c[2])]
        rot = rotation(lean, origin)
        out.append(stick([c[0] - w / 2, 0, c[2] - w / 2], [c[0] + w / 2, length, c[2] + w / 2], 'y', rot, f'{side}/t{n}', old))
        if fork:
            at, shoot, shoot_lean = fork[0]
            base = turn(rot, (c[0], at * length, c[2])) if rot else (c[0], at * length, c[2])
            sw = max(.75, w * .6)
            o = [r(v) for v in base]
            out.append(stick([base[0] - sw / 2, base[1], base[2] - sw / 2], [base[0] + sw / 2, base[1] + shoot, base[2] + sw / 2], 'y',
                             rotation(shoot_lean, o), f'{side}/f{n}', old))
    for n, (key, s, y, d, tilt) in enumerate(screen['leaves']):
        bx0, by0, bx1, by1 = BOXES[key]
        w, h = bx1 - bx0, by1 - by0
        a, b = world(s - w / 2, d, y), world(s + w / 2, d, y + h)
        lo, hi = [min(a[k], b[k]) for k in range(3)], [max(a[k], b[k]) for k in range(3)]
        # the sprite is mapped the same way up from both sides; the far side shows it mirrored, like a cut-out plant
        e = {'from': [r(v) for v in lo], 'to': [r(v) for v in hi], 'faces': {f: {'uv': sprite_uv(key), 'texture': '#screen'} for f in pair}}
        if tilt:
            e['rotation'] = {'angle': 22.5 * tilt * sign, 'axis': axis, 'origin': [r(v) for v in world(s, d, y + h / 2)]}
        out.append(e)
    return out


def sprite_uv(key):
    bx0, by0, bx1, by1 = BOXES[key]
    ox, oy = LEAVES[key]
    return [r((ox + bx0) / U), r((oy + by0) / U), r((ox + bx1) / U), r((oy + by1) / U)]


def middle_elements():
    out = []
    for i, w, h, yaw in STUFFING:
        mx, my = MATS[i]
        uv = [r(mx / U), r(my / U), r((mx + w) / U), r((my + h) / U)]
        out.append({'from': [r(8 - w / 2), 0, 8], 'to': [r(8 + w / 2), h, 8],
                    'rotation': {'angle': yaw, 'axis': 'y', 'origin': [8, 0, 8]},
                    'faces': {f: {'uv': uv, 'texture': '#screen'} for f in ('north', 'south')}})
    for n, (key, x, z, yaw) in enumerate(LITTER):
        bx0, by0, bx1, by1 = BOXES[key]
        w, h = bx1 - bx0, by1 - by0
        y = .05 + .02 * n
        out.append({'from': [r(x - w / 2), r(y), r(z - h / 2)], 'to': [r(x + w / 2), r(y), r(z + h / 2)],
                    'rotation': {'angle': yaw, 'axis': 'y', 'origin': [x, r(y), z]},
                    'faces': {f: {'uv': sprite_uv(key), 'texture': '#screen'} for f in ('up', 'down')}})
    for n, (a, b, c, w, yaw, along) in enumerate(GROUND_TWIGS):
        lo, hi = ([a, 0, c - w / 2], [b, w, c + w / 2]) if along == 'x' else ([c - w / 2, 0, a], [c + w / 2, w, b])
        mid = [r((lo[k] + hi[k]) / 2) for k in range(3)]
        out.append(stick(lo, hi, along, {'angle': yaw, 'axis': 'y', 'origin': mid}, f'ground/{n}', n == 1))
    return out


def turn(rot, p):
    """A point turned by an element rotation (FaceBakery convention, no rescale)."""
    a = math.radians(rot['angle']); c, s = math.cos(a), math.sin(a)
    ox, oy, oz = rot['origin']
    x, y, z = p[0] - ox, p[1] - oy, p[2] - oz
    if rot['axis'] == 'z': x, y = c * x - s * y, s * x + c * y
    elif rot['axis'] == 'x': y, z = c * y - s * z, s * y + c * z
    else: x, z = c * x + s * z, -s * x + c * z
    return (x + ox, y + oy, z + oz)


def corners(e):
    """World corners of an element after its rotation (FaceBakery convention, no rescale)."""
    x0, y0, z0 = e['from']; x1, y1, z1 = e['to']
    pts = [(x, y, z) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
    rot = e.get('rotation')
    return [turn(rot, p) for p in pts] if rot else pts


def model():
    elements = middle_elements()
    for side, (screen, mirrored) in SIDES.items():
        elements += side_elements(side, screen, mirrored)
    for e in elements:
        for x, y, z in corners(e):
            assert -.1 <= x <= 16.1 and -.5 <= y <= 16.0 + 1e-6 and -.1 <= z <= 16.1, (e, (x, y, z))
    return {'ambientocclusion': False, 'render_type': 'minecraft:cutout',
            'textures': {'particle': TEX, 'screen': TEX}, 'elements': elements}


PNG, BOXES = texture()


def outputs():
    def dumps(data):
        return (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    result = {}
    png = preserve_equivalent_png(ASSETS / f'textures/block/{NAME}.png', PNG)
    result[ASSETS / f'textures/block/{NAME}.png'] = png
    result[SNAPSHOT / f'{NAME}.png'] = png
    data = dumps(model())
    result[ASSETS / f'models/block/{NAME}.json'] = data
    result[SNAPSHOT / f'{NAME}.json'] = data
    result[ASSETS / f'blockstates/{NAME}.json'] = dumps({'variants': {'': {'model': f'mcyukkuri:block/{NAME}'}}})
    files = {p.relative_to(ROOT).as_posix(): hashlib.sha256(v).hexdigest() for p, v in result.items()}
    result[SNAPSHOT / 'manifest.json'] = (json.dumps({
        'id': NAME, 'status': 'pending_user_confirmation', 'generator': GENERATOR,
        'elements': len(model()['elements']), 'collision': [1, 0, 1, 15, 16, 15],
        'texture': '64x64, 1 texel per unit, wild_kit bark / wood end / leaf litter / leaf sprites',
        'files': files}, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    changed = []
    for path, content in outputs().items():
        if not check_output(path, content):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
    if args.check and changed:
        raise SystemExit('Den barrier exports differ: ' + ', '.join(changed))
    print('Den barrier: ' + ('match.' if args.check else f'{len(changed)} files written.') + f' {len(model()["elements"])} elements')


if __name__ == '__main__':
    main()
