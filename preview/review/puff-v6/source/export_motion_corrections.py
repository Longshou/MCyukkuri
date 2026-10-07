"""Compile selected motion overrides; retain geometry and its attachment hashes.

Reads the contact-safe recipe and all selected runtime models. Writes the motion
resource, derived cheek face atlases and source manifest. --check is read-only.
Never launches a game or UI.
"""
from pathlib import Path
import argparse, hashlib, json, runpy
import io, math
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'standards/yukkuri-library/motions/variants/contact-safe'
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
OUTPUT = ASSETS/'yukkuri_presentation/motion_corrections.json'
MANIFEST = ROOT/'art/source/motion-corrections/manifest.json'

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def rel(path):return path.relative_to(ROOT).as_posix()

def eye_ink_bounds(model,texture):
    """Protect visible eyes, not the much larger transparent decal rectangles."""
    image=Image.open(io.BytesIO(texture)).convert('RGBA');size=image.width;points=[]
    for part in model['parts']:
        if not part['name'].startswith('face_eye_'):continue
        for k in range(part['start']*8,(part['start']+part['count'])*8,24):
            tri=[model['vertices'][k+j*8:k+j*8+8] for j in range(3)]
            if tri[0][7]>=-.2:continue
            a,b,c=tri;den=(b[4]-c[4])*(a[3]-c[3])+(c[3]-b[3])*(a[4]-c[4])
            if abs(den)<1e-10:continue
            for y in range(max(0,int(min(v[4] for v in tri)*size)),min(size,math.ceil(max(v[4] for v in tri)*size))):
                for x in range(max(0,int(min(v[3] for v in tri)*size)),min(size,math.ceil(max(v[3] for v in tri)*size))):
                    if image.getpixel((x,y))[3]<128:continue
                    # Pixel corners bound the complete visible texel, not only its centre.
                    for dx,dy in [(0,0),(1,0),(0,1),(1,1)]:
                        u,v=(x+dx)/size,(y+dy)/size
                        wa=((b[4]-c[4])*(u-c[3])+(c[3]-b[3])*(v-c[4]))/den
                        wb=((c[4]-a[4])*(u-c[3])+(a[3]-c[3])*(v-c[4]))/den;wc=1-wa-wb
                        if min(wa,wb,wc)<-1e-5:continue
                        points.append([wa*a[j]+wb*b[j]+wc*c[j] for j in range(2)])
    assert points,model['id']
    return [max(abs(p[0]) for p in points),min(p[1] for p in points)]

