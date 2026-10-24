"""Preview sheet of every remains form and water stage beside the living body.

Writes .local/previews/remains/ in the main checkout (or --out). Uses the
runtime meshes, the authored atlases in assembled/ and render.cjs.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
ROOT = LIB.parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
sys.path.insert(0, str(ROOT / 'scripts'))
from build_preview_hub import font  # noqa: E402

RECIPE = json.loads((HERE / 'recipe.json').read_text(encoding='utf-8'))
NAMES = {'reimu': '灵梦', 'marisa': '魔理沙', 'chen': '橙', 'alice': '爱丽丝', 'patchouli': '帕秋莉',
         'marisa_tsumuri': '螺壳魔理沙', 'reimu_wasa': '和灵梦', 'reimu_deibu': '灵胖', 'reimu_wasa_deibu': '和灵胖'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', default=str(ROOT.parent.parent / '.local/previews/remains') if ROOT.parent.name == '.worktrees'
                        else str(ROOT / '.local/previews/remains'))
    parser.add_argument('--models', default='reimu,marisa')
    parser.add_argument('--node', default='node')
    parser.add_argument('--front-only', action='store_true')
    parser.add_argument('--name', default='remains-forms')
    args = parser.parse_args()
    out = Path(args.out)
    work = out.parent / '_work' / 'remains'
    work.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    models = args.models.split(',')

    def raw(path, key):
        image = Image.open(path).convert('RGBA')
        target = work / f'{key}.rgba'
        target.write_bytes(image.tobytes())
        return dict(file=str(target), width=image.size[0], height=image.size[1])

    forms = RECIPE['forms']
    water = RECIPE['water']
    columns = [('living', '活体（比例）', None, None)] + [(name, spec['label'] + f"\n{spec['face_code']} {spec['face']}", name, None) for name, spec in forms.items()]
    columns += [('softened', '水中·泡软\n溺死遗体 6000 tick', 'drowned', 'softened'),
                ('dissolving', '水中·溶化\n24000 tick 沉底残渣', 'drowned', 'dissolving')]
    shared = {p.stem: p for p in (HERE / 'assembled/shared').glob('*.png')}
    views = [('front', 0.0, 0.12)] + ([] if args.front_only else [('three', 0.62, 0.42)])
    jobs = []
    for ident in models:
        living = raw(ASSETS / 'textures/entity' / ident / 'neutral.png', f'{ident}-neutral')
        for key, _, form, stage in columns:
            textures, actor = [], dict(model=ident, x=0)
            if form is None:
                textures.append(living)
                actor.update(expression='neutral', texture=0, transform=dict(sx=1, sy=1, sz=1))
            else:
                spec = forms[form]
                atlas = 'dissolving' if stage == 'dissolving' else form
                face = water['dissolving']['face'] if stage == 'dissolving' else spec['face']
                textures.append(raw(HERE / 'assembled' / ident / f'remains_{atlas}.png', f'{ident}-{atlas}'))
                sx, sy, sz = spec['scale']
                roll = spec.get('roll', 0)
                overlay = spec.get('overlay')
                decal = spec.get('decal')
                if stage:
                    w = water[stage]
                    sx, sy, sz = sx * w['scale'][0], max(.15, sy * w['scale'][1]), sz * w['scale'][2]
                    overlay = w.get('overlay', overlay)
                    decal = w.get('decal', decal)
                    if stage == 'dissolving':
                        roll = 0
                actor.update(expression=face, texture=0, transform=dict(sx=sx, sy=sy, sz=sz, roll=roll))
                if overlay:
                    textures.append(raw(shared[f'overlay_{overlay}'], f'overlay-{overlay}'))
                    actor['overlay'] = len(textures) - 1
                if decal:
                    textures.append(raw(shared[f'decal_{decal["name"]}'], f'decal-{decal["name"]}'))
                    actor['decal'] = dict(texture=len(textures) - 1, radius=decal['radius'] * 16)
            for view, az, el in views:
                jobs.append(dict(textures=textures, actors=[actor], width=300, height=300, az=az, el=el, span=13 if view == 'front' else 14,
                                 centerY=6.5 if view == 'front' else 4.5, out=str(work / f'{args.name}-{ident}-{key}-{view}.rgba')))
    tasks = work / f'{args.name}-tasks.json'
    tasks.write_text(json.dumps(dict(tile=256, jobs=jobs)), encoding='utf-8')
    subprocess.run([args.node, str(HERE / 'render.cjs'), str(tasks)], check=True)

    tile, label_h = 220, 52
    title, small, tiny = font(30), font(17), font(14)
    width = 150 + tile * len(columns)
    height = 90 + len(models) * len(views) * tile + len(models) * 10 + 60
    sheet = Image.new('RGB', (width, height), (238, 234, 224))
    draw = ImageDraw.Draw(sheet)
    draw.text((24, 16), '遗体造型 · 按死因 8 形态与水中阶段（溺死列即未泡水；草稿，待确认）', font=title, fill='#2b2b26')
    for c, (_, label, _, _) in enumerate(columns):
        for k, line in enumerate(label.split('\n')):
            draw.text((150 + c * tile + 8, 58 + k * 18), line, font=small if k == 0 else tiny, fill='#34352e' if k == 0 else '#6a6a60')
    y = 90 + label_h - 40
    for ident in models:
        for view, _, _ in views:
            draw.text((16, y + tile // 2 - 12), NAMES.get(ident, ident) + ('\n正面' if view == 'front' else '\n斜上'), font=small, fill='#2b2b26')
            for c, (key, _, _, _) in enumerate(columns):
                image = Image.frombytes('RGBA', (300, 300), (work / f'{args.name}-{ident}-{key}-{view}.rgba').read_bytes())
                cell = Image.new('RGB', (300, 300), (226, 220, 206) if c % 2 else (232, 227, 214))
                cell.paste(image, mask=image.getchannel('A'))
                sheet.paste(cell.resize((tile - 4, tile - 4)), (150 + c * tile, y))
            y += tile
        y += 10
    draw.text((24, height - 40), '活体为同尺度中性对照；遗体按死因换死亡脸、姿态和贴附表面层。真实伤口、烧伤、油霉、腐败与饱腹塌陷由既有层叠加，本图未画。',
              font=tiny, fill='#55554c')
    target = out / f'{args.name}.png'
    sheet.save(target)
    print(target)


if __name__ == '__main__':
    main()
