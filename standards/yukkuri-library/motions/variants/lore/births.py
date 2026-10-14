"""Write births.html: scripted birth and midwifery scenes on the game models, as one static page.

Couples, newborns, the Marisa midwife's hat, the stem and the hat boat are posed per scene on
the runtime models with their motion corrections. The pregnant belly and contractions follow
LiveBirthGeometry (deform, belly, the 48-tick contraction, births every 2.4 s), the stem and
its fruit follow FamilyGeometry / FamilyRenderer with family.json, the hat cradle and boat use
the hat_boat prop. A small shared-depth rasterizer draws every actor in one frame. The page
opens from disk; writing it does not start a browser or the game. Needs numpy and Pillow.

  python births.py [--out art/source/motion-corrections/previews/current/lore/births.html]
"""
from pathlib import Path
import argparse, base64, datetime, io, json, math, struct, sys
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import preview as P

PRESENT = P.ASSETS/'yukkuri_presentation'
CLIPS = ['idle', 'waddle', 'hop', 'brood', 'labor', 'postpartum', 'first_wake', 'hat_cradle', 'sleep', 'wake', 'bereaved',
         'midwife', 'doff_hat', 'boat_drift', 'boat_brood', 'boat_labor', 'boat_steady']
LABOR_START_FACE = 'teary'


def b64(fmt, values): return base64.b64encode(struct.pack(f'<{len(values)}{fmt}', *values)).decode()


def png_url(array):
    buffer = io.BytesIO(); Image.fromarray(array).save(buffer, 'PNG', optimize=True)
    return 'data:image/png;base64,'+base64.b64encode(buffer.getvalue()).decode()


def pack(ident, corrections):
    p = P.Posed(ident, corrections); m = p.model
    rest = p.frame('idle', 0)[0]
    keep = np.repeat(np.linalg.norm(np.cross(rest[:, 1]-rest[:, 0], rest[:, 2]-rest[:, 0]), axis=1) > 1e-7, 3)
    prefixes = tuple(json.loads((PRESENT/'living.json').read_text(encoding='utf-8'))['variants'][ident]['accessory_prefixes'])
    accessory = np.concatenate([[1 if part['name'].startswith(prefixes) else 0]*part['count'] for part in m['parts'] if not part.get('anatomy')])
    v = np.c_[p.v, p.uv, p.n][keep]
    packed = np.c_[np.round(v[:, :3]*1024), np.round(v[:, 3:5]*16384), np.round(v[:, 5:8]*1024)].clip(-32768, 32767).astype(int).ravel().tolist()
    base = {}
    for tr in p.clips['idle']['tracks']:
        base.setdefault(tr['bone'], {})[tr['channel']] = P.sample(tr['keys'], 0)
    bones = [[b['parent']]+[round(x, 4) for x in b['pivot']+base.get(i, {}).get('position', [0, 0, 0])+base.get(i, {}).get('rotation', [0, 0, 0])
              +base.get(i, {}).get('scale', [1, 1, 1])] for i, b in enumerate(m['bones'])]
    clips, faces = {}, {'neutral', 'sleep', 'happy', 'surprise', LABOR_START_FACE}
    for name in CLIPS:
        if name not in p.clips: continue
        c = p.clips[name]
        clips[name] = dict(d=c['duration'], loop=c['loop'], faces=c.get('expression_spans', []),
                           tracks=[[tr['bone'], {'position': 0, 'rotation': 3, 'scale': 6}[tr['channel']], [round(x, 4) for k in tr['keys'] for x in k]] for tr in c['tracks']])
        faces |= {f for _, _, f in c.get('expression_spans', [])}
    textures = {f: png_url(p.texture(f)) for f in sorted(faces)}
    return dict(vertices=b64('h', packed), weights=b64('H', np.c_[p.a[keep], p.b[keep]].ravel().tolist()), blend=b64('B', np.round(p.q[keep, 0]*255).astype(int).tolist()),
                accessory=b64('B', accessory[keep].tolist()), bones=bones, clips=clips, textures=textures, textureSize=int(p.texture('neutral').shape[1]))


def hat_prop():
    prop = json.loads((P.ASSETS/'yukkuri_props/hat_boat.json').read_text(encoding='utf-8'))
    V = np.array(prop['vertices']).reshape(-1, 8)
    packed = np.c_[np.round(V[:, :3]*1024), np.round(V[:, 3:5]*16384), np.round(V[:, 5:8]*1024)].clip(-32768, 32767).astype(int).ravel().tolist()
    tex = np.array(Image.open(P.ASSETS/'textures/props/hat_boat.png').convert('RGBA'))
    return dict(vertices=b64('h', packed), texture=png_url(tex), textureSize=int(tex.shape[1]))


# ------------------------------------------------------------------ scenes (model units, 16 per block, y up, -Z = mother's face)
CROWN = 4.05           # the cradle rests on its crown (HatBoatRenderer lift)
BOAT_SEAT = .055*16    # an owner sits this far above her boat
INTERVAL, EMERGE = 2.4, 1.8


def key(*pairs): return [list(p) for p in pairs]


def live_birth(t_del, count):
    return [t_del+EMERGE+INTERVAL*i for i in range(count)]


