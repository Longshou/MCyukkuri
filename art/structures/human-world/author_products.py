"""Original native pixel assets and block geometry for factory equipment, packages and badges."""
from pathlib import Path
import io,json,hashlib,sys
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'scripts'));sys.path.insert(0,str(ROOT/'art/props/badges'))
import author_badges
import numpy as np
from PIL import Image,ImageDraw
from asset_export_checks import preserve_equivalent_png
ART=Path(__file__).parent/'products'
ASSET=ROOT/'src/main/resources/assets/mcyukkuri'
def write(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8',newline='\n')
def write_bytes(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    if not path.is_file() or path.read_bytes()!=data:path.write_bytes(data)
def write_png(path,image):
    stream=io.BytesIO();image.save(stream,format='PNG')
    data=preserve_equivalent_png(path,stream.getvalue())
    write_bytes(path,data)
    return data

# ---- The plant's short floor belt and the manju steamer (加工所 F7a): 16 x 16 pixel art at block density (one texel per model pixel),
# plain steel in the station palette. Contact geometry for code is in the README section 加工所; numbers here are model pixels (1/16).
# Belt states: facing = the running direction, running = moving texture and a green lamp on both sides. The model faces north.
BELT_TOP, BELT_FRAMES, BELT_FRAMETIME = 6, 8, 2      # support surface 6/16; the running texture moves 1 px per 2 ticks (0.5 block/s)
# Steamer: boolean properties its blockstate keys on. FactoryStationBlock declares only `facing` today (an unknown key would fail the
# whole blockstate), so the closed cold steamer is drawn for every state until the code package declares `open` and `lit` and lists them.
STEAMER_PROPERTIES = ()
STEAMER_SLOTS = {'input': (8, 12, 10.5), 'wheat': (4.75, 11, 3.5), 'output': (11.25, 11, 3.5)}   # surface centres, north-facing model
FACES = ('north', 'south', 'east', 'west', 'up', 'down')
def rgba(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) + (255,)
def speck(x, y, seed): return ((x * 73856093) ^ (y * 19349663) ^ (seed * 83492791)) % 7
def element(a, b, faces, shade=True):
    out = {'from': a, 'to': b, 'faces': {f: {'texture': '#' + t, 'uv': uv, **({'rotation': r} if r else {})} for f, (t, uv, *rr) in faces.items()
                                         for r in [rr[0] if rr else 0]}}
    if not shade: out['shade'] = False
    return out
def belt_textures():
    """Rubber belt with chevron cleats pointing north (the running direction), its steel side frame and end drum."""
    top = Image.new('RGBA', (16, 16), '#3a3f3e'); p = top.load()
    for x in range(16):
        for y in range(16):
            if speck(x, y, 1) == 0: p[x, y] = rgba('#343938')
            elif speck(x, y, 1) == 1: p[x, y] = rgba('#414645')
        p[0, x] = p[15, x] = rgba('#2a2e2d')
    for layer, (dy, c) in enumerate(((2, '#2b2f2e'), (0, '#868d8a'), (1, '#5e6563'))):   # shadow first, then the raised cleat
        for r0 in (0, 8):
            for x in range(3, 13): p[x, (r0 + int(abs(x - 7.5)) + dy) % 16] = rgba(c)   # a 45-degree V, 2-px tip toward north
    frames = Image.new('RGBA', (16, 16 * BELT_FRAMES))
    for k in range(BELT_FRAMES): frames.paste(Image.fromarray(np.roll(np.array(top), -k, axis=0)), (0, 16 * k))
    sides = {}
    for running in (False, True):
        im = Image.new('RGBA', (16, 16), '#7d8a86'); d = ImageDraw.Draw(im)
        d.line((0, 10, 15, 10), fill='#2a2e2d'); d.line((0, 11, 15, 11), fill='#a9b6b1'); d.line((0, 13, 15, 13), fill='#5f6b68')
        for x in (1, 14): d.point((x, 12), fill='#34454a')
        d.line((7, 12, 8, 12), fill='#8ef07a' if running else '#6e2a24')
        if running: d.line((7, 11, 8, 11), fill='#d4ffc4')
        sides[running] = im
    end = Image.new('RGBA', (16, 16), '#7d8a86'); d = ImageDraw.Draw(end)
    d.line((0, 10, 15, 10), fill='#2a2e2d'); d.rectangle((1, 11, 14, 12), fill='#474d4b'); d.line((0, 13, 15, 13), fill='#2a2e2d')
    d.rectangle((7, 11, 8, 12), fill='#a9b6b1')
    return {'factory_belt_top': top, 'factory_belt_top_running': frames, 'factory_belt_side': sides[False],
            'factory_belt_side_running': sides[True], 'factory_belt_end': end}
def steam_frames():
    """Dithered pixel steam rising 2 px a frame and thinning upward (vertically tileable puffs under a fixed fade)."""
    bayer = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16
    puffs = [(5, 2, 2.6), (10, 5, 2.4), (7, 9, 2.8), (11, 12, 2.2), (4, 13, 2.0), (8, 0, 1.8)]
    ys, xs = np.mgrid[0:16, 0:16]
    density = np.zeros((16, 16))
    for cx, cy, r in puffs:
        dy = np.minimum(abs(ys - cy), 16 - abs(ys - cy)); density += np.exp(-((xs - cx) ** 2 + dy ** 2) / (2 * r * r))
    density /= density.max()
    fade = .25 + .75 * (ys / 15) ** 1.4
    strip = Image.new('RGBA', (16, 128))
    for k in range(8):
        rolled = np.roll(density, -2 * k, axis=0) * fade
        frame = np.zeros((16, 16, 4), dtype='uint8')
        on = rolled > .32 + .45 * bayer[ys % 4, xs % 4]
        frame[on] = rgba('#e6ece9'); frame[on & (rolled > .62)] = rgba('#fbfcfa')
        strip.paste(Image.fromarray(frame, 'RGBA'), (0, 16 * k))
    return strip
def steamer_textures():
    """The stove cabinet with its firebox door (cold / burning), deck, worker-side tray, steel pot, rack, lid and steam."""
    def cabinet():
        im = Image.new('RGBA', (16, 16), '#9daaa5'); d = ImageDraw.Draw(im)
        d.rectangle((0, 0, 13, 8), outline='#424e50'); d.line((1, 1, 12, 1), fill='#b8c7bc'); d.line((1, 7, 12, 7), fill='#7f8b87')
        for xy in ((2, 2), (11, 2), (2, 6), (11, 6)): d.point(xy, fill='#34454a')
        return im, d
    out = {}
    im, _ = cabinet(); out['manju_steamer_cabinet'] = im
    for lit in (False, True):
        im, d = cabinet()
        d.rectangle((3, 1, 10, 7), fill='#2f3d3e', outline='#1b2526')
        for y in (3, 5): d.line((4, y, 9, y), fill='#ffb347' if lit else '#121a1b')
        if lit: d.line((6, 3, 7, 3), fill='#ffe08a'); d.line((6, 5, 7, 5), fill='#ffe08a'); d.line((4, 7, 9, 7), fill='#d6793d')
        d.point((9, 4), fill='#b8c7bc'); d.rectangle((11, 2, 12, 3), fill='#e4dfc5'); d.point((12, 2), fill='#b64038')
        out['manju_steamer_firebox' + ('_lit' if lit else '')] = im
    im = Image.new('RGBA', (16, 16), '#8e9b97'); d = ImageDraw.Draw(im)
    d.rectangle((0, 0, 15, 15), outline='#6f7c78'); d.rectangle((1, 1, 14, 14), outline='#a9b6b1'); out['manju_steamer_deck'] = im
    im = Image.new('RGBA', (16, 16), '#efe8d4'); p = im.load()        # u0..6.5 straw mat for the wheat, u7.5..14 steaming cloth for buns
    for x in range(16):
        for y in range(16):
            if x < 7: p[x, y] = rgba('#c8a85e' if (x + 2 * y) % 4 else '#a8883f')
            elif (x + y) % 4 == 0: p[x, y] = rgba('#ddd5bf')
    out['manju_steamer_tray'] = im
    im = Image.new('RGBA', (16, 16), '#bdc7be'); d = ImageDraw.Draw(im)
    d.line((0, 0, 15, 0), fill='#e4e9e0')
    for y in (2, 7): d.line((0, y, 15, y), fill='#8e9a95')
    for x in (0, 11): d.line((x, 1, x, 8), fill='#a3aea8')
    d.rectangle((4, 4, 7, 5), fill='#d6793d'); d.point((5, 4), fill='#f6d79e'); out['manju_steamer_pot'] = im
    im = Image.new('RGBA', (16, 16), '#6f7c78'); d = ImageDraw.Draw(im); p = im.load()
    d.rectangle((0, 7, 15, 15), fill='#5a6663')
    for x in range(16):
        for y in range(7):
            if speck(x, y, 3) == 0: p[x, y] = rgba('#9fb7bb')
    out['manju_steamer_pot_inner'] = im
    im = Image.new('RGBA', (16, 16), '#2b3534'); d = ImageDraw.Draw(im)
    for y in (1, 3, 5): d.line((1, y, 8, y), fill='#a0aca7')
    for x in (0, 9): d.line((x, 0, x, 6), fill='#8e9a95')
    out['manju_steamer_rack'] = im
    im = Image.new('RGBA', (16, 16), '#a9b5b0'); d = ImageDraw.Draw(im)
    d.rectangle((0, 0, 13, 10), outline='#6f7c78'); d.rectangle((2, 2, 11, 8), outline='#c3ccc6')
    d.line((0, 11, 15, 11), fill='#8e9b97'); d.line((0, 12, 15, 12), fill='#5f6b68')
    d.line((0, 13, 15, 13), fill='#4a5859'); d.line((0, 14, 15, 14), fill='#2f3d3e'); out['manju_steamer_lid'] = im
    im = Image.new('RGBA', (16, 16), '#7f8b87'); d = ImageDraw.Draw(im); p = im.load()
    d.rectangle((0, 0, 13, 10), outline='#5f6b68')
    d.rectangle((2, 2, 11, 8), outline='#748079')
    for xy in ((4, 4), (8, 3), (10, 6), (5, 7)): p[xy] = rgba('#b9cfd3')
    out['manju_steamer_lid_inner'] = im
    out['manju_steamer_steam'] = steam_frames()
    return out
def belt_model(running):
    side = 'side'; uv = [0, 10, 16, 10 + BELT_TOP - 2]
    parts = [element([0, 2, 0], [16, BELT_TOP, 16], {'up': ('top', [0, 0, 16, 16]), 'north': ('end', uv), 'south': ('end', uv),
                                                   'east': (side, uv), 'west': (side, uv), 'down': (side, [0, 12, 16, 13])})]
    parts += [element([x, 0, z], [x + 2, 2, z + 2], {f: (side, [2, 12, 4, 14]) for f in FACES[:4]}) for x in (1, 13) for z in (1, 13)]
    suffix = '_running' if running else ''
    return {'parent': 'minecraft:block/block', 'render_type': 'minecraft:cutout',
            'textures': {'particle': 'mcyukkuri:block/factory_belt_side', 'top': 'mcyukkuri:block/factory_belt_top' + suffix,
                         'side': 'mcyukkuri:block/factory_belt_side' + suffix, 'end': 'mcyukkuri:block/factory_belt_end'}, 'elements': parts}
def steamer_model(open_, lit):
    """North-facing: the stove cabinet (y0..9) under a deck plate, the worker-side tray at the front (wheat left of the divider, buns
    right), the steel pot behind it (y10..19) with the rack inside, the lid on top or flipped up against the back, steam when lit."""
    sides = lambda tex, uv, faces=FACES[:4]: {f: (tex, uv) for f in faces}
    e = [element([1, 0, 1], [15, 9, 15], {**sides('cabinet', [0, 0, 14, 9]), 'north': ('firebox', [0, 0, 14, 9]), 'down': ('cabinet', [0, 0, 14, 9])}),
         element([0, 9, 0], [16, 10, 16], {**sides('deck', [0, 0, 16, 1]), 'up': ('deck', [0, 0, 16, 16]), 'down': ('deck', [0, 0, 16, 16])}),
         element([1, 10, 0], [15, 11, 6], {'up': ('tray', [0, 0, 14, 6]), **sides('deck', [0, 0, 14, 1], ('north', 'east', 'west'))})]
    for a, b in (([1, 11, 0], [15, 12, 1]), ([1, 11, 1], [2, 12, 6]), ([14, 11, 1], [15, 12, 6]), ([7.5, 11, 1], [8.5, 12, 6])):
        e.append(element(a, b, {**sides('deck', [0, 0, b[0] - a[0], 1]), 'up': ('deck', [0, 0, b[0] - a[0], b[2] - a[2]])}))
    if open_:
        e += [element([2, 10, 6], [14, 19, 7], {'north': ('pot', [0, 0, 12, 9]), 'south': ('pot_inner', [0, 0, 12, 9]), 'up': ('pot', [0, 0, 12, 1]),
                                                'east': ('pot', [0, 0, 1, 9]), 'west': ('pot', [0, 0, 1, 9])}),
              element([2, 10, 14], [14, 19, 15], {'south': ('pot', [0, 0, 12, 9]), 'north': ('pot_inner', [0, 0, 12, 9]), 'up': ('pot', [0, 0, 12, 1]),
                                                  'east': ('pot', [0, 0, 1, 9]), 'west': ('pot', [0, 0, 1, 9])}),
              element([2, 10, 7], [3, 19, 14], {'west': ('pot', [1, 0, 8, 9]), 'east': ('pot_inner', [0, 0, 7, 9]), 'up': ('pot', [0, 0, 1, 7])}),
              element([13, 10, 7], [14, 19, 14], {'east': ('pot', [1, 0, 8, 9]), 'west': ('pot_inner', [0, 0, 7, 9]), 'up': ('pot', [0, 0, 1, 7])}),
              element([3, 10, 7], [13, 12, 14], {'up': ('rack', [0, 0, 10, 7])}),
              element([1, 19, 14], [15, 30, 16], {'north': ('lid_inner', [0, 0, 14, 11]), 'south': ('lid', [0, 0, 14, 11]), 'up': ('lid', [0, 11, 14, 13]),
                                                  'down': ('lid', [0, 11, 14, 13]), 'east': ('lid', [0, 11, 11, 13], 90), 'west': ('lid', [0, 11, 11, 13], 90)})]
        if lit:
            e += [element([3, 19, 10.5], [13, 32, 10.5], sides('steam', [3, 3, 13, 16], ('north', 'south')), shade=False),
                  element([8, 19, 7], [8, 32, 14], sides('steam', [4.5, 3, 11.5, 16], ('east', 'west')), shade=False)]
    else:
        e += [element([2, 10, 6], [14, 19, 15], {'north': ('pot', [0, 0, 12, 9]), 'south': ('pot', [0, 0, 12, 9]), 'east': ('pot', [1, 0, 10, 9]),
                                                 'west': ('pot', [1, 0, 10, 9])}),
              element([1, 19, 5], [15, 21, 16], {'up': ('lid', [0, 0, 14, 11]), 'down': ('lid_inner', [0, 0, 14, 11]), 'north': ('lid', [0, 11, 14, 13]),
                                                 'south': ('lid', [0, 11, 14, 13]), 'east': ('lid', [0, 11, 11, 13]), 'west': ('lid', [0, 11, 11, 13])}),
              element([5, 21, 10], [11, 22, 11], {**sides('lid', [0, 13, 6, 14]), 'up': ('lid', [0, 13, 6, 14])}),
              element([11, 21, 12], [13, 25, 14], {**sides('pot', [0, 1, 2, 5]), 'up': ('rack', [0, 0, 2, 2])})]
        if lit:
            e += [element([10, 25, 13], [14, 32, 13], sides('steam', [6, 9, 10, 16], ('north', 'south')), shade=False),
                  element([12, 25, 11], [12, 32, 15], sides('steam', [6, 9, 10, 16], ('east', 'west')), shade=False)]
    names = ['cabinet', 'deck', 'tray', 'pot', 'pot_inner', 'rack', 'lid', 'lid_inner', 'steam']
    textures = {'particle': 'mcyukkuri:block/manju_steamer_cabinet', 'firebox': 'mcyukkuri:block/manju_steamer_firebox' + ('_lit' if lit else ''),
                **{n: 'mcyukkuri:block/manju_steamer_' + n for n in names}}
    return {'parent': 'minecraft:block/block', 'render_type': 'minecraft:cutout', 'textures': textures, 'elements': e}
def steamer_name(state): return 'manju_steamer' + ('_open' if state.get('open') else '') + ('_lit' if state.get('lit') else '')
def machines():
    """Textures, models, blockstates, item models and loot of the belt and the steamer; returns the texture records."""
    records = {}
    for name, image in {**belt_textures(), **steamer_textures()}.items():
        source = ART / (name + '.png'); data = write_png(source, image); target = ASSET / 'textures/block' / source.name; write_bytes(target, data)
        if image.height > 16:
            write(ASSET / 'textures/block' / (name + '.png.mcmeta'), {'animation': {'frametime': BELT_FRAMETIME}})
        records[name] = {'source': source.relative_to(ROOT).as_posix(), 'target': target.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(data).hexdigest()}
    headings = [('north', 0), ('east', 90), ('south', 180), ('west', 270)]
    for running in (False, True): write(ASSET / 'models/block' / ('factory_belt' + ('_running' if running else '') + '.json'), belt_model(running))
    write(ASSET / 'blockstates/factory_belt.json', {'variants': {f'facing={f},running={str(r).lower()}': {'model': 'mcyukkuri:block/factory_belt' + ('_running' if r else ''), 'y': a}
                                                                for f, a in headings for r in (False, True)}})
    for open_ in (False, True):
        for lit in (False, True): write(ASSET / 'models/block' / (steamer_name({'open': open_, 'lit': lit}) + '.json'), steamer_model(open_, lit))
    states = [{}]
    for prop in STEAMER_PROPERTIES: states = [{**s, prop: v} for s in states for v in (False, True)]
    write(ASSET / 'blockstates/manju_steamer.json', {'variants': {','.join([f'facing={f}'] + [f'{k}={str(v).lower()}' for k, v in sorted(s.items())]):
                                                                  {'model': 'mcyukkuri:block/' + steamer_name(s), 'y': a} for f, a in headings for s in states}})
    for name in ('factory_belt', 'manju_steamer'):
        write(ASSET / 'models/item' / f'{name}.json', {'parent': 'mcyukkuri:block/' + name})
        write(ROOT / 'src/main/resources/data/mcyukkuri/loot_table/blocks' / f'{name}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1,
              'entries': [{'type': 'minecraft:item', 'name': 'mcyukkuri:' + name}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    return records
def export():
    records={}
    metal=Image.new('RGBA',(32,32),'#899994');md=ImageDraw.Draw(metal)
    md.rectangle((0,0,31,31),outline='#394b4c',width=2);md.rectangle((3,3,28,28),outline='#b8c7bc')
    for x in (4,27):
        for y in (4,27):md.rectangle((x,y,x+1,y+1),fill='#34454a')
    mp=ART/'workshop_casing.png';metal_data=write_png(mp,metal)
    target=ASSET/'textures/block/workshop_casing.png';write_bytes(target,metal_data)
    names=['yukkuri_manju','live_yukkuri_bag','bronze_yukkuri_badge','silver_yukkuri_badge','gold_yukkuri_badge','platinum_yukkuri_badge','labor_bench','live_packing_table','nursery_station']
    for name in names:
        im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);dark='#43392f'
        if name.endswith('badge'):
            im=author_badges.badge(name.split('_')[0])  # one 16x16 source for item, worn decal and handbook
        elif name=='yukkuri_manju':
            d.ellipse((2,7,30,29),fill='#a7703e');d.ellipse((3,5,29,25),fill='#e3b978');d.ellipse((5,6,26,21),fill='#f6d79e')
            d.line((7,12,24,12),fill='#bb8142',width=2);d.point((11,15),fill=dark);d.point((21,15),fill=dark);d.arc((13,15,19,19),0,180,fill=dark)
            d.polygon([(6,8),(9,5),(12,8)],fill='#b5763a')
        elif name=='live_yukkuri_bag':
            d.polygon([(6,2),(26,2),(25,7),(29,26),(26,30),(5,30),(3,26),(7,7)],fill='#697b78')
            d.polygon([(8,6),(24,6),(27,26),(24,28),(7,28),(5,25)],fill='#dccf9c')
            d.rectangle((6,3,26,5),fill='#b64038');d.ellipse((7,10,25,24),fill='#f5dfb3')
            d.polygon([(8,12),(11,9),(15,11),(19,9),(24,12),(24,15),(8,15)],fill='#54402c')
            for x in (11,20):d.line((x,17,x+2,17),fill=dark,width=2)
            d.line((15,20,18,20),fill=dark);d.rectangle((20,23,26,27),fill='#f7efcf');d.line((21,25,25,25),fill='#546756')
            d.line((6,8,5,23),fill='#fffbe2');d.line((24,8,27,23),fill='#fffbe2')
        else:
            color={'labor_bench':'#aa914d','live_packing_table':'#629797','nursery_station':'#ce9aa1'}[name]
            d.rectangle((0,0,31,31),fill='#424e50');d.rectangle((1,1,30,29),fill=color)
            d.rectangle((2,2,29,5),fill='#e4dfc5');d.rectangle((3,8,28,23),fill='#273b3d')
            if name=='labor_bench':
                for x in (5,10,15,20,25):d.rectangle((x,10,x+2,21),fill='#b9ada0')
                d.line((4,16,27,16),fill='#ead4a1',width=2)
            elif name=='live_packing_table':
                for x in (6,16):
                    d.rectangle((x,11,x+7,21),fill='#d9c99b');d.rectangle((x,10,x+7,12),fill='#b64038')
                    d.ellipse((x+1,14,x+6,19),fill='#f7dfb3');d.point((x+2,16),fill=dark);d.point((x+5,16),fill=dark)
            else:
                d.ellipse((7,14,24,22),fill='#f5d9af');d.line((11,17,13,17),fill=dark);d.line((19,17,21,17),fill=dark)
                d.arc((14,16,18,20),0,180,fill=dark);d.rectangle((14,7,17,12),fill='#fff0e1');d.rectangle((12,9,19,10),fill='#fff0e1')
            for x in (3,27):d.rectangle((x,26,x+1,28),fill='#232f30')
        source=ART/(name+'.png');source_data=write_png(source,im)
        block=name in names[6:];target=ASSET/'textures'/('block' if block else 'item')/source.name
        write_bytes(target,source_data)
        if block:
            def box(a,b,texture='casing',front=None):
                return {'from':a,'to':b,'faces':{face:{'texture':'#'+(front if face=='north' and front else texture),'uv':[0,0,16,16]} for face in ['north','south','east','west','up','down']}}
            if name=='labor_bench':
                parts=[box([0,0,0],[16,3,16]),box([0,9,0],[16,11,16])]
                parts += [box([x,3,z],[x+2,9,z+2]) for x in (1,13) for z in (1,13)]
                parts += [box([2,11,z],[14,14,z+1]) for z in (2,5,8,11)]
                parts += [box([1,3,1],[15,9,3],front='front')]
            elif name=='live_packing_table':
                parts=[box([0,0,0],[16,3,16]),box([0,9,0],[16,11,16]),box([0,11,12],[2,23,16]),box([14,11,12],[16,23,16]),box([0,21,12],[16,24,16])]
                parts += [box([x,3,z],[x+2,9,z+2]) for x in (1,13) for z in (1,13)]
                parts += [box([4,11,5],[12,21,6],'package'),box([2,4,1],[14,8,3],front='front')]
            else:
                parts=[box([0,0,0],[16,5,16]),box([0,5,0],[2,10,16]),box([14,5,0],[16,10,16]),box([0,5,14],[16,18,16],front='front'),box([2,5,2],[14,6,14],'front')]
            write(ASSET/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','render_type':'minecraft:cutout',
                'textures':{'particle':'mcyukkuri:block/workshop_casing','casing':'mcyukkuri:block/workshop_casing','front':'mcyukkuri:block/'+name,'package':'mcyukkuri:item/live_yukkuri_bag'},'elements':parts})
            write(ASSET/'blockstates'/f'{name}.json',{'variants':{f'facing={f}':{'model':'mcyukkuri:block/'+name,'y':angle} for f,angle in [('north',0),('east',90),('south',180),('west',270)]}})
            model={'parent':'mcyukkuri:block/'+name}
            write(ROOT/'src/main/resources/data/mcyukkuri/loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'mcyukkuri:'+name}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
        else:model={'parent':'minecraft:item/generated','textures':{'layer0':'mcyukkuri:item/'+name}}
        write(ASSET/'models/item'/f'{name}.json',model)
        records[name]={'source':source.relative_to(ROOT).as_posix(),'target':target.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(source_data).hexdigest()}
    records.update(machines())
    write(ART/'manifest.json',{'status':'pending_visual_approval','author':'Original procedural pixel art; no external source pixels.','assets':records})
    return records
if __name__=='__main__':export()
