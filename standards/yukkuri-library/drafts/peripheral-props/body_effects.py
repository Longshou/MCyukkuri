"""Body-effect art candidates, attached to the selected runtime character snapshots.

No game resources or approved characters are modified. The reusable geometry,
graded variants, emitter metadata and editable scenes are exported together.
"""
import copy
import json
import math
import random
from pathlib import Path
from PIL import Image, ImageDraw

COLORS = {
    'skin_mid':'e8c9a8', 'skin_light':'f0d6b7', 'abrasion':'b67a65',
    'wound_edge':'925c54', 'deep_filling':'28152e', 'filling_wet':'49304d',
    'filling_glint':'755c78', 'mold_dark':'246543', 'mold_green':'35936c',
    'mold_mint':'75af90', 'mold_fuzz':'b4c5a2', 'charcoal':'322c2b',
    'char_edge':'76503e', 'burn_edge':'a3624b', 'dry_line':'ac8467',
    'dry_light':'dfbb98',
}
EFFECTS = [
    ('scratch','擦伤','表皮擦痕由少量细线扩展为较宽的磨损面。'),
    ('tear','裂口','局部皮面凹入形成裂口，边缘不规则，内部显示深紫馅料。'),
    ('dent','凹陷','实际身体网格局部内凹，五官贴面随同一表面变形。'),
    ('compression','受压','身体逐级变矮、变宽；帽子和大蝴蝶结保留形状并随头顶下移。'),
    ('scorch','焦痕','从底缘开始的焦黑和褐色损伤，扩大为不规则斑块。'),
    ('desiccation','干瘪','身体逐渐收缩，表皮出现细长褶痕；头饰保持独立形状。'),
    ('mold','油霉','绿斑从局部点状扩散为相连斑块，0 级恢复无油霉状态。'),
    ('leak','漏馅过程','伤口处渗出、拉丝、飞散及地面摊开；份量为实际漏馅单位的预览输入。'),
]


