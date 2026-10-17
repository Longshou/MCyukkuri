"""Side-view contact sheet of the house locomotion clips (house_crawl, waddle) from runtime models.

Usage: python render_gait.py OUT.png [MODEL_DIR]   (MODEL_DIR defaults to the packaged yukkuri models)
Offline software rasterization only; no game or browser.
"""
from pathlib import Path
import json
import shutil
import subprocess
import sys
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
WORK = ROOT/'.local/house-gait/work'
TILE = (300, 260)
COLUMNS = 6


def font(size):
    return ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', size)


def main():
    out = Path(sys.argv[1]).resolve()
    source_dir = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else ASSETS/'yukkuri'
    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir(parents=True)
    models, textures, shots, labels = {}, {}, [], []
    atlas = Image.new('RGBA', (1024, 1024))
    for i, ident in enumerate(('house_reimu', 'house_marisa')):
        copy = WORK/(ident+'.json')
        shutil.copyfile(source_dir/(ident+'.json'), copy)
        models[ident] = dict(model=copy.relative_to(ROOT).as_posix(), character=True)
        image = Image.open(ASSETS/'textures/entity'/ident/'neutral.png').convert('RGBA')
        atlas.paste(image, (i*256, 0)); textures[ident+'/neutral'] = dict(x=i*256, y=0, size=image.width)
    (WORK/'atlas.rgba').write_bytes(atlas.tobytes())
    plan = [('house_reimu', 'house_crawl', [i*.05 for i in range(12)]),
            ('house_reimu', 'waddle', [i*.1 for i in range(12)]),
            ('house_marisa', 'house_crawl', [i*.05 for i in range(12)]),
            ('house_marisa', 'waddle', [i*.1 for i in range(12)])]
    for ident, clip, times in plan:
        for n, t in enumerate(times):
            key = f'{ident}-{clip}-{n:02d}'
            shots.append(dict(id=key, az=0, el=0.05, span=4.6, centerY=2.3, background='#ece8dc',
                              instances=[dict(id=ident, at=[0, 0, 0], clip=clip, time=t, angle=-1.5708)]))
            labels.append((ident, clip, t))
    scene = dict(models=models, textures=textures, atlasSize=1024, shots=shots, width=TILE[0], height=TILE[1])
    (WORK/'scene.json').write_text(json.dumps(scene), encoding='utf-8')
    subprocess.run(['node', str(HERE/'render_scene.cjs'), str(WORK/'scene.json'), str(WORK)], check=True, cwd=ROOT)
    rows = len(plan)*2
    sheet = Image.new('RGB', (COLUMNS*TILE[0], rows*TILE[1]), '#ece8dc')
    draw = ImageDraw.Draw(sheet)
    for i, (shot, (ident, clip, t)) in enumerate(zip(shots, labels)):
        pixels = Image.frombytes('RGBA', TILE, (WORK/(shot['id']+'.rgba')).read_bytes())
        x, y = (i % COLUMNS)*TILE[0], (i//COLUMNS)*TILE[1]
        tile = Image.new('RGB', TILE, '#ece8dc'); tile.paste(pixels, (0, 0), pixels)
        sheet.paste(tile, (x, y))
        # ground line at the rest foot level for judging contact
        draw.text((x+8, y+4), f'{ident} {clip} {t:.2f}s', font=font(15), fill='#32443a')
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    print(out)


if __name__ == '__main__':
    main()
