"""Shop toys and small care goods as vanilla-style prop items; editable source, not approved standards.

Block style (block-props/toys.py): yarn ball, glass marble, pretty stone, Reimu and Marisa dolls. Extruded inventory icons
(block-props/kit.py extrude): teaching whistle (standing plate), wash cloth and companion cord (the plate laid flat on the
ground, icon up; every view but the dropped one turns it upright again, so hands, mouths and frames show the icon).
No original game pixels. 16 units = one block. --check verifies editable, selected and packaged exports.
"""
import argparse, importlib.util, json, math, sys
from pathlib import Path

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts'))
from asset_export_checks import check_output, fingerprints, preserve_equivalent_png, provenance
if 'block_props_kit' not in sys.modules:   # loaded under its own name: care-props also has a `kit`
    _spec=importlib.util.spec_from_file_location('block_props_kit',HERE.parent/'block-props/kit.py')
    sys.modules['block_props_kit']=_module=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(_module)
kit=sys.modules['block_props_kit']
sys.path.insert(0,str(HERE.parent/'block-props'))
import toys
ICONS=ROOT/'art/props/item-icons'
# Extruded icons: the height in units of the icon's opaque rows, and whether the plate lies flat.
EXTRUDED={'wash_cloth':(4.3,True),'teaching_whistle':(3.4,False),'companion_cord':(6.8,True)}
TOYS=('glass_marble','reimu_doll','marisa_doll','pretty_stone')   # also kept editable in assembled/, crafted by recipe
SNAPSHOT=ROOT/'art/props/care-expansion'
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
DATA=ROOT/'src/main/resources/data/mcyukkuri'
INGREDIENTS={'glass_marble':['glass','red_dye'],
 'reimu_doll':['white_wool','black_wool','red_dye'],'marisa_doll':['white_wool','black_wool','yellow_dye'],
 'pretty_stone':['cobblestone','amethyst_shard']}
LABELS={'glass_marble':['玻璃弹珠','Glass marble'],'reimu_doll':['灵梦布偶','Reimu doll'],'marisa_doll':['魔理沙布偶','Marisa doll'],
 'pretty_stone':['漂亮石子','Pretty stone']}

def lay_flat(mesh,bbmodel):
    """Turn a standing extruded plate (front -Z) a quarter turn about X onto the ground: front up, icon top toward +Z."""
    rows=mesh['vertices'];thick=max(rows[2::8])-min(rows[2::8]);height=max(rows[1::8])
    place=lambda p:[p[0],round(thick/2-p[2],6)+0.0,round(p[1]-height/2,6)+0.0]
    for i in range(0,len(rows),8):
        rows[i:i+3]=place(rows[i:i+3]);rows[i+5:i+8]=[rows[i+5],-rows[i+7]+0.0,rows[i+6]]
    mesh['bounds']=[[min(rows[k::8]),max(rows[k::8])] for k in range(3)]
    for element in bbmodel['elements']:element['vertices']={k:place(p) for k,p in element['vertices'].items()}

def _matrix(rotation):
    """Minecraft display rotation (degrees) as the matrix Rx * Ry * Rz."""
    (sa,ca),(sb,cb),(sc,cc)=((math.sin(math.radians(v)),math.cos(math.radians(v))) for v in rotation)
    return [[cb*cc,-cb*sc,sb],[sa*sb*cc+ca*sc,-sa*sb*sc+ca*cc,-sa*cb],[-ca*sb*cc+sa*sc,ca*sb*sc+sa*cc,ca*cb]]

