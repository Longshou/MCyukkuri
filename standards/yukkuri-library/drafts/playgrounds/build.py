"""Block-style playground equipment (block-props/kit.py) after the inventory icons, and the matching physical geometry.

16 units = one block, +Y up; a facility is entered from -Z. Every model is cells on the 1/16 grid painted with the icon
ramps of item-icons/draw.py; collision is read from the same cells (merged boxes), so what a body stands on is what the
player sees. Gameplay heights stay where PlayLife's routes expect them: tunnel floor 2, slide steps 4/8/12/16 and deck
16, pool side-step top 6; the trampoline bed is raised to 8, inside the route's 0.6-block reach of its 0.3125 waypoint.

ceramic_tunnel: a clay pipe lying along Z, a stepped round section (flat foot on the ground) with a raised rim band one
unit behind each mouth (its steps show from either end); the bore keeps the old flat floor and widens to 24 units, so wide hats and long hair still pass, and darkens
with depth like the icon's bore.
yukkuri_slide: steel steps and tower to a teal deck, a teal chute with side lips, a deck guard and stair handrails. The
chute and the rails are authored flat and sheared onto their slope (the lectern's slanted-board way); the chute's
collision stays sampled at one unit along the slope.
yukkuri_trampoline: a gold round-cornered frame on four iron legs, its skirt hiding the springs, and a dark bed. The
bed is its own part (unit 2, pivot at the bed surface y 8) so the renderer can press it down; the frame's inner wall
runs from the skirt bottom up, so a bed pressed by up to 2 units shows only the dark inside of the skirt, no gap or spring.
yukkuri_pool: a tiled basin with a white rim, a blue floor and inner walls with a line under the rim, between two tiled
side decks, each with a lower outer step. The vanilla water fluid fills the basin in game.
--check verifies every export.
"""
import argparse,importlib.util,json,sys
from pathlib import Path

HERE=Path(__file__).resolve().parent; ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts'))
from asset_export_checks import check_output,fingerprints,preserve_equivalent_png,preserve_negative_zero_mesh_json,provenance
KIT=HERE.parent/'block-props/kit.py'
if 'block_props_kit' not in sys.modules:   # loaded under its own name: care-props also has a `kit`
    _spec=importlib.util.spec_from_file_location('block_props_kit',KIT)
    sys.modules['block_props_kit']=_module=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(_module)
kit=sys.modules['block_props_kit']
SNAPSHOT=ROOT/'art/props/playgrounds';ASSETS=ROOT/'src/main/resources/assets/mcyukkuri';DATA=ROOT/'src/main/resources/data/mcyukkuri'
FAR=100           # height at which sheared parts are authored, clear of every other cell (no shared faces)


def M(ramp,pattern,bevel=True):
    return kit.Material(ramp,top=0,bottom=0,bevel=bevel,pattern=pattern)


def boxes(cells):
    """Greedy merge of cells into [x0, y0, z0, x1, y1, z1] boxes (x runs, then z rows, then y slabs)."""
    left,result=set(cells),[]
    for x,y,z in sorted(cells,key=lambda c:(c[1],c[2],c[0])):
        if (x,y,z) not in left:continue
        x1=x
        while (x1+1,y,z) in left:x1+=1
        z1=z
        while all((i,y,z1+1) in left for i in range(x,x1+1)):z1+=1
        y1=y
        while all((i,y1+1,k) in left for i in range(x,x1+1) for k in range(z,z1+1)):y1+=1
        for i in range(x,x1+1):
            for j in range(y,y1+1):
                for k in range(z,z1+1):left.discard((i,j,k))
        result.append([x,y,z,x1+1,y1+1,z1+1])
    return result


def section(half_widths):
    """Plan of a lying pipe section {(x, y)} from the half width of each row, y = 0 upward."""
    return {(x,y) for y,h in enumerate(half_widths) for x in range(-h,h)}


