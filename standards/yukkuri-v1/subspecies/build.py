"""Build three editable Marisa variants from the portable ordinary-species standard.

No extracted game art is read. Run with Python + Pillow. The body's bind geometry,
UVs and independent braid hierarchy are inherited without scaling or reshaping.
"""
from pathlib import Path
import base64
import copy
import hashlib
import io
import json
import math
import uuid
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
STANDARD = HERE.parent
SIZE = 256
read = lambda p: json.loads(p.read_text(encoding='utf-8'))


def save(p, value):
    p.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')


def uid(name):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'mcyukkuri/marisa-variants/v1/' + name))


# New, code-authored pixel materials occupy only the unused right half of a larger
# atlas. Existing 128x128 artwork and its pixel UV coordinates remain untouched.
MATERIALS = {
    'shell': (128, 0, (207, 213, 169)),
    'shell_light': (160, 0, (224, 227, 191)),
    'shell_dark': (192, 0, (130, 143, 109)),
    'shell_lip': (224, 0, (176, 185, 140)),
    'wood': (128, 32, (122, 77, 49)),
    'wood_edge': (160, 32, (80, 48, 33)),
    'quilt': (192, 32, (196, 61, 65)),
    'quilt_dark': (224, 32, (145, 40, 47)),
    'hem': (128, 64, (247, 235, 208)),
    'quilt_light': (160, 64, (215, 82, 81)),
}


