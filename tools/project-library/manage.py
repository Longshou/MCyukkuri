"""Lossless, categorized art preservation. Never moves or deletes source files.

build: snapshot every art collection into a versioned source pack and local backup.
verify: hash-check every saved file against its pack (optionally against the workspace).
restore: restore all source packs into a NEW directory, retaining relative dependencies.
index: refresh names/status/dependency notes without changing saved asset bytes.
"""
from pathlib import Path, PurePosixPath
from datetime import datetime, timezone
import argparse, hashlib, json, shutil, time, zipfile

ROOT=Path(__file__).resolve().parents[2]
LIB=ROOT/'art/library'
CACHE={'node_modules','__pycache__','chrome-profile','npm-cache','node-compile-cache'}
LOCAL_ONLY={'reference','hakkei-reference','frames','temp'}
STATUSES={'selected-base':'选定基础','candidate':'候选保留','historical':'历史保留','working':'已制作，待实体接入','support':'共享工具'}
class SourceChanged(RuntimeError):pass

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def digest_file(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def rel(path):return path.relative_to(ROOT).as_posix()
def retired_ids():return set(read(LIB/'collections.json').get('retired',{}))
def assert_allowed(entries):
    retired=retired_ids()
    forbidden=[e['path'] for e in entries if retired & set(PurePosixPath(e['path']).parts)]
    if forbidden:raise ValueError(f'Retired assets cannot be saved or restored: {forbidden[:5]}')
def inventory(files):
    return [dict(path=rel(p),bytes=p.stat().st_size,sha256=digest_file(p)) for p in sorted(set(files))]
def list_files(folder):
    return [p for p in folder.rglob('*') if p.is_file() and not (set(p.relative_to(folder).parts)&CACHE) and p.suffix not in {'.pyc','.log'}]
def portable(path):
    parts=set(path.relative_to(ROOT).parts)
    return not(parts&LOCAL_ONLY) and not path.name.startswith('reference-') and path.suffix!='.zip'
def fingerprint(entries):
    return hashlib.sha256(json.dumps(entries,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def verify_members(archive,entries):
    expected={e['path']:e for e in entries}
    with zipfile.ZipFile(archive) as z:
        assert len(z.namelist())==len(expected) and set(z.namelist())==set(expected),f'Member mismatch: {archive}'
        for name,e in expected.items():
            with z.open(name) as f:actual=hashlib.file_digest(f,'sha256').hexdigest()
            assert actual==e['sha256'] and z.getinfo(name).file_size==e['bytes'],name
def make_pack(folder,entries):
    assert_allowed(entries)
    token=fingerprint(entries);archive=folder/(token[:20]+'.zip');manifest=archive.with_suffix('.manifest.json')
    folder.mkdir(parents=True,exist_ok=True)
    if archive.exists():
        if not manifest.exists():
            verify_members(archive,entries)
            write(manifest,dict(schema_version=1,content_sha256=token,files=entries))
        if read(manifest)['files']!=entries:
            raise RuntimeError(f'Existing snapshot metadata mismatch: {archive}')
        return dict(archive=rel(archive),manifest=rel(manifest),sha256=digest_file(archive),files=len(entries),bytes=archive.stat().st_size)
    temporary=archive.with_suffix('.partial')
    if temporary.exists():
        # A fully written pack may remain when a Windows indexer briefly locks the rename.
        # Partial or damaged packs fail verification and are never silently overwritten.
        try:verify_members(temporary,entries)
        except (AssertionError,zipfile.BadZipFile):
            # Preserve interrupted output for inspection, then start a fresh pack.
            temporary.rename(temporary.with_name(temporary.name+'.incomplete-'+str(time.time_ns())))
    if not temporary.exists():
        with zipfile.ZipFile(temporary,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as z:
            for entry in entries:
                p=ROOT/entry['path']
                if p.stat().st_size!=entry['bytes'] or digest_file(p)!=entry['sha256']:
                    raise SourceChanged(f'Source changed during snapshot: {p}')
                info=zipfile.ZipInfo(entry['path'],date_time=(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                copied=hashlib.sha256()
                with p.open('rb') as src,z.open(info,'w',force_zip64=True) as dst:
                    while chunk:=src.read(1024*1024):dst.write(chunk);copied.update(chunk)
                if copied.hexdigest()!=entry['sha256']:raise SourceChanged(f'Source changed while reading: {p}')
    for attempt in range(6):
        try:temporary.rename(archive);break
        except PermissionError:
            if attempt==5:raise
            time.sleep(.1*2**attempt)
    write(manifest,dict(schema_version=1,content_sha256=token,files=entries))
    result=dict(archive=rel(archive),manifest=rel(manifest),sha256=digest_file(archive),files=len(entries),bytes=archive.stat().st_size)
    verify_pack(result)
    return result
def verify_pack(pack,workspace=False):
    archive=ROOT/pack['archive'];manifest=read(ROOT/pack['manifest'])
    assert_allowed(manifest['files'])
    assert digest_file(archive)==pack['sha256'],f'Archive checksum: {archive}'
    assert fingerprint(manifest['files'])==manifest['content_sha256']
    entries={e['path']:e for e in manifest['files']}
    with zipfile.ZipFile(archive) as z:
        assert len(z.namelist())==len(entries) and set(z.namelist())==set(entries),f'Member mismatch: {archive}'
        for name,e in entries.items():
            with z.open(name) as f:actual=hashlib.file_digest(f,'sha256').hexdigest()
            assert actual==e['sha256'] and z.getinfo(name).file_size==e['bytes'],name
            if workspace:assert digest_file(ROOT/name)==e['sha256'],f'Workspace changed: {name}'
    return len(entries)
def clips(files):
    out=[]
    for p in files:
        if not p.name.endswith('.animation.json'):continue
        for name,a in read(p).get('animations',{}).items():
            content=json.dumps(a,sort_keys=True,separators=(',',':')).encode()
            out.append(dict(name=name,duration=a.get('animation_length'),loop=a.get('loop',False),source=rel(p),sha256=hashlib.sha256(content).hexdigest()))
    return out
def editable_clips(files):
    out=[]
    for p in files:
        if p.suffix!='.bbmodel':continue
        model=read(p)
        for a in model.get('animations',[]):
            out.append(dict(name=a.get('name'),duration=a.get('length'),loop=a.get('loop'),source=rel(p)))
        del model
    return out

def build():
    config=read(LIB/'collections.json');timestamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    previous={c['id']:c for c in read(LIB/'catalog.json')['collections']} if (LIB/'catalog.json').exists() else {}
    collections=[];full_packs=[]
    for folder in sorted((ROOT/'variants').iterdir()):
        if not folder.is_dir() or folder.name in CACHE:continue
        if folder.name in config.get('retired',{}):continue
        meta=config['collections'].get(folder.name,dict(category='history',title=folder.name,status='candidate'))
        if meta.get('status') == 'frozen':continue
        for attempt in range(4):
            try:
                files=list_files(folder);sourcefiles=[p for p in files if portable(p)]
                source_entries=inventory(sourcefiles)
                pack=make_pack(LIB/meta['category']/folder.name,source_entries)
                full=make_pack(ROOT/'archives/art-preservation/objects'/folder.name,inventory(files))
                if inventory(sourcefiles)!=source_entries:raise SourceChanged(f'Collection changed: {folder}')
                break
            except SourceChanged:
                if attempt==3:raise
                print(f'Active edits detected in {folder.name}; retrying a fresh snapshot.',flush=True)
                time.sleep(3)
        full_packs.append(dict(collection=folder.name,**full))
        preview=next((rel(folder/n) for n in ['preview.html','index.html'] if (folder/n).exists()),None)
        old=previous.get(folder.name)
        unchanged=old and old['package']['sha256']==pack['sha256']
        records=old['clips'] if unchanged else clips(sourcefiles)
        model_records=old.get('editable_clips',[]) if unchanged else editable_clips(sourcefiles)
        scripts=[rel(p) for p in sourcefiles if p.suffix in {'.py','.js','.cjs','.mjs'}]
        entry=dict(id=folder.name,category=meta['category'],title=meta['title'],status=meta.get('status','working'),workspace=rel(folder),preview=preview,dependencies=meta.get('dependencies',[]),package=pack,clips=records,editable_clips=model_records,scripts=scripts)
        collections.append(entry)
        print(f"Saved {folder.name}: {len(sourcefiles)} source files, {len(records)} exported clips",flush=True)
    # Root-level art history and shared runtime keep every relative import recoverable.
    shared=[];sharedfull=[]
    for name in ['models','textures','marisa-original','preview','reference']:
        folder=ROOT/name
        if folder.exists():
            files=list_files(folder);sharedfull+=files
            shared += [p for p in files if portable(p)]
    shared += [p for p in (ROOT/'tools').iterdir() if p.is_file()]
    shared += [p for p in (ROOT/'tools/browser-check').iterdir() if p.is_file()]
    shared += [p for p in (ROOT/'variants').iterdir() if p.is_file()]
    sharedfull += shared
    pack=make_pack(LIB/'shared/project-support',inventory(shared))
    full_packs.append(dict(collection='project-support',**make_pack(ROOT/'archives/art-preservation/objects/project-support',inventory(sharedfull))))
    collections.append(dict(id='project-support',category='shared',title='早期模型、预览运行库与生成工具',status='support',workspace=None,preview=None,dependencies=[],package=pack,clips=clips(shared),editable_clips=editable_clips(shared),scripts=[]))
    allclips=[clip for c in collections for clip in c['clips']]
    summary=dict(collections=len(collections),source_files=sum(c['package']['files'] for c in collections),exported_clips=len(allclips),editable_model_clips=sum(len(c['editable_clips']) for c in collections),distinct_clip_names=len({c['name'] for c in allclips}),distinct_clip_contents=len({c['sha256'] for c in allclips}),source_pack_bytes=sum(c['package']['bytes'] for c in collections),local_backup_files=sum(p['files'] for p in full_packs))
    snapshot=dict(schema_version=1,created_utc=timestamp,summary=summary,packages=full_packs)
    snapshot_path=ROOT/'archives/art-preservation/snapshots'/f'{timestamp}.json'
    write(snapshot_path,snapshot)
    write(ROOT/'archives/latest-preservation.json',dict(snapshot=rel(snapshot_path),sha256=digest_file(snapshot_path),summary=summary,verified=True))
    catalog=dict(schema_version=1,created_utc=timestamp,summary=summary,categories=config['categories'],collections=collections)
    write(LIB/'catalog.json',catalog)
    # A portable catalog snapshot also preserves older package selection on subsequent builds.
    write(LIB/'snapshots'/f'{timestamp}.json',catalog)
    write_indices(catalog)
    print(json.dumps(summary,ensure_ascii=False),flush=True)

def write_indices(catalog):
    lines=['# 美术与动作资料库','',
      '按用途分类的源文件快照。保留采用中和未淘汰的动作版本；明确弃用的方案不再保存或恢复。同名动作不会覆盖另一模型的曲线。',
      '原工作目录路径保留，可直接打开预览；克隆后先运行恢复命令，即可重建这些目录。','',
      '```powershell','python tools/project-library/manage.py verify',
      'python tools/project-library/manage.py restore --out ../MCyukkuri-art-restored','```','',
      '恢复目标必须是不存在的新目录，工具不会覆盖已有工程。完整流程见 [资产保存流程](../../docs/ASSET_WORKFLOW.md)。','',
      f"保存 {catalog['summary']['source_files']} 个源文件、{catalog['summary']['exported_clips']} 条动画 JSON 导出记录和 {catalog['summary']['editable_model_clips']} 条模型内嵌动作记录（包含重复与历史版本，两种记录不能相加当作独立动作数），另有互动场景状态机与表情脚本。",'']
    for category,title in catalog['categories'].items():
        lines += [f'## {title}','','| 项目 | 状态 | 动作记录 JSON / 模型内嵌 | 源文件包 | 原工作目录 |','| --- | --- | --- | --- | --- |']
        for c in catalog['collections']:
            if c['category']!=category:continue
            package=c['package']['archive'].removeprefix('art/library/')
            preview=f"[打开预览](../../{c['preview']})" if c['preview'] else '—'
            lines.append(f"| {c['title']} | {STATUSES[c['status']]} | {len(c['clips'])} / {len(c['editable_clips'])} | [完整源文件]({package}) | {preview} |")
            entrypath=LIB/category/c['id']/'README.md'
            entrylines=[f"# {c['title']}",'',f"状态：{STATUSES[c['status']]}。原目录：`{c['workspace'] or '多个共享目录'}`。",'',
             f"[当前源文件包]({Path(package).name}) · [逐文件校验清单]({Path(c['package']['manifest']).name})",'',
             'ZIP 内保存原项目相对路径。与其它源文件包一同恢复，保留跨目录生成依赖。',
             f"已记录的主要依赖：{', '.join(c['dependencies']) or '无额外变体依赖'}；验证工具可能依赖共享运行库。",'',
             '| 动作名 | 秒 | 循环 |','| --- | ---: | --- |']
            for a in c['clips']:entrylines.append(f"| `{a['name']}` | {a['duration']} | {a['loop']} |")
            if not c['clips']:entrylines.append('| 无独立动画导出；模型、表情或场景脚本见包内 | — | — |')
            entrylines+=['','## 可编辑模型内的动画','','| 模型 | 动作 | 秒 |','| --- | --- | ---: |']
            for a in c['editable_clips']:entrylines.append(f"| `{a['source']}` | `{a['name']}` | {a['duration']} |")
            entrypath.write_text('\n'.join(entrylines)+'\n',encoding='utf-8')
        lines.append('')
    lines += ['## 道具','', '[独立馅料模型](../props/filling/README.md) · [食盆与油粮模型](../props/food-bowl/README.md)','',
      '包内保留可编辑模型、几何、动画 JSON、rig、贴图、表情、行为脚本、生成/验证工具及预览。原作提取图层、逐帧渲染与本地参考另存于 `archives/art-preservation/`，不进入这些源文件包。','',
      '候选/历史状态只用于归档，不代表用户选定或完成游戏实体接入。']
    (LIB/'README.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

def verify(workspace=False):
    catalog=read(LIB/'catalog.json');count=0
    for c in catalog['collections']:count+=verify_pack(c['package'],workspace)
    report=dict(source_files=count,collections=len(catalog['collections']),source_packs_verified=True,workspace_compared=workspace)
    latest=ROOT/'archives/latest-preservation.json'
    if latest.exists():
        pointer=read(latest);snapshot=ROOT/pointer['snapshot'];assert digest_file(snapshot)==pointer['sha256']
        countfull=0
        for p in read(snapshot)['packages']:countfull+=verify_pack(p,workspace)
        report['local_backup_files']=countfull;report['local_backup_verified']=True
    write(LIB/'verification.json',report);print(json.dumps(report),flush=True)

def refresh_index():
    config=read(LIB/'collections.json');catalog=read(LIB/'catalog.json')
    if retired_ids() & {c['id'] for c in catalog['collections']}:
        raise SystemExit('Retirement policy changed: run build to create a catalog without retired assets.')
    for c in catalog['collections']:
        meta=config['collections'].get(c['id'])
        if not meta:continue
        if meta['category']!=c['category']:raise SystemExit('Category path changed: use build to create a pack in its new category.')
        c.update(title=meta['title'],status=meta.get('status','working'),dependencies=meta.get('dependencies',[]))
    catalog['metadata_updated_utc']=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    write(LIB/'catalog.json',catalog);write_indices(catalog)
    write(LIB/'snapshots'/(catalog['metadata_updated_utc']+'-index.json'),catalog)
    print('Index metadata refreshed; saved source packs unchanged.')

def restore(target):
    target=Path(target).resolve()
    if target.exists():raise SystemExit('Restore requires a new directory; existing paths are never overwritten.')
    catalog=read(LIB/'catalog.json');entries={}
    # Verify ALL bytes and paths before creating the destination.
    for c in catalog['collections']:
        pack=c['package'];verify_pack(pack)
        for e in read(ROOT/pack['manifest'])['files']:
            path=PurePosixPath(e['path'])
            if path.is_absolute() or '..' in path.parts or any(':' in p or '\\' in p for p in path.parts):raise ValueError(e['path'])
            if e['path'] in entries and entries[e['path']]!=e['sha256']:raise ValueError(f"Conflicting file {e['path']}")
            entries[e['path']]=e['sha256']
    target.mkdir(parents=True)
    for c in catalog['collections']:
        with zipfile.ZipFile(ROOT/c['package']['archive']) as z:
            for name in z.namelist():
                dest=target/Path(name);dest.parent.mkdir(parents=True,exist_ok=True)
                if dest.exists():continue
                with z.open(name) as src,dest.open('xb') as dst:shutil.copyfileobj(src,dst)
    for name,sha in entries.items():assert digest_file(target/name)==sha,name
    write(target/'RESTORE-REPORT.json',dict(files=len(entries),verified=True,catalog_created_utc=catalog['created_utc']))
    print(f'Restored and verified {len(entries)} files in {target}',flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['build','verify','restore','index']);p.add_argument('--out');p.add_argument('--workspace',action='store_true');a=p.parse_args()
    if a.command=='build':build()
    elif a.command=='verify':verify(a.workspace)
    elif a.command=='index':refresh_index()
    elif a.out:restore(a.out)
    else:p.error('restore requires --out NEW_DIRECTORY')
