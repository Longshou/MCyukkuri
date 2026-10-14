"""Compile seven single-braid interactions from the current rounded ordinary Marisa.

Source recipes + a reusable reach solver; sampled tracks are exports, never sources.
v2: Marisa has one thick braid on her own left (+X). It does all gripping; the short
loose lock on the other side stays with the head. Braid, hat and body constants are
measured from assembled/marisa, so the choreography follows later shape revisions.
Run the parent library build first when standard components change.
"""
from pathlib import Path
import sys, json, copy, math, uuid, base64, io, hashlib

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
sys.path[:0] = [str(LIB), str(LIB/'ecology/house-yukkuri')]
from mesh_geometry import vertices, poly_mesh

def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p, data): p.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
def add(a,b): return [x+y for x,y in zip(a,b)]
def sub(a,b): return [x-y for x,y in zip(a,b)]
def mul(a,s): return [x*s for x in a]
def length(a): return math.sqrt(sum(x*x for x in a))
def mix(a,b,u): return add(mul(a,1-u),mul(b,u))
def smooth(t): t=max(0,min(1,t)); return t*t*(3-2*t)
def curve(keys,t):
    if t<=keys[0][0]: return keys[0][1]
    for (a,x),(b,y) in zip(keys,keys[1:]):
        if t<=b: return mix(x,y,smooth((t-a)/(b-a)))
    return keys[-1][1]
def rotate(v,angles):
    x,y,z=[math.radians(x) for x in angles]
    X,Y,Z=v;Y,Z=Y*math.cos(x)-Z*math.sin(x),Y*math.sin(x)+Z*math.cos(x)
    X,Z=X*math.cos(y)+Z*math.sin(y),-X*math.sin(y)+Z*math.cos(y)
    return [X*math.cos(z)-Y*math.sin(z),X*math.sin(z)+Y*math.cos(z),Z]
def aim(a,b):
    # Shortest-arc swing from -Y, avoiding roll when the lock points forward.
    d=sub(b,a);d=mul(d,1/max(length(d),1e-8))
    q=[-d[2],0,d[0],1-d[1]];norm=length(q)
    if norm<1e-8:return [0,0,180]
    x,y,z,w=mul(q,1/norm)
    return [math.degrees(math.atan2(2*(w*x+y*z),1-2*(x*x+y*y))),
            math.degrees(math.asin(max(-1,min(1,2*(w*y-z*x))))),
            math.degrees(math.atan2(2*(w*z+x*y),1-2*(y*y+z*z)))]

recipes=read(HERE/'recipes.json')
bb=read(LIB/'assembled/marisa/marisa.bbmodel')
base_rig=read(LIB/'assembled/marisa/animation-rig.json')
source=list(zip(bb['elements'],base_rig['groups']))
pivots={b['name']:b['pivot'] for b in base_rig['bones']}
def bounds(match):
    pts=[v[i:i+3] for e,_ in source if match(e['name']) for v in [vertices(e,256)] for i in range(0,len(v),8)]
    return [min(p[k] for p in pts) for k in range(3)],[max(p[k] for p in pts) for k in range(3)]

# Braid measured from the standard export: one straight chain from root to tuft.
core_lo,core_hi=bounds(lambda n:n=='braid_lock_core')
AXIS_X=(core_lo[0]+core_hi[0])/2; AXIS_Z=(core_lo[2]+core_hi[2])/2
TOP_Y=pivots['hand_right'][1]; BOTTOM_Y=bounds(lambda n:n.startswith('braid_lock_'))[0][1]
band_lo,band_hi=bounds(lambda n:n=='braid_lock_ribbon_band'); BAND_TOP,BAND_BOTTOM=band_hi[1],band_lo[1]
TUFT_TOP=bounds(lambda n:n.startswith('braid_lock_tuft'))[1][1]
LENGTH=TOP_Y-BOTTOM_Y; SEGMENTS=6; SEG=(TOP_Y-BAND_TOP)/SEGMENTS
ROOT=[AXIS_X,TOP_Y,AXIS_Z]; REST_END=[AXIS_X,BOTTOM_Y,AXIS_Z]
frac=lambda y:(TOP_Y-y)/LENGTH
# Hat: grip on solid brim at the front right, nearest the braid.
brim_lo,brim_hi=bounds(lambda n:n=='brim_center'); front_lo,front_hi=bounds(lambda n:n=='brim_front')
band_hat_lo,band_hat_hi=bounds(lambda n:n=='hat_band'); knot_lo,knot_hi=bounds(lambda n:n=='bow_knot')
HAT_BOTTOM=bounds(lambda n:n.startswith('brim_'))[0][1]; HAT_TOP=brim_hi[1]
HAT_CENTER=[0,HAT_TOP,(brim_lo[2]+brim_hi[2])/2]; HAT_PIVOT=[0,HAT_TOP,-3]
BRIM_RECTS=[(lo[0],hi[0],lo[2],hi[2]) for lo,hi in (bounds(lambda n,m=m:n==m) for m in ('brim_center','brim_sides','brim_front','brim_back'))]
GRIP_LOCAL=[front_hi[0]*.70,(front_lo[1]+front_hi[1])/2,(front_lo[2]+front_hi[2])/2]
GROUND_GRIP_Y=GRIP_LOCAL[1]-HAT_BOTTOM+.03
S=[1.08,1.1,1.05]  # v1 prop paths were authored on the pre-rounded body
def scaled(p): return [p[k]*S[k] for k in range(3)]

