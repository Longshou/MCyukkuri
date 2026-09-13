"""Original MC facility/toy candidates; parametric editable source, not approved standards.

Uses the adjacent tool package's stable mesh primitives. No original game pixels.
16 units = one block. --check verifies editable, selected and packaged exports.
"""
import argparse, base64, hashlib, io, json, math, runpy, uuid, random
from pathlib import Path
from PIL import Image

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
BASE=HERE.parent/'operation-tools/build.py'
primitive=runpy.run_path(str(BASE))
box,rod,ball,geometry=(primitive[k] for k in ('box','rod','ball','geometry'))
sub,cross,unit=(primitive[k] for k in ('sub','cross','unit'))
PALETTE=['#ede2c6','#35322e','#7d5738','#ab7950','#bd925c','#f4f2e8','#e68b94','#c23449','#292629','#e2b960','#89bfd3','#aedfea','#656969','#497868','#e9dbad','#eee9e0']
SNAPSHOT=ROOT/'art/props/care-expansion'
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
DATA=ROOT/'src/main/resources/data/mcyukkuri'

RECIPES={
 'newspaper_mat':[box([-8,0,-8],[8,.12,8],0)]+[box([x,.12,z],[x+5,.135,z+.15],1) for x in (-6.8,.8) for z in range(-6,7,2)],
 'soil_mat':[box([-8,0,-8],[8,.6,8],2)]+[ball([x,.62,z],[.5,.22,.6],3) for x in (-6,-3,0,3,6) for z in (-6,-2,2,6)],
 'sandpaper_mat':[box([-8,0,-8],[8,.12,8],4)]+[ball([x,.14,z],[.18,.1,.18],1) for x in range(-7,8,2) for z in range(-7,8,2)],
 'ceramic_toilet':[box([-7.6,0,-7],[7.6,.8,7],5),box([-7.6,.8,-7],[-6.8,2.8,7],5),box([6.8,.8,-7],[7.6,2.8,7],5),box([-6.8,.8,6.2],[6.8,2.8,7],5),box([-6.8,.8,-7],[6.8,1.3,-6.2],5)],
 'covered_toilet':[box([-7.6,0,-7],[7.6,1.3,7],13),box([-7.6,1.3,-7],[-6.8,4,7],13),box([6.8,1.3,-7],[7.6,4,7],13),box([-6.8,1.3,6.2],[6.8,4,7],13),box([-6.8,1.3,-7],[6.8,1.8,-6.2],13),box([-7.6,4,3],[7.6,4.5,7],5)],
 'glass_marble':[rod([-.8,.6,-.2],[.8,3.4,.2],.25,7),ball([0,2,0],[2,2,2],10)],
 'rubber_ball':[ball([0,3,0],[3,3,3],6),primitive['hoop']([0,3,0],3,.10,5)],
 'reimu_doll':[ball([0,2.7,0],[3,2.7,2.5],14),ball([0,4.2,.2],[3,.9,2.4],8),box([-3.4,4.3,-.6],[-.5,5.8,.6],7),box([.5,4.3,-.6],[3.4,5.8,.6],7),box([-.6,4.6,-.7],[.6,5.5,.7],5),ball([-1,2.7,-2.3],[.25,.25,.15],1),ball([1,2.7,-2.3],[.25,.25,.15],1)],
 'marisa_doll':[ball([0,2.7,0],[3,2.7,2.5],14),ball([0,4,.2],[3,.9,2.4],9),ball([0,4.7,0],[3.4,.4,2.8],8),rod([0,4.7,0],[.6,8,0],2.2,8,end=0),box([-1.5,5,-1.9],[1.5,5.7,-1.4],5),ball([-1,2.7,-2.3],[.25,.25,.15],1),ball([1,2.7,-2.3],[.25,.25,.15],1)],
 'pretty_stone':[ball([0,1.5,0],[3,1.5,2.4],12)],
 'clear_dice':[],
}
# Opposite faces sum to seven; draw opaque pips before the transparent shell.
pips={1:[(0,0)],2:[(-1,-1),(1,1)],3:[(-1,-1),(0,0),(1,1)],
      4:[(-1,-1),(-1,1),(1,-1),(1,1)],5:[(-1,-1),(-1,1),(0,0),(1,-1),(1,1)],
      6:[(x,y) for x in (-1,1) for y in (-1,0,1)]}
