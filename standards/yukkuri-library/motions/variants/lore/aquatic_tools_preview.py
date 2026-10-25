"""Offline sheets for the aquatic tool clips, the paddle stick, the hull dressing and the sad face.

Reads the exported runtime clips (motion_corrections.json), including each clip's props.paddle keys, markers and
contacts, the same data the game reads; the stick, wet band and hull are the runtime props. Pillow/NumPy CPU renders
with the hat_motion_preview rasterizer; nothing launches the game. Writes .local/previews/aquatic-tools/*.png
(MCYUKKURI_PREVIEWS overrides the previews root).

    python standards/yukkuri-library/motions/variants/lore/aquatic_tools_preview.py
"""
from pathlib import Path
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hat_motion_preview as HM
import preview as P

ROOT = P.ROOT
OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT/'.local/previews'))/'aquatic-tools'
MEDIA = ('paddle', 'cradle', 'boat-life', 'bite', 'capsize', 'self-rescue', 'hull', 'faces')
BG = HM.BG
DATA = json.loads((P.ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
VESSEL = json.loads((P.ASSETS/'yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))
K = VESSEL['aquatic_scale']; SQ = VESSEL['squash']['bowl']; FREE = VESSEL['bowl']['freeboard']
WATER = -VESSEL['bowl']['owner_y']*K  # the captain's model space: water plane height above her seat


def prop(name):
    return json.loads((P.ASSETS/'yukkuri_props'/f'{name}.json').read_text(encoding='utf-8'))


def prop_mesh(name, atlas, matrix, opaque_alpha=False):
    data = prop(name); raw = np.array(data['vertices'], float).reshape(-1, 8); out = []
    for part in data['parts']:
        mesh = raw[part['start']:part['start']+part['count']].copy()
        texture = part.get('texture', f'mcyukkuri:textures/props/{name}.png').split(':', 1)[-1]
        image = Image.open(P.ASSETS/texture).convert('RGBA')
        if opaque_alpha:  # translucent band: encode its alpha as grey for the blend pass
            a = np.array(image)[:, :, 3]; image = Image.fromarray(np.dstack([a, a, a, np.full_like(a, 255)]))
        mesh[:, 3:5] = atlas.uv(texture+('#alpha' if opaque_alpha else ''), image, mesh[:, 3:5]); out.append(mesh)
    return transform(np.concatenate(out), matrix)


def transform(mesh, matrix):
    out = mesh.copy(); out[:, :3] = mesh[:, :3]@matrix[:3, :3].T+matrix[:3, 3]
    n = mesh[:, 5:8]@np.linalg.inv(matrix[:3, :3]); out[:, 5:8] = n/np.maximum(1e-9, np.linalg.norm(n, axis=1, keepdims=True))
    return out


def affine(R=np.eye(3), t=(0, 0, 0), s=1.):
    m = np.eye(4); m[:3, :3] = np.asarray(R)*np.asarray(s, float); m[:3, 3] = t; return m


def world(at=(0, 0, 0), yaw=0., scale=1., R=None):
    return affine(P.euler([0, yaw, 0])@(np.eye(3) if R is None else R), at, scale)


class Actor:
    def __init__(self, ident='marisa', aquatic=False, hat_scale=1.):
        self.posed = HM.SelectedPosed(ident, DATA, aquatic, worn_hat_scale=hat_scale); self.ident = ident
        self.bones = {b['name']: i for i, b in enumerate(self.posed.model['bones'])}

    def mesh(self, clip, t, atlas, M, face=None, hull=False):
        c = self.posed.clips[clip]; d = c['duration']; t = t % d if c['loop'] else min(d, max(0, t))
        face = face or self.posed.face(clip, t)
        pos, uv, n = self.posed.frame(clip, t)
        mesh = np.concatenate((pos, uv, np.repeat(n[:, None, :], 3, axis=1)), axis=2).reshape(-1, 8)
        if self.posed.boat and not hull: mesh = mesh[:-self.posed.boat]
        mesh[:, 3:5] = atlas.uv(f'{self.ident}/{face}', Image.fromarray(self.posed.texture(face)), mesh[:, 3:5])
        parts = [transform(mesh, M)]
        stick = self.stick(clip, t)
        if stick is not None: parts.append(prop_mesh('paddle_stick', atlas, M@stick))
        return np.concatenate(parts)

    def stick(self, clip, t):
        """props.paddle from the exported clip: the stick socket frame in the tuft bone, then the prop's own socket."""
        c = self.posed.clips[clip]
        if 'props' not in c: return None
        p = c['props']['paddle']; v = P.sample(p['keys'], t); i = self.bones[p['bone']]
        pivot = np.array(self.posed.model['bones'][i]['pivot'], float)
        socket = np.array(prop(p['prop'])['socket'], float)
        return self.posed.last_matrices[i]@affine(t=pivot+np.array(v[:3]))@affine(P.euler(v[3:]))@affine(t=-socket)

    def point(self, bone, point):
        return (self.posed.last_matrices[self.bones[bone]]@np.r_[point, 1])[:3]


MOUTH = json.loads((P.ASSETS/'yukkuri_presentation/braid_rescue.json').read_text(encoding='utf-8'))['mouth_root']


def block(atlas, M, size=16., top=(96, 140, 60), side=(122, 88, 56)):
    """A plain grass-topped bank block stand-in (16 units), corner at the origin of M."""
    img = Image.new('RGBA', (2, 1)); img.putpixel((0, 0), top+(255,)); img.putpixel((1, 0), side+(255,))
    quads = []
    for axis in range(3):
        for sign in (0, 1):
            n = np.zeros(3); n[axis] = 1 if sign else -1
            a, b = [i for i in range(3) if i != axis]
            corners = []
            for u, v in ((0, 0), (1, 0), (1, 1), (0, 1)):
                c = np.zeros(3); c[axis] = sign*size; c[a] = u*size; c[b] = v*size; corners.append(c)
            uvx = .25 if (axis == 1 and sign) else .75
            for i in (0, 1, 2, 0, 2, 3): quads.append([*corners[i], uvx, .5, *n])
    mesh = np.array(quads, float)
    for k in range(0, len(mesh), 3):  # face the stored normal
        a, b, c = mesh[k:k+3, :3]
        if np.cross(b-a, c-a)@mesh[k, 5:8] < 0: mesh[[k+1, k+2]] = mesh[[k+2, k+1]]
    mesh[:, 3:5] = atlas.uv('bank_block', img, mesh[:, 3:5])
    return transform(mesh, M)


def item(atlas, M, at, name='oil_grain_regular', size=3.0):
    data = prop(name); b = np.array(data['bounds']); extent = max(b[:, 1]-b[:, 0])
    return prop_mesh(name, atlas, M@affine(t=at)@affine(s=size/extent)@affine(t=-b.mean(axis=1)))


def hull(atlas, M, wet=False, stick=False, capsized=0.):
    """The afloat aquatic hull in an adult captain's units: freeboard over the water plane, optionally turned over."""
    R = P.euler([0, 0, capsized]); lift = FREE*K*.45*(1-math.cos(math.radians(capsized)))
    frame = M@affine(t=(0, lift, 0))@affine(R)@affine(t=(0, FREE*K, 0))
    parts = [prop_mesh('marisa_hat_turned', atlas, frame@affine(np.diag([K, K*SQ, K])))]
    alpha = prop_mesh('marisa_hat_wetline', atlas, frame@affine(np.diag([K, K*SQ, K])), True) if wet else None
    if stick:
        a = prop('paddle_stick')['anchors']['stow_hull']; socket = np.array(prop('paddle_stick')['socket'], float)
        parts.append(prop_mesh('paddle_stick', atlas, frame@affine(s=K)@affine(P.euler(a['rotation']), a['position'], a['scale'])@affine(t=-socket)))
    return np.concatenate(parts), alpha


def render(meshes, atlas, camera, water=True, wet=None, mask=None, tick=0):
    mesh = np.concatenate(meshes).reshape(-1, 3, 8); texture = np.array(atlas.image)
    pixels, depth, view = HM.rasterize(mesh, texture, **camera)
    if wet is not None:
        band, bdepth, _ = HM.rasterize(wet.reshape(-1, 3, 8), texture, **camera)
        show = np.isfinite(bdepth) & (bdepth >= depth-.05)
        a = (band[..., 0].astype(float)/255*1.25)[..., None]
        tint = np.array([34, 40, 52], float)
        pixels = np.where(show[..., None], pixels*(1-a)+tint*a, pixels).astype(np.uint8)
    if not water: return Image.fromarray(pixels)
    return HM.water_layer(pixels, depth, view, 'current', tick, np.eye(3), VESSEL, mask)


def opening_mask(M):
    return [np.array([(M@np.r_[p[0]*K, FREE*K+.02, p[1]*K, 1])[:3] for p in VESSEL['bowl']['mask']])]


def label(image, text, sub=None, size=(330, 230)):
    image = image.resize(size, Image.Resampling.LANCZOS); d = ImageDraw.Draw(image)
    d.rectangle((0, 0, size[0], 22), fill=(255, 255, 255)); d.text((5, 2), text, font=P.preview_font(14), fill='#203e48')
    if sub: d.text((5, size[1]-20), sub, font=P.preview_font(13), fill='#4b6b74')
    return image


def sheet(path, title, note, rows, cell=(330, 230)):
    width = max(len(r[1]) for r in rows)*cell[0]+170; out = Image.new('RGB', (width, 64+len(rows)*cell[1]), BG); d = ImageDraw.Draw(out)
    d.text((10, 6), title, font=P.preview_font(22), fill='#203e48'); d.text((10, 36), note, font=P.preview_font(14), fill='#4b6b74')
    for i, (name, cells) in enumerate(rows):
        y = 64+i*cell[1]; d.text((8, y+8), name, font=P.preview_font(15), fill='#203e48')
        for j, image in enumerate(cells): out.paste(image, (170+j*cell[0], y))
    out.save(path); print(path, flush=True)


def cam(az, el, span, target):
    return dict(az=az, el=el, span=span, center_x=target[0], center_y=target[1], center_z=target[2])


def marker_text(clip, t):
    c = CAP.posed.clips[clip]; m = c.get('markers', {})
    if clip == 'boat_paddle':
        u = ((t-m['power_start'])/c['duration']) % 1; return '推水段' if u < .5 else '回桨段'
    names = [k for k, v in m.items() if isinstance(v, (int, float)) and v <= t+1e-6]
    return max(names, key=lambda k: m[k]) if names else ''


def captain_scene(clip, t, views, extra=None):
    """Captain seated at her stern seat in the floating hull; the water plane at y = 0."""
    out = []
    for az, el, span, target in views:
        atlas = HM.Atlas(); M = world((0, -WATER, 0))
        meshes = [CAP.mesh(clip, t, atlas, M, hull=True)]
        if extra: meshes += extra(atlas, M, t)
        out.append(render(meshes, atlas, cam(az, el, span, target), mask=opening_mask(affine(t=(0, 0, -(VESSEL['opening'][3]*K-5.9))))))
    return out


def paddle_sheet():
    views = [(1.0, .22, 15, (5, 2, -3)), (math.pi/2, .1, 15, (6, 1.5, -3))]
    rows = []
    for clip, times in (('boat_paddle', [.2, .4, .6, .8, 1.0, 1.2, 1.4, 1.55]), ('hat_brim_offer', [0, .4, .8]), ('boat_nudge_young', [0, .34, .6])):
        side, rear = [], []
        for t in times:
            a, b = captain_scene(clip, t, views)
            side.append(label(a, f'{t:.2f}s {marker_text(clip, t)}', '前侧视（辫子一侧）'))
            rear.append(label(b, f'{t:.2f}s', '侧视（看入水）'))
        rows += [(clip, side), (clip+'（侧）', rear)]
    sheet(OUT/'paddle.png', '划桨：辫梢缠住捡来的木棍上端；推水段木棍下端入水向后扫，回桨段抬出水面向前',
          '运行片段 boat_paddle 的 props.paddle 关键帧与 power_start/power_end（游戏同读）；船体按 hat_vessel.json 漂浮；离线渲染，非游戏实录', rows)


def cradle_sheet():
    young = Actor('marisa', hat_scale=K); size = .2; rows = []
    contact = CAP.posed.clips['braid_cradle_lift']['contacts']['braid_cradle']
    closed = CAP.posed.clips['braid_cradle_lift']['markers']['contact_closed']
    def baby(atlas, M, t):
        CAP.posed.frame('braid_cradle_lift', max(t, closed))  # where the scoop closes under the young
        p = (M@np.r_[CAP.point(contact['bone'], contact['point']), 1])[:3]
        CAP.posed.frame('braid_cradle_lift', t)
        if t < closed: p[1] = -1.2*size*4  # still floating in the water beside the hull, waiting for the scoop
        clip = 'be_cradled' if t >= closed else 'water_struggle'
        return [young.mesh(clip, t, atlas, world(p-np.array([0, 1.1*size, 0]), 180, size))]
    for view, (az, el, span, target) in (('前侧视', (1.0, .2, 12, (8, 2, -3))), ('近景', (1.25, .28, 7, (10, 3, -4))), ('侧视', (math.pi/2, .1, 12, (8, 2, -3)))):
        cells = []
        for t in (0, .5, .8, 1.15, 1.5, 2.0, 2.4):
            cells.append(label(captain_scene('braid_cradle_lift', t, [(az, el, span, target)], baby)[0], f'{t:.2f}s {marker_text("braid_cradle_lift", t)}', view))
        rows.append(('braid_cradle_lift', cells))
    cells = []
    for t in (0, .3, .6, .9):
        atlas = HM.Atlas(); cells.append(label(render([young.mesh('be_cradled', t, atlas, world())], atlas, cam(.6, .15, 10, (0, 5.5, 0)), water=False), f'{t:.2f}s', '被兜的幼油（成体比例）'))
    rows.append(('be_cradled', cells))
    sheet(OUT/'cradle.png', '辫梢卷兜：伸到船边、卷起成兜托住幼油、越过帽缘放进帽里、展开',
          '幼油位置取片段 contacts.braid_cradle 的辫梢点（演示；真实转移位置由服务端给出）；幼油 0.2 倍', rows)


def bite_sheet():
    rows = []
    for ident, name in (('marisa', '魔理沙'), ('reimu', '灵梦（无辫，只用嘴）')):
        actor = CAP if ident == 'marisa' else Actor('reimu'); cells = []
        for t in (0, .25, .5, .75):
            atlas = HM.Atlas()
            meshes = [actor.mesh('mouth_bite_hold', t, atlas, world(), hull=False)]
            mouth = actor.point('root', MOUTH); hat = prop('accessory_marisa'); b = np.array(hat['bounds'])
            meshes.append(prop_mesh('accessory_marisa', atlas, world(mouth+[0, -.5, -2.2], 0, .45)@affine(t=-np.array([b[0].mean(), b[1, 0], b[2, 1]]))))
            cells.append(label(render(meshes, atlas, cam(1.0, .15, 10, (0, 4.5, -2)), water=False), f'{t:.2f}s', name+'（咬住小帽帽檐，示意）'))
        rows.append(('mouth_bite_hold', cells))
    sheet(OUT/'bite.png', '嘴叼：咬住帽檐或幼油头饰，每循环向后小拽两下（咬合脸）', '扶正翻帽、拖回漂远幼油、递粮共用；无辫种类只用嘴', rows)


def capsize_sheet():
    cells = []; splash = CAP.posed.clips['boat_thrown']['markers']['splash']
    for t in (0, .1, .2, .3, .4, .5, .6):
        atlas = HM.Atlas(); q = min(1, t/splash); y = 9*math.sin(math.pi*q*.75)-4*q*q if t < splash else -3.2
        meshes = [CAP.mesh('boat_thrown', t, atlas, world((0, y, 9*q)))]
        hmesh, _ = hull(atlas, world((0, 0, -12)), capsized=180*min(1, (t+.15)/.4))
        meshes.append(hmesh)
        cells.append(label(render(meshes, atlas, cam(1.0, .2, 20, (0, 4, -4))), f'{t:.2f}s {marker_text("boat_thrown", t)}', '弧线为示意（服务端物理）'))
    sheet(OUT/'capsize.png', '翻船：船长被甩出（惊吓伸展、半圈偏航、辫子乱甩），落水压扁；帽子最终帽冠朝上漂着',
          'boat_thrown 片段；翻覆帽体按 CAPSIZED_ROLL=180 与上浮补偿（HatBoatRenderer 同算法）', [('boat_thrown', cells)])


def rescue_sheet():
    rows = []
    for clip, capsized, times in (('water_hat_climb', 0, (0, .35, .6, .8, 1.05, 1.3, 1.55, 1.8)),
                                  ('water_hat_right', 180, (0, .24, .48, .72, .96, 1.2, 1.4, 1.6))):
        cells = []
        for t in times:
            atlas = HM.Atlas(); body = world((0, -2.6, 0))
            roll = capsized*(1-HM.playback_smooth((t-.72)/.8)) if capsized else 0
            hmesh, _ = hull(atlas, world((0, 0, -6.4-7.2*K), 90), capsized=roll)
            meshes = [CAP.mesh(clip, t, atlas, body), hmesh]
            cells.append(label(render(meshes, atlas, cam(1.35, .25, 14, (0, 1.5, -7))), f'{t:.2f}s {marker_text(clip, t)}', '帽子翻转为示意' if capsized else '水中自救'))
        rows.append((clip, cells))
    sheet(OUT/'self-rescue.png', '自救：咬檐＋辫子勾过帽檐、两次发力把身体拉上去；扶正：咬檐翻起＋辫子把远端推过去',
          '片段 markers 与 contacts（mouth、braid_hook、braid_push）由游戏读取；帽子转动由服务端扶正进度驱动，此处示意', rows)


def hull_sheet():
    cells = []
    for title, az, el, kw in (('漂浮：吃水湿色带＋左侧帽檐存放的木棍', -.9, .35, dict(wet=True, stick=True)), ('左侧视（看湿色带）', -math.pi/2, .06, dict(wet=True, stick=True)),
                              ('翻覆终态：帽冠朝上', .9, .3, dict(capsized=180, wet=True))):
        atlas = HM.Atlas(); mesh, wet = hull(atlas, world(), **kw)
        mask = None if kw.get('capsized') else opening_mask(world())
        cells.append(label(render([mesh], atlas, cam(az, el, 15, (0, 0, 0)), wet=wet, mask=mask), title, None, (440, 300)))
    atlas = HM.Atlas(); stick = prop_mesh('paddle_stick', atlas, world(R=P.euler([0, 0, 90])))
    cells.append(label(render([stick], atlas, cam(.5, .5, 8, (4.4, 0, 0)), water=False), 'paddle_stick（捡来的木棍，原色像素风）', None, (440, 300)))
    sheet(OUT/'hull.png', '帽船：吃水线湿色带、存放的木棍、翻覆帽冠朝上', '破损方格与戴着的帽子同一算法（HatDamageFaces），本页未模拟', [('船体', cells)], (440, 300))


def life_sheet():
    rows = []
    young = Actor('marisa', hat_scale=K)
    seat_z = -(VESSEL['opening'][3]*K-5.9)
    def cover(atlas, M, t):
        return [young.mesh('hat_cradle', t, atlas, world((x, VESSEL['bowl']['freeboard']*K-VESSEL['ledge']*SQ*K, seat_z-4.5), 180+20*x, .16)) for x in (-1.6, 1.6)]
    def bank(atlas, M, t):
        return [block(atlas, world((-10, -12, -34))), block(atlas, world((-10, -12, -50))), block(atlas, world((6, -12, -34)))]
    def side_bank(atlas, M, t):
        return [block(atlas, world((14, -12, -14))), block(atlas, world((14, -12, 2)))]
    def mouthful(clip):
        def place(atlas, M, t):
            m = CAP.posed.clips[clip]['markers']; inside = (0, -VESSEL['ledge']*SQ*K, seat_z-2.0)
            held = t < m['release'] if clip == 'boat_cargo_stow' else t >= m['bite']
            p = (M@np.r_[CAP.point('root', MOUTH), 1])[:3]+[0, 0, -.6] if held else np.array(inside)
            return [item(atlas, world(), p)]
        return place
    def pushed(atlas, M, t):
        m = CAP.posed.clips['boat_jettison']['markers']; c = CAP.posed.clips['boat_jettison']['contacts']['braid_push']
        tip = (M@np.r_[CAP.point(c['bone'], c['point']), 1])[:3]
        start = np.array([7.5, VESSEL['bowl']['freeboard']*K+.8, seat_z-1])
        p = start if t < m['push'] else tip+[1.2, 0, 0] if t < m['over'] else tip+[3.5, -2.2-6*(t-m['over']), 0]
        return [item(atlas, world(), p)]
    for clip, times, extra in (('boat_cover_young', (0, .8, 1.6), cover), ('boat_wedge_berth', (0, .35, .9, 1.2, 1.6), bank),
                               ('boat_push_off', (0, .25, .35, .5, .8), side_bank), ('boat_cargo_stow', (0, .35, .55, 1.0), mouthful('boat_cargo_stow')),
                               ('boat_cargo_fetch', (0, .5, .7, 1.0), mouthful('boat_cargo_fetch')), ('boat_jettison', (0, .3, .45, .7, 1.0), pushed)):
        cells = [label(captain_scene(clip, t, [(1.0, .3, 17, (4, 3, -5))], extra)[0], f'{t:.2f}s {marker_text(clip, t)}') for t in times]
        rows.append((clip, cells))
    sheet(OUT/'boat-life.png', '船上日常：俯身护子、楔岸夜泊、辫推离岸、帽冠袋放粮／取粮、推出帽檐',
          '只用辫子、嘴和身体（无绳无结）；岸块、粮与幼油为示意摆放，位置由服务端决定；markers 与 contacts 随片段导出', rows)


def face_sheet():
    idents = sorted(p.name for p in (P.ASSETS/'textures/entity').iterdir() if (p/'sad.png').is_file())
    cells = []
    for ident in idents:
        actor = Actor(ident); trio = []
        height = max(v[1] for p in actor.posed.model['parts'] if 'body_surface' in p['name']
                     for v in np.array(actor.posed.model['vertices']).reshape(-1, 8)[p['start']:p['start']+p['count'], :3])
        for face in ('teary', 'sad', 'afraid'):
            atlas = HM.Atlas(); img = render([actor.mesh('idle', 0, atlas, world(), face=face)], atlas, cam(0, .08, .62*height, (0, .55*height, 0)), water=False)
            trio.append(img.crop((180, 0, 820, 660)).resize((140, 145), Image.Resampling.LANCZOS))
        cell = Image.new('RGB', (440, 170), BG)
        for i, img in enumerate(trio): cell.paste(img, (i*146, 22))
        ImageDraw.Draw(cell).text((4, 2), f'{ident}  含泪 / 沮丧 sad / 害怕 afraid', font=P.preview_font(13), fill='#203e48'); cells.append(cell)
    rows = [(f'{i//4+1}', cells[i:i+4]) for i in range(0, len(cells), 4)]
    sheet(OUT/'faces.png', '新表情 sad（沮丧：含泪去泪、垂眼）与 afraid（害怕：惊大眼、瞳孔缩小、抿嘴）', '派生贴图 expressions/derived/faces.py；待用户确认', rows, (440, 170))


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    CAP = Actor('marisa', aquatic=True)
    paddle_sheet(); cradle_sheet(); life_sheet(); bite_sheet(); capsize_sheet(); rescue_sheet(); hull_sheet(); face_sheet()
    (OUT/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>水生工具动作</title><body style="background:#ecf5f6;font-family:sans-serif">'
                                  +''.join(f'<h2>{m}</h2><img src="{m}.png" style="max-width:100%">' for m in MEDIA)+'</body>\n', encoding='utf-8')
