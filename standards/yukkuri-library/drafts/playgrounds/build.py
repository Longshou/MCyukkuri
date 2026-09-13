"""Original three-dimensional playground candidates and matching physical geometry.

16 units = one block. No extracted game images. --check verifies every export.
"""
import argparse,base64,hashlib,io,json,math,runpy,uuid
from pathlib import Path
from PIL import Image

HERE=Path(__file__).resolve().parent; ROOT=HERE.parents[3]
BASE=HERE.parent/'operation-tools/build.py'
primitive=runpy.run_path(str(BASE))
box,rod=(primitive[k] for k in ('box','rod'))
SNAPSHOT=ROOT/'art/props/playgrounds';ASSETS=ROOT/'src/main/resources/assets/mcyukkuri';DATA=ROOT/'src/main/resources/data/mcyukkuri'
PALETTE=['#cba471','#8c6740','#ede4cf','#c2744f','#9b4931','#73a2a7','#456d7d','#d8e4e2','#393c49','#a2a9a9','#e2ba60','#92bbb4']
RECIPES={};COLLISION={}

def prism(corners,z0,z1,color):
    points=[[x,y,z] for z in (z0,z1) for x,y in corners]
    return {'poly':[points,[[0,3,2,1],[4,5,6,7],[0,1,5,4],[1,2,6,5],[2,3,7,6],[3,0,4,7]]],'color':color}

def bounds(points):return [min(p[i] for p in points) for i in range(3)]+[max(p[i] for p in points) for i in range(3)]

for name,color in (('paper_tunnel',0),('ceramic_tunnel',3)):
    shapes=[];physical=[]
    # Eight open-sided panels, with a flat floor and a broad clear bore.
    outer=[(-9,0),(9,0),(14,5),(14,23),(9,28),(-9,28),(-14,23),(-14,5)]
    inner=[(-8,2),(8,2),(12,6),(12,22),(8,26),(-8,26),(-12,22),(-12,6)]
    for i in range(8):
        j=(i+1)%8;shape=prism([outer[i],outer[j],inner[j],inner[i]],-22,22,color)
        shapes.append(shape);physical.append(bounds(shape['poly'][0]))
    if name=='paper_tunnel':
        shapes += [box([-9,28,-22],[9,28.15,22],1),box([-14.1,5,-22],[-13.9,23,-20.5],2)]
    else:
        for z in (-22,20.5):
            for x in (-14.4,13.8):shapes.append(box([x,5,z],[x+.6,23,z+1.5],4))
    RECIPES[name]=shapes;COLLISION[name]=physical

slide=[];solid=[]
def add_box(shapes,physical,a,b,c):shapes.append(box(a,b,c));physical.append(a+b)
# Back steps lead to a 1-block deck. The ramp has a continuous visual face;
# the exported collision stair is sampled at one model unit (1/16 block).
for z,h in ((-36,4),(-28,8),(-20,12),(-12,16)):
    add_box(slide,solid,[-9,0,z],[9,h,z+8],5)
add_box(slide,solid,[-9,0,-4],[9,16,2],5)
ramp={'poly':[[[-9,16,2],[9,16,2],[9,1,36],[-9,1,36],[-9,15,2],[9,15,2],[9,0,36],[-9,0,36]],
              [[0,3,2,1],[4,5,6,7],[0,1,5,4],[1,2,6,5],[2,3,7,6],[3,0,4,7]]],'color':7}
slide.append(ramp)
for z in range(2,36):solid.append([-9,max(0,15-(z-1)*15/34),z,9,16-(z-2)*15/34,z+1])
for x in (-9.2,9.2):
    slide.append(rod([x,19,1],[x,4,36],.65,6))
    slide.append(rod([x,21,-6],[x,19,1],.65,6))
    for z in (2,18,35):
        slide.append(rod([x,16-(z-2)*15/34-.2,z],[x,19-(z-1)*15/35,z],.45,6))
    for z in (-32,-20,-6):slide.append(rod([x,0,z],[x,9+(z+32)*12/26,z],.6,6))
    slide.append(rod([x,9,-32],[x,21,-6],.6,6))
