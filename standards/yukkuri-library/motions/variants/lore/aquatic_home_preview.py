"""Offline sheet for the aquatic hat boat as a home (ecology design D 3): the larder and the crew as seen from the bank.

Reads the authored marisa-hat-vessel/geometry.json and exported runtime data: hat_deck_layout.json (seats, conflicts,
larder exclusions, the crew pose table), hat_cargo_layout.json and hat_provisions.json (the larder), hat_frames.json
(freeboard by load), marisa_hat_turned / marisa_hat_wetline / paddle_stick and the exported clips. Seats are chosen
from the deck table with the same rules the runtime uses (classes, night, larder and stick exclusions, conflicts,
preference order). Stored items are drawn with the vanilla item sprites from the local NeoForge client resources jar
(preview only, not copied). Pillow/NumPy CPU renders; nothing launches the game. The cameras stand where a player stands on
the museum pond's bank (feet one block over the water cell, eye 1.62 higher: 30 degrees down at 3 blocks, 16 at 6),
broadside on the braid (right) and the left bank, framed as a 70-degree view at that distance.

    python standards/yukkuri-library/motions/variants/lore/aquatic_home_preview.py

Writes <MCYUKKURI_PREVIEWS or .local/previews>/aquatic-home/shore.png and index.html, and prints two checks: the young under
the captain by night, and the larder under her own posed mesh (every resting clip she shows aboard).
"""
from pathlib import Path
import argparse, io, json, math, os, subprocess, sys, zipfile
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hat_motion_preview as HM
import preview as P
import aquatic_tools_preview as T

ROOT = P.ROOT
OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT/'.local/previews'))/'aquatic-home'
JAR = ROOT/'build/moddev/artifacts/neoforge-21.1.250-client-extra-aka-minecraft-resources.jar'
if not JAR.is_file(): JAR = ROOT.parents[1]/'build/moddev/artifacts/neoforge-21.1.250-client-extra-aka-minecraft-resources.jar'
ORDER = ['newborn', 'baby', 'small_child', 'child', 'large_child', 'juvenile', 'adult', 'gravid']
CABIN = ('BOW_PERCH', 'WELL_FRONT', 'NEST_HOLLOW')
GRASS = (145, 189, 89)  # vanilla default grass item colour (GrassColor default), the short grass sprite is grey
ITEMS = {'apple': 'item/apple.png', 'sweet_berries': 'item/sweet_berries.png', 'short_grass': 'block/short_grass.png',
         'carrot': 'item/carrot.png', 'wheat': 'item/wheat.png'}
STEM = ('short_grass', 'wheat')
SMALL = ('sweet_berries',)
NAMES = {'apple': '苹果', 'sweet_berries': '甜浆果', 'short_grass': '短草', 'carrot': '胡萝卜', 'wheat': '小麦'}


def rank(c): return ORDER.index(c)


class Resources:
    """One version of the runtime data: the working tree (now) or a git revision (before)."""

    def __init__(self, rev=None):
        self.rev = rev

    def read(self, rel):
        path = (P.ASSETS/rel).resolve().relative_to(ROOT)
        if self.rev is None: return (ROOT/path).read_bytes()
        return subprocess.run(['git', '-C', str(ROOT), 'show', f'{self.rev}:{path.as_posix()}'],
                              capture_output=True, check=True).stdout

    def json(self, rel): return json.loads(self.read(rel).decode('utf-8'))


