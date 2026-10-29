"""Resolve the actual living/subspecies model registry into explicit art receivers."""
import json
import re
from pathlib import Path
import numpy as np
from PIL import Image

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[4]
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
LABELS=dict(reimu='灵梦',marisa='魔理沙',house_reimu='家灵梦',house_marisa='家魔理沙',chen='橙',alice='爱丽丝',patchouli='帕秋莉',remilia='蕾米莉亚',flandre='芙兰',myon='妖梦',reimu_wasa='多毛灵梦',reimu_deibu='灵胖',reimu_wasa_deibu='多毛灵胖',reimu_sparse='稀发灵梦',reimu_face='面部不足灵梦',reimu_bean='豆形灵梦',marisa_sparse='稀发魔理沙',marisa_face='面部不足魔理沙',marisa_bean='豆形魔理沙',marisa_tsumuri='螺壳魔理沙',marisa_kotatsu='被炉魔理沙',chen_longtail='长尾橙',chen_roundtail='圆尾橙',myon_redeye='红眼妖梦')

def body_bounds(model):
    part=next(p for p in model['parts'] if p['name'].endswith('body_surface'))
    xyz=np.asarray(model['vertices']).reshape(-1,8)[part['start']:part['start']+part['count'],:3]
    return part['name'],xyz.min(axis=0),xyz.max(axis=0)

def filling_palette(name,palettes):
    # Species, not the donor geometry, determines the filling. Myon reuses some
    # Alice topology but has its own adopted white-chocolate filling.
    if name.startswith('myon'):result='white_chocolate'
    elif name in ('remilia','flandre'):result='meat_bun'
    elif name=='alice':result='custard'
    elif name.startswith('chen'):result='chocolate'
    elif name.startswith(('reimu','marisa','house_reimu','house_marisa')):result='red_bean'
    else:result='generic'
    if result not in palettes:raise ValueError(f'Missing filling palette {result} for {name}')
    return result