def atlas(source):
    im = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    im.paste(Image.open(source).convert('RGBA'), (0, 0))
    draw = ImageDraw.Draw(im)
    for name, (x, y, color) in MATERIALS.items():
        draw.rectangle((x, y, x + 31, y + 31), fill=(*color, 255))
        for v in range(32):
            for u in range(32):
                delta = ((u * 17 + v * 29) % 7 - 3)
                if name.startswith('wood'):
                    delta += -10 if (v + (u // 9) % 2) % 9 == 0 else 0
                elif name.startswith('quilt'):
                    delta += -5 if u % 8 == 0 or v % 8 == 0 else 0
                draw.point((x + u, y + v), fill=tuple(max(0, min(255, c + delta)) for c in color) + (255,))
    return im


def data_url(im):
    buffer = io.BytesIO()
    im.save(buffer, format='PNG')
    return 'data:image/png;base64,' + base64.b64encode(buffer.getvalue()).decode()


def cube(name, lower, upper, material, bone, uv=None):
    if uv is None:
        x, y, _ = MATERIALS[material]
        uv = [x, y, x + 16, y + 16]
    return dict(name=name, type='cube', uuid=uid(name),
                **{'from': [round(v, 6) for v in lower], 'to': [round(v, 6) for v in upper]},
                origin=[0, 0, 0], rotation=[0, 0, 0], box_uv=False, rescale=False,
                autouv=0, color=2, bone=bone,
                faces={face: dict(uv=uv[:], texture=0) for face in ('north', 'east', 'south', 'west', 'up', 'down')})


def ring(name, rx, rz, cy, height, zc, thickness, material, bone, uv=None, strips=12, power=2):
    """An open, stepped elliptical ring made from editable cuboids."""
    out = []
    for j in range(strips):
        z0 = -rz + 2 * rz * j / strips
        z1 = -rz + 2 * rz * (j + 1) / strips
        # Midpoint sampling gives the deliberately stepped Minecraft silhouette.
        zm = (z0 + z1) / 2
        extent = rx * max(0, 1 - abs(zm / rz) ** power) ** (1/power)
        inner_rx, inner_rz = max(0, rx - thickness), max(0, rz - thickness)
        inner = inner_rx * max(0, 1 - abs(zm / inner_rz) ** power) ** (1/power) if inner_rz and abs(zm) < inner_rz else 0
        # Adjacent stepped annulus strips must overlap. Midpoint-only inner
        # radii otherwise leave longitudinal slots on steep parts of the arc.
        neighbor_midpoint=max(abs(z0),abs(z1))+(z1-z0)/2
        edge_extent=rx*max(0,1-abs(neighbor_midpoint/rz)**power)**(1/power)
        inner=min(inner,max(0,edge_extent-.015))
        for side in (-1, 1):
            lo, hi = sorted((side * inner, side * extent))
            if hi - lo > .001:
                out.append(cube(f'{name}_{j}_{side}', [lo, cy, zc + z0], [hi, cy + height, zc + z1], material, bone, uv))
    return out


def shell_parts():
    from shell import build_shell
    return build_shell(cube, ring)


def boat_parts(accessories):
    out = []
    black = [64, 48, 80, 64]
    white = [80, 48, 96, 64]
    # Open brim and cup below the waterline; no solid lid across the passenger.
    out += ring('boat_brim', 10.9, 10.5, .15, .62, 2.35, 2.4, None, 'boat', black, 16)
    out += ring('boat_brim_edge', 11.0, 10.6, .12, .22, 2.35, .35, None, 'boat', black, 16)
    for row, (y, rx, rz) in enumerate([(-.8, 8.5, 8.1), (-1.6, 7.6, 7.2), (-2.4, 6.65, 6.3), (-3.2, 5.5, 5.2)]):
        out += ring(f'boat_crown_{row}', rx, rz, y, .92, 2.35, 1.2, None, 'boat', black, 12)
    out += ring('boat_ribbon', 8.56, 8.16, -.62, .52, 2.35, .18, None, 'boat', white, 12)
    out.append(cube('boat_bottom', [-4.8, -3.45, -2.6], [4.8, -3.15, 7.3], None, 'boat', black))
    out.append(cube('boat_tip_fold', [2.5, -4.05, 1.2], [6.8, -3.4, 4.4], None, 'boat', black))
    # Preserve the ordinary hat's actual white bow UVs and geometry proportions.
    for original in accessories:
        if original['bone'] != 'bow':
            continue
        e = copy.deepcopy(original)
        e['name'] = 'boat_' + e['name']
        e['bone'] = 'boat_bow'
        for key in ('from', 'to'):
            x, y, z = e[key]
            e[key] = [x, (y - 11.5) * .68 - .15, z - 2.8]
        out.append(e)
    return out


def kotatsu_parts():
    out = []
    out.append(cube('kotatsu_table_edge', [-8.2, 10.08, -3.75], [8.2, 10.62, 13.2], 'wood_edge', 'table'))
    out.append(cube('kotatsu_tabletop', [-8.08, 10.60, -3.63], [8.08, 10.89, 13.08], 'wood', 'table'))
    for side in (-1, 1):
        x = side * 6.8
        out.append(cube(f'kotatsu_leg_front_{side}', [x - .4, .4, -2.5], [x + .4, 9.98, -1.7], 'wood_edge', 'table'))
        out.append(cube(f'kotatsu_leg_back_{side}', [x - .4, .4, 11.3], [x + .4, 9.98, 12.1], 'wood_edge', 'table'))
    # Overlapping cloth panels flare at the floor. Front stays open around the
    # ordinary face, while side and rear folds hide the ordinary back and rump.
    for row in range(12):
        t = row / 11
        y = .38 + row * .8
        halfwidth = 9.45 - 1.5 * t
        back = 14.3 - 1.4 * t
        front = -4.0 + .3 * t
        material = 'hem' if row == 0 else 'quilt'
        for side in (-1, 1):
            for strip in range(5):
                z0 = front + (back - front) * strip / 5
                z1 = front + (back - front) * (strip + 1) / 5 + .02
                fold = .2 * math.sin(strip * 2.2 + t * 1.2)
                x = side * (halfwidth + fold)
                out.append(cube(f'quilt_side_{side}_{row}_{strip}',
                                [x - .30, y, z0], [x + .30, y + .84, z1],
                                material if row == 0 else ('quilt_dark' if strip == 2 else 'quilt_light' if strip == 4 else 'quilt'),
                                'quilt_left' if side < 0 else 'quilt_right'))
            # Front corner curtains leave a 13-unit aperture around hair/braids.
            a, b = sorted((side * 6.65, side * (halfwidth + .28)))
            out.append(cube(f'quilt_front_{side}_{row}', [a, y, front - .34], [b, y + .84, front + .2], material, 'quilt_front'))
        out.append(cube(f'quilt_back_{row}', [-halfwidth, y, back - .3], [halfwidth, y + .84, back + .3], material, 'quilt_back'))
    out.append(cube('quilt_top', [-7.98, 9.75, -3.65], [7.98, 10.12, 13.0], 'quilt', 'quilt_back'))
    return out


def vertices(e):
    x, y, z = e['from']; X, Y, Z = e['to']
    faces = {'north': [(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)], 'south': [(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],
             'east': [(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)], 'west': [(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],
             'up': [(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)], 'down': [(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]}
    normals = {'north':[0,0,-1],'south':[0,0,1],'east':[1,0,0],'west':[-1,0,0],'up':[0,1,0],'down':[0,-1,0]}
    out = []
    ax,ay,az=[math.radians(v*s) for v,s in zip(e.get('rotation',[0,0,0]),[-1,-1,1])]
    cx,sx,cy,sy,cz,sz=math.cos(ax),math.sin(ax),math.cos(ay),math.sin(ay),math.cos(az),math.sin(az)
    matrix=[[cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx],
            [sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx],[-sy,cy*sx,cy*cx]]
    def rotate(v,point=False):
        pivot=e.get('origin',[0,0,0]) if point else [0,0,0]
        v=[n-p for n,p in zip(v,pivot)]
        return [sum(row[k]*v[k] for k in range(3))+pivot[i] for i,row in enumerate(matrix)]
    for face, positions in faces.items():
        u,v,U,V = e['faces'][face]['uv']; uvs = [(u,v),(U,v),(U,V),(u,V)]
        for i in (0,1,2,0,2,3):
            out.extend([*rotate(positions[i],True),uvs[i][0]/SIZE,uvs[i][1]/SIZE,*rotate(normals[face])])
    return out


def clips_for(kind):
    def track(channel, values):
        return {channel: {str(t): v for t, v in values}}
    still = dict(duration=4, loop=True, tracks={})
    idle = dict(duration=4, loop=True, tracks={
        'body_motion': track('position', [(0,[0,0,0]),(2,[0,.10,0]),(4,[0,0,0])]),
        'hand_left': track('rotation', [(0,[0,0,0]),(2,[0,0,-2]),(4,[0,0,0])]),
        'hand_right': track('rotation', [(0,[0,0,0]),(2,[0,0,2]),(4,[0,0,0])]),
    })
    move = copy.deepcopy(idle)
    if kind == 'aquatic':
        idle['tracks']['root'] = track('position', [(0,[0,0,0]),(1,[0,.24,0]),(2,[0,0,0]),(3,[0,-.24,0]),(4,[0,0,0])])
        move = copy.deepcopy(idle)
        move['tracks']['root'].update(track('rotation', [(0,[0,0,-2]),(1,[1.5,0,0]),(2,[0,0,2]),(3,[-1.5,0,0]),(4,[0,0,-2])]))
        for side, sign in [('left',-1),('right',1)]:
            move['tracks']['hand_'+side] = track('rotation', [(0,[0,0,sign*8]),(1,[14,0,sign*24]),(2,[0,0,sign*8]),(3,[-10,0,sign*3]),(4,[0,0,sign*8])])
    elif kind == 'tsumuri':
        move['tracks']['body_motion'] = track('position', [(0,[0,0,0]),(1,[0,.08,-.3]),(2,[0,0,0]),(3,[0,.08,.3]),(4,[0,0,0])])
        move['tracks']['shell'] = track('rotation', [(0,[0,0,0]),(1,[0,0,-1]),(2,[0,0,0]),(3,[0,0,1]),(4,[0,0,0])])
    elif kind == 'kotatsu':
        move['tracks']['body_motion'] = track('position', [(0,[0,0,0]),(1,[0,0,-.35]),(2,[0,0,-.5]),(3,[0,0,-.2]),(4,[0,0,0])])
        for side, sign in [('left',-1),('right',1)]:
            move['tracks']['quilt_'+side] = track('rotation', [(0,[0,0,0]),(2,[0,0,sign*.8]),(4,[0,0,0])])
    clips = {'still': still, 'idle': idle, 'move': move}
    if kind == 'tsumuri':
        from shell import PIVOT, TILT, ROLL, RETRACT_SCALE
        radians=math.radians(TILT)
        roll=math.radians(ROLL)
        shift_y=math.cos(radians)*.35+math.sin(radians)*6.3*RETRACT_SCALE[2]
        retreat=[PIVOT[0]-math.sin(roll)*shift_y,PIVOT[1]+math.cos(roll)*shift_y,
                 PIVOT[2]+math.sin(radians)*.35-math.cos(radians)*6.3*RETRACT_SCALE[2]+4.6]
        retract = dict(duration=6,loop=True,tracks={
            'body_motion': {
                **track('position', [(0,[0,0,0]),(.5,[0,.3,.3]),(1.8,retreat),(3.2,retreat),(4.8,[0,.3,.3]),(5.5,[0,0,0]),(6,[0,0,0])]),
                **track('rotation', [(0,[0,0,0]),(.5,[0,0,0]),(1.8,[TILT,0,ROLL]),(3.2,[TILT,0,ROLL]),(4.8,[0,0,0]),(5.5,[0,0,0]),(6,[0,0,0])]),
                **track('scale', [(0,[1,1,1]),(.5,[1,1,1]),(1.8,RETRACT_SCALE),(3.2,RETRACT_SCALE),(4.8,[1,1,1]),(5.5,[1,1,1]),(6,[1,1,1])])
            }
        })
        for side,sign in [('left',1),('right',-1)]:
            retract['tracks']['hand_'+side] = track('rotation', [(0,[0,0,0]),(1.5,[-20,0,sign*12]),(3.5,[-20,0,sign*12]),(5.5,[0,0,0]),(6,[0,0,0])])
        clips['retract'] = retract
    return clips


def export(kind, label, elements, bones, textures):
    out = HERE / kind; out.mkdir(parents=True, exist_ok=True)
    names = [e['name'] for e in elements]
    assert len(names) == len(set(names))
    nodes = {b['name']: dict(name=b['name'], uuid=uid(kind+'/bone/'+b['name']), origin=b['pivot'], children=[]) for b in bones}
    for e in elements:
        e['uuid'] = uid(kind+'/'+e['name'])
        nodes[e['bone']]['children'].append(e['uuid'])
    for b in bones:
        if b['parent']:
            nodes[b['parent']]['children'].append(nodes[b['name']])
    clips = clips_for(kind)
    rig = dict(bones=bones, groups=[e['bone'] for e in elements], clips=clips, default_hidden=[])
    bb_elements = [{k:v for k,v in e.items() if k != 'bone'} for e in elements]
    bb = dict(meta=dict(format_version='4.10', model_format='free', box_uv=False), name=label,
              resolution=dict(width=SIZE,height=SIZE), elements=bb_elements,
              outliner=[nodes[b['name']] for b in bones if b['parent'] is None],
              textures=[dict(path='marisa.png',name='marisa.png',id='0',uuid=uid(kind+'/texture'),
                             source=textures['neutral'],mode='bitmap',width=SIZE,height=SIZE,uv_width=SIZE,uv_height=SIZE)], animations=[])
    exported_clips = {}
    for label_key, clip in clips.items():
        animators = {}; channels = {}
        for bone, tracks in clip['tracks'].items():
            frames = []; channels[bone] = {}
            for channel, keys in tracks.items():
                channels[bone][channel] = {}
                for t, v in keys.items():
                    converted = [-v[0],-v[1],v[2]] if channel == 'rotation' else [-v[0],v[1],v[2]] if channel == 'position' else v
                    channels[bone][channel][t] = converted
                    frames.append(dict(channel=channel,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',map(str,converted)))],uuid=uid(kind+bone+channel+label_key+t)))
            animators[nodes[bone]['uuid']] = dict(name=bone,type='bone',keyframes=frames)
        animation_name = f'animation.marisa_{kind}.{label_key}'
        bb['animations'].append(dict(name=animation_name,uuid=uid(animation_name),loop='loop',length=clip['duration'],snapping=100,animators=animators))
        exported_clips[animation_name] = dict(loop=True,animation_length=clip['duration'],bones=channels)
    geometry = []
    for b in bones:
        g = dict(name=b['name'],pivot=b['pivot'],cubes=[])
        if b['parent']: g['parent'] = b['parent']
        for e in elements:
            if e['bone'] == b['name']:
                g['cubes'].append(dict(origin=e['from'],size=[hi-lo for lo,hi in zip(e['from'],e['to'])],
                                      uv={f:dict(uv=v['uv'][:2],uv_size=[v['uv'][2]-v['uv'][0],v['uv'][3]-v['uv'][1]]) for f,v in e['faces'].items()}))
                if any(e.get('rotation',[0,0,0])):
                    g['cubes'][-1].update(pivot=e['origin'],rotation=e['rotation'])
        geometry.append(g)
    save(out/'marisa.bbmodel', bb)
    save(out/'marisa.geo.json', {'format_version':'1.12.0','minecraft:geometry':[{'description':dict(identifier='geometry.marisa_'+kind,texture_width=SIZE,texture_height=SIZE,visible_bounds_width=4,visible_bounds_height=5,visible_bounds_offset=[0,1,0]),'bones':geometry}]})
    save(out/'marisa.animation.json',dict(format_version='1.8.0',animations=exported_clips))
    save(out/'animation-rig.json',rig)
    save(out/'components.json',dict(id=kind,base='../..',elements=elements))
    (out/'marisa.png').write_bytes(base64.b64decode(textures['neutral'].split(',')[1]))
    for expression, data in textures.items():
        if expression != 'neutral':
            (out/f'marisa.{expression}.png').write_bytes(base64.b64decode(data.split(',')[1]))
    model = dict(label=label,vertices=[v for e in elements for v in vertices(e)],rig=rig,textures=textures,cubeCount=len(elements))
    print(kind, len(elements), 'cubes;', len(bones), 'bones;', len(clips), 'clips')
    return model


def main():
    from shell import PIVOT
    c = read(STANDARD/'characters/marisa.json')
    bones = read(STANDARD/c['skeleton'])['bones']
    base = []
    for ref in c['components']:
        if ref.endswith(('accessories.json','puff.json')): continue
        base.extend(read(STANDARD/ref)['elements'])
    accessories = read(STANDARD/'components/marisa/accessories.json')['elements']
    textures = {name:data_url(atlas(STANDARD/path)) for name,path in c['textures'].items() if name in ('neutral','happy','sleepy','sleep')}
    ordinary = export('ordinary','普通魔理沙',copy.deepcopy(base+accessories),copy.deepcopy(bones),textures)
    models = {'ordinary': ordinary}
    manifests = {}
    for kind,label,parts,new_bones in [
        ('tsumuri','螺壳魔理沙',shell_parts(),[('shell','root',PIVOT)]),
        ('aquatic','水生魔理沙',boat_parts(accessories),[('boat','root',[0,0,2.35]),('boat_bow','boat',[0,0,-8])]),
        ('kotatsu','被炉魔理沙',kotatsu_parts(),[('table','root',[0,0,4]),('quilt_left','table',[-7.8,10,4]),('quilt_right','table',[7.8,10,4]),('quilt_front','table',[0,10,-3.7]),('quilt_back','table',[0,10,13])]),
    ]:
        variant_bones = copy.deepcopy(bones) + [dict(name=n,parent=p,pivot=v) for n,p,v in new_bones]
        models[kind] = export(kind,label,copy.deepcopy(base)+parts,variant_bones,textures)
        save(HERE/kind/'accessory-components.json',dict(id=kind+'.accessories',bones=variant_bones[len(bones):],elements=parts))
        manifests[kind] = dict(label=label,base_character='../characters/marisa.json',inherited_components=[r for r in c['components'] if not r.endswith(('accessories.json','puff.json'))],accessories=f'{kind}/accessory-components.json',body_scale=[1,1,1],editable_model=f'{kind}/marisa.bbmodel',geometry=f'{kind}/marisa.geo.json',animation=f'{kind}/marisa.animation.json')
    save(HERE/'manifest.json',dict(schema_version=1,variants=manifests))
    hashes={str(p.relative_to(STANDARD)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [STANDARD/c['skeleton'],*[STANDARD/r for r in c['components']],*[STANDARD/c['textures'][t] for t in ('neutral','happy','sleepy','sleep')]]}
    save(HERE/'base-provenance.json',dict(base='yukkuri-v1/characters/marisa.json',sha256=hashes,unchanged='Inherited body, rump, face, hair and independent braids preserve bind-space geometry and pixel UVs. New atlas has 256px dimensions, old pixels remain at upper left.'))
    runtime = (STANDARD/'runtime/motion.js').read_text(encoding='utf-8')
    # The shared CPU renderer is fixed to 128px. This local instance operates on
    # the expanded atlas; no global renderer or existing character is changed.
    renderer = (STANDARD/'runtime/render.js').read_text(encoding='utf-8').replace('*128','*256').replace('127,','255,')
    html = (HERE/'viewer.template.html').read_text(encoding='utf-8').replace('__DATA__',json.dumps(models,ensure_ascii=False,separators=(',',':'))).replace('__RUNTIME__',runtime+'\n'+renderer)
    (HERE/'index.html').write_text(html,encoding='utf-8')


if __name__ == '__main__': main()
