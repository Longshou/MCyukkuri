#!/usr/bin/env python3
"""Adapt the six frozen deficient runtime selections without rebuilding their authoring models.

The face/sparse legacy rump reaches outside the lower hair envelope. Give only
that lower rear hair enough clearance and bind its tips to the adjacent rump.
Bean bodies retain their selected geometry and weights. --publish copies the
reviewed results to runtime resources; default only stages and renders them.
"""
from pathlib import Path
import argparse
import copy
import hashlib
import json
import math
import subprocess
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SELECTED = ROOT / 'art/source/subspecies-runtime'
PACKAGE = SELECTED / 'motion-contact'
TMP = ROOT / '.local/deficient-motion-contact'
IDS = [f'{base}_{shape}' for base in ('reimu', 'marisa') for shape in ('sparse', 'face', 'bean')]


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':'))+'\n', encoding='utf-8')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rel(path):
    return path.relative_to(ROOT).as_posix()


def adapt(model, rig):
    changed = []
    rig['element_vertex_counts'] = [part['count'] for part in model['parts']]
    if model['id'].endswith('_bean'):
        # The old bean snapshot retained the ordinary body's rig arrays after
        # replacing its skin mesh. Match the authoritative selected triangles.
        rig['vertex_weights'] = copy.deepcopy(model['weights'])
        return changed
    vertices = model['vertices']
    rear = [(i, b['pivot'][1]) for i, b in enumerate(model['bones'])
            if b['name'].startswith('rump_round_end_slice_')]
    for part in model['parts']:
        if not (part['name'] in ('back_hair', 'back_hair_lower') or part['name'].startswith('back_lock_')):
            continue
        changed.append(part['name'])
        for index in range(part['start'], part['start']+part['count']):
            y = vertices[index*8+1]
            # Anchor the original root; the low outer surface covers the 8.375
            # model-unit rump edge with 0.25 units of rear motion clearance.
            amount = max(0, min(1, (6.4-y)/(6.4-3.4)))
            vertices[index*8+2] = round(vertices[index*8+2] + 1.65*amount, 7)
            weight = round(.45*max(0, min(1, (6.4-y)/(6.4-4.0))), 7)
            target = min(rear, key=lambda item: abs(item[1]-y))[0]
            previous = model['weights'][index]
            assert previous[0] == previous[1] or previous[2] == 0, 'Expected frozen rigid rear hair'
            model['weights'][index] = [previous[0], target, weight]
        # Recompute actual triangle normals after the local envelope change.
        for first in range(part['start'], part['start']+part['count'], 3):
            a = vertices[first*8:first*8+3]
            b = [vertices[(first+1)*8+k]-a[k] for k in range(3)]
            c = [vertices[(first+2)*8+k]-a[k] for k in range(3)]
            n = [b[1]*c[2]-b[2]*c[1], b[2]*c[0]-b[0]*c[2], b[0]*c[1]-b[1]*c[0]]
            length = math.sqrt(sum(v*v for v in n))
            assert length > 1e-8
            for offset in range(3):
                vertices[(first+offset)*8+5:(first+offset)*8+8] = [round(v/length, 7) for v in n]
    rig['vertex_weights'] = copy.deepcopy(model['weights'])
    return changed


