"""Offline current-mesh cabin study; no game, browser or Java compilation.

Run with Python/numpy/Pillow. Reuses the authored software depth renderer and the
selected model's placement splice. Staged seats are checked against the exported
stepped surfaces; they are not a recording of server passenger selection.
"""
from pathlib import Path
import hashlib
import html
import json
import math
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
OUT = ROOT / '.local/previews/hat-cabin'
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/motions/variants/lore'))
import hat_motion_preview as H
import preview as P

VANILLA_JAR = Path('F:/yukkuri/build/moddev/artifacts/neoforge-21.1.250-client-extra-aka-minecraft-resources.jar')


def vanilla_texture(ref):
    """A vanilla sprite read from the local game resources jar for the preview only; never copied into the repository."""
    import io, zipfile
    with zipfile.ZipFile(VANILLA_JAR) as jar:
        return Image.open(io.BytesIO(jar.read('assets/minecraft/' + ref.split(':', 1)[1]))).convert('RGBA')


def tier_of(count):
    """HatBoatCargo.tier(): 0 nothing, 1 for 1-2 portions, 2 for 3-5, 3 for 6-8."""
    return 0 if count <= 0 else 1 if count <= 2 else 2 if count <= 5 else 3


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


class Cabin:
    def __init__(self):
        self.vessel = read(ASSETS / 'yukkuri_presentation/hat_vessel.json')
        self.atlas = H.Atlas()
        self.inputs = {ASSETS / 'yukkuri_presentation/hat_vessel.json'}
        self.hull = self.prop('marisa_hat_turned')
        self.hull[:, 1] = self.hull[:, 1] * self.vessel['squash']['bowl'] + self.vessel['bowl']['freeboard']
        self.hull[:, 6] /= self.vessel['squash']['bowl']
        self.hull[:, 5:] /= np.linalg.norm(self.hull[:, 5:], axis=1, keepdims=True)
        self.young, self.seats = [], []
        corrections = read(ASSETS / 'yukkuri_presentation/motion_corrections.json')
        body_bounds = read(ASSETS / 'yukkuri_presentation/body_geometry.json')['variants']['marisa']['bounds']
        posed = H.SelectedPosed('marisa', corrections, worn_hat_scale=self.vessel['aquatic_scale'])
        pos, uv, normals = posed.frame('idle', 0)
        raw = np.concatenate((pos, uv, np.repeat(normals[:, None, :], 3, axis=1)), axis=2).reshape(-1, 8)
        raw[:, 3:5] = self.atlas.uv('marisa/neutral', Image.fromarray(posed.texture('neutral')), raw[:, 3:5])
        # The aquatic parent (her own hat is the boat) sits on the stern step, facing the bow; real runtime body and texture.
        parent = H.SelectedPosed('marisa', corrections, True)
        ppos, puv, pnormals = parent.frame('idle', 0)
        self.parent = np.concatenate((ppos, puv, np.repeat(pnormals[:, None, :], 3, axis=1)), axis=2).reshape(-1, 8)[:-parent.boat]   # her own hull is the boat mesh already drawn
        self.parent[:, 3:5] = self.atlas.uv('marisa/neutral', Image.fromarray(parent.texture('neutral')), self.parent[:, 3:5])
        self.parent[:, :3] *= 1 / self.vessel['aquatic_scale']
        self.parent[:, :3] += [0, self.vessel['bowl']['owner_y'], max(0, self.vessel['opening'][3] - 5.9 / self.vessel['aquatic_scale'])]
        # The staged children face the owner's stern berth, across the cabin.
        raw[:, (0, 2, 5, 7)] *= -1
        for path in ('yukkuri/marisa.json', 'yukkuri_presentation/motion_corrections.json',
                     'yukkuri_presentation/placement_fit.json', 'yukkuri_presentation/body_geometry.json',
                     'textures/entity/marisa/neutral.png'):
            if (ASSETS / path).exists(): self.inputs.add(ASSETS / path)
        for size, preferred in ((.22, (-1.7, -5.15)), (.14, (1.7, -5.15))):
            mesh = raw.copy()
            mesh[:, :3] *= size / self.vessel['aquatic_scale']
            # Origin is the runtime body contact plane, not the bottom of its accessory.
            choices = []
            for x in np.arange(-3, 3.01, .25):
                for z in np.arange(-5.75, 3.51, .125):
                    at = np.array([x, self.surface(x, z), z])
                    candidate = mesh.copy(); candidate[:, :3] += at
                    if not self.clear(candidate): continue
                    if any(self.bounds_overlap(candidate, other) for other in self.young + self.food + [self.parent]): continue
                    # HatVessel.cabinClear uses full body width plus the worn rectangular brim.
                    half = (body_bounds[0][1]-body_bounds[0][0]) * size / self.vessel['aquatic_scale'] * .5
                    if not self.rect_clear(x, at[1], z, half, half): continue
                    base = at[1] + self.vessel['anchor_worn'][1] * size / self.vessel['aquatic_scale']
                    if not self.rect_clear(x, base, z, self.vessel['brim'][0]*size, self.vessel['brim'][1]*size): continue
                    choices.append((math.hypot(x-preferred[0], z-preferred[1]), candidate, at))
            if not choices: raise RuntimeError(f'No mesh-clear staged seat for {size}')
            _, candidate, at = min(choices, key=lambda item: item[0])
            self.young.append(candidate)
            self.seats.append(dict(growthScale=size, position=at.tolist(), facingLocal='+Z, towards owner',
                                   layout='offline staged mesh-clear seat'))

    def provisions(self, count):
        """The picture layer HatCargoRenderer draws for this many portions: only its own tier, flat on the brim top."""
        mesh = self.prop('hat_provisions', tier=tier_of(count))
        mesh[:, :3] += [0, self.vessel['bowl']['freeboard'], 0]
        return mesh

    def prop(self, name, units=None, tier=None):
        path = ASSETS / 'yukkuri_props' / f'{name}.json'; self.inputs.add(path)
        data = read(path); raw = np.array(data['vertices']).reshape(-1, 8); pieces = []
        for part in data['parts']:
            if name == 'leaked_filling' and part.get('unit', 0) > 1: continue
            if units is not None and part.get('unit', 0) > units: continue
            if tier is not None and part.get('unit', 0) != tier: continue
            mesh = raw[part['start']:part['start']+part['count']].copy()
            texture = part.get('texture', f'mcyukkuri:textures/props/{name}.png').split(':', 1)[-1]
            if part.get('texture', '').startswith('minecraft:'):
                # Vanilla sprites are referenced, not stored; this stand-in only shows the placement (golden ear).
                image = vanilla_texture(part['texture']); texture = 'vanilla/' + part['texture']
            else:
                self.inputs.add(ASSETS / texture); image = Image.open(ASSETS / texture)
            mesh[:, 3:5] = self.atlas.uv(texture, image, mesh[:, 3:5])
            pieces.append(mesh)
        return np.concatenate(pieces)

    def surface(self, x, z):
        height = self.vessel['bowl']['freeboard']
        for section in self.vessel['cavity_sections']:
            a, b, c, d = section['rect']
            if a <= x <= b and c <= z <= d:
                height = self.vessel['bowl']['freeboard'] - section['depth'] * self.vessel['squash']['bowl']
        return height

    def rect_clear(self, x, y, z, hx, hz):
        if y >= self.vessel['bowl']['freeboard'] - .0001: return True
        for section in self.vessel['cavity_sections']:
            if y < self.vessel['bowl']['freeboard']-section['depth']*self.vessel['squash']['bowl']-.0001: continue
            a, b, c, d = section['rect']
            return x-hx >= a+.025 and x+hx <= b-.025 and z-hz >= c+.025 and z+hz <= d-.025
        return False

    def clear(self, mesh):
        triangles = mesh[:, :3].reshape(-1, 3, 3)
        points = np.concatenate((triangles.reshape(-1, 3), triangles.mean(axis=1),
                                 ((triangles+np.roll(triangles, 1, axis=1))*.5).reshape(-1, 3)))
        heights = np.full(len(points), self.vessel['bowl']['freeboard'])
        for section in self.vessel['cavity_sections']:
            a, b, c, d = section['rect']
            inside = (points[:, 0]>=a)&(points[:, 0]<=b)&(points[:, 2]>=c)&(points[:, 2]<=d)
            heights[inside] = self.vessel['bowl']['freeboard']-section['depth']*self.vessel['squash']['bowl']
        return bool(np.all(points[:, 1] >= heights-.02))

    @staticmethod
    def bounds_overlap(a, b):
        return bool(np.all(a[:, :3].min(0) < b[:, :3].max(0)+.025) and
                    np.all(b[:, :3].min(0) < a[:, :3].max(0)+.025))

    def mesh(self, count, young, cut=False):
        hull = self.hull.reshape(-1, 3, 8)
        if cut:
            # Explanatory cutaway only; the actual complete mesh is used in the main view.
            triangles = []
            for triangle in hull:
                polygon = []
                for previous, current in zip(np.roll(triangle, 1, axis=0), triangle):
                    before, after = previous[0] <= -.25, current[0] <= -.25
                    if before != after:
                        t = (-.25-previous[0])/(current[0]-previous[0])
                        polygon.append(previous+(current-previous)*t)
                    if after: polygon.append(current)
                for i in range(1, len(polygon)-1):
                    triangles.append([polygon[0], polygon[i], polygon[i+1]])
            hull = np.array(triangles)
        pieces = [hull.reshape(-1, 8), *([self.provisions(count)] if count else [])]
        if young: pieces.extend(self.young)
        return np.concatenate(pieces).reshape(-1, 3, 8)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cabin = Cabin()
    H.W, H.H, H.BG = 840, 610, (237, 241, 237)
    texture = np.array(cabin.atlas.image)
    panels = []
    states = [('empty', '空帽 · 0 份粮食', 0, False), ('food', '仅储粮 · 8 份', 8, False),
              ('young', '仅幼体 · 赤油 14% / 子油 22%', 0, True), ('mixed', '粮幼共存 · 8 份 + 2 只', 8, True)]
    for name, label, count, young in states:
        panel = Image.new('RGB', (1680, 740), '#edf1ed'); draw = ImageDraw.Draw(panel)
        draw.text((24, 12), label, font=P.preview_font(29), fill='#243c38')
        for j, cut in enumerate((False, True)):
            pixels, _, _ = H.rasterize(cabin.mesh(count, young, cut), texture,
                                      az=1.1 if cut else .23, el=.38 if cut else 1.18,
                                      span=11, center_y=-1, center_z=0)
            panel.paste(Image.fromarray(pixels), (840*j, 57))
            draw.text((24+840*j, 672), '侧剖示意：移除近侧船壳，内容物保持原位' if cut else '完整当前船壳 · 高视角查看层层收窄的内壁',
                      font=P.preview_font(20), fill='#455c56')
        draw.text((24, 708), '离线布置 / 当前运行网格与贴图 / 非游戏验收；幼体脚底落在真实阶面，粮食逐份显示。',
                  font=P.preview_font(18), fill='#65766e')
        panel.save(OUT / f'{name}.png'); panels.append(panel)
    overview = Image.new('RGB', (1680, 4*740+110), '#edf1ed')
    draw = ImageDraw.Draw(overview)
    draw.text((25, 16), '帽船内部：空舱、储粮、幼体与共存', font=P.preview_font(37), fill='#243c38')
    draw.text((25, 67), '船帽保持原外轮廓；阶梯内腔随锥形向下收窄。剖视仅用于解释内部结构。', font=P.preview_font(22), fill='#526c61')
    for i, panel in enumerate(panels): overview.paste(panel, (0, 110+740*i))
    overview.save(OUT / 'hat-cabin.png')
    strip = Image.new('RGB', (1680, 680), '#edf1ed'); draw = ImageDraw.Draw(strip)
    H.W, H.H = 420, 550
    for i, count in enumerate((0, 1, 4, 8)):
        pixels, _, _ = H.rasterize(cabin.mesh(count, False), texture, az=.23, el=1.3, span=13, center_y=-1)
        strip.paste(Image.fromarray(pixels), (i*420, 50))
        draw.text((i*420+25, 20), f'{count} 份储粮（第 {tier_of(count)} 档贴图层）', font=P.preview_font(25), fill='#243c38')
    draw.text((25, 620), '数量示例：每份一处可见模型；离线样本不模拟库存同步或拾取。', font=P.preview_font(23), fill='#526c61')
    strip.save(OUT / 'quantities.png')
    H.W, H.H = 1680, 1000
    pixels, _, _ = H.rasterize(cabin.mesh(8, True), texture, az=math.pi-.35, el=.48, span=10.8, center_y=-.4)
    eye = Image.new('RGB', (1680, 1110), '#edf1ed'); eye.paste(Image.fromarray(pixels), (0, 65))
    draw = ImageDraw.Draw(eye)
    draw.text((25, 15), '普通斜视 · 完整船壳 · 粮幼共存', font=P.preview_font(35), fill='#243c38')
    draw.text((25, 1070), '离线布置：幼体在前部实心台阶面向舱内；从亲油侧观察。完整船壳，非游戏验收。', font=P.preview_font(24), fill='#526c61')
    eye.save(OUT / 'mixed-eye.png')
    (OUT / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>帽船内部</title><style>body{background:#edf1ed;color:#243c38;font:20px sans-serif;margin:24px}img{max-width:100%}</style><a href="../index.html">预览总入口</a><p>当前运行资源离线布置，非游戏画面、非行为验收。剖面裁除部分船壳；完整船壳见普通斜视及每行左侧。</p><img src="mixed-eye.png"><img src="hat-cabin.png"><img src="quantities.png">', encoding='utf-8')
    manifest = dict(scope='Offline staged current runtime meshes, no game or browser verification',
                    validation='Mesh vertices, triangle centroids and edge midpoints clear stepped surfaces; conservative mesh AABBs separated; body and hat rectangle contract checked.',
                    seats=cabin.seats, sources={p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(cabin.inputs)})
    (OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    build_local_hub()
    print(OUT / 'hat-cabin.png')
    print(json.dumps(cabin.seats))


def build_local_hub():
    # An isolated art worktree needs only its generated topics; keep an existing full gallery intact.
    hub = OUT.parent / 'index.html'
    marker = '<!-- hat-cabin-local-hub -->'
    if not hub.exists() or marker in hub.read_text(encoding='utf-8'):
        links = ''.join(f'<li><a href="{html.escape(p.parent.name)}/index.html">{html.escape(p.parent.name)}</a></li>'
                        for p in sorted(OUT.parent.glob('*/index.html')))
        hub.write_text('<!doctype html><meta charset="utf-8">'+marker+
                       '<title>当前工作树预览</title><h1>当前工作树预览</h1><p>离线制作结果；非游戏验收。</p><ul>'+links+'</ul>', encoding='utf-8')


if __name__ == '__main__':
    main()
