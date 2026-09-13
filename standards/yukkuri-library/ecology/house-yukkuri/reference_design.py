"""Rounded house body with a reference-sized library face and accessories.

The house body uses shared-vertex ovoid sections; the other components remain
cuboids. These sections and a separately scaled face bring the silhouette closer to the
provided round, big-eyed reference. Hair is fitted to the egg as short layered
wraparound panels; canonical UVs, face textures and accessory shapes are reused.
"""
import copy, math
from mesh_geometry import body_mesh, mesh

SCALE=.24
HEAD_OFFSET=[0,1.50,-.08]
HAT_OFFSET=[0,1.10,-.08]
FACE_ANCHOR=[0,4.10,-4.603]
FACE_OUTPUT=[0,2.28,-1.50]
FACE_SCALE=.33
RUMP_OFFSET=[0,.30,-.39]
# Match the standard component width to the new 4.32 / 12 body-width ratio.
PENI_SCALE=.36
PENI_OFFSET=[0,.282,.30]
PENI_TRAVEL=[0,0,-1.49]
# The rear marker follows the local lower-belly slope; its rest plane is inside.
MAMU_TRAVEL=[0,0,.10]
MAMU_PIVOT=[0,.4704,1.23328]
MAMU_OFFSET=[-.072,.30,-.57632]
MAMU_ROTATION=[-math.degrees(math.atan2(.12,.35)),0,0]
BODY_DIMENSIONS=[4.32,3.82,2.93]
# Height, half width, rear depth, front depth. The broad belly continues almost
# to the floor. Face skin and belly share one solid, including the front plane;
# there is no separate floating face support panel.
# The front rounds only near the floor. Collinear lower front sections share
# one normal instead of forming several broad, differently shaded belly bands.
BODY_PROFILE=[(0,1.14,.84,.84),(.12,1.48,1.04,.84+.64*.12/.35),(.35,1.86,1.28,1.48),
              (.70,2.00,1.40,1.48),(1.12,2.16,1.45,1.48),
              (2.08,2.04,1.40,1.48),
              (2.56,1.88,1.33,1.48),(2.95,1.70,1.20,1.48),
              (3.28,1.46,.98,1.48),(3.55,.96,.73,.94),
              (3.75,.50,.43,.44),(3.82,.22,.20,.20)]
# Height, panel half width/depth. The lower hem stays around mid-body;
# the crown narrows in BOTH horizontal axes instead of forming a long roof.
HAIR_PROFILE=[(1.60,1.92,1.46),(2.00,1.96,1.51),(2.40,1.95,1.53),
              (2.80,1.86,1.54),(3.12,1.73,1.53),(3.32,1.58,1.49),
              (3.52,1.36,1.26),(3.72,1.02,.96),(3.88,.63,.60),
              (4.04,.12,.12)]
HAIR_HEM={'reimu':1.84,'marisa':1.72}
HAIR_LAYER_HEIGHT=.12
HAIR_THICKNESS=.14
HAT_HAIR_TOP=3.50
SIDE_OFFSET_Y=1.40
SIDE_OFFSET_Z=-.77


def hair_radius(y):
    for (y0,x0,z0),(y1,x1,z1) in zip(HAIR_PROFILE,HAIR_PROFILE[1:]):
        if y<=y1:
            t=max(0,min(1,(y-y0)/(y1-y0)))
            return x0+(x1-x0)*t,z0+(z1-z0)*t
    return HAIR_PROFILE[-1][1:]


def transform_side_point(p,center_y=None):
    y=p[1]*SCALE+SIDE_OFFSET_Y
    radius,_=hair_radius(y if center_y is None else center_y)
    side=1 if p[0]>=0 else -1
    return [round((p[0]-side*6.5)*SCALE+side*(radius+.06),9),
            round(y,9),round(p[2]*SCALE+SIDE_OFFSET_Z,9)]


def cropped_slice(src,lo,hi):
    """Preserve vertical atlas continuity when fitting long side locks in slices."""
    e=copy.deepcopy(src);y0,y1=src['from'][1],src['to'][1]
    e['from'][1]=lo;e['to'][1]=hi
    for face in ['north','south','east','west']:
        u,v,U,V=src['faces'][face]['uv']
        e['faces'][face]['uv']=[u,v+(V-v)*(y1-hi)/(y1-y0),U,v+(V-v)*(y1-lo)/(y1-y0)]
    return e