def registry():
    living=json.loads((ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))['variants']
    variants={**living,**json.loads((ASSETS/'yukkuri_presentation/subspecies.json').read_text(encoding='utf-8'))}
    actual={p.stem for p in (ASSETS/'yukkuri').glob('*.json') if p.stem!='badge_mounts'}
    if set(variants)!=actual or actual!=set(LABELS):raise ValueError('New model needs explicit wound-art classification')
    base=json.loads((ASSETS/'yukkuri/reimu.json').read_text(encoding='utf-8'));_,low0,high0=body_bounds(base)
    original=json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))['models']
    palettes=json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))['filling_palettes']
    java=ROOT/'src/main/java/io/github/longshou/mcyukkuri'
    base_health=float(re.search(r'\.add\(Attributes\.MAX_HEALTH,\s*([\d.]+)',(java/'entity/YukkuriEntity.java').read_text(encoding='utf-8')).group(1))
    myon_health=float(re.search(r'\.add\(Attributes\.MAX_HEALTH,\s*([\d.]+)',(java/'rare/MyonLife.java').read_text(encoding='utf-8')).group(1))
    body_constants=(java/'body/BodyConstants.java').read_text(encoding='utf-8')
    house_health={stage.lower():float(re.search(r'HOUSE_'+stage+r'_HEALTH\s*=\s*([\d.]+)',body_constants).group(1)) for stage in ['BABY','JUVENILE','ADULT']}
    models={}
    for name,entry in variants.items():
        m=json.loads((ASSETS/f'yukkuri/{name}.json').read_text(encoding='utf-8'))
        body,low,high=body_bounds(m);scale=(high-low)/(high0-low0);offset=low-low0*scale
        texture=Image.open(ASSETS/f'textures/entity/{name}/neutral.png').convert('RGBA')
        hair=[];extra=[];ornaments=[];other=[]
        for p in m['parts']:
            n=p['name']
            if n==body or n.startswith('face_') or p.get('anatomy') or n.startswith(('mamu_','peni_')):continue
            if n.startswith(tuple(entry['accessory_prefixes'])) or any(s in n for s in ['cuff','ribbon','bow_','ponytail_tie','earring','crystal','bean_braid_tie','bean_braid_rim']):ornaments.append(n)
            elif n.startswith(('chen_ear_','chen_tail_','predator_membrane','predator_wing_','house_rump','rounded_rump','puff_','bean_rear')):extra.append(n)
            elif n.startswith(('crown','hair_','back_','bang_','centre_bang','rear_side','side_hair','tied_lock','lock_tip','braid_lock','wasa_','house_hair','house_temple','house_bang','alice_','chen_','patchouli_','predator_hair','predator_bang','predator_side_lock','predator_rear','predator_ponytail','bean_hair','bean_bang','bean_braid')):hair.append(n)
            else:other.append(n)
        if other:raise ValueError(f'Unclassified visible parts for {name}: {other}')
        # Existing two reviewed receivers keep their exact boundary registration.
        local=original.get(name)
        if local:hair=local['hair_parts'];extras=local['thermal_extra_hair_parts']+extra
        else:extras=extra
        colors=[];pixels=np.asarray(texture);v=np.asarray(m['vertices']).reshape(-1,8)
        for p in m['parts']:
            if p['name'] not in hair:continue
            uv=v[p['start']:p['start']+p['count'],3:5]
            xy=np.clip((uv*np.array(texture.size)).astype(int),0,np.array(texture.size)-1)
            col=pixels[xy[:,1],xy[:,0]];colors.extend(col[col[:,3]>128,:3].tolist())
        c=np.median(colors,axis=0) if colors else np.array([70,55,40])
        eyes=[]
        for p in m['parts']:
            if p['name'].startswith('face_eye_'):
                points=v[p['start']:p['start']+p['count']];points=points[points[:,7]<-.2]
                eyes.extend(points[:,1].tolist())
        skin_y=(float(np.mean(eyes))-offset[1])/scale[1]-4.5-.4
        hair_y=3.7 if name.endswith('_bean') else 2.4 if name.startswith('house_') else 2.3 if name in ['remilia','flandre'] else 1.15
        models[name]=dict(label=LABELS[name],filling_palette=filling_palette(name,palettes),body_part=body,hair_parts=hair,
            core_tunnel_parts=[n for n in hair if 'underlayer' in n],thermal_extra_hair_parts=extras,
            protected_parts=ornaments,hair_edge=local['hair_edge'] if local else np.clip(c*.7,0,255).astype(int).tolist(),
            hair_highlight=local['hair_highlight'] if local else np.clip(c*1.08+8,0,255).astype(int).tolist(),
            fit=dict(scale=scale.tolist(),offset=offset.tolist()),texture_size=texture.width,
            adult_only=name in ['reimu_deibu','reimu_wasa_deibu'],house=bool(m.get('house')),
            injury_scopes=['sole_burn','whole_body_burn'] if m.get('house') else ['mechanical','core_exposure','sole_burn','whole_body_burn'],
            thermal_grades=[3] if m.get('house') else [1,2,3],
            accessory_prefixes=entry['accessory_prefixes'],max_health=house_health['adult'] if m.get('house') else myon_health if name.startswith('myon') else base_health,
            stage_health=house_health if m.get('house') else None,
            thermal_accessories={n:('soot' if name=='marisa_tsumuri' and n.startswith(tuple(entry['accessory_prefixes'])) or 'crystal' in n or 'earring' in n else 'char') for n in ornaments},
            sample_sites={'hair':dict(az=1.7,y=hair_y),'skin':dict(az=.22,y=skin_y)},
            rigid_groups={n:('main_accessory' if n.startswith(tuple(entry['accessory_prefixes'])) else
                'crystal_'+re.search(r'(left|right)_\d+',n).group() if 'crystal' in n else
                'braid_tie' if 'braid' in n else 'earring' if 'earring' in n else
                'cuff_left' if '-1' in n else 'cuff_right' if 'cuff' in n else n) for n in ornaments})
    hat=json.loads((ASSETS/'yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))
    return dict(format='mcyukkuri.wound-species.v1',runtime_selected=False,models=models,
        aliases={'aquatic_marisa':dict(model='marisa',label='水生魔理沙',accessory_scale=hat['scale']['aquatic_marisa'],accessory_anchor=hat['anchor_worn'])},
        growth=json.loads((ASSETS/'yukkuri_presentation/family.json').read_text(encoding='utf-8'))['growth'])

if __name__=='__main__':
    (HERE/'species.json').write_text(json.dumps(registry(),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