class Home:
    def __init__(self, res):
        self.res = res; self.V = res.json(os.path.relpath(ROOT/'standards/yukkuri-library/interactions/marisa-hat-vessel/geometry.json', P.ASSETS)); self.k = self.V['scale']['aquatic_marisa']
        self.deck = res.json('yukkuri_presentation/hat_deck_layout.json'); self.mode = self.deck['modes']['AQUATIC_FLOAT']
        self.frames = res.json('yukkuri_presentation/hat_frames.json')['modes']['aquatic']
        self.cargo = res.json('yukkuri_presentation/hat_cargo_layout.json')
        self.provisions = res.json('yukkuri_props/hat_provisions.json')
        self.hull_prop = res.json('yukkuri_props/marisa_hat_turned.json')
        self.wet_prop = res.json('yukkuri_props/marisa_hat_wetline.json')
        self.stick = res.json('yukkuri_props/paddle_stick.json')
        self.sq = self.V['squash']['bowl']; self.top = self.V['bowl']['freeboard']
        self.slots = {s['id']: s for s in self.mode['slots']}
        self.young = {}

    # ---------------------------------------------------------------- the runtime seat rule over the exported table
    def tier_of(self, food):
        return 0 if food <= 0 else next(t for t, m in enumerate(self.cargo['tier_max_counts']) if food <= m)

    def assign(self, bodies, food, night, owner='adult'):
        tier = self.tier_of(food); blocked = {}
        larder = self.mode.get('larder') or []
        if tier and tier < len(larder): blocked.update(larder[tier]['excludes'])
        for k, v in self.mode.get('fixtures', {}).get('paddle_stow', {}).get('excludes', {}).items():
            blocked[k] = min(v, blocked.get(k, v), key=rank)
        seat = next((s for s in self.mode['slots'] if s['kind'] == ('CAPTAIN_HIGH' if owner == 'gravid' else 'CAPTAIN')), None)
        when = 'night' if night else 'day'
        homes = sorted((s for s in self.mode['slots'] if s['kind'] in CABIN+('BRIM',)), key=lambda s: s.get('prefer', {}).get(when, 999))
        order = sorted(range(len(bodies)), key=lambda i: -rank(bodies[i])); result = [None]*len(bodies)

        def free(slot, c, placed):
            if c not in slot['classes'] or (night and not slot['night']): return False
            if slot['requires_jump'] and not self.deck['jumps'][c]: return False
            if slot['id'] in blocked and rank(c) >= rank(blocked[slot['id']]): return False
            for o, oc in placed:
                if o is slot: return False
                limit = o['conflicts'].get(oc, {}).get(slot['id'])
                if limit is not None and rank(c) >= rank(limit): return False
            return True

        def go(i, placed, last):
            if i == len(order): return True
            k = order[i]; c = bodies[k]; start = last+1 if i > 0 and bodies[order[i-1]] == c else 0
            for n in range(start, len(homes)):
                if free(homes[n], c, placed):
                    result[k] = homes[n]
                    if go(i+1, placed+[(homes[n], c)], n): return True
            return False
        return [seat]+result if go(0, [(seat, owner)], -1) else None

    def fit(self, bodies, food, night):
        """The crew that fits, dropping the last listed bodies until a legal set exists; returns (seats, dropped)."""
        for n in range(len(bodies), -1, -1):
            got = self.assign(bodies[:n], food, night)
            if got: return got, bodies[n:]
        return None, bodies

    # ---------------------------------------------------------------- meshes (model units, water plane y = 0)
    def load(self, bodies, food, owner='adult'):
        L = self.deck['load']; return sum(L[c] for c in bodies)+food*L['food']+L['paddle']+(L['gravid'] if owner == 'gravid' else 0)

    def sink(self, load):
        rows = self.frames.get('freeboard_by_load')
        if not rows: return 0.
        fb = min(rows, key=lambda r: abs(r[0]-load))[1]; return (self.top-fb)*self.k

    def hull(self, atlas, M):
        frame = M@T.affine(np.diag([self.k, self.k*self.sq, self.k]))@T.affine(t=(0, self.top/self.sq, 0))
        mesh = mesh_of(self.hull_prop, atlas, frame, 'marisa_hat_turned')
        wet = mesh_of(self.wet_prop, atlas, frame, 'marisa_hat_wetline', alpha=True)
        wet_rgb = mesh_of(self.wet_prop, atlas, frame, 'marisa_hat_wetline', opaque=True)
        a = self.stick['anchors']['stow_hull']; socket = np.array(self.stick['socket'], float)
        sframe = M@T.affine(s=self.k)@T.affine(t=(0, self.top, 0))@T.affine(P.euler(a['rotation']), a['position'], a['scale'])@T.affine(t=-socket)
        return np.concatenate([mesh, mesh_of(self.stick, atlas, sframe, 'paddle_stick')]), (wet, wet_rgb)

    def mask(self, M):
        return [np.array([(M@np.r_[p[0]*self.k, self.top*self.k+.02, p[1]*self.k, 1])[:3] for p in self.V['bowl']['mask']])]

    def larder(self, atlas, M, stock):
        """stock: [(item, count)] in stored order. New layout: leaf bed + item sprites; old layout: the heap prop units."""
        total = sum(n for _, n in stock); tier = self.tier_of(total)
        if not tier: return []
        if 'larder' not in self.cargo:  # the previous whole-heap provisions, drawn as before (prop frame, squashed)
            frame = M@T.affine(np.diag([self.k, self.k*self.sq, self.k]))@T.affine(t=(0, self.top/self.sq, 0))
            return [mesh_of(self.provisions, atlas, frame, 'hat_provisions', unit=tier)]
        L = self.cargo['larder'][tier]; out = []
        frame = M@T.affine(s=self.k)@T.affine(t=(0, self.top, 0))
        out.append(mesh_of(self.provisions, atlas, frame, 'hat_provisions', unit=L['bed_unit']))
        kinds = sorted(stock, key=lambda kv: -kv[1]); left = [n for _, n in kinds]
        for i, place in enumerate(L['items'][:min(total, L['max_count'])]):
            k = place['cluster'] if place['cluster'] < len(left) and left[place['cluster']] > 0 else -1
            if k < 0: k = max((j for j in range(len(left)) if left[j] > 0), key=lambda j: left[j], default=-1)
            if k < 0: break
            left[k] -= 1; name = kinds[k][0]
            size = place['size']*(.8 if name in SMALL else 1.)
            base = frame@T.affine(t=place['pos'])@T.affine(quat(place['quaternion']))@T.affine(s=size)
            if name in STEM:
                for side in (-1, 1):
                    fold = T.affine(t=(0, -.5, 0))@T.affine(rot_x(side*22))@T.affine(rot_y(side*14))@T.affine(t=(0, .5, 0))
                    out.append(sprite(atlas, base@fold, name))
            else: out.append(sprite(atlas, base, name))
        return out

    def young_actor(self):
        if 'm' not in self.young: self.young['m'] = T.Actor('marisa', hat_scale=self.deck.get('young_hat_multiplier', self.k))
        return self.young['m']

    def scene(self, crew, food_stock, night, atlas, captain_clip=None, captain_face=None, t=1.0, owner='adult'):
        """The captain, crew in their assigned seats and poses (crew pose table), larder, hull; returns meshes, wet band
        meshes, mask and the dropped bodies."""
        food = sum(n for _, n in food_stock)
        seats, dropped = self.fit(crew, food, night)
        load = self.load(crew[:len(crew)-len(dropped)], food, owner); M = T.affine(t=(0, -self.sink(load), 0))
        hull, wet = self.hull(atlas, M); meshes = [hull]+self.larder(atlas, M, food_stock)
        cap = seats[0]; poses = self.deck.get('crew', {})
        young_in_nest = any(s['kind'] in ('NEST_HOLLOW', 'WELL_FRONT') for s in seats[1:])
        if captain_clip is None:
            key = 'night_with_young' if night and young_in_nest else 'night' if night else 'day'
            row = poses.get('captain', {}).get(key) or {'clip': 'boat_cover_young' if night else 'boat_drift'}
            captain_clip, captain_face = row['clip'], captain_face or row.get('face')
        x, y, z = cap['pos']
        meshes.append(CAPTAIN.mesh(captain_clip, t, atlas, M@T.world((x*self.k, y*self.k, z*self.k), -cap['yaw']), face=captain_face))
        for c, s in zip(crew, seats[1:]):
            row = young_pose(poses, s['kind'], c, night)
            x, y, z = s['pos']; size = self.deck['classes'][c]
            meshes.append(self.young_actor().mesh(row['clip'], t+.37*len(meshes), atlas,
                                                  M@T.world((x*self.k, y*self.k, z*self.k), -s['yaw'], size), face=row.get('face')))
        return meshes, wet, self.mask(M), dropped, seats


