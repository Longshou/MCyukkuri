"""Rebuild only the two bean selections, preserving the other subspecies."""
import copy
import json
import os
import export_subspecies as legacy


def main():
    records={}
    for base in ('reimu','marisa'):
        ident=base+'_bean'
        selection=legacy.author(base,'bean')
        model,record=legacy.export(ident,base,selection,False,preserve_textures=True)
        bb,rig,bp,rp,textures=selection
        rig['element_vertex_counts']=[p['count'] for p in model['parts']]
        rig['vertex_weights']=copy.deepcopy(model['weights'])
        rig['binding_mode']='two_bone_linear_skinning'
        rig['clips']={}
        for name,clip in model['clips'].items():
            channels={}
            for track in clip['tracks']:
                bone=model['bones'][track['bone']]['name']
                channels.setdefault(bone,{})[track['channel']]={str(k[0]):k[1:] for k in track['keys']}
            rig['clips'][name]={'duration':clip['duration'],'loop':clip['loop'],'tracks':channels}
        legacy.save(rp,rig);legacy.save(legacy.OUT/ident/'animation-rig.json',rig)
        record.update(selected_rig_sha256=legacy.sha(rp),rig=legacy.rel(legacy.OUT/ident/'animation-rig.json'),
                      hair_revision='bean-profile-fit',author=legacy.rel(legacy.OWN/'bean_hair.py'),
                      author_sha256=legacy.sha(legacy.OWN/'bean_hair.py'),
                      face_revision='bean-skin-anchor-motion',face_author=legacy.rel(legacy.OWN/'bean_face.py'),
                      face_author_sha256=legacy.sha(legacy.OWN/'bean_face.py'))
        records[ident]=record
        print(ident,len(model['weights']),'vertices')
    manifest=legacy.read(legacy.OUT/'manifest.json');manifest['models'].update(records)
    legacy.save(legacy.OUT/'manifest.json',manifest)
    ledger=legacy.ROOT/'art/runtime-sources.json';data=legacy.read(ledger)
    data['models'].update(records)
    # Replace a completed file on the same volume so readers never see a truncated ledger.
    pending=legacy.ROOT/'.local/bean-hair-export/runtime-sources.json'
    pending.parent.mkdir(parents=True,exist_ok=True)
    pending.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8');os.replace(pending,ledger)
    legacy.save(legacy.OWN/'hair-fit-manifest.json',{'status':'pending_user_confirmation','models':records,
        'recipe_sha256':legacy.sha(legacy.OWN/'recipes.json'),'exporter_sha256':legacy.sha(legacy.ROOT/'scripts/export_bean_assets.py')})


if __name__=='__main__':main()
