"""Rounded, hand-drawn-style revision based on the user's local Hachikei reference.
Creates native polygon meshes, original face textures, Blockbench projects and GLB.
"""
from pathlib import Path
import math, json, base64, uuid, struct
import numpy as np
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
TAU=math.tau
COLORS={'skin':'#ffdda1','hair':'#35291e','hair_light':'#48382a','hair_dark':'#291f19',
        'gold':'#ffd326','gold_light':'#ffe35d','gold_dark':'#eba510',
        'hat':'#28282a','hat_light':'#333335','white':'#fffdf1','white_shadow':'#e0dfd8',
        'red':'#ec202a','red_light':'#fa4144','red_dark':'#b71822','ink':'#30251f'}

def bez(a,b,c,d,t):
    return [(1-t)**3*a[i]+3*(1-t)**2*t*b[i]+3*(1-t)*t*t*c[i]+t**3*d[i] for i in range(len(a))]

def face_texture(kind):
    S=3; im=Image.new('RGB',(512*S,512*S),COLORS['skin']);d=ImageDraw.Draw(im)
    def points(p):return [(round(x*S),round(y*S)) for x,y in p]
    def ellipse(b,fill,outline=None,width=1):d.ellipse(tuple(round(v*S) for v in b),fill,outline,width*S)
    def line(p,color,w=2):d.line(points(p),fill=color,width=w*S,joint='curve')
    def poly(p,color):d.polygon(points(p),fill=color)
    # Warm, very restrained lower-cheek tint, avoiding the old pixel-square face.
    pix=np.array(im).astype(float)
    y,x=np.mgrid[0:512*S,0:512*S];x=x/S;y=y/S
    tint=np.zeros_like(x)
    for cx in [95,417]:tint+=np.exp(-((x-cx)/37)**2-((y-301)/23)**2)*.3
    target=np.array([243,141,101]);pix=pix*(1-tint[:,:,None])+target*tint[:,:,None]
    warm=np.clip((y-320)/205,0,1)**1.8*.30
    pix=pix*(1-warm[:,:,None])+np.array([251,164,64])*warm[:,:,None]
    im=Image.fromarray(pix.astype('uint8'));d=ImageDraw.Draw(im)
    ink='#352b24'
    for side,cx in [(-1,155),(1,357)]:
        # Very large round/almond eyes, black iris and broad oblique highlights.
        outline=[]
        for a,b,c,e in [((-42,10),(-42,-47),(30,-47),(40,-7)),((40,-7),(46,31),(24,50),(-15,44)),((-15,44),(-38,41),(-45,27),(-42,10))]:
            outline += [bez(a,b,c,e,t) for t in np.linspace(0,1,20)]
        pp=[(cx+side*x,217+y) for x,y in outline]
        poly(pp,'#fffbee');line(pp+[pp[0]],ink,3)
        ellipse((cx-29,179,cx+29,261),'#302922')
        ellipse((cx-20,233,cx+20,257),'#64543b')
        ellipse((cx-22,191,cx+23,245),'#25241e')
        # Sweeping white highlight characteristic of the reference sprite eyes.
        highlight=[]
        for t in np.linspace(0,TAU,60):
            xx=27*math.cos(t); yy=12*math.sin(t)
            angle=side*.55
            highlight.append((cx+xx*math.cos(angle)-yy*math.sin(angle)-side*3,198+xx*math.sin(angle)+yy*math.cos(angle)))
        poly(highlight,'#fffef5')
        ellipse((cx-side*15-6,238,cx-side*15+6,250),'#fffceb')
        upper=[(cx+side*x,217+y) for x,y in outline[:20]];line(upper,ink,4)
        # Fine eyebrows follow the eye, not sleepy horizontal eyelids.
        brow=[(cx+side*x,165-4*math.sin(t*math.pi)) for t,x in zip(np.linspace(0,1,25),np.linspace(-29,27,25))]
        line(brow,'#70502c' if kind=='marisa' else '#4d3523',2)
    # Open, small red happy mouth, as in the local game screenshot.
    mouth=[]
    for a,b,c,e in [((229,284),(237,280),(275,280),(283,286)),((283,286),(291,313),(273,327),(256,327)),((256,327),(236,327),(224,308),(229,284))]:
        mouth.extend(bez(a,b,c,e,t) for t in np.linspace(0,1,24))
    poly(mouth,'#d81b19');line(mouth+[mouth[0]],'#a94123',1)
    poly([(231,286),(281,286),(282,297),(230,297)],'#a92116')
    ellipse((239,310,275,322),'#f24425')
    for cx in [103,409]:
        for offset in [-8,0,8]:line([(cx+offset,286),(cx+offset-3,297)],'#db9170',1)
    im=im.resize((512,512),Image.Resampling.LANCZOS).convert('RGBA')
    p=ROOT/'textures'/f'{kind}_round.png';im.save(p)
    return 'data:image/png;base64,'+base64.b64encode(p.read_bytes()).decode()

