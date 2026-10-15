"""Original single-portion care consumables. Parametric source, editable meshes and runtime export."""
import argparse,base64,io,json,runpy,sys,uuid
from pathlib import Path
from PIL import Image
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts'))
from asset_export_checks import check_output,fingerprints,preserve_equivalent_png,preserve_negative_zero_mesh_json,provenance
BASE=HERE.parent/'operation-tools/build.py';primitive=runpy.run_path(str(BASE))
box,rod,ball=(primitive[k] for k in ('box','rod','ball'))
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri';DATA=ROOT/'src/main/resources/data/mcyukkuri';SELECTED=ROOT/'art/props/consumables'
PALETTE=['#f2edda','#427da3','#85c5ca','#da9c50','#952f3d','#cba6ce','#735576','#333c46','#e1c484','#ef8b74','#728f49','#fdf8ef']
def mesh_number(value):
 value=round(value,6)
 return 0.0 if isinstance(value,float) and value==0 else value
def bottle(radius,height,body,cap):
 return [rod([0,0,0],[0,.4,0],radius*.84,body,radius),rod([0,.4,0],[0,height-1.6,0],radius,body),rod([0,height-1.6,0],[0,height-.7,0],radius,body,radius*.55),rod([0,height-.7,0],[0,height,0],radius*.55,cap)]
def label(radius,y,color):return box([-radius*.7,y,-radius-.02],[radius*.7,y+2,-radius+.15],color)
RECIPES={
 'yogurt_drink':bottle(1.6,6,0,1)+[label(1.6,1.6,1),ball([0,2.6,-1.67],[.55,.75,.09],11)],
 'stimulant':bottle(1.3,5,3,4)+[label(1.3,1.2,0),rod([-.35,1.5,-1.35],[.25,2.25,-1.35],.15,4),rod([.25,2.25,-1.35],[-.2,2.25,-1.35],.15,4),rod([-.2,2.25,-1.35],[.35,3,-1.35],.15,4)],
 'sitataran':bottle(1.2,4.8,5,6)+[label(1.2,1.2,11),box([-.6,1.8,-1.29],[.6,2.8,-1.18],6),rod([-.45,1.45,-1.31],[-.25,1.9,-1.31],.13,6)]+[ball([x,2.3,-1.32],[.09,.09,.05],11) for x in [-.35,0,.35]],
 'sleep_spray':bottle(1.5,5.5,2,7)+[label(1.5,1.4,1),rod([0,5.5,0],[0,6.2,0],.3,7),box([-.55,6,-1.9],[.55,6.7,.8],0),rod([0,6.35,-1.95],[0,6.35,-2.1],.25,7),box([-.23,5.4,-1.2],[.23,6.1,-.85],7),ball([0,2.45,-1.57],[.55,.6,.1],0),ball([.28,2.67,-1.69],[.46,.46,.1],1)],
 'spicy_grain':[box([-2,0,-1.2],[2,4.8,1.2],4),rod([-2,4.8,0],[2,4.8,0],.32,9),box([-1.45,.7,-1.24],[1.45,3.8,-1.17],0),rod([-.45,1.1,-1.3],[.35,2.6,-1.3],.02,4,.45),rod([.35,2.6,-1.3],[.1,3.05,-1.3],.45,4,.2),rod([.1,3.05,-1.3],[.4,3.5,-1.3],.1,10)],
 'reproductive_filling':[box([-1.5,0,-.6],[1.5,.35,.6],1),rod([0,.35,0],[0,3.7,0],1.25,0,.9),rod([0,3.7,0],[0,4.5,0],.9,0,.35),rod([0,4.5,0],[0,5.8,0],.35,1,.15),box([-.75,1.2,-1.26],[.75,2.9,-1.2],1),ball([0,2.25,-1.29],[.35,.45,.08],11)],
 'water_serving':[ball([0,0,0],[.6,.35,.5],2)],
 'yogurt_serving':[ball([0,0,0],[.6,.35,.5],0)],
 'stimulant_serving':[rod([-.55,0,0],[0,0,0],.25,4),rod([0,0,0],[.55,0,0],.25,0),ball([-.55,0,0],[.25,.25,.25],4),ball([.55,0,0],[.25,.25,.25],0)],
 'sitataran_serving':[ball([0,0,0],[.6,.25,.4],5)],
}
LABELS={'yogurt_drink':['酸奶饮品','Yogurt drink'],'stimulant':['兴奋药','Stimulant'],'sitataran':['幼语药（シタタラン）','Sitataran baby-talk medicine'],'sleep_spray':['助眠喷雾','Sleep spray'],'spicy_grain':['超辣油粮','Extra-spicy yukkuri feed'],'reproductive_filling':['生殖用馅','Reproductive filling']}
INGREDIENTS={'yogurt_drink':['minecraft:milk_bucket','minecraft:sugar','minecraft:glass_bottle'],'stimulant':['minecraft:sugar','minecraft:redstone','minecraft:glass_bottle'],
 'sitataran':['minecraft:paper','minecraft:amethyst_shard','minecraft:glass_bottle'],'sleep_spray':['minecraft:poppy','minecraft:brown_mushroom','minecraft:glass_bottle'],'spicy_grain':['mcyukkuri:oil_grain_regular','minecraft:blaze_powder'],
 'reproductive_filling':['minecraft:sugar','minecraft:wheat','minecraft:milk_bucket','minecraft:paper']}