for value,axis,side in ((1,2,-1),(6,2,1),(2,0,1),(5,0,-1),(3,1,1),(4,1,-1)):
    tangent=[i for i in range(3) if i!=axis]
    for a,b in pips[value]:
        center=[0,2,0];radius=[.27,.27,.27]
        center[axis]+=side*2.02;center[tangent[0]]+=a;center[tangent[1]]+=b;radius[axis]=.08
        RECIPES['clear_dice'].append(ball(center,radius,1))
RECIPES['clear_dice'].append(box([-2,0,-2],[2,4,2],11))
BLOCKS={'newspaper_mat','soil_mat','sandpaper_mat','ceramic_toilet','covered_toilet'}
scatter=random.Random(9073)
RECIPES['soil_mat']=[box([-8,0,-8],[8,.5,8],2)]+[ball([scatter.uniform(-7.2,7.2),.5,scatter.uniform(-7.2,7.2)],
    [scatter.uniform(.25,.8),scatter.uniform(.1,.35),scatter.uniform(.25,.8)],scatter.choice([2,2,3])) for _ in range(55)]
RECIPES['sandpaper_mat']=[box([-8,0,-8],[8,.12,8],4)]
for _ in range(240):
    x,z=scatter.uniform(-7.7,7.7),scatter.uniform(-7.7,7.7);r=scatter.uniform(.04,.11)
    RECIPES['sandpaper_mat'].append(box([x-r,.12,z-r],[x+r,.16,z+r],scatter.choice([1,2,3])))
INGREDIENTS={'newspaper_mat':['paper','paper'],'soil_mat':['dirt','dirt'],'sandpaper_mat':['paper','sand'],
 'ceramic_toilet':['brick','brick','brick','white_dye'],'covered_toilet':['iron_ingot','iron_ingot','green_dye','brick'],
 'glass_marble':['glass','red_dye'],'rubber_ball':['slime_ball','pink_dye'],
 'reimu_doll':['white_wool','black_wool','red_dye'],'marisa_doll':['white_wool','black_wool','yellow_dye'],
 'pretty_stone':['cobblestone','amethyst_shard'],'clear_dice':['glass','black_dye']}
LABELS={'newspaper_mat':['报纸垫料','Newspaper lining'],'soil_mat':['泥土垫料','Soil lining'],'sandpaper_mat':['砂纸垫料','Sandpaper lining'],
 'ceramic_toilet':['陶瓷油厕','Ceramic potty'],'covered_toilet':['带盖油厕','Covered potty'],'glass_marble':['玻璃弹珠','Glass marble'],
 'rubber_ball':['橡胶球','Rubber ball'],'reimu_doll':['灵梦布偶','Reimu doll'],'marisa_doll':['魔理沙布偶','Marisa doll'],
 'pretty_stone':['漂亮石子','Pretty stone'],'clear_dice':['透明骰子','Clear die']}

