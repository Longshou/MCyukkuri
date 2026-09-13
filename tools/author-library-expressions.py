"""Author pixel expression recipes after visual review of verified Hakkei U sprites.
No extracted game pixels are used in the distributable library.
"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageOps
import json,hashlib,copy,re
ROOT=Path(__file__).resolve().parent.parent;R=ROOT/'standards/yukkuri-library';OLD=ROOT/'standards/yukkuri-v1';REF=ROOT/'reference/hakkei-u-expression-audit'
if (R/'expression-restoration.json').exists():
 raise SystemExit('Existing expressions are protected. Edit missing draft presets individually; do not batch-replace retained artwork.')
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
face=read(R/'components/common/face.json')['elements'];geom={e['name']:e for e in face};atlas=Image.open(OLD/'textures/marisa/neutral.png').convert('RGBA')
ink='#49362d';white='#fff8e9';red='#a65448';tongue='#dc8974';pink='#dba58d';water='#bee0e4'
def blank(size):return Image.new('RGBA',size)
def seed(part,preset='neutral'):
 im=Image.open(OLD/f'textures/marisa/{preset}.png').convert('RGBA');return im.crop(tuple(map(int,geom[part]['faces']['north']['uv'])))
def eyes(kind,side=0):
 if kind=='normal':return seed('face_eye_left' if side==0 else 'face_eye_right')
 im=blank((15,12));d=ImageDraw.Draw(im)
 if kind=='none':return im
 if kind in ['happy','sleep','closed','squeeze','flat','weak','wink']:
  if kind=='happy':p=[(1,7),(3,5),(6,4),(9,4),(12,5),(14,7)];d.line(p,fill=ink,width=2)
  elif kind in ['sleep','wink']:d.line([(1,6),(4,8),(8,9),(11,8),(14,6)],fill=ink,width=2)
  elif kind=='closed':d.line([(1,7),(5,6),(10,6),(14,7)],fill=ink,width=2);d.line([(2,9),(7,8),(13,9)],fill=ink)
  elif kind=='squeeze':d.line([(1,3),(11,7),(1,8)],fill=ink,width=2)
  elif kind=='weak':d.line([(1,6),(13,8),(2,9)],fill=ink,width=1)
  else:d.line([(1,7),(14,7)],fill=ink,width=2)
  if side and kind in ['squeeze','weak']:im=ImageOps.mirror(im)
  return im
 if kind=='hollow':return seed('face_eye_left' if side==0 else 'face_eye_right','surprise')
 if kind=='down':
  d.ellipse((1,1,13,11),fill=white,outline=ink,width=1);d.ellipse((3,5,11,12),fill=ink);d.rectangle((5,6,7,7),fill=white)
 elif kind in ['dull','gray','pin','dead','dry','squashed']:
  if kind=='dry':d.polygon([(4,0),(8,2),(13,10),(11,11),(2,11),(1,9)],fill=ink);d.polygon([(4,3),(7,4),(10,9),(4,10)],fill='#646158')
  else:
   d.ellipse((1,1,13,11),fill=white if kind in ['pin','dead'] else ink,outline=ink)
   if kind in ['pin','dead']:d.ellipse((2,0,12,8),fill='#78766f');d.ellipse((6,2,8,4),fill=ink if kind=='pin' else white)
   elif kind in ['gray','squashed']:d.ellipse((3,3,11,9),fill='#aaa69a')
 elif kind=='half':
  im=seed('face_eye_left' if side==0 else 'face_eye_right');d=ImageDraw.Draw(im);d.rectangle((0,0,14,4),fill=(0,0,0,0));d.line((1,5,13,5),fill=ink,width=2)
 elif kind=='shiny':
  im=seed('face_eye_left' if side==0 else 'face_eye_right');d=ImageDraw.Draw(im);d.line((4,10,10,10),fill=white,width=1)
 else:raise ValueError('eye '+kind)
 return im

def brows(kind,side=0):
 if kind=='normal':return seed('face_brow_left' if side==0 else 'face_brow_right')
 im=blank((17,6));d=ImageDraw.Draw(im)
 if kind=='none':return im
 p={'sad':[(1,1),(5,3),(10,3),(15,0)],'angry':[(1,0),(8,3),(15,5)],'relaxed':[(1,3),(5,1),(11,1),(15,3)],'worried':[(1,5),(6,4),(11,2),(15,0)],'flat':[(1,3),(15,3)],'arched':[(1,5),(4,2),(8,0),(12,2),(15,5)]}[kind]
 d.line(p,fill=ink,width=1)
 return ImageOps.mirror(im) if side and kind in ['sad','angry','worried'] else im

def mouth(kind):
 if kind=='normal':return seed('face_mouth')
 if kind=='wail':return seed('face_mouth','surprise')
 im=blank((14,12));d=ImageDraw.Draw(im)
 if kind=='none':return im
 if kind in ['smile','round','yawn','pant','frown_open','tongue','tongue_short','tongue_long','cut_wail','dry']:
  shapes={'smile':[(2,2),(11,2),(11,5),(9,8),(5,8),(2,5)],'round':[(3,2),(9,2),(11,4),(10,8),(7,10),(3,8),(2,4)],'yawn':[(4,0),(9,0),(12,5),(11,9),(2,9),(1,5)],'pant':[(5,4),(8,4),(9,7),(5,7)],'frown_open':[(2,6),(4,3),(8,3),(11,6),(10,7),(6,5),(3,7)],'tongue':[(4,2),(10,2),(9,7),(7,11),(5,7)],'tongue_short':[(3,3),(10,3),(9,7),(5,7)],'tongue_long':[(4,0),(10,0),(12,5),(9,11),(5,11),(2,6)],'dry':[(5,0),(9,0),(10,11),(3,11)],'cut_wail':[(2,0),(10,0),(12,10),(9,7),(6,6),(3,9)]}
  p=shapes[kind];d.polygon(p,fill=red,outline=ink)
  if kind in ['tongue','tongue_long','tongue_short']:d.polygon([(5,4),(9,4),(8,9),(6,9)],fill=tongue);d.line((7,5,7,8),fill=red)
  elif kind in ['smile','round','yawn','wail']:d.rectangle((4,5,9,7),fill=tongue)
  if kind=='pant':d.line((5,9,5,10),fill=ink);d.line((8,9,9,10),fill=ink)
  if kind=='cut_wail':d.rectangle((0,8,13,11),fill=(0,0,0,0))
 elif kind=='beak':d.polygon([(3,4),(7,2),(10,4),(7,6)],fill=red,outline=ink)
 elif kind=='purse':d.line([(1,3),(4,5),(6,2),(8,5),(11,3)],fill=ink,width=1);d.line((5,7,4,10),fill=ink);d.line((8,7,9,9),fill=ink)
 elif kind=='caret':d.line([(4,6),(7,2),(10,6)],fill=ink,width=1)
 elif kind=='frown':d.line([(3,7),(7,3),(11,7)],fill=red,width=2)
 elif kind=='wave':d.line([(2,5),(5,4),(8,5),(11,4)],fill=ink)
 elif kind=='closed_smile':d.line([(2,4),(4,6),(7,7),(10,6),(12,4)],fill=ink)
 elif kind=='omega':d.line([(2,3),(2,5),(5,6),(7,4),(9,6),(12,5),(12,3)],fill=ink)
 elif kind=='chew':d.line([(1,5),(6,6),(11,5)],fill=ink);d.arc((9,2,14,11),90,270,fill=ink)
 else:raise ValueError('mouth '+kind)
 return im

def tears(kind,side=0):
 im=blank((15,16));d=ImageDraw.Draw(im)
 if kind=='none':return im
 if kind in ['wet','shimmer']:
  d.line((2,5,10,5),fill=white,width=1);return im
 y=4+(1 if kind.endswith('b') else 0)
 if kind.startswith('stream'):d.polygon([(3,y),(10,y),(9,15),(5,13)],fill=water)
 d.ellipse((1,y,12,y+4),fill=water,outline='#97bac7');d.line((3,y,9,y),fill=white,width=1)
 if kind in ['side','stream_b']:d.rectangle((0,y+1,2,y+2),fill=white)
 if side:im=ImageOps.mirror(im)
 return im

def blush(kind):
 if kind=='normal':return seed('face_blush_left')
 im=blank((10,6));d=ImageDraw.Draw(im)
 if kind=='none':return im
 d.rounded_rectangle((0,1,9,4),radius=2,fill={'flushed':'#e48f7a','pale':'#c8b7bd','puffed':'#efbf9e'}.get(kind,pink))
 if kind=='flushed':d.line((2,2,2,4),fill='#be6e62');d.line((5,2,5,4),fill='#be6e62');d.line((8,2,8,4),fill='#be6e62')
 return im

def effect(kind):
 im=blank((14,11));d=ImageDraw.Draw(im)
 if kind=='none':return im
 if kind in ['drool','drool_b']:
  y=2 if kind=='drool' else 4;d.polygon([(9,y),(11,y),(12,y+4),(11,y+6),(9,y+5)],fill=water,outline=ink)
 elif kind=='blush_bridge':d.rectangle((0,0,13,2),fill=(219,112,94,120))
 elif kind=='stress':d.line((4,5,3,8),fill=ink);d.line((8,5,9,8),fill=ink)
 return im

PRESETS={}
def add(id,label,eye='normal',brow='normal',mouth_='normal',tear='none',blush_='normal',effect_='none',**kw):
 PRESETS[id]=dict(id=id,label=label,group=kw.pop('group','日常'),recipe=dict(eyes=eye,brows=brow,mouth=mouth_,tears=tear,blush=blush_,effect=effect_),**kw)
add('neutral','普通')
add('open_smile','睁眼笑',mouth_='smile')
add('happy','闭眼大笑','happy','relaxed','smile',group='笑脸')
add('closed_smile','闭口微笑','happy','relaxed','closed_smile',group='笑脸')
add('smug','得意','normal','angry','wave')
add('cry','闭眼哭喊','squeeze','sad','wail','flow',group='哭泣')
add('cry_b','闭眼哭喊 · 泪帧 B','squeeze','sad','wail','flow_b',group='哭泣')
add('teary','含泪','normal','sad','caret','flow',group='哭泣')
add('teary_b','含泪 · 泪帧 B','normal','sad','caret','flow_b',group='哭泣')
add('drool','闭眼流口水','closed','relaxed','round',effect_='drool',group='睡眠 / 生理')
add('drool_b','闭眼流口水 · 滴落','closed','relaxed','round',effect_='drool_b',group='睡眠 / 生理')
add('confused','困惑张嘴','normal','sad','smile',group='惊吓 / 困惑')
add('confused_small','困惑小嘴','normal','sad','beak',group='惊吓 / 困惑')
add('blank_confused','困惑白眼','hollow','sad','beak',group='惊吓 / 困惑')
add('teary_smile','含泪笑','shiny','sad','smile','flow',group='笑脸')
add('teary_smile_b','含泪笑 · 泪帧 B','normal','sad','smile','flow_b',group='笑脸')
add('yawn','打哈欠','closed','relaxed','yawn','flow',group='睡眠 / 生理')
add('yawn_b','打哈欠 · 泪帧 B','closed','relaxed','yawn','flow_b',group='睡眠 / 生理')
add('sleep','睡眠','sleep','relaxed','round',effect_='drool',group='睡眠 / 生理')
add('sleep_b','睡眠 · 滴落','sleep','relaxed','round',effect_='drool_b',group='睡眠 / 生理')
add('tear_sleep','含泪睡眠','sleep','sad','beak','flow',group='睡眠 / 生理')
add('tear_sleep_b','含泪睡眠 · 泪帧 B','sleep','sad','beak','flow_b',group='睡眠 / 生理')
add('squeezed_teary','挤眼含泪','squeeze','sad','caret','flow',effect_='stress',group='哭泣')
add('squeezed_teary_b','挤眼含泪 · 泪帧 B','squeeze','sad','caret','flow_b',effect_='stress',group='哭泣')
add('squeezed_frown','挤眼哭丧','squeeze','sad','frown_open','flow',effect_='stress',group='哭泣')
add('squeezed_frown_b','挤眼哭丧 · 泪帧 B','squeeze','sad','frown_open','flow_b',effect_='stress',group='哭泣')
add('waking','刚睡醒','normal','relaxed','round',effect_='drool',eye_left='wink',group='睡眠 / 生理')
add('puff','闭眼噗咕','squeeze','sad','purse',group='生气 / 鼓脸',requires=['puff-cheeks'])
add('angry','生气','normal','angry','caret',effect_='stress',group='生气 / 鼓脸')
add('puff_open','睁眼噗咕','half','sad','purse',group='生气 / 鼓脸',requires=['puff-cheeks'])
add('puff_teary','含泪噗咕','half','sad','purse','wet',group='生气 / 鼓脸',requires=['puff-cheeks'])
add('surprise','受惊逃跑','hollow','sad','wail',group='惊吓 / 困惑',accepted_override=True)
add('look_down','低头困惑','down','relaxed','beak',group='惊吓 / 困惑')
add('pin_cry','灰瞳哭喊','pin','sad','wail','flow',group='特殊状态')
add('pin_cry_b','灰瞳哭喊 · 泪帧 B','pin','sad','wail','flow_b',group='特殊状态')
add('tongue_cry','灰瞳伸舌哭喊','pin','sad','tongue_long','stream','pale',group='特殊状态')
add('tongue_cry_b','灰瞳伸舌哭喊 · 泪帧 B','pin','sad','tongue_long','stream_b','pale',group='特殊状态')
add('dead_tongue','失神垂舌','dead','sad','tongue_long','stream','pale',group='特殊状态')
add('blank_cry','白眼哭喊','hollow','sad','wail','flow',group='哭泣')
add('blank_cry_b','白眼哭喊 · 泪帧 B','hollow','sad','wail','flow_b',group='哭泣')
add('squeezed_cry','挤眼喊叫','squeeze','angry','wail',group='哭泣')
add('squeezed_cry_tears','挤眼喊叫含泪','squeeze','angry','wail','flow',group='哭泣')
add('teary_pant','含泪喘嘴','shiny','sad','pant','flow',group='哭泣')
add('teary_pant_b','含泪喘嘴 · 泪帧 B','shiny','sad','pant','flow_b',group='哭泣')
add('dull','失去高光','dull','relaxed','smile',group='特殊状态')
add('flushed_smile','潮红闭眼笑','happy','relaxed','smile',blush_='flushed',effect_='blush_bridge',group='特殊状态')
add('flushed_tongue','潮红闭眼伸舌','happy','relaxed','tongue',blush_='flushed',effect_='blush_bridge',group='特殊状态')
add('teary_tongue','含泪伸舌','normal','sad','tongue','flow','flushed','blush_bridge',group='特殊状态')
add('teary_tongue_b','含泪伸舌 · 泪帧 B','normal','sad','tongue','flow_b','flushed','blush_bridge',group='特殊状态')
add('endure','白眼忍耐','hollow','normal','wave',group='惊吓 / 困惑')
add('horrified','嫌恶惊惧','shiny','arched','frown_open','flow',effect_='stress',group='惊吓 / 困惑')
add('horrified_b','嫌恶惊惧 · 泪帧 B','shiny','arched','frown_open','flow_b',effect_='stress',group='惊吓 / 困惑')
add('side_teary','垂眼哭丧','flat','sad','frown_open','flow',effect_='stress',group='哭泣')
add('side_teary_b','垂眼哭丧 · 泪帧 B','flat','sad','frown_open','flow_b',effect_='stress',group='哭泣')
add('down_angry','低头生气','down','angry','frown',effect_='stress',group='生气 / 鼓脸')
add('dry_dead','干枯失神','dry','worried','dry',blush_='none',group='特殊状态')
add('squashed','压扁失神','squashed','worried','frown_open',blush_='none',group='特殊状态')
add('teary_frown','睁眼哭丧','normal','sad','frown_open','flow',group='哭泣')
add('teary_frown_b','睁眼哭丧 · 泪帧 B','normal','sad','frown_open','flow_b',group='哭泣')
add('open_drool','睁眼流口水','normal','normal','round',effect_='drool',group='睡眠 / 生理')
add('open_drool_b','睁眼流口水 · 滴落','normal','normal','round',effect_='drool_b',group='睡眠 / 生理')
add('landing','挤眼抿嘴','squeeze','sad','omega',group='动作表情')
add('open_cry','睁眼哭喊','normal','sad','wail','flow',group='哭泣')
add('open_cry_b','睁眼哭喊 · 泪帧 B','normal','sad','wail','flow_b',group='哭泣')
add('pursed','睁眼抿嘴','normal','normal','purse',group='动作表情')
add('chewing','咀嚼张嘴','closed','normal','round',group='动作表情')
add('chewing_closed','咀嚼闭嘴','closed','normal','chew',blush_='puffed',group='动作表情')
add('bad_taste','难吃张嘴','squeeze','angry','wail','flow',group='动作表情')
add('bad_taste_small','难吃缩嘴','squeeze','angry','round','flow',group='动作表情')
add('bad_taste_closed','难吃闭嘴','squeeze','angry','chew','flow','puffed',group='动作表情')
add('angry_stare','不满凝视','normal','angry','beak',blush_='flushed',effect_='blush_bridge',group='生气 / 鼓脸')
add('blank_happy','白眼张嘴','hollow','relaxed','smile',group='惊吓 / 困惑')
add('cold','寒冷发颤','weak','sad','beak','flow',effect_='stress',group='睡眠 / 生理')
add('cold_b','寒冷发颤 · 泪帧 B','weak','sad','beak','flow_b',effect_='stress',group='睡眠 / 生理')
add('cut_cry','受损哭喊','hollow','sad','cut_wail','flow',group='特殊状态')
add('pressed_teary','挤脸含泪','hollow','sad','caret','flow','puffed',group='特殊状态',cheek_scale=1.4)
add('pressed_cry','挤脸哭喊','hollow','sad','wail','flow','puffed',group='特殊状态',cheek_scale=1.4)
add('licking','闭眼吐舌','happy','angry','tongue_short',blush_='flushed',effect_='blush_bridge',group='动作表情')
add('dying','垂瞳含泪','down','sad','pant','flow',group='特殊状态')
add('dying_b','垂瞳含泪 · 泪帧 B','down','sad','pant','flow_b',group='特殊状态')
add('old_dead','闭眼平嘴','sleep','relaxed','wave',group='特殊状态')
add('dying_blank','白眼喘嘴','hollow','sad','pant','flow',group='特殊状态')
add('dying_closed','虚弱闭眼','weak','worried','pant','side',blush_='none',group='特殊状态')
add('dying_closed_b','虚弱闭眼 · 泪帧 B','weak','worried','pant','flow_b',blush_='none',group='特殊状态')
add('pressed_hidden','被压遮挡','none','angry','none',blush_='none',group='特殊状态')
add('satisfied','满足闭眼','closed','relaxed','round',group='动作表情')
add('blank_frown','白眼哭丧','hollow','sad','frown_open','flow',group='特殊状态')
add('impact','受击挤眼','squeeze','angry','frown_open',group='特殊状态')
add('impact_teary','受击挤眼含泪','squeeze','angry','frown_open','flow',group='特殊状态')
add('shattered','表情破碎','squeeze','angry','frown_open',group='特殊状态',mask='fracture')
add('shattered_fade','破碎散落','squeeze','angry','frown_open',group='特殊状态',mask='scatter')
add('flick_dull','受击失神','dull','relaxed','smile',blush_='puffed',group='特殊状态',cheek_scale=1.4)
# Explicit source-to-recipe mapping, based on the contact sheets, not name guessing.
MAP={}
def link(id,ids):
 for sid in ids:assert sid not in MAP,(sid,id);MAP[sid]=id
link('open_smile',[6280,7933,6637,5619,4718,7445,7716,4449,6290,5460])
link('smug',[4478,6822])
link('cry',[6927,6204,6882,4755,5625,5306,7987,6706,5724,7978,8260,5890,6801,4389])
link('cry_b',[6786,5297,5339,7184,7794])
link('teary',[5577,7798,6339,7712,6416,6392,4661])
link('teary_b',[7035,8059,6594,7791])
link('drool',[6449]);link('drool_b',[6156]);link('confused',[6401,8414]);link('confused_small',[4494,6014]);link('blank_confused',[5640,6626])
link('teary_smile',[7697,5276,6424,5736,7626,5286]);link('teary_smile_b',[5146,6292,4878]);link('yawn',[8564]);link('yawn_b',[5295])
link('sleep',[5335]);link('sleep_b',[6235]);link('tear_sleep',[8410]);link('tear_sleep_b',[8131]);link('squeezed_teary',[8373]);link('squeezed_teary_b',[7004]);link('squeezed_frown',[6616]);link('squeezed_frown_b',[6830]);link('waking',[5778])
link('puff',[6547,4503]);link('angry',[6360]);link('puff_open',[5600]);link('puff_teary',[7819,5731]);link('surprise',[8207,7708]);link('look_down',[4610,6779]);link('happy',[6604,6403,7808,6503,6533,7470,8633,8153])
link('pin_cry',[8002,7469]);link('pin_cry_b',[5373,5485]);link('tongue_cry',[5545,5904]);link('tongue_cry_b',[8643,7234]);link('dead_tongue',[6860,4682])
link('blank_cry',[5717,5334,5542,5021]);link('blank_cry_b',[5040,5006,6264]);link('squeezed_cry',[4683,8154,4830,7100]);link('squeezed_cry_tears',[8332,7078]);link('teary_pant',[5011]);link('teary_pant_b',[7240]);link('dull',[7788,5294,6463,7114,6048,5400])
link('flushed_smile',[5469,6320,5936]);link('flushed_tongue',[5757,7964,5394]);link('teary_tongue',[4738,4907,8284]);link('teary_tongue_b',[5905,6358,5546]);link('endure',[5404,5661]);link('horrified',[6602,7345]);link('horrified_b',[6567,6247]);link('side_teary',[7789,4421,5710]);link('side_teary_b',[5030,5100,8420]);link('down_angry',[6397,7360]);link('dry_dead',[7055]);link('squashed',[6407,5578])
link('teary_frown',[4910,5279]);link('teary_frown_b',[7189,8168]);link('open_drool',[5937,5508]);link('open_drool_b',[4745,5026]);link('landing',[6863,6377,5970,8666,8087,4643]);link('open_cry',[7294,5765]);link('open_cry_b',[7246]);link('pursed',[4893]);link('chewing',[7193,4462]);link('chewing_closed',[4474,5353]);link('bad_taste',[5503]);link('bad_taste_small',[8580]);link('bad_taste_closed',[6069,7173]);link('angry_stare',[6939,5620]);link('blank_happy',[7359]);link('cold',[8343]);link('cold_b',[8091]);link('cut_cry',[7117]);link('pressed_teary',[6399,6868]);link('pressed_cry',[7846,5453]);link('licking',[4383,7333]);link('dying',[6328,7222,7378]);link('dying_b',[7219,8255,5562]);link('old_dead',[5151,5018,6149]);link('dying_blank',[7782]);link('dying_closed',[4424,5649,6831]);link('dying_closed_b',[5412,6158,8480]);link('pressed_hidden',[5805]);link('satisfied',[7890,6038,4748,7480]);link('blank_frown',[6477]);link('impact',[8180,4484,5019,6298,5959]);link('impact_teary',[6335,7175]);link('shattered',[7266]);link('shattered_fade',[5253]);link('flick_dull',[4675])
OVERLAYS={7555:'wet_highlight',6549:'side_tears',5514:'closed_wet_highlight'}
rows=read(REF/'face-fields.json');unique=read(REF/'unique-faces.json');unmapped=[x for x in unique if x['id'] not in MAP and x['id'] not in OVERLAYS];assert not unmapped,unmapped
# Preserve the accepted fear face pixel-for-pixel. Other recipes are original pixel adaptations.
assets={};presets={};partnames=list(geom)
def asset(im):
 payload=bytes(im.size)+im.tobytes();key=hashlib.sha256(payload).hexdigest()[:20];ref=f'expressions/parts/{key}.png';p=R/ref;p.parent.mkdir(parents=True,exist_ok=True)
 if not p.exists():im.save(p)
 assets[key]=dict(path=ref,size=list(im.size),sha256=hashlib.sha256(im.tobytes()).hexdigest());return ref
for id,p in PRESETS.items():
 rec=p['recipe'];images={}
 for side,suffix in enumerate(['left','right']):
  images['face_eye_'+suffix]=eyes(p.get('eye_'+suffix,rec['eyes']),side)
  images['face_brow_'+suffix]=brows(rec['brows'],side)
  images['face_blush_'+suffix]=blush(rec['blush'])
  images['face_tear_'+suffix]=tears(rec['tears'],side)
 images['face_mouth']=mouth(rec['mouth']);images['face_effect_mouth']=effect(rec['effect'])
 if id=='neutral':images={n:seed(n) for n in partnames}
 if id=='surprise':images={n:seed(n,'surprise') for n in partnames}
 if 'puff-cheeks' in p.get('requires',[]):
  p['geometry_offsets']={'face_eye_left':{'position':[0,1.219,0]},'face_eye_right':{'position':[0,1.219,0]},'face_mouth':{'position':[0,-.053,0],'scale':[.75,.85,1]}}
 if id=='puff':
  accepted=read(OLD/'components/marisa/puff.json')['elements'];accepted_atlas=Image.open(OLD/'textures/marisa/neutral.png').convert('RGBA')
  for part,oldname in [('face_eye_left','puff_eye_left'),('face_eye_right','puff_eye_right'),('face_mouth','puff_mouth')]:
   e=next(e for e in accepted if e['name']==oldname);images[part]=accepted_atlas.crop(tuple(map(int,e['faces']['north']['uv'])))
 if p.get('mask'):
  for part,im in images.items():
   d=ImageDraw.Draw(im)
   for y in range(im.height):
    for x in range(im.width):
     if ((x*7+y*11)%17)<(9 if p['mask']=='fracture' else 13):d.point((x,y),fill=(0,0,0,0))
 p['parts']={n:asset(im) for n,im in images.items()};p['sources']=[dict(field=x['field'],index=0,sprite_id=x['refs'][0]['id'],file_id=2,scope='expression; perspective handled by 3D camera') for x in rows if x['refs'] and MAP.get(x['refs'][0]['id'])==id]
 p['status']='implemented';p['art']='original pixel adaptation; accepted neutral/fear retained';p['aliases']=[]
 save(R/f'expressions/presets/{id}.json',p);presets[id]=f'expressions/presets/{id}.json'
 # Small composed face thumbnail, retaining standardized geometric placement.
 canvas=Image.new('RGBA',(128,100),'#e5d0a7')
 for name in ['face_brow_left','face_brow_right','face_eye_left','face_eye_right','face_mouth','face_blush_left','face_blush_right','face_tear_left','face_tear_right','face_effect_mouth']:
  e=copy.deepcopy(geom[name]);delta=p.get('geometry_offsets',{}).get(name,{})
  if 'blush' in name and p.get('cheek_scale'):delta={'scale':[1,p['cheek_scale'],1]}
  center=[(a+b)/2 for a,b in zip(e['from'],e['to'])]
  for key in ['from','to']:e[key]=[center[j]+(v-center[j])*delta.get('scale',[1,1,1])[j]+delta.get('position',[0,0,0])[j] for j,v in enumerate(e[key])]
  x,y,z=e['from'];X,Y,Z=e['to'];im=images[name].resize((max(1,round((X-x)*8)),max(1,round((Y-y)*8))),Image.Resampling.NEAREST);left=round(64-X*8);top=round(82-Y*8);canvas.alpha_composite(im,(left,top))
 (R/'expressions/thumbnails').mkdir(exist_ok=True);canvas.convert('RGB').save(R/f'expressions/thumbnails/{id}.png')
# Overlay-only source sprites are reusable layers, not fake full expressions.
effects={'wet_highlight':dict(label='眼部湿润高光',parts={'face_tear_left':asset(tears('shimmer')),'face_tear_right':asset(tears('shimmer',1))}),'side_tears':dict(label='横向眼泪',parts={'face_tear_left':asset(tears('side')),'face_tear_right':asset(tears('side',1))}),'closed_wet_highlight':dict(label='闭眼湿润高光',parts={'face_tear_left':asset(tears('wet')),'face_tear_right':asset(tears('wet',1))})}
for id,v in effects.items():save(R/f'expressions/effects/{id}.json',dict(id=id,kind='overlay',**v))
coverage=[]
for row in rows:
 refs=row['refs'];sid=refs[0]['id'] if refs else 0
 coverage.append(dict(field=row['field'],section=row['section'],array_index=0,sprite_id=sid,all_slots=refs,status='source-empty' if sid==0 else 'implemented-overlay' if sid in OVERLAYS else 'implemented-recipe',target=None if sid==0 else ('expressions/effects/'+OVERLAYS[sid]+'.json') if sid in OVERLAYS else presets[MAP[sid]]))
# All formerly exposed IDs stay discoverable aliases.
aliases={'sleepy':'waking','sleepy_eye':'waking','crying':'cry','smile':'happy','fear':'surprise','puff_eye_a':'puff'}
save(R/'expressions/index.json',dict(schema_version=2,presets=presets,effects={k:f'expressions/effects/{k}.json' for k in effects},aliases=aliases,parts='expressions/parts/index.json',source_coverage='provenance/expression-coverage.json',default='neutral',rules=['One shared preset for all characters.','Identical pixel parts stored once by content hash.','Direction and action variants map to existing recipes; A/B tear phases use shared eye/mouth parts.','No raw Hakkei U pixels in this library.']))
save(R/'expressions/parts/index.json',assets)
save(R/'provenance/expression-coverage.json',dict(version='Hakkei U v8.3 installed build',source_assets='E:/yukkuri/eightSight.v8.3/ゆっくり八景U_Data/sharedassets0.assets',assembly='E:/yukkuri/eightSight.v8.3/ゆっくり八景U_Data/Managed/Assembly-CSharp.dll',assembly_sha256=hashlib.sha256(Path('E:/yukkuri/eightSight.v8.3/ゆっくり八景U_Data/Managed/Assembly-CSharp.dll').read_bytes()).hexdigest(),scene='level0',monobehaviour_id=76216,parsed_fields=1352,parsed_bytes=224788,serialized_bytes=224788,external_file_id=2,external_file='sharedassets0.assets',reference_array_index=0,scope='All face/eye/expression-effect fields in this installed build; age/direction resource slots are provenance, not separate 3D character types.',field_count=len(rows),nonempty_fields=sum(bool(x['sprite_id']) for x in coverage),unique_source_sprites=len(unique),viewed_contact_sheets=8,rows=coverage))
print('Expression recipes:',len(presets),'unique pixel parts:',len(assets),'source fields:',len(coverage),'all mapped')

# Visual duplicates become aliases; they must not become a second editable source.
seen={};canonical={};visual_aliases={}
for id,ref in list(presets.items()):
 p=read(R/ref);signature=json.dumps({'parts':p['parts'],'requires':p.get('requires',[]),'cheek_scale':p.get('cheek_scale',1)},sort_keys=True)
 if signature not in seen:seen[signature]=id;canonical[id]=ref;continue
 target=seen[signature];visual_aliases[id]=target;keep=read(R/canonical[target]);keep['sources']+=p['sources'];keep['aliases'].append({'id':id,'label':p['label']});save(R/canonical[target],keep)
 for f in [R/ref,R/f'expressions/thumbnails/{id}.png']:
  assert f.resolve().is_relative_to((R/'expressions').resolve());f.unlink()
for row in coverage:
 if row['target']:
  oldid=Path(row['target']).stem
  if oldid in visual_aliases:row['target']=canonical[visual_aliases[oldid]]
index=read(R/'expressions/index.json');index['presets']=canonical;index['aliases'].update(visual_aliases)
for id,target in list(index['aliases'].items()):
 while target in visual_aliases:target=visual_aliases[target]
 index['aliases'][id]=target
save(R/'expressions/index.json',index)
cov=read(R/'provenance/expression-coverage.json');cov['rows']=coverage;save(R/'provenance/expression-coverage.json',cov)
print('Deduplicated visual recipes:',len(canonical),'aliases:',len(visual_aliases))
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',13);items=list(canonical)
for page in range((len(items)+23)//24):
 sheet=Image.new('RGB',(960,640),'#eee5d4');d=ImageDraw.Draw(sheet)
 for i,id in enumerate(items[page*24:(page+1)*24]):
  x=i%6*160;y=i//6*160;im=Image.open(R/f'expressions/thumbnails/{id}.png');sheet.paste(im,(x+16,y+4));d.text((x+6,y+108),PRESETS[id]['label'],font=font,fill='#453b2f');d.text((x+6,y+132),id,font=font,fill='#736650')
 sheet.save(ROOT/f'reference/hakkei-u-expression-audit/adapted-{page+1}.png')

# The same wet highlight layer applies to open or closed eyes.
index=read(R/'expressions/index.json');index['effect_aliases']={'closed_wet_highlight':'wet_highlight'};index['effects'].pop('closed_wet_highlight',None);save(R/'expressions/index.json',index)
f=R/'expressions/effects/closed_wet_highlight.json'
if f.exists():
 assert f.resolve().is_relative_to((R/'expressions').resolve());f.unlink()
cov=read(R/'provenance/expression-coverage.json')
for row in cov['rows']:
 if row['target']=='expressions/effects/closed_wet_highlight.json':row['target']='expressions/effects/wet_highlight.json';row['alias']='closed_wet_highlight'
save(R/'provenance/expression-coverage.json',cov)
