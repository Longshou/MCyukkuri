"""Current models, exported clips and shared brim solver, rendered offline without a game or browser.

The six panels and looping GIF illustrate the server event; they are not a gameplay recording.
All generated geometry, images and the Node runner stay in .local/previews/first-voyage.
"""
from pathlib import Path
import argparse, json, math, runpy, subprocess
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import preview as P
from aquatic_family_preview import vertices


def plane(x0, x1, z0, z1, y, color):
    v = np.array([[x0,y,z0],[x0,y,z1],[x1,y,z1],[x1,y,z0]])
    return np.concatenate([np.c_[v[idx], np.tile([-1-color,0,0,1,0], (3,1))] for idx in ([0,1,2],[0,2,3])])


def main():
    ap = argparse.ArgumentParser(description=__doc__); ap.add_argument('--node', default='node')
    args = ap.parse_args(); out=P.ROOT/'.local/previews/first-voyage'; out.mkdir(parents=True, exist_ok=True)
    data=json.loads((P.ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
    V=json.loads((P.ASSETS/'yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))
    dl=runpy.run_path(str(P.ROOT/'standards/yukkuri-library/interactions/marisa-hat-vessel/delivery.py'))
    hat=dl['Hat'](V,'bowl'); k=V['aquatic_scale']; D=V['delivery']
    body=json.loads((P.ASSETS/'yukkuri_presentation/body_geometry.json').read_text(encoding='utf-8'))['variants']['marisa']
    width=body['bounds'][0][1]-body['bounds'][0][0]
    parent=P.Posed('marisa',data,True); shore=P.Posed('marisa',data,worn_hat_scale=k)
    young=P.Posed('marisa',data,worn_hat_scale=k); sailor=P.Posed('marisa',data,True)
    owner=np.array([0,V['bowl']['owner_y']*k,V['opening'][3]*k-body['bounds'][2][1]])
    size=.30; r=dl['radius'](D,width*size)/k
    brim=dl['brim_place'](hat,D,r); assert brim is not None
    well=dl['place'](hat,D,r,'day'); T=dl['solve'](np.array(well)*k,np.array(brim)*k,dl['Pose']([0,0,0],0,k),hat,r*k,D,tmin=D['hop_tmin'])
    # The same gravity arc and target as the server passenger-seat transition.
    arc=dl['arc'](np.array(well)*k,np.array(brim)*k,T,D['gravity'])
    (out/'seat-data.json').write_text(json.dumps({'scale':size,'radius':r,'brim':brim,'inside':well,'hop_ticks':T},indent=2),encoding='utf-8')
    labels=[('01  小子油站上帽檐','体型 30% · 前沿站稳、左右张望'),
            ('02  长大后准备初航','体型达到 50% · 亲油在干岸守望'),
            ('03  用自己的帽子浮起','收身试坐 · 同一顶帽子随体型缩放'),
            ('04  试着独立划行','真实木棍作桨 · 亲油看护的短航线'),
            ('05  转向、返回岸边','走完短程再返岸 · 不靠计时发奖励'),
            ('06  亲油鼓励，留下初航记录','安全上岸后完成 · 中断可再次尝试')]
    colors=[[116,184,204,255],[183,206,145,255],[227,215,178,255],[177,222,230,255]]

    def scene(index,time,progress=1):
        texture=Image.new('RGBA',(512,512)); mesh=[]; holes=[]
        def actor(model,clip,t,scale,yaw,pos,atlas=(0,0)):
            mesh.append(vertices(model,clip,t,scale,yaw,pos,atlas))
            texture.paste(Image.fromarray(model.texture(model.face(clip,t))),(int(atlas[0]*512),int(atlas[1]*512)))
        def boat(model,clip,t,scale,center,atlas,yaw=0):
            at=np.array(center)+P.euler([0,yaw,0])@owner*scale
            actor(model,clip,t,scale,yaw,at,atlas)
            holes.append([center[0]+V['opening'][0]*k*scale,center[0]+V['opening'][1]*k*scale,
                          center[2]+V['opening'][2]*k*scale,center[2]+V['opening'][3]*k*scale])
            return at
        if index==0:
            boat(parent,'boat_drift',time,1,[0,0,0],(0,0))
            at=np.array(arc(T*progress)) if progress<1 else np.array(brim)*k
            actor(young,'boat_board' if progress<1 else 'hat_brim',time%.8 if progress<1 else time%3,size,0,at,(.5,0))
        else:
            parent_clip='greet' if index==5 else 'look_around'
            actor(shore,parent_clip,time%(1.4 if index==5 else 4),1,0,[-11,1.8,15])
            if index==1:
                actor(young,'hat_off_self_aq',min(time,1.8),.5,0,[10,1.8,10],(.5,0))
            elif index==5:
                actor(young,'shiawase',time%2,.5,-20,[9,1.8,13],(.5,0))
            else:
                center=[11,0,0] if index==2 else [11,0,-12*progress] if index==3 else [11,0,-12*(1-progress)]
                clip='boat_first_balance' if index==2 else 'boat_paddle'
                yaw=180 if index==4 else 0
                at=boat(sailor,clip,min(time,2) if index==2 else time%1.6,.5,center,(.5,0),yaw)
                # Same tip pivot / grip transform as YukkuriRenderer's real twig (a narrow flat stick texture).
                if index in (3,4):
                    from aquatic_family_preview import item_quad
                    tip=next(i for i,b in enumerate(sailor.model['bones']) if b['name']=='hand_right_tip')
                    m=sailor.last_matrices[tip].copy(); pv=np.array(sailor.model['bones'][tip]['pivot'])
                    # Relative to the braid tip: translate down .22 blocks, rotate 45 degrees, item size .85.
                    m[:3,3]=(m@np.r_[pv,1])[:3]+m[:3,:3]@np.array([0,-.22*16,0])
                    m[:3,:3]=m[:3,:3]@P.euler([0,0,45]); world=np.eye(4);world[:3,:3]=P.euler([0,yaw,0])*.5;world[:3,3]=at
                    mesh.append(item_quad(world@m,.85))
                    texture.paste(stick,(0,256))
        edge=28 if index==0 else 6
        # Water with dry openings cut around the physical hats, matching the game's water mask.
        xs=sorted(set([-40,40]+[v for h in holes for v in h[:2]])); zs=sorted(set([-38,edge]+[v for h in holes for v in h[2:] if -38<v<edge]))
        for x0,x1 in zip(xs,xs[1:]):
            for z0,z1 in zip(zs,zs[1:]):
                x,z=(x0+x1)/2,(z0+z1)/2
                if not any(h[0]<x<h[1] and h[2]<z<h[3] for h in holes):mesh.append(plane(x0,x1,z0,z1,-.02,0))
        mesh.extend([plane(-40,40,edge,edge+3,1.8,2),plane(-40,40,edge+3,45,1.8,1)])
        return np.concatenate(mesh).astype('<f4'),texture

    # Native item texture from the already installed local resources, never fetched or copied into runtime.
    import zipfile,io
    jar=P.ROOT/'.local/setup/preload/build/moddev/artifacts/neoforge-21.1.250-client-extra-aka-minecraft-resources.jar'
    with zipfile.ZipFile(jar) as z:stick=Image.open(io.BytesIO(z.read('assets/minecraft/textures/item/stick.png'))).convert('RGBA').resize((64,64),Image.Resampling.NEAREST)
    runner=out/'render.cjs'
    runner.write_text("const fs=require('fs');\n"+(P.ROOT/'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf-8')+'''
    const dir=process.argv[2], tasks=JSON.parse(fs.readFileSync(dir+'/tasks.json'));
    for(const task of tasks){
      const raw=fs.readFileSync(dir+'/'+task.id+'.bin'), v=new Float32Array(raw.buffer,raw.byteOffset,raw.length/4);
      const tex=fs.readFileSync(dir+'/'+task.id+'.rgba');
      const img=renderModel(v,tex,720,490,.35,.48,task.span,{centerY:task.cy,textureSize:512,colors:task.colors});
      fs.writeFileSync(dir+'/'+task.id+'-render.rgba',img);
    }
    ''',encoding='utf-8')
    tasks=[]
    def add(ident,index,time,progress=1):
        mesh,tex=scene(index,time,progress); mesh.tofile(out/f'{ident}.bin');(out/f'{ident}.rgba').write_bytes(tex.tobytes())
        tasks.append(dict(id=ident,span=17 if index==0 else 30,cy=3 if index==0 else 16,colors=colors))
    for i,t in enumerate([.45,.50,1.15,.75,.45,.5]):add(f'panel-{i}',i,t,.8 if i in (3,4) else 1)
    stages=[(0,i*.16,min(1,i/5)) for i in range(16)]
    stages += [(1,i*.2,1) for i in range(10)]+[(2,i*.2,1) for i in range(10)]
    stages += [(3,i*.16,i/11) for i in range(12)]+[(4,i*.16,i/11) for i in range(12)]+[(5,i*.18,1) for i in range(12)]
    for f,(i,t,q) in enumerate(stages):add(f'frame-{f}',i,t,q)
    (out/'tasks.json').write_text(json.dumps(tasks),encoding='utf-8');subprocess.run([args.node,str(runner),str(out)],check=True)
    fontpath='C:/Windows/Fonts/msyh.ttc';font=ImageFont.truetype(fontpath,22);small=ImageFont.truetype(fontpath,18);title=ImageFont.truetype(fontpath,32)
    def rendered(ident):return Image.frombytes('RGBA',(720,490),(out/f'{ident}-render.rgba').read_bytes())
    sheet=Image.new('RGB',(2160,1260),(239,245,245));draw=ImageDraw.Draw(sheet)
    draw.text((30,15),'水生魔理沙 · 从帽檐上的小乘客，到第一次独立航行',font=title,fill=(25,57,65))
    draw.text((30,62),'当前模型与导出动作离线预览  /  体型、帽子比例和站位使用运行资源',font=small,fill=(66,96,101))
    for i,(label,sub) in enumerate(labels):
        x=i%3*720;y=102+i//3*565
        frame=rendered(f'panel-{i}');sheet.paste(frame,(x,y+64),frame)
        draw.text((x+20,y),label,font=font,fill=(25,57,65));draw.text((x+20,y+29),sub,font=small,fill=(50,87,95))
    draw.text((30,1230),'初航阶段由服务器驱动；此图展示造型与动作，航线、多人同步和完整事件仍需游戏实测。',font=small,fill=(60,87,94))
    sheet.save(out/'first-voyage.png')
    frames=[]
    for f,(i,_,_) in enumerate(stages):
        frame=Image.new('RGB',(720,562),(239,245,245));d=ImageDraw.Draw(frame);image=rendered(f'frame-{f}')
        frame.paste(image,(0,65),image);d.text((18,7),labels[i][0],font=font,fill=(25,57,65));d.text((18,37),labels[i][1]+' · 离线动作示意',font=small,fill=(50,87,95))
        frames.append(frame)
    frames[0].save(out/'first-voyage.gif',save_all=True,append_images=frames[1:],duration=160,loop=0,disposal=2)
    print(out/'first-voyage.png');print(out/'first-voyage.gif')


if __name__=='__main__':main()
