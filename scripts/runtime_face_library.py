"""Approved face selection, shared by the ecology exporter and offline directory."""
import copy
import io
import json
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / 'standards/yukkuri-library'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def encoded(value):
    return (json.dumps(value, ensure_ascii=False, separators=(',', ':'))+'\n').encode()

def outputs():
    index = read(LIB/'expressions/index.json')
    approved = read(LIB/index['standard_set'])['entries']
    selection = read(LIB/'expressions/runtime.json')
    faces = {r['id']: read(LIB/r['preset']) for r in approved}
    files = {}
    models = {}
    common = {e['name']: e for e in read(LIB/'components/common/face.json')['elements']}
    for ident, row in selection['models'].items():
        model = read(ROOT/row['model'])
        for name, source in row['retained_textures'].items():
            files[ASSETS/'textures/entity'/ident/(name+'.png')] = (ROOT/source).read_bytes()
        patches = {}
        size = Image.open(ROOT/row['neutral_source']).width
        boxes = {}
        for p in model['parts']:
            if not p['name'].startswith('face_'):
                continue
            v = model['vertices'][p['start']*8:(p['start']+p['count'])*8]
            # Front-facing triangles carry the expression; side UVs can point at a palette texel.
            front = [v[k:k+8] for k in range(0,len(v),8) if v[k+7] < -.5]
            boxes[p['name']] = tuple(round(f([v[c] for v in front])*size) for c,f in [(3,min),(4,min),(3,max),(4,max)])
        assert boxes, ident+' needs an explicit face_fallback row'
        for name, preset in faces.items():
            assert preset['status'] == 'accepted', name
            if 'texture_source' in row:
                files[ASSETS/'textures/entity'/ident/(name+'.png')] = (ROOT/row['texture_source']/(name+'.png')).read_bytes()
            elif name not in row['existing_textures']:
                atlas = Image.open(ROOT/row['neutral_source']).convert('RGBA')
                assert atlas.size == (size,size)
                for slot, box in boxes.items():
                    im = Image.open(LIB/preset['parts'][slot]).convert('RGBA')
                    atlas.paste(im.resize((box[2]-box[0],box[3]-box[1]),Image.Resampling.NEAREST),box[:2])
                buf = io.BytesIO(); atlas.save(buf,format='PNG')
                files[ASSETS/'textures/entity'/ident/(name+'.png')] = buf.getvalue()
            if name in model.get('expression_patches', {}):
                continue
            changes = copy.deepcopy(preset.get('geometry_offsets', {}))
            if preset.get('cheek_scale',1) != 1:
                for slot in ('face_blush_left','face_blush_right'):
                    changes[slot] = {'scale':[1,preset['cheek_scale'],1]}
            entries = []
            for i,p in enumerate(model['parts']):
                if p['name'] not in changes:
                    continue
                delta = changes[p['name']]
                v = model['vertices'][p['start']*8:(p['start']+p['count'])*8].copy()
                src = common[p['name']]
                for c in range(2):
                    low,high = min(v[c::8]),max(v[c::8]); center = (low+high)/2
                    ratio = (high-low)/(src['to'][c]-src['from'][c])
                    for k in range(c,len(v),8):
                        v[k] = round(center+(v[k]-center)*delta.get('scale',[1,1,1])[c]+delta.get('position',[0,0,0])[c]*ratio,7)
                entries.append(dict(index=i,vertices=v))
            if entries:
                patches[name] = entries
        models[ident] = dict(bone_names=[b['name'] for b in model['bones']],clips={},
                            expression_patches=patches,face_aliases=row['face_aliases'],
                            selectable=sorted(set(faces)|set(row['existing_textures'])|set(row['legacy_faces'])))
    animations = selection['animations']
    # One duration (ticks) per frame; the client cycles them in order.
    assert all(a == b['frames'][0] and set(b['frames']) <= set(faces) and len(b['frame_ticks']) == len(b['frames']) >= 2
               and all(t > 0 for t in b['frame_ticks']) for a,b in animations.items())
    files[ASSETS/'yukkuri_presentation/face_library.json'] = encoded(dict(format=1,faces=list(faces),aliases={**index['aliases'],**selection['aliases']},animations=animations,models=models,face_fallback=selection['face_fallback']))
    ledger = read(ROOT/'art/runtime-sources.json')
    ledger['face_library'] = dict(selection='standards/yukkuri-library/expressions/runtime.json',
        exporter='scripts/export_ecology_expressions.py', helper='scripts/runtime_face_library.py',
        approval='standards/yukkuri-library/provenance/standard-expressions.json',
        runtime=[p.relative_to(ROOT).as_posix() for p in files])
    files[ROOT/'art/runtime-sources.json'] = (json.dumps(ledger,ensure_ascii=False,indent=2)+'\n').encode()
    return files
