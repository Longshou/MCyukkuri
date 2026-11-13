"""Real 3D models of the operation tools: the player's hand and, for the bound tools, the body contact.

Each tool is a list of axis-aligned cuboids in pixels (16 per block), the way vanilla Java block models are built, so one
source gives both outputs:

* the item model's 3D base for both hands (first and third person). The inventory, ground and item frame keep the
  16x16 sprite from icons.py, like the vanilla trident and spyglass (`neoforge:separate_transforms`);
* the yukkuri_props contact mesh of a bound tool (pin in the skin, hook through the head, rotor pressed on the body),
  in the frame the renderer already uses: +Y is the outward skin normal and the origin is the skin contact point.

Tool frame: +Y runs from the grip (low) to the working end (high), centred on x = z = 0. A 'tool' is held like a vanilla
tool: the whole model turns -45 degrees about Z onto the item diagonal (grip bottom left, working end top right) and uses
the vanilla handheld transforms. 'item' and 'block' tools stand upright with the generated-item or block transforms.

Textures are 32x32 per tool: four 16x16 material swatches (ramps shared with icons.py) sampled at one texel per model
pixel, so the 3D model has the same pixel density as the sprite. Light comes from Minecraft's face shading.
"""
import io
import math
import zlib

from PIL import Image

import icons as I

TEX = 32           # texture size; four 16x16 swatches
SWATCH = 16


def hexrgb(colour):
    return tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))


def _noise(seed, x, y):
    return zlib.crc32(f'{seed}:{x}:{y}'.encode()) / 0xffffffff


