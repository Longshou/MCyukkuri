"""Read original Hakkei U CSVs, preserve row IDs, and separately index Mod additions.

Writes art/dialogue/native-u/ (text/reference only). Never edits the source games
or exports their dialogue wholesale into Minecraft's runtime resources.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'art/dialogue/native-u'
NAMES = {'reimu':'灵梦','marisa':'魔理沙','arisu':'爱丽丝','patyu':'帕秋莉','tilen':'橙'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(original, mod):
    stream = original / 'ゆっくり八景U_Data/StreamingAssets'
    native = stream / 'yukkuri'
    translation = {}
    if mod:
        with (mod/'localization/native-dialogue.zh-CN.tsv').open(encoding='utf-8-sig',newline='') as f:
            for row in csv.DictReader(f,delimiter='\t'):
                translation[(row['relative_file'],int(row['line_1based']))] = row
    files, entries, groups = [], [], []
    for path in sorted(native.glob('*/*serifu.csv')):
        species = path.parent.name
        age = 'adult' if path.name=='sei_serifu.csv' else 'young'
        rel = path.relative_to(stream).as_posix()
        lines = path.read_text(encoding='utf-8-sig').splitlines()
        files.append({'path':rel,'sha256':sha(path),'lines':len(lines),'species':species,'age':age})
        group = '未标注'; group_line=0
        for index, raw in enumerate(lines):
            if raw.startswith('//'):
                group=raw[2:].strip();group_line=index+1
                groups.append({'file':rel,'line':group_line,'name':group})
                continue
            if not raw.strip():continue
            text=raw.replace(',','').replace('"','').strip()
            tr=translation.get((rel,index+1),{})
            # Translation is a separate ModLab annotation, never Japanese source.
            translation_matches=tr.get('source_sha256')==hashlib.sha256(raw.encode('utf-8')).hexdigest()
            entries.append({'origin':'native','species':species,'age':age,'file':rel,'line':index+1,
                'index':index,'group':group,'group_line':group_line,'text':text,'raw':raw,
                'zh_reference':tr.get('translation','').strip('"') if translation_matches else '',
                'translation_origin':'ModLab native-row translation' if translation_matches else ''})
    additions=[];mod_files=[]
    if mod:
        for path in sorted((mod/'localization').glob('*dialogue.tsv')):
            if path.name=='native-dialogue.zh-CN.tsv':continue
            with path.open(encoding='utf-8-sig',newline='') as f:
                reader=csv.DictReader(f,delimiter='\t')
                data=list(reader)
            mod_files.append({'path':path.relative_to(mod).as_posix(),'sha256':sha(path),'rows':len(data)})
            for i,row in enumerate(data,2):
                additions.append({'origin':'mod','file':path.name,'line':i,'fields':row})
    result={'version':1,'original':str(original),'source_version':'Hakkei U v9.8',
        'assembly_sha256':sha(original/'ゆっくり八景U_Data/Managed/Assembly-CSharp.dll'),
        'files':files,'groups':groups,'entries':entries,'mod_files':mod_files,'mod_entries':additions,
        'boundary':'Mod TSVs are comparison references, excluded from native corpus and runtime selection. Inline Mod code dialogue is not exhaustively indexed.'}
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'catalogue-data.js').write_text('window.HAKKEI_DIALOGUE='+json.dumps(result,ensure_ascii=False,separators=(',',':'))+';\n',encoding='utf-8')
    summary={k:v for k,v in result.items() if k not in ['entries','mod_entries','groups']}
    summary.update({'native_entries':len(entries),'native_groups':len(groups),'mod_entries':len(additions),
        'catalogue_sha256':sha(OUT/'catalogue-data.js')})
    (OUT/'sources.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Indexed {len(entries)} native rows / {len(groups)} groups / {len(files)} files; {len(additions)} separate Mod TSV rows.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original',type=Path,required=True)
    parser.add_argument('--mod-reference',type=Path)
    args=parser.parse_args()
    build(args.original.resolve(),args.mod_reference.resolve() if args.mod_reference else None)