def make_hair(species,templates,box):
    """Octagonal horizontal panels wrap the crown, temples and back as cuboids.

    This adapts the DOS height-slice idea to a shorter hem. Front lower corners
    stay behind the face plane; separate tapered bangs frame the forehead.
    """
    cap=templates['hair_cap'];hem=HAIR_HEM[species]
    top=HAT_HAIR_TOP if species=='marisa' else 4.04
    count=math.ceil((top-hem)/HAIR_LAYER_HEIGHT)
    for i in range(count):
        y0=hem+(top-hem)*i/count;y1=hem+(top-hem)*(i+1)/count
        # Choose the wider endpoint so adjoining tiers overlap at the shoulders.
        x=max(hair_radius(y0)[0],hair_radius(y1)[0])
        z=max(hair_radius(y0)[1],hair_radius(y1)[1])
        front=z if y0>=3.12 else min(z,1.30)
        ring=[(x,-.7*front),(x,.7*z),(.7*x,z),(-.7*x,z),
              (-x,.7*z),(-x,-.7*front),(-.7*x,-front),(.7*x,-front)]
        if y0>=3.32:
            box(f'house_hair_crown_x_{i}',[-x,y0,-.7*z],[x,y1,.7*z],source=cap,group='head')
            box(f'house_hair_crown_z_{i}',[-.7*x,y0,-z],[.7*x,y1,z],source=cap,group='head')
        for j,(a,b) in enumerate(zip(ring,ring[1:]+ring[:1])):
            if j==6 and y0<3.32:continue  # Seven bangs define the open face.
            if j in [0,4,5,7] and y0<hem+.24:continue
            if j in [1,3] and i==0:continue  # Short staggered rear hem.
            dx,dz=b[0]-a[0],b[1]-a[1];length=math.hypot(dx,dz)
            center=[(a[0]+b[0])/2,(y0+y1)/2,(a[1]+b[1])/2]
            half=length/2+.035
            template=templates.get('back_lock_'+str((j+i%2)%5),cap) if y0<2.20 else cap
            box(f'house_hair_wrap_{i}_{j}',[center[0]-half,y0,center[2]-HAIR_THICKNESS],
                [center[0]+half,y1+.018,center[2]+(.5*min(x,z) if y0>=3.32 else .025)],center,
                [0,math.degrees(math.atan2(dz,dx)),0],template,'head')
    # The widened face surface needs short temple panels above the cheek quads.
    count=math.ceil((3.32-2.02)/HAIR_LAYER_HEIGHT)
    for i in range(count):
        y0=2.02+(3.32-2.02)*i/count;y1=2.02+(3.32-2.02)*(i+1)/count
        x=max(hair_radius(y0)[0],hair_radius(y1)[0])+.025
        inner=max(1.56,x-.25)
        for side in [-1,1]:
            lo,hi=sorted([side*inner,side*x])
            box(f'house_temple_{side}_{i}',[lo,y0,-1.53],[hi,y1+.018,-.86],source=cap,group='head')
    if species!='marisa':
        box('house_hair_crown_close',[-.15,3.99,-.15],[.15,4.065,.15],source=cap,group='head')
    edges=[-1.64,-1.20,-.74,-.27,.27,.74,1.20,1.64]
    hems=[2.76,3.08,2.98,2.89,2.98,3.08,2.76]
    if species=='marisa':hems=[2.79,3.10,2.94,2.86,3.00,3.12,2.83]
    for i,(left,right,low) in enumerate(zip(edges,edges[1:],hems)):
        template=templates['bang_'+str(i)]
        bang_top=3.48 if species=='marisa' else 3.52
        count=math.ceil((bang_top-low)/.10)
        for j in range(count):
            y0=low+(bang_top-low)*j/count;y1=low+(bang_top-low)*(j+1)/count
            x,z=hair_radius(y0)
            # A stepped point broadens into its root, with no long central fringe.
            taper=min(1,.32+.68*(y1-low)/.20)
            mid=(left+right)/2;half=(right-left)*taper/2
            lo=max(-x,mid-half);hi=min(x,mid+half)
            # Keep the low temple tips outside the enlarged eye quads.
            if y0<3.00 and i==0:hi=min(hi,-1.56)
            if y0<3.00 and i==6:lo=max(lo,1.56)
            if lo>=hi:continue
            box(f'house_bang_{i}_{j}',[lo,y0,-z-.035],[hi,y1+.018,-z+.15],
                source=template,group='head')


