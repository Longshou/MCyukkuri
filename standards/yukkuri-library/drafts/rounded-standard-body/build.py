"""Build only this draft from the selected standard exports and predator body recipe."""
from pathlib import Path
import base64
import copy
import hashlib
import json
import math
import os
import sys
import uuid
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
ROOT = LIB.parents[1]
sys.path[:0] = [str(LIB/'subspecies/predator'), str(LIB/'ecology/house-yukkuri'), str(LIB)]
from species_geometry import body
from mesh_geometry import mesh, vertices, poly_mesh
from anatomy_library import compile_anatomy


def read(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))


def save(p, value, pretty=False):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2 if pretty else None,
                           separators=None if pretty else (',', ':'), allow_nan=False), encoding='utf-8')


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


CFG = read(HERE/'recipe.json')
PROFILE = read((HERE/CFG['body_source']).resolve())['body_profile']
SY = CFG['attachment_scale'][1]


def point(p):
    x, y, z = p
    sx, sy, sz = CFG['attachment_scale']
    return [x*sx, y*sy, z*(CFG['front_scale'] if z < 0 else sz)]


def profile_at(y):
    for a, b in zip(PROFILE, PROFILE[1:]):
        if y <= b[0]:
            t = min(1, max(0, (y-a[0])/(b[0]-a[0])))
            return [a[k]+(b[k]-a[k])*t for k in range(1, 4)]
    return PROFILE[-1][1:]


def face_mesh(e):
    # Subdivide in height: every opaque pixel lies just in front of the new skin,
    # including the rounded chin, and receives the same bend weights as the body.
    x0, y0, _ = e['from']; x1, y1, _ = e['to']
    sx, sy = CFG['face_scale']
    u, v, U, V = e['faces']['north']['uv']
    count = max(2, math.ceil((y1-y0)*sy/.28))
    pts, uv, tri = [], [], []
    for i in range(count+1):
        t = i/count; y = (y0+(y1-y0)*t)*sy
        z = -profile_at(y)[2]-CFG['face_clearance']
        pts.extend([[x0*sx, y, z], [x1*sx, y, z]])
        uv.extend([[U, V+(v-V)*t], [u, V+(v-V)*t]])
    for i in range(count):
        a = i*2
        tri.extend([(a,a+2,a+3), (a,a+3,a+1)])
    out = mesh(e['name'], pts, tri, [[uv[k] for k in t] for t in tri])
    out['bone'] = e['name']
    return out


def common_site():
    # The actuator pivot is inside the body. Match the actual surface exit, not
    # that internal pivot, so both visible parts emerge from the same place.
    cfg=CFG['anatomy'];root=[cfg['peni_offset'][0],1.15*SY+cfg['peni_offset'][1],-.88+cfg['peni_offset'][2]]
    x,y,z=[root[k]+cfg['peni_travel'][k] for k in range(3)]
    slope=math.tan(math.radians(cfg['peni_rotation'][0]));surface_y=y
    for _ in range(32):surface_y=y+slope*(profile_at(surface_y)[2]+z)
    return [x,surface_y,-profile_at(surface_y)[2]]


def convert_cube(e):
    out = copy.deepcopy(e)
    for key in ('from','to','origin'):
        if key in out: out[key] = point(out[key])
    return out


def continuous_binding(y, names):
    q = min(31, max(0, y/(8.48*SY)*32-.5))
    lo = int(q); hi = min(31, lo+1)
    return [names[f'soft_head_slice_{lo}_bend'], names[f'soft_head_slice_{hi}_bend'], round(q-lo,7)]


