"""Offline review sheet for the supine restraint table; no game or browser required.

Renders the runtime block models with the care-props render_preview: the empty table from four sides and close up at the
fixed end and the buckle; the occupied table with a juvenile (scale 0.4), an adult and a wide Reimu (deibu, 1.5) and an adult
Marisa with her hat, each with the closed straps of build.closed_straps (the render hook's reference geometry); the in-game look
until the hook lands; the inventory sprite and the hand model.
Usage: <py> art/props/restraint-table/preview.py
Output: <MCYUKKURI_PREVIEWS or shared .local/previews>/restraint-table/{current.png, index.html}."""
import json, os, subprocess, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SHARED = Path(subprocess.check_output(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], cwd=ROOT, text=True).strip()).parent
os.environ.setdefault('MCYUKKURI_VANILLA', str(SHARED / '.local/vanilla-ref/tex/assets/minecraft'))
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(HERE))
import render_preview as R
from preview_block_model import mesh
import build as B

OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', SHARED / '.local/previews')) / 'restraint-table'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
FONT = os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc')
BG = (226, 228, 222, 255)
MODELS = {data['id']: (data, prefixes) for data, prefixes in B.runtime_models()}
FITS = B.strap_fits()


class Scene(R.Source):
    """m:<block model> (origin at the block's bottom centre); sup:<asset>:<bare>:<scale>:<flip>:<expression> a supine body
    on the tabletop as the renderer's FIXED transform places it; straps:<asset>:<bare>:<scale>:<flip> its closed straps."""

    def load(self, name):
        kind, _, rest = name.partition(':')
        if kind == 'm':
            model = json.loads((ASSETS / f'models/block/{rest}.json').read_text(encoding='utf-8'))
            return mesh(model, model['textures'])
        if kind in ('sup', 'straps'):
            bits = rest.split(':')
            asset, bare, scale, flip = bits[0], bits[1] == '1', float(bits[2]), int(bits[3])
            data, prefixes = MODELS[asset]
            lift, centre = B.supine_frame(asset, data, prefixes, bare)
            if kind == 'straps':
                rows, texture = B.strap_rows(B.closed_straps(FITS[asset + ('_bare' if bare else '')], lift, centre, scale, flip))
                return {'parts': [{'name': 'straps', 'start': 0, 'count': len(rows), 'texture': texture}]}, np.array(rows, dtype=np.float32)
            d, v = self.load_yukkuri(asset, bits[4])
            if bare:
                keep = [p for p in d['parts'] if not p['name'].startswith(prefixes)]
                v = np.concatenate([v[p['start']:p['start'] + p['count']] for p in keep])
                n = 0
                for p in keep:
                    p['start'] = n; n += p['count']
                d = {'parts': keep}
            v = v.copy()
            x, y, z = v[:, 0].copy(), v[:, 1].copy(), v[:, 2].copy()
            v[:, 0] = flip * scale * x
            v[:, 1] = B.TOP + scale * (lift - z)
            v[:, 2] = flip * scale * (y - centre)
            ny, nz = v[:, 6].copy(), v[:, 7].copy()
            v[:, 5] *= flip; v[:, 6] = -nz; v[:, 7] = flip * ny
            return d, v
        return super().load(name)

    def tex_of(self, name, part):
        t = part.get('texture')
        if t:
            return R.vanilla(t) if t.startswith('minecraft:') else self.assets / t.split(':', 1)[1]
        if name.startswith('sup:'):
            bits = name.split(':')
            return self.assets / f'textures/entity/{bits[1]}/{bits[5]}.png'
        return super().tex_of(name, part)


def font(size):
    return ImageFont.truetype(FONT, size)


