"""Current runtime hat, Marisa meshes and poses, rendered offline with NumPy/Pillow only.

Rocking/capsize curves, shared pivot, ledge packing and release instant mirror the Java
implementation. Post-release water drag/escape paths are illustrative, not engine replay.
No browser, game, Java compilation or asset export is invoked.
"""
from pathlib import Path
import json, math, shutil
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


def motion(kind,age,side=-1,amplitude=8):
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
        self.sizes=[1,.27,.20,.14]
        self.seats=[np.array([0,V['bowl']['owner_y'],max(0,V['opening'][3]-5.9/self.k)])]
        self.cargo=[]
        for i in range(8):
            left=i%2==0;inner=V['well'][0 if left else 1];outer=V['opening'][0 if left else 1]
            radius=min(.3,abs(outer-inner)*.30)
            at=np.array([outer+(1 if left else -1)*radius*1.1,self.ledge,.5-(i//2)*(abs(V['well'][2])-.8)/3])
            self.cargo.append((at,radius,('oil_grain_regular','food_scrap','food_mash_serving')[i%3]))
        taken=[[*self.seats[0],3.584,6.4,1]]+[[*p,r,2*r,2] for p,r,_ in self.cargo]
        for size in self.sizes[1:]:
            r=3.584*size;seat=self.place(r,taken);self.seats.append(seat);taken.append([*seat,r])
        self.props={}
        for name in ('marisa_hat_turned','oil_grain_regular','food_scrap','food_mash_serving'):
            self.props[name]=json.loads((P.ASSETS/'yukkuri_props'/f'{name}.json').read_text(encoding='utf-8'))
        self.pose_cache={}

    def surface(self,x,z):
        V=self.vessel
        def inside(r):return r[0]<=x<=r[1] and r[2]<=z<=r[3]
        if inside(V['well']):return self.floor
        if inside(V['opening']):return self.ledge
        if any(inside(b) for b in V['brim_rects']):return V['bowl']['freeboard']
        return float('nan')

    def place(self,r,taken):
        x0,x1,z0,z1=self.vessel['opening'];best=None
        for i in range(max(0,math.floor((x1-x0-2*r)/.25+1e-9))+1):
            x=x0+r+i*.25
            for j in range(max(0,math.floor((z1-z0-2*r)/.25+1e-9))+1):
                z=z0+r+j*.25
                if z>.5:continue
                h=max(self.surface(x,z),*[self.surface(x+r*math.cos(a*math.pi/4),z+r*math.sin(a*math.pi/4)) for a in range(8)])
                if not math.isfinite(h) or abs(self.surface(x,z)-h)>1e-4:continue
                if any(len(s)>5 and s[5]>0 and math.hypot(x-s[0],z-s[2])<r+s[3] for s in taken):continue
                overlap=sum(max(0,1.1*(r+s[3])-math.hypot(x-s[0],z-s[2])) for s in taken)
                cost=100*overlap+(0 if h>self.floor+1e-6 else 8)+math.hypot(x,z-(z0+self.vessel['well'][2])*.5)
                if best is None or cost<best[0]-1e-9:best=(cost,x,h,z)
        if best is None:raise RuntimeError('No supported preview berth for the selected real body size.')
        return np.array(best[1:])

    def actor(self,index,clip,time,atlas,face):
        model=self.parent if index==0 else self.young
        duration=model.clips[clip]['duration'];time=time%duration
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
                # Existing water-escape jumping adds upward intent; no safety surface is created.
                velocity[1]+=.04*16
            else:velocity[1]-=.08*16
            at+=velocity
        return at+velocity*(ticks-int(ticks))

    def build(self,kind,age):
        atlas=Atlas();meshes=[];rotation=motion(kind,age);released=kind=='cap' and age>=9
        hat=self.prop('marisa_hat_turned',atlas)
        hat[:,:3]*=np.array([self.k,self.k*self.vessel['squash']['bowl'],self.k]);hat[:,1]+=self.vessel['bowl']['freeboard']*self.k
        hat[:,5:]/=np.array([1,self.vessel['squash']['bowl'],1]);hat[:,5:]/=np.linalg.norm(hat[:,5:],axis=1)[:,None]
        meshes.append(rotate_mesh(hat,rotation))
        for i,size in enumerate(self.sizes):
            clip='boat_steady' if i==0 else 'hat_cradle';face='surprise' if i==0 else 'teary'
            if kind=='rock' and age>=44:clip='boat_drift' if i==0 else 'hat_cradle';face='closed_smile'
            actor=self.actor(i,clip,age/20,atlas,face)
            if released:
                at,velocity=self.release(i);at=self.drift(at,velocity,age-9)
                # After the real release, no boat transform applies to body or origin.
                actor=self.actor(i,'tremble',age/20,atlas,'teary')
                t=age+i*.31
                body_rotation=P.euler([0,0,math.sin(t*.65)*6])@P.euler([-4+math.sin(t*.43)*3,0,0])
                meshes.append(rotate_mesh(actor,body_rotation,at))
            else:meshes.append(rotate_mesh(rotate_mesh(actor,np.eye(3),self.seats[i]*self.k),rotation))
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


def rasterize(vertices,texture,az=.55,el=.65,span=22,center_y=3):
    """The existing runtime/render.js orthographic depth renderer, vectorized into NumPy pixel batches."""
    s,c,se,ce=math.sin(az),math.cos(az),math.sin(el),math.cos(el)
    right=np.array([c,0,s]);up=np.array([-s*se,ce,c*se]);eye=np.array([s*ce,se,-c*ce]);scale=H/(2*span)
    pos=vertices[:,:,:3].copy();pos[:,:,1]-=center_y
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
    return pixels.reshape(H,W,3),depth.reshape(H,W),(right,up,eye,scale,center_y)


def water_layer(pixels,depth,camera,kind,age,rotation,vessel):
    right,up,eye,scale,cy=camera
    yy,xx=np.mgrid[:H,:W];sx=(xx+.5-W/2)/scale;sy=(H/2-yy-.5)/scale+cy*up[1]
    inverse=np.linalg.inv(np.array([[right[0],right[2]],[up[0],up[2]]]))
    x=inverse[0,0]*sx+inverse[0,1]*sy;z=inverse[1,0]*sx+inverse[1,1]*sy
    water_depth=x*eye[0]-cy*eye[1]+z*eye[2]
    wet=(np.abs(x)<75)&(np.abs(z)<70)&(water_depth>depth+1e-7)
    if kind=='rock':
        # Project the tilted depth-only opening, after entity colours; skip water hidden behind it.
        corners=np.array([[p[0]*1.5,.48,p[1]*1.5] for p in vessel['bowl']['mask']])@rotation.T
        polygon=np.c_[W/2+corners@right*scale,H/2-(corners-[0,cy,0])@up*scale]
        mask=Image.new('1',(W,H));ImageDraw.Draw(mask).polygon([tuple(p) for p in polygon],fill=1)
        wet&=~np.array(mask,dtype=bool)
    ripple=(np.sin(x*.22+z*.17+age*.025)> .975)&(np.cos(z*.31-x*.04)>.15)
    water=np.zeros_like(pixels)+[123,187,203];water[ripple]=[171,218,224]
    occupied=np.isfinite(depth);alpha=np.where(occupied,.52,1.0)
    pixels[wet]=(pixels[wet]*(1-alpha[wet,None])+water[wet]*alpha[wet,None]).astype(np.uint8)
    return Image.fromarray(pixels)


def caption(image,kind,age):
    out=Image.new('RGB',(W,H+100),BG);out.paste(image,(0,88));d=ImageDraw.Draw(out)
    if kind=='rock':title='轻摇帽船 → 亲油稳住、幼体受惊 → 摇晃衰减';subtitle='真实帽子、乘员和八份食物，共绕同一船中心；单次轻摇不等于翻覆。'
    else:title='连续倾倒 → 第 9 tick 真实离船 → 落水挣扎';subtitle='释放瞬间按当前源码；离船后不再跟船旋转，后续静水运动仅为动作示意。'
    d.text((24,10),title,font=P.preview_font(27),fill='#203e48')
    d.text((24,49),subtitle,font=P.preview_font(18),fill='#4b6b74')
    d.rounded_rectangle((20,110,152,142),8,fill='#e7f3f4');d.text((29,114),f't = {age/20:.2f} 秒',font=P.preview_font(17),fill='#294e59')
    d.text((24,H+76),'当前运行模型离线渲染 · 非游戏实录 · 未模拟完整碰撞、导航与水伤过程',font=P.preview_font(16),fill='#46636c')
    return out


def main():
    OUT.mkdir(parents=True,exist_ok=True);scene=Scene();shots={}
    schedules={'rock':list(range(0,45,2))+[48,52,56],'cap':list(range(0,17))+list(range(18,35,2))}
    for kind,ages in schedules.items():
        frames=[]
        for n,age in enumerate(ages):
            mesh,texture,rotation=scene.build(kind,age)
            pixels,depth,camera=rasterize(mesh,texture,span=23 if kind=='cap' else 20,center_y=2)
            image=caption(water_layer(pixels,depth,camera,kind,age,rotation,scene.vessel),kind,age)
            frames.append(image);shots[kind,age]=image
            if kind=='rock' and age==2:
                image.save(OUT/'rock-poster.png');print(OUT/'rock-poster.png',flush=True)
            if kind=='cap' and age==8:image.save(OUT/'capsize-poster.png')
            print(f'{kind}: {n+1}/{len(ages)} (tick {age})',flush=True)
        filename='rock-and-steady.gif' if kind=='rock' else 'capsize-and-struggle.gif'
        durations=[max(50,(ages[min(i+1,len(ages)-1)]-age)*50) for i,age in enumerate(ages)];durations[-1]=850
        frames[0].save(OUT/filename,save_all=True,append_images=frames[1:],duration=durations,loop=0,disposal=2)
    shots['rock',2].save(OUT/'rock-poster.png');shots['cap',8].save(OUT/'capsize-poster.png')
    sheet=Image.new('RGB',(1800,1025),BG);d=ImageDraw.Draw(sheet)
    d.text((30,15),'帽船：同中心摇晃、连续翻覆与落水',font=P.preview_font(36),fill='#203e48')
    d.text((30,65),'当前模型 / 亲油 100% · 子油 27%、20% · 赤油 14% / 八份真实库存样本',font=P.preview_font(22),fill='#4b6b74')
    panels=[('rock',2,'① 轻摇：全家与库存一起倾动'),('rock',16,'② 亲油稳身，幼体保持低坐姿'),('rock',52,'③ 逐渐停稳，不靠点击翻船'),('cap',6,'④ 帽体连续倾倒，尚未离船'),('cap',9,'⑤ 第 9 tick：按实际座位释放'),('cap',26,'⑥ 离船后：水中挣扎动作示意')]
    for i,(kind,age,label) in enumerate(panels):
        x=i%3*600;y=110+i//3*440
        tile=shots[kind,age].resize((600,456),Image.Resampling.LANCZOS)
        sheet.paste(tile.crop((0,60,600,438)),(x,y+38));d.text((x+20,y),label,font=P.preview_font(21),fill='#294e59')
    d.text((30,995),'离线软件渲染，不是游戏实录；释放后路线不代表完整引擎物理或救援结果。',font=P.preview_font(18),fill='#4b6b74')
    sheet.save(OUT/'storyboard.png');sheet.resize((1200,683),Image.Resampling.LANCZOS).save(OUT/'cover.png')
    cabin_detail(scene)
    # An honest short reference for the page, not an acceptance/check report.
    (OUT/'motion-notes.txt').write_text('当前源码离线动作预览，非游戏实录。\n单次轻摇A=8°，指数衰减，44tick；倾倒16tick至110°，第9tick释放。\n亲油无孕，静水，初始船速度为0；乘员与货物的释放坐标及初速依当前源码。\n释放后的水阻、跳水逃生和位置仅作短时示意，未运行游戏碰撞、AI、生命结算或救援。\n幼体座位镜像当前HatVessel.place，食物按8份真实库存样本逐份占位；不是假装地图中已有这些实体或物品。\n',encoding='utf-8')
    template=Path(__file__).with_suffix('.html')
    if template.exists():shutil.copyfile(template,OUT/'index.html')
    print(OUT/'storyboard.png',flush=True)


def cabin_detail(scene):
    """A closer front-side camera, retaining every real occluder and all original berth/size values."""
    mesh,texture,rotation=scene.build('rock',0)
    pixels,depth,camera=rasterize(mesh,texture,az=-.32,el=.43,span=11.7,center_y=.3)
    image=water_layer(pixels,depth,camera,'rock',0,rotation,scene.vessel)
    card=Image.new('RGB',(W,H+100),BG);card.paste(image,(0,88));draw=ImageDraw.Draw(card)
    draw.text((24,10),'帽内局部 · 实际座位、实际体型与两侧储粮',font=P.preview_font(28),fill='#203e48')
    draw.text((24,50),'仅拉近前侧相机；亲油头部可出画，帽檐和幼体帽子保留真实遮挡。',font=P.preview_font(18),fill='#4b6b74')
    draw.text((24,H+76),'八份真实库存样本，未放大食物或幼体 · 当前模型离线渲染，非游戏实录',font=P.preview_font(16),fill='#46636c')
    card.save(OUT/'cabin-detail.png');print(OUT/'cabin-detail.png',flush=True)


if __name__=='__main__':main()
