"""Top-down PNG of the museum layout, drawn from the generator's own data (nothing is built or launched).

    python scripts/render_museum_map.py [output.png]      # default: .local/previews/museum/map.png in the main checkout

Left: lobby, corridor, five halls with their stages, button rows, landing spots and the settlement meadow.
Right/below: the remote courtyards (births, independence, house yukkuri) with their observation decks.
"""
from pathlib import Path
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
from PIL import Image, ImageDraw, ImageFont  # noqa: E402
import build_museum_full as museum  # noqa: E402

COLORS = {'cyan': '#3fd0e0', 'lime': '#9be15d', 'pink': '#ff9ec4', 'red': '#e0523f', 'orange': '#ff9f33', 'yellow': '#f5e050',
          'light_blue': '#6fb7ff', 'purple': '#c08aff', 'green': '#6fe06f', 'white': '#eeeeee'}
FONT = 'C:/Windows/Fonts/msyh.ttc'


def font(size):
    try:
        return ImageFont.truetype(FONT, size)
    except OSError:
        return ImageFont.load_default()


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(r'F:\yukkuri\.local\previews\museum\map.png')
    halls = museum.build_layout()
    stations = [c for h in halls for c in h.contexts]
    S = 6                      # pixels per block, main area
    x_min, x_max, z_min, z_max = -14, 112, -44, 196
    W, H = (x_max - x_min) * S, (z_max - z_min) * S
    remote_h = 330
    img = Image.new('RGB', (W + 40, H + remote_h + 60), '#20242b')
    d = ImageDraw.Draw(img)
    f_s, f_m, f_l = font(11), font(14), font(24)

    def px(x, z):
        return 20 + (x - x_min) * S, 20 + (z - z_min) * S

    def rect(x1, z1, x2, z2, fill=None, outline=None, width=1, scale=None):
        a = px(x1, z1)
        b = px(x2 + 1, z2 + 1)
        d.rectangle([a, b], fill=fill, outline=outline, width=width)

    # corridor and lobby
    rect(-11, museum.LOBBY_Z0, 0, halls[-1].z0 + 31, fill='#3a3f4b', outline='#8a93a6')
    d.text(px(-10.5, museum.LOBBY_Z0 + .3), 'LOBBY / 大厅', font=f_m, fill='#ffd36b')
    for hall in halls:
        rect(0, hall.z0, hall.xe, hall.z0 + 31, fill='#2c3038', outline='#8a93a6', width=2)
        d.text(px(1, hall.z0 - 2.3), f'{hall.id}厅 {hall.title}', font=f_m, fill=hall.hex)
        rect(0, hall.z0 + 14, 0, hall.z0 + 18, fill='#d0d0d0')           # doorway
        rect(2, hall.z0 + 15, hall.xe - 2, hall.z0 + 16, fill='#4a5060')
        for row, x0 in hall.lounges:
            z1, z2 = (24, 29) if row == 'S' else (2, 7)
            rect(x0 + 2, hall.z0 + z1, x0 + 12, hall.z0 + z2, fill='#383d49', outline='#666d7d')
            d.text(px(x0 + 4, hall.z0 + (z1 + z2) / 2 - .6), '展台一览', font=f_s, fill='#9aa3b5')
    # stations
    for ctx in stations:
        south = ctx.row == 'S'
        z1, z2 = (24, 29) if south else (2, 7)
        z0 = ctx.z0
        color = COLORS.get(ctx.color, '#cccccc')
        rect(ctx.x0 + 2, z0 + z1, ctx.x0 + 12, z0 + z2, fill=color, outline='#ffffff')
        rail = z0 + (23 if south else 8)
        rect(ctx.x0 + 1, rail, ctx.x0 + 13, rail, fill=('#c98a3d' if ctx.kind == 'open' else '#8fd3ff'))
        title = f'{ctx.number:02d} {ctx.title}'
        d.text(px(ctx.x0 + 2.3, z0 + z1 + (0.2 if not south else 0.2)), title, font=f_s, fill='#101418')
        d.text(px(ctx.x0 + 2.3, z0 + z1 + 2.2), museum.kind_tag(ctx), font=f_s, fill='#303642')
        # button rows
        front = z0 + (21 if south else 10)
        row2 = z0 + (18 if south else 13)
        n1 = len(ctx.demos) + len(ctx.buttons) + sum(1 for s in ctx.sites if not s.get('button_done'))
        for i in range(n1):
            x, z = px(ctx.x0 + 2 + 2 * i + .5, front + .5)
            d.ellipse([x - 3, z - 3, x + 3, z + 3], fill='#ffe066')
        for i in range(4 if museum.resettable(ctx) else 3):
            x, z = px(ctx.x0 + 3 + 2 * i + .5, row2 + .5)
            d.ellipse([x - 3, z - 3, x + 3, z + 3], fill='#66e0ff')
        # landing spot, facing the stage
        lx, ly, lz, yaw = ctx.land
        x, z = px(lx, lz)
        tip = (x, z - 11) if yaw == 180 else (x, z + 11)
        d.polygon([(x - 6, z), (x + 6, z), tip], fill='#ff4d6d')
    # lobby directory buttons (west and east wall) as dots
    for z in range(museum.LOBBY_Z0 + 1, -4, 2):
        x, y = px(-8.5, z + .5)
        d.ellipse([x - 2, y - 2, x + 2, y + 2], fill='#ff9f33')
        x, y = px(-1.5, z + .5)
        d.ellipse([x - 2, y - 2, x + 2, y + 2], fill='#ff9f33')
    sx, sz = px(museum.SPAWN[0] + .5, museum.SPAWN[2] + .5)
    d.ellipse([sx - 6, sz - 6, sx + 6, sz + 6], outline='#ffffff', width=2)
    d.text((sx + 9, sz - 8), 'spawn', font=f_s, fill='#ffffff')
    # settlement meadow (hall E) and its gate deck
    east = halls[-1]
    rect(east.xe + 2, east.z0, east.xe + 80, east.z0 + 39, fill='#2f5a34', outline='#6fe06f')
    d.text(px(east.xe + 6, east.z0 + 2), '聚落草地（东门外，观察台在东门内侧）', font=f_m, fill='#bff0b5')
    # remote courtyards: drawn to scale in their own strip
    R = 3
    remote = []
    for ctx in stations:
        for r in ctx.regions:
            remote.append((ctx, r))
    ox = 20
    oy = 20 + H + 40
    xs = [r[0] for _, r in remote] + [r[2] for _, r in remote]
    zs = [r[1] for _, r in remote] + [r[3] for _, r in remote]
    d.text((20, oy - 28), '远处庭院（各按比例；每个庭院的观察台在围栏外侧）', font=f_m, fill='#ffd36b')
    # births / independence yard on one row (z 30..70), house yard below it
    row_a = [(c, r) for c, r in remote if r[1] < 100]
    row_b = [(c, r) for c, r in remote if r[1] >= 100]
    xmin_a = min(r[0] for _, r in row_a)
    xmax_a = max(r[2] for _, r in row_a)
    R = min(3.0, (W - 20) / (xmax_a - xmin_a))
    for c, r in row_a:
        a = (ox + (r[0] - xmin_a) * R, oy + (r[1] - 30) * R)
        b = (ox + (r[2] - xmin_a) * R, oy + (r[3] - 30) * R)
        d.rectangle([a, b], fill='#2f5a34', outline='#6fe06f')
    for c in stations:
        for s in c.sites:
            lx, ly, lz, yaw = s['land']
            if lx > 0 and lz < 100 or lx > 0 and c.sid in ('settle',):
                continue
            if lz < 100:
                x, z = ox + (lx - xmin_a) * R, oy + (lz - 30) * R
                d.ellipse([x - 5, z - 5, x + 5, z + 5], fill='#ff4d6d')
                d.text((x - 40, z - 22), s['caption'], font=f_s, fill='#ffffff')
    if row_b:
        xb = min(r[0] for _, r in row_b)
        oy2 = oy + 150
        d.text((ox, oy2 - 18), '家油庭院 (x -130, z 300)', font=f_s, fill='#ffd36b')
        for c, r in row_b:
            a = (ox + 0, oy2)
            b = (ox + (r[2] - r[0]) * R, oy2 + (r[3] - r[1]) * R)
            d.rectangle([a, b], fill='#2f5a34', outline='#6fe06f')
            for s in c.sites:
                lx, ly, lz, yaw = s['land']
                x, z = ox + (lx - r[0]) * R, oy2 + (lz - r[1]) * R
                d.ellipse([x - 5, z - 5, x + 5, z + 5], fill='#ff4d6d')
                d.text((x - 30, z - 22), '观察台', font=f_s, fill='#ffffff')
    d.text((20, 2), '油库里展馆 · 俯视布局   黄点=演示/操作按钮  青点=站内导航  红三角=传送落点(朝向舞台)  橙点=站点墙按钮', font=f_m, fill='#e6e9ef')
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print(out, img.size)


if __name__ == '__main__':
    main()
