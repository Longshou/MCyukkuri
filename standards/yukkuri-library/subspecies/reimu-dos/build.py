"""Derive reviewable subspecies from the current canonical assembled library.

Run ../../build.py first if canonical components change. No legacy variants or
extracted/reference pixels are used. Recipes remain the authoring source.
"""
from pathlib import Path
import base64, copy, hashlib, io, json, math, sys, uuid
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
sys.path.insert(0, str(LIB))
from geometry import vertices


def read(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))


def save(p, data):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')


def source(base):
    folder = LIB / 'body-standard/inputs' / base
    html = (folder / 'index.html').read_text(encoding='utf-8')
    data = json.JSONDecoder().raw_decode(html.split('const DATA=', 1)[1])[0]
    return read(folder / f'{base}.bbmodel'), data


class Shape:
    def __init__(self, recipe):
        self.r = recipe
        self.s = recipe['scale']*recipe.get('world_scale',1)

    def affine(self, name, center_y=4):
        """Per-cuboid affine transform keeps exports cuboids, with no mesh loss."""
        r = self.r
        scale, offset = [1., 1., 1.], [0., 0., 0.]
        face = name.startswith('face_')
        if r['shape'] == 'dos':
            scale = r['body_scale'][:]
            q = max(-1, min(1, (center_y-4.24)/4.5))
            oval = .40 + .60*math.sqrt(max(0, 1-q*q))
            if name.startswith(('soft_head_slice', 'upper_rear_slice', 'left_cheek_slice', 'right_cheek_slice', 'rump_')):
                scale[0] *= oval
                if not name.startswith(('soft_head_slice','left_cheek_slice','right_cheek_slice')):
                    scale[2] *= oval
                    offset[2] = 1.8*(1-oval)*r['body_scale'][2]
            elif name.startswith(('crown','hair_cap')):
                scale = r['hair_cap_scale'][:]
                offset[1] = 8.48*r['body_scale'][1]-9.646*scale[1]
            elif name.startswith(('hat', 'brim', 'bow')):
                scale = r['hat_scale'][:]
                offset[1] = 8.48*(r['body_scale'][1]-scale[1])-r['hat_sink']
            elif any(k in name for k in ['hair', 'bang', 'lock', 'side_tip', 'side_ribbon', 'hand_']):
                scale[0] = .87
                if any(k in name for k in ['back_hair','back_lock','rear_side_hair','side_lock','side_tip','side_ribbon','hand_','braid_lock','side_hair_loose']):
                    scale[0] = r['body_scale'][0]*oval
                if any(k in name for k in ['back_hair','back_lock','rear_side_hair']):
                    scale[2] *= oval
                    offset[2] = 1.8*(1-oval)*r['body_scale'][2]
                if name.startswith('bang'):
                    scale[0] = .68
                    scale[1] = r['bang_height_scale']
                    offset[1] = r['bang_height_offset']
        if r['shape'] == 'deibu':
            x, y, z = r['body_scale']
            scale = [1.30, y, z]
            if (name.startswith(('soft_head_slice', 'upper_rear_slice', 'left_cheek_slice', 'right_cheek_slice', 'rump_'))
                    or any(t in name for t in ['tied_lock','cuff','lock_tip','hand_','hair','bang','back_lock','crown'])):
                q=max(0,min(1,(center_y-.55)/7.93))
                # A full cheek arc and tucked lower corners soften the taper.
                scale[0]=(x+(r['upper_width']-x)*q
                    +r['cheek_roundness']*math.sin(math.pi*q)
                    -r['lower_corner_tuck']*math.exp(-(q/.18)**2))
                if name.startswith(('upper_rear_slice','rump_','back_hair','rear_side_hair','back_lock')):
                    depth_round=1-.10*math.exp(-(center_y/1.7)**2)
                    scale[2]*=depth_round
                    offset[2]=1.8*z*(1-depth_round)
        if face:
            fx, fy = r.get('face_scale', [1, 1])
            y = scale[1]
            scale[0], scale[1] = fx, fy * y
            offset[1] = (4.4 * (1 - fy) + r.get('face_raise', 0)) * y
        return [a * self.s for a in scale], [a * self.s for a in offset]

    def point(self, p, name='', center_y=4):
        a, b = self.affine(name, center_y)
        return [p[i] * a[i] + b[i] for i in range(3)]

    def element(self, e):
        e = copy.deepcopy(e)
        cy = (e['from'][1] + e['to'][1]) / 2
        for k in ['from', 'to', 'origin']:
            if k in e:
                e[k] = self.point(e[k], e['name'], cy)
        if self.r['shape']=='dos' and e['name'].startswith(('soft_head_slice','left_cheek_slice','right_cheek_slice')):
            # Curve the chin in depth; the eye/mouth band stays on the base face plane.
            taper = .65+.35*math.sin(math.pi/2*max(0,min(1,cy/2.5)))
            if cy>7.2:taper=1-.22*min(1,(cy-7.2)/1.28)
            for k in ['from','to']:
                if e[k][2]<0:e[k][2]*=taper
        if self.r['shape']=='deibu' and e['name'].startswith(('soft_head_slice','left_cheek_slice','right_cheek_slice')):
            taper=1-.10*math.exp(-(cy/1.7)**2)
            for k in ['from','to']:
                if e[k][2]<0:e[k][2]*=taper
        if self.r['shape']=='dos' and e['name'].startswith('brim_'):
            # The front/back rim dips around the oval head rather than sitting
            # as a separate flat shelf above the scalp.
            drop = .18*self.s if e['name'] in ['brim_front','brim_back'] else 0
            for k in ['from','to','origin']:e[k][1]-=drop
        return e

    def vertex_array(self, v, name):
        out = v[:]
        a, b = self.affine(name)
        for i in range(0, len(out), 8):
            for j in range(3):
                out[i+j] = v[i+j] * a[j] + b[j]
            n = [v[i+5+j] / a[j] for j in range(3)]
            length = math.sqrt(sum(x*x for x in n))
            out[i+5:i+8] = [x/length for x in n]
        return out

    def displacement(self, v):
        a, _ = self.affine('')
        return [v[i]*a[i] for i in range(3)]


