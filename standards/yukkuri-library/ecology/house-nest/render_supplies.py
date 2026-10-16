"""Offline natural-house and supply diagram using current meshes and real scales.

Staged states explain the intended loop; they are not evidence of game execution.
Only software rasterization runs. No browser, game or compilation is involved.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import math
import re
import subprocess
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
OUT = ROOT/'.local/previews/house-supplies'
WORK = OUT/'_work'
WIDTH, HEIGHT, SPAN, ELEVATION = 560, 360, 9.0, 1.10


def font(size):
    return ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', size)


def project(x, y, z, elevation=ELEVATION):
    scale = HEIGHT/(2*SPAN)
    return (WIDTH/2+x*scale, HEIGHT/2-(y*math.cos(elevation)+z*math.sin(elevation))*scale)


def dashed(draw, start, end, fill, width=2):
    distance = math.dist(start, end)
    for offset in range(0, int(distance), 14):
        a = offset/distance; b = min(1, (offset+8)/distance)
        draw.line((start[0]+(end[0]-start[0])*a, start[1]+(end[1]-start[1])*a,
                   start[0]+(end[0]-start[0])*b, start[1]+(end[1]-start[1])*b), fill=fill, width=width)


def floor_image(corner=False, cleaned=False):
    image = Image.new('RGBA', (WIDTH, HEIGHT), (0, 0, 0, 0)); draw = ImageDraw.Draw(image)
    def at(x, y, z):
        return project(x, y, z, .65 if corner else ELEVATION)
    corners = [at(-8, 0, -8), at(8, 0, -8), at(8, 0, 8), at(-8, 0, 8)]
    if corner:
        draw.polygon([at(-8, 0, 8), at(8, 0, 8), at(8, 5, 8), at(-8, 5, 8)], fill='#c7c4b4')
        draw.polygon([at(-8, 0, -8), at(-8, 0, 8), at(-8, 5, 8), at(-8, 5, -8)], fill='#d3cfbf')
    draw.polygon(corners, fill='#e4ddcc' if cleaned else '#ddd3bc')
    for z in (-4, 0, 4):
        draw.line([at(-8, 0, z), at(8, 0, z)], fill='#cec2a9', width=2)
    for i, start in enumerate(corners):
        dashed(draw, start, corners[(i+1) % 4], fill='#60806c')
    return image


def main():
    OUT.mkdir(parents=True, exist_ok=True); WORK.mkdir(parents=True, exist_ok=True)
    client = ROOT/'src/main/java/io/github/longshou/mcyukkuri/client'
    renderer = client/'SupplyPileRenderer.java'
    nest_renderer = client/'HouseNestRenderer.java'
    code = renderer.read_text(encoding='utf-8')
    raw = re.search(r'CLUSTERS\s*=\s*\{(.*?)\};', code, re.S).group(1)
    clusters = [tuple(float(x) for x in pair.split(',')) for pair in re.findall(r'\{([^{}]+)\}', raw)]
    held = (client/'HouseScrapRenderer.java').read_text(encoding='utf-8')
    scrap_scale = float(re.search(r'HELD_SCALE\s*=\s*([.\d]+)f', held).group(1))*.75
    lint_scale = float(re.search(r'case "nest_lint"->([.\d]+)f', code).group(1))
    recipe_path = HERE/'recipe.json'
    display = json.loads(recipe_path.read_text(encoding='utf-8'))['display']
    nest_be = ROOT/'src/main/java/io/github/longshou/mcyukkuri/house/HouseNestBlockEntity.java'
    material_cost = int(re.search(r'MATERIAL_COST\s*=\s*(\d+)', nest_be.read_text(encoding='utf-8')).group(1))
    recipes = ROOT/'src/main/resources/data/mcyukkuri/recipe'
    scrap_recipe = recipes/'food_scrap.json'; lint_recipe = recipes/'nest_lint.json'
    scrap_yield = json.loads(scrap_recipe.read_text(encoding='utf-8'))['result']['count']
    lint_yield = json.loads(lint_recipe.read_text(encoding='utf-8'))['result']['count']
    guard_source = HERE.parent/'house-guard'
    spec = importlib.util.spec_from_file_location('household_guard_preview', guard_source/'preview.py')
    guard_preview = importlib.util.module_from_spec(spec); spec.loader.exec_module(guard_preview)
    guard_mesh, guard_texture = guard_preview.prepare(WORK)
    models = {}; textures = {}; sources = [renderer, nest_renderer, nest_be, recipe_path, HERE/'render_supplies.py',
        HERE/'render_scene.cjs', scrap_recipe, lint_recipe, client/'HouseScrapRenderer.java',
        ASSETS/'yukkuri_presentation/house_ecology.json', ASSETS/'yukkuri_presentation/motion_corrections.json',
        ASSETS/'yukkuri_presentation/placement_fit.json', guard_source/'recipe.json', guard_source/'build.py',
        guard_source/'preview.py', ASSETS/'models/block/household_guard.json']
    atlas = Image.new('RGBA', (1024, 1024))

    def texture(ident, path):
        image = Image.open(path).convert('RGBA'); index = len(textures)
        x, y = (index % 4)*256, (index//4)*256
        if image.width > 256 or image.height != image.width or index >= 16:
            raise ValueError('Invalid texture or atlas overflow: '+str(path))
        atlas.paste(image, (x, y)); textures[ident] = dict(x=x, y=y, size=image.width); sources.append(path)

    for ident in ('food_scrap', 'nest_lint', 'yukkuri_waste', 'house_lint_bedding'):
        model = ASSETS/'yukkuri_props'/(ident+'.json')
        models[ident] = dict(model=model.relative_to(ROOT).as_posix()); sources.append(model)
        texture(ident, ASSETS/'textures/props'/(ident+'.png'))
    model = ASSETS/'yukkuri/house_reimu.json'
    models['house_reimu'] = dict(model=model.relative_to(ROOT).as_posix(), character=True); sources.append(model)
    texture('house_reimu/neutral', ASSETS/'textures/entity/house_reimu/neutral.png')
    models['household_guard'] = dict(model=guard_mesh.relative_to(ROOT).as_posix())
    texture('household_guard', guard_texture)
    (WORK/'atlas.rgba').write_bytes(atlas.tobytes())

    def pile(ident, count, at=(0, 0, 0)):
        model = json.loads((ASSETS/'yukkuri_props'/(ident+'.json')).read_text(encoding='utf-8'))
        center = [sum(bounds)/2 for bounds in model['bounds']]
        scale = scrap_scale if ident == 'food_scrap' else lint_scale
        instances = []
        for index, (x, z) in enumerate(clusters[:min(count, 8)]):
            angle = -index*137.5*math.pi/180; c, s = math.cos(angle), math.sin(angle)
            # Same local centering, rotation, scale and .025-block anchor as the real renderer.
            instances.append(dict(id=ident, scale=[scale]*3, angle=angle,
                at=[at[0]+x*16-(center[0]*c-center[2]*s)*scale,
                    at[1]+.025*16-center[1]*scale,
                    at[2]+z*16-(center[0]*s+center[2]*c)*scale]))
        return instances

    def block_at(pos):
        return [(pos[0]-.5)*16, pos[1]*16, (pos[2]-.5)*16]

    def bedding(materials):
        if materials <= 0:
            return []
        built = min(1, materials/material_cost); spread = .45+.55*built
        def bed(name):
            scale = display[name+'_scale']
            return dict(id='house_lint_bedding', at=block_at(display[name+'_center']),
                        scale=[scale[0]*spread, scale[1]*built, scale[2]*spread])
        return [bed('sleeping')]+([bed('egg')] if materials >= 3 else [])

    shots = [dict(id='dust', instances=pile('nest_lint', 1)),
             dict(id='natural-home', instances=bedding(3)+[
                 dict(id='house_reimu', at=block_at((.39, .025, .31)), clip='house_peek', time=.75, angle=-.18)]),
             dict(id='guard', instances=[dict(id='household_guard', at=[0,0,0], angle=-.26)])]
    shots.extend(dict(id='count-'+str(count), instances=pile('food_scrap', count)) for count in (1, 8, 64))
    for shot in shots:
        shot.update(span=SPAN, centerY=0, az=0, el=.65 if shot['id'] in ('dust', 'natural-home', 'guard') else ELEVATION)
    scene = dict(models=models, textures=textures, atlasSize=1024, shots=shots, width=WIDTH, height=HEIGHT)
    (WORK/'scene.json').write_text(json.dumps(scene), encoding='utf-8')
    subprocess.run(['node', str(HERE/'render_scene.cjs'), str(WORK/'scene.json'), str(WORK)], check=True, cwd=ROOT)

    sheet = Image.new('RGB', (1800, 1980), '#f4f0e6'); draw = ImageDraw.Draw(sheet)
    draw.text((46, 28), '室内寄居：日常来源、清扫与永久防护', font=font(45), fill='#263e37')
    draw.text((48, 96), '当前网格与正常尺寸 · 屋顶省略，墙角为构图背景 · 离线机制示意，尚非实机验证', font=font(24), fill='#64766a')

    def panel(col, y, title, ident, notes, corner=False, cleaned=False):
        card = Image.new('RGB', (WIDTH, 490), '#fffdf6'); d = ImageDraw.Draw(card)
        d.text((20, 16), title, font=font(28), fill='#2b453a')
        floor = floor_image(corner, cleaned)
        if ident:
            pixels = Image.frombytes('RGBA', (WIDTH, HEIGHT), (WORK/(ident+'.rgba')).read_bytes())
            floor.alpha_composite(pixels)
        if cleaned and not ident:
            fd = ImageDraw.Draw(floor)
            fd.text((188, 163), '清扫后空地', font=font(25), fill='#6e8872')
        card.paste(floor, (0, 61), floor)
        for line, text in enumerate(notes):
            d.text((20, 424+line*30), text, font=font(20), fill='#68796b')
        sheet.paste(card, (40+col*585, y))

    panel(0, 150, '01  玩家与村民带来日常来源', 'dust', ['室内活动缓慢积絮，有数量上限。', '自动碎食直接记入地面散料，不生成掉落物。'], corner=True)
    panel(1, 150, '02  积絮处出现寄居者', 'natural-home', ['图示 3 份材料的低絮窝与正常体型家油。', '有屋顶、可走干地；不要求夜间或投喂。'], corner=True)
    panel(2, 150, '03  扫干净，再放永久防护盒', 'guard', ['无燃料、无电、无损耗，放置后持续生效。', '水平 ±16 格、垂直 ±8 格的方形范围。'], corner=True, cleaned=True)

    draw.rounded_rectangle((40, 677, 1760, 955), 14, fill='#e4eadf')
    draw.text((65, 700), '来源守恒', font=font(29), fill='#2c4839')
    draw.text((240, 704), '玩家和油库里实际吃饭留下碎食；村民低频消耗粮袋中的 1 份食物才产生碎屑。', font=font(25), fill='#3d5545')
    draw.text((240, 750), '散料在地面持久计数；资源积累引来寄居，巢垫随真实材料铺开，0 份不画巢垫。', font=font(25), fill='#3d5545')
    draw.text((65, 800), '清扫控制', font=font(29), fill='#2c4839')
    draw.text((240, 804), '干抹布扫周围 3×3 零碎；清扫后 2400 tick（2 分钟）抑制积尘与新寄居。', font=font(25), fill='#3d5545')
    draw.text((65, 850), '永久防护', font=font(29), fill='#2c4839')
    draw.text((240, 854), '盒子持续停止范围内的积尘、自动碎屑和新家油；已有碎屑仍需要扫除。', font=font(25), fill='#3d5545')
    draw.text((240, 903), '已有居民与卵保留；湿抹布继续用于洗身和巢内操作。', font=font(25), fill='#3d5545')
    draw.text((48, 987), '微小物体仍按整格管理', font=font(32), fill='#263e37')
    draw.text((48, 1032), '虚线表示可选的 1 格地面；物体与家油均按一格 16 模型单位绘制，没有放大。', font=font(24), fill='#64766a')
    for col, count in enumerate((1, 8, 64)):
        panel(col, 1088, f'碎食 × {count}', 'count-'+str(count),
              ['近距离对准整格可看名称与准确数量。', '显示 1 簇。' if count == 1 else '最多显示 8 簇；一格最多储存 64 份。'])

    draw.text((50, 1617), '直接管理', font=font(28), fill='#2c4839')
    draw.text((228, 1622), '空手右键查数量；蹲下空手收回整堆。家油到跟前夹取，每次搬 1 份。', font=font(25), fill='#3d5545')
    draw.text((50, 1676), '可选投放', font=font(28), fill='#2c4839')
    draw.text((228, 1681), '持物右键顶面放 1 份，蹲下放 8 份；同种追加、异种分格，手动 Q 掉落兼容。', font=font(25), fill='#3d5545')
    draw.text((50, 1735), '可选合成', font=font(28), fill='#2c4839')
    draw.text((228, 1740), f'1 面包 → {scrap_yield} 碎食；1 羊毛 → {lint_yield} 棉絮。可用于定点观察或控制散料。', font=font(25), fill='#3d5545')
    draw.line((50, 1811, 1750, 1811), fill='#c8d1c3', width=2)
    draw.text((50, 1840), '示意中的积尘、寄居和防护是不同时间点；已有小窝与居民不会因放下盒子立即消失。', font=font(25), fill='#526c5a')
    draw.text((50, 1892), '造型与机制的离线示意；数量同步、自然生成与防护范围仍需远端检查及游戏内确认。', font=font(24), fill='#69796b')
    sheet.save(OUT/'house-supplies.png')
    (OUT/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>室内寄居与永久防护</title><style>body{background:#f4f0e6;margin:24px;font:18px sans-serif;color:#2e453a}img{max-width:100%;height:auto}a{color:#526d55}</style><a href="../index.html">回到预览总入口</a><p>当前运行网格与正常尺寸的离线机制示意。自动碎食直接记入地面散料；原创建模的防护盒使用原版表面材质。场景状态为演出安排，仍需游戏内确认。</p><img src="house-supplies.png">', encoding='utf-8')
    (WORK/'manifest.json').write_text(json.dumps(dict(
        scope='Offline staged natural house diagram; current meshes, animation, actual pile scales, partial bedding and native guard block model. Not game validation.',
        staging=dict(materials=3, materials_required=material_cost, dust_interval_ticks=600, cleaning_suppression_ticks=2400,
                     guard_horizontal_radius=16, guard_vertical_radius=8),
        inputs={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}), ensure_ascii=False, indent=2), encoding='utf-8')
    print(OUT/'house-supplies.png')


if __name__ == '__main__':
    main()
