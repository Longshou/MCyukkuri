"""Derive the predator-subspecies draft from the canonical assembled library.

Design basis is limited to what the local reference check actually confirms:
flight, wings and fangs. Wing structure, fang size, hair colour and the body
uplift are this draft's own choices and are recorded as such in recipes.json.

Run ../../build.py first if canonical components change. No legacy variants or
extracted reference pixels are used. recipes.json remains the authoring source.
"""
from pathlib import Path
import base64, copy, hashlib, io, json, math, sys, uuid
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
sys.path.insert(0, str(LIB/'ecology/house-yukkuri'))
sys.path.insert(0, str(LIB))
from mesh_geometry import vertices, poly_mesh


def read(p):
    return json.loads(Path(p).read_text(encoding='utf-8-sig'))


def save(p, data):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')


def source(base):
    folder = LIB / 'assembled' / base
    html = (folder / 'index.html').read_text(encoding='utf-8')
    data = json.JSONDecoder().raw_decode(html.split('const DATA=', 1)[1])[0]
    return read(folder / f'{base}.bbmodel'), data


# ---------------------------------------------------------------- texture work

def draw_wing(img, box, sign, colors, struts):
    """Author the wing skin for panels running diagonally across the region.

    A strip's six faces all sample the same UV rect, and that rect is wide and
    short, so it is drawn on the diagonal: broad membrane bands running corner
    to corner read as cells along the wing's length, which is how the reference
    wing is shaded, instead of the checkering vertical bands would give.
    """
    u0, v0, u1, v1 = box
    x0, y0, x1, y1 = u0 + 1, v0 + 1, u1 - 2, v1 - 2
    w, h = x1 - x0, y1 - y0
    big = Image.new('RGBA', (w * 4, h * 4), colors['membrane'])
    d = ImageDraw.Draw(big)
    W, H = big.size
    band = max(8, W // 12)
    for i, start in enumerate(range(-H, W, band)):
        if i % 2:
            continue
        d.polygon([(start, H), (start + band, H), (start + band + H, 0), (start + H, 0)],
                  fill=colors['membrane_dark'])
    d.polygon([(0, 0), (W, 0), (W, h), (0, h)], fill=colors['wing_leading'])
    for i, start in enumerate(range(-H, W, band)):
        if i % 2:
            continue
        d.line([(start, H), (start + H, 0)], fill=colors['bone'], width=max(2, h // 3))
    d.line([(0, H - 1), (W, H - 1)], fill=colors['membrane_light'], width=2)
    if sign < 0:
        big = big.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    img.paste(big.resize((w, h), Image.Resampling.NEAREST), (x0, y0))


def draw_cap(img, box, colors):
    """Author the mob cap skin as three horizontal bands.

    Reference R1/R3 show a pink cap with a ruffled brim, so the region is split
    by height: dome pink on top, a red ribbon band in the middle, and a ruffled
    hem below. Cap cubes are placed to match, so each samples its own band; the
    tabs are drawn large because each band is only a few texels tall.
    """
    u0, v0, u1, v1 = box
    w, h = u1 - u0, v1 - v0
    dome, band_lo, ruff_lo = h // 3, h // 2, (h * 3) // 4
    dark = tuple(max(0, c - 52) for c in colors['cap_shade'][:3]) + (colors['cap_shade'][3],)
    px = Image.new('RGBA', (w, h), colors['cap'])
    d = ImageDraw.Draw(px)
    d.rectangle([0, dome, w - 1, ruff_lo - 1], fill=colors['cap_shade'])
    d.rectangle([0, band_lo, w - 1, ruff_lo - 1], fill=colors['cap_ribbon'])
    tab = max(5, w // 8)
    for x in range(0, w, tab * 2):
        d.rectangle([x, ruff_lo, min(x + tab - 1, w - 1), h - 1], fill=dark)
    d.rectangle([0, h - 1, w - 1, h - 1], fill=colors['cap_ribbon'])
    img.paste(px, (u0, v0))


def draw_fang(img, box, colors, bare=False):
    """Author the fang strip: two canines at the outer thirds, or bare gum."""
    u0, v0, u1, v1 = box
    px = Image.new('RGBA', (u1 - u0, v1 - v0), (0, 0, 0, 0))
    d = ImageDraw.Draw(px)
    w, h = px.size
    if bare:
        d.rectangle([w // 8, 0, w // 2 - 3, 2], fill=colors['gum'])
        d.rectangle([w // 2 + 2, 0, w - w // 8, 2], fill=colors['gum'])
    else:
        for cx in (w * 3 // 8, w * 5 // 8):
            length = h - 1
            for i in range(length):
                t = i / length
                # Wide at the gum line, tapering to a point.
                half = max(1, int(round((1 - t * t) * 3)))
                d.rectangle([cx - half, i, cx + half - 1, i], fill=colors['tooth'])
                px.putpixel((cx - half, i), colors['tooth_edge'])
                px.putpixel((cx + half - 1, i), colors['tooth_edge'])
            d.rectangle([cx - 4, 0, cx + 3, 1], fill=colors['gum'])
    img.paste(px, (u0, v0))


def paint_cap(img, regions, colors):
    """Flat fills for the mob cap: white body, dimmer brim, red band."""
    for key, colour in (('cap_body', colors['cap']), ('cap_brim', colors['cap_shade']),
                        ('cap_band', colors['cap_ribbon'])):
        u0, v0, u1, v1 = regions[key]
        img.paste(Image.new('RGBA', (u1 - u0, v1 - v0), colour), (u0, v0))


def recolor_hair(atlas, config):
    """Swap the golden hair palette for the draft's pale blue inside hair-only UV."""
    spec = config['hair_recolor']
    mapping = {tuple(int(v) for v in k.split(',')): tuple(v) for k, v in spec['map'].items()}
    px, changed = atlas.load(), 0
    for u, v, U, V in spec['scope_uv']:
        for y in range(v, V):
            for x in range(u, U):
                cur = px[x, y]
                if cur[3] == 0:
                    continue
                hit = mapping.get((cur[0], cur[1], cur[2]))
                if hit is None:
                    continue
                px[x, y] = (hit[0], hit[1], hit[2], cur[3])
                changed += 1
    return changed


# ------------------------------------------------------------------- geometry

def element(name, bone, lo, hi, rotation=0, origin=None, uv_box=None):
    uv = [0, 0, 1, 1] if uv_box is None else list(uv_box)
    faces = {side: {'uv': list(uv), 'texture': 0}
             for side in ('north', 'south', 'east', 'west', 'up', 'down')}
    return dict(name=name, type='cube', from_=list(lo), to=list(hi),
                origin=list(origin) if origin is not None else [(a + b) / 2 for a, b in zip(lo, hi)],
                rotation=[0, 0, rotation], box_uv=False, rescale=False, autouv=0,
                color=0, bone=bone, faces=faces)


def finalize(e):
    e['from'] = e.pop('from_')
    return e


def wing_outline(spec, sign):
    """Sample the wing spine and offset it into a closed silhouette.

    Each spine point carries a half-width plus separate `edge` (trailing) and
    `edge_top` (leading) multipliers. Reference R2 shows a bat wing with a
    continuous leading edge and a scalloped trailing edge, so the leading side
    normally stays at 1.0 while `edge` dips at each inter-finger notch. Keeping
    the spine monotone in X means the scanline fill below cannot self-intersect.
    """
    spine = [(x, y, w, e, et) for x, y, w, e, et in spec['spine']]
    upper, lower = [], []
    for i, (x, y, w, edge, edge_top) in enumerate(spine):
        if i == 0:
            tx, ty = spine[1][0] - x, spine[1][1] - y
        elif i == len(spine) - 1:
            tx, ty = x - spine[i - 1][0], y - spine[i - 1][1]
        else:
            tx = spine[i + 1][0] - spine[i - 1][0]
            ty = spine[i + 1][1] - spine[i - 1][1]
        length = math.hypot(tx, ty) or 1.0
        nx, ny = -ty / length, tx / length
        hy = w * spec['width_y']
        upper.append((sign * (x + nx * w * edge_top), y + ny * hy * edge_top))
        lower.append((sign * (x - nx * w * edge), y - ny * hy * edge))
    # A polygon traversal, not a sort: out along the leading edge, back along
    # the trailing edge. Sorting by X would interleave the two chains.
    return upper + lower[::-1]


def refit_bow(elements, groups, spec):
    """Squash and drop the inherited bow so it sits on the shorter mob cap."""
    off, scale = spec['offset_y'], spec['scale_y']
    for e, g in zip(elements, groups):
        if g != 'bow':
            continue
        for key in ('from', 'to', 'origin'):
            if key in e and isinstance(e[key], list) and len(e[key]) == 3:
                e[key][1] = e[key][1] * scale + off
    return elements


def _wing_layout(spec):
    """Resolve the wing plane's world transform and its right-side outline."""
    rx, ry, rz = spec['root']
    yaw = math.radians(spec['yaw'])

    def to_world(x, y):
        reach = x - rx
        return [x - reach * (1 - math.cos(yaw)), y, rz + reach * math.sin(yaw)]

    outline = [to_world(x, y) for x, y in spec['outline']]
    fingers = [(to_world(*f['to']), f['bone']) for f in spec['fingers']]
    return to_world(rx, ry), outline, fingers


def wing_parts(recipe, regions):
    """Author one bat wing per side from an authored outline plus finger spars.

    Reference R2 reads as separated fingers with membrane slung between them, so
    the outline is authored with paired out/in points that give the trailing
    edge its scalloped notches, and the membrane is a scanline fill of that
    polygon. The finger spars are separate raised bars laid along the leading
    edge, so the articulation is genuine geometry rather than painted on. The
    whole plane is yawed so the wing sweeps backwards.
    """
    spec = recipe['wing']
    thickness = spec['thickness']
    rx, ry, rz = spec['root']
    fw = spec['finger_width']
    root_w, outline, fingers = _wing_layout(spec)
    # Webbing rides the outermost finger's bone, whatever it happens to be named.
    last_bone = fingers[-1][1]
    out = []
    for side, sign in (('left', -1), ('right', 1)):
        mirror = lambda p: [sign * p[0], p[1], p[2]]
        poly = [mirror(p) for p in outline]
        root_m = mirror(root_w)
        # Webbing: scanline fill of the outline polygon.
        x_lo = min(p[0] for p in poly)
        x_hi = max(p[0] for p in poly)
        steps = max(8, int((x_hi - x_lo) / 0.5))
        for s in range(steps):
            x0 = x_lo + (x_hi - x_lo) * s / steps
            x1 = x_lo + (x_hi - x_lo) * (s + 1) / steps

            def span(px):
                ys = []
                for a, b in zip(poly, poly[1:] + poly[:1]):
                    if abs(b[0] - a[0]) < 1e-9:
                        continue
                    t = (px - a[0]) / (b[0] - a[0])
                    if -1e-9 <= t <= 1 + 1e-9:
                        ys.append(a[1] + (b[1] - a[1]) * t)
                return (min(ys), max(ys)) if ys else None

            sa, sb = span(x0), span(x1)
            if not sa or not sb:
                continue
            y0, y1 = min(sa[0], sb[0]), max(sa[1], sb[1])
            if y1 - y0 < 0.3:
                continue
            out.append(element(f'wing_{side}_web_{s}', f'{last_bone}_{side}',
                               [x0, y0, rz - thickness * 0.35], [x1, y1, rz + thickness * 0.35],
                               0, root_m, regions['wing_' + side]))
        # Finger spars along the leading edge, each on its own bone.
        for tip_w, bone in fingers:
            tip = mirror(tip_w)
            lo = [min(root_m[0], tip[0]) - fw / 2, min(root_m[1], tip[1]) - fw / 2, rz - thickness / 2]
            hi = [max(root_m[0], tip[0]) + fw / 2, max(root_m[1], tip[1]) + fw / 2, rz + thickness / 2]
            out.append(element(f'wing_{side}_spar_{bone}', f'{bone}_{side}', lo, hi, 0, root_m,
                               regions['wing_' + side]))
    return [finalize(e) for e in out]


def wing_bones(recipe, parent='features_squash'):
    """One bone per finger per side, pivoted at the wrist."""
    spec = recipe['wing']
    root_w, _, fingers = _wing_layout(spec)
    out = []
    for side, sign in (('left', -1), ('right', 1)):
        for _, bone in fingers:
            out.append(dict(name=f'{bone}_{side}', parent=parent,
                            pivot=[sign * root_w[0], root_w[1], root_w[2]]))
    return out

def find_mouth_bounds(mouth_element, base_png):
    """Locate the drawn mouth in face-plane units by probing its UV block."""
    u0, v0, U0, V0 = mouth_element['faces']['north']['uv']
    px = Image.open(base_png).convert('RGBA').load()
    fx0, fy0 = mouth_element['from'][0], mouth_element['from'][1]
    fx1, fy1 = mouth_element['to'][0], mouth_element['to'][1]
    pts = []
    for y in range(int(v0), int(V0)):
        for x in range(int(u0), int(U0)):
            if px[x, y][3] <= 8:
                continue
            pts.append((fx0 + (x - u0 + .5) / (U0 - u0) * (fx1 - fx0),
                        fy1 - (y - v0 + .5) / (V0 - v0) * (fy1 - fy0)))
    if not pts:
        raise AssertionError('mouth pixels not found in base atlas')
    return min(p[0] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts)

def fang_parts(recipe, regions, mouth_element, base_png):
    """Tusks hanging from the mouth's upper edge, driven by the mouth bone.

    The face is a thin shell at the mouth's Z, so the tusks must sit *in front*
    of that plane (smaller Z) or they end up buried inside the head.
    """
    spec = recipe['fang']
    left, right, top = find_mouth_bounds(mouth_element, base_png)
    face_z = mouth_element['from'][2]
    back_z = face_z - spec['gap']
    front_z = back_z - spec['reach']
    hw = spec.get('half_width', 0.165)
    fang_uv = regions['fang']
    span = fang_uv[2] - fang_uv[0]
    out = []
    for side, sign in (('left', 1), ('right', -1)):
        cx = (right - hw * 0.9) if sign > 0 else (left + hw * 0.9)
        uv_box = ([fang_uv[0], fang_uv[1], fang_uv[0] + span, fang_uv[3]] if sign > 0
                  else [fang_uv[0] + span, fang_uv[1], fang_uv[0] + 2 * span, fang_uv[3]])
        out.append(element(f'fang_{side}', spec['parent_bone'],
                           [cx - hw, top - spec['length'], front_z], [cx + hw, top + 0.08, back_z],
                           0, None, uv_box))
    return [finalize(e) for e in out]


def clear_regions(atlas, boxes):
    """Blank atlas rectangles. Used to erase the base face art this variant
    replaces with its own larger features."""
    px = atlas.load()
    cleared = 0
    for u, v, U, V in boxes:
        for y in range(int(v), int(V)):
            for x in range(int(u), int(U)):
                if px[x, y][3]:
                    cleared += 1
                px[x, y] = (0, 0, 0, 0)
    return cleared


def retarget_face(elements, spec, body_scale, regions):
    """Move the ten shared face parts onto this variant's larger UV blocks.

    The parts keep their names, bones and expression bindings, so all 105
    expression presets still composite onto them; only the boxes and texture
    coordinates grow. This is a per-variant change and leaves the shared
    components untouched.
    """
    changed = []
    for e in elements:
        part = spec['parts'].get(e['name'])
        if not part:
            continue
        x0, y0, x1, y1 = part['box']
        # Keep the base parts' thin depth: a zero-extent cube is degenerate and
        # would lose its side faces.
        front = spec['face_z']
        back = front + spec.get('face_depth', 0.1)
        e['from'] = [x0 * body_scale[0], y0 * body_scale[1], front]
        e['to'] = [x1 * body_scale[0], y1 * body_scale[1], back]
        e['origin'] = [(e['from'][i] + e['to'][i]) / 2 for i in range(3)]
        u, v, U, V = regions[part['uv']]
        e['faces'] = {side: {'uv': [u, v, U, V], 'texture': 0}
                      for side in ('north', 'south', 'east', 'west', 'up', 'down')}
        changed.append(e['name'])
    return changed


def cap_parts(recipe, regions):
    """Mob cap pieces, each bound to its own slice of the shared cap texture.

    Reference R1/R3 show a round disc, but the block base builds square plates,
    so both the crown and the brim are emitted as ellipse segments: side plates
    at +-rx*corner plus four corner blocks sitting on the ellipse edge. The brim
    then gets a geometric ruffle - alternating tabs pushed outward - which is
    what reads as the ruffled hem.
    """
    spec = recipe['cap']
    bone = spec['bone']
    shape = spec['shape']
    cx, cz, rx, rz, corner = shape['cx'], shape['cz'], shape['rx'], shape['rz'], shape['corner']
    u0, v0, u1, v1 = regions['cap']
    h = v1 - v0
    dome, band_lo, ruff_lo = h // 3, h // 2, (h * 3) // 4
    strips = {
        'cap_body': [u0, v0, u1, v0 + dome],
        'cap_band': [u0, v0 + band_lo, u1, v0 + ruff_lo],
        'cap_brim': [u0, v0 + ruff_lo, u1, v1]
    }
    half_x = rx * corner
    half_z = rz * math.sqrt(max(0.0, 1 - corner * corner))
    # Ellipse half-extent in X at a given Z, used to place the corner blocks.
    def x_at(z):
        q = (z - cz) / rz
        return rx * math.sqrt(max(0.0, 1 - q * q))

    def ring(level, key, shrink, y):
        s = 1 - shrink
        w, d = half_x * s, half_z * s
        uvc = strips[key]
        out = []
        for side, sign in (('left', -1), ('right', 1)):
            xs = sorted([cx + sign * w, cx - sign * w])
            out.append(element(f'{level}_{side}', bone, [xs[0], y[0], cz - d],
                               [xs[1], y[1], cz + d]))
        # Corner blocks straddle the side plate's Z edge so they leave the
        # silhouette continuous; width is sampled at their outermost Z.
        depth = 0.9
        for zs, tag in ((-1, 'near'), (1, 'far')):
            z = cz + zs * d
            x = x_at(z + zs * depth / 2) * s
            out.append(element(f'{level}_{tag}', bone,
                               [cx - x, y[0], z - depth / 2 if zs < 0 else z],
                               [cx + x, y[1], z if zs < 0 else z + depth / 2]))
        return [(e, uvc) for e in out]

    pieces = []
    pieces += ring('cap_crown', 'cap_body', 0.0, spec['crown']['y'])
    # Stacked, progressively narrower tiers curve the silhouette toward a dome;
    # a single straight wall is what made the cap read as a plain cylinder.
    domes = spec.get('dome')
    if domes:
        for i, tier in enumerate(domes if isinstance(domes, list) else [domes]):
            pieces += ring(f'cap_dome{i}', tier['key'], tier['shrink'], tier['y'])
    pieces += ring('cap_band', 'cap_band', spec['band']['shrink'], spec['band']['y'])
    pieces += ring('cap_brim', 'cap_brim', spec['brim']['shrink'], spec['brim']['y'])
    # Geometric ruffle: tabs pushed outward along the ellipse normal.
    r = spec.get('ruffle')
    if r:
        ry = r['y']
        for i in range(r['tabs']):
            a = 2 * math.pi * i / r['tabs']
            t = 0.5 + 0.5 * math.cos(2 * a)  # 0 at the sides, 1 at front/back
            px = cx + rx * math.cos(a)
            pz = cz + rz * math.sin(a)
            bump = r['bump'] * (1 - r['taper'] * t)
            nx, nz = math.cos(a), math.sin(a)
            pieces.append((element(f'cap_ruffle_{i}', bone,
                                   [px - 0.9, ry[0], pz - 0.9],
                                   [px + 0.9 + nx * bump, ry[1], pz + 0.9 + nz * bump]),
                           strips['cap_brim']))
    for e, uvc in pieces:
        e['faces'] = {side: {'uv': list(uvc), 'texture': 0}
                      for side in ('north', 'south', 'east', 'west', 'up', 'down')}
    return [finalize(e) for e, _ in pieces]


# --------------------------------------------------------------------- export

def merge_meshes(entries):
    points, faces = [], {}
    for e in entries:
        offset = len(points)
        points.extend(e['vertices'][k] for k in sorted(e['vertices'], key=int))
        for key, face in e['faces'].items():
            faces[str(len(faces))] = dict(face, vertices=[str(int(k) + offset) for k in face['vertices']],
                                           uv={str(int(k) + offset): uv for k, uv in face['uv'].items()})
    return dict(vertices={str(i): p for i, p in enumerate(points)}, faces=faces)


def scale_element(e, scale):
    e = copy.deepcopy(e)
    for key in ('from', 'to', 'origin'):
        if key in e:
            e[key] = [e[key][i] * scale[i] for i in range(3)]
    return e


def scale_vertices(v, scale):
    """Scale baked 8-float vertices (position, uv, normal) and renormalise."""
    out = list(v)
    for i in range(0, len(out), 8):
        for j in range(3):
            out[i + j] = v[i + j] * scale[j]
        n = [v[i + 5 + j] / scale[j] for j in range(3)]
        length = math.sqrt(sum(x * x for x in n)) or 1.0
        out[i + 5:i + 8] = [x / length for x in n]
    return out


def build_atlas(config, recipe):
    size = config['texture']['size']
    atlas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    atlas.paste(Image.open(LIB / f"textures/{recipe['base']}/base.png").convert('RGBA'), (0, 0))
    regions = config['uv_regions']
    colors = {k: tuple(v) for k, v in config['part_colors'].items()}
    struts = len(recipe['wing']['fingers'])
    draw_cap(atlas, regions['cap'], colors)
    draw_wing(atlas, regions['wing_left'], -1, colors, struts)
    draw_wing(atlas, regions['wing_right'], 1, colors, struts)
    recolored = recolor_hair(atlas, config)
    # The base face art is superseded by this variant's larger features, so its
    # old blocks are blanked rather than left to bleed through.
    face_spec = recipe.get('face')
    cleared = clear_regions(atlas, face_spec['clear_base_uv']) if face_spec else 0
    return atlas, recolored, cleared


def draw_biting_mouth(atlas, mouth_element, palette):
    """Author the variant's open mouth straight into the mouth's UV block.

    The preset mouth is a small closed smile; hanging tusks read better with an
    open, toothy mouth, so this expression owns its own pixels: a pale tooth row
    along the top, a dark cavity, and a tongue only along the lower lip.
    """
    u0, v0, u1, v1 = map(int, mouth_element['faces']['north']['uv'])
    w, h = u1 - u0, v1 - v0
    px = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(px)
    d.rectangle([0, 0, w - 1, 1], fill=palette['tooth'])
    d.rectangle([0, 0, w - 1, 0], fill=palette['tooth_edge'])
    d.rectangle([1, 2, w - 2, h - 3], fill=palette['outline'])
    d.rectangle([2, 3, w - 3, h - 4], fill=palette['cavity'])
    for cx in (w // 4, (w * 3) // 4):
        d.rectangle([cx, 2, cx, 3], fill=palette['tooth'])
    d.rectangle([2, h - 6, w - 3, h - 4], fill=palette['tongue'])
    atlas.paste(px, (u0, v0))


def biting_mouth_patch(elements, index, geom, size):
    """Vertices for an opened mouth: the preset mouth scaled about its centre.

    Returned as an expression patch rather than baked into the rest pose, so
    the neutral face keeps its normal mouth and only `biting` opens up.
    """
    e = elements[index]
    cx = (e['from'][0] + e['to'][0]) / 2
    cy = (e['from'][1] + e['to'][1]) / 2
    opened = copy.deepcopy(e)
    for key in ('from', 'to'):
        p = e[key]
        opened[key] = [cx + (p[0] - cx) * geom['scale_x'],
                       cy + (p[1] - cy) * geom['scale_y'] + geom['raise'],
                       p[2]]
    return dict(index=index, vertices=vertices(opened, size))


def export(name, config, recipe):
    bb, data = source(recipe['base'])
    base_png = LIB / f"textures/{recipe['base']}/base.png"
    size = config['texture']['size']
    body = recipe['body_scale']
    regions = config['uv_regions']
    atlas, recolored, face_cleared = build_atlas(config, recipe)

    # Swap the base's witch hat for this draft's mob cap: drop the hat cubes,
    # then author replacements onto the same bone so head-follow still applies.
    drop = set(recipe.get('remove_elements', []))
    keep_idx = [i for i, e in enumerate(bb['elements']) if e['name'] not in drop]
    elements = [scale_element(bb['elements'][i], body) for i in keep_idx]
    groups = [data['model']['rig']['groups'][i] for i in keep_idx]
    base_names = {bb['elements'][i]['name'] for i in keep_idx}
    by_name = {e['name']: e for e in elements}
    extra = cap_parts(recipe, regions)
    extra += wing_parts(recipe, regions)
    # No tusks: reference R1/R3 show none, so the mouth stays the base's own.
    elements += extra
    groups += [e['bone'] for e in extra]
    refit_bow(elements, groups, recipe['cap']['bow_fit'])
    face_parts = retarget_face(elements, recipe['face'], body, regions) if recipe.get('face') else []

    rig = copy.deepcopy(data['model']['rig'])
    rig['groups'] = groups
    for bone in rig['bones']:
        bone['pivot'] = [bone['pivot'][i] * body[i] for i in range(3)]
    existing = {b['name'] for b in rig['bones']}
    for bone in wing_bones(recipe):
        if bone['name'] not in existing:
            rig['bones'].append(copy.deepcopy(bone))
            existing.add(bone['name'])

    keep = ['idle', 'waddle', 'turn', 'puff', 'startle', 'hop', 'brace']
    rig['clips'] = {k: v for k, v in rig['clips'].items() if k in keep or k.startswith('anatomy_')}
    for clip in rig['clips'].values():
        for channels in clip['tracks'].values():
            if 'position' in channels:
                channels['position'] = {t: [v[i] * body[i] for i in range(3)] for t, v in channels['position'].items()}
    sway = copy.deepcopy(rig['clips']['idle'])
    sway.update(id='wing_sway', label='振翅', duration=1.2, loop=True, tracks={})
    # Derive the chain from the recipe rather than naming bones, so the wing can
    # change finger count without silently losing its animation channels.
    wing_names = [f['bone'] for f in recipe['wing']['fingers']]
    for i, bone in enumerate(wing_names):
        amp = 4.0 + 3.0 * i
        phase = 0.08 * i
        keys = {str(round(step * 0.2, 2)): round(amp * math.sin(2 * math.pi * (step * 0.2 / 1.2 + phase)), 3)
                for step in range(7)}
        sway['tracks'][f'{bone}_left'] = {'rotation': {t: [0, 0, -v] for t, v in keys.items()}}
        sway['tracks'][f'{bone}_right'] = {'rotation': {t: [0, 0, v] for t, v in keys.items()}}
    rig['clips']['wing_sway'] = sway
    # Folded rest pose: R3 shows the wings tucked along the flanks, so this clip
    # rotates every finger chain down and back. Children inherit, so the whole
    # fan closes together; the left side mirrors the sign.
    fold = recipe['wing'].get('fold')
    if fold:
        tucked = copy.deepcopy(rig['clips']['idle'])
        tucked.update(id='wing_fold', label='收翼', duration=1.0, loop=False, tracks={})
        for bone in wing_names:
            deg = fold  # children inherit the chain rotation
            for side, sign in (('left', 1), ('right', -1)):
                tucked['tracks'][f'{bone}_{side}'] = {
                    'rotation': {'0': [0, 0, 0], '1.0': [0, 0, sign * deg]}}
        rig['clips']['wing_fold'] = tucked
    rig['stride_units'] *= (body[0] + body[2]) / 2

    data['textureSize'] = size
    # Expression patches address the base element list by index, so translate
    # them through the hat-removal mapping before the new parts are appended.
    old_to_new = {old: new for new, old in enumerate(keep_idx)}
    remapped = {}
    for key, items in data['model']['expressionPatches'].items():
        kept = [dict(index=old_to_new[p['index']], vertices=scale_vertices(p['vertices'], body))
                for p in items if p['index'] in old_to_new]
        if kept:
            remapped[key] = kept
    dropped_patches = sorted({bb['elements'][p['index']]['name']
                              for items in data['model']['expressionPatches'].values()
                              for p in items if p['index'] not in old_to_new})
    if dropped_patches:
        raise AssertionError(f'removed elements still carry expression patches: {dropped_patches}')
    data['model'].update(vertices=[v for e in elements for v in vertices(e, size)],
                         expressionPatches=remapped)
    data['model']['rig'] = rig
    data['motions']['wing_sway'] = dict(id='wing_sway', label='振翅', kind='variant',
                                        duration=1.2, dependencies=[])
    if 'wing_fold' in rig['clips']:
        data['motions']['wing_fold'] = dict(id='wing_fold', label='收翼', kind='variant',
                                            duration=1.0, dependencies=[])
    data.update(character=name, label=recipe['label'],
                preview=dict(span=recipe['preview']['span'], centerY=recipe['preview']['centerY'],
                             default_motion='idle', default_expression='neutral'))
    data['physical'] = dict(units_per_meter=16, world_scale=recipe['world_scale'])
    for part in data.get('anatomy', {}).get('parts', []):
        part['offset'] = [part['offset'][i] * body[i] for i in range(3)]

    out = HERE / 'assembled' / name
    out.mkdir(parents=True, exist_ok=True)
    (out / 'textures').mkdir(exist_ok=True)
    uid = lambda key: str(uuid.uuid5(uuid.NAMESPACE_URL, 'yukkuri-predator/' + name + '/' + key))

    for expression in list(data['textures']):
        patch = atlas.copy()
        preset_path = LIB / 'expressions/presets' / f'{expression}.json'
        preset = read(preset_path) if preset_path.exists() else {'parts': {}}
        for e in elements:
            ref = preset['parts'].get(e['name'])
            if not ref:
                continue
            u, v, U, V = map(round, e['faces']['north']['uv'])
            im = Image.open(LIB / ref).convert('RGBA').resize((U - u, V - v), Image.Resampling.NEAREST)
            patch.paste(im, (u, v))
        patch.save(out / 'textures' / f'{expression}.png')
        buf = io.BytesIO(); patch.save(buf, format='PNG')
        data['textures'][expression] = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()

    default_texture = data['textures'][recipe['preview'].get('default_expression', 'neutral')]
    (out / f'{name}.png').write_bytes(base64.b64decode(default_texture.split(',', 1)[1]))

    nodes = {b['name']: dict(name=b['name'], uuid=uid('bone/' + b['name']), origin=b['pivot'], children=[])
             for b in rig['bones']}
    for e, g in zip(elements, groups):
        e['uuid'] = uid(e['name'])
        nodes[g]['children'].append(e['uuid'])
    for b in rig['bones']:
        if b['parent']:
            nodes[b['parent']]['children'].append(nodes[b['name']])
    bb = copy.deepcopy(bb)
    bb.update(name=recipe['label'] + ' · design draft', elements=elements,
              outliner=[nodes[b['name']] for b in rig['bones'] if b['parent'] is None], animations=[])
    bb['textures'][0].update(name=name + '.png', path=name + '.png', source=default_texture)
    save(out / f'{name}.bbmodel', bb)

    exports = {}
    for label, clip in rig['clips'].items():
        animators, channels = {}, {}
        for n, chs in clip['tracks'].items():
            kfs = []; channels[n] = {}
            for ch, keys in chs.items():
                channels[n][ch] = {}
                for t, v in keys.items():
                    val = [-v[0], -v[1], v[2]] if ch == 'rotation' else [-v[0], v[1], v[2]] if ch == 'position' else v
                    channels[n][ch][t] = val
                    kfs.append(dict(channel=ch, time=float(t), interpolation='linear',
                                    data_points=[dict(zip('xyz', [format(x, '.8f') for x in val]))],
                                    uuid=uid(label + n + ch + t)))
            animators[uid('bone/' + n)] = dict(name=n, type='bone', keyframes=kfs)
        an = 'animation.' + name + '.' + label
        bb['animations'].append(dict(name=an, uuid=uid(an), loop='loop' if clip['loop'] else 'once',
                                     length=clip['duration'], snapping=100, animators=animators))
        exports[an] = dict(loop=clip['loop'], animation_length=clip['duration'], bones=channels)
    save(out / f'{name}.animation.json', dict(format_version='1.8.0', animations=exports))
    save(out / 'animation-rig.json', rig)

    geometry = []
    for b in rig['bones']:
        g = dict(name=b['name'], pivot=b['pivot'], cubes=[])
        if b['parent']:
            g['parent'] = b['parent']
        for e, n in zip(elements, groups):
            if n != b['name']:
                continue
            if e.get('type')=='mesh' and 'vertices' in e and all('vertices' in f for f in e.get('faces',{}).values()):
                g.setdefault('_meshes',[]).append(e);continue
            c = dict(origin=e['from'], size=[y - x for x, y in zip(e['from'], e['to'])],
                     uv={k: dict(uv=f['uv'][:2], uv_size=[f['uv'][2] - f['uv'][0], f['uv'][3] - f['uv'][1]])
                         for k, f in e['faces'].items()})
            if any(e.get('rotation', [0, 0, 0])):
                c.update(pivot=e['origin'], rotation=e['rotation'])
            g['cubes'].append(c)
        meshes=g.pop('_meshes',None)
        if meshes:g['poly_mesh']=poly_mesh(merge_meshes(meshes))
        geometry.append(g)
    save(out / f'{name}.geo.json', {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': dict(identifier='geometry.' + name, texture_width=size, texture_height=size,
                            visible_bounds_width=24, visible_bounds_height=20, visible_bounds_offset=[0, 8, 0]),
        'bones': geometry}]})

    runtime = 'const DATA=' + json.dumps(data, ensure_ascii=False, separators=(',', ':')) + ';\n'
    runtime += '\n'.join((LIB / 'runtime' / f).read_text(encoding='utf-8-sig')
                         for f in ['motion.js', 'render.js', 'anatomy.js', 'anatomy-controls.js'])
    template = (LIB / 'runtime/viewer.html').read_text(encoding='utf-8-sig')
    template = template.replace("expression='neutral'", "expression=DATA.preview.default_expression||'neutral'")
    # This draft has no braid bones; repoint the stock hand buttons at the wing chain.
    template = (template
                .replace('<button id="hand-left">抬左辫</button><button id="hand-right">抬右辫</button>',
                         '<button id="wing-left">展左翼</button><button id="wing-right">展右翼</button>')
                .replace("o['hand_'+side]", "o['wing_a_'+side]")
                .replace("side==='left'?-35:35", "side==='left'?-16:16"))
    template = template.replace('双角色对比', '同尺度对照').replace('素材登记', '捕食种设计稿')
    (out / 'index.html').write_text(
        template.replace('__RUNTIME__', runtime).replace('__CHARACTER_LABEL__', recipe['label'])
                .replace('__MODEL_FILE__', name + '.bbmodel'), encoding='utf-8')

    return dict(base=recipe['base'], cube_count=len(elements), bones=len(rig['bones']),
                source_sha256=hashlib.sha256((LIB / 'assembled' / recipe['base'] / f"{recipe['base']}.bbmodel").read_bytes()).hexdigest(),
                base_scale=recipe['scale'], world_scale=recipe['world_scale'], units_per_meter=16,
                shape=recipe['shape'], texture_size=size, hair_pixels_recolored=recolored,
                face_parts_retargeted=len(face_parts), face_pixels_cleared=face_cleared,
                wing_cubes=sum(1 for g in groups if g.startswith('wing_')),
                fang_cubes=sum(1 for g in groups if g.startswith('fang_')),
                cap_cubes=sum(1 for g in groups if g.startswith('cap_')),
                removed_elements=len(drop), status='design_draft')


if __name__ == '__main__':
    config = read(HERE / 'recipes.json')
    manifest = {}
    for name, recipe in config['variants'].items():
        manifest[name] = export(name, config, recipe)
        m = manifest[name]
        print(name, m['cube_count'], 'cubes', m['bones'], 'bones',
              'hair_px', m['hair_pixels_recolored'], flush=True)
    save(HERE / 'manifest.json', manifest)
    save(HERE / 'physical-scale.json', config['physical_scale'])
