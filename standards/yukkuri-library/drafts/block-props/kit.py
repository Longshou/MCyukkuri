"""Shared kit for the vanilla-style world props: block-style voxel models and extruded item icons.

Which style: small handheld or dropped items (bottles, cord, whistle, cloth) are extruded icons; anything placed as a
block, larger than about 8 units, or standing on furniture is block style, and every rounded object gets an explicit
stepped-round recipe (octagon() and inset() for flat vessels, Shape.ball() for spheres) plus painted detail, never a
plain cuboid stack.

Block style: cells of one unit (1/16 block) filled with materials. Only faces between a
filled cell and an empty one are drawn, merged into rectangles, so no two faces ever share a plane (no z-fighting) and no
face hides inside the solid. Every face texel is painted from its cell: one texel per unit (vanilla density), each
material a ramp from the item icons (`item-icons/draw.py` is the only palette source), light from the top left (top faces
one step lighter; the top row of a side face and the north and west border of a top face one step lighter, the bottom
row and the south and east border one step darker), no noise tiles and no vanilla textures.

Extruded icons (small handheld items): the 16x16 icon itself becomes the prop the way vanilla builds `item/generated`:
a front and a back face plus one-pixel edge strips, one pixel thick, scaled to the item's real height. The texture is
the icon, so the world view cannot drift from it. prop_item_model(extruded=True) gives the plate vanilla hand angles
and a turned fixed view so it is never held edge-on.

Models export the PropModels triangle mesh (eight floats per vertex), a Blockbench `.bbmodel` and the PNG atlas.
Units are 1/16 block, +Y up, the icon front faces -Z.
"""
import base64, importlib.util, io, json, uuid
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
DRAW = HERE.parent / 'item-icons/draw.py'

_spec = importlib.util.spec_from_file_location('item_icon_ramps', DRAW)
draw = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(draw)


def ramp(name):
    """An icon ramp by its draw.py name (dark -> light), including the ramps draw.py borrows from its two sources."""
    for module in (draw, draw.T, draw.S):
        value = getattr(module, name, None)
        if isinstance(value, list):
            return [tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) for c in value]
    raise KeyError(f'No icon ramp {name}')