def hair_box(template,name,lo,hi,group,rotation=0,origin=None,highlight=False):
    e=copy.deepcopy(template)
    e.update(name=name,rotation=[0,0,rotation],origin=origin or [(a+b)/2 for a,b in zip(lo,hi)])
    e['from']=lo;e['to']=hi
    if highlight:
        for f in e['faces'].values():f['uv']=[32,48,48,64]
    return e,group


def detailed_hair(recipe, originals):
    """Author scalp silhouettes and tapered, unbound sideburn clusters."""
    result=[];template=next(e for e in originals if e['name'].startswith('tied_lock'))
    def box(name,c,size,group='hair',angle=0,highlight=False):
        result.append(hair_box(template,name,[c[i]-size[i]/2 for i in range(3)],
            [c[i]+size[i]/2 for i in range(3)],group,angle,c,highlight))
    def tuft(name,root,direction,length,width,depth,group='hair',highlight=False):
        angle=math.degrees(math.atan2(-direction[0],direction[1]))
        norm=math.hypot(*direction);direction=[v/norm for v in direction]
        for j in range(3):
            t=(j+.5)/3
            c=[root[0]+direction[0]*length*t,root[1]+direction[1]*length*t,root[2]]
            box(f'{name}_{j}',c,[width*(1-.73*t),length/3+.10,depth*(1-.58*t)],group,angle,highlight and j<2)
    if recipe['shape']=='deibu':
        # Rounded shoulders flow into the slightly pointed central crest.
        for i in range(18):
            q=(i+.5)/18;half=6.2*(1-.87*q**1.45)
            box(f'hair_deibu_pointed_scalp_{i}',[0,8.25+q*1.6,1.15],
                [half*2,1.6/18+.008,10.3*(1-.24*q)])
        return result
    center,radius,layers=(recipe['fur'][k] for k in ['center','radii','layers'])
    for side,sign in [('left',-1),('right',1)]:
        group='sideburn_'+side
        # A wide root at the temple joins the fluff directly to the scalp.
        tuft(f'wasa_{side}_root',[sign*6.1,7.35,-2.2],[sign*.7,-1],3.15,2.0,2.2,group)
        for i in range(layers):
            q=-1+(i+.5)*2/layers;w=math.sqrt(max(0,1-q*q))
            box(f'wasa_{side}_mass_{i}',[sign*(center[0]-.42*q),center[1]+radius[1]*q,center[2]],
                [radius[0]*2*w,radius[1]*2/layers+.01,radius[2]*2*w],group)
        for i,(x,y,z,dx,dy,length,width) in enumerate([
            (7.3,6.0,-2.6,1,.65,2.5,1.15),(8.1,5.35,-3.1,1,.2,2.5,1.3),
            (8.3,4.4,-2.8,1,-.35,2.45,1.3),(7.9,3.45,-3.0,1,-.8,2.5,1.2),
            (7.2,3.0,-3.5,.5,-1,2.2,1.15),(6.75,4.6,-4.0,.25,-1,2.1,1.05),
            (7.8,5.3,-4.12,.8,-.45,2.05,.9),(7.7,4.5,-1.15,1,.45,2.0,1.2)
        ]):tuft(f'wasa_{side}_spray_{i}',[sign*(x-.25),y,z],[sign*dx,dy],length*.78,width*1.4,1.1,group,i in [1,6])
    for i in range(10):
        q=(i+.5)/10;w=6.8*math.sqrt(1-.74*q*q)
        box(f'wasa_scalp_{i}',[0,8.15+q*2,1.0],[w*2,.21,10.7*(1-.20*q)])
    # A few irregular layers establish abundance without a uniform spiked ring.
    for i,(x,y,z,dx,dy,l,w) in enumerate([
        (-4.8,9.5,-1.7,-1,.7,2.0,1.4),(-2.8,9.8,-1.9,-.6,1,1.3,1.5),
        (.2,9.9,-1.7,.6,1,1.4,1.5),(3.0,9.5,-2.0,1,.6,2.15,1.4),
        (5.4,8.75,-2.2,1,.35,1.8,1.45),(-5.5,8.5,-2.0,-1,.15,1.7,1.3)
    ]):tuft(f'wasa_crown_spray_{i}',[x,y,z],[dx,dy],l,w,.95)
    for sign in [-1,1]:
        for i in range(4):
            tuft(f'wasa_rear_spray_{sign}_{i}',[sign*(3.7+i*.6),5.8+i*.65,7.1],
                [sign*.75,-1],1.6+i*.12,1.15,.95)
        for i in range(3):
            tuft(f'wasa_fringe_layer_{sign}_{i}',[sign*(1.1+i*1.7),8.9,-5.08],
                [sign*.25,-1],2.3-i*.12,1.1,.38,'bangs',i==1)
    return result


