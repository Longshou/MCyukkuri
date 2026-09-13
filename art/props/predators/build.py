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
    texture=SOURCE/'nest-palette.png';palette=Image.new('RGBA',(16,16),(151,112,67,255))
    for y in range(8,16):
        for x in range(16):palette.putpixel((x,y),(198,164,103,255))
    palette.save(texture)
    elements=[]
    for row in range(4):
        n=24;rx=6.2+row*.35;rz=5.2+row*.35;y=.35+row*.5
        for i in range(n):
            a=i*math.tau/n;b=(i+1.22)*math.tau/n
            elements.append(tube(f'woven_twig_{row}_{i}',[rx*math.cos(a),y,rz*math.sin(a)],[rx*math.cos(b),y+.1,rz*math.sin(b)],.21,[4,4],'nest'))
    for i in range(15):
        z=-4.5+i*.64;width=5.8*math.sqrt(max(.1,1-z*z/30))
        elements.append(tube('dry_fibre_'+str(i),[-width,.33,z],[width,.35,z+.28],.20,[4,12],'nest'))
    index['predator_nest']=output('predator_nest',elements,texture,16)
    save(ASSETS/'yukkuri_props/predators-index.json',index)
    save(ASSETS/'models/block/predator_nest.json',{'textures':{'particle':'mcyukkuri:props/predator_nest'},'elements':[]})
    save(ASSETS/'blockstates/predator_nest.json',{'variants':{'':{'model':'mcyukkuri:block/predator_nest'}}})
    data=ROOT/'src/main/resources/data/mcyukkuri'
    save(data/'loot_table/blocks/predator_nest.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'mcyukkuri:predator_nest'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    save(data/'recipe/predator_nest.json',{'type':'minecraft:crafting_shaped','category':'misc','pattern':['S S','SWS','SSS'],'key':{'S':{'item':'minecraft:stick'},'W':{'item':'minecraft:wheat'}},'result':{'id':'mcyukkuri:predator_nest','count':1}})
    save(SOURCE/'provenance.json',{'status':'pending_confirmation','source':'build.py; selected predator cap elements','outputs':index})
    print('Two selected bonnets and woven predator nest exported.')
if __name__=='__main__':main()