def scene_land_live():
    t_del = 3.4; births = live_birth(t_del, 2); end = births[-1]
    hat = [0, CROWN, HAT_Z]; seats = [[-3, 3.8, HAT_Z-2.5], [3, 3.8, HAT_Z-2.5]]
    babies = [dict(model='reimu' if i == 0 else 'marisa', scale=.18, visible=[b-EMERGE, 99],
                   pos=key([b-EMERGE, 0, 1.2, -1.5], [b-.15, 0, .8, -12], [b+.2, 0, 6, -19], [b+.45, *seats[i]]), yaw=key([0, 0]),
                   clips=[[b-EMERGE, 'idle', 0], [b+.45, 'first_wake', 0], [b+2.45, 'hat_cradle', 0], [12.6, 'idle', 0]],
                   faces=[[b-EMERGE, b+.45, 'sleep']]) for i, b in enumerate(births)]
    for i, baby in enumerate(babies): baby['pos'] += key([12.4, *seats[i]], [12.9, 8*(1 if i else -1), 0, HAT_Z+4])
    return dict(id='land_live', title='陆生胎生：魔理沙父亲摘帽接生', duration=15, span=30, center=7, focus=[8, -12],
                captions=[[0, 2.2, '灵梦母亲临产，魔理沙父亲赶来'], [2.2, 3.4, '魔理沙摘下帽子，翻过来放在出口前'], [3.4, births[0]-.1, '灵梦随收缩用力，魔理沙伏低打气'],
                          [births[0]-.1, births[0]+1.6, '孩子挤出来，落进帽里'], [births[0]+1.6, end+.6, '第二个孩子也落进帽里'], [end+.6, 11.5, '产后休息；子油在帽里初醒'], [11.5, 15, '魔理沙戴回帽子，子油下来跟着亲油']],
                ground='land', actors=[
        dict(model='reimu', scale=1, pos=key([0, 0, 0, 0]), yaw=key([0, 0]), belly=key([0, 1], [births[0], 1], [births[0]+.05, .61], [births[1], .61], [births[1]+.05, .22], [end+3.5, 0]),
             contraction=[t_del, end], clips=[[0, 'brood', 0], [t_del, 'labor', 0], [end+.6, 'postpartum', 0], [end+3.6, 'idle', 0]]),
        dict(model='marisa', scale=1, pos=key([0, 50, 0, -4], [2.2, *FATHER]), yaw=key([0, 60], [2.2, FATHER_YAW]),
             hat=[[0, 1], [2.7, 0], [12.2, 1]], clips=[[0, 'waddle', 0], [2.2, 'doff_hat', 0], [t_del, 'midwife', 0], [end+.6, 'idle', 0], [11.5, 'doff_hat', 0], [12.7, 'idle', 0]]),
    ]+babies, props=[hat_flight(2.7, 3.1, 11.95, 12.2, FATHER, hat)])


def hat_flight(off, down, up, on, head, hat):
    """The hat leaves the head (upright, flip 180), lands turned over (flip 0, long brim away), and goes back on."""
    top = [head[0], 17, head[2]]
    return dict(kind='hat', visible=[off, on], pos=key([off, *top], [down, *hat], [up, *hat], [on, *top]),
                flip=key([off, 180], [down, 0], [up, 0], [on, 180]), yaw=key([0, 180]))


HAT_Z = -23
FATHER, FATHER_YAW = [24, 0, -9], 56


def stem_bases(count):
    """FamilyGeometry.childBase for a Reimu mother (scale 1, variance 1, ripe), in model units."""
    fam = json.loads((PRESENT/'family.json').read_text(encoding='utf-8')); stem = fam['stem']
    units = stem['reference_units_to_model']/16; origin = stem['reference_origin'][1]; length = fam['lengthScale'][count-1]
    var = fam['variants']['reimu']; root = [x/16 for x in var['root']]
    def point(z, y):
        rise = (y-origin)*units*length; clearance = var['depth_clearance']/16*min(1, max(0, rise/(var['clearance_rise']/16)))
        return [root[0], root[1]+rise, root[2]+z*units*length-clearance]
    pendant = stem['pendant_length_reference']*stem['reference_units_to_model']/16; out = []
    for i, (z, y) in enumerate(stem['socket_layouts'][str(count)]):
        s = point(z, y); crown = fam['variants']['reimu']['crown_height']/16*.3
        out.append([s[0]*16, (s[1]-pendant-crown)*16, (s[2]-fam['fruitForwardClearance']/16)*16])
    return out


def scene_land_stem():
    count = 3; bases = stem_bases(count); drops = [6.0+.4*i for i in range(count)]
    cx = min(-21, sum(b[2] for b in bases)/count); hat = [0, CROWN, cx]; seats = [[-3, 3.8, cx-2.5], [3, 3.8, cx-2.5], [0, 3.8, cx+1]]
    babies = []
    for i, b in enumerate(bases):
        babies.append(dict(model='reimu' if i != 1 else 'marisa', scale=.3, visible=[drops[i], 99],
                           pos=key([drops[i], *b], [drops[i]+.55, *seats[i]], [10.9, *seats[i]], [11.4, 8*(i-1), 0, cx+6]), yaw=key([0, 0]), fall=[drops[i], drops[i]+.55],
                           clips=[[drops[i], 'idle', 0], [drops[i]+.55, 'first_wake', 0], [drops[i]+2.6, 'hat_cradle', 0], [11.4, 'idle', 0]],
                           faces=[[drops[i], drops[i]+.55, 'sleep']]))
    return dict(id='land_stem', title='陆生茎生：实子落进魔理沙的帽子', duration=14, span=32, center=10, focus=[8, -12],
                captions=[[0, 2.2, '灵梦头上的茎结满实子，魔理沙父亲赶来'], [2.2, 3.4, '魔理沙摘帽，放在茎下'], [3.4, 6.0, '实子成熟，魔理沙伏低等着'],
                          [6.0, 7.6, '实子一个个落进帽里'], [7.6, 10.4, '母亲产后休息，子油在帽里初醒'], [10.4, 14, '魔理沙戴回帽子，子油下来']],
                ground='land', stem=dict(actor=0, count=count, progress=key([0, .62], [5.6, 1]), drops=drops, kinds=['reimu', 'marisa', 'reimu']),
                actors=[dict(model='reimu', scale=1, pos=key([0, 0, 0, 0]), yaw=key([0, 0]), clips=[[0, 'brood', 0], [drops[-1]+.4, 'postpartum', 0], [drops[-1]+3.4, 'idle', 0]]),
                        dict(model='marisa', scale=1, pos=key([0, 50, 0, -4], [2.2, *FATHER]), yaw=key([0, 60], [2.2, FATHER_YAW]),
                             hat=[[0, 1], [2.7, 0], [10.9, 1]], clips=[[0, 'waddle', 0], [2.2, 'doff_hat', 0], [3.4, 'midwife', 0], [drops[-1]+.6, 'idle', 0], [10.2, 'doff_hat', 0], [11.4, 'idle', 0]])]+babies,
                props=[hat_flight(2.7, 3.1, 10.65, 10.9, FATHER, hat)])


