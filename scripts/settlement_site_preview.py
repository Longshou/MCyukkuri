"""Previews and geometry checks of wild settlement sites, built by the mod's own planner and builder (settlement/site).

Compiles the mod sources and the small harness ``settlement_site_preview/SiteDump.java`` with the local JDK (no Gradle, no
game), lets the real ``SitePlanner``/``SiteBuilder`` lay out and build every generated country on synthetic ground in
memory, then checks every burrow with a mirror of ``NaturalShelter.survey`` and walks from the plaza to every door and to
the waste pit. Renders an isometric view and a plan per case into ``.local/previews/wild-settlements/``.

Usage: python settlement_site_preview.py [--seeds N] [--no-render]
  Terrain "hill" is a slope with a wavy foot five blocks north of the origin (generated sites use ``SitePlanner.choose``);
  "flat" is a command placement on level ground, where banks are heaped up. Needs the JDK in .local/tools/java, the
  NeoForge merged jar in build/moddev/artifacts and the Gradle cache in .local/gradle-home of the main checkout.
"""
import argparse
import json
import os
import re
import subprocess
import sys
from collections import deque
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent


def main_checkout():
    common = subprocess.run(['git', 'rev-parse', '--git-common-dir'], cwd=REPO, capture_output=True, text=True).stdout.strip()
    return (REPO / common).resolve().parent if common else REPO


MAIN = main_checkout()
WORK = REPO / '.local/settlement-preview'
OUT = REPO / '.local/previews/wild-settlements'

# Countries that generate sites (#mcyukkuri:settlement_sites), with the terrain their preview stands on.
CASES = [('FOREST', 'hill'), ('BIRCH', 'hill'), ('FLOWER', 'hill'), ('TAIGA', 'hill'), ('PLAINS', 'hill'), ('CHERRY', 'hill'),
         ('MEADOW', 'hill'), ('HILLS', 'rock'), ('SAVANNA', 'hill')]


def source_digest():
    """Fingerprint of everything a preview depends on (planner, builder, profiles, harness, this script)."""
    import hashlib
    digest = hashlib.sha256()
    for path in sorted((REPO / 'src/main/java/io/github/longshou/mcyukkuri/settlement/site').glob('*.java')) + [HERE / 'settlement_site_preview/SiteDump.java', Path(__file__)]:
        digest.update(path.read_bytes().replace(b'\r\n', b'\n'))
    return digest.hexdigest()


# The images the preview hub publishes (scripts/build_preview_hub.py).
HUB_MEDIA = ('forest_village_hill', 'taiga_band_hill', 'cherry_band_hill', 'hills_band_rock', 'savanna_band_hill', 'forest_band_flat')


def tool(name):
    for jdk in sorted((MAIN / '.local/tools/java').glob('*')):
        exe = jdk / 'bin' / (name + '.exe')
        if exe.exists():
            return str(exe)
    sys.exit('no JDK in .local/tools/java')


def classpath():
    root = MAIN / '.local/gradle-home/caches/modules-2/files-2.1'

    def vkey(v):
        return [(0, int(x)) if x.isdigit() else (1, x) for x in re.split(r'[.\-]', v)]
    jars = [str(MAIN / 'build/moddev/artifacts/neoforge-21.1.250-merged.jar')]
    for group in sorted(os.listdir(root)):
        if group.startswith('org.gradle'):
            continue
        for artifact in sorted(os.listdir(root / group)):
            if artifact == 'neoforge':
                continue
            for version in sorted(os.listdir(root / group / artifact), key=vkey, reverse=True):
                found = [str(p) for p in (root / group / artifact / version).glob('*/*.jar') if 'sources' not in p.name and 'javadoc' not in p.name]
                if found:
                    jars += found
                    break
    return jars


def compile_all():
    classes = WORK / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    sources = [str(p) for p in (REPO / 'src/main/java').rglob('*.java')] + [str(HERE / 'settlement_site_preview/SiteDump.java')]
    (WORK / 'sources.txt').write_text('\n'.join(s.replace('\\', '/') for s in sources), encoding='utf-8')
    cp = ';'.join(classpath())
    (WORK / 'cp.txt').write_text('-cp\n"' + cp.replace('\\', '/') + '"\n', encoding='utf-8')
    run = subprocess.run([tool('javac'), '-proc:none', '-nowarn', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(WORK / 'cp.txt'),
                          '@' + str(WORK / 'sources.txt')], capture_output=True, text=True)
    if run.returncode:
        sys.exit(run.stdout + run.stderr)
    (WORK / 'run.txt').write_text('-cp\n"' + (str(classes) + ';' + cp).replace('\\', '/') + '"\n', encoding='utf-8')