def swatch(kind, ramp):
    """16x16 material tile: mid tone with sparse light and dark pixels; wood has streaks along the length (v)."""
    tile = Image.new('RGBA', (SWATCH, SWATCH))
    n = len(ramp)
    for y in range(SWATCH):
        for x in range(SWATCH):
            r = _noise(kind, x, y)
            if kind == 'wood':
                streak = _noise(kind, x, 0)
                step = 3 if streak > .62 else 1 if streak < .2 else 2
                if r > .93:
                    step = max(1, step - 1)
            elif kind == 'hot':                       # pale yellow at the tip (top row) to dull red by the ferrule (row 7)
                step = max(1, round((n - 1) * (1 - min(1, y / 7))) - (r > .85))
            elif kind == 'dark':
                step = 0 if r < .7 else 1
            elif kind == 'metal':                     # smooth plate: 2x2 patches of two light steps, rare dark pits
                cell = _noise(kind, x // 2, y // 2)
                step = 1 if r < .05 else 3 if cell > .6 else 2
            else:
                step = 3 if r > .86 else 1 if r < .12 else 2
            tile.putpixel((x, y), hexrgb(ramp[min(step, n - 1)]) + (255,))
    return tile


MATERIALS = {
    'iron': ('metal', I.IRON), 'drum': ('metal', I.DRUM), 'wood': ('wood', I.WOOD), 'plank': ('wood', I.PLANK),
    'leather': ('cloth', I.LEATHER), 'pink': ('cloth', I.PINK), 'switch': ('cloth', I.PINK[:3]), 'strap': ('wood', I.STRAP),
    'brass': ('metal', I.BRASS), 'cloth': ('cloth', I.CLOTH[1:]), 'string': ('cloth', I.STRING), 'hot': ('hot', I.HOT),
    'dark': ('dark', I.DRUM),
}


def cube(a, b, material, hand_only=False, rotation=None):
    return {'from': list(a), 'to': list(b), 'material': material, 'hand_only': hand_only, 'rotation': rotation}


def bar_x(x0, x1, y, z, r, material, **kw):
    return cube((x0, y - r, z - r), (x1, y + r, z + r), material, **kw)


def rod_y(y0, y1, r, material, x=0.0, z=0.0, **kw):
    return cube((x - r, y0, z - r), (x + r, y1, z + r), material, **kw)


def pin_tool():
    return [rod_y(0, 3, 1.5, 'pink'), rod_y(-.5, 0, 1, 'pink'), rod_y(3, 3.5, 1, 'pink'),    # rounded pink head
            rod_y(3.5, 10, .5, 'iron'), rod_y(10, 11, .25, 'iron')]


def care_mallet():
    head = [cube((-4, 10.5, -2.5), (4, 14.5, 2.5), 'drum'), cube((-4, 10, -2), (4, 15, 2), 'drum')]   # octagonal drum
    faces = [cube((4, 10.75, -1.75), (4.75, 14.25, 1.75), 'iron'), cube((-4.75, 10.75, -1.75), (-4, 14.25, 1.75), 'iron')]
    return [rod_y(0, 10, .75, 'wood'), rod_y(.5, 3.5, 1, 'leather')] + head + faces


def _awl(spike):
    return [rod_y(0, 6, 1.25, 'wood'), rod_y(-.5, 0, .9, 'wood'), rod_y(6, 7, 1.5, 'iron'),
            rod_y(7, 13, .5, spike), rod_y(13, 14, .25, spike)]


def care_awl():
    return _awl('iron')


def care_awl_hot():
    return _awl('hot')


def hanging_hook():
    """Same eye (0, 9) and point (-2.6, 3.1) as OperationAttachments; the short rope above the eye is only in the hand."""
    t = .4
    return [cube((-1.3, 8.2, -t), (-.7, 10.8, t), 'iron'), cube((.7, 8.2, -t), (1.3, 10.8, t), 'iron'),
            cube((-1.3, 10.8, -t), (1.3, 11.4, t), 'iron'), cube((-1.3, 7.6, -t), (1.3, 8.2, t), 'iron'),
            cube((-.4, 1.2, -t), (.4, 7.6, t), 'iron'),                       # shank
            cube((-2.6, .4, -t), (.4, 1.2, t), 'iron'),                       # bend
            cube((-3.0, .4, -t), (-2.2, 3.1, t), 'iron'),                     # point rising to (-2.6, 3.1)
            cube((-2.2, 1.9, -t), (-1.6, 2.5, t), 'iron'),                    # barb
            rod_y(11.4, 15.5, .3, 'string', hand_only=True)]


def care_vibrator():
    egg = [rod_y(0, 5, 1.75, 'pink'), cube((-2.25, .75, -1.25), (2.25, 4.25, 1.25), 'pink'),
           cube((-1.25, .75, -2.25), (1.25, 4.25, 2.25), 'pink'),
           cube((-1.25, 5, -1.25), (1.25, 5.5, 1.25), 'pink'), cube((-1.25, -.5, -1.25), (1.25, 0, 1.25), 'pink')]
    cord = [rod_y(-3, -.5, .25, 'string'), rod_y(-7, -3, .25, 'string', hand_only=True)]
    box = [cube((-1.5, -10, -1), (1.5, -7, 1), 'switch', hand_only=True)]
    return egg + cord + box


def restraint_frame():
    straps = []
    for z in (-3.5, 3.5):
        straps += [cube((-5, 1, z - .75), (-4, 5, z + .75), 'strap'), cube((4, 1, z - .75), (5, 5, z + .75), 'strap'),
                   cube((-5, 5, z - .75), (5, 6, z + .75), 'strap'), cube((1, 4.75, z - 1), (2.5, 6.25, z + 1), 'brass')]
    return [cube((-6, 0, -7), (6, 1, 7), 'plank')] + straps


def wrap_sling():
    cords = [cube((3.4, 3, -.3), (4.0, 12.1, .3), 'string', rotation={'origin': [3.7, 3, 0], 'axis': 'z', 'angle': 22.5}),
             cube((-4.0, 3, -.3), (-3.4, 12.1, .3), 'string', rotation={'origin': [-3.7, 3, 0], 'axis': 'z', 'angle': -22.5})]
    ring = [cube((-1.25, 11.5, -.3), (1.25, 12, .3), 'iron'), cube((-1.25, 13.5, -.3), (1.25, 14, .3), 'iron'),
            cube((-1.25, 12, -.3), (-.75, 13.5, .3), 'iron'), cube((.75, 12, -.3), (1.25, 13.5, .3), 'iron')]
    cloth = [cube((-4, 0, -3), (4, 1.5, 3), 'cloth'), cube((-5, 1, -3), (-4, 3.5, 3), 'cloth'),
             cube((4, 1, -3), (5, 3.5, 3), 'cloth'), cube((-4, 1.5, -3), (4, 2.5, -2.5), 'cloth'),
             cube((-4, 1.5, 2.5), (4, 2.5, 3), 'cloth')]
    return cloth + cords + ring


def iron_enclosure():
    walls = [cube((-5, 0, -5), (5, 1, 5), 'iron'), cube((-5, 9, -5), (5, 10, 5), 'iron'),
             cube((-5, 1, 4), (5, 9, 5), 'iron'), cube((-5, 1, -5), (-4, 9, 5), 'iron'), cube((4, 1, -5), (5, 9, 5), 'iron')]
    bars = [rod_y(1, 9, .5, 'iron', x=x, z=-4.5) for x in (-2.5, -.75, 1, 2.75)]
    return walls + bars + [cube((-4, 1, -3.5), (4, 9, 4), 'dark')]


# id: (cuboids, display, materials in swatch order, contact transform or None)
# A contact transform maps the tool frame to the body contact frame: p' = R (p - origin), R given as three rows.
FLIP_Y = ((1, 0, 0), (0, -1, 0), (0, 0, -1))         # pin: point down into the skin, head outwards
CYCLE = ((0, 0, 1), (1, 0, 0), (0, 1, 0))            # rotor: egg lies along the skin (tool Y -> Z), tool X -> normal
HELD = {
    'pin_tool': (pin_tool, 'tool', ('pink', 'iron'), {'origin': (0, 9.5, 0), 'rows': FLIP_Y}),
    'care_mallet': (care_mallet, 'tool', ('wood', 'leather', 'drum', 'iron'), None),
    'care_awl': (care_awl, 'tool', ('wood', 'iron'), None),
    'care_awl_hot': (care_awl_hot, 'tool', ('wood', 'iron', 'hot'), None),
    'hanging_hook': (hanging_hook, 'item', ('iron', 'string'), {'origin': (0, 0, 0), 'rows': ((1, 0, 0), (0, 1, 0), (0, 0, 1))}),
    'care_vibrator': (care_vibrator, 'item', ('pink', 'string', 'switch'), {'origin': (-2.25, 2.5, 0), 'rows': CYCLE}),
    'restraint_frame': (restraint_frame, 'block', ('plank', 'strap', 'brass', 'cloth'), None),
    'wrap_sling': (wrap_sling, 'item', ('cloth', 'string', 'iron'), None),
    'iron_enclosure': (iron_enclosure, 'block', ('iron', 'dark'), None),
}

DISPLAY = {
    'tool': {'thirdperson_righthand': {'rotation': [0, -90, 55], 'translation': [0, 4, .5], 'scale': [.85] * 3},
             'thirdperson_lefthand': {'rotation': [0, 90, -55], 'translation': [0, 4, .5], 'scale': [.85] * 3},
             'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [.68] * 3},
             'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [.68] * 3}},
    'item': {'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [.55] * 3},
             'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [.55] * 3},
             'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [.68] * 3},
             'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [.68] * 3}},
    'block': {'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [.5] * 3},
              'thirdperson_lefthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [.5] * 3},
              'firstperson_righthand': {'rotation': [0, 45, 0], 'translation': [0, 0, 0], 'scale': [.55] * 3},
              'firstperson_lefthand': {'rotation': [0, 225, 0], 'translation': [0, 0, 0], 'scale': [.55] * 3}},
}

FACES = ('north', 'south', 'east', 'west', 'up', 'down')


def texture(ident):
    """32x32 PNG with the tool's material swatches at (0,0), (16,0), (0,16), (16,16)."""
    image = Image.new('RGBA', (TEX, TEX), (0, 0, 0, 0))
    for slot, material in enumerate(HELD[ident][2]):
        kind, ramp = MATERIALS[material]
        image.paste(swatch(kind, ramp), (slot % 2 * SWATCH, slot // 2 * SWATCH))
    stream = io.BytesIO()
    image.save(stream, format='PNG')
    return stream.getvalue()


def _face_size(face, size):
    dx, dy, dz = size
    return {'north': (dx, dy), 'south': (dx, dy), 'east': (dz, dy), 'west': (dz, dy), 'up': (dx, dz), 'down': (dx, dz)}[face]


def face_uv(ident, index, element, face):
    """UV rectangle in texels: one texel per model pixel inside the material swatch, offset per element."""
    slot = HELD[ident][2].index(element['material'])
    size = [element['to'][i] - element['from'][i] for i in range(3)]
    w, h = (max(.5, min(SWATCH, v)) for v in _face_size(face, size))
    ox = (index * 5 + FACES.index(face) * 3) % int(SWATCH - w + 1)
    oy = (index * 7 + FACES.index(face)) % int(SWATCH - h + 1)
    if element['material'] == 'hot':
        oy = 0
    u0, v0 = slot % 2 * SWATCH + ox, slot // 2 * SWATCH + oy
    return [u0, v0, u0 + w, v0 + h]


def _centre(cuboids):
    lo = [min(c['from'][i] for c in cuboids) for i in range(3)]
    hi = [max(c['to'][i] for c in cuboids) for i in range(3)]
    return [(lo[i] + hi[i]) / 2 for i in range(3)]


def hand_elements(ident):
    """Java block-model elements: the tool centred on (8, 8, 8); a 'tool' turns -45 degrees about Z onto the diagonal."""
    factory, display, _, _ = HELD[ident]
    cuboids = factory()
    centre = _centre(cuboids)
    shift = [8 - centre[i] for i in range(3)]
    elements = []
    for index, c in enumerate(cuboids):
        a = [round(c['from'][i] + shift[i], 4) for i in range(3)]
        b = [round(c['to'][i] + shift[i], 4) for i in range(3)]
        element = {'from': a, 'to': b}
        if display == 'tool':
            element['rotation'] = {'angle': -45, 'axis': 'z', 'origin': [8, 8, 8]}
        elif c['rotation']:
            r = c['rotation']
            element['rotation'] = {'angle': r['angle'], 'axis': r['axis'],
                                   'origin': [round(r['origin'][i] + shift[i], 4) for i in range(3)]}
        element['faces'] = {face: {'uv': [round(v * 16 / TEX, 4) for v in face_uv(ident, index, c, face)], 'texture': '#model'}
                            for face in FACES}
        elements.append(element)
    return elements


def item_model(ident, sprite):
    """separate_transforms: the 3D base in the hands, the sprite in the GUI, on the ground and in frames."""
    display = HELD[ident][1]
    generated = {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'mcyukkuri:item/{sprite}'}}
    return {'loader': 'neoforge:separate_transforms', 'gui_light': 'front',
            'base': {'textures': {'model': f'mcyukkuri:item/{ident}_model', 'particle': f'mcyukkuri:item/{sprite}'},
                     'elements': hand_elements(ident), 'display': DISPLAY[display]},
            'perspectives': {'gui': generated, 'ground': generated, 'fixed': generated}}


def _rotate(point, rotation):
    if not rotation:
        return point
    axis = 'xyz'.index(rotation['axis'])
    a = math.radians(rotation['angle'])
    p = [point[i] - rotation['origin'][i] for i in range(3)]
    i, j = [(1, 2), (2, 0), (0, 1)][axis]
    p[i], p[j] = p[i] * math.cos(a) - p[j] * math.sin(a), p[i] * math.sin(a) + p[j] * math.cos(a)
    return [p[k] + rotation['origin'][k] for k in range(3)]


# Corner order per face, matching the vanilla FaceBakery UV layout (u0,v0 at the first corner).
CORNERS = {
    'north': [(1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)], 'south': [(0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)],
    'east': [(1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)], 'west': [(0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)],
    'up': [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)], 'down': [(0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)],
}


def triangles(ident, frame='tool', hand_only=True):
    """Props-format rows (x, y, z, u, v, nx, ny, nz) in pixels: 'tool' frame, 'hand' (item space, as baked) or 'contact'."""
    factory, display, _, contact = HELD[ident]
    cuboids = factory()
    shift = [0, 0, 0]
    if frame == 'hand':
        centre = _centre(cuboids)
        shift = [8 - centre[i] for i in range(3)]
    rows = []
    for index, c in enumerate(cuboids):
        if c['hand_only'] and not hand_only:
            continue
        lo = [c['from'][i] + shift[i] for i in range(3)]
        hi = [c['to'][i] + shift[i] for i in range(3)]
        rotation = c['rotation']
        if frame == 'hand':
            rotation = {'angle': -45, 'axis': 'z', 'origin': [8, 8, 8]} if display == 'tool' else (
                rotation and {**rotation, 'origin': [rotation['origin'][i] + shift[i] for i in range(3)]})
        for face in FACES:
            u0, v0, u1, v1 = face_uv(ident, index, c, face)
            uv = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            points = [_rotate([hi[k] if corner[k] else lo[k] for k in range(3)], rotation) for corner in CORNERS[face]]
            if frame == 'contact':
                origin, R = contact['origin'], contact['rows']
                points = [[sum(R[r][k] * (p[k] - origin[k]) for k in range(3)) for r in range(3)] for p in points]
            e1 = [points[3][k] - points[0][k] for k in range(3)]
            e2 = [points[2][k] - points[0][k] for k in range(3)]
            n = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]]
            length = math.sqrt(sum(x * x for x in n)) or 1
            n = [x / length for x in n]
            for tri in ((0, 3, 2), (0, 2, 1)):          # counter-clockwise seen from outside
                for k in tri:
                    rows.append([*points[k], uv[k][0] / TEX, uv[k][1] / TEX, *n])
    return rows


# Palette colours of the body-fitted meshes in build.py -> material of the same tool's texture.
FITTED = {
    'restraint_frame': {0: 'plank', 7: 'cloth', 8: 'strap'},
    'wrap_sling': {7: 'cloth', 8: 'iron'},
    'iron_enclosure': {3: 'iron', 4: 'iron'},
}


def fitted_tool(name):
    return next((tool for tool in FITTED if name.startswith(tool + '_')), None)


def swatch_uv(tool, colour, points, normal):
    """Planar UVs for one fitted triangle (pixels in, texture units out): project along the dominant normal axis,
    one texel per pixel, kept inside the material's swatch."""
    slot = HELD[tool][2].index(FITTED[tool][colour])
    axis = max(range(3), key=lambda k: abs(normal[k]))
    i, j = [(2, 1), (0, 2), (0, 1)][axis]
    uv = [(p[i], -p[j]) for p in points]
    lo = [math.floor(min(c[k] for c in uv)) for k in range(2)]
    span = max(max(c[k] for c in uv) - lo[k] for k in range(2))
    scale = min(1.0, (SWATCH - .5) / span) if span > 0 else 1.0
    base = (slot % 2 * SWATCH + .25, slot // 2 * SWATCH + .25)
    return [((base[0] + (c[0] - lo[0]) * scale) / TEX, (base[1] + (c[1] - lo[1]) * scale) / TEX) for c in uv]


def contact_mesh(ident):
    rows = triangles(ident, 'contact', hand_only=False)
    vertices = [round(x, 6) for row in rows for x in row]
    bounds = [[min(vertices[i::8]), max(vertices[i::8])] for i in range(3)]
    return {'format': 1, 'units_per_block': 16, 'vertices': vertices,
            'parts': [{'name': 'model', 'start': 0, 'count': len(rows), 'texture': f'mcyukkuri:textures/item/{ident}_model.png'}],
            'bounds': bounds}