# ---- ceramic tunnel ---------------------------------------------------------------------------------------------------
def ceramic_tunnel():
    b=kit.Block('ceramic_tunnel')
    low=[8,10,11,12,13,13]                                     # stepped round: rows 0-5, then 14 to the mirrored top
    body=section(low+[14]*16+low[::-1])                       # 28 x 28
    band=section([h+1 for h in low]+[15]*17+[h+1 for h in low[::-1]])   # rim band: one unit proud, foot on the ground
    bore=section([0,0,8,9,10,11]+[12]*16+[11,10,9,8])         # flat floor at y 2; contains the old 16/24/16 bore
    Z0,Z1,RIM=-22,22,2
    in_band=lambda z:Z0+1<=z<Z0+1+RIM or Z1-1-RIM<=z<Z1-1   # set back one unit, so both collars show their steps
    def clay(cell,face):
        x,y,z=cell
        (axis,sign),_,_=kit.FACES[face]
        n=[0,0,0];n[axis]=sign
        if (x+n[0],y+n[1]) in bore and Z0<=z+n[2]<Z1:      # a bore face: darker with depth from the nearer mouth
            depth=min(z-Z0,Z1-1-z)
            return ('TERRA',1) if depth<2 else ('TERRA',0) if depth<5 else ('ANKO',1)   # shadowed clay, warm to the end
        if axis==2 and z+n[2] in (Z0-1,Z1):return ('TERRA',4)     # bright mouth rings
        if face=='down':return ('TERRA',0)
        collar=in_band(z)                                   # both rim bands one step lighter than the body
        if face=='up':return ('TERRA',3+collar)
        # Sides, and the band's step down to the body shaded like the side at that height, so each collar reads the
        # same from either mouth (no dark crease lines on the far one only).
        return ('TERRA',(3 if y>=18 else 2 if y>=7 else 1)+collar)
    clay_m=M('TERRA',clay,bevel=False)                   # a lying round: shading by row, not per step
    pipe=b.part('pipe')
    for z in range(Z0,Z1):
        plan=band if in_band(z) else body
        for x,y in plan-bore:pipe.cell((x,y,z),clay_m)
    return b,boxes(pipe.cells),{}


# ---- slide -------------------------------------------------------------------------------------------------------------
STEPS=((-36,4),(-28,8),(-20,12),(-12,16))                   # (front z, top) of each 8-deep step
DECK=(-4,2,16)                                               # z0, z1, top
CHUTE=(2,36,15/34)                                           # z0, z1, drop per unit (top 16 at z 2 to 1 at z 36)


def yukkuri_slide():
    b=kit.Block('yukkuri_slide')
    def steel(cell,face):
        if face=='up':return ('IRON',3)
        if face=='north':return ('IRON',1)
        return ('IRON',0) if face=='down' else ('IRON',2)
    def deck(cell,face):
        if cell[1]==DECK[2]-1:return ('TEAL',3 if face=='up' else 2)  # the deck plate
        return steel(cell,face)
    steps=b.part('steps')
    for z,top in STEPS:steps.box((-9,0,z),(9,top,z+8),M('IRON',steel))
    steps.box((-9,0,DECK[0]),(9,DECK[2],DECK[1]),M('IRON',deck))
    # Chute bed and side lips, flat at FAR; sheared down the slope in outputs().
    def chute(cell,face):
        x,y,z=cell
        if face=='up':return ('TEAL',4 if x in (-8,-7) and y==FAR else 3)   # a long highlight down the lit side
        if face=='down':return ('TEAL',1)
        return ('TEAL',2)
    lip=lambda cell,face:('TEAL',4 if face=='up' else 1 if face=='down' else 2)
    bed=b.part('chute')
    bed.box((-9,FAR,CHUTE[0]),(9,FAR+1,CHUTE[1]),M('TEAL',chute))
    for x in (-10,9):bed.box((x,FAR,CHUTE[0]),(x+1,FAR+3,CHUTE[1]),M('TEAL',lip))
    # Steel frame: posts at the stair foot and the deck corners, a deck guard bar, and the stair handrails.
    rod=M('IRON',lambda cell,face:('IRON',2 if face=='up' else 0 if face=='down' else 1))
    frame=b.part('frame')
    for x in (-10,9):
        frame.box((x,0,-33),(x+1,8,-32),rod)
        for z in (-5,1):frame.box((x,0,z),(x+1,22,z+1),rod)
        frame.box((x,21,-4),(x+1,22,1),rod)
    rails=b.part('handrails')
    for x in (-10,9):rails.box((x,FAR,-32),(x+1,FAR+1,-5),rod)
    collision=boxes(steps.cells)
    collision+=[[-9,max(0,15-(z-1)*15/34),z,9,16-(z-2)*15/34,z+1] for z in range(CHUTE[0],CHUTE[1])]
    def shear(part,x,y,z):
        if part=='chute':return x,y-FAR+15-(z-CHUTE[0])*CHUTE[2],z          # bed top 16 at z 2, 1 at z 36
        if part=='handrails':return x,y-FAR+21+(z+5)*.5,z                 # top 22 at the deck post, 8 at the foot post
        return x,y,z
    return b,collision,{'transform':shear}


