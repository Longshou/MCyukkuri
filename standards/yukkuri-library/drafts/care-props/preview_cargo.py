"""Offline current-mesh hat cargo contact sheet and authored envelope checks.

No game, compilation or UI automation. Reads current runtime hull, parent and
cargo; outputs only .local/previews/hat-cargo. The full cabin card intentionally
shows its interior without a parent to reveal the three quantity tiers.
"""
import json
import io
import sys
import zipfile
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
OUT = ROOT / '.local/previews/hat-cargo'
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/motions/variants/lore'))
import hat_motion_preview as H
import preview as P
import cargo


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


class Preview:
    def __init__(self):
        self.vessel = read(cargo.VESSEL_PATH)
        self.layout = read(cargo.LAYOUT_PATH)
        self.atlas = H.Atlas()
        self.meshes = {}
        self.hull = self.prop('marisa_hat_turned')
        self.stored = {i: self.prop('hat_provisions', i) for i in range(1, 7)}
        self.validate()
        for mesh in [self.hull, *self.stored.values()]:
            mesh[:, 1] = mesh[:, 1]*self.vessel['squash']['bowl']+self.vessel['bowl']['freeboard']
            mesh[:, 6] /= self.vessel['squash']['bowl']
            mesh[:, 5:] /= np.linalg.norm(mesh[:, 5:], axis=1, keepdims=True)
        parent = H.SelectedPosed('marisa', read(ASSETS / 'yukkuri_presentation/motion_corrections.json'), True)
        pos, uv, normal = parent.frame('idle', 0)
        self.parent = np.concatenate((pos, uv, np.repeat(normal[:, None, :], 3, axis=1)), axis=2).reshape(-1, 8)[:-parent.boat]
        self.parent[:, 3:5] = self.atlas.uv('marisa/neutral', Image.fromarray(parent.texture('neutral')), self.parent[:, 3:5])
        self.parent[:, :3] /= self.vessel['aquatic_scale']
        self.parent[:, :3] += [0, self.vessel['bowl']['owner_y'], max(0, self.vessel['opening'][3]-5.9/self.vessel['aquatic_scale'])]

    def prop(self, name, unit=None):
        data = self.meshes.setdefault(name, read(ASSETS / f'yukkuri_props/{name}.json'))
        raw = np.array(data['vertices']).reshape(-1, 8)
        pieces = []
        for part in data['parts']:
            if unit is not None and part.get('unit') != unit:
                continue
            mesh = raw[part['start']:part['start']+part['count']].copy()
            texture = part.get('texture', f'mcyukkuri:textures/props/{name}.png').split(':', 1)[-1]
            if part.get('texture','').startswith('minecraft:'):
                jar=ROOT/'build/moddev/artifacts/neoforge-21.1.250-client-extra-aka-minecraft-resources.jar'
                with zipfile.ZipFile(jar) as archive:
                    picture=Image.open(io.BytesIO(archive.read('assets/minecraft/'+texture))).convert('RGBA')
                repeat=np.maximum(1,np.ceil(mesh[:,3:5].max(axis=0)).astype(int))
                if np.any(repeat>1):
                    picture=Image.fromarray(np.tile(np.array(picture),(repeat[1],repeat[0],1)))
                    mesh[:,3:5]/=repeat
                key='minecraft/'+texture
            else:
                picture=Image.open(ASSETS/texture);key=texture
            mesh[:, 3:5] = self.atlas.uv(key,picture,mesh[:,3:5])
            pieces.append(mesh)
        return np.concatenate(pieces)

    def surface(self, x, z):
        value = np.zeros_like(x)
        for section in self.vessel['cavity_sections']:
            a,b,c,d = section['rect']
            mask = (x>=a)&(x<=b)&(z>=c)&(z<=d)
            value[mask] = -section['depth']
        return value

    def validate(self):
        data = self.meshes['hat_provisions']
        raw = np.array(data['vertices']).reshape(-1,8)
        for part in data['parts']:
            side, tier = part['name'].split('_')[:2]
            zones = self.layout['aside_tiers' if side=='aside' else 'tiers'][int(tier.removeprefix('tier'))]
            v = raw[part['start']:part['start']+part['count'], :3]
            inside=np.zeros(len(v),dtype=bool)
            for zone in zones:
                inside|=(np.hypot(v[:,0]-zone['x'],v[:,2]-zone['z'])<=zone['radius']+.001)&(v[:,1]>=-zone['depth']-.001)&(v[:,1]<=-zone['depth']+zone['height']+.001)
            assert np.all(inside), part['name']
            triangles = v.reshape(-1,3,3)
            probes = np.concatenate((v,triangles.mean(1),((triangles+np.roll(triangles,1,axis=1))*.5).reshape(-1,3)))
            assert np.all(probes[:,1]>=self.surface(probes[:,0],probes[:,2])-.025), f'{part["name"]} crosses hat surface'
        for key in ('tiers', 'aside_tiers'):
            for small, big in zip(self.layout[key][1:], self.layout[key][2:]):
                for old in small:
                    assert any(old['x']==new['x'] and old['z']==new['z'] and old['depth']==new['depth']
                               and old['radius']<=new['radius'] and old['height']<=new['height'] for new in big), 'non-monotonic '+key
        print('All authored heap vertices inside shared cylinders; sampled triangles clear current hull; tiers are nested.')

    def scene(self, tier=0, aside=0, parent=False):
        return np.concatenate([self.hull]+([self.stored[tier]] if tier else [])+
                              ([self.stored[aside+3]] if aside else [])+([self.parent] if parent else [])).reshape(-1,3,8)


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    study = Preview()
    H.W,H.H,H.BG = 520,370,(238,242,233)
    sheet = Image.new('RGB',(1600,1020),'#eef2e9')
    d = ImageDraw.Draw(sheet)
    d.text((28,18),'帽内储粮堆 · 数量决定三档',font=P.preview_font(36),fill='#2b4035')
    d.text((30,67),'直接复用游戏现有储粮堆，只改变整体大小；满载时孩子让出帽腔，照料时可暂放侧檐。',font=P.preview_font(22),fill='#536855')
    cards = [('较少 · 1—2 份',1,0,False),('较多 · 3—5 份',2,0,False),('满载 · 6—8 份',3,0,False),
             ('空帽 · 亲油位置',0,0,True),('满载 · 亲油与粮堆',3,0,True),('暂放侧檐 · 帽腔腾空',0,3,True)]
    texture = np.array(study.atlas.image)
    for i,(label,tier,aside,parent) in enumerate(cards):
        x=20+i%3*530;y=118+i//3*440
        d.rounded_rectangle((x,y,x+518,y+423),16,fill='#f9faf5')
        d.text((x+14,y+10),label,font=P.preview_font(25),fill='#2b4035')
        pixels,_,_=H.rasterize(study.scene(tier,aside,parent),texture,az=.22,el=1.12 if not parent else .83,
                               span=11.6,center_y=-.8 if not parent else 1.3,center_z=0)
        sheet.paste(Image.fromarray(pixels),(x,y+45))
    d.text((30,995),'离线造型／占位包络检查；非游戏画面，救援弃粮与恢复取粮仍需试玩确认。',font=P.preview_font(18),fill='#536855')
    path=OUT/'hat-cargo.png';sheet.save(path)
    (OUT/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>帽内储粮堆</title><style>body{font:20px sans-serif;background:#eef2e9;color:#2b4035;margin:24px}img{max-width:100%}</style><a href="../index.html">预览总入口</a><p>帽内储粮堆：当前运行资源离线预览；不是游戏行为验收。</p><img src="hat-cargo.png">',encoding='utf-8')
    print(path)


if __name__=='__main__':
    main()
