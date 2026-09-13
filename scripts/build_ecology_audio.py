"""Build two original, wordless yukkuri melodies with the installed AquesTalk1 tool.

Own tool copy and presets avoid interfering with native-dialogue production.
--check verifies masters and the packaged files without synthesis or game launch.
"""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess

import numpy as np
import soundfile as sf

from build_aquestalk_dialogue import upsample

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'art/audio/ecology'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--player', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check:
        manifest = json.loads((OUT / 'manifest.json').read_text(encoding='utf-8'))
        for record in manifest['clips'].values():
            for key in ('master', 'runtime'):
                assert digest(ROOT / record[key]) == record[key + '_sha256'], record[key]
            wave, rate = sf.read(ROOT / record['runtime'])
            assert wave.ndim == 1 and rate == 32000 and np.isfinite(wave).all() and np.max(np.abs(wave)) < 1
        print('Ecology audio: 2 original melodies, master/runtime fingerprints verified')
        return
    if not args.player or not args.player.is_file():
        parser.error('--player must point to the existing AquesTalkPlayer.exe')
    scratch = ROOT / '.local/ecology/audio'
    tool = scratch / 'tool'
    if not tool.exists():
        shutil.copytree(args.player.parent, tool)
    assert digest(args.player) == digest(tool / 'AquesTalkPlayer.exe')
    # Original composition. No lyrics or melody copied from a wiki, novel or game.
    notes = [0, 2, 4, 2, 0, -3, 0, 4, 7, 4, 2, 0]
    beats = [0.5] * 10 + [0.75, 1.25]
    recipe = {'status': 'candidate-awaiting-listening-review', 'engine': 'AquesTalk1',
              'syllable': 'ゆー', 'notes_semitones': notes, 'beats_seconds': beats,
              'duration_seconds': 8, 'rate': 32000,
              'profiles': {'reimu': 'f1', 'marisa': 'f2'},
              'attribution': 'Original MCyukkuri melody; behavior inspired by documented おうた. Not an original-game recording.'}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'recipe.json').write_text(json.dumps(recipe, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    preset = tool / 'AquesTalkPlayer.preset'
    rows = list(csv.reader(io.StringIO(preset.read_text(encoding='cp932'))))
    rows = [row for row in rows if not row[0].startswith('MCY-Ecology-')]
    for name, voice in recipe['profiles'].items():
        rows.append(['MCY-Ecology-'+name, 'true', 'AquesTalk1', voice, 100, 100, 100, 100, 100, 100, 'Original humming'])
    buffer = io.StringIO(newline=''); csv.writer(buffer, lineterminator='\r\n').writerows(rows)
    preset.write_bytes(buffer.getvalue().encode('cp932'))
    clips = {}
    for name in recipe['profiles']:
        text = scratch / (name+'.txt'); text.write_bytes('#>ゆー。\r\n'.encode('cp932'))
        raw = scratch / (name+'.wav')
        subprocess.run([str(tool/'AquesTalkPlayer.exe'), '/F', str(text), '/P', 'MCY-Ecology-'+name, '/W', str(raw)],
                       cwd=tool, check=True, timeout=20, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        sample, rate = sf.read(raw)
        voiced = np.flatnonzero(np.abs(sample) > .012)
        assert len(voiced) > 0
        sample = sample[max(0,voiced[0]-int(rate*.015)):min(len(sample),voiced[-1]+int(rate*.035))]
        sample = upsample(sample, rate, 32000)
        melody = np.zeros(8*32000); offset = 0
        for semitones, beat in zip(notes, beats):
            positions = np.arange(0,len(sample),2**(semitones/12))
            note = np.interp(positions,np.arange(len(sample)),sample)
            length = min(len(note),round((beat-.06)*32000)); note = note[:length].copy()
            fade = min(round(.025*32000),length//2)
            note[:fade] *= np.linspace(0,1,fade); note[-fade:] *= np.linspace(1,0,fade)
            melody[offset:offset+length] += note
            offset += round(beat*32000)
        melody *= .65/max(.01,np.max(np.abs(melody)))
        master = OUT/(name+'-song.wav'); runtime = ASSETS/('sounds/ecology/'+name+'_song.ogg')
        runtime.parent.mkdir(parents=True,exist_ok=True)
        sf.write(master,melody,32000,subtype='PCM_16'); sf.write(runtime,melody,32000,format='OGG',subtype='VORBIS')
        clips[name] = {'master': master.relative_to(ROOT).as_posix(), 'runtime': runtime.relative_to(ROOT).as_posix(),
                       'master_sha256': digest(master), 'runtime_sha256': digest(runtime), 'duration_seconds': 8}
    manifest = {'recipe_sha256':digest(OUT/'recipe.json'),'tool_sha256':digest(tool/'AquesTalkPlayer.exe'),
                'engine_sha256':{voice:digest(tool/f'aqtk1/{voice}/AquesTalk.dll') for voice in recipe['profiles'].values()},'clips':clips}
    (OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    # Merge at the final write so concurrent native-dialogue entries stay intact.
    sounds_path=ASSETS/'sounds.json'; sounds=json.loads(sounds_path.read_text(encoding='utf-8'))
    for name in recipe['profiles']:
        sounds['ecology.song.'+name]={'subtitle':'subtitles.mcyukkuri.ecology_song','sounds':[{'name':'mcyukkuri:ecology/'+name+'_song','attenuation_distance':12}]}
    sounds_path.write_text(json.dumps(sounds,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Original ecology songs exported; listening review pending')


if __name__ == '__main__':
    main()