# ---- trampoline -------------------------------------------------------------------------------------------------------
def rounded(half,radius):
    """Plan of a square 2*half wide with stepped round corners of `radius`."""
    inner=half-radius
    return {(x,z) for x in range(-half,half) for z in range(-half,half)
            if max(0,abs(x+.5)-inner)**2+max(0,abs(z+.5)-inner)**2<=radius*radius}


def yukkuri_trampoline():
    b=kit.Block('yukkuri_trampoline')
    outer=rounded(19,5);bed_plan=kit.inset(outer,2);ring=outer-bed_plan
    SKIRT,TOP,BED=4,9,8                                       # skirt bottom (legs below), frame top, bed surface
    def frame(cell,face):
        x,y,z=cell
        if face=='up':return ('GOLD',3)
        if face=='down':return ('GOLD',0)
        if kit.inward(cell,face):                             # above the bed a shaded lip; below it the dark inside
            return ('GOLD',1) if y>=BED else ('INK',1)
        return ('GOLD',2)
    gold=M('GOLD',frame)
    rim=b.part('frame')
    rim.layer(ring,SKIRT,TOP,gold)
    legs=b.part('legs')
    iron=M('IRON',lambda cell,face:('IRON',1 if face=='down' else 2 if face!='up' else 3))
    for sx in (-1,1):                                         # 2x2 under the ring where its corners start to round
        for sz in (-1,1):
            foot={(sx*x-(sx<0),sz*z-(sz<0)) for x in (17,18) for z in (13,14)}
            assert foot<=ring,foot
            legs.layer(foot,0,SKIRT,iron)
    edge=bed_plan-kit.inset(bed_plan,2)
    def bed(cell,face):
        x,_,z=cell
        if face!='up':return ('INK',0)
        if (x,z) in edge and (x,z) not in kit.inset(bed_plan,1):return ('INK',4)   # light bound edge against the frame
        if (x,z) in edge:return ('INK',3)                                          # stitch row inside it
        return ('INK',2)
    mat=b.part('bed',unit=2)
    mat.layer(bed_plan,BED-1,BED,M('INK',bed,bevel=False))
    collision=boxes(rim.cells)+boxes(legs.cells)+boxes(mat.cells)
    return b,collision,{'origin':{'bed':[0,BED,0]}}


