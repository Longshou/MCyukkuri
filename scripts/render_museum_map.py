"""Top-down PNG of the playground layout, drawn from the generator's own data (nothing is built or launched).

    python scripts/render_museum_map.py [output.png]      # default: .local/previews/museum/map.png in the main checkout

The lobby plaza, every field with its scenario anchors, its observation deck and the pond / cave details.
"""
from pathlib import Path
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
from PIL import Image, ImageDraw, ImageFont  # noqa: E402
import build_museum_full as museum  # noqa: E402
import museum_fields  # noqa: E402

FONT = 'C:/Windows/Fonts/msyh.ttc'
GROUP_COLORS = {'wild': '#b08a5a', 'biome': '#3fa05a', 'family': '#6fe06f', 'react': '#9bd0ff', 'aqua': '#6fb7ff', 'house': '#ffb36b', 'human': '#c9c9a0', 'misc': '#c08aff'}


def font(size):
    try:
        return ImageFont.truetype(FONT, size)
    except OSError:
        return ImageFont.load_default()


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(r'F:\yukkuri\.local\previews\museum\map.png')
    fields = museum.FIELDS
    lx1, lz1, lx2, lz2 = museum.LOBBY
    x_min = min([lx1] + [f.deck[0] - 4 for f in fields] + [f.x1 - 4 for f in fields])
    x_max = max([lx2 + 60] + [f.x2 + 60 for f in fields])
    z_min = min([lz1] + [f.deck[1] - 4 for f in fields])
    z_max = max([lz2] + [f.gate[1] + 4 for f in fields])
    S = 1.6
    W, H = int((x_max - x_min) * S) + 40, int((z_max - z_min) * S) + 40
    img = Image.new('RGB', (W, H), '#20242b')
    d = ImageDraw.Draw(img)
    f_s, f_m, f_l = font(11), font(15), font(24)

    def px(x, z):
        return 20 + (x - x_min) * S, 20 + (z - z_min) * S

    def rect(x1, z1, x2, z2, fill=None, outline=None, width=1):
        d.rectangle([px(x1, z1), px(x2 + 1, z2 + 1)], fill=fill, outline=outline, width=width)

    rect(lx1, lz1, lx2, lz2, fill='#3a3f4b', outline='#8a93a6', width=2)
    d.text(px(lx1 + 2, lz1 + 2), 'LOBBY / 大厅（站点墙：每行一个场地）', font=f_m, fill='#ffd36b')
    sx, sz = museum.SPAWN[0], museum.SPAWN[2]
    rect(sx - 1, sz - 1, sx + 1, sz + 1, fill='#ffd36b')
    for f in fields:
        color = GROUP_COLORS.get(f.group, '#ffffff')
        rect(f.x1, f.z1, f.x2, f.z2, fill='#2c3038', outline=color, width=2)
        d.text(px(f.x1 + 1, f.z1 + 1), f'{f.title}（{dict(museum.GROUPS)[f.group]}）', font=f_m, fill=color)
        dx1, dz1, dx2, dz2 = f.deck
        rect(dx1, dz1, dx2, dz2, fill='#7a5a3a', outline='#d0b080')
        d.text(px(dx1 + 1, dz1 + 1), f'观察台 · {f.title}', font=f_s, fill='#ffffff')
        for s in f.scenarios:
            ax, az = s.anchor
            r = 1.2
            d.ellipse([px(ax - r, az - r), px(ax + r, az + r)], fill=color)
        if f.id == 'pond':
            for x in range(f.px - f.RX - 1, f.px + f.RX + 2):
                for z in range(f.pz - f.RZ - 1, f.pz + f.RZ + 2):
                    if f.depth(x, z):
                        rect(x, z, x, z, fill='#2d6fbf')
            rect(f.bank[0], f.bank[2], f.bank[0], f.bank[2], fill='#ffd36b')
        if f.id == 'den':
            for name, (x, y, z) in f.doorways.items():
                rect(x - 2, z - 6, x + 2, z + 1, fill='#6b4f33', outline='#b08a5a')
                d.text(px(x - 2, z + 2), name, font=f_s, fill='#ffffff')
        if f.id == 'biomes':
            for plot, ppx, ppz in f.plots:
                x1, z1, x2, z2 = f.plot_rect(ppx, ppz)
                rect(x1, z1, x2, z2, outline=color)
                d.text(px(x1 + 1, z1 + 1), plot[5], font=f_s, fill='#ffffff')
        if f.id == 'house':
            rect(f.cx - f.HX, f.cz - f.HZ, f.cx + f.HX, f.cz + f.HZ, fill='#8a6a44', outline='#ffb36b')
        gx, gz = f.gate
        rect(gx - 3, gz - 1, gx + 3, gz + 1, fill='#4a5568', outline='#ffd36b')
        d.text(px(gx + 4, gz - 2), '出口', font=f_s, fill='#ffd36b')
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print(out)


if __name__ == '__main__':
    main()
