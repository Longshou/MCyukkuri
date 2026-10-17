"""Original twig nest and detached copies of the selected predator bonnets."""
from pathlib import Path
import sys,json,math,hashlib,shutil
from PIL import Image
ROOT=Path(__file__).resolve().parents[3]
LIB=ROOT/'standards/yukkuri-library'
sys.path[:0]=[str(LIB/'subspecies/predator'),str(LIB/'ecology/house-yukkuri'),str(LIB)]
from species_geometry import tube,ring_surface,ellipse
from mesh_geometry import vertices
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
SOURCE=Path(__file__).resolve().parent
def save(p,d):
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
def output(name,elements,texture,size):
    flat=[];parts=[]
    for e in elements:
        vv=vertices(e,size);parts.append(dict(name=e['name'],start=len(flat)//8,count=len(vv)//8));flat.extend(vv)
    bounds=[[min(flat[k::8]),max(flat[k::8])] for k in range(3)]
    model=dict(format=1,units_per_block=16,vertices=flat,parts=parts,bounds=bounds)
    save(SOURCE/'assembled'/name/(name+'.mesh.json'),model)
    save(ASSETS/'yukkuri_props'/(name+'.json'),model)
    target=ASSETS/'textures/props'/(name+'.png');target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(texture,target)
    save(ASSETS/'models/item'/(name+'.json'),dict(parent='minecraft:builtin/entity',textures={'particle':'mcyukkuri:props/'+name}))
    return dict(mesh='art/props/predators/assembled/'+name+'/'+name+'.mesh.json',sha256=hashlib.sha256((ASSETS/'yukkuri_props'/(name+'.json')).read_bytes()).hexdigest())
def main():
    index={}
    for ident in ('remilia','flandre'):
        folder=LIB/'subspecies/predator/assembled'/ident
        bb=json.loads((folder/(ident+'.bbmodel')).read_text(encoding='utf-8'))
        index[ident+'_bonnet']=output(ident+'_bonnet',[e for e in bb['elements'] if e['name'].startswith('predator_cap_')],folder/'textures/neutral.png',256)
    sys.path.insert(0,str(LIB/'drafts/care-props'))
    import nests
    nest=nests.predator_nest()           # rough woven twig bowl on the shared care-prop palette (art/props/palette.json)
    mesh=nest.mesh()
    save(SOURCE/'assembled/predator_nest'/'predator_nest.mesh.json',mesh)
    save(SOURCE/'assembled/predator_nest'/'predator_nest.bbmodel',nest.bbmodel())
    save(ASSETS/'yukkuri_props/predator_nest.json',mesh)
    for target in (SOURCE/'assembled/predator_nest/texture.png',ASSETS/'textures/props/predator_nest.png'):
        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(nest.png())
    save(ASSETS/'models/item/predator_nest.json',dict(parent='minecraft:builtin/entity',textures={'particle':'mcyukkuri:props/predator_nest'}))
    index['predator_nest']=dict(mesh='art/props/predators/assembled/predator_nest/predator_nest.mesh.json',sha256=hashlib.sha256((ASSETS/'yukkuri_props/predator_nest.json').read_bytes()).hexdigest())
    save(ASSETS/'yukkuri_props/predators-index.json',index)
    save(ASSETS/'models/block/predator_nest.json',{'textures':{'particle':'mcyukkuri:props/predator_nest'},'elements':[]})
    save(ASSETS/'blockstates/predator_nest.json',{'variants':{'':{'model':'mcyukkuri:block/predator_nest'}}})
    data=ROOT/'src/main/resources/data/mcyukkuri'
    save(data/'loot_table/blocks/predator_nest.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'mcyukkuri:predator_nest'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    save(data/'recipe/predator_nest.json',{'type':'minecraft:crafting_shaped','category':'misc','pattern':['S S','SWS','SSS'],'key':{'S':{'item':'minecraft:stick'},'W':{'item':'minecraft:wheat'}},'result':{'id':'mcyukkuri:predator_nest','count':1}})
    save(SOURCE/'provenance.json',{'status':'pending_confirmation','source':'build.py; selected predator cap elements','outputs':index})
    print('Two selected bonnets and woven predator nest exported.')
if __name__=='__main__':main()
