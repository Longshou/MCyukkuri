"""Rebuild only sparse/uneven-face Marisa from frozen rounded editable inputs.

The two variants retain their congenital feature recipes. Other deficient forms
and approved body records are read-only. Run preview_marisa_deficient.py after export.
"""
import copy
import gzip
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

import export_rounded_runtime as runtime

ROOT, LIB, ASSETS = runtime.ROOT, runtime.LIB, runtime.ASSETS
OWN = LIB/'subspecies/deficient'
SPECIES = 'marisa'
PACKAGE = OWN/'rounded-marisa'
HAIR = PACKAGE/'hair.py'
EXPORTER = Path(__file__)
REVISION = 'rounded-marisa-deficient-v1'
SELECTED = ROOT/'art/source/subspecies-runtime'
IDS = ('marisa_sparse', 'marisa_face')
read, save, sha, rel = runtime.read, runtime.save, runtime.sha, runtime.rel
sys.path.insert(0, str(LIB/'body-standard'))
import build as body


def configure(species):
    """Reuse the rounded deficient pipeline with a separate species input package."""
    global SPECIES, PACKAGE, IDS, EXPORTER, REVISION
    if species not in ('reimu', 'marisa'):
        raise ValueError(species)
    SPECIES = species
    PACKAGE = OWN/('rounded-'+species)
    IDS = tuple(species+'_'+shape for shape in ('sparse','face'))
    EXPORTER = ROOT/'scripts'/('export_'+species+'_deficient_rounded.py')
    REVISION = 'rounded-'+species+'-deficient-v1'


def freeze():
    target = PACKAGE/'inputs'
    if (target/'manifest.json').exists():
        return
    paths = [LIB/'assembled'/SPECIES/name for name in
             (SPECIES+'.bbmodel', 'animation-rig.json', 'runtime.model.json')]
    paths += [LIB/'body-standard'/name for name in ('recipe.json', 'profile.json')]
    paths += [ASSETS/'yukkuri'/f'{i}.json' for i in IDS]
    paths += [ASSETS/'yukkuri_wounds'/f'{i}.json.gz' for i in IDS]
    records = {}
    for path in paths:
        output = target/path.relative_to(ROOT)
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, output)
        records[rel(path)] = sha(path)
    save(target/'manifest.json', {'purpose': 'Immutable rounded donor and pre-fix wound migration inputs', 'files': records}, True)


def transform(e, fn):
    if e.get('type') == 'mesh':
        for key, point in e['vertices'].items():
            e['vertices'][key] = fn(point)
    else:
        for key in ('from', 'to'):
            e[key] = fn(e[key])


HAT_DROPS = {}