def build(kind):
    meshes=[]
    def mesh(name,v,f,color,group='head',uv=None):
        meshes.append(dict(name=name,vertices=[[round(float(a),5) for a in p] for p in v],faces=f,color=color,group=group,
                           uv=uv if uv is not None else [[0,0] for p in v]))
    def ellipsoid(name,center,radius,color,group='head',nu=48,nv=24,face=False):
        v=[];uv=[];f=[];bf=[]
        for i in range(nv+1):
            theta=.0001+(math.pi-.0002)*i/nv
            for j in range(nu+1):
                phi=-math.pi+TAU*j/nu
                x=radius[0]*math.sin(theta)*math.sin(phi);y=radius[1]*math.cos(theta);z=radius[2]*math.sin(theta)*math.cos(phi)
                p=[center[0]+x,max(.13,center[1]+y),center[2]+z];v.append(p)
                uv.append([.5+x/(radius[0]*2),(p[1]-.0)/11.1])
        for i in range(nv):
            for j in range(nu):
                a=i*(nu+1)+j;b=a+nu+1
                target=f if (not face or math.cos(-math.pi+TAU*(j+.5)/nu)>0) else bf
                target.extend([[a,b,b+1],[a,b+1,a+1]])
        mesh(name,v,f,'face' if face else color,group,uv)
        if bf:mesh(name+'_back',v,bf,color,group)
    def tube(name,centers,radii,color,group='hair',segments=14,orient=False):
        v=[];f=[]
        for index,((x,y,z),(rx,rz)) in enumerate(zip(centers,radii)):
            if orient:
                tangent=np.array(centers[min(index+1,len(centers)-1)])-np.array(centers[max(0,index-1)])
                tangent=tangent/np.linalg.norm(tangent);axis=np.cross(tangent,[0,0,1]);axis=axis/np.linalg.norm(axis);other=np.cross(axis,tangent)
            for j in range(segments):
                a=TAU*j/segments
                v.append((np.array([x,y,z])+axis*rx*math.cos(a)+other*rz*math.sin(a)).tolist() if orient else [x+rx*math.cos(a),y,z+rz*math.sin(a)])
        for i in range(len(centers)-1):
            for j in range(segments):
                a=i*segments+j;b=i*segments+(j+1)%segments;c=b+segments;e=a+segments
                f.extend([[a,c,b],[a,e,c]])
        # Reorient against radial direction, independent of ascending/descending paths.
        for face in f:
            p=np.array([v[k] for k in face]);n=np.cross(p[1]-p[0],p[2]-p[0]);idx=face[0]//segments
            radial=p.mean(axis=0)-np.array(centers[idx])
            if not orient:radial[1]=0
            if np.dot(n,radial)<0:face.reverse()
        mesh(name,v,f,color,group)
    def cloth(name,boundary,color,group='accessory',bulge=.3,thickness=.12):
        boundary=[list(p) for p in boundary]
        area=sum(boundary[i][0]*boundary[(i+1)%len(boundary)][1]-boundary[(i+1)%len(boundary)][0]*boundary[i][1] for i in range(len(boundary)))
        if area<0:boundary.reverse()
        center=np.mean(boundary,axis=0).tolist();front=center[:];front[2]+=bulge
        back=center[:];back[2]-=thickness
        v=[front]+boundary+[back]+[[x,y,z-thickness] for x,y,z in boundary]
        n=len(boundary);f=[]
        for j in range(n):
            a=1+j;b=1+(j+1)%n;c=n+2+j;e=n+2+(j+1)%n
            f.extend([[0,a,b],[n+1,e,c],[a,c,e],[a,e,b]])
        mesh(name,v,f,color,group)
    def ribbon_bow(prefix,cx,cy,z,scale,color,lace=False):
        for side in [-1,1]:
            contours=[]
            for a,b,c,d in [((.7,.15),(2,1.0),(4.9,3.2),(6.2,2.5)),((6.2,2.5),(7.2,1.9),(7.0,-1.8),(5.5,-3.0)),((5.5,-3.0),(4.2,-3.3),(2,-.9),(.7,-.15)),((.7,-.15),(.5,-.1),(.5,.1),(.7,.15))]:
                contours.extend(bez(a,b,c,d,t) for t in np.linspace(0,1,12,endpoint=False))
            boundary=[[cx+side*x*scale,cy+y*scale,z+.16*math.sin(x*.6)] for x,y in contours]
            if lace:
                # A continuous white fabric edge with small scalloped ruffles.
                outer=[]
                for j,(x,y) in enumerate(contours):
                    bump=.30+.12*(1+math.cos(j*math.pi)) if x>4 else .15
                    outer.append([cx+side*(x+bump)*scale,cy+(y+math.copysign(bump,y))*scale,z-.11])
                cloth(prefix+f'_white_ruffle_{side}',outer,'white',bulge=.15)
                cloth(prefix+f'_red_back_{side}',[[x,y,z-.42] for x,y,z in boundary],color,bulge=.03,thickness=.10)
            cloth(prefix+f'_wing_{side}',boundary,color,bulge=.45*scale)
            # Two thin curved fabric creases, modeled instead of rectangular strips.
            for k in [-1,1]:
                path=[bez([cx+side*.9*scale,cy+k*.08*scale,z+.52*scale],
                          [cx+side*2.1*scale,cy+k*.3*scale,z+.60*scale],
                          [cx+side*3.4*scale,cy+k*.9*scale,z+.57*scale],
                          [cx+side*4.8*scale,cy+k*1.25*scale,z+.43*scale],t) for t in np.linspace(0,1,16)]
                tube(prefix+f'_fold_{side}_{k}',path,[(.035*scale,.035*scale)]*len(path),'red_dark' if color=='red' else 'white_shadow','accessory',6)
        ellipsoid(prefix+'_knot',[cx,cy,z+.12],[.67*scale,1.3*scale,.44*scale],color,'accessory',20,12)

    ellipsoid('round_cheeks',[0,5.55,0],[7.9,5.55,5.65],'skin',face=True,nu=64,nv=32)
    hair='gold' if kind=='marisa' else 'hair'
    # Curved scalp: the front opens over the eyes; the back falls nearly to ground.
    v=[];f=[];nu=64;nv=18
    for i in range(nv+1):
        for j in range(nu+1):
            phi=-math.pi+TAU*j/nu
            front=max(0,math.cos(phi))
            end=2.52-1.58*front**.18
            end+=(.03*math.sin(j*2.2))*(1-front)
            t=.0001+i/nv*end
            v.append([8.03*math.sin(t)*math.sin(phi),max(.3,5.55+5.9*math.cos(t)),5.83*math.sin(t)*math.cos(phi)])
    for i in range(nv):
        for j in range(nu):
            a=i*(nu+1)+j;b=a+nu+1;f.extend([[a,b,b+1],[a,b+1,a+1]])
    mesh('curved_hair_cap',v,f,hair,'hair')

    def front_z(x,y):return 5.65*math.sqrt(max(.035,1-(x/7.9)**2-((y-5.55)/5.55)**2))+.48
    def lock(name,points,width,color):
        v=[];f=[];rows=18;cols=6
        for i in range(rows+1):
            t=i/rows;x,y=bez(*points,t)
            w=width*(.65+.45*math.sin(t*math.pi))*(1-t**3.5)+.015
            for j in range(cols+1):
                u=(j/cols-.5)*2;xx=x+w*u
                v.append([xx,y,front_z(xx,y)+.22*(1-u*u)*math.sin(t*math.pi)])
        for i in range(rows):
            for j in range(cols):
                a=i*(cols+1)+j;b=a+cols+1;f.extend([[a,b,b+1],[a,b+1,a+1]])
        # Winding toward +Z, matching the smooth curved face.
        for tri in f:
            p=np.array([v[k] for k in tri])
            if np.cross(p[1]-p[0],p[2]-p[0])[2]<0:tri.reverse()
        mesh(name,v,f,color,'hair')
    specs=[([-4.9,9.7],[-5.6,9],[-6.6,7.5],[-6.85,5.9],.83),
           ([-3.7,10.6],[-4.6,10.1],[-5.0,8.4],[-5.5,7.2],1.0),
           ([-2.4,11.0],[-3.1,10.4],[-3.6,9.2],[-3.4,8.1],1.05),
           ([-.9,11.3],[-1.6,10.9],[-1.8,9.3],[-.6,8.4],1.18),
           ([.8,11.25],[.5,10.4],[1.1,9.5],[2.2,7.9],1.05),
           ([2.5,10.95],[3.2,10.5],[3.8,9.0],[4.7,7.8],.97),
           ([4.0,10.3],[5.0,9.7],[6.0,8.1],[6.7,6.0],.87)]
    if kind=='reimu':
        # Reimu's shorter, dark, slightly jagged fringe.
        specs=[(a,b,c,[e[0],max(e[1],8.0)+(.15 if i%2 else -.2)],w) for i,(a,b,c,e,w) in enumerate(specs)]
    for i,(a,b,c,e,w) in enumerate(specs):lock(f'pointed_bang_{i}',[a,b,c,e],w,hair+'_light' if i in [1,4] else hair)
    # Fine, curved back strands follow the scalp rather than hanging as boxes.
    for j in range(11):
        phi=math.pi/2+.16+j*(math.pi-.32)/10
        path=[]
        for t in np.linspace(.45,2.45,22):path.append([8.07*math.sin(t)*math.sin(phi),max(.35,5.55+5.94*math.cos(t)),5.87*math.sin(t)*math.cos(phi)])
        tube('back_strand_'+str(j),path,[(.024,.024)]*len(path),hair+'_dark','hair',5)
    if kind=='reimu':
        for side in [-1,1]:
            centers=[[side*7.35,7.3,2.7],[side*7.65,5.7,3.15],[side*7.7,3.8,3.15],[side*7.55,2.2,3.05],[side*7.65,1.3,2.95]]
            tube(f'soft_sidelock_{side}',centers,[(.7,.8),(.6,.75),(.52,.64),(.6,.68),(.75,.75)],'hair')
            tube(f'red_hair_tie_{side}',[[side*7.63,2.05,3.08],[side*7.68,3.75,3.12]],[(.66,.77),(.64,.77)],'red','accessory')
            for y in [2.02,3.65]:tube(f'hair_tie_trim_{side}_{y}',[[side*7.66,y,3.12],[side*7.66,y+.22,3.12]],[(.70,.8)]*2,'white','accessory')
            for k in range(4):
                x=side*7.6+(k-1.5)*.35
                tube(f'hair_tip_{side}_{k}',[[x,1.9,3.1],[x+(k-1.5)*.08,.9+.12*(k%2),3.05]],[(.22,.55),(.02,.03)],'hair')
        ribbon_bow('red_bow',0,12.7,-.65,1.03,'red',lace=True)
    else:
        for side in [-1,1]:
            centers=[[side*6.6,9.2,2.7],[side*7.2,7,3.2],[side*7.6,4.7,2.5],[side*7.5,2.0,2.0],[side*8.15,.8,2.4]]
            tube(f'flowing_side_hair_{side}',centers,[(.9,1.4),(.7,1.2),(.6,1.0),(.48,.7),(.04,.08)],'gold')
            for j in range(3):
                centers2=[[x+(j-1)*.22,y,z+.8] for x,y,z in centers[:-1]]+[centers[-1]]
                tube(f'gold_side_strand_{side}_{j}',centers2,[(.023,.023)]*len(centers2),'gold_dark','hair',5)
        for i in range(6):
            ellipsoid('braid_segment_'+str(i),[7.42+(-1)**i*.20,5.0-i*.52,3.8],[.43,.48,.5],'gold_light' if i%2 else 'gold','hair',12,10)
        ribbon_bow('braid_ribbon',7.45,1.95,4.0,.22,'white')
        # Broad elliptical brim, slightly waving cloth, no stepped blocks.
        N=64;v=[];f=[]
        for lower in [False,True]:
            for ri in range(5):
                r=.001+ri/4
                for j in range(N+1):
                    p=TAU*j/N;wave=(.24*math.cos(3*p)+.18*math.sin(2*p))*r*r
                    v.append([10.6*r*math.sin(p),11.05+wave-(.16 if lower else 0),7*r*math.cos(p)])
        count=5*(N+1)
        for ri in range(4):
            for j in range(N):
                a=ri*(N+1)+j;b=a+N+1
                f.extend([[a,b+1,b],[a,a+1,b+1],[a+count,b+count,b+1+count],[a+count,b+1+count,a+1+count]])
        for j in range(N):
            a=4*(N+1)+j;f.extend([[a,a+count,a+1+count],[a,a+1+count,a+1]])
        mesh('wavy_wide_brim',v,f,'hat','accessory')
        centers=[[0,11.05,0],[-.15,12.7,0],[-.45,14.2,0],[-.45,15.6,0],[.1,16.8,0],[1.15,17.4,0],[2.6,17.65,0],[3.75,17.7,0]]
        radii=[(5.8,4.6),(5.2,4.1),(4.4,3.5),(3.3,2.7),(2.2,1.9),(1.25,1.1),(.55,.5),(.02,.02)]
        # Interpolate the bend so the outline reads as soft cloth rather than a kink.
        values=[np.array(c+list(r)) for c,r in zip(centers,radii)];smooth=[]
        for i in range(len(values)-1):
            a=values[max(0,i-1)];b=values[i];c=values[i+1];d=values[min(len(values)-1,i+2)]
            for t in np.linspace(0,1,6,endpoint=False):
                smooth.append(.5*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t*t+(-a+3*b-3*c+d)*t**3))
        smooth.append(values[-1]);centers=[p[:3].tolist() for p in smooth];radii=[np.maximum(.02,p[3:]).tolist() for p in smooth]
        tube('soft_bent_hat',centers,radii,'hat','accessory',48)
        tube('white_hat_band',centers[2:7],[[x+.18,z+.18] for x,z in radii[2:7]],'white','accessory',48)
        # White lace under the brim, with small scallops.
        for j in range(40):
            p=TAU*j/40
            x=10.1*math.sin(p);z=6.64*math.cos(p);wave=.24*math.cos(3*p)+.18*math.sin(2*p)
            ellipsoid(f'brim_lace_{j}',[x,10.57+wave,z],[.36,.24,.30],'white','accessory',8,6)
        ribbon_bow('white_hat_bow',0,12.35,4.65,.47,'white')
    return dict(kind=kind,meshes=meshes,texture=face_texture(kind))

