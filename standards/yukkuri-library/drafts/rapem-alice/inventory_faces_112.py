"""Inventory and adapt the 1.12 adult-Alice face sheets.

Reads only the user-provided local package (never packed). Produces:
- .local/previews/rapem-alice/reference-faces-112.png : every eye/brow/mouth frame,
  numbered and measured, for picking exact adaptation frames.
- variant face textures composited from chosen original frames (build step below).
"""
from pathlib import Path
import sys
from PIL import Image, ImageDraw, ImageFont
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
PICT = ROOT / '油库里八景ver1.12/油库里八景ver1.12/pict'
OUT = ROOT / '.local/previews/rapem-alice/reference-faces-112.png'
FONT = Path('C:/Windows/Fonts/msyh.ttc')
TILE = 240


def tiles(path):
    im = Image.open(path)
    im = im.convert('RGBA') if im.mode == 'RGBA' else im
    n = im.height // TILE
    return im, n


def tile_rgba(im, index):
    """240x240 frame; RGB sheets keep their alpha in the adjacent 240 column."""
    y = index * TILE
    if im.mode == 'RGBA':
        return im.crop((0, y, TILE, y + TILE))
    part = im.crop((0, y, TILE, y + TILE)).convert('RGBA')
    part.putalpha(im.crop((TILE, y, TILE * 2, y + TILE)).convert('L'))
    return part


def stats(tile):
    a = np.array(tile)
    op = a[:, :, 3] > 40
    if not op.any():
        return None
    dark = (a[:, :, 3] > 40) & (a[:, :, :3].sum(axis=2) < 330)
    ys, xs = np.nonzero(op)
    return dict(x0=int(xs.min()), x1=int(xs.max()), y0=int(ys.min()), y1=int(ys.max()),
                px=int(op.sum()), dark=int(dark.sum()))


def contact(sheet_rows):
    font = lambda s: ImageFont.truetype(str(FONT), s)
    cols = 8
    cell, pad = 130, 26
    rows = sum((n + cols - 1) // cols for _, _, n in sheet_rows)
    height = 96 + rows * (cell + pad)
    out = Image.new('RGB', (cols * (cell + pad) + pad, height), (244, 242, 236))
    d = ImageDraw.Draw(out)
    d.text((pad, 18), '八景 ver1.12 · 爱丽丝成体表情层全帧清单（240px 网格）', font=font(30), fill='#24272c')
    d.text((pad, 56), '帧号与实测：不透明像素数 px / 深色像素 dark / 内容框。用于选定状态脸适配帧。', font=font(18), fill='#55585d')
    y = 96
    for title, im, n in sheet_rows:
        d.text((pad, y), title + f'（共 {n} 帧）', font=font(22), fill='#24272c')
        y += pad
        for i in range(n):
            x = pad + (i % cols) * (cell + pad)
            if i % cols == 0 and i:
                y += cell + 8
            thumb = tile_rgba(im, i).resize((120, 120), Image.Resampling.NEAREST)
            out.paste(thumb, (x, y), thumb)
            s = stats(tile_rgba(im, i))
            label = f'#{i}' + (f' px{s["px"]} d{s["dark"]}' if s else ' 空')
            d.text((x, y + 122), label, font=font(14), fill='#333' if s else '#999')
        y += cell + 8 + pad
    out.save(OUT)
    print(OUT)


if __name__ == '__main__':
    sheets = []
    for name, rel in [('眼 6.png', 'arisu/sei_new/6.png'), ('眉 7.png', 'arisu/sei_new/7.png'),
                      ('口 8.png', 'base/sei/8.png'), ('頬 9.png', 'base/sei/9.png'),
                      ('op2 14.png', 'arisu/sei_new/14.png')]:
        im, n = tiles(PICT / rel)
        sheets.append((name + ' ' + rel, im, n))
    contact(sheets)
