"""Original single-portion care consumables. Parametric source, editable meshes and runtime export.

Hand-held consumables and the spicy-grain pouch are their 16x16 inventory icons extruded into one-pixel-thick plates
(block-props/kit.py extrude), and the oral *_serving doses are stepped voxel shapes built with block-props/kit.py and
icon ramps from item-icons/draw.py. food_mash_serving remains authored by care-props/small.py."""
import argparse,importlib.util,io,json,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts'))
from asset_export_checks import check_output,fingerprints,preserve_equivalent_png,preserve_negative_zero_mesh_json,provenance
sys.path.insert(0,str(HERE.parent/'care-props'))
import small as care_small   # shared-palette kit design: the food mash dollop
KIT={'food_mash_serving':care_small.food_mash_serving}
if 'block_props_kit' not in sys.modules:   # loaded under its own name: care-props also has a `kit`
 _spec=importlib.util.spec_from_file_location('block_props_kit',HERE.parent/'block-props/kit.py')
 sys.modules['block_props_kit']=_module=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(_module)
block_kit=sys.modules['block_props_kit']
# Extruded icons (block-props/kit.py extrude): the 16x16 inventory icon as a one-pixel-thick plate of this height in units.
ICONS=ROOT/'art/props/item-icons'
EXTRUDED={'mold_remover':6.3,'yogurt_drink':6.0,'stimulant':5.0,'sitataran':4.8,'sleep_spray':6.7,'spicy_grain':11.5,'reproductive_filling':5.8,'wheat_flour':5.7}
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri';DATA=ROOT/'src/main/resources/data/mcyukkuri';SELECTED=ROOT/'art/props/consumables'
def mesh_number(value):
 value=round(value,6)
 return 0.0 if isinstance(value,float) and value==0 else value
def scaled_block(block,cell_size):
 """Export a block-props voxel model scaled down to mouth-serving dimensions (cell_size units per voxel)."""
 mesh,bb,png=block.export()
 verts=mesh['vertices']
 for i in range(0,len(verts),8):
  for k in range(3):verts[i+k]=mesh_number(verts[i+k]*cell_size)
 mesh['bounds']=[[mesh_number(min(verts[k::8])),mesh_number(max(verts[k::8]))] for k in range(3)]
 for elem in bb['elements']:
  for key,pos in elem['vertices'].items():elem['vertices'][key]=[mesh_number(c*cell_size) for c in pos]
 return mesh,bb,png
def _pill_capsule():
 b=block_kit.Block('stimulant_serving',offset=(-5,-2,-2))
 red=block_kit.Material('RED',step=2,top=1,bottom=-1);paper=block_kit.Material('PAPER',step=3,top=1,bottom=-1)
 part=b.part('capsule')
 for x in range(10):
  mat=red if x<5 else paper
  for y in range(4):
   for z in range(4):
    if x in (0,9) and not (1<=y<=2 and 1<=z<=2):continue
    if (y in (0,3)) and (z in (0,3)):continue
    part.cell((x,y,z),mat)
 return scaled_block(b,0.15)
def _lozenge():
 b=block_kit.Block('sitataran_serving',offset=(-4,-1.5,-3))
 lilac=block_kit.Material('LILAC',step=3,top=1,bottom=-1,pattern=lambda cell,face:('PINK',2) if cell[1]==2 and face=='up' and 3<=cell[0]<=4 and 2<=cell[2]<=3 else 0)
 part=b.part('lozenge')
 for x in range(8):
  for z in range(6):
   if not (x in (0,7) and z in (0,5)):part.cell((x,1,z),lilac)
   if 1<=x<=6 and 1<=z<=4:
    part.cell((x,0,z),lilac);part.cell((x,2,z),lilac)
 return scaled_block(b,0.15)
