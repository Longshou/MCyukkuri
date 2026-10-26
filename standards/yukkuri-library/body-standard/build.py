"""Rebuild the approved rounded standard from its frozen feature inputs and recipe."""
from pathlib import Path
import base64
import copy
import hashlib
import json
import math
import os
import sys
import uuid
import runpy

HERE = Path(__file__).resolve().parent
LIB = HERE.parent
ROOT = LIB.parents[1]
PREVIEW = ROOT/'.local/previews/body-standard'
PREVIEW_HUB = ROOT/'.local/previews/index.html'
sys.path[:0] = [str(LIB/'subspecies/predator'), str(LIB/'ecology/house-yukkuri'), str(LIB)]
from species_geometry import body
from mesh_geometry import mesh, vertices, poly_mesh
from anatomy_library import compile_anatomy
from motion_library import MotionLibrary
from character_appearance import apply_style as apply_character_style


def read(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))


def save(p, value, pretty=False):
    p.parent.mkdir(parents=True, exist_ok=True)
    payload=json.dumps(value, ensure_ascii=False, indent=2 if pretty else None,
                       separators=None if pretty else (',', ':'), allow_nan=False).encode('utf-8')
    if p.exists() and p.read_bytes()==payload:return
    temporary=p.with_name(p.name+'.writing')
    temporary.write_bytes(payload)
    temporary.replace(p)


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
    e=copy.deepcopy(next(p for p in read(LIB/'components/common/anatomy.json')['elements'] if p['name']==('peni_taper_00' if group=='anatomy_peni' else e['name'])))
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


def preview_anchor(ident, out):
    href=Path(os.path.relpath(PREVIEW_HUB,out)).as_posix()
    return f'<p><a href="{href}">素材总览</a></p>'


def replace_preview_anchor(page, ident, out):
    old_href=Path(os.path.relpath(HERE/'previews/current/index.html',out)).as_posix()+f'?character={ident}'
    old=f'<p><a href="{old_href}">标准体型总览与胎生</a></p>'.encode('utf-8')
    new=preview_anchor(ident,out).encode('utf-8')
    if old not in page and page.count(new)==1:
        return page
    if page.count(old)!=1:
        raise ValueError(f'Expected one legacy preview link for {ident}')
    return page.replace(old,new,1)


def update_preview_links():
    changed=[]
    for ident,spec in CFG['sources'].items():
        if ident not in CFG['characters']:
            continue
        out=LIB/spec['export']
        page=out/'index.html'
        original=page.read_bytes()
        updated=replace_preview_anchor(original,ident,out)
        if updated!=original:
            page.write_bytes(updated)
            changed.append(str(page.relative_to(ROOT)))
    print(json.dumps({'updated':changed,'count':len(changed)},ensure_ascii=False))


def write_legacy_preview(ident,model,rig,old_data,out,atlas_size):
    # Keep the existing catalog/compare protocol and all expression choices.
    data=copy.deepcopy(old_data)
    data.update(character=ident,label=CFG['labels'][ident],textureSize=atlas_size,anatomy=model['anatomy'])
    data['textures']={p.stem:'data:image/png;base64,'+base64.b64encode(p.read_bytes()).decode() for p in sorted((out/'textures').glob('*.png'))}
    data['motions']={k:{**old_data.get('motions',{}).get(k,{}),'id':k,'label':old_data.get('motions',{}).get(k,{}).get('label',k),'kind':old_data.get('motions',{}).get(k,{}).get('kind','variant'),'duration':c['duration']} for k,c in rig['clips'].items() if not k.startswith('anatomy_')}
    data['preview'].update(span=18.5 if ident=='marisa_tsumuri' else 13,centerY=10 if ident in ('marisa','marisa_tsumuri') else 6.5,default_motion='idle')
    data['model'].update(vertices=model['vertices'],rig=rig,expressionPatches=model.get('expression_patches',{}),bodyCubeCount=0,bodyVertexCount=sum(p['count'] for p in model['parts'][:2]))
    runtime='const DATA='+json.dumps(data,ensure_ascii=False,separators=(',',':'))+';\n'+'\n'.join((LIB/'runtime'/f).read_text('utf8') for f in ['motion.js','render.js','anatomy.js','anatomy-controls.js'])
    template=(LIB/'runtime/viewer.html').read_text('utf8')
    for page in ['index.html','compare.html']:
        template=template.replace('href="../../'+page+'"','href="'+Path(os.path.relpath(LIB/page,out)).as_posix()+'"')
    label=CFG['labels'][ident]+' · 标准圆润体型'+(' · 面容及蝴蝶结待确认' if CFG['sources'][ident].get('bow_scale') else ' · 面容待确认' if CFG['sources'][ident].get('face_style') else '')
    template=template.replace('__RUNTIME__',runtime).replace('__CHARACTER_LABEL__',label).replace('__MODEL_FILE__',ident+'.bbmodel')
    if ident=='reimu_wasa':template=template.replace("o['hand_'+side]","o['sideburn_'+side]").replace('抬左辫','轻摆左鬓角').replace('抬右辫','轻摆右鬓角')
    if ident=='reimu_deibu':template=template.replace("expression='neutral'","expression='mature'")
    template=template.replace('<footer>',preview_anchor(ident,out)+'<footer>')
    (out/'index.html').write_text(template,'utf8')


