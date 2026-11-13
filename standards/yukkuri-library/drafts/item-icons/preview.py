"""Offline before/after page of the whole creative tab 油库里世界 at native size (local preview only, nothing is shipped).

Before: the tab of a reference checkout (default: the main checkout this worktree belongs to) with each item drawn the way the
game drew it: 3D prop meshes and element models rendered with the care-props preview renderer at the GUI camera and scaled
down to the slot, sprites as they are, spawn eggs from the vanilla template. After: this tree, where the icons of draw.py fill
the slot. Both at GUI scale 3 in the creative-inventory grid, plus 8x sheets of the new icons beside the reference sprites.
Vanilla textures are read from MCYUKKURI_VANILLA (default .local/vanilla-ref/tex/assets/minecraft); nothing vanilla is copied
into the repository. Output: <MCYUKKURI_PREVIEWS or the main checkout's .local/previews>/item-style-cleanup/.
Usage: python preview.py [--before <checkout>]
"""
import argparse, html, importlib.util, json, math, os, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
MAIN = ROOT.parents[1] if ROOT.parent.name == '.worktrees' else ROOT
os.environ.setdefault('MCYUKKURI_VANILLA', str(MAIN / '.local/vanilla-ref/tex/assets/minecraft'))   # a worktree reads the main checkout's copy
sys.path.insert(0, str(HERE.parent / 'care-props'))
import render_preview as R
spec = importlib.util.spec_from_file_location('item_icon_draw', HERE / 'draw.py')
draw = importlib.util.module_from_spec(spec); spec.loader.exec_module(draw)

PREVIEWS = Path(os.environ.get('MCYUKKURI_PREVIEWS', MAIN / '.local/previews'))
OUT = PREVIEWS / 'item-style-cleanup'
VANILLA = R.VANILLA
FONT = os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc')
F, FS = ImageFont.truetype(FONT, 18), ImageFont.truetype(FONT, 13)
BG = (236, 238, 232, 255)
SCALE = 3
EGGS = {'reimu': (0xF1D5AA, 0xB52E36), 'marisa': (0xF1D5AA, 0x342D38), 'house_reimu': (0xD9C39D, 0xB52E36),
        'house_marisa': (0xD9C39D, 0x342D38), 'chen': (0xF1D5AA, 0x497647), 'alice': (0xF1D5AA, 0xC93D4E),
        'patchouli': (0xF1D5AA, 0xAD87BA), 'remilia': (0xA8C4E8, 0xC63642), 'flandre': (0xEFCA65, 0xD03848),
        'myon': (0xDDE2E6, 0x2C2A30), 'oniisan': (0x333b48, 0xa24e42), 'caretaker_brother': (0x486f66, 0xebd6ad),
        'caretaker_sister': (0xad596b, 0xf0d4aa)}
# ModCreativeTabs order (the human-world and freezer entries are appended by their listeners).
TAB = ['field_notebook', 'reimu_spawn_egg', 'marisa_spawn_egg', 'marisa_aquatic_hat', 'junk_headwear', 'chen_spawn_egg',
       'alice_spawn_egg', 'patchouli_spawn_egg', 'myon_spawn_egg', 'remilia_spawn_egg', 'flandre_spawn_egg', 'predator_nest',
       'remilia_bonnet', 'flandre_bonnet', 'house_reimu_spawn_egg', 'house_marisa_spawn_egg', 'yukkuri_carrier',
       'yukkuri_carrier_fine', 'yukkuri_carrier_large', 'food_bowl', 'yukkuri_nest', 'large_yukkuri_nest', 'large_wild_nest',
       'yukkuri_toilet', 'cardboard_shelter', 'crate_shelter', 'forage_pile', 'wash_cloth', 'household_guard', 'yarn_ball',
       'teaching_whistle', 'magnifying_glass', 'companion_cord', 'pet_harness', 'swaddle_cloth', 'yukkuri_stem',
       'oil_grain_budget', 'oil_grain_regular', 'oil_grain_premium', 'sweet_candy', 'custard_pudding', 'orange_juice',
       'flour_paste', 'mold_remover', 'pin_tool', 'care_mallet', 'care_awl', 'restraint_frame', 'wrap_sling', 'hanging_hook',
       'care_vibrator', 'iron_enclosure', 'newspaper_mat', 'soil_mat', 'sandpaper_mat', 'ceramic_toilet', 'covered_toilet',
       'glass_marble', 'rubber_ball', 'reimu_doll', 'marisa_doll', 'pretty_stone', 'clear_dice', 'paper_tunnel',
       'ceramic_tunnel', 'yukkuri_slide', 'yukkuri_trampoline', 'yukkuri_pool', 'yogurt_drink', 'stimulant', 'sitataran',
       'sleep_spray', 'spicy_grain', 'reproductive_filling', 'wheat_flour', 'oniisan_spawn_egg', 'caretaker_brother_spawn_egg',
       'caretaker_sister_spawn_egg', 'bronze_yukkuri_badge', 'silver_yukkuri_badge', 'gold_yukkuri_badge',
       'platinum_yukkuri_badge', 'badge_handbook', 'exam_podium', 'exam_hide_box', 'exam_mark', 'cold_storage']
