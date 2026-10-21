"""Build/check game reference views from manual entity/scene/emotion annotations.

game-annotations.json is the primary input. Source manifests retain provenance;
classifications.json maintains the old topic URLs. Never infer uses from titles.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
LIB=ROOT/'wiki/reference-library'
MANIFESTS=['wiki/art-references/manifest.json','wiki/art-references/ehentai/manifest.json']
FACET_FILES={'entities':'entities','scenarios':'scenarios','emotions':'emotions','targets':'production'}

def read(path): return json.loads(path.read_text(encoding='utf-8'))
def write(path,text):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text,encoding='utf-8')
def dump(path,data): write(path,json.dumps(data,ensure_ascii=False,indent=2)+'\n')
def relative(path,folder=LIB): return Path(os.path.relpath(ROOT/path,folder)).as_posix()
def cell(value): return str(value).replace('|','／').replace('\n',' ')

def collect():
    annotations=read(LIB/'game-annotations.json');config=read(LIB/'classifications.json');quality=read(LIB/'quality-selection.json')
    raw=[];sources={}
    for path in MANIFESTS:
        manifest=read(ROOT/path);raw+=manifest['images']
        for s in manifest['sources']:
            assert s['id'] not in sources,s['id']
            sources[s['id']]=s
    images={a['id']:a for a in raw};classes={a['id']:a for a in config['items']}
    assert len(images)==len(raw)
    assert len(classes)==len(config['items']) and set(classes)==set(images)
    scope={e['id']:e for e in annotations['entity_scope']}
    assert len(scope)==len(annotations['entity_scope'])
    for e in scope.values(): assert (ROOT/e['basis']).is_file(),e
    topics={t['id']:t for t in config['topics']};assets=[];seen=set()
    def annotate(a,n):
        assert n['id'] not in seen,n['id']
        seen.add(n['id'])
        assert n['focus'].strip() and n['purpose'].strip(),n['id']
        assert n['entities'] and n['scenarios'] and n['targets'],n['id']
        assert set(n['entities'])<=scope.keys(),n['id']
        for facet,spec in annotations['facets'].items():
            assert len(n[facet])==len(set(n[facet])) and set(n[facet])<=set(spec['values']),n['id']
            a[facet]=n[facet]
        a.update(focus=n['focus'],description=n['purpose'],kind='reference');assets.append(a)
    for n in annotations['images']:
        row=images[n['id']];sid=row['source_id'];source=sources[sid];c=classes[row['id']]
        assert sid not in annotations['excluded_sources'],sid
        assert n['sha256']==row['sha256'],f'Stale annotation: {row["id"]}'
        assert c['kind']=='reference' and c['topics'] and set(c['topics'])<=topics.keys(),row['id']
        work=f"wiki/art-references/{'ehentai/' if sid.startswith('EH') else ''}works/{sid}.md"
        assert (ROOT/row['path']).is_file() and (ROOT/work).is_file(),row['id']
        annotate(dict(id=row['id'],media='image',topics=c['topics'],path=row['path'],source_id=sid,
            source_url=source['url'],source_title=source['title'],source_note=source.get('artist_note',source.get('attribution_on_page','署名未确认')),
            source_index=work,sha256=row['sha256'],bytes=row['bytes'],width=row['width'],height=row['height'],review_status=n['review_method']),n)
    active_images={a['id'] for a in assets};queue={a['id']:a['state'] for a in annotations['image_queue']}
    assert len(queue)==len(annotations['image_queue']) and not active_images & queue.keys()
    assert active_images | queue.keys()==images.keys(),'New image needs a purpose or an explicit queue state'
    assert set(queue.values())<={'out_of_scope','overview_not_selected','pending_purpose_review'}
    for aid,state in queue.items():
        assert (state=='out_of_scope')==(images[aid]['source_id'] in annotations['excluded_sources']),aid
        assert classes[aid]['kind']!='reference' and not classes[aid]['topics'],aid
    cards=read(ROOT/'wiki/novel-reading/cards.json')['cards'];cards_by_id={c['id']:c for c in cards}
    assert len(cards_by_id)==len(cards)
    assert {n['id'] for n in annotations['texts']}|set(annotations['excluded_texts'])==cards_by_id.keys()
    for n in annotations['texts']:
        c=cards_by_id[n['id']];path=f"wiki/novel-reading/cards/{c['id']}.md"
        assert n['id'] not in annotations['excluded_texts'] and (ROOT/path).is_file()
        annotate(dict(id=c['id'],media='text',topics=c['topics'],path=path,source_id=c['id'],source_url=c['url'],
            source_title=c['title'],source_note=c['author_and_scope'],source_group=c['source_group'],
            observation=c['summary'],limits=c['limits'],review_status='沿用指定段落核读，补写游戏用途'),n)
    byid={a['id']:a for a in assets};reviewed=set()
    for q in quality['items']:
        aid=q['id'];assert aid not in reviewed and aid in active_images,aid
        reviewed.add(aid);a=byid[aid]
        assert q['sha256']==a['sha256'] and q['status'] in {'selected','supplement'},aid
        assert all(q.get(k) for k in ['style','reason','viewing_note','review']),aid
        a['quality']={k:v for k,v in q.items() if k not in {'id','sha256'}}
    states=Counter(queue.values());selected=[a for a in assets if a.get('quality',{}).get('status')=='selected']
    stats=dict(image_files=len(raw),image_sources=len(sources),image_bytes=sum(a['bytes'] for a in raw),
        image_unique_hashes=len({a['sha256'] for a in raw}),game_images=len(active_images),game_texts=len(annotations['texts']),
        game_image_sources=len({a['source_id'] for a in assets if a['media']=='image'}),quality_selected=len(selected),quality_reviewed=len(reviewed),
        scanned_sheets=len(annotations['scanned_sheets']),scanned_images=len(active_images)+states['overview_not_selected'],
        out_of_scope=states['out_of_scope'],overview_not_selected=states['overview_not_selected'],pending_purpose_review=states['pending_purpose_review'])
    return dict(schema_version=3,updated_on=annotations['reviewed_on'],stats=stats,facets=annotations['facets'],
        entity_scope=annotations['entity_scope'],topics=config['topics'],quality_review={k:v for k,v in quality.items() if k!='items'},assets=assets)

def table(assets,folder=LIB):
    out=['| 实体 · 情景 · 情绪 | 看哪一处 | 用于游戏制作 | 材料与出处 |','| --- | --- | --- | --- |']
    for a in sorted(assets,key=lambda a:('、'.join(a['scenarios']),'、'.join(a['entities']),a['id'])):
        tags='；'.join('、'.join(a[k]) for k in ['entities','scenarios','emotions'] if a[k])
        link=f"[{a['id']}]({relative(a['path'],folder)})"
        if a['media']=='image':link+=f" · [出处]({relative(a['source_index'],folder)})"
        else:link+=' · 文字卡'
        out.append(f"| {cell(tags)} | {cell(a['focus'])} | **{'／'.join(a['targets'])}**：{cell(a['description'])} | {link} |")
    return out

def build():
    data=collect();assets=data['assets'];s=data['stats'];dump(LIB/'catalog.json',data)
    lines=['# 游戏参考库','','[Wiki](../README.md) · **[按实体、情景、情绪筛图](gallery.html)** · [制作归纳](production-briefs.md) · [清晰画面精选](quality-picks.md)','',
        '从要做的游戏内容出发找图：选实体，再叠加情景、情绪或制作目标。每条记录指向具体画格，并说明能用于哪个模型部位、动作、表情、道具、场景或事件。作品编号只用来追溯出处。','',
        f"当前游戏参考为 **{s['game_images']} 张图片、{s['game_texts']} 张文字卡**，图片覆盖 **{s['game_image_sources']} 个来源入口**。现有扫图登记覆盖 {s['scanned_images']} 张已存图片，选用画面均注明制作用途；各批次实际阅读范围见来源登记，扫图不等于全文核读。",'',
        f"其中 {s['quality_selected']} 张保留原文件清晰度与画风精选标签。筛选器默认显示全部已写明用途的游戏图片，精选优先排列；可再开启“只看美术精选”。",'',
        '| 查找方向 | 入口与用途 |','| --- | --- |',
        '| 实体 | [实体索引](entities.md)：角色、已纳入形态，以及帽船、茎果、窝和徽章等对象 |',
        '| 情景 | [情景索引](scenarios.md)：进食、照料、睡眠、避险、交往、考核、搬运等 |',
        '| 情绪 | [情绪索引](emotions.md)：喜悦、安心、期待、困惑、愤怒、恐惧、悲伤等；没有明确情绪的物件图留空 |',
        '| 制作目标 | [制作索引](production.md)：模型、动作、表情、形变、道具、场景、事件 |','',
        '## 怎么使用','',
        '- 壳魔理沙＋采集进食：找身体低、够不到树桩食物的动作与场景关系。',
        '- 帕秋莉＋认知教学：找书页接触点，以及低头阅读到抬头应答的姿态。',
        '- 照料幼体＋安心＋动作：找侧毛拥抱、贴靠与窝内安抚。',
        '- 身体受损＋形变：找受力点、身体挤出和饰物滑移；受伤画面按实际制作价值分类。','',
        '## 范围与阅读','',
        '实体范围来自现有代码和用户采用的方向；已接入种类、形态与未做的 DOS 分开标示。未纳入种类不建参考标签、不作为新搜集候选。混合画面只登记明确指定的已纳入主体或画格。完整范围见 [game-annotations.json](game-annotations.json)。','',
        '“用于制作”是编辑归纳，不代表该机制已经实装。小说补动机和过程，图片补可见外形与姿态；具体版本冲突继续看[交叉核对](cross-checks.md)。同作者、改编和转载的关系见[来源关系](source-families.json)。','',
        '## 次级主题与原始资料','',
        '旧主题网址保留作为次级入口，内容已按当前游戏范围重建：','']
    lines+=[' · '.join(f"[{t['title']}](topics/{t['id']}.md)" for t in data['topics']),'',
        f"原始存档仍有 {s['image_files']} 个文件，约 {s['image_bytes']/1024**2:.1f} MiB。它与有效游戏参考分开计数；未选、待复核及撤出范围的材料不在筛选器中混入。见[整理进度](context-and-provenance.md)。",'',
        '[博客出处](../art-references/blog-sources.md) · [图集出处](../art-references/ehentai/README.md) · [文字出处](../novel-reading/README.md)','',
        '## 维护','',
        '逐图修改 `game-annotations.json` 的实体、情景、情绪、制作目标、画格位置和用途；未入选材料明确登记队列状态。清晰度／画风仍在 `quality-selection.json`；旧主题兼容关系在 `classifications.json`。运行 `python scripts/wiki_reference_topics.py build` 与 `check`。新文件没有用途或队列状态时检查会失败。原图字节、署名与出处保留；不直接复制为游戏贴图。','']
    write(LIB/'README.md','\n'.join(lines))
    for facet,spec in data['facets'].items():
        out=[f"# 按{spec['title']}查找游戏参考",'','[游戏参考库](README.md) · [组合筛选](gallery.html) · [制作归纳](production-briefs.md)','',
            '同一画面可在多个标签下出现；表内写明目标画格和具体制作目的。','',
            ' · '.join(f'[{v}](#{v.lower()})' for v in spec['values']),'']
        for value in spec['values']:
            subset=[a for a in assets if value in a[facet]];out+=['## '+value,'']
            if facet=='entities':out += [f"范围：{next(e['status'] for e in data['entity_scope'] if e['id']==value)}。",'']
            out+=table(subset)+['']
        write(LIB/(FACET_FILES[facet]+'.md'),'\n'.join(out))
    for t in data['topics']:
        folder=LIB/'topics';subset=[a for a in assets if t['id'] in a['topics']]
        out=[f"# {t['title']}",'','[游戏参考库](../README.md) · [实体／情景／情绪筛选](../gallery.html) · [制作归纳](../production-briefs.md)','',
            '旧主题兼容入口；主分类以实体、情景、情绪和制作目标为准。','']+table(subset,folder)+['']
        write(folder/f"{t['id']}.md",'\n'.join(out))
    out=['# 游戏参考整理进度','','[游戏参考库](README.md)','','| 状态 | 图片数 |','| --- | --- |',
        f"| 已逐图写明制作用途 | {s['game_images']} |",f"| 已扫联系表，本轮未选 | {s['overview_not_selected']} |",
        f"| 尚待按游戏目的复核 | {s['pending_purpose_review']} |",f"| 按实体范围撤出 | {s['out_of_scope']} |",'',
        '以上合计为原始存档文件数。未选不等于画质差；有的页为出处、重复姿态或本轮没有具体制作问题。待复核不计为本轮扫图。',
        '原文件与原始出处仍保留用于旧链接追溯，撤出的来源已停止继续抓取。这里不再为未纳入种类建立内容卡。','',
        '本轮只将能定位具体画格并给出制作目的的图片放入游戏筛选器。逐页状态保存在 [game-annotations.json](game-annotations.json)。','']
    write(LIB/'context-and-provenance.md','\n'.join(out));build_quality(data);build_gallery(data)
    print(json.dumps(s,ensure_ascii=False))

def build_quality(data):
    reviewed=[a for a in data['assets'] if a.get('quality')]
    out=['# 清晰画面与画风筛选','','[游戏参考库](README.md) · [组合筛选](gallery.html?quality=selected) · [逐图质量记录](quality-selection.json)','',
        f"当前 {data['stats']['quality_selected']} 张美术精选、{len(reviewed)-data['stats']['quality_selected']} 张质量补充。它们都已有明确游戏用途；清晰度与画风不再代替用途描述。",'',
        data['quality_review']['scope']+' 查看局部细节应打开原尺寸图片；作品署名与前后页从出处链接回查。','',
        '| 原图／尺寸 | 实体与情景 | 看哪一处、做什么 | 清晰度与画风 |','| --- | --- | --- | --- |']
    for a in sorted(reviewed,key=lambda a:('、'.join(a['scenarios']),a['id'])):
        q=a['quality'];tag='精选' if q['status']=='selected' else '补充'
        out.append(f"| [{a['id']}]({relative(a['path'])}) · {a['width']}×{a['height']} | {'、'.join(a['entities'])}；{'、'.join(a['scenarios'])} | {cell(a['focus'])}。{cell(a['description'])} | {tag} · {q['style']}。{cell(q['reason'])} {cell(q['viewing_note'])} |")
    write(LIB/'quality-picks.md','\n'.join(out)+'\n')

def build_gallery(data):
    payload={**data,'assets':[{**a,'href':relative(a['path']),'source_href':relative(a.get('source_index',a['path']))} for a in data['assets']]}
    encoded=json.dumps(payload,ensure_ascii=False).replace('</','<\\/')
    template=(ROOT/'scripts/wiki_reference_gallery.html').read_text(encoding='utf-8')
    write(LIB/'gallery.html',template.replace('__DATA__',encoded))

def check():
    current=collect();assert current==read(LIB/'catalog.json'),'Generated catalog is stale; run build'
    for a in current['assets']:
        if a['media']=='image':assert hashlib.sha256((ROOT/a['path']).read_bytes()).hexdigest()==a['sha256'],a['id']
        for facet,filename in FACET_FILES.items():
            if a[facet]:assert f"[{a['id']}]" in (LIB/(filename+'.md')).read_text(encoding='utf-8'),(facet,a['id'])
        for tid in a['topics']:assert f"[{a['id']}]" in (LIB/'topics'/f'{tid}.md').read_text(encoding='utf-8'),(tid,a['id'])
    page=(LIB/'gallery.html').read_text(encoding='utf-8');marker='<script id="data" type="application/json">'
    start=page.index(marker)+len(marker);payload=json.loads(page[start:page.index('</script>',start)])
    expected={**current,'assets':[{**a,'href':relative(a['path']),'source_href':relative(a.get('source_index',a['path']))} for a in current['assets']]}
    assert payload==expected,'Gallery payload differs from catalog'
    for a in payload['assets']:assert (LIB/a['href']).is_file() and (LIB/a['source_href']).is_file(),a['id']
    assert '<script src=' not in page
    print(json.dumps({'status':'PASS',**current['stats']},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=['build','check'])
    {'build':build,'check':check}[parser.parse_args().command]()
