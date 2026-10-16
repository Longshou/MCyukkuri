"""Original pixel clothing for the Java humanoid rig. No external image inputs."""
from pathlib import Path
import hashlib, json, sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from author_staff_skins import ROLES as STAFF
LIB=Path(__file__).resolve().parents[1]
ROOT=LIB.parents[1]
def export():
    result={}
    for name,draw in STAFF.items():  # every skin is drawn in author_staff_skins.py
        im=draw()
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
