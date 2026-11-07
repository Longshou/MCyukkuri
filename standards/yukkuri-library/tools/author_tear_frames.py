"""Tear-flow B frames: each two-frame tear face's B keeps its A face and shows a tear that has spilled and runs down.

A is the welling hold (its own pool, unchanged). B is drawn as the same pool with a short trail spilling from the
pool's lowest row and a separate drop further down the cheek, so A -> B reads as a tear running and B -> A as the
next one welling. `cry` (A already streams) breaks its streak into a trail and a drop. The drop uses the pool's own
colours (outline / fill / glint); faint wet-line pools borrow the approved teal tear colours of `squeezed_teary`.

B keeps its existing tear quad (geometry is unchanged, so no model, wound-surface or patch re-export is needed): A's
pool is placed on B's quad, each B texel taking the strongest pool texel whose centre falls in its footprint, so the
pool stays where A shows it; the trail, gap and drop are then stamped on B's own texels above the chin limit.
Project design; replaces the earlier pale-smear B frames. Phase durations live in expressions/runtime.json.

    python standards/yukkuri-library/tools/author_tear_frames.py [--check]
"""
from pathlib import Path
import argparse, hashlib, io, json
from PIL import Image

LIB = Path(__file__).resolve().parents[1]
SLOTS = ('face_tear_left', 'face_tear_right')
SIZE = (15, 16)  # tear tile texels; face.json tear element is 3.75 x 4.24 units
UNIT = (3.75/15, 4.24/16)  # face units per texel
TEAL = ((151, 186, 199, 255), (190, 224, 228, 255), (255, 248, 233, 255))  # squeezed_teary pool: outline, fill, glint
# tongue_cry and side_teary keep their approved B frames: those already run a long streak and a falling drop.
PAIRS = ('cry', 'teary', 'teary_smile', 'tear_sleep', 'squeezed_teary', 'squeezed_frown', 'pin_cry', 'blank_cry',
         'teary_pant', 'teary_tongue', 'horrified', 'teary_frown', 'open_cry', 'cold', 'dying', 'dying_closed')
# Sprites are 4 texels wide; O outline, F fill, H glint. Two-tone pools (no outline) get a narrower trail and a
# plain round drop. The trail fills whatever room the pool leaves above the one-row gap and the drop. A B quad whose
# texels are stretched tall (old pale-smear frames kept their 1.7x geometry) uses the two-row drop so the drop stays
# round on the face.
OUTLINED = dict(trail=lambda n: ['OFFO'] + ['OHFO']*(n-2) + ['.OO.'][:n-1], drop=('OFFO', 'OFHO', '.OO.'),
                tall=('OFHO', '.OO.'))
TWO_TONE = dict(trail=lambda n: ['.FF.'] + ['.HF.']*(n-2) + ['..F.'][:n-1], drop=('.FF.', 'FHFF', 'FFFF', '.FF.'),
                tall=('FHFF', '.FF.'))