def bob(t): return .5*math.sin(2*math.pi*t/3)


def scene_boat():
    t_del = 2.0; births = live_birth(t_del, 2); end = births[-1]; R = .74*16
    seats = [[-R*math.sin(math.radians(a)), BOAT_SEAT*.1+.96, R*math.cos(math.radians(a))] for a in (-20, 20)]
    babies = []
    for i, b in enumerate(births):
        babies.append(dict(model='marisa', scale=.18, visible=[b-EMERGE, 99], bob=True,
                           pos=key([b-EMERGE, 0, 1.2+BOAT_SEAT, -1.5], [b-.1, 0, .9+BOAT_SEAT, -7.5], [b+.25, 0, 1.5+BOAT_SEAT, -9],
                                   [b+.6, 9*(1 if i else -1), 2+BOAT_SEAT, 0], [b+.9, *seats[i]]), yaw=key([0, 0], [b+.9, 180]),
                           clips=[[b-EMERGE, 'idle', 0], [b+.9, 'first_wake', 0], [b+2.9, 'hat_cradle', 0], [11, 'sleep', 0]], faces=[[b-EMERGE, b+.25, 'sleep']]))
    return dict(id='boat', title='水生帽船分娩：子油生进帽里，夜里在帽里睡', duration=16, span=26, center=6, focus=[0, 0], night=key([0, 0], [9.5, 0], [11, 1]),
                captions=[[0, t_del, '水生魔理沙在自己的帽船上待产'], [t_del, end, '在船上用力，生下后转身把孩子揽进帽里'], [end, 10, '子油坐在帽檐上初醒'], [10, 16, '入夜，子油在亲油的帽里睡']],
                ground='water', actors=[dict(model='marisa', scale=1, bob=True, pos=key([0, 0, BOAT_SEAT, 0]), yaw=key([0, 0]), hat=[[0, 0]],
                                             belly=key([0, 1], [births[0], 1], [births[0]+.05, .61], [births[1], .61], [births[1]+.05, .22], [end+3.5, 0]),
                                             contraction=[t_del, end], clips=[[0, 'boat_brood', 0], [t_del, 'boat_labor', 0], [end+.6, 'boat_drift', 0]])]+babies,
                props=[dict(kind='hat', bob=True, visible=[0, 99], pos=key([0, 0, 0, 0]), flip=key([0, 0]), yaw=key([0, 0]))])


def scene_dock():
    t_shore = 3.6; t_del = 8.4; births = live_birth(t_del, 2); end = births[-1]
    mother = [10, 0, 2]; hat = [10, CROWN, 2+HAT_Z]; seats = [[7, 3.8, HAT_Z], [13, 3.8, HAT_Z]]; father = [34, 0, -7]
    babies = [dict(model='marisa', scale=.18, visible=[b-EMERGE, 99], pos=key([b-EMERGE, 10, 1.2, .5], [b-.15, 10, .8, -10], [b+.2, 10, 6, -17], [b+.45, *seats[i]], [18.4, *seats[i]], [18.9, 10+8*(1 if i else -1), 0, -15]),
                   yaw=key([0, 0]), clips=[[b-EMERGE, 'idle', 0], [b+.45, 'first_wake', 0], [b+2.45, 'hat_cradle', 0], [18.9, 'idle', 0]], faces=[[b-EMERGE, b+.45, 'sleep']])
              for i, b in enumerate(births)]
    return dict(id='dock', title='水生靠岸分娩：父亲魔理沙摘帽接', duration=21, span=40, center=8, focus=[6, -8], ground='bank',
                captions=[[0, t_shore, '临产的水生魔理沙让帽船漂向岸边'], [t_shore, 5.2, '上岸拿回帽子，在河岸就地待产'], [5.2, t_del, '水生父亲赶来，摘帽放在出口前'],
                          [t_del, end+.6, '母亲用力，父亲伏低打气，孩子落进帽里'], [end+.6, 17.4, '产后休息，子油在帽里初醒'], [17.4, 21, '父亲戴回帽子']],
                actors=[dict(model='marisa', scale=1, pos=key([0, -40, BOAT_SEAT, 2], [t_shore, -14, BOAT_SEAT, 2], [t_shore+.6, *mother]), yaw=key([0, 0]),
                             hat=[[0, 0], [t_shore+.6, 1]], belly=key([0, 1], [births[0], 1], [births[0]+.05, .61], [births[1], .61], [births[1]+.05, .22], [end+3.5, 0]),
                             contraction=[t_del, end], clips=[[0, 'boat_brood', 0], [t_shore, 'hop', 0], [t_shore+.8, 'brood', 0], [t_del, 'labor', 0], [end+.6, 'postpartum', 0], [end+3.6, 'idle', 0]]),
                        dict(model='marisa', scale=1, pos=key([0, 64, 0, -10], [5.6, 64, 0, -10], [7.2, *father]), yaw=key([0, 70], [7.2, 60]),
                             hat=[[0, 1], [7.7, 0], [18.2, 1]], clips=[[0, 'idle', 0], [5.6, 'waddle', 0], [7.2, 'doff_hat', 0], [t_del, 'midwife', 0], [end+.6, 'idle', 0], [17.4, 'doff_hat', 0], [18.6, 'idle', 0]])]+babies,
                props=[dict(kind='hat', visible=[0, t_shore+.6], pos=key([0, -40, 0, 2], [t_shore, -14, 0, 2]), flip=key([0, 0]), yaw=key([0, 0])),
                       hat_flight(7.7, 8.1, 17.95, 18.2, father, hat)])


