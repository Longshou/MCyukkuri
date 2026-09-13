"""Archive selected actual framebuffer captures and the fixed-runtime test provenance."""
from pathlib import Path
import json,hashlib,shutil
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'.local/hat-interactions'
OUT=ROOT/'art/source/hat-interactions/reports/current'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    OUT.mkdir(parents=True,exist_ok=True);control=WORK/'preview/p0-control';screens=control/'screenshots'
    checks=read(WORK/'gametest/p0-control/hat-checks.json');assert len(checks)==33 and all(c['pass'] for c in checks)
    shutil.copy2(WORK/'gametest/p0-control/hat-checks.json',OUT/'game-checks.json')
    shutil.copy2(WORK/'final-build.log',OUT/'build.log')
    shutil.copy2(WORK/'isolation.json',OUT/'isolation.json')
    shutil.copy2(ROOT/'.local/marisa-deficient-rounded/resource-check/assets.json',OUT/'resources.json')
    run=read(WORK/'client-run.json');fixed=Path(run['runtime'])
    manifests={};rows=[];labels={'sparse':'稀毛','face':'歪脸','bean':'豆形','ordinary':'普通','house':'家油'}
    for prefix in ['sparse','face','bean','ordinary','house']:
        names=[prefix+'-remove',prefix+'-return']+([prefix+'-carry-side'] if prefix!='house' else [])
        for name in names:
            m=read(control/f'capture-{name}.json')
            assert len(m['captures'])>=10
            for phase in [0,4,8,12]:
                shot=Path(m['captures'][phase]['file']);assert shot.is_file()
                item=m['captures'][phase];item['framebuffer_sha256']=sha(shot)
            # Preserve the full recorded client states; raw intermediate screenshots stay in .local.
            manifests[name]=m
        images=[(prefix+'-remove-0003.png','脱帽中'),(prefix+'-remove-0015.png','帽子取下'),(prefix+'-return-0015.png','戴回完成'),(prefix+'-carry-side-0006.png','转向帽托')]
        if prefix=='house':images[-1]=(prefix+'-return-0003.png','戴回中')
        row=Image.new('RGB',(1920,340),(241,237,227));draw=ImageDraw.Draw(row);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
        for col,(name,label) in enumerate(images):
            source=screens/name;shutil.copy2(source,OUT/name)
            row.paste(Image.open(source).convert('RGB').resize((480,300)),(col*480,36));draw.text((col*480+10,8),labels[prefix]+' / '+label,font=font,fill='#303229')
        rows.append(row)
    sheet=Image.new('RGB',(1920,340*len(rows)))
    for i,row in enumerate(rows):sheet.paste(row,(0,i*340))
    sheet.save(OUT/'minecraft-overview.png')
    for name in ['sparse-remove','sparse-carry-side','face-carry-side','bean-carry-side']:
        m=manifests[name];frames=[Image.open(c['file']).convert('RGB').resize((800,500)) for c in m['captures']]
        frames[0].save(OUT/(name+'.gif'),save_all=True,append_images=frames[1:],duration=100 if 'remove' in name else 400,loop=0)
    (OUT/'client-captures.json').write_text(json.dumps(manifests,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    jar=next((fixed/'libs').glob('*.jar'))
    actual={p.relative_to(fixed).as_posix():sha(p) for p in fixed.rglob('*') if p.is_file() and (p.relative_to(fixed).parts[0] in ['classes','resources','libs'])}
    selected={}
    for name in ['marisa_sparse','marisa_face']:
        file=ROOT/'src/main/resources/assets/mcyukkuri/yukkuri'/f'{name}.json';assert sha(file)==sha(fixed/'resources/main/assets/mcyukkuri/yukkuri'/f'{name}.json');selected[name]=sha(file)
    meta=ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_interactions.json';assert sha(meta)==sha(fixed/'resources/main/assets/mcyukkuri/yukkuri_presentation/hat_interactions.json')
    report=dict(passed=True,game_assertions=len(checks),client_variants=list(labels),selected_models=selected,hat_metadata_sha256=sha(meta),jar=str(jar.relative_to(ROOT)),jar_sha256=sha(jar),runtime=str(fixed.relative_to(ROOT)),production_and_test_fingerprints=actual,client_capture_series=len(manifests),scope='One isolated GameTest with 33 assertions; five actual client takeoff/return scenes, four permitted carry combinations. No shared full-suite or multiplayer acceptance claimed.')
    (OUT/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(passed=True,assertions=len(checks),client_series=len(manifests),jar_sha256=sha(jar))))
if __name__=='__main__':main()