# ---- pool --------------------------------------------------------------------------------------------------------------
def yukkuri_pool():
    b=kit.Block('yukkuri_pool')
    well=kit.octagon(8,2)                                     # the basin opening over the core block
    RIM=6
    def tiles(cell,face):
        x,y,z=cell
        (axis,sign),(ua,_),(va,_)=kit.FACES[face]
        n=[0,0,0];n[axis]=sign
        if face=='down':return ('TILE',0)
        inside=(x+n[0],z+n[2]) in well and 1<=y+n[1]<RIM
        if inside:
            if face=='up':return ('WATER',2) if x%4==0 or z%4==0 else ('WATER',3)         # blue tiled floor
            if y==RIM-1:return ('WATER',1)                                                 # the line under the rim
            return ('WATER',3) if (cell[ua]%4==0 or y%3==1) else ('WATER',4)
        if face=='up':
            if -11<=x<11:return ('TILE',4)                                                 # white basin rim
            return ('TILE',2) if x%4==0 or z%4==0 else ('TILE',3)
        u=cell[ua]
        return ('TILE',1) if u%4==0 or y%3==0 else ('TILE',2 if y<3 else 3)
    tile=M('TILE',tiles)
    pool=b.part('pool')
    square={(x,z) for x in range(-11,11) for z in range(-11,11)}
    pool.layer(square,0,1,tile).layer(square-well,1,RIM,tile)
    for sign in (-1,1):
        deck={(x,z) for x in range(11,20) for z in range(-11,11)}
        step={(x,z) for x in range(20,23) for z in range(-11,11) if not (x==22 and abs(z+.5)>9)}
        pool.layer({(sign*x-(sign<0),z) for x,z in deck},0,RIM,tile)
        pool.layer({(sign*x-(sign<0),z) for x,z in step},0,3,tile)
    return b,boxes(pool.cells),{}


PROPS={'ceramic_tunnel':ceramic_tunnel,'yukkuri_slide':yukkuri_slide,'yukkuri_trampoline':yukkuri_trampoline,'yukkuri_pool':yukkuri_pool}
LABELS={'ceramic_tunnel':['陶管','Ceramic tunnel'],'yukkuri_slide':['油库里滑梯','Yukkuri slide'],
        'yukkuri_trampoline':['油库里蹦床','Yukkuri trampoline'],'yukkuri_pool':['油库里水池','Yukkuri pool']}
INGREDIENTS={'ceramic_tunnel':['brick']*6,'yukkuri_slide':['iron_ingot']*4+['stick']*2,
             'yukkuri_trampoline':['string']*4+['slime_ball','iron_ingot'],'yukkuri_pool':['brick']*4+['cyan_dye']}


def build(name):
    """(mesh, bbmodel, png, collision, parts summary) of one prop, with sheared parts moved onto their slope."""
    block,collision,extra=PROPS[name]()
    mesh,bb,png=block.export()
    move=extra.get('transform')
    if move:
        verts=mesh['vertices']
        for part in mesh['parts']:
            for v in range(part['start'],part['start']+part['count']):
                o=v*8;verts[o:o+3]=[round(c,6)+0.0 for c in move(part['name'],*verts[o:o+3])]
            for t in range(part['start'],part['start']+part['count'],3):   # face normals of the moved triangles
                a,b2,c=([verts[(t+k)*8+i] for i in range(3)] for k in range(3))
                u=[b2[i]-a[i] for i in range(3)];w=[c[i]-a[i] for i in range(3)]
                n=[u[1]*w[2]-u[2]*w[1],u[2]*w[0]-u[0]*w[2],u[0]*w[1]-u[1]*w[0]];size=sum(k*k for k in n)**.5
                for k in range(3):verts[(t+k)*8+5:(t+k)*8+8]=[round(n[i]/size,6)+0.0 for i in range(3)]
        for element in bb['elements']:
            element['vertices']={i:[round(c,6)+0.0 for c in move(element['name'],*p)] for i,p in element['vertices'].items()}
        mesh['bounds']=[[min(verts[k::8]),max(verts[k::8])] for k in range(3)]
    for element in bb['elements']:
        element['origin']=extra.get('origin',{}).get(element['name'],element['origin'])
    summary=[{'name':p['name'],'unit':p['unit'],'cells':len(p['cells'])} for p in block.parts]
    return mesh,bb,png,collision,summary