def scene_miss():
    t_del = 3.4; births = live_birth(t_del, 2); fail = births[1]
    hat = [0, CROWN, HAT_Z]; seat = [-3, 3.8, HAT_Z-2.5]
    babies = [dict(model='reimu', scale=.18, visible=[births[0]-EMERGE, 99], pos=key([births[0]-EMERGE, 0, 1.2, -1.5], [births[0]-.15, 0, .8, -12], [births[0]+.2, 0, 6, -19], [births[0]+.45, *seat]),
                   yaw=key([0, 0]), clips=[[births[0]-EMERGE, 'idle', 0], [births[0]+.45, 'first_wake', 0], [births[0]+2.45, 'hat_cradle', 0]], faces=[[births[0]-EMERGE, births[0]+.45, 'sleep']]),
              dict(model='marisa', scale=.18, visible=[fail-EMERGE, fail+1.6], pos=key([fail-EMERGE, 0, 1.2, -1.5], [fail-.15, 0, .8, -12], [fail+.3, 0, 12, -30], [fail+.65, -2, -.5, -48], [fail+1.6, -2, -7, -50]),
                   yaw=key([0, 0]), clips=[[fail-EMERGE, 'idle', 0]], faces=[[fail-EMERGE, fail, 'sleep'], [fail, 99, 'surprise']])]
    return dict(id='miss', title='接生失误：用力过猛，孩子越过帽子落水', duration=14, span=36, center=6, focus=[6, -20], ground='shore',
                captions=[[0, t_del, '河边的胎生，魔理沙父亲已摘帽在前面接'], [t_del, fail, '第一个孩子落进帽里'], [fail, fail+.7, '用力过猛，第二个孩子越过帽子……'],
                          [fail+.7, fail+2.3, '落进水里。父母愣住，呆滞'], [fail+2.3, 14, '「ゆっくり……ゆっくりぃ！」（油库里……油库里——！）父母哭泣']],
                actors=[dict(model='reimu', scale=1, pos=key([0, 0, 0, 0]), yaw=key([0, 0]), belly=key([0, 1], [births[0], 1], [births[0]+.05, .61], [fail, .61], [fail+.05, .22], [fail+3.5, 0]),
                             contraction=[t_del, fail], clips=[[0, 'brood', 0], [t_del, 'labor', 0], [fail+.7, 'bereaved', 0]]),
                        dict(model='marisa', scale=1, pos=key([0, *FATHER]), yaw=key([0, FATHER_YAW]), hat=[[0, 0]], clips=[[0, 'idle', 0], [t_del, 'midwife', 0], [fail+.7, 'bereaved', 0]])]+babies,
                props=[dict(kind='hat', visible=[0, 99], pos=key([0, *hat]), flip=key([0, 0]), yaw=key([0, 180]))])


def scene_night():
    seats = [[0, 3.8, -2], [-3, 3.8, -4], [3, 3.8, -4]]
    babies = [dict(model='marisa', scale=.18, visible=[0, 99], pos=key([0, *s], [8.2, *s], [8.8, 8*(i-1), 0, 10]), yaw=key([0, 160+20*i]),
                   clips=[[0, 'sleep', i*.7], [6.6, 'wake', 0], [8.8, 'idle', 0]]) for i, s in enumerate(seats)]
    return dict(id='night', title='水生子油夜里睡在亲油的帽里', duration=12, span=28, center=6, focus=[8, 2], ground='bank', night=key([0, 1], [5, 1], [6.5, 0]),
                captions=[[0, 6, '夜里，水生魔理沙把帽子翻过来放在岸边，子油睡在帽里，亲油睡在旁边'], [6, 8, '天亮，大家醒来'], [8, 12, '亲油戴回帽子，子油从帽里下来']],
                actors=[dict(model='marisa', scale=1, pos=key([0, 24, 0, 6]), yaw=key([0, 75]), hat=[[0, 0], [8.2, 1]],
                             clips=[[0, 'sleep', 1.3], [6.6, 'wake', 0], [7.6, 'doff_hat', 0], [8.8, 'idle', 0]])]+babies,
                props=[dict(kind='hat', visible=[0, 8.2], pos=key([0, 0, CROWN, 0], [7.95, 0, CROWN, 0], [8.2, 24, 17, 6]), flip=key([0, 0], [7.95, 0], [8.2, 180]), yaw=key([0, 180]))])


SCENES = [scene_land_live, scene_land_stem, scene_boat, scene_dock, scene_miss, scene_night]