def outputs():
 previous=provenance(SELECTED/'manifest.json');result={};index={};image=Image.new('RGBA',(16,16))
 for x in range(16):
  for y in range(16):image.putpixel((x,y),tuple(bytes.fromhex(PALETTE[min(x,len(PALETTE)-1)][1:]))+(255,))
 stream=io.BytesIO();image.save(stream,format='PNG');texture=preserve_equivalent_png(HERE/'assembled'/next(iter(RECIPES))/'texture.png',stream.getvalue())
 def emit(path,data):result[path]=(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode()
 def emit_mesh(path,data):result[path]=preserve_negative_zero_mesh_json(path,(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode())
 emit(HERE/'recipe.json',{'status':'pending-review','units_per_block':16,'props':RECIPES,'palette':PALETTE,'recipes':INGREDIENTS})
 for name,shapes in RECIPES.items():
  vertices=[];parts=[];elements=[]
  for number,shape in enumerate(shapes):
   points,faces=primitive['geometry'](shape);start=len(vertices)//8;bbfaces={};u=(shape['color']+.5)/16
   for face in faces:
    for j in range(1,len(face)-1):
     tri=[face[0],face[j],face[j+1]];normal=primitive['unit'](primitive['cross'](primitive['sub'](points[tri[1]],points[tri[0]]),primitive['sub'](points[tri[2]],points[tri[0]])))
     if sum(x*x for x in normal)<.5:continue
     for i in tri:vertices.extend([*points[i],u,.5,*normal])
     bbfaces[str(len(bbfaces))]={'vertices':[str(i) for i in tri],'uv':{str(i):[u*16,8] for i in tri},'texture':0}
   parts.append({'name':f'part_{number}','start':start,'count':len(vertices)//8-start})
   elements.append({'name':f'part_{number}','type':'mesh','uuid':str(uuid.uuid5(uuid.NAMESPACE_URL,name+str(number))),'origin':[0,0,0],'rotation':[0,0,0],'vertices':{str(i):p for i,p in enumerate(points)},'faces':bbfaces})
  extent=[[min(vertices[i::8]),max(vertices[i::8])] for i in range(3)]
  mesh={'format':1,'units_per_block':16,'vertices':[mesh_number(v) for v in vertices],'parts':parts,'bounds':extent}
  bb={'meta':{'format_version':'4.10','model_format':'free'},'name':name,'resolution':{'width':16,'height':16},'elements':elements,'outliner':[x['uuid'] for x in elements],'textures':[{'name':'texture','source':'data:image/png;base64,'+base64.b64encode(texture).decode(),'width':16,'height':16,'uv_width':16,'uv_height':16,'id':'0'}]}
  for folder in [HERE/'assembled'/name,SELECTED/'selected'/name]:
   emit(folder/f'{name}.bbmodel',bb);emit_mesh(folder/f'{name}.mesh.json',mesh);result[folder/'texture.png']=texture
  emit_mesh(ASSETS/f'yukkuri_props/{name}.json',mesh);result[ASSETS/f'textures/props/{name}.png']=texture
  if name in INGREDIENTS:
   emit(ASSETS/f'models/item/{name}.json',{'parent':'minecraft:builtin/entity','gui_light':'front','textures':{'particle':f'mcyukkuri:props/{name}'},'display':{'gui':{'rotation':[20,225,0]}}})
   emit(DATA/f'recipe/{name}.json',{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':x} for x in INGREDIENTS[name]],'result':{'id':'mcyukkuri:'+name,'count':1}})
  index[name]={'bounds':extent,'triangles':len(vertices)//24}
 emit(ASSETS/'yukkuri_props/consumables-index.json',index)
 for n,locale in enumerate(['zh_cn','en_us']):
  path=ASSETS/f'lang/{locale}.json';data=json.loads(path.read_text(encoding='utf8'));strings={'item.mcyukkuri.'+k:v[n] for k,v in LABELS.items()}
  strings.update({'activity.mcyukkuri.drink':['饮水','Drinking'][n],'activity.mcyukkuri.seek_water':['寻找饮水处','Seeking drinking water'][n],
   'notebook.mcyukkuri.consumables':['饮用与药效\n干燥程度：%s／100\n便秘：%s\n辣味不适：%s 秒\n喷雾起效剩余：%s 秒\n助眠剩余：%s 秒\n兴奋状态：%s 秒\n幼语药效：%s','Drinks and medicine\nDryness: %s/100\nConstipation: %s\nSpicy irritation: %s s\nSpray onset: %s s\nSleep remaining: %s s\nStimulation: %s s\nBaby talk: %s'][n],
   'death.attack.mcyukkuri.spicy_food':['%s 没能撑过超辣食物的不适','%s succumbed to extra-spicy food'][n]})
  if any(data.get(k)!=v for k,v in strings.items()):data.update(strings);result[path]=(json.dumps(data,ensure_ascii=False,indent=2)+'\n').encode()
 emit(SELECTED/'manifest.json',{'status':'pending-review-game-adaptation','source':HERE.relative_to(ROOT).as_posix()+'/build.py','inputs':fingerprints(ROOT,{p:p.read_bytes() for p in [Path(__file__).resolve(),BASE]},previous.get('inputs',{})),'files':fingerprints(ROOT,{p:v for p,v in result.items() if p.parent.name!='lang'},previous.get('files',{}))})
 return result
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');parser.add_argument('--skip-language',action='store_true');args=parser.parse_args();changed=[]
 for path,data in outputs().items():
  if args.skip_language and path.parent.name=='lang':continue
  if not check_output(path,data):
   changed.append(path.relative_to(ROOT).as_posix())
   if not args.check:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
 if args.check and changed:raise SystemExit('Stale exports: '+', '.join(changed))
 print(f'Consumables: {len(RECIPES)} models; {len(changed)} files '+('differ' if args.check else 'written'))
