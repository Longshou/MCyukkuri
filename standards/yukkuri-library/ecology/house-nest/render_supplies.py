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
    guard_source = HERE.parent/'house-repellent'
    spec = importlib.util.spec_from_file_location('house_repellent_preview', guard_source/'preview.py')
    guard_preview = importlib.util.module_from_spec(spec); spec.loader.exec_module(guard_preview)
    guard_mesh, guard_texture = guard_preview.prepare(WORK)
    models = {}; textures = {}; sources = [renderer, nest_renderer, nest_be, recipe_path, HERE/'render_supplies.py',
        HERE/'render_scene.cjs', scrap_recipe, lint_recipe, client/'HouseScrapRenderer.java',
        ASSETS/'yukkuri_presentation/house_ecology.json', ASSETS/'yukkuri_presentation/motion_corrections.json',
        ASSETS/'yukkuri_presentation/placement_fit.json', guard_source/'recipe.json', guard_source/'build.py',
        guard_source/'preview.py', ASSETS/'models/item/household_guard.json']
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
        # Close-view LOD is capped at four clusters; stored quantity is never inferred from art.
        for index, (x, z) in enumerate(clusters[:min(count, 4)]):
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
             dict(id='spray', instances=[dict(id='household_guard', at=[0,0,0], angle=-.26)]),
             dict(id='spray-detail', instances=[dict(id='household_guard', at=[0,0,0], angle=-.12)]),
             dict(id='spray-side', instances=[dict(id='household_guard', at=[0,0,0], angle=-1.05)])]
    shots.extend(dict(id='count-'+str(count), instances=pile('food_scrap', count)) for count in (1, 8, 64))
    for shot in shots:
        shot.update(span=SPAN, centerY=0, az=0, el=.65 if shot['id'] in ('dust', 'natural-home') else ELEVATION)
        if shot['id'].startswith('spray'):
            shot.update(span=8.0 if shot['id']=='spray' else 7.0, centerY=6, el=.18)
    scene = dict(models=models, textures=textures, atlasSize=1024, shots=shots, width=WIDTH, height=HEIGHT)
    (WORK/'scene.json').write_text(json.dumps(scene), encoding='utf-8')
    subprocess.run(['node', str(HERE/'render_scene.cjs'), str(WORK/'scene.json'), str(WORK)], check=True, cwd=ROOT)

    sheet = Image.new('RGB', (1800, 1980), '#f4f0e6'); draw = ImageDraw.Draw(sheet)
    draw.text((46, 28), '室内寄居：日常来源、清扫与驱油喷雾', font=font(45), fill='#263e37')
    draw.text((48, 96), '当前运行模型 · 屋顶省略、墙角为背景 · 喷雾为手持物品示意，不是放置场景', font=font(24), fill='#64766a')

    def panel(col, y, title, ident, notes, corner=False, cleaned=False, object_view=False):
        card = Image.new('RGB', (WIDTH, 490), '#fffdf6'); d = ImageDraw.Draw(card)
        d.text((20, 16), title, font=font(28), fill='#2b453a')
        floor = Image.new('RGBA',(WIDTH,HEIGHT),(0,0,0,0)) if object_view else floor_image(corner, cleaned)
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
    panel(2, 150, '03  清扫后，喷一次驱油喷雾', 'spray', ['16 次／瓶；潜行右键查询，不耗次数。', '对可交互方块喷洒，不放置任何方块。'], object_view=True)

    draw.rounded_rectangle((40, 677, 1760, 955), 14, fill='#e4eadf')
    draw.text((65, 700), '来源守恒', font=font(29), fill='#2c4839')
    draw.text((240, 704), '玩家和油库里实际吃饭留下碎食；村民低频消耗粮袋中的 1 份食物才产生碎屑。', font=font(25), fill='#3d5545')
    draw.text((240, 750), '散料在地面持久计数；资源积累引来寄居，巢垫随真实材料铺开，0 份不画巢垫。', font=font(25), fill='#3d5545')
    draw.text((65, 800), '清扫控制', font=font(29), fill='#2c4839')
    draw.text((240, 804), '干抹布扫周围 3×3 零碎；清扫后 2400 tick（2 分钟）抑制积尘与新寄居。', font=font(25), fill='#3d5545')
    draw.text((65, 850), '限时保护', font=font(29), fill='#2c4839')
    draw.text((240, 854), '喷洒点水平 ±8、垂直 ±4 格，持续 72000 游戏 tick＝3 MC 日，正常速率 60 分钟。', font=font(25), fill='#3d5545')
    draw.text((240, 903), '阻止新积尘、自动碎屑与新寄居；已有散料需清扫，居民与卵保留。', font=font(25), fill='#3d5545')
    draw.text((48, 987), '微小物体仍按整格管理', font=font(32), fill='#263e37')
    draw.text((48, 1032), '地面为真实数值库存；虚线表示可选整格。下图碎屑按正常尺寸，近处最多显示 4 簇。', font=font(24), fill='#64766a')
    for col, count in enumerate((1, 8, 64)):
        panel(col, 1088, f'碎食 × {count}', 'count-'+str(count),
              ['近距离对准整格可看名称与准确数量。', '显示 1 簇。' if count == 1 else '近处最多 4 簇；实际单格容量仍为 64 份。'])

    draw.text((50, 1617), '直接管理', font=font(28), fill='#2c4839')
    draw.text((228, 1622), '空手右键查数量；蹲下空手收回整堆。家油到跟前夹取，每次搬 1 份。', font=font(25), fill='#3d5545')
    draw.text((50, 1676), '可选投放', font=font(28), fill='#2c4839')
    draw.text((228, 1681), '持物右键顶面放 1 份，蹲下放 8 份；同种追加、异种分格，手动 Q 掉落兼容。', font=font(25), fill='#3d5545')
    draw.text((50, 1735), '可选合成', font=font(28), fill='#2c4839')
    draw.text((228, 1740), f'1 面包 → {scrap_yield} 碎食；1 羊毛 → {lint_yield} 棉絮。可用于定点观察或控制散料。', font=font(25), fill='#3d5545')
    draw.line((50, 1811, 1750, 1811), fill='#c8d1c3', width=2)
    draw.text((50, 1840), '距离 4／8／12 格内最多显示 4／2／1 簇，12 格外不画；外观不代表库存被消耗。', font=font(25), fill='#526c5a')
    draw.text((50, 1892), '模型与范围均为离线示意；数量同步、自然生成与喷雾效果仍需远端检查及游戏内确认。', font=font(24), fill='#69796b')
    sheet.save(OUT/'house-supplies.png')
    render_spray_diagram()
    (OUT/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>室内寄居与驱油喷雾</title><style>body{background:#f4f0e6;margin:24px;font:18px sans-serif;color:#2e453a}img{max-width:100%;height:auto}a{color:#526d55}</style><a href="../index.html">回到预览总入口</a><p>当前运行模型与机制的离线示意。喷雾是手持消耗品，不放置方块；自动碎食记入地面数值库存。</p><img src="repellent-spray.png"><img src="house-supplies.png">', encoding='utf-8')
    (WORK/'manifest.json').write_text(json.dumps(dict(
        scope='Offline staged natural house diagram; current meshes, animation, actual pile scales, partial bedding and native repellent item model. Not game validation.',
        staging=dict(materials=3, materials_required=material_cost, dust_interval_ticks=600, cleaning_suppression_ticks=2400,
                     spray_horizontal_radius=8, spray_vertical_radius=4, spray_duration_ticks=72000, spray_uses=16,
                     near_pile_clusters=4),
        inputs={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}), ensure_ascii=False, indent=2), encoding='utf-8')
    print(OUT/'house-supplies.png')


def render_spray_diagram():
    sheet = Image.new('RGB',(1600,1140),'#f4f0e6'); draw=ImageDraw.Draw(sheet)
    draw.text((42,28),'驱油喷雾 · 一次喷洒，保护一片区域',font=font(42),fill='#263e37')
    draw.text((44,88),'原创当前物品模型与范围示意 · 不是游戏截图 · 喷洒不会放下任何方块',font=font(23),fill='#64766a')
    draw.rounded_rectangle((35,140,730,940),14,fill='#fffdf6')
    draw.text((65,158),'喷嘴 · 扳机 · 握持瓶身 · 油库里禁入标记',font=font(24),fill='#3d5545')
    for name,at,size in [('spray-detail',(72,204),(600,386)),('spray-side',(216,601),(360,232))]:
        pixels=Image.frombytes('RGBA',(WIDTH,HEIGHT),(WORK/(name+'.rgba')).read_bytes())
        pixels=pixels.resize(size,Image.Resampling.NEAREST);sheet.paste(pixels,at,pixels)
    draw.text((76,864),'每瓶 16 次；成功喷洒才消耗 1 次。',font=font(27),fill='#2c4839')
    draw.text((76,905),'正面与侧面造型视图，待用户审阅。',font=font(21),fill='#68796b')
    draw.text((806,158),'俯视：以被喷方块为中心',font=font(27),fill='#2c4839')
    x0,y0,cell=824,225,30
    for z in range(17):
        for x in range(17):
            fill='#e0e9da' if (x,z)!=(8,8) else '#b8654e'
            draw.rectangle((x0+x*cell,y0+z*cell,x0+(x+1)*cell,y0+(z+1)*cell),fill=fill,outline='#becfb9')
    for a,b in [((x0,y0),(x0+510,y0)),((x0+510,y0),(x0+510,y0+510)),((x0+510,y0+510),(x0,y0+510)),((x0,y0+510),(x0,y0))]:
        dashed(draw,a,b,'#4f755e',3)
    draw.text((917,190),'水平向四周各 8 格',font=font(23),fill='#526c5a')
    draw.text((848,754),'中心格 + 两侧各 8 格；垂直向上／下各 4 格',font=font(22),fill='#526c5a')
    for i in range(9):
        y=340+i*31
        draw.rectangle((1403,y,1434,y+30),fill='#b8654e' if i==4 else '#e0e9da',outline='#becfb9')
    draw.text((1376,285),'上 4 格',font=font(21),fill='#526c5a')
    draw.text((1376,634),'下 4 格',font=font(21),fill='#526c5a')
    draw.text((805,813),'72000 游戏 tick = 3 个 Minecraft 日',font=font(28),fill='#2c4839')
    draw.text((805,858),'正常 20 TPS 下约 60 分钟；到期可再次喷洒。',font=font(23),fill='#526c5a')
    draw.text((805,905),'潜行右键：查询覆盖和剩余时间，不耗次数。',font=font(23),fill='#526c5a')
    draw.rounded_rectangle((35,969,1565,1102),14,fill='#e4eadf')
    draw.text((60,989),'使用：对可交互方块右键喷洒。保护期间阻止新积尘、自动碎屑与新寄居。',font=font(26),fill='#3d5545')
    draw.text((60,1041),'已有散料仍需清扫，现有家油与卵保留；地面容量与实际库存计数不因渲染距离改变。',font=font(24),fill='#526c5a')
    sheet.save(OUT/'repellent-spray.png')


if __name__ == '__main__':
    main()
