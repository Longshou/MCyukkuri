"""Fit bean hair to the authored skin cross sections, in model units."""
import copy
import json
import math
import uuid
from pathlib import Path
from mesh_geometry import mesh


def profile(y, r):
    height=8.48*r['body_height'];q=max(0,min(1,y/height))
    radius=(.76+.24*math.sin(min(1,q/.23)*math.pi/2))*(1-.46*q)
    if q>.90:radius*=math.sqrt(max(.035,1-((q-.90)/.102)**2))
    front=4.6*r['depth']*(.78+.22*min(1,q/.12))
    back=4.075*r['depth']*(.76+.24*math.sin(math.pi*q))
    if q>.94:front*=max(.35,1-(q-.94)*9);back*=max(.35,1-(q-.94)*9)
    return 6*r['lower_width']*radius,back,front


def standard_braid(base, r, cfg, spec, rig):
    """Marisa's shared side hair (one braid, one loose lock) at bean head scale.

    Uses the same head transform as the other bean head parts, then moves each
    piece so its lock axis follows the bean side contour at that height. The braid
    keeps its bone chain (marisa-single-braid/author.py), each joint moved with the
    pieces at its height, so it bends like the standard braid; the loose lock rides the skin.
    """
    lib=Path(__file__).resolve().parents[2];s=r['head_scale'];out=[]
    def shift(y, sign, braid):
        x=profile(y-cfg['surface_lift'],r)[0]+cfg['clearance']
        return sign*(x+(spec['axis_offset'] if braid else spec['loose_offset']))-sign*(spec['braid_axis'] if braid else spec['loose_axis'])*s
    for side in ('left','right'):
        for src in json.loads((lib/spec['source']/f'hand-{side}.json').read_text(encoding='utf-8'))['elements']:
            e=copy.deepcopy(src);bone=e.pop('bone')
            for key in ('from','to','origin'):
                e[key]=[e[key][0]*s,e[key][1]*s+r['head_raise'],e[key][2]*r['depth']]
            braid=e['name'].startswith('braid_lock_')
            sign=1 if e['from'][0]+e['to'][0]>0 else -1
            dx=shift((e['from'][1]+e['to'][1])/2,sign,braid)
            for key in ('from','to','origin'):e[key][0]+=dx
            if not any(e.get('rotation',[0,0,0])):e['origin']=[0.0,0.0,0.0]
            e['uuid']=str(uuid.uuid5(uuid.NAMESPACE_URL,base+'/'+e['name']))
            out.append((e,bone if braid else 'body_stretch'))
    chain=[b for b in json.loads((lib/'rigs/marisa.json').read_text(encoding='utf-8'))['bones'] if b['name'].startswith(('hand_left_','hand_right_'))]
    bones=[b for b in rig['bones'] if not b['name'].startswith(('hand_left_','hand_right_'))]
    at=next(i for i,b in enumerate(bones) if b['name']=='hand_right')+1
    for b in chain:
        x,y,z=b['pivot'];y=y*s+r['head_raise'];sign=1 if x>0 else -1
        b['pivot']=[round(x*s+shift(y,sign,True),6),round(y,6),round(z*r['depth'],6)]
    bones[at:at]=chain;rig['bones']=bones
    return out


