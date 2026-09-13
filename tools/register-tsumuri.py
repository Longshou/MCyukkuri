"""One-time promotion of the approved shell into the portable standard library."""
from pathlib import Path
from PIL import Image
import json, copy

root=Path(__file__).resolve().parents[1]
lib=root/'standards/yukkuri-library'
source=root/'standards/yukkuri-v1/subspecies'
if (lib/'characters/marisa_tsumuri.json').exists():
    raise SystemExit('Already registered. Edit the library sources; do not overwrite them from the compatibility preview.')
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def save(path,data):
    p=lib/path;p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

shell=read(source/'tsumuri/accessory-components.json')
save('components/marisa-tsumuri/shell.json',dict(id='marisa_tsumuri.shell',status='user_approved',elements=shell['elements']))
save('components/marisa-tsumuri/shell-rig.json',dict(bones=shell['bones']))
clips=read(source/'tsumuri/animation-rig.json')['clips']
names={'still':('still','静止'),'move':('shell_sway','螺壳轻摆'),'retract':('shell_retract','缩入 / 探出')}
extra={}
for old,(name,label) in names.items():
    clip=copy.deepcopy(clips[old]);clip.update(id=name,label=label,kind='variant')
    ref=f'motions/variants/marisa-tsumuri/{name}.json';save(ref,clip);extra[name]=ref
c=read(lib/'characters/marisa.json')
c.update(id='marisa_tsumuri',label='螺壳魔理沙',status='user_approved',base_character='marisa',
         base_texture='textures/marisa-tsumuri/base.png',expression_binding='bindings/marisa_tsumuri.json',
         motion_ids=['idle'],extra_motions=extra,preview=dict(span=18.5,centerY=10.5,default_motion='still'))
c['components']=[r for r in c['components'] if r!='components/marisa/accessories.json']+['components/marisa-tsumuri/shell.json']
c['rig_components']+=['components/marisa-tsumuri/shell-rig.json']
save('characters/marisa_tsumuri.json',c)
im=Image.new('RGBA',(256,256));im.paste(Image.open(lib/'textures/marisa/base.png'),(0,0))
im.paste(Image.open(source/'tsumuri/marisa.png').crop((128,0,256,32)),(128,0))
target=lib/c['base_texture'];target.parent.mkdir(parents=True,exist_ok=True);im.save(target)
save('provenance/marisa-tsumuri.json',dict(status='user_approved',source='Approved centered shell from yukkuri-v1/subspecies; imported once as editable component.',pivot=[0,5.9,1.75],rotation=[-25,0,0],size=.9,whorls=6,spikes=8,rear_edge_y=2.6642408454,anatomy='Shared standard adult components, rig and behavior; no copied anatomy source.'))
