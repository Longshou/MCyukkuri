"""Embed the current recorded dialogue and its actual Chinese subtitles for hat playback.

This only generates preview data. It does not synthesize speech, alter game resources,
launch a browser or run a check. The playback page must wait for the user to enable sound.
"""
from pathlib import Path
import argparse
import base64
import json
import re


ROOT = Path(__file__).resolve().parents[5]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
OUT = ROOT / '.local/previews/hat-motion'
SPEAKERS = {
    'water_help': ('young', 'child'),
    'braid_rescue_offer': ('adult', 'parent'),
    'braid_rescue_grip': ('young', 'child'),
    'braid_rescue_safe': ('adult', 'parent'),
    'self_rescue_hat': ('young', 'child'),
    'self_rescue_grip': ('young', 'child'),
    'self_rescue_climb': ('young', 'child'),
    'self_rescue_slip': ('young', 'child'),
    'self_rescue_safe': ('young', 'child'),
}


def recorded_audio(sound, sounds):
    """Return an existing file explicitly named by the catalogue's sound event."""
    for entry in sounds.get(sound, {}).get('sounds', []):
        if isinstance(entry, dict):
            if entry.get('type', 'file') != 'file':
                continue
            name = entry['name']
        else:
            name = entry
        namespace, separator, relative = name.partition(':')
        if not separator:
            namespace, relative = 'minecraft', name
        if namespace != 'mcyukkuri':
            continue
        path = ASSETS / 'sounds' / (relative + '.ogg')
        if path.is_file():
            return path, name
    return None


def build(out=OUT):
    source = (ROOT / 'src/main/java/io/github/longshou/mcyukkuri/dialogue/NativeDialogue.java').read_text(encoding='utf-8')
    aliases = dict(re.findall(r'Map\.entry\("([^"\n]+)",\s*"([^"\n]+)"\)', source))
    catalog = json.loads((ASSETS / 'dialogue/catalog.json').read_text(encoding='utf-8'))
    subtitles = json.loads((ASSETS / 'lang/zh_cn.json').read_text(encoding='utf-8'))
    sounds = json.loads((ASSETS / 'sounds.json').read_text(encoding='utf-8'))
    audio = {}
    for event, (age, role) in SPEAKERS.items():
        # Mirror NativeDialogue: direct event first, otherwise its existing recorded alias.
        choices = [line for line in catalog if line['species'] == 'marisa' and line['age'] == age and event in line['events']]
        if not choices and event in aliases:
            choices = [line for line in catalog if line['species'] == 'marisa' and line['age'] == age and aliases[event] in line['events']]
        for line in choices:
            recording = recorded_audio(line['sound'], sounds)
            text = subtitles.get(line['key'])
            if recording is None or not text:
                continue
            path, name = recording
            audio[event] = {
                'src': 'data:audio/ogg;base64,' + base64.b64encode(path.read_bytes()).decode('ascii'),
                'text': text,
                'key': line['key'],
                'sound': line['sound'],
                'recording': name,
                'age': age,
                'role': role,
                'ticks': line['ticks'],
            }
            break
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    target = out / 'playback-audio.js'
    target.write_text('window.HAT_AUDIO=' + json.dumps(audio, ensure_ascii=False, separators=(',', ':')) + ';\n', encoding='utf-8')
    print(f'Wrote {len(audio)} existing recorded events to {target}')
    return target


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=OUT)
    build(parser.parse_args().out)