RECIPES['yukkuri_slide']=slide;COLLISION['yukkuri_slide']=solid

trampoline=[];solid=[]
add_box(trampoline,solid,[-17,4,-17],[17,5,17],8)
for x in (-19,17):add_box(trampoline,solid,[x,3,-19],[x+2,5.5,19],10)
for z in (-19,17):add_box(trampoline,solid,[-17,3,z],[17,5.5,z+2],10)
for x in (-16,16):
    for z in (-16,16):add_box(trampoline,solid,[x-1,0,z-1],[x+1,4,z+1],9)
for n in range(-14,15,4):
    for side in (-1,1):
        trampoline += [rod([n,4.8,side*16],[n,4.8,side*18],.13,9),rod([side*16,4.8,n],[side*18,4.8,n],.13,9)]
RECIPES['yukkuri_trampoline']=trampoline;COLLISION['yukkuri_trampoline']=solid

pool=[];solid=[]
add_box(pool,solid,[-11,0,-11],[11,1,11],11)
for x in (-11,8):add_box(pool,solid,[x,1,-11],[x+3,6,11],11)
for z in (-11,8):add_box(pool,solid,[-8,1,z],[8,6,z+3],11)
for x in (-23,11):add_box(pool,solid,[x,0,-11],[x+12,6,11],7)
RECIPES['yukkuri_pool']=pool;COLLISION['yukkuri_pool']=solid
LABELS={'paper_tunnel':['纸筒','Paper tunnel'],'ceramic_tunnel':['陶管','Ceramic tunnel'],'yukkuri_slide':['油库里滑梯','Yukkuri slide'],
        'yukkuri_trampoline':['油库里蹦床','Yukkuri trampoline'],'yukkuri_pool':['油库里水池','Yukkuri pool']}
INGREDIENTS={'paper_tunnel':['paper']*6,'ceramic_tunnel':['brick']*6,'yukkuri_slide':['iron_ingot']*4+['stick']*2,
             'yukkuri_trampoline':['string']*4+['slime_ball','iron_ingot'],'yukkuri_pool':['brick']*4+['cyan_dye']}

