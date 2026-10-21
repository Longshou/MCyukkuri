"""Export posed accessory attachment coordinates, owned badge UI strings and handbook recipe."""
from pathlib import Path
import argparse, json, math
from PIL import Image, ImageDraw
from io import BytesIO

import sys
from asset_export_checks import fingerprint, provenance
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'art/props/badges'))
import author_badges

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'art/props/badges'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'

def sub(a,b): return [x-y for x,y in zip(a,b)]
def dot(a,b): return sum(x*y for x,y in zip(a,b))
def cross(a,b): return [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
def length(a): return math.sqrt(dot(a,a))
def add(a,b): return [x+y for x,y in zip(a,b)]
def mul(a,n): return [x*n for x in a]

def nearest(p,a,b,c):
    # Closest point on a triangle, retaining barycentric coordinates on its surface.
    ab,ac,ap=sub(b,a),sub(c,a),sub(p,a);d1,d2=dot(ab,ap),dot(ac,ap)
    if d1<=0 and d2<=0:return [1,0,0]
    bp=sub(p,b);d3,d4=dot(ab,bp),dot(ac,bp)
    if d3>=0 and d4<=d3:return [0,1,0]
    vc=d1*d4-d3*d2
    if vc<=0 and d1>=0 and d3<=0:
        v=d1/(d1-d3);return [1-v,v,0]
    cp=sub(p,c);d5,d6=dot(ab,cp),dot(ac,cp)
    if d6>=0 and d5<=d6:return [0,0,1]
    vb=d5*d2-d1*d6
    if vb<=0 and d2>=0 and d6<=0:
        w=d2/(d2-d6);return [1-w,0,w]
    va=d3*d6-d5*d4
    if va<=0 and d4-d3>=0 and d5-d6>=0:
        w=(d4-d3)/((d4-d3)+(d5-d6));return [0,1-w,w]
    denom=1/(va+vb+vc);v=vb*denom;w=vc*denom;return [1-v-w,v,w]

def mounts(only=None,detached=None):
    recipe=json.loads((ART/'mounts.json').read_text(encoding='utf-8'));result={'format':1}
    rounded=ROOT/'art/source/rounded-runtime/attachments.json'
    if rounded.exists():
        for group,entries in json.loads(rounded.read_text(encoding='utf-8'))['badge_mounts'].items():recipe[group].update(entries)
    previous=provenance(ASSETS/'yukkuri/badge_mounts.json')
    if only:
        if only not in recipe['variants']:raise ValueError('Unknown badge variant: '+only)
        result=json.loads((ASSETS/'yukkuri/badge_mounts.json').read_text(encoding='utf-8'))
    if detached:
        # One detached accessory alone (e.g. accessory_marisa_aquatic); every other exported mount is kept as it is.
        if detached not in recipe['detached']:raise ValueError('Unknown detached accessory: '+detached)
        result=json.loads((ASSETS/'yukkuri/badge_mounts.json').read_text(encoding='utf-8'))
        result['detached'].update(export_mount_group({detached:recipe['detached'][detached]},'yukkuri_props',previous.get('detached',{})))
        return result
    for kind in ['variants','detached']:
        specs=recipe[kind]
        if only:specs={name:spec for name,spec in specs.items() if name==only or name=='accessory_'+only}
        exported=export_mount_group(specs, 'yukkuri' if kind=='variants' else 'yukkuri_props', previous.get(kind,{}))
        if only:result[kind].update(exported)
        else:result[kind]=exported
    return result

def export_mount_group(specs,folder,previous):
    out={}
    for name, spec in specs.items():
        path=ASSETS/folder/f'{name}.json';raw=path.read_bytes();d=json.loads(raw);verts=d['vertices']
        part=next(p for p in d['parts'] if p['name']==spec['part']);best=None
        for start in range(part['start'],part['start']+part['count'],3):
            vs=[verts[i*8:i*8+3] for i in range(start,start+3)]
            normal=cross(sub(vs[1],vs[0]),sub(vs[2],vs[0]));size=length(normal)
            if size<1e-8:continue
            normal=mul(normal,1/size)
            # The export uses outward normals; reject back/edge faces rather than flipping them.
            if dot(normal,spec['normal'])<.3:continue
            weights=nearest(spec['point'],*vs);point=[sum(v[k]*w for v,w in zip(vs,weights)) for k in range(3)]
            distance=dot(sub(point,spec['point']),sub(point,spec['point']))
            if best is None or distance<best[0]:best=(distance,start,weights,point,normal)
        if best is None:raise ValueError('No outward accessory face for '+name)
        out[name]={'model_sha256':fingerprint(path,raw,previous.get(name,{}).get('model_sha256')),'part':spec['part'],'triangle':best[1],
                   'weights':best[2],'rest_point':best[3],'rest_normal':best[4],'diameter':spec['diameter']/16}
    return out

def language(zh):
    """Badge keys this script owns (the pet-shop UI strings live in the lang files directly; the badge page, handbook name and wear states are kept in sync here)."""
    pairs={
        'title':('宠物手册','Pet Handbook'),
        'shop':('考试 / 补发徽章','Exams / Badge reissue'),
        'subject':('对象：%s（%s/%s）','Subject: %s (%s/%s)'),
        'no_subject':('请把你的油库里带到身边','Bring your yukkuri over'),
        'unavailable':('现在办不了：它要是你的、在近处、没在忙。','Not possible right now: it must be yours, close by and not busy.'),
        'token_refused':('别不上这枚徽章：它得是这只油库里的有效徽章，而且它要戴着饰物、当前没别着别的徽章。','Cannot pin this badge: it must be a valid badge for this yukkuri, which must be wearing its accessory with no other badge on.'),
        'token_hint':('只属于一只油库里的圆牌；手持它右键原来的油库里即可别上。空白章和旧章无效。','A badge bound to one yukkuri. Hold it and right-click that yukkuri to pin it on. Blank or old badges do not count.'),
        'book_hint':('右键你的油库里：查看档案、上课、办繁殖许可。考试请找考官。','Right-click your yukkuri: view its page, start lessons, grant breeding permits. Exams are with the Examiner.'),
        'guide':('徽章记录本人的资格与考试结果；技能要通过真实练习掌握。','Badges record individual qualifications and exam outcomes. Skills require real practice.'),
        'wear.0':('徽章已摘下','Badge off'),
        'wear.1':('徽章佩戴中','Badge on'),
        'wear.2':('饰物离身','No accessory'),
        'action.5':('摘下徽章','Take off badge'),
        'action.8':('切换对象','Next pet'),
        'action.10':('佩戴手中徽章','Pin held badge'),
        'help.5':('把徽章摘下来变成一枚徽章物品，等级保留；手持它右键这只油库里即可再别上。','Take the badge off as an item. Its grade is kept; hold it and right-click this yukkuri to pin it back.'),
        'help.8':('在附近你的油库里之间切换。','Switch between your yukkuri nearby.'),
        'help.10':('手里拿着这只油库里的徽章时可用；也可以关掉界面直接右键它。',"Works while you hold this yukkuri's badge. You can also close the screen and right-click it."),
    }
    for key,pair in {
        'exam':('ゆ！要认真参加考试！','Yuh! Time for my exam!'),'practice':('ゆ！慢慢学会！','Yuh! Let’s learn!'),
        'learned':('这次记住了哟！','I remembered this time!'),'recall':('听懂了，要去那边！','I understand, go over there!'),
        'thinking':('先想清楚，再回答哟……','Think first, then answer…'),'passed':('合格了！要好好珍惜徽章！','I passed! I’ll treasure my badge!'),
        'interrupted':('现在没法好好考试……','I can’t keep going now…'),'failed':('还要继续练习呢……','I need more practice…'),
        'envy':('为什么它的徽章比自己的高级……','Why is its badge better than mine…'),
        'admire':('好厉害的徽章！也想学会呢！','What a badge! I want to learn too!'),
        'help':('不会的事情，可以慢慢教你哟！','I can help you learn, slowly!'),
        'boast':('看好啦！自己可是有徽章的！','Look! I have a badge!'),
        'polite':('有徽章也要好好打招呼哟。','A badge still means greeting politely.'),
        'peer':('你也有徽章呢，一起慢慢来吧！','You have a badge too! Take it easy together!')}.items():pairs['speech.'+key]=pair
    result={'badge.mcyukkuri.'+k:v[0 if zh else 1] for k,v in pairs.items()}
    result['item.mcyukkuri.badge_handbook']='宠物手册' if zh else 'Pet Handbook'
    result['human.mcyukkuri.help.16']='带上你的宠物去考官那里参加考试、查看记录或补发徽章。' if zh else 'Bring your pet to the Examiner for exams, records or a badge reissue.'
    return result

def handbook_png():
    """16x16 handbook icon: green book with page block, plus mini gold/silver medallions from the locked badge palettes."""
    G,P=author_badges.PALETTES,author_badges._rgba
    image=Image.new('RGBA',(16,16));px=image.load()
    def fill(x0,y0,x1,y1,c):
        for y in range(y0,y1+1):
            for x in range(x0,x1+1):px[x,y]=P(c)
    ol,cover,hi,spine,deep='#17261f','#3f7a5c','#5f9d77','#2a5440','#1f3f31'
    fill(1,0,14,14,ol)                      # outline
    fill(2,1,13,13,cover);fill(2,1,13,1,hi);fill(2,1,2,13,hi)   # cover, top/left lit edge
    fill(3,1,4,13,spine);fill(3,1,3,13,'#1f4030')                 # spine
    fill(13,2,13,13,deep);fill(3,13,13,13,deep)                   # shaded right/bottom edge
    fill(14,2,14,14,'#efe3bd');fill(3,14,14,14,'#d3c391');fill(14,14,14,14,ol)  # page block
    px[14,1]=P(ol)
    def medal(x0,y0,tier):
        # 6x6 round medallion: outline only on the outer ring, 4x4 shaded domed interior, top-left light.
        pal=G[tier];rows=['.XXXX.','XXXXXX','XXXXXX','XXXXXX','XXXXXX','.XXXX.']
        cells={(i,j) for j,row in enumerate(rows) for i,ch in enumerate(row) if ch=='X'}
        for i,j in cells:
            if any(n not in cells for n in ((i+1,j),(i-1,j),(i,j+1),(i,j-1))):c=pal['outline']
            else:
                d=(i-1)+(j-1);c=pal['shade'][2] if d<=1 else pal['shade'][1] if d<=3 else pal['shade'][0]
            px[x0+i,y0+j]=P(c)
        px[x0+1,y0+1]=P(pal['spec'])
    medal(5,2,'gold');medal(8,7,'silver')
    stream=BytesIO(); image.save(stream,format='PNG'); return stream.getvalue()

def main(check=False, mounts_only=False, only=None, detached=None):
    if mounts_only:
        path=ASSETS/'yukkuri/badge_mounts.json'; data=mounts(only,detached)
        if check: assert json.loads(path.read_text(encoding='utf-8'))==data,str(path)
        else: path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print('Badge mounts '+('match.' if check else 'exported.')); return
    png=handbook_png()
    for path in [ART/'badge_handbook.png', ASSETS/'textures/item/badge_handbook.png']:
        if check: assert path.exists() and path.read_bytes()==png,str(path)
        else: path.parent.mkdir(parents=True,exist_ok=True); path.write_bytes(png)
    paths={ASSETS/'yukkuri/badge_mounts.json':mounts(),
           ASSETS/'models/item/badge_handbook.json':{'parent':'minecraft:item/generated','textures':{'layer0':'mcyukkuri:item/badge_handbook'}},
           ROOT/'src/main/resources/data/mcyukkuri/recipe/badge_handbook.json':{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':'minecraft:book'},{'item':'minecraft:gold_nugget'},{'item':'minecraft:iron_nugget'}],'result':{'id':'mcyukkuri:badge_handbook','count':1}}}
    for path,data in paths.items():
        if check:
            assert path.exists() and json.loads(path.read_text(encoding='utf-8'))==data,str(path)
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for lang in ['zh_cn','en_us']:
        path=ASSETS/'lang'/f'{lang}.json';data=json.loads(path.read_text(encoding='utf-8'));keys=language(lang=='zh_cn')
        if check:assert all(data.get(k)==v for k,v in keys.items()),lang
        else:data.update(keys);path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Badge mounts, handbook and language '+('match.' if check else 'exported.'))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');p.add_argument('--mounts-only',action='store_true')
    p.add_argument('--only',help='Refresh one variant and its derived accessory (requires --mounts-only).')
    p.add_argument('--detached',help='Refresh one detached accessory mount alone, e.g. accessory_marisa_aquatic (requires --mounts-only).')
    args=p.parse_args()
    if (args.only or args.detached) and not args.mounts_only:p.error('--only and --detached require --mounts-only')
    if args.only and args.detached:p.error('use --only or --detached, not both')
    main(args.check,args.mounts_only,args.only,args.detached)