elements=[]; bones=[]; groups=[]
def bone(name,pivot=(0,0,0),parent=None):
    bones.append(dict(name=name,pivot=list(pivot),parent=parent))
def element(e,group):
    e=copy.deepcopy(e);e.pop('uuid',None);elements.append(e);groups.append(group)
def box(name,lo,hi,color,group):
    u=color*4  # prop palette row; the rounded atlas leaves rows 176+ empty
    element(dict(name=name,type='cube',**{'from':lo,'to':hi},origin=[0,0,0],rotation=[0,0,0],box_uv=False,
        faces={side:dict(uv=[u,240,u+3,243],texture=0) for side in ['north','south','east','west','up','down']}),group)

bone('adult')
bone('hat',HAT_PIVOT,'adult')
bone('hat_front',[0,HAT_TOP,front_hi[2]],'hat')
bone('bow',[0,(knot_lo[1]+knot_hi[1])/2,knot_hi[2]],'hat')
for i in range(SEGMENTS):bone(f'braid_{i}',[AXIS_X,TOP_Y-i*SEG,AXIS_Z],'adult')
bone('braid_ribbon',[AXIS_X,BAND_TOP,AXIS_Z],'adult')
bone('braid_tip',[AXIS_X,TUFT_TOP,AXIS_Z],'adult')
bone('grip',REST_END,'adult')
bone('coil',REST_END,'grip')
skipped=lambda g:g.startswith(('anatomy_','puff_')) or 'contact_' in g
for e,g in source:
    if skipped(g) or e['name'].startswith('braid_lock_'):continue
    element(e,'hat_front' if e['name']=='brim_front' else 'bow' if g=='bow' else 'hat' if g=='hat' else 'adult')
for e,g in source:
    n=e['name']
    if not n.startswith('braid_lock_'):continue
    if n=='braid_lock_core':
        # The core spans every segment; slice it with cropped side UVs.
        for i in range(SEGMENTS):
            top=min(e['to'][1],TOP_Y-i*SEG);bottom=max(e['from'][1],TOP_Y-(i+1)*SEG)
            if top<=bottom:continue
            part=copy.deepcopy(e);part['name']=f'braid_lock_core_{i}';part['from'][1]=bottom;part['to'][1]=top
            for f in ['north','south','east','west']:
                u,v,U,V=e['faces'][f]['uv'];h=e['to'][1]-e['from'][1]
                part['faces'][f]['uv']=[u,v+(V-v)*(e['to'][1]-top)/h,U,v+(V-v)*(e['to'][1]-bottom)/h]
            element(part,f'braid_{i}')
    elif n.startswith(('braid_lock_ribbon','braid_lock_bow')):element(e,'braid_ribbon')
    elif n.startswith('braid_lock_tuft'):element(e,'braid_tip')
    else:
        y=sum(vertices(e,256)[1::8])/36
        element(e,f'braid_{max(0,min(SEGMENTS-1,int((TOP_Y-y)/SEG)))}')