PAGE = r'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>出生与接生 · 场景预览</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#eae7de;color:#51463e;font:15px/1.6 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
main{max-width:1100px;margin:auto;padding:18px}h1{font-size:22px;margin:0 0 4px}p{margin:4px 0 8px}
.bar{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin:6px 0}button{font:inherit;font-size:14px;border:1px solid #b9ad99;background:#f7f4ec;color:#51463e;border-radius:6px;padding:3px 10px;cursor:pointer}
button[aria-pressed=true]{background:#51463e;color:#f7f4ec;border-color:#51463e}label{display:inline-flex;gap:6px;align-items:center;font-size:14px}
canvas{display:block;width:100%;height:540px;background:#f4f1e9;border:1px solid #d8d0c1;border-radius:10px;touch-action:none;cursor:grab}
#caption{min-height:2.2em;font-size:16px;margin:8px 0 4px;color:#3d342c}footer{margin-top:12px;font-size:12.5px;color:#7b6f60}
</style>
<main><h1>出生与接生 · 场景预览（待复查）</h1>
<p>灵梦×魔理沙夫妻灵梦为母、魔理沙为父；只有魔理沙接生，摘帽翻过来接。水生魔理沙夫妻都是魔理沙。游戏模型与动作修正资源（__REVISION__），孕肚、收缩、茎与实子按游戏的几何绘制。拖动旋转，滚轮缩放，直接双击打开。单个动作见<a href="motions.html">交互预览</a>。</p>
<div class="bar" id="scenes"></div>
<div class="bar"><button type="button" id="play" aria-pressed="true">暂停</button>
<label>速度 <select id="speed"><option value=".5">0.5×</option><option value="1" selected>1×</option><option value="2">2×</option></select></label>
<label>时间 <input id="time" type="range" min="0" max="1" step=".01" value="0" style="width:320px"> <span id="clock">0.0 秒</span></label></div>
<div id="caption"></div><canvas id="view"></canvas>
<footer>生成于 __DATE__。离线页面只供查看出生与接生的编排，不代表游戏运行验收。场景中的失误是 12% 的原创几率事件；台词沿用已有原生台词。</footer></main>
<script>
const D=__DATA__;
// ---- engine (also run offline by the checks) ----
function decode(b,T){const s=atob(b),u=new Uint8Array(s.length);for(let i=0;i<s.length;i++)u[i]=s.charCodeAt(i);return new T(u.buffer);}
function unpack(src){const raw=decode(src.vertices,Int16Array),out=new Float32Array(raw.length);for(let i=0;i<raw.length;i+=8){for(let k=0;k<3;k++){out[i+k]=raw[i+k]/1024;out[i+5+k]=raw[i+5+k]/1024;}out[i+3]=raw[i+3]/16384;out[i+4]=raw[i+4]/16384;}return out;}
const MODELS={};for(const [id,m] of Object.entries(D.models))MODELS[id]={m,rest:unpack(m),w:decode(m.weights,Uint16Array),q:decode(m.blend,Uint8Array),acc:decode(m.accessory,Uint8Array),mats:m.bones.map(()=>new Float64Array(12)),tex:{},size:m.textureSize,cache:new Map()};
const HAT={rest:unpack(D.hat),size:D.hat.textureSize,tex:null};
function smooth(x){x=Math.min(1,Math.max(0,x));return x*x*(3-2*x);}
function sample(keys,t){const n=keys.length/4;if(t<=keys[0])return [keys[1],keys[2],keys[3]];
  for(let i=1;i<n;i++)if(t<=keys[i*4]){const a=(i-1)*4,b=i*4,q=keys[b]>keys[a]?(t-keys[a])/(keys[b]-keys[a]):0;return [0,1,2].map(k=>keys[a+1+k]+(keys[b+1+k]-keys[a+1+k])*q);}
  const a=(n-1)*4;return [keys[a+1],keys[a+2],keys[a+3]];}
function poseModel(M,name,t){
  const ck=name+'@'+t.toFixed(3);if(M.cache.has(ck))return M.cache.get(ck);
  const clip=M.m.clips[name]||M.m.clips.idle,vals=M.m.bones.map(b=>b.slice(4,13));
  const local=clip.loop?t%clip.d:Math.min(t,clip.d);for(const [bone,off,keys] of clip.tracks){const v=sample(keys,local);vals[bone][off]=v[0];vals[bone][off+1]=v[1];vals[bone][off+2]=v[2];}
  M.m.bones.forEach((b,i)=>{const v=vals[i],P=[b[1],b[2],b[3]];const [x,y,z]=[v[3],v[4],v[5]].map(a=>a*Math.PI/180),cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
    const R=[cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx,sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx,-sy,cy*sx,cy*cx],L=new Float64Array(12);
    for(let r=0;r<3;r++){for(let c=0;c<3;c++)L[r*4+c]=R[r*3+c]*v[6+c];L[r*4+3]=P[r]+v[r]-(L[r*4]*P[0]+L[r*4+1]*P[1]+L[r*4+2]*P[2]);}
    if(b[0]<0)M.mats[i]=L;else{const A=M.mats[b[0]],W=new Float64Array(12);for(let r=0;r<3;r++)for(let c=0;c<4;c++)W[r*4+c]=A[r*4]*L[c]+A[r*4+1]*L[4+c]+A[r*4+2]*L[8+c]+(c===3?A[r*4+3]:0);M.mats[i]=W;}});
  const Rv=M.rest,O=new Float32Array(Rv.length);
  for(let i=0;i<Rv.length/8;i++){const o=i*8,A=M.mats[M.w[i*2]],B=M.mats[M.w[i*2+1]],q=M.q[i]/255,x=Rv[o],y=Rv[o+1],z=Rv[o+2];
    for(let r=0;r<3;r++){const a=A[r*4]*x+A[r*4+1]*y+A[r*4+2]*z+A[r*4+3],b=B[r*4]*x+B[r*4+1]*y+B[r*4+2]*z+B[r*4+3];O[o+r]=a+(b-a)*q;}
    O[o+3]=Rv[o+3];O[o+4]=Rv[o+4];const nx=Rv[o+5],ny=Rv[o+6],nz=Rv[o+7];let mx=A[0]*nx+A[1]*ny+A[2]*nz,my=A[4]*nx+A[5]*ny+A[6]*nz,mz=A[8]*nx+A[9]*ny+A[10]*nz;const l=Math.hypot(mx,my,mz)||1;O[o+5]=mx/l;O[o+6]=my/l;O[o+7]=mz/l;}
  if(M.cache.size>400)M.cache.clear();M.cache.set(ck,O);return O;}
function keyAt(keys,t){if(t<=keys[0][0])return keys[0].slice(1);for(let i=1;i<keys.length;i++)if(t<=keys[i][0]){const a=keys[i-1],b=keys[i],q=smooth((t-a[0])/Math.max(1e-6,b[0]-a[0]));return a.slice(1).map((v,k)=>v+(b[k+1]-v)*q);}return keys[keys.length-1].slice(1);}
function stepAt(list,t,def){let v=def;for(const e of list)if(t>=e[0])v=e;return v;}
function deform(x,y,z,belly,c){if(belly<=0)return [x,y,z];const blend=smooth(y/4.2),lower=1-blend;return [x*(1+.22*belly*lower)*(1+.035*c),(y+2.5*belly*blend)*(1-.04*c),z*(1+.24*belly*lower)];}
function contraction(a,t){if(!a.contraction||t<a.contraction[0]||t>a.contraction[1]+.3)return 0;const w=.5+.5*Math.sin((t-a.contraction[0])*20*Math.PI*2/48);return .22+.78*Math.pow(w,4);}
function bobAt(s,t){return .5*Math.sin(2*Math.PI*t/3);}
function place(src,out,T,skip){ // T: {s,yaw,rx,pos:[x,y,z]}
  const cy=Math.cos(T.yaw*Math.PI/180),sy=Math.sin(T.yaw*Math.PI/180),cx=Math.cos((T.rx||0)*Math.PI/180),sx=Math.sin((T.rx||0)*Math.PI/180);let n=0;
  for(let i=0;i<src.length;i+=24){if(skip&&skip(i/8))continue;for(let j=0;j<3;j++){const o=i+j*8;let x=src[o]*T.s,y=src[o+1]*T.s,z=src[o+2]*T.s,nx=src[o+5],ny=src[o+6],nz=src[o+7];
      if(T.rx){[y,z]=[y*cx-z*sx,y*sx+z*cx];[ny,nz]=[ny*cx-nz*sx,ny*sx+nz*cx];}
      out[n]=x*cy+z*sy+T.pos[0];out[n+1]=y+T.pos[1];out[n+2]=-x*sy+z*cy+T.pos[2];out[n+3]=src[o+3];out[n+4]=src[o+4];out[n+5]=nx*cy+nz*sy;out[n+6]=ny;out[n+7]=-nx*sy+nz*cy;n+=8;}}
  return out.subarray(0,n);}
function faceAt(M,a,clip,local,t){if(a.faces)for(const [t0,t1,f] of a.faces)if(t>=t0&&t<t1)return f;const c=M.m.clips[clip];if(!c)return 'neutral';const u=c.loop?local%c.d:Math.min(local,c.d);
  for(const [s,e,f] of c.faces)if(u>=s&&u<e)return f;return 'neutral';}
function quad(x0,z0,x1,z1,y,color){const v=new Float32Array(48);const P=[[x0,z0],[x1,z0],[x1,z1],[x0,z0],[x1,z1],[x0,z1]];P.forEach((p,i)=>{v.set([p[0],y,p[1],-1,0,0,1,0],i*8);});return {v,color};}
function tube(out,a,b,r0,r1){const ax=[b[0]-a[0],b[1]-a[1],b[2]-a[2]],l=Math.hypot(...ax)||1;ax.forEach((v,i)=>ax[i]=v/l);const ref=Math.abs(ax[2])<.9?[0,0,1]:[1,0,0];
  const cr=(p,q)=>[p[1]*q[2]-p[2]*q[1],p[2]*q[0]-p[0]*q[2],p[0]*q[1]-p[1]*q[0]];let u=cr(ax,ref);const ul=Math.hypot(...u);u=u.map(v=>v/ul);const w=cr(ax,u);
  for(let i=0;i<8;i++){const A=i*Math.PI/4,B=(i+1)*Math.PI/4,na=[0,1,2].map(k=>u[k]*Math.cos(A)+w[k]*Math.sin(A)),nb=[0,1,2].map(k=>u[k]*Math.cos(B)+w[k]*Math.sin(B));
    const p=[a.map((v,k)=>v+na[k]*r0),b.map((v,k)=>v+na[k]*r1),b.map((v,k)=>v+nb[k]*r1),a.map((v,k)=>v+nb[k]*r0)];
    for(const [i0,i1,i2] of [[0,1,2],[0,2,3]])for(const k of [i0,i1,i2])out.push(...p[k],-1,0,...na);}}
function stemItems(scene,actor,t){
  const st=scene.stem,F=D.family,S=F.stem,count=st.count,prog=keyAt(st.progress,t)[0],grow=smooth(Math.min(1,prog/.15)),units=S.reference_units_to_model/16,len=F.lengthScale[count-1],V=F.variants.reimu;
  const root=V.root.map(v=>v/16);const point=(z,y)=>{const rise=(y-S.reference_origin[1])*units*len,cl=V.depth_clearance/16*Math.min(1,Math.max(0,rise/(V.clearance_rise/16)));return [root[0],root[1]+rise,root[2]+z*units*len-cl];};
  const spent=t>=st.drops[st.drops.length-1],col=spent?[157,135,79]:[66,186,57],tri=[];const arc=S.centerline.map(([z,y])=>{const p=point(z,y);return root.map((r,k)=>(r+(p[k]-r)*grow)*16);});
  for(let i=1;i<arc.length;i++){const r0=(S.radius[0]+(S.radius[1]-S.radius[0])*(i-1)/(arc.length-1))*F.radiusScale[count-1],r1=(S.radius[0]+(S.radius[1]-S.radius[0])*i/(arc.length-1))*F.radiusScale[count-1];tube(tri,arc[i-1],arc[i],r0,r1);}
  const items=[];const fs=Math.min(.3,.12+.18*Math.min(1,Math.max(0,(prog-.15)/.8)));
  S.socket_layouts[String(count)].forEach(([z,y],i)=>{if(t>=st.drops[i])return;const s=point(z,y).map(v=>v*16);const base=[s[0],s[1]-S.pendant_length_reference*S.reference_units_to_model-F.variants.reimu.crown_height*fs,s[2]-F.fruitForwardClearance];
    tube(tri,s,[base[0],base[1]+F.variants.reimu.crown_height*fs,base[2]],.12,.09);const M=MODELS[st.kinds[i]],posed=poseModel(M,'idle',0);
    items.push({v:place(posed,new Float32Array(posed.length),{s:fs,yaw:0,pos:base}),tex:M.tex.sleep,size:M.size});});
  const T=actorT(actor,t);const v=place(new Float32Array(tri),new Float32Array(tri.length),T);items.push({v,color:col});
  for(const it of items.slice(0,-1))it.v=place(it.v,new Float32Array(it.v.length),T);return items;}
function actorT(a,t){const p=keyAt(a.pos,t);if(a.bob)p[1]+=bobAt(null,t);return {s:a.scale,yaw:keyAt(a.yaw,t)[0],pos:p};}
function frameItems(scene,t){
  const items=[];
  if(scene.ground==='land')items.push(quad(-70,-70,70,70,-.02,[214,204,178]));
  if(scene.ground==='water')items.push(quad(-70,-70,70,70,-1.2,[96,148,196]));
  if(scene.ground==='bank'){items.push(quad(-6,-70,70,70,-.02,[214,204,178]));items.push(quad(-80,-70,-6,70,-1.2,[96,148,196]));}
  if(scene.ground==='shore'){items.push(quad(-70,-30,70,70,-.02,[214,204,178]));items.push(quad(-70,-80,70,-30,-1.2,[96,148,196]));}
  scene.actors.forEach((a,idx)=>{
    if(a.visible&&(t<a.visible[0]||t>=a.visible[1]))return;const M=MODELS[a.model];
    const [t0,clip,off]=stepAt(a.clips,t,a.clips[0]);let local=t-t0+off;
    if(a.fall&&t>=a.fall[0]&&t<a.fall[1])local=0;
    const posed=poseModel(M,clip,local),belly=a.belly?keyAt(a.belly,t)[0]:0,c=contraction(a,t),hat=a.hat?stepAt(a.hat,t,[0,1])[1]:1;
    let src=posed;if(belly>0){src=new Float32Array(posed);for(let i=0;i<src.length;i+=8){const d=deform(src[i],src[i+1],src[i+2],belly,c);src[i]=d[0];src[i+1]=d[1];src[i+2]=d[2];}}
    const T=actorT(a,t);if(a.fall&&t>=a.fall[0]&&t<a.fall[1]){const q=(t-a.fall[0])/(a.fall[1]-a.fall[0]),k=a.pos;T.pos=k[0].slice(1).map((v,i)=>v+(k[1][i+1]-v)*(i===1?q*q:q));}
    const v=place(src,new Float32Array(src.length),T,hat?null:(vi=>M.acc[vi]));
    items.push({v,tex:M.tex[faceAt(M,a,clip,local,t)]||M.tex.neutral,size:M.size});
    if(scene.stem&&scene.stem.actor===idx)items.push(...stemItems(scene,a,t));});
  for(const p of scene.props){if(t<p.visible[0]||t>=p.visible[1])continue;const pos=keyAt(p.pos,t);if(p.bob)pos[1]+=bobAt(null,t);
    items.push({v:place(HAT.rest,new Float32Array(HAT.rest.length),{s:1,yaw:keyAt(p.yaw,t)[0],rx:keyAt(p.flip,t)[0],pos}),tex:HAT.tex,size:HAT.size});}
  return items;}
function renderScene(items,width,height,az,el,span,centerY,night,focus){
  const fx=focus?focus[0]:0,fz=focus?focus[1]:0;
  const px=new Uint8ClampedArray(width*height*4),depth=new Float64Array(width*height).fill(-Infinity);
  const s=Math.sin(az),c=Math.cos(az),se=Math.sin(el),ce=Math.cos(el),right=[c,0,s],up=[-s*se,ce,c*se],eye=[s*ce,se,-c*ce],scale=height/(2*span);
  const tint=[1-.55*night,1-.5*night,1-.25*night];
  for(const it of items){const V=it.v,tex=it.tex,size=it.size;if(!it.color&&!tex)continue;
    for(let i=0;i<V.length;i+=24){
      const a=[0,1,2].map(k=>V[i+8+k]-V[i+k]),b=[0,1,2].map(k=>V[i+16+k]-V[i+k]);const nx=a[1]*b[2]-a[2]*b[1],ny=a[2]*b[0]-a[0]*b[2],nz=a[0]*b[1]-a[1]*b[0];
      if(!it.color&&nx*eye[0]+ny*eye[1]+nz*eye[2]<=0)continue;
      const nl=Math.hypot(nx,ny,nz)||1,light=.78+.22*Math.max(0,(V[i+5]*-.35+V[i+6]*.8+V[i+7]*-.48));const lit=it.color?(.86+.14*Math.abs(ny/nl)):light;
      const tri=[];for(let j=0;j<3;j++){const k=i+j*8,x=V[k]-fx,y=V[k+1]-centerY,z=V[k+2]-fz;tri.push([width/2+(x*right[0]+z*right[2])*scale,height/2-(x*up[0]+y*up[1]+z*up[2])*scale,x*eye[0]+y*eye[1]+z*eye[2],V[k+3]*size,V[k+4]*size]);}
      const [A,B,C]=tri,det=(B[1]-C[1])*(A[0]-C[0])+(C[0]-B[0])*(A[1]-C[1]);if(Math.abs(det)<1e-8)continue;
      const x0=Math.max(0,Math.floor(Math.min(A[0],B[0],C[0]))),x1=Math.min(width-1,Math.ceil(Math.max(A[0],B[0],C[0]))),y0=Math.max(0,Math.floor(Math.min(A[1],B[1],C[1]))),y1=Math.min(height-1,Math.ceil(Math.max(A[1],B[1],C[1])));
      for(let y=y0;y<=y1;y++)for(let x=x0;x<=x1;x++){const wa=((B[1]-C[1])*(x+.5-C[0])+(C[0]-B[0])*(y+.5-C[1]))/det,wb=((C[1]-A[1])*(x+.5-C[0])+(A[0]-C[0])*(y+.5-C[1]))/det,wc=1-wa-wb;
        if(wa<-1e-7||wb<-1e-7||wc<-1e-7)continue;const z=wa*A[2]+wb*B[2]+wc*C[2],idx=y*width+x;if(z<=depth[idx]+1e-7)continue;
        let r,g,bl;if(it.color){[r,g,bl]=it.color;}else{const u=Math.max(0,Math.min(size-1,Math.floor(wa*A[3]+wb*B[3]+wc*C[3]))),v=Math.max(0,Math.min(size-1,Math.floor(wa*A[4]+wb*B[4]+wc*C[4]))),t=(v*size+u)*4;if(tex[t+3]<128)continue;r=tex[t];g=tex[t+1];bl=tex[t+2];}
        depth[idx]=z;const p=idx*4;px[p]=r*lit*tint[0];px[p+1]=g*lit*tint[1];px[p+2]=bl*lit*tint[2];px[p+3]=255;}}}
  return px;}
// ---- end engine ----
const $=id=>document.getElementById(id),canvas=$('view');let scene=D.scenes[0],time=0,playing=true,last=performance.now(),az=-.55,el=.38,zoom=1,ready=0;
function load(src,set){const img=new Image();img.onload=()=>{const c=document.createElement('canvas');c.width=img.width;c.height=img.height;const x=c.getContext('2d');x.drawImage(img,0,0);set(x.getImageData(0,0,c.width,c.height).data);ready--;};ready++;img.src=src;}
for(const M of Object.values(MODELS))for(const [f,src] of Object.entries(M.m.textures))load(src,d=>M.tex[f]=d);load(D.hat.texture,d=>HAT.tex=d);
D.scenes.forEach((s,i)=>{const b=document.createElement('button');b.type='button';b.textContent=s.title;b.onclick=()=>{scene=s;time=0;$('time').max=s.duration;document.querySelectorAll('#scenes button').forEach(o=>o.setAttribute('aria-pressed',String(o===b)));};$('scenes').append(b);if(!i)b.click();});
$('play').onclick=()=>{playing=!playing;$('play').textContent=playing?'暂停':'播放';$('play').setAttribute('aria-pressed',String(playing));};
$('time').oninput=()=>{time=Number($('time').value);playing=false;$('play').textContent='播放';};
const ptr=new Map();canvas.addEventListener('pointerdown',e=>{ptr.set(e.pointerId,[e.clientX,e.clientY]);canvas.setPointerCapture(e.pointerId);});
canvas.addEventListener('pointermove',e=>{if(!ptr.has(e.pointerId))return;const o=ptr.get(e.pointerId);ptr.set(e.pointerId,[e.clientX,e.clientY]);az-=(e.clientX-o[0])*.008;el=Math.max(-.2,Math.min(1.3,el+(e.clientY-o[1])*.008));});
for(const n of ['pointerup','pointercancel'])canvas.addEventListener(n,e=>ptr.delete(e.pointerId));
canvas.addEventListener('wheel',e=>{e.preventDefault();zoom=Math.max(.5,Math.min(3,zoom*Math.exp(-e.deltaY*.001)));},{passive:false});
function tick(now){const dt=Math.min(.1,(now-last)/1000);last=now;if(playing){time+=dt*Number($('speed').value);if(time>scene.duration)time=0;$('time').value=time;}
  $('clock').textContent=time.toFixed(1)+' 秒';const cap=scene.captions.find(([a,b])=>time>=a&&time<b);$('caption').textContent=cap?cap[2]:'';
  if(ready===0){const w=Math.max(1,Math.round(canvas.clientWidth)),h=Math.max(1,Math.round(canvas.clientHeight));if(canvas.width!==w)canvas.width=w;if(canvas.height!==h)canvas.height=h;
    const night=scene.night?keyAt(scene.night,time)[0]:0;canvas.getContext('2d').putImageData(new ImageData(renderScene(frameItems(scene,time),w,h,az,el,scene.span/zoom,scene.center,night,scene.focus),w,h),0,0);}
  requestAnimationFrame(tick);}
requestAnimationFrame(tick);
</script></html>
'''


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0]); ap.add_argument('--out', default=str(P.OUT/'births.html')); a = ap.parse_args()
    corrections = json.loads((PRESENT/'motion_corrections.json').read_text(encoding='utf-8'))
    family = json.loads((PRESENT/'family.json').read_text(encoding='utf-8'))
    data = dict(models={ident: pack(ident, corrections) for ident in ('reimu', 'marisa')}, hat=hat_prop(),
                family=dict(stem=family['stem'], lengthScale=family['lengthScale'], radiusScale=family['radiusScale'],
                            fruitForwardClearance=family['fruitForwardClearance'], variants={k: family['variants'][k] for k in ('reimu', 'marisa')}),
                scenes=[s() for s in SCENES])
    html = (PAGE.replace('__REVISION__', corrections['revision']).replace('__DATE__', datetime.date.today().isoformat())
            .replace('__DATA__', json.dumps(data, ensure_ascii=False, separators=(',', ':'))))
    Path(a.out).write_text(html, encoding='utf-8')
    print('wrote', a.out, f'{len(html)/1e6:.2f} MB', len(data['scenes']), 'scenes')


if __name__ == '__main__':
    main()