def rump_mark():
    # Reuse only the standard pigment. The transparent patch follows the new rear
    # surface and its body weights, instead of restoring the old rectangular rump.
    src=[e for e in read(LIB/'components/common/rump.json')['elements'] if e['name'].startswith('rump_round_end_slice_')]
    low=min(e['from'][1] for e in src)*SY;high=max(e['to'][1] for e in src)*SY
    width=max(e['to'][0] for e in src)*CFG['attachment_scale'][0]
    rows=sorted(set([low,high]+[p[0] for p in PROFILE if low<p[0]<high]))
    points,tri,uv=[],[],[];u,v=CFG['anatomy']['anus_atlas']
    for y in rows:
        z=profile_at(y)[1]+CFG['anatomy']['anus_clearance']
        points.extend([[-width,y,z],[width,y,z]])
        ty=v+32*(high-y)/(high-low);uv.extend([[u,ty],[u+32,ty]])
    for i in range(len(rows)-1):
        a=i*2;tri.extend([(a,a+1,a+3),(a,a+3,a+2)])
    out=mesh('rounded_rump_mark',points,tri,[[uv[k] for k in t] for t in tri]);out['bone']='body_stretch'
    return out


def anatomy_geometry(e, group):
    # Bake the standard component into the local surface frame. The mamu base
    # is curved to the lower front; its outer pigment lies 0.05 units above the skin
    # only at full extension. Peni retains the approved taper and tilt.
    cfg=CFG['anatomy']
    def warp(p):
        x,y,z=p
        if group=='anatomy_mamu':
            cx,cy=common_site()[:2];Y=(y-.71)*SY+cy
            return [(x-.3)*CFG['attachment_scale'][0]+cx,Y,-profile_at(Y)[2]-(z-7.54)+cfg['mamu_retracted_inset']]
        offset=cfg['peni_offset']
        return [x*CFG['attachment_scale'][0]+offset[0],y*SY+offset[1],z+offset[2]]
    if group=='anatomy_mamu':
        # Preserve the rear profile's slope break at y=.7. A single quad joining
        # the two end heights would cut through that convex part of the skin.
        lo,hi=e['from'][1],e['to'][1]
        rows={lo,hi}
        rows.update(lo+(hi-lo)*i/8 for i in range(1,8))
        rows.update((p[0]-common_site()[1])/SY+.71 for p in PROFILE if lo<(p[0]-common_site()[1])/SY+.71<hi)
        rows=sorted(rows);flat=[]
        for j,(y0,y1) in enumerate(zip(rows,rows[1:])):
            slab=copy.deepcopy(e);slab['from'][1]=y0;slab['to'][1]=y1;vv=vertices(slab,128)
            for i in range(0,len(vv),24):
                if vv[i+6]>.9 and j<len(rows)-2:continue
                if vv[i+6]<-.9 and j>0:continue
                flat.extend(vv[i:i+24])
    else:flat=vertices(e,128)
    points=[];tri=[];uv=[]
    for i in range(0,len(flat),24):
        verts=[flat[i+j*8:i+j*8+8] for j in range(3)]
        a=[verts[1][k]-verts[0][k] for k in range(3)];b=[verts[2][k]-verts[0][k] for k in range(3)]
        n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
        if sum(n[k]*verts[0][5+k] for k in range(3))<0:verts[1],verts[2]=verts[2],verts[1]
        if group=='anatomy_mamu':verts[1],verts[2]=verts[2],verts[1]
        start=len(points);points.extend(warp(v[:3]) for v in verts)
        tri.append((start,start+1,start+2));uv.append([[v[3]*128,v[4]*128] for v in verts])
    out=mesh(e['name'],points,tri,uv);out['bone']=group
    return out


