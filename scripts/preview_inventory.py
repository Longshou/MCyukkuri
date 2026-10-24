"""Read the repository's registration forms without loading or compiling Java.

This is deliberately not a Java evaluator. Unrecognised dynamic registrations
stay out of the preview and appear in the resource audit, rather than treating
every leftover JSON/localisation key as a current item.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'src/main/java/io/github/longshou/mcyukkuri'


def java(path):
    return re.sub(r'//[^\n]*|/\*[\s\S]*?\*/', '', path.read_text(encoding='utf-8'))


def enum_ids(path, name):
    text = java(JAVA/path)
    body = re.search(r'\benum\s+'+name+r'\s*\{([^;]+);', text)
    if not body:
        raise ValueError(f'Cannot read preview registration enum: {path}: {name}')
    return dict(re.findall(r'\b([A-Z][A-Z_0-9]*)\s*\(\s*"([a-z0-9_]+)"', body[1]))


def current_items():
    result = {}

    def add(ident, path):
        result[ident] = (JAVA/path).relative_to(ROOT).as_posix()

    for path in JAVA.rglob('*.java'):
        text = java(path)
        for register in re.findall(r'DeferredRegister\.Items\s+(\w+)\s*=', text):
            for ident in re.findall(r'\b'+register+r'\.register(?:SimpleItem|SimpleBlockItem)?\s*\(\s*"([a-z0-9_]+)"\s*,', text):
                add(ident, path.relative_to(JAVA))

    # These helpers register both a block and its item; unrelated block-only
    # registrations must not become player items.
    for path, helpers in [('human/HumanWorld.java', 'station|simple'),
                          ('life/Facilities.java', 'block')]:
        for ident in re.findall(r'=\s*(?:'+helpers+r')\(\s*"([a-z0-9_]+)"', java(JAVA/path)):
            add(ident, path)
    facilities = java(JAVA/'life/Facilities.java')
    for group in re.findall(r'for\s*\(String name:List\.of\(([^)]+)\)\)\s*ITEMS\.put', facilities):
        for ident in re.findall(r'"([a-z0-9_]+)"', group):
            add(ident, 'life/Facilities.java')
    mod_items = java(JAVA/'registry/ModItems.java')
    accessories = enum_ids('entity/YukkuriVariant.java', 'Accessory')
    for name in re.findall(r'=\s*registerAccessory\(YukkuriVariant\.Accessory\.([A-Z_]+)\)', mod_items):
        add(accessories[name], 'registry/ModItems.java')
    for owner, token, source, enum in [
        ('registry/ModItems.java', 'OperationToolItem.Tool.values()', 'item/OperationToolItem.java', 'Tool'),
        ('life/Consumables.java', 'Kind.values()', 'life/Consumables.java', 'Kind'),
        ('play/Playgrounds.java', 'PlayBlock.Kind.values()', 'play/PlayBlock.java', 'Kind'),
    ]:
        if token in java(JAVA/owner):
            for ident in enum_ids(source, enum).values():
                add(ident, owner)
    return result


def referenced_actions():
    """Literal runtime references, excluding the manual museum action override.

    A reference proves only that a resource is named, not that a Goal succeeds.
    Dynamic names which cannot be resolved here are reported as unconfirmed.
    """
    refs = {}
    for path in JAVA.rglob('*.java'):
        if 'museum' in path.parts:
            continue
        for ident in re.findall(r'"([a-z][a-z0-9_]*)"', java(path)):
            refs.setdefault(ident, []).append(path.relative_to(ROOT).as_posix())
    return refs
