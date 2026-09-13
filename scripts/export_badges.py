"""Export posed accessory attachment coordinates, owned badge UI strings and handbook recipe."""
from pathlib import Path
import argparse, hashlib, json, math
from PIL import Image, ImageDraw
from io import BytesIO

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

def mounts():
    recipe=json.loads((ART/'mounts.json').read_text(encoding='utf-8'));result={'format':1}
    for kind in ['variants','detached']:
        result[kind]=export_mount_group(recipe[kind], 'yukkuri' if kind=='variants' else 'yukkuri_props')
    return result

def export_mount_group(specs,folder):
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
        out[name]={'model_sha256':hashlib.sha256(raw).hexdigest(),'part':spec['part'],'triangle':best[1],
                   'weights':best[2],'rest_point':best[3],'rest_normal':best[4],'diameter':spec['diameter']/16}
    return out

def language(zh):
    pairs={
        'title':('金银章档案与考核','Badge records and assessment'),
        'shop':('金银章考核 / 补发','Badge assessment / reissue'),
        'subject':('对象：%s（%s/%s）','Subject: %s (%s/%s)'),
        'no_subject':('请将自己的油库里带到身边','Bring your own yukkuri here'),
        'certificate':('资格：%s · %s · %s','Grade: %s · %s · %s'),
        'skills':('知识 %s / 理解 %s / 社会应对 %s（满分100）','Knowledge %s / Reasoning %s / Social %s (max 100)'),
        'routines':('食盆进食 %s · 如厕 %s · 休息 %s · 等待课 %s/3','Bowl %s · Toilet %s · Rest %s · Wait lessons %s/3'),
        'requirements':('银章：三项60，进食/如厕各2，休息1，等待课1','Silver: skills 60; bowl/toilet 2; rest 1; wait 1'),
        'thresholds':('金章：三项80，进食/如厕各3，休息2，等待课2','Gold: skills 80; bowl/toilet 3; rest 2; wait 2'),
        'session':('当前：%s %s · %s秒 · 冷却%s秒','Now: %s %s · %ss · Cooldown %ss'),
        'result':('最近结果：%s（报名%s次）','Last result: %s (attempts %s)'),
        'scores':('等待 / 生活知识 / 理解 / 社会 / 行动：%s %s %s %s %s','Wait / Living / Reason / Social / Move: %s %s %s %s %s'),
        'examiner_hint':('白天报名；银章3、金章8绿宝石；中断不退费','Daytime: silver 3 / gold 8 emeralds; no refund on stop'),
        'home_hint':('训练每次15秒；升级需到商店找店员报名','Practice: 15s per lesson. Visit a shop to take the exam.'),
        'condition_hint':('保持近距离、安全、清洁；先满足饥饿、睡眠、如厕','Stay nearby, safe and clean; meet urgent needs first.'),
        'unavailable':('无法办理：查看对象归属、距离、冷却、身体状态、营业时间和费用。','Unavailable: check owner, distance, cooldown, needs, hours and fee.'),
        'token_refused':('这枚徽章不能佩戴：须属于该个体的有效编号、已有资格并已摘章，且饰物在身。','Cannot attach: the current token must belong to this certified, unbadged animal with its accessory.'),
        'token_hint':('身份绑定圆牌；右键原个体佩戴。空白章、旧编号不能取得资格。','Bound ID badge; use on its animal. Blank or obsolete tokens do not grant a grade.'),
        'book_hint':('右键油库里查看档案并训练；店员可办理考核。','Use on a yukkuri to review and train; shopkeepers examine.'),
        'guide':('用金银章手册右键油库里。未登记且信任达到10的个体可登记为家油。用手册练知识、理解和社会应对；原有口令课练等待。食盆进食、厕所排泄和完整休息会积累生活记录。带自己的油库里到商店，点“金银章考核”。实测等待、生活知识、理解、社会和走到考位；银章每项60分，金章80分。失败或中断需等60秒。徽章附着于饰物，摘饰物保留资格；摘章后右键原个体重戴，丢失可花1绿宝石补发，旧编号随即失效。','Use the handbook on a yukkuri. Unowned animals with trust 10 can register. Train knowledge, reasoning and social skills; existing command lessons teach waiting. Bowl meals, toilet use and completed rest build records. Bring your pet to a shop for a five-stage exam: wait, living knowledge, reasoning, social response and walking to a marker. Silver requires 60 per stage, gold 80. Failure/interruption has a 60s cooldown. Badges attach to accessories; removing either preserves certification. Reissue costs 1 emerald and invalidates the old serial.')
    }
    groups={
        'source': [('无记录','No record'),('旧存档资格，未复核','Legacy, not re-examined'),('店内既有评级','Shop stock certificate'),('考核取得','Examined')],
        'wear':[('已摘章','Detached'),('已佩戴','Worn'),('饰物离身','Accessory absent')],
        'action':[('知识练习','Knowledge'),('理解练习','Reasoning'),('社会情境练习','Social practice'),('银章考试 · 3绿宝石','Silver exam · 3E'),('金章考试 · 8绿宝石','Gold exam · 8E'),('摘下徽章','Detach'),('补发 · 1绿宝石','Reissue · 1E'),('登记家油','Register pet'),('切换对象','Next subject'),('中止本次','Stop session'),('佩戴手中徽章','Attach held badge')],
        'help':[('每次15秒完成后知识+10；只上口令课不能取得徽章。','15s for +10 knowledge; command lessons alone cannot certify.'),('练条件顺序与他者视角；每次15秒，理解+10。','Practice ordered conditions and perspectives: 15s, +10 reasoning.'),('练礼貌、求助和识别情境；每次15秒，社会应对+10。','Practice manners, help and recognition: 15s, +10 social.'),('五项实测均须60；失败或中断保留记录并冷却60秒。','All five stages need 60; failures persist with a 60s cooldown.'),('复杂条件与社会情境均须80；晋级失败保留银章。','Complex reasoning and social scenarios need 80; failure retains silver.'),('摘章生成绑定原个体编号的物品；资格保留。','Remove to receive the bound token; certification remains.'),('仅补发已取得的资格；旧编号立即失效。','Reissues existing certification only; old serial becomes invalid.'),('无主人、非店内库存，且信任达到10时可登记。','Requires unowned, non-stock animal with trust at least 10.'),('对象列表在打开时固定，不会因排序变化错选。','The subject list is fixed when opened.'),('停止训练或考试；中断考试不退费。','Stop practice/exam; interrupted exams are not refunded.'),('手持这只个体的有效徽章；也可关界面后直接右键佩戴。','Hold this animal’s current token, or use it directly after closing.')],
        'session':[('空闲','Idle'),('知识练习','Knowledge'),('理解练习','Reasoning'),('社会练习','Social'),('考核中','Exam')],
        'stage':[('等待口令','Wait command'),('生活与基础知识','Living / knowledge'),('条件与他者视角','Conditions / perspective'),('礼貌求助与识别','Manners / help / recognition'),('走到指定考位','Walk to marker')]
    }
    for prefix, entries in groups.items():
        for i,pair in enumerate(entries):pairs[prefix+'.'+str(i)]=pair
    for i,pair in {0:('尚无结果','No result'),1:('合格，已佩章','Passed, badge attached'),2:('中断，未发证','Interrupted, no new certificate'),10:('等待不合格','Wait failed'),11:('生活记录或知识不足','Living records / knowledge insufficient'),12:('理解不足','Reasoning insufficient'),13:('社会应对不足','Social response insufficient'),14:('未到达考位','Did not reach marker')}.items():pairs['result.'+str(i)]=pair
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
    questions={
        1:[('题目：先等待口令，不能抢先行动。','Task: wait for the signal before moving.'),('题目：数清食物，说明进食、如厕和休息的规矩。','Task: count food and explain daily routines.'),('题目：先安静等待，再得到点心。顺序能倒过来吗？','Task: wait quietly, then a treat. Can these be reversed?'),('题目：打招呼；遇到困难时应向谁求助？','Task: greet others; who can help when you are stuck?'),('口令：请走到绿色粒子标出的考位。','Signal: walk to the green particle marker.')],
        2:[('题目：听完整口令，在允许之前保持等候。','Task: hear all conditions; wait until permitted.'),('题目：规划三只油的用餐次序，并说明生活规矩。','Task: plan meals for three yukkuri and explain routines.'),('题目：自己闭眼后，对方还能看见自己吗？','Task: can others still see you when you close your eyes?'),('题目：同伴掉了饰物，如何判断身份并提供帮助？','Task: identify and help a companion without its accessory.'),('口令：确认路线安全，再走到绿色考位。','Signal: check the route, then walk to the green marker.')]
    }
    for tier,entries in questions.items():
        for stage,pair in enumerate(entries):pairs[f'question.{tier}.{stage}']=pair
    result={'badge.mcyukkuri.'+k:v[0 if zh else 1] for k,v in pairs.items()}
    result['item.mcyukkuri.badge_handbook']='金银章手册' if zh else 'Badge Handbook'
    result['human.mcyukkuri.help.16']='带自己的油库里到店员身边办理考核、查看档案或补发。' if zh else 'Bring your pet for assessment, records or badge reissue.'
    return result

def handbook_png():
    image = Image.new('RGBA', (32,32)); draw = ImageDraw.Draw(image)
    draw.rectangle((5,3,26,29), fill='#293d38'); draw.rectangle((7,2,27,27), fill='#527067')
    draw.rectangle((10,4,25,25), outline='#b9c6a1'); draw.rectangle((5,4,8,26), fill='#314940')
    draw.rectangle((8,28,26,29), fill='#e9dbb3'); draw.line((10,29,25,29), fill='#a69776')
    draw.ellipse((11,7,22,18), fill='#996824'); draw.ellipse((13,9,20,16), fill='#e9c55b')
    draw.ellipse((17,14,25,22), fill='#5c7380'); draw.ellipse((18,15,24,21), fill='#c5d9dd')
    stream=BytesIO(); image.save(stream,format='PNG'); return stream.getvalue()

def main(check=False, mounts_only=False):
    if mounts_only:
        path=ASSETS/'yukkuri/badge_mounts.json'; data=mounts()
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
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');p.add_argument('--mounts-only',action='store_true');args=p.parse_args();main(args.check,args.mounts_only)
