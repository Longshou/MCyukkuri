"""Author both predator species from the local expression/rig library.

The old predator_remilia draft stays available as a historical design source.
This entry owns remilia/flandre geometry, palette, numeric clips and BBModel.
"""
from pathlib import Path
import base64
import copy
import hashlib
import io
import json
import math
import sys
import uuid
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
ROOT = LIB.parents[1]
sys.path[:0] = [str(LIB / 'ecology/house-yukkuri'), str(LIB)]
from mesh_geometry import vertices, poly_mesh
from species_geometry import body, ring_surface, ellipse, tube, prism, crystal


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def save(path, data, pretty=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2 if pretty else None,
                               separators=None if pretty else (',', ':'), allow_nan=False), encoding='utf-8')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def weights_for(element, group, names, geometry):
    result=[]
    for i in range(0,len(geometry),8):
        if element['name']=='predator_body_surface':
            q=min(31,max(0,geometry[i+1]/9.328*32-.5));lo=int(q);hi=min(31,lo+1)
            result.append([names[f'soft_head_slice_{lo}_bend'],names[f'soft_head_slice_{hi}_bend'],round(q-lo,7)])
        else:result.append([names[group],names[group],0])
    return result


def cute_fangs(atlas, mouth, config, expression):
    """Author clear ivory triangles in a dedicated high-resolution mouth tile."""
    u,v,U,V=map(int,mouth['faces']['north']['uv'])
    a,b,A,B=config['uv']
    patch=atlas.crop((u,v,U,V)).resize((A-a,B-b),Image.Resampling.NEAREST)
    draw=ImageDraw.Draw(patch)
    if expression in ('neutral','neutral__new'):
        # A small open smile gives the canines a readable dark mouth behind them.
        draw.rectangle((0,0,patch.width,patch.height),fill=(0,0,0,0))
        draw.polygon([(28,16),(36,20),(76,20),(84,16),(80,36),(70,45),(42,45),(32,36)],fill=(83,43,45,255))
        draw.ellipse((43,37,69,44),fill=(213,117,121,255))
    box=patch.getbbox()
    if not box or box[2]-box[0]<8:
        atlas.paste(patch,(a,b));return
    left,top,right,bottom=box
    for q in config['positions']:
        x=round(left+(right-left-1)*q)
        occupied=[y for y in range(top,bottom) if patch.getpixel((x,y))[3]>128]
        if not occupied:continue
        y=occupied[0]+1;w=config['width'];h=config['length'];tip=(x,y+h)
        draw.polygon([(x-w//2,y),(x+w//2,y),tip],fill=tuple(config['color']),outline=tuple(config['outline']),width=1)
        draw.polygon([(x-w//2+1,y+1),(x-w//2+3,y+1),(x,y+h-2)],fill=tuple(config['shadow']))
        draw.point(tip,fill=tuple(config['color']))
    atlas.paste(patch,(a,b))


def palette(spec):
    colors = {'skin': [244,219,174], 'hair': spec['hair'], 'hair_light': spec['hair_light'],
              'hair_shadow': spec['hair_shadow'], 'cap': spec['cap'], 'cap_light': spec['cap_light'],
              'ribbon': spec['ribbon'], 'ribbon_light': [218,74,98], 'wing': [63,42,71],
              'wing_light': [93,61,101], 'wing_bone': [121,86,121], 'wing_frame': [72,37,67],
              'crystal_red': [233,102,117], 'crystal_gold': [250,211,102],
              'crystal_green': [127,205,159], 'crystal_blue': [116,183,229]}
    coords={k:[132+(i%8)*8,132+(i//8)*8] for i,k in enumerate(colors)}
    return colors, coords


def cap(config, uv, ident):
    c=config['cap']; rx=c['radius_x'];rz=c['radius_z'];z=c['center_z'];y=c['base_y'];n=48
    out=[]
    out.append(ring_surface('predator_cap_ruffle',[
        ellipse(rx-.12,rz-.12,y-.12,n,cz=z,wave=.23),
        ellipse(rx+.38,rz+.38,y+.08,n,cz=z,wave=.32),
        ellipse(rx,rz,y+.35,n,cz=z,wave=.23)],n,uv['cap_light'],'hat'))
    rings=[ellipse(rx*s,rz*s,y+h,n,cz=z) for h,s in [(0.1,.91),(.55,.94),(1.45,.83),(2.1,.59),(2.35,.18)]]
    out.append(ring_surface('predator_cap_crown',rings,n,uv['cap'],'hat'))
    out.append(ring_surface('predator_cap_band',[ellipse(rx*.925,rz*.925,y+.37,n,cz=z),
        ellipse(rx*.913,rz*.913,y+.77,n,cz=z)],n,uv['ribbon'],'hat'))
    # Back bow on Remilia. Flandre's offset side bow follows the seen cover reference.
    bx,bz=(-4.1,-5.25) if ident=='flandre' else (0,5.75)
    for side in (-1,1):
        poly=[[bx,y+.95,bz],[bx+side*2.4,y+2.0,bz+.18],
              [bx+side*2.65,y+.25,bz+.18],[bx+side*.75,y+.4,bz]]
        out.append(prism(f'predator_cap_bow_{side}',poly,.5,uv['ribbon'],'hat'))
        out.append(prism(f'predator_cap_tail_{side}',[[bx+side*.3,y+.55,bz+.12],
            [bx+side*1.25,y+.5,bz+.12],[bx+side*1.75,y-1.05,bz+.35],
            [bx+side*.75,y-.7,bz+.35]],.24,uv['ribbon'],'hat'))
    out.append(tube('predator_cap_knot',[bx,y+.45,bz+.20],[bx,y+1.2,bz+.20],.52,uv['ribbon_light'],'hat'))
    return out


def hair(config, uv, ident):
    out=[]
    out.append(ring_surface('predator_hair_crown',[
        ellipse(6.45,5.28,7.7,32,cz=.30),ellipse(6.05,4.95,8.9,32,cz=.30),
        ellipse(4.4,3.7,9.65,32,cz=.30),ellipse(1.2,1.1,9.85,32,cz=.30)],32,uv['hair'],'hair'))
    # Sculpted bangs with pointed tips. They overlap the crown at their roots.
    for i,(x,w,tip) in enumerate([(-5.25,1.95,5.8),(-3.5,1.8,7.0),(-1.75,1.9,6.5),(.1,2.0,6.0),(2.0,1.8,6.75),(3.8,1.75,7.1),(5.25,1.6,5.85)]):
        poly=[[x-w/2,8.9,-4.55],[x+w/2,8.9,-4.55],[x+w*.37,tip+.58,-5.08],
              [x-.05,tip,-5.16],[x-w*.37,tip+.7,-5.10]]
        out.append(prism(f'predator_bang_{i}',poly,.40,uv['hair_light' if i%3==1 else 'hair'],'bangs'))
    # Locks taper and curl at the tips, exposing the lower cheek and avoiding a box-like back.
    for side in (-1,1):
        for i,z in enumerate([-2.35,.5,3.0]):
            cx=side*(6.05 if i==0 else 6.15)
            rings=[]
            for yy,xx,w,zz in [(8.2,cx,1.3,z),(5.8,cx+side*.3,1.30,z+.15),
                               (3.3,cx+side*.1,1.02,z+.1),(1.9,cx-side*.55,.42,z-.15)]:
                rings.append(ellipse(w,.95,yy,8,cx=xx,cz=zz))
            out.append(ring_surface(f'predator_side_lock_{side}_{i}',rings,8,uv['hair_light' if i==0 else 'hair'],
                                    'hand_left' if side<0 else 'hand_right'))
    # Continuous rear underlayer closes the gaps between individually animated locks.
    rear=[]
    for yy,rx,rz in [(8.4,6.3,5.4),(5.7,6.45,6.05),(3.0,5.65,5.75)]:
        rear.append([[x*rx,yy,.2+z*rz] for x,z in [(1,0),(.94,.60),(.70,.91),(.35,1),(-.35,1),(-.70,.91),(-.94,.60),(-1,0)]])
    out.append(ring_surface('predator_rear_underlayer',rear,8,uv['hair'],'rear_hair'))
    for i in range(7):
        x=(i-3)*1.75;z=5.5-.04*x*x
        rings=[ellipse(1.12,.64,8.2,8,cx=x,cz=z-.55),ellipse(1.1,.64,5.7,8,cx=x,cz=z),
               ellipse(.8,.45,3.5+(i%2)*.25,8,cx=x,cz=z+.18),ellipse(.25,.17,2.55+(i%2)*.5,8,cx=x+.18,cz=z-.05)]
        out.append(ring_surface(f'predator_rear_lock_{i}',rings,8,uv['hair_light' if i%3==1 else 'hair'],'rear_hair'))
    if ident=='flandre':
        rings=[ellipse(.85,.95,7.8,10,cx=-6.6,cz=1.5),ellipse(1.1,1.0,6.0,10,cx=-7.2,cz=1.8),
               ellipse(1.15,.85,3.8,10,cx=-7.55,cz=1.6),ellipse(.65,.55,1.7,10,cx=-7.6,cz=1.2),
               ellipse(.18,.2,.9,10,cx=-6.9,cz=.8)]
        out.append(ring_surface('predator_ponytail',rings,10,uv['hair_light'],'hand_left'))
        out.append(tube('predator_ponytail_tie',[-6.6,7.5,.75],[-6.6,7.5,2.25],.4,uv['ribbon'],'hand_left'))
    return out


def wings(config, spec, uv):
    root=config['wing']['root'];out=[];bones=[]
    for side,sign in [('left',-1),('right',1)]:
        bone='predator_wing_'+side
        pivot=[sign*root[0],root[1],root[2]]
        bones.append(dict(name=bone,parent='features_squash',pivot=pivot))
        def point(x,y,z=0):return [sign*(root[0]+x),root[1]+y,root[2]+z+x*.14]
        if spec['wing_type']=='bat':
            outline=[(0,0),(.9,1.25),(2.25,2.0),(4,1.5),(5.7,.65),(7,-1.25),
                     (5.8,-.75),(5.15,-1.12),(4.6,-2.5),(3.7,-1.65),(2.95,-1.65),
                     (2.25,-2.5),(1.45,-1.6),(.2,-1.1)]
            out.append(prism('predator_membrane_'+side,[point(x,y) for x,y in outline],.14,uv['wing'],bone))
            ridge=[(0,0),(.9,1.25),(2.25,2.0),(4,1.5),(5.7,.65),(7,-1.25)]
            for i,(a,b) in enumerate(zip(ridge,ridge[1:])):
                out.append(tube(f'predator_wing_ridge_{side}_{i}',point(*a,-.02),point(*b,-.02),.17,uv['wing_bone'],bone,taper=.10))
            for i,end in enumerate([(7,-1.25),(4.6,-2.5),(2.25,-2.5),(.2,-1.1)]):
                out.append(tube(f'predator_wing_finger_{side}_{i}',point(1.2,.9,-.10),point(*end,-.08),.13,uv['wing_light'],bone,taper=.055))
        else:
            ridge=[(0,0),(2.0,1.45),(4.7,.6),(7.15,-.25)]
            for i,(a,b) in enumerate(zip(ridge,ridge[1:])):
                out.append(tube(f'predator_wing_frame_{side}_{i}',point(*a),point(*b),.19,uv['wing_frame'],bone,taper=.10))
            for i,(x,y,color) in enumerate([(1.75,.65,'red'),(3.5,.55,'gold'),(5.15,-.08,'green'),(6.8,-.58,'blue')]):
                attach=point(x,y)
                crystal_bone=f'predator_crystal_{side}_{i}'
                bones.append(dict(name=crystal_bone,parent=bone,pivot=attach))
                out.append(tube(f'predator_crystal_link_{side}_{i}',attach,[attach[0],attach[1]-.5,attach[2]],.055,uv['wing_frame'],crystal_bone))
                out.append(crystal(f'predator_crystal_{side}_{i}',attach[0],attach[1]-1.3,attach[2],uv['crystal_'+color],crystal_bone))
    return out,bones


def rig_for(original, config, added_bones):
    rig=copy.deepcopy(original)
    # The predator and current rounded standard use the same body profile.
    # Reuse its complete locomotion/anatomy rig without the old cuboid rescaling.
    for clip in rig['clips'].values():
        for name,channels in clip['tracks'].items():
            for key,v in channels.get('position',{}).items():
                if name=='world_motion': channels['position'][key]=[0,v[1],0]
            if name=='world_motion':channels.pop('rotation',None)
    rig['bones'].extend(added_bones)
    # Resting wings stay tucked. One hinge carries each complete membrane, so it cannot tear between fingers.
    for clip in rig['clips'].values():
        for side,sign in [('left',-1),('right',1)]:
            clip['tracks']['predator_wing_'+side]={'rotation':{'0':[0,sign*config['wing']['fold_yaw'],-sign*config['wing']['fold_roll']]}}
    for name,duration,amp,tilt,loop in [('predator_fly',.6,28,-6,True),('predator_dive',.6,13,-18,True),
                                         ('predator_land',.8,36,10,False),('predator_bite',.8,9,-14,False)]:
        tracks={'body_motion':{'rotation':{'0':[tilt,0,0]}},'world_motion':{'position':{'0':[0,0,0]}}}
        for side,sign in [('left',-1),('right',1)]:
            keys={str(round(i*duration/8,5)):[0,sign*(12 if name!='predator_bite' else 30),
                 sign*amp*math.sin(i*math.tau/8)] for i in range(9)}
            if name=='predator_land':keys[str(duration)]=[0,sign*config['wing']['fold_yaw'],-sign*config['wing']['fold_roll']]
            tracks['predator_wing_'+side]={'rotation':keys}
        if name=='predator_bite':
            tracks['face_mouth']={'scale':{'0':[1,1,1],'.2':[1.15,1.25,1],'.4':[1,.9,1],'.8':[1,1,1]}}
        for b in added_bones:
            if 'crystal' in b['name']:
                tracks[b['name']]={'rotation':{'0':[0,0,-8],str(duration/2):[0,0,8],str(duration):[0,0,-8]}}
        rig['clips'][name]=dict(duration=duration,loop=loop,tracks=tracks)
    return rig


def export(ident, config):
    spec=config['variants'][ident];base=LIB/'body-standard/inputs/marisa'
    bb=read(base/'marisa.bbmodel');face_rig=read(base/'animation-rig.json')
    shared=LIB/'assembled/marisa'
    shared_bb=read(shared/'marisa.bbmodel');original=read(shared/'animation-rig.json')
    colors,uv=palette(spec)
    elements=[body(config['body_profile'],uv['skin'])]
    for element,group in zip(bb['elements'],face_rig['groups']):
        name=element['name']
        if not name.startswith('face_'):continue
        e=copy.deepcopy(element);e['bone']=group
        if name.startswith('face_'):
            for key in ['from','to','origin']:
                if key in e:e[key]=[e[key][0]*1.02,e[key][1]*1.08,-4.865-(.001 if key=='from' else 0)]
            e['rotation']=[0,0,0]
        else:
            for key in ['from','to','origin']:
                if key in e:e[key]=[e[key][0],e[key][1],e[key][2]*.70]
        elements.append(e)
    for element,group in zip(shared_bb['elements'],original['groups']):
        if element['name']!='rounded_rump_mark' and not group.startswith('anatomy_'):continue
        e=copy.deepcopy(element);e['bone']=group
        if e['name']=='rounded_rump_mark':
            # Dedicated atlas tile avoids the predator's cap/wing palette.
            for face in e['faces'].values():
                face['uv']={key:[v[0]+48,v[1]+48] for key,v in face['uv'].items()}
        elements.append(e)
    elements.extend(hair(config,uv,ident));elements.extend(cap(config,uv,ident))
    wing_elements,wing_bones=wings(config,spec,uv);elements.extend(wing_elements)
    rig=rig_for(original,config,wing_bones)
    rig['groups']=[e['bone'] for e in elements]
    rig['element_vertex_counts']=[len(vertices(e,256))//8 for e in elements]
    names={b['name']:i for i,b in enumerate(rig['bones'])}
    rig['vertex_weights']=[w for e in elements for w in weights_for(e,e['bone'],names,vertices(e,256))]
    out=HERE/'assembled'/ident;out.mkdir(parents=True,exist_ok=True)
    texture_records={}
    for source in sorted((base/'textures').glob('*.png')):
        atlas=Image.new('RGBA',(256,256));atlas.paste(Image.open(source).convert('RGBA'),(0,0))
        shared_atlas=Image.open(shared/'textures/neutral.png').convert('RGBA')
        atlas.paste(shared_atlas.crop((144,144,176,176)),(192,192))
        for rect in [(1,49,2,50),(111,111,112,112)]:atlas.paste(shared_atlas.crop(rect),rect[:2])
        draw=ImageDraw.Draw(atlas)
        for key,color in colors.items():
            x,y=uv[key];draw.rectangle((x-2,y-2,x+2,y+2),fill=tuple(color)+(255,))
        # Retain approved eye shapes/highlights; only recolor the species' iris pixels.
        for e in elements:
            if e['name'] not in ('face_eye_left','face_eye_right'):continue
            a,b,c,d=map(int,e['faces']['north']['uv'])
            for y in range(b,d):
                for x in range(a,c):
                    r,g,bl,alpha=atlas.getpixel((x,y))
                    if alpha and r>65 and r<210 and r>g*1.1 and g>bl*1.1:
                        ratio=r/150;atlas.putpixel((x,y),tuple(min(255,round(v*ratio)) for v in spec['eye'])+(alpha,))
        mouth=next(e for e in elements if e['name']=='face_mouth')
        cute_fangs(atlas,mouth,config['fangs'],source.stem)
        target=out/'textures'/source.name;target.parent.mkdir(exist_ok=True);atlas.save(target)
        texture_records[source.stem]=sha(target)
    # Bite is an authored expression over the existing angry eye/brow components.
    atlas=Image.open(out/'textures/angry.png').convert('RGBA');draw=ImageDraw.Draw(atlas)
    mouth=next(e for e in elements if e['name']=='face_mouth');u,v,U,V=map(int,mouth['faces']['north']['uv'])
    draw.rectangle((u,v,U-1,V-1),fill=(0,0,0,0))
    draw.ellipse((u+2,v+1,U-3,V-2),fill=(77,35,43,255))
    draw.ellipse((u+5,V-6,U-6,V-3),fill=(226,113,126,255))
    cute_fangs(atlas,mouth,config['fangs'],'biting')
    atlas.save(out/'textures/biting.png');texture_records['biting']=sha(out/'textures/biting.png')
    mouth['faces']['north']['uv']=config['fangs']['uv']
    png=(out/'textures/neutral.png').read_bytes();(out/(ident+'.png')).write_bytes(png)
    uid=lambda key:str(uuid.uuid5(uuid.NAMESPACE_URL,'mcyukkuri/predators/'+ident+'/'+key))
    nodes={b['name']:dict(name=b['name'],uuid=uid('bone/'+b['name']),origin=b['pivot'],children=[]) for b in rig['bones']}
    for e in elements:e['uuid']=uid(e['name']);nodes[e['bone']]['children'].append(e['uuid'])
    for b in rig['bones']:
        if b['parent']:nodes[b['parent']]['children'].append(nodes[b['name']])
    model=copy.deepcopy(bb);model.update(name=spec['label'],elements=elements,animations=[],
        resolution={'width':256,'height':256},outliner=[nodes[b['name']] for b in rig['bones'] if not b['parent']])
    model['meta']['model_format']='free'
    model['textures']=[{**bb['textures'][0],'name':ident+'.png','path':ident+'.png','source':'data:image/png;base64,'+base64.b64encode(png).decode(),
                        'width':256,'height':256,'uv_width':256,'uv_height':256}]
    exports={}
    for name,clip in rig['clips'].items():
        animators={};channels={}
        for bone,tracks in clip['tracks'].items():
            frames=[];channels[bone]={}
            for channel,keys in tracks.items():
                channels[bone][channel]={}
                for t,value in keys.items():
                    translated=[-value[0],-value[1],value[2]] if channel=='rotation' else [-value[0],value[1],value[2]] if channel=='position' else value
                    frames.append(dict(channel=channel,data_points=[dict(zip(['x','y','z'],map(str,translated)))],uuid=uid(name+bone+channel+t),time=float(t),interpolation='linear'))
                    channels[bone][channel][t]=translated
            animators[uid('bone/'+bone)]=dict(name=bone,type='bone',keyframes=frames)
        model['animations'].append(dict(uuid=uid('clip/'+name),name='animation.'+ident+'.'+name,loop='loop' if clip['loop'] else 'once',length=clip['duration'],animators=animators))
        exports['animation.'+ident+'.'+name]=dict(loop=clip['loop'],animation_length=clip['duration'],bones=channels)
    save(out/(ident+'.bbmodel'),model)
    save(out/'animation-rig.json',rig)
    save(out/(ident+'.animation.json'),dict(format_version='1.8.0',animations=exports))
    # One named geometry entry per element avoids overwriting polygon meshes on a shared bone.
    geo=[]
    for b in rig['bones']:
        item=dict(name=b['name'],pivot=b['pivot'],cubes=[])
        if b['parent']:item['parent']=b['parent']
        geo.append(item)
    for e in elements:
        item=dict(name=e['name']+'_geometry',parent=e['bone'],pivot=[0,0,0])
        if e.get('type')=='mesh':item['poly_mesh']=poly_mesh(e)
        else:item['cubes']=[dict(origin=e['from'],size=[e['to'][i]-e['from'][i] for i in range(3)],uv={k:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for k,f in e['faces'].items()})]
        geo.append(item)
    save(out/(ident+'.geo.json'),{'format_version':'1.12.0','minecraft:geometry':[{'description':dict(identifier='geometry.'+ident,texture_width=256,texture_height=256),'bones':geo}]})
    return dict(status='pending_confirmation',source='species.json + species_geometry.py + author_species.py',
        base_model_sha256=sha(base/'marisa.bbmodel'),base_rig_sha256=sha(base/'animation-rig.json'),
        shared_model='standards/yukkuri-library/assembled/marisa/marisa.bbmodel',shared_model_sha256=sha(shared/'marisa.bbmodel'),
        shared_rig='standards/yukkuri-library/assembled/marisa/animation-rig.json',shared_rig_sha256=sha(shared/'animation-rig.json'),
        geometry_identifier='geometry.'+ident,model_sha256=sha(out/(ident+'.bbmodel')),rig_sha256=sha(out/'animation-rig.json'),
        elements=len(elements),triangles=sum(rig['element_vertex_counts'])//3,bones=len(rig['bones']),textures=texture_records,
        body_width_blocks=.8,units_per_block=16)


def main():
    config=read(HERE/'species.json')
    config['body_profile']=read(LIB/'body-standard/profile.json')['body_profile']
    records={ident:export(ident,config) for ident in config['variants']}
    save(HERE/'species-manifest.json',dict(status='pending_confirmation',models=records,
        inputs={p.name:sha(p) for p in [HERE/'species.json',HERE/'species_geometry.py',Path(__file__)]}),True)
    for ident,record in records.items():print(ident,record['elements'],'elements',record['triangles'],'triangles')


if __name__=='__main__':main()