def outputs():
    image=Image.new('RGBA',(16,16))
    for x,color in enumerate(PALETTE):
        for y in range(16):image.putpixel((x,y),tuple(bytes.fromhex(color[1:]))+(200 if x==11 else 90 if x==10 else 255,))
    stream=io.BytesIO();image.save(stream,format='PNG');texture=stream.getvalue()
    result={};index={}
    def emit(path,data):result[path]=(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode()
    emit(HERE/'recipe.json',{'status':'pending-review','units_per_block':16,'props':RECIPES,'palette':PALETTE,'recipes':INGREDIENTS})
    for name,shapes in RECIPES.items():
        vertices=[];parts=[];elements=[]
        for number,shape in enumerate(shapes):
            points,faces=geometry(shape)
            if 'ball' in shape:faces=[list(reversed(face)) for face in faces]
            start=len(vertices)//8;bbfaces={};u=(shape['color']+.5)/16
            for face in faces:
                for j in range(1,len(face)-1):
                    tri=[face[0],face[j],face[j+1]];normal=unit(cross(sub(points[tri[1]],points[tri[0]]),sub(points[tri[2]],points[tri[0]])))
                    if sum(x*x for x in normal)<.5:continue
                    for i in tri:vertices.extend([*points[i],u,.5,*normal])
                    bbfaces[str(len(bbfaces))]={'vertices':[str(i) for i in tri],'uv':{str(i):[u*16,8] for i in tri},'texture':0}
            parts.append({'name':f'part_{number}','start':start,'count':len(vertices)//8-start,'translucent':shape['color'] in (10,11)})
            elements.append({'name':f'part_{number}','type':'mesh','uuid':str(uuid.uuid5(uuid.NAMESPACE_URL,name+str(number))),'origin':[0,0,0],'rotation':[0,0,0],'vertices':{str(i):p for i,p in enumerate(points)},'faces':bbfaces})
        bounds=[[min(vertices[i::8]),max(vertices[i::8])] for i in range(3)]
        mesh={'format':1,'units_per_block':16,'vertices':[round(v,6) for v in vertices],'parts':parts,'bounds':bounds}
        bb={'meta':{'format_version':'4.10','model_format':'free'},'name':name,'resolution':{'width':16,'height':16},'elements':elements,'outliner':[x['uuid'] for x in elements],
            'textures':[{'name':'texture','source':'data:image/png;base64,'+base64.b64encode(texture).decode(),'width':16,'height':16,'uv_width':16,'uv_height':16,'id':'0'}]}
        for folder in (HERE/'assembled'/name,SNAPSHOT/'selected'/name):
            emit(folder/f'{name}.bbmodel',bb);emit(folder/f'{name}.mesh.json',mesh);result[folder/'texture.png']=texture
        emit(ASSETS/f'yukkuri_props/{name}.json',mesh);result[ASSETS/f'textures/props/{name}.png']=texture
        emit(ASSETS/f'models/item/{name}.json',{'parent':'minecraft:builtin/entity','gui_light':'front','textures':{'particle':f'mcyukkuri:props/{name}'},'display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]},'ground':{'translation':[0,3,0],'scale':[.5,.5,.5]},'thirdperson_righthand':{'translation':[0,3,0],'scale':[.5,.5,.5]}}})
        if name in BLOCKS:
            emit(ASSETS/f'blockstates/{name}.json',{'variants':{'':{'model':f'mcyukkuri:block/{name}'}}})
            emit(ASSETS/f'models/block/{name}.json',{'textures':{'particle':f'mcyukkuri:props/{name}'},'elements':[]})
            emit(DATA/f'loot_table/blocks/{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'mcyukkuri:{name}'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
        emit(DATA/f'recipe/{name}.json',{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':'minecraft:'+x} for x in INGREDIENTS[name]],'result':{'id':'mcyukkuri:'+name,'count':1}})
        index[name]={'bounds':bounds,'triangles':len(vertices)//24}
    emit(ASSETS/'yukkuri_props/facilities-index.json',index)
    emit(DATA/'damage_type/terrain_abrasion.json',{'message_id':'terrain_abrasion','scaling':'never','exhaustion':0.1})
    language={}
    for i,locale in enumerate(('zh_cn','en_us')):
        path=ASSETS/f'lang/{locale}.json';existing=json.loads(path.read_text(encoding='utf8'))
        strings={('block' if name in BLOCKS else 'item')+'.mcyukkuri.'+name:labels[i] for name,labels in LABELS.items()}
        strings['death.attack.terrain_abrasion']=['%1$s 被粗糙垫料擦伤致死','%1$s was worn down by rough lining'][i]
        language[locale]=strings;changed=any(existing.get(k)!=v for k,v in strings.items());existing.update(strings)
        result[path]=(json.dumps(existing,ensure_ascii=False,indent=2)+'\n').encode() if changed else path.read_bytes()
    emit(SNAPSHOT/'manifest.json',{'status':'pending-review-game-adaptation','source':str(HERE.relative_to(ROOT)).replace('\\','/')+'/build.py','language_keys':language,'inputs':{p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in (Path(__file__).resolve(),BASE)},'files':{p.relative_to(ROOT).as_posix():hashlib.sha256(v).hexdigest() for p,v in result.items() if p.parent.name!='lang'}})
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args();changed=[]
    for path,data in outputs().items():
        if not path.exists() or path.read_bytes()!=data:
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    if args.check and changed:raise SystemExit('Stale exports: '+', '.join(changed))
    print(f'Care expansion: {len(RECIPES)} models; {len(changed)} files '+('differ' if args.check else 'written'))