def transform_point(p,offset):return [round(v*SCALE+o,9) for v,o in zip(p,offset)]

def transform_face_point(p):
    """Scale the canonical face about its center without enlarging accessories."""
    return [round((p[0]-FACE_ANCHOR[0])*FACE_SCALE+FACE_OUTPUT[0],9),
            round((p[1]-FACE_ANCHOR[1])*FACE_SCALE+FACE_OUTPUT[1],9),
            round((p[2]-FACE_ANCHOR[2])*SCALE+FACE_OUTPUT[2],9)]

def make(species,base_bb,base_rig,standard_names):
    elements=[];groups=[]
    skin=next(e for e in base_bb['elements'] if e['name']=='soft_head_slice_0')
    def box(name,lo,hi,origin=None,rotation=None,source=skin,group='body'):
        e=copy.deepcopy(source);e.update(name=name,origin=origin or [0,0,0],rotation=rotation or [0,0,0])
        e['from']=lo;e['to']=hi;elements.append(e);groups.append(group)
        return e
    elements.append(body_mesh(BODY_PROFILE,skin));groups.append('body')
    hair_templates={e['name']:e for e in base_bb['elements'] if standard_names.get(e['name'])=='hair'}
    make_hair(species,hair_templates,box)
    for src in base_bb['elements']:
        if src['name'] not in standard_names:continue
        kind=standard_names[src['name']]
        if kind=='hair':continue
        if species=='reimu' and kind=='head' and src['name'].startswith('bow_tail'):
            lo,hi=src['from'][1],src['to'][1]
            count=max(1,math.ceil((hi-lo)*SCALE/.10))
            for i in range(count):
                e=cropped_slice(src,lo+(hi-lo)*i/count,lo+(hi-lo)*(i+1)/count)
                e['name']+=f'_house_fit_{i}'
                cy=(e['from'][1]+e['to'][1])/2*SCALE+HEAD_OFFSET[1]
                front=hair_radius(cy)[1]+HAIR_THICKNESS-.015
                if 'tail_top' in src['name']:front=max(1.40,front)
                for key in ['from','to','origin']:
                    if key in e:
                        old_z=e[key][2];e[key]=transform_point(e[key],HEAD_OFFSET)
                        e[key][2]=front+(old_z-7.825)*SCALE
                elements.append(e);groups.append('head')
            continue
        if kind in ['left','right']:
            # Fit long side locks like the DOS recipe, but keep short house tips.
            lo,hi=src['from'][1],src['to'][1]
            count=max(1,math.ceil((hi-lo)*SCALE/HAIR_LAYER_HEIGHT))
            for i in range(count):
                e=cropped_slice(src,lo+(hi-lo)*i/count,lo+(hi-lo)*(i+1)/count)
                e['name']=src['name']+f'_house_fit_{i}'
                cy=(e['from'][1]+e['to'][1])/2*SCALE+SIDE_OFFSET_Y
                for key in ['from','to','origin']:
                    if key in e:e[key]=transform_side_point(e[key],cy)
                elements.append(e);groups.append('hand_left' if kind=='left' else 'hand_right')
            continue
        e=copy.deepcopy(src)
        if kind=='face':
            for key in ['from','to','origin']:
                if key in e:e[key]=transform_face_point(e[key])
        else:
            for key in ['from','to','origin']:
                if key in e:e[key]=transform_point(e[key],HAT_OFFSET if species=='marisa' and kind=='head' else HEAD_OFFSET)
            if species=='reimu' and kind=='head':
                # Bring the bow knot forward onto the new rounded rear crown.
                for key in ['from','to','origin']:
                    if key in e:e[key][2]-=.25
        elements.append(e)
        groups.append('face' if kind=='face' else 'hand_left' if kind=='left' else 'hand_right' if kind=='right' else 'head')
    bones=[dict(name='world_motion',parent=None,pivot=[0,0,0]),
           dict(name='body',parent='world_motion',pivot=[0,0,0]),
           dict(name='head',parent='body',pivot=transform_point([0,7.42,1.6351562],HEAD_OFFSET)),
           dict(name='face',parent='body',pivot=transform_face_point([0,4.3725,2.3128906]))]
    # Core stays inside the body; the marker follows the actual rear surface.
    for prefix in ['rump_core_slice_','rump_round_end_slice_']:
        sources=[e for e in base_bb['elements'] if e['name'].startswith(prefix)]
        lo=[min(e['from'][i] for e in sources) for i in range(3)]
        hi=[max(e['to'][i] for e in sources) for i in range(3)]
        if 'round_end' in prefix:
            low,high=transform_point(lo,RUMP_OFFSET)[1],transform_point(hi,RUMP_OFFSET)[1]
            rows=[low]+[p[0] for p in BODY_PROFILE if low<p[0]<high]+[high]
            points=[];triangles=[];uvs=[]
            for y in rows:
                for a,b in zip(BODY_PROFILE,BODY_PROFILE[1:]):
                    if a[0]<=y<=b[0]:
                        z=a[2]+(b[2]-a[2])*(y-a[0])/(b[0]-a[0])+.006;break
                points.extend([[-1.152,y,z],[1.152,y,z]])
            for i in range(len(rows)-1):
                a=i*2;b=a+1;c=a+3;d=a+2
                v0=128-32*(rows[i]-low)/(high-low);v1=128-32*(rows[i+1]-low)/(high-low)
                triangles.extend([(a,b,c),(a,c,d)])
                uvs.extend([[(96,v0),(128,v0),(128,v1)],[(96,v0),(128,v1),(96,v1)]])
            elements.append(mesh('house_rump_round_end',points,triangles,uvs));groups.append('rump')
        else:
            e=box('house_rump_core',transform_point(lo,RUMP_OFFSET),transform_point(hi,RUMP_OFFSET),source=skin,group='rump')
            e['from'][0]=-1.02;e['to'][0]=1.02
            e['to'][2]=1.25
    bones.append(dict(name='rump',parent='body',pivot=[0,.82,1.24]))
    for part,offset,pivot in [('mamu',MAMU_OFFSET,[.3,.71,7.54]),('peni',PENI_OFFSET,[0,1.15,-.88])]:
        bone='anatomy_'+part
        part_scale=PENI_SCALE if part=='peni' else SCALE
        part_point=lambda p:[round(v*part_scale+o,9) for v,o in zip(p,offset)]
        bones.append(dict(name=bone,parent='rump' if part=='mamu' else 'body',pivot=part_point(pivot)))
        for src in base_bb['elements']:
            if not src['name'].startswith(part+'_'):continue
            e=copy.deepcopy(src)
            for key in ['from','to','origin']:e[key]=part_point(e[key])
            if part=='mamu':e.update(origin=MAMU_PIVOT.copy(),rotation=MAMU_ROTATION.copy())
            elements.append(e);groups.append(bone)
    for side in ['left','right']:
        old=next(b for b in base_rig['bones'] if b['name']=='hand_'+side)
        bones.append(dict(name='hand_'+side,parent='body',pivot=transform_side_point(old['pivot'])))
    clips={}
    for name in ['idle','waddle','flee']:
        src=base_rig['clips'][name];tracks={}
        if name!='idle':
            tracks['body']=copy.deepcopy(src['tracks'].get('upper_body',{}))
            roll=src['tracks'].get('rump_roll',{}).get('rotation')
            if roll:tracks['body']['rotation']=copy.deepcopy(roll)
            world=copy.deepcopy(src['tracks'].get('world_motion',{}))
            if 'position' in world:world['position']={t:[round(x*SCALE,9) for x in v] for t,v in world['position'].items()}
            if world:tracks['world_motion']=world
        clips[name]=dict(duration=src['duration'],loop=src['loop'],tracks=tracks)
    return elements,dict(bones=bones,groups=groups,clips=clips,default_hidden=[],stride_units=base_rig['stride_units']*SCALE)
