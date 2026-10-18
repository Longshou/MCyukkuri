"""Caption the current multi-adult software renders. No UI automation or game capture."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / '.local/previews/rapem-alice'
DATA = json.loads((OUT / 'flow-frame-data.json').read_text(encoding='utf-8'))
BG, INK, MUTED = (240, 245, 240), (30, 49, 38), (76, 96, 82)
FONT = 'C:/Windows/Fonts/msyh.ttc'
font = lambda size: ImageFont.truetype(FONT, size)

def wrapped(draw, text, xy, width, size=18, color=MUTED):
    x, y = xy
    f = font(size)
    line = ''
    for char in text:
        if line and draw.textlength(line + char, font=f) > width:
            draw.text((x, y), line, font=f, fill=color)
            y += size + 6
            line = char
        else:
            line += char
    draw.text((x, y), line, font=f, fill=color)

def source(name, frame):
    return Image.open(OUT / 'flow-frames' / f'{name}-{frame:03d}.png').convert('RGB')

def cutaway(width, height):
    im = Image.new('RGB', (width, height), (45, 57, 50))
    d = ImageDraw.Draw(im)
    for y, text, size in [(height*.28, '镜头外 · 事件过程省略', 34),
                          (height*.46, '这里不展示侵犯过程', 24),
                          (height*.61, '下一页：独立的事后后果与照护示例', 24)]:
        f = font(size)
        d.text(((width-d.textlength(text, font=f))/2, y), text, font=f, fill=(237, 244, 236))
    return im

def caption(name, state):
    scene = DATA['scenes'][name]
    im = Image.new('RGB', (960, 650), BG)
    d = ImageDraw.Draw(im)
    d.text((24, 12), scene['title'], font=font(28), fill=INK)
    d.text((24, 52), state['phase']['label'], font=font(22), fill=INK)
    d.text((862, 55), f"{state['t']:04.1f} s", font=font(18), fill=MUTED)
    raw = cutaway(960, 480) if state['card'] else source(name, state['frame'])
    im.paste(raw, (0, 88))
    d = ImageDraw.Draw(im)
    if not state['card']:
        names = {'alice': 'Alice · 成体', 'reimu': '灵梦 · 成体', 'marisa': '魔理沙 · 成年同伴'}
        for actor in state['actors']:
            text = names[actor['id']]
            f = font(18)
            x = 480+actor['x']*16-d.textlength(text, font=f)/2
            d.text((x, 540), text, font=f, fill=MUTED)
    wrapped(d, state['phase']['note'], (24, 580), 912)
    d.rectangle((24, 637, 936, 640), fill=(199, 213, 202))
    d.rectangle((24, 637, 24+912*state['t']/scene['duration'], 640), fill=(86, 126, 94))
    return im

for name, scene in DATA['scenes'].items():
    states = DATA['frames'][name]
    frames = [caption(name, state) for state in states]
    # One palette covers the current cast and the complete cutaway.
    palette = Image.new('RGB', (960, 650*4))
    for i, ratio in enumerate([0, .35, .57, .9]):
        palette.paste(frames[min(len(frames)-1, round((len(frames)-1)*ratio))], (0, i*650))
    palette = palette.quantize(colors=224)
    if len(states) == round(scene['duration']*DATA['fps'])+1:
        quantized = [im.quantize(palette=palette, dither=Image.Dither.NONE) for im in frames]
        quantized[0].save(OUT/f'{name}-flow.gif', save_all=True, append_images=quantized[1:],
                          duration=[round(1000/DATA['fps'])]*(len(frames)-1)+[1000], loop=0, optimize=True, disposal=2)
    board = Image.new('RGB', (1800, 950), BG)
    d = ImageDraw.Draw(board)
    d.text((24, 12), scene['title']+' · 采样分镜', font=font(30), fill=INK)
    for i, t in enumerate(scene['samples']):
        state = min(states, key=lambda s: abs(s['t']-t))
        x, y = (i % 3)*600, 67+(i//3)*420
        d.text((x+16, y), f"{state['t']:.1f}秒 · {state['phase']['label']}", font=font(19), fill=INK)
        raw = cutaway(960, 480) if state['card'] else source(name, state['frame'])
        board.paste(raw.resize((600, 300), Image.Resampling.LANCZOS), (x, y+33))
        d = ImageDraw.Draw(board)
        wrapped(d, state['phase']['note'], (x+16, y+342), 568, 17)
    d.text((24, 918), '离线流程编排，非游戏录屏；行进路线为示意，动作读取当前运行素材。所有出镜角色均为成体。', font=font(18), fill=MUTED)
    board.save(OUT/f'{name}-flow-storyboard.png')
    print('Wrote', name, 'flow captions')

faces = Image.new('RGB', (1800, 610), BG)
d = ImageDraw.Draw(faces)
d.text((24, 14), 'Alice生产表情｜同一正面 / 同一姿态 / 同一比例', font=font(31), fill=INK)
d.text((24, 58), '蓝虹膜、双高光与细眉参考1.12普通成体；异常眉口为项目改编，原作专属状态帧仍未核定。', font=font(22), fill=MUTED)
for i, (face, title, note) in enumerate([
        ('breeding_period', '持续繁殖期 · 中性外形', '细弧眉、平闭嘴；状态是否持续由服务器决定。'),
        ('alice_agitated', '异常状态 · 项目改编', '眉内端压低、紧口；这里没有叠加前倾姿态。'),
        ('state_recover', '疲惫恢复 · 项目提示', '上眼睑压低；保留同一蓝虹膜和高光体系。')]):
    faces.paste(Image.open(OUT/'flow-frames'/f'face-{face}.png').convert('RGB'), (i*600, 125))
    d = ImageDraw.Draw(faces)
    d.text((i*600+24, 105), title, font=font(25), fill=INK)
    wrapped(d, note, (i*600+24, 566), 552, 19)
faces.save(OUT/'state-faces.png')
print('Wrote state-faces.png')
