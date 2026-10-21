"""Source-backed mechanical injury decals and continuous thermal material; no new game states."""
import io
import numpy as np
from PIL import Image
import preview as source
from export_wounds import Tile


class InjuryTile(Tile):
    def __init__(self, size):
        grid=(np.arange(size,dtype=np.float32)+.5)/size
        self.x,self.y=np.meshgrid(grid*2-1,1-grid*2)
        self.u,self.v=self.x,self.y
        self.color=np.zeros((*self.x.shape,3),dtype=np.float32)
        self.alpha=np.zeros(self.x.shape,dtype=np.float32)


def material(kind, grade=1, size=512):
    s=InjuryTile(size)
    x,y=s.x,s.y
    stroke,smooth=source.stroke,source.smooth
    rng=np.random.default_rng(28)
    if kind=='scratch':
        for j in range([3,5,8][grade-1]):
            cx=-.55+j*1.1/([3,5,8][grade-1]-1)
            length=.40+.40*rng.random()
            points=[(cx-.11,-length*.5),(cx,.02),(cx+.09,length*.5)]
            broken=.45+.55*(np.sin(y*(23+j*2)+j)>.05)
            s.put((71,47,33),stroke(x,y,points,.018+.009*grade)*broken*.80)
        if grade==3:
            for j in range(45):
                px,py=rng.uniform(-.65,.65),rng.uniform(-.38,.38)
                r=rng.uniform(.009,.025)
                s.put((206,173,128),(1-smooth(r*.5,r,np.hypot(x-px,y-py)))*.75)
    elif kind=='tear':
        points=[(-.88,-.04),(-.47,.04),(-.13,-.04),(.22,.045),(.58,.01),(.88,.1)]
        if grade==1:
            s.put((38,28,24),stroke(x,y,points,.023)*.95)
        else:
            for sign in [-1,1]:
                s.put((56,35,28),stroke(x*sign,y,[(.62,.05),(.82,.10),(.96,.035)],.015)*.75)
                if grade==3:s.put((132,99,66),stroke(x*sign,y,[(.65,.14),(.78,.37),(.85,.44)],.014)*.5)
    elif kind=='dent':
        r=np.sqrt(x*x+(y/.85)**2)
        s.put((39,30,27),(1-smooth(.18,.92,r))*(.18+.10*grade))
        for j in range(3+grade*2):
            angle=j*2*np.pi/(3+grade*2)+.35
            p=[(.34*np.cos(angle),.34*np.sin(angle)),(.60*np.cos(angle+.06),.60*np.sin(angle+.06)),(.83*np.cos(angle),.83*np.sin(angle))]
            s.put((48,33,27),stroke(x,y,p,.012+.004*grade)*.5)
    elif kind=='thorn':
        # Contact field, not a manufactured ring or one large knife wound.
        points=[(-.56,.22),(-.12,.45),(.37,.31),(-.34,-.25),(.20,-.28),(.63,-.04),(-.66,-.47),(.05,.02),(.46,-.57)]
        for px,py in points[:[3,6,9][grade-1]]:
            s.put((63,40,27),stroke(x,y,[(px-.025,py-.04),(px+.025,py+.04)],.018+.004*grade)*.9)
    elif kind=='impale':
        length=[.3,.57,.82][grade-1]
        s.put((55,35,25),stroke(x,y,[(0,-length),(-.04,0),(.06,length)],.022+.008*grade)*.9)
        for sign in [-1,1]:
            s.put((72,45,30),stroke(x,y,[(0,-.2),(.16*sign,-.45),(.23*sign,-.64)],.012)*.65)
    elif kind=='puncture':
        r=np.sqrt((x/.36)**2+(y/.48)**2)
        if grade==1:
            s.put((54,34,24),(1-smooth(.10,.42,r))*.95)
        for sign in [-1,1]:
            s.put((71,48,32),stroke(x,y,[(.12*sign,.25*sign),(.3*sign,.55*sign),(.34*sign,.75*sign)],.018)*.70)
    elif kind=='bite':
        # Opposed bite arcs, not repeated slashes or a projectile hole.
        for sign in [-1,1]:
            for j in range(5):
                xx=-.65+j*.325;yy=sign*(.20+.26*(1-(xx/.8)**2))
                s.put((66,42,27),stroke(x,y,[(xx-.045,yy-sign*.065),(xx,yy),(xx+.045,yy-sign*.065)],.025)*.86)
    elif kind=='blast':
        r=np.hypot(x,y)
        s.put((73,48,30),(1-smooth(.25,.95,r))*.24)
        for j in range(9):
            angle=j*2*np.pi/9+.2;length=.62+.30*rng.random()
            points=[(.18*np.cos(angle),.18*np.sin(angle)),(.45*np.cos(angle+.1),.45*np.sin(angle+.1)),(length*np.cos(angle),length*np.sin(angle))]
            s.put((58,36,25),stroke(x,y,points,.018+.004*grade)*.78)
    elif kind=='thermal':
        # Wiki EH16-008: soot stipple over continuous dough, not polygonal plates.
        # Periodic low-contrast mottling and fine char grains keep the surface whole.
        cloud=np.zeros(x.shape,dtype=np.float32)
        for frequency,weight in [(1,.38),(2,.24),(4,.13),(7,.08)]:
            phase=rng.uniform(0,2*np.pi,2)
            cloud+=weight*np.sin(np.pi*(frequency*x+(frequency+1)*y)+phase[0])*np.cos(np.pi*((frequency+1)*x-frequency*y)+phase[1])
        cloud=np.clip(.5+cloud*.6,0,1)
        # Discrete, short soot flecks; no connected crack graph or bright cell rims.
        grains=rng.random((size//2,size//2)).repeat(2,0).repeat(2,1)[:size,:size]
        soot=smooth([.83,.66,.48][grade-1],.98,grains)
        base=np.array([(129,83,39),(73,48,28),(36,29,23)][grade-1])
        shade=.92+.16*cloud-soot*[.28,.30,.23][grade-1]
        color=base[None,None,:]*shade[...,None]
        alpha=np.clip([.22,.58,.88][grade-1]+cloud*.12+soot*[.30,.24,.10][grade-1],0,1)
        s.put(color,alpha)
    elif kind=='scar':
        p=[(-.82,-.09),(-.43,-.035),(-.1,.04),(.25,.02),(.78,.14)]
        s.put((46,34,28),stroke(x,y+.014,p,.038)*.62)
        s.put((219,190,145),stroke(x,y,p,.022)*.82)
    else:raise ValueError(kind)
    color=s.color/np.maximum(s.alpha[...,None],1e-8)
    rgba=np.dstack((np.clip(color,0,255),s.alpha*255)).round().astype(np.uint8)
    rgba[rgba[...,3]==0,:3]=0
    out=io.BytesIO();Image.fromarray(rgba).save(out,format='PNG')
    return out.getvalue()