loop=next(e for e,_ in source if e['name']=='braid_lock_tuft_1')
for i,(lo,hi) in enumerate([([-.8,-.33,-.55],[-.47,.39,.39]),([.47,-.33,-.55],[.8,.39,.39]),([-.8,-.44,-.66],[.8,-.17,-.33])]):
    e=copy.deepcopy(loop);e['name']=f'grasp_{i}';e['from']=add(REST_END,lo);e['to']=add(REST_END,hi);e['rotation']=[0,0,0];e['origin']=[0,0,0];element(e,'coil')

palette=[[113,120,127],[155,161,158],[79,89,96],[176,208,220],[230,241,235],[184,137,53],[99,66,37],[64,115,50],[99,150,58],[121,83,49],[197,169,117],[231,222,197],[158,149,123]]
bone('stone');box('stone_body',[-.88,-.66,-.7],[.88,.66,.7],0,'stone');box('stone_top',[-.6,.66,-.44],[.44,.88,.5],1,'stone')
bone('sword');box('sword_grip',[-.24,-.88,-.24],[.24,.88,.24],6,'sword');box('sword_guard',[-1.1,.88,-.35],[1.1,1.2,.35],5,'sword');box('sword_blade',[-.42,1.2,-.13],[.42,6,.13],3,'sword');box('sword_edge',[-.14,1.2,-.16],[.14,6.1,.16],4,'sword');box('sword_tip',[-.24,6,-.11],[.24,6.6,.11],4,'sword');box('sword_pommel',[-.37,-1.15,-.33],[.37,-.77,.33],5,'sword')
bone('grass');box('grass_stem',[-.16,-.55,-.16],[.16,1.65,.16],7,'grass')
for i in range(5):
    box(f'grass_leaf_{i}',[-.88+i*.35,.22+(i%2)*.33,-.27],[-.66+i*.35,1.98+(i%3)*.33,.2],8 if i%2 else 7,'grass')
bone('grass_root',(0,0,0),'grass');box('grass_roots',[-.5,-.94,-.44],[.5,-.55,.44],9,'grass_root')
for i in range(5):
    bone(f'dirt_{i}');box(f'dirt_{i}',[-.13]*3,[.13]*3,9,f'dirt_{i}')
# Dust flecks sit on the front of the hat band and travel with the hat.
bow_right=bounds(lambda n:n.startswith('bow_'))[1][0]
for i in range(4):
    x=bow_right+.5+i*(band_hat_hi[0]-bow_right-1)/3;y=band_hat_lo[1]+.3+.25*(i%2);z=band_hat_lo[2]-.02
    bone(f'hat_dust_{i}',[x,y,z],'hat');box(f'hat_dust_{i}',[x-.26,y-.12,z-.05],[x+.26,y+.12,z+.02],9,f'hat_dust_{i}')
bone('child')
for e,g in source:
    if skipped(g):continue
    e=copy.deepcopy(e);e['name']='child_'+e['name'];element(e,'child')
bone('floor');box('ground',[-18,-.23,-22],[18,-.05,13],11,'floor')
for i in range(6):
    box(f'ground_line_{i}',[-16+i*6.4,-.049,-22],[-15.98+i*6.4,-.04,13],12,'floor')

def brim_depth(local,margin=.25):
    """Depth of a hat-local point inside the brim slab grown by `margin` (negative outside)."""
    vertical=min(local[1]-(HAT_BOTTOM-margin),HAT_TOP+margin-local[1])
    across=max(min(local[0]-x0+margin,x1+margin-local[0],local[2]-z0+margin,z1+margin-local[2]) for x0,x1,z0,z1 in BRIM_RECTS)
    return min(vertical,across)

def brim_detour(start,target,hat):
    """Control point that leads the braid out under the brim edge before it rises.

    Weighted by how deep the straight path would cut into the worn brim, so the path
    bends in continuously instead of switching between frames."""
    pos,rot=hat;inverse=lambda p:add(rotate(sub(sub(p,pos),HAT_PIVOT),[0,-rot[1],0]),HAT_PIVOT)
    line=[inverse(mix(start,target,i/40)) for i in range(41)]
    depth,q=max((brim_depth(p),p) for p in line)
    # No detour while the tip holds the brim itself; it fades in as the tip moves away.
    weight=smooth(depth/.6)*smooth((-brim_depth(line[-1],0)-.2)/.8)
    if weight<=0:return None
    d=[q[0]-HAT_CENTER[0],q[2]-HAT_CENTER[2]];n=math.hypot(*d) or 1;d=[d[0]/n,d[1]/n]
    r=0
    while brim_depth([HAT_CENTER[0]+d[0]*r,(HAT_BOTTOM+HAT_TOP)/2,HAT_CENTER[2]+d[1]*r],0)>0:r+=.1
    r+=1.2;local=[HAT_CENTER[0]+d[0]*r,HAT_BOTTOM-.5,HAT_CENTER[2]+d[1]*r]
    return mix(mix(start,target,.5),hat_point(local,pos,rot),weight)

