"""Original low-poly tool geometry. No extracted game pixels are used.

Run from any directory; --check compares both editable and selected runtime exports.
Dimensions use 16 units per block. The recipe below is the editable parametric source.
"""
import argparse
import base64
import hashlib
import io
import json
import math
from pathlib import Path
import uuid
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
SNAPSHOT = ROOT / 'art/props/operation-tools'
PALETTE = ['#a46d39','#d9a85d','#353941','#818994','#d5dce0','#ee809b','#b74651','#f0e4c7','#614c37','#f38b35','#473126','#cbe9f3','#689cb5']

def box(a,b,c): return {'box':[a,b], 'color':c}
def rod(a,b,r,c,end=None): return {'rod':[a,b,r,r if end is None else end], 'color':c}
def ball(center,r,c): return {'ball':[center,r], 'color':c}
def ring(center,r,t,c): return {'ring':[center,r,t], 'color':c}
def hoop(center,r,t,c): return {'hoop':[center,r,t], 'color':c}
def band(a,b,c,width=.8): return {'band':[a,b,width,.14], 'color':c}

RECIPE = {
 'pin_tool':[rod([0,0,0],[0,7.4,0],.10,4),rod([0,-.8,0],[0,0,0],0,4,.10),ball([0,8,0],[.8,.8,.8],5)],
 'care_mallet':[rod([0,0,0],[0,8.5,0],.46,0),rod([-3,8.5,0],[3,8.5,0],1.8,2),rod([-3.1,8.5,0],[-2.6,8.5,0],1.82,3),rod([2.6,8.5,0],[3.1,8.5,0],1.82,3)],
 'care_awl':[rod([0,0,0],[0,5.5,0],.58,0),rod([0,5.2,0],[0,6.2,0],.6,3),rod([0,6.2,0],[0,11,0],.13,4,.015)],
 'heated_awl':[rod([0,0,0],[0,5.5,0],.58,0),rod([0,5.2,0],[0,6.2,0],.6,3),rod([0,6.2,0],[0,11,0],.13,9,.015)],
 'restraint_frame':[box([-7,0,-6],[7,.65,22],0),box([-6,.65,-5],[-5,1.2,21],1),box([5,.65,-5],[6,1.2,21],1),box([-6,6.6,-1],[6,7.1,0],8),box([-6,6.6,6],[6,7.1,7],8),box([-6,.65,-1],[-5.5,7.1,0],8),box([5.5,.65,-1],[6,7.1,0],8),box([-6,.65,6],[-5.5,7.1,7],8),box([5.5,.65,6],[6,7.1,7],8)],
 'wrap_sling':[box([-6,.1,-3.2],[6,.55,3.2],7),rod([-6,.4,0],[-3,10,0],.30,7),rod([6,.4,0],[3,10,0],.30,7),rod([-3,10,0],[0,12,0],.30,7),rod([3,10,0],[0,12,0],.30,7),ring([0,12.5,0],.6,.14,8)],
 'hanging_hook':[ring([0,9,0],.6,.15,3),rod([0,8.5,0],[0,2,0],.17,4),rod([0,2,0],[-.8,.4,0],.17,4),rod([-.8,.4,0],[-2,.4,0],.17,4),rod([-2,.4,0],[-2.8,1.5,0],.17,4),rod([-2.8,1.5,0],[-2.6,3.1,0],.17,4,0)],
 'hair_clippers':[box([-1.25,0,-.75],[1.25,6,.75],2),box([-.8,1,.75],[.8,4.4,.95],3),box([-1.65,6,-.7],[1.65,7,.7],4)]+[box([x,7,-.65],[x+.25,7.8,.65],4) for x in [-1.5,-1,-.5,0,.5,1]],
 'cutting_knife':[rod([-2.2,0,0],[-.4,4.8,0],.46,6),rod([2.2,0,0],[.4,4.8,0],.46,6),rod([-.4,4.8,0],[2.4,10,0],.3,4,.04),rod([.4,4.8,0],[-2.4,10,0],.3,4,.04),ball([0,4.8,0],[.5,.5,.65],3)],
 'trimmed_hair':[rod([-2,0,0],[1,2,.3],.22,10,.02),rod([-1,0,.4],[2,2,-.3],.22,10,.02),rod([-2,0,.6],[-.5,2.4,0],.22,10,.02)],
 'care_vibrator':[ball([0,2,0],[.8,2,.8],5),hoop([0,2,0],.8,.05,6)],
 'sterilizing_shears':[ring([-1.5,1,0],1,.25,3),ring([1.5,1,0],1,.25,3),rod([-1.2,2,0],[1.8,8,0],.23,4,.035),rod([1.2,2,0],[-1.8,8,0],.23,4,.035),ball([0,4.3,0],[.4,.4,.5],2)],
 'iron_enclosure':[box([-5,0,-5],[5,.4,5],3),box([-5,.4,-5],[-4.6,6,5],3),box([4.6,.4,-5],[5,6,5],3),box([-5,.4,4.6],[5,6,5],3),box([-5,.4,-5],[5,2,-4.6],3)]+[rod([x,2,-4.8],[x,6,-4.8],.10,4) for x in [-4,-2,0,2,4]]+[box([-5,6,-5],[5,6.4,5],3)],
 'stem_shears':[ring([-1.7,1,0],1,.28,8),ring([1.7,1,0],1,.28,8),rod([-1.4,2,0],[2.7,9,0],.34,4,.03),rod([1.4,2,0],[-2.7,9,0],.34,4,.03),ball([0,4.5,0],[.4,.4,.5],0)],
 'bottle_stem_shears':[ring([-1.7,1,0],1,.28,5),ring([1.7,1,0],1,.28,5),rod([-1.4,2,0],[2.7,9,0],.34,4,.03),rod([1.4,2,0],[-2.7,9,0],.34,4,.03),ball([0,4.5,0],[.4,.4,.5],0)],
 'brood_bottle':[hoop([0,.15,0],2.4,.13,4),hoop([0,4,0],2.1,.14,4),{**rod([0,.1,0],[0,3.5,0],2.4,11),'caps':'bottom'},{**rod([0,3.5,0],[0,4,0],2.4,11,2.1),'caps':'none'},rod([0,.18,0],[0,.75,0],2.27,12)],
}