TALL = 1.5  # B texel height (in A texels) from which the two-row drop is used
# Faces whose own tear is a wide pool or a faint wet line get a spill sized and coloured like that tear, so A -> B
# reads as the same tear moving (user 2026-11-08): `sag` lets the wide pool's bottom open and hang as a drop as wide
# as the pool's lower edge; `thin` runs a two-texel trail and drop in the wet line's own colours.
MATCHED = {'squeezed_teary': 'sag', 'blank_cry': 'sag', 'teary_tongue': 'thin'}
THIN = dict(trail=TWO_TONE['trail'], drop=('.FF.', '.HF.', '.FF.'))
# Lowest unshifted tile row a drop may reach: lower rows sit past the rounded face front and float below the chin.
BOTTOM = 12


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def palette(cells):
    """Outline, fill and glint from the pool's own colours; faint wet lines take the teal tear colours."""
    colours = sorted(set(cells.values()), key=lambda c: sum(c[:3]))
    if all(c[3] < 255 for c in colours):
        return TEAL
    if len(colours) == 2:
        return None, colours[0], colours[1]
    return colours[0], colours[len(colours)//2], colours[-1]


def transform(offsets):
    """Tile texel coordinate -> unshifted tile coordinate for a geometry offset (scale about the tile centre)."""
    scale = offsets.get('scale', [1, 1, 1]); position = offsets.get('position', [0, 0, 0])
    centre = (SIZE[0]/2, SIZE[1]/2)
    return (lambda x: centre[0] + (x-centre[0])*scale[0], scale[0], position[0]), \
           (lambda y: centre[1] + (y-centre[1])*scale[1] - position[1]/UNIT[1], scale[1])


def flow(a, b, slot, cry, style=None):
    """A's pool placed on B's quad, then the spilled trail, a one-row gap and the drop stamped on B's own texels."""
    a_offsets = a.get('geometry_offsets', {}).get(slot, {}); b_offsets = b.get('geometry_offsets', {}).get(slot, {})
    (ax, asx, apx), (ay, asy) = transform(a_offsets)
    (bx, bsx, bpx), (by, bsy) = transform(b_offsets)
    assert apx == bpx and asx <= bsx and asy <= bsy, (a_offsets, b_offsets)
    source = Image.open(LIB/a['parts'][slot]).convert('RGBA')
    cells = {(x, y): source.getpixel((x, y)) for x in range(SIZE[0]) for y in range(SIZE[1]) if source.getpixel((x, y))[3]}
    if cry:  # A's streak below the pool is redrawn as a trail and a drop
        cells = {k: v for k, v in cells.items() if k[1] <= 6}
    colours = palette(cells)
    out, fill, glint = colours
    rank = {c: i for i, c in enumerate(colours) if c}
    im = Image.new('RGBA', SIZE)
    for c in range(SIZE[0]):  # each B texel takes the strongest pool texel whose centre falls in its footprint
        for r in range(SIZE[1]):
            found = [v for (x, y), v in cells.items() if bx(c) <= ax(x+.5) < bx(c+1) and by(r) <= ay(y+.5) < by(r+1)]
            if found:
                im.putpixel((c, r), max(found, key=lambda v: (rank.get(v, -1), v[3])))
    bottom = max(r for r in range(SIZE[1]) if any(im.getpixel((c, r))[3] for c in range(SIZE[0])))
    lowest = max(r for r in range(SIZE[1]) if by(r+1) <= BOTTOM+1)
    if style == 'sag':  # the pool's lower outline opens; the tear hangs below it, as wide as that edge
        assert bsy == asy and bsx == asx, (a['id'], slot)
        edge = [c for c in range(SIZE[0]) if im.getpixel((c, bottom))[3]]
        x0, width = edge[0], len(edge)
        rows = ['O'+'F'*(width-2)+'O']*(lowest-bottom-1) + ['.O'+'F'*(width-4)+'O.', '..'+'O'*(width-4)+'..']
        rows[1] = 'OH'+rows[1][2:]
        for dy, row in enumerate(rows):
            for dx, key in enumerate(row):
                if key != '.':
                    im.putpixel((x0+dx, bottom+dy), {'O': out, 'F': fill, 'H': glint}[key])
        name = 'expressions/parts/' + hashlib.sha256(im.tobytes()).hexdigest()[:20] + '.png'
        buf = io.BytesIO(); im.save(buf, format='PNG')
        return name, buf.getvalue()
    if style == 'thin':  # the wet line's own translucent colours: its middle tone and its glint
        line = sorted(set(cells.values()), key=lambda c: c[3])
        out, fill, glint = None, line[1], line[-1]
    shape = THIN if style == 'thin' else TWO_TONE if out is None else OUTLINED
    drop = shape['tall'] if bsy/asy >= TALL else shape['drop']
    trail = lowest - len(drop) - (bottom+1)
    assert trail >= 0, (a['id'], slot, bottom, lowest)
    centre = (min(x for x, _ in cells) + max(x for x, _ in cells) + 1)/2  # A texel units
    left = round(SIZE[0]/2 + (ax(centre) - SIZE[0]/2)/bsx - 2)
    for dy, row in enumerate((shape['trail'](trail) if trail else []) + ['....'] + list(drop)):
        for dx, key in enumerate(row):
            if key != '.':
                im.putpixel((left+dx, bottom+1+dy), {'O': out, 'F': fill, 'H': glint}[key])
    name = 'expressions/parts/' + hashlib.sha256(im.tobytes()).hexdigest()[:20] + '.png'
    buf = io.BytesIO(); im.save(buf, format='PNG')
    return name, buf.getvalue()


def outputs():
    index = read(LIB/'expressions/index.json')
    animations = read(LIB/'expressions/runtime.json')['animations']
    files = {}
    registry_path = LIB/'expressions/parts/index.json'; registry = read(registry_path)
    for ident in PAIRS:
        a_id, b_id = animations[ident]['frames']
        a = read(LIB/index['presets'][a_id]); b_path = LIB/index['presets'][b_id]; b = read(b_path)
        for slot in SLOTS:
            name, data = flow(a, b, slot, ident == 'cry', MATCHED.get(ident))
            files[LIB/name] = data
            registry[Path(name).stem] = dict(path=name, size=list(SIZE), sha256=hashlib.sha256(data).hexdigest())
            b['parts'][slot] = name
            if 'donors' in b['recipe']:
                b['recipe']['donors'][slot] = a['review_code']
        b['recipe']['tears'] = f"tear flow from {a['review_code']} pool (tools/author_tear_frames.py)"
        files[b_path] = json.dumps(b, ensure_ascii=False, indent=2).encode()
    files[registry_path] = json.dumps(registry, indent=2).encode()
    return files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    files = outputs()
    for path, data in files.items():
        if args.check:
            assert path.is_file() and path.read_bytes() == data, 'Outdated tear frame: '+str(path)
        else:
            path.write_bytes(data)
    print(f'{len(PAIRS)} tear-flow B frames '+('verified' if args.check else 'written'))