def author(pairs, rig, base, r):
    old={e['name']:e for e,g in pairs};cfg=r['hair_fit']
    uv=old['hair_cap']['faces']['north']['uv'];u,v,U,V=uv
    def surface(name, rows, texture_uv=None, closed=False):
        u,v,U,V=texture_uv or uv
        points=[p for row in rows for p in row];n=len(rows[0]);tri=[];tex=[]
        descending=sum(p[1] for p in rows[0])>sum(p[1] for p in rows[-1])
        for j in range(len(rows)-1):
            for i in range(n):
                a=j*n+i;b=j*n+(i+1)%n;c=b+n;d=a+n
                tri.extend([(a,d,c),(a,c,b)])
                tex.extend([[(u,V),(u,v),(U,v)],[(u,V),(U,v),(U,V)]])
        if descending:
            tri=[tuple(reversed(t)) for t in tri];tex=[list(reversed(t)) for t in tex]
        if closed:
            for row in [0,len(rows)-1]:
                center=len(points);points.append([sum(p[k] for p in rows[row])/n for k in range(3)])
                for i in range(n):
                    t=(center,row*n+i,row*n+(i+1)%n)
                    if (row!=0)!=descending:t=tuple(reversed(t))
                    tri.append(t);tex.append([(u+2,v+2)]*3)
        e=mesh(name,points,tri,tex);e['uuid']=str(uuid.uuid5(uuid.NAMESPACE_URL,base+'/'+name));return e
    def contour(y):
        x,b,f=profile(y-cfg['surface_lift'],r);c=cfg['clearance'];x+=c;b+=c;f+=c
        return [[x,y,-.7*f],[x,y,.7*b],[.7*x,y,b],[-.7*x,y,b],[-x,y,.7*b],[-x,y,-.7*f],[-.9*x,y,-f],[.9*x,y,-f]]
    # A continuous cap replaces the detached rectangular lid, side walls and rear strips.
    rows=[];n=32
    for j in range(19):
        row=[];t=j/18
        for i in range(n):
            k=i//4;s=(i%4)/4
            lows=[10.8,10.65,10.45,10.45,10.65,10.8,12.18,12.18]
            low=lows[k]*(1-s)+lows[(k+1)%8]*s
            if k in [1,2,3]:low-=.16*math.sin(i*math.pi/2)**2
            y=low+(cfg['top_y']-low)*t
            ring=contour(y);p=[ring[k][a]*(1-s)+ring[(k+1)%8][a]*s for a in range(3)]
            if j==18:p=[p[0]*.08,y,p[2]*.08]
            row.append(p)
        rows.append(row)
    cap=surface('bean_hair_cap',rows)
    # Close the tiny top ring under the bow/hat.
    top=rows[-1];points=list(cap['vertices'].values());offset=len(points);points.append([0,cfg['top_y'],0]);cap['vertices'][str(offset)]=points[-1]
    for i in range(n):
        a=(len(rows)-1)*n+i;b=(len(rows)-1)*n+(i+1)%n
        cap['faces']['top_'+str(i)]={'vertices':list(map(str,[a,offset,b])),'uv':{str(k):[u+3,v+3]for k in [a,offset,b]},'texture':0}
    new=[(cap,'body_stretch')]
    # Each fringe is one joined tapered piece; the old independent tip shortening left gaps.
    for i in range(7):
        src=old['bang_'+str(i)];tip=old['bang_tip_'+str(i)]
        left=src['from'][0]/3.12;right=src['to'][0]/3.12
        bottom=tip['from'][1];rows=[]
        for j in range(9):
            t=j/8;y=12.66+(bottom-12.66)*t;x,b,f=profile(y-cfg['surface_lift'],r)
            width=(1-.35*t*t)*.9*(x+cfg['clearance']);mid=(left+right)/2
            xl=mid*.9*(x+cfg['clearance'])+(left-mid)*width;xr=mid*.9*(x+cfg['clearance'])+(right-mid)*width
            z=-f-cfg['clearance']-.045
            rows.append([[xl,y,z],[xr,y,z],[xr,y,z+.16],[xl,y,z+.16]])
        new.append((surface('bean_bang_'+str(i),rows,closed=True),'body_stretch'))
    # Marisa shares the standard single braid; Reimu keeps the twisted pair with cuffs.
    spec=cfg.get('marisa_braid') if base=='marisa' else None
    if spec:new.extend(standard_braid(base,r,cfg,spec,rig))
    for sign in ([] if spec else [-1,1]):
        # Two closed intertwined strands expose a real round silhouette from the side.
        for strand in [0,1]:
            rows=[]
            for j in range(33):
                t=j/32;y=cfg['braid_root_y']+(cfg['braid_tip_y']-cfg['braid_root_y'])*t;x,b,f=profile(y,r)
                phase=t*math.pi*3+strand*math.pi
                radius=.31*(1-.25*t)*max(.12,min(1,(1-t)/.16))
                spread=.23*math.sin(min(1,t/.15)*math.pi/2)*max(.2,min(1,(1-t)/.12))
                cx=sign*(x+.16+.42*min(1,t/.35))+math.cos(phase)*spread
                z=cfg['braid_z']+math.sin(phase)*spread
                rows.append([[cx+math.cos(k*math.tau/10)*radius,y,z+math.sin(k*math.tau/10)*radius*1.5]for k in range(10)])
            new.append((surface(f'bean_braid_{sign}_{strand}',rows,closed=True),'body_stretch'))
        # The cuffs wrap both strands; old flat ribbon cuboids are removed below.
        for label,y0,y1,material,extra in ([('tie',9.27,9.94,[64,48,80,64],0),('rim_top',9.91,10.05,[112,48,128,64],.035),('rim_low',9.18,9.32,[112,48,128,64],.035)] if base=='reimu' else [('tie',9.58,9.82,[112,48,128,64],0)]):
            rows=[]
            for j in range(5):
                y=y0+(y1-y0)*j/4;x,b,f=profile(y,r);cx=sign*(x+.58)
                rows.append([[cx+math.cos(k*math.tau/12)*(.49+extra),y,cfg['braid_z']+math.sin(k*math.tau/12)*(.66+extra)]for k in range(12)])
            new.append((surface(f'bean_braid_{label}_{sign}',rows,material,closed=True),'body_stretch'))
    remove=lambda n: n in ('crown','hair_cap','back_hair','back_hair_lower','centre_bang_tip') or n.startswith(('bang_','back_lock_','rear_side_hair','side_hair','tied_lock','lock_tip','side_lock','side_tip','cuff','side_ribbon'))
    result=[]
    def accessory(p, group):
        if base=='reimu' and group.startswith('bow'):p[2]-=cfg['reimu_bow_back_shift']
        if base=='marisa' and group in ('hat','bow'):
            p[2]=(p[2]-1)*cfg['marisa_hat_depth']-.35
            p[1]-=cfg['marisa_hat_seat']
    for e,g in pairs:
        if remove(e['name']):continue
        for key in ('from','to','origin'):
            if key in e:accessory(e[key],g)
        if e['name'].startswith(('cuff','side_ribbon')):g='body_stretch'
        result.append((e,g))
    for b in rig['bones']:accessory(b['pivot'],b['name'])
    return result+new
