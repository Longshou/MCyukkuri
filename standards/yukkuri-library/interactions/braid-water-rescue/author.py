"""Export the selected single-braid contact rig; this is resource production, not a game/check runner."""
from pathlib import Path
import hashlib, json, math, sys
import numpy as np

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts'))
import placement_fit


def export():
    recipe=json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))
    path=ROOT/f"src/main/resources/assets/mcyukkuri/yukkuri/{recipe['model']}.json"
    model=placement_fit.fitted(path,placement_fit.load())
    bones={b['name']:b for b in model['bones']}
    pivot=np.array(bones[recipe['root_bone']]['pivot']);base=np.array(bones[recipe['braid_bone']]['pivot']);joint=np.array(bones[recipe['tip_bone']]['pivot'])
    raw=np.array(model['vertices']).reshape(-1,8)
    part=next(p for p in model['parts'] if p['name']==recipe['contact_part'])
    tuft=raw[part['start']:part['start']+part['count'],:3]
    tip=tuft[np.argmax(np.linalg.norm(tuft-base,axis=1))]
    body=np.concatenate([raw[p['start']:p['start']+p['count'],:3] for p in model['parts'] if 'body_surface' in p['name']])
    angle=math.radians(recipe['root_pitch_degrees']);c,s=math.cos(angle),math.sin(angle)
    rotation=np.array([[1,0,0],[0,c,-s],[0,s,c]])
    lift=float(body[:,1].min()-((body-pivot)@rotation.T+pivot)[:,1].min())
    posed_body=(body-pivot)@rotation.T+pivot+np.array([0,lift,0])
    support=[]
    for i in range(65):
        a=angle*i/64;ca,sa=math.cos(a),math.sin(a)
        r=np.array([[1,0,0],[0,ca,-sa],[0,sa,ca]])
        support.append(float(body[:,1].min()-((body-pivot)@r.T+pivot)[:,1].min()))
    board_bounds=[]
    for pitch in range(-18,7):
        row=[]
        for roll in range(-3,4):
            a,b=math.radians(pitch),math.radians(roll);ca,sa,cb,sb=math.cos(a),math.sin(a),math.cos(b),math.sin(b)
            r=np.array([[cb,-sb,0],[sb,cb,0],[0,0,1]])@np.array([[1,0,0],[0,ca,-sa],[0,sa,ca]])
            posed=(body-pivot)@r.T+pivot
            row.append([posed.min(axis=0).tolist(),posed.max(axis=0).tolist()])
        board_bounds.append(row)
    # Standard adult mouth: front centre of the mouth decal, pushed in front of the face plane like the living.json anchor.
    mpart=next(p for p in model['parts'] if p['name']==recipe['mouth_part'])
    mv=raw[mpart['start']:mpart['start']+mpart['count'],:3]
    mouth_root=[round(float((mv[:,0].max()+mv[:,0].min())/2),4),round(float(mv[:,1].mean()),4),round(float(mv[:,2].min()-recipe['mouth_forward_units']),4)]
    living=json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/living.json').read_text(encoding='utf-8'))['variants'][recipe['model']]['mouth']
    assert max(abs(a-b) for a,b in zip(mouth_root,living))<.01,(mouth_root,living)
    # The reach lean (v5): the same root-bone pose the braid_reach_low clip plays, composed shift + Rz*Ry*Rx*S about the pivot.
    lean=recipe.get('reach_lean');lean_out={}
    if lean:
        assert math.hypot(lean['pitch'],lean['roll'])<=12+1e-9,'whole-body tilt over 12 degrees'
        x,y,z=map(math.radians,(lean['pitch'],lean['yaw'],lean['roll']))
        rx=np.array([[1,0,0],[0,math.cos(x),-math.sin(x)],[0,math.sin(x),math.cos(x)]])
        ry=np.array([[math.cos(y),0,math.sin(y)],[0,1,0],[-math.sin(y),0,math.cos(y)]])
        rz=np.array([[math.cos(z),-math.sin(z),0],[math.sin(z),math.cos(z),0],[0,0,1]])
        pose=lambda p:(p-pivot)*np.array(lean['scale'])@(rz@ry@rx).T+pivot+np.array(lean['shift'])
        # The leaning body would sink its low side into the deck: it is raised so its lowest body point stays on the floor.
        lean_lift=float(body[:,1].min()-pose(body)[:,1].min())
        lean_out={'reach_lean_lift':round(lean_lift,5),'reach_lean_root':[round(float(v),5) for v in pose(base)+np.array([0,round(lean_lift,5),0])]}
    vessel=json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))
    output={**recipe,'units_per_block':16,'bone':recipe['braid_bone'],'root_rotation':[recipe['root_pitch_degrees'],0,0],
            'grip_height':recipe['grip_height_ratio'],'mouth_root':mouth_root,'mouth_reach':recipe['mouth_reach_blocks'],'root_pivot':pivot.tolist(),'braid_root':base.tolist(),'braid_tip':tip.tolist(),'tip_joint':joint.tolist(),
            'braid_length':float(np.linalg.norm(tip-base)),'root_lift':lift,
            'braid_first_length':float(np.linalg.norm(joint-base)),'braid_second_length':float(np.linalg.norm(tip-joint)),
            'posed_body_min':posed_body.min(axis=0).tolist(),'posed_body_max':posed_body.max(axis=0).tolist(),
            'body_min':body.min(axis=0).tolist(),'body_max':body.max(axis=0).tolist(),
            'root_support_samples':support,
            'boarding_body_bounds':board_bounds,'boarding_body_pitch_min':-18,'boarding_body_pitch_max':6,'boarding_body_roll_min':-3,'boarding_body_roll_max':3,
            'hat_height_model_units':vessel['keel'],**lean_out,
            'source_model':path.relative_to(ROOT).as_posix(),'source_model_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'source_placement':placement_fit.RESOURCE.relative_to(ROOT).as_posix(),'source_placement_sha256':hashlib.sha256(placement_fit.RESOURCE.read_bytes()).hexdigest(),
            'source_recipe':'standards/yukkuri-library/interactions/braid-water-rescue/recipe.json',
            'source_recipe_sha256':hashlib.sha256((HERE/'recipe.json').read_bytes()).hexdigest()}
    target=ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json'
    target.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(target,flush=True)
    print('Selected braid root/tip, length, root support:',base.tolist(),tip.tolist(),output['braid_length'],lift,flush=True)


if __name__=='__main__':export()
