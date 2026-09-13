"""Preview real Minecraft forage sprites; runtime keeps using vanilla item IDs.
The local preview atlas embeds local-client pixels only for offline inspection.
No Minecraft textures are copied into the mod's packaged resources.
"""
from pathlib import Path
import hashlib, io, math, os, zipfile, random
from PIL import Image

def native_sprite(model,resource,item,tint=None):
    jar=Path(os.environ.get('MC_CLIENT_JAR',str(Path.home()/'.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar')))
    with zipfile.ZipFile(jar) as z:raw=z.read(resource)
    sprite=Image.open(io.BytesIO(raw)).convert('RGBA')
    if tint:
        sprite.putdata([tuple(p[i]*tint[i]//255 for i in range(3))+(p[3],) for p in sprite.getdata()])
    atlas=Image.new('RGBA',(512,512));atlas.paste(model.texture,(0,0));atlas.paste(sprite,(256,0));model.texture=atlas
    model.sources.append(dict(resource=resource,item=item,minecraft_version='1.21.1',source_archive='minecraft_1.21.1_client.jar',sha256=hashlib.sha256(raw).hexdigest(),preview_tint='#91bd59' if tint else None,runtime='reference vanilla resource; do not ship copied pixels'))
    return sprite

def textured_mesh(model,name,points,triangles,uv,unit=None):
    faces={str(i):dict(vertices=[str(k) for k in tri],uv={str(k):uv[k] for k in tri},texture=0) for i,tri in enumerate(triangles)}
    part=dict(name=name,type='mesh',origin=[0,0,0],rotation=[0,0,0],vertices={str(i):p for i,p in enumerate(points)},faces=faces)
    if unit is not None:part['unit']=unit
    model.parts.append(part)

def add_native_forage(model,kind,units=8):
    wheat=kind=='wheat'
    resource='assets/minecraft/textures/item/wheat.png' if wheat else 'assets/minecraft/textures/block/short_grass.png'
    sprite=native_sprite(model,resource,'minecraft:wheat' if wheat else 'minecraft:short_grass',None if wheat else (145,189,89))
    opaque=[(x,y) for y in range(16) for x in range(16) if sprite.getpixel((x,y))[3]>200]
    rng=random.Random(277 if wheat else 431)
    for unit in range(1,units+1):
        per_unit=12 if wheat else 50
        per_layer=20 if wheat else 80
        for blade in range(per_unit):
            i=(unit-1)*per_unit+blade;layer=i//per_layer;j=i%per_layer
            radius=(3.45 if wheat else 3.8)-max(0,layer-1)*.32
            a=i*2.399963;r=math.sqrt((j+.5)/per_layer)*radius
            angle=rng.uniform(0,math.tau);x=r*math.cos(a);z=r*.88*math.sin(a)
            y=.74+layer*.76+.38*(1-r/radius)+rng.uniform(-.23 if layer else 0,.23);c=math.cos(angle);s=math.sin(angle)
            slope=rng.uniform(-.2,.2) if layer else 0
            def point(u,v,h):return [x+u*c-v*s,max(.64,y+h+u*slope),z+u*s+v*c]
            if wheat:
                # Detached heads and stems lie across the bowl, with shallow bends.
                pts=[point(-1.25,-1.25,0),point(1.25,-1.25,.04),point(1.25,1.25,.13),point(-1.25,1.25,.09)]
                textured_mesh(model,f'laid_wheat_{unit}_{blade}',pts,[[0,1,2],[0,2,3],[2,1,0],[3,2,0]],[[256,16],[272,16],[272,0],[256,0]],unit)
            else:
                # Short cut ribbons, with both ends lying near the floor. No planted roots.
                length=rng.uniform(1.05,2.35);width=rng.uniform(.22,.38);bend=rng.uniform(-.25,.25)
                pts=[point(-length/2,-width/2,0),point(-length/2,width/2,0),point(0,bend+width/2,.12),point(length/2,width*.3,.03),point(length/2,-width*.3,.03),point(0,bend-width/2,.12)]
                px,py=rng.choice(opaque);uv=[[256+px+.5,py+.5]]*6
                tri=[[0,1,2],[0,2,5],[5,2,3],[5,3,4]]
                textured_mesh(model,f'cut_grass_{unit}_{blade}',pts,tri+[t[::-1] for t in tri],uv,unit)

def add_native_book(model):
    sprite=native_sprite(model,'assets/minecraft/textures/item/book.png','minecraft:book')
    # Pixel extrusion follows the vanilla generated-item silhouette and colours.
    for y in range(16):
        for x in range(16):
            if sprite.getpixel((x,y))[3]<128:continue
            pts=[[(x+dx-8)*.43,(16-y-dy)*.43,z] for dx,dy,z in [(0,1,-.15),(1,1,-.15),(1,0,-.15),(0,0,-.15),(0,1,.15),(1,1,.15),(1,0,.15),(0,0,.15)]]
            quads=[[0,3,2,1],[4,5,6,7],[0,1,5,4],[3,7,6,2],[0,4,7,3],[1,2,6,5]]
            tri=[t for q in quads for t in [[q[0],q[1],q[2]],[q[0],q[2],q[3]]]]
            textured_mesh(model,f'book_pixel_{x}_{y}',pts,tri,[[256+x+.5,y+.5]]*8)
