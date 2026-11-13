"""Original MC facility/toy candidates; parametric editable source, not approved standards.

Uses the adjacent tool package's stable mesh primitives. No original game pixels.
16 units = one block. --check verifies editable, selected and packaged exports.
"""
import argparse, base64, io, json, runpy, sys, uuid
from pathlib import Path
from PIL import Image

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts'))
from asset_export_checks import (check_output, fingerprints, preserve_equivalent_png,
                                 preserve_negative_zero_mesh_json, provenance)
BASE=HERE.parent/'operation-tools/build.py'
primitive=runpy.run_path(str(BASE))
box,rod,ball,geometry=(primitive[k] for k in ('box','rod','ball','geometry'))
sub,cross,unit=(primitive[k] for k in ('sub','cross','unit'))
sys.path.insert(0,str(HERE.parent/'care-props'))
import kit as care_kit
H=care_kit.hex_of
# Toy palette touched up to the shared care-prop ramps (art/props/palette.json); indices are unchanged.
PALETTE=[H('paper',2),H('anko',0),H('wood',1),H('wood',2),H('wood',3),H('paper',3),H('berry',2),H('chili',1),H('anko',0),H('gold',2),H('sky',2),H('sky',3),H('steel',0),H('mint',1),H('ceramic',2),H('paper',3)]
SNAPSHOT=ROOT/'art/props/care-expansion'
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
DATA=ROOT/'src/main/resources/data/mcyukkuri'

def mesh_number(value):
    value=round(value,6)
    return 0.0 if isinstance(value,float) and value==0 else value

RECIPES={
 'glass_marble':[rod([-.8,.6,-.2],[.8,3.4,.2],.25,7),ball([0,2,0],[2,2,2],10)],
 'reimu_doll':[ball([0,2.7,0],[3,2.7,2.5],14),ball([0,4.2,.2],[3,.9,2.4],8),box([-3.4,4.3,-.6],[-.5,5.8,.6],7),box([.5,4.3,-.6],[3.4,5.8,.6],7),box([-.6,4.6,-.7],[.6,5.5,.7],5),ball([-1,2.7,-2.3],[.25,.25,.15],1),ball([1,2.7,-2.3],[.25,.25,.15],1)],
 'marisa_doll':[ball([0,2.7,0],[3,2.7,2.5],14),ball([0,4,.2],[3,.9,2.4],9),ball([0,4.7,0],[3.4,.4,2.8],8),rod([0,4.7,0],[.6,8,0],2.2,8,end=0),box([-1.5,5,-1.9],[1.5,5.7,-1.4],5),ball([-1,2.7,-2.3],[.25,.25,.15],1),ball([1,2.7,-2.3],[.25,.25,.15],1)],
 'pretty_stone':[ball([0,1.5,0],[3,1.5,2.4],12)],
}
INGREDIENTS={'glass_marble':['glass','red_dye'],
 'reimu_doll':['white_wool','black_wool','red_dye'],'marisa_doll':['white_wool','black_wool','yellow_dye'],
 'pretty_stone':['cobblestone','amethyst_shard']}
LABELS={'glass_marble':['玻璃弹珠','Glass marble'],'reimu_doll':['灵梦布偶','Reimu doll'],'marisa_doll':['魔理沙布偶','Marisa doll'],
 'pretty_stone':['漂亮石子','Pretty stone']}

def outputs():
    previous=provenance(SNAPSHOT/'manifest.json')
    image=Image.new('RGBA',(16,16))
    for x,color in enumerate(PALETTE):
        for y in range(16):image.putpixel((x,y),tuple(bytes.fromhex(color[1:]))+(200 if x==11 else 90 if x==10 else 255,))
    stream=io.BytesIO();image.save(stream,format='PNG');texture=preserve_equivalent_png(HERE/'assembled'/next(iter(RECIPES))/'texture.png',stream.getvalue())
    result={};index={}
    def emit(path,data):result[path]=(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode()
    def emit_mesh(path,data):result[path]=preserve_negative_zero_mesh_json(path,(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode())
    emit(HERE/'recipe.json',{'status':'pending-review','units_per_block':16,'props':RECIPES,'palette':PALETTE,'recipes':INGREDIENTS,'shared_palette':'art/props/palette.json'})
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
        bounds=[[min(vertices[i::8]),max(vertices[i::8])] for i in range(3)] if vertices else None
        mesh={'format':1,'units_per_block':16,'vertices':[mesh_number(v) for v in vertices],'parts':parts,'bounds':bounds}
        model_texture=texture
        bb={'meta':{'format_version':'4.10','model_format':'free'},'name':name,'resolution':{'width':16,'height':16},'elements':elements,'outliner':[x['uuid'] for x in elements],
            'textures':[{'name':'texture','source':'data:image/png;base64,'+base64.b64encode(texture).decode(),'width':16,'height':16,'uv_width':16,'uv_height':16,'id':'0'}]}
        for folder in (HERE/'assembled'/name,SNAPSHOT/'selected'/name):
            emit(folder/f'{name}.bbmodel',bb);emit_mesh(folder/f'{name}.mesh.json',mesh);result[folder/'texture.png']=model_texture
        emit_mesh(ASSETS/f'yukkuri_props/{name}.json',mesh);result[ASSETS/f'textures/props/{name}.png']=model_texture
        emit(ASSETS/f'models/item/{name}.json',{'parent':'minecraft:builtin/entity','gui_light':'front','textures':{'particle':f'mcyukkuri:props/{name}'},'display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]},'ground':{'translation':[0,3,0],'scale':[.5,.5,.5]},'thirdperson_righthand':{'translation':[0,3,0],'scale':[.5,.5,.5]}}})
        emit(DATA/f'recipe/{name}.json',{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':'minecraft:'+x} for x in INGREDIENTS[name]],'result':{'id':'mcyukkuri:'+name,'count':1}})
        index[name]={'bounds':bounds,'triangles':len(vertices)//24}
    emit(ASSETS/'yukkuri_props/facilities-index.json',index)
    language={}
    for i,locale in enumerate(('zh_cn','en_us')):
        path=ASSETS/f'lang/{locale}.json';existing=json.loads(path.read_text(encoding='utf8'))
        strings={'item.mcyukkuri.'+name:labels[i] for name,labels in LABELS.items()}
        language[locale]=strings;changed=any(existing.get(k)!=v for k,v in strings.items());existing.update(strings)
        result[path]=(json.dumps(existing,ensure_ascii=False,indent=2)+'\n').encode() if changed else path.read_bytes()
    emit(SNAPSHOT/'manifest.json',{'status':'pending-review-game-adaptation','source':str(HERE.relative_to(ROOT)).replace('\\','/')+'/build.py','language_keys':language,'inputs':fingerprints(ROOT,{p:p.read_bytes() for p in (Path(__file__).resolve(),BASE)},previous.get('inputs',{})),'files':fingerprints(ROOT,{p:v for p,v in result.items() if p.parent.name!='lang'},previous.get('files',{}))})
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args();changed=[]
    for path,data in outputs().items():
        if not check_output(path,data):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    if args.check and changed:raise SystemExit('Stale exports: '+', '.join(changed))
    print(f'Care expansion: {len(RECIPES)} models; {len(changed)} files '+('differ' if args.check else 'written'))
