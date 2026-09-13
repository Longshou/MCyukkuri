"""Second art revision: owner scale, actual units, pet food and compact broods.
Called by build.py before exports. Previous candidates remain in archive/r1.zip.
"""
import copy, json, math
from PIL import Image, ImageDraw, ImageFont
from native_forage import add_native_forage, add_native_book

def revise(ctx):
    G=ctx;models=G['models'];Model=G['Model'];ROOT=G['ROOT'];HERE=G['HERE'];TAU=math.tau
    old={m.id:m for m in models}
    obsolete={'food_bowl','food_bowl_full','food_bowl_low','oil_grain','bandage','herbal_medicine','body_bandage','yukkuri_remains','birth_stem','spent_stem','house_egg','house_egg_cracked','leaked_filling'}
    models[:]=[m for m in models if m.id not in obsolete]
    def new(id,label,category,note,state='常态'):
        m=Model(id,label,category,note,state);models.append(m);return m
    def owner(m):m.controls['ownerScale']=dict(min=.24,max=1.4,value=1,step=.01,label='油库里实际体型',binding='owner.bodyScale',rule='所有线性尺寸 = 基准尺寸 × 排出者／佩戴者／被埋葬者的实际体型')
    for id in ['yukkuri_waste','burial_mound','reimu_ribbon','marisa_hat']:owner(old[id])
    old['yukkuri_waste'].label='独立便便'
    old['yukkuri_waste'].note='厕所之外的独立便便模型；本轮核读八景 U9.8 的「うんこ-でふぉ」及「うんこ」原始图层。体积随排出者体型变化。'
    old['burial_mound'].note='覆土轮廓随被埋葬油库里的体型缩放；不是固定大小的统一土堆。'
    old['reimu_ribbon'].note='沿用角色真实蝴蝶结网格。脱落后保存佩戴者体型，几何按相同比例显示。'
    old['marisa_hat'].note='沿用角色真实帽子网格。脱落后保存佩戴者体型，几何按相同比例显示。'
    notebook=old['field_notebook'];notebook.parts=[];notebook.sources=[]
    notebook.note='直接沿用原版书的像素轮廓与贴图；游戏中的观察手册原本已引用 minecraft:item/book。'
    add_native_book(notebook)

    def garbage(m):
        # Each portion is recognizable kitchen refuse rather than generic pellets.
        for i in range(96):
            unit=i//12+1;kind=i%4;layer=i//20;j=i%20
            a=i*2.399963;radius=3.55-max(0,layer-1)*.36
            r=math.sqrt((j+.5)/20)*radius;x=r*math.cos(a);z=r*.88*math.sin(a);angle=a+.5
            begin=len(m.parts);c=math.cos(angle);s=math.sin(angle);base=.93+layer*.76+.36*(1-r/radius)+(.18*math.sin(i*1.7) if layer else 0)
            def p(u,v,y=0):return [x+u*c-v*s,max(.68,base+y+u*.23*math.sin(i*2.1)),z+u*s+v*c]
            if kind==0:
                m.tube(f'fish_spine_{unit}',[p(-1.05,0),p(0,.03),p(1.3,0)],.095,'shell',6)
                m.ellipsoid(f'fish_skull_{unit}',p(-1.38,0,.04),[.48,.2,.42],'shell',10,5)
                m.ellipsoid(f'fish_eye_socket_{unit}',p(-1.47,-.13,.235),[.13,.027,.13],'black',8,4)
                # Fine paired ribs and a broad forked tail read clearly from above.
                for k in range(6):
                    u=-.8+k*.34;w=.57*(1-k*.085)
                    for side in (-1,1):m.tube(f'fish_rib_{unit}_{k}_{side}',[p(u,0),p(u+.12,side*w*.6,.055),p(u+.25,side*w,.02)],.055,'shell',5)
                m.mesh(f'fish_tail_{unit}',[p(1.15,0),p(1.95,-.55),p(1.68,0),p(1.95,.55)],[[0,1,2],[0,2,3],[2,1,0],[3,2,0]],'shell')
            elif kind==1:
                m.ellipsoid(f'apple_core_{unit}',p(0,0),[.5,.23,.29],'paper',9,5)
                for end in (-.48,.48):m.ellipsoid(f'apple_peel_{unit}_{end}',p(end,0),[.22,.26,.42],'red',9,5)
                m.tube(f'apple_stalk_{unit}',[p(-.55,0,.18),p(-.73,0,.3)],.045,'wood_dark',5)
            elif kind==2:
                m.mesh(f'broken_eggshell_{unit}',[p(-.45,-.35,.2),p(.32,-.36,.08),p(.5,.1,.27),p(.05,.42,.17),p(-.44,.24,.29),p(0,0,-.13)],[[0,1,5],[1,2,5],[2,3,5],[3,4,5],[4,0,5],[5,1,0],[5,2,1],[5,3,2],[5,4,3],[5,0,4]],'shell')
            else:
                m.mesh(f'crumpled_wrapper_{unit}',[p(-.7,-.45,0),p(.65,-.45,.07),p(.6,.4,0),p(-.65,.5,.05),p(0,0,.32)],[[0,1,4],[1,2,4],[2,3,4],[3,0,4],[4,1,0],[4,2,1],[4,3,2],[4,0,3]],'trash_paper')
                m.tube(f'peel_strip_{unit}',[p(-.6,-.3,.1),p(-.2,.1,.24),p(.2,.2,.16),p(.5,0,.06)],.085,'orange',5)
            for part in m.parts[begin:]:
                part['unit']=unit;part['name']+='_'+str(i)

    # Dedicated stainless pet bowl: splayed skirt, rolled lip, non-slip base.
    def pet_bowl(m):
        m.lathe('rubber_foot',[(0,0),(6.85,0),(7.08,.13),(7.02,.33),(0,.33)],'rubber',n=24,sz=.91)
        m.lathe('steel_skirt',[(7.02,.33),(6.8,.5),(5.9,2.0)],'steel',n=24,sz=.91)
        m.lathe('rolled_rim',[(5.9,2),(5.83,2.18),(5.6,2.22),(5.38,2.07)],'steel_light',n=24,sz=.91)
        m.lathe('inner_wall',[(5.38,2.07),(4.8,.72),(4.5,.58),(0,.58)],'steel_dark',n=24,sz=.91)
        # A second shallow brushed ring follows the skirt, without intersections.
        m.lathe('brushed_highlight',[(6.92,.425),(6.85,.53),(6.64,.855)],'steel_light',n=24,sz=.91)
    tiers=[('regular','普通款','bag_regular'),('budget','廉价款','bag_budget'),('premium','高级款','bag_premium')]
    for content,label in [('empty','空盆'),('regular','普通油粮'),('budget','廉价油粮'),('premium','高级油粮'),('forage','剪短散草'),('wheat','横放麦穗'),('garbage','垃圾')]:
        m=new('food_bowl' if content=='empty' else 'food_bowl_'+content,'宠物食盆' if content=='empty' else '食盆 · '+label,'block','低矮不锈钢宠物盆。三档油粮共用相同颗粒外观，只区分属性；草料直接复用原版短草／小麦。',label)
        pet_bowl(m)
        if content=='empty':continue
        m.controls['quantity']=dict(min=0,max=8,value=8,step=1,label='盆内实际份量',binding='bowl.contents.units',unit='份',rule='0 份空盆；随份量从盆底分层堆高；8 份高于盆沿形成近溢出的满堆')
        if content in ('forage','wheat'):
            add_native_forage(m,content)
            m.note='原版草料经过整理后放入盆中：短草剪成低伏散段，麦穗横放；沿用原版颜色与物品，不另造一种草。'
            continue
        if content=='garbage':
            garbage(m);m.note='吃剩的鱼骨、果核、破蛋壳、皱纸和果皮；按实际份量出现，鱼骨包含鱼头、脊骨、肋刺和尾骨。'
            continue
        for unit in range(1,9):
            begin=len(m.parts)
            for k in range(30):
                i=(unit-1)*30+k
                layer=0 if i<80 else 1 if i<152 else 2 if i<208 else 3
                j=i-[0,80,152,208][layer];count=[80,72,56,32][layer];radius=[4.4,4.3,3.65,2.8][layer]
                a=i*2.399963;r=math.sqrt((j+.5)/count)*radius;x=r*math.cos(a);z=r*.88*math.sin(a)
                y=[.98,1.75,2.52,3.29][layer]+.48*(1-r/radius)
                m.ellipsoid('food_'+str(i),[x,y,z],[.53,.37,.43],'grain',8,5)
            for e in m.parts[begin:]:e['unit']=unit

    font_file='C:/Windows/Fonts/msyh.ttc'
    def font(size):return ImageFont.truetype(font_file,size)
    def face_logo(d,cx,cy,s=1):
        d.ellipse((cx-57*s,cy-38*s,cx+57*s,cy+38*s),fill='#f4dfb4',outline='#594a39',width=max(1,int(2*s)))
        d.polygon([(cx-49*s,cy-11*s),(cx-46*s,cy-35*s),(cx+37*s,cy-33*s),(cx+52*s,cy-10*s),(cx+26*s,cy-23*s),(cx+14*s,cy-12*s),(cx,cy-27*s),(cx-12*s,cy-13*s),(cx-25*s,cy-25*s)],fill='#4a3830')
        for x in [-23,23]:d.ellipse((cx+(x-5)*s,cy-6*s,cx+(x+5)*s,cy+9*s),fill='#43392f')
        d.arc((cx-15*s,cy+3*s,cx,cy+19*s),0,180,fill='#594a39',width=max(1,int(2*s)));d.arc((cx,cy+3*s,cx+15*s,cy+19*s),0,180,fill='#594a39',width=max(1,int(2*s)))
        d.polygon([(cx-21*s,cy-34*s),(cx-38*s,cy-59*s),(cx-9*s,cy-54*s),(cx+4*s,cy-37*s)],fill='#b74b4e')
        d.polygon([(cx-9*s,cy-37*s),(cx+12*s,cy-61*s),(cx+22*s,cy-37*s)],fill='#b74b4e')
    def custom_texture(m):
        im=Image.new('RGBA',(512,512),(0,0,0,0));im.paste(G['atlas'],(0,0));m.texture=im;return ImageDraw.Draw(im)
    def poster_quad(m,name,points,uv):
        m.parts.append(dict(name=name,type='mesh',origin=[0,0,0],rotation=[0,0,0],vertices={str(i):p for i,p in enumerate(points)},faces={'0':dict(vertices=['0','2','1'],uv={str(i):uv[i] for i in [0,1,2]},texture=0),'1':dict(vertices=['0','3','2'],uv={str(i):uv[i] for i in [0,2,3]},texture=0)}))
    for tier,label,mat in tiers:
        m=new('oil_grain_'+tier,'袋装油粮 · '+label,'item','有封口、侧折和自绘宣传图的油粮袋；'+label+'采用独立包装配色，倒入食盆后显示对应颗粒。',label)
        profile=[(0,2.25,.78),(.45,2.95,1.2),(1.15,3.02,1.26),(7.4,2.82,1.26),(8.1,2.62,.9),(9,2.76,.15)]
        pts=[]
        for y,w,d in profile:pts.extend([[-w*.82,y,-d],[w*.82,y,-d],[w,y,-d*.6],[w,y,d*.6],[w*.82,y,d],[-w*.82,y,d],[-w,y,d*.6],[-w,y,-d*.6]])
        fs=[list(range(8))]
        for row in range(len(profile)-1):
            for i in range(8):a=row*8+i;b=row*8+(i+1)%8;fs.append([a,a+8,b+8,b])
        fs.append(list(reversed(range(40,48))));m.mesh('sealed_bag',pts,fs,mat)
        m.box('heat_seal',[0,8.95,0],[5.62,.32,.34],mat)
        for j in range(13):m.box('seal_rib_'+str(j),[-2.5+j*.42,8.97,-.182],[.065,.24,.016],'cloth_seam')
        d=custom_texture(m);bg={'regular':'#e9ead5','budget':'#eee0ba','premium':'#f2e6be'}[tier];accent={'regular':'#446954','budget':'#8d6234','premium':'#58456a'}[tier]
        d.rectangle((256,0,511,383),fill=bg);d.rectangle((256,0,511,59),fill=accent)
        d.text((384,30),'ゆっくり',font=font(30),anchor='mm',fill='#fff9e9')
        d.text((384,91),'油粮',font=font(45),anchor='mm',fill=accent)
        face_logo(d,384,194,1.35)
        d.text((384,278),label,font=font(30),anchor='mm',fill=accent)
        slogan={'regular':'每天悠闲一餐','budget':'实惠 · 饱饱的','premium':'精选 · 香香的'}[tier]
        d.text((384,323),slogan,font=font(19),anchor='mm',fill=accent)
        d.line((280,349,488,349),fill=accent,width=2);d.text((384,368),'YUKKURI FOOD',font=font(12),anchor='mm',fill=accent)
        poster_quad(m,'printed_front',[[-2.22,1.2,-1.275],[2.22,1.2,-1.275],[2.22,7.38,-1.275],[-2.22,7.38,-1.275]],[[256,383],[511,383],[511,0],[256,0]])

    m=new('leaked_filling','漏出馅料','item','每个馅料单位生成一份团块，显示数量直接读取实际漏出量；零份不显示。')
    m.controls['quantity']=dict(min=0,max=12,value=4,step=1,label='实际出馅数量',binding='filling.leakedUnits',unit='份',rule='显示 0–12 份独立团块，不用固定一大团代表所有出馅量')
    for unit in range(1,13):
        begin=len(m.parts);a=(unit-1)*2.39996;r=.65*math.sqrt(unit-1);x=r*math.cos(a);z=r*.7*math.sin(a)
        m.ellipsoid('paste_unit_'+str(unit),[x,.32,z],[.73,.32,.53],'filling',10,6)
        m.ellipsoid('paste_ridge_'+str(unit),[x-.12,.55,z],[.36,.16,.26],'filling_light',8,5)
        for e in m.parts[begin:]:e['unit']=unit

    for medicine in [False,True]:
        id='mold_remover' if medicine else 'orange_juice';label='去霉药' if medicine else '橙汁'
        m=new(id,label,'item','清除油霉；药效不与物理伤口治疗混用。' if medicine else '用于治愈油库里的所有物理性创伤；包装采用橙子图案。')
        mat='medicine' if medicine else 'orange';cap='medicine_cap' if medicine else 'orange_light'
        m.lathe('bottle',[(0,0),(1.34,0),(1.6,.25),(1.6,4.5),(1.1,5.2),(.82,5.35),(.82,5.9),(0,5.9)],mat,n=20,sz=1)
        m.lathe('screw_cap',[(0,5.66),(.99,5.66),(1.02,5.78),(1.02,6.25),(.92,6.34),(0,6.34)],cap,n=20)
        for i in range(20):
            a=i*TAU/20;m.tube('cap_ridge_'+str(i),[[1.025*math.cos(a),5.78,1.025*math.sin(a)],[1.025*math.cos(a),6.21,1.025*math.sin(a)]],.035,cap,4)
        d=custom_texture(m);d.rectangle((256,0,511,319),fill='#f2f0df');d.rectangle((256,0,511,49),fill='#466d82' if medicine else '#dd8c32')
        d.text((384,26),'悠闲护理',font=font(23),anchor='mm',fill='#ffffff')
        d.text((384,86),label,font=font(39),anchor='mm',fill='#466d82' if medicine else '#a96125')
        if medicine:
            d.ellipse((339,140,429,230),fill='#a0b47d')
            for a in range(0,360,45):
                x=384+58*math.cos(math.radians(a));y=185+58*math.sin(math.radians(a));d.line((384,185,x,y),fill='#7d975e',width=6);d.ellipse((x-7,y-7,x+7,y+7),fill='#7d975e')
            d.line((329,238,438,131),fill='#466d82',width=13)
            d.text((384,282),'清除油霉',font=font(24),anchor='mm',fill='#466d82')
        else:
            d.ellipse((326,131,442,247),fill='#ec9a38',outline='#d1842b',width=4)
            d.polygon([(381,138),(388,114),(421,108),(409,132)],fill='#6f914f')
            d.arc((340,151,427,231),210,270,fill='#ffd184',width=7)
            d.text((384,282),'修复物理创伤',font=font(22),anchor='mm',fill='#a96125')
        # Label hugs the faceted bottle, using the same 20-sided circumference.
        for i in range(11,19):
            a=i*TAU/20;b=(i+1)*TAU/20;u=256+(i-11)/8*255;U=256+(i-10)/8*255
            pts=[[1.611*math.cos(a),1.15,1.611*math.sin(a)],[1.611*math.cos(b),1.15,1.611*math.sin(b)],[1.611*math.cos(b),4.4,1.611*math.sin(b)],[1.611*math.cos(a),4.4,1.611*math.sin(a)]]
            # Mesh winding follows the outward radial side.
            poster_quad(m,'label_'+str(i),pts,[[u,319],[U,319],[U,0],[u,0]])

    # Rebuild the egg as a continuous asymmetric ovoid, without flat pole caps.
    egg_height=1.6;egg_sides=40;egg_rings=32;egg_depth=.94
    egg_profile=[]
    for j in range(egg_rings+1):
        t=math.pi*j/egg_rings
        egg_profile.append((.49*math.sin(t)*(1+.17*math.cos(t)),egg_height*(1-math.cos(t))/2))
    for cracked in [False,True]:
        m=new('house_egg_cracked' if cracked else 'house_egg','家油卵 · 裂壳' if cracked else '家油卵','attachment','竖长卵形：下部圆钝饱满，上部渐收，顶底连续圆收；细裂纹贴合当前壳面。','裂壳' if cracked else '完整')
        m.lathe('continuous_ovoid_shell',egg_profile,'shell',n=egg_sides,sz=egg_depth)
        if cracked:
            def surface(a,y):
                row=next(i for i in range(egg_rings) if egg_profile[i+1][1]>=y)
                r0,y0=egg_profile[row];r1,y1=egg_profile[row+1];r=r0+(r1-r0)*(y-y0)/(y1-y0)
                f=(a%TAU)/TAU*egg_sides;idx=math.floor(f);u=f-idx
                A=idx*TAU/egg_sides;B=(idx+1)*TAU/egg_sides
                # Interpolate on the actual polygon face, offset just above the shell.
                return [(r+.003)*((1-u)*math.cos(A)+u*math.cos(B)),y,(r+.003)*((1-u)*math.sin(A)+u*math.sin(B))*egg_depth]
            paths=[[(.10,1.40),(-.04,1.29),(.16,1.18),(.02,1.06),(.24,.94),(.10,.83)],[(.16,1.18),(.36,1.20),(.49,1.12)],[(.02,1.06),(-.22,1.03),(-.34,.94)]]
            for branch,path in enumerate(paths):
                pts=[];faces=[]
                for (a,y),(b,Y) in zip(path,path[1:]):
                    steps=max(2,math.ceil(max(abs(b-a)/.035,abs(Y-y)/.018)))
                    for k in range(steps):
                        t=k/steps;T=(k+1)/steps;A=a+(b-a)*t-math.pi/2;B=a+(b-a)*T-math.pi/2;h=y+(Y-y)*t;H=y+(Y-y)*T
                        start=len(pts);pts.extend([surface(A-.014,h),surface(B-.014,H),surface(B+.014,H),surface(A+.014,h)])
                        faces.extend([[start,start+1,start+2],[start,start+2,start+3],[start+2,start+1,start],[start+3,start+2,start]])
                m.mesh('shell_hairline_'+str(branch),pts,faces,'shell_shade')

    # Compact stalk candidates reuse the actual mother/child asset and source route.
    family=json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/family.json').read_text(encoding='utf-8'))
    rules=dict(version=2,status='art_preview_candidate_not_runtime',units_per_block=16,owner_scale=['yukkuri_waste','burial_mound','reimu_ribbon','marisa_hat'],filling_units='one geometric portion per actual leaked unit; zero is invisible',bowl_capacity=8,food_types=['regular','budget','premium','forage','garbage'],treatment=dict(orange_juice='heal all physical injuries',mold_remover='remove mold'),excluded=['bandage','body_bandage'],deferred=dict(yukkuri_remains='death-cause-specific shapes, not represented by a single sleeping model'),family=dict(child_scale=.18,previous_child_scale=.30,count_length_scale=[.72,.82,1.08,1.38,1.72],previous_count_length_scale=family['lengthScale'],source='art/props/family-life/recipe.json'))
    rules.update(revision='2.4',shared_grain_visual='food_bowl_regular; budget and premium use identical vertices and texture',forage_items=['minecraft:short_grass','minecraft:tall_grass','minecraft:wheat'],forage_tag='mcyukkuri:yukkuri_forage',forage_visual='cut horizontal grass ribbons sampled from vanilla colours; detached wheat heads laid flat; no separate mod grass item',garbage_visual=['fish skeleton','apple core','broken eggshell','crumpled wrapper','fruit peel'],notebook_visual='minecraft:item/book; vanilla generated item already used at runtime')
    rules['egg_shape']=dict(height=1.6,radial_segments=40,profile_segments=32,profile='asymmetric ovoid; single-point rounded poles; no flat caps',cracks='thin branched strips interpolated on the actual shell facets')
    rules['bowl_fill']=dict(rim_height=2.22,full_heap_target_height=[4.0,4.7],rule='fill bottom, then build overlapping layers and a mound above the rim at 8 units')
    rules['food_types']=['regular','budget','premium','forage','wheat','garbage']
    G['write'](HERE/'presentation-rules.json',rules)
    for species in ['reimu','marisa']:
        path=ROOT/f'art/source/standard-runtime/{species}/runtime.model.json';data=json.loads(path.read_text(encoding='utf-8'))
        modelverts=[]
        for part in data['parts']:
            if not part.get('anatomy'):modelverts+=data['vertices'][part['start']*8:(part['start']+part['count'])*8]
        config=family['variants'][species]
        m=new('birth_family_'+species,'短茎与子油 · '+('灵梦' if species=='reimu' else '魔理沙'),'attachment','连同真实母体和子油检查比例。子油由 0.30 调为 0.18；按 1–5 胎检查短茎、帽沿和挂点。此处是候选组合预览。')
        tex=G['atlas'].copy();tex.paste(Image.open(path.parent/'textures/neutral.png').convert('RGBA'),(0,0));tex.paste(Image.open(path.parent/'textures/sleep.png').convert('RGBA'),(128,0))
        root=config['root'];stem=family['stem'];layouts={};child_scale=.18
        for count in range(1,6):
            fac=stem['reference_units_to_model']*rules['family']['count_length_scale'][count-1]
            def route(z,y):
                rise=(y-stem['reference_origin'][1])*fac;clearance=config.get('depth_clearance',2)*min(1,max(0,rise/config.get('clearance_rise',1)))
                return [root[0],root[1]+rise,root[2]+z*fac-clearance]
            arc=[route(z,y) for z,y in stem['centerline']]
            anchor=config.get('hanging_anchor',[0,config['crown_height'],0]);children=[]
            for z,y in stem['socket_layouts'][str(count)]:
                p=route(z,y);offset=[p[i]-anchor[i]*child_scale-(.28 if i==1 else 0) for i in range(3)]
                children.append(dict(offset=offset,scale=child_scale,socket=p,anchor=[p[0],p[1]-.28,p[2]]))
            layouts[str(count)]=dict(arc=arc,children=children,radius=.14+.012*count)
        def poseverts(offset,scale,child):
            out=[]
            for i in range(0,len(modelverts),8):
                v=modelverts[i:i+8];out.extend([*[v[k]*scale+offset[k] for k in range(3)],v[3]*.5+(.5 if child else 0),v[4]*.5,*v[5:8]])
            return out
        master=poseverts([0,0,0],1,False)
        for child in layouts['3']['children']:master+=poseverts(child['offset'],child_scale,True)
        G['import_triangles'](m,master,tex,path)
        m.tube('compact_stem',layouts['3']['arc'],layouts['3']['radius'],'green',8)
        for i,child in enumerate(layouts['3']['children']):m.tube('pendant_'+str(i),[child['socket'],child['anchor']],.07,'green_light',6)
        color_index=G['NAMES'].index('green')
        m.family=dict(species=species,base_vertices=modelverts,layouts=layouts,count=3,editable_default_count=3,stem_uv=[((color_index%8)*32+16)/256,((color_index//8)*32+16)/256])
        m.controls['broodCount']=dict(min=1,max=5,value=3,step=1,label='挂茎子油数量',unit='只')
        m.sources.append(dict(path=(path.parent/'textures/neutral.png').relative_to(ROOT).as_posix(),sha256=G['sha'](path.parent/'textures/neutral.png')))
        m.sources.append(dict(path=(path.parent/'textures/sleep.png').relative_to(ROOT).as_posix(),sha256=G['sha'](path.parent/'textures/sleep.png')))
        m.note+='可编辑下载包含默认三胎组合；一至五胎布局均保存在同包配方。'
    m=new('spent_stem','缩短的枯茎','attachment','缩短后的茎部候选，和繁殖组合中的短茎比例保持对应。')
    line=family['stem']['centerline'];origin=family['stem']['reference_origin'][1];factor=family['stem']['reference_units_to_model']*.82
    m.tube('short_spent_stem',[[0,(y-origin)*factor,z*factor] for z,y in line],.13,'stem_dry',8)
    # Ensure the gallery remains grouped and deterministic.
    order={'block':0,'item':1,'attachment':2};models.sort(key=lambda m:(order[m.category],0 if m.id.startswith('food_bowl') else 1))
