#!/usr/bin/env python3
"""Export editable native house/care draft geometry, recipes and their source fingerprints."""
import argparse
import json
from asset_export_checks import check_output, fingerprint, fingerprints, provenance
from export_peripheral_assets import owned_paths
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'art/props/house-care'
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
DATA=ROOT/'src/main/resources/data/mcyukkuri'
def outputs():
    recipe=json.loads((SOURCE/'recipe.json').read_text(encoding='utf-8'));result={}
    previous=provenance(SOURCE/'provenance.json')
    delegated=owned_paths()
    def emit(path,value):
        if path not in delegated:result[path]=(json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
    for name,model in recipe['blocks'].items():
        elements=[{'from':box[:3],'to':box[3:],'faces':{face:{'texture':'#base'} for face in ['north','south','east','west','up','down']}} for box in model['boxes']]
        emit(ASSETS/f'models/block/{name}.json',{'parent':'minecraft:block/block','textures':{'base':model['texture'],'particle':model['texture']},'elements':elements})
        emit(ASSETS/f'blockstates/{name}.json',{'variants':{'':{'model':f'mcyukkuri:block/{name}'}}})
    emit(ASSETS/'models/item/house_crevice.json',{'parent':'mcyukkuri:block/house_crevice'})
    for name,texture in recipe['items'].items():emit(ASSETS/f'models/item/{name}.json',{'parent':'minecraft:item/generated','textures':{'layer0':texture}})
    # Explicit renderer configurations are selected sources, not placeholder icons.
    selected={ROOT/path:(ROOT/path).read_bytes() for path in recipe.get('item_models',{}).values()}
    for name,path in recipe.get('item_models',{}).items():
        target=ASSETS/f'models/item/{name}.json'
        if name in recipe['items'] or target in delegated:
            raise ValueError(f'Conflicting item model owner: {name}')
        json.loads(selected[ROOT/path])
        result[target]=selected[ROOT/path]
    for name,details in recipe['recipes'].items():
        emit(DATA/f'recipe/{name}.json',{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':item} for item in details['ingredients']],'result':{'id':'mcyukkuri:'+name,'count':details['count']}})
        ingredient=details['ingredients'][0]
        emit(DATA/f'advancement/recipes/misc/{name}.json',{'parent':'minecraft:recipes/root','criteria':{'has_item':{'trigger':'minecraft:inventory_changed','conditions':{'items':[{'items':ingredient}]}},'has_the_recipe':{'trigger':'minecraft:recipe_unlocked','conditions':{'recipe':'mcyukkuri:'+name}}},'requirements':[['has_item','has_the_recipe']],'rewards':{'recipes':['mcyukkuri:'+name]}})
    # Saved crevices migrate to loose nesting material; never award another artificial habitat.
    emit(DATA/'loot_table/blocks/house_crevice.json',{'type':'minecraft:block','pools':[]})
    records={'status':recipe['status'],'input':{'recipe.json':fingerprint(SOURCE/'recipe.json',(SOURCE/'recipe.json').read_bytes(),previous.get('input',{}).get('recipe.json'))},'outputs':fingerprints(ROOT,result,previous.get('outputs',{}))}
    if selected:records['selected_models']=fingerprints(ROOT,selected,previous.get('selected_models',{}))
    emit(SOURCE/'provenance.json',records)
    return result
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    generated=outputs()
    for path,data in generated.items():
        if args.check:
            if not check_output(path,data):raise SystemExit(f'House export differs: {path}')
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print(f'{len(generated)} house/care exports '+('match.' if args.check else 'written.'))
if __name__=='__main__':main()