def reach(target,hat=None):
    """Taut braid with a shallow outward bow; it stretches axially, never loops.

    `hat` is the pose of a worn hat to pass around: the braid then curves out
    under the brim edge (a quadratic path, spaced by arc length)."""
    # When reaching inward in front of the face, the root slides forward along the
    # side hair and the braid narrows slightly, so it does not cut into the cheek.
    clearance=smooth((AXIS_X-target[0]-.3)/4)*smooth((AXIS_Z-target[2]-.5)/4)
    start=add(ROOT,[.2*clearance,0,-2.6*clearance])
    dist=length(sub(target,start));width=1-.1*clearance
    bow=min(.09,dist*.0125)*smooth(length(sub(target,REST_END))/2)
    via=brim_detour(start,target,hat) if hat else None
    if via is None:
        def at(u):return add(mix(start,target,u),[bow*4*u*(1-u),0,0])
    else:
        bezier=lambda s:add(add(mul(start,(1-s)**2),mul(via,2*s*(1-s))),mul(target,s*s))
        table=[bezier(i/64) for i in range(65)];acc=[0]
        for a,b in zip(table,table[1:]):acc.append(acc[-1]+length(sub(b,a)))
        def at(u):
            goal=u*acc[-1];k=next((i for i in range(1,65) if acc[i]>=goal),64)
            f=(goal-acc[k-1])/max(acc[k]-acc[k-1],1e-9);return mix(table[k-1],table[k],f)
    result={}
    for i in range(SEGMENTS):
        a,b=at(i*SEG/LENGTH),at((i+1)*SEG/LENGTH)
        result[f'braid_{i}']=dict(position=sub(a,[AXIS_X,TOP_Y-i*SEG,AXIS_Z]),rotation=aim(a,b),scale=[width,length(sub(b,a))/SEG,width])
    a,b=at(frac(BAND_TOP)),at(frac(BAND_BOTTOM))
    result['braid_ribbon']=dict(position=sub(a,[AXIS_X,BAND_TOP,AXIS_Z]),rotation=aim(a,b),scale=[width,1,width])
    a,b=at(frac(TUFT_TOP)),at(1)
    result['braid_tip']=dict(position=sub(a,[AXIS_X,TUFT_TOP,AXIS_Z]),rotation=aim(a,b),scale=[width,length(sub(b,a))/(TUFT_TOP-BOTTOM_Y),width])
    result['grip']=dict(position=sub(target,REST_END))
    return result

def reach_blend(target,weight):
    """Sweep the taut braid about its root instead of passing through the head."""
    delta=sub(target,ROOT);distance=length(delta);direction=mul(delta,1/max(distance,1e-8))
    angle=math.acos(max(-1,min(1,-direction[1])))
    if angle<1e-6:return mix(REST_END,target,weight)
    axis=[direction[0],0,direction[2]];axis=mul(axis,1/max(length(axis),1e-8))
    # Leave and return by the outside: the azimuth starts outward (+X) and turns toward
    # the target while the braid rises, so the sweep clears the cheek.
    axis=mix([1,0,0],axis,weight);axis=mul(axis,1/max(length(axis),1e-8))
    heading=add(mul([0,-1,0],math.cos(angle*weight)),mul(axis,math.sin(angle*weight)))
    return add(ROOT,mul(heading,LENGTH*(1-weight)+distance*weight))

def hat_point(p,position,rotation):return add(add(rotate(sub(p,HAT_PIVOT),rotation),HAT_PIVOT),position)
def hat_from_grip(grip,rotation):
    """Hat pose that keeps its brim grip point at `grip`: a single braid turns it there."""
    return sub(grip,add(rotate(sub(GRIP_LOCAL,HAT_PIVOT),rotation),HAT_PIVOT)),rotation

