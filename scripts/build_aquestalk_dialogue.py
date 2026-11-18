"""Build native Japanese lines through a local AquesTalkPlayer and encode mono Vorbis.

Copies the user's tool into .local before changing presets. Never redistributes
the executable/DLLs. OGGs and the synthesis manifest are versioned; WAV masters
stay local in .local/aquestalk-masters so the repository carries only the
runtime-ready OGG of each clip.
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
import sys

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'art/audio/aquestalk'
SHARED_SPECIES = ('reimu', 'marisa', 'alice', 'chen', 'patchouli', 'myon', 'remilia', 'flandre')
TIERS = ('baby', 'juvenile', 'young', 'adult')
SPECIAL_SHARED = (
    {'id': 'deficient_babble', 'japanese': 'ゆぴー', 'chinese': '油嗶——',
     'english': 'Yupi!', 'events': []},
)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def text_digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def source_digest(line):
    """Tier rows are generated without a stored source hash; the Japanese is the source."""
    return line.get('source_sha256') or text_digest(line['japanese'])


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
    value = text.replace('　', ' ').replace('〜', 'ー').replace('～', 'ー').replace('—', 'ー')
    # A standalone voicing mark after a kana (「ゆ゛」) is a written strain, not a phoneme AquesTalk accepts.
    value = value.replace('゛', '').replace('゜', '')
    value = value.replace('ぢ', 'じ').replace('づ', 'ず')
    # Readings the AquesTalk1 dictionary rejects or would guess wrongly in these coinages.
    value = value.replace('観楼剣', 'かんろうけん').replace('一緒', 'いっしょ')
    # Keep the first small vowel of a run (「じぇー」 stays je, not jii) and lengthen the rest.
    value = re.sub(r'([ぁぃぅぇぉァィゥェォ])(\1+)', lambda m: m.group(1) + 'ー'*len(m.group(2)), value)
    # A long mark directly after the small tsu is not a valid AquesTalk symbol; the strain
    # becomes a pause instead (「ゆっ——」 -> 「ゆっ、」).
    value = re.sub(r'っー+', 'っ、', value)
    for syllable in ('やぁ', 'よぉ', 'よぅ', 'ぜぇ', 'りぃ', 'ちぃ', 'わぁ'):
        value = value.replace(syllable, syllable[0]+'ー')
    for syllable in ('ゅぅ', 'ねぇ', 'ふぅ'):
        value = value.replace(syllable, syllable[0]+'ー')
    value = re.sub(r'…+', '、', value).replace('♪', '。').replace('！', '。').replace('？', '?')
    value = value.replace(' ', '/')
    value = re.sub(r'。+', '。', value)
    return value if value.endswith(('。', '?', '、')) else value + '。'


def raw_input(text):
    """Feed ordinary Japanese without #>, so AqKanji2Koe reads kanji for us.

    The player's #> mode treats the whole line as AquesTalk symbols and rejects every
    kanji (exit 2012), while its ordinary mode accepts the written sentence and handles
    kana, kanji and punctuation. Normalise the few characters the player cannot encode
    and the strain spellings that are invalid in either mode.
    """
    value = text.replace('　', ' ').replace('—', 'ー').replace('–', 'ー').replace('−', 'ー').replace('～', '〜')
    value = value.replace('゛', '').replace('゜', '')
    value = value.replace('観楼剣', 'かんろうけん').replace('一緒', 'いっしょ')
    # A long mark directly after the small tsu is invalid in AquesTalk's ordinary text
    # mode too (2012). Collapse a strained "っーっ" to one tsu, then drop the mark.
    value = re.sub(r'っ[ー〜～]+っ', 'っ', value)
    value = re.sub(r'っ[ー〜～]+', 'っ', value)
    value = value.replace('♪', '。')
    value.encode('cp932')
    return value


def origin_label(line):
    origin = line.get('origin') or 'native'
    return '八景 U 原版' if origin == 'native' else '八景 ver1.12 迁入' if origin == 'hakkei112' else '项目原创（非原版引文）'


def card(line):
    ident = line['id']
    return (f'<article><strong>{ident}</strong><small>{origin_label(line)}</small>'
            f'<p>{html.escape(line["japanese"])}</p><p>{html.escape(line.get("chinese", ""))}</p>'
            f'<small>{", ".join(line.get("events", []))}</small>'
            f'<audio controls preload="none" src="ogg/{ident}.ogg"></audio></article>')


def shared_card(shared_id, japanese, chinese, variants):
    options = ''.join(f'<option value="{html.escape(cid)}">{html.escape(cid[len(shared_id)+1:])}</option>'
                      for cid in variants)
    return (f'<article><strong>{html.escape(shared_id)}</strong><small>共享情境（按说话者种族与阶段各录一版）</small>'
            f'<p>{html.escape(japanese)}</p><p>{html.escape(chinese)}</p>'
            f'<select onchange="this.nextElementSibling.src=\'ogg/\'+this.value+\'.ogg\'">{options}</select>'
            f'<audio controls preload="none" src="ogg/{html.escape(variants[0])}.ogg"></audio></article>')


def shared_lines(situation):
    """Synthetic one-speaker rows: shared wording recorded in every species and tier."""
    rows = []
    for row in situation['lines']:
        if not row.get('shared_runtime'):
            continue
        for species in SHARED_SPECIES:
            for age in TIERS:
                rows.append({'id': f'{row["id"]}.{species}.{age}', 'shared_id': row['id'],
                             'species': species, 'age': age, 'japanese': row['ja'],
                             'chinese': row['zh'], 'english': row['en'], 'events': [row['event']],
                             'origin': 'project-original'})
    for row in SPECIAL_SHARED:
        for species in SHARED_SPECIES:
            for age in TIERS:
                rows.append({'id': f'{row["id"]}.{species}.{age}', 'shared_id': row['id'],
                             'species': species, 'age': age, 'japanese': row['japanese'],
                             'chinese': row['chinese'], 'english': row['english'], 'events': row['events'],
                             'origin': 'project-original'})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--player', type=Path, required=True, help='Existing AquesTalkPlayer.exe')
    parser.add_argument('--work-directory', type=Path, default=ROOT / '.local/dialogue-species', help='Owned scratch and tool directory')
    parser.add_argument('--masters-directory', type=Path, default=ROOT / '.local/aquestalk-masters', help='Local-only WAV masters (never versioned)')
    parser.add_argument('--ids-json', type=Path, help='Only process IDs in a JSON string array or {"ids": [...]}; retain all other recordings')
    parser.add_argument('--keep-going', action='store_true', help='Record a failed line and continue with the rest')
    parser.add_argument('--checkpoint-every', type=int, default=100, help='Write the manifest every N processed lines (0 disables)')
    parser.add_argument('--failures-json', type=Path, default=ROOT / '.local/aquestalk-failures.json')
    parser.add_argument('--manifest', type=Path, default=OUT/'manifest.json', help='Manifest to read and write (shard runs use a private copy)')
    parser.add_argument('--no-index', action='store_true', help='Skip the audition page (used by parallel shard runs)')
    args = parser.parse_args()
    recipe = json.loads((OUT / 'recipe.json').read_text(encoding='utf-8'))
    source = json.loads((ROOT / 'art/dialogue/runtime-lines.json').read_text(encoding='utf-8'))
    tiers_path = ROOT / 'art/dialogue/speech-tiers.json'
    all_lines = list(source['lines'])
    if tiers_path.exists():
        all_lines += json.loads(tiers_path.read_text(encoding='utf-8'))['lines']
    situation = json.loads((ROOT / 'art/dialogue/corpus/situation.json').read_text(encoding='utf-8'))
    all_lines += shared_lines(situation)
    by_id = {}
    for line in all_lines:
        if line['id'] in by_id:
            parser.error(f'Duplicate dialogue ID {line["id"]!r}')
        by_id[line['id']] = line
    selected = all_lines
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
    masters = args.masters_directory.resolve()
    masters.mkdir(parents=True, exist_ok=True)
    (OUT / 'ogg').mkdir(parents=True, exist_ok=True)
    prior_path=args.manifest
    prior=json.loads(prior_path.read_text(encoding='utf-8')) if prior_path.exists() else {}
    # Keep entries for lines that are not selected in this run, so a partial batch or an
    # interrupted full batch can resume without re-synthesizing completed audio.
    clips = dict(prior.get('clips', {}))
    player_hash = digest(player)
    preset_hash = digest(preset)
    engine_hashes = {v:digest(tool/f'aqtk1/{v}/AquesTalk.dll') for v in sorted({p['voice'] for p in recipe['profiles'].values()})}
    recipe_hash = digest(OUT/'recipe.json')
    failures = []
    processed = 0

    def manifest():
        result = {'engine':'AquesTalk1','status':'implemented-candidate-awaiting-listening-review',
                  'player_sha256':player_hash,'preset_sha256':preset_hash,
                  'engine_sha256':engine_hashes,'recipe_sha256':recipe_hash,'clips':clips}
        if args.ids_json is not None and prior:
            # Legacy clips inherited these global hashes. Keep that provenance unchanged; newly
            # synthesized clips carry their own hashes and subsequent cache checks prefer them.
            result = prior | {'clips':clips}
        return result

    def save_manifest():
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(json.dumps(manifest(),ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')

    def fail(line, message):
        failures.append({'id':line['id'],'japanese':line['japanese'],'phonetics':phonetics(line['japanese']),
                         'speech_input':raw_input(line['japanese']),'input_mode':'raw',
                         'profile':line['species']+'.'+line['age'],'error':message})
        clips.pop(line['id'], None)
        print(f'{line["id"]}: FAILED {message}', flush=True)

    for line in selected:
        ident = line['id']
        symbols = phonetics(line['japanese'])
        speech = raw_input(line['japanese'])
        profile=recipe['profiles'][line['species']+'.'+line['age']]
        cached=prior.get('clips',{}).get(ident)
        # Legacy clips were fed AquesTalk symbols; every new clip uses ordinary Japanese,
        # letting AqKanji2Koe read kanji instead of rejecting it with exit 2012.
        cached_mode = cached.get('input_mode','phonetic') if cached else 'raw'
        expected_input = symbols if cached_mode == 'phonetic' else speech
        cached_input = cached.get('speech_input', cached['phonetics']) if cached else ''
        # The original 559 tracked clips carry a 'wav' master. Their audio and phonetics
        # predate this script, so their exact symbol spelling must not force a re-record.
        input_matches = cached is not None and ('wav' in cached or cached_input == expected_input)
        reusable=(cached and cached['japanese']==line['japanese'] and input_matches
                  and cached['profile']==profile and cached['source_sha256']==source_digest(line)
                  and cached.get('player_sha256',prior.get('player_sha256'))==player_hash
                  and cached.get('engine_sha256',prior.get('engine_sha256',{}).get(profile['voice']))==engine_hashes[profile['voice']]
                  and (ROOT/cached['ogg']).is_file() and digest(ROOT/cached['ogg'])==cached['sha256'])
        if reusable:
            clips[ident]=cached
            clips[ident]['origin']=line.get('origin') or 'native'
            print(f'{ident}: retained',flush=True)
            processed += 1
            if args.checkpoint_every and processed % args.checkpoint_every == 0:
                save_manifest()
            continue
        clips.pop(ident, None)
        textfile = scratch / (ident+'.txt')
        textfile.write_bytes((speech+'\r\n').encode('cp932'))
        raw = scratch / (ident+'.wav')
        raw.unlink(missing_ok=True)
        try:
            subprocess.run([str(player), '/F', str(textfile), '/P', 'MCY-'+line['species']+'.'+line['age'], '/W', str(raw)],
                           cwd=tool, check=True, timeout=20, creationflags=subprocess.CREATE_NO_WINDOW,
                           capture_output=True)
            assert raw.is_file(), f'No synthesized audio: {ident} / {symbols}'
            wave, rate = sf.read(raw, dtype='float64')
            assert wave.ndim == 1 and .05 < len(wave)/rate < 20 and np.max(np.abs(wave)) > .005, ident
            target_rate = recipe['output_sample_rate']
            wave = upsample(wave, rate, target_rate)
            ceiling = 10**(recipe['peak_limit_dbfs']/20)
            gain = min(1, ceiling / max(np.max(np.abs(wave)), 1e-12))
            wave *= gain
            wav, ogg = masters / (ident+'.wav'), OUT / 'ogg' / (ident+'.ogg')
            sf.write(wav, wave, target_rate, subtype='PCM_16')
            sf.write(ogg, wave, target_rate, format='OGG', subtype='VORBIS')
            decoded, check_rate = sf.read(ogg)
            assert check_rate == target_rate and decoded.ndim == 1 and abs(len(decoded)-len(wave))<=2
            assert np.isfinite(decoded).all() and np.max(np.abs(decoded)) < 1, ident
        except subprocess.CalledProcessError as error:
            detail = (error.stdout or b'').decode('cp932','replace') + (error.stderr or b'').decode('cp932','replace')
            if not args.keep_going: raise
            fail(line, f'exit {error.returncode}: {detail.strip()}')
            processed += 1
            continue
        except subprocess.TimeoutExpired:
            if not args.keep_going: raise
            fail(line, 'timeout')
            processed += 1
            continue
        except Exception as error:
            if not args.keep_going: raise
            fail(line, f'{type(error).__name__}: {error}')
            processed += 1
            continue
        master_relative = wav.relative_to(ROOT).as_posix() if wav.is_relative_to(ROOT) else str(wav)
        clips[ident] = {'engine':'AquesTalk1','japanese':line['japanese'],'phonetics':symbols,
                        'speech_input':speech,'input_mode':'raw',
                        'origin':line.get('origin') or 'native',
                        'profile':profile,
                        'source_sha256':source_digest(line), 'ogg':ogg.relative_to(ROOT).as_posix(),
                        'sha256':digest(ogg), 'master':master_relative, 'master_sha256':digest(wav),
                        'duration_seconds':len(decoded)/target_rate,'gain':gain,
                        'player_sha256':player_hash,'preset_sha256':preset_hash,
                        'engine_sha256':engine_hashes[profile['voice']],'recipe_sha256':recipe_hash}
        if line.get('shared_id'):
            clips[ident]['shared_id'] = line['shared_id']
            clips[ident]['species'] = line['species']
            clips[ident]['age'] = line['age']
        print(f'{ident}: {len(decoded)/target_rate:.2f}s', flush=True)
        processed += 1
        if args.checkpoint_every and processed % args.checkpoint_every == 0:
            save_manifest()
    if args.ids_json is None:
        keep = {line['id'] for line in all_lines}
        clips = {ident: clip for ident, clip in clips.items() if ident in keep}
    save_manifest()
    if failures:
        args.failures_json.parent.mkdir(parents=True, exist_ok=True)
        args.failures_json.write_text(json.dumps(failures, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    else:
        args.failures_json.unlink(missing_ok=True)
    if args.no_index:
        print(f'Processed {len(selected)} selected lines; manifest contains {len(clips)} clips; {len(failures)} failures.', flush=True)
        if failures:
            print(f'Failures written to {args.failures_json}', flush=True)
            sys.exit(1)
        return
    normal = [line for line in all_lines if not line.get('shared_id')]
    normal_cards = [card(line) for line in normal if line['id'] in clips]
    grouped = {}
    for line in all_lines:
        shared_id = line.get('shared_id')
        if shared_id and line['id'] in clips:
            grouped.setdefault(shared_id, []).append(line)
    shared_by_id = {row['id']: row for row in situation['lines'] if row.get('shared_runtime')}
    shared_by_id.update({row['id']: row for row in SPECIAL_SHARED})
    shared_cards = []
    for shared_id, lines in grouped.items():
        row = shared_by_id[shared_id]
        japanese = row.get('ja') or row.get('japanese')
        chinese = row.get('zh') or row.get('chinese')
        shared_cards.append(shared_card(shared_id, japanese, chinese, [line['id'] for line in lines]))
    cards = shared_cards + normal_cards
    page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>油库里对白 · AquesTalk 试听</title>
<style>body{font:16px/1.6 system-ui;background:#f4f0e6;color:#29271f;margin:32px auto;max-width:1100px;padding:0 20px}input,select{padding:12px;font:inherit}#clips{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:16px}article{padding:18px;background:#fffdf5;border:1px solid #d4cbbc;border-radius:8px}p{margin:8px 0}small{display:block;color:#71634d}audio{width:100%;margin-top:12px}select{width:100%;margin-top:12px}</style>
<h1>油库里对白 · AquesTalk 试听</h1><p>灵梦、魔理沙、爱丽丝、橙、帕秋莉、妖梦、蕾米莉亚与芙兰朵露；成体、幼语、子油、赤油分开制作。共享情境按说话者种族与阶段各录一版，用下拉框切换。声种、音程与中文译法为候选，尚待听审。</p>
<p><a href="../../dialogue/native-u/index.html">查原版完整对白</a> · <a href="../../../.local/audio-reference/diary/index.html">本机日记原声参考</a></p><p><input id="search" placeholder="搜索角色、adult / young / juvenile / baby、日文、中文或事件"></p><div id="clips">'''+''.join(cards)+'''</div><script>
document.getElementById('search').addEventListener('input',e=>{for(const card of document.querySelectorAll('article'))card.hidden=!card.textContent.toLowerCase().includes(e.target.value.toLowerCase())});
document.addEventListener('play',e=>{if(e.target.tagName==='AUDIO')for(const a of document.querySelectorAll('audio'))if(a!==e.target)a.pause()},true);
</script></html>'''
    (OUT/'index.html').write_text(page,encoding='utf-8',newline='\n')
    print(f'Processed {len(selected)} selected lines; manifest contains {len(clips)} clips; {len(failures)} failures. Run scripts/export_dialogue.py to install them.')
    if failures:
        print(f'Failures written to {args.failures_json}', flush=True)
        sys.exit(1)


if __name__ == '__main__':
    main()