PROP_NAME = {'marisa_aquatic_hat': 'aquatic_basin_hat'}


class Tree:
    def __init__(self, root):
        self.root = Path(root)
        self.assets = self.root / 'src/main/resources/assets/mcyukkuri'
        self.src = R.Source(self.root)
        self.lang = json.loads((self.assets / 'lang/zh_cn.json').read_text(encoding='utf-8'))

    def has(self, ident):
        return (self.assets / f'models/item/{ident}.json').is_file()

    def name(self, ident):
        return self.lang.get(f'item.mcyukkuri.{ident}') or self.lang.get(f'block.mcyukkuri.{ident}') or ident

    def model(self, ident):
        return json.loads((self.assets / f'models/item/{ident}.json').read_text(encoding='utf-8'))

    def texture(self, ref):
        ns, path = ref.split(':', 1) if ':' in ref else ('minecraft', ref)
        base = VANILLA if ns == 'minecraft' else self.assets
        return Image.open(base / f'textures/{path}.png').convert('RGBA').crop((0, 0, 16, 16))

    def slot(self, ident):
        """The item as the inventory slot shows it, 16*SCALE pixels square."""
        size = 16 * SCALE
        icon = self.assets / f'models/item/icon/{ident}.json'
        if icon.is_file():
            return self.texture(json.loads(icon.read_text(encoding='utf-8'))['textures']['layer0']).resize((size, size), Image.NEAREST)
        if ident.endswith('_spawn_egg'):
            return egg(EGGS[ident[:-len('_spawn_egg')]]).resize((size, size), Image.NEAREST)
        model = self.model(ident)
        if model.get('loader') == 'neoforge:separate_transforms':
            model = model['perspectives']['gui']
        parent = model.get('parent', '')
        if parent == 'minecraft:item/generated':
            return self.texture(model['textures']['layer0']).resize((size, size), Image.NEAREST)
        if parent == 'minecraft:builtin/entity':
            return fit(R.render(self.src, [(PROP_NAME.get(ident, ident), 8, 0, 0, 0, 1)], ppu=24, az=225, el=30)[0])
        if parent.startswith('mcyukkuri:block/'):
            model = json.loads((self.assets / f'models/block/{parent.split("/", 1)[1]}.json').read_text(encoding='utf-8'))
        return fit(elements(self, model))


