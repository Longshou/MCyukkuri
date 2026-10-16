"""Current runtime hat, Marisa meshes and poses, rendered offline with NumPy/Pillow only.

Rocking/capsize curves, shared pivot, ledge packing and release instant mirror the Java
implementation. Post-release water drag/escape paths are illustrative, not engine replay.
No browser, game, Java compilation or asset export is invoked.
"""
from pathlib import Path
import json, math, shutil, runpy, sys
import numpy as np
from PIL import Image, ImageDraw
import preview as P

OUT=P.ROOT/'.local/previews/hat-motion'
W,H=1000,660
BG=(236,245,246)


class Atlas:
    def __init__(self):
        self.image=Image.new('RGBA',(1024,1024));self.slots={}
    def uv(self,key,image,uv):
        if key not in self.slots:
            i=len(self.slots);self.slots[key]=i
            self.image.paste(image.convert('RGBA').resize((256,256),Image.Resampling.NEAREST),((i%4)*256,(i//4)*256))
        i=self.slots[key]
        return uv*.25+np.array([(i%4)*.25,(i//4)*.25])


def rotate_mesh(mesh,rotation,offset=(0,0,0)):
    out=mesh.copy();out[:,:3]=out[:,:3]@rotation.T+offset;out[:,5:]=out[:,5:]@rotation.T
    return out


def motion(kind,age,side=-1,amplitude=12):
    if kind=='rock':
        if age<0 or age>=44:return np.eye(3)
        envelope=amplitude*math.exp(-age/14)
        roll=side*envelope*math.sin(age*.62);pitch=.24*envelope*math.sin(age*.44)
    else:
        u=max(0,min(1,age/16));roll=side*110*u*u*(3-2*u);pitch=7*math.sin(math.pi*u)
    return P.euler([0,0,roll])@P.euler([pitch,0,0])


class Scene:
    def __init__(self):
        data=json.loads((P.ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
        self.vessel=V=json.loads((P.ASSETS/'yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))
        self.k=V['aquatic_scale'];self.floor=V['bowl']['freeboard']-V['floor']*V['squash']['bowl']
        self.ledge=V['bowl']['freeboard']-V['ledge']*V['squash']['bowl']
        self.parent=P.Posed('marisa',data,True)
        self.young=P.Posed('marisa',data,worn_hat_scale=self.k)
        self.sizes=[1,.20,.16,.14]
        self.seats=[np.array([0,V['bowl']['owner_y'],max(0,V['opening'][3]-5.9/self.k)])]
        self.cargo=[]
        for i in range(8):
            left=i%2==0;inner=V['well'][0 if left else 1];outer=V['opening'][0 if left else 1]
            radius=min(.3,abs(outer-inner)*.30)
            at=np.array([outer+(1 if left else -1)*radius*1.1,self.ledge,.5-(i//2)*(abs(V['well'][2])-.8)/3])
            self.cargo.append((at,radius,('oil_grain_regular','food_scrap','food_mash_serving')[i%3]))
        self.body_height=json.loads((P.ASSETS/'yukkuri_presentation/body_geometry.json').read_text(encoding='utf-8'))['variants']['marisa']['height']
        self.seats.extend([np.zeros(3) for _ in self.sizes[1:]])
        self.props={}
        for name in ('marisa_hat_turned','oil_grain_regular','food_scrap','food_mash_serving'):
            self.props[name]=json.loads((P.ASSETS/'yukkuri_props'/f'{name}.json').read_text(encoding='utf-8'))
        self.pose_cache={}
        self.delivery=runpy.run_path(str(P.ROOT/'standards/yukkuri-library/interactions/marisa-hat-vessel/delivery.py'))
        brim_taken=[[*self.seats[0],3.584,6.4,1]]+[[*p,r,2*r,2] for p,r,_ in self.cargo]
        for i in (2,3):
            seat=self.refuge_place(3.584*self.sizes[i],brim_taken,(-2.4 if i==2 else 2.4,-6.9))
            if seat is None:raise RuntimeError('No fully supported real brim berth for this newborn.')
            self.seats[i]=np.array(seat);brim_taken.append(self.reservation(seat,self.sizes[i]))
        self.seats[1]=self.place(3.584*.20,brim_taken,self.body_space(.20))

    def body_space(self,size):
        V=self.vessel
        return (self.body_height*size/self.k,V['brim'][0]*size,V['brim'][1]*size,V['anchor_worn'][1]*size/self.k,V['keel']*size)

    def reservation(self,seat,size):
        height,hx,hz,base,hat_height=self.body_space(size)
        return [*seat,3.584*size,height,0,hx,hz,base,hat_height]

    @staticmethod
    def accessory_overlap(x,y,z,r,body,other):
        height,hx,hz,base,hat_height=body;oh=other[4] if len(other)>4 else other[3]*2
        rect=lambda dx,dz,ax,az:math.hypot(max(0,abs(dx)-ax),max(0,abs(dz)-az))
        if hx>0 and y+base<other[1]+oh and y+base+hat_height>other[1] and rect(other[0]-x,other[2]-z,hx,hz)<other[3]+.04:return True
        if len(other)<10 or other[6]<=0:return False
        ob=other[1]+other[8];ot=ob+other[9]
        if y<ot and y+height>ob and rect(x-other[0],z-other[2],other[6],other[7])<r+.04:return True
        return hx>0 and y+base<ot and y+base+hat_height>ob and abs(x-other[0])<hx+other[6]+.04 and abs(z-other[2])<hz+other[7]+.04

    def refuge_place(self,r,taken,prefer):
        V=self.vessel;best=None;x=V['opening'][0]+r
        while x<=V['opening'][1]-r+1e-8:
            z=V['opening'][2]-r-.22
            while z>=max(-V['brim'][1]+r+.22,V['opening'][2]-3.2)-1e-8:
                supported=abs(self.surface(x,z)-V['bowl']['freeboard'])<1e-4 and all(abs(self.surface(x+(r+.18)*math.cos(a*math.pi/8),z+(r+.18)*math.sin(a*math.pi/8))-V['bowl']['freeboard'])<1e-4 for a in range(16))
                clear=all(math.hypot(x-s[0],z-s[2])>=r+s[3]+.03 and not(s[1]>V['bowl']['freeboard']-.1 and abs(x-s[0])<(r+s[3])*2.8 and abs(z-s[2])<(r+s[3])*3.4) for s in taken)
                cost=math.hypot(x-prefer[0],z-prefer[1])
                if supported and clear and (best is None or cost<best[0]):best=(cost,x,V['bowl']['freeboard'],z)
                z-=.25
            x+=.25
        return None if best is None else best[1:]

    @staticmethod
    def distress(age,low=False):
        if age<12:return 'water_plunge',age/20
        if low and age>=44:return 'water_gasp',(age-44)/20%1.6
        at=(age-12)%32
        return ('water_struggle' if at<8 else 'water_gasp'),at/20

    @staticmethod
    def balance(age,index,roll,pitch,contacts=0):
        # Representative fixed UUID hash phases; only timing differs per child, never scale or space.
        hashes=(0,37,68,89);phase=(hashes[index]%97)*math.tau/97
        amplitude=12*math.exp(-max(0,age)/14) if age<44 else 0
        rz=(-1.3*roll+math.sin((age+index*3)*.72+phase)*4*amplitude/12) if age<44 else 0
        rx=(-pitch+math.sin((age+index*3)*.65+(hashes[index]%71)*math.tau/71)*2) if age<44 else 0
        if contacts>=12:rz+=min(16,contacts*.7)+math.sin(age*.9+phase)*2;rx+=-4+math.sin(age*.8+phase)*2
        return P.euler([0,0,max(-24,min(24,rz))])@P.euler([max(-12,min(12,rx)),0,0])

    def surface(self,x,z):
        V=self.vessel
        def inside(r):return r[0]<=x<=r[1] and r[2]<=z<=r[3]
        if inside(V['well']):return self.floor
        if inside(V['opening']):return self.ledge
        if any(inside(b) for b in V['brim_rects']):return V['bowl']['freeboard']
        return float('nan')

    def place(self,r,taken,body=None):
        x0,x1,z0,z1=self.vessel['opening'];best=None
        for i in range(max(0,math.floor((x1-x0-2*r)/.25+1e-9))+1):
            x=x0+r+i*.25
            for j in range(max(0,math.floor((z1-z0-2*r)/.25+1e-9))+1):
                z=z0+r+j*.25
                if z>.5:continue
                h=max(self.surface(x,z),*[self.surface(x+r*math.cos(a*math.pi/4),z+r*math.sin(a*math.pi/4)) for a in range(8)])
                if not math.isfinite(h) or abs(self.surface(x,z)-h)>1e-4:continue
                if any(len(s)>5 and s[5]>0 and math.hypot(x-s[0],z-s[2])<r+s[3] for s in taken):continue
                if any(h<s[1]+(s[4] if len(s)>4 else s[3]*2)-.05 and h+r*2>s[1]+.05 and math.hypot(x-s[0],z-s[2])<r+s[3]+.04 for s in taken):continue
                if body is not None and any(self.accessory_overlap(x,h,z,r,body,s) for s in taken):continue
                overlap=sum(max(0,1.1*(r+s[3])-math.hypot(x-s[0],z-s[2])) for s in taken)
                cost=100*overlap+(0 if h>self.floor+1e-6 else 8)+math.hypot(x,z-(z0+self.vessel['well'][2])*.5)
                if best is None or cost<best[0]-1e-9:best=(cost,x,h,z)
        if best is None:raise RuntimeError('No supported preview berth for the selected real body size.')
        return np.array(best[1:])

    def actor(self,index,clip,time,atlas,face):
        model=self.parent if index==0 else self.young
        duration=model.clips[clip]['duration'];time=time%duration if model.clips[clip]['loop'] else min(duration,max(0,time))
        key=(index==0,clip,round(time,5))
        if key not in self.pose_cache:
            pos,uv,normal=model.frame(clip,time)
            mesh=np.concatenate((pos,uv,np.repeat(normal[:,None,:],3,axis=1)),axis=2).reshape(-1,8)
            if index==0:mesh=mesh[:-model.boat]
            self.pose_cache[key]=mesh
        mesh=self.pose_cache[key].copy();mesh[:,:3]*=self.sizes[index]
        image=Image.fromarray(model.texture(face))
        mesh[:,3:5]=atlas.uv('marisa/'+face,image,mesh[:,3:5])
        return mesh

    def prop(self,name,atlas):
        data=self.props[name];raw=np.array(data['vertices'],dtype=float).reshape(-1,8);pieces=[]
        for part in data['parts']:
            if part.get('unit',0)>8:continue
            mesh=raw[part['start']:part['start']+part['count']].copy()
            texture=part.get('texture',f'mcyukkuri:textures/props/{name}.png').split(':',1)[-1]
            image=Image.open(P.ASSETS/texture).convert('RGBA')
            mesh[:,3:5]=atlas.uv(texture,image,mesh[:,3:5]);pieces.append(mesh)
        return np.concatenate(pieces)

    def release(self,index):
        rotation=motion('cap',9);down=np.array([1.,0,0]);along=np.array([0.,0,1.])
        at=rotation@(self.seats[index]*self.k)+down*(.035+.008*index)*16
        velocity=(down*(.07+.013*index)+along*((index-1.5)*.018)+[0,.025-.006*index,0])*16
        return at,velocity

    @staticmethod
    def drift(at,velocity,ticks,body_height=0):
        # Short illustrative static-water continuation. Only the release instant/initial velocity above is an engine contract.
        at=at.copy();velocity=velocity.copy()
        for _ in range(int(ticks)):
            submerged=at[1]<0
            if submerged:
                velocity*=.8;velocity[1]-=.005*16
                # Deliberately no permanent visual upward term. Only the real server may authorize finite effort.
            else:velocity[1]-=.08*16
            at+=velocity
        return at+velocity*(ticks-int(ticks))

    def build(self,kind,age):
        if kind=='crowd':return self.crowd(age)
        atlas=Atlas();meshes=[];rotation=motion(kind,age);released=kind=='cap' and age>=9
        hat=self.prop('marisa_hat_turned',atlas)
        hat[:,:3]*=np.array([self.k,self.k*self.vessel['squash']['bowl'],self.k]);hat[:,1]+=self.vessel['bowl']['freeboard']*self.k
        hat[:,5:]/=np.array([1,self.vessel['squash']['bowl'],1]);hat[:,5:]/=np.linalg.norm(hat[:,5:],axis=1)[:,None]
        meshes.append(rotate_mesh(hat,rotation))
        for i,size in enumerate(self.sizes):
            clip='boat_steady' if i==0 else 'hat_cradle';face='surprise' if i==0 else 'teary'
            if kind=='rock' and age>=44:clip='boat_drift' if i==0 else 'hat_cradle';face='closed_smile'
            if i>0 and kind=='rock' and age<44:clip='hat_stumble'
            actor=self.actor(i,clip,(age+i*3)/20,atlas,face)
            if released:
                at,velocity=self.release(i);at=self.drift(at,velocity,age-9)
                # After the real release, no boat transform applies to body or origin.
                initial,velocity=self.release(i)
                wet=next((t for t in range(45) if self.drift(initial,velocity,t)[1]<0),45)
                water_clip,water_time=self.distress(age-9-wet) if age-9>=wet else ('hat_stumble',(age-9)/20)
                actor=self.actor(i,water_clip,water_time,atlas,self.young.face(water_clip,water_time))
                meshes.append(rotate_mesh(actor,np.eye(3),at))
            else:
                own=np.eye(3)
                if i>0 and kind=='rock' and age<44:
                    envelope=12*math.exp(-age/14);roll=-envelope*math.sin(age*.62);pitch=.24*envelope*math.sin(age*.44)
                    own=self.balance(age,i,roll,pitch)
                meshes.append(rotate_mesh(rotate_mesh(actor,own,self.seats[i]*self.k),rotation))
        for j,(point,radius,name) in enumerate(self.cargo):
            item=self.prop(name,atlas);bounds=np.array(self.props[name]['bounds']);extent=max(bounds[:,1]-bounds[:,0])
            item[:,:3]-=np.array([bounds[0].mean(),bounds[1,0],bounds[2].mean()])
            item[:,:3]*=radius*self.k*math.sqrt(2)*.92/extent
            if released:
                at=motion('cap',9)@(point*self.k)+[.025*16,0,0]
                velocity=np.array([.08+.008*j,.03,(j-4)*.014])*16
                at=self.drift(at,velocity,age-9)
                meshes.append(rotate_mesh(item,P.euler([0,(age-9)*8,0]),at))
            else:meshes.append(rotate_mesh(item,np.eye(3),point*self.k) if kind=='rock' and age>=44 else rotate_mesh(rotate_mesh(item,np.eye(3),point*self.k),rotation))
        return np.concatenate(meshes).reshape(-1,3,8),np.array(atlas.image),rotation

    def crowd_plan(self):
        victim=self.refuge_place(3.584*.14,[[*self.seats[2],3.584*.16]],(2.4,-9.15))
        victim=np.array(victim);pusher=victim+np.array([0,0,3.584*(.14+.20)+.02])
        positions=[victim.copy()]*12
        current=victim.copy()
        for tick in range(12,200):
            proposed=current+[0,0,-min(.075,.035+tick*.001)]
            if not math.isfinite(self.surface(proposed[0],proposed[2])):
                release=tick;current=proposed;break
            current=proposed;positions.append(current.copy())
        origin=current*self.k;velocity=np.array([0,.015,-.075])*16
        falls=[origin.copy()];water_tick=None;age=0
        for tick in range(90):
            if current is None:break
            at=falls[-1].copy()
            if at[1]<0:
                if water_tick is None:water_tick=tick
                age=tick-water_tick;velocity*=.8;velocity[1]-=.005*16
                if age>=12 and (age-12)%32<8:
                    toward=self.seats[0]*self.k-at;toward[1]=0;toward/=max(1e-6,np.linalg.norm(toward))
                    velocity+=toward*(.005 if age>=44 else .006)*16
            else:velocity[1]-=.08*16
            falls.append(at+velocity)
        rescue=water_tick+48;start=falls[rescue]
        taken=[[*self.seats[0],3.584,6.4,1]]+[[*p,r,2*r,2] for p,r,_ in self.cargo]+[self.reservation(self.seats[1],.20),self.reservation(self.seats[2],.16)]
        safe=self.place(3.584*.14,taken,self.body_space(.14))*self.k
        dl=self.delivery;hat=dl['Hat'](self.vessel,'bowl');pose=dl['Pose']([0,0,0],0,self.k)
        duration=dl['solve'](start,safe,pose,hat,.42*.8*16*.14,self.vessel['delivery'],tmin=self.vessel['delivery']['hop_tmin'])
        return dict(start=victim,pusher=pusher,positions=positions,release=release,falls=falls,water=water_tick,rescue=rescue,
                    landing=safe,duration=duration,arc=dl['arc'](start,safe,duration,self.vessel['delivery']['gravity']))

    def crowd(self,age):
        if not hasattr(self,'crowd_data'):self.crowd_data=self.crowd_plan()
        plan=self.crowd_data;atlas=Atlas();meshes=[];V=self.vessel
        hat=self.prop('marisa_hat_turned',atlas)
        hat[:,:3]*=[self.k,self.k*V['squash']['bowl'],self.k];hat[:,1]+=V['bowl']['freeboard']*self.k
        hat[:,5:]/=[1,V['squash']['bowl'],1];hat[:,5:]/=np.linalg.norm(hat[:,5:],axis=1)[:,None]
        meshes.append(hat)
        release=plan['release'];flight_start=release+plan['rescue'];land=flight_start+plan['duration']
        for i,size in enumerate(self.sizes):
            clip='boat_reassure' if i==0 and age>=8 else 'boat_drift' if i==0 else 'hat_brim' if i>=2 else 'hat_cradle'
            position=self.seats[i]*self.k;time=age/20;own=np.eye(3)
            if i!=3 and age>=release:
                clip='boat_reassure';time=(age-release)/20
            elif i in (1,2) and age>=8:
                clip='boat_reassure';time=(age-8)/20
            elif i==2:
                clip='hat_stumble'
            if i==3:
                if age<release:
                    position=plan['positions'][min(int(age),len(plan['positions'])-1)]*self.k
                    clip='hat_jostle' if age>=12 else 'hat_stumble'
                    if age>=12:own=P.euler([0,0,min(16,age*.7)])@P.euler([-4,0,0])
                elif age<flight_start:
                    fall=age-release;position=plan['falls'][min(int(fall),len(plan['falls'])-1)]
                    if fall<plan['water']:clip='hat_stumble';time=fall/20
                    else:clip,time=self.distress(fall-plan['water'],low=True)
                elif age<land:
                    clip='boat_board';time=(age-flight_start)/20;position=np.array(plan['arc'](age-flight_start))
                else:
                    position=plan['landing'];clip='water_recover' if age>=land+3 else 'hat_cradle';time=max(0,(age-land-3)/20)
            elif i==2 and 12<=age<release:
                clip='hat_stumble'
            face=(self.parent if i==0 else self.young).face(clip,time)
            if i==3 and flight_start<=age<land+3:face='teary'
            actor=self.actor(i,clip,time,atlas,face);meshes.append(rotate_mesh(actor,own,position))
        # The crisis brings one real returning juvenile into contact; the calm scene above stays sparse.
        at=plan['pusher']+(plan['positions'][min(int(age),len(plan['positions'])-1)]-plan['start'])
        clip='hat_jostle' if age<release else 'boat_reassure'
        actor=self.actor(1,clip,age/20,atlas,self.young.face(clip,age/20));meshes.append(rotate_mesh(actor,np.eye(3),at*self.k))
        for point,radius,name in self.cargo:
            food=self.prop(name,atlas);bounds=np.array(self.props[name]['bounds']);extent=max(bounds[:,1]-bounds[:,0])
            food[:,:3]-=[bounds[0].mean(),bounds[1,0],bounds[2].mean()];food[:,:3]*=radius*self.k*math.sqrt(2)*.92/extent
            meshes.append(rotate_mesh(food,np.eye(3),point*self.k))
        return np.concatenate(meshes).reshape(-1,3,8),np.array(atlas.image),np.eye(3)


def rasterize(vertices,texture,az=.55,el=.65,span=22,center_y=3,center_x=0,center_z=0):
    """The existing runtime/render.js orthographic depth renderer, vectorized into NumPy pixel batches."""
    s,c,se,ce=math.sin(az),math.cos(az),math.sin(el),math.cos(el)
    right=np.array([c,0,s]);up=np.array([-s*se,ce,c*se]);eye=np.array([s*ce,se,-c*ce]);scale=H/(2*span)
    center=np.array([center_x,center_y,center_z]);pos=vertices[:,:,:3].copy()-center
    points=np.stack((W/2+pos@right*scale,H/2-pos@up*scale,pos@eye),axis=2)
    normals=vertices[:,:,5:];visible=normals[:,0,:]@eye>0
    points=points[visible];uv=vertices[visible,:,3:5];normals=normals[visible]
    a,b,c=points[:,0],points[:,1],points[:,2]
    det=(b[:,1]-c[:,1])*(a[:,0]-c[:,0])+(c[:,0]-b[:,0])*(a[:,1]-c[:,1])
    valid=np.abs(det)>1e-8;points=points[valid];uv=uv[valid];normals=normals[valid];det=det[valid]
    lo=np.maximum(0,np.floor(points[:,:,:2].min(axis=1)).astype(int));hi=np.minimum([W-1,H-1],np.ceil(points[:,:,:2].max(axis=1)).astype(int))
    dims=np.maximum(0,hi-lo+1);areas=dims[:,0]*dims[:,1]
    depth=np.full(W*H,-np.inf);pixels=np.tile(np.array(BG,dtype=np.uint8),(W*H,1))
    shade=.78+.22*np.maximum(0,normals@np.array([-.35,.8,-.48]))
    for first in range(0,len(points),1024):
        end=min(first+1024,len(points));counts=areas[first:end];total=counts.sum()
        if not total:continue
        ids=np.repeat(np.arange(first,end),counts);local=np.arange(total)-np.repeat(np.r_[0,np.cumsum(counts)[:-1]],counts)
        widths=dims[ids,0];x=lo[ids,0]+local%widths;y=lo[ids,1]+local//widths
        a,b,c=points[ids,0],points[ids,1],points[ids,2]
        wa=((b[:,1]-c[:,1])*(x+.5-c[:,0])+(c[:,0]-b[:,0])*(y+.5-c[:,1]))/det[ids]
        wb=((c[:,1]-a[:,1])*(x+.5-c[:,0])+(a[:,0]-c[:,0])*(y+.5-c[:,1]))/det[ids];wc=1-wa-wb
        inside=(wa>=-1e-7)&(wb>=-1e-7)&(wc>=-1e-7)
        ids=ids[inside];x=x[inside];y=y[inside];weights=np.stack((wa[inside],wb[inside],wc[inside]),axis=1)
        tex=np.clip(np.floor((uv[ids]*weights[:,:,None]).sum(axis=1)*1024).astype(int),0,1023)
        color=texture[tex[:,1],tex[:,0]];opaque=color[:,3]>=128
        ids=ids[opaque];x=x[opaque];y=y[opaque];weights=weights[opaque];color=color[opaque,:3]
        z=(points[ids,:,2]*weights).sum(axis=1);address=y*W+x;candidate=z>depth[address]+1e-7
        ids=ids[candidate];weights=weights[candidate];color=color[candidate];z=z[candidate];address=address[candidate]
        np.maximum.at(depth,address,z)
        nearest=z>=depth[address]-1e-8
        pixels[address[nearest]]=(color[nearest]*(shade[ids[nearest]]*weights[nearest]).sum(axis=1)[:,None]).astype(np.uint8)
    return pixels.reshape(H,W,3),depth.reshape(H,W),(right,up,eye,scale,center)


def water_layer(pixels,depth,camera,kind,age,rotation,vessel):
    right,up,eye,scale,center=camera
    yy,xx=np.mgrid[:H,:W];sx=(xx+.5-W/2)/scale+center@right;sy=(H/2-yy-.5)/scale+center@up
    inverse=np.linalg.inv(np.array([[right[0],right[2]],[up[0],up[2]]]))
    x=inverse[0,0]*sx+inverse[0,1]*sy;z=inverse[1,0]*sx+inverse[1,1]*sy
    water_depth=x*eye[0]+z*eye[2]-center@eye
    # An unbounded static plane stays continuous when the camera follows a real submerged body.
    wet=water_depth>depth+1e-7
    if kind in ('rock','crowd'):
        # Project the tilted depth-only opening, after entity colours; skip water hidden behind it.
        corners=np.array([[p[0]*1.5,.48,p[1]*1.5] for p in vessel['bowl']['mask']])@rotation.T
        polygon=np.c_[W/2+(corners-center)@right*scale,H/2-(corners-center)@up*scale]
        mask=Image.new('1',(W,H));ImageDraw.Draw(mask).polygon([tuple(p) for p in polygon],fill=1)
        wet&=~np.array(mask,dtype=bool)
    ripple=(np.sin(x*.22+z*.17+age*.025)> .975)&(np.cos(z*.31-x*.04)>.15)
    water=np.zeros_like(pixels)+[123,187,203];water[ripple]=[171,218,224]
    occupied=np.isfinite(depth);alpha=np.where(occupied,.52,1.0)
    pixels[wet]=(pixels[wet]*(1-alpha[wet,None])+water[wet]*alpha[wet,None]).astype(np.uint8)
    return Image.fromarray(pixels)


def caption(image,kind,age):
    out=Image.new('RGB',(W,H+100),BG);out.paste(image,(0,88));d=ImageDraw.Draw(out)
    if kind=='rock':title='轻摇帽船 → 赤油压低撑稳 → 亲油稳住';subtitle='赤油 14% / 16% 在实际前檐；每只独立失衡相位，不放大体型或改座位。'
    elif kind=='crowd':title='帽檐争抢 → 真实挤落 → 冲击呼救与恢复';subtitle='低体力受控样本；持续身体接触才推移，按实际帽面边缘释放，非 AI 实录。'
    else:title='连续倾倒 → 第 9 tick 真实离船 → 分阶段落水';subtitle='释放瞬间按当前源码；冲击收身、努力与喘息按服务端阶段源动作采样。'
    d.text((24,10),title,font=P.preview_font(27),fill='#203e48')
    d.text((24,49),subtitle,font=P.preview_font(18),fill='#4b6b74')
    d.rounded_rectangle((20,110,152,142),8,fill='#e7f3f4');d.text((29,114),f't = {age/20:.2f} 秒',font=P.preview_font(17),fill='#294e59')
    d.text((24,H+76),'当前运行模型离线渲染 · 非游戏实录 · 未模拟完整碰撞、导航与水伤过程',font=P.preview_font(16),fill='#46636c')
    return out


def camera_for(scene,kind,age,detail=False):
    if kind=='cap':return dict(span=23,center_y=2)
    args=dict(az=.10,el=.27,span=7.5 if detail else 10.8,center_y=1.8,center_z=-5)
    if kind=='crowd':
        p=scene.crowd_data;release=p['release'];flight=release+p['rescue'];land=flight+p['duration']
        if age>=release:
            if age<flight:at=p['falls'][min(int(age-release),len(p['falls'])-1)]
            elif age<land:at=np.array(p['arc'](age-flight))
            else:at=p['landing']
            blend=min(1,(age-release)/10)
            args['center_y']=1.8*(1-blend)+(at[1]+2)*blend
            args['center_z']=-5*(1-blend)+at[2]*blend
            if detail:args['center_x']=at[0]
            if detail and age>=land:args['az']=1.1;args['el']=.55
    return args


def main():
    OUT.mkdir(parents=True,exist_ok=True);scene=Scene();shots={}
    plan=scene.crowd_plan();scene.crowd_data=plan
    land=plan['release']+plan['rescue']+plan['duration']
    crowd_end=land+59
    crowd_ages=sorted(set(list(range(0,crowd_end+1,3))+[12,plan['release']-1,plan['release']+plan['water']+4,
                       plan['release']+plan['water']+14,plan['release']+plan['water']+46,land+23,crowd_end]))
    schedules={'rock':list(range(0,45,2))+[48,52,56],'cap':list(range(0,17))+list(range(18,45,2)),'crowd':crowd_ages}
    if '--crowd-only' in sys.argv or '--panels-only' in sys.argv:
        for kind in (('rock','cap','crowd') if '--panels-only' in sys.argv else ('rock','cap')):
            name={'rock':'rock-and-steady.gif','cap':'capsize-and-struggle.gif','crowd':'crowd-and-distress.gif'}[kind]
            with Image.open(OUT/name) as gif:
                for index,age in enumerate(schedules[kind]):
                    gif.seek(index);shots[kind,age]=gif.convert('RGB').copy()
        schedules={} if '--panels-only' in sys.argv else {'crowd':crowd_ages}
    for kind,ages in schedules.items():
        frames=[]
        for n,age in enumerate(ages):
            mesh,texture,rotation=scene.build(kind,age)
            pixels,depth,camera=rasterize(mesh,texture,**camera_for(scene,kind,age))
            image=caption(water_layer(pixels,depth,camera,kind,age,rotation,scene.vessel),kind,age)
            frames.append(image);shots[kind,age]=image
            if kind=='rock' and age==2:
                image.save(OUT/'rock-poster.png');print(OUT/'rock-poster.png',flush=True)
            if kind=='cap' and age==8:image.save(OUT/'capsize-poster.png')
            print(f'{kind}: {n+1}/{len(ages)} (tick {age})',flush=True)
            filename='rock-and-steady.gif' if kind=='rock' else 'crowd-and-distress.gif' if kind=='crowd' else 'capsize-and-struggle.gif'
        durations=[max(50,(ages[min(i+1,len(ages)-1)]-age)*50) for i,age in enumerate(ages)];durations[-1]=850
        frames[0].save(OUT/filename,save_all=True,append_images=frames[1:],duration=durations,loop=0,disposal=2)
    shots['rock',2].save(OUT/'rock-poster.png');shots['cap',8].save(OUT/'capsize-poster.png')
    sheet=Image.new('RGB',(1800,1025),BG);d=ImageDraw.Draw(sheet)
    d.text((30,15),'帽船：同中心摇晃、连续翻覆与落水',font=P.preview_font(36),fill='#203e48')
    d.text((30,65),'当前模型 / 亲油 100% · 子油 20% · 帽檐赤油 16%、14% / 原尺寸与真实帽面',font=P.preview_font(22),fill='#4b6b74')
    panels=[('rock',2,'① 轻摇：全家与库存一起倾动'),('rock',16,'② 亲油稳身，幼体保持低坐姿'),('rock',52,'③ 逐渐停稳，不靠点击翻船'),('cap',6,'④ 帽体连续倾倒，尚未离船'),('cap',9,'⑤ 第 9 tick：按实际座位释放'),('cap',26,'⑥ 离船后：水中挣扎动作示意')]
    for i,(kind,age,label) in enumerate(panels):
        x=i%3*600;y=110+i//3*440
        tile=shots[kind,age].resize((600,456),Image.Resampling.LANCZOS)
        sheet.paste(tile.crop((0,60,600,438)),(x,y+38));d.text((x+20,y),label,font=P.preview_font(21),fill='#294e59')
    d.text((30,995),'离线软件渲染，不是游戏实录；释放后路线不代表完整引擎物理或救援结果。',font=P.preview_font(18),fill='#4b6b74')
    sheet.save(OUT/'storyboard.png');sheet.resize((1200,683),Image.Resampling.LANCZOS).save(OUT/'cover.png')
    cabin_detail(scene)
    sequence=[(6,'压低撑檐'),(12,'持续接触争抢'),(plan['release']+plan['water']+4,'落水冲击'),
              (plan['release']+plan['water']+14,'抬脸呼救扑水'),(plan['release']+plan['water']+46,'低力停顿'),(land+23,'真实回帽后喘息')]
    stage_sheet=Image.new('RGB',(1800,1025),BG);sd=ImageDraw.Draw(stage_sheet)
    sd.text((28,18),'帽檐与落水 · 六个真实阶段的离线分镜',font=P.preview_font(34),fill='#203e48')
    sd.text((30,66),'原尺寸样本；新回归孩子造成持续接触，实际越过帽面边界后释放。救援只接回仍完整的亲帽。',font=P.preview_font(20),fill='#4b6b74')
    for i,(tick,label) in enumerate(sequence):
        mesh,tex,rot=scene.build('crowd',tick);pixels,depth,camera=rasterize(mesh,tex,**camera_for(scene,'crowd',tick,True))
        image=caption(water_layer(pixels,depth,camera,'crowd',tick,rot,scene.vessel),'crowd',tick).resize((600,456),Image.Resampling.LANCZOS)
        x=i%3*600;y=110+i//3*440;stage_sheet.paste(image.crop((0,60,600,438)),(x,y+38))
        sd.text((x+20,y),str(i+1)+' / '+label,font=P.preview_font(23),fill='#294e59')
    sd.text((30,995),'受控低体力动作样本，不是游戏录像；实际触发、完整水物理、伤害及多人同步仍待游戏确认。',font=P.preview_font(18),fill='#4b6b74')
    stage_sheet.save(OUT/'distress-stages.png')
    shots['crowd',plan['release']+plan['water']+4].save(OUT/'crowd-poster.png')
    # An honest short reference for the page, not an acceptance/check report.
    (OUT/'motion-notes.txt').write_text('当前源码离线动作预览，非游戏实录。\n单次轻摇A=12°，指数衰减，44tick；倾倒16tick至110°，第9tick释放。\n亲油无孕，静水，初始船速度0；16%和14%赤油使用17点完整支撑前檐位，20%子油按实际身体和帽饰包络选择内座，均不额外缩放。\n拥挤片段为另一个20%回归孩子接触的受控低体力场景：真实接触12tick后按源码步长沿帽面推移，中心越界后按.075水平/.015竖直初速释放。\nIMPACT12tick、STRUGGLE短努力及喘息、低体力EXHAUSTED分别采样新源动作；无持续展示浮力。完整水阻、AI、伤害未模拟。\n亲油近帽缘救援采用当前HatVessel.solve/arc回实际安全位，真实支撑3tick后RECOVER56tick；这是参数分镜，不宣称AI已在游戏自动成功。\n',encoding='utf-8')
    template=Path(__file__).with_suffix('.html')
    if template.exists():shutil.copyfile(template,OUT/'index.html')
    print(OUT/'storyboard.png',flush=True)


def cabin_detail(scene):
    """A closer front-side camera, retaining every real occluder and all original berth/size values."""
    mesh,texture,rotation=scene.build('rock',0)
    pixels,depth,camera=rasterize(mesh,texture,az=-.12,el=.25,span=8.8,center_y=1.8,center_z=-5)
    image=water_layer(pixels,depth,camera,'rock',0,rotation,scene.vessel)
    card=Image.new('RGB',(W,H+100),BG);card.paste(image,(0,88));draw=ImageDraw.Draw(card)
    draw.text((24,10),'帽内局部 · 实际座位、实际体型与两侧储粮',font=P.preview_font(28),fill='#203e48')
    draw.text((24,50),'仅拉近前侧相机；亲油头部可出画，帽檐和幼体帽子保留真实遮挡。',font=P.preview_font(18),fill='#4b6b74')
    draw.text((24,H+76),'八份真实库存样本，未放大食物或幼体 · 当前模型离线渲染，非游戏实录',font=P.preview_font(16),fill='#46636c')
    card.save(OUT/'cabin-detail.png');print(OUT/'cabin-detail.png',flush=True)


if __name__=='__main__':main()
