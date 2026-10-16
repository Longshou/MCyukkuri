"""Current models, exported clips and shared brim solver, rendered offline without a game or browser.

The six panels and looping GIF illustrate the server event; they are not a gameplay recording.
All generated geometry, images and the Node runner stay in .local/previews/first-voyage.
"""
from pathlib import Path
import argparse, io, json, math, runpy, subprocess, zipfile
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import preview as P
from aquatic_family_preview import vertices



def plane(x0, x1, z0, z1, y, color):
    v = np.array([[x0,y,z0],[x0,y,z1],[x1,y,z1],[x1,y,z0]])
    return np.concatenate([np.c_[v[idx], np.tile([-1-color,0,0,1,0], (3,1))] for idx in ([0,1,2],[0,2,3])])


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--node', default='node')
    ap.add_argument('--minecraft-resources', type=Path, required=True,
                    help='Local Minecraft client resource JAR used only for the stick sprite.')
    ap.add_argument('--quick', action='store_true',
                    help='Render a compact 24-frame GIF while retaining all six voyage phases.')
    args = ap.parse_args(); out=P.ROOT/'.local/previews/first-voyage'; out.mkdir(parents=True, exist_ok=True)
    data=json.loads((P.ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
    V=json.loads((P.ASSETS/'yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))
    dl=runpy.run_path(str(P.ROOT/'standards/yukkuri-library/interactions/marisa-hat-vessel/delivery.py'))
    hat=dl['Hat'](V,'bowl'); k=V['aquatic_scale']; D=V['delivery']
    body=json.loads((P.ASSETS/'yukkuri_presentation/body_geometry.json').read_text(encoding='utf-8'))['variants']['marisa']
    width=body['bounds'][0][1]-body['bounds'][0][0]
    parent=P.Posed('marisa',data,True)
    young=P.Posed('marisa',data,worn_hat_scale=k)
    pupil=P.Posed('marisa',data,worn_hat_scale=k); sailor=P.Posed('marisa',data,True)
    owner=np.array([0,V['bowl']['owner_y']*k,V['opening'][3]*k-body['bounds'][2][1]])
    def places(sizes):
        taken=[]; result=[]
        for size in sizes: # same descending size order as the server
            r=dl['radius'](D,width*size)/k; brim=dl['brim_place'](hat,D,r,taken)
            seat=brim if brim is not None else dl['place'](hat,D,r,'day',taken=taken)
            taken.append(list(seat)+[r]); result.append((size,np.array(seat)*k,brim is not None))
        return result
    small=places([.25,.23,.22,.21,.20]); family=places([.35,.23,.22,.21,.20])
    others=[list(at/k)+[dl['radius'](D,width*ss)/k] for ss,at,_ in family[1:]]
    launch=dl['brim_place'](hat,D,dl['radius'](D,width*.35)/k,others,launch=True)
    assert launch is not None
    family[0]=(.35,np.array(launch)*k,True)
    size,berth,_=family[0]; dock=berth+np.array([0,0,-18.174*size]);dock[1]=0
    (out/'seat-data.json').write_text(json.dumps({'small':[(s,p.tolist(),b) for s,p,b in small],
        'first_voyage':[(s,p.tolist(),b) for s,p,b in family],'dock':dock.tolist()},indent=2),encoding='utf-8')
    labels=[('01  五只小子油，共住父油帽船','体型 20%～25% · 三只帽檐、两只帽内'),
            ('02  从父油帽檐，放下自己的帽子','初航子油 35% · 其余四只继续留船'),
            ('03  小帽浮起，第一次试着坐稳','父油全程在水上 · 留着回船的位置'),
            ('04  划出一小段，父油停船守望','真实木棍作桨 · 练习短程往返'),
            ('05  转向，回到父油帽船旁','兄弟姐妹仍在船上 · 不需要上岸'),
            ('06  跳回原位，收帽，再接受鼓励','完成往返才记初航 · 全家继续水上生活')]
    colors=[[116,184,204,255],[183,206,145,255],[227,215,178,255],[177,222,230,255]]

    def scene(index,time,progress=1):
        texture=Image.new('RGBA',(512,512)); mesh=[]; holes=[]
        def actor(model,clip,t,scale,yaw,pos,atlas=(0,0)):
            mesh.append(vertices(model,clip,t,scale,yaw,pos,atlas))
            texture.paste(Image.fromarray(model.texture(model.face(clip,t))),(int(atlas[0]*512),int(atlas[1]*512)))
        def boat(model,clip,t,scale,center,atlas,yaw=0,empty=False):
            at=np.array(center)+P.euler([0,yaw,0])@owner*scale
            if empty:
                mesh.append(vertices(model,clip,t,scale,yaw,at,atlas)[-model.boat:])
                texture.paste(Image.fromarray(model.texture(model.face(clip,t))),(int(atlas[0]*512),int(atlas[1]*512)))
            else:actor(model,clip,t,scale,yaw,at,atlas)
            corners=np.array([[x,0,z] for x in V['opening'][:2] for z in V['opening'][2:]])*k*scale
            corners=corners@P.euler([0,yaw,0]).T+center
            holes.append([corners[:,0].min(),corners[:,0].max(),corners[:,2].min(),corners[:,2].max()])
            return at
        boat(parent,'boat_nuzzle' if index==5 else 'boat_drift',time%2.4,1,[0,0,0],(0,0))
        seats=small if index==0 else family[1:]
        for n,(ss,at,brim) in enumerate(seats):
            actor(young,'hat_brim' if brim else 'hat_cradle',time%3,ss,0,at,(.5,0))
        if index==1:
            actor(pupil,'hat_off_self_aq',min(time,1.8),size,0,berth,(.5,.5))
        elif index>=2:
            center=dock.copy()
            if index==3:center[2]-=32*progress
            if index==4:center[2]-=8+32*(1-progress)
            if index==5:center[2]-=8
            yaw=90 if index==2 else 180 if index>=4 else 0
            clip='boat_first_balance' if index==2 else 'boat_paddle'
            if index==5:
                if progress<.65:
                    # Same arc clears both the small hat and the father's raised brim.
                    at=boat(sailor,'boat_drift',0,size,center,(.5,.5),180,True)
                    r=dl['radius'](D,width*size)
                    pose=dl['Pose']([0,0,0],0,k); ownpose=dl['Pose'](center,180,k*size)
                    duration=max(dl['solve'](at,berth,pose,hat,r,D,tmin=D['hop_tmin']),
                                 dl['solve'](at,berth,ownpose,hat,r,D,tmin=D['hop_tmin']))
                    arc=dl['arc'](at,berth,duration,D['gravity']);pos=np.array(arc(duration*progress/.65))
                    mesh.append(vertices(sailor,'boat_board',time%.8,size,0,pos,(.5,.5))[:-sailor.boat])
                else:actor(pupil,'shiawase',time%2,size,0,berth,(.5,.5))
            else:
                at=boat(sailor,clip,min(time,2) if index==2 else time%1.6,size,center,(.5,.5),yaw)
                if index in (3,4):
                    from aquatic_family_preview import item_quad
                    tip=next(i for i,b in enumerate(sailor.model['bones']) if b['name']=='hand_right_tip')
                    m=sailor.last_matrices[tip].copy(); pv=np.array(sailor.model['bones'][tip]['pivot'])
                    m[:3,3]=(m@np.r_[pv,1])[:3]+m[:3,:3]@np.array([0,-.22*16,0])
                    m[:3,:3]=m[:3,:3]@P.euler([0,0,45]); world=np.eye(4);world[:3,:3]=P.euler([0,yaw,0])*size;world[:3,3]=at
                    mesh.append(item_quad(world@m,.85));texture.paste(stick,(0,256))
        edge=28
        # Water with dry openings cut around the physical hats, matching the game's water mask.
        xs=sorted(set([-80,80]+[v for h in holes for v in h[:2]])); zs=sorted(set([-85,edge]+[v for h in holes for v in h[2:] if -85<v<edge]))
        for x0,x1 in zip(xs,xs[1:]):
            for z0,z1 in zip(zs,zs[1:]):
                x,z=(x0+x1)/2,(z0+z1)/2
                if not any(h[0]<x<h[1] and h[2]<z<h[3] for h in holes):mesh.append(plane(x0,x1,z0,z1,-.02,0))
        mesh.extend([plane(-40,40,edge,edge+3,1.8,2),plane(-40,40,edge+3,45,1.8,1)])
        combined=np.concatenate(mesh)
        # Move the camera's framing center along the practice course without changing relative placement.
        if index>=2:combined[:,2]+=18 if index in (3,4) else 10
        return combined.astype('<f4'),texture

    # Native item texture from the supplied local resources JAR, never fetched or copied into runtime.
    with zipfile.ZipFile(args.minecraft_resources) as z:
        stick = Image.open(io.BytesIO(z.read('assets/minecraft/textures/item/stick.png'))).convert('RGBA').resize((64,64), Image.Resampling.NEAREST)
    runner=out/'render.cjs'
    runner.write_text("const fs=require('fs');\n"+(P.ROOT/'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf-8')+'''
    const dir=process.argv[2], tasks=JSON.parse(fs.readFileSync(dir+'/tasks.json'));
    for(const task of tasks){
      const raw=fs.readFileSync(dir+'/'+task.id+'.bin'), v=new Float32Array(raw.buffer,raw.byteOffset,raw.length/4);
      const tex=fs.readFileSync(dir+'/'+task.id+'.rgba');
      const img=renderModel(v,tex,720,490,.55,.53,task.span,{centerY:task.cy,textureSize:512,colors:task.colors});
      fs.writeFileSync(dir+'/'+task.id+'-render.rgba',img);
    }
    ''',encoding='utf-8')
    tasks=[]
    def add(ident,index,time,progress=1):
        mesh,tex=scene(index,time,progress); mesh.tofile(out/f'{ident}.bin');(out/f'{ident}.rgba').write_bytes(tex.tobytes())
        tasks.append(dict(id=ident,span=20 if index<2 or index==5 else 27,cy=4,colors=colors))
    for i,t in enumerate([.45,.50,1.15,.75,.45,.5]):add(f'panel-{i}',i,t,.8 if i in (3,4) else 1)
    if args.quick:
        stages = [(phase, frame * (.6 if phase in (1, 2) else .4), frame / 3 if phase >= 3 else 1)
                  for phase in range(6) for frame in range(4)]
    else:
        stages=[(0,i*.16,1) for i in range(14)]
        stages += [(1,i*.2,1) for i in range(10)]+[(2,i*.2,1) for i in range(10)]
        stages += [(3,i*.16,i/11) for i in range(12)]+[(4,i*.16,i/11) for i in range(12)]+[(5,i*.16,min(1,i/8)) for i in range(16)]
    for f,(i,t,q) in enumerate(stages):add(f'frame-{f}',i,t,q)
    (out/'tasks.json').write_text(json.dumps(tasks),encoding='utf-8');subprocess.run([args.node,str(runner),str(out)],check=True)
    font=P.preview_font(22);small=P.preview_font(18);title=P.preview_font(32)
    def rendered(ident):return Image.frombytes('RGBA',(720,490),(out/f'{ident}-render.rgba').read_bytes())
    sheet=Image.new('RGB',(2160,1260),(239,245,245));draw=ImageDraw.Draw(sheet)
    draw.text((30,15),'水生魔理沙 · 五子同船，从父油帽檐开始初航',font=title,fill=(25,57,65))
    draw.text((30,62),'当前模型离线预览  /  父油 100% · 小子油 20%～25% · 初航子油 35% · 帽子仍为 1.5 倍',font=small,fill=(66,96,101))
    for i,(label,sub) in enumerate(labels):
        x=i%3*720;y=102+i//3*565
        frame=rendered(f'panel-{i}');frame.save(out/f'panel-{i + 1}.png');sheet.paste(frame,(x,y+64),frame)
        draw.text((x+20,y),label,font=font,fill=(25,57,65));draw.text((x+20,y+29),sub,font=small,fill=(50,87,95))
    draw.text((30,1230),'主体在水上生活，岸边只作短暂停留；此图为当前模型的离线分镜，完整事件仍需游戏实测。',font=small,fill=(60,87,94))
    sheet.save(out/'first-voyage.png')
    frames=[]
    for f,(i,_,_) in enumerate(stages):
        frame=Image.new('RGB',(720,562),(239,245,245));d=ImageDraw.Draw(frame);image=rendered(f'frame-{f}')
        frame.paste(image,(0,65),image);d.text((18,7),labels[i][0],font=font,fill=(25,57,65));d.text((18,37),labels[i][1]+' · 离线动作示意',font=small,fill=(50,87,95))
        frames.append(frame)
    frames[0].save(out/'first-voyage.gif',save_all=True,append_images=frames[1:],duration=280 if args.quick else 160,loop=0,disposal=2)
    print(out/'first-voyage.png');print(out/'first-voyage.gif')


if __name__=='__main__':main()
