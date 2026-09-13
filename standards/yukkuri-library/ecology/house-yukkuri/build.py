"""Build the user-reference house design and original ecology props.

No reference images are read or bundled. Rebuild the parent library after editing
its source components; this module never copies them into a new editable source.
"""
from pathlib import Path
import argparse, base64, copy, hashlib, io, json, math, sys, uuid
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
sys.path.insert(0, str(LIB))
from mesh_geometry import vertices, poly_mesh
from reference_design import make as make_reference_design
from reference_design import SCALE, BODY_DIMENSIONS, BODY_PROFILE, FACE_OUTPUT, HAT_OFFSET, HAT_HAIR_TOP, HAIR_PROFILE, HAIR_HEM, HAIR_LAYER_HEIGHT
from anatomy_library import compile_anatomy
from reference_design import PENI_SCALE, PENI_TRAVEL, MAMU_TRAVEL, MAMU_OFFSET, MAMU_ROTATION

def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p, data):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
def image_uri(im, original_path=None):
    if original_path is not None and original_path.exists():
        with Image.open(original_path) as original:
            if original.convert('RGBA').tobytes() == im.convert('RGBA').tobytes() and original.size == im.size:
                return 'data:image/png;base64,' + base64.b64encode(original_path.read_bytes()).decode()
    buf = io.BytesIO(); im.save(buf, format='PNG')
    return 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()

def save_texture(path, im):
    # Geometry-only exports retain the original PNG bytes when pixels are unchanged.
    if path.exists():
        with Image.open(path) as original:
            if original.size == im.size and original.convert('RGBA').tobytes() == im.convert('RGBA').tobytes():
                return
    im.save(path)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--characters-only', action='store_true', help='Export static house character assets and refresh preview data; preserve existing props.')
args = parser.parse_args()
CFG = read(HERE/'config.json')
previous_data = None
if args.characters_only:
    previous = (HERE/'index.html').read_text(encoding='utf-8').split('const DATA=', 1)[1]
    previous_data = json.JSONDecoder().raw_decode(previous)[0]
OUT = HERE/'assembled'; OUT.mkdir(exist_ok=True)
provenance = {}
anatomy=read(LIB/'behaviors/anatomy.json')
for part in anatomy['parts']:part['offset']=(PENI_TRAVEL if part['id']=='peni' else MAMU_TRAVEL).copy()
anatomy.update(id='anatomy.house',status='pending_confirmation',scope='House adult models; fitted to current belly',reference='README.md')