def young_pose(poses, kind, c, night):
    for row in poses.get('young', []):
        if row['slot'] == kind and c in row['classes']: return row['night' if night else 'day']
    return {'clip': 'boat_nest_curl' if night else 'hat_cradle', 'face': 'sleep' if night else None}


def rot_x(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a)); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a)); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def quat(q):
    x, y, z, w = q
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
                     [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])


def mesh_of(data, atlas, M, name, unit=None, alpha=False, opaque=False):
    raw = np.array(data['vertices'], float).reshape(-1, 8); out = []
    for part in data['parts']:
        if unit is not None and part.get('unit') != unit: continue
        mesh = raw[part['start']:part['start']+part['count']].copy()
        texture = part.get('texture', f'mcyukkuri:textures/props/{name}.png').split(':', 1)[-1]
        image = Image.open(P.ASSETS/texture).convert('RGBA'); key = texture
        if alpha:
            a = np.array(image)[:, :, 3]; image = Image.fromarray(np.dstack([a, a, a, np.full_like(a, 255)])); key += '#alpha'
        elif opaque:
            arr = np.array(image); arr[:, :, 3] = 255; image = Image.fromarray(arr); key += '#rgb'
        mesh[:, 3:5] = atlas.uv(key, image, mesh[:, 3:5]); out.append(mesh)
    return T.transform(np.concatenate(out), M) if out else np.zeros((0, 8))


