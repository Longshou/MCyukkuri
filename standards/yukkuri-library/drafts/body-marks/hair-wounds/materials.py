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
            # Exposed strand ends have a muted warm edge, readable even on black
            # hair. This narrow injury stroke has no surrounding skin backing.
            s.put((148,98,56),stroke(x,y,points,.14)*.95)
            s.put((42,28,21),stroke(x,y,points,.052)*.98)
            for xx,yy in [(-.46,.04),(.23,.045),(.58,.01)]:
                s.put((130,83,44),stroke(x,y,[(xx,yy),(xx+.055,yy+.15)],.038)*.95)
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
        # Separate tooth contacts arranged along opposing jaws. The jaw curve is
        # placement only: no line exists between teeth on the damaged surface.
        for sign in [-1,1]:
            for j in range(5):
                xx=-.65+j*.325;yy=sign*(.20+.26*(1-(xx/.8)**2))
                # Canine contacts are longer; central teeth leave blunt dents.
                length=.18 if j in (0,4) else .10
                points=[(xx-.035,yy-sign*length),(xx,yy),(xx+.035,yy-sign*length)]
                if grade==1:s.put((148,98,56),stroke(x,y,points,.070)*.95)
                s.put((55,33,22),stroke(x,y,points,.041 if grade==1 else .025)*.96)
            if grade>=2:
                # Canines penetrate beyond the main crushed opening. These
                # isolated contacts remain visible when the centre is missing.
                for side in [-1,1]:
                    xx=side*(.63 if sign>0 else .60)
                    points=[(xx-side*.09,sign*.26),(xx,sign*.59),(xx+side*.045,sign*(.91 if side<0 else .82))]
                    s.put((146,93,48),stroke(x,y,points,.065)*.9)
                    s.put((48,28,21),stroke(x,y,points,.033)*.98)
    elif kind=='blast':
        r=np.hypot(x,y)
        s.put((73,48,30),(1-smooth(.25,.95,r))*.24)
        for j in range(9):
            angle=j*2*np.pi/9+.2;length=.62+.30*rng.random()
            points=[(.18*np.cos(angle),.18*np.sin(angle)),(.45*np.cos(angle+.1),.45*np.sin(angle+.1)),(length*np.cos(angle),length*np.sin(angle))]
            s.put((58,36,25),stroke(x,y,points,.018+.004*grade)*.78)
    elif kind=='thermal':
        # Wiki EH16-008: clustered black soot with ragged stippled transitions.
        # Broad heat-darkened material and small char pores, not uniform dirt
        # noise, outlined plates, or a network of cracks.
        cloud=np.zeros(x.shape,dtype=np.float32)
        # Independent random values with smooth interpolation at each scale;
        # no sinusoidal interference bands or repeated cross-hatched cells.
        for cells,weight in [(5,.48),(11,.28),(23,.16),(47,.08)]:
            grid=rng.random((cells,cells))
            pos=(np.arange(size)+.5)*cells/size
            indices=np.floor(pos).astype(int);t=pos-indices;t=t*t*(3-2*t)
            lo=indices%cells;hi=(indices+1)%cells
            field=(grid[lo[:,None],lo[None,:]]*(1-t[None,:])+grid[lo[:,None],hi[None,:]]*t[None,:])*(1-t[:,None])
            field+=(grid[hi[:,None],lo[None,:]]*(1-t[None,:])+grid[hi[:,None],hi[None,:]]*t[None,:])*t[:,None]
            cloud+=weight*field
        cloud=np.clip(.5+(cloud-.5)*1.6,0,1)
        grains=rng.random((size//2,size//2)).repeat(2,0).repeat(2,1)[:size,:size]
        heat=smooth(.20,.78,cloud)
        s.put([(155,83,32),(114,58,28),(67,44,31)][grade-1],
              [.18,.56,.84][grade-1]+heat*[.22,.20,.12][grade-1])
        # Strongly clustered char coverage grows with severity, leaving browned
        # transitions. Texture remains continuous and matte across the body.
        char=smooth([.66,.37,.23][grade-1],[.87,.67,.55][grade-1],cloud+(grains-.5)*.09)
        # Warm charcoal has visible low-contrast surface grain even in the
        # deepest areas. It never resolves into featureless black cutouts.
        tone=cloud*13+grains*9
        charcoal=np.stack((30+tone,27+tone*.88,24+tone*.72),axis=-1)
        s.put(charcoal,char*[.40,.94,.98][grade-1])
        pores=smooth(.90,.99,grains)*smooth(.28,.58,cloud)
        s.put((12,12,12),pores*[.30,.47,.52][grade-1])
        ash=smooth(.965,.998,grains)*char
        s.put((115,105,88),ash*[.06,.14,.22][grade-1])
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
