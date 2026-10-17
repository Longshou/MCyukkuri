"""Export fitted carry contact triangles and an offline, actual-mesh contact preview. No game compilation."""
from pathlib import Path
import gzip, hashlib, json, sys, math, runpy
import numpy as np

ROOT=Path(__file__).resolve().parents[5]
sys.path.insert(0,str(ROOT/'scripts'))
import placement_fit

def export():
    models={}
    recipe=json.loads((Path(__file__).parent/'recipe.json').read_text(encoding='utf-8'))
    rotation=runpy.run_path(str(Path(__file__).parent/'hat.py'))['euler'](recipe['clips']['boat_carry_young']['braid_fold'])
    for path in sorted((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri').glob('marisa*.json')):
        model=placement_fit.fitted(path)
        raw=np.array(model['vertices']).reshape(-1,8)[:,:3]
        bones=model['bones'];weights=np.array(model['weights']);parent_points=raw.copy()
        for braid,bone in enumerate(bones):
            if bone['name'] not in ('hand_left','hand_right'):continue
            descendants={braid}
            for i,b in enumerate(bones):
                if b['parent'] in descendants:descendants.add(i)
            pivot=np.array(bone['pivot']);changed=(raw-pivot)@rotation.T+pivot
            blend=np.array([(1-w[2])*(int(w[0]) in descendants)+w[2]*(int(w[1]) in descendants) for w in weights])[:,None]
            parent_points+=(changed-raw)*blend
        parts=[]
        for part in model['parts']:
            name=part['name']
            if name.startswith(('face_','mamu','peni','rounded_rump','puff_')):continue
            points=raw[part['start']:part['start']+part['count']]
            parts.append(dict(name=name,hat=name.startswith(('brim_','hat_','bow_')),
                              body='body_surface' in name,triangles=np.round(points,7).ravel().tolist()))
            parent=parent_points[part['start']:part['start']+part['count']]
            if not np.array_equal(parent,points):parts[-1]['parent_triangles']=np.round(parent,7).ravel().tolist()
        models[model['id']]=dict(pivot=next(b['pivot'] for b in model['bones'] if b['name']=='root'),
                                 source_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),parts=parts)
    resource=ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_carry_geometry.json.gz'
    data=dict(format=1,
              placement_sha256=hashlib.sha256(placement_fit.RESOURCE.read_bytes()).hexdigest(),
              models=models)
    resource.write_bytes(gzip.compress(json.dumps(data,separators=(',',':')).encode(),mtime=0))
    print('Exported fitted carry models:',', '.join(models))
    return data

def preview(data):
    from PIL import Image,ImageDraw,ImageFont
    source=data['models']['marisa']
    motion=json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))['models']['marisa']
    root=motion['bone_names'].index('root');pivot=np.array(source['pivot']);anchor=np.array([0,10.3774,.2307]);mouth=np.array([0,2.0034,-5.215])
    def transform(points,action,time):
        values={'position':np.zeros(3),'rotation':np.zeros(3),'scale':np.ones(3)}
        for track in motion['clips'][action]['tracks']:
            if track['bone']!=root:continue
            keys=np.array(track['keys']);values[track['channel']]=np.array([np.interp(time,keys[:,0],keys[:,i]) for i in range(1,4)])
        x,y,z=np.radians(values['rotation']);cx,sx,cy,sy,cz,sz=np.cos(x),np.sin(x),np.cos(y),np.sin(y),np.cos(z),np.sin(z)
        R=np.array([[cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx],[sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx],[-sy,cy*sx,cy*cx]])
        return ((points-pivot)*values['scale'])@R.T+pivot+values['position']
    def rotate(v,yaw):
        c,s=math.cos(yaw),math.sin(yaw);return v@np.array([[c,0,-s],[0,1,0],[s,0,c]])
    growth=.14
    pm=transform(mouth,'boat_carry_young',0)/1.5;cm=transform(mouth,'boat_be_carried',0)*growth/1.5*np.array([-1,1,-1]);offset=pm-cm
    child=np.array([-.11654,.375,-4.70154]);parentx=0.;dx=child[0]-parentx;reach=abs(offset[2]);direction=np.array([dx,0,-math.sqrt(reach*reach-dx*dx)])/reach
    yaw=math.atan2(-direction[0],-direction[2]);offset=rotate(offset,yaw);parent=np.array([parentx,.375,child[2]-offset[2]])
    canvas=Image.new('RGB',(1680,680),'#f7f5ed');pen=ImageDraw.Draw(canvas)
    font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',19);small=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',15)
    eye=np.array([.65,.25,-.70]);eye/=np.linalg.norm(eye);right=np.cross(np.array([0,1,0]),eye);right/=np.linalg.norm(right);up=np.cross(eye,right)
    def draw(column,parent_at,child_at,action,time,title):
        triangles=[];mouths=[];origin=np.array([0,3,-1])
        for young,at in [(False,parent_at),(True,child_at)]:
            for part in source['parts']:
                if part['hat'] and not young:continue
                vertices=np.array(part.get('parent_triangles',part['triangles']) if not young else part['triangles']).reshape(-1,3)
                if part['hat']:vertices=anchor+(vertices-anchor)*1.5
                vertices=transform(vertices,action if young else 'boat_carry_young',time if young else 0)
                vertices=rotate(vertices*(growth if young else 1)/1.5,yaw+(math.pi if young else 0))+at
                color=np.array([238,224,183]) if part['body'] else np.array([53,48,63]) if part['hat'] else np.array([186,145,57])
                for triangle in vertices.reshape(-1,3,3):
                    n=np.cross(triangle[1]-triangle[0],triangle[2]-triangle[0]);length=np.linalg.norm(n)
                    shade=.75+.25*abs(n@eye)/length if length>1e-9 else .8
                    triangles.append((triangle.mean(0)@eye,triangle,tuple((color*shade).astype(int))))
            mouths.append(rotate(transform(mouth,action if young else 'boat_carry_young',time if young else 0)*(growth if young else 1)/1.5,yaw+(math.pi if young else 0))+at)
        def project(v):return (column*560+280+(v-origin)@right*34,340-(v-origin)@up*34)
        for _,triangle,color in sorted(triangles,key=lambda row:row[0]):pen.polygon([project(v) for v in triangle],fill=color)
        for v in mouths:
            x,y=project(v);pen.ellipse((x-3,y-3,x+3,y+3),fill='#e65b70')
        pen.text((column*560+22,560),title,font=font,fill='#33414a')
    draw(0,np.array([0,-.4863,.99]),child,'boat_prepare_carry',.6,'1  Same-tread crawl and supported tuck')
    draw(1,parent,child,'boat_be_carried',0,'2  Parent reaches the supported tread')
    draw(2,parent,parent+offset,'boat_be_carried',0,'3  Held mouth anchors coincide')
    pen.text((30,20),'Current fitted carry geometry | child growth 0.14 | child pitch 38 degrees',font=font,fill='#24323b')
    pen.text((30,620),'Actual selected triangles and authored poses. Pink dots are mouth anchors. Offline geometry preview, not a game screenshot.',font=small,fill='#42525e')
    out=ROOT/'.local/previews/hat-care';out.mkdir(parents=True,exist_ok=True)
    canvas.save(out/'current.png')
    (out/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>Hat care contact geometry</title><style>body{background:#f7f5ed;font-family:system-ui;margin:2rem}img{max-width:100%}</style><h1>Current fitted carry geometry</h1><p>Same-tread crawl, supported tuck, parent tread approach, and precise held mouth contact. This is an offline render of the current source mesh and poses. The parent currently enters the stable 52-degree hair-fold pose directly; its visual transition remains unfinished.</p><img src="current.png">',encoding='utf-8')
    print('Preview:',out/'current.png')

if __name__=='__main__':
    data=export()
    if '--preview' in sys.argv:preview(data)
