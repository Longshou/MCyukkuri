"""Hand-authored pixel expression parts, informed by the inspected Hakkei U layers."""
from PIL import Image,ImageDraw
import json,base64
PARTS={'eye_left':[6,9,21,21],'eye_right':[27,9,42,21],'brow_left':[5,4,22,10],'brow_right':[26,4,43,10],'mouth':[17,19,31,31],'blush_left':[3,20,13,26],'blush_right':[35,20,45,26],'tear_left':[6,15,21,31],'tear_right':[27,15,42,31],'effect_mouth':[18,21,32,32]}
PRESETS=[('neutral','基础微笑',6280),('happy','闭眼笑',6604),('smug','得意',4478),('angry','生气',6360),('puff','鼓嘴',6547),('surprise','吃惊',8207),('confused','困惑',6401),('teary','含泪',5577),('cry','大哭',6927),('sleepy','刚睡醒',5778),('sleep','睡眠',5335),('yawn','哈欠',8564),('drool','馋嘴',6449)]
INK='#48352e';BROW='#ba8e53';MOUTH='#805044';INNER='#d97a72'
def layers(kind):
 out={n:Image.new('RGBA',(48,32)) for n in PARTS}
 for side,x in [('left',9),('right',30)]:
  f=ImageDraw.Draw(out['eye_'+side]);mode=kind
  if kind=='sleepy':mode='sleep' if side=='left' else 'neutral'
  if mode in ('happy','yawn','drool'):
   f.line([(x,16),(x+1,14),(x+4,13),(x+7,14),(x+8,16)],fill=INK,width=2)
  elif mode=='sleep':f.line([(x,15),(x+2,17),(x+5,18),(x+8,16)],fill=INK,width=2)
  elif mode=='puff':
   x0=x+1; w=6
   points=[(x0,12),(x0+w,16),(x0,16)] if side=='left' else [(x0+w,12),(x0,16),(x0+w,16)]
   f.line(points,fill=INK,width=2)
  elif mode=='cry':
   points=[(x,12),(x+8,17),(x+1,17)] if side=='left' else [(x+8,12),(x,17),(x+7,17)]
   f.line(points,fill=INK,width=2)
  elif mode=='surprise':
   f.ellipse((x-1,10,x+9,21),fill=INK);f.ellipse((x+1,12,x+7,19),fill='#fff8e9')
  else:
   f.ellipse((x,11,x+8,20),fill=INK);f.ellipse((x+1,12,x+7,19),fill='#322923');f.line([(x+2,19),(x+5,19)],fill='#765239');f.ellipse((x+1,12,x+4,15),fill='#fff8e9');f.point((x+6,18),fill='#ead4ad')
  f=ImageDraw.Draw(out['brow_'+side]);color=BROW
  if kind in ('smug','angry'):points=[(x,6),(x+8,9)] if side=='left' else [(x,9),(x+8,6)];color=INK
  elif kind in ('confused','teary','cry','puff','surprise'):points=[(x,8),(x+4,9),(x+8,6)] if side=='left' else [(x,6),(x+4,9),(x+8,8)]
  else:points=[(x+1,8),(x+4,7),(x+7,8)]
  f.line(points,fill=color,width=1)
  f=ImageDraw.Draw(out['blush_'+side]);bx=5 if side=='left' else 36
  f.ellipse((bx,21,bx+6,23),fill='#f4bda4' if kind not in ('happy','smug') else '#edada6')
  f=ImageDraw.Draw(out['tear_'+side])
  if kind in ('teary','cry','yawn'):
   f.ellipse((x-1,18,x+8,21),fill='#a9c8e7');f.line([(x,19),(x+3,20),(x+7,19)],fill='#edf7ff',width=1)
   if kind=='cry':f.line([(x+2,21),(x+2,25),(x+3,27)],fill='#a9c8e7',width=2);f.point((x+2,24),fill='#edf7ff')
 f=ImageDraw.Draw(out['mouth'])
 if kind=='neutral':
  f.line([(21,21),(22,23),(25,23),(26,21)],fill=MOUTH);f.line([(22,22),(25,22)],fill=INNER)
 elif kind=='smug':f.line([(20,23),(22,24),(25,24),(28,22)],fill=MOUTH)
 elif kind in ('angry','teary'):f.line([(21,25),(24,22),(27,25)],fill=MOUTH,width=1)
 elif kind=='puff':f.line([(21,23),(23,21),(24,24),(25,21),(28,24)],fill=MOUTH)
 elif kind in ('surprise','cry'):
  f.polygon([(20,21),(22,20),(24,21),(27,20),(28,28),(25,26),(23,26),(20,28)],fill=MOUTH);f.polygon([(22,22),(26,22),(27,26),(24,25),(21,26)],fill=INNER)
 elif kind in ('happy','drool'):
  f.polygon([(19,21),(22,22),(26,22),(29,21),(28,26),(25,28),(22,27),(20,25)],fill=MOUTH);f.polygon([(21,23),(27,23),(27,25),(25,26),(23,26),(21,25)],fill=INNER)
 elif kind=='yawn':
  f.ellipse((20,20,28,29),fill=MOUTH);f.ellipse((22,23,26,28),fill=INNER)
 elif kind in ('sleep','sleepy'):
  f.ellipse((21,22,27,27),fill=MOUTH);f.ellipse((23,23,26,25),fill=INNER)
 elif kind=='confused':
  f.ellipse((21,22,27,27),fill=MOUTH);f.ellipse((22,23,26,26),fill=INNER)
 if kind in ('sleep','sleepy','drool'):
  f=ImageDraw.Draw(out['effect_mouth']);f.line([(27,25),(28,27),(27,30)],fill='#71999d',width=2);f.line([(27,26),(27,29)],fill='#d8f5f1')
 return out

def create(root,palette):
 root.joinpath('textures').mkdir(exist_ok=True);root.joinpath('parts').mkdir(exist_ok=True)
 partmeta={}
 for i,(name,box) in enumerate(PARTS.items()):
  w,h=box[2]-box[0],box[3]-box[1];x=i%4*32;y=i//4*16;partmeta[name]={'face_rect':box,'uv':[x,y,x+w,y+h]}
 manifest={'parts':partmeta,'presets':[],'default':'neutral'};neutral=None
 for key,label,sid in PRESETS:
  atlas=palette.convert('RGBA');atlas.paste((0,0,0,0),(0,0,128,48));parts=layers(key);root.joinpath('parts',key).mkdir(exist_ok=True)
  face=Image.new('RGBA',(48,32))
  for name,im in parts.items():
   face.alpha_composite(im);box=PARTS[name];tile=im.crop(box);tile.save(root/'parts'/key/(name+'.png'));u=partmeta[name]['uv'];atlas.paste(tile,(u[0],u[1]))
  for j,n in enumerate(('eye_left','eye_right','mouth')):
   atlas.paste(layers('puff')[n].crop(PARTS[n]),(j*32,80))
  atlas.save(root/'textures'/(key+'.png'));(root/'textures'/(key+'.rgba')).write_bytes(atlas.tobytes());face.save(root/'parts'/key/'assembled.png')
  manifest['presets'].append(dict(id=key,label=label,referenceSprite=sid,texture='textures/'+key+'.png',source='data:image/png;base64,'+base64.b64encode((root/'textures'/(key+'.png')).read_bytes()).decode()))
  if key=='neutral':neutral=atlas
 (root/'expressions.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
 return neutral,partmeta
