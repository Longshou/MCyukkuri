"""Editable cuboid adaptation of the user's 2026-09-11 house Reimu drawing.

Shared egg silhouette and UV face sheet; no pixels sampled from the attachment.
Canonical Marisa hat and motion curves are reused through explicit adapters.
"""
import copy, math
from PIL import Image, ImageDraw

H=4.2
RX=1.66
RZ=1.22
PROFILE=[(0,.52),(.03,.69),(.10,.86),(.22,.97),(.37,1),(.53,.98),(.67,.92),(.79,.79),(.89,.61),(.96,.34),(1,0)]
FACES=['north','south','east','west','up','down']
PALETTE=[(255,236,206),(236,208,172),(64,45,40),(79,56,47),(110,76,57),(219,171,64),(244,202,96),(165,116,42),(218,53,52),(247,83,71),(255,246,222),(156,37,40),(35,33,41),(65,61,69)]

def radius(y):
    t=max(0,min(1,y/H))
    for (a,r),(b,s) in zip(PROFILE,PROFILE[1:]):
        if t<=b:return r+(s-r)*(t-a)/(b-a)
    return 0

def depth(x,y):
    r=radius(y)
    return RZ*r*math.sqrt(max(0,1-(x/max(.001,RX*r))**2))

def make(species,base_bb,base_rig):
    elements=[];groups=[]
    def box(name,lo,hi,color=0,bone='body',rotation=None,face=False):
        u=128+color*8
        faces={k:dict(uv=[u,0,u+8,8],texture=0) for k in FACES}
        if face:
            # Project the common facial sheet onto the actual curved skin surface.
            faces['north']['uv']=[(hi[0]+2)/4*128,(4.4-hi[1])/4.4*128,(lo[0]+2)/4*128,(4.4-lo[1])/4.4*128]
            # Color the tiny exposed stair edges from the same face projection,
            # so glossy eyes are not cut by pale horizontal/vertical seams.
            U=lambda x:(x+2)/4*128
            V=lambda y:(4.4-y)/4.4*128
            for side,x in [('east',hi[0]),('west',lo[0])]:faces[side]['uv']=[U(x),V(hi[1]),U(x),V(lo[1])]
            for side,y in [('up',hi[1]),('down',lo[1])]:faces[side]['uv']=[U(lo[0]),V(y),U(hi[0]),V(y)]
        elements.append(dict(name=name,type='cube',**{'from':lo,'to':hi},origin=[(a+b)/2 for a,b in zip(lo,hi)],rotation=rotation or [0,0,0],box_uv=False,faces=faces))
        groups.append(bone)

    # Use one aligned shell for skin and hair: mismatched overlapping grids
    # would create pinholes and exposed pale seams around the fringe.
    hair=2 if species=='reimu' else 5
    light=3 if species=='reimu' else 6
    tips=[(-1.66,2.25),(-1.40,2.22),(-1.25,2.76),(-1.03,2.99),(-.80,3.15),(-.59,3.43),(-.45,2.86),(-.23,2.71),(0,2.58),(.22,2.82),(.40,3.19),(.61,3.43),(.82,3.10),(1.03,2.99),(1.25,2.76),(1.40,2.22),(1.66,2.25)]
    def fringe(x):
        for (a,b),(c,d) in zip(tips,tips[1:]):
            if a<=x<=c:return b+(d-b)*(x-a)/(c-a)
        return 2.25
    rows=40;columns=32
    for j in range(rows):
        y0=j*H/rows;y1=(j+1)*H/rows;ym=(y0+y1)/2;r=radius(ym);w=RX*r
        for i in range(columns):
            x0=-w+2*w*i/columns;x1=-w+2*w*(i+1)/columns;xm=(x0+x1)/2;z=depth(xm,ym)
            is_hair=ym>=fringe(xm)
            col=light if is_hair and -.95<xm<-.77 and 3.38<ym<3.7 else hair
            box(f'egg_skin_{j}_{i}',[x0,y0,-z],[x1,y1,z],col if is_hair else 0,face=not is_hair)
            if ym>1.66 and not is_hair:
                # Cover the rear half continuously down to the short bob line.
                box(f'rear_hair_{j}_{i}',[x0-.004,y0-.003,max(0,z-.20)],[x1+.004,y1+.003,z+.022],hair)

    for side,sign in [('left',-1),('right',1)]:
        # Short tied locks, each with its own pivot and a tapered feathered tip.
        x=sign*1.63;bone='lock_'+side
        for j in range(10):
            y0=1.06+j*.105;w=.10+.17*math.sin((j+1)*math.pi/13)
            offset=sign*(.07*math.sin(j*.4))
            box(f'{side}_lock_{j}',[x+offset-w,y0,-.42],[x+offset+w,y0+.112,.09],hair,bone)
        for j,(y,h,col,w) in enumerate([(1.77,.055,10,.18),(1.83,.15,8 if species=='reimu' else 10,.175),(1.99,.05,10,.18)]):
            box(f'{side}_tie_{j}',[x-w,y,-.445],[x+w,y+h,.115],col,bone)

    if species=='reimu':
        for sign in [-1,1]:
            for i in range(12):
                a=i/12;b=(i+1)/12
                xa=.16+a*1.08;xb=.16+b*1.08
                y0=4.05-.10*a;y1=4.19+.45*a
                lo,hi=sorted([sign*xa,sign*xb])
                box(f'bow_lobe_{sign}_{i}',[lo,y0,.12],[hi,y1,.34],8 if i%4 else 9,'accessory')
            for j in range(6):
                box(f'bow_trim_{sign}_{j}',[sign*1.27-.12,3.99+j*.105,.095],[sign*1.27+.12,4.085+j*.105,.365],10,'accessory')
        box('bow_center',[-.19,4.025,.055],[.19,4.27,.395],8,'accessory')
    else:
        # Reuse the current ordinary Marisa hat geometry, adapted to this crown.
        for src,group in zip(base_bb['elements'],base_rig['groups']):
            if group not in ['hat','bow']:continue
            e=copy.deepcopy(src);e['name']='canonical_'+e['name']
            for k in ['from','to','origin']:
                if k in e:e[k]=[e[k][0]*.205,4.00+(e[k][1]-9.434)*.15,e[k][2]*.17]
            e['faces']={k:dict(uv=[128+(10 if 'band' in src['name'] or group=='bow' else 12)*8,0,136+(10 if 'band' in src['name'] or group=='bow' else 12)*8,8],texture=0) for k in FACES}
            elements.append(e);groups.append('accessory')

    bones=[dict(name='world_motion',parent=None,pivot=[0,0,0]),dict(name='body',parent='world_motion',pivot=[0,.04,0]),dict(name='accessory',parent='body',pivot=[0,4.05,.15]),dict(name='lock_left',parent='body',pivot=[-1.63,2.05,-.2]),dict(name='lock_right',parent='body',pivot=[1.63,2.05,-.2])]
    clips={}
    for name in ['idle','waddle','flee']:
        src=base_rig['clips'][name];tracks={}
        if name!='idle':
            tracks['body']=copy.deepcopy(src['tracks'].get('upper_body',{}))
            roll=src['tracks'].get('rump_roll',{}).get('rotation')
            if roll:tracks['body']['rotation']=copy.deepcopy(roll)
            world=copy.deepcopy(src['tracks'].get('world_motion',{}))
            if 'position' in world:world['position']={t:[v[0]*.22,v[1]*.3,v[2]*.22] for t,v in world['position'].items()}
            if world:tracks['world_motion']=world
        clips[name]=dict(duration=src['duration'],loop=src['loop'],tracks=tracks)
    rig=dict(bones=bones,groups=groups,clips=clips,default_hidden=[],stride_units=base_rig['stride_units']*.22)
    return elements,rig

