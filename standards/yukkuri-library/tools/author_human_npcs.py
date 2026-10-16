"""Original pixel clothing for the Java humanoid rig. No external image inputs."""
from pathlib import Path
import hashlib, json, sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from author_staff_skins import ROLES as STAFF
from PIL import Image, ImageDraw
LIB=Path(__file__).resolve().parents[1]
ROOT=LIB.parents[1]
def export():
    palette={'oniisan':('#303d4c','#a05042','#302623'),'brother':('#48786e','#ddd3a5','#553b29'),
             'sister':('#a2556d','#f2d2ad','#4a2c21'),'shopkeeper':('#446a68','#e2d7b8','#503829'),
             'factory_worker':('#849596','#e7e4dc','#463b31'),
             'examiner':('#393c46','#f2f0ea','#3b3b46'),'keeper':('#6e8a3c','#eef0ea','#3a2d26')}
    result={}
    for name,(coat,apron,hair) in palette.items():
        im=Image.new('RGBA',(64,64)); d=ImageDraw.Draw(im);skin='#eac09b';dark='#373b41'
        # Default HumanoidModel UVs; opposing limbs share the mirrored texture.
        d.rectangle((0,0,31,15),fill=hair);d.rectangle((8,8,15,15),fill=skin)
        d.rectangle((8,8,15,9),fill=hair);d.point((8,10),fill=hair);d.point((15,10),fill=hair)
        for x in (9,13):d.rectangle((x,11,x+1,11),fill='#fff5db');d.point((x,11),fill='#40352c')
        d.rectangle((11,14,12,14),fill='#ae765d')
        d.rectangle((16,16,39,31),fill=coat);d.rectangle((0,16,15,31),fill=dark)
        d.rectangle((0,29,15,31),fill='#252b2c');d.rectangle((40,16,55,31),fill=coat)
        d.rectangle((40,27,55,31),fill=skin)
        d.rectangle((20,20,27,30),fill=apron);d.rectangle((21,20,26,21),fill=coat)
        d.line((20,27,27,27),fill='#a79273');d.rectangle((22,28,25,29),fill='#c0ab88')
        d.rectangle((23,17,24,19),fill=skin)
        # Small original yukkuri face badge on the apron, rather than an unrelated item icon.
        d.rectangle((25,23,27,25),fill='#f7e6bd');d.point((25,24),fill=dark);d.point((27,24),fill=dark)
        d.rectangle((0,36,15,49),fill=hair);d.line((4,38,4,48),fill='#69412f');d.line((9,38,9,48),fill='#69412f')
        if name=='oniisan':
            # Shaved sides and a narrow central ridge; the raised crest is head-bound geometry.
            d.rectangle((0,0,31,15),fill=skin)
            d.rectangle((11,0,12,7),fill=hair);d.rectangle((27,8,28,15),fill=hair)
            for x in (9,13):d.rectangle((x,11,x+1,11),fill='#fff5db');d.point((x,11),fill=dark)
            d.rectangle((11,14,12,14),fill='#ae765d')
            d.rectangle((18,36,37,53),fill='#a43e30');d.rectangle((20,38,29,39),fill='#d37847')
            d.rectangle((20,20,27,31),fill=coat);d.line((23,20,23,30),fill=apron);d.rectangle((40,28,55,31),fill=dark)
            d.line((9,10,10,10),fill=dark);d.line((13,10,14,10),fill=dark)
        if name=='factory_worker':
            d.rectangle((32,0,63,9),fill='#e8e4d7');d.rectangle((8,13,15,15),fill='#e8e4d7')
            d.rectangle((40,27,55,31),fill='#ddc78e')
        if name=='sister':d.rectangle((8,10,8,13),fill=hair);d.rectangle((15,9,15,13),fill=hair)
        if name in STAFF: im=STAFF[name]()  # staff skins are drawn in author_staff_skins.py
        source=LIB/'textures/human'/f'{name}.png';source.parent.mkdir(parents=True,exist_ok=True);im.save(source)
        targets=[LIB/'assembled/humans/textures'/source.name, ROOT/'art/source/human-runtime/textures'/source.name,
                 ROOT/'src/main/resources/assets/mcyukkuri/textures/entity/human'/source.name]
        for path in targets:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(source.read_bytes())
        result[name]={'source':source.relative_to(ROOT).as_posix(),'targets':[p.relative_to(ROOT).as_posix() for p in targets],
                      'sha256':hashlib.sha256(source.read_bytes()).hexdigest()}
    manifest={'status':'implemented_pending_visual_approval','authorship':'Original programmatic pixel artwork; no downloaded artwork.',
              'geometry':'src/main/java/io/github/longshou/mcyukkuri/client/HumanRenderer.java',
              'rig':'Minecraft HumanoidModel: head, hat, body, right_arm, left_arm, right_leg, left_leg; long_hair and mohawk parent=head',
              'texture_size':[64,64],'animation':'Native humanoid walking, head look and arm swing; resting pose override','textures':result}
    p=LIB/'provenance/human-npcs.json';p.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    p=ROOT/'art/source/human-runtime/manifest.json';p.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    return result
if __name__=='__main__':print(json.dumps(export()))
