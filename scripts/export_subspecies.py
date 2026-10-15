#!/usr/bin/env python3
"""Select existing subspecies, author congenital geometry, and export only this domain.

--stage writes editable source exports and local snapshots, without publishing resources.
The seven ordinary runtime exports, their motion curves and approval records are read-only.
"""
from pathlib import Path
import argparse, base64, copy, hashlib, json, math, re, shutil, sys

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / 'standards/yukkuri-library'
OWN = LIB / 'subspecies/deficient'
OUT = ROOT / 'art/source/subspecies-runtime'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
sys.path[:0] = [str(LIB / 'ecology/house-yukkuri'), str(LIB)]
from mesh_geometry import vertices, body_mesh, mesh
from export_ecology_expressions import IDS as ECOLOGY_EXPRESSIONS

def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p, value):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def rel(p): return p.relative_to(ROOT).as_posix()

def export_textures(ident, base, textures, resolution, stage=False, check=False):
    """Resolve every runtime expression explicitly; a missing expression is an error."""
    from PIL import Image
    expressions=list(dict.fromkeys(read(LIB/'ecology/house-yukkuri/config.json')['expressions']+ECOLOGY_EXPRESSIONS))
    approved=read(LIB/'provenance/standard-expressions.json')
    records={}
    for expr in expressions:
        source=textures/(expr+'.png')
        if expr in ECOLOGY_EXPRESSIONS:
            species=ident if ident=='marisa_tsumuri' else base
            source=LIB/'assembled'/species/'textures'/(expr+'.png')
            preset=read(LIB/'expressions/presets'/(expr+'.json'))
            assert preset['status']=='accepted' and not preset.get('geometry_offsets'), expr
            assert sha(source)==approved['exports'][source.relative_to(LIB).as_posix()], 'Changed approved expression: '+str(source)
        assert source.is_file(), 'Missing expression source: '+str(source)
        with Image.open(source) as atlas:
            atlas.load()
            assert atlas.size==(resolution['width'],resolution['height']), 'UV atlas size mismatch: '+str(source)
        snapshot=OUT/ident/'textures'/(expr+'.png')
        runtime=ASSETS/'textures/entity'/ident/(expr+'.png')
        for target in ([snapshot] if stage else [snapshot,runtime]):
            if check:
                assert target.is_file() and sha(target)==sha(source), 'Missing or stale expression: '+str(target)
            else:
                target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
        records[expr]={'source':rel(source),'sha256':sha(source),'snapshot':rel(snapshot),
                       'runtime':rel(runtime),'width':resolution['width'],'height':resolution['height']}
    return records
def all_points(element): return [element[k] for k in ('from','to','origin') if k in element]

def affine(element, scale, shift=(0,0,0)):
    for point in all_points(element):
        point[:] = [round(point[i]*scale[i]+shift[i],7) for i in range(3)]

def body_part(name):
    return name.startswith(('soft_head_slice','upper_rear_slice','left_cheek_slice','right_cheek_slice','rump_'))