def normals(vertices,faces):
    v=np.array(vertices,dtype=np.float32);n=np.zeros_like(v)
    for f in faces:
        cross=np.cross(v[f[1]]-v[f[0]],v[f[2]]-v[f[0]])
        for k in f:n[k]+=cross
    lengths=np.linalg.norm(n,axis=1);lengths[lengths<1e-9]=1
    return (n/lengths[:,None]).astype(np.float32)

def export(model):
    kind=model['kind']; palette=Image.new('RGBA',(64,64),'#ffffff');d=ImageDraw.Draw(palette)
    pal={}
    for i,(k,c) in enumerate(COLORS.items()):
        x=i%8*8;y=i//8*8;d.rectangle((x,y,x+7,y+7),fill=c);pal[k]=[x+3,y+3]
    p=ROOT/'textures'/'round_palette.png';palette.save(p)
    src='data:image/png;base64,'+base64.b64encode(p.read_bytes()).decode()
    elements=[];outliner=[]
    for group in ['head','hair','accessory']:
        children=[]
        for m in [m for m in model['meshes'] if m['group']==group]:
            uid=str(uuid.uuid5(uuid.NAMESPACE_DNS,kind+'round'+m['name']));children.append(uid)
            vertices={'v'+str(i):v for i,v in enumerate(m['vertices'])};faces={}
            for i,f in enumerate(m['faces']):
                ids=['v'+str(j) for j in f]
                uv={k:([m['uv'][j][0]*512,(1-m['uv'][j][1])*512] if m['color']=='face' else pal[m['color']]) for j,k in zip(f,ids)}
                faces['f'+str(i)]=dict(vertices=ids,uv=uv,texture=1 if m['color']=='face' else 0)
            elements.append(dict(name=m['name'],type='mesh',uuid=uid,origin=[0,0,0],rotation=[0,0,0],vertices=vertices,faces=faces))
        outliner.append(dict(name=group,origin=[0,0,0],uuid=str(uuid.uuid5(uuid.NAMESPACE_DNS,kind+'round'+group)),children=children))
    textures=[dict(name='round_palette.png',id='0',source=src,mode='bitmap',uuid=str(uuid.uuid4()),width=64,height=64,uv_width=64,uv_height=64),
              dict(name=kind+'_round.png',id='1',source=model['texture'],mode='bitmap',uuid=str(uuid.uuid4()),width=512,height=512,uv_width=512,uv_height=512)]
    bb=dict(meta=dict(format_version='4.10',model_format='free',box_uv=False),name=kind+'_round',resolution=dict(width=512,height=512),elements=elements,outliner=outliner,textures=textures)
    (ROOT/'models'/f'{kind}_round.bbmodel').write_text(json.dumps(bb,separators=(',',':')),encoding='utf8')
    # Standard GLB with smooth normals, embedded PNG, and a hierarchy matching the project.
    gltf=dict(asset=dict(version='2.0',generator='MCyukkuri rounded model builder'),scene=0,scenes=[dict(nodes=[0])],nodes=[dict(name=kind,children=[])],meshes=[],materials=[],buffers=[],bufferViews=[],accessors=[],images=[],textures=[],samplers=[dict(magFilter=9729,minFilter=9729,wrapS=33071,wrapT=33071)])
    binary=bytearray()
    def view(data,target=None):
        while len(binary)%4:binary.append(0)
        offset=len(binary);binary.extend(data);v=dict(buffer=0,byteOffset=offset,byteLength=len(data))
        if target:v['target']=target
        gltf['bufferViews'].append(v);return len(gltf['bufferViews'])-1
    def accessor(a,type_,ctype,target):
        a=np.asarray(a,dtype=np.uint32 if ctype==5125 else np.float32)
        vi=view(a.tobytes(),target);ac=dict(bufferView=vi,componentType=ctype,count=len(a),type=type_)
        if type_=='VEC3':ac.update(min=a.min(axis=0).tolist(),max=a.max(axis=0).tolist())
        gltf['accessors'].append(ac);return len(gltf['accessors'])-1
    png=(ROOT/'textures'/f'{kind}_round.png').read_bytes();gltf['images']=[dict(bufferView=view(png),mimeType='image/png')];gltf['textures']=[dict(source=0,sampler=0)]
    mats={}
    for k,c in COLORS.items():
        rgb=[int(c[i:i+2],16)/255 for i in [1,3,5]];rgb=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in rgb]
        mats[k]=len(gltf['materials']);gltf['materials'].append(dict(name=k,pbrMetallicRoughness=dict(baseColorFactor=rgb+[1],metallicFactor=0,roughnessFactor=1),doubleSided=True))
    mats['face']=len(gltf['materials']);gltf['materials'].append(dict(name='hand_drawn_face',pbrMetallicRoughness=dict(baseColorTexture=dict(index=0),metallicFactor=0,roughnessFactor=1),doubleSided=True))
    for group in ['head','hair','accessory']:
        gi=len(gltf['nodes']);gltf['nodes'].append(dict(name=group,children=[]));gltf['nodes'][0]['children'].append(gi)
        for m in [m for m in model['meshes'] if m['group']==group]:
            p=accessor(m['vertices'],'VEC3',5126,34962);n=accessor(normals(m['vertices'],m['faces']),'VEC3',5126,34962)
            uv=accessor([[u,1-v] for u,v in m['uv']],'VEC2',5126,34962);ix=accessor(np.array(m['faces']).flatten(),'SCALAR',5125,34963)
            mi=len(gltf['meshes']);gltf['meshes'].append(dict(name=m['name'],primitives=[dict(attributes=dict(POSITION=p,NORMAL=n,TEXCOORD_0=uv),indices=ix,material=mats[m['color']])]))
            ni=len(gltf['nodes']);gltf['nodes'].append(dict(name=m['name'],mesh=mi));gltf['nodes'][gi]['children'].append(ni)
    while len(binary)%4:binary.append(0)
    gltf['buffers']=[dict(byteLength=len(binary))];js=json.dumps(gltf,separators=(',',':')).encode();js+=b' '*((-len(js))%4)
    total=12+8+len(js)+8+len(binary)
    (ROOT/'models'/f'{kind}_round.glb').write_bytes(struct.pack('<III',0x46546c67,2,total)+struct.pack('<II',len(js),0x4e4f534a)+js+struct.pack('<II',len(binary),0x004e4942)+binary)

if __name__=='__main__':
    data=[build('marisa'),build('reimu')]
    for m in data:
        export(m)
        print(m['kind'],len(m['meshes']),'meshes',sum(len(n['faces']) for n in m['meshes']),'triangles')
    (ROOT/'preview'/'models-round.js').write_text('window.ROUND_MODELS='+json.dumps(data,separators=(',',':'))+';\n',encoding='utf8')