def sub(a,b): return [a[i]-b[i] for i in range(3)]
def cross(a,b): return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
def unit(a):
    length=math.sqrt(sum(x*x for x in a))
    return [x/length for x in a] if length>1e-9 else [0,0,0]

def geometry(shape):
    points,faces=[],[]
    if 'hoop' in shape:
        center,r,t=shape['hoop'];points,faces=geometry({'ring':[[0,0,0],r,t],'color':shape['color']})
        return [[center[0]+p[0],center[1]+p[2],center[2]+p[1]] for p in points],faces
    if 'band' in shape:
        a,b,width,thickness=shape['band'];direction=unit(sub(b,a));normal=unit([direction[1],-direction[0],0])
        for point in (a,b):
            for side in (-1,1):
                for depth in (-1,1):points.append([point[0]+normal[0]*thickness*side/2,point[1]+normal[1]*thickness*side/2,point[2]+width*depth/2])
        faces=[[0,1,3,2],[4,6,7,5],[0,4,5,1],[2,3,7,6],[0,2,6,4],[1,5,7,3]]
    elif 'box' in shape:
        a,b=shape['box'];points=[[x,y,z] for x in (a[0],b[0]) for y in (a[1],b[1]) for z in (a[2],b[2])]
        faces=[[0,1,3,2],[4,6,7,5],[0,4,5,1],[2,3,7,6],[0,2,6,4],[1,5,7,3]]
    elif 'rod' in shape:
        a,b,r,s=shape['rod'];axis=unit(sub(b,a));u=unit(cross(axis,[1,0,0] if abs(axis[1])>.9 else [0,1,0]));v=cross(axis,u)
        for center,radius in ((a,r),(b,s)):
            for i in range(12):
                t=i*math.tau/12;points.append([center[j]+radius*(u[j]*math.cos(t)+v[j]*math.sin(t)) for j in range(3)])
        for i in range(12):
            k=(i+1)%12;faces.append([i,k,k+12,i+12])
        if shape.get('caps','both') in ('both','bottom'):faces.append(list(reversed(range(12))))
        if shape.get('caps','both') in ('both','top'):faces.append(list(range(12,24)))
    elif 'ball' in shape:
        center,r=shape['ball']
        for j in range(7):
            t=j*math.pi/6
            for i in range(12):
                p=i*math.tau/12;points.append([center[0]+r[0]*math.sin(t)*math.cos(p),center[1]+r[1]*math.cos(t),center[2]+r[2]*math.sin(t)*math.sin(p)])
        for j in range(6):
            for i in range(12):
                k=(i+1)%12;faces.append([j*12+i,(j+1)*12+i,(j+1)*12+k,j*12+k])
    elif 'ring' in shape:
        center,r,t=shape['ring']
        for j in range(16):
            a=j*math.tau/16
            for i in range(6):
                b=i*math.tau/6;points.append([center[0]+(r+t*math.cos(b))*math.cos(a),center[1]+(r+t*math.cos(b))*math.sin(a),center[2]+t*math.sin(b)])
        for j in range(16):
            for i in range(6):faces.append([j*6+i,((j+1)%16)*6+i,((j+1)%16)*6+(i+1)%6,j*6+(i+1)%6])
    return points,faces

