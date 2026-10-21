"""Four actual injury types, three authored grades each; no new game states."""
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
    elif kind=='scorch':
        r=source.blob(x,y,.84,.82,28,.11)
        s.put((134,87,40),(1-smooth(.65,1.1,r))*(.30+.16*grade))
        if grade>=2:
            s.put((43,35,28),(1-smooth(.48,.94,r))*(.70 if grade==2 else .97))
            s.put((25,23,22),(1-smooth(.08,.64,r))*.67)
        for j in range([3,7,12][grade-1]):
            xx=-.65+j*1.3/max(1,[3,7,12][grade-1]-1)
            p=[(xx-.035,-.60),(xx+.035,-.22),(xx-.035,.20),(xx+.02,.57)]
            s.put((111,88,58),stroke(x,y,p,.008+.002*grade)*(1-smooth(.72,1,r))*(.4+.15*grade))
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
