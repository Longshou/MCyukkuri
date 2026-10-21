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
            s.put((227,195,148),stroke(x-.023,y+.01,points,.011+.004*grade)*broken*.85)
        if grade==3:
            for j in range(45):
                px,py=rng.uniform(-.65,.65),rng.uniform(-.38,.38)
                r=rng.uniform(.009,.025)
                s.put((206,173,128),(1-smooth(r*.5,r,np.hypot(x-px,y-py)))*.75)
    elif kind=='tear':
        points=[(-.88,-.04),(-.47,.04),(-.13,-.04),(.22,.045),(.58,.01),(.88,.1)]
        if grade==1:
            s.put((38,28,24),stroke(x,y,points,.023)*.95)
            s.put((214,180,135),stroke(x,y-.017,points,.011)*.72)
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
            s.put((185,151,104),stroke(x-.014,y+.012,p,.007)*.35)
    elif kind=='puncture':
        r=np.sqrt((x/.36)**2+(y/.48)**2)
        if grade==1:
            s.put((54,34,24),(1-smooth(.10,.42,r))*.95)
            s.put((212,174,125),smooth(.35,.45,r)*(1-smooth(.55,.65,r))*.70)
        for sign in [-1,1]:
            s.put((71,48,32),stroke(x,y,[(.12*sign,.25*sign),(.3*sign,.55*sign),(.34*sign,.75*sign)],.018)*.70)
    elif kind=='bite':
        # Opposed bite arcs, not repeated slashes or a projectile hole.
        for sign in [-1,1]:
            for j in range(5):
                xx=-.65+j*.325;yy=sign*(.20+.26*(1-(xx/.8)**2))
                s.put((66,42,27),stroke(x,y,[(xx-.045,yy-sign*.065),(xx,yy),(xx+.045,yy-sign*.065)],.025)*.86)
                s.put((218,179,131),stroke(x-.015,y+.01,[(xx-.04,yy),(xx+.04,yy)],.009)*.65)
    elif kind=='blast':
        r=np.hypot(x,y)
        s.put((73,48,30),(1-smooth(.25,.95,r))*.24)
        for j in range(9):
            angle=j*2*np.pi/9+.2;length=.62+.30*rng.random()
            points=[(.18*np.cos(angle),.18*np.sin(angle)),(.45*np.cos(angle+.1),.45*np.sin(angle+.1)),(length*np.cos(angle),length*np.sin(angle))]
            s.put((58,36,25),stroke(x,y,points,.018+.004*grade)*.78)
            s.put((207,169,121),stroke(x-.018,y+.012,points,.008)*.60)
    elif kind=='thermal':
        # Periodic, irregular char plates: low sheen, pitted interior and broken seams.
        # Distances wrap at the tile edges so the full-body material has no stripe seam.
        seeds=rng.uniform(-1,1,(34,2))
        distances=[]
        for sx,sy in seeds:
            dx=np.abs(x-sx);dy=np.abs(y-sy)
            dx=np.minimum(dx,2-dx);dy=np.minimum(dy,2-dy)
            distances.append(dx*dx+dy*dy)
        nearest=np.partition(np.stack(distances),1,axis=0)
        edge=np.sqrt(nearest[1])-np.sqrt(nearest[0])
        crack=1-smooth(.006,.017+.006*grade,edge)
        rim=(1-smooth(.025,.055,edge))*(1-crack)
        grain=rng.random(x.shape)
        pit=(grain>.94)*rng.uniform(.08,.25,x.shape)
        base=np.array([(151,98,46),(65,46,28),(31,25,19)][grade-1])
        shade=(.90+.20*grain-pit)*(1-crack*[.12,.42,.60][grade-1])+rim*.12
        color=base[None,None,:]*shade[...,None]
        # Alpha has its own crack modulation: dry seams remain visible on dark hair.
        alpha=np.clip([.42,.76,.96][grade-1]+crack*.12,0,1)
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
