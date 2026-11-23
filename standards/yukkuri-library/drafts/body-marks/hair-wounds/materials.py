"""Pixel-authored mechanical injury decals and continuous thermal material."""
import io
import math
import numpy as np
from PIL import Image, ImageDraw
import preview as source
from export_wounds import Tile


class InjuryTile(Tile):
    def __init__(self, size):
        grid=(np.arange(size,dtype=np.float32)+.5)/size
        self.x,self.y=np.meshgrid(grid*2-1,1-grid*2)
        self.u,self.v=self.x,self.y
        self.color=np.zeros((*self.x.shape,3),dtype=np.float32)
        self.alpha=np.zeros(self.x.shape,dtype=np.float32)


# A small, shared dough / bean-paste palette. The hard pixel edges remain crisp
# when Minecraft magnifies the decal; no skin-coloured backing is painted.
GRID=64
DEEP=(70,45,34,255)
INK=(88,54,40,255)
MID=(126,83,57,255)
EDGE=(203,158,103,255)
DOUGH=(214,177,126,255)
LIGHT=(235,205,151,255)
BRUISE=(105,79,60,72)
SCAR_SHADOW=(147,113,83,150)
SCAR_LIGHT=(232,207,158,235)


def png(image):
    out=io.BytesIO();image.save(out,format='PNG');return out.getvalue()


def canvas():
    image=Image.new('RGBA',(GRID,GRID),(0,0,0,0))
    return image,ImageDraw.Draw(image,'RGBA')


def point(x,y):
    return (round((x+1)*.5*(GRID-1)),round((1-y)*.5*(GRID-1)))


def line(draw,points,colour,width=1,offset=(0,0)):
    draw.line([(point(x,y)[0]+offset[0],point(x,y)[1]+offset[1]) for x,y in points],
              fill=colour,width=width,joint='curve')


def polygon(draw,points,colour):
    draw.polygon([point(x,y) for x,y in points],fill=colour)


def chip(draw,x,y,colour=DOUGH,size=1):
    px,py=point(x,y);half=size//2
    draw.rectangle((px-half,py-half,px-half+size-1,py-half+size-1),fill=colour)


def lip(draw,points,width=1):
    # One warm broken dough edge and one narrow dark incision, never a thick ring.
    line(draw,points,EDGE,width+2,offset=(0,1))
    line(draw,points,DEEP,width)


