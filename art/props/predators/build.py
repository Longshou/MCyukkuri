"""Predator twig nest (block-style, block-props/nests.py) and detached copies of the selected predator bonnets.

--check is read-only: it compares every output with the files on disk."""
from pathlib import Path
import argparse,sys,json,hashlib,importlib.util
ROOT=Path(__file__).resolve().parents[3]
LIB=ROOT/'standards/yukkuri-library'
sys.path[:0]=[str(LIB/'subspecies/predator'),str(LIB/'ecology/house-yukkuri'),str(LIB),str(ROOT/'scripts')]
from mesh_geometry import vertices
from asset_export_checks import check_output
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
DATA=ROOT/'src/main/resources/data/mcyukkuri'
SOURCE=Path(__file__).resolve().parent
def dumps(d):return (json.dumps(d,ensure_ascii=False,separators=(',',':'))+'\n').encode('utf-8')
def nests():
    spec=importlib.util.spec_from_file_location('block_props_nests',LIB/'drafts/block-props/nests.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
def outputs():
    out,index={},{}
    for ident in ('remilia','flandre'):
        folder=LIB/'subspecies/predator/assembled'/ident;name=ident+'_bonnet'
        bb=json.loads((folder/(ident+'.bbmodel')).read_text(encoding='utf-8'))
        flat,parts=[],[]
        for e in bb['elements']:
            if not e['name'].startswith('predator_cap_'):continue
            vv=vertices(e,256);parts.append(dict(name=e['name'],start=len(flat)//8,count=len(vv)//8));flat.extend(vv)
        model=dumps(dict(format=1,units_per_block=16,vertices=flat,parts=parts,bounds=[[min(flat[k::8]),max(flat[k::8])] for k in range(3)]))
        out[SOURCE/'assembled'/name/(name+'.mesh.json')]=out[ASSETS/'yukkuri_props'/(name+'.json')]=model
        out[ASSETS/'textures/props'/(name+'.png')]=(folder/'textures/neutral.png').read_bytes()
        out[ASSETS/'models/item'/(name+'.json')]=dumps(dict(parent='minecraft:builtin/entity',textures={'particle':'mcyukkuri:props/'+name}))
        index[name]=dict(mesh='art/props/predators/assembled/'+name+'/'+name+'.mesh.json',sha256=hashlib.sha256(model).hexdigest())
    nest=nests().predator_nest()         # stepped twig bowl with its own atlas (block-props/nests.py)
    mesh=dumps(nest.mesh())
    out[SOURCE/'assembled/predator_nest/predator_nest.mesh.json']=out[ASSETS/'yukkuri_props/predator_nest.json']=mesh
    out[SOURCE/'assembled/predator_nest/predator_nest.bbmodel']=dumps(nest.bbmodel())
    out[SOURCE/'assembled/predator_nest/texture.png']=out[ASSETS/'textures/props/predator_nest.png']=nest.png()
    out[ASSETS/'models/item/predator_nest.json']=dumps(dict(parent='minecraft:builtin/entity',textures={'particle':'mcyukkuri:props/predator_nest'}))
    index['predator_nest']=dict(mesh='art/props/predators/assembled/predator_nest/predator_nest.mesh.json',sha256=hashlib.sha256(mesh).hexdigest())
    out[ASSETS/'yukkuri_props/predators-index.json']=dumps(index)
    out[ASSETS/'models/block/predator_nest.json']=dumps({'textures':{'particle':'mcyukkuri:props/predator_nest'},'elements':[]})
    out[ASSETS/'blockstates/predator_nest.json']=dumps({'variants':{'':{'model':'mcyukkuri:block/predator_nest'}}})
    out[DATA/'loot_table/blocks/predator_nest.json']=dumps({'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'mcyukkuri:predator_nest'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    out[DATA/'recipe/predator_nest.json']=dumps({'type':'minecraft:crafting_shaped','category':'misc','pattern':['S S','SWS','SSS'],'key':{'S':{'item':'minecraft:stick'},'W':{'item':'minecraft:wheat'}},'result':{'id':'mcyukkuri:predator_nest','count':1}})
    out[SOURCE/'provenance.json']=dumps({'status':'pending_confirmation','source':'build.py; block-props/nests.py; selected predator cap elements','outputs':index})
    return out
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    changed=[p for p,v in outputs().items() if not check_output(p,v)]
    if args.check:
        if changed:raise SystemExit('Predator exports differ: '+', '.join(p.relative_to(ROOT).as_posix() for p in changed))
        print('Predator nest and bonnets match.');return
    for p,v in outputs().items():
        if p in changed:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(v)
    print(f'Two selected bonnets and the twig nest exported; {len(changed)} files written.')
if __name__=='__main__':main()