def tile(src, items, label, size, ppu, az=200, el=24):
    im = Image.new('RGBA', size, BG)
    ImageDraw.Draw(im).text((8, 5), label, font=font(15), fill=(0, 0, 0, 255))
    r, _ = R.render(src, items, az=az, el=el, ppu=ppu)
    k = min((size[0] - 10) / r.width, (size[1] - 34) / r.height)
    if k < 1:
        r = r.resize((max(1, int(r.width * k)), max(1, int(r.height * k))), Image.LANCZOS)
    im.paste(r, ((size[0] - r.width) // 2, 30 + (size[1] - 30 - r.height) // 2), r)
    return im


def occupied(asset, scale, expression='afraid', bare=False, flip=1, straps=True):
    key = f'{asset}:{int(bare)}:{scale}:{flip}'
    items = [('ground:20', 8, 0, 0, 0, 1), ('m:restraint_frame_occupied' if straps else 'm:restraint_frame', 8, 0, 0, 0, 1),
             (f'sup:{key}:{expression}', 8, 0, 0, 0, 1)]
    if straps:
        items.append((f'straps:{key}', 8, 0, 0, 0, 1))
    return items


def icon_sheet(src):
    """Inventory sprite at 1x, 4x in a slot, and the hand model (block display: 30, 225 at 16 px per block)."""
    im = Image.new('RGBA', (440, 160), BG)
    d = ImageDraw.Draw(im)
    d.text((8, 5), '物品栏图标（1 倍、4 倍）与手持模型', font=font(15), fill=(0, 0, 0, 255))
    sprite = Image.open(ASSETS / 'textures/item/restraint_frame.png').convert('RGBA')
    slot = Image.new('RGBA', (18, 18), (139, 139, 139, 255)); slot.paste(sprite, (1, 1), sprite)
    im.paste(slot, (20, 70)); im.paste(slot.resize((72, 72), Image.NEAREST), (60, 42))
    r, _ = R.render(src, [('m:restraint_frame', 8, 0, 0, 0, 1)], az=225, el=30, ppu=6)
    im.paste(r, (170, 40 + (110 - r.height) // 2), r)
    hand, _ = R.render(src, [('m:restraint_frame', 8, 0, 0, 0, 1)], az=225, el=30, ppu=1.0)
    slot2 = Image.new('RGBA', (18, 18), (139, 139, 139, 255)); slot2.paste(hand, ((18 - hand.width) // 2, (18 - hand.height) // 2), hand)
    im.paste(slot2, (330, 70)); d.text((300, 100), '方块式 1 倍', font=font(12), fill=(0, 0, 0, 255))
    return im


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    src = Scene(ROOT)
    size = (440, 340)
    empty = [('ground:14', 8, 0, 0, 0, 1), ('m:restraint_frame', 8, 0, 0, 0, 1)]
    sides = [tile(src, empty, f'空台 · {label}', (330, 260), 14, az=az, el=24)
             for label, az in (('南侧（正面）', 180), ('东侧', 90), ('北侧', 0), ('西侧', 270))]
    close = [tile(src, [('m:restraint_frame', 8, 0, 0, 0, 1)], '近看（西南）：西侧铁板压住固定端，皮带越过台边', size, 40, az=240, el=25),
             tile(src, [('m:restraint_frame', 8, 0, 0, 0, 1)], '近看（东南）：东侧带扣，敞开的皮带头带孔', size, 40, az=120, el=25),
             tile(src, empty, '俯视：两条皮带敞开横放', size, 18, az=180, el=88)]
    bodies = [
        tile(src, occupied('reimu', .4), '小（子体 0.4）：皮带收窄、斜拉到边缘固定点', size, 16, az=205, el=30),
        tile(src, occupied('reimu', 1.0), '成体灵梦：额前刘海与身体下沿各一条，脸露出', size, 13, az=205, el=30),
        tile(src, occupied('reimu_deibu', 1.5), '宽（灵胖 1.5）：皮带绕过超出台面的两侧', size, 9, az=205, el=26),
        tile(src, occupied('marisa', 1.0), '成体魔理沙（戴帽）', size, 13, az=205, el=30),
        tile(src, occupied('reimu', 1.0), '成体侧面：皮带从固定端上身、绕回带扣', size, 13, az=95, el=10),
        tile(src, occupied('reimu', 1.0, straps=False), '接入前的游戏内样子：躺在敞开的皮带上', size, 13, az=205, el=30),
    ]
    sheet = Image.new('RGBA', (3 * size[0], 260 + 3 * size[1] + 160), BG)
    for i, im in enumerate(sides):
        sheet.paste(im, (i * 330, 0))
    for i, im in enumerate(close + bodies):
        sheet.paste(im, ((i % 3) * size[0], 260 + (i // 3) * size[1]))
    sheet.paste(icon_sheet(src), (0, 260 + 3 * size[1]))
    sheet.save(OUT / 'current.png')
    (OUT / 'index.html').write_text(
        '<!doctype html><meta charset="utf-8"><title>仰卧固定台</title><h1>仰卧固定台</h1>'
        '<p>低矮结实的深色木台：四根包铁脚，四角铁护角；两条红褐皮带，西侧固定端压在铁压条下，东侧是铁带扣。空台时皮带敞开横放；'
        '有油库里时皮带按个体大小贴身绕过刘海与身体下沿，脸露出，再斜拉回台边的固定点。大小按游戏实际比例。</p>'
        '<p>要你决定：固定台与皮带的外观能否采用。</p>'
        '<img src="current.png" alt="仰卧固定台当前预览" style="max-width:100%">', encoding='utf-8')
    print(OUT / 'current.png')


if __name__ == '__main__':
    main()