def dump(tier, seed, profile, terrain, mode):
    run = subprocess.run([tool('java'), '@' + str(WORK / 'run.txt'), 'SiteDump', tier, str(seed), profile, terrain, mode],
                         capture_output=True, text=True, encoding='utf-8')
    lines = [json.loads(l) for l in run.stdout.splitlines() if l.startswith('{')]
    if not lines:
        sys.exit(run.stderr[-3000:])
    if 'fail' in lines[0]:
        return None, lines[0]['fail']
    head, slots, blocks = lines[0], [l for l in lines if 'slot' in l], {}
    for l in lines:
        if 'b' in l:
            blocks[(l['x'], l['y'], l['z'])] = l['b']
    head['slots'] = slots
    return head, blocks


# ---- mirror of NaturalShelter (earth, stand, roof, enclosed, survey)
DIRT = {'dirt', 'coarse_dirt', 'rooted_dirt', 'podzol', 'grass_block', 'mycelium', 'moss_block', 'mud', 'muddy_mangrove_roots'}
STONE = {'stone', 'granite', 'diorite', 'andesite', 'tuff', 'deepslate'}
PASS = {'air', 'short_grass', 'fern', 'dead_bush', 'pink_petals', 'snow', 'dandelion', 'poppy', 'cornflower', 'oxeye_daisy', 'allium',
        'azure_bluet', 'lily_of_the_valley', 'blue_orchid', 'sweet_berry_bush', 'lily_pad'}
NOT_STURDY = PASS | {'water', 'dirt_path', 'forage_pile'}
RADIUS, MAX_CELLS = 5, 20
DIRS = {'north': (0, -1), 'east': (1, 0), 'south': (0, 1), 'west': (-1, 0)}


def earth(b, p):
    s = b.get(p, 'air')
    return s in DIRT or s in STONE or s.endswith('_log') or s.endswith('_wood')


def stand(b, p):
    x, y, z = p
    return b.get(p, 'air') in PASS - {'lily_pad'} and b.get((x, y - 1, z), 'air') not in NOT_STURDY


def roof(b, p):
    return any(earth(b, (p[0], p[1] + k, p[2])) for k in (1, 2, 3))


def enclosed(b, p):
    if not stand(b, p):
        return False
    banks = 0
    for dx, dz in DIRS.values():
        if any(earth(b, (p[0] + dx * d, p[1], p[2] + dz * d)) for d in (1, 2, 3)):
            banks += 1
    return banks >= 2 if roof(b, p) else banks >= 3


def survey(b, center, facing):
    if not enclosed(b, center):
        return None
    f = DIRS[facing]
    entrance = (center[0] + f[0], center[1], center[2] + f[1])
    if not stand(b, entrance) or enclosed(b, entrance):
        entrance = (entrance[0], entrance[1] + 1, entrance[2])
        if roof(b, center) or not stand(b, entrance) or enclosed(b, entrance):
            return None
    queue, seen, cells = deque([center]), {center}, []
    while queue and len(cells) < MAX_CELLS:
        p = queue.popleft()
        if not enclosed(b, p):
            continue
        cells.append(p)
        for dx, dz in DIRS.values():
            n = (p[0] + dx, p[1], p[2] + dz)
            if abs(n[0] - center[0]) <= RADIUS and abs(n[2] - center[2]) <= RADIUS and n not in seen:
                seen.add(n)
                queue.append(n)
    return cells or None


