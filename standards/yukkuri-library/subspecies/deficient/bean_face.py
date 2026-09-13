"""Retarget bean facial anchors to the actual skin motion at their height."""
import copy


def fit(model, bb, rig, recipe):
    names={b['name']:i for i,b in enumerate(model['bones'])}
    for aid,anchor in enumerate(model['bones']):
        if not (anchor['name'].startswith('face_') and anchor['name'].endswith('_skin_anchor')):continue
        child=next((b for b in model['bones'] if b['parent']==aid),None)
        if child is None:continue
        q=max(0,min(31,child['pivot'][1]/(8.48*recipe['body_height'])*32-.5))
        lo=int(q);hi=min(31,lo+1);weight=q-lo
        low=names[f'soft_head_slice_{lo}_bend'];high=names[f'soft_head_slice_{hi}_bend']
        anchor['pivot']=[model['bones'][low]['pivot'][k]*(1-weight)+model['bones'][high]['pivot'][k]*weight for k in range(3)]
        # These anchors and the two sampled skin bones all share the head parent.
        assert anchor['parent']==model['bones'][low]['parent']==model['bones'][high]['parent']
        for clip in model['clips'].values():
            clip['tracks']=[t for t in clip['tracks'] if t['bone']!=aid]
            for channel in ('position','rotation','scale'):
                a=next((t for t in clip['tracks'] if t['bone']==low and t['channel']==channel),None)
                b=next((t for t in clip['tracks'] if t['bone']==high and t['channel']==channel),None)
                if not a and not b:continue
                a=a or b;b=b or a
                assert [k[0] for k in a['keys']]==[k[0] for k in b['keys']],channel
                keys=[[u[0],*[u[k]*(1-weight)+v[k]*weight for k in range(1,4)]] for u,v in zip(a['keys'],b['keys'])]
                clip['tracks'].append({'bone':aid,'channel':channel,'keys':keys})
    # Tiny surface clearance covers the difference between a flat expression card
    # and the skin's interpolation across its height, without visible floating.
    clearance=recipe['face_fit']['clearance']
    for p in model['parts']:
        if p['name'].startswith('face_'):
            for i in range(p['start'],p['start']+p['count']):model['vertices'][i*8+2]=round(model['vertices'][i*8+2]-clearance,7)
    for e in bb['elements']:
        if e['name'].startswith('face_'):
            for key in ('from','to','origin'):
                if key in e:e[key][2]=round(e[key][2]-clearance,7)
    for source,target in zip(rig['bones'],model['bones']):source['pivot']=copy.deepcopy(target['pivot'])
    def sync(nodes):
        for node in nodes:
            if isinstance(node,dict):
                if node.get('name') in names:node['origin']=copy.deepcopy(model['bones'][names[node['name']]]['pivot'])
                sync(node.get('children',[]))
    sync(bb['outliner'])