def export(ident):
    from PIL import Image, ImageDraw
    spec=CFG['sources'][ident];base=HERE/spec['folder'];atlas_size=spec['atlas_size']
    out=LIB/spec['export']
    source_model=base/(ident+'.bbmodel')
    source_rig=base/('selected-rig.json' if (base/'selected-rig.json').exists() else 'animation-rig.json')
    bb=read(source_model);rig=copy.deepcopy(read(source_rig));norm=spec['normalization']
    for e in bb['elements']:
        for key in ('from','to','origin'):
            if key in e:e[key]=[v/norm[k] for k,v in enumerate(e[key])]
    for b in rig['bones']:b['pivot']=[v/norm[k] for k,v in enumerate(b['pivot'])]
    for clip in rig['clips'].values():
        for channels in clip['tracks'].values():
            for t,v in channels.get('position',{}).items():channels['position'][t]=[x/norm[k] for k,x in enumerate(v)]
    # Keep original expression metadata, layers and existing character-only actions.
    old_data=json.JSONDecoder().raw_decode((base/'index.html').read_text('utf8').split('const DATA=',1)[1])[0]
    original_groups = read(base/'animation-rig.json')['groups']
    if ident=='reimu_wasa':
        extras=copy.deepcopy(read(HERE/'inputs/reimu/selected-rig.json')['clips'])
        for name,clip in extras.items():
            if name not in rig['clips']:
                clip['tracks']={n.replace('hand_left','sideburn_left').replace('hand_right','sideburn_right'):v for n,v in clip['tracks'].items()}
                rig['clips'][name]=clip
    bone_names={b['name'] for b in rig['bones']}
    for clip in rig['clips'].values():clip['tracks']={n:v for n,v in clip['tracks'].items() if n in bone_names}
    body_names = {e['name'] for p in ['body','rump','puff'] for e in read(LIB/f'components/common/{p}.json')['elements']}
    palette_origin=atlas_size//2+4
    skin = body(PROFILE, [palette_origin,palette_origin]); skin['name'] = 'rounded_body_surface'
    original_anus=CFG['anatomy']['anus_atlas']
    CFG['anatomy']['anus_atlas']=[palette_origin+12,palette_origin+12]
    elements = [skin,rump_mark()]
    for e, group in zip(bb['elements'], original_groups):
        if e['name'] in body_names or group=='anatomy_peni' and e['name']!='peni_taper_00': continue
        item = anatomy_geometry(e,group) if group.startswith('anatomy_') else face_mesh(e) if e['name'].startswith('face_') else convert_cube(e)
        item['bone'] = group
        elements.append(item)
    for b in rig['bones']: b['pivot'] = point(b['pivot'])
    hair_recipe = HERE/'features/hair-contact/recipe.json'
    hair_config = read(hair_recipe)
    fit_hair = runpy.run_path(str(hair_recipe.parent/'author.py'))['fit']
    elements = fit_hair(elements, ident, hair_config, PROFILE, profile_at, mesh, vertices, atlas_size)
    for name, clip in rig['clips'].items():
        for bone, channels in clip['tracks'].items():
            for key, value in channels.get('position', {}).items():
                channels['position'][key] = point(value)
                if bone == 'world_motion':
                    channels['position'][key][0] = channels['position'][key][2] = 0
    if spec.get('bow_scale'):
        # Resize the accessory about its attachment, independently of body size.
        # The frozen deibu input also widened the bow by 13/12 along X.
        factors=spec['bow_scale']
        anchor=next(b['pivot'][:] for b in rig['bones'] if b['name']=='bow_knot')
        local=lambda p:[anchor[k]+(p[k]-anchor[k])*factors[k] for k in range(3)]
        for e in elements:
            if not e['bone'].startswith('bow_'):continue
            for key in ('from','to','origin'):
                if key in e:e[key]=local(e[key])
        for b in rig['bones']:
            if b['name'].startswith('bow_'):b['pivot']=local(b['pivot'])
        for clip in rig['clips'].values():
            for bone,channels in clip['tracks'].items():
                if bone.startswith('bow_') and 'position' in channels:
                    channels['position']={t:[v[k]*factors[k] for k in range(3)] for t,v in channels['position'].items()}
    # The new continuous rear has no old rump blocks: drive its actual skin joints.
    clip = rig['clips'].get('waddle'); duration = clip['duration'] if clip else 1
    for bone in rig['bones']:
        if not clip or not bone['name'].startswith('soft_head_slice_'): continue
        x,y,z = bone['pivot']; h = min(1,max(0,y/(8.48*SY)))
        rot, pos = {}, {}
        for i in range(61):
            t = i*duration/60; degrees = (5-3.5*h*h)*math.sin(t/duration*math.tau)
            a = math.radians(degrees); c,s = math.cos(a),math.sin(a); oz = -4.5
            rot[str(round(t,8))] = [0,degrees,0]
            pos[str(round(t,8))] = [c*x+s*(z-oz)-x,0,-s*x+c*(z-oz)+oz-z]
        clip['tracks'][bone['name']] = {'position':pos,'rotation':rot}
    anatomy=copy.deepcopy(read(LIB/'behaviors/anatomy.json'))
    relative=lambda p:Path(os.path.relpath(p,out)).as_posix()
    anatomy.update(id='anatomy.rounded_standard_body',status='pending_visual_review',component=relative(HERE/'components'/(ident+'.json')),rig=relative(HERE/'rigs'/(ident+'.json')),placement='mamu and peni share the same front lower skin site; anus remains at rear',reference=relative(LIB/'ANATOMY.md'))
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
    out = LIB/spec['export']; out.mkdir(parents=True,exist_ok=True)
    textures = {}
    for source in sorted((base/'textures').glob('*.png')):
        atlas = Image.new('RGBA',(atlas_size,atlas_size)); original=Image.open(source).convert('RGBA')
        atlas.paste(original,(0,0))
        ImageDraw.Draw(atlas).rectangle((palette_origin-2,palette_origin-2,palette_origin+3,palette_origin+3),fill=original.getpixel((8,56)))
        pigment=original.crop(tuple(CFG['anatomy']['anus_uv']));skin_color=original.getpixel((8,56))
        pigment.putdata([(0,0,0,0) if pixel==skin_color else pixel for pixel in pigment.getdata()])
        atlas.paste(pigment,tuple(CFG['anatomy']['anus_atlas']))
        if spec.get('face_style'):
            style_file=HERE/spec['face_style'];style=read(style_file)
            author=runpy.run_path(str(style_file.parent/'author.py'))['apply_style']
            boxes={e['name']:list(map(round,e['faces']['north']['uv'])) for e in bb['elements'] if e['name'].startswith('face_')}
            atlas=author(atlas,boxes,source.stem,style)
        atlas=apply_character_style(ident,atlas)
        target=out/'textures'/source.name;target.parent.mkdir(exist_ok=True);atlas.save(target)
        if source.stem in CFG['expressions']:
            textures[source.stem]='data:image/png;base64,'+base64.b64encode(target.read_bytes()).decode()
    png=(out/'textures/neutral.png').read_bytes();(out/(ident+'.png')).write_bytes(png)
    flat,weights,parts=[],[],[]
    for e in elements:
        vv=vertices(e,atlas_size);start=len(weights)
        for i in range(0,len(vv),24):
            a=[vv[i+8+k]-vv[i+k] for k in range(3)];b=[vv[i+16+k]-vv[i+k] for k in range(3)]
            n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
            if sum(n[k]*vv[i+5+k] for k in range(3))<0:
                vv[i+8:i+24]=vv[i+16:i+24]+vv[i+8:i+16]
        for i in range(0,len(vv),8):
            flexible=e['name'] in ('rounded_body_surface','rounded_rump_mark') or e['name'].startswith('face_') or e.get('body_follow',False)
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
    birth.update(id='rounded-'+ident+'-viviparous',character=ident,status='user_approved_standard',anatomy_policy='Front mamu site is the delivery outlet; peni and mamu are mutually exclusive. Delivery locks independent controls.')
    birth['references']=[Path(os.path.relpath(LIB.parent/'yukkuri-v1'/p,out)).as_posix() for p in birth['references']]
    birth['birth_outlet']['anchor']=common_site()
    birth['birth_outlet']['anchor'][2]-=CFG['birth']['outlet_clearance']
    birth['pregnant_shape']['lower_height']*=SY
    birth['pregnant_shape']['transition_y']*=SY
    birth['offspring_count']['preview_default']=CFG['birth']['count']
    birth['newborn_spacing']=(max(flat[0::8])-min(flat[0::8]))*birth['birth_scale']+.45
    save(out/'birth.json',dict(viviparous=birth,body_height=CFG['birth']['body_height'],face_clearance=CFG['face_clearance'],palette_skin=[palette_origin+.5,palette_origin+.5],profile=PROFILE,growth={'stages':[dict(id='adult',min_scale=.6,max_scale=1.4)]}),True)
    model=dict(format=1,id=ident,units_per_block=16,house=False,texture_size=atlas_size,anatomy=anatomy,birth=read(out/'birth.json'),vertices=flat,weights=weights,parts=parts,
               bones=[dict(name=b['name'],parent=-1 if b['parent'] is None else names[b['parent']],pivot=b['pivot']) for b in rig['bones']],
               clips={k:dict(duration=c['duration'],loop=c['loop'],tracks=[dict(bone=names[n],channel=ch,keys=[[float(t),*v] for t,v in sorted(keys.items(),key=lambda p:float(p[0]))]) for n,channels in c['tracks'].items() for ch,keys in channels.items()]) for k,c in rig['clips'].items()})
    patches={}
    for expression in old_data['textures']:
        preset=LIB/'expressions/presets'/(expression+'.json')
        if not preset.exists():continue
        data=read(preset);changes=copy.deepcopy(data.get('geometry_offsets',{}))
        if data.get('cheek_scale',1)!=1:
            for name in ['face_blush_left','face_blush_right']:changes[name]={'scale':[1,data['cheek_scale'],1]}
        for i,e in enumerate(elements):
            if e['name'] not in changes:continue
            delta=changes[e['name']];patched=copy.deepcopy(e)
            center=[sum(p[k] for p in e['vertices'].values())/len(e['vertices']) for k in range(3)]
            for p in patched['vertices'].values():
                for k in range(2):p[k]=center[k]+(p[k]-center[k])*delta.get('scale',[1,1,1])[k]+delta.get('position',[0,0,0])[k]*CFG['face_scale'][k]
                p[2]=-profile_at(p[1])[2]-CFG['face_clearance']
            patches.setdefault(expression,[]).append(dict(index=i,vertices=vertices(patched,atlas_size)))
    model['expression_patches']=patches
    common_anatomy=copy.deepcopy(anatomy)
    scale=spec.get('overall_scale',1)
    if scale!=1:
        # Bake one uniform size change after the normalized construction, so
        # attachments, translations and skinning retain exactly the same ratios.
        vector=lambda v:[x*scale for x in v]
        for e in elements:
            for key in ['from','to','origin']:
                if key in e:e[key]=vector(e[key])
            if e.get('type')=='mesh':e['vertices']={k:vector(v) for k,v in e['vertices'].items()}
        for b in rig['bones']:b['pivot']=vector(b['pivot'])
        for c in rig['clips'].values():
            for channels in c['tracks'].values():
                if 'position' in channels:channels['position']={t:vector(v) for t,v in channels['position'].items()}
        if 'stride_units' in rig:rig['stride_units']*=scale
        for b in model['bones']:b['pivot']=vector(b['pivot'])
        for c in model['clips'].values():
            for track in c['tracks']:
                if track['channel']=='position':track['keys']=[[k[0],*vector(k[1:])] for k in track['keys']]
        for values in [flat]+[p['vertices'] for ps in patches.values() for p in ps]:
            for i in range(0,len(values),8):values[i:i+3]=vector(values[i:i+3])
        anatomy['surface_site']=vector(anatomy['surface_site'])
        for part in anatomy['parts']:part['offset']=vector(part['offset'])
        data=model['birth'];data['profile']=[vector(p) for p in data['profile']]
        for key in ['body_height','face_clearance']:data[key]*=scale
        birth=data['viviparous']
        for key in ['anchor','radii']:birth['birth_outlet'][key]=vector(birth['birth_outlet'][key])
        for key in ['lower_height','transition_y']:birth['pregnant_shape'][key]*=scale
        surface=birth['outlet_surface']
        for key in ['rim_width','rim_depth','contact_band']:surface[key]*=scale
        surface['patch']={k:v*scale for k,v in surface['patch'].items()}
        birth['newborn_spacing']*=scale
        model['overall_scale']=scale
        save(out/'birth.json',data,True)
        configured=copy.deepcopy(anatomy)
        configured.update(component='../components/'+ident+'.json',rig='../rigs/'+ident+'.json',reference='../../ANATOMY.md')
        save(HERE/'anatomy'/(ident+'.json'),configured,True)
    uid=lambda n:str(uuid.uuid5(uuid.NAMESPACE_URL,'rounded-standard-body/'+ident+'/'+n))
    nodes={b['name']:dict(name=b['name'],uuid=uid('bone/'+b['name']),origin=b['pivot'],children=[]) for b in rig['bones']}
    for e in elements:e['uuid']=uid(e['name']);nodes[e['bone']]['children'].append(e['uuid'])
    for b in rig['bones']:
        if b['parent']:nodes[b['parent']]['children'].append(nodes[b['name']])
    bbo=copy.deepcopy(bb);bbo.update(elements=elements,outliner=[nodes[b['name']] for b in rig['bones'] if b['parent'] is None],animations=[],resolution=dict(width=atlas_size,height=atlas_size))
    bbo['textures']=[{**bb['textures'][0],'name':ident+'.png','path':ident+'.png','source':'data:image/png;base64,'+base64.b64encode(png).decode(),'width':atlas_size,'height':atlas_size,'uv_width':atlas_size,'uv_height':atlas_size}]
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
        if e.get('type')!='mesh' and any(e.get('rotation',[0,0,0])):
            g['cubes'][0].update(pivot=e['origin'],rotation=e['rotation'])
        geo.append(g)
    save(HERE/'components'/(ident+'.json'),dict(status='pending_confirmation',revision=hair_config['revision'],elements=elements))
    save(HERE/'rigs'/(ident+'.json'),rig)
    common_anatomy.update(component={id:'components/'+id+'.json' for id in CFG['characters']},rig={id:'rigs/'+id+'.json' for id in CFG['characters']},reference='../ANATOMY.md')
    save(HERE/'anatomy.json',common_anatomy,True)
    save(out/(ident+'.bbmodel'),bbo);save(out/'animation-rig.json',rig);save(out/'runtime.model.json',model)
    save(out/(ident+'.animation.json'),dict(format_version='1.8.0',animations=animations))
    save(out/(ident+'.geo.json'),{'format_version':'1.12.0','minecraft:geometry':[dict(description=dict(identifier='geometry.standard_'+ident,texture_width=atlas_size,texture_height=atlas_size),bones=geo)]})
    CFG['anatomy']['anus_atlas']=original_anus
    write_legacy_preview(ident,model,rig,old_data,out,atlas_size)
    return dict(model=model,textures=textures),dict(source_model=str(source_model.relative_to(ROOT)),source_model_sha256=sha(source_model),source_rig=str(source_rig.relative_to(ROOT)),source_rig_sha256=sha(source_rig),model_sha256=sha(out/(ident+'.bbmodel')),rig_sha256=sha(out/'animation-rig.json'),runtime_sha256=sha(out/'runtime.model.json'),elements=len(elements),vertices=len(weights))