def outputs():
    result={};index={};img=Image.new('RGBA',(16,16))
    for x in range(16):
        color=PALETTE[min(x,len(PALETTE)-1)]
        for y in range(16):img.putpixel((x,y),tuple(bytes.fromhex(color[1:]))+(255,))
    stream=io.BytesIO();img.save(stream,format='PNG');texture=stream.getvalue()
    def emit(path,data):result[path]=(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode()
    emit(HERE/'recipe.json',{'status':'pending-review','units_per_block':16,'props':RECIPES,'collision':COLLISION,'palette':PALETTE})
    for name,shapes in RECIPES.items():
        vertices=[];parts=[];elements=[]
        for number,shape in enumerate(shapes):
            points,faces=shape['poly'] if 'poly' in shape else primitive['geometry'](shape)
            start=len(vertices)//8;bbfaces={};u=(shape['color']+.5)/16
            for face in faces:
                for j in range(1,len(face)-1):
                    tri=[face[0],face[j],face[j+1]]
                    normal=primitive['unit'](primitive['cross'](primitive['sub'](points[tri[1]],points[tri[0]]),primitive['sub'](points[tri[2]],points[tri[0]])))
                    if sum(v*v for v in normal)<.5:continue
                    for i in tri:vertices.extend([*points[i],u,.5,*normal])
                    bbfaces[str(len(bbfaces))]={'vertices':[str(i) for i in tri],'uv':{str(i):[u*16,8] for i in tri},'texture':0}
            parts.append({'name':f'part_{number}','start':start,'count':len(vertices)//8-start})
            elements.append({'name':f'part_{number}','type':'mesh','uuid':str(uuid.uuid5(uuid.NAMESPACE_URL,name+str(number))),'origin':[0,0,0],'rotation':[0,0,0],'vertices':{str(i):p for i,p in enumerate(points)},'faces':bbfaces})
        extent=[[min(vertices[i::8]),max(vertices[i::8])] for i in range(3)]
        mesh={'format':1,'units_per_block':16,'vertices':[round(v,6) for v in vertices],'parts':parts,'bounds':extent}
        bb={'meta':{'format_version':'4.10','model_format':'free'},'name':name,'resolution':{'width':16,'height':16},'elements':elements,'outliner':[x['uuid'] for x in elements],
            'textures':[{'name':'texture','source':'data:image/png;base64,'+base64.b64encode(texture).decode(),'width':16,'height':16,'uv_width':16,'uv_height':16,'id':'0'}]}
        for folder in (HERE/'assembled'/name,SNAPSHOT/'selected'/name):
            emit(folder/f'{name}.bbmodel',bb);emit(folder/f'{name}.mesh.json',mesh);result[folder/'texture.png']=texture
        emit(ASSETS/f'yukkuri_props/{name}.json',mesh);result[ASSETS/f'textures/props/{name}.png']=texture
        emit(ASSETS/f'models/item/{name}.json',{'parent':'minecraft:builtin/entity','gui_light':'front','textures':{'particle':f'mcyukkuri:props/{name}'},'display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]}}})
        emit(ASSETS/f'blockstates/{name}.json',{'variants':{'':{'model':f'mcyukkuri:block/{name}'}}})
        emit(ASSETS/f'models/block/{name}.json',{'textures':{'particle':f'mcyukkuri:props/{name}'},'elements':[]})
        emit(DATA/f'loot_table/blocks/{name}.json',{'type':'minecraft:block','pools':[]})
        emit(DATA/f'playgrounds/{name}.json',{'collision':COLLISION[name]})
        emit(DATA/f'recipe/{name}.json',{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':'minecraft:'+x} for x in INGREDIENTS[name]],'result':{'id':'mcyukkuri:'+name,'count':1}})
        index[name]={'bounds':extent,'triangles':len(vertices)//24}
    emit(ASSETS/'yukkuri_props/playgrounds-index.json',index)
    for i,locale in enumerate(('zh_cn','en_us')):
        path=ASSETS/f'lang/{locale}.json';existing=json.loads(path.read_text(encoding='utf8'))
        strings={'item.mcyukkuri.'+name:labels[i] for name,labels in LABELS.items()}
        strings.update({'block.mcyukkuri.'+name:labels[i] for name,labels in LABELS.items()})
        strings.update({'activity.mcyukkuri.'+key:values[i] for key,values in {'tunnel':['穿过管道','Passing through a tunnel'],'slide_climb':['登上滑梯','Climbing the slide'],
            'slide_down':['顺坡滑下','Sliding down'],'trampoline':['玩蹦床','Bouncing on a trampoline'],'pool_edge':['沿水池探看','Exploring the pool edge']}.items()})
        strings.update({'play.mcyukkuri.no_space':['需要平坦、空旷的 %s×%s×%s 空间','Needs a clear, supported %s×%s×%s space'][i],
                        'play.mcyukkuri.pool':['水池：%s／3 份水。右键使用水桶补水；入水仍会浸湿。','Pool: %s/3 water portions. Use a water bucket to fill; entering still wets the body.'][i]})
        changed=any(existing.get(k)!=v for k,v in strings.items());existing.update(strings)
        if changed:result[path]=(json.dumps(existing,ensure_ascii=False,indent=2)+'\n').encode()
    emit(SNAPSHOT/'manifest.json',{'status':'pending-review-game-adaptation','source':HERE.relative_to(ROOT).as_posix()+'/build.py',
        'inputs':{p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in (Path(__file__).resolve(),BASE)},
        'files':{p.relative_to(ROOT).as_posix():hashlib.sha256(v).hexdigest() for p,v in result.items() if p.parent.name!='lang'}})
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args();changed=[]
    for path,data in outputs().items():
        if not path.exists() or path.read_bytes()!=data:
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    if args.check and changed:raise SystemExit('Stale exports: '+', '.join(changed))
    print(f'Playgrounds: {len(RECIPES)} models; {len(changed)} files '+('differ' if args.check else 'written'))
