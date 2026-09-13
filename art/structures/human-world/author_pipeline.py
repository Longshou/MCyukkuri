"""Original native block geometry for receiving, inspection, washing and physical transfer trays."""
from pathlib import Path
import json, hashlib
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parents[3]
ART=Path(__file__).parent/'products'
ASSET=ROOT/'src/main/resources/assets/mcyukkuri'
NAMES=['factory_receiving_table','factory_inspection_table','factory_washing_table','factory_observation_station','factory_transfer_tray']
def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def export():
    records={}
    for index,name in enumerate(NAMES):
        im=Image.new('RGBA',(32,32),['#c39d59','#669980','#6cabc0','#c48470','#798b90'][index]);d=ImageDraw.Draw(im)
        d.rectangle((0,0,31,31),outline='#31494e',width=2);d.rectangle((4,4,27,27),fill='#ecdfba')
        if index==0:
            d.line((8,8,8,22,24,22,24,8),fill='#42555a',width=3);d.polygon([(14,6),(18,6),(18,13),(22,13),(16,19),(10,13),(14,13)],fill='#a85d36')
        elif index==1:
            d.ellipse((7,6,21,20),outline='#325b46',width=3);d.line((20,19,26,25),fill='#325b46',width=4)
            d.line((11,13,14,16,18,10),fill='#325b46',width=2)
        elif index==2:
            d.polygon([(16,6),(8,18),(10,24),(22,24),(24,18)],fill='#468fad');d.line((13,15,11,19,13,21),fill='#c8edf4',width=2)
        elif index==3:
            d.ellipse((6,10,26,23),outline='#865648',width=2);d.ellipse((12,11,20,21),fill='#865648');d.rectangle((15,5,17,8),fill='#865648')
        else:
            d.rectangle((7,10,25,20),fill='#586e74');d.rectangle((9,8,23,17),fill='#b6c6b6');d.ellipse((8,21,13,26),fill='#31494e');d.ellipse((20,21,25,26),fill='#31494e')
        source=ART/(name+'.png');source.parent.mkdir(parents=True,exist_ok=True);im.save(source)
        target=ASSET/'textures/block'/source.name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.read_bytes())
        def box(a,b,tex='casing'):
            return {'from':a,'to':b,'faces':{f:{'texture':'#'+tex,'uv':[0,0,16,16]} for f in ['north','south','east','west','up','down']}}
        if index==4:
            # Model coordinates are shared by the block preview and the runtime entity renderer.
            parts=[box([0,2,0],[16,4,16]),box([0,4,0],[1,6,16]),box([15,4,0],[16,6,16]),box([1,4,15],[15,6,16])]
            parts += [box([x,0,z],[x+3,2,z+3],'front') for x in (1,12) for z in (1,12)]
        else:
            parts=[box([1,0,1],[15,3,15]),box([0,10,0],[16,12,16]),box([1,3,12],[15,10,15],'front')]
            parts += [box([x,3,z],[x+2,10,z+2]) for x in (1,13) for z in (1,13)]
            if index in (0,3):parts += [box([0,12,1],[2,16,16]),box([14,12,1],[16,16,16]),box([2,12,14],[14,16,16],'front')]
            if index==1:parts += [box([13,12,12],[15,22,14]),box([5,20,9],[15,22,14],'front')]
            if index==2:parts += [box([1,12,1],[3,15,15]),box([13,12,1],[15,15,15]),box([3,12,13],[13,15,15]),box([3,12,1],[13,15,3]),box([3,12,3],[13,12.5,13],'front'),box([11,15,12],[13,22,14]),box([8,20,10],[13,22,14])]
        write(ASSET/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','textures':{'particle':'mcyukkuri:block/'+name,'front':'mcyukkuri:block/'+name,'casing':'mcyukkuri:block/workshop_casing'},'elements':parts})
        write(ASSET/'blockstates'/f'{name}.json',{'variants':{f'facing={f}':{'model':'mcyukkuri:block/'+name,'y':angle} for f,angle in [('north',0),('east',90),('south',180),('west',270)]}})
        write(ASSET/'models/item'/f'{name}.json',{'parent':'mcyukkuri:block/'+name})
        write(ROOT/'src/main/resources/data/mcyukkuri/loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'mcyukkuri:'+name}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
        records[name]={'source':source.relative_to(ROOT).as_posix(),'target':target.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(source.read_bytes()).hexdigest()}
    write(ART/'pipeline-manifest.json',{'status':'pending_visual_approval','geometry':'Native block elements; tray passenger attaches at y=4/16 blocks.','assets':records})
    return records
if __name__=='__main__':export()