_SPRITES = {}


def sprite(atlas, M, name):
    """A vanilla item picture as a two-sided square quad (picture in x-y, front +z), alpha-tested."""
    if name not in _SPRITES:
        with zipfile.ZipFile(JAR) as jar: img = Image.open(io.BytesIO(jar.read('assets/minecraft/textures/'+ITEMS[name]))).convert('RGBA')
        if name == 'short_grass':
            arr = np.array(img).astype(float); arr[:, :, :3] *= np.array(GRASS)/255.; img = Image.fromarray(arr.astype(np.uint8))
        _SPRITES[name] = img
    q = []
    for nz, order in ((1, (0, 1, 2, 0, 2, 3)), (-1, (0, 2, 1, 0, 3, 2))):
        corners = [(-.5, -.5, 0, 0, 1), (.5, -.5, 0, 1, 1), (.5, .5, 0, 1, 0), (-.5, .5, 0, 0, 0)]
        for i in order: x, y, z, u, v = corners[i]; q.append([x, y, z*nz+.001*nz, u, v, 0, 0, nz])
    mesh = np.array(q, float); mesh[:, 3:5] = atlas.uv('mc/'+name, _SPRITES[name], mesh[:, 3:5])
    return T.transform(mesh, M)


def render(meshes, wet, mask, atlas, camera, night=False, tick=0):
    mesh = np.concatenate([m for m in meshes if len(m)]).reshape(-1, 3, 8); texture = np.array(atlas.image)
    pixels, depth, view = HM.rasterize(mesh, texture, **camera)
    if wet is not None:
        alpha, adepth, _ = HM.rasterize(wet[0].reshape(-1, 3, 8), texture, **camera)
        color, _, _ = HM.rasterize(wet[1].reshape(-1, 3, 8), texture, **camera)
        show = np.isfinite(adepth) & (adepth >= depth-.05)
        a = (alpha[..., 0].astype(float)/255)[..., None]
        pixels = np.where(show[..., None], pixels*(1-a)+color.astype(float)*a, pixels).astype(np.uint8)
    image = HM.water_layer(pixels, depth, view, 'current', tick, np.eye(3), T.VESSEL, mask)
    if night:
        arr = np.array(image).astype(float); arr = arr*np.array([.42, .48, .7])+np.array([8, 12, 30])
        image = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    return image


def cam(az, el, span, target): return T.cam(az, el, span, target)


def label(image, text, sub=None, size=(380, 260), warn=False):
    image = image.resize(size, Image.Resampling.LANCZOS); d = ImageDraw.Draw(image)
    d.rectangle((0, 0, size[0], 22), fill=(255, 236, 230) if warn else (255, 255, 255))
    d.text((5, 2), text, font=P.preview_font(14), fill='#7a2a1a' if warn else '#203e48')
    if sub:
        d.rectangle((0, size[1]-22, size[0], size[1]), fill=(255, 255, 255)); d.text((5, size[1]-20), sub, font=P.preview_font(13), fill='#4b6b74')
    return image


def sheet(path, title, notes, rows, cell=(380, 260), head=170):
    width = max(len(r[1]) for r in rows)*cell[0]+head; top = 40+22*len(notes)
    out = Image.new('RGB', (width, top+len(rows)*cell[1]), T.BG); d = ImageDraw.Draw(out)
    d.text((10, 6), title, font=P.preview_font(22), fill='#203e48')
    for i, n in enumerate(notes): d.text((10, 36+22*i), n, font=P.preview_font(14), fill='#4b6b74')
    for i, (name, cells) in enumerate(rows):
        y = top+i*cell[1]
        for j, line in enumerate(name.split('\n')): d.text((8, y+8+20*j), line, font=P.preview_font(15), fill='#203e48')
        for j, image in enumerate(cells): out.paste(image, (head+j*cell[0], y))
    out.save(path); print(path, flush=True)