# Hat routes (single grip). Turning happens high in front, so the brim clears the head.
G_UP=add(GRIP_LOCAL,[0,2.4,0])
REMOVE_GRIP=[(0,GRIP_LOCAL),(1.7,GRIP_LOCAL),(2.7,add(GRIP_LOCAL,[.6,3.2,-1.8])),(4.9,[8.4,13.0,-9.4]),(5.6,[8.4,13.0,-9.4]),(7.7,G_UP),(8.2,GRIP_LOCAL)]
REMOVE_TURN=[(0,[0,0,0]),(1.7,[0,0,0]),(2.7,[-6,-30,0]),(4.9,[-14,-125,0]),(5.6,[-14,-125,0]),(7.7,[0,0,0])]
CARE_HIGH=[6.9,13.6,-8.4]
CARE_GROUND=[6.9,GROUND_GRIP_Y,-8.8]
CARE_GRIP=[(0,GRIP_LOCAL),(1.7,GRIP_LOCAL),(2.8,add(GRIP_LOCAL,[.4,3.0,-1.6])),(4.6,CARE_HIGH),(7.4,CARE_HIGH),(8.6,CARE_GROUND),
           (12.2,CARE_GROUND),(13.2,CARE_HIGH),(15.6,CARE_HIGH),(16.6,G_UP),(17.6,G_UP),(18.6,GRIP_LOCAL)]
CARE_TURN=[(0,[0,0,0]),(4.6,[0,0,0]),(7.4,[0,-180,0]),(13.2,[0,-180,0]),(15.6,[0,0,0])]
# The tuft touches the band and knot from outside; the tip itself is about 1 unit thick.
# The bow covers most of the band front; wipe the exposed band beside it.
bow_lo,bow_hi=bounds(lambda n:n.startswith('bow_'))
WIPE=[[bow_hi[0]+.9,(band_hat_lo[1]+band_hat_hi[1])/2,band_hat_lo[2]-.75],[band_hat_hi[0]-.6,(band_hat_lo[1]+band_hat_hi[1])/2,band_hat_lo[2]-.75]]
KNOT=[(knot_lo[0]+knot_hi[0])/2,(knot_lo[1]+knot_hi[1])/2,knot_lo[2]-.7]

def hat_pose(action,t):
    keys,turn=(REMOVE_GRIP,REMOVE_TURN) if action=='remove_hat' else (CARE_GRIP,CARE_TURN)
    return hat_from_grip(curve(keys,t),curve(turn,t))

def braid_rest(o):o.update(reach(REST_END));o['coil']={'scale':[.000001]*3}

