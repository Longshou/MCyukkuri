"""Index current packaged models and audio for preview/review; never change game assets."""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
OUT = ROOT / '.local/previews/models'
ROLES = [
    ('reimu','灵梦','普通种'),('marisa','魔理沙','普通种'),('chen','橙','普通种'),
    ('alice','爱丽丝','普通种'),('patchouli','帕秋莉','普通种'),('myon','妖梦','普通种'),
    ('house_reimu','家灵梦','家油'),('house_marisa','家魔理沙','家油'),
    ('remilia','蕾米莉亚','捕食种'),('flandre','弗兰','捕食种'),
    ('marisa_tsumuri','壳魔理沙','亚种'),('reimu_wasa','多毛灵梦','亚种'),
    ('reimu_deibu','灵胖','亚种'),('reimu_wasa_deibu','多毛灵胖','亚种'),
    ('myon_redeye','红眼妖梦','亚种'),('chen_longtail','长尾橙','亚种'),
    ('chen_roundtail','圆尾橙','亚种'),('marisa_kotatsu','被炉魔理沙','亚种'),
    ('reimu_sparse','稀毛灵梦','不足形态'),('reimu_face','歪脸灵梦','不足形态'),
    ('reimu_bean','豆形灵梦','不足形态'),('marisa_sparse','稀毛魔理沙','不足形态'),
    ('marisa_face','歪脸魔理沙','不足形态'),('marisa_bean','豆形魔理沙','不足形态')]

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def url(path):
    return '/' + path.relative_to(ROOT).as_posix()

def current_roles():
    variants = read(ASSETS/'yukkuri_presentation/living.json')['variants']
    forms = read(ASSETS/'yukkuri_presentation/subspecies.json')
    registered = set(variants) | set(forms)
    roles = [role for role in ROLES if role[0] in registered]
    listed = {ident for ident, _, _ in roles}
    lang = read(ASSETS/'lang/zh_cn.json')
    for ident in sorted(registered - listed):
        name = lang.get('entity.mcyukkuri.'+ident) or lang.get('subspecies.mcyukkuri.kind.'+ident) or ident
        roles.append((ident, name, '亚种' if ident in forms else '普通种'))
    return roles


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name in ('index.html', 'style.css', 'app.js', 'pose.js'):
        shutil.copy2(ROOT / 'preview/review' / name, OUT / name)
    models=[]
    motion_path=ASSETS/'yukkuri_presentation/motion_corrections.json'
    motions=read(motion_path)['models']
    motion_hash=hashlib.sha256(motion_path.read_bytes()).hexdigest()
    # Placement fit is spliced before the motion revision; both identify what a note was written against.
    fit_path=ASSETS/'yukkuri_presentation/placement_fit.json'
    fit_hash=hashlib.sha256(fit_path.read_bytes()).hexdigest()
    for ident,name,group in current_roles():
        path=ASSETS/'yukkuri'/f'{ident}.json'
        raw=path.read_bytes(); model=json.loads(raw)
        textures={p.stem:url(p) for p in sorted((ASSETS/'textures/entity'/ident).glob('*.png'))}
        assert 'neutral' in textures
        models.append(dict(id=ident,name=name,group=group,url=url(path),
            hash=hashlib.sha256(raw+motion_hash.encode()+fit_hash.encode()).hexdigest(),geometryHash=hashlib.sha256(raw).hexdigest(),textures=textures,
            formScale=1.5 if ident in ('reimu_deibu','reimu_wasa_deibu') else 1,
            actions=list(dict.fromkeys([*model['clips'], *model.get('behavior_clips',{}), *motions.get(ident,{}).get('clips',{})])),
            textureSize=model.get('texture_size',128)))
    zh=read(ASSETS/'lang/zh_cn.json'); ja=read(ASSETS/'lang/ja_jp.json')
    sounds=read(ASSETS/'sounds.json'); manifest=read(ROOT/'art/audio/aquestalk/manifest.json')['clips']
    audio=[]
    subtitles=[]
    for entry in read(ASSETS/'dialogue/catalog.json'):
        if not entry['sound']:
            subtitles.append(dict(id=entry['id'],species=entry['species'],events=entry['events'],
                                  zh=zh.get(entry['key'],''),ja=ja.get(entry['key'],'')))
            continue
        sound=sounds[entry['sound']]['sounds'][0]
        soundname=sound['name'] if isinstance(sound,dict) else sound
        path=ASSETS/'sounds'/(soundname.split(':',1)[-1]+'.ogg')
        assert path.is_file(),path
        meta=manifest.get(entry['id'],{})
        audio.append(dict(id=entry['id'],species=entry['species'],age=entry['age'],events=entry['events'],
            origin=entry['origin'],zh=zh.get(entry['key'],''),ja=ja.get(entry['key'],meta.get('japanese','')),
            url=url(path),duration=meta.get('duration_seconds',0),hash=hashlib.sha256(path.read_bytes()).hexdigest()))
    songs=read(ROOT/'art/audio/ecology/manifest.json')['clips']
    for species in ('reimu','marisa'):
        path=ASSETS/'sounds/ecology'/f'{species}_song.ogg'
        assert path.is_file()
        audio.append(dict(id=f'ecology.song.{species}',species=species,age='song',events=['song'],origin='project-original',
            zh='生态原创哼唱',ja='',url=url(path),duration=songs[species]['duration_seconds'],hash=hashlib.sha256(path.read_bytes()).hexdigest()))
    catalog=dict(models=models,audio=audio,subtitles=subtitles,motions=url(motion_path),motionHash=motion_hash,fit=url(fit_path),fitHash=fit_hash)
    (OUT/'catalog.js').write_text('window.REVIEW_CATALOG='+json.dumps(catalog,ensure_ascii=False,separators=(',',':'))+';\n',encoding='utf-8')
    print(f'Indexed {len(models)} current models, {len(audio)} playable sounds.')

if __name__=='__main__': main()