def mechanical(kind,grade,size):
    image,d=canvas()
    if kind=='scratch':
        # Uneven bundles share one friction direction; they are not short hairs.
        bundles=[(-.42,.20,.46),(-.10,.06,.58),(.23,-.12,.50),(-.31,-.17,.42),(.02,.29,.40),(.39,.11,.36)]
        for j,(cx,cy,length) in enumerate(bundles[:[2,4,6][grade-1]]):
            start=(cx-length*.52,cy+length*.20)
            middle=(cx+.03,cy-.015)
            end=(cx+length*.48,cy-length*.20)
            line(d,[start,(cx-.10,cy+.045)],DOUGH if grade==1 else EDGE,2)
            line(d,[start,(cx-.10,cy+.045)],DEEP,1)
            line(d,[middle,end],DOUGH if grade==1 else EDGE,2)
            line(d,[middle,end],DEEP,1)
        if grade==3:
            for x,y in [(-.47,.08),(-.26,.25),(-.15,-.04),(.02,.14),(.17,-.20),(.31,.02),(.42,-.09)]:
                line(d,[(x-.025,y+.01),(x+.035,y-.01)],DOUGH,1)
    elif kind=='tear':
        if grade==1:
            cut=[(-.80,.01),(-.61,.01),(-.61,.07),(-.38,.07),(-.38,-.02),(-.15,-.02),(-.15,.04),(.10,.04),(.10,-.04),(.37,-.04),(.37,.03),(.59,.03),(.59,.09),(.80,.09)]
            lip(d,cut)
            line(d,[(-.18,.03),(-.26,.19),(-.22,.27)],MID,1)
            line(d,[(.36,-.03),(.43,-.17)],EDGE,1)
        else:
            for sign in (-1,1):
                lip(d,[(sign*.90,.02),(sign*.80,.10),(sign*.70,.06)])
                line(d,[(sign*.79,-.09),(sign*.67,-.22),(sign*.55,-.25)],MID,1)
            if grade==3:
                for x,y in [(-.64,.36),(-.28,.48),(.18,.42),(.57,.31),(-.46,-.38),(.34,-.43)]:chip(d,x,y,DOUGH,1)
    elif kind=='dent':
        # Hard-edged, discontinuous pressure marks support the actual shallow
        # displacement. There is no translucent grey disc over the skin.
        patches=[(-.30,.16,.16),(.02,.25,.18),(.30,.09,.13),(-.12,-.17,.17),(.23,-.22,.12),(-.43,-.05,.11),(.02,.01,.15)]
        for j,(cx,cy,r) in enumerate(patches[:2+grade*2]):
            polygon(d,[(cx-r,cy+.025),(cx-r*.35,cy+r*.55),(cx+r*.55,cy+r*.32),(cx+r,cy-.02),(cx+r*.20,cy-r*.50),(cx-r*.60,cy-r*.32)],
                    (126,91,63,95+grade*25))
        creases=[
            [(-.22,.08),(-.36,.20),(-.48,.22)],
            [(.12,.15),(.29,.25),(.41,.23)],
            [(-.08,-.12),(-.20,-.27),(-.23,-.37)],
            [(.18,-.09),(.34,-.18)],
        ]
        for stroke in creases[:grade+1]:
            line(d,stroke,EDGE,2);line(d,stroke,DEEP,1)
        if grade>=2:
            for x,y in [(-.23,.29),(.29,.19),(-.33,-.21)]:chip(d,x,y,(190,145,96,190),1)
        if grade==3:
            lip(d,[(-.28,.06),(-.15,.01),(-.03,.07),(.09,-.04),(.25,.01)])
            for x,y in [(-.50,.20),(.43,-.16),(.08,.43)]:chip(d,x,y,DOUGH,1)
    elif kind=='puncture':
        if grade==1:
            polygon(d,[(0,.31),(.14,.13),(.11,-.15),(0,-.31),(-.12,-.14),(-.15,.12)],DEEP)
            polygon(d,[(0,.19),(.07,.07),(.05,-.14),(0,-.22),(-.05,-.10),(-.07,.08)],INK)
            line(d,[(-.11,.14),(-.03,.25),(.03,.24)],DOUGH,2)
        else:
            line(d,[(-.05,.48),(-.10,.65),(-.06,.79)],MID,1)
            line(d,[(.06,-.43),(.14,-.60),(.12,-.76)],MID,1)
            line(d,[(-.20,.14),(-.34,.22)],EDGE,1)
            if grade==3:
                chip(d,.19,-.19,DOUGH,1);chip(d,-.17,.30,EDGE,1)
    elif kind=='thorn':
        contacts=[(-.48,.18),(-.16,.37),(.26,.22),(-.30,-.22),(.12,-.31),(.51,-.08),(-.61,-.39),(.02,.03),(.37,-.52)]
        directions=[(1,1),(-1,1),(1,-1),(-1,-1),(1,.6),(-1,.8),(.7,-1),(-1,-.6),(1,-1)]
        for j,((x,y),(sx,sy)) in enumerate(zip(contacts[:[3,6,9][grade-1]],directions)):
            length=.065+.012*(j%3)
            stroke=[(x-.022*sx,y-length*sy),(x+.022*sx,y+length*sy)]
            line(d,stroke,DOUGH if grade==1 else EDGE,2);line(d,stroke,DEEP,1)
            if j%2==0:chip(d,x+.035,y-.018,MID,1)
    elif kind=='impale':
        if grade==1:
            lip(d,[(0,.43),(-.05,.20),(.03,-.03),(-.03,-.25),(0,-.43)])
            line(d,[(.01,.08),(.17,.22),(.23,.35)],MID,1)
        else:
            line(d,[(-.17,.27),(-.33,.46),(-.29,.68)],EDGE,2)
            line(d,[(-.17,.27),(-.33,.46),(-.29,.68)],DEEP,1)
            line(d,[(.10,-.43),(.22,-.61),(.18,-.79)],MID,1)
            if grade==3:
                line(d,[(.26,.06),(.42,.20),(.47,.34)],EDGE,1)
                for x,y in [(-.38,.16),(.34,-.12),(.19,.61)]:chip(d,x,y,DOUGH,1)
    elif kind=='bite':
        # Offset jaws and separate blunt/canine contacts avoid a decorative ring.
        xs=[-.68,-.31,.03,.32,.61]
        for sign in (-1,1):
            for j,x in enumerate(xs):
                xx=x+(.035 if sign>0 else -.025)*(1 if j%2 else -1)
                y=sign*(.23+.19*(1-(x/.8)**2)+.025*((j%3)-1))
                length=.21 if j in (0,4) else .10+.025*(j%2)
                skew=.025 if (j+sign)%2 else -.02
                tooth=[(xx,y),(xx+skew,y-sign*length)]
                line(d,tooth,EDGE,3 if j in (0,4) else 2)
                line(d,tooth,DEEP,1)
                if j in (1,2,3):chip(d,xx+skew,y-sign*length,INK,1)
                if grade>=2 and j in (0,4):
                    line(d,[(xx,y+sign*.03),(xx+(.04 if j==0 else -.04),y+sign*.19)],MID,1)
    elif kind=='blast':
        if grade==1:
            polygon(d,[(-.18,.06),(-.04,.20),(.15,.13),(.22,-.05),(.03,-.18),(-.15,-.12)],(112,75,52,125))
            chip(d,-.23,.20,DOUGH,1);chip(d,.19,-.19,MID,1)
        angles=[.18,.91,1.63,2.38,3.12,3.92,4.70,5.51]
        for j,a in enumerate(angles[:[5,6,8][grade-1]]):
            start=.26 if grade==1 else .70
            end=[.62,.78,.88][grade-1]*(.88+.04*(j%3))
            bend=.07*(-1 if j%2 else 1)
            stroke=[(math.cos(a)*start,math.sin(a)*start),
                    (math.cos(a+bend)*(start+end)*.52,math.sin(a+bend)*(start+end)*.52),
                    (math.cos(a)*end,math.sin(a)*end)]
            line(d,stroke,EDGE if j%3==0 else MID,2 if j%3==0 else 1)
            if j%2==0:line(d,stroke,DEEP,1)
        if grade==3:
            for x,y in [(-.69,.37),(-.33,.72),(.29,.66),(.73,.19),(.52,-.55),(-.46,-.60)]:chip(d,x,y,DOUGH,1)
    elif kind=='scar':
        healed=[(-.80,-.06),(-.58,-.06),(-.58,-.02),(-.34,-.02),(-.34,.04),(-.08,.04),(-.08,.01),(.18,.01),(.18,.07),(.43,.07),(.43,.12),(.77,.12)]
        line(d,healed,SCAR_SHADOW,2,offset=(0,1))
        line(d,healed,SCAR_LIGHT,1)
        line(d,[(-.30,.03),(-.23,.15)],SCAR_LIGHT,1)
        line(d,[(.20,.06),(.27,-.07)],SCAR_SHADOW,1)
    else:
        raise ValueError(kind)
    if size!=GRID:image=image.resize((size,size),Image.Resampling.NEAREST)
    return png(image)


