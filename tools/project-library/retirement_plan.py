"""Read-only inventory of retired source directories and archive copies; never deletes."""
from pathlib import Path, PurePosixPath
from datetime import datetime, timezone
import json, os, zipfile

ROOT=Path(__file__).resolve().parents[2]
LIB=ROOT/'art/library'
retired=json.loads((LIB/'collections.json').read_text(encoding='utf-8'))['retired']
names=set(retired)
skip={'.git','node_modules','__pycache__','npm-cache','chrome-profile','.gradle','build'}
directories=[];mixed=[];metadata=[];issues=[]
def relative(p):return p.relative_to(ROOT).as_posix()
def matches(name):return bool(names & set(PurePosixPath(name.replace('\\','/')).parts))

for current,dirs,files in os.walk(ROOT):
    parent=Path(current)
    for name in list(dirs):
        if name in skip:dirs.remove(name);continue
        if name not in names:continue
        folder=(parent/name).resolve()
        if not folder.is_relative_to(ROOT):raise ValueError(str(folder))
        members=[p for p in folder.rglob('*') if p.is_file()]
        directories.append(dict(path=relative(folder),collection=name,files=len(members),bytes=sum(p.stat().st_size for p in members)))
        dirs.remove(name)
    for name in files:
        p=parent/name
        if p.suffix=='.zip':
            try:
                with zipfile.ZipFile(p) as z:
                    rejected=[i for i in z.infolist() if matches(i.filename)]
                    if rejected:
                        mixed.append(dict(path=relative(p),retired_members=[i.filename for i in rejected],retired_bytes=sum(i.file_size for i in rejected),retained_members=len(z.infolist())-len(rejected),action='Rewrite archive preserving every other member byte-for-byte; then verify checksums before replacement.'))
            except zipfile.BadZipFile:issues.append(dict(path=relative(p),error='Invalid ZIP'))
        elif p.suffix=='.json' and ('snapshots' in p.parts or name in {'catalog.json','latest-motion-snapshot.json','RESTORE-REPORT.json'}):
            value=p.read_text(encoding='utf-8-sig')
            found=[item for item in names if item in value]
            if found:metadata.append(dict(path=relative(p),retired_references=sorted(found)))

report=dict(schema_version=1,scanned_utc=datetime.now(timezone.utc).isoformat(),
    status='blocked_by_execution_policy' if directories or mixed else 'no_retired_asset_copies_found',
    deletion_executed=False,reason='Native PowerShell deletion rejected: blocked by policy. This script only inventories remaining copies.',
    retired=retired,directories=directories,mixed_archives=mixed,metadata_to_refresh=metadata,scan_issues=issues,
    summary=dict(directories=len(directories),files_in_directories=sum(d['files'] for d in directories),bytes_in_directories=sum(d['bytes'] for d in directories),mixed_archives=len(mixed),retired_archive_members=sum(len(a['retired_members']) for a in mixed)))
(LIB/'retirement-plan.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report['summary'],ensure_ascii=False),flush=True)