def author(base, shape):
    selected = ROOT / 'art/source/standard-runtime' / base
    bb = read(selected / f'{base}.bbmodel'); rig = read(selected / 'animation-rig.json')
    recipe = read(OWN/'recipes.json')['shape_rules'][shape]
    pairs=[]
    for source, group in zip(bb['elements'], rig['groups']):
        e=copy.deepcopy(source); n=e['name']
        hair = 'hair' in n or 'bang' in n or n.startswith(('crown','tied_lock','side_lock','side_tip','hand_','braid_'))
        if shape=='sparse' and hair and n!='crown':
            m=re.match(r'bang_(?:tip_)?(\d+)$',n)
            if m and int(m[1]) not in recipe['keep_bangs']: continue
            if n in ('hair_cap','back_hair','back_hair_lower') or n.startswith('rear_side_hair'): continue
            lo,hi=e['from'],e['to']; cx=(lo[0]+hi[0])/2; cz=(lo[2]+hi[2])/2
            affine(e,[recipe['hair_width'],recipe['hair_length'],.7],[cx*(1-recipe['hair_width']),recipe['hair_root_y']-recipe['hair_source_root_y']*recipe['hair_length'],cz*.3])
        elif shape=='sparse' and n.startswith('bow'):
            affine(e,[1,1,1],[0,0,recipe['bow_back_shift']])
        elif shape=='face' and n.startswith('face_'):
            if n.endswith('_left'):
                cx=(e['from'][0]+e['to'][0])/2; cy=(e['from'][1]+e['to'][1])/2
                sx,sy=recipe['left_eye_scale']; dx,dy=recipe['left_eye_offset']
                affine(e,[sx,sy,1],[cx*(1-sx)+dx,cy*(1-sy)+dy,0])
            elif 'mouth' in n: affine(e,[1,1,1],[*recipe['mouth_shift'],0])
        elif shape=='bean':
            if n.startswith(('soft_head_slice','upper_rear_slice','left_cheek_slice','right_cheek_slice','rump_core_slice','rump_round_end_slice')):
                continue
            if body_part(n) or group.startswith('anatomy_'):
                if body_part(n):
                    middle=(e['from'][1]+e['to'][1])/2
                    q=max(0,min(1,middle/8.48)); width=recipe['lower_width']*(1-q)+recipe['upper_width']*q
                    # Slice-local taper preserves original construction and root attachments.
                    affine(e,[width,recipe['body_height'],recipe['depth']])
                else: affine(e,[recipe['lower_width'],recipe['body_height'],recipe['depth']])
            elif n.startswith('puff_'):
                affine(e,[.52,.52,recipe['depth']],[0,recipe['head_raise'],0])
            else:
                s=recipe['head_scale']
                affine(e,[s,s,recipe['depth']],[0,recipe['head_raise'],0])
                if hair:
                    # Long rear hair is shortened at its root rather than draped down the whole bean.
                    top=e['to'][1]
                    for k in ('from','to'):
                        e[k][1]=top+(e[k][1]-top)*.65
        pairs.append((e,group))
    if shape=='bean':
        # A continuous ring mesh removes stair steps and the square torso silhouette.
        profile=[]; height=8.48*recipe['body_height']
        for i in range(49):
            q=i/48; y=q*height
            radius=(.76+.24*math.sin(min(1,q/.23)*math.pi/2))*(1-.46*q)
            if q>.90: radius*=math.sqrt(max(.035,1-((q-.90)/.102)**2))
            x=6*recipe['lower_width']*radius
            front=4.6*recipe['depth']*(.78+.22*min(1,q/.12))
            back=4.075*recipe['depth']*(.76+.24*math.sin(math.pi*q))
            if q>.94: front*=max(.35,1-(q-.94)*9);back*=max(.35,1-(q-.94)*9)
            profile.append([y,x,back,front])
        skin=next(e for e in bb['elements'] if e['name']=='soft_head_slice_0')
        surface=body_mesh(profile,skin);surface['name']='deficient_body_surface'
        import uuid
        surface['uuid']=str(uuid.uuid5(uuid.NAMESPACE_URL,base+'/deficient_body_surface'))
        pairs.insert(0,(surface,'body_stretch'))
        # Keep the established rear texture on the actual new skin, without a separate rear block.
        mark_points=[];mark_triangles=[];mark_uv=[]
        for j in range(17):
            y=.65+j*3.5/16; q=y/height; z=4.075*recipe['depth']*(.76+.24*math.sin(math.pi*q))+.006
            mark_points.extend([[-2.25,y,z],[2.25,y,z]])
        for j in range(16):
            a=j*2;b=a+1;c=a+3;d=a+2;v=96+j*32/16;V=v+32/16
            mark_triangles.extend([(a,b,c),(a,c,d)]);mark_uv.extend([[(96,128-v+96),(128,128-v+96),(128,128-V+96)],[(96,128-v+96),(128,128-V+96),(96,128-V+96)]])
        rear=mesh('bean_rear_mark',mark_points,mark_triangles,mark_uv);rear['uuid']=str(uuid.uuid5(uuid.NAMESPACE_URL,base+'/bean_rear_mark'))
        pairs.append((rear,'body_stretch'))
    bb['elements']=[p[0] for p in pairs]; rig['groups']=[p[1] for p in pairs]
    if shape=='sparse':
        for bone in rig['bones']:
            n=bone['name'];p=bone['pivot']
            if n.startswith('bow'):p[2]+=recipe['bow_back_shift']
            elif n!='crown' and ('hair' in n or 'bang' in n or n.startswith(('tied_lock','side_lock','side_tip','hand_','braid_'))):p[1]=p[1]*recipe['hair_length']+recipe['hair_root_y']-recipe['hair_source_root_y']*recipe['hair_length']
    if shape=='bean':
        r=recipe
        for bone in rig['bones']:
            n=bone['name']; p=bone['pivot']; q=max(0,min(1,p[1]/8.48))
            width=r['lower_width']*(1-q)+r['upper_width']*q
            if n in ('world_motion','root','body_motion','body_stretch') or 'slice_' in n or n.startswith(('rump','anatomy')):
                bone['pivot']=[p[0]*width,p[1]*r['body_height'],p[2]*r['depth']]
            else: bone['pivot']=[p[0]*r['head_scale'],p[1]*r['head_scale']+r['head_raise'],p[2]*r['depth']]
    if shape=='bean' and recipe.get('hair_fit'):
        sys.path.insert(0,str(OWN))
        from bean_hair import author as fit_bean_hair
        pairs=fit_bean_hair(pairs,rig,base,recipe)
        bb['elements']=[p[0] for p in pairs];rig['groups']=[p[1] for p in pairs]
        rig['element_vertex_counts']=[len(vertices(e,bb['resolution']['width']))//8 for e,g in pairs]
        rig.pop('vertex_weights',None)
    # Rebuild the editable hierarchy so removed hair elements cannot remain dangling.
    groups={}; roots=[]
    import uuid
    for bone in rig['bones']:
        groups[bone['name']]={'name':bone['name'],'origin':bone['pivot'],'rotation':[0,0,0],
            'uuid':str(uuid.uuid5(uuid.NAMESPACE_URL,base+'_'+shape+'/'+bone['name'])),'children':[]}
    for bone in rig['bones']:
        (roots if bone['parent'] is None else groups[bone['parent']]['children']).append(groups[bone['name']])
    for e,g in pairs: groups[g]['children'].append(e['uuid'])
    bb['outliner']=roots; bb['animations']=[]; bb['name']=base+'_'+shape
    source=OWN/'assembled'/bb['name']; save(source/(bb['name']+'.bbmodel'),bb); save(source/'animation-rig.json',rig)
    textures=selected/'textures'
    return bb,rig,source/(bb['name']+'.bbmodel'),source/'animation-rig.json',textures

def load_existing(ident):
    if ident=='marisa_tsumuri':
        d=LIB/'assembled'/ident; scale=1
    else:
        d=LIB/'subspecies/reimu-dos/assembled/reimu_wasa'
        scale=1/read(LIB/'subspecies/reimu-dos/recipes.json')['variants']['reimu_wasa']['world_scale']
    bp=d/(ident+'.bbmodel'); rp=d/'animation-rig.json'; bb=read(bp); rig=read(rp)
    if scale!=1:
        for e in bb['elements']: affine(e,[scale]*3)
        for b in rig['bones']: b['pivot']=[v*scale for v in b['pivot']]
        for clip in rig['clips'].values():
            for tracks in clip['tracks'].values():
                for value in tracks.get('position',{}).values(): value[:]=[v*scale for v in value]
        def normalize_tree(tree):
            for node in tree:
                if isinstance(node,dict):
                    node['origin']=[v*scale for v in node['origin']]; normalize_tree(node.get('children',[]))
        normalize_tree(bb['outliner'])
    return bb,rig,bp,rp,d/'textures'

def export(ident, base, selection, stage, preserve_textures=False):
    bb,rig,bp,rp,textures=selection; names={b['name']:i for i,b in enumerate(rig['bones'])}
    flat=[]; weights=[]; parts=[]
    for e,g in zip(bb['elements'],rig['groups']):
        vv=vertices(e,bb['resolution']['width'])
        for i in range(0,len(vv),24):
            a=[vv[i+8+k]-vv[i+k] for k in range(3)]; b=[vv[i+16+k]-vv[i+k] for k in range(3)]
            n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
            if sum(n[k]*vv[i+5+k] for k in range(3))<0: vv[i+8:i+24]=vv[i+16:i+24]+vv[i+8:i+16]
        count=len(vv)//8; parts.append({'name':e['name'],'start':len(weights),'count':count,'anatomy':g.removeprefix('anatomy_') if g.startswith('anatomy_') else ''})
        flat.extend(round(v,7) for v in vv)
        if e['name'] in ('deficient_body_surface','bean_rear_mark') or (ident.endswith('_bean') and g=='body_stretch'):
            for i in range(0,len(vv),8):
                q=max(0,min(31,vv[i+1]/(8.48*1.55)*32-.5));lo=int(q);hi=min(31,lo+1)
                weights.append([names[f'soft_head_slice_{lo}_bend'],names[f'soft_head_slice_{hi}_bend'],round(q-lo,7)])
        else: weights.extend([[names[g],names[g],0] for _ in range(count)])
    bones=[{'name':b['name'],'parent':names[b['parent']] if b['parent'] is not None else -1,'pivot':b['pivot']} for b in rig['bones']]
    clips={}
    # Retarget names from the current selected ordinary curves. Never execute the seven-model exporter.
    ordinary=read(ASSETS/'yukkuri'/(base+'.json'))
    for key,clip in ordinary['clips'].items():
        c=copy.deepcopy(clip); tracks=[]
        for track in c['tracks']:
            bone=ordinary['bones'][track['bone']]['name']
            if ident=='reimu_wasa': bone={'hand_left':'sideburn_left','hand_right':'sideburn_right'}.get(bone,bone)
            if bone in names: track['bone']=names[bone]; tracks.append(track)
        c['tracks']=tracks; clips[key]=c
    for key,clip in rig['clips'].items():
        if key not in ('still','shell_sway','shell_retract','fur_sway'): continue
        clips[key]={'duration':clip['duration'],'loop':clip['loop'],'tracks':[
            {'bone':names[n],'channel':ch,'keys':[[float(t),*v] for t,v in sorted(keys.items(),key=lambda x:float(x[0]))]}
            for n,channels in clip['tracks'].items() for ch,keys in channels.items()]}
    # Idle defaults must preserve the selected shell and puff visibility.
    clips['idle']={'duration':rig['clips']['idle']['duration'],'loop':True,'tracks':[
        {'bone':names[n],'channel':ch,'keys':[[float(t),*v] for t,v in sorted(keys.items(),key=lambda x:float(x[0]))]}
        for n,channels in rig['clips']['idle']['tracks'].items() for ch,keys in channels.items()]}
    if ident=='marisa_tsumuri':
        # Flexible torso bends cannot carry the rigid shell through the head. Shell types
        # use a small whole-root lean for these two actions; their rest geometry is unchanged.
        for key in ('side_bend','exercise_sides'):
            duration=clips[key]['duration'];clips[key]={'duration':duration,'loop':clips[key]['loop'],'tracks':[
                {'bone':names['root'],'channel':'rotation','keys':[[0,0,0,0],[duration*.25,0,0,5],[duration*.5,0,0,0],[duration*.75,0,0,-5],[duration,0,0,0]]}]}
        clips['waddle']['duration']*=1.4
        for t in clips['waddle']['tracks']:
            for k in t['keys']: k[0]*=1.4
    if ident.endswith('_bean'):
        for clip in clips.values():
            for t in clip['tracks']:
                if t['channel']=='position':
                    for k in t['keys']: k[1]*=.65; k[2]*=1.3; k[3]*=.82
    model={'format':1,'id':ident,'units_per_block':16,'house':False,'vertices':flat,'weights':weights,'parts':parts,'bones':bones,'clips':clips}
    if ident.endswith('_bean'):
        recipe=read(OWN/'recipes.json')['shape_rules']['bean']
        if recipe.get('face_fit'):
            sys.path.insert(0,str(OWN))
            from bean_face import fit as fit_bean_face
            fit_bean_face(model,bb,rig,recipe)
            save(bp,bb);save(rp,rig)
    dest=OUT/ident; save(dest/(ident+'.bbmodel'),bb); save(dest/'animation-rig.json',rig); save(dest/'runtime.model.json',model)
    if not stage: save(ASSETS/'yukkuri'/(ident+'.json'),model)
    if preserve_textures:
        texture_records=copy.deepcopy(read(OUT/'manifest.json')['models'][ident]['textures'])
        for expr,entry in texture_records.items():
            assert sha(OUT/ident/'textures'/(expr+'.png'))==sha(ASSETS/'textures/entity'/ident/(expr+'.png'))==entry['sha256'], expr
    else:
        texture_records=export_textures(ident,base,textures,bb['resolution'],stage)
    record={'geometry_identifier':ident,'base_species':base,'selected_model':rel(bp),'selected_model_sha256':sha(bp),
        'selected_rig':rel(rp),'selected_rig_sha256':sha(rp),'snapshot':rel(dest/(ident+'.bbmodel')),
        'runtime':rel(ASSETS/'yukkuri'/(ident+'.json')),'runtime_sha256':sha(dest/'runtime.model.json'),
        'bone_count':len(bones),'clips':list(clips),'textures':texture_records,
        'motion_source':rel(ASSETS/'yukkuri'/(base+'.json')),'motion_source_sha256':sha(ASSETS/'yukkuri'/(base+'.json')),
        'shape_status':'existing_user_approved_geometry' if ident=='marisa_tsumuri' else 'pending_user_confirmation',
        'approval_note':'Existing shell approval has no file hash; baseline fingerprint is recorded here without inventing a historic approved hash.' if ident=='marisa_tsumuri' else '',
        'runtime_acceptance':'not_executed'}
    return model,record

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--stage',action='store_true')
    mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--textures-only',action='store_true',help='Refresh expression selection without rebuilding geometry or motion')
    mode.add_argument('--check-textures',action='store_true',help='Read-only coverage, decode, size, provenance and snapshot/runtime equality checks')
    args=parser.parse_args()
    if (ROOT/'art/source/rounded-runtime/manifest.json').exists() and not (args.textures_only or args.check_textures):
        raise SystemExit('Rounded shell/Wasa selection is active. Use export_rounded_runtime.py and deficient_motion_contact.py; legacy regeneration would replace the selected bodies.')
    if args.textures_only or args.check_textures:
        manifest=read(OUT/'manifest.json')
        for ident,record in manifest['models'].items():
            if record.get('revision')in ('rounded-marisa-deficient-v1','rounded-reimu-deficient-v1'):continue  # Matching 256px atlases are owned by the rounded deficient exporter.
            if (ROOT/'art/source/rounded-runtime/manifest.json').exists() and ident in ('marisa_tsumuri','reimu_wasa'):continue
            source=(ROOT/record['selected_model']).parent/'textures' if ident in ('marisa_tsumuri','reimu_wasa') else ROOT/'art/source/standard-runtime'/record['base_species']/'textures'
            bb=read(ROOT/record['snapshot'])
            records=export_textures(ident,record['base_species'],source,bb['resolution'],args.stage,args.check_textures)
            if args.check_textures:
                assert record['textures']==records, 'Stale texture selection: '+ident
            else:
                record['textures']=records
                record['texture_acceptance']='offline_checked; current client acceptance pending'
        if not args.check_textures:
            manifest['texture_exporter_sha256']=sha(Path(__file__))
            save(OUT/'manifest.json',manifest)
        print('8 models x 16 expressions: '+('verified' if args.check_textures else 'exported; geometry and motion preserved'))
        return
    records={}; models={}
    for ident,base in [('marisa_tsumuri','marisa'),('reimu_wasa','reimu')]:
        models[ident],records[ident]=export(ident,base,load_existing(ident),args.stage)
    for base in ('reimu','marisa'):
        for shape in ('sparse','face','bean'):
            ident=base+'_'+shape;models[ident],records[ident]=export(ident,base,author(base,shape),args.stage)
    manifest={'version':1,'exporter':rel(Path(__file__)),'exporter_sha256':sha(Path(__file__)),'models':records}
    shell=models['marisa_tsumuri'];flat=[];parts=[]
    for p in shell['parts']:
        if not p['name'].startswith('shell'):continue
        start=len(flat)//8;flat.extend(shell['vertices'][p['start']*8:(p['start']+p['count'])*8])
        parts.append({'name':p['name'],'start':start,'count':p['count'],'texture':'mcyukkuri:textures/props/marisa_shell.png'})
    shell_prop={'format':1,'units_per_block':16,'vertices':flat,'parts':parts,'bounds':[[min(flat[k::8]),max(flat[k::8])]for k in range(3)]}
    save(OUT/'marisa_shell/runtime.model.json',shell_prop)
    manifest['marisa_shell']={'source_model':records['marisa_tsumuri']['selected_model'],'source_model_sha256':records['marisa_tsumuri']['selected_model_sha256'],'selected_parts':'shell*','snapshot':rel(OUT/'marisa_shell/runtime.model.json'),'runtime':rel(ASSETS/'yukkuri_props/marisa_shell.json'),'runtime_sha256':sha(OUT/'marisa_shell/runtime.model.json'),'texture':rel(ASSETS/'textures/props/marisa_shell.png'),'texture_sha256':sha(OUT/'marisa_tsumuri/textures/neutral.png'),'shape_status':'derived_from_selected_shell'}
    if not args.stage:
        save(ASSETS/'yukkuri_props/marisa_shell.json',shell_prop)
        shutil.copyfile(OUT/'marisa_tsumuri/textures/neutral.png',ASSETS/'textures/props/marisa_shell.png')
        save(ASSETS/'models/item/marisa_shell.json',{'parent':'builtin/entity','gui_light':'front','display':{'gui':{'rotation':[20,145,0],'scale':[.8,.8,.8]}}})
    save(OUT/'manifest.json',manifest)
    ordinary=read(ASSETS/'yukkuri_presentation/living.json')['variants']; presentation={}
    for ident,record in records.items():
        p=copy.deepcopy(ordinary[record['base_species']])
        if ident=='marisa_tsumuri':p['accessory_prefixes']=['shell_']
        if ident.endswith('_bean'):p['mouth']=[0,1.855*.52+8.25,-4.7*.82];p['food_scale']*=.65
        presentation[ident]=p
    save(OUT/'living.json',presentation)
    if not args.stage:save(ASSETS/'yukkuri_presentation/subspecies.json',presentation)
    print(json.dumps({'models':{k:{'parts':len(m['parts']),'bones':len(m['bones']),'triangles':len(m['weights'])//3} for k,m in models.items()},'stage_only':args.stage},ensure_ascii=False))

if __name__=='__main__':main()