def export(name, elements, rig, im):
    uid = lambda n: str(uuid.uuid5(uuid.NAMESPACE_URL, 'mcyukkuri/house/'+name+'/'+n))
    elements = copy.deepcopy(elements)
    nodes = {b['name']: dict(name=b['name'], uuid=uid('bone/'+b['name']), origin=b['pivot'], children=[]) for b in rig['bones']}
    for e, g in zip(elements, rig['groups']):
        e['uuid'] = uid(e['name']); nodes[g]['children'].append(e['uuid'])
    for b in rig['bones']:
        if b['parent']: nodes[b['parent']]['children'].append(nodes[b['name']])
    animations = []; exports = {}
    for label, clip in rig['clips'].items():
        animators = {}; channels = {}
        for n, chs in clip['tracks'].items():
            keys = []; channels[n] = {}
            for ch, values in chs.items():
                channels[n][ch] = {}
                for t, v in values.items():
                    val = [-v[0], -v[1], v[2]] if ch == 'rotation' else [-v[0], v[1], v[2]] if ch == 'position' else v
                    channels[n][ch][t] = val
                    keys.append(dict(channel=ch, time=float(t), interpolation='linear', data_points=[dict(zip('xyz', map(str, val)))], uuid=uid(label+n+ch+t)))
            animators[uid('bone/'+n)] = dict(name=n, type='bone', keyframes=keys)
        key = 'animation.'+name+'.'+label
        animations.append(dict(name=key, uuid=uid(key), loop='loop' if clip['loop'] else 'once', length=clip['duration'], snapping=100, animators=animators))
        exports[key] = dict(loop=clip['loop'], animation_length=clip['duration'], bones=channels)
    size = im.width
    texture_path=OUT/(name+'.png');save_texture(texture_path,im)
    model = dict(meta=dict(format_version='4.10', model_format='free', box_uv=False), name=name, resolution=dict(width=size, height=size), elements=elements,
        outliner=[nodes[b['name']] for b in rig['bones'] if b['parent'] is None], animations=animations,
        textures=[dict(name=name+'.png', path=name+'.png', id='0', uuid=uid('texture'), source=image_uri(im,texture_path), mode='bitmap', width=size, height=size, uv_width=size, uv_height=size)])
    save(OUT/(name+'.bbmodel'), model)
    save(OUT/(name+'.animation.json'), dict(format_version='1.8.0', animations=exports))
    geo = []
    for b in rig['bones']:
        g = dict(name=b['name'], pivot=b['pivot'], cubes=[])
        if b['parent']: g['parent'] = b['parent']
        for e, group in zip(elements, rig['groups']):
            if group != b['name']: continue
            if e.get('type')=='mesh':
                if 'poly_mesh' in g:raise ValueError('Only one mesh per bone is supported')
                g['poly_mesh']=poly_mesh(e);continue
            cube = dict(origin=e['from'], size=[y-x for x,y in zip(e['from'], e['to'])], uv={k: dict(uv=f['uv'][:2], uv_size=[f['uv'][2]-f['uv'][0], f['uv'][3]-f['uv'][1]]) for k,f in e['faces'].items()})
            if any(e.get('rotation', [0,0,0])): cube.update(pivot=e['origin'], rotation=e['rotation'])
            g['cubes'].append(cube)
        geo.append(g)
    save(OUT/(name+'.geo.json'), {'format_version':'1.12.0', 'minecraft:geometry':[dict(description=dict(identifier='geometry.'+name, texture_width=size, texture_height=size, visible_bounds_width=4, visible_bounds_height=4, visible_bounds_offset=[0,1,0]), bones=geo)]})
    rig['element_vertex_counts']=[len(vertices(e,size))//8 for e in elements]
    save(OUT/(name+'.rig.json'), rig)
    return dict(vertices=[v for e in elements for v in vertices(e,size)], rig=rig, texture=image_uri(im,texture_path), textureSize=size)

characters = {}; ordinary_characters = previous_data['ordinaryCharacters'] if previous_data else {}
for species in CFG['species']:
    source = LIB/'assembled'/species
    bb = read(source/(species+'.bbmodel')); rig = read(source/'animation-rig.json')
    if not args.characters_only:
        ordinary_characters[species] = dict(vertices=[v for e in bb['elements'] for v in vertices(e,128)], rig=copy.deepcopy(rig), textureSize=128,texture=image_uri(Image.open(source/(species+'.png')).convert('RGBA')))
        ordinary_characters[species]['rig']['clips']={'idle':copy.deepcopy(rig['clips']['idle'])}
    character=read(LIB/'characters'/(species+'.json'))
    standard_names={}
    component_paths=[]
    for ref in character['components']:
        kind='face' if ref.endswith('/face.json') else 'left' if ref.endswith('/hand-left.json') else 'right' if ref.endswith('/hand-right.json') else 'hair' if ref.endswith('/hair.json') else 'head' if ref.endswith('/accessories.json') else None
        if kind:
            component_paths.append(LIB/ref)
            for e in read(LIB/ref)['elements']:standard_names[e['name']]=kind
    elements,rig = make_reference_design(species,bb,rig,standard_names)
    rig['clips'].update(compile_anatomy(anatomy))
    m = export('house_'+species, elements, rig, Image.open(source/(species+'.png')).convert('RGBA'))
    m['textures'] = {}
    for expression in CFG['expressions']:
        im = Image.open(source/'textures'/(expression+'.png')).convert('RGBA')
        texture_path=OUT/('house_'+species+'_'+expression+'.png');save_texture(texture_path,im)
        m['textures'][expression] = image_uri(im,texture_path)
    characters[species] = m
    for p in [source/(species+'.bbmodel'), source/'animation-rig.json', source/(species+'.png'),LIB/'characters'/(species+'.json')] + component_paths + [source/'textures'/(e+'.png') for e in CFG['expressions']]:
        provenance[str(p.relative_to(LIB)).replace('\\','/')] = hashlib.sha256(p.read_bytes()).hexdigest()

if args.characters_only:
    # Reuse the exported scene without regenerating unrelated props. No renderer runs here.
    props = previous_data['props']
else:
    # Small original pixel tiles, not sampled from a published drawing.
    palette = [(161,156,145),(189,186,175),(211,209,197),(142,132,115), (242,220,172),(255,242,206),(209,191,155),(174,115,55), (111,75,49),(160,117,77),(218,187,143),(108,91,75)]
    atlas = Image.new('RGBA',(128,128)); draw = ImageDraw.Draw(atlas)
    for i,color in enumerate(palette):
        x=i*8; draw.rectangle((x,0,x+7,7), fill=(*color,255))
        for j in range(5):
            xx=x+(j*3+i)%8; yy=(j*5+i)%8
            draw.point((xx,yy),fill=tuple(min(255,v+12) for v in color)+(255,))

    def box(name,lo,hi,color,bone='root',rotation=None):
        u=color*8
        return dict(name=name,type='cube',**{'from':lo,'to':hi},origin=[(a+b)/2 for a,b in zip(lo,hi)],rotation=rotation or [0,0,0],box_uv=False,
            faces={side:dict(uv=[u,0,u+8,8],texture=0) for side in ['north','south','east','west','up','down']},bone=bone)
    def prop(name,elements):
        groups=[e.pop('bone') for e in elements]
        rig=dict(bones=[dict(name=n,parent=None,pivot=[0,0,0]) for n in dict.fromkeys(groups)],groups=groups,clips={'idle':dict(duration=1,loop=True,tracks={})})
        return export(name,elements,rig,atlas)

    nest=[]
    for i in range(28):
        a=i*math.tau/28; x=3.4*math.cos(a); z=2.15*math.sin(a)
        nest.append(box('lint_rim_'+str(i),[x-.55,.04,z-.38],[x+.55,.42+(i%3)*.09,z+.38],i%3,rotation=[0,-a*180/math.pi,0]))
    for i in range(22):
        a=i*2.4; r=math.sqrt((i+.5)/22)
        x=2.85*r*math.cos(a);z=1.65*r*math.sin(a)
        nest.append(box('lint_bedding_'+str(i),[x-.48,.015,z-.28],[x+.48,.14,z+.28],i%3))
    for i in range(20):
        a=i*2.4;x=3.45*math.cos(a);z=2.2*math.sin(a)
        nest.append(box('loose_fiber_'+str(i),[x-.34,.36,z-.022],[x+.34,.4,z+.022],2,rotation=[0,i*41,0]))
    props={'nest':prop('lint_nest',nest)}
    egg=[]
    for i,(y,w,d) in enumerate([(0,.35,.29),(.12,.48,.38),(.32,.47,.37),(.52,.36,.28),(.67,.20,.17)]):
        egg.append(box('sugar_shell_'+str(i),[-w,y,-d],[w,y+.17,d],4 if i%2 else 5))
    props['egg']=prop('house_egg',egg)
    shell=[]
    for i in range(8):
        a=i*math.tau/8;x=.42*math.cos(a);z=.32*math.sin(a)
        shell.append(box('broken_shell_'+str(i),[x-.12,0,z-.10],[x+.12,.13+(i%3)*.045,z+.10],4+i%2,rotation=[0,-i*45,0]))
    props['shell']=prop('house_eggshell',shell)
    props['crumb']=prop('food_crumb',[box('crumb',[-.22,0,-.18],[.22,.28,.18],7),box('crumb_soft',[-.17,.28,-.14],[.12,.38,.12],10)])
    props['lint']=prop('loose_lint',[box('lint',[-.3,0,-.22],[.3,.21,.22],1),box('fiber',[-.44,.16,-.025],[.44,.20,.025],2)])
    furniture=[box('floor',[-12,-.3,-9],[12,-.02,8],9),box('wall',[-12,-.3,7.5],[12,8,8],10),box('skirting',[-12,0,7.05],[12,.75,7.5],8),box('shelf',[-8,6.3,1.6],[8,6.8,7],8),box('left_leg',[-8,0,2],[-7.25,6.3,7],8),box('right_leg',[7.25,0,2],[8,6.3,7],8)]
    for i in range(8):furniture.append(box('floor_joint_'+str(i),[-12+i*3,-.019,-9],[-11.97+i*3,-.01,7],8))
    props['room']=prop('furniture_gap',furniture)

expression_index=read(LIB/'expressions/index.json')
expression_labels={k:read(LIB/expression_index['presets'][k])['label'] for k in CFG['expressions']}
for rel in ['components/common/rump.json','components/common/anatomy.json','components/common/anatomy-rig.json','behaviors/anatomy.json','anatomy_library.py','runtime/anatomy.js','runtime/motion.js']:
    provenance[rel]=hashlib.sha256((LIB/rel).read_bytes()).hexdigest()
data=dict(config=CFG,characters=characters,ordinaryCharacters=ordinary_characters,props=props,expressionLabels=expression_labels,anatomy=anatomy)
runtime='const DATA='+json.dumps(data,ensure_ascii=False,separators=(',',':'))+';\n'
for p in [LIB/'runtime/motion.js', LIB/'runtime/render.js', LIB/'runtime/anatomy.js', HERE/'lifecycle.js', HERE/'viewer.js']:
    runtime+=p.read_text(encoding='utf-8-sig')+'\n'
(HERE/'index.html').write_text((HERE/'viewer.html').read_text(encoding='utf-8').replace('__RUNTIME__',runtime),encoding='utf-8')
preview=HERE/'previews/current/index.html';preview.parent.mkdir(parents=True,exist_ok=True)
preview_revision=hashlib.sha256((HERE/'index.html').read_bytes()).hexdigest()[:12]
preview_target='../../index.html?rev='+preview_revision
preview.write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width,initial-scale=1">'
    '<meta http-equiv="Cache-Control" content="no-cache, no-store, must-revalidate">'
    '<title>家油 · 当前造型预览</title><style>body{margin:0}iframe{width:100%;height:100vh;border:0;display:block}</style>'
    f'<iframe src="{preview_target}" title="家油当前造型预览"></iframe>'
    f'<noscript><a href="{preview_target}">打开完整预览（需要 JavaScript）</a></noscript></html>',encoding='utf-8')