def upright(model):
    """The item model of a flat plate: every display but the dropped one first stands the plate up again (a quarter
    turn back about X), then applies the kit's rotation for a standing plate."""
    stand=_matrix([-90,0,0])
    for context,entry in model['display'].items():
        if context=='ground':continue
        a=_matrix(entry.get('rotation',[0,0,0]));m=[[sum(a[i][k]*stand[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
        if abs(m[0][2])>.9999:raise ValueError('display rotation at the Euler pole')
        angles=[math.degrees(v) for v in (math.atan2(-m[1][2],m[2][2]),math.asin(m[0][2]),math.atan2(-m[0][1],m[0][0]))]
        entry['rotation']=[round(v,2)+0.0 for v in angles]
    return model

def outputs():
    previous=provenance(SNAPSHOT/'manifest.json')
    result={};index={};icons={}
    def emit(path,data):result[path]=(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode()
    props={}
    for name,design in toys.PROPS.items():
        prop=design()
        props[name]={'style':'block','source':'standards/yukkuri-library/drafts/block-props/toys.py'}
        index[name]=(name,prop.mesh(),prop.bbmodel(),prop.png(),kit.prop_item_model(name))
    for name,(height,flat) in EXTRUDED.items():
        icons[ICONS/f'{name}.png']=icon=(ICONS/f'{name}.png').read_bytes()
        mesh,bb,png=kit.extrude(name,icon,height)
        model=kit.prop_item_model(name,extruded=True)
        if flat:lay_flat(mesh,bb);upright(model)
        props[name]={'style':'extruded_icon','icon':f'art/props/item-icons/{name}.png','height':height,'flat':flat}
        index[name]=(name,mesh,bb,png,model)
    emit(HERE/'recipe.json',{'status':'pending-review','units_per_block':16,'props':props,'recipes':INGREDIENTS})
    catalog={}
    for name,mesh,bb,png,model in index.values():
        for folder in (SNAPSHOT/'selected'/name,*([HERE/'assembled'/name] if name in TOYS else [])):
            emit(folder/f'{name}.mesh.json',mesh);emit(folder/f'{name}.bbmodel',bb)
            result[folder/'texture.png']=preserve_equivalent_png(folder/'texture.png',png)
        emit(ASSETS/f'yukkuri_props/{name}.json',mesh)
        result[ASSETS/f'textures/props/{name}.png']=result[SNAPSHOT/'selected'/name/'texture.png']
        emit(ASSETS/f'models/item/{name}.json',model)
        if name in INGREDIENTS:
            emit(DATA/f'recipe/{name}.json',{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':'minecraft:'+x} for x in INGREDIENTS[name]],'result':{'id':'mcyukkuri:'+name,'count':1}})
        catalog[name]={'bounds':mesh['bounds'],'triangles':len(mesh['vertices'])//24}
    emit(ASSETS/'yukkuri_props/facilities-index.json',catalog)
    language={}
    for i,locale in enumerate(('zh_cn','en_us')):
        path=ASSETS/f'lang/{locale}.json';existing=json.loads(path.read_text(encoding='utf8'))
        strings={'item.mcyukkuri.'+name:labels[i] for name,labels in LABELS.items()}
        language[locale]=strings;changed=any(existing.get(k)!=v for k,v in strings.items());existing.update(strings)
        result[path]=(json.dumps(existing,ensure_ascii=False,indent=2)+'\n').encode() if changed else path.read_bytes()
    sources={p:p.read_bytes() for p in (Path(__file__).resolve(),HERE.parent/'block-props/kit.py',HERE.parent/'block-props/toys.py',HERE.parent/'item-icons/draw.py')}
    emit(SNAPSHOT/'manifest.json',{'status':'pending-review-game-adaptation','source':str(HERE.relative_to(ROOT)).replace('\\','/')+'/build.py','language_keys':language,'inputs':fingerprints(ROOT,{**sources,**icons},previous.get('inputs',{})),'files':fingerprints(ROOT,{p:v for p,v in result.items() if p.parent.name!='lang'},previous.get('files',{}))})
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args();changed=[]
    for path,data in outputs().items():
        if not check_output(path,data):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    if args.check and changed:raise SystemExit('Stale exports: '+', '.join(changed))
    print(f'Care expansion: {len(toys.PROPS)+len(EXTRUDED)} models; {len(changed)} files '+('differ' if args.check else 'written'))
