"""Build native Japanese lines through a local AquesTalkPlayer and encode mono Vorbis.

Copies the user's tool into .local before changing presets. Never redistributes
the executable/DLLs. Writes masters, OGGs, synthesis manifest and audition page.
"""
import argparse
import csv
import hashlib
import html
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'art/audio/aquestalk'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def upsample(wave, source_rate, target_rate):
    """Windowed-sinc interpolation for the 8/10 kHz AquesTalk1 master."""
    assert target_rate >= source_rate
    result = np.zeros(round(len(wave)*target_rate/source_rate))
    for begin in range(0, len(result), 8192):
        position = np.arange(begin, min(begin+8192, len(result))) * source_rate / target_rate
        indices = np.floor(position).astype(int)[:,None] + np.arange(-16,18)[None,:]
        delta = position[:,None] - indices
        weights = np.sinc(delta) * (.5+.5*np.cos(np.pi*np.clip(delta,-17,17)/17))
        samples = wave[np.clip(indices,0,len(wave)-1)] * ((indices>=0)&(indices<len(wave)))
        result[begin:begin+len(position)] = np.sum(samples*weights,axis=1)/np.sum(weights,axis=1)
    return result


def phonetics(text):
    # AquesTalk's symbol spelling differs from ordinary kana in these cases.
    value = text.replace('\u3000', ' ').replace('〜', 'ー').replace('～', 'ー')
    # AquesTalk's symbol spelling differs from ordinary kana in these cases.
    value = value.replace('ぢ', 'じ').replace('づ', 'ず')
    value = re.sub(r'([ぁぃぅぇぉ])\1+', lambda m: m[1]+'ー'*(len(m[0])-1), value)
    for syllable in ('やぁ', 'よぉ', 'ぜぇ', 'りぃ', 'ちぃ', 'わぁ'):
        value = value.replace(syllable, syllable[0]+'ー')
    for syllable in ('ゅぅ', 'ねぇ'):
        value = value.replace(syllable, syllable[0]+'ー')
    value = re.sub(r'…+', '、', value).replace('♪', '。').replace('！', '。').replace('？', '?')
    value = value.replace(' ', '/')
    value = re.sub(r'。+', '。', value)
    return value if value.endswith(('。', '?', '、')) else value + '。'


