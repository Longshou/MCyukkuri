"""Caption software-rendered state flows, facial comparisons and family development."""
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

def cutaway(width, height, card):
    im = Image.new('RGB', (width, height), (45, 57, 50))
    d = ImageDraw.Draw(im)
    lines = [(height*.20, card['title'], 34)]
    lines += [(height*(.40+i*.13), text, 24) for i, text in enumerate(card['lines'])]
    for y, text, size in lines:
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
    raw = cutaway(960, 480, state['card']) if state['card'] else source(name, state['frame'])
    im.paste(raw, (0, 88))
    d = ImageDraw.Draw(im)
    if not state['card']:
        names = {'alice': 'Alice · 成体', 'reimu': '灵梦 · 成体'}
        for actor in state['actors']:
            text = '灵梦 · 遗体' if actor.get('remains') else names[actor['id']]
            if 'stemProgress' in actor and not actor.get('remains'):
                text += f" · 茎进度 {actor['stemProgress']*100:.1f}%"
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
    board_height = ((len(scene['samples'])+2)//3)*420+110
    board = Image.new('RGB', (1800, board_height), BG)
    d = ImageDraw.Draw(board)
    d.text((24, 12), scene['title']+' · 采样分镜', font=font(30), fill=INK)
    for i, t in enumerate(scene['samples']):
        state = min(states, key=lambda s: abs(s['t']-t))
        x, y = (i % 3)*600, 67+(i//3)*420
        d.text((x+16, y), f"{state['t']:.1f}秒 · {state['phase']['label']}", font=font(19), fill=INK)
        raw = cutaway(960, 480, state['card']) if state['card'] else source(name, state['frame'])
        board.paste(raw.resize((600, 300), Image.Resampling.LANCZOS), (x, y+33))
        d = ImageDraw.Draw(board)
        note = state['phase']['note']
        if name == 'abnormal' and state['actors'] and state['t'] >= 7 and state['t'] < 14:
            note = f"当前茎进度 {state['actors'][0].get('stemProgress', 0)*100:.1f}%。" + note
        wrapped(d, note, (x+16, y+342), 568, 17)
    footer = ('离线制作预览，时间压缩；营养仍足够可继续发育，本段为耗竭分支。'
              if name == 'abnormal' else '离线制作预览，动作取当前运行素材。')
    d.text((24, board_height-32), footer, font=font(18), fill=MUTED)
    board.save(OUT/f'{name}-flow-storyboard.png')
    print('Wrote', name, 'flow captions')

faces = Image.new('RGB', (2400, 610), BG)
d = ImageDraw.Draw(faces)
d.text((24, 14), '爱丽丝状态表情｜同一正面 / 同一姿态 / 同一比例', font=font(31), fill=INK)
d.text((24, 58), '蓝虹膜、双高光与细眉参考1.12普通成体；攻击魔演出脸采用标准确认表情，不再使用1.12原帧合成脸。', font=font(22), fill=MUTED)
for i, (face, title, note) in enumerate([
        ('breeding_period', '持续繁殖期 · 中性外形', '细弧眉、平闭嘴；状态是否持续由服务器决定。'),
        ('open_drool', '搜寻与追击 · 标准表情', '标准确认表情 N048 睁眼流口水；这里没有叠加前倾姿态。'),
        ('flushed_tongue', '素鸡立动作 · 标准表情', '标准确认表情 N035 潮红闭眼伸舌；接触后动作全程使用。'),
        ('state_recover', '疲惫恢复 · 项目提示', '上眼睑压低；保留同一蓝虹膜和高光体系。')]):
    faces.paste(Image.open(OUT/'flow-frames'/f'face-{face}.png').convert('RGB'), (i*600, 125))
    d = ImageDraw.Draw(faces)
    d.text((i*600+24, 105), title, font=font(25), fill=INK)
    wrapped(d, note, (i*600+24, 566), 552, 19)
faces.save(OUT/'state-faces.png')
print('Wrote state-faces.png')

if DATA.get('development'):
    growth = Image.new('RGB', (1800, 950), BG)
    d = ImageDraw.Draw(growth)
    d.text((24, 12), '营养仍足够｜茎生发育的另一条分支', font=font(31), fill=INK)
    for i, step in enumerate(DATA['development']):
        x, y = (i % 3)*600, 67+(i//3)*420
        d.text((x+16, y), step['label'], font=font(23), fill=INK)
        raw = Image.open(OUT/'flow-frames'/f'stem-{i}.png').convert('RGB')
        growth.paste(raw.resize((600, 300), Image.Resampling.LANCZOS), (x, y+33))
        d = ImageDraw.Draw(growth)
        wrapped(d, step['note'], (x+16, y+342), 568, 19)
    d.text((24, 918), '按发育进度取样；挂接与成长来自 FamilyGeometry / FamilyRenderer，出生后茎离身沿用 finishStem。', font=font(18), fill=MUTED)
    growth.save(OUT/'stem-development.png')
    print('Wrote stem-development.png')