def ordinary_state(action,t):
    target=copy.deepcopy(REST_END);o={};contact=None;coil=1;visible=recipes['actions'][action]['prop']
    for n in ['stone','sword','grass','child']+[f'dirt_{i}' for i in range(5)]+[f'hat_dust_{i}' for i in range(4)]:o[n]={'scale':[.000001]*3}
    if action in ['pick_stone','swing_sword','pull_grass']:
        n={'pick_stone':'stone','swing_sword':'sword','pull_grass':'grass'}[action]
        ground=scaled([8.4,.77,-6]);end=scaled([10.2,.77,-6]) if n=='grass' else ground
        if n=='stone':ks=[(0,ground),(1.6,ground),(2.7,scaled([9.4,5.7,-6])),(4,scaled([9.4,5.7,-6])),(5.5,end)]
        elif n=='sword':ks=[(0,ground),(1.6,ground),(2.6,scaled([9.8,6.5,-5])),(3.15,scaled([8.9,5,-7])),(3.6,scaled([9.4,6.1,-5])),(4.2,scaled([9.1,4.9,-7])),(4.8,scaled([9.9,5.7,-5])),(5.8,end)]
        else:ks=[(0,ground),(2.5,ground),(2.85,scaled([8.8,3.6,-6])),(3.25,scaled([8.9,3.9,-6])),(3.5,scaled([9.1,3.5,-6])),(3.8,scaled([8.8,3.9,-6])),(4.1,scaled([9.1,3.6,-6])),(4.6,scaled([9,3.8,-6])),(5.6,end)]
        p=curve(ks,t);o[n]={'position':p,'scale':[1]*3}
        if n=='sword':o[n]['rotation']=curve([(0,[0,0,-68]),(1.6,[0,0,-68]),(2.6,[-15,0,25]),(3.15,[25,0,-65]),(3.6,[-15,0,25]),(4.2,[25,0,-65]),(4.8,[0,0,-10]),(5.8,[0,0,-68])],t)
        grab=1.6 if n!='grass' else 1.8;release=5.5 if n=='stone' else 5.8 if n=='sword' else 5.6
        target=reach_blend(p,smooth((t-.4)/(grab-.4))*(1-smooth((t-release)/.65)))
        contact=n if grab<=t<=release else None
        if n=='grass':
            o['grass_root']={'scale':[1]*3 if t>=2.6 else [.000001]*3}
            for i in range(5):
                age=t-(2.8+i*.10)
                if 0<=age<=1.5:o[f'dirt_{i}']={'position':[p[0]+(i-2)*age*.44,max(.11,3.5-age*age*4.2),p[2]+(i%2-.5)*age],'scale':[1]*3}
    elif action=='remove_hat':
        pos,rot=hat_pose(action,t);o['hat']={'position':pos,'rotation':rot}
        end=recipes['actions'][action]['release_time']
        target=reach_blend(hat_point(GRIP_LOCAL,pos,rot),smooth((t-.25)/1.45)*(1-smooth((t-end)/1.8)))
        contact='hat' if 1.7<=t<=end else None
    elif action=='care_hat':
        pos,rot=hat_pose(action,t);o['hat']={'position':pos,'rotation':rot}
        grip=hat_point(GRIP_LOCAL,pos,rot)
        # While the hat rests on the floor the braid lets go, wipes the band facing
        # Marisa, tidies the bow, then hooks the same brim point again.
        wipe=curve([(8.6,GRIP_LOCAL),(9.3,WIPE[0]),(9.9,WIPE[1]),(10.3,WIPE[0]),(10.6,WIPE[1]),(11.2,KNOT),(11.8,KNOT),(12.2,GRIP_LOCAL)],t) if 8.6<t<12.2 else GRIP_LOCAL
        work=hat_point(wipe,pos,rot) if 8.6<t<12.2 else grip
        end=recipes['actions'][action]['release_time']
        target=reach_blend(work,smooth((t-.25)/1.45)*(1-smooth((t-end)/1.8)))
        contact='hat' if 1.7<=t<=8.6 or 12.2<=t<=end else 'wipe' if t<12.2 and t>8.6 else None
        coil=1 if contact=='hat' else .000001
        o['floor']={'scale':[1,1,1],'position':[0,0,0]}
        tidy=smooth((t-11.2)/.25)*(1-smooth((t-11.8)/.4))
        o['bow']={'rotation':[0,0,tidy*3*math.sin(t*18)]}
        for i in range(4):
            # Each fleck is cleared as the braid tip passes over it.
            remaining=max(.000001,1-smooth((t-9.35-i*.14)/.25))
            o[f'hat_dust_{i}']={'scale':[remaining]*3}
    elif action=='wave_goodbye':
        # Raise and lower by sweeping about the root; a straight path from the hanging
        # tip would pass the root and flip the braid within a frame.
        # Wave outside the brim edge, so the braid never stands straight up.
        low,high=[13.6,10.9,-3.7],[12.4,12.6,-3.7]
        wave=curve([(1.4,[13.0,10.9,-3.7]),(1.8,high),(2.2,low),(2.6,high),(3,low),(3.4,high),(3.8,[13.0,10.9,-3.7])],t)
        target=reach_blend(wave,smooth((t-.6)/.8)*(1-smooth((t-3.8)/1.0)))
    # A worn or resting hat that the braid is not holding is passed around, not through.
    worn=hat_pose(action,t) if action in ('remove_hat','care_hat') else ([0,0,0],[0,0,0])
    o.update(reach(target,None if contact=='hat' else worn))
    o['coil']={'scale':[1 if contact and coil==1 else .000001]*3}
    if action=='wave_goodbye':
        nod=smooth((t-3.8)/.25)*(1-smooth((t-4.15)/.35))
        o['adult']={'scale':[1,1-.035*nod,1]}
    return o,dict(target=target,contact=contact)

