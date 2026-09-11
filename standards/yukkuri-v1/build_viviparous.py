"""Build a portable, offline viviparous preview using standard Reimu components."""
from pathlib import Path
import base64
import hashlib
import json
from PIL import Image
from build_lifecycle import vertices

ROOT=Path(__file__).resolve().parent
def read(ref): return json.loads((ROOT/ref).read_text(encoding='utf-8-sig'))
def build():
    character=read('characters/reimu.json');sleep=read('expressions/reimu/sleep.json')
    all_elements=[e for ref in character['components'] if not ref.endswith('/puff.json') for e in read(ref)['elements']]
    neutral=[e for e in all_elements if not e['bone'].startswith('face_effort')]
    effort=[e for e in all_elements if not e['bone'].startswith('face_rest')]
    mesh=lambda elements:[v for e in elements for v in vertices(e)]
    # Carve a rectangular pocket out of the lower front body. Runtime fills
    # its front with a continuous annulus and a deforming inner wall.
    patch=read('behaviors/viviparous-birth.json')['outlet_surface']['patch']
    def carve(elements):
        result=[]
        for e in elements:
            lo=e['from'];hi=e['to']
            if not e['name'].startswith('soft_head_slice_') or lo[1]>=patch['top']:
                result.append(e);continue
            for axis,a,b in [(0,lo[0],-patch['half_width']),(0,patch['half_width'],hi[0]),(2,patch['back_z'],hi[2])]:
                if a>=b:continue
                part={**e,'from':lo.copy(),'to':hi.copy()}
                part['from'][axis]=a;part['to'][axis]=b
                if axis==2:
                    part['from'][0]=-patch['half_width'];part['to'][0]=patch['half_width']
                result.append(part)
        return mesh(result)
    cfg=read('behaviors/viviparous-birth.json')
    texture=Image.open(ROOT/character['textures']['neutral']).convert('RGB')
    skin=texture.getpixel((1,49));crease=texture.getpixel((111,111))
    cfg['palette'] += [list(skin),list(crease),[round(.65*a+.35*b) for a,b in zip(skin,crease)]]
    def refine(flat):
        out=[]
        def split(a,b,c):
            distance=lambda a,b:max(abs(a[k]-b[k]) for k in range(3))
            edges=[(distance(a,b),a,b,c),(distance(b,c),b,c,a),(distance(c,a),c,a,b)]
            length,p,q,r=max(edges,key=lambda e:e[0])
            if length<=2:
                out.extend(a+b+c);return
            m=[(x+y)/2 for x,y in zip(p,q)]
            split(p,m,r);split(m,q,r)
        for k in range(0,len(flat),24):split(flat[k:k+8],flat[k+8:k+16],flat[k+16:k+24])
        return out
    data={'viviparous':read('behaviors/viviparous-birth.json'),'growth':read('profiles/growth.json'),
          'vertices':mesh(neutral),'effort_vertices':mesh(effort),
          'delivery_vertices':carve(neutral),'delivery_effort_vertices':carve(effort),
          'sleep_vertices':mesh([e for e in neutral if e['name'] not in sleep['remove_parts']]),
          'sleep':sleep,'body_height':8.48,
          'texture':'data:image/png;base64,'+base64.b64encode((ROOT/character['textures']['neutral']).read_bytes()).decode()}
    data['viviparous']=cfg
    data['child_sleep_vertices']=refine(data['sleep_vertices'])
    runtimes=['runtime/growth.js','runtime/stem-birth.js','runtime/viviparous-birth.js','runtime/render.js']
    html=(ROOT/'runtime/viviparous.template.html').read_text(encoding='utf-8').replace('__DATA__',json.dumps(data,ensure_ascii=False,separators=(',',':'))).replace('__RUNTIME__','\n'.join((ROOT/ref).read_text(encoding='utf-8') for ref in runtimes))
    output=ROOT/'viviparous';output.mkdir(exist_ok=True)
    (output/'index.html').write_text(html,encoding='utf-8')
    sources=character['components']+runtimes+['characters/reimu.json',character['textures']['neutral'],'behaviors/viviparous-birth.json','profiles/growth.json','expressions/reimu/sleep.json','runtime/viviparous.template.html','references/viviparous-comics.md','build_lifecycle.py','build_viviparous.py']
    (output/'source-provenance.json').write_text(json.dumps({'character':'standard Reimu','minecraft_integration':False,'source_sha256':{ref:hashlib.sha256((ROOT/ref).read_bytes()).hexdigest() for ref in sources}},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Built {output / "index.html"}: {len(neutral)} standard cubes; separate delivery action')
if __name__=='__main__':build()
