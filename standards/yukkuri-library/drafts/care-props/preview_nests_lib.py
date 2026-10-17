import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from PIL import Image, ImageDraw, ImageFont
import render_preview as R

# BEFORE = a checkout of the previous revision (set MCYUKKURI_BEFORE), AFTER = this tree
import os
AFTER = R.Source(R.ROOT)
BEFORE = R.Source(os.environ.get('MCYUKKURI_BEFORE', R.ROOT))
OUT = Path(os.environ.get('MCYUKKURI_OUT', R.ROOT / '.local/previews/props-v2'))
OUT.mkdir(parents=True, exist_ok=True)
F = ImageFont.truetype(os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc'), 14)
FS = ImageFont.truetype(os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc'), 11)
BG = (226, 228, 222, 255)
FACE = 'yuk:reimu:neutral'


def occupants(floor, mode):
    """mode: none | adult | child | both | big (1.2x adult) ; positions in model units, face toward +z/-x camera."""
    out = []
    if mode in ('adult', 'both'):
        out.append((FACE, 8, 0, floor, 0, 1.0, 180))
    if mode == 'big':
        out.append((FACE, 8, 0, floor, 0, 1.2, 180))
    if mode == 'child':
        out.append((FACE, 8, 0, floor, 0, .5, 180))
    return out


def tile(src, items, label, size=(430, 330), ppu=14, az=20, el=34, lock=None):
    im = Image.new('RGBA', size, BG)
    d = ImageDraw.Draw(im)
    d.text((6, 4), label, font=FS, fill=(0, 0, 0, 255))
    r, _ = R.render(src, items, az=az, el=el, ppu=ppu, lock=lock)
    k = min((size[0] - 10) / r.width, (size[1] - 28) / r.height, 1.6)
    if k != 1:
        r = r.resize((max(1, int(r.width * k)), max(1, int(r.height * k))), Image.NEAREST if k > 1 else Image.LANCZOS)
    im.paste(r, ((size[0] - r.width) // 2, 24 + (size[1] - 24 - r.height) // 2), r)
    return im


def sheet(rows, out, title, cols, size=(430, 330)):
    """rows: list of (label, [tile images])"""
    W, H = size
    s = Image.new('RGBA', (W * cols, 30 + H * len(rows)), BG)
    ImageDraw.Draw(s).text((8, 6), title, font=F, fill=(0, 0, 0, 255))
    for ri, tiles in enumerate(rows):
        for ci, t in enumerate(tiles):
            s.paste(t, (ci * W, 30 + ri * H))
    s.convert('RGB').save(OUT / out)
