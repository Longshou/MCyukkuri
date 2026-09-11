"""Author species-specific cuboids; shared Reimu body, face and motion sources stay shared.

Run this only to regenerate the authored species components, then run build.py.
No original game or comic pixels are read by this portable authoring script.
Use --parts hair hand-left hand-right for side-hair geometry edits without rewriting textures or rigs.
"""
from pathlib import Path
import json, copy, math, argparse

R=Path(__file__).resolve().parent
def read(p): return json.loads((R/p).read_text(encoding='utf-8-sig'))
def save(p,data):
    p=R/p;p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

BASE=read('characters/reimu.json')
COMMON=[p for p in BASE['components'] if '/common/' in p and not p.endswith('/hair.json')]
COLORS={
 'chen':{'hair':(132,83,68),'hair_light':(157,103,84),'hair_dark':(101,61,52),'green':(61,148,51),'green_light':(81,177,59),'green_dark':(35,103,43),'cream':(246,231,200),'pink':(226,153,148),'gold':(241,204,86),'ear':(65,56,47),'ear_rim':(165,113,96),'ear_shadow':(194,122,119),'tail_white':(246,243,232)},
 'alice':{'hair':(236,204,102),'hair_light':(255,227,138),'hair_dark':(197,160,72),'red':(209,46,59),'red_dark':(164,34,49),'cream':(255,246,218)},
 'patchouli':{'hair':(112,81,161),'hair_light':(137,106,185),'hair_dark':(85,60,133),'cream':(247,222,233),'cream_light':(255,237,242),'cream_dark':(211,180,207),'red':(209,57,74),'blue':(66,107,202),'gold':(252,219,92),'fringe':(112,88,165),'fringe_light':(127,104,180)}
}

parser=argparse.ArgumentParser();parser.add_argument('--species',nargs='+',choices=list(COLORS),default=list(COLORS))
parser.add_argument('--parts',nargs='+',choices=['hair','hand-left','hand-right'],help='Author only selected hair components; keep textures, character registration and rig unchanged.')
args=parser.parse_args()
if not args.parts:
    from PIL import Image, ImageDraw
