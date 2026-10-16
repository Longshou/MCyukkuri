"""Current runtime hat, Marisa meshes and poses, rendered offline with NumPy/Pillow only.

Rocking/capsize curves, shared pivot, ledge packing and release instant mirror the Java
implementation. Post-release water drag/escape paths are illustrative, not engine replay.
No browser, game, Java compilation or asset export is invoked.
"""
from pathlib import Path
import json, math, shutil, runpy, sys, io, zipfile, uuid
import heapq
import numpy as np
from PIL import Image, ImageDraw
import preview as P

OUT=P.ROOT/'.local/previews/hat-motion'
W,H=1000,660
BG=(236,245,246)


class SelectedPosed(P.Posed):
    """The exact selected placement splice and the existing one-braid skin, also used by the game."""
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs)
        sys.path.insert(0,str(P.ROOT/'scripts'));import placement_fit
        model=placement_fit.fitted(P.ASSETS/'yukkuri'/f'{self.ident}.json',placement_fit.load())
        V=np.array(model['vertices']).reshape(-1,8);weights=np.array(model['weights'])
        hidden=tuple(json.loads((P.ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))['variants'][self.ident]['accessory_prefixes']) if self.aquatic else ()
        keep=[i for part in model['parts'] if not part.get('anatomy') and not(hidden and part['name'].startswith(hidden)) for i in range(part['start'],part['start']+part['count'])]
        old=(self.v[-self.boat:].copy(),self.uv[-self.boat:].copy(),self.n[-self.boat:].copy()) if self.boat else None
        self.model=model;self.v=V[keep,:3];self.uv=V[keep,3:5];self.n=V[keep,5:8]
        self.a=weights[keep,0].astype(int);self.b=weights[keep,1].astype(int);self.q=weights[keep,2:3]
        if old is not None:
            self.v=np.r_[self.v,old[0]];self.uv=np.r_[self.uv,old[1]];self.n=np.r_[self.n,old[2]]
            self.a=np.r_[self.a,np.zeros(self.boat,int)];self.b=np.r_[self.b,np.zeros(self.boat,int)];self.q=np.r_[self.q,np.zeros((self.boat,1))]
        self.rescue_tip=None;self.self_hat_transfer=None

    def frame(self,name,t):
        result=super().frame(name,t)
        if self.self_hat_transfer is not None:
            destination,q,spec=self.self_hat_transfer;old=self.last_matrices.copy();M=old.copy()
            bones=self.model['bones'];hat=next(i for i,b in enumerate(bones) if b['name']=='hat_follow')
            anchor=np.array(json.loads((P.ASSETS/'yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))['anchor_worn'])
            start=M[hat].copy();from_at=(start@np.r_[anchor,1])[:3];to_at=(destination@np.r_[anchor,1])[:3]
            smooth=lambda x:(lambda u:u*u*(3-2*u))(min(1,max(0,x)))
            slide=smooth((q-spec['slide_begin'])/(spec['lower_begin']-spec['slide_begin']));lower=smooth((q-spec['lower_begin'])/(1-spec['lower_begin']))
            at=from_at+(to_at-from_at)*slide;at[1]=(from_at[1]+spec['clearance_model_units']*smooth(q/spec['slide_begin']))*(1-lower)+to_at[1]*lower
            a_scale=np.linalg.norm(start[:3,:3],axis=0);b_scale=np.linalg.norm(destination[:3,:3],axis=0)
            rig=runpy.run_path(str(Path(__file__).with_name('hat.py')))
            R=rig['slerp'](start[:3,:3]/a_scale,destination[:3,:3]/b_scale,lower)
            M[hat,:3,:3]=R*(a_scale+(b_scale-a_scale)*lower);M[hat,:3,3]=at-M[hat,:3,:3]@anchor
            for i,b in enumerate(bones):
                ancestor=b['parent'];follows=False
                while ancestor>=0:
                    if ancestor==hat:follows=True;break
                    ancestor=bones[ancestor]['parent']
                if follows:M[i]=M[b['parent']]@np.linalg.inv(old[b['parent']])@old[i]
            self.last_matrices=M;hv=np.c_[self.v,np.ones(len(self.v))]
            pos=np.einsum('nij,nj->ni',M[self.a],hv)[:,:3]*(1-self.q)+np.einsum('nij,nj->ni',M[self.b],hv)[:,:3]*self.q
            nrm=np.einsum('nij,nj->ni',M[self.a][:,:3,:3],self.n)
            result=pos.reshape(-1,3,3),self.uv.reshape(-1,3,2),nrm.reshape(-1,3,3)[:,0]
        if self.rescue_tip is None:return result
        bones=self.model['bones'];names={b['name']:i for i,b in enumerate(bones)};joint=names['hand_right'];tip=names['hand_right_tip']
        old=self.last_matrices.copy();M=old.copy();parent=bones[joint]['parent'];root=np.array(bones[joint]['pivot']);end=np.array(bones[tip]['pivot'])
        desired=(np.linalg.inv(M[parent])@np.r_[self.rescue_tip,1])[:3]-root
        geometry=json.loads((P.ASSETS/'yukkuri_presentation/braid_rescue.json').read_text(encoding='utf-8'))
        rest=np.array(geometry['braid_tip'])-root
        rig=runpy.run_path(str(Path(__file__).with_name('hat.py')));R=rig['aim'](rest,desired)
        local=np.eye(4);local[:3,:3]=R;local[:3,3]=root-R@root;M[joint]=M[parent]@local
        for i,bone in enumerate(bones):
            ancestor=bone['parent'];follows=False
            while ancestor>=0:
                if ancestor==joint:follows=True;break
                ancestor=bones[ancestor]['parent']
            if follows:M[i]=M[bone['parent']].copy() if bone['name'] in ('hand_right_tip','hand_right_ribbon') else M[bone['parent']]@np.linalg.inv(old[bone['parent']])@old[i]
        self.last_matrices=M;hv=np.c_[self.v,np.ones(len(self.v))]
        pos=np.einsum('nij,nj->ni',M[self.a],hv)[:,:3]*(1-self.q)+np.einsum('nij,nj->ni',M[self.b],hv)[:,:3]*self.q
        nrm=np.einsum('nij,nj->ni',M[self.a][:,:3,:3],self.n)
        if self.boat:pos[-self.boat:],nrm[-self.boat:]=self.v[-self.boat:],self.n[-self.boat:]
        return pos.reshape(-1,3,3),self.uv.reshape(-1,3,2),nrm.reshape(-1,3,3)[:,0]


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
        self.parent=SelectedPosed('marisa',data,True)
        self.young=SelectedPosed('marisa',data,worn_hat_scale=self.k)
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

    def place(self,r,taken,body=None,all_candidates=False):
        x0,x1,z0,z1=self.vessel['opening'];best=None;candidates=[]
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
                candidates.append((cost,x,h,z))
                if best is None or cost<best[0]-1e-9:best=(cost,x,h,z)
        if best is None:raise RuntimeError('No supported preview berth for the selected real body size.')
        if all_candidates:return [np.array(p[1:]) for p in sorted(candidates,key=lambda p:p[0])]
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


class CausalScene(Scene):
    """Controlled, source-parameter stories. These are authored scenes, never a recording of AI success."""
    def __init__(self):
        super().__init__()
        self.rig=json.loads((P.ASSETS/'yukkuri_presentation/braid_rescue.json').read_text(encoding='utf-8'))
        self.root_rotation=P.euler(self.rig['root_rotation']);pivot=np.array(self.rig['root_pivot'])
        posed=lambda point:self.root_rotation@(np.array(point)-pivot)+pivot+[0,self.rig['root_lift'],0]
        self.braid_root=posed(self.rig['braid_root']);self.braid_rest=posed(self.rig['braid_tip']);self.braid_length=self.rig['braid_length']
        self.aim=runpy.run_path(str(Path(__file__).with_name('hat.py')))['slerp']
        self.masks=[]
        self.calm_infant=self.seats[3].copy()
        self.rescue=None
        self.voices=self.voice_cues()
        with zipfile.ZipFile(P.ROOT/'.local/gradle-home/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar') as archive:
            self.stick=Image.open(io.BytesIO(archive.read('assets/minecraft/textures/item/stick.png'))).convert('RGBA')
        self.source=np.array([1.6,0,-48.])
        self.escape=self.sail(np.zeros(3),np.array([0.,0.,96.]),44,180,stop_distance=7*16,source=self.source)

    @staticmethod
    def sail(start,destination,begin,yaw=180,stop_distance=None,source=None):
        at=np.array(start,float);velocity=np.zeros(3);out=[(at.copy(),180-yaw,False)]*begin
        for tick in range(begin,420):
            delta=destination-at;delta[1]=0
            if np.linalg.norm(delta)<math.sqrt(.12)*16 or stop_distance is not None and np.linalg.norm(at-source)>=stop_distance:break
            forward=delta/max(1e-8,np.linalg.norm(delta));velocity*=.88
            velocity+=forward*(.004+.008*max(0,math.sin((tick-begin)*math.pi/16)))*16
            speed=np.linalg.norm(velocity)
            if speed>.08*16:velocity*=.08*16/speed
            heading=math.degrees(math.atan2(forward[2],forward[0]))-90
            diff=(heading-yaw+180)%360-180;yaw+=max(-4,min(4,diff))
            at+=velocity;out.append((at.copy(),180-yaw,True))
        stop=len(out);out.extend([(at.copy(),180-yaw,False)]*81)
        return dict(frames=out,calm=stop,end=stop+80)

    def rock_frame(self,age):
        at,yaw,moving=self.escape['frames'][min(int(age),len(self.escape['frames'])-1)]
        tilt=motion('rock',age);R=P.euler([0,yaw,0]);atlas=Atlas();self.masks=[]
        meshes=self.vessel_mesh(atlas,1,at,yaw,tilt if age<44 else None,food=2)
        calm=age>=self.escape['calm'];time=(age-self.escape['calm'])/20 if calm else age/20
        clip='boat_calm' if calm else 'boat_paddle' if moving else 'boat_alarm_call' if age<24 else 'boat_alarm_wait'
        face=self.parent.face(clip,time) if not moving else 'teary'
        parent=self.person(1,clip,time,atlas,face,np.zeros(3),hatless=True)
        meshes.append(rotate_mesh(parent,R@tilt,R@(tilt@(self.seats[0]*self.k))+at))
        for index in (2,3):
            clip='boat_calm' if calm else 'hat_stumble' if age<44 else 'boat_reassure'
            child=self.person(self.sizes[index],clip,time,atlas,self.young.face(clip,time),np.zeros(3))
            correction=np.eye(3)
            if age<44:
                envelope=12*math.exp(-age/14);correction=self.balance(age,index,-envelope*math.sin(age*.62),.24*envelope*math.sin(age*.44))
            meshes.append(rotate_mesh(child,R@tilt@correction,R@(tilt@(self.seats[index]*self.k))+at))
        stage='受惊，亲油呼子与警戒' if age<44 else '真实操桨，朝摇船者反方向离开' if moving else '源距已超过7格，持续安全后逐渐安心'
        return np.concatenate(meshes).reshape(-1,3,8),np.array(atlas.image),dict(at=at,yaw=yaw,stage=stage,focus=R@(self.calm_infant*self.k)+at)

    def foot(self,x,z,r):
        heights=[self.surface(x,z)]+[self.surface(x+r*math.cos(i*math.pi/4),z+r*math.sin(i*math.pi/4)) for i in range(8)]
        return max((h for h in heights if math.isfinite(h)),default=float('nan'))

    def parent_fits(self,at,taken):
        if not math.isfinite(self.surface(at[0],at[2])) or abs(self.foot(at[0],at[2],3.584)-at[1])>1e-4:return False
        body=(self.body_height/self.k,0,0,0,0)
        for other in taken:
            oh=other[4] if len(other)>4 else other[3]*2
            if ((len(other)>5 and other[5]>0) or (at[1]<other[1]+oh-.05 and at[1]+body[0]>other[1]+.05)) and math.hypot(at[0]-other[0],at[2]-other[2])<3.584+other[3]+.04:return False
            if self.accessory_overlap(*at,3.584,body,other):return False
        return True

    def refine(self,target,candidate):
        at=np.array(candidate,float)
        for _ in range(3):
            root=at*self.k+self.braid_root;delta=target-root;dy=delta[1]
            if abs(dy)>=self.braid_length:return None
            direction=delta.copy();direction[1]=0;direction/=max(1e-8,np.linalg.norm(direction))
            wanted=target-self.braid_root-direction*math.sqrt(self.braid_length**2-dy**2)
            local=wanted/self.k;h=self.foot(local[0],local[2],3.584)
            if not math.isfinite(h):return None
            at=np.array([local[0],h,local[2]])
        return at if abs(np.linalg.norm(target-(at*self.k+self.braid_root))-self.braid_length)<=.004*16 else None

    def route(self,from_at,to_at,taken):
        V=self.vessel;step=.4;nx=math.ceil(V['brim'][0]*2/step)+1;nz=math.ceil(V['brim'][1]*2/step)+1
        points={}
        def route_clear(at):
            for other in taken:
                oh=other[4] if len(other)>4 else other[3]*2
                if at[1]>=other[1]+oh-.15 or at[1]+self.body_height/self.k<=other[1]+.15:continue
                distance=math.hypot(at[0]-other[0],at[2]-other[2]);initial=math.hypot(from_at[0]-other[0],from_at[2]-other[2]);clearance=3.584+other[3]
                if distance<clearance and (initial>=clearance or distance<initial-.01):return False
            return True
        for i in range(nx):
            for j in range(nz):
                x=-V['brim'][0]+i*step;z=-V['brim'][1]+j*step;h=self.foot(x,z,3.584)
                if math.isfinite(h) and math.isfinite(self.surface(x,z)):
                    at=np.array([x,h,z])
                    if route_clear(at):points[i,j]=at
        start=(-1,-1);end=(-2,-2);points[start]=np.array(from_at);points[end]=np.array(to_at)
        def segment(a,b):
            ledge=self.ledge;max_step=max(ledge-self.floor,V['bowl']['freeboard']-ledge)
            if abs(a[1]-b[1])>max_step+.001:return False
            for q in np.linspace(0,1,6):
                p=a+(b-a)*q;h=self.foot(p[0],p[2],3.584)
                if not math.isfinite(self.surface(p[0],p[2])) or not math.isfinite(h) or h>max(a[1],b[1])+.001:return False
                for other in taken:
                    oh=other[4] if len(other)>4 else other[3]*2
                    if p[1]>=other[1]+oh-.15 or p[1]+self.body_height/self.k<=other[1]+.15:continue
                    distance=math.hypot(p[0]-other[0],p[2]-other[2]);initial=math.hypot(from_at[0]-other[0],from_at[2]-other[2]);clearance=3.584+other[3]
                    if distance<clearance and (initial>=clearance or distance<initial-.01):return False
            return True
        distances={start:0};previous={};queue=[(0,start)]
        while queue:
            cost,key=heapq.heappop(queue)
            if cost>distances[key]+1e-8:continue
            if key==end:break
            if key==start:candidates=[k for k,v in points.items() if k!=start and np.linalg.norm((v-from_at)[[0,2]])<1]
            else:candidates=[(key[0]+dx,key[1]+dz) for dx in (-1,0,1) for dz in (-1,0,1) if dx or dz]
            if np.linalg.norm((points[key]-to_at)[[0,2]])<1:candidates.append(end)
            for nxt in candidates:
                if nxt not in points or not segment(points[key],points[nxt]):continue
                score=cost+np.linalg.norm(points[nxt]-points[key])
                if score<distances.get(nxt,float('inf'))-1e-8:distances[nxt]=score;previous[nxt]=key;heapq.heappush(queue,(score,nxt))
        if end not in previous:return None
        keys=[end]
        while keys[-1]!=start:keys.append(previous[keys[-1]])
        return [points[k] for k in reversed(keys)]

    @staticmethod
    def along(path,q):
        lengths=[np.linalg.norm(b-a) for a,b in zip(path,path[1:])];distance=sum(lengths)*min(1,max(0,q))
        for a,b,length in zip(path,path[1:],lengths):
            if distance<=length:return a+(b-a)*(distance/max(length,1e-8))
            distance-=length
        return path[-1]

    def rescue_plan(self,feet,others):
        cargo_taken=[[*p,r,2*r,2] for p,r,_ in self.cargo[:2]]
        taken=cargo_taken+others;grip=feet+[0,self.body_height*.14*self.rig['grip_height'],0]
        candidates=[];V=self.vessel
        for x in np.arange(-V['brim'][0],V['brim'][0]+1e-6,.4):
            for z in np.arange(-V['brim'][1],V['brim'][1]+1e-6,.4):
                h=self.foot(x,z,3.584)
                if not math.isfinite(h):continue
                refined=self.refine(grip,[x,h,z])
                if refined is not None and self.parent_fits(refined,taken):candidates.append(refined)
        candidates.sort(key=lambda a:np.linalg.norm(a-self.seats[0]))
        pickup=None;walk=None
        for at in candidates[:16]:
            path=self.route(self.seats[0],at,taken)
            if path is not None:pickup=at;walk=path;break
        if pickup is None:raise RuntimeError('This authored water sample has no real supported pickup route; choose a reachable scene.')
        seats=self.place(3.584*.14,[[*self.seats[0],3.584,self.body_height/self.k,1]]+taken,self.body_space(.14),True)
        place_route=None
        for reserved in seats:
            target=reserved*self.k+[0,self.body_height*.14*self.rig['grip_height'],0];placements=[]
            for x in np.arange(-V['brim'][0],V['brim'][0]+1e-6,.4):
                for z in np.arange(-V['brim'][1],V['brim'][1]+1e-6,.4):
                    at=self.refine(target,[x,self.foot(x,z,3.584),z])
                    if at is not None and self.parent_fits(at,taken) and max(abs((at-reserved)[[0,2]]))*self.k>=(12.8+12.8*.14)/2:placements.append(at)
            placements.sort(key=lambda a:np.linalg.norm(a-pickup))
            for at in placements:
                path=self.route(pickup,at,taken+[self.reservation(reserved,.14)])
                if path is not None:place_route=path;break
            if place_route is not None:break
        if place_route is None:raise RuntimeError('No jointly supported parent/child placement; no substitute flight is rendered.')
        print('Authored shared joint placement:',dict(pickup=pickup.tolist(),childSeat=reserved.tolist(),parentPlacement=place_route[-1].tolist()),flush=True)
        travel=lambda path:max(16,math.ceil(sum(np.linalg.norm(b-a) for a,b in zip(path,path[1:]))/.20))
        goal=grip-(pickup*self.k+self.braid_root);raised=np.array([goal[0]*.12,np.linalg.norm(goal),goal[2]*.12])
        end_direction=target-(place_route[-1]*self.k+self.braid_root)
        duration=lambda a,b,phase:max(self.rig[phase+'_ticks'],math.ceil(1.5*math.acos(np.clip(a@b/np.linalg.norm(a)/np.linalg.norm(b),-1,1))*self.braid_length/(.075*16)))
        return dict(feet=feet,grip=grip,pickup=pickup,walk=walk,walk_ticks=travel(walk),seat=reserved,place_route=place_route,place_walk=travel(place_route),lift_ticks=duration(goal,raised,'lift'),place_ticks=duration(raised,end_direction,'place'))

    def spherical(self,root,from_direction,to_direction,q):
        a=from_direction/max(1e-9,np.linalg.norm(from_direction));b=to_direction/max(1e-9,np.linalg.norm(to_direction));q=min(1,max(0,q));q=q*q*(3-2*q)
        angle=math.acos(np.clip(a@b,-1,1))
        direction=a if angle<1e-8 else (math.sin((1-q)*angle)*a+math.sin(q*angle)*b)/math.sin(angle)
        return root+direction*self.braid_length

    def rescue_at(self,elapsed,plan=None):
        p=self.rescue if plan is None else plan;R=self.rig;walk=p['walk_ticks'];reach=max(walk,R['reach_ticks']);contact=reach+R['contact_ticks'];grip=contact+R['grip_ticks'];lift=grip+p['lift_ticks'];move=lift+p['place_walk'];place=move+p['place_ticks'];end=place+R['release_ticks']
        parent=self.along(p['walk'],elapsed/walk) if elapsed<walk else self.along(p['place_route'],(elapsed-lift)/p['place_walk']) if lift<=elapsed<move else p['place_route'][-1] if elapsed>=move else p['pickup']
        feet=parent*self.k;root=feet+self.braid_root;start=self.braid_rest-self.braid_root;goal=p['grip']-(p['pickup']*self.k+self.braid_root);raised=np.array([goal[0]*.12,np.linalg.norm(goal),goal[2]*.12])
        phase='REACH';tip=self.spherical(root,start,p['grip']-root,elapsed/R['reach_ticks']);child=p['feet'];clip='braid_rescue_reach';time=min(.8,max(0,elapsed/R['reach_ticks']*.8))
        if elapsed>=reach:phase='CONTACT';tip=root+goal/np.linalg.norm(goal)*self.braid_length
        if elapsed>=contact:phase='GRIP';clip='braid_rescue_hold';time=(elapsed-contact)/20;child=tip-[0,self.body_height*.14*R['grip_height'],0]
        if elapsed>=grip:phase='LIFT';tip=self.spherical(root,goal,raised,(elapsed-grip)/p['lift_ticks']);child=tip-[0,self.body_height*.14*R['grip_height'],0];time=.3+.9*min(1,(elapsed-grip)/p['lift_ticks'])
        if elapsed>=lift:phase='PLACE';clip='braid_rescue_settle';time=0;tip=root+raised/np.linalg.norm(raised)*self.braid_length;child=tip-[0,self.body_height*.14*R['grip_height'],0]
        if elapsed>=move:
            tip=self.spherical(root,raised,p['seat']*self.k+[0,self.body_height*.14*R['grip_height'],0]-root,(elapsed-move)/p['place_ticks']);child=tip-[0,self.body_height*.14*R['grip_height'],0];time=.6*min(1,(elapsed-move)/p['place_ticks'])
        if elapsed>=place:phase='RELEASE';tip=p['seat']*self.k+[0,self.body_height*.14*R['grip_height'],0];child=p['seat']*self.k
        if elapsed>=end:phase='RECOVER';tip=None;child=p['seat']*self.k;clip='boat_reassure';time=(elapsed-end)/20
        return dict(parent=feet,tip=tip,child=child,phase=phase,clip=clip,time=time,end=end,contact=contact,lift=grip,place=move)

    def person(self,scale,clip,time,atlas,face,at,yaw=0,hatless=False,tip=None,hat_transfer=None):
        model=self.parent if hatless else self.young;duration=model.clips[clip]['duration'];time=time%duration if model.clips[clip]['loop'] else min(duration,max(0,time))
        model.rescue_tip=tip;model.self_hat_transfer=hat_transfer
        pos,uv,normals=model.frame(clip,time);model.rescue_tip=None;model.self_hat_transfer=None
        mesh=np.concatenate((pos,uv,np.repeat(normals[:,None,:],3,axis=1)),axis=2).reshape(-1,8)
        if hatless:mesh=mesh[:-model.boat]
        if clip=='boat_paddle':
            index=next(i for i,b in enumerate(model.model['bones']) if b['name']=='hand_right_tip');pivot=np.array(model.model['bones'][index]['pivot'])
            transform=model.last_matrices[index].copy();transform[:3,3]=(transform@np.r_[pivot,1])[:3]+transform[:3,:3]@np.array([0,-.22*16,0])
            transform[:3,:3]=transform[:3,:3]@P.euler([0,0,45])
            corners=np.array([[-8,-8,0],[8,-8,0],[8,8,0],[-8,8,0]])*(.85*.5)
            points=(transform@np.c_[corners,np.ones(4)].T).T[:,:3]
            uv=atlas.uv('minecraft/stick',self.stick,np.array([[0,1],[1,1],[1,0],[0,0]]))
            item=[]
            for order in ([0,1,2],[0,2,3],[2,1,0],[3,2,0]):
                p=points[order];normal=np.cross(p[1]-p[0],p[2]-p[0]);normal/=np.linalg.norm(normal)
                item.append(np.c_[p,uv[order],np.tile(normal,(3,1))])
            item=np.concatenate(item);mesh[:,3:5]=atlas.uv('marisa/'+face,Image.fromarray(model.texture(face)),mesh[:,3:5])
            mesh=np.concatenate((mesh,item));mesh[:,:3]*=scale
            return rotate_mesh(mesh,P.euler([0,yaw,0]),at)
        mesh[:,:3]*=scale;mesh[:,3:5]=atlas.uv('marisa/'+face,Image.fromarray(model.texture(face)),mesh[:,3:5])
        return rotate_mesh(mesh,P.euler([0,yaw,0]),at)

    def vessel_mesh(self,atlas,scale,at=(0,0,0),yaw=0,tilt=None,food=2):
        V=self.vessel;k=V['aquatic_scale']*scale;rotation=P.euler([0,yaw,0])@(np.eye(3) if tilt is None else tilt)
        mesh=self.prop('marisa_hat_turned',atlas);mesh[:,:3]*=[k,k*V['squash']['bowl'],k];mesh[:,1]+=V['bowl']['freeboard']*k
        mesh[:,5:]/=[1,V['squash']['bowl'],1];mesh[:,5:]/=np.linalg.norm(mesh[:,5:],axis=1)[:,None]
        parts=[rotate_mesh(mesh,rotation,at)]
        for point,radius,name in self.cargo[:food]:
            item=self.prop(name,atlas);bounds=np.array(self.props[name]['bounds']);extent=max(bounds[:,1]-bounds[:,0])
            item[:,:3]-=[bounds[0].mean(),bounds[1,0],bounds[2].mean()];item[:,:3]*=radius*k*math.sqrt(2)*.92/extent
            parts.append(rotate_mesh(item,rotation,rotation@(point*k)+at))
        corners=np.array([[p[0]*k,.48*scale,p[1]*k] for p in V['bowl']['mask']])@rotation.T+at;self.masks.append(corners)
        return parts

    def rescue_frame(self,age):
        lead=32;atlas=Atlas();self.masks=[];meshes=self.vessel_mesh(atlas,1,food=2)
        if age<lead:
            parent=self.seats[0]*self.k;tip=None;clip='boat_alarm_wait';time=age/20;child=self.rescue['feet'];phase='FLOAT'
        else:
            sample=self.rescue_at(age-lead);parent=sample['parent'];tip=sample['tip'];child=sample['child'];clip=sample['clip'];time=sample['time'];phase=sample['phase']
        local_tip=None if tip is None else tip-parent
        meshes.append(self.person(1,clip,time,atlas,self.parent.face(clip,time),parent,hatless=True,tip=local_tip))
        if phase in ('FLOAT','REACH','CONTACT'):
            child_clip,ct=self.distress(age+14);face=self.young.face(child_clip,ct)
        elif phase=='RECOVER':child_clip='water_recover';ct=max(0,(age-lead-self.rescue_at(1000)['end']-3)/20);face=self.young.face(child_clip,ct)
        else:child_clip='idle';ct=0;face='teary'
        meshes.append(self.person(.14,child_clip,ct,atlas,face,child))
        labels={'FLOAT':'赤油身体约56%浸水，实际浮力与扑腾呼救','REACH':'亲油沿真实帽面靠近，原单辫伸出','CONTACT':'同一真实辫梢接触持续6 tick，孩子仍在水面','GRIP':'真正抓托，孩子抓点与辫梢相同','LIFT':'原长辫子绕根抬举，孩子同梢同行','PLACE':'亲油走到实际支撑位，辫梢带子落向真帽面','RELEASE':'已经真实落座，保持接触后再松开','RECOVER':'放稳后才伏低喘息，亲油守在身边'}
        focus=child.copy();focus[1]=max(-1,focus[1])
        return np.concatenate(meshes).reshape(-1,3,8),np.array(atlas.image),dict(at=np.zeros(3),yaw=0,stage=labels[phase],focus=focus,phase=phase)

    def add_rescue_stories(self,stories):
        self.rescue=self.rescue_plan(np.array([5.1,-.56*self.body_height*.14,-15.8]),[])
        sample=self.rescue_at(1000);end=32+sample['end']+56;cue=self.voices
        rescue_cues=[cue(0,'marisa.young.l0596','赤油'),cue(32,'marisa.adult.l0596','亲油'),cue(32+sample['contact'],'marisa.young.l1982','赤油'),cue(32+sample['end'],'marisa.adult.l1927','亲油')]
        stories['rescue']=dict(file='float-and-braid-rescue.gif',poster='rescue-poster.png',end=end,cues=rescue_cues,frames=self.rescue_frame)
        if '--only-rescue' not in sys.argv:self.prepare_recall(stories)

    def emergency_brim(self,r,taken,prefer):
        V=self.vessel;best=None
        for x in np.arange(-V['brim'][0]+r*.8,V['brim'][0]-r*.8+1e-8,.25):
            for z in np.arange(-2,max(-V['brim'][1]+r*.8+.12,V['opening'][2]-3.2)-1e-8,-.25):
                if any(not math.isfinite(self.surface(x+(r*.8)*math.cos(i*math.pi/8),z+(r*.8)*math.sin(i*math.pi/8))) or abs(self.surface(x+(r*.8)*math.cos(i*math.pi/8),z+(r*.8)*math.sin(i*math.pi/8))-V['bowl']['freeboard'])>1e-4 for i in range(17)):continue
                if abs(self.surface(x,z)-V['bowl']['freeboard'])>1e-4:continue
                pressure=0;clear=True
                for other in taken:
                    distance=math.hypot(x-other[0],z-other[2]);total=r+other[3];allowed=1 if len(other)>5 and other[5]>0 else .8
                    if distance<total*allowed+.03:clear=False;break
                    pressure+=max(0,total+.05-distance)
                cost=pressure*80+math.hypot(x-prefer[0],z-prefer[2])
                if clear and (best is None or cost<best[0]):best=(cost,x,V['bowl']['freeboard'],z)
        if best is None:raise RuntimeError('The controlled returning family has no real emergency berth.')
        return np.array(best[1:])

    def pressure_scene(self,positions,sizes,seed):
        """The frozen server's contact reaction and equal-area foot support, not a timed fall."""
        parent=self.seats[0];at=[p.copy() for p in positions];n=len(at)
        contacts=[0]*n;touched=[-100]*n;falling=[0.]*n;reaction=[-100]*n;vertical=[-100]*n
        moved_at=[-100]*n;moved=[0.]*n;push=[np.zeros(3) for _ in at];responsible=[None]*n
        radii=[3.584*s for s in sizes];heights=[self.body_height*s/self.k for s in sizes]
        masses=[max(.001,r*r*h) for r,h in zip(radii,heights)]
        hashes=[]
        for i in range(n):
            value=uuid.uuid5(uuid.NAMESPACE_DNS,f'causal-hat-family:{seed}:{i}').int;value=(value>>64)^(value&((1<<64)-1));value=((value>>32)^value)&0xffffffff
            hashes.append(value-(1<<32) if value>=1<<31 else value)
        order=sorted(range(n),key=lambda i:hashes[i]);frames=[];drops=[]
        def norm(v):
            v=v.copy();v[1]=0;return v/max(1e-9,np.linalg.norm(v))
        def support(i,feet,force):
            radius=radii[i];length=np.linalg.norm(force[[0,2]])
            load=feet+norm(force)*min(radius*.3,heights[i]*.08*min(1,length/.035))
            points=[];bearing=0;exterior=0;highest=-np.inf
            for sample in range(49):
                r=radius*math.sqrt((sample+.5)/49);angle=sample*2.399963229728653
                x=feet[0]+r*math.cos(angle);z=feet[2]+r*math.sin(angle);floor=self.surface(x,z)
                if not math.isfinite(floor):exterior+=1;continue
                highest=max(highest,floor)
                if abs(floor-feet[1])<=.12:bearing+=1;points.append((x,z))
            for sample in range(17):
                x=feet[0] if sample==16 else feet[0]+radius*math.cos(sample*math.pi/8)
                z=feet[2] if sample==16 else feet[2]+radius*math.sin(sample*math.pi/8)
                floor=self.surface(x,z)
                if math.isfinite(floor):
                    highest=max(highest,floor)
                    if abs(floor-feet[1])<=.12:points.append((x,z))
            stable=len(points)>=3
            for sample in range(16):
                if not stable:break
                x=math.cos(sample*math.pi/8);z=math.sin(sample*math.pi/8)
                edge=max((px-feet[0])*x+(pz-feet[2])*z for px,pz in points)
                if (load[0]-feet[0])*x+(load[2]-feet[2])*z>edge-radius*.02:stable=False
            return bearing/49,exterior,highest,stable,not math.isfinite(self.surface(load[0],load[2]))
        for tick in range(220):
            def touch(i,j,force):
                if touched[i]!=tick:contacts[i]=contacts[i]+1 if touched[i]==tick-1 else 1;touched[i]=tick
                responsible[i]=j;push[i]=force.copy()
            def move(i,delta,pusher):
                a=at[i];delta=delta.copy()
                if moved_at[i]!=tick:moved_at[i]=tick;moved[i]=0
                distance=np.linalg.norm(delta[[0,2]]);remaining=max(0,.075-moved[i])
                if distance>0 and remaining<1e-6:return False
                if distance>remaining:delta*=remaining/distance
                next_at=a+delta;_,exterior,highest,stable,outside=support(i,next_at,delta)
                floor=self.surface(next_at[0],next_at[2]);who=pusher if pusher is not None else responsible[i] if touched[i]>=tick-1 else None
                if exterior>0 and not stable and outside:
                    if who is None or contacts[i]<12:return False
                    outward=delta if np.linalg.norm(delta[[0,2]])**2>1e-8 else push[i]
                    drops.append((tick,i,next_at.copy(),outward.copy()));return True
                if not math.isfinite(floor) and not stable:return False
                if distance>0 and highest>a[1]+.08:return False
                for point,r,_ in self.cargo:
                    if abs(a[1]-point[1])<heights[i] and np.linalg.norm((next_at-point)[[0,2]])<radii[i]+r:return False
                if stable:floor=a[1]
                elif not math.isfinite(floor):return False
                if a[1]>floor+.04:
                    if vertical[i]!=tick:
                        vertical[i]=tick;falling[i]=max(-.18*16/self.k,falling[i]-.04*16/self.k);next_at[1]=max(floor,a[1]+falling[i])
                    else:next_at[1]=a[1]
                else:falling[i]=0;next_at[1]=max(a[1],floor)
                moved[i]+=np.linalg.norm(delta[[0,2]]);at[i]=next_at;return True
            def attempt(i,delta,visited,depth=0,pusher=None,allow_reaction=True):
                if i in visited or depth>4:return False
                visited.add(i);a=at[i];proposed=a+delta
                if np.linalg.norm((proposed-parent)[[0,2]])<3.584+radii[i]:
                    normal=norm(a-parent)
                    if np.linalg.norm(normal)**2<.01:normal=np.array([0.,0,-1.])
                    delta=np.array([-normal[2],0,normal[0]])*np.linalg.norm(delta[[0,2]])*(1 if hashes[i]%2==0 else -1);proposed=a+delta
                for j in order:
                    if i==j:continue
                    where=at[j]
                    if a[1]>=where[1]+heights[j]-.1 or a[1]+heights[i]<=where[1]+.1:continue
                    distance=np.linalg.norm((proposed-where)[[0,2]]);current=np.linalg.norm((a-where)[[0,2]])
                    if distance>radii[i]+radii[j]+.04 or distance>=current+.002:continue
                    direction=norm(where-a)
                    if np.linalg.norm(direction)**2<.01:direction=norm(delta)
                    touch(i,j,-direction);touch(j,i,direction)
                    if contacts[j]<12:return False
                    if not attempt(j,direction*min(.075,.035+contacts[j]*.001),visited,depth+1,i,allow_reaction):
                        if allow_reaction and contacts[i]>=12 and reaction[i]!=tick:
                            reaction[i]=tick;brace=support(j,at[j],np.zeros(3))[0]
                            step=min(.035,np.linalg.norm(delta[[0,2]])*masses[j]/(masses[i]+masses[j])*(.35+.65*brace))
                            backward=-direction*step;responsible[i]=j;push[i]=backward.copy();returning=visited-{i}
                            attempt(i,backward,returning,depth+1,j,False)
                        return False
                return move(i,delta,pusher)
            for i in order:
                if touched[i]<tick-2:contacts[i]=0;responsible[i]=None
                delta=parent-at[i];delta[1]=0;safe=3.584+radii[i]+.25
                if (i in (0,3) and tick<100) or at[i][1]>=self.vessel['bowl']['freeboard']-.1:
                    if np.linalg.norm(delta)>safe or at[i][1]>=self.vessel['bowl']['freeboard']-.1:attempt(i,norm(delta)*.035,set())
                if falling[i]<0:move(i,np.zeros(3),None)
                if drops:break
            frames.append([p.copy() for p in at])
            if drops:return frames,drops[0]
        return frames,None

    def prepare_recall(self,stories):
        base=[self.seats[1].copy(),self.seats[2].copy(),self.seats[3].copy()];sizes=[.20,.16,.14]
        taken=[[*self.seats[0],3.584,self.body_height/self.k,1]]+[[*p,r,2*r,2] for p,r,_ in self.cargo]+[self.reservation(p,s) for p,s in zip(base,sizes)]
        own_b=np.array([54.,0,-20.]);prefer=own_b/self.k
        bsize=.24
        try:normal=self.place(3.584*bsize,taken,self.body_space(bsize))
        except RuntimeError:normal=None
        if normal is not None:raise RuntimeError(f'This family still has a normal berth {normal.tolist()}; an emergency contest is not manufactured.')
        berth=self.emergency_brim(3.584*bsize,taken,prefer)
        roster=base+[berth];sizes=sizes+[bsize]
        incoming=[(bsize,own_b,28,berth)]
        seed=16
        safe_frames,safe_drop=self.pressure_scene(roster,sizes,seed)
        (OUT/'safe-four-riders.json').write_text(json.dumps(dict(description='Four ordinary children, fixed actual identities: safe contact aggregation, no staged fall.',sizes=sizes,positions=[p.tolist() for p in roster],frames=[[p.tolist() for p in row] for row in safe_frames],drop=None if safe_drop is None else safe_drop[0]),indent=2),encoding='utf-8')
        growth=json.loads((P.ASSETS/'yukkuri_presentation/family.json').read_text(encoding='utf-8'))['growth']['stages'][1]
        def late_child(nourishment):
            return growth['min_scale']+(growth['max_scale']-growth['min_scale'])*(nourishment-growth['nourishment'][0])/(growth['nourishment'][1]-growth['nourishment'][0])
        a_size,bsize=late_child(.49),late_child(.48)
        infant_positions=[self.seats[2].copy(),self.seats[3].copy()]
        occupied=[[*self.seats[0],3.584,self.body_height/self.k,1]]+[[*p,r,2*r,2] for p,r,_ in self.cargo]+[self.reservation(p,s) for p,s in zip(infant_positions,[.16,.14])]
        standing=[]
        for size in (late_child(.375),late_child(.40)):
            try:seat=self.place(3.584*size,occupied,self.body_space(size))
            except RuntimeError:seat=self.emergency_brim(3.584*size,occupied,np.array([0.,0,-8.]))
            standing.append((seat,size));occupied.append(self.reservation(seat,size))
        try:a_seat=self.place(3.584*a_size,occupied,self.body_space(a_size))
        except RuntimeError:a_seat=self.emergency_brim(3.584*a_size,occupied,np.array([-54.,0,-20.])/self.k)
        occupied.append(self.reservation(a_seat,a_size))
        try:berth=self.place(3.584*bsize,occupied,self.body_space(bsize))
        except RuntimeError:berth=self.emergency_brim(3.584*bsize,occupied,own_b/self.k)
        base=[a_seat]+infant_positions;roster=base+[berth]+[p for p,s in standing];sizes=[a_size,.16,.14,bsize]+[s for p,s in standing]
        incoming=[(bsize,own_b,28,berth)]
        crowd=self.pressure_scene(roster,sizes,seed)
        self.recall=dict(roster=roster,sizes=sizes,pressure=crowd[0],drop=crowd[1],seed=seed)
        dl=self.delivery;hat=dl['Hat'](self.vessel,'bowl');home_pose=dl['Pose']([0,0,0],0,self.k);trips=[]
        for size,start,begin,seat in [(a_size,np.array([-54.,0,-20.]),16,base[0])]+incoming:
            sail=self.sail(start,np.zeros(3),begin,180)
            cutoff=next(i for i,(p,_,_) in enumerate(sail['frames']) if i>=begin and np.linalg.norm(p)<=2.5*16)
            center,yaw,_=sail['frames'][cutoff];R=P.euler([0,yaw,0]);origin=center+R@(self.seats[0]*self.k*size)
            own_pose=dl['Pose'](center,yaw,self.k*size);goal=seat*self.k
            duration=max(dl['solve'](origin,goal,home_pose,hat,.42*12.8*size,self.vessel['delivery'],tmin=self.vessel['delivery']['hop_tmin']),dl['solve'](origin,goal,own_pose,hat,.42*12.8*size,self.vessel['delivery'],tmin=self.vessel['delivery']['hop_tmin']))
            trips.append(dict(size=size,sail=sail,cutoff=cutoff,land=cutoff+duration,arc=dl['arc'](origin,goal,duration,self.vessel['delivery']['gravity']),seat=seat,origin=origin))
        start_crowd=max(p['land'] for p in trips)+2
        if crowd[1] is None:
            self.recall.update(trips=trips,start=start_crowd,released=math.inf,falls=[],who=1,water=0,safe=True)
            cue=self.voices;end=start_crowd+160
            cues=[cue(0,'marisa.adult.l2598','亲油'),cue(8,'marisa.adult.l0596','亲油'),cue(16,'marisa.young.l1916','小子油甲'),cue(28,'marisa.young.l1916','小子油乙')]
            stories['recall']=dict(file='recall-and-crowding.gif',poster='recall-poster.png',end=end,cues=cues,frames=self.recall_frame)
            print('Actual six-rider scene: supported aggregation, no fall; rendering the honest current result.',flush=True)
            return
        self.recall['safe']=False
        drop_tick,who,drop_at,delta=crowd[1];released=start_crowd+drop_tick
        # Actual release impulse followed by the new finite fluid force and vanilla drag/gravity.
        at=drop_at*self.k;velocity=delta/max(1e-8,np.linalg.norm(delta))*.075*16+[0,.015*16,0];falls=[at.copy()];height=self.body_height*sizes[who]
        water_at=None
        for tick in range(48):
            if at[1]<0:
                if water_at is None:water_at=tick
                fraction=min(1,max(0,-at[1]/height));force=max(-.018,min(.032,.005+.045*(fraction-.56)-.08*velocity[1]/16))
                if force>0:force=max(0,min(force,.085-velocity[1]/16))
                velocity[1]=(velocity[1]+force*16)*.8-.005*16;velocity[[0,2]]*=.8
            else:velocity[1]-=.08*16
            at+=velocity;falls.append(at.copy())
        self.recall.update(trips=trips,start=start_crowd,released=released,falls=falls,who=who,water=water_at)
        end=released+48;cue=self.voices
        cues=[cue(0,'marisa.adult.l2598','亲油'),cue(8,'marisa.adult.l0596','亲油'),cue(16,'marisa.young.l1916','小子油甲'),cue(28,'marisa.young.l1916','小子油乙'),cue(released+water_at,'marisa.young.l0596','落水幼体')]
        stories['recall']=dict(file='recall-and-crowding.gif',poster='recall-poster.png',end=end,cues=cues,frames=self.recall_frame)

    def recall_frame(self,age):
        p=self.recall;atlas=Atlas();self.masks=[];tilt=motion('rock',age) if age<44 else np.eye(3)
        meshes=self.vessel_mesh(atlas,1,tilt=tilt,food=8)
        parent_clip='boat_alarm_call' if age<24 else 'boat_alarm_wait' if age<p['start'] else 'boat_reassure'
        parent_time=age/20 if age<p['start'] else (age-p['start'])/20
        actor=self.person(1,parent_clip,parent_time,atlas,self.parent.face(parent_clip,parent_time),np.zeros(3),hatless=True)
        meshes.append(rotate_mesh(actor,tilt,tilt@(self.seats[0]*self.k)))
        frame=p['pressure'][min(max(0,int(age-p['start'])),len(p['pressure'])-1)]
        for i in (1,2):
            if i==p['who'] and age>=p['released']:
                elapsed=age-p['released'];at=p['falls'][min(int(elapsed),len(p['falls'])-1)]
                clip,time=self.distress(elapsed-p['water']) if elapsed>=p['water'] else ('hat_stumble',elapsed/20)
                meshes.append(self.person(p['sizes'][i],clip,time,atlas,self.young.face(clip,time),at))
            else:
                at=p['roster'][i] if age<p['start'] else frame[i];clip='hat_stumble' if age<44 else 'hat_jostle' if p['start']<=age<p['released'] else 'boat_reassure';time=age/20
                actor=self.person(p['sizes'][i],clip,time,atlas,self.young.face(clip,time),np.zeros(3))
                meshes.append(rotate_mesh(actor,tilt,tilt@(at*self.k)))
        for i in range(4,len(p['roster'])):
            if i==p['who'] and age>=p['released']:
                elapsed=age-p['released'];at=p['falls'][min(int(elapsed),len(p['falls'])-1)]
                clip,time=self.distress(elapsed-p['water']) if elapsed>=p['water'] else ('hat_stumble',elapsed/20)
                meshes.append(self.person(p['sizes'][i],clip,time,atlas,self.young.face(clip,time),at))
            else:
                at=p['roster'][i] if age<p['start'] else frame[i];clip='hat_stumble' if age<44 else 'hat_jostle' if p['start']<=age<p['released'] else 'boat_reassure';time=age/20
                actor=self.person(p['sizes'][i],clip,time,atlas,self.young.face(clip,time),np.zeros(3))
                meshes.append(rotate_mesh(actor,tilt,tilt@(at*self.k)))
        for n,trip in enumerate(p['trips']):
            i=0 if n==0 else n+2;size=trip['size']
            if age<trip['land']+1:
                at,yaw,moving=trip['sail']['frames'][min(int(age),trip['cutoff'])]
                meshes.extend(self.vessel_mesh(atlas,size,at,yaw,food=1))
                if age<trip['cutoff']:
                    clip='boat_paddle' if moving else 'boat_answer_call';time=age/20;where=at+P.euler([0,yaw,0])@(self.seats[0]*self.k*size)
                else:clip='boat_board';time=(age-trip['cutoff'])/20;where=np.array(trip['arc'](min(age-trip['cutoff'],trip['land']-trip['cutoff'])));yaw=0
                meshes.append(self.person(size,clip,time,atlas,'teary',where,yaw,hatless=True))
            elif i==p['who'] and age>=p['released']:
                elapsed=age-p['released'];at=p['falls'][min(int(elapsed),len(p['falls'])-1)]
                clip,time=self.distress(elapsed-p['water']) if elapsed>=p['water'] else ('hat_stumble',elapsed/20)
                meshes.append(self.person(size,clip,time,atlas,self.young.face(clip,time),at))
            else:
                at=trip['seat'] if age<p['start'] else frame[i];clip='hat_jostle' if p['start']<=age<p['released'] else 'boat_reassure';time=(age-trip['land'])/20
                meshes.append(self.person(size,clip,time,atlas,self.young.face(clip,time),at*self.k))
        if age<8:stage='发现帽船晃动，亲油受惊'
        elif age<28:stage='亲油真实呼声；两只小子油按不同反应时间答复'
        elif age<min(t['land'] for t in p['trips']):stage='各自帽船按实际桨力、水阻与转速赶回'
        elif age<p['start']:stage='先到孩子清过两帽檐真实跳回，随后收回自己的帽子'
        elif p.get('safe') and age>=p['start']+100:stage='实际结果：六只仍有足够支撑，安全聚拢；本构成没有挤落'
        elif age<p['released']:stage='后续近缘归帽，持续身体接触才递归推移；非计时踢下船'
        else:stage='真实身体足面失去足够支撑后释放；实际落水，再浮着扑腾呼救'
        focus=frame[p['who']]*self.k if age>=p['start'] else np.array([0,2,-6.])
        if age>=p['released']:focus=p['falls'][min(int(age-p['released']),len(p['falls'])-1)]
        return np.concatenate(meshes).reshape(-1,3,8),np.array(atlas.image),dict(at=np.zeros(3),yaw=0,stage=stage,focus=focus,close=age>=p['start'])

    def storyboards(self,stories,shots):
        def ages_for(story):
            end=story['end']
            return sorted(set(list(range(0,min(45,end),2))+list(range(48,end+1,5))+[end]+[q['tick'] for q in story['cues'] if q['tick']<=end]))
        def still(kind,tick):
            story=stories[kind];ages=ages_for(story);age=min(ages,key=lambda t:abs(t-tick))
            if (kind,age) in shots:return shots[kind,age]
            with Image.open(OUT/story['file']) as gif:
                gif.seek(ages.index(age));return gif.convert('RGB').copy()
        def sheet(filename,title,subtitle,panels):
            card=Image.new('RGB',(1800,1070),BG);draw=ImageDraw.Draw(card)
            draw.text((28,18),title,font=P.preview_font(34),fill='#203e48')
            draw.text((30,66),subtitle,font=P.preview_font(20),fill='#4b6b74')
            for i,(kind,tick,label) in enumerate(panels):
                image=still(kind,tick).resize((600,475),Image.Resampling.LANCZOS)
                x=i%3*600;y=110+i//3*448
                card.paste(image.crop((0,52,600,453)),(x,y+38))
                draw.text((x+18,y),str(i+1)+' / '+label,font=P.preview_font(22),fill='#294e59')
            draw.text((28,1035),'当前选用模型的离线参数演出，非游戏录像；保留原体型、真实遮挡与共享接触锚。',font=P.preview_font(19),fill='#4b6b74')
            card.save(OUT/filename)
        p=self.recall;first=min(t['land'] for t in p['trips'])
        sheet('storyboard.png','呼唤、返航与拥挤：当前真实结果','两只较大子油由真实成长曲线导出；此六只构成虽有接触争位，仍保持支撑，没有挤落。',[
            ('recall',8,'亲油发现危险并呼子'),('recall',40,'孩子回应后各自操桨'),('recall',first,'先到孩子实际上帽'),
            ('recall',p['start']+12,'后到归帽，接触争位'),('recall',p['start']+75,'有限反作用，保持支撑'),('recall',p['start']+160,'当前构成安全聚拢')])
        # These panels explicitly join the three separately labelled scenes, not a claimed engine recording.
        sheet('distress-stages.png','受惊、落水与单辫救援','跨三段预览的动作分镜：危险阶段保持害怕；抓前不移动孩子，接触后跟随同一个真实辫梢。',[
            ('rock',6,'受惊压低撑稳'),('recall',p['start']+12,'归帽后拥挤争位'),('rescue',2,'另一个真实落水样本'),
            ('rescue',20,'真实水面扑腾呼救'),('rescue',stories['rescue']['end']-75,'自然长单辫抓托回帽'),('rescue',stories['rescue']['end']-20,'支撑落稳后伏低喘息')])
        still('rescue',stories['rescue']['end']-75).save(OUT/'rescue-poster.png')
        still('recall',p['start']+12).save(OUT/'recall-poster.png')

    @staticmethod
    def voice_cues():
        catalogue=json.loads((P.ASSETS/'dialogue/catalog.json').read_text(encoding='utf-8'));zh=json.loads((P.ASSETS/'lang/zh_cn.json').read_text(encoding='utf-8'))
        ids={row['id']:row for row in catalogue}
        def cue(t,ident,role):
            row=ids[ident];return dict(tick=t,id=ident,role=role,text=zh[row['key']],duration=row['ticks'],file=ident+'.ogg')
        return cue


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


def water_layer(pixels,depth,camera,kind,age,rotation,vessel,mask_quads=None):
    right,up,eye,scale,center=camera
    yy,xx=np.mgrid[:H,:W];sx=(xx+.5-W/2)/scale+center@right;sy=(H/2-yy-.5)/scale+center@up
    inverse=np.linalg.inv(np.array([[right[0],right[2]],[up[0],up[2]]]))
    x=inverse[0,0]*sx+inverse[0,1]*sy;z=inverse[1,0]*sx+inverse[1,1]*sy
    water_depth=x*eye[0]+z*eye[2]-center@eye
    # An unbounded static plane stays continuous when the camera follows a real submerged body.
    wet=water_depth>depth+1e-7
    if mask_quads is not None:
        mask=Image.new('1',(W,H));draw=ImageDraw.Draw(mask)
        for corners in mask_quads:
            polygon=np.c_[W/2+(corners-center)@right*scale,H/2-(corners-center)@up*scale]
            draw.polygon([tuple(p) for p in polygon],fill=1)
        wet&=~np.array(mask,dtype=bool)
    elif kind in ('rock','crowd'):
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


def legacy_main():
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


def causal_caption(scene,image,kind,age,context,cues):
    names={'rock':'轻摇受惊 → 呼子警戒 → 反向撤离 → 渐渐安心','rescue':'真实水面扑腾 → 单辫接触 → 同梢抓托 → 放回帽内','recall':'呼唤返船 → 各自帽船赶回 → 拥挤争位 → 安全聚拢'}
    out=Image.new('RGB',(W,H+132),BG);out.paste(image,(0,84));d=ImageDraw.Draw(out)
    d.text((22,8),names[kind],font=P.preview_font(26),fill='#203e48')
    d.text((22,47),context['stage'],font=P.preview_font(18),fill='#4b6b74')
    d.rounded_rectangle((18,105,152,137),8,fill='#e7f3f4');d.text((26,110),f't = {age/20:.2f} 秒',font=P.preview_font(17),fill='#294e59')
    current=next((q for q in reversed(cues) if q['tick']<=age<q['tick']+q['duration']),None)
    line=f"{current['role']}：{current['text']}" if current else 'GIF 无声；页面可播放同一时序的现有原声录音。'
    d.text((22,H+92),line,font=P.preview_font(19),fill='#294e59')
    d.text((22,H+119),'当前选用模型离线参数演出 · 原体型/真实遮挡 · 非游戏录像，完整AI与碰撞仍待游戏确认',font=P.preview_font(14),fill='#4b6b74')
    return out


def causal_main():
    OUT.mkdir(parents=True,exist_ok=True);scene=CausalScene();shots={}
    cue=scene.voices
    rock_cues=[cue(0,'marisa.adult.l2598','亲油'),cue(8,'marisa.adult.l0596','亲油'),cue(28,'marisa.young.l1916','赤油'),cue(65,'marisa.adult.l0596','亲油'),cue(scene.escape['calm'],'marisa.adult.l6041','亲油')]
    stories={'rock':dict(file='rock-and-flee.gif',poster='rock-poster.png',end=scene.escape['end'],cues=rock_cues,frames=scene.rock_frame)}
    if '--only-rock' not in sys.argv:scene.add_rescue_stories(stories)
    if '--only-rescue' in sys.argv:stories={'rescue':stories['rescue']}
    schedule_note={key:dict(file=s['file'],poster=s['poster'],ticks=s['end'],seconds=s['end']/20,cues=s['cues']) for key,s in stories.items()}
    for kind,story in stories.items():
        if '--only-recall' in sys.argv and kind!='recall':continue
        end=story['end'];ages=sorted(set(list(range(0,min(45,end),2))+list(range(48,end+1,5))+[end]))
        ages=sorted(set(ages+[q['tick'] for q in story['cues'] if q['tick']<=end]));frames=[]
        for index,age in enumerate(ages):
            mesh,texture,context=story['frames'](age)
            target=context['focus'];az=math.radians(context['yaw'])+.10
            close=kind!='recall' or context.get('close',False)
            pixels,depth,camera=rasterize(mesh,texture,az=az,el=.27 if close else .52,span=10.8 if close else 37,
                                           center_y=target[1]+2,center_x=target[0]*.5,center_z=target[2])
            image=causal_caption(scene,water_layer(pixels,depth,camera,'causal',age,np.eye(3),scene.vessel,scene.masks),kind,age,context,story['cues'])
            frames.append(image);shots[kind,age]=image
            if index==1:image.save(OUT/story['poster']);print(OUT/story['poster'],flush=True)
            print(f'{kind}: {index+1}/{len(ages)} tick={age}',flush=True)
        durations=[max(50,(ages[min(i+1,len(ages)-1)]-age)*50) for i,age in enumerate(ages)];durations[-1]=1000
        frames[0].save(OUT/story['file'],save_all=True,append_images=frames[1:],duration=durations,loop=0,disposal=2)
        schedule_note[kind]=dict(file=story['file'],poster=story['poster'],ticks=end,seconds=end/20,cues=story['cues'])
        audio=OUT/'audio';audio.mkdir(exist_ok=True)
        for q in story['cues']:shutil.copyfile(P.ASSETS/'sounds/voice/native'/q['file'],audio/q['file'])
    (OUT/'stories.json').write_text(json.dumps(schedule_note,ensure_ascii=False,indent=2),encoding='utf-8')
    if '--only-rock' not in sys.argv and '--only-rescue' not in sys.argv:scene.storyboards(stories,shots)
    page=Path(__file__).with_suffix('.html').read_text(encoding='utf-8')
    (OUT/'index.html').write_text(page.replace('__STORY_DATA__',json.dumps(schedule_note,ensure_ascii=False).replace('<','\\u003c')),encoding='utf-8')
    print('CAUSAL STORIES:',json.dumps({k:v['seconds'] for k,v in schedule_note.items()}),flush=True)


def current_interactions():
    """One current contact storyboard, sampled from the shared finite boarding and low-tip formulae.

    This is source-driven art production, not an engine run or a successful AI scenario search.
    Each group is an explicitly constructed calm-water parameter sample, with real model sizes.
    """
    OUT.mkdir(parents=True,exist_ok=True);scene=CausalScene();V=scene.vessel
    smooth=lambda q:(lambda t:t*t*(3-2*t))(min(1,max(0,q)))
    rig=runpy.run_path(str(Path(__file__).with_name('hat.py')))
    config=json.loads(Path(__file__).with_name('recipe.json').read_text(encoding='utf-8'))['clips']['water_hat_flip']['hat_transfer']
    panels=[]
    def add(meshes,atlas,label,detail,camera):
        mesh=np.concatenate(meshes).reshape(-1,3,8)
        pixels,depth,view=rasterize(mesh,np.array(atlas.image),**camera)
        picture=water_layer(pixels,depth,view,'current',0,np.eye(3),V,scene.masks)
        panels.append((picture,label,detail));print('Current interaction panel:',len(panels),label,flush=True)
    # A real .25-size juvenile, on the near brim of its own .375-scale hat.
    # The .23-block foot-to-foot gap is below HatBoarding.maximumGap=.48; only 4..10 ticks are considered.
    size=.25;own=np.array([0.,0,-20.1]);source=own+np.array([0,V['bowl']['freeboard'],8.75])*scene.k*size
    target=np.array([0,V['bowl']['freeboard'],-8.75])*scene.k
    distance=np.linalg.norm((target-source)[[0,2]])/16;gravity=V['delivery']['gravity']/16
    ticks=next(t for t in range(4,11) if distance/t<=.05+.06*size and 0<=(target[1]-source[1])/16/t+gravity*t*.5<=.22+.14*size)
    for i,t in enumerate((0,ticks*.5,ticks)):
        atlas=Atlas();scene.masks=[];meshes=scene.vessel_mesh(atlas,1,food=2)+scene.vessel_mesh(atlas,size,own,food=0)
        meshes.append(scene.person(1,'boat_alarm_wait',.5,atlas,'teary',scene.seats[0]*scene.k,hatless=True))
        where=source+(target-source)*t/ticks;where[1]+=V['delivery']['gravity']*.5*t*(ticks-t)
        meshes.append(scene.person(size,'water_hat_grip' if i==0 else 'water_hat_climb',.25 if i==0 else .35+.3*t/ticks,atlas,'teary',where,180,True))
        add(meshes,atlas,('① 先走到两帽的近缘','② 受限短跳，保持实际重力','③ 真正落到近檐支撑')[i],
            ('源帽足点与目标帽面均取真实几何',f'水平约 {distance:.2f} 格 · {ticks} tick，不飞越远帽','落座后才回收原帽；并非远处直接跳进帽井')[i],
            dict(az=1.9,el=.38,span=8.5,center_y=2,center_z=-14.7))
    # One planned deployment in empty calm water, using the server's first fixed reachable rim candidate.
    child=np.array([0.,-.56*scene.body_height*size,0.]);distance=(.8*size*.5+1.05*size*.5+.03)*16
    brim=[]
    for i in range(32):
        angle=i*math.pi/16;dx,dz=math.cos(angle),math.sin(angle);extent=0
        while math.isfinite(scene.surface(dx*extent,dz*extent)) and extent<64:extent+=.125
        brim.append(np.array([dx*max(0,extent-.125),V['bowl']['freeboard'],dz*max(0,extent-.125)]))
    root=child+scene.braid_root*size;roll=P.euler([0,0,110]);planned=None
    for i in range(8):
        angle=math.pi+i*math.pi/4;center=np.array([math.sin(angle)*distance,0,-math.cos(angle)*distance])
        if any(np.linalg.norm(center+roll@(p*scene.k*size)-root)<=scene.braid_length*size+.02*16 for p in brim):planned=center;break
    if planned is None:raise RuntimeError('No finite natural-braid deployment endpoint in this single calm-water art sample.')
    relative=(planned-child)/size;anchor=np.array(V['anchor_worn']);Mv=np.array([[0,0,-1],[0,-1,0],[-1,0,0.]])
    transform=roll@np.diag([scene.k,scene.k*V['squash']['bowl'],scene.k])@Mv
    destination=np.eye(4);destination[:3,:3]=transform;destination[:3,3]=relative+roll@np.array([0,V['bowl']['freeboard']*scene.k,0])-transform@anchor
    atlas=Atlas();scene.masks=[]
    meshes=[scene.person(size,'water_hat_flip',.8*.7,atlas,'teary',child,hat_transfer=(destination,.7,config))]
    add(meshes,atlas,'④ 原帽离头，连续移到固定端点','同一帽骨；18 tick 后才生成该物理帽，失败反向退回',dict(az=.9,el=.32,span=5.2,center_y=1,center_z=1))
    atlas=Atlas();scene.masks=[];u=.5;tilt=P.euler([0,0,110*(1-smooth(u))])@P.euler([7*math.sin(math.pi*u),0,0])
    meshes=scene.vessel_mesh(atlas,size,planned,tilt=tilt,food=0)
    meshes.append(scene.person(size,'water_gasp',.5,atlas,'cry',child,180,True))
    add(meshes,atlas,'⑤ 同一真帽翻为船，仍在泡水','部署20 tick；旧翻覆帽则需锁定同一檐点连续扶正',dict(az=1.3,el=.38,span=5.2,center_y=1,center_z=planned[2]*.5))
    # A near-edge own-hat climb follows HatBoarding's two smoothstep segments, never its old flight arc.
    r=3.584;seat=np.array([-V['brim'][0]+r+round((V['brim'][0]-r)/.25)*.25,V['bowl']['freeboard'],-V['brim'][1]+r])
    to=planned+seat*scene.k*size;crest=np.array([child[0],max(child[1],to[1],V['bowl']['freeboard']*scene.k*size+.015*16),child[2]])
    at=crest+(to-crest)*smooth(.5)
    atlas=Atlas();scene.masks=[];meshes=scene.vessel_mesh(atlas,size,planned,food=0)
    meshes.append(scene.person(size,'water_hat_climb',.65+.55*.5,atlas,'teary',at,180,True))
    add(meshes,atlas,'⑥ 贴檐抓稳后，实际爬入近支撑位','GRIP 8 → LIFT 16 → SETTLE 16；耗力、可滑脱、入水掉血',dict(az=2.7,el=.38,span=5.2,center_y=1.3,center_z=planned[2]*.5))
    # Shared BraidRescueGeometry.lowTip/lowArc, with known supported parent/child placements.
    wet=np.array([5.1,-.56*scene.body_height*.14,-15.8]);offset=np.array([0,scene.body_height*.14*scene.rig['grip_height'],0])
    pickup=np.array([-.6012007706195452,-.4863,-1.6741996084474984])*scene.k
    placement=np.array([-.400923440251366,-.4863,-.5336705508019405])*scene.k
    seat=np.array([.38346,-.4863,-5.50824])*scene.k
    atlas=Atlas();scene.masks=[];boat=np.array([0.,0,26.]);meshes=scene.vessel_mesh(atlas,1,boat,food=2)
    meshes.append(scene.person(1,'boat_paddle',.4,atlas,'teary',boat+scene.seats[0]*scene.k,hatless=True))
    meshes.append(scene.person(.14,'water_struggle',.2,atlas,'cry',wet))
    add(meshes,atlas,'⑦ 亲油先真正操桨划近落水孩子','接近目标是孩子ID；距离不够不能隔空伸辫救起',dict(az=1.1,el=.42,span=25,center_y=2,center_z=5))
    atlas=Atlas();scene.masks=[];meshes=scene.vessel_mesh(atlas,1,food=2);tip=wet+offset
    meshes.append(scene.person(1,'braid_rescue_reach',1.05,atlas,'teary',pickup,hatless=True,tip=tip-pickup))
    meshes.append(scene.person(.14,'idle',0,atlas,'teary',wet))
    add(meshes,atlas,'⑧ 靠到真实辫长内，接触后才抓托','既有发梢接触点；孩子抓前仍在实际水面',dict(az=1.0,el=.33,span=12,center_x=2,center_y=2,center_z=-9))
    def low_tip(root,direction,height):
        dy=height-root[1];xz=direction.copy();xz[1]=0;xz/=np.linalg.norm(xz)
        return root+xz*math.sqrt(scene.braid_length**2-dy*dy)+[0,dy,0]
    clear=max(wet[1],seat[1]);samples=max(1,math.ceil(np.linalg.norm(seat-wet)/(.025*16)))
    for q in np.linspace(0,1,samples+1):
        local=(wet+(seat-wet)*q)/scene.k;floor=scene.foot(local[0],local[2],3.584*.14)
        if math.isfinite(floor):clear=max(clear,floor*scene.k)
    height=clear+.015*16+offset[1]
    from_dir=tip-(pickup+scene.braid_root);to_dir=seat+offset-(placement+scene.braid_root)
    a=math.atan2(from_dir[2],from_dir[0]);b=math.atan2(to_dir[2],to_dir[0]);angle=a+math.atan2(math.sin(b-a),math.cos(b-a))*smooth(.5)
    tip=low_tip(placement+scene.braid_root,np.array([math.cos(angle),0,math.sin(angle)]),height)
    atlas=Atlas();scene.masks=[];meshes=scene.vessel_mesh(atlas,1,food=2)
    meshes.append(scene.person(1,'braid_rescue_hold',1,atlas,'teary',placement,hatless=True,tip=tip-placement))
    meshes.append(scene.person(.14,'idle',0,atlas,'teary',tip-offset))
    add(meshes,atlas,'⑨ 同梢贴近实檐低位托回，再短降放稳','只到足面最高实檐 + 0.015 格；无高举或孩子飞行弧线',dict(az=1.0,el=.35,span=11,center_x=2,center_y=2.5,center_z=-8))
    sheet=Image.new('RGB',(1800,1640),BG);draw=ImageDraw.Draw(sheet)
    draw.text((28,15),'近檐登船、自帽自救与亲油低位托回',font=P.preview_font(36),fill='#203e48')
    draw.text((30,67),'当前模型 / 原体型 / 共享物理曲线的离线动作分镜 · 未启动游戏、未实测，不代表AI已自动成功',font=P.preview_font(21),fill='#4b6b74')
    for i,(image,label,detail) in enumerate(panels):
        x=i%3*600;y=110+i//3*490;sheet.paste(image.resize((600,396),Image.Resampling.LANCZOS),(x,y+66))
        draw.text((x+15,y),label,font=P.preview_font(21),fill='#294e59')
        draw.text((x+15,y+34),detail,font=P.preview_font(16),fill='#4b6b74')
    draw.text((28,1598),'每格独立取景，保留真实遮挡；成功仍取决于空间、持续接触、体力与伤害，可滑脱或来不及脱水而死亡。',font=P.preview_font(20),fill='#4b6b74')
    sheet.save(OUT/'current-interactions.png')
    template=Path(__file__).with_suffix('.html');shutil.copyfile(template,OUT/'index.html')
    print(OUT/'current-interactions.png',flush=True)


if __name__=='__main__':
    if any(flag in sys.argv for flag in ('--only-rock','--only-rescue','--only-recall')):causal_main()
    else:current_interactions()
