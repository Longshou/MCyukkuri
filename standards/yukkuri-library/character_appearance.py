"""Apply registered character-local atlas revisions after composing shared face parts."""
from functools import lru_cache
from pathlib import Path
import json
import runpy

LIB = Path(__file__).resolve().parent


@lru_cache(None)
def author(character):
    registration = LIB / 'characters' / (character + '.json')
    if not registration.exists():
        return None
    source = json.loads(registration.read_text(encoding='utf-8')).get('texture_style')
    return runpy.run_path(str(LIB / source))['repaint'] if source else None


def apply_style(character, atlas):
    repaint = author(character)
    return repaint(atlas) if repaint else atlas