def main():
    if '--preview-links-only' in sys.argv:
        update_preview_links()
        return
    selected=sys.argv[sys.argv.index('--character')+1] if '--character' in sys.argv else None
    if selected and selected not in CFG['characters']:raise ValueError('Unknown character: '+selected)
    preview_cache=PREVIEW/'preview-data.json'
    catalog=read(preview_cache) if selected and preview_cache.exists() else {}
    records=read(HERE/'manifest.json')['characters'] if selected else {}
    for ident in ([selected] if selected else CFG['characters']):
        entry,records[ident]=export(ident)
        entry['model']=dict(entry['model']);entry['model'].pop('expression_patches',None)
        entry['textures']={k:v for k,v in entry['textures'].items() if k in CFG['expressions']}
        catalog[ident]=entry
    preview=PREVIEW;preview.mkdir(parents=True,exist_ok=True)
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
    inputs=[HERE/'recipe.json',HERE/'profile.json',Path(__file__),HERE/'runtime/birth.js',HERE/'inputs/manifest.json',LIB/'runtime/motion.js',LIB/'runtime/viewer.html',HERE/'features/hair-contact/recipe.json',HERE/'features/hair-contact/author.py']
    inputs.append(LIB/'character_appearance.py')
    for ident in CFG['characters']:
        registration=LIB/'characters'/(ident+'.json')
        style=read(registration).get('texture_style')
        if style:inputs.extend([registration,LIB/style,(LIB/style).parent/'author.py'])
    for spec in CFG['sources'].values():
        if spec.get('face_style'):
            style=HERE/spec['face_style'];inputs.extend([style,style.parent/'author.py'])
    appearances={id:dict(status=read(HERE/spec['face_style'])['status'],recipe=spec['face_style'],candidate=str(Path(spec['face_style']).with_name('candidate.json')).replace('\\','/')) for id,spec in CFG['sources'].items() if spec.get('face_style')}
    for ident,spec in CFG['sources'].items():
        if spec.get('bow_scale'):
            appearances[ident].update(candidate='features/deibu-bow/candidate.json',bow_scale=spec['bow_scale'],bow_source='recipe.json')
    appearances['hair_contact'] = dict(status='pending_confirmation', recipe='features/hair-contact/recipe.json', characters=CFG['characters'], baseline_approval='../provenance/standard-body.json')
    save(HERE/'manifest.json',dict(status='user_approved_standard',body_profile=PROFILE,recipe_sha256=sha(HERE/'recipe.json'),builder_sha256=sha(Path(__file__)),inputs={str(p.relative_to(ROOT)):sha(p) for p in inputs},characters=records,already_matching=CFG['already_matching'],excluded=CFG['excluded'],appearance_revisions=appearances,game_integration='not_performed',approval='provenance/standard-body.json'),True)
    public=read(LIB/'catalog-data.json')
    for ident in CFG['characters']:
        folder=CFG['sources'][ident]['export']
        public['models'][ident]=dict(label=CFG['labels'][ident],source='characters/'+ident+'.json',preview=folder+'/index.html',model=folder+'/'+ident+'.bbmodel',body_standard='body-standard/profile.json')
    public['standardComponents']['roundedBody']=dict(label='已保存的圆润标准体型',component='body-standard/profile.json',recipe='body-standard/recipe.json',documentation='body-standard/README.md',preview='../../.local/previews/index.html',characters=CFG['characters']+CFG['already_matching'],excluded=CFG['excluded'])
    public['standardComponents']['anatomy'].update(component='body-standard/anatomy.json',rig='body-standard/rigs/',behavior='body-standard/anatomy.json',characters=CFG['characters'])
    save(LIB/'catalog-data.json',public)
    payload='const CATALOG='+json.dumps(public,ensure_ascii=False,separators=(',',':'))+';'
    target=LIB/'catalog-data.js'
    if not target.exists() or target.read_text(encoding='utf-8')!=payload:
        temporary=target.with_suffix('.js.writing');temporary.write_text(payload,encoding='utf-8');temporary.replace(target)
    print(json.dumps({k:{a:b for a,b in v.items() if a in ['elements','vertices']} for k,v in records.items()}))


if __name__=='__main__':main()
