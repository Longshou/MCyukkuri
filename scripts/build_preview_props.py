"""Render current care props from packaged meshes into .local/previews/care-current.

Uses the existing software renderer, Pillow and numpy; no export, browser or game.
Vanilla textures, when used by a mesh, follow render_preview.MCYUKKURI_VANILLA.
"""
from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
from render_preview import Source, render
from build_preview_hub import font

OUT = ROOT / '.local/previews/care-current'
GROUPS = {
    'feeding': [('food_bowl', '食盆 · 空'), ('food_bowl+food_bowl_regular', '食盆 · 油粮'),
                ('food_bowl+food_bowl_wheat', '食盆 · 小麦'), ('food_bowl+food_bowl_forage', '食盆 · 短草')],
    'nests': [('yukkuri_nest', '宠物软垫窝'), ('large_yukkuri_nest', '大编织窝'),
              ('large_wild_nest', '野生大草窝'), ('personal_leaf_nest', '个人叶巢')],
    'toilets': [('yukkuri_toilet', '沙厕 · 干净'), ('yukkuri_toilet_dirty', '沙厕 · 脏'),
                ('ceramic_toilet', '陶厕'), ('covered_toilet', '带盖厕')],
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    source = Source(ROOT)
    for ident, items in GROUPS.items():
        sheet = Image.new('RGB', (1200, 820), '#f3f0e8')
        draw = ImageDraw.Draw(sheet)
        for i, (model, label) in enumerate(items):
            x, y = i % 2 * 600, i // 2 * 410
            picture, _ = render(source, [(name, 8, 0, 0, 0, 1) for name in model.split('+')],   # fills are drawn inside the bowl
                                ppu=24, az=220, el=32, bg=(243, 240, 232, 255))
            picture = ImageOps.contain(picture, (580, 350))
            sheet.paste(picture, (x+(600-picture.width)//2, y+10+(350-picture.height)//2), picture)
            draw.text((x+24, y+370), label, font=font(24), fill='#253c35')
        sheet.save(OUT / f'{ident}.png')
    print(f'Rendered {sum(map(len, GROUPS.values()))} current care props.')


if __name__ == '__main__':
    main()