def texture(expression):
    # Analytic facial art. The attached image is a shape reference only.
    scale=3;im=Image.new('RGBA',(256*scale,256*scale),(0,0,0,0))
    face=Image.new('RGBA',(128*scale,128*scale));px=face.load()
    for y in range(face.height):
        for x in range(face.width):
            X=(x/face.width-.5)*2;Y=y/face.height
            edge=min(1,abs(X)**2*.5+max(0,Y-.78)*1.4)
            px[x,y]=(int(255-17*edge),int(236-24*edge),int(207-30*edge),255)
    im.paste(face,(0,0));d=ImageDraw.Draw(im)
    def p(x,y):return ((x+2)/4*128*scale,(4.4-y)/4.4*128*scale)
    def ellipse(x,y,rx,ry,color):
        a=p(x-rx,y+ry);b=p(x+rx,y-ry);d.ellipse((*a,*b),fill=color)
    def line(points,color,width):d.line([p(x,y) for x,y in points],fill=color,width=max(1,round(width*128/4*scale)),joint='curve')
    ink=(35,24,24,255)
    for x in [-.79,.79]:
        ellipse(x*1.28,1.69,.225,.10,(255,181,158,255))
        if expression=='sleep':
            points=[(x+.32*math.cos(a),2.22-.12*math.sin(a)) for a in [i*math.pi/20 for i in range(21)]]
            line(points,ink,.045)
        else:
            ellipse(x,2.20,.34,.405,ink)
            ellipse(x-.08,2.40,.112,.137,(255,255,249,255))
            ellipse(x+.045,2.27,.05,.052,(255,255,247,255))
    if expression=='surprise':
        line([(-1.03,2.78),(-.92,2.72),(-.83,2.76),(-.72,2.84)],ink,.04)
        line([(.72,2.84),(.83,2.76),(.92,2.72),(1.03,2.78)],ink,.04)
        points=[(-.35,1.17),(-.32,1.68),(-.22,1.82),(-.10,1.75),(0,1.80),(.11,1.75),(.23,1.82),(.33,1.68),(.35,1.17)]
        d.polygon([p(x,y) for x,y in points],fill=(201,71,68,255));line(points+[points[0]],ink,.047)
        ellipse(0,1.32,.245,.115,(242,130,123,255))
    elif expression=='sleep':
        ellipse(0,1.67,.09,.11,ink)
    else:
        points=[]
        for cx in [-.12,.12]:
            points.extend((cx+.12*math.cos(a),1.76-.095*math.sin(a)) for a in [math.pi-i*math.pi/16 for i in range(17)])
        line(points,ink,.048)
        if expression=='chew':
            line([(-.29,1.85),(-.25,1.74),(-.27,1.63)],ink,.035)
            ellipse(.43,1.62,.135,.13,(174,112,39,255));ellipse(.43,1.65,.095,.085,(246,192,84,255))
        if expression=='happy':
            ellipse(0,1.56,.11,.095,(231,124,111,255))
    for i,color in enumerate(PALETTE):d.rectangle(((128+i*8)*scale,0,(136+i*8)*scale-1,8*scale-1),fill=(*color,255))
    return im.resize((256,256),Image.Resampling.LANCZOS)
