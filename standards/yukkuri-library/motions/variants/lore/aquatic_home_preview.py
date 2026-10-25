"""Offline sheets for the aquatic hat boat as a home (ecology design D 3): hull size, crown larder, crew seats and poses.

Reads only exported runtime data, as the game does: hat_vessel.json (size), hat_deck_layout.json (seats, conflicts,
larder exclusions, the crew pose table), hat_cargo_layout.json and hat_provisions.json (the larder), hat_frames.json
(freeboard by load), marisa_hat_turned / marisa_hat_wetline / paddle_stick and the exported clips. Seats are chosen
from the deck table with the same rules the runtime uses (classes, night, larder and stick exclusions, conflicts,
preference order). The "before" column reads the previous commit's resources from git (hull 1.5, the old heap).
Stored items are drawn with the vanilla item sprites from the local NeoForge client resources jar (preview only, not
copied). Pillow/NumPy CPU renders; nothing launches the game.

    python standards/yukkuri-library/motions/variants/lore/aquatic_home_preview.py [--before <git rev>]

Writes <MCYUKKURI_PREVIEWS or .local/previews>/aquatic-home/{home,larder,crew}.png and index.html.
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
        if self.rev is None: return (P.ASSETS/rel).read_bytes()
        return subprocess.run(['git', '-C', str(ROOT), 'show', f'{self.rev}:src/main/resources/assets/mcyukkuri/{rel}'],
                              capture_output=True, check=True).stdout

    def json(self, rel): return json.loads(self.read(rel).decode('utf-8'))


class Home:
    def __init__(self, res):
        self.res = res; self.V = res.json('yukkuri_presentation/hat_vessel.json'); self.k = self.V['aquatic_scale']
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
        if 'm' not in self.young: self.young['m'] = T.Actor('marisa', hat_scale=self.k)
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
CREW = {0: ['large_child', 'baby'], 1: ['child', 'baby', 'baby'], 2: ['small_child', 'small_child'], 3: ['baby', 'baby', 'baby']}
VIEWS = (('前方 3/4（岸上俯看约 38°）', lambda h: cam(.7, .66, 17, (0, 2, 0))),
         ('侧视（右舷，看吃水）', lambda h: cam(math.pi/2, .1, 17, (0, 2, 0))))


def home_sheet(now, before):
    rows = []
    for tier in (0, 1, 2, 3):
        for tag, home in (('1.75 现在', now), (f'{before.k:g} 之前', before)):
            cells = []
            for night in (False, True):
                for vname, view in VIEWS:
                    atlas = HM.Atlas()
                    meshes, wet, mask, dropped, seats = home.scene(CREW[tier], STOCK[tier], night, atlas)
                    img = render(meshes, wet, mask, atlas, view(home), night=night)
                    food = sum(n for _, n in STOCK[tier]); load = home.load(CREW[tier][:len(CREW[tier])-len(dropped)], food)
                    cells.append(label(img, f"{'夜' if night else '昼'} · {vname}", describe(CREW[tier], seats, dropped)+f' · 载重 {load:g}', warn=bool(dropped)))
            rows.append((f'粮 {tier} 档\n（{sum(n for _, n in STOCK[tier])} 份）\n{tag}', cells))
    sheet(OUT/'home.png', '帽船即家：帽子 1.5 → 1.75 倍，成体船长 + 乘员 × 粮袋四档 × 昼夜',
          ['座位按 hat_deck_layout.json 的席位、冲突、粮袋与木棍排除和昼夜偏好求得（与运行时同一规则）；“之前”列读上一提交的资源（1.5 倍、旧整堆粮）。',
           '吃水随载重下沉（hat_frames.json freeboard_by_load）；帽檐上不放粮。离线渲染、夜色为调暗示意，非游戏实录。'], rows)


def larder_sheet(now):
    rows = []
    for row_name, night, baby, view, sub in (
            ('昼 · 岸上俯看 38°\n无赤油', False, False, lambda: cam(.7, .66, 12, (0, 0, -3)), '前方 3/4：高档时顶上几件从船长身前露出'),
            ('昼 · 前上 45°\n有赤油', False, True, lambda: cam(.5, .8, 10, (0, -1, -3)), '看进帽口：粮袋、叶托与孩子'),
            ('夜 · 前上 45°\n赤油在深窝', True, True, lambda: cam(.5, .8, 10, (0, -1, -3)), '船长俯身盖住深窝'),
            ('正上\n（席位不重叠，帽檐无粮）', False, True, lambda: cam(0, 1.5707, 13, (0, 0, 0)), None)):
        cells = []
        for tier in (0, 1, 2, 3):
            atlas = HM.Atlas(); crew = ['baby', 'baby'] if baby else []
            meshes, wet, mask, dropped, seats = now.scene(crew, STOCK[tier], night, atlas)
            img = render(meshes, wet, mask, atlas, view(), night=night)
            stock = '、'.join(f'{NAMES[n]}×{c}' for n, c in STOCK[tier]) or '空'
            cells.append(label(img, f'{tier} 档 · {stock}', sub if tier == 0 or sub is None else describe(crew, seats, dropped) if crew else None, warn=bool(dropped)))
        rows.append((row_name, cells))
    # a cut through the larder at each tier, the hull drawn without its starboard half so the heap shows in section
    sheet(OUT/'larder.png', '帽冠粮袋：0 / 1–3 / 4–7 / 8–12 份，按实际库存种类显示（最多 4 种）',
          ['物品用各自的原版物品模型缩小，按放入顺序落在倒置帽冠底的叶托上（嘴叼放、推压，无捆扎）；草类对折塞进缝里，浆果一小把。',
           '1 档低于三段地面；2 档到三段地面；3 档最高点低于船长脚底与帽口，粮堆顶在船长胸前（视线越过前台阶最深处），从岸上俯看越过帽檐能看到顶上几件；只为看得见的顶层花心思；帽檐上不出现粮。'], rows)


def crew_sheet(now):
    shots = [('赤油 ×3\n昼：前台阶探头看水', ['baby', 'baby', 'baby'], [], False),
             ('赤油 ×3\n夜：深窝挤着睡\n船长俯身盖住', ['baby', 'baby', 'baby'], [], True),
             ('新生 ×4\n昼／夜', ['newborn']*4, [('apple', 2)], None),
             ('小子油 ×2\n昼／夜：前台阶', ['small_child', 'small_child'], [], None),
             ('子油 + 赤油 ×2\n子油在前台阶', ['child', 'baby', 'baby'], [('apple', 2), ('sweet_berries', 1)], None),
             ('大子油\n昼夜都在前台阶', ['large_child'], [('apple', 1)], None)]
    views = (('前方 3/4', cam(.7, .47, 13, (0, 1, -1))), ('右舷侧视', cam(math.pi/2, .12, 13, (0, 1, -1))),
             ('前上近景', cam(.4, .85, 9, (0, 0, -2))))
    rows = []
    for name, crew, stock, night in shots:
        cells = []
        for n in ((False, True) if night is None else (night,)):
            for vname, view in (views if night is not None else views[:2]):
                atlas = HM.Atlas(); meshes, wet, mask, dropped, seats = now.scene(crew, stock, n, atlas)
                cells.append(label(render(meshes, wet, mask, atlas, view, night=n), f"{'夜' if n else '昼'} · {vname}",
                                   describe(crew, seats, dropped), warn=bool(dropped)))
        rows.append((name, cells))
    sheet(OUT/'crew.png', '乘员姿态：赤油昼探头、夜挤在船长身下；子油前台阶面朝外；船长夜里俯身盖窝（boat_cover_young）',
          ['姿态与表情取 hat_deck_layout.json 的 crew 表（boat_rim_peek / boat_nest_curl / hat_cradle / boat_cover_young）；孩子戴自己的小帽，深窝里小帽被挤歪一点。',
           '座位由席位表求得；离线渲染，夜色为调暗示意，非游戏实录。'], rows)


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


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__); ap.add_argument('--before', default='HEAD')
    ap.add_argument('--only', nargs='*', default=['home', 'larder', 'crew'])
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    CAPTAIN = T.Actor('marisa', aquatic=True)
    now, before = Home(Resources()), Home(Resources(args.before))
    if 'crew' in args.only:
        for crew, worst in under_check(now): print('under-captain check', crew, 'young top minus captain underside, worst column (model units, < 0 is clear):', round(worst, 3))
    if 'home' in args.only: home_sheet(now, before)
    if 'larder' in args.only: larder_sheet(now)
    if 'crew' in args.only: crew_sheet(now)
    (OUT/'index.html').write_text(
        '<!doctype html><meta charset="utf-8"><title>帽船即家</title><body style="background:#ecf5f6;font-family:sans-serif;margin:20px">'
        '<a href="../index.html">预览总入口</a><h1>帽船即家：尺寸、粮袋与乘员</h1><p>当前运行资源离线渲染；不是游戏画面或行为验收。</p>'
        + ''.join(f'<h2>{t}</h2><img src="{m}.png" style="max-width:100%">' for m, t in
                  (('home', '帽子 1.5 → 1.75 倍：成体船长与乘员 × 粮袋四档 × 昼夜'), ('larder', '帽冠粮袋各档'), ('crew', '乘员昼夜姿态')))
        + '</body>\n', encoding='utf-8')
