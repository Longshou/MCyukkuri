"""Offline review sheets for the junk headwear (local only; nothing here ships).

Poses the runtime models as the game loads them (placement fit, motion corrections), hides the accessory, and seats each
junk prop with the exported anchor in the features frame, exactly as YukkuriModel.renderJunkHat. Renders with the care-prop
software rasterizer. Writes PNG sheets to .local/previews/junk-headwear/ (or --out).

  forms   every runtime form at rest wearing each of the three props
  hero    Reimu and Marisa with each prop at a three-quarter view, beside their own bow and hat
  motion  Reimu and Marisa wearing each prop through moving clips
  icon    the item sprite at 1x, 2x and 8x on light and dark slots
"""
import argparse, json, math, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/motions/variants/lore'))
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
sys.path.insert(0, str(HERE))
import placement_fit  # noqa: E402
import render_preview as rp  # noqa: E402
from preview import Posed, preview_font  # noqa: E402
import build  # noqa: E402

NAMES = {'reimu': '灵梦', 'marisa': '魔理沙', 'alice': '爱丽丝', 'chen': '橙', 'patchouli': '帕秋莉', 'remilia': '蕾米莉亚',
         'flandre': '芙兰朵露', 'myon': '妖梦', 'house_reimu': '家油灵梦', 'house_marisa': '家油魔理沙'}
LABELS = {'paper_box': '纸盒', 'bottle_cap': '瓶盖', 'ice_cream_cone': '冰淇淋筒'}
SKIP = ('face_effect', 'face_tear', 'face_blush')


class Bare(Posed):
    """A Posed model with the placement fit applied and its accessory hidden (or kept for the native comparison)."""
    def __init__(self, ident, corrections, accessory=False):
        super().__init__(ident, corrections)
        m = self.model = placement_fit.fitted(ASSETS / 'yukkuri' / f'{ident}.json')
        V = np.array(m['vertices']).reshape(-1, 8); W = np.array(m['weights'])
        hidden = () if accessory else build.prefixes()[ident]
        keep = [i for p in m['parts'] if not p.get('anatomy') and not p['name'].startswith(('anatomy', 'peni', 'mamu') + SKIP)
                and not (hidden and p['name'].startswith(hidden)) for i in range(p['start'], p['start'] + p['count'])]
        self.v, self.uv, self.n = V[keep, :3], V[keep, 3:5], V[keep, 5:8]
        self.a, self.b, self.q = W[keep, 0].astype(int), W[keep, 1].astype(int), W[keep, 2:3]
        self.features = [b['name'] for b in m['bones']].index('features_squash')


class Scene:
    """care-props render_preview source holding already-placed triangles."""
    def __init__(self):
        self.items = {}

    def add(self, name, rows, texture):
        self.items[name] = ({'parts': [{'name': name, 'start': 0, 'count': len(rows)}], 'bounds': None}, rows.astype(np.float32), texture)

    def load(self, name):
        data, rows, _ = self.items[name]
        return data, rows

    def tex_of(self, name, part):
        return self.items[name][2]


def junk_rows(variant, model_id, matrix):
    mesh = json.loads((ASSETS / f'yukkuri_props/junk_{variant}.json').read_text(encoding='utf-8'))
    seat = mesh['anchors'][model_id]
    v = np.array(mesh['vertices']).reshape(-1, 8).copy()
    R = build.rotation(*seat['rotation'])
    local = (R @ (v[:, :3] * seat['scale']).T).T + seat['position']
    h = np.c_[local, np.ones(len(local))]
    v[:, :3] = (matrix @ h.T).T[:, :3]
    v[:, 5:8] = (matrix[:3, :3] @ (R @ v[:, 5:8].T)).T
    v[:, 5:8] /= np.maximum(np.linalg.norm(v[:, 5:8], axis=1, keepdims=True), 1e-9)
    return v


def scene(model, model_id, variant, clip='idle', t=0.0, face=None):
    tri, uv, normal = model.frame(clip, t)
    rows = np.c_[tri.reshape(-1, 3), uv.reshape(-1, 2), np.repeat(normal, 3, axis=0)]
    s = Scene()
    folder = ASSETS / 'textures/entity' / model_id
    face = face or model.face(clip, t)
    s.add('body', rows, folder / f'{face}.png' if (folder / f'{face}.png').exists() else folder / 'neutral.png')
    items = [('body', 99, 0, 0, 0, 1)]
    if variant:
        s.add('junk', junk_rows(variant, model_id, model.last_matrices[model.features]), ASSETS / f'textures/props/junk_{variant}.png')
        items.append(('junk', 99, 0, 0, 0, 1))
    return s, items


def panel(s, items, size, az, el, ppu, centre_y=None, bg=(214, 222, 205, 255)):
    lock = None
    if centre_y is not None:
        half_w, half_h = size[0] / 2 / ppu, size[1] / 2 / ppu
        lock = (-half_w, half_w, centre_y - half_h, centre_y + half_h)
    image, _ = rp.render(s, items, az=az, el=el, ppu=ppu, size=size, lock=lock, bg=bg)
    return image


def label(image, text, font, at=(6, 4)):
    ImageDraw.Draw(image).text(at, text, font=font, fill='#2f3428')


