"""Low-poly house body with canonical library face, hair and accessories.

A low-bellied egg profile and fitted hair retain the library's cuboid construction.
Face, accessories and anatomy retain canonical geometry and UVs.
"""
import copy, math

SCALE=.24
HEAD_OFFSET=[0,1.50,-.08]
FACE_OFFSET=[0,1.15,-.08]
RUMP_OFFSET=[0,.30,-.71]
PENI_OFFSET=[0,.42,0]
BODY_DIMENSIONS=[3.28,3.95,2.36]
# Height, half width, half depth: round base, low belly, progressively narrower shoulders.
BODY_PROFILE=[(0,.60,.43),(.35,1.20,.86),(.85,1.53,1.10),(1.45,1.64,1.18),
              (2.05,1.55,1.11),(2.70,1.32,.95),(3.35,.88,.63),(3.95,.28,.20)]


def transform_point(p,offset):return [round(v*SCALE+o,9) for v,o in zip(p,offset)]

def make(species,base_bb,base_rig,standard_names):
    elements=[];groups=[]
    skin=next(e for e in base_bb['elements'] if e['name']=='soft_head_slice_0')
    def box(name,lo,hi,origin=None,rotation=None,source=skin,group='body'):
        e=copy.deepcopy(source);e.update(name=name,origin=origin or [0,0,0],rotation=rotation or [0,0,0])
        e['from']=lo;e['to']=hi;elements.append(e);groups.append(group)
        return e
    def mirrored(e,axis):
        e=copy.deepcopy(e)
        e['from'][axis],e['to'][axis]=-e['to'][axis],-e['from'][axis]
        e['origin'][axis]*=-1
        # Reflect the two axes of each rotation plane consistently with geometry.vertices.
        for k in range(3):
            if k!=axis:e['rotation'][k]*=-1
        return e
    def add_mirror(e,axis,suffix):
        other=mirrored(e,axis);other['name']+=suffix
        elements.append(other);groups.append('body')
        return other
    for i,((y0,x0,z0),(y1,x1,z1)) in enumerate(zip(BODY_PROFILE,BODY_PROFILE[1:])):
        x,z=min(x0,x1),min(z0,z1)
        # Solid octagonal infill stays inside both ends of each sloping interval.
        box(f'house_body_fill_x_{i}',[-x,y0,-.7*z],[x,y1,.7*z])
        box(f'house_body_fill_z_{i}',[-.7*x,y0,-z],[.7*x,y1,z])
        dx,dz=-.3*x,.3*z;length=math.hypot(dx,dz)
        corner=box(f'house_body_corner_{i}',[x,y0,.7*z],[x+length,y1,.7*z+.65*min(x,z)],
                   [x,y0,.7*z],[0,math.degrees(math.atan2(dz,dx)),0])
        opposite=add_mirror(corner,0,'_left')
        add_mirror(corner,2,'_front');add_mirror(opposite,2,'_front')
        # Continuous angled solids cover the outer silhouette; no full-height straight wall.
        for axis,r0,r1,span in [(0,x0,x1,.7*z),(2,z0,z1,.7*x)]:
            dr=r1-r0;dy=y1-y0;length=math.hypot(dr,dy)
            depth=abs(dr)*length/dy+.04
            angle=math.degrees(math.atan2(-dr,dy))
            if axis==0:
                e=box(f'house_body_side_x_{i}',[r0-depth,y0,-span],[r0,y0+length,span],
                      [r0,y0,0],[0,0,angle])
            else:
                e=box(f'house_body_side_z_{i}',[-span,y0,r0-depth],[span,y0+length,r0],
                      [0,y0,r0],[angle,0,0])
            add_mirror(e,axis,'_opposite')
    # The canonical pixel face remains flat; only this shallow support reaches its plane.
    box('house_body_face_support',[-1.27,1.10,-1.18],[1.27,2.98,-.95])
    for src in base_bb['elements']:
        if src['name'] not in standard_names:continue
        kind=standard_names[src['name']];offset=FACE_OFFSET if kind=='face' else HEAD_OFFSET
        e=copy.deepcopy(src)
        for key in ['from','to','origin']:
            if key in e:e[key]=transform_point(e[key],offset)
        # Fit the standard hair to the pointed egg crown; preserve its UVs.
        if kind=='hair':
            if e['name'] in ['hair_cap','crown']:
                if e['name']=='crown':continue
                for side in [-1,1]:
                    for segment,(x0,y0,x1,y1) in enumerate([(0,4.12,.65,4.0),(.65,4.0,1.50,3.58)]):
                        x=side*(x0+x1)/2;y=(y0+y1)/2
                        half=math.hypot(x1-x0,y1-y0)/2+.015
                        angle=side*math.degrees(math.atan2(y1-y0,x1-x0))
                        box(f'house_hair_slope_{side}_{segment}',[x-half,y-.14,-1.19],[x+half,y+.14,1.61],[x,y,0],[0,0,angle],e,'head')
                continue
            if e['name']=='back_hair':
                e['to'][1]=3.5
                e['from'][0]*=.87;e['to'][0]*=.87
            elif 'side_hair' in e['name']:
                e['to'][1]=min(e['to'][1],3.44)
                side=1 if e['from'][0]>0 else -1
                e['origin']=[(e['from'][0]+e['to'][0])/2,e['from'][1],0]
                e['rotation'][2]=side*13
            elif e['name'].startswith('bang_') and not e['name'].startswith('bang_tip_'):
                mid=(e['from'][0]+e['to'][0])/2
                e['to'][1]=4.18-abs(mid)*.27
        elements.append(e)
        groups.append('face' if kind=='face' else 'hand_left' if kind=='left' else 'hand_right' if kind=='right' else 'head')
    bones=[dict(name='world_motion',parent=None,pivot=[0,0,0]),
           dict(name='body',parent='world_motion',pivot=[0,0,0]),
           dict(name='head',parent='body',pivot=transform_point([0,7.42,1.6351562],HEAD_OFFSET)),
           dict(name='face',parent='body',pivot=transform_point([0,4.3725,2.3128906],FACE_OFFSET))]
    # Collapse the standard sliced rump to two cuboids, restoring its full anus UV.
    for prefix in ['rump_core_slice_','rump_round_end_slice_']:
        sources=[e for e in base_bb['elements'] if e['name'].startswith(prefix)]
        lo=[min(e['from'][i] for e in sources) for i in range(3)]
        hi=[max(e['to'][i] for e in sources) for i in range(3)]
        e=box('house_'+prefix.replace('_slice_',''),transform_point(lo,RUMP_OFFSET),transform_point(hi,RUMP_OFFSET),source=skin,group='rump')
        if 'round_end' in prefix:
            e['faces']['south']['uv']=[96,96,128,128]
            e['to'][2]=1.195
        else:e['from'][0]=-1.02;e['to'][0]=1.02
    bones.append(dict(name='rump',parent='body',pivot=[0,.82,.92]))
    for part,offset,pivot in [('mamu',RUMP_OFFSET,[.3,.71,8.2]),('peni',PENI_OFFSET,[0,1.15,-.88])]:
        bone='anatomy_'+part
        bones.append(dict(name=bone,parent='rump' if part=='mamu' else 'body',pivot=transform_point(pivot,offset)))
        for src in base_bb['elements']:
            if not src['name'].startswith(part+'_'):continue
            e=copy.deepcopy(src)
            for key in ['from','to','origin']:e[key]=transform_point(e[key],offset)
            elements.append(e);groups.append(bone)
    for side in ['left','right']:
        old=next(b for b in base_rig['bones'] if b['name']=='hand_'+side)
        bones.append(dict(name='hand_'+side,parent='body',pivot=transform_point(old['pivot'],HEAD_OFFSET)))
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