def card(line):
    ident=line['id']
    label='八景 U 原版' if line.get('origin','native')=='native' else '项目原创（非原版引文）'
    return f'<article><strong>{ident}</strong><small>{label}</small><p>{html.escape(line["japanese"])}</p><p>{html.escape(line["chinese"])}</p><small>{", ".join(line["events"])}</small><audio controls preload="metadata" src="ogg/{ident}.ogg"></audio></article>'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--player', type=Path, required=True, help='Existing AquesTalkPlayer.exe')
    parser.add_argument('--work-directory', type=Path, default=ROOT / '.local/dialogue-species', help='Owned scratch and tool directory')
    parser.add_argument('--ids-json', type=Path, help='Only process IDs in a JSON string array or {"ids": [...]}; retain all other recordings')
    args = parser.parse_args()
    recipe = json.loads((OUT / 'recipe.json').read_text(encoding='utf-8'))
    source = json.loads((ROOT / 'art/dialogue/runtime-lines.json').read_text(encoding='utf-8'))
    selected = source['lines']
    if args.ids_json is not None:
        try:
            requested = json.loads(args.ids_json.read_text(encoding='utf-8-sig'))
        except (OSError, ValueError) as error:
            parser.error(f'Cannot read --ids-json {args.ids_json}: {error}')
        if isinstance(requested, dict):
            requested = requested.get('ids')
        if not isinstance(requested, list) or not requested or any(not isinstance(ident, str) or not ident for ident in requested):
            parser.error('--ids-json must contain a nonempty array of ID strings, or {"ids": [...]}')
        if len(set(requested)) != len(requested):
            parser.error('--ids-json contains duplicate IDs')
        by_id = {line['id']: line for line in source['lines']}
        unknown = [ident for ident in requested if ident not in by_id]
        if unknown:
            parser.error('Unknown dialogue IDs: ' + ', '.join(unknown))
        selected = [by_id[ident] for ident in requested]
    for line in selected:
        name = line['species']+'.'+line['age']
        if name not in recipe['profiles']:
            parser.error(f'Unknown voice profile {name!r} for dialogue {line["id"]}; define its actual voice in art/audio/aquestalk/recipe.json')
    assert os.name == 'nt' and args.player.is_file()
    tool = args.work_directory.resolve() / 'tool/aquestalkplayer'
    if args.player.parent.resolve() != tool.resolve():
        if not tool.exists():
            shutil.copytree(args.player.parent, tool)
        else:
            assert digest(args.player) == digest(tool / 'AquesTalkPlayer.exe'), 'Local tool version differs'
    player = tool / 'AquesTalkPlayer.exe'
    preset = tool / 'AquesTalkPlayer.preset'
    rows = list(csv.reader(io.StringIO(preset.read_text(encoding='cp932'))))
    rows = [row for row in rows if not row[0].startswith('MCY-')]
    for name, profile in recipe['profiles'].items():
        rows.append(['MCY-'+name, 'true', 'AquesTalk1', profile['voice'], profile['speed'], 100, 100, 100, 100, profile['pitch'], 'Native dialogue candidate'])
    buffer = io.StringIO(newline='')
    csv.writer(buffer, lineterminator='\r\n').writerows(rows)
    preset.write_bytes(buffer.getvalue().encode('cp932'))
    scratch = args.work_directory.resolve() / 'aques-input'
    scratch.mkdir(parents=True, exist_ok=True)
    for folder in ['masters', 'ogg']:
        (OUT / folder).mkdir(parents=True, exist_ok=True)
    prior_path=OUT/'manifest.json'
    prior=json.loads(prior_path.read_text(encoding='utf-8')) if prior_path.exists() else {}
    # Partial synthesis must never erase the manifest entries or touch the audio of unselected lines.
    clips = dict(prior.get('clips', {})) if args.ids_json is not None else {}
    player_hash = digest(player)
    preset_hash = digest(preset)
    engine_hashes = {v:digest(tool/f'aqtk1/{v}/AquesTalk.dll') for v in sorted({p['voice'] for p in recipe['profiles'].values()})}
    recipe_hash = digest(OUT/'recipe.json')
    for line in selected:
        ident = line['id']
        symbols = phonetics(line['japanese'])
        profile=recipe['profiles'][line['species']+'.'+line['age']]
        cached=prior.get('clips',{}).get(ident)
        reusable=(cached and cached['japanese']==line['japanese'] and cached['phonetics']==symbols
                  and cached['profile']==profile and cached['source_sha256']==line['source_sha256']
                  and cached.get('player_sha256',prior.get('player_sha256'))==player_hash
                  and cached.get('engine_sha256',prior.get('engine_sha256',{}).get(profile['voice']))==engine_hashes[profile['voice']]
                  and all((ROOT/cached[k]).is_file() and digest(ROOT/cached[k])==cached[h] for k,h in [('ogg','sha256'),('wav','wav_sha256')]))
        if reusable:
            clips[ident]=cached
            clips[ident]['origin']=line.get('origin','native')
            print(f'{ident}: retained',flush=True)
            continue
        textfile = scratch / (ident+'.txt')
        textfile.write_bytes(('#>'+symbols+'\r\n').encode('cp932'))
        raw = scratch / (ident+'.wav')
        raw.unlink(missing_ok=True)
        subprocess.run([str(player), '/F', str(textfile), '/P', 'MCY-'+line['species']+'.'+line['age'], '/W', str(raw)],
                       cwd=tool, check=True, timeout=20, creationflags=subprocess.CREATE_NO_WINDOW)
        assert raw.is_file(), f'No synthesized audio: {ident} / {symbols}'
        wave, rate = sf.read(raw, dtype='float64')
        assert wave.ndim == 1 and .05 < len(wave)/rate < 20 and np.max(np.abs(wave)) > .005, ident
        target_rate = recipe['output_sample_rate']
        wave = upsample(wave, rate, target_rate)
        ceiling = 10**(recipe['peak_limit_dbfs']/20)
        gain = min(1, ceiling / max(np.max(np.abs(wave)), 1e-12))
        wave *= gain
        wav, ogg = OUT / 'masters' / (ident+'.wav'), OUT / 'ogg' / (ident+'.ogg')
        sf.write(wav, wave, target_rate, subtype='PCM_16')
        sf.write(ogg, wave, target_rate, format='OGG', subtype='VORBIS')
        decoded, check_rate = sf.read(ogg)
        assert check_rate == target_rate and decoded.ndim == 1 and abs(len(decoded)-len(wave))<=2
        assert np.isfinite(decoded).all() and np.max(np.abs(decoded)) < 1, ident
        clips[ident] = {'engine':'AquesTalk1','japanese':line['japanese'],'phonetics':symbols,
                        'origin':line.get('origin','native'),
                        'profile':recipe['profiles'][line['species']+'.'+line['age']],
                        'source_sha256':line['source_sha256'], 'ogg':ogg.relative_to(ROOT).as_posix(),
                        'wav':wav.relative_to(ROOT).as_posix(),'sha256':digest(ogg),'wav_sha256':digest(wav),
                        'duration_seconds':len(decoded)/target_rate,'gain':gain,
                        'player_sha256':player_hash,'preset_sha256':preset_hash,
                        'engine_sha256':engine_hashes[profile['voice']],'recipe_sha256':recipe_hash}
        print(f'{ident}: {len(decoded)/target_rate:.2f}s', flush=True)
    manifest = {'engine':'AquesTalk1','status':'implemented-candidate-awaiting-listening-review',
                'player_sha256':player_hash,'preset_sha256':preset_hash,
                'engine_sha256':engine_hashes,'recipe_sha256':recipe_hash,'clips':clips}
    if args.ids_json is not None and prior:
        # Legacy clips inherited these global hashes. Keep that provenance unchanged; newly
        # synthesized clips carry their own hashes and subsequent cache checks prefer them.
        manifest = prior | {'clips':clips}
    (OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    cards = [card(line) for line in source['lines'] if line['id'] in clips]
    page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>油库里对白 · AquesTalk 试听</title>
<style>body{font:16px/1.6 system-ui;background:#f4f0e6;color:#29271f;margin:32px auto;max-width:1100px;padding:0 20px}input{padding:12px;width:90%;font:inherit}#clips{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:16px}article{padding:18px;background:#fffdf5;border:1px solid #d4cbbc;border-radius:8px}p{margin:8px 0}small{display:block;color:#71634d}audio{width:100%;margin-top:12px}</style>
<h1>油库里对白 · AquesTalk 试听</h1><p>灵梦、魔理沙、爱丽丝、橙与帕秋莉；成体与幼语分开制作。每条注明原版或项目原创。声种、音程与中文译法为候选，尚待听审。</p>
<p><a href="../../dialogue/native-u/index.html">查原版完整对白</a> · <a href="../../../.local/audio-reference/diary/index.html">本机日记原声参考</a></p><p><input id="search" placeholder="搜索角色、adult / young、日文、中文或事件"></p><div id="clips">'''+''.join(cards)+'''</div><script>
document.getElementById('search').addEventListener('input',e=>{for(const card of document.querySelectorAll('article'))card.hidden=!card.textContent.toLowerCase().includes(e.target.value.toLowerCase())});
document.addEventListener('play',e=>{if(e.target.tagName==='AUDIO')for(const a of document.querySelectorAll('audio'))if(a!==e.target)a.pause()},true);
</script></html>'''
    (OUT/'index.html').write_text(page,encoding='utf-8')
    print(f'Processed {len(selected)} selected lines; manifest contains {len(clips)} clips. Run scripts/export_dialogue.py to install them.')


if __name__ == '__main__':
    main()