def describe(crew, seats, dropped):
    names = {'newborn': '新生', 'baby': '赤油', 'small_child': '小子油', 'child': '子油', 'large_child': '大子油'}
    where = {'BOW_PERCH': '前台阶', 'NEST_HOLLOW': '深窝', 'WELL_FRONT': '二段', 'BRIM': '前帽檐'}
    got = [f"{names[c]}@{where.get(s['kind'], s['kind'])}" for c, s in zip(crew, seats[1:])]
    return '，'.join(got)+(f"；坐不下：{'、'.join(names[c] for c in dropped)}" if dropped else '')


# ------------------------------------------------------------------------------------------------- sheets
STOCK = {0: [], 1: [('apple', 2), ('sweet_berries', 1)], 2: [('apple', 3), ('sweet_berries', 2), ('short_grass', 2)],
         3: [('apple', 4), ('sweet_berries', 3), ('short_grass', 3), ('carrot', 1)]}
# A player on the museum pond's bank: feet one block over the water cell (0.11 over the surface), eye 1.62 higher.
EYE = 1.73
SHORE = [(f'{side} {d} 格', az, math.atan2(EYE, d), d) for side, az in (('右岸（辫子侧）', math.pi/2), ('左岸', -math.pi/2)) for d in (3, 6)]


CELL = (420, 290)


def shore_view(az, el, d):
    """An orthographic stand-in for a 70-degree view at distance d blocks (16 model units a block), aimed at the hull and
    cropped to the cell at the pixel size a 1080-line screen shows it."""
    return cam(az, el, 16*d*math.tan(math.radians(35))*CELL[1]/1080, (0, 2.5, -.5))


def shore_sheet(now):
    rows = []
    shots = [(f'粮 {t} 档（{sum(n for _, n in STOCK[t])} 份）\n昼 · 赤油 ×2 在前台阶', ['baby', 'baby'], t, False) for t in (0, 1, 2, 3)]
    shots += [('粮 3 档\n昼 · 子油 + 赤油', ['child', 'baby'], 3, False), ('粮 3 档\n夜 · 赤油 ×2\n船长俯身盖窝', ['baby', 'baby'], 3, True)]
    for name, crew, tier, night in shots:
        cells = []
        for vname, az, el, d in SHORE:
            atlas = HM.Atlas(); meshes, wet, mask, dropped, seats = now.scene(crew, STOCK[tier], night, atlas)
            img = render(meshes, wet, mask, atlas, shore_view(az, el, d), night=night)
            cells.append(label(img, f"{'夜' if night else '昼'} · {vname}（俯角 {math.degrees(el):.0f}°）", describe(crew, seats, dropped),
                               size=CELL, warn=bool(dropped)))
        rows.append((name, cells))
    sheet(OUT/'shore.png', '帽船即家：从岸上看粮与孩子（粮在船长肩前两侧的侧窝，左右交替放入）',
          ['相机在试玩场池塘岸上玩家眼高（脚下比水面高约 0.1 格，眼高 1.62）：3 格处俯角约 30°，6 格处约 16°；画面为 70° 视野、1080 线屏幕上的实际像素大小裁切；左右两岸各一列。',
           '粮在船长肩前两侧帽壁边的二段台阶上，左右交替放入，靠帽壁斜立、图面朝帽腔中间：近岸一侧被帽壁挡住，对岸一侧越过帽口看得见；不上帽檐、不高出帽口。离线渲染、夜色为调暗示意，非游戏实录。'],
          rows, cell=CELL)