for species,label in [('chen','橙'),('alice','爱丽丝'),('patchouli','帕秋莉')]:
    if species not in args.species:continue
    if not args.parts:atlas=Image.open(R/BASE['base_texture']).convert('RGBA')
    occupied=set()
    for ref in COMMON:
        if ref.endswith('/face.json'):continue
        for e in read(ref)['elements']:
            for f in e['faces'].values():
                u,v,U,V=f['uv'];occupied.update((x,y) for x in range(math.floor(min(u,U)),math.ceil(max(u,U))) for y in range(math.floor(min(v,V)),math.ceil(max(v,V))))
    palette={}
    if not args.parts:draw=ImageDraw.Draw(atlas)
    for index,(name,color) in enumerate(COLORS[species].items()):
        x,y=next((x,y) for y in range(64,121,8) for x in range(0,121,8) if not any((X,Y) in occupied for X in range(x,x+8) for Y in range(y,y+8)))
        occupied.update((X,Y) for X in range(x,x+8) for Y in range(y,y+8));palette[name]=[x,y,x+8,y+8]
        if not args.parts:
            draw.rectangle((x,y,x+7,y+7),fill=(*color,255))
            if name.startswith('hair'):
                for dx,dy in [(1,1),(4,3),(6,0)]:draw.line((x+dx,y+dy,x+dx,y+min(7,dy+3)),fill=tuple(min(255,c+9) for c in color)+(255,))
    parts={'hair':[],'hand-left':[],'hand-right':[],'accessories':[]};extra=[]
    def cube(part,name,a,b,material='hair',bone='hair',rotation=None,origin=None):
        e=dict(name=species+'_'+name,type='cube',**{'from':list(a),'to':list(b)},origin=origin or [0,0,0],rotation=rotation or [0,0,0],box_uv=False,rescale=False,autouv=0,faces={f:dict(uv=palette[material][:],texture=0) for f in ['north','east','south','west','up','down']},bone=bone)
        parts[part].append(e)
    def bone(name,parent,pivot):extra.append(dict(name=name,parent=parent,pivot=pivot))
    def bar(part,name,p,q,width,material,bn):
        # Cuboid between two authored endpoints, using the same Euler convention as geometry.py.
        d=[q[i]-p[i] for i in range(3)];length=math.sqrt(sum(v*v for v in d));middle=[(a+b)/2 for a,b in zip(p,q)]
        angle_x=math.degrees(math.asin(-d[2]/length));angle_z=math.degrees(math.atan2(-d[0],d[1]))
        cube(part,name,[middle[0]-width/2,middle[1]-length/2,middle[2]-width/2],[middle[0]+width/2,middle[1]+length/2,middle[2]+width/2],material,bn,[angle_x,0,angle_z],middle)
    # Preserve Reimu's overall head envelope, with distinct silhouettes at the hair ends.
    cube('hair','cap',[-6.35,8.18,-4.58],[6.35,9.50,6.85],'hair','hair')
    cube('hair','crown',[-5.35,9.40,-3.60],[5.35,9.78,5.95],'hair_light','crown')
    back_bottom={'chen':3.10,'alice':1.90,'patchouli':.65}[species]
    cube('hair','back',[-6.20,back_bottom+1,6.02],[6.20,8.90,7.45],'hair_dark')
    for i in range(7):
        x=-6.2+i*1.78;end=back_bottom+[.45,0,.4,.12,.3,0,.5][i]
        material='hair' if i%2 else 'hair_light'
        cube('hair','back_lock_'+str(i),[x,end+.48,7.10],[x+1.65,8.35,7.80],material)
        if species=='chen':
            cube('hair','back_point_'+str(i),[x+.20,end,7.12],[x+1.40,end+.65,7.78],material)
            cube('hair','back_point_tip_'+str(i),[x+.48,end-.36,7.16],[x+.97,end+.1,7.73],'hair_dark')
        elif species=='alice':
            # A bob hem turns inward instead of continuing down as a straight curtain.
            cube('hair','bob_hem_'+str(i),[x+.13,end,6.85],[x+1.52,end+.58,7.57],material)
            cube('hair','bob_hem_tip_'+str(i),[x+.36,end-.20,6.55],[x+1.25,end+.10,7.23],'hair')
        else:
            cube('hair','long_back_hem_'+str(i),[x-.10,end,7.08],[x+1.78,end+.72,7.95],material)
    bangs={'chen':[6.2,6.95,6.35,5.65,6.85,6.3,6.0],'alice':[6.1,7.1,7.25,6.6,7.35,7.0,6.1],'patchouli':[6.8,7.10,7.10,7.10,7.10,7.10,6.8]}[species]
    for i,y in enumerate(bangs if species!='patchouli' else []):
        x=-6.05+i*1.73
        cube('hair','bang_'+str(i),[x,y,-5.08],[x+1.75,9.02,-4.57],'hair_light' if i in [1,4] else 'hair','bangs')
        if species!='patchouli':
            cube('hair','bang_tip_'+str(i),[x+.25,y-.38,-5.06],[x+1.48,y+.08,-4.6],'hair','bangs')
            cube('hair','bang_point_'+str(i),[x+.50,y-.70,-5.04],[x+1.17,y-.28,-4.61],'hair','bangs')
    if species=='patchouli':
        # Five unequal broad locks form the soft comic fringe; the off-center split is geometry.
        # Each lock has an authored low point, shallow notches and a rounded-under lower edge.
        locks=[(-6.48,-4.24,5.66,.78,.12),(-4.24,-.72,6.38,.55,.42),
               (-.72,1.62,6.52,.66,.41),(1.62,4.36,6.43,.51,.57),
               (4.36,6.48,5.70,.79,.88)]
        for lock,(left,right,low,rise,tip) in enumerate(locks):
            columns=math.ceil((right-left)/.28);step=(right-left)/columns
            for j in range(columns):
                x=left+j*step;mid=x+step/2;t=(mid-left)/(right-left)
                distance=(tip-t)/max(tip,.01) if t<tip else (t-tip)/max(1-tip,.01)
                lower=low+rise*distance**1.65
                z=-5.43+.68*(abs(mid)/6.48)**2
                # Recessed top, full middle, and a tucked edge give volume without thin stray rods.
                for k,(y,Y,front) in enumerate([(lower,lower+.29,z+.13),(lower+.27,8.12,z),(8.10,9.26,z+.19)]):
                    cube('hair',f'soft_fringe_{lock}_{j}_{k}',[x,y,front],[x+step+.018,Y,z+1.05],'hair','bangs')
        # A few broad, quiet highlights follow the curved surface rather than outlining every lock.
        for i,(x,width,y) in enumerate([(-2.85,.75,8.15),(.37,.48,8.24),(3.18,.63,8.08)]):
            z=-5.43+.68*(abs(x)/6.48)**2
            # These sit on the middle tier; keep their top below its recessed shoulder.
            cube('hair','fringe_soft_sheen_'+str(i),[x-width/2,y-.28,z-.012],[x+width/2,min(y,8.10),z+.025],'hair_light','bangs')
    for sign,side in [(-1,'left'),(1,'right')]:
        x=sign*6.5
        if species=='patchouli':
            # Patchouli has broad cheek-framing locks, authored independently of the bob/tuft templates.
            # Each section is (height, outward center, half width, depth center, half depth).
            def section(profile,y):
                for a,b in zip(profile,profile[1:]):
                    if y<=b[0]:
                        t=max(0,min(1,(y-a[0])/(b[0]-a[0])))
                        return [a[k]+(b[k]-a[k])*t for k in range(1,5)]
                return list(profile[-1][1:])
            def rounded_lock(part,name,profile,rows,bn):
                low,high=profile[0][0],profile[-1][0]
                for j in range(rows):
                    y=low+(high-low)*j/rows;Y=low+(high-low)*(j+1)/rows
                    X,w,Z,d=section(profile,(y+Y)/2)
                    # Three overlapping depth slabs bevel both side edges of the full hair mass.
                    for k,(front,back,factor) in enumerate([(-1,-.48,.74),(-.5,.50,1),(.48,1,.78)]):
                        a,b=sorted([sign*(X-w*factor),sign*(X+w*factor)])
                        cube(part,f'{name}_{side}_{j}_{k}',[a,y,Z+d*front],[b,Y+.008,Z+d*back],'hair' if k<2 else 'hair_dark',bn)
            # The side curtain swells around the cheek and joins the rear hair, instead of a flat wall.
            curtain=[(.22,7.10,.36,.65,5.05),(.75,7.30,1.04,1.05,5.15),
                     (2.1,7.48,1.35,1.35,5.35),(4.4,7.14,1.32,1.80,5.10),
                     (6.7,6.54,1.08,1.85,4.60),(8.1,6.10,.84,1.48,4.12),
                     (9.2,5.88,.72,1.28,3.74)]
            rounded_lock('hair','cheek_curtain',curtain,24,'hair')
            # The entire lock is one elongated spindle, broadest in its lower half, with no middle waist.
            # It wraps the cheek from the bonnet to the floor; only its small terminal tuft is tied.
            lock=[(.10,7.75,.85,-5.50,.68),(.65,8.00,1.70,-5.20,1.26),
                  (1.65,8.05,2.20,-4.98,1.55),(2.65,7.91,2.18,-4.79,1.59),
                  (3.85,7.55,1.98,-4.54,1.55),(5.25,7.13,1.72,-4.24,1.42),
                  (6.55,6.76,1.39,-3.97,1.22),(7.85,6.36,1.10,-3.57,1.02),
                  (9.10,5.96,.73,-3.08,.82)]
            hand='hand-'+side;bn='hand_'+side
            rounded_lock(hand,'cheek_lock',lock,36,bn)
            # Sparse lengthwise sheen follows the flowing lock; no horizontal alternating braid bands.
            for j in range(22):
                y=.75+j*.34;Y=y+.35;X,w,Z,d=section(lock,(y+Y)/2)
                xx=sign*(X+.22*w)
                cube(hand,f'lock_sheen_{side}_{j}',[xx-.09,y,Z-d-.012],[xx+.09,Y,Z-d+.025],'hair_light',bn)
            # A low tie and a short rounded tuft extend from the front of the grounded hair mass.
            X=sign*8.20;color='blue' if sign<0 else 'red'
            cube(hand,'terminal_neck_'+side,[X-.37,.35,-6.49],[X+.37,.90,-5.98],'hair',bn)
            cube(hand,'terminal_tie_'+side,[X-.40,.29,-6.58],[X+.40,.94,-6.34],color,bn)
            cube(hand,'terminal_knot_'+side,[X-.17,.43,-6.66],[X+.17,.76,-6.54],color,bn)
            tuft=[(0,8.20,.29,-6.89,.24),(.18,8.20,.52,-6.90,.43),
                  (.45,8.20,.55,-6.80,.48),(.70,8.20,.30,-6.63,.25)]
            rounded_lock(hand,'resting_tuft',tuft,4,bn)
            continue
        side_bottom=3.4 if species=='alice' else back_bottom+.3
        cube('hair','side_'+side,[x-.63,side_bottom,-2.5],[x+.63,8.6,6.30],'hair')
        if species=='alice':
            for j,(y,h,shift,width) in enumerate([(2.6,1.05,0,1.3),(1.85,.95,-.30,1.15),(1.2,.8,-.67,.8)]):
                xx=x+sign*shift
                cube('hair','bob_side_curve_'+side+str(j),[xx-width/2,y,-2.35],[xx+width/2,y+h,6.2-j*.22],'hair_light')
        elif species=='chen':
            for j in range(4):
                xx=x+sign*(.12+j*.11)
                cube('hair','ragged_side_'+side+str(j),[xx-.52,2.85+j*.85,-2.2],[xx+.52,4.0+j*.8,6.45],'hair',rotation=[0,0,-sign*(8+j*2)],origin=[x,6,2])
            cube('hair','crown_tuft_'+side,[x-.2,8.7,3.1],[x+1.0,9.25,4.9],'hair_light',rotation=[0,0,sign*16],origin=[x,8.7,4])
        hand='hand-'+side;bn='hand_'+side
        if species=='alice':
            cube(hand,'bob_front_'+side,[x-.69,3.65,-3.95],[x+.69,8.45,-2.45],'hair_light',bn)
            for j,(y,h,shift,width) in enumerate([(2.8,1.10,0,1.32),(2.03,1.02,-.26,1.15),(1.39,.86,-.6,.9),(.98,.57,-.9,.48)]):
                xx=x+sign*shift
                cube(hand,'bob_inward_tip_'+side+str(j),[xx-width/2,y,-3.93],[xx+width/2,y+h,-2.53],'hair_light' if j<2 else 'hair',bn)
        elif species=='chen':
            cube(hand,'side_lock_'+side,[x-.57,2.05,-3.8],[x+.57,8.5,-2.5],'hair',bn)
            for j in range(3):
                xx=x+sign*(.2+j*.20)
                cube(hand,'tuft_'+side+str(j),[xx-.32,1.95+j*.95,-3.85],[xx+.32,3.3+j*.85,-2.55],'hair_dark' if j==0 else 'hair',bn,[0,0,-sign*14],[x,4,-3.2])
    if species=='alice':
        bone('alice_headband','features_squash',[0,9.4,-2.8])
        for i,(left,right,y) in enumerate([(-6.45,-5.2,8.65),(-5.2,-3.7,9.4),(-3.7,3.7,9.9),(3.7,5.2,9.4),(5.2,6.45,8.65)]):
            cube('accessories','band_white_'+str(i),[left,y-.15,-3.88],[right,y+.62,-2.6],'cream','alice_headband')
            cube('accessories','band_red_'+str(i),[left,y,-3.94],[right,y+.44,-2.55],'red','alice_headband')
        for i in range(13):
            x=-5.95+i*.92;y=9.9 if abs(x)<3.7 else 9.4 if abs(x)<5.2 else 8.65
            cube('accessories','lace_'+str(i),[x-.20,y+.60,-3.90],[x+.2,y+.86,-2.62],'cream','alice_headband')
        for sign in [-1,1]:
            x=sign*6.46;cube('accessories','band_side_'+str(sign),[x-.12,6.9,-3.1],[x+.12,8.9,-2.0],'red','alice_headband')
    elif species=='patchouli':
        bone('patchouli_bonnet','features_squash',[0,9.6,1])
        bn='patchouli_bonnet'
        # Layered soft bonnet with staggered ruffles around the full perimeter.
        cube('accessories','bonnet_rim',[-5.5,9.10,-4.90],[5.5,9.65,7.05],'cream_dark',bn)
        cube('accessories','bonnet_body',[-4.8,9.55,-4.55],[4.8,11.15,6.7],'cream',bn)
        cube('accessories','bonnet_top',[-3.8,11.1,-3.5],[3.8,11.78,5.8],'cream_light',bn)
        for sign in [-1,1]:
            for name,inner,outer,y,Y,z,Z,mat in [('rim',5.5,7.,9.1,9.65,-3.7,5.9,'cream_dark'),('body',4.8,6.7,9.55,10.95,-3.4,5.6,'cream'),('top',3.8,5.1,11.1,11.55,-2.5,4.7,'cream_light')]:
                X=sorted([sign*inner,sign*outer]);cube('accessories','bonnet_corner_'+name+str(sign),[X[0],y,z],[X[1],Y,Z],mat,bn)
        for i in range(11):
            x=-6.55+i*1.25
            for z in ([-4.96,6.8] if abs(x)<5.4 else [-3.78,5.72]):cube('accessories','frill_'+str(i)+'_'+str(z),[x-.54,8.88+(i%2)*.2,z-.22],[x+.54,9.80,z+.3],'cream_light' if i%2 else 'cream',bn)
        for sign in [-1,1]:
            for j in range(6):cube('accessories','side_frill_'+str(sign)+'_'+str(j),[sign*6.95-.27,9.05+(j%2)*.16,-3.6+j*1.8],[sign*6.95+.27,9.85,-2.1+j*1.8],'cream',bn)
            cube('accessories','hat_ribbon_'+str(sign),[sign*6.5-.65,9.8,3.6],[sign*6.5+.65,10.25,4.5],'blue' if sign<0 else 'red',bn)
        # Crescent is stepped, solid cuboid geometry, with a genuinely open cutout.
        for row in range(12):
            yy=(row-5.5)*.22
            for col in range(10):
                xx=(col-4.5)*.22
                if xx*xx+yy*yy<=1.27**2 and (xx-.50)**2+(yy-.30)**2>.98**2:
                    cube('accessories',f'crescent_{row}_{col}',[2.25+xx,10.65+yy,-4.98],[2.47+xx,10.87+yy,-4.63],'gold',bn,[0,0,25],[2.36,10.76,-4.8])
    else:
        bone('chen_hat','features_squash',[0,9.4,1])
        for name,a,b,mat in [('hat_rim',[-5.5,9.45,-3.9],[5.5,9.88,5.9],'green_dark'),('hat_lower',[-5.3,9.85,-3.6],[5.3,10.60,5.6],'green'),('hat_upper',[-4.5,10.55,-2.9],[4.5,11.05,4.9],'green_light'),('hat_top',[-3.2,11,-1.9],[3.2,11.25,3.8],'green')]:cube('accessories',name,a,b,mat,'chen_hat')
        for i in range(9):
            x=-5.1+i*1.25
            cube('accessories','hat_scallop_'+str(i),[x-.38,9.22+(i%2)*.15,-3.92],[x+.38,9.72,-3.50],'green','chen_hat')
        for sign,side in [(-1,'left'),(1,'right')]:
            bn='chen_ear_'+side;root=[sign*5.65,7.95,-2.85];bone(bn,'hair',root)
            cube('accessories','ear_socket_'+side,[sign*5.65-.8,7.5,-3.95],[sign*5.65+.8,9.05,-1.75],'hair',bn)
            # Coarse bevelled blocks use the same spacing and hair palette as the model.
            # A broad clipped tip, inset light inner ear and root tufts retain the cat shape.
            rows=[(7.5,8.0,5.5,6.75),(8.0,8.5,5.5,7.0),(8.5,9.0,5.5,7.5),(9.0,9.5,5.75,8.0),(9.5,10.0,6.50,8.0),(10.0,10.5,7.25,8.0)]
            for j,(y,Y,a,b) in enumerate(rows):
                X=sorted([sign*a,sign*b])
                cube('accessories','ear_block_'+side+str(j),[X[0],y,-4.94],[X[1],Y,-4.06],'hair_dark',bn)
            for j,(y,Y,a,b) in enumerate([(8.15,8.50,6.35,6.78),(8.5,9.,6.32,7.23),(9.,9.5,6.35,7.67),(9.5,10.,6.92,7.67)]):
                X=sorted([sign*a,sign*b]);cube('accessories','ear_inner_'+side+str(j),[X[0],y,-4.965],[X[1],Y,-4.94],'cream',bn)
                X=sorted([sign*max(a,b-.27),sign*b]);cube('accessories','ear_pink_'+side+str(j),[X[0],y+.04,-4.98],[X[1],Y,-4.965],'pink',bn)
            X=sorted([sign*7.25,sign*8.0]);cube('accessories','ear_bevel_'+side,[X[0],10.,-4.96],[X[1],10.25,-4.88],'hair',bn)
            for j in range(2):
                X=sorted([sign*(6.38+j*.33),sign*(6.62+j*.33)])
                cube('accessories','ear_root_fur_'+side+str(j),[X[0],8.35+j*.25,-5.01],[X[1],8.57+j*.25,-4.96],'cream',bn)
            # Two separately rigged, gently curved tails. Their 3D path is an adaptation.
            bn='chen_tail_'+side;bone(bn,'rump_roll',[sign*.48,3.35,8.25])
            points=[[sign*.48,3.35,8.25],[sign*.60,3.20,9.45],[sign*.76,2.95,10.7],[sign*.93,2.85,11.95],[sign*1.09,3.0,13.15],[sign*1.21,3.4,14.25],[sign*1.27,4.05,15.05],[sign*1.25,4.65,15.35]]
            points=[[X,Y,8.25+(Z-8.25)*1.55] for X,Y,Z in points]
            for j,(p,q) in enumerate(zip(points,points[1:])):bar('accessories','tail_'+side+str(j),p,q,.78 if j<4 else .64,'tail_white' if j>=5 else 'hair_dark' if j<4 else 'hair',bn)
        # Anatomical left ear is screen-right from the front; only one gold hoop.
        bn='chen_ear_right'
        for i in range(12):
            a=i*math.tau/12;b=(i+1)*math.tau/12
            p=[6.86+.29*math.cos(a),7.99+.45*math.sin(a),-4.99];q=[6.86+.29*math.cos(b),7.99+.45*math.sin(b),-4.99]
            bar('accessories','earring_'+str(i),p,q,.14,'gold',bn)
    if species=='patchouli':
        # User refinement: slightly larger bonnet; preserve its sitting height.
        pivot=[0,9.1,1.1];scale=[1.12,1.10,1.12]
        for e in parts['accessories']:
            for key in ['from','to','origin']:
                e[key]=[round(pivot[i]+(v-pivot[i])*scale[i],5) for i,v in enumerate(e[key])]
    refs=[]
    for part,elements in parts.items():
        ref=f'components/{species}/{part}.json';refs.append(ref)
        if not args.parts or part in args.parts:save(ref,dict(id=species+'.'+part,elements=elements))
    if args.parts:
        print(species,', '.join(args.parts),'geometry authored; textures and rig preserved')
        continue
    save(f'components/{species}/rig.json',dict(bones=extra))
    out=R/f'textures/{species}/base.png';out.parent.mkdir(parents=True,exist_ok=True);atlas.save(out)
    c=copy.deepcopy(BASE);c.update(id=species,label=label,components=COMMON+refs,base_texture=f'textures/{species}/base.png',expression_binding=f'bindings/{species}.json',rig_components=BASE['rig_components']+[f'components/{species}/rig.json'],preview=dict(span=12.8,centerY=6.4,hand_label='侧发'),provenance='provenance/standard-species.json')
    # Regenerated characters require explicit approval against their new file fingerprints.
    if species=='patchouli':c['status']='pending-review'
    save(f'characters/{species}.json',c)
    print(species,sum(len(x) for x in parts.values()),'authored cuboids; shared Reimu rig')