def _droplet(name,ramp_name,step,glint_step):
 b=block_kit.Block(name,offset=(-4,-2,-3))
 mat=block_kit.Material(ramp_name,step=step,top=1,bottom=-1,pattern=lambda cell,face:(ramp_name,glint_step) if cell==(3,3,2) and face=='up' else 0)
 part=b.part('droplet')
 for x in range(8):
  for z in range(6):
   if not (x in (0,7) and z in (0,5)):part.cell((x,1,z),mat)
   if 1<=x<=6 and 1<=z<=4:
    part.cell((x,0,z),mat)
    if not (x in (1,6) and z in (1,4)):part.cell((x,2,z),mat)
   if 3<=x<=4 and 2<=z<=3:part.cell((x,3,z),mat)
 return scaled_block(b,0.15)
SERVINGS={
 'water_serving':lambda:_droplet('water_serving','WATER',2,4),
 'yogurt_serving':lambda:_droplet('yogurt_serving','YOGURT',3,4),
 'stimulant_serving':_pill_capsule,
 'sitataran_serving':_lozenge,
}
LABELS={'yogurt_drink':['酸奶饮品','Yogurt drink'],'stimulant':['兴奋药','Stimulant'],'sitataran':['幼语药（シタタラン）','Sitataran baby-talk medicine'],'sleep_spray':['弹珠汽水喷雾','Ramune spray'],'spicy_grain':['超辣油粮','Extra-spicy yukkuri feed'],'reproductive_filling':['生殖用馅','Reproductive filling'],'wheat_flour':['小麦粉','Wheat flour'],'flour_paste':['小麦粉糊','Wheat flour paste']}
INGREDIENTS={'yogurt_drink':['minecraft:milk_bucket','minecraft:sugar','minecraft:glass_bottle'],'stimulant':['minecraft:sugar','minecraft:redstone','minecraft:glass_bottle'],
 'sitataran':['minecraft:paper','minecraft:amethyst_shard','minecraft:glass_bottle'],'sleep_spray':['minecraft:poppy','minecraft:brown_mushroom','minecraft:glass_bottle'],'spicy_grain':['mcyukkuri:oil_grain_regular','minecraft:blaze_powder'],
 'reproductive_filling':['minecraft:sugar','minecraft:wheat','minecraft:milk_bucket','minecraft:paper'],
 'wheat_flour':['minecraft:wheat','minecraft:wheat','minecraft:wheat'],'flour_paste':['mcyukkuri:wheat_flour','mcyukkuri:wheat_flour','minecraft:sugar','minecraft:water_bucket']}
