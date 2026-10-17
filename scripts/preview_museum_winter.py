"""Render the winter exhibit's authored layout, without launching Minecraft or a browser."""
from pathlib import Path
import sys

sys.dont_write_bytecode = True
from PIL import Image, ImageDraw, ImageFont
from museum_dsl import Ctx
from museum_scenes import winter

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.local/previews/museum-winter'


def main():
    c = Ctx('winter', 'N', 0, 0, 'light_blue')
    winter(c)
    blocks = {}
    for command in c.functions['winter_layout']:
        p = command.split()
        if p[0] == 'fill':
            x0, y0, z0, x1, y1, z1 = map(int, p[1:7])
            for x in range(x0, x1 + 1):
                for y in range(y0, y1 + 1):
                    for z in range(z0, z1 + 1):
                        blocks[x, y, z] = p[7].split('[')[0]
        elif p[0] == 'setblock':
            blocks[tuple(map(int, p[1:4]))] = p[4].split('[')[0]
    canvas = Image.new('RGB', (1260, 860), '#edf2f6')
    draw = ImageDraw.Draw(canvas)
    font_path = 'C:/Windows/Fonts/msyh.ttc'
    def text(at, label, size=22, color='#243e4b'):
        draw.text(at, label, font=ImageFont.truetype(font_path, size), fill=color)
    text((45, 28), '冬日巢穴 · 敞开剖面', 36)
    text((47, 84), '俯视布局预览｜按展台生成指令绘制，非游戏截图', 21, '#617681')
    cell = 91
    def rect(u, v):
        return (48 + u * cell, 150 + (5 - v) * cell, 48 + (u + 1) * cell, 150 + (6 - v) * cell)
    colors = {'minecraft:snow_block': '#e0eaf1', 'minecraft:coarse_dirt': '#af8763',
              'minecraft:dirt': '#71513e', 'minecraft:packed_ice': '#9ccfe5'}
    for u in range(11):
        for v in range(6):
            x, z = c.cell(u, v)
            material = blocks.get((x, 66, z), 'minecraft:air')
            if material in ('minecraft:air', 'mcyukkuri:forest_burrow'):
                material = blocks.get((x, 65, z), 'minecraft:snow_block')
            draw.rectangle(rect(u, v), fill=colors.get(material, '#af8763'), outline='#becbd1', width=1)
    # Adjacent shared beds: rendered by ShelterRenderer from the two resident slots.
    for u, label in ((2, '幼体'), (3, '亲油')):
        x0, y0, x1, y1 = rect(u, 3)
        draw.rounded_rectangle((x0 + 4, y0 + 4, x1 - 4, y1 - 4), 14, fill='#879457')
        radius = 14 if u == 2 else 26
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        draw.ellipse((cx-radius, cy-radius, cx+radius, cy+radius), fill='#fff1d7', outline='#aa7952', width=2)
        text((x0 - 55 if u == 2 else x1 + 10, y0 + 31), label, 19)
    def point(u, v):
        return (48 + u * cell, 150 + (5.5 - v) * cell)
    route = [point(8.5, 1), point(3.5, 1), point(3.5, 2.35)]
    draw.line(route, fill='#c66c42', width=6, joint='curve')
    ex, ey = route[-1]
    draw.polygon([(ex, ey-10), (ex-9, ey+8), (ex+9, ey+8)], fill='#c66c42')
    text((680, 570), '从前方入口回巢', 20, '#a04d2b')
    text((165, 166), '雪顶后墙 / 暖光', 22, '#ffffff')
    for u, label in ((1, '麦穗'), (4, '果实')):
        x0, y0, _, _ = rect(u, 4)
        text((x0+14, y0+30), label, 20, '#fff8db')
    text((58, 712), '观众走廊：前方全开，幼体面前没有整块雪或围墙', 25)
    text((58, 764), '积雪顶面与巢内地面齐平；顶部敞开，能从正常观看位置看见叶垫、存粮和动作。', 21, '#617681')
    OUT.mkdir(parents=True, exist_ok=True)
    canvas.save(OUT / 'layout.png')
    print(OUT / 'layout.png')


if __name__ == '__main__':
    main()