def dumps(data):
    return (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')


def png_bytes(image):
    stream = io.BytesIO()
    image.save(stream, format='PNG', optimize=True)
    return stream.getvalue()


def prop_item_model(name, gui=None, extruded=False):
    """The `builtin/entity` item model drawn by client/PropItemRenderer; `gui` overrides the inventory transform.

    An extruded icon plate takes vanilla's item/generated hand angles (turned half a turn, its front being -Z) so a hand
    never holds it edge-on, and a fixed (mouth, item frame) view turned 30 degrees so neither the front nor the side of
    the holder sees only its edge. On the ground the item entity spins it as vanilla does."""
    display = {
        'gui': {'rotation': [25, 225, 0], 'translation': [0, 0, 0], 'scale': [.85, .85, .85], **(gui or {})},
        'ground': {'translation': [0, 3, 0], 'scale': [.5, .5, .5]},
        'fixed': {'scale': [.75, .75, .75]},
        'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [.5, .5, .5]},
        'firstperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [.6, .6, .6]}}
    if extruded:
        display.update({
            'fixed': {'rotation': [0, 30, 0], 'scale': [.75, .75, .75]},
            'thirdperson_righthand': {'rotation': [0, 90, -55], 'translation': [0, 4, .5], 'scale': [.85, .85, .85]},
            'firstperson_righthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [.68, .68, .68]}})
    return {'parent': 'minecraft:builtin/entity', 'gui_light': 'front', 'textures': {'particle': f'mcyukkuri:props/{name}'},
            'display': display}


# ---- face frames ------------------------------------------------------------------------------------------------------
# Per face direction: normal (axis, sign) and the texture axes u (right) and v (down) as (axis, sign) seen from outside,
# the same orientation as Blockbench cube faces.
FACES = {
    'north': ((2, -1), (0, -1), (1, -1)),
    'south': ((2, 1), (0, 1), (1, -1)),
    'east': ((0, 1), (2, -1), (1, -1)),
    'west': ((0, -1), (2, 1), (1, -1)),
    'up': ((1, 1), (0, 1), (2, 1)),
    'down': ((1, -1), (0, 1), (2, -1)),
}


def inward(cell, face, centre=(0, 0)):
    """Whether a side face of `cell` looks toward the vertical axis through `centre` (the inside of a ring wall)."""
    (axis, sign), _, _ = FACES[face]
    if axis == 1:
        return False
    offset = cell[axis] + .5 - (centre[0] if axis == 0 else centre[1])
    return offset * sign < 0


def _step(axis, sign):
    d = [0, 0, 0]
    d[axis] = sign
    return tuple(d)


def _add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


CLEAR = (0, 0, 0, 0)


class Material:
    """A ramp (draw.py name), its base step and an optional `pattern(cell, face)` returning a step offset, (ramp name,
    step) or CLEAR (a cut-out texel)."""

    def __init__(self, ramp_name, step=2, top=1, bottom=-1, bevel=True, pattern=None):
        self.ramp = ramp(ramp_name)
        self.step, self.top, self.bottom, self.bevel, self.pattern = step, top, bottom, bevel, pattern

    def texel(self, cell, face, lit, shaded):
        colours, step = self.ramp, self.step
        step += self.top if face == 'up' else self.bottom if face == 'down' else 0
        if self.pattern:
            value = self.pattern(cell, face)
            if value == CLEAR:
                return CLEAR
            if isinstance(value, tuple):
                colours, step = ramp(value[0]), value[1]
            elif value:
                step += value
        if self.bevel:
            step += 1 if lit else -1 if shaded else 0
        return colours[max(0, min(len(colours) - 1, step))]


class Clear(Material):
    """Cut-out cells: the geometry is there but every texel is transparent. A family member (one look of a shared atlas)
    paints an optional part of the family's geometry with it to leave that part out."""

    def __init__(self):
        pass

    def texel(self, cell, face, lit, shaded):
        return CLEAR


class Block:
    """Voxel prop. A part's faces against its own unit and lower units are omitted (always drawn with it); faces
    against higher units stay, because those units may be hidden. Each cell belongs to one part. A part made with
    solid=False (an optional layer that some looks cut out) hides no faces of the other parts, so whatever lies under
    it stays closed when it is clear."""

    def __init__(self, name, offset=(0, 0, 0)):
        self.name, self.offset, self.parts, self._export = name, offset, [], None

    # The care-prop exporters read props through mesh() / bbmodel() / png() / tri_count().
    def mesh(self):
        return self._exported()[0]

    def bbmodel(self):
        return self._exported()[1]

    def png(self):
        return self._exported()[2]

    def tri_count(self):
        return len(self.mesh()['vertices']) // 24

    def _exported(self):
        if self._export is None:
            self._export = self.export()
        return self._export

    def part(self, name, unit=0, solid=True):
        part = {'name': name, 'unit': unit, 'cells': {}, 'solid': solid}
        self.parts.append(part)
        return Shape(part)

    def _occupied(self, part):
        cells = set(part['cells'])
        for other in self.parts:
            if other['unit'] <= part['unit'] and other['solid']:
                cells |= set(other['cells'])
        return cells

    def faces(self):
        """(part, face, plane, rectangles) with every rectangle a dict {(u, v): rgb} of exposed texels."""
        result = []
        for part in self.parts:
            filled = self._occupied(part)
            for face, ((axis, sign), (ua, us), (va, vs)) in FACES.items():
                normal = _step(axis, sign)
                planes = {}
                for cell, material in part['cells'].items():
                    if _add(cell, normal) in filled:
                        continue
                    u, v = (cell[ua] if us > 0 else -cell[ua] - 1), (cell[va] if vs > 0 else -cell[va] - 1)
                    planes.setdefault(cell[axis], {})[(u, v)] = (cell, material)
                for depth, texels in sorted(planes.items()):
                    painted = {}
                    for (u, v), (cell, material) in texels.items():
                        edge = lambda du, dv: (u + du, v + dv) not in texels
                        flat = axis == 1        # side faces bevel only along their top and bottom rows
                        painted[(u, v)] = material.texel(cell, face, edge(0, -1) or (flat and edge(-1, 0)),
                                                         edge(0, 1) or (flat and edge(1, 0)))
                    plane = depth + 1 if sign > 0 else depth
                    for rect in _rectangles(set(painted)):
                        result.append((part, face, plane, rect, painted))
        return result

    def _check_cells(self):
        seen = {}
        for part in self.parts:
            for cell in part['cells']:
                if seen.setdefault(cell, part['name']) != part['name']:
                    raise ValueError(f'{self.name}: cell {cell} is in {seen[cell]} and {part["name"]}')

    def export(self):
        """Returns (mesh, bbmodel, png bytes)."""
        faces = self.faces()
        self._check_cells()
        sizes = [(r[2] - r[0], r[3] - r[1]) for _, _, _, r, _ in faces]
        size, slots = _pack(sizes)
        image = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        for (_, _, _, (u0, v0, u1, v1), painted), (x, y) in zip(faces, slots):
            for (u, v), rgb in painted.items():
                if u0 <= u < u1 and v0 <= v < v1:
                    image.putpixel((x + u - u0, y + v - v0), _rgba(rgb))
        return self._build(faces, slots, size, image)

    def _build(self, faces, slots, size, image):
        """Mesh, bbmodel and PNG from the faces, the atlas position of each face rectangle and the painted atlas."""
        vertices, parts, elements = [], [], []
        for part in self.parts:
            start = len(vertices) // 8
            bb_vertices, bb_faces = {}, {}
            for (owner, face, plane, (u0, v0, u1, v1), painted), (x, y) in zip(faces, slots):
                if owner is not part:
                    continue
                (axis, sign), (ua, us), (va, vs) = FACES[face]
                normal = [0.0, 0.0, 0.0]
                normal[axis] = float(sign)
                corners = []
                for cu, cv, tu, tv in ((u0, v0, x, y), (u1, v0, x + u1 - u0, y), (u1, v1, x + u1 - u0, y + v1 - v0), (u0, v1, x, y + v1 - v0)):
                    p = [0.0, 0.0, 0.0]
                    p[axis], p[ua], p[va] = plane, cu * us, cv * vs
                    corners.append(([p[k] + self.offset[k] + 0.0 for k in range(3)], (tu, tv)))
                quad = (0, 1, 2, 3)
                a, b, c = (corners[k][0] for k in quad[:3])
                turn = [(b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]),
                        (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]),
                        (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])]
                if sum(turn[k] * normal[k] for k in range(3)) < 0:
                    quad = (0, 3, 2, 1)                    # counter-clockwise seen from outside
                for tri in ((quad[0], quad[1], quad[2]), (quad[0], quad[2], quad[3])):
                    for k in tri:
                        pos, (tu, tv) = corners[k]
                        vertices.extend([*pos, tu / size, tv / size, *normal])
                ids = []
                for pos, _ in (corners[k] for k in quad):
                    key = ','.join(str(c) for c in pos)
                    bb_vertices.setdefault(key, (str(len(bb_vertices)), pos))
                    ids.append(bb_vertices[key][0])
                bb_faces[str(len(bb_faces))] = {'vertices': ids, 'texture': 0,
                                                'uv': {i: list(corners[k][1]) for i, k in zip(ids, quad)}}
            parts.append({'name': part['name'], 'start': start, 'count': len(vertices) // 8 - start, 'unit': part['unit']})
            elements.append({'name': part['name'], 'type': 'mesh', 'origin': [0, 0, 0], 'rotation': [0, 0, 0],
                             'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL, f'mcyukkuri/block-props/{self.name}/{part["name"]}')),
                             'vertices': {i: pos for i, pos in bb_vertices.values()}, 'faces': bb_faces})
        bounds = [[min(vertices[k::8]), max(vertices[k::8])] for k in range(3)]
        mesh = {'format': 1, 'units_per_block': 16, 'id': self.name, 'texture_size': [size, size],
                'vertices': vertices, 'parts': parts, 'bounds': bounds}
        png = png_bytes(image)
        return mesh, _bbmodel(self.name, elements, size, size, png), png


def _rgba(colour):
    return colour if len(colour) == 4 else colour + (255,)


def share_atlas(blocks):
    """One atlas layout for a family of blocks with the same part names (a bed and its shapes fitted against walls): each
    (part, face direction, plane) gets one atlas region covering the texels it shows in any member, so a member's
    faces sample the same texels as the others'. A texel shown by several members takes the first member's paint.
    Members built with different materials but the same cells give the same layout, so one member's geometry can be
    drawn with another look's atlas. Sets every block's export; returns the shared PNG bytes."""
    regions, colours, faces = {}, {}, {}
    for block in blocks:
        block._check_cells()
        faces[block.name] = block.faces()
        for part, face, plane, (u0, v0, u1, v1), painted in faces[block.name]:
            key = (part['name'], face, plane)
            box = regions.setdefault(key, [u0, v0, u1, v1])
            box[:] = [min(box[0], u0), min(box[1], v0), max(box[2], u1), max(box[3], v1)]
            for (u, v), rgb in painted.items():
                if u0 <= u < u1 and v0 <= v < v1:
                    colours.setdefault((key, u, v), rgb)
    keys = sorted(regions)
    size, slots = _pack([(regions[k][2] - regions[k][0], regions[k][3] - regions[k][1]) for k in keys])
    origin = {k: (x - regions[k][0], y - regions[k][1]) for k, (x, y) in zip(keys, slots)}
    image = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    for (key, u, v), rgb in colours.items():
        image.putpixel((origin[key][0] + u, origin[key][1] + v), _rgba(rgb))
    for block in blocks:
        rects = faces[block.name]
        placed = [(origin[(p['name'], f, plane)][0] + r[0], origin[(p['name'], f, plane)][1] + r[1]) for p, f, plane, r, _ in rects]
        block._export = block._build(rects, placed, size, image)
    return blocks[0]._export[2]


class Shape:
    """Edits one part's cells; boxes are [lo, hi) in integer units."""

    def __init__(self, part):
        self.cells = part['cells']

    def box(self, lo, hi, material):
        for x in range(lo[0], hi[0]):
            for y in range(lo[1], hi[1]):
                for z in range(lo[2], hi[2]):
                    self.cells[(x, y, z)] = material
        return self

    def carve(self, lo, hi):
        for x in range(lo[0], hi[0]):
            for y in range(lo[1], hi[1]):
                for z in range(lo[2], hi[2]):
                    self.cells.pop((x, y, z), None)
        return self

    def cell(self, at, material):
        self.cells[tuple(at)] = material
        return self

    def layer(self, cells, y0, y1, material):
        """Fill (or with material None carve) the plan `cells` {(x, z)} from y0 to y1."""
        for x, z in cells:
            if material is None:
                self.carve((x, y0, z), (x + 1, y1, z + 1))
            else:
                self.box((x, y0, z), (x + 1, y1, z + 1), material)
        return self

    def ball(self, radius, centre, material):
        """Stepped sphere: every cell whose centre lies within `radius` of `centre` (cell-centre coordinates), so the
        corners are cut on all three axes and each layer is a stepped round, as vanilla rounds a shape in cubes."""
        cx, cy, cz = centre
        reach = int(radius) + 2
        for x in range(int(cx) - reach, int(cx) + reach):
            for y in range(int(cy) - reach, int(cy) + reach):
                for z in range(int(cz) - reach, int(cz) + reach):
                    if (x + .5 - cx) ** 2 + (y + .5 - cy) ** 2 + (z + .5 - cz) ** 2 <= radius * radius:
                        self.cells[(x, y, z)] = material
        return self


def octagon(half, chamfer, centre=(0, 0), half_z=None):
    """Plan cells of a square 2*half wide (2*half_z deep when given) with each corner stepped off one cell per row over
    `chamfer` rows: the stepped round of flat vessels and trays. Symmetric under both mirrors (and the diagonal when square)."""
    cx, cz = centre
    hz = half if half_z is None else half_z
    return {(x, z) for x in range(cx - half, cx + half) for z in range(cz - hz, cz + hz)
            if max(x - cx + 1, cx - x) + max(z - cz + 1, cz - z) <= half + hz - chamfer}


def disc(radius, centre=(0, 0), squircle=2):
    """Plan cells whose centres lie within `radius` of `centre` (a stepped circle; squircle=4 gives the rounder square of
    a basket or a 2x2 nest)."""
    cx, cz = centre
    reach = int(radius) + 1
    return {(x, z) for x in range(int(cx) - reach - 1, int(cx) + reach + 1) for z in range(int(cz) - reach - 1, int(cz) + reach + 1)
            if abs(x + .5 - cx) ** squircle + abs(z + .5 - cz) ** squircle <= radius ** squircle}


def inset(cells, steps=1):
    """The plan shrunk by whole cells: a cell stays when its four neighbours are in the shape. A wall between a plan and
    its inset is one cell thick everywhere, the steps of the corners included."""
    for _ in range(steps):
        cells = {(x, z) for x, z in cells if {(x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)} <= cells}
    return cells


def _rectangles(texels):
    """Greedy merge of a texel set into [u0, v0, u1, v1) rectangles, row by row."""
    left, result = set(texels), []
    for u, v in sorted(texels, key=lambda t: (t[1], t[0])):
        if (u, v) not in left:
            continue
        u1 = u
        while (u1 + 1, v) in left:
            u1 += 1
        v1 = v
        while all((k, v1 + 1) in left for k in range(u, u1 + 1)):
            v1 += 1
        for k in range(u, u1 + 1):
            for j in range(v, v1 + 1):
                left.discard((k, j))
        result.append((u, v, u1 + 1, v1 + 1))
    return result


def _pack(sizes):
    """Shelf packing, tallest first, into the smallest square power-of-two atlas (at least 16)."""
    order = sorted(range(len(sizes)), key=lambda i: (-sizes[i][1], -sizes[i][0], i))
    size = 16
    while True:
        slots, x, y, row = [None] * len(sizes), 0, 0, 0
        for i in order:
            w, h = sizes[i]
            if x + w > size:
                x, y, row = 0, y + row, 0
            if w > size or y + h > size:
                break
            slots[i] = (x, y)
            x += w
            row = max(row, h)
        else:
            return size, slots
        size *= 2


def _bbmodel(name, elements, width, height, png):
    return {'meta': {'format_version': '4.10', 'model_format': 'free'}, 'name': name,
            'resolution': {'width': width, 'height': height}, 'elements': elements,
            'outliner': [e['uuid'] for e in elements],
            'textures': [{'name': 'texture', 'source': 'data:image/png;base64,' + base64.b64encode(png).decode(),
                          'width': width, 'height': height, 'uv_width': width, 'uv_height': height, 'id': '0'}]}


# ---- extruded icons ---------------------------------------------------------------------------------------------------
def extrude(name, icon_png, height):
    """The icon as a one-pixel-thick plate standing on y = 0, centred on x and z, its opaque rows `height` units tall.

    Returns (mesh, bbmodel, png bytes); the texture is the icon file itself."""
    with Image.open(io.BytesIO(icon_png)) as opened:
        icon = opened.convert('RGBA')
    W, H = icon.size
    solid = {(x, y) for y in range(H) for x in range(W) if icon.getpixel((x, y))[3] >= 128}
    if any(0 < icon.getpixel((x, y))[3] < 255 for y in range(H) for x in range(W)):
        raise ValueError(f'{name}: icon has partial alpha; extrusion needs a cut-out sprite')
    x0, x1 = min(x for x, _ in solid), max(x for x, _ in solid) + 1
    y0, y1 = min(y for _, y in solid), max(y for _, y in solid) + 1
    s = height / (y1 - y0)
    cx = (x0 + x1) / 2

    def at(px, py, z):
        # Seen from -Z (the front) the icon reads unmirrored, so image x runs toward -X.
        return [round((cx - px) * s, 6) + 0.0, round((y1 - py) * s, 6) + 0.0, round(z * s, 6) + 0.0]

    quads = []        # (part, four (position, uv-pixel) corners counter-clockwise from outside, normal)
    front = [(at(x0, y0, -.5), (x0, y0)), (at(x1, y0, -.5), (x1, y0)), (at(x1, y1, -.5), (x1, y1)), (at(x0, y1, -.5), (x0, y1))]
    back = [(at(x1, y0, .5), (x1, y0)), (at(x0, y0, .5), (x0, y0)), (at(x0, y1, .5), (x0, y1)), (at(x1, y1, .5), (x1, y1))]
    quads += [('front', front, [0.0, 0.0, -1.0]), ('back', back, [0.0, 0.0, 1.0])]
    # Edge strips: runs of opaque pixels whose neighbour on that side is clear, each sampling its own pixel row/column.
    m = .5
    for dx, dy, normal in ((0, -1, [0.0, 1.0, 0.0]), (0, 1, [0.0, -1.0, 0.0]), (-1, 0, [1.0, 0.0, 0.0]), (1, 0, [-1.0, 0.0, 0.0])):
        edges = sorted(p for p in solid if (p[0] + dx, p[1] + dy) not in solid)
        horizontal = dy != 0
        runs = []
        for px, py in sorted(edges, key=(lambda p: (p[1], p[0])) if horizontal else (lambda p: (p[0], p[1]))):
            if runs and ((runs[-1][1] == py and runs[-1][2] == px) if horizontal else (runs[-1][0] == px and runs[-1][3] == py)):
                if horizontal:
                    runs[-1][2] = px + 1
                else:
                    runs[-1][3] = py + 1
            else:
                runs.append([px, py, px + 1, py + 1] if horizontal else [px, py, px + 1, py + 1])
        for ax, ay, bx, by in runs:
            if horizontal:
                ey = ay if dy < 0 else by                 # the pixel's top or bottom boundary
                vy = ay + m * .5 + (0 if dy < 0 else m)   # sample inside the pixel row
                corners = [(at(ax, ey, -.5), (ax, vy)), (at(bx, ey, -.5), (bx, vy)), (at(bx, ey, .5), (bx, vy)), (at(ax, ey, .5), (ax, vy))]
            else:
                ex = ax if dx < 0 else bx
                vx = ax + m * .5 + (0 if dx < 0 else m)
                corners = [(at(ex, ay, -.5), (vx, ay)), (at(ex, by, -.5), (vx, by)), (at(ex, by, .5), (vx, by)), (at(ex, ay, .5), (vx, ay))]
            quads.append(('edge', corners, normal))
    vertices, counts, bb_vertices, bb_faces = [], {}, {}, {}
    for part in ('front', 'back', 'edge'):
        counts[part] = len(vertices) // 8
        for owner, corners, normal in quads:
            if owner != part:
                continue
            a, b, c = (corners[k][0] for k in range(3))
            turn = [(b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]),
                    (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]),
                    (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])]
            order = (0, 1, 2, 3) if sum(turn[k] * normal[k] for k in range(3)) >= 0 else (0, 3, 2, 1)
            for tri in ((order[0], order[1], order[2]), (order[0], order[2], order[3])):
                for k in tri:
                    pos, (u, v) = corners[k]
                    vertices.extend([*pos, round(u / W, 6), round(v / H, 6), *normal])
            ids = []
            for k in order:
                key = ','.join(str(c) for c in corners[k][0])
                bb_vertices.setdefault(key, (str(len(bb_vertices)), corners[k][0]))
                ids.append(bb_vertices[key][0])
            bb_faces[str(len(bb_faces))] = {'vertices': ids, 'texture': 0,
                                            'uv': {i: [round(corners[k][1][0], 3), round(corners[k][1][1], 3)] for i, k in zip(ids, order)}}
    total = len(vertices) // 8
    names = ['front', 'back', 'edge']
    parts = [{'name': n, 'start': counts[n], 'count': (counts[names[i + 1]] if i < 2 else total) - counts[n], 'unit': 0}
             for i, n in enumerate(names)]
    bounds = [[min(vertices[k::8]), max(vertices[k::8])] for k in range(3)]
    mesh = {'format': 1, 'units_per_block': 16, 'id': name, 'texture_size': [W, H], 'vertices': vertices, 'parts': parts,
            'bounds': bounds}
    element = {'name': 'extruded_icon', 'type': 'mesh', 'origin': [0, 0, 0], 'rotation': [0, 0, 0],
               'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL, f'mcyukkuri/block-props/{name}/extruded_icon')),
               'vertices': {i: pos for i, pos in bb_vertices.values()}, 'faces': bb_faces}
    return mesh, _bbmodel(name, [element], W, H, icon_png), icon_png
