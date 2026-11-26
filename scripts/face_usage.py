"""Source-level emitted face coverage, deliberately checked on every runtime model."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'


def emitted_faces():
    shared = Path(subprocess.check_output(['git','rev-parse','--path-format=absolute','--git-common-dir'],cwd=ROOT,text=True).strip()).parent
    java = next((shared/'.local/tools/java').rglob('java.exe'))
    result = subprocess.run([str(java),'-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',str(ROOT/'scripts/FaceUsage.java'),str(ROOT/'src/main/java')],
                            capture_output=True,text=True,encoding='utf-8',check=True)
    usage = {}
    for line in result.stdout.splitlines():
        face, sources = line.split('\t',1)
        usage.setdefault(face,set()).update(sources.split(';'))

    def spans(value, source):
        if isinstance(value,dict):
            for span in value.get('expression_spans',[]):
                usage.setdefault(span[2],set()).add(source)
            for key,child in value.items():
                if key!='expression_spans': spans(child,source)
        elif isinstance(value,list):
            for child in value: spans(child,source)
    # Includes base/behavior clips and every presentation revision, even overridden spans.
    for directory in ('yukkuri','yukkuri_presentation'):
        for path in sorted((ASSETS/directory).glob('*.json')):
            spans(json.loads(path.read_text(encoding='utf-8')),path.relative_to(ROOT).as_posix())
    # Moment rows are data emitters, including condition overrides and predator remaps.
    moments = ASSETS/'yukkuri_presentation/moments.json'
    if moments.is_file():
        data = json.loads(moments.read_text(encoding='utf-8'))
        for ident,row in data['moments'].items():
            for step in row['steps']:
                for _,face in step['faces']: usage.setdefault(face,set()).add('moment:'+ident)
            if 'condition_face' in row: usage.setdefault(row['condition_face'],set()).add('moment:'+ident)
        for face in data['predator_faces'].values(): usage.setdefault(face,set()).add('moment:predator_faces')
    return usage


def resolve(data, model, face):
    face = data.get('aliases', {}).get(face, face)
    return data.get('models', {}).get(model, {}).get('face_aliases', {}).get(face, face)


def check_coverage(data, usage, generated=None):
    registry = json.loads((ROOT/'standards/yukkuri-library/expressions/registry.json').read_text(encoding='utf-8'))['faces']
    for model,row in data['models'].items():
        for face,sources in sorted(usage.items()):
            # A face carried by named models only (registry models) is emitted only by code that checks the form.
            if model not in registry.get(face,{}).get('models',[model]): continue
            selected = face
            if selected not in row['selectable']:
                raise ValueError(f'Unselectable emitted face id={face} model={model} source={sorted(sources)[0]}')
            path = ASSETS/'textures/entity'/model/(selected+'.png')
            if not (generated is not None and path in generated) and not path.is_file():
                raise ValueError(f'Missing emitted face texture id={face} model={model}')
