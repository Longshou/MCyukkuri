"""Build the offline growth / stem-birth preview from the portable standard only.

Run after changing a component, growth profile, behavior, or lifecycle runtime.
Does not rebuild or overwrite the existing character assembly or animation files.
"""
from pathlib import Path
import base64
import hashlib
import json

ROOT = Path(__file__).resolve().parent

def read(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8-sig'))

def vertices(element):
    x,y,z=element['from']; X,Y,Z=element['to']
    faces={
        'north': ([(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)], [0,0,-1]),
        'south': ([(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)], [0,0,1]),
        'east': ([(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)], [1,0,0]),
        'west': ([(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)], [-1,0,0]),
        'up': ([(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)], [0,1,0]),
        'down': ([(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)], [0,-1,0]),
    }
    result=[]
    for face,(points,normal) in faces.items():
        u,v,U,V=element['faces'][face]['uv']; uv=[(u,v),(U,v),(U,V),(u,V)]
        for i in (0,1,2,0,2,3):
            result.extend([*points[i],uv[i][0]/128,uv[i][1]/128,*normal])
    return result

def build():
    character=read('characters/reimu.json')
    hidden=character['default_hidden']
    elements=[]
    # Neutral binding pose: exclude alternate-expression and puff geometry entirely.
    for ref in character['components']:
        if ref.endswith('/puff.json'):
            continue
        for element in read(ref)['elements']:
            if any(element['bone'].startswith(group) for group in hidden):
                continue
            elements.append(element)
    body=read('components/common/body.json')['elements']
    bounds=[[min(e['from'][axis] for e in body),max(e['to'][axis] for e in body)] for axis in range(3)]
    hair=read('components/reimu/hair.json')['elements']
    # Attach on the actual crown, not the top of the red ribbon.
    crown_y=max(e['to'][1] for e in hair)
    sleep=read('expressions/reimu/sleep.json')
    data={
        'growth':read('profiles/growth.json'), 'birth':read('behaviors/stem-birth.json'),
        'stem':read('components/common/stem.json'), 'body_bounds':bounds, 'crown_y':crown_y,
        'vertices':[v for e in elements for v in vertices(e)],
        'sleep':sleep,
        'sleep_vertices':[v for e in elements if e['name'] not in sleep['remove_parts'] for v in vertices(e)],
        'texture':'data:image/png;base64,'+base64.b64encode((ROOT/character['textures']['neutral']).read_bytes()).decode(),
    }
    runtime='\n'.join((ROOT/'runtime'/name).read_text(encoding='utf-8') for name in ['growth.js','stem-birth.js','render.js'])
    html=(ROOT/'runtime/lifecycle.template.html').read_text(encoding='utf-8').replace('__DATA__',json.dumps(data,separators=(',',':'),ensure_ascii=False)).replace('__RUNTIME__',runtime)
    output=ROOT/'lifecycle'; output.mkdir(exist_ok=True)
    (output/'index.html').write_text(html,encoding='utf-8')
    landing=ROOT/'index.html'
    if landing.exists():
        text=landing.read_text(encoding='utf-8')
        if 'lifecycle/index.html' not in text:
            text=text.replace('</html>','<p><a href="lifecycle/index.html">成长体型与灵梦茎生 · 1–5 只闭眼实子</a></p></html>')
            landing.write_text(text,encoding='utf-8')
    sources=character['components']+[character['textures']['neutral'],'profiles/growth.json','behaviors/stem-birth.json','components/common/stem.json','expressions/reimu/sleep.json','runtime/growth.js','runtime/stem-birth.js','runtime/render.js','runtime/lifecycle.template.html','build_lifecycle.py']
    provenance={
        'character':'standard Reimu, neutral binding pose', 'body_bounds':bounds,'crown_y':crown_y,
        'source_sha256':{ref:hashlib.sha256((ROOT/ref).read_bytes()).hexdigest() for ref in sources},
        'minecraft_integration':False,
    }
    (output/'source-provenance.json').write_text(json.dumps(provenance,ensure_ascii=False,indent=2),encoding='utf-8')
    print(f'Built {output / "index.html"}; {len(elements)} standard cubes; body {bounds}; crown {crown_y}')

if __name__=='__main__':
    build()