def cheek_texture(model,source):
    image=Image.open(source).convert('RGBA');size=image.width;colors=set()
    for part in model['parts']:
        if 'body_surface' not in part['name']:continue
        for k in range(part['start']*8,(part['start']+part['count'])*8,8):
            v=model['vertices'];colors.add(image.getpixel((min(size-1,int(v[k+3]*size)),min(size-1,int(v[k+4]*size))))[:3])
    # Remove only skin-coloured backgrounds from facial UV islands, retaining
    # eyes, blush and mouth ink. The body's separate solid skin swatch is intact.
    boxes={}
    for part in model['parts']:
        if not part['name'].startswith('face_'):continue
        v=model['vertices'][part['start']*8:(part['start']+part['count'])*8]
        front=[v[k:k+8] for k in range(0,len(v),8) if v[k+7]<-.2]
        if not front:continue
        box=[max(0,round(min(p[3] for p in front)*size)),max(0,round(min(p[4] for p in front)*size)),
             min(size,round(max(p[3] for p in front)*size)),min(size,round(max(p[4] for p in front)*size))]
        boxes[part['name']]=box
        for y in range(box[1],box[3]):
            for x in range(box[0],box[2]):
                color=image.getpixel((x,y))
                if color[:3] in colors:image.putpixel((x,y),(*color[:3],0))
    draw=ImageDraw.Draw(image)
    # Preserve the character's open eyes and brows. A small closed omega mouth
    # and soft blush carry the held-breath expression without a strained grimace.
    x,y,X,Y=boxes['face_mouth'];draw.rectangle((x,y,X-1,Y-1),fill=(0,0,0,0))
    w,h=X-x,Y-y;cx=x+w*.5;cy=y+h*.32
    points=[(-3,0),(-3,1),(-2,2),(-1,2),(0,1),(1,2),(2,2),(3,1),(3,0)]
    draw.line([(round(cx+dx*w/28),round(cy+dy*h/24)) for dx,dy in points],fill=(133,82,66,255),width=max(1,round(w/28)))
    for name in ['face_blush_left','face_blush_right']:
        x,y,X,Y=boxes[name];draw.rectangle((x,y,X-1,Y-1),fill=(0,0,0,0));w,h=X-x,Y-y
        draw.ellipse((x+w*.28,y+h*.2,X-1-w*.28,Y-1-h*.15),fill=(239,153,146,200))
        draw.line((x+w*.38,y+h*.4,x+w*.55,y+h*.4),fill=(255,188,177,220),width=1)
    output=io.BytesIO();image.save(output,format='PNG');return output.getvalue()

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    source_files=[SOURCE/'recipe.json',SOURCE/'author.py',Path(__file__)]
    source_hashes={path:sha(path) for path in source_files}
    cfg=json.loads((SOURCE/'recipe.json').read_text());compile_revision=runpy.run_path(str(SOURCE/'author.py'))['compile_revision']
    models={};inputs={}
    for path in sorted((ASSETS/'yukkuri').glob('*.json')):
        model=json.loads(path.read_text(encoding='utf-8'))
        if not all(k in model for k in ['bones','clips','weights']):continue
        ident=model['id'];tex=ASSETS/'textures/entity'/ident
        expression='neutral'
        assert (tex/(expression+'.png')).exists()
        texture_path=tex/'cheek_puff.png';texture=cheek_texture(model,tex/(expression+'.png'))
        if args.check:assert texture_path.read_bytes()==texture, f'Stale cheek texture: {ident}'
        else:texture_path.write_bytes(texture)
        models[ident]=compile_revision(model,cfg,'cheek_puff',eye_ink_bounds(model,texture));models[ident]['expression_aliases']={'cheek_puff':expression}
        inputs[ident]={'model':rel(path),'sha256':sha(path),'expression':expression,'texture_source':rel(tex/(expression+'.png')),
                       'texture_source_sha256':sha(tex/(expression+'.png')),'texture':rel(texture_path),'texture_sha256':hashlib.sha256(texture).hexdigest()}
        print(ident, 'hop height', round(models[ident]['hop_height'],3))
    assert all(sha(path)==fingerprint for path,fingerprint in source_hashes.items()), 'Motion source changed during export; rerun from the current source'
    payload=json.dumps({'format':1,'revision':'contact-safe-20261007-v6','models':models},separators=(',',':'),ensure_ascii=False)+'\n'
    manifest={'status':'pending_user_review','geometry_modified':False,'runtime_morph_geometry_modified':True,
        'geometry_note':'Base selected models and their fingerprints are unchanged; puff-only skin and attachment replacements are exported in the motion resource.',
        'recipe':rel(SOURCE/'recipe.json'),'recipe_sha256':sha(SOURCE/'recipe.json'),
        'author':rel(SOURCE/'author.py'),'author_sha256':sha(SOURCE/'author.py'),'exporter':rel(Path(__file__)),'exporter_sha256':sha(Path(__file__)),
        'runtime':rel(OUTPUT),'runtime_sha256':hashlib.sha256(payload.encode()).hexdigest(),'models':inputs}
    records=[(OUTPUT,payload),(MANIFEST,json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')]
    for path,text in records:
        if args.check:
            assert path.read_text(encoding='utf-8')==text, f'Stale export: {rel(path)}'
        else:
            path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8',newline='\n')

if __name__=='__main__':main()