def signature_texture(data, recipe, elements):
    """Local authoring of two signature expressions from existing pixel parts.

    These are variant-only expressions; canonical neutral and every retained
    expression remain intact. Discrete edits preserve the established palette.
    """
    signature=recipe.get('signature_expression')
    if not signature:return
    atlas=Image.open(io.BytesIO(base64.b64decode(data['textures']['neutral'].split(',',1)[1]))).convert('RGBA')
    neutral=read(LIB/'expressions/presets/neutral.json')
    dark=(72,53,46,255);brown=(118,82,57,255);white=(255,248,233,255)
    for e in elements:
        name=e['name']
        if name not in neutral['parts']:continue
        part=Image.open(LIB/neutral['parts'][name]).convert('RGBA')
        if name.startswith('face_eye_'):
            # Flatten the upper eyelid, reduce the large youthful catchlight,
            # and keep the original iris colors in the lower half.
            mask=Image.new('RGBA',part.size)
            top=5 if signature=='dignified' else 4
            mask.paste(part.crop((0,top,part.width,part.height)),(0,top))
            d=ImageDraw.Draw(mask)
            if signature=='dignified':
                d.line([(3,5),(8,5),(11,6)],fill=dark,width=2)
                for y in range(6,10):
                    for x in range(3,12):
                        pixel=mask.getpixel((x,y))
                        if pixel[3] and sum(pixel[:3])>650:mask.putpixel((x,y),brown)
                d.point((6,7),fill=white)
            else:
                d.line([(4,4),(10,4)],fill=dark,width=1)
            part=mask if name.endswith('left') else mask.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        elif name.startswith('face_brow_'):
            part=Image.new('RGBA',part.size);d=ImageDraw.Draw(part)
            if signature=='dignified':d.line([(4,3),(9,3),(12,4)],fill=dark,width=1)
            else:d.line([(5,3),(10,3),(12,4)],fill=(146,105,65,255),width=1)
            if name.endswith('right'):part=part.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        elif name=='face_mouth':
            part=Image.new('RGBA',part.size);d=ImageDraw.Draw(part)
            if signature=='dignified':
                d.line([(3,3),(10,3)],fill=(128,80,68,255),width=1)
                d.line([(5,4),(8,4)],fill=(186,142,100,255),width=1)
            else:d.line([(4,3),(5,4),(9,4),(10,3)],fill=(128,80,68,255),width=1)
        elif name.startswith('face_blush_') and signature=='dignified':
            part=Image.new('RGBA',part.size)
        u,v,U,V=map(round,e['faces']['north']['uv'])
        if part.size!=(U-u,V-v):
            part=part.resize((U-u,V-v),Image.Resampling.NEAREST)
        atlas.paste(part,(u,v))
    buf=io.BytesIO();atlas.save(buf,format='PNG')
    data['textures'][signature]='data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode()
    data['expressions'][signature]=dict(id=signature,label='沉稳威严' if signature=='dignified' else '成熟沉稳',
        group='亚种专用',status='pending-review',origin='new',review_code='V02' if signature=='dignified' else 'V01',requires=[],sources=[])


