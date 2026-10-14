"""Re-assemble the frozen body-standard inputs of every Marisa from edited cube components.

The inputs/<id>/ folders are the pre-rounding library exports (built by the cube-era
build.py from inputs/<id>/character.json). This repeats that assembly for characters whose
registration uses components/marisa/hand-left.json and rewrites their bbmodel, geo.json,
animation-rig.json, selected-rig.json (bones, groups, rigid weights), animation.json, the
model data inside index.html and the hashes in inputs/manifest.json. Textures, expression
atlases and animation curves are kept, so edited parts must reuse existing UV cells.
With unchanged components the files are reproduced byte for byte.
"""
from pathlib import Path
import copy, hashlib, json, math, sys, uuid

R = Path(__file__).resolve().parents[3]  # standards/yukkuri-library
INPUTS = 'body-standard/inputs'
sys.path.insert(0, str(R))
from geometry import vertices

def read(p): return json.loads((R/p).read_text(encoding='utf-8-sig'))
def save(p, v): (R/p).write_text(json.dumps(v, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')

def assemble(name, c):
    rig = read(c['rig']); bones = copy.deepcopy(rig['bones'])
    for ref in c.get('rig_components', []): bones.extend(read(ref)['bones'])
    for b in bones:
        if b['name'].endswith('_skin_anchor'): b['parent'] = 'head'
    ordered, pending, done = [], bones[:], set()
    while pending:
        ready = [b for b in pending if b['parent'] is None or b['parent'] in done]; assert ready
        for b in ready: ordered.append(b); done.add(b['name']); pending.remove(b)
    elements = [copy.deepcopy(e) for ref in c['components'] for e in read(ref)['elements']]
    groups = [e.pop('bone') for e in elements]
    assert set(groups) <= done, (name, set(groups)-done)
    # Face UVs come from the existing binding; edited parts must not take over its texels.
    binding = read(c['expression_binding'])['uv']
    def cells(uv):
        u, v, U, V = uv
        return {(x, y) for x in range(math.floor(min(u, U)), math.ceil(max(u, U))) for y in range(math.floor(min(v, V)), math.ceil(max(v, V)))}
    used = set()
    for e in elements:
        if e['name'] not in binding:
            for f in e['faces'].values(): used |= cells(f['uv'])
    for part, uv in binding.items(): assert not (cells(uv) & used), (name, part, 'binding overlaps geometry UV')
    for e in elements:
        if e['name'] in binding: e['faces']['north']['uv'] = binding[e['name']]
    uid = lambda n: str(uuid.uuid5(uuid.NAMESPACE_URL, 'yukkuri-library/'+name+'/'+n))
    for e in elements: e['uuid'] = uid(e['name'])
    return ordered, elements, groups, uid

def sync(name):
    out = f'{INPUTS}/{name}'; c = read(f'{out}/character.json')
    bones, elements, groups, uid = assemble(name, c); names = {b['name'] for b in bones}
    rig = read(f'{out}/animation-rig.json'); old_elements = read(f'{out}/{name}.bbmodel')['elements']
    rig['bones'], rig['groups'] = bones, groups
    for clip in rig['clips'].values(): clip['tracks'] = {n: t for n, t in clip['tracks'].items() if n in names}
    # Character-only motions are compiled straight from their variant sources, as the
    # cube-era build.py did, so edits to e.g. the tsumuri shell motions reach the inputs.
    hidden = read(c['rig'])['default_hidden']; extra = {}
    for cid, ref in c.get('extra_motions', {}).items():
        clip = read(ref); clip['tracks'] = {n: t for n, t in clip['tracks'].items() if n in names}
        for n in ['puff_eye_left', 'puff_eye_right', 'puff_mouth']:
            if n in names: clip['tracks'][n] = {'scale': {'0': [.000001]*3}}
        for n in hidden: clip['tracks'].setdefault(n, {}).setdefault('scale', {'0': [.000001]*3})
        rig['clips'][cid] = extra[cid] = clip

    bb = read(f'{out}/{name}.bbmodel')
    nodes = {b['name']: dict(name=b['name'], uuid=uid('bone/'+b['name']), origin=b['pivot'], children=[]) for b in bones}
    for e, g in zip(elements, groups): nodes[g]['children'].append(e['uuid'])
    for b in bones:
        if b['parent']: nodes[b['parent']]['children'].append(nodes[b['name']])
    bb['elements'] = elements; bb['outliner'] = [nodes[b['name']] for b in bones if b['parent'] is None]
    live = {nodes[n]['uuid'] for n in names}
    for a in bb['animations']: a['animators'] = {k: v for k, v in a['animators'].items() if k in live}

    anim = read(f'{out}/{name}.animation.json')
    for a in anim['animations'].values(): a['bones'] = {n: v for n, v in a['bones'].items() if n in names}
    for cid, clip in extra.items():
        animators, channels = {}, {}
        for n, chs in clip['tracks'].items():
            kfs = []; channels[n] = {}
            for ch, keys in chs.items():
                channels[n][ch] = {}
                for t, v in keys.items():
                    val = [-v[0], -v[1], v[2]] if ch == 'rotation' else [-v[0], v[1], v[2]] if ch == 'position' else v
                    channels[n][ch][t] = val
                    kfs.append(dict(channel=ch, time=float(t), interpolation='linear', data_points=[dict(zip('xyz', [format(x, '.8f') for x in val]))], uuid=uid(cid+n+ch+t)))
            animators[uid('bone/'+n)] = dict(name=n, type='bone', keyframes=kfs)
        an = 'animation.'+name+'.'+cid
        entry = dict(name=an, uuid=uid(an), loop='loop' if clip['loop'] else 'once', length=clip['duration'], snapping=100, animators=animators)
        bb['animations'] = [entry if a['name'] == an else a for a in bb['animations']]
        anim['animations'][an] = dict(loop=clip['loop'], animation_length=clip['duration'], bones=channels)

    geo = read(f'{out}/{name}.geo.json'); geo_bones = []
    for b in bones:
        g = dict(name=b['name'], pivot=b['pivot'], cubes=[])
        if b['parent']: g['parent'] = b['parent']
        for e, n in zip(elements, groups):
            if n != b['name']: continue
            cube = dict(origin=e['from'], size=[y-x for x, y in zip(e['from'], e['to'])],
                        uv={k: dict(uv=f['uv'][:2], uv_size=[f['uv'][2]-f['uv'][0], f['uv'][3]-f['uv'][1]]) for k, f in e['faces'].items()})
            if any(e.get('rotation', [0, 0, 0])): cube.update(pivot=e['origin'], rotation=e['rotation'])
            g['cubes'].append(cube)
        geo_bones.append(g)
    geo['minecraft:geometry'][0]['bones'] = geo_bones

    html_path = R/out/'index.html'; html = html_path.read_text(encoding='utf-8-sig')
    start = html.index('const DATA=')+len('const DATA='); data, length = json.JSONDecoder().raw_decode(html[start:])
    data['model']['vertices'] = [v for e in elements for v in vertices(e, data['textureSize'])]
    data['model']['rig'] = rig
    index = {e['uuid']: i for i, e in enumerate(elements)}
    for patches in data['model'].get('expressionPatches', {}).values():
        for p in patches: p['index'] = index[old_elements[p['index']]['uuid']]
    html = html[:start]+json.dumps(data, ensure_ascii=False, separators=(',', ':'))+html[start+length:]

    if (R/out/'selected-rig.json').exists():  # marisa only; body-standard/build.py prefers it
        selected = read(f'{out}/selected-rig.json'); index_of = {b['name']: i for i, b in enumerate(bones)}
        for clip in selected['clips'].values(): clip['tracks'] = {n: t for n, t in clip['tracks'].items() if n in names}
        selected.update(bones=bones, groups=groups, element_vertex_counts=[36]*len(groups),
                        vertex_weights=[[index_of[g], index_of[g], 0] for g in groups for _ in range(36)])
        save(f'{out}/selected-rig.json', selected)
    save(f'{out}/{name}.bbmodel', bb); save(f'{out}/animation-rig.json', rig)
    save(f'{out}/{name}.animation.json', anim); save(f'{out}/{name}.geo.json', geo)
    html_path.write_text(html, encoding='utf-8')
    print(name, len(elements), 'cubes', len(bones), 'bones; textures and animation curves preserved')

if __name__ == '__main__':
    targets = [p.parent.name for p in sorted((R/INPUTS).glob('*/character.json'))
               if 'components/marisa/hand-left.json' in read(p.relative_to(R))['components']]
    for name in sys.argv[1:] or targets: sync(name)
    # Stored hashes are raw bytes of a Windows checkout (CRLF for text=auto files such as
    # .html). Only files whose content changed are rehashed, in the convention already used.
    manifest = read(f'{INPUTS}/manifest.json'); sha = lambda b: hashlib.sha256(b).hexdigest()
    crlf = lambda b: b.replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')
    for key, stored in manifest['files'].items():
        if key.split('/')[1] not in (sys.argv[1:] or targets): continue
        data = (R/'body-standard'/key).read_bytes()
        if stored in (sha(data), sha(crlf(data))) or key.endswith('.png'):
            if key.endswith('.png'): manifest['files'][key] = sha(data)
            continue
        windows = Path(key).suffix in ('.html', '.js', '.css', '.py', '.txt')
        manifest['files'][key] = sha(crlf(data) if windows else data)
    (R/INPUTS/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