def walk(b, head):
    """SiteBuilder.validate's walk: from the plaza over cells with two free blocks, up two or down three per step."""
    def walkable(p):
        x, y, z = p
        below = b.get((x, y - 1, z), 'air')
        return b.get(p, 'air') in PASS and b.get((x, y + 1, z), 'air') in PASS and below != 'water' and (below == 'dirt_path' or below not in NOT_STURDY)
    start = next(s for s in head['slots'] if s['slot'] == 'PLAZA')
    start = (start['x'], start['y'], start['z'])
    seen, queue = {start}, deque([start])
    while queue and len(seen) < 8000:
        x, y, z = queue.popleft()
        for dx, dz in DIRS.values():
            for dy in (2, 1, 0, -1, -2, -3):
                n = (x + dx, y + dy, z + dz)
                if abs(n[0] - head['ox']) > head['bound'] or abs(n[2] - head['oz']) > head['bound']:
                    continue
                if walkable(n) and n not in seen:
                    seen.add(n)
                    queue.append(n)
                    break
    return seen


def check(head, b):
    problems, dug, burrows = [], 0, 0
    seen = walk(b, head)
    for s in head['slots']:
        anchor = (s['x'], s['y'], s['z'])
        if s['slot'] in ('HOME', 'HALL'):
            cells = survey(b, anchor, s['facing'])
            if cells is None:
                problems.append(f"{s['slot']} {anchor} fails the survey")
            elif len(cells) < min(s['cap'], 9):
                problems.append(f"{s['slot']} {anchor} has {len(cells)} beds")
            burrows += 1
            if earth(b, (anchor[0], anchor[1] + 3, anchor[2])):
                dug += 1
        if s['slot'] in ('HOME', 'HALL', 'GRANARY'):
            f = DIRS[s['facing']]
            door = anchor if s['slot'] == 'GRANARY' else (anchor[0] + f[0], anchor[1], anchor[2] + f[1])
            if door not in seen:
                problems.append(f"{s['slot']} door {door} unreachable")
    pit = [(s['x'], s['y'], s['z']) for s in head['slots'] if s['slot'] == 'PIT']
    if pit and not any(p in seen for p in pit):
        problems.append('waste pit unreachable')
    return problems, dug, burrows


# ---- rendering
COLORS = {'dirt': (134, 96, 67), 'coarse_dirt': (119, 85, 59), 'rooted_dirt': (144, 104, 76), 'podzol': (91, 63, 24),
          'dirt_path': (148, 122, 65), 'gravel': (131, 127, 126), 'stone': (125, 125, 125), 'andesite': (136, 136, 137), 'tuff': (108, 109, 102),
          'mossy_cobblestone': (100, 118, 94), 'cobblestone': (120, 120, 120), 'moss_block': (89, 109, 45), 'mud': (60, 57, 61), 'clay': (160, 166, 179),
          'water': (63, 118, 228), 'dead_bush': (148, 108, 52), 'forage_pile': (200, 180, 90), 'pink_petals': (240, 170, 200), 'lily_pad': (32, 128, 48),
          'sweet_berry_bush': (60, 110, 50), 'snow': (240, 240, 250), 'granite': (150, 105, 85), 'sand': (219, 207, 163), 'sandstone': (216, 203, 155),
          'dandelion': (240, 220, 40), 'poppy': (200, 40, 40), 'cornflower': (90, 120, 220), 'oxeye_daisy': (235, 235, 225), 'allium': (180, 110, 220),
          'azure_bluet': (225, 230, 240), 'lily_of_the_valley': (240, 240, 240), 'blue_orchid': (60, 160, 230)}
# vanilla biome tints: grass (grass blocks, short grass, ferns) and foliage (oak-type leaves); spruce, birch and cherry leaves have their own
TINTS = {'FOREST': ('79C05A', '59AE30'), 'BIRCH': ('88BB67', '80A755'), 'FLOWER': ('79C05A', '59AE30'), 'TAIGA': ('86B783', '619961'),
         'PLAINS': ('91BD59', '77AB2F'), 'CHERRY': ('B6DB61', 'F2B6D2'), 'MEADOW': ('83BB6D', '63A948'), 'HILLS': ('8AB689', '619961'),
         'SAVANNA': ('BFB755', 'AEA42A')}
PLANTS = PASS | {'forage_pile'}


