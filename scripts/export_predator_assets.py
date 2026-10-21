"""Export the two selected predator models without re-exporting other species."""
from pathlib import Path
import base64
import copy
import json
import shutil
import sys

ROOT=Path(__file__).resolve().parents[1]
LIB=ROOT/'standards/yukkuri-library'
SOURCE=LIB/'subspecies/predator'
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
OUT=ROOT/'art/source/standard-runtime'
PREVIEW=ROOT/'art/source/predator-runtime'
sys.path[:0]=[str(SOURCE),str(LIB/'ecology/house-yukkuri'),str(LIB)]
from author_species import read,save,sha,weights_for
from mesh_geometry import vertices


def relative(path):return path.relative_to(ROOT).as_posix()


def export(ident):
    source=SOURCE/'assembled'/ident
    bbpath=source/(ident+'.bbmodel');rigpath=source/'animation-rig.json'
    bb=read(bbpath);rig=copy.deepcopy(read(rigpath))
    names={b['name']:i for i,b in enumerate(rig['bones'])}
    flat=[];weights=[];parts=[]
    for e,group in zip(bb['elements'],rig['groups']):
        vv=vertices(e,256);start=len(weights)
        for i in range(0,len(vv),24):
            a=[vv[i+8+k]-vv[i+k] for k in range(3)];b=[vv[i+16+k]-vv[i+k] for k in range(3)]
            n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
            if sum(n[k]*vv[i+5+k] for k in range(3))<0:vv[i+8:i+24]=vv[i+16:i+24]+vv[i+8:i+16]
        weights.extend(weights_for(e,group,names,vv))
        flat.extend(round(v,7) for v in vv)
        parts.append(dict(name=e['name'],start=start,count=len(vv)//8,anatomy=group.removeprefix('anatomy_') if group.startswith('anatomy_') else ''))
    clips={}
    for name,clip in rig['clips'].items():
        clips[name]=dict(duration=clip['duration'],loop=clip['loop'],tracks=[dict(bone=names[bone],channel=channel,
            keys=[[float(t),*value] for t,value in sorted(keys.items(),key=lambda pair:float(pair[0]))])
            for bone,channels in clip['tracks'].items() for channel,keys in channels.items()])
    behavior={k:clips.pop(k) for k in list(clips) if k.startswith('predator_')}
    model=dict(format=1,id=ident,units_per_block=16,house=False,texture_size=256,
               vertices=flat,weights=weights,parts=parts,bones=[dict(name=b['name'],parent=-1 if b['parent'] is None else names[b['parent']],pivot=b['pivot']) for b in rig['bones']],
               clips=clips,behavior_clips=behavior)
    target=OUT/ident;target.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(bbpath,target/(ident+'.bbmodel'))
    rig['vertex_weights']=weights;rig['binding_mode']='two_bone_linear_skinning'
    save(target/'animation-rig.json',rig);save(target/'runtime.model.json',model)
    runtime=ASSETS/'yukkuri'/(ident+'.json');save(runtime,model)
    textures={};records={}
    expressions=read(LIB/'ecology/house-yukkuri/config.json')['expressions']+['biting']
    for expression in expressions:
        original=source/'textures'/(expression+'.png');output=ASSETS/'textures/entity'/ident/(expression+'.png')
        output.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(original,output)
        (target/'textures').mkdir(exist_ok=True);shutil.copyfile(original,target/'textures'/(expression+'.png'))
        textures[expression]='data:image/png;base64,'+base64.b64encode(original.read_bytes()).decode()
        records[expression]=dict(source=relative(original),runtime=relative(output),sha256=sha(output))
    record=dict(selected_model=relative(bbpath),selected_model_sha256=sha(bbpath),selected_rig=relative(rigpath),selected_rig_sha256=sha(rigpath),
                standard_motion_source=relative(rigpath),standard_motion_sha256=sha(rigpath),snapshot=relative(target/(ident+'.bbmodel')),
                rig=relative(target/'animation-rig.json'),runtime=relative(runtime),runtime_sha256=sha(runtime),geometry_identifier=ident,
                bone_count=len(model['bones']),clips=list(clips),behavior_clips=list(behavior),textures=records,shape_status='pending_confirmation',
                game_acceptance='not_executed',retargeting=dict(body='shared profile with continuous two-bone binding',wings='one articulated hinge per complete wing'))
    return model,textures,record


def main():
    catalog={};records={}
    for ident in ('remilia','flandre'):
        model,textures,record=export(ident);catalog[ident]=dict(model=model,textures=textures);records[ident]=record
        print(ident,len(model['weights']),'vertices',len(model['clips']),'base clips',len(model['behavior_clips']),'flight clips')
    PREVIEW.mkdir(parents=True,exist_ok=True)
    save(PREVIEW/'preview-data.json',catalog)
    save(PREVIEW/'provenance.json',dict(exporter=relative(Path(__file__)),exporter_sha256=sha(Path(__file__)),entities=records),True)
    ledger=ROOT/'art/runtime-sources.json';data=read(ledger)
    data['models']={**data.get('models',{}),**records};data['predator_preview']=relative(PREVIEW/'preview.html')
    save(ledger,data,True)


if __name__=='__main__':main()