# The child waits at the front right, nearest the braid. The braid wraps its body,
# lifts it over the brim and sets it on the extended front brim. The target override
# below is shared by exports and preview, so scrubbing cannot detach the child.
SHELF_Y=front_hi[1]+.16
# Rise in front of the brim, clear its top, then settle onto the brim as it extends.
CHILD=[(0,[5.4,0,-9.6]),(2,[5.4,0,-9.6]),(3.6,[5.8,6.6,-11.2]),(4.6,[4.6,SHELF_Y+1.9,-10.8]),(5.6,[3.6,SHELF_Y+.35,-8.6]),(6.2,[3.4,SHELF_Y,-8.1])]
def state(action,t):
    if action!='lift_child':return ordinary_state(action,t)
    o,meta=ordinary_state('wave_goodbye',0)
    p=curve(CHILD,t);s=recipes['child_scale']
    shelf=smooth((t-4.5)/.9)
    o['hat_front']={'scale':[1,1,1+3.65*shelf],'position':[0,.16*shelf,0]}
    o['child']={'position':p,'scale':[s]*3}
    hold=add(p,[0,1.45,0]);w=smooth((t-.5)/1.5)*(1-smooth((t-6.2)/2.0))
    target=reach_blend(hold,w);o.update(reach(target,([0,0,0],[0,0,0])));meta.update(target=target,contact='child' if 2<=t<=6.2 else None)
    o['coil']={'scale':[1.7]*3 if 2<=t<=6.2 else [.000001]*3}
    return o,meta

def compile_clips():
    clips={}; samples={}
    for action,recipe in recipes['actions'].items():
        tracks={};duration=recipe['duration'];samples[action]=[]
        for i in range(round(duration*recipes['sample_rate'])+1):
            t=round(i/recipes['sample_rate'],6);pose,meta=state(action,t);samples[action].append(dict(time=t,**meta))
            for b in bones:
                values=pose.get(b['name'],{})
                for ch,default in [('position',[0]*3),('rotation',[0]*3),('scale',[1]*3)]:
                    tracks.setdefault(b['name'],{}).setdefault(ch,{})[str(t)]=[round(v,7) for v in values.get(ch,default)]
        # Euler equivalents stay continuous before linear interpolation in both the
        # viewer and exported engines; otherwise +/-180 produces a full spin.
        for channels in tracks.values():
            previous=None
            for values in channels['rotation'].values():
                if previous is not None:
                    candidates=[values[:],[values[0]+180,180-values[1],values[2]+180]]
                    for candidate in candidates:
                        for axis in range(3):candidate[axis]+=360*round((previous[axis]-candidate[axis])/360)
                    values[:]=min(candidates,key=lambda v:sum((a-b)**2 for a,b in zip(v,previous)))
                previous=values
        clips[action]=dict(label=recipe['label'],duration=duration,loop=False,tracks=tracks)
    return clips,samples