def author(ident):
    body.CFG = read(PACKAGE/'inputs/standards/yukkuri-library/body-standard/recipe.json')
    body.PROFILE = read(PACKAGE/'inputs/standards/yukkuri-library/body-standard/profile.json')['body_profile']
    body.SY = body.CFG['attachment_scale'][1]
    folder = PACKAGE/'inputs/standards/yukkuri-library/assembled'/SPECIES
    bb, rig, donor = [read(folder/name) for name in (SPECIES+'.bbmodel', 'animation-rig.json', 'runtime.model.json')]
    recipe = read(OWN/'recipes.json')['shape_rules'][ident.split('_')[1]]
    pairs = []
    oldparts = {p['name']: p for p in donor['parts']}
    names = {b['name']: i for i, b in enumerate(rig['bones'])}
    patches = {}
    for element, group in zip(bb['elements'], rig['groups']):
        e = copy.deepcopy(element)
        n = e['name']
        if n=='crown' and (SPECIES=='marisa' or ident.endswith('_sparse')):continue  # Rounded scalp already closes the head; the legacy slab floats without a hat.
        changed = False
        if ident.endswith('_sparse'):
            import importlib.util
            spec=importlib.util.spec_from_file_location('rounded_sparse_hair',HAIR)
            hair_author=importlib.util.module_from_spec(spec);spec.loader.exec_module(hair_author)
            bang = re.fullmatch(r'bang_(?:tip_)?(\d+)', n)
            if bang and int(bang[1]) not in recipe['keep_bangs']:
                continue
            if n.startswith('bang_tip_') or n=='centre_bang_tip':continue
            if n in ('hair_cap', 'back_hair', 'back_hair_lower') or n.startswith('rear_side_hair'):
                continue
            if SPECIES=='reimu' and n.startswith(('tied_lock_', 'cuff', 'lock_tip_', 'side_hair_')):
                if n.startswith(('cuff_fold_', 'side_hair_')):
                    continue
                lane=-1 if n.endswith('_-1') else 1
                top,bottom,width=(8.55,4.45,.50) if n.startswith('tied_lock_') else (4.55,3.35,.74) if n.startswith('cuff_') else (3.4,2.7,.40)
                if n.startswith('cuff_top_'):top,bottom,width=4.7,4.53,.82
                if n.startswith('cuff_hem_'):top,bottom,width=3.36,3.18,.72
                e=hair_author.ribbon(e,body,bb['resolution']['width'],top,bottom,lane,side=True,width=width)
                group='body_stretch';pairs.append((e,group,True));continue
            hair = bool(bang) or n.startswith(('back_lock_', 'side_lock_', 'side_tip_', 'side_ribbon_', 'braid_lock_', 'side_hair_loose'))
            if hair:
                if bang:
                    lane={1:-.52,3:0,5:.52}[int(bang[1])]
                    e=hair_author.ribbon(e,body,bb['resolution']['width'],9.15,7.05 if lane else 6.75,lane)
                    group='body_stretch';pairs.append((e,group,True));continue
                if n.startswith(('braid_lock_','side_hair_loose')):
                    # Single-braid Marisa: only the recipe's strand, tie, tip and loose strand stay.
                    rule=read(PACKAGE/'recipe.json').get('sparse_side_hair',{}).get(n)
                    if not rule:continue
                    points=list(e['vertices'].values()) if e.get('type')=='mesh' else [e['from'],e['to']]
                    lane=1 if sum(q[0] for q in points)>0 else -1
                    top,bottom,width=rule
                    e=hair_author.ribbon(e,body,bb['resolution']['width'],top,bottom,lane,side=True,width=width)
                    group='body_stretch';pairs.append((e,group,True));continue
                if n.startswith(('side_lock_','side_tip_','side_ribbon_')):
                    lane=-1 if n.endswith('_-1') else 1
                    top,bottom,width=(8.55,6.05,.42) if n.startswith('side_lock_') else (6.04,5.65,.36) if n.startswith('side_tip_') else (6.28,6.10,.50)
                    e=hair_author.ribbon(e,body,bb['resolution']['width'],top,bottom,lane,side=True,width=width)
                    group='body_stretch';pairs.append((e,group,True));continue
                points = list(e['vertices'].values()) if e.get('type') == 'mesh' else [e['from'], e['to']]
                cx = (min(p[0] for p in points)+max(p[0] for p in points))/2
                root = 9.328
                def thin(p):
                    x,y,z = p
                    y = root+(y-root)*recipe['hair_length']
                    x = cx+(x-cx)*recipe['hair_width']
                    return [x,y,z]
                transform(e, thin)
                # The narrow back tufts follow the new rear envelope and its bend.
                if n.startswith('back_lock_'):
                    values=body.vertices(e,bb['resolution']['width'])
                    z0,z1=min(values[2::8]),max(values[2::8])
                    points=[]
                    for i in range(0,len(values),8):
                        x,y,z=values[i:i+3];x*=.83
                        rx,rz,_=body.profile_at(y)
                        outline=[(0,rz),(.35*rx,rz),(.70*rx,.91*rz),(.94*rx,.60*rz),(rx,0)]
                        rear=0
                        for a,b in zip(outline,outline[1:]):
                            if abs(x)<=b[0]:
                                q=max(0,(abs(x)-a[0])/(b[0]-a[0]));rear=a[1]+q*(b[1]-a[1]);break
                        points.append([x,y,rear-.06+(z-z0)/(z1-z0)*.26])
                    triangles=[]
                    for i in range(0,len(points),3):
                        a,b,c=[values[j*8:j*8+3] for j in (i,i+1,i+2)]
                        u=[b[k]-a[k] for k in range(3)];v=[c[k]-a[k] for k in range(3)]
                        normal=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]]
                        triangles.append((i,i+2,i+1) if sum(normal[k]*values[i*8+5+k] for k in range(3))<0 else (i,i+1,i+2))
                    uv=[[values[j*8+3:j*8+5] for j in tri] for tri in triangles]
                    # mesh() expects pixel UVs; vertices() returned normalized UVs.
                    uv=[[[u*bb['resolution']['width'],v*bb['resolution']['height']] for u,v in tri] for tri in uv]
                    e={**body.mesh(n,points,triangles,uv),'uuid':e['uuid'],'bone':group}
                    e['body_follow'] = True
                changed = True
        elif n.startswith('face_') and (n.endswith('_left') or 'mouth' in n):
            points = list(e['vertices'].values())
            cx = (min(p[0] for p in points)+max(p[0] for p in points))/2
            cy = (min(p[1] for p in points)+max(p[1] for p in points))/2
            sx,sy = recipe['left_eye_scale'] if n.endswith('_left') else (1,1)
            dx,dy = recipe['left_eye_offset'] if n.endswith('_left') else recipe['mouth_shift']
            def skew(p):
                x = cx+(p[0]-cx)*sx+dx
                y = cy+(p[1]-cy)*sy+dy
                return [x,y,-body.profile_at(y)[2]-body.CFG['face_clearance']]
            transform(e, skew)
            for expression, entries in donor.get('expression_patches', {}).items():
                for patch in entries:
                    if donor['parts'][patch['index']]['name'] != n:
                        continue
                    values = copy.deepcopy(patch['vertices'])
                    for i in range(0,len(values),8):
                        values[i:i+3] = skew(values[i:i+3])
                    patches.setdefault(expression, []).append({'index':len(pairs),'vertices':values})
            changed = True
        pairs.append((e, group, changed))
    # Sparse hair leaves the scalp bare: seat the hat down onto the head as a separate
    # accessory, its brim just above the brows, instead of resting on the crown.
    seat=read(PACKAGE/'recipe.json').get('sparse_hat_seat') if SPECIES=='marisa' and ident.endswith('_sparse') else None
    hat_drop=0
    if seat:
        heights=lambda prefix:[y for e,_,_ in pairs if e['name'].startswith(prefix) for y in body.vertices(e,bb['resolution']['width'])[1::8]]
        hat_drop=round(max(heights('face_brow_'))+seat['brow_clearance']-min(heights('brim_')),4)
    HAT_DROPS[ident]=hat_drop
    if hat_drop:
        for index,(e,group,changed) in enumerate(pairs):
            if e['name'].startswith(('hat_','brim_','bow_')):
                transform(e,lambda p:[p[0],p[1]+hat_drop,p[2]])
                if 'origin' in e:e['origin'][1]+=hat_drop
                pairs[index]=(e,group,True)
        for bone in rig['bones']:
            if bone['name'] in ('hat_follow','hat','bow'):bone['pivot'][1]+=hat_drop
    if SPECIES=='reimu' and ident.endswith('_sparse'):
        offset=read(PACKAGE/'recipe.json')['sparse_bow_offset']
        for index,(e,group,changed) in enumerate(pairs):
            if e['name'].startswith('bow_'):
                def fit_bow(p):
                    x,y,z=[p[k]+offset[k] for k in range(3)]
                    if e['name'].startswith('bow_tail_'):
                        rx,rz,_=body.profile_at(y)
                        rear=rz*(1-.09*min(1,abs(x)/max(rx,.001)))
                        z=max(z,rear+.05)
                    return [x,y,z]
                transform(e,fit_bow)
                if 'origin' in e:e['origin']=[e['origin'][k]+offset[k] for k in range(3)]
                pairs[index]=(e,group,True)
        for bone in rig['bones']:
            if bone['name'].startswith('bow'):
                bone['pivot']=[bone['pivot'][k]+offset[k] for k in range(3)]
    flat, weights, parts = [], [], []
    for e, group, changed in pairs:
        p = oldparts[e['name']]
        if changed:
            values = body.vertices(e, bb['resolution']['width'])
            for i in range(0,len(values),24):
                a=[values[i+8+k]-values[i+k] for k in range(3)];b=[values[i+16+k]-values[i+k] for k in range(3)]
                normal=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
                if sum(normal[k]*values[i+5+k] for k in range(3))<0:
                    values[i+8:i+24]=values[i+16:i+24]+values[i+8:i+16]
            ww = [body.continuous_binding(values[i+1], names) if e.get('body_follow') or e['name'].startswith('face_')
                  else [names[group],names[group],0] for i in range(0,len(values),8)]
        else:
            values = donor['vertices'][p['start']*8:(p['start']+p['count'])*8]
            ww = donor['weights'][p['start']:p['start']+p['count']]
        parts.append(dict(p,start=len(weights),count=len(ww)))
        flat.extend(round(v,7) for v in values)
        weights.extend(ww)
    kept = {e['uuid'] for e,_,_ in pairs}
    def prune(nodes):
        return [dict(n,children=prune(n['children'])) if isinstance(n,dict) else n
                for n in nodes if isinstance(n,dict) or n in kept]
    bb.update(name=ident,elements=[p[0] for p in pairs],outliner=prune(bb['outliner']))
    nodes={}
    def collect(tree):
        for node in tree:
            if isinstance(node,dict):
                nodes[node['name']]=node
                node['children']=[n for n in node['children'] if isinstance(n,dict)]
                collect(node['children'])
    collect(bb['outliner'])
    for bone in rig['bones']:nodes[bone['name']]['origin']=bone['pivot']
    for e,group,_ in pairs:nodes[group]['children'].append(e['uuid'])
    rig.update(groups=[p[1] for p in pairs],element_vertex_counts=[p['count'] for p in parts],vertex_weights=weights)
    for clip in rig['clips'].values():
        for value in clip['tracks'].get('world_motion',{}).get('position',{}).values():
            value[0]=value[2]=0
    for animation in bb.get('animations',[]):
        animation['name']=animation['name'].replace('animation.'+SPECIES+'.','animation.'+ident+'.')
        for animator in animation.get('animators',{}).values():
            if animator['name']=='world_motion':
                for key in animator['keyframes']:
                    if key['channel']=='position':
                        for point in key['data_points']:point['x']=point['z']='0'
    for texture in bb['textures']:
        texture['name']=texture['path']=ident+'.png'
    if ident.endswith('_face'):
        altered={p['index'] for ps in patches.values() for p in ps}
        for ex,ps in donor.get('expression_patches',{}).items():
            patches.setdefault(ex,[]).extend(copy.deepcopy(p) for p in ps if p['index'] not in altered)
    model = dict(donor,vertices=flat,weights=weights,parts=parts,expression_patches=patches if ident.endswith('_face') else donor.get('expression_patches',{}))
    # Sparse removal shifts part indices, including any unaffected facial patches.
    if ident.endswith('_sparse'):
        mapping={p['name']:i for i,p in enumerate(parts)}
        model['expression_patches']={ex:[dict(p,index=mapping[donor['parts'][p['index']]['name']]) for p in ps if donor['parts'][p['index']]['name'] in mapping] for ex,ps in model['expression_patches'].items()}
    model = runtime.compile_model(model,rig,ident)
    model['revision'] = REVISION
    folder = OWN/'assembled'/ident
    save(folder/f'{ident}.bbmodel',bb)
    save(folder/'animation-rig.json',rig)
    save(folder/'runtime.model.json',model)
    return model,rig,folder