def export(name, recipe):
    bb, data = source(recipe['base'])
    shape = Shape(recipe)
    original = bb['elements']
    rig = data['model']['rig']
    inputs = []
    for e,g in zip(original,rig['groups']):
        if recipe['shape'] in ['deibu','wasa'] and e['name'] in ['crown','hair_cap']:continue
        if recipe['shape']=='deibu' and e['name'].startswith(('back_hair','rear_side_hair','side_hair','tied_lock')):
            for i in range(14):
                part=copy.deepcopy(e);lo,hi=e['from'][1],e['to'][1]
                part['name']=e['name']+f'_taper_{i}'
                part['from'][1]=lo+(hi-lo)*i/14;part['to'][1]=lo+(hi-lo)*(i+1)/14
                for side in ['north','south','east','west']:
                    u,v,U,V=e['faces'][side]['uv'];part['faces'][side]['uv']=[u,v+(V-v)*(1-(i+1)/14),U,v+(V-v)*(1-i/14)]
                inputs.append((part,g))
            continue
        if recipe['shape']=='dos' and (e['name'].startswith(('back_hair','back_lock','rear_side_hair','side_lock')) or e['name'] in ('braid_lock_core','side_hair_loose')):
            # Slice long hair so it follows the ellipse instead of boxing it in.
            for i in range(12):
                part=copy.deepcopy(e);lo,hi=e['from'][1],e['to'][1]
                part['name']=e['name']+f'_oval_{i}'
                part['from'][1]=lo+(hi-lo)*i/12
                part['to'][1]=lo+(hi-lo)*(i+1)/12
                for side in ['north','south','east','west']:
                    u,v,U,V=e['faces'][side]['uv']
                    part['faces'][side]['uv']=[u,v+(V-v)*(1-(i+1)/12),U,v+(V-v)*(1-i/12)]
                inputs.append((part,g))
        else:inputs.append((e,g))
    pairs = [(shape.element(e), g) for e, g in inputs
             if recipe['shape'] != 'wasa' or g not in ['hand_left', 'hand_right']]
    if recipe['shape'] == 'wasa':
        pairs += [(shape.element(e),g) for e,g in detailed_hair(recipe, original)]
    if recipe['shape']=='deibu':pairs += [(shape.element(e),g) for e,g in detailed_hair(recipe,original)]
    elements, groups = map(list, zip(*pairs))
    old_to_new = {e['name']: i for i, e in enumerate(elements)}
    patches = {}
    for expression, items in data['model']['expressionPatches'].items():
        patches[expression] = [dict(index=old_to_new[original[p['index']]['name']],
             vertices=shape.vertex_array(p['vertices'], original[p['index']]['name'])) for p in items
             if original[p['index']]['name'] in old_to_new]
        if expression=='puff' and recipe.get('puff_eye_lower'):
            for p in patches[expression]:
                if elements[p['index']]['name'].startswith('face_eye_'):
                    for i in range(1,len(p['vertices']),8):p['vertices'][i]-=recipe['puff_eye_lower']*recipe.get('world_scale',1)
    for bone in rig['bones']:
        bone['pivot'] = shape.point(bone['pivot'], bone['name'], bone['pivot'][1])
    if recipe['shape']=='wasa':
        rename={'hand_left':'sideburn_left','hand_right':'sideburn_right'}
        for bone in rig['bones']:
            old=bone['name'];bone['name']=rename.get(old,old);bone['parent']=rename.get(bone['parent'],bone['parent'])
            if old in rename:bone['pivot']=shape.point([-6.15 if old=='hand_left' else 6.15,7.1,-2.2])
        for clip in rig['clips'].values():clip['tracks']={rename.get(n,n):v for n,v in clip['tracks'].items()}
    keep = ['idle', 'waddle', 'turn', 'puff']
    rig['clips'] = {k:v for k,v in rig['clips'].items() if k in keep or k.startswith('anatomy_')}
    for clip in rig['clips'].values():
        for channels in clip['tracks'].values():
            if 'position' in channels:
                channels['position'] = {t:shape.displacement(v) for t,v in channels['position'].items()}
    if recipe['shape'] == 'wasa':
        clip = copy.deepcopy(rig['clips']['idle'])
        clip.update(id='fur_sway', label='鬓毛轻摆', duration=2.4, loop=True)
        for side, sign in [('left', -1), ('right', 1)]:
            clip['tracks']['sideburn_'+side] = {'rotation':{'0':[0,0,0],'.6':[0,0,sign*7],
                '1.2':[0,0,0],'1.8':[0,0,-sign*5],'2.4':[0,0,0]}}
        rig['clips']['fur_sway'] = clip
    rig['groups'] = groups
    rig['stride_units'] *= shape.s
    data['motions'] = {k:v for k,v in data['motions'].items() if k in keep}
    if 'fur_sway' in rig['clips']:
        data['motions']['fur_sway'] = dict(id='fur_sway',label='鬓毛轻摆',kind='variant',duration=2.4,dependencies=[])
    for part in data.get('anatomy', {}).get('parts', []):
        part['offset'] = shape.displacement(part['offset'])
    # The original 13 expressions are now saved as 'accepted' standards; keep the same set.
    retained = {k:v for k,v in data['expressions'].items() if v['status']=='retained' or (v.get('origin')=='original' and v['status']=='accepted')}
    data['expressions'] = retained
    data['textures'] = {k:v for k,v in data['textures'].items() if k in retained}
    signature_texture(data,recipe,elements)
    default_texture=data['textures'][recipe.get('signature_expression','neutral')]
    data['model'].update(vertices=[v for e in elements for v in vertices(e,data['textureSize'])],
                         expressionPatches={k:v for k,v in patches.items() if k in retained})
    data.update(character=name,label=recipe['label'],preview=copy.deepcopy(recipe['preview']))
    for key in ['span','centerY']:data['preview'][key]*=recipe.get('world_scale',1)
    data['physical']=dict(units_per_meter=16,world_scale=recipe.get('world_scale',1))
    data['preview']['default_motion'] = 'idle'
    out = HERE/'assembled'/name
    out.mkdir(parents=True,exist_ok=True)
    uid = lambda key: str(uuid.uuid5(uuid.NAMESPACE_URL, 'yukkuri-subspecies/'+name+'/'+key))
    nodes = {b['name']:dict(name=b['name'],uuid=uid('bone/'+b['name']),origin=b['pivot'],children=[]) for b in rig['bones']}
    for e,g in zip(elements,groups):
        e['uuid'] = uid(e['name'])
        nodes[g]['children'].append(e['uuid'])
    for b in rig['bones']:
        if b['parent']:
            nodes[b['parent']]['children'].append(nodes[b['name']])
    bb.update(name=recipe['label']+' · design draft',elements=elements,
              outliner=[nodes[b['name']] for b in rig['bones'] if b['parent'] is None],animations=[])
    bb['textures'][0].update(name=name+'.png',path=name+'.png',source=default_texture)
    exports = {}
    for label,clip in rig['clips'].items():
        animators, channels = {}, {}
        for n,chs in clip['tracks'].items():
            kfs=[];channels[n]={}
            for ch,keys in chs.items():
                channels[n][ch]={}
                for t,v in keys.items():
                    val = [-v[0],-v[1],v[2]] if ch=='rotation' else [-v[0],v[1],v[2]] if ch=='position' else v
                    channels[n][ch][t]=val
                    kfs.append(dict(channel=ch,time=float(t),interpolation='linear',
                        data_points=[dict(zip('xyz',[format(x,'.8f') for x in val]))],uuid=uid(label+n+ch+t)))
            animators[uid('bone/'+n)]=dict(name=n,type='bone',keyframes=kfs)
        an='animation.'+name+'.'+label
        bb['animations'].append(dict(name=an,uuid=uid(an),loop='loop' if clip['loop'] else 'once',
            length=clip['duration'],snapping=100,animators=animators))
        exports[an]=dict(loop=clip['loop'],animation_length=clip['duration'],bones=channels)
    geometry=[]
    for b in rig['bones']:
        g=dict(name=b['name'],pivot=b['pivot'],cubes=[])
        if b['parent']:g['parent']=b['parent']
        for e,n in zip(elements,groups):
            if n!=b['name']:continue
            cube=dict(origin=e['from'],size=[y-x for x,y in zip(e['from'],e['to'])],
                uv={k:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for k,f in e['faces'].items()})
            if any(e.get('rotation',[0,0,0])):cube.update(pivot=e['origin'],rotation=e['rotation'])
            g['cubes'].append(cube)
        geometry.append(g)
    save(out/f'{name}.bbmodel',bb)
    save(out/f'{name}.animation.json',dict(format_version='1.8.0',animations=exports))
    save(out/'animation-rig.json',rig)
    save(out/f'{name}.geo.json',{'format_version':'1.12.0','minecraft:geometry':[{
        'description':dict(identifier='geometry.'+name,texture_width=data['textureSize'],texture_height=data['textureSize'],
            visible_bounds_width=4*shape.s,visible_bounds_height=5*shape.s,visible_bounds_offset=[0,2*shape.s,0]),'bones':geometry}]})
    for expression,tex in data['textures'].items():
        (out/'textures').mkdir(exist_ok=True)
        (out/'textures'/f'{expression}.png').write_bytes(base64.b64decode(tex.split(',',1)[1]))
    (out/f'{name}.png').write_bytes(base64.b64decode(default_texture.split(',',1)[1]))
    runtime='const DATA='+json.dumps(data,ensure_ascii=False,separators=(',',':'))+';\n'
    runtime+='\n'.join((LIB/'runtime'/f).read_text(encoding='utf-8-sig') for f in ['motion.js','render.js','anatomy.js','anatomy-controls.js'])
    template=(LIB/'runtime/viewer.html').read_text(encoding='utf-8-sig')
    template=template.replace("expression='neutral'","expression=DATA.preview.default_expression||'neutral'")
    if recipe['shape']=='wasa':
        template=template.replace("o['hand_'+side]","o['sideburn_'+side]").replace("side==='left'?-35:35","side==='left'?-8:8")
        template=template.replace('抬左辫','轻摆左鬓角').replace('抬右辫','轻摆右鬓角')
    template=template.replace('href="../../index.html"','href="../../index.html"').replace('href="../../compare.html"','href="../../index.html#scale"')
    template=template.replace('双角色对比','同尺度对照').replace('素材登记','三种设计').replace('基础动作、复合配方、共用表情和角色组件独立保存。','设计稿；基于当前素材库。旋转与缩放可查看毛团、腹部和背面。')
    template=template.replace('Math.max(7,Math.min(30,','Math.max((DATA.preview.span||14.5)*.45,Math.min((DATA.preview.span||14.5)*2.1,')
    template=template.replace('Math.max(span,13*h/w)','Math.max(span,(DATA.preview.span||14.5)*.9*h/w)')
    template=template.replace('span=11;centerY=6.5;',f'span={11*shape.s};centerY={6.5*shape.s};')
    html=template.replace('__RUNTIME__',runtime).replace('__CHARACTER_LABEL__',recipe['label']).replace('__MODEL_FILE__',name+'.bbmodel')
    (out/'index.html').write_text(html,encoding='utf-8')
    # Small static scene payloads let the comparison apply a single camera scale.
    comparison=copy.deepcopy(data)
    comparison['textures']={'neutral':default_texture}
    return comparison,dict(base=recipe['base'],cube_count=len(elements),bones=len(rig['bones']),
        source_sha256=hashlib.sha256((LIB/'body-standard/inputs'/recipe['base']/f'{recipe["base"]}.bbmodel').read_bytes()).hexdigest(),
        base_scale=recipe['scale'],world_scale=recipe.get('world_scale',1),units_per_meter=16,shape=recipe['shape'],status='design_draft')


