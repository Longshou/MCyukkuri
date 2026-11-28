"""Build native Japanese lines through local AquesTalk1 engines and encode mono Vorbis.

Uses scripts/aquestalk_prosody.py to direct per-emotion #> accent contours, clause
pauses, tempo and dynamic loudness while locking each species' classic AquesTalk1 timbre.
WAV masters stay local in .local/aquestalk-masters so the repository carries only the
runtime-ready 32 kHz mono OGG of each clip.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
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
import threading

import numpy as np
import soundfile as sf

from aquestalk_prosody import PROSODY_VERSION, classify_emotion, compile_emotion_clauses

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'art/audio/aquestalk'
VOICE_ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri/sounds/voice'
PS32 = Path(r'C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe')
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


def clamp(val, lo, hi):
    return max(lo, min(hi, val))


def upsample(wave, source_rate, target_rate):
    """Windowed-sinc interpolation for the AquesTalk1 master (pitch-shifted sample rate)."""
    assert target_rate >= source_rate
    if source_rate == target_rate:
        return wave
    result = np.zeros(round(len(wave) * target_rate / source_rate))
    for begin in range(0, len(result), 8192):
        position = np.arange(begin, min(begin + 8192, len(result))) * source_rate / target_rate
        indices = np.floor(position).astype(int)[:, None] + np.arange(-16, 18)[None, :]
        delta = position[:, None] - indices
        weights = np.sinc(delta) * (.5 + .5 * np.cos(np.pi * np.clip(delta, -17, 17) / 17))
        samples = wave[np.clip(indices, 0, len(wave) - 1)] * ((indices >= 0) & (indices < len(wave)))
        result[begin:begin + len(position)] = np.sum(samples * weights, axis=1) / np.sum(weights, axis=1)
    return result


def trim_silence(wave, rate, thresh_db=-45.0, pad_ms=24):
    thresh = 10 ** (thresh_db / 20.0)
    idx = np.flatnonzero(np.abs(wave) > thresh)
    if len(idx) == 0:
        return wave
    pad = int(rate * pad_ms / 1000)
    lo = max(0, int(idx[0]) - pad)
    hi = min(len(wave), int(idx[-1]) + pad + 1)
    trimmed = wave[lo:hi].copy()
    fade = min(int(rate * 0.008), len(trimmed) // 4)
    if fade > 1:
        ramp = np.linspace(0.0, 1.0, fade)
        trimmed[:fade] *= ramp
        trimmed[-fade:] *= ramp[::-1]
    return trimmed


def apply_target_peak(wave, target_dbfs):
    peak = max(float(np.max(np.abs(wave))), 1e-12)
    target = 10 ** (target_dbfs / 20.0)
    return wave * (target / peak)


def apply_tail_fade(wave, rate, fade_ms):
    if fade_ms <= 0:
        return wave
    n = min(int(rate * fade_ms / 1000), len(wave) // 2)
    if n <= 1:
        return wave
    out = wave.copy()
    out[-n:] *= np.cos(np.linspace(0.0, np.pi * 0.5, n))
    return out


class Aq1DllWorker:
    """Persistent 32-bit PowerShell bridge hosting AquesTalk1 DLL voice engines."""

    def __init__(self, tool_dir, voices):
        self.tool_dir = Path(tool_dir)
        self.voices = sorted(set(voices))
        classes = []
        dispatch = []
        for v in self.voices:
            dll = str((self.tool_dir / f'aqtk1/{v}/AquesTalk.dll').resolve()).replace('\\', '\\\\')
            cname = f'Aq1_{v}'
            classes.append(f'''
public class {cname} {{
    [DllImport("{dll}", EntryPoint="AquesTalk_Synthe", CallingConvention=CallingConvention.StdCall)]
    public static extern IntPtr Synthe(byte[] koe, int iSpeed, out int size);
    [DllImport("{dll}", EntryPoint="AquesTalk_FreeWave", CallingConvention=CallingConvention.StdCall)]
    public static extern void FreeWave(IntPtr wav);
}}''')
            dispatch.append(
                f'"{v}" {{ $ptr = [{cname}]::Synthe($koeBytes, $spd, [ref]$sz); '
                f'if ($ptr -ne [IntPtr]::Zero -and $sz -gt 0) {{ '
                f'$buf = New-Object byte[] $sz; '
                f'[System.Runtime.InteropServices.Marshal]::Copy($ptr, $buf, 0, $sz); '
                f'[{cname}]::FreeWave($ptr); '
                f'[Console]::Out.WriteLine("OK|" + [Convert]::ToBase64String($buf)) '
                f'}} else {{ [Console]::Out.WriteLine("ERR|SYNTH_NULL") }} }}'
            )

        ps_code = f'''$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
{"".join(classes)}
"@
$sjis = [System.Text.Encoding]::GetEncoding(932)
[Console]::Out.WriteLine("READY")
[Console]::Out.Flush()
while (($line = [Console]::In.ReadLine()) -ne $null) {{
    if ($line -eq "__EXIT__") {{ break }}
    $parts = $line.Split('|', 3)
    if ($parts.Length -ne 3) {{
        [Console]::Out.WriteLine("ERR|BAD_LINE")
        [Console]::Out.Flush()
        continue
    }}
    $voice = $parts[0]
    $spd = [int]$parts[1]
    $koe = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($parts[2]))
    $koeBytes = $sjis.GetBytes($koe + "`0")
    $sz = 0
    try {{
        switch ($voice) {{
            {" ".join(dispatch)}
            default {{ [Console]::Out.WriteLine("ERR|UNKNOWN_VOICE") }}
        }}
    }} catch {{
        [Console]::Out.WriteLine("ERR|" + $_.Exception.Message)
    }}
    [Console]::Out.Flush()
}}
'''
        self.ps_script = self.tool_dir / f'_aq1_worker_{threading.get_ident()}.ps1'
        self.ps_script.write_bytes(ps_code.encode('utf-8-sig'))
        self.proc = subprocess.Popen(
            [str(PS32), '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(self.ps_script)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW,
        )
        ready = self.proc.stdout.readline().strip()
        if ready != 'READY':
            err = self.proc.stderr.read()
            raise RuntimeError(f'Aq1DllWorker failed to start ({ready!r}): {err}')

    def synth(self, voice, speed, koe_text):
        payload = base64.b64encode(koe_text.encode('utf-8')).decode('ascii')
        self.proc.stdin.write(f'{voice}|{int(speed)}|{payload}\n')
        self.proc.stdin.flush()
        reply = self.proc.stdout.readline().strip()
        if not reply.startswith('OK|'):
            raise RuntimeError(f'AquesTalk_Synthe error for voice={voice} spd={speed} koe={koe_text!r}: {reply}')
        wav_bytes = base64.b64decode(reply[3:])
        return sf.read(io.BytesIO(wav_bytes), dtype='float64')

    def close(self):
        try:
            if self.proc.poll() is None:
                self.proc.stdin.write('__EXIT__\n')
                self.proc.stdin.flush()
                self.proc.wait(timeout=3)
        except Exception:
            self.proc.kill()
        finally:
            self.ps_script.unlink(missing_ok=True)


def phonetics(text):
    value = text.replace('　', ' ').replace('〜', 'ー').replace('～', 'ー').replace('—', 'ー')
    value = value.replace('゛', '').replace('゜', '')
    value = value.replace('観楼剣', 'かんろうけん').replace('一緒', 'いっしょ')
    value = value.replace('ぢ', 'じ').replace('づ', 'ず')
    value = re.sub(r'([ぁぃぅぇぉァィゥェォ])(\1+)', lambda m: m.group(1) + 'ー' * len(m.group(2)), value)
    value = re.sub(r'っー+', 'っ、', value)
    value = re.sub(r'…+', '、', value).replace('♪', '。').replace('！', '。').replace('？', '?')
    value = value.replace(' ', '/')
    value = re.sub(r'。+', '。', value)
    return value if value.endswith(('。', '?', '、')) else value + '。'


def voice_namespace(line):
    origin = line.get('origin') or 'native'
    return 'native' if origin == 'native' else 'native112' if origin == 'hakkei112' else 'original'


def voice_ogg_path(line):
    return VOICE_ASSETS / voice_namespace(line) / f'{line["id"]}.ogg'


def audition_src(ogg_rel_path):
    return os.path.relpath(ROOT / ogg_rel_path, OUT).replace('\\', '/')


def origin_label(line):
    origin = line.get('origin') or 'native'
    return '八景 U 原版' if origin == 'native' else '八景 ver1.12 迁入' if origin == 'hakkei112' else '项目原创（非原版引文）'


def card(line, ogg_rel_path):
    ident = line['id']
    src = html.escape(audition_src(ogg_rel_path))
    return (f'<article><strong>{ident}</strong><small>{origin_label(line)}</small>'
            f'<p>{html.escape(line["japanese"])}</p><p>{html.escape(line.get("chinese", ""))}</p>'
            f'<small>{", ".join(line.get("events", []))}</small>'
            f'<audio controls preload="none" src="{src}"></audio></article>')


def shared_card(shared_id, japanese, chinese, variants):
    options = ''.join(f'<option value="{html.escape(audition_src(ogg_path))}">{html.escape(cid[len(shared_id)+1:])}</option>'
                      for cid, ogg_path in variants)
    first_src = html.escape(audition_src(variants[0][1]))
    return (f'<article><strong>{html.escape(shared_id)}</strong><small>共享情境（按说话者种族与阶段各录一版）</small>'
            f'<p>{html.escape(japanese)}</p><p>{html.escape(chinese)}</p>'
            f'<select onchange="this.nextElementSibling.src=this.value">{options}</select>'
            f'<audio controls preload="none" src="{first_src}"></audio></article>')


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
                             'variant': row.get('variant', ''), 'origin': 'project-original'})
    for row in SPECIAL_SHARED:
        for species in SHARED_SPECIES:
            for age in TIERS:
                rows.append({'id': f'{row["id"]}.{species}.{age}', 'shared_id': row['id'],
                             'species': species, 'age': age, 'japanese': row['japanese'],
                             'chinese': row['chinese'], 'english': row['english'], 'events': row['events'],
                             'variant': '', 'origin': 'project-original'})
    return rows


def synth_clause_with_fallback(worker, voice, spd, koe):
    try:
        return worker.synth(voice, spd, koe)
    except RuntimeError:
        # Fallback 1: drop custom accent / low-pitch markers on edge-case moras
        relaxed = koe.replace("'", '').replace(';', '/')
        try:
            return worker.synth(voice, spd, relaxed)
        except RuntimeError:
            # Fallback 2: flatten phrase boundaries if an isolated cluster triggered parser reject
            plain = re.sub(r'[/;]+', '', relaxed).lstrip("っんーゃゅょぁぃぅぇぉ")
            if not plain or plain[0] in '。?':
                plain = 'ゆっ' + plain
            return worker.synth(voice, spd, plain)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--player', type=Path, required=True, help='Existing AquesTalkPlayer.exe')
    parser.add_argument('--work-directory', type=Path, default=ROOT / '.local/dialogue-species', help='Owned scratch and tool directory')
    parser.add_argument('--masters-directory', type=Path, default=ROOT / '.local/aquestalk-masters', help='Local-only WAV masters (never versioned)')
    parser.add_argument('--ids-json', type=Path, help='Only process IDs in a JSON string array or {"ids": [...]}; retain all other recordings')
    parser.add_argument('--workers', type=int, default=4, help='Parallel persistent 32-bit AquesTalk1 DLL workers')
    parser.add_argument('--keep-going', action='store_true', help='Record a failed line and continue with the rest')
    parser.add_argument('--checkpoint-every', type=int, default=200, help='Write the manifest every N processed lines (0 disables)')
    parser.add_argument('--failures-json', type=Path, default=ROOT / '.local/aquestalk-failures.json')
    parser.add_argument('--manifest', type=Path, default=OUT / 'manifest.json', help='Manifest to read and write')
    parser.add_argument('--no-index', action='store_true', help='Skip the audition page')
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
        requested = json.loads(args.ids_json.read_text(encoding='utf-8-sig'))
        if isinstance(requested, dict):
            requested = requested.get('ids')
        if not isinstance(requested, list) or not requested or any(not isinstance(ident, str) or not ident for ident in requested):
            parser.error('--ids-json must contain a nonempty array of ID strings, or {"ids": [...]}')
        unknown = [ident for ident in requested if ident not in by_id]
        if unknown:
            parser.error('Unknown dialogue IDs: ' + ', '.join(unknown))
        selected = [by_id[ident] for ident in requested]

    for line in selected:
        name = line['species'] + '.' + line['age']
        if name not in recipe['profiles']:
            parser.error(f'Unknown voice profile {name!r} for dialogue {line["id"]}')

    assert os.name == 'nt' and args.player.is_file()
    tool = args.work_directory.resolve() / 'tool/aquestalkplayer'
    if args.player.parent.resolve() != tool.resolve():
        if not tool.exists():
            shutil.copytree(args.player.parent, tool)
        else:
            assert digest(args.player) == digest(tool / 'AquesTalkPlayer.exe'), 'Local tool version differs'

    player = tool / 'AquesTalkPlayer.exe'
    preset = tool / 'AquesTalkPlayer.preset'
    masters = args.masters_directory.resolve()
    masters.mkdir(parents=True, exist_ok=True)
    VOICE_ASSETS.mkdir(parents=True, exist_ok=True)

    prior_path = args.manifest
    prior = json.loads(prior_path.read_text(encoding='utf-8')) if prior_path.exists() else {}
    clips = dict(prior.get('clips', {}))
    player_hash = digest(player)
    preset_hash = digest(preset)
    voices_used = sorted({p['voice'] for p in recipe['profiles'].values()})
    engine_hashes = {v: digest(tool / f'aqtk1/{v}/AquesTalk.dll') for v in voices_used}
    recipe_hash = digest(OUT / 'recipe.json')
    target_rate = recipe['output_sample_rate']

    failures = []
    processed = 0

    def manifest():
        result = {
            'engine': 'AquesTalk1', 'status': 'implemented-candidate-awaiting-listening-review',
            'prosody_version': PROSODY_VERSION,
            'player_sha256': player_hash, 'preset_sha256': preset_hash,
            'engine_sha256': engine_hashes, 'recipe_sha256': recipe_hash, 'clips': clips,
        }
        if args.ids_json is not None and prior:
            result = prior | {'prosody_version': PROSODY_VERSION, 'clips': clips}
        return result

    def save_manifest():
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(json.dumps(manifest(), ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')

    todo = []
    for line in selected:
        ident = line['id']
        profile = recipe['profiles'][line['species'] + '.' + line['age']]
        emotion = classify_emotion(line.get('events', []), line.get('variant', ''), line['japanese'])
        clauses = compile_emotion_clauses(line['japanese'], emotion)
        speech_repr = ' + '.join(f'#{c[0]}' for c in clauses)
        cached = prior.get('clips', {}).get(ident)
        reusable = (
            cached
            and cached.get('prosody_version') == PROSODY_VERSION
            and cached.get('emotion') == emotion
            and cached.get('japanese') == line['japanese']
            and cached.get('speech_input') == speech_repr
            and cached.get('profile') == profile
            and cached.get('source_sha256') == source_digest(line)
            and cached.get('engine_sha256', prior.get('engine_sha256', {}).get(profile['voice'])) == engine_hashes[profile['voice']]
            and (ROOT / cached['ogg']).is_file()
            and digest(ROOT / cached['ogg']) == cached['sha256']
        )
        if reusable:
            clips[ident] = cached
            clips[ident]['origin'] = line.get('origin') or 'native'
            processed += 1
        else:
            todo.append((line, profile, emotion, clauses, speech_repr))

    print(f'Selected {len(selected)} lines: {len(selected) - len(todo)} retained, {len(todo)} to synthesize (workers={args.workers}).', flush=True)

    thread_local = threading.local()
    workers_lock = threading.Lock()
    all_workers = []

    def get_worker():
        w = getattr(thread_local, 'worker', None)
        if w is None:
            w = Aq1DllWorker(tool, voices_used)
            thread_local.worker = w
            with workers_lock:
                all_workers.append(w)
        return w

    def process_one(item):
        line, profile, emotion, clauses, speech_repr = item
        ident = line['id']
        worker = get_worker()
        segs = []
        for koe, speed_d, pitch_d, peak_db, gap_ms, fade_ms in clauses:
            spd = clamp(profile['speed'] + speed_d, 58, 145)
            pit = clamp(profile['pitch'] + pitch_d, max(70, profile['pitch'] - 4), min(172, profile['pitch'] + 6))
            wave, raw_rate = synth_clause_with_fallback(worker, profile['voice'], spd, koe)
            eff_rate = raw_rate * (pit / 100.0)
            wave = upsample(wave, eff_rate, target_rate)
            wave = trim_silence(wave, target_rate, thresh_db=-45.0, pad_ms=24)
            wave = apply_target_peak(wave, peak_db)
            if fade_ms > 0:
                wave = apply_tail_fade(wave, target_rate, fade_ms)
            segs.append(wave)
            if gap_ms > 0:
                segs.append(np.zeros(int(target_rate * gap_ms / 1000)))

        full = np.clip(np.concatenate(segs), -0.99, 0.99)
        assert full.ndim == 1 and .05 < len(full) / target_rate < 20 and np.max(np.abs(full)) > .005, ident
        wav_path, ogg_path = masters / (ident + '.wav'), voice_ogg_path(line)
        ogg_path.parent.mkdir(parents=True, exist_ok=True)
        sf.write(wav_path, full, target_rate, subtype='PCM_16')
        sf.write(ogg_path, full, target_rate, format='OGG', subtype='VORBIS')
        decoded, check_rate = sf.read(ogg_path)
        assert check_rate == target_rate and decoded.ndim == 1 and abs(len(decoded) - len(full)) <= 2
        assert np.isfinite(decoded).all() and np.max(np.abs(decoded)) < 1, ident

        master_rel = wav_path.relative_to(ROOT).as_posix() if wav_path.is_relative_to(ROOT) else str(wav_path)
        entry = {
            'engine': 'AquesTalk1', 'prosody_version': PROSODY_VERSION, 'emotion': emotion,
            'japanese': line['japanese'], 'phonetics': ' + '.join(c[0] for c in clauses),
            'speech_input': speech_repr, 'input_mode': 'sharp',
            'origin': line.get('origin') or 'native', 'profile': profile,
            'source_sha256': source_digest(line), 'ogg': ogg_path.relative_to(ROOT).as_posix(),
            'sha256': digest(ogg_path), 'master': master_rel, 'master_sha256': digest(wav_path),
            'duration_seconds': len(decoded) / target_rate,
            'player_sha256': player_hash, 'preset_sha256': preset_hash,
            'engine_sha256': engine_hashes[profile['voice']], 'recipe_sha256': recipe_hash,
        }
        if line.get('shared_id'):
            entry['shared_id'] = line['shared_id']
            entry['species'] = line['species']
            entry['age'] = line['age']
        return ident, entry

    try:
        if todo:
            with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
                futures = {pool.submit(process_one, item): item for item in todo}
                for fut in as_completed(futures):
                    item = futures[fut]
                    line, _profile, emotion, _clauses, speech_repr = item
                    ident = line['id']
                    try:
                        _ident, entry = fut.result()
                        clips[ident] = entry
                    except Exception as error:
                        if not args.keep_going:
                            raise
                        clips.pop(ident, None)
                        failures.append({
                            'id': ident, 'japanese': line['japanese'], 'emotion': emotion,
                            'speech_input': speech_repr, 'input_mode': 'sharp',
                            'profile': line['species'] + '.' + line['age'],
                            'error': f'{type(error).__name__}: {error}',
                        })
                        print(f'{ident}: FAILED {error}', flush=True)
                    processed += 1
                    if processed % 250 == 0:
                        print(f'Progress: {processed}/{len(selected)} ({len(failures)} failures)', flush=True)
                    if args.checkpoint_every and processed % args.checkpoint_every == 0:
                        save_manifest()
    finally:
        for w in all_workers:
            w.close()

    if args.ids_json is None:
        keep = {line['id'] for line in all_lines}
        clips = {ident: clip for ident, clip in clips.items() if ident in keep}
    save_manifest()

    if failures:
        args.failures_json.parent.mkdir(parents=True, exist_ok=True)
        args.failures_json.write_text(json.dumps(failures, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    else:
        args.failures_json.unlink(missing_ok=True)

    if args.no_index:
        print(f'Processed {len(selected)} selected lines; manifest contains {len(clips)} clips; {len(failures)} failures.', flush=True)
        if failures:
            sys.exit(1)
        return

    normal = [line for line in all_lines if not line.get('shared_id')]
    normal_cards = [card(line, clips[line['id']]['ogg']) for line in normal if line['id'] in clips]
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
        shared_cards.append(shared_card(shared_id, row.get('ja') or row.get('japanese'),
                                        row.get('zh') or row.get('chinese'),
                                        [(line['id'], clips[line['id']]['ogg']) for line in lines]))
    cards = shared_cards + normal_cards
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>油库里对白 · AquesTalk 试听</title>
<style>body{font:16px/1.6 system-ui;background:#f4f0e6;color:#29271f;margin:32px auto;max-width:1100px;padding:0 20px}input,select{padding:12px;font:inherit}#clips{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:16px}article{padding:18px;background:#fffdf5;border:1px solid #d4cbbc;border-radius:8px}p{margin:8px 0}small{display:block;color:#71634d}audio{width:100%;margin-top:12px}select{width:100%;margin-top:12px}</style>
<h1>油库里对白 · AquesTalk 试听</h1><p>灵梦、魔理沙、爱丽丝、橙、帕秋莉、妖梦、蕾米莉亚与芙兰朵露；成体、幼语、子油、赤油分开制作。共享情境按说话者种族与阶段各录一版，用下拉框切换。声种、音程与中文译法为候选，尚待听审。</p>
<p><a href="../../dialogue/native-u/index.html">查原版完整对白</a> · <a href="../../../.local/audio-reference/diary/index.html">本机日记原声参考</a></p><p><input id="search" placeholder="搜索角色、adult / young / juvenile / baby、日文、中文或事件"></p><div id="clips">''' + ''.join(cards) + '''</div><script>
document.getElementById('search').addEventListener('input',e=>{for(const card of document.querySelectorAll('article'))card.hidden=!card.textContent.toLowerCase().includes(e.target.value.toLowerCase())});
document.addEventListener('play',e=>{if(e.target.tagName==='AUDIO')for(const a of document.querySelectorAll('audio'))if(a!==e.target)a.pause()},true);
</script></html>'''
    (OUT / 'index.html').write_text(page, encoding='utf-8', newline='\n')
    print(f'Processed {len(selected)} selected lines; manifest contains {len(clips)} clips; {len(failures)} failures. Run scripts/export_dialogue.py to install them.')
    if failures:
        print(f'Failures written to {args.failures_json}', flush=True)
        sys.exit(1)


if __name__ == '__main__':
    main()