def render(models):
    TMP.mkdir(parents=True, exist_ok=True)
    for ident in IDS:
        if args.publish and ident.endswith('_bean'):
            continue  # Bean hair-fit revisions are owned by their selected-source author; never replay the old frozen geometry.
        image = Image.open(SELECTED/ident/'textures/neutral.png').convert('RGBA')
        (TMP/f'{ident}.rgba').write_bytes(image.tobytes())
        models[ident]['texture_size'] = image.width
    save(TMP/'models.json', models)
    renderer = (ROOT/'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf-8')
    # Shared matrix/weight sampler from the selected current preview, no service.
    pose = (ROOT/'standards/yukkuri-library/body-standard/previews/current/pose.js').read_text(encoding='utf-8')
    code = "const fs=require('fs');\n"+renderer+'\n'+pose+'\n'
    code += 'const dir='+json.dumps(str(TMP))+';const models=JSON.parse(fs.readFileSync(dir+"/models.json"));\n'
    code += '''for(const [id,m] of Object.entries(models))for(let i=0;i<=12;i++){
      const t=m.clips.waddle.duration*i/12;
      const pixels=renderModel(posed(m,'waddle',t),fs.readFileSync(dir+'/'+id+'.rgba'),360,340,2.8,.12,12.6,{centerY:6.8,textureSize:m.texture_size});
      fs.writeFileSync(dir+'/'+id+'-'+i+'.rgba',pixels);
    }'''
    states = [('idle', 0, 0), ('idle', 0, .7), ('idle', 0, 1.5708), ('idle', 0, 3.14159),
              ('side_bend', 0, 2.8), ('side_bend', .33, 2.8), ('side_bend', .66, 2.8), ('side_bend', 1, 2.8),
              ('stretch_shape', 0, 2.8), ('stretch_shape', .5, 2.8), ('stretch_shape', 1, 2.8), ('waddle', .25, 1.5708)]
    code += '\nconst states='+json.dumps(states)+';\n'
    code += '''for(const [id,m] of Object.entries(models))for(let i=0;i<states.length;i++){
      const [clip,phase,az]=states[i],t=m.clips[clip].duration*phase;
      const pixels=renderModel(posed(m,clip,t),fs.readFileSync(dir+'/'+id+'.rgba'),360,340,az,.12,12.6,{centerY:6.8,textureSize:m.texture_size});
      fs.writeFileSync(dir+'/'+id+'-state-'+i+'.rgba',pixels);
    }
    const checks=[];for(const [id,m] of Object.entries(models))for(const [action,clip] of Object.entries(m.clips))for(let i=0;i<=12;i++){
      const vertices=posed(m,action,clip.duration*i/12);
      if(vertices.some(x=>!Number.isFinite(x)))throw new Error(id+' '+action+' contains nonfinite posed coordinates');
      checks.push({id,action,phase:i/12,finite:true});
    }fs.writeFileSync(dir+'/pose-checks.json',JSON.stringify(checks));'''
    (TMP/'render.cjs').write_text(code, encoding='utf-8')
    subprocess.run(['node', str(TMP/'render.cjs')], check=True)
    output = PACKAGE/'previews/current'
    output.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 16)
    for ident in IDS:
        sheet = Image.new('RGB', (5*360, 3*375), (241, 237, 227))
        draw = ImageDraw.Draw(sheet)
        for phase in range(13):
            image = Image.frombytes('RGBA', (360, 340), (TMP/f'{ident}-{phase}.rgba').read_bytes())
            x, y = phase % 5*360, phase // 5*375
            sheet.paste(image, (x, y+30), image)
            draw.text((x+10, y+5), f'{ident} / waddle {phase}/12', font=font, fill='black')
        sheet.save(output/f'{ident}-waddle.png')
        sheet = Image.new('RGB', (4*360, 3*375), (241, 237, 227))
        draw = ImageDraw.Draw(sheet)
        for index, (clip, phase, az) in enumerate(states):
            image = Image.frombytes('RGBA', (360, 340), (TMP/f'{ident}-state-{index}.rgba').read_bytes())
            x, y = index % 4*360, index // 4*375
            sheet.paste(image, (x, y+30), image)
            draw.text((x+10, y+5), f'{ident} / {clip} {phase:g}', font=font, fill='black')
        sheet.save(output/f'{ident}-states.png')
    sheet = Image.new('RGB', (1080, 6*375), (241, 237, 227))
    draw = ImageDraw.Draw(sheet)
    for row, ident in enumerate(IDS):
        for col, phase in enumerate((0, 3, 9)):
            image = Image.frombytes('RGBA', (360, 340), (TMP/f'{ident}-{phase}.rgba').read_bytes())
            sheet.paste(image, (col*360, row*375+30), image)
            draw.text((col*360+10, row*375+5), f'{ident} / {phase}/12', font=font, fill='black')
    sheet.save(output/'overview.png')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--no-preview', action='store_true')
    args = parser.parse_args()
    if args.publish and any(read(SELECTED/i/'runtime.model.json').get('revision') == 'rounded-marisa-deficient-v1' for i in ('marisa_sparse','marisa_face')):
        raise SystemExit('Rounded deficient Marisa is selected. Use export_marisa_deficient_rounded.py; this frozen legacy batch would restore the old bodies.')
    records, models = {}, {}
    for ident in IDS:
        frozen = PACKAGE/'input'/ident
        frozen.mkdir(parents=True, exist_ok=True)
        for name in ('runtime.model.json', 'animation-rig.json'):
            destination = frozen/name
            if not destination.exists():
                destination.write_bytes((SELECTED/ident/name).read_bytes())
        original = read(frozen/'runtime.model.json')
        model, rig = copy.deepcopy(original), read(frozen/'animation-rig.json')
        changed = adapt(model, rig)
        assert len(original['vertices']) == len(model['vertices'])
        assert original['clips'] == model['clips'] and original['bones'] == model['bones']
        assert all(math.isfinite(v) for v in model['vertices'])
        for part in model['parts']:
            if part['name'] not in changed:
                start, end = part['start']*8, (part['start']+part['count'])*8
                assert original['vertices'][start:end] == model['vertices'][start:end]
        target = PACKAGE/'assembled'/ident
        target.mkdir(parents=True, exist_ok=True)
        if changed:
            save(target/'runtime.model.json', model)
            save(target/'animation-rig.json', rig)
        else:
            (target/'runtime.model.json').write_bytes((frozen/'runtime.model.json').read_bytes())
            save(target/'animation-rig.json', rig)
        models[ident] = model
        records[ident] = {'input': rel(frozen), 'input_model_sha256': sha(frozen/'runtime.model.json'),
                          'input_rig_sha256': sha(frozen/'animation-rig.json'), 'modified_parts': changed,
                          'output': rel(target/'runtime.model.json'), 'output_sha256': sha(target/'runtime.model.json'),
                          'rig_sha256': sha(target/'animation-rig.json'), 'clips_and_bones_unchanged': True,
                          'runtime': f'src/main/resources/assets/mcyukkuri/yukkuri/{ident}.json'}
        if args.publish:
            for name in ('runtime.model.json', 'animation-rig.json'):
                (SELECTED/ident/name).write_bytes((target/name).read_bytes())
            (ROOT/records[ident]['runtime']).write_bytes((target/'runtime.model.json').read_bytes())
    if args.publish:
        manifest = read(SELECTED/'manifest.json')
        for ident, record in records.items():
            current = manifest['models'][ident]
            current['runtime_sha256'] = record['output_sha256']
            current['motion_contact'] = {'record': rel(PACKAGE/'manifest.json'), 'input_model_sha256': record['input_model_sha256'],
                                         'modified_parts': record['modified_parts'], 'status': 'offline_reviewed_client_pending'}
        save(SELECTED/'manifest.json', manifest)
    save(PACKAGE/'manifest.json', {'script': rel(Path(__file__)), 'script_sha256': sha(Path(__file__)),
         'method': 'frozen_deficient_rear_hair_envelope_and_rump_tip_binding',
         'status': 'offline_reviewed_client_pending', 'published': args.publish, 'models': records})
    if not args.no_preview:
        render(models)
    print(json.dumps({'models': len(records), 'published': args.publish, 'manifest': rel(PACKAGE/'manifest.json')}))


if __name__ == '__main__':
    main()