design_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [HERE/'reference_design.py',HERE/'config.json',HERE/'build.py',HERE/'viewer.html',HERE/'viewer.js',HERE/'render_review.py',HERE/'mesh_geometry.py']}
design_parameters=dict(peni_scale=PENI_SCALE,peni_travel=PENI_TRAVEL,mamu_travel=MAMU_TRAVEL,mamu_offset=MAMU_OFFSET,mamu_rotation=MAMU_ROTATION,body_dimensions=BODY_DIMENSIONS,body_profile=BODY_PROFILE,face_output=FACE_OUTPUT,hat_offset=HAT_OFFSET,hat_hair_top=HAT_HAIR_TOP,hair_profile=HAIR_PROFILE,hair_hem=HAIR_HEM,hair_layer_height=HAIR_LAYER_HEIGHT)
outputs={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in OUT.iterdir() if p.is_file() and p.name.startswith(('house_reimu.','house_marisa.'))}
save(OUT/'provenance.json',dict(inputs=provenance,design_sources=design_hashes,design_parameters=design_parameters,character_outputs=outputs,config='../config.json',references='../REFERENCES.md',preview='../previews/current/index.html',design=CFG['design'],appearance_status=CFG['appearance_status'],runtime_validation='not_performed',external_images_included=False))
print('Built low-poly house characters with canonical components and expressions.')
