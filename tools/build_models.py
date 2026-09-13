"""Build original, editable cuboid models and pixel textures. No game sprites copied."""
from pathlib import Path
from PIL import Image, ImageDraw
import json, base64, uuid

ROOT = Path(__file__).resolve().parents[1]
COLORS = {'skin':'#ffe1b0','skin_shadow':'#efbe87','skin_light':'#ffe8c3',
 'gold':'#f2c332','gold_light':'#ffdf57','gold_dark':'#c99325',
 'hair':'#382b28','hair_light':'#51403a','hair_dark':'#271e1d',
 'hat':'#292934','hat_light':'#393944','hat_dark':'#20212a',
 'red':'#e3343d','red_light':'#f5545b','red_dark':'#ad2531',
 'white':'#fff4db','white_shadow':'#d8d0c6'}
PALETTE = {k:[(i%16)*8,96+(i//16)*8] for i,k in enumerate(COLORS)}

def texture(kind):
    im=Image.new('RGBA',(128,128),(0,0,0,0)); d=ImageDraw.Draw(im)
    d.rectangle((0,0,63,47),fill=COLORS['skin'])
    # Pixel adaptation of the large bright eyes and open red mouth in Hachikei.
    d.rectangle((0,45,63,47),fill='#f6d09b')
    ink='#352b24'; iris='#796045' if kind=='reimu' else '#897137'
    for x in [10,39]:
        d.polygon([(x+3,17),(x+10,17),(x+13,20),(x+13,27),(x+10,30),(x+3,30),(x,27),(x,21)],fill=ink)
        d.polygon([(x+3,18),(x+10,18),(x+12,21),(x+12,27),(x+9,29),(x+3,29),(x+1,26),(x+1,21)],fill='#fff8df')
        d.ellipse((x+3,18,x+11,29),fill=ink)
        d.rectangle((x+5,26,x+9,28),fill=iris)
        d.polygon([(x+3,20),(x+4,19),(x+9,20),(x+10,22),(x+6,23),(x+3,22)],fill='#fffef4')
        d.rectangle((x+9,26,x+10,27),fill='#fffef4')
        d.line([(x+2,15),(x+7,14),(x+11,15)],fill='#956b40',width=1)
    for x in [6,49]:
        d.rectangle((x,31,x+8,32),fill='#f0b399')
        for dx in [1,4,7]: d.point((x+dx,32),fill='#d78f77')
    d.polygon([(28,34),(35,34),(36,35),(36,38),(34,40),(29,40),(27,38),(27,35)],fill='#bd3024')
    d.rectangle((28,35,35,37),fill='#d91d20')
    d.rectangle((29,38,34,39),fill='#f4482c')
    if kind=='marisa':
        # User's 2026-09-09 screenshot: round glossy black eyes, raised red mouth,
        # subtle cheek tint. Repaint only Marisa; preserve Reimu's current face.
        d.rectangle((0,0,63,47),fill=COLORS['skin'])
        d.rectangle((0,45,63,47),fill='#f6d09b')
        for x in (9,38):
            d.ellipse((x,15,x+16,31),fill='#443527')
            d.ellipse((x+1,16,x+15,30),fill='#fff5d8')
            d.ellipse((x+3,16,x+15,30),fill='#171514')
            # Large slanted upper reflection; tiny lower reflection stays separate.
            d.polygon([(x+4,17),(x+8,17),(x+12,19),(x+12,21),
                       (x+9,21),(x+5,20),(x+3,19)],fill='#fffef6')
            d.rectangle((x+11,27,x+12,28),fill='#fff5df')
            d.line([(x+3,12),(x+7,11),(x+12,12)],fill='#926c38',width=1)
        for x in (4,53):
            d.ellipse((x,30,x+7,35),fill='#f7c5a0')
            d.ellipse((x+2,31,x+5,34),fill='#f2b49a')
        # Compact open mouth: rounded silhouette, vivid red, no horizontal tongue stripe.
        d.ellipse((26,31,37,40),fill='#b93823')
        d.ellipse((27,31,36,39),fill='#e51b1d')
        d.line([(29,32),(34,32)],fill='#f32a27',width=1)
    for k,(x,y) in PALETTE.items(): d.rectangle((x,y,x+7,y+7),fill=COLORS[k])
    im.save(ROOT/'textures'/f'{kind}.png')
    return 'data:image/png;base64,'+base64.b64encode((ROOT/'textures'/f'{kind}.png').read_bytes()).decode()

def build(kind):
    cubes=[]
    def box(name,lo,hi,color,group='head',rot=0,face=False):
        cubes.append(dict(name=name,from_=lo,to=hi,color=color,group=group,rotation=rot,face=face))
    box('soft_head',[-7,1,-5.5],[7,10,6],'skin',face=True)
    box('chin',[-6,0,-4.5],[6,1.05,5.3],'skin')
    box('crown',[-6,10,-4.5],[6,11,5.3],'skin')
    for s in [-1,1]:
        lo,hi=sorted([s*7,s*8])
        box(f'cheek_{s}',[lo,2,-4.6],[hi,8.3,5.5],'skin')
    hair='gold' if kind=='marisa' else 'hair'
    box('hair_cap',[-6.8,9.6,-5.8],[6.8,11.5,5.9],hair,'hair')
    box('back_hair',[-7,1.0,-6.1],[7,10.4,-4.9],hair,'hair')
    for s in [-1,1]:
        a,b=sorted([s*6.7,s*8.25]); box(f'side_hair_{s}',[a,3.0,-4.8],[b,10.0,3.9],hair,'hair')
    # Individually stepped fringe; neither a skin cube nor a flat sprite silhouette.
    ends=[8.1,7.4,8.2,7.8,8.5,7.6,8.0]
    for i in range(7):
        x=-6.7+i*1.9
        box(f'bang_{i}',[x,ends[i],5.85],[x+1.94,10.7,6.6],hair if i%3 else hair+'_light','hair',rot=[-8,8,-6,4,-8,9,-5][i])
        if i in [0,2,5]: box(f'bang_glint_{i}',[x+.25,ends[i]+.6,6.62],[x+.5,10.1,6.69],hair+'_light','hair')
    for i in range(7):
        x=-6.7+i*1.9
        box(f'back_lock_{i}',[x,.65+(i%3)*.25,-6.3],[x+1.8,7.5,-5.9],hair if i%2 else hair+'_light','hair')
    if kind=='reimu':
        for s in [-1,1]:
            x=s*7.15
            box(f'sidelock_{s}',[x-.95,1.4,4.4],[x+.95,8.2,6.5],'hair','hair')
            box(f'red_tie_{s}',[x-1.05,2.4,4.3],[x+1.05,4.4,6.62],'red','accessory')
            for y in [2.25,4.3]: box(f'tie_trim_{s}_{y}',[x-1.1,y,4.25],[x+1.1,y+.35,6.72],'white','accessory')
        # Oversized upright cloth bow, on the back of the crown.
        for s in [-1,1]:
            a,b=sorted([s*1.0,s*7.6]); r=-s*12
            box(f'bow_lobe_{s}',[a,11.3,-2.0],[b,16.3,.3],'red','accessory',r)
            a,b=sorted([s*6.6,s*7.7])
            box(f'bow_lace_{s}',[a,11.2,-2.1],[b,16.4,.42],'white','accessory',r)
            for j in range(5):
                x=s*(7.0+(j%2)*.4)
                box(f'lace_tooth_{s}_{j}',[x-.45,11.5+j*.95,.1],[x+.45,12.02+j*.95,.55],'white','accessory',r)
            a,b=sorted([s*1.6,s*5.9]);box(f'bow_fold_{s}',[a,13.1,.35],[b,13.65,.5],'red_dark','accessory',r)
            a,b=sorted([s*2.0,s*5.8]);box(f'bow_light_{s}',[a,15.3,.35],[b,15.7,.5],'red_light','accessory',r)
        box('bow_knot',[-1.15,11.6,-2.15],[1.15,14.8,.8],'red_light','accessory')
        box('knot_shadow',[-.55,11.7,.81],[-.15,14.6,.9],'red','accessory')
    else:
        for s in [-1,1]:
            x=s*7.5
            box(f'curled_lock_{s}',[x-.9,1.3,3.8],[x+.9,8.1,6.2],'gold','hair',-s*8)
            box(f'curl_tip_{s}',[x-.9,.9,4.2],[x+1.0,2.6,6.1],'gold_light','hair',-s*18)
        # Small side braid and its white ribbon, visible from the front.
        for i in range(4):
            x=7.85+(i%2)*.3
            box(f'braid_{i}',[x-.7,1.4+i*.85,5.5],[x+.7,2.6+i*.85,6.8],'gold_light' if i%2 else 'gold','hair',(-1)**i*15)
        for s in [-1,1]:
            x=7.95+s*.8
            box(f'braid_ribbon_{s}',[x-.6,1.55,6.8],[x+.6,2.5,7.2],'white','accessory',s*18)
        box('braid_knot',[7.65,1.7,6.85],[8.25,2.35,7.4],'white_shadow','accessory')
        box('hat_brim',[-10.6,10.8,-7.1],[10.6,11.6,7.0],'hat','accessory')
        box('hat_brim_front',[-9.5,10.65,7.0],[9.5,11.45,7.7],'hat_dark','accessory')
        for s in [-1,1]:
            x=s*10.45
            box(f'brim_lift_{s}',[x-.85,11.1,-5.6],[x+.85,11.85,5.8],'hat','accessory',s*8)
        box('hat_base',[-6.1,11.6,-4.9],[6.1,14.3,4.8],'hat','accessory')
        box('hat_ribbon',[-6.2,11.8,-5.0],[6.2,13.1,4.92],'white','accessory')
        box('hat_tier2',[-4.9,14.3,-3.9],[4.7,16.4,3.8],'hat','accessory')
        box('hat_tier3',[-3.8,16.4,-3.0],[2.9,18.3,2.9],'hat','accessory')
        box('hat_tip',[-3.7,18.3,-2.0],[.4,20.1,1.9],'hat','accessory',18)
        box('hat_flop',[-5.0,18.3,-1.55],[-2.7,19.7,1.5],'hat','accessory',-18)
        for s in [-1,1]:
            x=.3+s*2.1
            box(f'hat_bow_{s}',[x-1.5,11.9,5],[x+1.5,14.1,5.7],'white','accessory',s*15)
            box(f'hat_bow_fold_{s}',[x-.8,12.7,5.72],[x+.8,13.0,5.78],'white_shadow','accessory',s*15)
        box('hat_bow_knot',[-.4,12.0,5.2],[1.0,13.6,6.1],'white_shadow','accessory')
    source=texture(kind)
    elements=[]; outliner=[]; bones=[]
    for group in ['head','hair','accessory']:
        children=[]; geocubes=[]
        for c in [c for c in cubes if c['group']==group]:
            lo=c['from_'];hi=c['to'];p=PALETTE[c['color']];uv=[p[0]+2,p[1]+2,p[0]+3,p[1]+3]
            faces={f:dict(uv=uv,texture=0) for f in ['north','south','east','west','up','down']}
            # +Z is the front for both the viewer and editable models.
            if c['face']: faces['south']=dict(uv=[0,0,64,48],texture=0)
            uid=str(uuid.uuid5(uuid.NAMESPACE_DNS,kind+c['name']));children.append(uid)
            origin=[(lo[i]+hi[i])/2 for i in range(3)]
            elements.append(dict(name=c['name'],type='cube',uuid=uid,**{'from':lo},to=hi,
                origin=origin,rotation=[0,0,c['rotation']],box_uv=False,rescale=False,faces=faces))
            guv={f:dict(uv=faces[f]['uv'][:2],uv_size=[faces[f]['uv'][2]-faces[f]['uv'][0],faces[f]['uv'][3]-faces[f]['uv'][1]]) for f in faces}
            # Bedrock reverses UV on the down face, irrelevant for these flat palette swatches.
            geocubes.append(dict(origin=lo,size=[round(hi[i]-lo[i],5) for i in range(3)],pivot=origin,rotation=[0,0,c['rotation']],uv=guv))
        outliner.append(dict(name=group,uuid=str(uuid.uuid5(uuid.NAMESPACE_DNS,kind+group)),origin=[0,5,0],children=children))
        bones.append(dict(name=group,pivot=[0,5,0],**({'parent':'head'} if group!='head' else {}),cubes=geocubes))
    bb=dict(meta=dict(format_version='4.10',model_format='free',box_uv=False),name=f'yukkuri_{kind}',
        model_identifier=f'yukkuri_{kind}',resolution=dict(width=128,height=128),elements=elements,outliner=outliner,
        textures=[dict(path=f'../textures/{kind}.png',name=f'{kind}.png',id='0',uuid=str(uuid.uuid5(uuid.NAMESPACE_DNS,kind+'texture')),
            source=source,mode='bitmap',saved=True,width=128,height=128,uv_width=128,uv_height=128)],animations=[])
    (ROOT/'models'/f'{kind}.bbmodel').write_text(json.dumps(bb,ensure_ascii=False,indent=2),encoding='utf8')
    geo={'format_version':'1.12.0','minecraft:geometry':[dict(description=dict(identifier=f'geometry.yukkuri.{kind}',
        texture_width=128,texture_height=128,visible_bounds_width=3,visible_bounds_height=3,visible_bounds_offset=[0,1,0]),bones=bones)]}
    (ROOT/'models'/f'{kind}.geo.json').write_text(json.dumps(geo,indent=2),encoding='utf8')
    return dict(kind=kind,cubes=cubes,texture=source,palette=COLORS)

if __name__=='__main__':
    data=[build('marisa'),build('reimu')]
    (ROOT/'preview'/'models.js').write_text('window.MODELS = '+json.dumps(data)+';\n',encoding='utf8')
    print({m['kind']:len(m['cubes']) for m in data})