def thermal(grade,size):
    s=InjuryTile(size);x,y=s.x,s.y
    smooth=source.smooth;rng=np.random.default_rng(28)
    # Wiki EH16-008: clustered black soot with ragged stippled transitions.
    # Broad heat-darkened material and small char pores, not uniform dirt
    # noise, outlined plates, or a network of cracks.
    cloud=np.zeros(x.shape,dtype=np.float32)
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
    char=smooth([.66,.37,.23][grade-1],[.87,.67,.55][grade-1],cloud+(grains-.5)*.09)
    tone=cloud*13+grains*9
    charcoal=np.stack((30+tone,27+tone*.88,24+tone*.72),axis=-1)
    s.put(charcoal,char*[.40,.94,.98][grade-1])
    pores=smooth(.90,.99,grains)*smooth(.28,.58,cloud)
    s.put((12,12,12),pores*[.30,.47,.52][grade-1])
    ash=smooth(.965,.998,grains)*char
    s.put((115,105,88),ash*[.06,.14,.22][grade-1])
    color=s.color/np.maximum(s.alpha[...,None],1e-8)
    rgba=np.dstack((np.clip(color,0,255),s.alpha*255)).round().astype(np.uint8)
    rgba[rgba[...,3]==0,:3]=0
    return png(Image.fromarray(rgba))


def material(kind,grade=1,size=512):
    if kind=='thermal':return thermal(grade,size)
    return mechanical(kind,grade,size)
