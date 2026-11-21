"""Shop toys and small care goods as vanilla-style items; editable source, not approved standards.

Toys have world models, because they are dropped, pushed, rolled and played with on the ground: yarn ball, glass marble,
Reimu and Marisa dolls are round props (care-props/toys.py) drawn by client/PropItemRenderer and, dropped,
by HouseScrapRenderer. The small handheld care tools (wash cloth, companion cord) have no world model:
they are vanilla item/generated models of their inventory icons, the way vanilla draws a held or dropped item.
No original game pixels. 16 units = one block. --check verifies editable, selected and packaged exports.
"""
import argparse, importlib.util, json, sys
from pathlib import Path

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts'))
from asset_export_checks import check_output, fingerprints, preserve_equivalent_png, provenance
if 'block_props_kit' not in sys.modules:   # loaded under its own name: care-props also has a `kit`
    _spec=importlib.util.spec_from_file_location('block_props_kit',HERE.parent/'block-props/kit.py')
    sys.modules['block_props_kit']=_module=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(_module)
block_kit=sys.modules['block_props_kit']   # the prop item model (builtin/entity) shared by every prop item
sys.path.insert(0,str(HERE.parent/'care-props'))
import toys
ITEMS=('wash_cloth','companion_cord')   # vanilla item models of their icons (textures/item/<id>.png)
TOYS=('glass_marble','reimu_doll','marisa_doll')   # also kept editable in assembled/, crafted by recipe
SNAPSHOT=ROOT/'art/props/care-expansion'
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
DATA=ROOT/'src/main/resources/data/mcyukkuri'
INGREDIENTS={'glass_marble':['glass','red_dye'],
 'reimu_doll':['white_wool','black_wool','red_dye'],'marisa_doll':['white_wool','black_wool','yellow_dye']}
LABELS={'glass_marble':['玻璃弹珠','Glass marble'],'reimu_doll':['灵梦布偶','Reimu doll'],'marisa_doll':['魔理沙布偶','Marisa doll']}

def outputs():
    previous=provenance(SNAPSHOT/'manifest.json')
    result={};index={}
    def emit(path,data):result[path]=(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode()
    props={}
    for name,design in toys.PROPS.items():
        prop=design()
        props[name]={'style':'round','source':'standards/yukkuri-library/drafts/care-props/toys.py'}
        index[name]=(name,prop.mesh(),prop.bbmodel(),prop.png(),block_kit.prop_item_model(name))
    for name in ITEMS:
        props[name]={'style':'vanilla_item','icon':f'src/main/resources/assets/mcyukkuri/textures/item/{name}.png'}
        emit(ASSETS/f'models/item/{name}.json',{'parent':'minecraft:item/generated','textures':{'layer0':f'mcyukkuri:item/{name}'}})
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
    sources={p:p.read_bytes() for p in (Path(__file__).resolve(),HERE.parent/'block-props/kit.py',HERE.parent/'care-props/toys.py',HERE.parent/'care-props/shapes.py',HERE.parent/'care-props/kit.py',HERE.parent/'item-icons/draw.py')}
    emit(SNAPSHOT/'manifest.json',{'status':'pending-review-game-adaptation','source':str(HERE.relative_to(ROOT)).replace('\\','/')+'/build.py','language_keys':language,'inputs':fingerprints(ROOT,sources,previous.get('inputs',{})),'files':fingerprints(ROOT,{p:v for p,v in result.items() if p.parent.name!='lang'},previous.get('files',{}))})
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args();changed=[]
    for path,data in outputs().items():
        if not check_output(path,data):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    if args.check and changed:raise SystemExit('Stale exports: '+', '.join(changed))
    print(f'Care expansion: {len(toys.PROPS)} models, {len(ITEMS)} item models; {len(changed)} files '+('differ' if args.check else 'written'))
