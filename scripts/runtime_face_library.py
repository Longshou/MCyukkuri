"""Approved face selection, shared by the ecology exporter and offline directory."""
import copy
import io
import json
import sys
from functools import lru_cache
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / 'standards/yukkuri-library'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
sys.path[:0] = [str(LIB), str(LIB/'body-standard'), str(LIB/'ecology/house-yukkuri')]
from expression_geometry import geometry_fingerprint, offset_element, offset_vertices, preset_changes, profile_point
from mesh_geometry import vertices as mesh_vertices

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def encoded(value):
    return (json.dumps(value, ensure_ascii=False, separators=(',', ':'))+'\n').encode()


@lru_cache(None)
def body_bake(model_id, library):
    root = library/'body-standard'
    config = read(root/'recipe.json')
    spec = config['sources'].get(model_id)
    if spec is None:
        return None
    source = library/spec['export']
    fingerprints = read(source/'expression-inputs.json')['presets']
    profile = read((root/config['body_source']).resolve())['body_profile']
    return config, spec, source, fingerprints, profile


@lru_cache(None)
def source_bbmodel(source, model_id):
    return read(source/(model_id+'.bbmodel'))


def rebake_patch(model, bake, index, delta):
    config, spec, source, _, profile = bake
    bb = source_bbmodel(source, model['id'])
    overall_scale = spec.get('overall_scale', 1)
    element = copy.deepcopy(bb['elements'][index])
    # The source BBModel is after overall sizing; the expression bake is before it.
    for key in ('from', 'to', 'origin'):
        if key in element:
            element[key] = [value/overall_scale for value in element[key]]
    for point in element.get('vertices', {}).values():
        point[:] = [value/overall_scale for value in point]
    patched = offset_element(element, delta, config['face_scale'],
                             lambda y: -profile_point(profile, y)[2]-config['face_clearance'])
    values = mesh_vertices(patched, bb['resolution']['width'])
    for index in range(0, len(values), 8):
        values[index:index+3] = [round(value/overall_scale, 7) for value in values[index:index+3]]
    return values


def compose_preset(model, preset, neutral_source, common, with_texture=True, library=LIB):
    """Compose one preset for a model; also used for draft previews."""
    assert any(p['name'].startswith('face_') for p in model['parts']), model['id']+' needs an explicit face_fallback row'
    atlas = None
    if with_texture:
        size = Image.open(neutral_source).width
        atlas = Image.open(neutral_source).convert('RGBA')
        assert atlas.size == (size,size)
        for p in model['parts']:
            if not p['name'].startswith('face_'):
                continue
            v = model['vertices'][p['start']*8:(p['start']+p['count'])*8]
            # Front-facing triangles carry the expression; side UVs can point at a palette texel.
            front = [v[k:k+8] for k in range(0,len(v),8) if v[k+7] < -.5]
            box = tuple(round(f([v[c] for v in front])*size) for c,f in [(3,min),(4,min),(3,max),(4,max)])
            im = Image.open(library/preset['parts'][p['name']]).convert('RGBA')
            atlas.paste(im.resize((box[2]-box[0],box[3]-box[1]),Image.Resampling.NEAREST),box[:2])
    changes = preset_changes(preset)
    baked = {entry['index']: entry['vertices'] for entry in model.get('expression_patches', {}).get(preset['id'], [])}
    bake = body_bake(model['id'], library)
    recorded = bake[3].get(preset['id']) if bake is not None else None
    changed_bake = recorded is not None and recorded != geometry_fingerprint(preset)
    entries = []
    for i,p in enumerate(model['parts']):
        if p['name'] not in changes:
            continue
        if changed_bake:
            entries.append(dict(index=i,vertices=rebake_patch(model, bake, i, changes[p['name']])))
            continue
        if i in baked:
            # An unchanged bake, or a patch retained by the normal exporter, is authoritative.
            entries.append(dict(index=i,vertices=baked[i].copy()))
            continue
        delta = changes[p['name']]
        v = model['vertices'][p['start']*8:(p['start']+p['count'])*8].copy()
        src = common[p['name']]
        ratio = [(max(v[c::8])-min(v[c::8]))/(src['to'][c]-src['from'][c]) for c in range(2)]
        entries.append(dict(index=i,vertices=offset_vertices(v, delta, ratio)))
    return atlas, entries


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
        for name, preset in faces.items():
            assert preset['status'] == 'accepted', name
            composed = None
            if 'texture_source' in row:
                files[ASSETS/'textures/entity'/ident/(name+'.png')] = (ROOT/row['texture_source']/(name+'.png')).read_bytes()
            elif name not in row['existing_textures']:
                composed = compose_preset(model, preset, ROOT/row['neutral_source'], common)
                buf = io.BytesIO(); composed[0].save(buf,format='PNG')
                files[ASSETS/'textures/entity'/ident/(name+'.png')] = buf.getvalue()
            if name in model.get('expression_patches', {}):
                continue
            if composed is None:
                composed = compose_preset(model, preset, ROOT/row['neutral_source'], common, with_texture=False)
            if composed[1]:
                patches[name] = composed[1]
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