def hull(points):
    points=sorted(set((round(x,5),round(y,5)) for x,y in points))
    def turn(a,b,c):return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
    low=[];high=[]
    for sequence,out in ((points,low),(list(reversed(points)),high)):
        for point in sequence:
            while len(out)>1 and turn(out[-2],out[-1],point)<=0:out.pop()
            out.append(point)
    return low[:-1]+high[:-1]

def fitted(bare=False):
    recipes={};sockets={};sources={};lifts={}
    presentations=json.loads((ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))['variants']
    presentations.update(json.loads((ASSETS/'yukkuri_presentation/subspecies.json').read_text(encoding='utf-8')))
    for path in sorted((ASSETS/'yukkuri').glob('*.json')):
        data=json.loads(path.read_text(encoding='utf-8'))
        if not all(k in data for k in ('id','vertices','weights','bones','clips')):continue
        name=data['id'];v=data['vertices'];triangles=[];body=[]
        presentation=presentations[name]
        prefixes=tuple(presentation['accessory_prefixes'])
        if bare:name+='_bare'
        for part in data['parts']:
            if part['anatomy'] or 'puff' in part['name']:continue
            if bare and part['name'].startswith(prefixes):continue
            for first in range(part['start'],part['start']+part['count'],3):
                tri=[v[(first+i)*8:(first+i)*8+3] for i in range(3)];triangles.append(tri)
                if part['name'].startswith('soft_head_slice_') or part['name'] in ('house_body_surface','predator_body_surface','rounded_body_surface','deficient_body_surface'):body.extend(tri)
        if not body:raise ValueError(f'{name}: no recognized body surface for operation fitting')
        points=[p for tri in triangles for p in tri];top=max(p[1] for p in points)
        def section(tris,axis,value):
            cut=[]
            for tri in tris:
                for i in range(3):
                    a,b=tri[i],tri[(i+1)%3]
                    if abs(a[axis]-b[axis])<1e-7:continue
                    t=(value-a[axis])/(b[axis]-a[axis])
                    if 0<=t<=1:cut.append([a[j]+t*(b[j]-a[j]) for j in range(3)])
            return cut
        # Fitted bands follow convex cross-sections of the actual visible model, including headwear.
        clearance=.10;lift=max(p[2] for p in points)+.096+.72
        turned=[[[p[0],lift-p[2],p[1]] for p in tri] for tri in triangles]
        bounds=[[min(p[i] for p in points),max(p[i] for p in points)] for i in range(3)]
        shapes=[box([bounds[0][0]-.5,0,-.5],[bounds[0][1]+.5,.65,top+.5],0)]
        pad_top=min(lift-p[2] for p in body)
        body_top=max(p[1] for p in body);body_left=min(p[0] for p in body);body_right=max(p[0] for p in body)
        if pad_top>.8:shapes.append(box([body_left+.4,.65,.5],[body_right-.4,pad_top-.02,body_top-.4],7))
        for z in (body_top*.24,body_top*.72):
            outline=hull([(p[0],p[1]) for p in section(turned,2,z)]);cx=sum(p[0] for p in outline)/len(outline);cy=sum(p[1] for p in outline)/len(outline)
            outline=[[x+(clearance if x>=cx else -clearance),y+(clearance if y>=cy else -clearance),z] for x,y in outline]
            for i,a in enumerate(outline):shapes.append(band(a,outline[(i+1)%len(outline)],8))
            for x in (min(p[0] for p in outline),max(p[0] for p in outline)):
                shapes.append(band([x,.65,z],min(outline,key=lambda p:abs(p[0]-x)),8))
        recipes['restraint_frame_'+name]=shapes
        lifts['restraint_frame_'+name]=lift/16
        outline=hull([(p[0],p[1]) for p in section(triangles,2,0)]+[(0,top+2)])
        shapes=[]
        for i,a in enumerate(outline):
            b=outline[(i+1)%len(outline)];aa=[a[0]+(clearance if a[0]>=0 else -clearance),a[1]-.06 if a[1]<.1 else a[1]+.08,0];bb=[b[0]+(clearance if b[0]>=0 else -clearance),b[1]-.06 if b[1]<.1 else b[1]+.08,0]
            shapes.append(band(aa,bb,7,1.1))
        shapes += [box([body_left,-.12,-2],[body_right,-.02,2],7),ring([0,top+2.45,0],.45,.12,8)]
        recipes['wrap_sling_'+name]=shapes;sockets['wrap_sling_'+name]=[0,top+2.45,0]
        sources[path.relative_to(ROOT).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
        if not bare:
            lo=[min(p[i] for p in points)-.6 for i in range(3)];hi=[max(p[i] for p in points)+.6 for i in range(3)]
            left,right,front,back,top=lo[0],hi[0],lo[2],hi[2],hi[1]
            recipes['iron_enclosure_'+name]=[box([left,-.12,front],[right,0,back],3),box([left,0,front],[left+.18,top,back],3),box([right-.18,0,front],[right,top,back],3),box([left,0,back-.18],[right,top,back],3),box([left,top,front],[right,top+.18,back],3)]+[rod([left+(right-left)*i/6,0,front],[left+(right-left)*i/6,top,front],.07,4) for i in range(7)]
    return recipes,sockets,sources,lifts

def outputs():
    image=Image.new('RGBA',(16,16),'#ffffff')
    for x,color in enumerate(PALETTE):
        for y in range(16):image.putpixel((x,y),tuple(bytes.fromhex(color[1:]))+((50 if x==11 else 150 if x==12 else 255),))
    stream=io.BytesIO();image.save(stream,format='PNG');texture=stream.getvalue()
    result={};index={}
    def emit(path,data): result[path]=(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode()
    emit(HERE/'recipe.json',{'status':'pending-review','units_per_block':16,'palette':PALETTE,'props':RECIPE})
    fitting,sockets,sources,lifts=fitted()
    bare,bare_sockets,bare_sources,bare_lifts=fitted(True);fitting.update(bare);sockets.update(bare_sockets);sources.update(bare_sources);lifts.update(bare_lifts)
    for name,shapes in {**RECIPE,**fitting}.items():
        vertices,parts,elements=[],[],[]
        for part_id,shape in enumerate(shapes):
            points,faces=geometry(shape);start=len(vertices)//8;u=(shape['color']+.5)/16;bbfaces={}
            for face in faces:
                for k in range(1,len(face)-1):
                    tri=[face[0],face[k],face[k+1]];normal=unit(cross(sub(points[tri[1]],points[tri[0]]),sub(points[tri[2]],points[tri[0]])))
                    if sum(x*x for x in normal)<.5:continue
                    for i in tri:vertices.extend([*points[i],u,.5,*normal])
                    bbfaces[str(len(bbfaces))]={'vertices':[str(i) for i in tri],'uv':{str(i):[u*16,8] for i in tri},'texture':0}
            parts.append({'name':f'part_{part_id}','start':start,'count':len(vertices)//8-start})
            if shape['color'] in (11,12):parts[-1]['translucent']=True
            elements.append({'name':f'part_{part_id}','type':'mesh','uuid':str(uuid.uuid5(uuid.NAMESPACE_URL,name+str(part_id))), 'origin':[0,0,0], 'rotation':[0,0,0], 'vertices':{str(i):point for i,point in enumerate(points)},'faces':bbfaces})
        bounds=[[min(vertices[i::8]),max(vertices[i::8])] for i in range(3)]
        mesh={'format':1,'units_per_block':16,'vertices':[round(x,6) for x in vertices],'parts':parts,'bounds':bounds}
        if name in sockets:mesh['socket']=sockets[name]
        if name=='brood_bottle':mesh['socket']=[0,4.6,0];mesh['root_socket']=[0,.64,0]
        if name in lifts:mesh['body_lift']=lifts[name]
        bb={'meta':{'format_version':'4.10','model_format':'free'},'name':name,'resolution':{'width':16,'height':16},'elements':elements,'outliner':[x['uuid'] for x in elements], 'textures':[{'name':'texture','source':'data:image/png;base64,'+base64.b64encode(texture).decode(),'width':16,'height':16,'uv_width':16,'uv_height':16,'id':'0'}]}
        for folder in (HERE/'assembled'/name,SNAPSHOT/'selected'/name):
            emit(folder/f'{name}.bbmodel',bb);emit(folder/f'{name}.mesh.json',mesh);result[folder/'texture.png']=texture
        emit(ASSETS/f'yukkuri_props/{name}.json',mesh);result[ASSETS/f'textures/props/{name}.png']=texture
        emit(ASSETS/f'models/item/{name}.json',{'parent':'minecraft:builtin/entity','gui_light':'front','textures':{'particle':f'mcyukkuri:props/{name}'},'display':{'gui':{'rotation':[20,225,0],'scale':[.85,.85,.85]},'ground':{'translation':[0,3,0],'scale':[.5,.5,.5]},'firstperson_righthand':{'translation':[0,2,0],'scale':[.6,.6,.6]},'thirdperson_righthand':{'translation':[0,3,0],'scale':[.5,.5,.5]}}})
        index[name]={'bounds':bounds,'triangles':len(vertices)//24}
    emit(ASSETS/'yukkuri_props/operations-index.json',index)
    ingredients={'pin_tool':['iron_nugget','pink_dye'],'care_mallet':['iron_ingot','iron_ingot','stick'],
      'care_awl':['iron_nugget','iron_ingot','stick'],'restraint_frame':['oak_planks','oak_planks','string','leather'],
      'wrap_sling':['white_wool','string','string'],'hanging_hook':['iron_ingot','string'],
      'hair_clippers':['iron_ingot','iron_ingot','redstone'],'cutting_knife':['iron_ingot','iron_ingot','red_dye']}
    ingredients.update({'care_vibrator':['redstone','iron_ingot','pink_dye'],'sterilizing_shears':['shears','iron_nugget'],
      'iron_enclosure':['iron_ingot','iron_ingot','iron_ingot','iron_bars'],'stem_shears':['shears','stick'],'bottle_stem_shears':['shears','glass_bottle']})
    for name in ('detached_brood_stem','bottled_brood_stem'):
        emit(ASSETS/f'blockstates/{name}.json',{'variants':{'':{'model':f'mcyukkuri:block/{name}'}}})
        emit(ASSETS/f'models/block/{name}.json',{'textures':{'particle':'mcyukkuri:props/brood_bottle'},'elements':[]})
    for name,items in ingredients.items():
        emit(ROOT/f'src/main/resources/data/mcyukkuri/recipe/{name}.json',{'type':'minecraft:crafting_shapeless','category':'equipment','ingredients':[{'item':'minecraft:'+x} for x in items],'result':{'id':'mcyukkuri:'+name,'count':4 if name=='pin_tool' else 1}})
    emit(SNAPSHOT/'manifest.json',{'status':'pending-review-game-adaptation','source':'standards/yukkuri-library/drafts/operation-tools/build.py','source_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'body_sources':sources,'reference':'provenance.json','files':{p.relative_to(ROOT).as_posix():hashlib.sha256(data).hexdigest() for p,data in result.items()}})
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args();changed=[]
    for path,data in outputs().items():
        if not path.exists() or path.read_bytes()!=data:
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    if args.check and changed:raise SystemExit('Stale exports: '+', '.join(changed))
    print(f'Operation props: {len(RECIPE)}; '+('exports match' if args.check else f'{len(changed)} files written'))