def main():
    freeze()
    # Rounded Marisa uses a 256px atlas; the old deficient selection used 128px.
    # Freeze the matching atlas inputs instead of reusing the old normalized UVs.
    frozen=PACKAGE/'inputs/standards/yukkuri-library/assembled'/SPECIES/'textures'
    expressions=read(PACKAGE/'recipe.json')['runtime_expressions']
    input_manifest=read(PACKAGE/'inputs/manifest.json')
    for expression in expressions:
        source=LIB/'assembled'/SPECIES/'textures'/f'{expression}.png'
        target=frozen/source.name
        if not target.exists():
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
            input_manifest['files'][rel(source)]=sha(source)
    save(PACKAGE/'inputs/manifest.json',input_manifest,True)
    records={}
    manifest=read(SELECTED/'manifest.json')
    presentation=read(ASSETS/'yukkuri_presentation/subspecies.json')
    metadata=read(ASSETS/'yukkuri_presentation/body_geometry.json')
    attachments=read(ROOT/'art/source/rounded-runtime/attachments.json')
    index=read(ASSETS/'yukkuri_props/rounded-index.json')
    base_mount=copy.deepcopy(attachments['badge_mounts']['variants'][SPECIES])
    for ident in IDS:
        model,rig,folder=author(ident)
        shutil.copyfile(frozen/'neutral.png',folder/f'{ident}.png')
        for name in (f'{ident}.bbmodel','animation-rig.json','runtime.model.json'):
            shutil.copyfile(folder/name,SELECTED/ident/name)
        path=ASSETS/'yukkuri'/f'{ident}.json'
        save(path,model)
        presentation[ident]=runtime.presentation(ident,model,presentation[ident])
        metadata['variants'][ident]=copy.deepcopy(metadata['variants'][SPECIES])
        # These hats are identical to rounded Marisa; texture and model IDs differ.
        prop={'format':1,'units_per_block':16,'vertices':[],'parts':[]}
        for p in model['parts']:
            if not p['name'].startswith(('hat_','brim_','bow_')):continue
            prop['parts'].append(dict(name=p['name'],start=len(prop['vertices'])//8,count=p['count'],texture=f'mcyukkuri:textures/entity/{ident}/neutral.png'))
            prop['vertices'].extend(model['vertices'][p['start']*8:(p['start']+p['count'])*8])
        prop['bounds']=[[min(prop['vertices'][k::8]),max(prop['vertices'][k::8])] for k in range(3)]
        save(ASSETS/'yukkuri_props'/f'accessory_{ident}.json',prop)
        index[f'accessory_{ident}']={'source_model':ident}
        mount=copy.deepcopy(base_mount)
        mount['point'][1]+=HAT_DROPS.get(ident,0)
        if SPECIES=='reimu' and ident.endswith('_sparse'):
            offset=read(PACKAGE/'recipe.json')['sparse_bow_offset']
            mount['point']=[mount['point'][k]+offset[k] for k in range(3)]
        attachments['badge_mounts']['variants'][ident]=copy.deepcopy(mount)
        attachments['badge_mounts']['detached'][f'accessory_{ident}']=copy.deepcopy(mount)
        attachments['detached'][f'accessory_{ident}']={'source_model':ident}
        attachments['sources'][rel(path)]=sha(path)
        record=copy.deepcopy(manifest['models'][ident])
        texture_records={}
        for expression in expressions:
            source=frozen/f'{expression}.png'
            snapshot=SELECTED/ident/'textures'/source.name
            target=ASSETS/'textures/entity'/ident/source.name
            shutil.copyfile(source,snapshot);shutil.copyfile(source,target)
            texture_records[source.stem]={'source':rel(source),'sha256':sha(source),'source_sha256':sha(source),
                'snapshot':rel(snapshot),'runtime':rel(target),'width':256,'height':256}
        record.update(selected_model=rel(folder/f'{ident}.bbmodel'),selected_model_sha256=sha(folder/f'{ident}.bbmodel'),
                      selected_rig=rel(folder/'animation-rig.json'),selected_rig_sha256=sha(folder/'animation-rig.json'),
                      snapshot=rel(SELECTED/ident/f'{ident}.bbmodel'),rig=rel(SELECTED/ident/'animation-rig.json'),
                      runtime=rel(path),runtime_sha256=sha(path),clips=list(model['clips']),bone_count=len(model['bones']),
                      revision=REVISION,shape_status='pending_user_confirmation',
                      runtime_acceptance='current offline verification; Minecraft acceptance pending',
                      recipe=rel(PACKAGE/'recipe.json'),exporter=rel(EXPORTER),textures=texture_records)
        record.pop('motion_contact',None)
        record['motion_source']=rel(PACKAGE/'inputs/standards/yukkuri-library/assembled'/SPECIES/'animation-rig.json')
        record['motion_source_sha256']=sha(ROOT/record['motion_source'])
        records[ident]=record
        old=read_gzip(PACKAGE/'inputs/src/main/resources/assets/mcyukkuri/yukkuri_wounds'/f'{ident}.json.gz')
        points=[v for i,v in enumerate(old['points'][:old['skinCount']*5]) if i%5<3]
        migration={'source_hash':old['runtimeSourceHash'],'points':points,'bounds':[min(points[k::3]) for k in range(3)]+[max(points[k::3]) for k in range(3)]}
        target=ASSETS/'yukkuri_wounds/legacy'/f'{ident}.json.gz'
        target.write_bytes(gzip.compress(json.dumps(migration,separators=(',',':')).encode(),mtime=0))
        print(ident,len(model['weights']),'vertices',len(model['clips']),'clips')
    save(ASSETS/'yukkuri_presentation/subspecies.json',presentation,True)
    save(ASSETS/'yukkuri_presentation/body_geometry.json',metadata,True)
    save(ROOT/'art/source/rounded-runtime/attachments.json',attachments,True)
    save(ASSETS/'yukkuri_props/rounded-index.json',index,True)
    manifest['models'].update(records);save(SELECTED/'manifest.json',manifest,True)
    ledger=read(ROOT/'art/runtime-sources.json');ledger['models'].update(records);save(ROOT/'art/runtime-sources.json',ledger,True)
    code="const fs=require('fs'),zlib=require('zlib'),crypto=require('crypto');const {roundedSurface}=require('./scripts/rounded_wound_surface.cjs');const root='src/main/resources/assets/mcyukkuri/';const pres=JSON.parse(fs.readFileSync(root+'yukkuri_presentation/subspecies.json'));for(const id of ['marisa_sparse','marisa_face']){const bytes=fs.readFileSync(root+'yukkuri/'+id+'.json');const surface=roundedSurface(JSON.parse(bytes),crypto.createHash('sha256').update(bytes).digest('hex'),pres[id]);fs.writeFileSync(root+'yukkuri_wounds/'+id+'.json.gz',zlib.gzipSync(JSON.stringify(surface),{level:9}));}"
    code=code.replace("['marisa_sparse','marisa_face']",json.dumps(IDS))
    subprocess.run(['node','-e',code],cwd=ROOT,check=True)
    import export_badges
    export_badges.main(mounts_only=True)
    save(PACKAGE/'manifest.json',{'status':'pending_user_confirmation','exporter':rel(EXPORTER),'exporter_sha256':sha(EXPORTER),
         'models':records,'wounds':{i:sha(ASSETS/'yukkuri_wounds'/f'{i}.json.gz') for i in IDS}},True)


def read_gzip(path):
    return json.loads(gzip.decompress(path.read_bytes()))


if __name__=='__main__':
    main()
