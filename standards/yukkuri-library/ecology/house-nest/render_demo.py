"""24-second offline house ecology demonstration using current runtime meshes and clips.

Only software rasterization and FFmpeg run. Frames, videos and manifests stay local.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import shutil
import subprocess
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
OUT=ROOT/'.local/previews/house-ecology'
WORK=OUT/'demo-work'
FPS=12
DURATION=24
WIDTH,HEIGHT=720,480
VIEW_HEIGHT=362


def font(size):
    for path in ('C:/Windows/Fonts/msyh.ttc','/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'):
        if Path(path).exists():
            return ImageFont.truetype(path,size)
    raise RuntimeError('A Chinese font is needed for the preview captions.')


def smooth(t):
    t=max(0,min(1,t));return t*t*(3-2*t)


def mix(a,b,t):
    return a+(b-a)*smooth(t)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg',required=True)
    parser.add_argument('--node',default='node')
    args=parser.parse_args()
    ffmpeg=Path(args.ffmpeg).resolve()
    if not ffmpeg.is_file():
        raise SystemExit('FFmpeg executable not found: '+str(ffmpeg))
    WORK.mkdir(parents=True,exist_ok=True)
    frames=WORK/'frames';frames.mkdir(exist_ok=True)
    recipe=json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))
    models,textures,sources={}, {}, []
    atlas=Image.new('RGBA',(1024,1024))

    def texture(key,path):
        image=Image.open(path).convert('RGBA');i=len(textures);x,y=i%4*256,i//4*256
        if image.width>256 or image.height!=image.width or i>=16:
            raise ValueError('Texture atlas overflow or invalid texture: '+str(path))
        atlas.paste(image,(x,y));textures[key]=dict(x=x,y=y,size=image.width);sources.append(path)

    for ident in ('nest_lint','house_lint_bedding','house_eggshell','house_egg','house_egg_cracked','food_scrap'):
        p=ASSETS/'yukkuri_props'/(ident+'.json')
        models[ident]=dict(model=p.relative_to(ROOT).as_posix());sources.append(p)
        texture(ident,ASSETS/'textures/props'/(ident+'.png'))
    for ident,expressions in [('house_reimu',('neutral','happy','sleep')),('house_marisa',('neutral','happy','sleep'))]:
        p=ASSETS/'yukkuri'/(ident+'.json')
        models[ident]=dict(model=p.relative_to(ROOT).as_posix(),character=True);sources.append(p)
        for expression in expressions:
            texture(ident+'/'+expression,ASSETS/'textures/entity'/ident/(expression+'.png'))
    (WORK/'atlas.rgba').write_bytes(atlas.tobytes())

    def obj(ident,at=(0,0,0),**extra):
        return dict(id=ident,at=list(at),**extra)
    def block_at(pos):
        return [(pos[0]-.5)*16,pos[1]*16,(pos[2]-.5)*16]
    display=recipe['display']
    egg_scale=recipe['egg_geometry']['model_scale']
    hatch_lift=recipe['egg_geometry']['hatch_foot_lift']
    birth_scale=json.loads((ROOT/'standards/yukkuri-v1/profiles/growth.json').read_text(encoding='utf-8'))['birth']['egg']['scale']
    beds=[obj('house_lint_bedding',block_at(display['sleeping_center']),scale=display['sleeping_scale']),
          obj('house_lint_bedding',block_at(display['egg_center']),scale=display['egg_scale'])]
    slots=[block_at(p) for p in display['egg_slots']]
    titles=['白天，把身子藏回小窝','夜深，探头与夹絮归来','靠近一点，听听卵里的动静','先裂开一枚，再迎来下一只']
    lines=['走入缝隙，转身贴地休息。','碎食留在前侧；棉絮由单辫夹着搬回。','亲油低头照看，然后侧脸停顿倾听。','幼体从原卵位出现，空壳短时留在身后。']
    shots=[]
    for frame in range(DURATION*FPS):
        time=frame/FPS;chapter=min(3,int(time//6));t=time-chapter*6
        instances=[*beds];anchors=[]
        # Four locations are the same places used by the real nest renderer.
        for slot in range(4):
            hatch_at=1.2 if slot==0 else 3.6 if slot==1 else 99
            if chapter<3 or t<hatch_at:
                cracked=chapter==3 and slot<2 and t>hatch_at-1.0
                egg=obj('house_egg_cracked' if cracked else 'house_egg',slots[slot],scale=[egg_scale]*3)
                if cracked:
                    intensity=smooth((t-hatch_at+1.0)/.85)
                    egg['tiltZ']=math.sin(t*18+slot*1.7)*.12*intensity
                    egg['at'][1]+=.04*abs(math.sin(t*18+slot*1.7))*intensity
                instances.append(egg)
            else:
                age=t-hatch_at
                instances.append(obj('house_eggshell',slots[slot],scale=[egg_scale]*3,angle=slot*.3))
                # The real birth keeps this scale and foot origin; the later crawl is only staged here.
                at=slots[slot].copy();at[1]+=hatch_lift;at[2]-=smooth((age-.20)/2.8)*.80
                instances.append(obj('house_marisa' if slot==0 else 'house_reimu',at,scale=[birth_scale]*3,
                                     face='happy',clip='newborn_crawl',time=age,angle=-.18 if slot==0 else .12))
        instances.append(obj('food_scrap',(-3.2,.12,-4.3),scale=[.40]*3))
        if chapter==0:
            if t<2.8:
                actor=obj('house_reimu',(-1.76,.16,mix(-5.2,3.04,t/2.8)),angle=math.pi,clip='waddle',time=t,face='neutral')
            elif t<3.65:
                actor=obj('house_reimu',(-1.76,.16,3.04),angle=mix(math.pi,0,(t-2.8)/.85),clip='idle',time=t)
            else:
                actor=obj('house_reimu',(-1.76,.16,3.04),clip='sleep',time=t-3.65,face='sleep')
            instances.append(actor)
        elif chapter==1:
            instances.append(obj('house_reimu',(-1.76,.16,3.04),clip='sleep',time=t+2,face='sleep'))
            if t<1.0:
                actor=obj('house_marisa',(-1.8,.16,-3.6),clip='house_peek',time=t,angle=-.1)
            elif t<1.6:
                actor=obj('house_marisa',(-1.8,.16,mix(-3.6,-4.2,(t-1.0)/.6)),clip='waddle',time=t-1.0,angle=mix(-.1,.12,(t-1.0)/.6))
            elif t<2.9:
                actor=obj('house_marisa',(-1.8,.16,-4.2),clip='house_pickup',time=t-1.6,angle=.12,key='carrier')
            elif t<4.6:
                actor=obj('house_marisa',(-1.8,.16,mix(-4.2,-2.8,(t-2.9)/1.7)),clip='house_carry',time=t-2.9,angle=.12,key='carrier')
            else:
                actor=obj('house_marisa',(-1.8,.16,-2.8),clip='house_deposit',time=min(t-4.6,1.3),angle=.12,key='carrier')
            instances.append(actor)
            anchors=[obj('house_marisa',(-1.8,.16,-4.2),clip='house_pickup',time=.65,angle=.12,key='pickup-contact'),
                     obj('house_marisa',(-1.8,.16,-2.8),clip='house_deposit',time=.65,angle=.12,key='release-contact')]
            if 2.25<=t<5.25:
                instances.append(obj('nest_lint',socketOf='carrier',scale=[.65]*3))
            elif t>=5.25:
                instances.append(obj('nest_lint',socketOf='release-contact',scale=[.65]*3))
            else:
                instances.append(obj('nest_lint',socketOf='pickup-contact',scale=[.65]*3))
        elif chapter==2:
            action='house_tend' if t<2.0 else 'house_listen'
            instances.append(obj('house_reimu',(-2.8,.16,.8),clip=action,time=t if t<2.0 else t-2.0,
                                 face='happy' if t<2.0 else 'neutral',angle=.68))
        else:
            instances.append(obj('house_reimu',(-3.1,.16,1.0),clip='house_listen',time=t,face='happy',angle=.55))
        shots.append(dict(id=f'frame-{frame:04d}',instances=instances,anchors=anchors,chapter=chapter,time=time,
                          az=.25 if chapter==1 else .12,el=.63 if chapter==1 else .49 if chapter==0 else .61,span=6.7,centerY=2.5))
    scene=dict(models=models,textures=textures,atlasSize=1024,shots=shots,width=WIDTH,height=VIEW_HEIGHT,progressEvery=36)
    (WORK/'scene.json').write_text(json.dumps(scene,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    subprocess.run([args.node,str(HERE/'render_scene.cjs'),str(WORK/'scene.json'),str(WORK)],check=True,cwd=ROOT)
    previous=None
    for frame,shot in enumerate(shots):
        night=shot['chapter']==1
        bg='#222e40' if night else '#f2ecdf';ink='#f2efe2' if night else '#2e4439';muted='#b9c6d5' if night else '#6a7669'
        raw=WORK/(shot['id']+'.rgba')
        pixels=Image.frombytes('RGBA',(WIDTH,VIEW_HEIGHT),raw.read_bytes())
        if night:
            # Moonlit tint preserves the same original texture and face pixels.
            r,g,b,a=pixels.split()
            pixels=Image.merge('RGBA',(r.point(lambda v:int(v*.72)),g.point(lambda v:int(v*.80)),b.point(lambda v:min(255,int(v*.97))),a))
        panel=Image.new('RGB',(WIDTH,HEIGHT),bg);panel.paste(pixels,(0,66),pixels)
        draw=ImageDraw.Draw(panel)
        draw.text((24,10),titles[shot['chapter']],font=font(27),fill=ink)
        draw.text((25,45),'家油生态 · 当前运行资源离线演出 · 家具剖视',font=font(13),fill=muted)
        draw.text((24,432),lines[shot['chapter']],font=font(19),fill=ink)
        # One compact progress strip shows where the current story beat sits in the full loop.
        for part in range(4):
            x=24+part*171;draw.rounded_rectangle((x,464,x+161,468),2,fill='#566172' if night else '#d8d3c7')
            if part<=shot['chapter']:
                fraction=1 if part<shot['chapter'] else min(1,(shot['time']%6)/6)
                if fraction>0:draw.rounded_rectangle((x,464,x+max(2,int(161*fraction)),468),2,fill='#d1d8c8' if night else '#72886c')
        local_frame=frame%(6*FPS)
        if local_frame<3 and frame>=6*FPS and previous is not None:
            panel=Image.blend(previous,panel,(local_frame+1)/3)
        if local_frame==6*FPS-1:
            previous=panel.copy()
        panel.save(frames/f'{frame:04d}.png')
        raw.unlink()  # Exact generated file; no recursive deletion of user content.
        if frame==23*FPS:
            panel.save(OUT/'demo-poster.png')
    mp4=OUT/'house-ecology-demo.mp4'
    subprocess.run([str(ffmpeg),'-hide_banner','-loglevel','warning','-y','-framerate',str(FPS),'-i',str(frames/'%04d.png'),
                    '-c:v','libx264','-preset','medium','-crf','20','-pix_fmt','yuv420p','-movflags','+faststart',str(mp4)],check=True)
    # Compact direct-chat preview: two representative seconds from every story beat.
    gif=OUT/'house-ecology-demo.gif'
    selection="select='between(t,1,3)+between(t,8,10)+between(t,14,16)+between(t,21,23)',setpts=N/FRAME_RATE/TB,fps=8,scale=480:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=192[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3"
    subprocess.run([str(ffmpeg),'-hide_banner','-loglevel','warning','-y','-i',str(mp4),'-filter_complex',selection,'-loop','0',str(gif)],check=True)
    sources.extend([HERE/'recipe.json',HERE/'render_demo.py',HERE/'render_scene.cjs',ROOT/'preview/review/pose.js',
                    ROOT/'standards/yukkuri-library/runtime/render.js',ASSETS/'yukkuri_presentation/house_ecology.json',
                    ASSETS/'yukkuri_presentation/motion_corrections.json',ASSETS/'yukkuri_presentation/placement_fit.json'])
    manifest=dict(format=1,seconds=DURATION,fps=FPS,size=[WIDTH,HEIGHT],chapters=[dict(start=i*6,title=titles[i]) for i in range(4)],
                  scope='Software-rendered current meshes, textures and animation curves. Travel, camera, day-night tint and shortened hatching times are preview staging, not a game recording.',
                  inputs={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
                  outputs={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (mp4,gif,OUT/'demo-poster.png')})
    (WORK/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    if (HERE/'demo.html').is_file():
        shutil.copy2(HERE/'demo.html',OUT/'index.html')
    print(mp4,flush=True)
    print(gif,flush=True)


if __name__=='__main__':
    main()