def fit(img):
    """Shrink a render into the slot the way the GUI draws a 3D item at this scale (smooth, about 13/16 of the slot)."""
    size = 16 * SCALE
    box = img.getbbox()
    img = img.crop(box) if box else img
    k = 13 * SCALE / max(img.size)
    img = img.resize((max(1, round(img.width * k)), max(1, round(img.height * k))), Image.LANCZOS)
    out = Image.new('RGBA', (size, size))
    out.alpha_composite(img, ((size - img.width) // 2, (size - img.height) // 2))
    return out


def egg(colours):
    base, spots = Image.open(VANILLA / 'textures/item/spawn_egg.png').convert('RGBA'), Image.open(VANILLA / 'textures/item/spawn_egg_overlay.png').convert('RGBA')
    out = Image.new('RGBA', (16, 16))
    for layer, colour in ((base, colours[0]), (spots, colours[1])):
        rgb = np.array([(colour >> 16) & 255, (colour >> 8) & 255, colour & 255], dtype=np.float32) / 255
        a = np.array(layer, dtype=np.float32)
        a[..., :3] *= rgb
        out.alpha_composite(Image.fromarray(a.astype(np.uint8), 'RGBA'))
    return out


class _Elements:
    """A block model's cuboids as a care-props renderer source (textures resolved through the model's own table)."""
    def __init__(self, tree, model):
        self.tree, self.model = tree, model

    def load(self, name):
        textures = dict(self.model.get('textures', {}))
        rows, parts = [], []
        for element in self.model['elements']:
            a, b = element['from'], element['to']
            rot = element.get('rotation')
            for face, info in element['faces'].items():
                ref = info['texture']
                while ref.startswith('#'):
                    ref = textures[ref[1:]]
                quad, normal = _face(a, b, face)
                u0, v0, u1, v1 = info.get('uv', [0, 0, 16, 16])
                uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
                pts = [_rotate(p, rot) for p in quad]
                n = _rotate(normal, rot, origin=False)
                start = len(rows)
                for k in (0, 1, 2, 0, 2, 3):
                    (x, y, z), (u, v) = pts[k], uvs[k]
                    rows.append([x - 8, y, z - 8, u / 16, v / 16, *n])
                ns, path = ref.split(':', 1) if ':' in ref else ('minecraft', ref)
                parts.append({'name': face, 'start': start, 'count': 6, 'texture': f'{ns}:textures/{path}.png'})
        arr = np.array(rows, dtype=np.float32)
        return {'parts': parts, 'bounds': [[float(arr[:, i].min()), float(arr[:, i].max())] for i in range(3)]}, arr

    def tex_of(self, name, part):
        ns, path = part['texture'].split(':', 1)
        return (R.VANILLA if ns == 'minecraft' else self.tree.assets) / path


def _face(a, b, face):
    (x0, y0, z0), (x1, y1, z1) = a, b
    return {'north': ([(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)], (0, 0, -1)),
            'south': ([(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)], (0, 0, 1)),
            'west': ([(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)], (-1, 0, 0)),
            'east': ([(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)], (1, 0, 0)),
            'up': ([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (0, 1, 0)),
            'down': ([(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], (0, -1, 0))}[face]


def _rotate(p, rot, origin=True):
    if not rot:
        return p
    angle = math.radians(rot['angle'])
    o = rot['origin'] if origin else (0, 0, 0)
    x, y, z = (p[i] - o[i] for i in range(3))
    c, s = math.cos(angle), math.sin(angle)
    axis = rot['axis']
    if axis == 'x': y, z = y * c - z * s, y * s + z * c
    elif axis == 'y': x, z = x * c + z * s, -x * s + z * c
    else: x, y = x * c - y * s, x * s + y * c
    return (x + o[0], y + o[1], z + o[2])


def elements(tree, model):
    return R.render(_Elements(tree, model), [('model', 8, 0, 0, 0, 1)], ppu=24, az=225, el=30)[0]


def grid(tree, idents, cols=9):
    """Creative-inventory grid at GUI scale 3: grey slots with the vanilla bevel."""
    s = 18 * SCALE
    rows = (len(idents) + cols - 1) // cols
    img = Image.new('RGBA', (cols * s + 2 * SCALE, rows * s + 2 * SCALE), (198, 198, 198, 255))
    d = ImageDraw.Draw(img)
    for k, ident in enumerate(idents):
        x, y = SCALE + (k % cols) * s, SCALE + (k // cols) * s
        d.rectangle((x, y, x + s - 1, y + s - 1), fill=(139, 139, 139, 255))
        d.line((x, y, x + s - 1, y), fill=(55, 55, 55, 255), width=SCALE)
        d.line((x, y, x, y + s - 1), fill=(55, 55, 55, 255), width=SCALE)
        d.line((x, y + s - 1, x + s - 1, y + s - 1), fill=(255, 255, 255, 255), width=SCALE)
        d.line((x + s - 1, y, x + s - 1, y + s - 1), fill=(255, 255, 255, 255), width=SCALE)
        img.alpha_composite(tree.slot(ident), (x + SCALE, y + SCALE))
    return img


def sheet(tree, before):
    """Every new icon at 8x beside its old slot look, with its name."""
    cols, cw, ch = 4, 300, 170
    names = list(draw.ICONS)
    out = Image.new('RGBA', (cols * cw + 20, ((len(names) + cols - 1) // cols) * ch + 20), BG)
    d = ImageDraw.Draw(out)
    for k, ident in enumerate(names):
        x, y = 10 + (k % cols) * cw, 10 + (k // cols) * ch
        d.rounded_rectangle((x, y, x + cw - 10, y + ch - 10), 8, fill=(250, 250, 246, 255), outline=(200, 200, 190, 255))
        d.text((x + 8, y + 4), f'{tree.name(ident)}  {ident}', font=FS, fill=(40, 40, 40, 255))
        d.rectangle((x + 8, y + 26, x + 8 + 54, y + 26 + 54), fill=(139, 139, 139, 255))
        out.alpha_composite(before.slot(ident), (x + 11, y + 29))
        d.text((x + 68, y + 45), '→', font=F, fill=(90, 90, 90, 255))
        d.rectangle((x + 92, y + 26, x + 92 + 127, y + 26 + 127), fill=(139, 139, 139, 255))
        out.alpha_composite(draw.ICONS[ident]().image().resize((128, 128), Image.NEAREST), (x + 92, y + 26))
        out.alpha_composite(draw.ICONS[ident]().image(), (x + 232, y + 30))
    return out


def page(before, after, removed):
    gone = '、'.join(f'{before.name(i)}（{i}）' for i in removed)
    note = ('上：改前的物品栏（3D 道具在格子里的样子）；下：改后。新图标是 16×16 原版风格像素图，与小麦粉糊、油粮、鬼威惨工具同一画法'
            '（左上受光、每种材质一条色阶、轮廓取本材质最暗一阶、无黑边无抗锯齿）。只换物品栏里的图标；放下、拿在手里、叼在嘴里、'
            '掉在地上和展示框里仍是原来的立体模型。饰物（帽子、软帽）的图标会显示破损、剪断和徽章，保留立体。')
    return f"""<!doctype html><meta charset="utf-8"><title>物品画风统一与清理 · 物品栏新旧对比</title>
<style>body{{font-family:'Microsoft YaHei',sans-serif;background:#eceee8;margin:24px;color:#222}}img{{image-rendering:pixelated;max-width:100%}}
h1{{font-size:22px}}p{{max-width:1100px;line-height:1.6}}</style>
<h1>物品画风统一与清理 · 物品栏新旧对比</h1>
<p>{html.escape(note)}</p>
<p>{html.escape('删除或并入（旧存档自动换成右侧物品）：报纸／泥土／砂纸垫料 → 白／棕／黄地毯；陶瓷油厕、带盖油厕 → 沙厕；纸筒 → 陶管；橡胶球 → 毛线球；透明骰子 → 玻璃弹珠。改前共 ' + str(len(TAB)) + ' 格，删去：' + gone)}</p>
<p>要你决定：新图标是否可以（可逐件指出要改的）；删除和合并是否同意。</p>
<h2>改前（GUI 缩放 3）</h2><img src="before.png">
<h2>改后（GUI 缩放 3）</h2><img src="after.png">
<h2>实际 1× 大小（左改前、右改后）</h2><img src="native.png">
<h2>逐件新旧对比（8 倍，右上为 1×）</h2><img src="icons.png">
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', default=str(MAIN), help='checkout holding the old tab (default: the main checkout)')
    args = parser.parse_args()
    before, after = Tree(args.before), Tree(ROOT)
    old = [i for i in TAB if before.has(i)]
    new = [i for i in TAB if after.has(i)]
    OUT.mkdir(parents=True, exist_ok=True)
    grid(before, old).save(OUT / 'before.png')
    grid(after, new).save(OUT / 'after.png')
    small = [img.resize((img.width // SCALE, img.height // SCALE), Image.NEAREST) for img in (grid(before, old), grid(after, new))]
    native = Image.new('RGBA', (small[0].width + small[1].width + 30, max(i.height for i in small) + 20), BG)
    native.alpha_composite(small[0], (10, 10)); native.alpha_composite(small[1], (20 + small[0].width, 10))
    native.save(OUT / 'native.png')
    sheet(after, before).save(OUT / 'icons.png')
    (OUT / 'index.html').write_text(page(before, after, [i for i in old if i not in new]), encoding='utf-8')
    print(OUT / 'index.html')


if __name__ == '__main__':
    main()