def palette(profile):
    grass, foliage = (tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) for h in TINTS.get(profile, TINTS['FOREST']))

    def color(name):
        if name in ('grass_block', 'short_grass', 'fern'):
            return tuple(int(c * (.82 if name == 'grass_block' else .95)) for c in grass)
        if name in COLORS:
            return COLORS[name]
        if name.endswith('_log'):
            return {'birch_log': (215, 210, 200), 'cherry_log': (60, 32, 40), 'spruce_log': (60, 44, 28), 'acacia_log': (105, 98, 90)}.get(name, (102, 81, 50))
        if name.endswith('_leaves'):
            return {'spruce_leaves': (97, 153, 97), 'birch_leaves': (128, 167, 85), 'cherry_leaves': (242, 182, 210)}.get(name, foliage)
        return (220, 200, 80)
    return color


def shade(c, k):
    return tuple(max(0, min(255, int(ch * k))) for ch in c)


def render_iso(head, b, color, span=None, center=None, s=7, labels=True):
    """Iso view from the south-east (south and east faces visible). Faces looking into a roofed cell (a burrow mouth or
    cavity) are drawn dark, so an open doorway reads as a dark hole in the bank."""
    from PIL import Image, ImageDraw
    span = span or head['bound'] + 3
    ox, oz = center if center else (head['ox'], head['oz'])
    pts = {q: n for q, n in b.items() if abs(q[0] - ox) <= span and abs(q[2] - oz) <= span and q[1] >= 60}
    top_y = max((q[1] for q in pts), default=76) + 2
    W, H = (2 * span + 2) * 2 * s + 20, (2 * span + 2) * s + (top_y - 58) * s * 1.1 + 20
    img = Image.new('RGB', (W, int(H)), (236, 238, 242))
    d = ImageDraw.Draw(img)

    def iso(x, y, z):
        u, v = x - ox, z - oz
        return W / 2 + (u - v) * s, 10 + (u + v + 2 * span) * s / 2 + (top_y - y) * s * 1.1

    def dark(x, y, z):
        return any(pts.get((x, y + k, z), 'air') not in PASS for k in (1, 2, 3))
    for q in sorted(pts, key=lambda q: (q[0] + q[2], q[1])):
        x, y, z = q
        name = pts[q]
        c = color(name)
        if name in PLANTS:
            cx, cy = iso(x + .5, y + .4, z + .5)
            d.ellipse([cx - s * .4, cy - s * .4, cx + s * .4, cy + s * .4], fill=c)
            continue
        if pts.get((x, y + 1, z), 'air') in PASS:
            d.polygon([iso(x, y + 1, z), iso(x + 1, y + 1, z), iso(x + 1, y + 1, z + 1), iso(x, y + 1, z + 1)], fill=shade(c, .45 if dark(x, y + 1, z) else 1))
        if pts.get((x, y, z + 1), 'air') in PASS:
            d.polygon([iso(x, y + 1, z + 1), iso(x + 1, y + 1, z + 1), iso(x + 1, y, z + 1), iso(x, y, z + 1)], fill=shade(c, .3 if dark(x, y, z + 1) else .72))
        if pts.get((x + 1, y, z), 'air') in PASS:
            d.polygon([iso(x + 1, y + 1, z), iso(x + 1, y + 1, z + 1), iso(x + 1, y, z + 1), iso(x + 1, y, z)], fill=shade(c, .35 if dark(x + 1, y, z) else .85))
    if labels:
        for slot in head['slots']:
            if slot['slot'] in ('HOME', 'HALL', 'GRANARY') or slot is next((t for t in head['slots'] if t['slot'] == 'PIT'), None):
                cx, cy = iso(slot['x'] + .5, slot['y'] + 3.2, slot['z'] + .5)
                label = {'HOME': '家', 'HALL': '长屋', 'GRANARY': '仓', 'PIT': '坑'}[slot['slot']]
                d.text((cx - 6, cy - 8), label, fill=(30, 30, 30), font=font(13))
    return img


def clearest_mouth(head, b):
    """The home whose mouth the south-east view sees best: fewest solid blocks above its door level in front of it."""
    best, score = None, None
    for t in head['slots']:
        if t['slot'] != 'HOME':
            continue
        f = DIRS[t['facing']]
        dx, dy, dz = t['x'] + f[0], t['y'], t['z'] + f[1]
        hidden = sum(1 for i in range(0, 6) for j in range(0, 6) for k in range(1, 7)
                     if (i or j) and b.get((dx + i, dy + k, dz + j), 'air') not in PASS)
        if score is None or hidden < score:
            best, score = t, hidden
    return best


