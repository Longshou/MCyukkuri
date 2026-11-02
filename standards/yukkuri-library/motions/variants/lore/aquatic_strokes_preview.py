"""Offline sheet: the two water strokes against their real contact geometry, four beats each (catch, mid pull, finish, mid
recovery). bank_beckon: Marisa ashore at a flush bank (the bank block to the clip's `bank.edge`, the tinted band its
waterline `bank.water`), seen from her braid side and from the water; boat_paddle: the captain on her hull's seat, stick
from the props.paddle keys, the band the hull's fixed waterline (aquatic_paddle_preview). Reads the exported runtime clips
(motion_corrections.json); flat-shaded orthographic Pillow render, nothing launches the game. Writes
.local/previews/water-strokes/current.png (MCYUKKURI_PREVIEWS overrides the previews root).

    python standards/yukkuri-library/motions/variants/lore/aquatic_strokes_preview.py
"""
from pathlib import Path
import json, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import aquatic_paddle_preview as A
P, TOOLS = A.P, A.TOOLS
OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', P.ROOT/'.local/previews'))/'water-strokes'


def bank_triangles(edge, depth=12., width=14., back=10.):
    """The bank block's top, water face and braid-side face (model units, -z toward the water) as flat triangles."""
    a, b = -edge, back; quads = [[(-width, 0, a), (width, 0, a), (width, 0, b), (-width, 0, b)],
                                 [(-width, -depth, a), (width, -depth, a), (width, 0, a), (-width, 0, a)],
                                 [(width, -depth, a), (width, 0, a), (width, 0, b), (width, -depth, b)]]
    return [('bank', np.array(q, float)[[0, i, i+1]]) for q in quads for i in (1, 2)]


def main():
    data = json.loads((P.ROOT/A.RUNTIME).read_text(encoding='utf-8')); OUT.mkdir(parents=True, exist_ok=True)
    recipe = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))['clips']; bank = recipe['bank_beckon']['bank']
    stick = TOOLS['paddle_spec'](recipe['boat_paddle']); length = (stick['top_length'], stick['blade_length'])
    beckon, paddle = A.Captain(data, 'bank_beckon', 'bank_beckon'), A.Captain(data, 'boat_paddle')
    form = next(f for f in TOOLS['hull_forms'](paddle.model['units_per_block']) if f['name'] == 'aquatic_basin_hat_turned')
    ground, hull, cell = bank_triangles(bank['edge']), A.hull_triangles(form['name']), (360, 264)
    rows = [('岸边拨水·侧视', [A.labelled(A.draw(beckon.triangles(t, length)+ground, 'side', bank['water'], centre=(-6., 2.)).resize(cell, Image.Resampling.LANCZOS),
                                        f'{t:.1f}s {beat}', '侧视：辫子一侧，水在左') for t, beat in beckon.poses()]),
            ('岸边拨水·正视', [A.labelled(A.draw(beckon.triangles(t, length)+ground, 'front', bank['water'], centre=(0., 2.)).resize(cell, Image.Resampling.LANCZOS),
                                        f'{t:.1f}s {beat}', '正视：自水面看岸') for t, beat in beckon.poses()]),
            ('持棍划水·侧视', [A.labelled(A.draw(paddle.triangles(t, length)+hull, 'side', form['water']).resize(cell, Image.Resampling.LANCZOS),
                                        f'{t:.1f}s {beat}', '侧视：辫子一侧，船头在左') for t, beat in paddle.poses()]),
            ('持棍划水·正视', [A.labelled(A.draw(paddle.triangles(t, length)+hull, 'front', form['water']).resize(cell, Image.Resampling.LANCZOS),
                                        f'{t:.1f}s {beat}', '正视：自船头，辫子在右') for t, beat in paddle.poses()])]
    out = Image.new('RGB', (190+4*cell[0], 70+len(rows)*cell[1]), A.BG); d = ImageDraw.Draw(out)
    d.text((10, 6), '拨水与持棍划水：入水、拉水、出水、回程（离线渲染，非游戏实录）', font=P.preview_font(22), fill=A.INK)
    d.text((10, 40), f'运行片段 bank_beckon / boat_paddle（游戏同读）；岸：平岸，水面低于岸顶 {-bank["water"]:.2f}，岸沿最远 {bank["edge"]:.1f}（模型单位）；船：固定水线；浅蓝为水面以下',
           font=P.preview_font(14), fill=A.MUTED)
    for i, (name, cells) in enumerate(rows):
        y = 70+i*cell[1]; d.text((8, y+cell[1]//2-10), name, font=P.preview_font(16), fill=A.INK)
        for j, image in enumerate(cells): out.paste(image, (190+j*cell[0], y))
    out.save(OUT/'current.png'); print(OUT/'current.png', flush=True)


if __name__ == '__main__':
    main()
