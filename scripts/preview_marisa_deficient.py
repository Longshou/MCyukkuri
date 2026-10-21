"""Render and verify the two current deficient Marisa exports, without a game process."""
from runtime_model_fingerprint import matches_model_fingerprint
import base64
import copy
import gzip
import json
import math
import os
import re
import subprocess
from PIL import Image, ImageDraw, ImageFont
from export_marisa_deficient_rounded import ROOT, LIB, ASSETS, OWN, PACKAGE, SELECTED, IDS, read, save, sha, rel


def main(species='marisa'):
    global PACKAGE, IDS
    import export_marisa_deficient_rounded as exporter
    exporter.configure(species)
    PACKAGE, IDS = exporter.PACKAGE, exporter.IDS
    label = '灵梦' if species=='reimu' else '魔理沙'

    out=PACKAGE/'previews/current';out.mkdir(parents=True,exist_ok=True)
    report=PACKAGE/'reports/current';report.mkdir(parents=True,exist_ok=True)
    tmp=ROOT/'.local'/(species+'-deficient-rounded');tmp.mkdir(parents=True,exist_ok=True)
    catalog={};checks=[];jobs=[]
    donor=read(PACKAGE/'inputs/standards/yukkuri-library/assembled'/species/'runtime.model.json')
    for ident in IDS:
        path=ASSETS/'yukkuri'/f'{ident}.json';m=read(path)
        bb=read(OWN/'assembled'/ident/f'{ident}.bbmodel');rig=read(OWN/'assembled'/ident/'animation-rig.json')
        assert sha(path)==sha(SELECTED/ident/'runtime.model.json')==sha(OWN/'assembled'/ident/'runtime.model.json')
        assert len(bb['elements'])==len(m['parts'])==len(rig['groups'])
        assert rig['vertex_weights']==m['weights'] and rig['element_vertex_counts']==[p['count'] for p in m['parts']]
        assert len(m['vertices'])==len(m['weights'])*8
        assert all(math.isfinite(v) for v in m['vertices'])
        record=read(SELECTED/'manifest.json')['models'][ident]
        for texture in record['textures'].values():
            assert sha(ROOT/texture['runtime'])==sha(ROOT/texture['snapshot'])==sha(ROOT/texture['source'])==texture['sha256']
            assert Image.open(ROOT/texture['runtime']).size==(m['texture_size'],m['texture_size'])==(bb['resolution']['width'],bb['resolution']['height'])
        for name in ('rounded_body_surface','rounded_rump_mark'):
            p=next(p for p in m['parts'] if p['name']==name);d=next(p for p in donor['parts'] if p['name']==name)
            assert m['vertices'][p['start']*8:(p['start']+p['count'])*8]==donor['vertices'][d['start']*8:(d['start']+d['count'])*8]
            assert m['weights'][p['start']:p['start']+p['count']]==donor['weights'][d['start']:d['start']+d['count']]
        surface=json.loads(gzip.decompress((ASSETS/'yukkuri_wounds'/f'{ident}.json.gz').read_bytes()))
        assert matches_model_fingerprint(path.read_bytes(),surface['runtimeSourceHash']) and surface['skinCount']>0
        assert len(surface['points'])==len(surface['weights'])*5
        assert all(abs(sum(w for b,w in row)-1)<.00003 for row in surface['weights'])
        checks.append(dict(id=ident,runtime_sha256=sha(path),source_and_export_match=True,rounded_skin_and_weights_match=True,wound_surface_matches=True))
        textures={name:'data:image/png;base64,'+base64.b64encode((SELECTED/ident/'textures'/f'{name}.png').read_bytes()).decode() for name in record['textures']}
        catalog[ident]=dict(model=m,textures=textures)
        image=Image.open(SELECTED/ident/'textures/neutral.png').convert('RGBA');(tmp/f'{ident}.rgba').write_bytes(image.tobytes())
        catalog[ident+'_before']=dict(model=read(PACKAGE/'inputs/src/main/resources/assets/mcyukkuri/yukkuri'/f'{ident}.json'),textures=textures)
        old_image=Image.open(ROOT/'art/source/standard-runtime'/species/'textures/neutral.png').convert('RGBA')
        (tmp/f'{ident}_before.rgba').write_bytes(old_image.tobytes())
        for key in (ident,ident+'_before'):
            for view,az in [('front',0),('angle',.55),('side',math.pi/2),('back',math.pi)]:
                jobs.append(dict(id=key,texture=key,file=key+'-'+view,action='idle',time=0,az=az))
        for action in ('waddle','side_bend','stretch_shape'):
            for i in range(9):jobs.append(dict(id=ident,texture=ident,file=f'{ident}-{action}-{i}',action=action,time=m['clips'][action]['duration']*i/8,az=.55))
    save(tmp/'models.json',catalog)
    code="const fs=require('fs');\n"+(LIB/'runtime/render.js').read_text(encoding='utf-8')+'\n'+(LIB/'body-standard/previews/current/pose.js').read_text(encoding='utf-8')+'\n'
    code+='const models=JSON.parse(fs.readFileSync('+json.dumps(str(tmp/'models.json'))+'));const dir='+json.dumps(str(tmp))+';const jobs='+json.dumps(jobs)+';\n'
    code+="for(const j of jobs){const m=models[j.id].model;const pixels=renderModel(posed(m,j.action,j.time),fs.readFileSync(dir+'/'+j.texture+'.rgba'),400,380,j.az,.13,12.8,{centerY:10.3,textureSize:m.texture_size||128});fs.writeFileSync(dir+'/'+j.file+'.rgba',pixels);}\n"
    code+="const checks=[];for(const [id,e]of Object.entries(models)){if(id.endsWith('_before'))continue;for(const [name,c]of Object.entries(e.model.clips))for(let i=0;i<=24;i++){if(posed(e.model,name,c.duration*i/24).some(v=>!Number.isFinite(v)))throw Error(id+' '+name);checks.push({id,action:name,phase:i/24,finite:true});}}fs.writeFileSync(dir+'/poses.json',JSON.stringify(checks));"
    (tmp/'render.cjs').write_text(code,encoding='utf-8');subprocess.run(['node',str(tmp/'render.cjs')],check=True)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    def tile(name):
        im=Image.frombytes('RGBA',(400,380),(tmp/f'{name}.rgba').read_bytes())
        bg=Image.new('RGB',im.size,(241,237,227));bg.paste(im,mask=im.getchannel('A'));return bg
    labels={species+'_sparse':'稀毛'+label,species+'_face':'歪脸'+label}
    overview=Image.new('RGB',(1600,860),(241,237,227))
    for row,ident in enumerate(IDS):
        sheet=Image.new('RGB',(1600,430),(241,237,227));draw=ImageDraw.Draw(sheet)
        for col,(view,label) in enumerate([('front','正面'),('angle','斜侧'),('side','侧面'),('back','背面')]):
            draw.text((col*400+12,10),labels[ident]+' / '+label,font=font,fill='#303229');sheet.paste(tile(ident+'-'+view),(col*400,45))
        sheet.save(out/f'{ident}-views.png');overview.paste(sheet,(0,row*430))
        comparison=Image.new('RGB',(1600,430),(241,237,227));draw=ImageDraw.Draw(comparison)
        for col,(key,label) in enumerate([(ident+'_before-angle','修复前 · 斜侧'),(ident+'-angle','当前 · 斜侧'),(ident+'_before-side','修复前 · 侧面'),(ident+'-side','当前 · 侧面')]):
            draw.text((col*400+12,10),label,font=font,fill='#303229');comparison.paste(tile(key),(col*400,45))
        comparison.save(out/f'{ident}-comparison.png')
        for action in ('waddle','side_bend','stretch_shape'):
            frames=[tile(f'{ident}-{action}-{i}') for i in range(9)]
            frames[0].save(out/f'{ident}-{action}.gif',save_all=True,append_images=frames[1:],duration=120,loop=0)
        motion=Image.new('RGB',(1600,3*430),(241,237,227));draw=ImageDraw.Draw(motion)
        for row,action in enumerate(('waddle','side_bend','stretch_shape')):
            for col,phase in enumerate((0,2,4,6)):
                draw.text((col*400+10,row*430+8),action+' / '+str(phase)+'/8',font=font,fill='#303229')
                motion.paste(tile(f'{ident}-{action}-{phase}'),(col*400,row*430+45))
        motion.save(out/f'{ident}-motion.png')
    overview.save(out/'overview.png')
    catalog={k:v for k,v in catalog.items() if not k.endswith('_before')}
    (out/'models.js').write_text('const MODELS='+json.dumps(catalog,separators=(',',':'))+';',encoding='utf-8')
    base=ROOT/'.local/previews/rounded-runtime'
    html=(base/'index.html').read_text(encoding='utf-8')
    html=re.sub(r'<select id="subject">.*?</select>','<select id="subject">'+''.join(f'<option value="{i}">{labels[i]}</option>' for i in IDS)+'</select>',html)
    html=re.sub(r'<p>.*?</p>','<p>稀毛与歪脸'+label+'：圆润体态修复。保留各自外观特征；可旋转、暂停和切换动作。当前造型待确认。</p>',html,count=1)
    html=re.sub(r'<p class="note">.*?</p>','<p class="note">本轮源模型与游戏资源同步。<a href="overview.png">四视图</a> · <a href="../../README.md">来源与检查范围</a></p>',html)
    for match in list(re.finditer(r'<script src="([^"]+)"',html)):
        source=match[1]
        if source=='models.js':continue
        path=(base/source.split('?')[0]).resolve()
        html=html.replace('src="'+source+'"','src="'+os.path.relpath(path,out).replace('\\','/')+'"')
    (out/'index.html').write_text(html,encoding='utf-8')
    poses=read(tmp/'poses.json')
    save(report/'checks.json',dict(passed=True,models=checks,pose_samples=len(poses),clips_per_model=19,
         visual_review='See README.md for current visual review',minecraft='not checked by this offline script',source_manifest_sha256=sha(PACKAGE/'manifest.json')),True)
    print('Verified two sources, runtime selections, body weights and wound surfaces;',len(poses),'pose samples. Rendered current four views and three motions each.')


if __name__=='__main__':main()