def outputs():
 previous=provenance(SELECTED/'manifest.json');result={};index={};icons={}
 def emit(path,data):result[path]=(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode()
 def emit_mesh(path,data):result[path]=preserve_negative_zero_mesh_json(path,(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode())
 emit(HERE/'recipe.json',{'status':'pending-review','units_per_block':16,'extruded_heights':EXTRUDED,'servings':list(SERVINGS),'recipes':INGREDIENTS,'kit_props':{k:'standards/yukkuri-library/drafts/care-props/'+v.__module__+'.py' for k,v in KIT.items()},'icon_source':'art/props/item-icons','ramps_source':'standards/yukkuri-library/drafts/item-icons/draw.py'})
 for name,height in EXTRUDED.items():
  icons[ICONS/f'{name}.png']=icon=(ICONS/f'{name}.png').read_bytes()
  mesh,bb,png=block_kit.extrude(name,icon,height)
  for folder in [HERE/'assembled'/name,SELECTED/'selected'/name]:
   emit(folder/f'{name}.bbmodel',bb);emit_mesh(folder/f'{name}.mesh.json',mesh);result[folder/'texture.png']=png
  emit_mesh(ASSETS/f'yukkuri_props/{name}.json',mesh);result[ASSETS/f'textures/props/{name}.png']=png
  emit(ASSETS/f'models/item/{name}.json',block_kit.prop_item_model(name,extruded=True))
  index[name]={'bounds':mesh['bounds'],'triangles':len(mesh['vertices'])//24}
 for name,builder in SERVINGS.items():
  mesh,bb,png=builder()
  model_texture=preserve_equivalent_png(HERE/'assembled'/name/'texture.png',png)
  for folder in [HERE/'assembled'/name,SELECTED/'selected'/name]:
   emit(folder/f'{name}.bbmodel',bb);emit_mesh(folder/f'{name}.mesh.json',mesh);result[folder/'texture.png']=model_texture
  emit_mesh(ASSETS/f'yukkuri_props/{name}.json',mesh);result[ASSETS/f'textures/props/{name}.png']=model_texture
  index[name]={'bounds':mesh['bounds'],'triangles':len(mesh['vertices'])//24}
 for name,kit_fn in KIT.items():
  kit_prop=kit_fn()
  mesh=kit_prop.mesh();bb=kit_prop.bbmodel();model_texture=preserve_equivalent_png(HERE/'assembled'/name/'texture.png',kit_prop.png())
  for folder in [HERE/'assembled'/name,SELECTED/'selected'/name]:
   emit(folder/f'{name}.bbmodel',bb);emit_mesh(folder/f'{name}.mesh.json',mesh);result[folder/'texture.png']=model_texture
  emit_mesh(ASSETS/f'yukkuri_props/{name}.json',mesh);result[ASSETS/f'textures/props/{name}.png']=model_texture
  index[name]={'bounds':mesh['bounds'],'triangles':len(mesh['vertices'])//24}
 # Crafting recipes (flour_paste uses its 16x16 item sprite from care-props/supplies.py, no prop mesh).
 for name in INGREDIENTS:
  emit(DATA/f'recipe/{name}.json',{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':x} for x in INGREDIENTS[name]],'result':{'id':'mcyukkuri:'+name,'count':1}})
 emit(ASSETS/'yukkuri_props/consumables-index.json',index)
 for n,locale in enumerate(['zh_cn','en_us']):
  path=ASSETS/f'lang/{locale}.json';data=json.loads(path.read_text(encoding='utf8'));strings={'item.mcyukkuri.'+k:v[n] for k,v in LABELS.items()}
  strings.update({'activity.mcyukkuri.drink':['饮水','Drinking'][n],'activity.mcyukkuri.seek_water':['寻找饮水处','Seeking drinking water'][n],
   'notebook.mcyukkuri.consumables':['饮用与药效\n干燥程度：%s／100\n便秘：%s\n辣味不适：%s 秒\n喷雾起效剩余：%s 秒\n困倦剩余：%s 秒\n兴奋状态：%s 秒\n幼语药效：%s','Drinks and medicine\nDryness: %s/100\nConstipation: %s\nSpicy irritation: %s s\nSpray onset: %s s\nSleep remaining: %s s\nStimulation: %s s\nBaby talk: %s'][n],
   'death.attack.mcyukkuri.spicy_food':['%s 没能撑过超辣食物的不适','%s succumbed to extra-spicy food'][n]})
  if any(data.get(k)!=v for k,v in strings.items()):data.update(strings);result[path]=(json.dumps(data,ensure_ascii=False,indent=2)+'\n').encode()
 emit(SELECTED/'manifest.json',{'status':'pending-review-game-adaptation','source':HERE.relative_to(ROOT).as_posix()+'/build.py','inputs':fingerprints(ROOT,{**{p:p.read_bytes() for p in [Path(__file__).resolve(),HERE.parent/'block-props/kit.py',HERE.parent/'item-icons/draw.py',HERE.parent/'care-props/small.py']},**icons},previous.get('inputs',{})),'files':fingerprints(ROOT,{p:v for p,v in result.items() if p.parent.name!='lang'},previous.get('files',{}))})
 return result
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');parser.add_argument('--skip-language',action='store_true');args=parser.parse_args();changed=[]
 for path,data in outputs().items():
  if args.skip_language and path.parent.name=='lang':continue
  if not check_output(path,data):
   changed.append(path.relative_to(ROOT).as_posix())
   if not args.check:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
 if args.check and changed:raise SystemExit('Stale exports: '+', '.join(changed))
 print(f'Consumables: {len(EXTRUDED)+len(SERVINGS)+len(KIT)} models; {len(changed)} files '+('differ' if args.check else 'written'))