def calibrate_reference(data,world_scale):
    """Calibrate comparison references without changing the canonical library."""
    model=data['model'];rig=model['rig']
    def scale_vertices(v):
        for i in range(0,len(v),8):
            for j in range(3):v[i+j]*=world_scale
    scale_vertices(model['vertices'])
    for patches in model['expressionPatches'].values():
        for patch in patches:scale_vertices(patch['vertices'])
    for b in rig['bones']:b['pivot']=[v*world_scale for v in b['pivot']]
    for clip in rig['clips'].values():
        for channels in clip['tracks'].values():
            if 'position' in channels:channels['position']={t:[v*world_scale for v in p] for t,p in channels['position'].items()}
    rig['stride_units']*=world_scale
    data['physical']=dict(units_per_meter=16,world_scale=world_scale,body_width_m=.24)
    return data


if __name__=='__main__':
    config=read(HERE/'recipes.json');recipes=config['variants']
    manifest={};scenes={}
    for name,recipe in recipes.items():
        if name in ('reimu_wasa','reimu_deibu'):
            folder=HERE/'assembled'/name
            html=(folder/'index.html').read_text(encoding='utf-8-sig')
            scenes[name]=json.loads(html.split('const DATA=',1)[1].split(';\n',1)[0])
            scenes[name]['textures']={'neutral':scenes[name]['textures']['neutral']}
            manifest[name]=dict(status='body_standard_approved',body_standard='../../body-standard/profile.json',cube_count=len(scenes[name]['model']['rig']['groups']),source='../../body-standard/recipe.json')
        else:scenes[name],manifest[name]=export(name,recipe)
        print(name,manifest[name]['cube_count'],'cubes',flush=True)
    for base in ['reimu','marisa']:
        _,data=source(base)
        data['textures']={'neutral':data['textures']['neutral']}
        scenes[base]=calibrate_reference(data,config['physical_scale']['world_scale'])
    js='const SCENES='+json.dumps(scenes,ensure_ascii=False,separators=(',',':'))+';\n'
    js+='\n'.join((LIB/'runtime'/f).read_text(encoding='utf-8-sig') for f in ['motion.js','render.js'])
    (HERE/'scene-data.js').write_text(js,encoding='utf-8')
    save(HERE/'manifest.json',manifest)
    save(HERE/'physical-scale.json',config['physical_scale'])