def render(head, b, problems, path, title, profile):
    from PIL import Image, ImageDraw
    color = palette(profile)
    overview = render_iso(head, b, color)
    home = clearest_mouth(head, b)
    pit = next((t for t in head['slots'] if t['slot'] == 'PIT'), None)
    views = []
    if home:
        f = DIRS[home['facing']]
        views.append(('家洞近景（洞口朝向右下，暗处是洞口与洞内）', render_iso(head, b, color, span=5, center=(home['x'] + f[0], home['z'] + f[1]), s=20, labels=False)))
    if pit:
        views.append(('嗯嗯坑近景', render_iso(head, b, color, span=4, center=(pit['x'], pit['z']), s=20, labels=False)))
    side = max((v.width for _, v in views), default=0)
    W = overview.width + side + 30
    H = max(overview.height, sum(v.height + 26 for _, v in views)) + 64
    sheet = Image.new('RGB', (W, H), (236, 238, 242))
    d = ImageDraw.Draw(sheet)
    d.text((12, 8), title, fill=(20, 20, 20), font=font(18))
    d.text((12, 34), ('问题：' + '；'.join(problems)) if problems else '勘测：全部洞穴通过 NaturalShelter.survey，空地可走到每个洞口与嗯嗯坑',
           fill=(170, 30, 30) if problems else (30, 110, 40), font=font(13))
    sheet.paste(overview, (0, 60))
    y = 60
    for label, view in views:
        d.text((overview.width + 20, y), label, fill=(30, 30, 30), font=font(14))
        sheet.paste(view, (overview.width + 20, y + 22))
        y += view.height + 26
    sheet.save(path)


_FONT = {}


def font(size):
    from PIL import ImageFont
    if size not in _FONT:
        for name in ('msyh.ttc', 'simhei.ttf', 'arial.ttf'):
            try:
                _FONT[size] = ImageFont.truetype(name, size)
                break
            except OSError:
                continue
        else:
            _FONT[size] = ImageFont.load_default()
    return _FONT[size]


TITLES = {'FOREST': '森林', 'BIRCH': '桦木林', 'FLOWER': '繁花森林', 'TAIGA': '针叶林', 'PLAINS': '平原（坡地）', 'CHERRY': '樱花林',
          'MEADOW': '草甸', 'HILLS': '风袭丘陵（岩坡）', 'SAVANNA': '热带草原'}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--seeds', type=int, default=3, help='seeds per case for the check (the first is rendered)')
    ap.add_argument('--no-render', action='store_true')
    args = ap.parse_args()
    compile_all()
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob('*.png'):
        old.unlink()
    report, bad = [], 0
    cases = [(p, t, tier, 'strict') for p, t in CASES for tier in ('band', 'village')] + [('FOREST', 'flat', 'band', 'command'), ('FOREST', 'flat', 'village', 'command')]
    for profile, terrain, tier, mode in cases:
        base = 1000 + sum(map(ord, profile)) * 37
        rendered = False
        # the first seed that yields a site is drawn; a few extra seeds are tried only to find one to draw
        for i in range(args.seeds + 4):
            if i >= args.seeds and (rendered or args.no_render):
                break
            seed = base + i * 7919
            head, blocks = dump(tier, seed, profile, terrain, mode)
            if head is None:
                report.append(f'{profile:8} {tier:7} {terrain:4} seed {seed}: no site ({blocks})')
                continue
            problems, dug, burrows = check(head, blocks)
            bad += bool(problems)
            report.append(f"{profile:8} {tier:7} {terrain:4} seed {seed}: rot {head['rotation']} origin {head['ox']},{head['oz']} roofed {dug}/{burrows}"
                          + (' PROBLEMS ' + '; '.join(problems) if problems else ' ok'))
            if not rendered and not args.no_render:
                rendered = True
                title = f"{TITLES[profile]} · {'村落' if tier == 'village' else '小群'} · {'山脚坡地，挖进坡里' if mode == 'strict' else '平地命令放置，土岸补起'}"
                render(head, blocks, problems, OUT / f'{profile.lower()}_{tier}_{terrain}.png', title, profile)
    print('\n'.join(report))
    print(f'{bad} problem runs')
    if not args.no_render:
        (OUT / 'source.sha256').write_text(source_digest(), encoding='utf-8')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