def export(ident):
    base = LIB/'assembled'/ident
    source_model = base/(ident+'.bbmodel')
    source_rig = ROOT/'art/source/standard-runtime'/ident/'animation-rig.json'
    bb = read(source_model); rig = copy.deepcopy(read(source_rig))
    original_groups = read(base/'animation-rig.json')['groups']
    body_names = {e['name'] for p in ['body','rump','puff'] for e in read(LIB/f'components/common/{p}.json')['elements']}
    skin = body(PROFILE, [132,132]); skin['name'] = 'rounded_body_surface'
    elements = [skin,rump_mark()]
    for e, group in zip(bb['elements'], original_groups):
        if e['name'] in body_names: continue
        item = anatomy_geometry(e,group) if group.startswith('anatomy_') else face_mesh(e) if e['name'].startswith('face_') else convert_cube(e)
        item['bone'] = group
        elements.append(item)
    for b in rig['bones']: b['pivot'] = point(b['pivot'])
    for name, clip in rig['clips'].items():
        for bone, channels in clip['tracks'].items():
            for key, value in channels.get('position', {}).items():
                channels['position'][key] = point(value)
                if bone == 'world_motion':
                    channels['position'][key][0] = channels['position'][key][2] = 0
    # The new continuous rear has no old rump blocks: drive its actual skin joints.
    clip = rig['clips']['waddle']; duration = clip['duration']
    for bone in rig['bones']:
        if not bone['name'].startswith('soft_head_slice_'): continue
        x,y,z = bone['pivot']; h = min(1,max(0,y/(8.48*SY)))
        rot, pos = {}, {}
        for i in range(61):
            t = i*duration/60; degrees = (5-3.5*h*h)*math.sin(t/duration*math.tau)
            a = math.radians(degrees); c,s = math.cos(a),math.sin(a); oz = -4.5
            rot[str(round(t,8))] = [0,degrees,0]
            pos[str(round(t,8))] = [c*x+s*(z-oz)-x,0,-s*x+c*(z-oz)+oz-z]
        clip['tracks'][bone['name']] = {'position':pos,'rotation':rot}
    rig['clips'] = {k:rig['clips'][k] for k in CFG['preview_actions']}
    anatomy=copy.deepcopy(read(LIB/'behaviors/anatomy.json'))
    anatomy.update(id='anatomy.rounded_standard_body',status='pending_confirmation',component='components/'+ident+'.json',rig='rigs/'+ident+'.json')
    anatomy['surface_site']=common_site()
    for part in anatomy['parts']:
        part['offset']=CFG['anatomy'][part['id']+'_travel']
        if part['id']=='peni':part['rotation']=CFG['anatomy']['peni_rotation']
    roots={'mamu':common_site(),
           'peni':[CFG['anatomy']['peni_offset'][0],1.15*SY+CFG['anatomy']['peni_offset'][1],-.88+CFG['anatomy']['peni_offset'][2]]}
    for part,pivot in roots.items():
        b=next(b for b in rig['bones'] if b['name']=='anatomy_'+part)
        b['parent']=f'soft_head_slice_{min(31,max(0,int(pivot[1]/(8.48*SY)*32-.5)))}_bend';b['pivot']=pivot
        for i in range(32):
            rig['bones'].append(dict(name=f'anatomy_{part}_deform_{i}',parent=f'soft_head_slice_{i}_bend',pivot=pivot.copy()))
    independent=compile_anatomy(anatomy)
    for c in independent.values():
        for part in roots:
            for i in range(32):c['tracks'][f'anatomy_{part}_deform_{i}']=copy.deepcopy(c['tracks']['anatomy_'+part])
    rig['clips'].update(independent)
    names = {b['name']:i for i,b in enumerate(rig['bones'])}
    out = HERE/'assembled'/ident; out.mkdir(parents=True,exist_ok=True)
    textures = {}
    for source in sorted((base/'textures').glob('*.png')):
        atlas = Image.new('RGBA',(256,256)); original=Image.open(source).convert('RGBA')
        atlas.paste(original,(0,0))
        ImageDraw.Draw(atlas).rectangle((130,130,135,135),fill=original.getpixel((8,56)))
        pigment=original.crop(tuple(CFG['anatomy']['anus_uv']));skin_color=original.getpixel((8,56))
        pigment.putdata([(0,0,0,0) if pixel==skin_color else pixel for pixel in pigment.getdata()])
        atlas.paste(pigment,tuple(CFG['anatomy']['anus_atlas']))
        target=out/'textures'/source.name;target.parent.mkdir(exist_ok=True);atlas.save(target)
        if source.stem in CFG['expressions']:
            textures[source.stem]='data:image/png;base64,'+base64.b64encode(target.read_bytes()).decode()
    png=(out/'textures/neutral.png').read_bytes();(out/(ident+'.png')).write_bytes(png)
    flat,weights,parts=[],[],[]
    for e in elements:
        vv=vertices(e,256);start=len(weights)
        for i in range(0,len(vv),24):
            a=[vv[i+8+k]-vv[i+k] for k in range(3)];b=[vv[i+16+k]-vv[i+k] for k in range(3)]
            n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
            if sum(n[k]*vv[i+5+k] for k in range(3))<0:
                vv[i+8:i+24]=vv[i+16:i+24]+vv[i+8:i+16]
        for i in range(0,len(vv),8):
            flexible=e['name'] in ('rounded_body_surface','rounded_rump_mark') or e['name'].startswith('face_')
            if e['bone'].startswith('anatomy_'):
                a,b,q=continuous_binding(vv[i+1],names)
                a=int(rig['bones'][a]['name'].split('_')[3]);b=int(rig['bones'][b]['name'].split('_')[3])
                weights.append([names[e['bone']+'_deform_'+str(a)],names[e['bone']+'_deform_'+str(b)],q])
            else:weights.append(continuous_binding(vv[i+1],names) if flexible else [names[e['bone']],names[e['bone']],0])
        flat.extend(round(v,7) for v in vv)
        parts.append(dict(name=e['name'],start=start,count=len(vv)//8,anatomy=e['bone'].removeprefix('anatomy_') if e['bone'].startswith('anatomy_') else ''))
    rig.update(groups=[e['bone'] for e in elements],element_vertex_counts=[p['count'] for p in parts],
               vertex_weights=weights,binding_mode='two_bone_linear_skinning')
    birth=copy.deepcopy(read((HERE/CFG['birth']['source']).resolve()))
    birth.update(id='rounded-'+ident+'-viviparous',character=ident,status='pending_confirmation',anatomy_policy='Front mamu site is the delivery outlet; peni and mamu are mutually exclusive. Delivery locks independent controls.')
    birth['references']=[Path(os.path.relpath(LIB.parent/'yukkuri-v1'/p,out)).as_posix() for p in birth['references']]
    birth['birth_outlet']['anchor']=common_site()
    birth['birth_outlet']['anchor'][2]-=CFG['birth']['outlet_clearance']
    birth['pregnant_shape']['lower_height']*=SY
    birth['pregnant_shape']['transition_y']*=SY
    birth['offspring_count']['preview_default']=CFG['birth']['count']
    birth['newborn_spacing']=(max(flat[0::8])-min(flat[0::8]))*birth['birth_scale']+.45
    save(out/'birth.json',dict(viviparous=birth,body_height=CFG['birth']['body_height'],face_clearance=CFG['face_clearance'],profile=PROFILE,growth={'stages':[dict(id='adult',min_scale=.6,max_scale=1.4)]}),True)
    model=dict(format=1,id=ident,units_per_block=16,house=False,texture_size=256,anatomy=anatomy,birth=read(out/'birth.json'),vertices=flat,weights=weights,parts=parts,
               bones=[dict(name=b['name'],parent=-1 if b['parent'] is None else names[b['parent']],pivot=b['pivot']) for b in rig['bones']],
               clips={k:dict(duration=c['duration'],loop=c['loop'],tracks=[dict(bone=names[n],channel=ch,keys=[[float(t),*v] for t,v in sorted(keys.items(),key=lambda p:float(p[0]))]) for n,channels in c['tracks'].items() for ch,keys in channels.items()]) for k,c in rig['clips'].items()})
    uid=lambda n:str(uuid.uuid5(uuid.NAMESPACE_URL,'rounded-standard-body/'+ident+'/'+n))
    nodes={b['name']:dict(name=b['name'],uuid=uid('bone/'+b['name']),origin=b['pivot'],children=[]) for b in rig['bones']}
    for e in elements:e['uuid']=uid(e['name']);nodes[e['bone']]['children'].append(e['uuid'])
    for b in rig['bones']:
        if b['parent']:nodes[b['parent']]['children'].append(nodes[b['name']])
    bbo=copy.deepcopy(bb);bbo.update(elements=elements,outliner=[nodes[b['name']] for b in rig['bones'] if b['parent'] is None],animations=[],resolution=dict(width=256,height=256))
    bbo['textures']=[{**bb['textures'][0],'name':ident+'.png','path':ident+'.png','source':'data:image/png;base64,'+base64.b64encode(png).decode(),'width':256,'height':256,'uv_width':256,'uv_height':256}]
    animations={}
    for name,c in rig['clips'].items():
        animators={};converted={}
        for n,channels in c['tracks'].items():
            kfs=[];converted[n]={}
            for channel,keys in channels.items():
                converted[n][channel]={}
                for t,v in keys.items():
                    v=[-v[0],-v[1],v[2]] if channel=='rotation' else [-v[0],v[1],v[2]] if channel=='position' else v
                    converted[n][channel][t]=v
                    kfs.append(dict(channel=channel,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',map(str,v)))],uuid=uid(name+n+channel+t)))
            animators[uid('bone/'+n)]=dict(name=n,type='bone',keyframes=kfs)
        key='animation.'+ident+'.'+name
        animations[key]=dict(loop=c['loop'],animation_length=c['duration'],bones=converted)
        bbo['animations'].append(dict(name=key,uuid=uid(key),loop='loop' if c['loop'] else 'once',length=c['duration'],snapping=100,animators=animators))
    geo=[dict(name=b['name'],pivot=b['pivot'],**({'parent':b['parent']} if b['parent'] else {})) for b in rig['bones']]
    for e in elements:
        g=dict(name=e['name']+'_geometry',parent=e['bone'],pivot=[0,0,0])
        if e.get('type')=='mesh':g['poly_mesh']=poly_mesh(e)
        else:g['cubes']=[dict(origin=e['from'],size=[e['to'][i]-e['from'][i] for i in range(3)],uv={k:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for k,f in e['faces'].items()})]
        geo.append(g)
    save(HERE/'components'/(ident+'.json'),dict(status='pending_confirmation',elements=elements))
    save(HERE/'rigs'/(ident+'.json'),rig)
    common_anatomy=copy.deepcopy(anatomy);common_anatomy.update(component={id:'components/'+id+'.json' for id in CFG['characters']},rig={id:'rigs/'+id+'.json' for id in CFG['characters']})
    save(HERE/'anatomy.json',common_anatomy,True)
    save(out/(ident+'.bbmodel'),bbo);save(out/'animation-rig.json',rig);save(out/'runtime.model.json',model)
    save(out/(ident+'.animation.json'),dict(format_version='1.8.0',animations=animations))
    save(out/(ident+'.geo.json'),{'format_version':'1.12.0','minecraft:geometry':[dict(description=dict(identifier='geometry.rounded_'+ident,texture_width=256,texture_height=256),bones=geo)]})
    return dict(model=model,textures=textures),dict(source_model=str(source_model.relative_to(ROOT)),source_model_sha256=sha(source_model),source_rig=str(source_rig.relative_to(ROOT)),source_rig_sha256=sha(source_rig),model_sha256=sha(out/(ident+'.bbmodel')),rig_sha256=sha(out/'animation-rig.json'),runtime_sha256=sha(out/'runtime.model.json'),elements=len(elements),vertices=len(weights))


def main():
    catalog={};records={}
    for ident in CFG['characters']:
        entry,records[ident]=export(ident);catalog[ident]=entry
        source=ROOT/'src/main/resources/assets/mcyukkuri'
        baseline=read(source/'yukkuri'/(ident+'.json'))
        baseline['clips']={k:v for k,v in baseline['clips'].items() if k in CFG['preview_actions'] or k.startswith('anatomy_')}
        baseline['anatomy']=read(LIB/'behaviors/anatomy.json')
        catalog[ident+'_baseline']=dict(model=baseline,textures={k:'data:image/png;base64,'+base64.b64encode((source/'textures/entity'/ident/(k+'.png')).read_bytes()).decode() for k in CFG['expressions'] if (source/'textures/entity'/ident/(k+'.png')).exists()})
    preview=HERE/'previews/current';preview.mkdir(parents=True,exist_ok=True)
    save(preview/'preview-data.json',catalog)
    (preview/'preview-data.js').write_text('const MODELS='+json.dumps(catalog,separators=(',',':'))+';',encoding='utf-8')
    js=(ROOT/'art/source/standard-runtime/preview.js').read_text(encoding='utf-8').replace('textureSize:128','textureSize:model.texture_size||128')
    core=js[js.index('const clamp='):js.index('function groundVertices')]
    core=core.replace('function posed(model,action,t){','function posed(model,action,t,overrides=null){')
    core=core.replace("sample(model.clips.idle,0,poses);sample(model.clips[action],t,poses);", """sample(model.clips.idle,0,poses);sample(model.clips[action],t,poses);
 if(overrides)for(let i=0;i<model.bones.length;i++){
  const name=model.bones[i].name,base=name.split('_deform_')[0],channels=overrides[base];
  if(channels)for(const [channel,value]of Object.entries(channels)){const off={position:0,rotation:3,scale:6}[channel];for(let k=0;k<3;k++)poses[i][off+k]=value[k];}
 }""")
    core=core.replace("if(part.anatomy&&!action.startsWith('anatomy_'+part.anatomy+'_'))continue;", """if(part.anatomy&&!action.startsWith('anatomy_'+part.anatomy+'_')){
   const channels=overrides&&overrides['anatomy_'+part.anatomy];
   if(!channels||!Object.values(channels).flat().some(v=>Math.abs(v)>1e-9))continue;
  }""")
    (preview/'pose.js').write_text(core,encoding='utf-8')
    inputs=[HERE/'recipe.json',Path(__file__),LIB/'subspecies/predator/species.json',LIB/'subspecies/predator/species_geometry.py',LIB/'ecology/house-yukkuri/mesh_geometry.py',LIB/'geometry.py',ROOT/'art/source/standard-runtime/preview.js',LIB/'runtime/render.js',LIB/'components/common/anatomy.json',LIB/'components/common/anatomy-rig.json',LIB/'behaviors/anatomy.json',LIB/'anatomy_library.py',LIB/'runtime/anatomy.js']
    for ident in CFG['characters']:
        inputs.extend([LIB/'assembled'/ident/(ident+'.bbmodel'),LIB/'assembled'/ident/'animation-rig.json',ROOT/'art/source/standard-runtime'/ident/'animation-rig.json',ROOT/'src/main/resources/assets/mcyukkuri/yukkuri'/(ident+'.json')])
        inputs.extend(sorted((LIB/'assembled'/ident/'textures').glob('*.png')))
    inputs.extend(LIB/f'components/common/{p}.json' for p in ['body','rump','puff'])
    inputs.extend([(HERE/CFG['birth']['source']).resolve(),LIB.parent/'yukkuri-v1/runtime/viviparous-birth.js',LIB.parent/'yukkuri-v1/runtime/growth.js',HERE/'previews/current/birth.js'])
    save(HERE/'manifest.json',dict(status='pending_confirmation',body_profile=PROFILE,body_source_sha256=sha((HERE/CFG['body_source']).resolve()),recipe_sha256=sha(HERE/'recipe.json'),builder_sha256=sha(Path(__file__)),inputs={str(p.relative_to(ROOT)):sha(p) for p in inputs},characters=records,game_integration='not_performed',approved_standard_modified=False),True)
    print(json.dumps({k:{a:b for a,b in v.items() if a in ['elements','vertices']} for k,v in records.items()}))


if __name__=='__main__':main()