def extend_body_effects(G):
    HERE,ROOT=G['HERE'],G['ROOT'];Model=G['Model'];TAU=math.tau
    banks={'version':1,'textures':{},'states':{},'frame':{'centerY':10.0,'span':17.0}}
    checks=[];actors={};atlases={};source_records={}
    variant_root=HERE/'body-variants'
    for species in ['reimu','marisa']:
        path=ROOT/f'art/source/standard-runtime/{species}/runtime.model.json'
        actors[species]=json.loads(path.read_text(encoding='utf-8'))
        atlas=Image.new('RGBA',(512,512));atlas.paste(G['atlas'],(0,0))
        refs=[dict(path=path.relative_to(ROOT).as_posix(),sha256=G['sha'](path))]
        for expression,(x,y) in {'neutral':(256,0),'cry':(384,0),'teary':(256,128),'confused':(384,128)}.items():
            tex=path.parent/'textures'/f'{expression}.png'
            atlas.paste(Image.open(tex).convert('RGBA'),(x,y));refs.append(dict(path=tex.relative_to(ROOT).as_posix(),sha256=G['sha'](tex)))
        draw=ImageDraw.Draw(atlas)
        for i,color in enumerate(COLORS.values()):
            x=256+(i%8)*32;y=256+(i//8)*32
            draw.rectangle((x,y,x+31,y+31),fill='#'+color)
        # Original, mottled alpha masks; fine spreading colonies rather than outlined discs.
        for grade in [1,2,3]:
            rng=random.Random(711+grade);ox=256;oy=320+(grade-1)*64
            colonies=[(-4.8,1.2),(4.75,1.4),(-5.0,4.45),(5.0,4.35),(-2.7,.5),(2.7,.45)]
            radius=[0,.34,.64,1.12][grade]
            for py in range(64):
                for px in range(128):
                    x=-5.7+(px+.5)/128*11.4;y=.1+(1-(py+.5)/64)*5.0
                    field=sum(math.exp(-((x-cx)**2+(y-cy)**2*1.5)/(2*radius*radius)) for cx,cy in colonies)
                    rough=.20*math.sin(x*13+y*7)+.14*math.cos(y*21-x*5)+rng.uniform(-.22,.22)
                    strength=field+rough-.48
                    if strength<=0:continue
                    color=list(bytes.fromhex(COLORS['mold_mint' if rng.random()<.26 else 'mold_green' if strength<.5 else 'mold_dark']))
                    alpha=min(240,int(140+strength*120))
                    atlas.putpixel((ox+px,oy+py),tuple(color)+(alpha,))
        atlases[species]=atlas;source_records[species]=refs;banks['textures'][species]=G['uri'](atlas)

    def model(id,label,species,effect,level):
        m=Model(id,label,'body','',str(level));m.texture=atlases[species]
        m.preserve_origin=True;m.sources=copy.deepcopy(source_records[species])
        primitive_mesh=m.mesh
        def mesh(name,points,faces,mat):
            if mat not in COLORS:return primitive_mesh(name,points,faces,mat)
            primitive_mesh(name,points,faces,'paper')
            i=list(COLORS).index(mat);u=256+(i%8)*32+8;v=256+(i//8)*32+8
            for face in m.parts[-1]['faces'].values():
                face['uv']={key:[u,v] for key in face['vertices']}
        m.mesh=mesh
        return m

    def transform(p,effect,level,rigid=False):
        x,y,z=p;v=level/3
        if effect=='compression':
            sy=1-.48*v;sx=1+.23*v;sz=1+.13*v
            if rigid:return [x+rigid[0]*(sx-1),y+rigid[1]*(sy-1),z+rigid[2]*(sz-1)]
            return [x*sx,y*sy,z*sz]
        if effect=='desiccation':
            sx=1-.20*v;sy=1-.13*v;sz=1-.18*v
            if rigid:return [x+rigid[0]*(sx-1),y+rigid[1]*(sy-1),z+rigid[2]*(sz-1)]
            return [x*sx,y*sy,z*sz]
        if effect in ('dent','tear') and z< -4.25:
            cx,cy,rx,ry=(-4.2,2.0,1.9,1.5) if effect=='dent' else (-4.3,1.75,1.5,1.1)
            if effect=='dent':rx*=.5+.5*v;ry*=.5+.5*v
            r=((x-cx)/rx)**2+((y-cy)/ry)**2
            if r<1:z+=([0,.35,.95,1.9][level] if effect=='dent' else .70*v)*(1-r)**2
        return [x,y,z]

    def actor_vertices(species,effect,level):
        data=actors[species];out=[]
        expression='neutral' if level==0 else 'confused' if effect=='mold' and level==1 else 'teary' if level==1 else 'cry'
        ox,oy={'neutral':(256,0),'cry':(384,0),'teary':(256,128),'confused':(384,128)}[expression]
        def append_triangle(tri,rigid=False):
            for v in tri:
                out.extend([*transform(v[:3],effect,level,rigid),(ox+v[3]*128)/512,(oy+v[4]*128)/512,*v[5:8]])
        for part in data['parts']:
            if part.get('anatomy') or part['name'].startswith('puff_'):continue
            rigid=([0,9.328,8.275] if species=='reimu' else [0,9.54,1.6351562]) if part['name'].startswith(('bow_','hat_','brim_')) else False
            src=data['vertices'][part['start']*8:(part['start']+part['count'])*8]
            vertices=[src[i:i+8] for i in range(0,len(src),8)]
            # Add actual surface vertices before indenting, not just a floating decal.
            dense=level>0 and effect in ('dent','tear') and part['name'].startswith(('soft_head_slice_','face_'))
            front=[]
            for i in range(0,len(vertices),3):
                tri=vertices[i:i+3]
                if dense and part['name'].startswith('soft_head_slice_') and abs(tri[0][6])>.9 and .001<tri[0][1]<8.479:
                    continue  # Internal slice caps must not cut across an indented surface.
                if dense and part['name'].startswith('face_') and max(abs(v[2]+4.6) for v in tri)<.025 and tri[0][7]>.5:
                    continue  # Coincident reverse glyph face would bridge the deformation.
                if dense and max(abs(v[2]+4.6) for v in tri)<.025 and tri[0][7]<-.5:
                    front+=tri
                else:append_triangle(tri,rigid)
            if not front:continue
            x0=min(v[0] for v in front);x1=max(v[0] for v in front);y0=min(v[1] for v in front);y1=max(v[1] for v in front)
            if x1-x0<1e-6 or y1-y0<1e-6:
                for i in range(0,len(front),3):append_triangle(front[i:i+3],rigid)
                continue
            corners=[min(front,key=lambda v:abs(v[0]-x)+abs(v[1]-y)) for x,y in [(x0,y0),(x1,y0),(x0,y1),(x1,y1)]]
            nx=max(1,math.ceil((x1-x0)/.45));ny=max(1,math.ceil((y1-y0)/.27))
            def vertex(u,v):return [sum(c[k]*w for c,w in zip(corners,[(1-u)*(1-v),u*(1-v),(1-u)*v,u*v])) for k in range(8)]
            for j in range(ny):
                for i in range(nx):
                    a,b,c,d=vertex(i/nx,j/ny),vertex((i+1)/nx,j/ny),vertex((i+1)/nx,(j+1)/ny),vertex(i/nx,(j+1)/ny)
                    append_triangle([a,c,b],rigid);append_triangle([a,d,c],rigid)
        return out

    def surface(x,y,effect,level,offset=.028):
        p=transform([x,y,-4.6],effect,level);p[2]-=offset;return p

    def spot(m,name,cx,cy,rx,ry,mat,effect,level,seed,offset=.035):
        rng=random.Random(seed);n=18;pts=[surface(cx,cy,effect,level,offset)]
        for i in range(n):
            a=i*TAU/n;w=.82+rng.random()*.30
            pts.append(surface(cx+rx*math.cos(a)*w,cy+ry*math.sin(a)*w,effect,level,offset))
        faces=[[0,i+1,(i+1)%n+1] for i in range(n)]
        m.mesh(name,pts,faces+[f[::-1] for f in faces],mat)

    def line(m,name,path,width,mat,effect,level):
        # Thin surface strips follow both deformation and a face's local coordinates.
        pts=[];faces=[]
        for (x,y),(X,Y) in zip(path,path[1:]):
            length=math.hypot(X-x,Y-y);steps=max(1,math.ceil(length/.16))
            dx=-(Y-y)/max(length,1e-9)*width/2;dy=(X-x)/max(length,1e-9)*width/2
            for i in range(steps):
                t=i/steps;u=(i+1)/steps;a=x+(X-x)*t;b=y+(Y-y)*t;A=x+(X-x)*u;B=y+(Y-y)*u
                k=len(pts);pts.extend([surface(a-dx,b-dy,effect,level,.05),surface(A-dx,B-dy,effect,level,.05),surface(A+dx,B+dy,effect,level,.05),surface(a+dx,b+dy,effect,level,.05)])
                faces.extend([[k,k+1,k+2],[k,k+2,k+3],[k+2,k+1,k],[k+3,k+2,k]])
        m.mesh(name,pts,faces,mat)

    def injury(m,effect,level):
        if level==0:return
        if effect=='scratch':
            if level>=2:spot(m,'abraded_skin',-4.25,1.65,1.12,.61,'abrasion',effect,level,132)
            for i in range(2+level*2):
                x=-5.2+i*.27;y=1.35+(i%3)*.22;length=.48+level*.26
                line(m,'scrape_'+str(i),[(x,y),(x+.25,y+.28),(x+length*.6,y+length)],.045+.01*level,'wound_edge',effect,level)
        elif effect=='tear':
            scale=.48+level*.17
            spot(m,'ragged_skin_lip',-4.3,1.75,1.36*scale,.95*scale,'skin_light',effect,level,43,.045)
            spot(m,'torn_rim',-4.3,1.75,1.19*scale,.80*scale,'wound_edge',effect,level,43,.060)
            spot(m,'recessed_filling',-4.3,1.75,.96*scale,.63*scale,'deep_filling',effect,level,43,.080)
            line(m,'torn_lower_edge',[(-5.1,1.15),(-4.7,1.0),(-4.3,1.14),(-3.75,1.22)],.065,'filling_wet',effect,level)
        elif effect=='dent':
            # Local inset uses the body's subdivided surface; crease is only a cue.
            extent=.5+level/6
            for side in (-1,1):
                path=[(-4.2+side*extent*(1.35-.15*math.sin(i*math.pi/7)),2+extent*(-.9+i*.25)) for i in range(8)]
                line(m,'compression_crease_'+str(side),path,.025+.035*level,'dry_line',effect,level)
        elif effect=='scorch':
            rng=random.Random(926)
            for i in range(5+level*5):
                x=rng.uniform(-5.35,5.35);y=rng.uniform(.15,.30+level*.38);rx=rng.uniform(.35,.7);ry=rng.uniform(.18,.40)
                spot(m,'scorch_edge_'+str(i),x,y,rx,ry,'burn_edge',effect,level,i,.032)
                spot(m,'charred_skin_'+str(i),x,y,rx*.83,ry*.74,'charcoal',effect,level,i,.055)
        elif effect=='desiccation':
            for i,x in enumerate([-5.3,-3.0,-2.1,2.1,3.0,5.3]):
                y=.45+(i%3)*.20;length=.45+level*.60
                line(m,'dry_crease_'+str(i),[(x,y),(x+.10,y+length*.35),(x-.09,y+length*.67),(x+.07,y+length)],.021+.014*level,'dry_line',effect,level)

    def mold(m,level):
        if not level:return
        y=320+(level-1)*64;uv=[[256,y+64],[384,y+64],[384,y],[256,y]]
        planes=[('front',[[-5.7,.1,-4.63],[5.7,.1,-4.63],[5.7,5.1,-4.63],[-5.7,5.1,-4.63]]),
                ('rear',[[4.75,.4,8.405],[-4.75,.4,8.405],[-4.75,3.6,8.405],[4.75,3.6,8.405]])]
        for side in [-1,1]:
            planes.append(('side_'+str(side),[[side*6.68,1.1,-2.8],[side*6.68,1.1,3.7],[side*6.68,2.1,3.7],[side*6.68,2.1,-2.8]]))
        for name,points in planes:
            faces={}
            for i,tri in enumerate([[0,2,1],[0,3,2],[1,2,0],[2,3,0]]):
                faces[str(i)]=dict(vertices=[str(k) for k in tri],uv={str(k):uv[k] for k in tri},texture=0)
            m.parts.append(dict(name='mold_surface_'+name,type='mesh',origin=[0,0,0],rotation=[0,0,0],vertices={str(i):p for i,p in enumerate(points)},faces=faces))

    def leak(m,mode):
        emitter=[-4.3,1.75,-4.65]
        # Visible outlet is a surface opening; all leaked material has unit metadata.
        spot(m,'leak_outlet',emitter[0],emitter[1],.43,.28,'deep_filling','leak',2,840,.05)
        rng=random.Random(813)
        for unit in range(1,13):
            begin=len(m.parts);a=unit*2.399963
            if mode==0:
                p=[emitter[0]+math.cos(a)*.21,emitter[1]+math.sin(a)*.13,emitter[2]-.07-(unit-1)*.023]
                m.ellipsoid('seeping_filling_'+str(unit),p,[.18,.15,.21],'filling_wet',8,5)
                m.ellipsoid('wet_glint_'+str(unit),[p[0]-.03,p[1]+.04,p[2]-.105],[.035,.016,.027],'filling_glint',6,4)
            elif mode==1:
                ox=math.cos(a)*.23;drop=.35+unit*.09
                path=[[emitter[0]+ox,emitter[1]-.03,emitter[2]-.02],[emitter[0]+ox*.9,emitter[1]-drop*.55,emitter[2]-.20],[emitter[0]+ox*.75,emitter[1]-drop,emitter[2]-.28]]
                m.tube('filling_thread_'+str(unit),path,[.10,.055,.065],'filling_wet',6)
                m.ellipsoid('hanging_drop_'+str(unit),path[-1],[.15,.22,.14],'deep_filling',8,5)
            elif mode==2:
                origin=emitter[:];velocity=[rng.uniform(-2.1,1.6),rng.uniform(1.8,3.5),rng.uniform(-5.0,-2.6)]
                gravity=10.;duration=(velocity[1]+math.sqrt(velocity[1]**2+2*gravity*(origin[1]-.1)))/gravity
                default_time=.50;age=duration*default_time
                position=[origin[0]+velocity[0]*age,origin[1]+velocity[1]*age-gravity*age*age/2,origin[2]+velocity[2]*age]
                m.ellipsoid('flying_portion_'+str(unit),position,[.25,.30,.23],'deep_filling',8,5)
                for part in m.parts[begin:]:part['motion']=dict(origin=origin,velocity=velocity,gravity=gravity,duration=duration,default_time=default_time,center=position)
            else:
                r=.55*math.sqrt(unit-.5);p=[emitter[0]+math.cos(a)*r,.105,emitter[2]-1.5+math.sin(a)*r*.78]
                m.ellipsoid('ground_filling_'+str(unit),p,[.75,.105,.60],'deep_filling',10,5)
                m.ellipsoid('ground_wet_glint_'+str(unit),[p[0]-.05,.19,p[2]-.04],[.36,.018,.23],'filling_wet',8,4)
            for part in m.parts[begin:]:part['unit']=unit

    def make_scene(id,species,effect,level):
        m=model(id,id,species,effect,level)
        display_level=2 if effect=='leak' else level
        verts=actor_vertices(species,effect,display_level)
        G['import_triangles'](m,verts,atlases[species],ROOT/f'art/source/standard-runtime/{species}/runtime.model.json')
        m.parts[0]['name']='actor_'+species;m.sources=copy.deepcopy(source_records[species])
        if effect=='mold':mold(m,level)
        elif effect=='leak':leak(m,level)
        else:injury(m,effect,level)
        return m

    # Export every authored discrete state, not just the currently selected thumbnail.
    defaults={}
    for effect,label,note in EFFECTS:
        banks['states'][effect]={}
        for species in ['reimu','marisa']:
            banks['states'][effect][species]={}
            for level in range(4):
                id=f'{effect}_{species}_{level}';m=make_scene(id,species,effect,level)
                entry,check=G['export_model'](m,variant_root/id,f'../../body-variants/{id}/{id}.bbmodel')
                banks['states'][effect][species][str(level)]={k:entry[k] for k in ['model','dims','bbmodel','triangles']}
                checks.append(check)
                if species=='reimu' and level==(1 if effect=='leak' else 2):defaults[effect]=copy.deepcopy(m)
        m=defaults[effect];m.id='body_'+effect;m.label=('伤情 · ' if effect not in ('mold','leak') else '')+label
        m.note=note+' 本页是角色挂接候选，尚未接入 Minecraft。'
        m.state='过程可切换' if effect=='leak' else '程度可切换'
        m.body_effects=dict(effect=effect,default_species='reimu',default_state=1 if effect=='leak' else 2)
        m.controls={'species':dict(value=0,label='角色',choices=['灵梦','魔理沙'],keys=['reimu','marisa'])}
        if effect=='leak':
            m.controls['mode']=dict(value=1,label='漏馅阶段',choices=['渗出','拉丝','飞散','落地'])
            m.controls['quantity']=dict(min=0,max=12,value=4,step=1,label='实际漏馅量',unit='份',binding='body.releasedFillingUnits')
            m.controls['time']=dict(min=0,max=1,value=.5,step=.02,label='飞散过程',unit='',when={'mode':2})
        else:m.controls['severity']=dict(min=0,max=3,value=2,step=1,label='伤情程度' if effect!='mold' else '油霉程度',unit='级',labels=['正常／清除','轻度','中度','重度'],binding='body.mold' if effect=='mold' else 'body.damageAppearance.'+effect)
        m.controls['ownerScale']=dict(min=.3,max=1.2,value=1,step=.05,label='实际体型',unit='倍',binding='owner.bodyScale')
        G['models'].append(m)

    recipe={
        'version':1,'status':'pending_user_confirmation','scope':['six graded injuries','mold spread and clearing','four filling stages'],
        'source_actors':source_records,'body_height_reference':9.54,'units_per_block':16,
        'deformation':{'compression':{'max_y_scale':.52,'max_x_scale':1.23,'max_z_scale':1.13},'desiccation':{'max_x_scale':.8,'max_y_scale':.87,'max_z_scale':.82},'dent_depth_by_grade':[0,.35,.95,1.9],'tear_max_depth':.70,'accessories':'bow/hat/brim stay rigid and translate with their transformed root','accessory_roots':{'reimu':[0,9.328,8.275],'marisa':[0,9.54,1.6351562]}},
        'zero_states':'level 0 removes added damage/mold and restores baseline; filling quantity 0 keeps outlet but removes all released material',
        'filling_emitter':[-4.3,1.75,-4.65], 'flight':'each portion keeps emitter, velocity, gravity, landing time; normalized time 0–1; visual sample only',
        'grade_semantics':'three visual grades for review; no implicit mapping from current game wound/mold counters',
        'reference':'REFERENCES-BODY.md','deferred':['death-cause remains','interaction tools and cages','Minecraft runtime adapter'],
    }
    banks['surface_input']={'actors':actors,'textures':banks['textures'],'sources':source_records,'materials':{name:[(256+(i%8)*32+8)/512,(256+(i//8)*32+8)/512] for i,name in enumerate(COLORS)}}
    G['write'](HERE/'wound-source.json',banks['surface_input'])
    G['write'](HERE/'body-effects-recipe.json',recipe)
    return banks,checks