def main():
    from PIL import Image
    clips,samples=compile_clips()
    atlas=Image.open(LIB/'assembled/marisa/marisa.png').convert('RGBA')
    for i,color in enumerate(palette):atlas.paste((*color,255),(i*4,240,i*4+4,244))
    OUT=HERE/'assembled';OUT.mkdir(exist_ok=True);atlas.save(OUT/'marisa-braids.png')
    buf=io.BytesIO();atlas.save(buf,format='PNG');texture='data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode()
    uid=lambda n:str(uuid.uuid5(uuid.NAMESPACE_URL,'mcyukkuri/marisa-braids/'+n))
    nodes={b['name']:dict(name=b['name'],uuid=uid('bone/'+b['name']),origin=b['pivot'],children=[]) for b in bones}
    for e,g in zip(elements,groups):e['uuid']=uid(e['name']);nodes[g]['children'].append(e['uuid'])
    for b in bones:
        if b['parent']:nodes[b['parent']]['children'].append(nodes[b['name']])
    animations=[];exports={}
    for action,clip in clips.items():
        animators={};channels={}
        for n,chs in clip['tracks'].items():
            animators[uid('bone/'+n)]=dict(name=n,type='bone',keyframes=[]);channels[n]={}
            for ch,keys in chs.items():
                # Omit constant default channels, preserving explicit visibility.
                if all(v==([1]*3 if ch=='scale' else [0]*3) for v in keys.values()):continue
                channels[n][ch]={}
                for t,v in keys.items():
                    val=[-v[0],-v[1],v[2]] if ch=='rotation' else [-v[0],v[1],v[2]] if ch=='position' else v
                    channels[n][ch][t]=val;animators[uid('bone/'+n)]['keyframes'].append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',map(str,val)))],uuid=uid(action+n+ch+t)))
        name='animation.marisa.'+action
        animations.append(dict(name=name,uuid=uid(name),loop='once',length=clip['duration'],snapping=20,animators=animators))
        exports[name]=dict(loop=False,animation_length=clip['duration'],bones=channels)
    model=dict(meta=dict(format_version='4.10',model_format='free',box_uv=False),name='普通魔理沙 · 单辫互动',resolution=dict(width=256,height=256),elements=elements,outliner=[nodes[b['name']] for b in bones if b['parent'] is None],textures=[dict(name='marisa-braids.png',path='marisa-braids.png',id='0',uuid=uid('texture'),source=texture,mode='bitmap',width=256,height=256,uv_width=256,uv_height=256)],animations=animations)
    save(OUT/'marisa-braids.bbmodel',model)
    save(OUT/'marisa-braids.animation.json',dict(format_version='1.8.0',animations=exports))
    geo=[]
    for b in bones:
        g=dict(name=b['name'],pivot=b['pivot'],cubes=[])
        if b['parent']:g['parent']=b['parent']
        geo.append(g)
        for e,n in zip(elements,groups):
            if n!=b['name']:continue
            if e.get('type')=='mesh':
                # Rounded body, face and hair surfaces are meshes, not cuboids.
                geo.append(dict(name=e['name']+'_mesh',parent=n,pivot=[0,0,0],poly_mesh=poly_mesh(e)))
                continue
            cube=dict(origin=e['from'],size=sub(e['to'],e['from']),uv={k:dict(uv=f['uv'][:2],uv_size=sub(f['uv'][2:],f['uv'][:2])) for k,f in e['faces'].items()})
            if any(e.get('rotation',[0,0,0])):cube.update(pivot=e['origin'],rotation=e['rotation'])
            g['cubes'].append(cube)
    save(OUT/'marisa-braids.geo.json',{'format_version':'1.12.0','minecraft:geometry':[dict(description=dict(identifier='geometry.marisa_braids',texture_width=256,texture_height=256,visible_bounds_width=5,visible_bounds_height=5,visible_bounds_offset=[0,1,0]),bones=geo)]})
    # Rigid per-element binding for the shared preview poser (meshes vary in size).
    flat=[];weights=[];counts=[];index={b['name']:i for i,b in enumerate(bones)}
    for e,g in zip(elements,groups):
        vv=vertices(e,256);flat.extend(vv);counts.append(len(vv)//8);weights.extend([[index[g],index[g],0]]*(len(vv)//8))
    rig=dict(bones=bones,groups=groups,clips=clips,element_vertex_counts=counts,vertex_weights=weights,binding_mode='rigid_per_element')
    save(OUT/'animation-rig.json',rig)
    data=dict(model=dict(vertices=[round(v,6) for v in flat],rig=rig),texture=texture,actions=recipes['actions'],samples=samples)
    runtime='const DATA='+json.dumps(data,ensure_ascii=False,separators=(',',':'))+';\n'+(LIB/'runtime/motion.js').read_text(encoding='utf-8')+'\n'+(LIB/'runtime/render.js').read_text(encoding='utf-8')
    html=(HERE/'viewer.html').read_text(encoding='utf-8').replace('__RUNTIME__',runtime)
    (HERE/'index.html').write_text(html,encoding='utf-8')
    base=LIB/'assembled/marisa/marisa.bbmodel'
    save(OUT/'provenance.json',dict(version='v2-single-braid',base='assembled/marisa/marisa.bbmodel',sha256=hashlib.sha256(base.read_bytes()).hexdigest(),
        rig='assembled/marisa/animation-rig.json',rig_sha256=hashlib.sha256((LIB/'assembled/marisa/animation-rig.json').read_bytes()).hexdigest(),
        recipes='../recipes.json',references='../REFERENCES.md',sample_rate=recipes['sample_rate'],actions=list(clips),
        braid=dict(root=ROOT,rest_end=REST_END,length=LENGTH,segments=SEGMENTS),hat_grip=GRIP_LOCAL))
    print(f'Built {len(clips)} single-braid interactions, {len(elements)} parts, {len(bones)} bones.')

if __name__=='__main__':
    main()
