"""Predator twig nest and its frame stages (block-style, block-props/nests.py) and detached copies of the selected predator
bonnets. The finished nest is a prop mesh (CareBlock renderer); the frame stages are native block models (no block entity).

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
FACE_OF={(0,1,0):'up',(0,-1,0):'down',(0,0,-1):'north',(0,0,1):'south',(1,0,0):'east',(-1,0,0):'west'}
def native_model(block,texture):
    """A block-props voxel block as a native block model: one flat element per merged face rectangle, sampling the same
    atlas texels, so a block without a block entity draws exactly the kit's mesh (corner block origin, +8 on x and z)."""
    v=block.mesh()['vertices'];elements=[]
    for i in range(0,len(v),48):                                  # one rectangle = two triangles of eight floats
        rows=[v[i+k*8:i+k*8+8] for k in range(6)]
        lo=[min(r[k] for r in rows)+(8 if k!=1 else 0) for k in range(3)];hi=[max(r[k] for r in rows)+(8 if k!=1 else 0) for k in range(3)]
        uv=[min(r[3] for r in rows),min(r[4] for r in rows),max(r[3] for r in rows),max(r[4] for r in rows)]
        face=FACE_OF[tuple(round(c) for c in rows[0][5:8])]
        elements.append({'from':[round(c,4) for c in lo],'to':[round(c,4) for c in hi],
                         'faces':{face:{'uv':[round(c*16,4) for c in uv],'texture':'#frame'}}})
    return {'ambientocclusion':False,'textures':{'particle':texture,'frame':texture},'elements':elements}
def padded(png):
    """The atlas with every clear texel filled from its nearest painted texel, so mipmaps of the opaque block layer
    never blend in black."""
    from PIL import Image
    import io
    im=Image.open(io.BytesIO(png)).convert('RGBA');w,h=im.size;px=im.load()
    front=[(x,y) for y in range(h) for x in range(w) if px[x,y][3]]
    while front:
        nxt=[]
        for x,y in front:
            for a,b in ((x+1,y),(x-1,y),(x,y+1),(x,y-1)):
                if 0<=a<w and 0<=b<h and not px[a,b][3]:px[a,b]=px[x,y];nxt.append((a,b))
        front=nxt
    out=io.BytesIO();im.save(out,format='PNG',optimize=True);return out.getvalue()
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
    # The unfinished nest: a plain block (no block entity) drawn by native models, one per twigs value, on one atlas.
    frames=nests().predator_nest_frames();texture='mcyukkuri:block/predator_nest_frame'
    tex=padded(frames[0].png())
    out[SOURCE/'assembled/predator_nest_frame/texture.png']=out[ASSETS/'textures/block/predator_nest_frame.png']=tex
    variants={}
    for n,frame in enumerate(frames,1):
        model=dumps(native_model(frame,texture));name=f'predator_nest_frame_{n}'
        out[ASSETS/'models/block'/(name+'.json')]=model;variants[f'twigs={n}']={'model':'mcyukkuri:block/'+name}
        index[name]=dict(model='src/main/resources/assets/mcyukkuri/models/block/'+name+'.json',sha256=hashlib.sha256(model).hexdigest())
    out[ASSETS/'blockstates/predator_nest_frame.json']=dumps({'variants':variants})
    counts=[{'function':'minecraft:set_count','count':n,'add':False,'conditions':[{'condition':'minecraft:block_state_property',
             'block':'mcyukkuri:predator_nest_frame','properties':{'twigs':str(n)}}]} for n in range(2,len(frames)+1)]
    out[DATA/'loot_table/blocks/predator_nest_frame.json']=dumps({'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item',
        'name':'minecraft:stick','functions':counts}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    out[ASSETS/'yukkuri_props/predators-index.json']=dumps({k:v for k,v in index.items() if 'mesh' in v})
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
    print(f'Two selected bonnets, the twig nest and its seven frame stages exported; {len(changed)} files written.')
if __name__=='__main__':main()
