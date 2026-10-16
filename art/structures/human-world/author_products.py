"""Original native pixel assets and block geometry for factory equipment, packages and badges."""
from pathlib import Path
import io,json,hashlib,sys
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'scripts'));sys.path.insert(0,str(ROOT/'art/props/badges'))
import author_badges
from PIL import Image,ImageDraw
from asset_export_checks import preserve_equivalent_png
ART=Path(__file__).parent/'products'
ASSET=ROOT/'src/main/resources/assets/mcyukkuri'
def write(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
def write_bytes(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    if not path.is_file() or path.read_bytes()!=data:path.write_bytes(data)
def write_png(path,image):
    stream=io.BytesIO();image.save(stream,format='PNG')
    data=preserve_equivalent_png(path,stream.getvalue())
    write_bytes(path,data)
    return data
def export():
    records={}
    metal=Image.new('RGBA',(32,32),'#899994');md=ImageDraw.Draw(metal)
    md.rectangle((0,0,31,31),outline='#394b4c',width=2);md.rectangle((3,3,28,28),outline='#b8c7bc')
    for x in (4,27):
        for y in (4,27):md.rectangle((x,y,x+1,y+1),fill='#34454a')
    mp=ART/'workshop_casing.png';metal_data=write_png(mp,metal)
    target=ASSET/'textures/block/workshop_casing.png';write_bytes(target,metal_data)
    names=['yukkuri_manju','live_yukkuri_bag','bronze_yukkuri_badge','silver_yukkuri_badge','gold_yukkuri_badge','labor_bench','manju_steamer','live_packing_table','nursery_station']
    for name in names:
        im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);dark='#43392f'
        if name.endswith('badge'):
            im=author_badges.badge(name.split('_')[0])  # one 16x16 source for item, worn decal and handbook
        elif name=='yukkuri_manju':
            d.ellipse((2,7,30,29),fill='#a7703e');d.ellipse((3,5,29,25),fill='#e3b978');d.ellipse((5,6,26,21),fill='#f6d79e')
            d.line((7,12,24,12),fill='#bb8142',width=2);d.point((11,15),fill=dark);d.point((21,15),fill=dark);d.arc((13,15,19,19),0,180,fill=dark)
            d.polygon([(6,8),(9,5),(12,8)],fill='#b5763a')
        elif name=='live_yukkuri_bag':
            d.polygon([(6,2),(26,2),(25,7),(29,26),(26,30),(5,30),(3,26),(7,7)],fill='#697b78')
            d.polygon([(8,6),(24,6),(27,26),(24,28),(7,28),(5,25)],fill='#dccf9c')
            d.rectangle((6,3,26,5),fill='#b64038');d.ellipse((7,10,25,24),fill='#f5dfb3')
            d.polygon([(8,12),(11,9),(15,11),(19,9),(24,12),(24,15),(8,15)],fill='#54402c')
            for x in (11,20):d.line((x,17,x+2,17),fill=dark,width=2)
            d.line((15,20,18,20),fill=dark);d.rectangle((20,23,26,27),fill='#f7efcf');d.line((21,25,25,25),fill='#546756')
            d.line((6,8,5,23),fill='#fffbe2');d.line((24,8,27,23),fill='#fffbe2')
        else:
            color={'labor_bench':'#aa914d','manju_steamer':'#9daaa5','live_packing_table':'#629797','nursery_station':'#ce9aa1'}[name]
            d.rectangle((0,0,31,31),fill='#424e50');d.rectangle((1,1,30,29),fill=color)
            d.rectangle((2,2,29,5),fill='#e4dfc5');d.rectangle((3,8,28,23),fill='#273b3d')
            if name=='labor_bench':
                for x in (5,10,15,20,25):d.rectangle((x,10,x+2,21),fill='#b9ada0')
                d.line((4,16,27,16),fill='#ead4a1',width=2)
            elif name=='manju_steamer':
                for y in (12,18):
                    d.line((5,y+3,26,y+3),fill='#bdc7be')
                    for x in (8,15,22):d.ellipse((x-2,y-2,x+2,y+2),fill='#f6d79e')
                d.rectangle((7,26,24,27),fill='#d6793d')
            elif name=='live_packing_table':
                for x in (6,16):
                    d.rectangle((x,11,x+7,21),fill='#d9c99b');d.rectangle((x,10,x+7,12),fill='#b64038')
                    d.ellipse((x+1,14,x+6,19),fill='#f7dfb3');d.point((x+2,16),fill=dark);d.point((x+5,16),fill=dark)
            else:
                d.ellipse((7,14,24,22),fill='#f5d9af');d.line((11,17,13,17),fill=dark);d.line((19,17,21,17),fill=dark)
                d.arc((14,16,18,20),0,180,fill=dark);d.rectangle((14,7,17,12),fill='#fff0e1');d.rectangle((12,9,19,10),fill='#fff0e1')
            for x in (3,27):d.rectangle((x,26,x+1,28),fill='#232f30')
        source=ART/(name+'.png');source_data=write_png(source,im)
        block=name in names[5:];target=ASSET/'textures'/('block' if block else 'item')/source.name
        write_bytes(target,source_data)
        if block:
            def box(a,b,texture='casing',front=None):
                return {'from':a,'to':b,'faces':{face:{'texture':'#'+(front if face=='north' and front else texture),'uv':[0,0,16,16]} for face in ['north','south','east','west','up','down']}}
            if name=='labor_bench':
                parts=[box([0,0,0],[16,3,16]),box([0,9,0],[16,11,16])]
                parts += [box([x,3,z],[x+2,9,z+2]) for x in (1,13) for z in (1,13)]
                parts += [box([2,11,z],[14,14,z+1]) for z in (2,5,8,11)]
                parts += [box([1,3,1],[15,9,3],front='front')]
            elif name=='manju_steamer':
                parts=[box([1,0,1],[15,22,15],front='front'),box([0,22,0],[16,24,16])]
                parts += [box([x,24,9],[x+3,32,12]) for x in (3,10)]
            elif name=='live_packing_table':
                parts=[box([0,0,0],[16,3,16]),box([0,9,0],[16,11,16]),box([0,11,12],[2,23,16]),box([14,11,12],[16,23,16]),box([0,21,12],[16,24,16])]
                parts += [box([x,3,z],[x+2,9,z+2]) for x in (1,13) for z in (1,13)]
                parts += [box([4,11,5],[12,21,6],'package'),box([2,4,1],[14,8,3],front='front')]
            else:
                parts=[box([0,0,0],[16,5,16]),box([0,5,0],[2,10,16]),box([14,5,0],[16,10,16]),box([0,5,14],[16,18,16],front='front'),box([2,5,2],[14,6,14],'front')]
            write(ASSET/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','render_type':'minecraft:cutout',
                'textures':{'particle':'mcyukkuri:block/workshop_casing','casing':'mcyukkuri:block/workshop_casing','front':'mcyukkuri:block/'+name,'package':'mcyukkuri:item/live_yukkuri_bag'},'elements':parts})
            write(ASSET/'blockstates'/f'{name}.json',{'variants':{f'facing={f}':{'model':'mcyukkuri:block/'+name,'y':angle} for f,angle in [('north',0),('east',90),('south',180),('west',270)]}})
            model={'parent':'mcyukkuri:block/'+name}
            write(ROOT/'src/main/resources/data/mcyukkuri/loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'mcyukkuri:'+name}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
        else:model={'parent':'minecraft:item/generated','textures':{'layer0':'mcyukkuri:item/'+name}}
        write(ASSET/'models/item'/f'{name}.json',model)
        records[name]={'source':source.relative_to(ROOT).as_posix(),'target':target.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(source_data).hexdigest()}
    write(ART/'manifest.json',{'status':'pending_visual_approval','author':'Original procedural pixel art; no external source pixels.','assets':records})
    return records
if __name__=='__main__':export()