def under_check(now):
    """By night the young in the nest hollow stay below the captain bent over them (boat_cover_young): compare the
    captain's underside with the young's tops column by column from straight below and above."""
    report = []
    for crew in (['baby', 'baby', 'baby'], ['newborn']*4, ['small_child'], ['baby', 'baby']):
        seats, dropped = now.fit(crew, 0, True); M = T.affine(t=(0, -now.sink(now.load(crew, 0)), 0))
        cap = seats[0]; x, y, z = cap['pos']
        row = now.deck.get('crew', {}).get('captain', {}).get('night_with_young', {'clip': 'boat_cover_young'})
        worst = -1e9
        for t in np.linspace(0, CAPTAIN.posed.clips[row['clip']]['duration'], 7):
            atlas = HM.Atlas()
            capm = CAPTAIN.mesh(row['clip'], t, atlas, M@T.world((x*now.k, y*now.k, z*now.k), -cap['yaw']))
            young = []
            for c, s in zip(crew, seats[1:]):
                if s['kind'] not in ('NEST_HOLLOW', 'WELL_FRONT'): continue
                yr = young_pose(now.deck.get('crew', {}), s['kind'], c, True); sx, sy, sz = s['pos']
                young.append(now.young_actor().mesh(yr['clip'], t, atlas, M@T.world((sx*now.k, sy*now.k, sz*now.k), -s['yaw'], now.deck['classes'][c])))
            if not young: continue
            tex = np.array(atlas.image); view = dict(span=14, center_y=0, center_x=0, center_z=0)
            _, below, _ = HM.rasterize(capm.reshape(-1, 3, 8), tex, az=0, el=-math.pi/2+1e-4, **view)
            _, above, _ = HM.rasterize(np.concatenate(young).reshape(-1, 3, 8), tex, az=0, el=math.pi/2-1e-4, **view)
            # seen from below the picture is flipped top to bottom; depth is -y from below and y from above
            under, top = np.flipud(-below), above
            both = np.isfinite(under) & np.isfinite(top)
            if both.any(): worst = max(worst, float((top-under)[both].max()))
        report.append((crew, worst))
    return report


def larder_check(now):
    """The larder items against the captain's own posed mesh in every resting clip she shows aboard (crew pose table and the
    agenda's base clips): from straight below her underside, from straight above the items' tops, column by column."""
    clips = ['boat_drift', 'boat_sleep', 'boat_cover_young', 'boat_steady', 'boat_eat', 'boat_paddle', 'boat_feed_young']
    report = []
    for tier in (1, 2, 3):
        cap = next(s for s in now.mode['slots'] if s['kind'] == 'CAPTAIN'); x, y, z = cap['pos']
        worst = (-1e9, '')
        for clip in clips:
            if clip not in CAPTAIN.posed.clips: continue
            for t in np.linspace(0, CAPTAIN.posed.clips[clip]['duration'], 6):
                atlas = HM.Atlas()
                capm = CAPTAIN.mesh(clip, t, atlas, T.world((x*now.k, y*now.k, z*now.k), -cap['yaw']))
                food = [m for m in now.larder(atlas, np.eye(4), STOCK[tier]) if len(m)]
                tex = np.array(atlas.image); view = dict(span=14, center_y=0, center_x=0, center_z=0)
                _, below, _ = HM.rasterize(capm.reshape(-1, 3, 8), tex, az=0, el=-math.pi/2+1e-4, **view)
                _, above, _ = HM.rasterize(np.concatenate(food).reshape(-1, 3, 8), tex, az=0, el=math.pi/2-1e-4, **view)
                under, top = np.flipud(-below), above
                both = np.isfinite(under) & np.isfinite(top)
                if both.any():
                    v = float((top-under)[both].max())
                    if v > worst[0]: worst = (v, f'{clip}@{t:.1f}s')
        report.append((tier, worst))
    return report


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    CAPTAIN = T.Actor('marisa', aquatic=True)
    now = Home(Resources())
    for crew, worst in under_check(now): print('under-captain check', crew, 'young top minus captain underside, worst column (model units, < 0 is clear):', round(worst, 3))
    for tier, (worst, where) in larder_check(now): print('larder check tier', tier, 'item top minus captain underside, worst column (model units, < 0 is clear):', round(worst, 3), where)
    shore_sheet(now)
    for old in ('home', 'larder', 'crew'):
        (OUT/f'{old}.png').unlink(missing_ok=True)
    (OUT/'index.html').write_text(
        '<!doctype html><meta charset="utf-8"><title>帽船即家</title><body style="background:#ecf5f6;font-family:sans-serif;margin:20px">'
        '<a href="../index.html">预览总入口</a><h1>帽船即家：从岸上看粮袋与孩子</h1><p>当前运行资源离线渲染；不是游戏画面或行为验收。</p>'
        '<img src="shore.png" style="max-width:100%"></body>\n', encoding='utf-8')