def outputs():
    previous=provenance(SNAPSHOT/'manifest.json');result={};index={};recipe={}
    def emit(path,data):result[path]=(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode()
    def emit_mesh(path,data):result[path]=preserve_negative_zero_mesh_json(path,(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n').encode())
    for name in PROPS:
        mesh,bb,png,collision,summary=build(name)
        recipe[name]={'parts':summary,'collision':collision}
        texture=preserve_equivalent_png(ASSETS/f'textures/props/{name}.png',png)
        for folder in (HERE/'assembled'/name,SNAPSHOT/'selected'/name):
            emit(folder/f'{name}.bbmodel',bb);emit_mesh(folder/f'{name}.mesh.json',mesh);result[folder/'texture.png']=texture
        emit_mesh(ASSETS/f'yukkuri_props/{name}.json',mesh);result[ASSETS/f'textures/props/{name}.png']=texture
        emit(ASSETS/f'models/item/{name}.json',{'parent':'minecraft:builtin/entity','gui_light':'front','textures':{'particle':f'mcyukkuri:props/{name}'},'display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]}}})
        emit(ASSETS/f'blockstates/{name}.json',{'variants':{'':{'model':f'mcyukkuri:block/{name}'}}})
        emit(ASSETS/f'models/block/{name}.json',{'textures':{'particle':f'mcyukkuri:props/{name}'},'elements':[]})
        emit(DATA/f'loot_table/blocks/{name}.json',{'type':'minecraft:block','pools':[]})
        emit(DATA/f'playgrounds/{name}.json',{'collision':collision})
        emit(DATA/f'recipe/{name}.json',{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':'minecraft:'+x} for x in INGREDIENTS[name]],'result':{'id':'mcyukkuri:'+name,'count':1}})
        index[name]={'bounds':mesh['bounds'],'triangles':len(mesh['vertices'])//24}
    emit(HERE/'recipe.json',{'status':'pending-review','units_per_block':16,'style':'block-props kit','props':recipe})
    emit(ASSETS/'yukkuri_props/playgrounds-index.json',index)
    for i,locale in enumerate(('zh_cn','en_us')):
        path=ASSETS/f'lang/{locale}.json';existing=json.loads(path.read_text(encoding='utf8'))
        strings={'item.mcyukkuri.'+name:labels[i] for name,labels in LABELS.items()}
        strings.update({'block.mcyukkuri.'+name:labels[i] for name,labels in LABELS.items()})
        strings.update({'activity.mcyukkuri.'+key:values[i] for key,values in {'tunnel':['穿过管道','Passing through a tunnel'],'slide_climb':['登上滑梯','Climbing the slide'],
            'slide_down':['顺坡滑下','Sliding down'],'trampoline':['玩蹦床','Bouncing on a trampoline'],'pool_edge':['沿水池探看','Exploring the pool edge']}.items()})
        strings.update({'play.mcyukkuri.no_space':['需要平坦、空旷的 %s×%s×%s 空间','Needs a clear, supported %s×%s×%s space'][i],
                        'play.mcyukkuri.pool':['水池：%s／3 份水。右键使用水桶补水；入水仍会浸湿。','Pool: %s/3 water portions. Use a water bucket to fill; entering still wets the body.'][i]})
        changed=any(existing.get(k)!=v for k,v in strings.items());existing.update(strings)
        if changed:result[path]=(json.dumps(existing,ensure_ascii=False,indent=2)+'\n').encode()
    emit(SNAPSHOT/'manifest.json',{'status':'pending-review-game-adaptation','source':HERE.relative_to(ROOT).as_posix()+'/build.py',
        'inputs':fingerprints(ROOT,{p:p.read_bytes() for p in (Path(__file__).resolve(),KIT,kit.DRAW)},previous.get('inputs',{})),
        'files':fingerprints(ROOT,{p:v for p,v in result.items() if p.parent.name!='lang'},previous.get('files',{}))})
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args();changed=[]
    for path,data in outputs().items():
        if not check_output(path,data):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    if args.check and changed:raise SystemExit('Stale exports: '+', '.join(changed))
    print(f'Playgrounds: {len(PROPS)} models; {len(changed)} files '+('differ' if args.check else 'written'))