def corrections():
    return json.loads((ASSETS / 'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))


def forms_sheet(out):
    fixes, font = corrections(), preview_font(16)
    ids = build.runtime_models()
    W, Hh = 220, 200
    sheet = Image.new('RGBA', (W * 4, Hh * len(ids)), (236, 234, 224, 255))
    for row, ident in enumerate(ids):
        model = Bare(ident, fixes)
        top = float(model.v[:, 1].max())
        ppu = 9 if not ident.startswith('house') else 22
        for col, variant in enumerate((None,) + build.VARIANTS):
            s, items = scene(model, ident, variant)
            image = panel(s, items, (W, Hh), 200, 18, ppu, centre_y=top * .55)
            label(image, f'{NAMES.get(ident, ident)} · {LABELS.get(variant, "无饰物")}', font)
            sheet.paste(image, (col * W, row * Hh))
    sheet.save(out / 'forms.png')


def hero_sheet(out):
    fixes, font = corrections(), preview_font(20)
    W, Hh = 360, 330
    sheet = Image.new('RGBA', (W * 4, Hh * 2), (236, 234, 224, 255))
    for row, ident in enumerate(('reimu', 'marisa')):
        native = Bare(ident, fixes, accessory=True)
        bare = Bare(ident, fixes)
        s, items = scene(native, ident, None)
        image = panel(s, items, (W, Hh), 215, 16, 15, centre_y=8 if ident == 'reimu' else 11)
        label(image, f'{NAMES[ident]} · 自己的饰物', font); sheet.paste(image, (0, row * Hh))
        for col, variant in enumerate(build.VARIANTS, 1):
            s, items = scene(bare, ident, variant)
            image = panel(s, items, (W, Hh), 215, 16, 15, centre_y=8 if ident == 'reimu' else 11)
            label(image, f'{NAMES[ident]} · {LABELS[variant]}', font); sheet.paste(image, (col * W, row * Hh))
    sheet.save(out / 'hero.png')


def motion_sheet(out):
    fixes, font = corrections(), preview_font(15)
    frames = [('idle', 0.0), ('hop', .35), ('stretch', .6), ('press', .6), ('turn', .4), ('side_bend', .5)]
    W, Hh = 200, 210
    sheet = Image.new('RGBA', (W * len(frames), Hh * 6), (236, 234, 224, 255))
    row = 0
    for ident in ('reimu', 'marisa'):
        model = Bare(ident, fixes)
        for variant in build.VARIANTS:
            for col, (clip, t) in enumerate(frames):
                clip_t = t * model.clips[clip]['duration']
                s, items = scene(model, ident, variant, clip, clip_t)
                image = panel(s, items, (W, Hh), 235, 12, 9, centre_y=8)
                label(image, f'{NAMES[ident]} {LABELS[variant]} · {clip} {clip_t:.2f}s', font)
                sheet.paste(image, (col * W, row * Hh))
            row += 1
    sheet.save(out / 'motion.png')


def props_sheet(out):
    font = preview_font(16)
    views = [(210, 20), (330, 20), (60, 15), (200, 70), (200, -45)]
    W, Hh = 300, 280
    sheet = Image.new('RGBA', (W * len(views), Hh * 3), (236, 234, 224, 255))
    for row, variant in enumerate(build.VARIANTS):
        mesh = json.loads((ASSETS / f'yukkuri_props/junk_{variant}.json').read_text(encoding='utf-8'))
        s = Scene(); s.add('junk', np.array(mesh['vertices']).reshape(-1, 8), ASSETS / f'textures/props/junk_{variant}.png')
        for col, (az, el) in enumerate(views):
            image = panel(s, [('junk', 99, 0, 0, 0, 1)], (W, Hh), az, el, 24 if variant == 'paper_box' else 34, centre_y=3)
            label(image, f'{LABELS[variant]} · {az}°/{el}°', font); sheet.paste(image, (col * W, row * Hh))
    sheet.save(out / 'props.png')


def icon_sheet(out):
    sprite = Image.open(ASSETS / 'textures/item/junk_headwear.png').convert('RGBA')
    others = [Image.open(ASSETS / f'textures/item/{n}.png').convert('RGBA') for n in ('flour_paste', 'orange_juice', 'oil_grain_budget')]
    sheet = Image.new('RGBA', (560, 220), (139, 139, 139, 255))
    sheet.paste(sprite.resize((128, 128), Image.NEAREST), (10, 10), sprite.resize((128, 128), Image.NEAREST))
    for i, bg in enumerate(((139, 139, 139, 255), (198, 198, 198, 255), (55, 55, 55, 255))):
        x = 160 + i * 130
        ImageDraw.Draw(sheet).rectangle((x, 10, x + 120, 140), fill=bg)
        for j, im in enumerate([sprite] + others):
            big = im.resize((32, 32), Image.NEAREST)
            sheet.paste(big, (x + 6 + (j % 2) * 56, 16 + (j // 2) * 56), big)
        sheet.paste(sprite, (x + 50, 124), sprite)
    sheet.save(out / 'icon.png')


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('sheets', nargs='*', default=['forms', 'hero', 'motion', 'icon'])
    parser.add_argument('--out', type=Path, default=ROOT / '.local/previews/junk-headwear')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for name in args.sheets:
        globals()[name + '_sheet'](args.out)
        print(args.out / f'{name}.png')


if __name__ == '__main__':
    main()
