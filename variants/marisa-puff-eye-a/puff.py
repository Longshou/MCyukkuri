"""Physical cheek lobes and an exported facial performance, all positive-scale bones."""
import math

def add_geometry(cubes,uv,parts):
    for side,label in [(-1,'left'),(1,'right')]:
        cx=side*3.8
        # Nine original contour tiers, resting as a flat patch on the skin.
        # Each tier expands out from the face plane instead of translating through it.
        for j in range(9):
            y=(j-4)*.09; radius=math.sqrt(1-(y/.46)**2)
            cubes.append(dict(name=f'puff_{label}_{j}',group=f'puff_{label}_{j}',
                lo=[cx-2.65*radius*.8,2.7+(y*4.5-.225)*.8,-4.622],
                hi=[cx+2.65*radius*.8,2.7+(y*4.5+.225)*.8,-4.62],
                faces={k:uv['skin'][:] for k in ('north','south','east','west','up','down')}))
    for j,n in enumerate(('eye_left','eye_right','mouth')):
        x,y,X,Y=parts[n]['face_rect'];u=j*32
        fs={k:[127,47,127.01,47.01] for k in ('north','south','east','west','up','down')}
        fs['north']=[u,80,u+X-x,80+Y-y]
        cubes.append(dict(name='puff_'+n,group='puff_'+n,lo=[6-X/4,8-Y/4,-4.3],hi=[6-x/4,8-y/4,-4.299],faces=fs))

def extra_bones(bb):
    specs=[('puff_left','face',[-3.8,2.7,-4.62]),('puff_right','face',[3.8,2.7,-4.62])]
    for label in ('left','right'):
        for j in range(9):specs.append((f'puff_{label}_{j}','puff_'+label,[0,0,-4.62]))
    for e in bb['elements']:
        if e['name'] in ('puff_eye_left','puff_eye_right','puff_mouth'):
            specs.append((e['name'],'face',[(a+b)/2 for a,b in zip(e['from'],e['to'])]))
    return specs

def animate():
    tracks={}
    def smooth(x):
        x=max(0,min(1,x));return x*x*(3-2*x)
    def curve(keys,t):
        for (a,x),(b,y) in zip(keys,keys[1:]):
            if t<=b:return x+(y-x)*smooth((t-a)/(b-a))
        return keys[-1][1]
    for i in range(321):
        t=round(i*.02,2)
        swell=curve([(0,0),(.65,0),(1.65,1),(3.65,1),(4.5,0),(6.4,0)],t)
        inhale=curve([(0,0),(.3,0),(.7,1),(1.5,0),(6.4,0)],t)
        release=curve([(0,0),(3.9,0),(4.45,1),(5.1,0),(6.4,0)],t)
        tremble=.018*math.sin((t-2)*math.pi*6)*smooth((t-2)/.4)*(1-smooth((t-3.4)/.3)) if t>2 else 0
        def put(n,ch,v):tracks.setdefault(n,{}).setdefault(ch,{})[str(t)]=[0 if abs(a)<1e-6 else round(a,7) for a in v]
        for side,label in [(-1,'left'),(1,'right')]:
            put('puff_'+label,'scale',[1+.25*swell,1+.25*swell,1])
            put('puff_'+label,'position',[side*(.6*swell+tremble),-.05*swell,0])
            for j in range(9):
                y=(j-4)*.09;radius=math.sqrt(1-(y/.46)**2)
                depth=1.596*radius-.12
                put(f'puff_{label}_{j}','scale',[1,1,1+(depth/.002-1)*swell])
            # Blush rides the front of each cheek, clear of the stepped surface.
            put('face_blush_'+label,'position',[-side*.15*swell,.18*swell,-1.55*swell])
            put('face_blush_'+label,'scale',[1+.2*swell,1+.2*swell,1])
            closed=curve([(0,0),(.45,0),(1.15,1),(4.5,1),(4.95,0),(5.45,0),(5.6,1),(5.8,0),(6.4,0)],t)
            alt=1 if 1.15<=t<=4.48 else 0
            put('face_eye_'+label,'scale',[1, max(.08,1-.92*closed),1])
            put('face_eye_'+label,'position',[0,0,.5*alt])
            put('puff_eye_'+label,'position',[0,.35*alt,-.4*alt])
            put('face_brow_'+label,'rotation',[0,0,-side*14*swell])
            put('face_brow_'+label,'position',[0,-.15*swell,0])
        pout=1 if .9<=t<=4.05 else 0
        put('face_mouth','position',[0,0,.6*pout])
        put('face_mouth','scale',[1-.3*inhale-.2*release,1+.6*inhale+.6*release,1])
        put('puff_mouth','position',[0,-.05*swell,-.44*pout])
        put('puff_mouth','scale',[1-.25*swell,1-.15*swell,1])
        put('root','scale',[1+.012*swell,1+.02*inhale-.012*release,1])
        put('features_lift','position',[tremble,.12*inhale-.12*release,0])
        put('hat_follow','rotation',[0,0,.7*math.sin(t*3)*release])
    return dict(duration=6.4,loop=True,tracks=tracks)
