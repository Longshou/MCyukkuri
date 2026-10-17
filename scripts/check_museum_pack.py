"""Offline cross-check of the museum data pack. No game, no compilation.

Generates the pack into a scratch directory below .local/experimental-map and then verifies, without running anything:

  * every `yukkuri ...` command in any function (and in every command block) is a path of the Brigadier tree that the
    Java sources register (literals and argument counts are parsed from the Java command classes);
  * every `function mcylab:x`, `schedule function` and `schedule clear` target exists in the pack;
  * every function stays below the command chain limit and starts with a known command;
  * every station has a `go/s/<id>` function that teleports, a lobby entry, a way back (previous/next/lobby buttons) and,
    for every remote site, a deck with back-to-station and back-to-lobby buttons;
  * every board and label fits (CJK-aware width and line counts).

    python scripts/check_museum_pack.py            # exit status 1 on any finding
"""
from pathlib import Path
import json
import re
import shutil
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import build_museum_full as museum  # noqa: E402
import museum_sites  # noqa: E402

JAVA = ROOT / 'src/main/java'
KNOWN_HEADS = {'kill', 'tp', 'tellraw', 'title', 'give', 'summon', 'fill', 'setblock', 'data', 'execute', 'function', 'schedule', 'scoreboard',
               'forceload', 'gamerule', 'difficulty', 'time', 'weather', 'setworldspawn', 'tag', 'gamemode', 'say', 'fillbiome', 'yukkuri',
               'return', 'particle'}


# ---------------------------------------------------------------------------- Brigadier tree from the Java sources
def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'(?<![:"])//[^\n]*', '', text)


def balanced(text, start):
    """Index just past the parenthesis group that opens at ``start`` (text[start] == '(')."""
    depth, i, n = 0, start, len(text)
    while i < n:
        ch = text[i]
        if ch == '"':
            i += 1
            while text[i] != '"':
                i += 2 if text[i] == '\\' else 1
        elif ch == "'":
            i += 1
            while text[i] != "'":
                i += 2 if text[i] == '\\' else 1
        elif ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError('unbalanced parentheses')


class Node:
    def __init__(self, kind, name, arg_type=''):
        self.kind, self.name, self.arg_type = kind, name, arg_type
        self.children, self.executes, self.refs = [], False, []

    def width(self):
        """How many command tokens an argument consumes."""
        t = self.arg_type
        if 'blockPos' in t or 'vec3' in t or 'Vec3' in t:
            return 3
        if 'greedy' in t:
            return 10 ** 6
        return 1


IDENT = re.compile(r'[A-Za-z_][\w.]*')


def parse_chain(text, pos=0):
    """Parse `Base(...).then(...).executes(...)...` starting at pos. Returns (node, end)."""
    while text[pos].isspace():
        pos += 1
    m = re.compile(r'Commands\s*\.\s*literal\s*\(\s*"([^"]+)"\s*\)').match(text, pos)
    if m:
        node, pos = Node('literal', m.group(1)), m.end()
    else:
        m = re.compile(r'Commands\s*\.\s*argument\s*\(').match(text, pos)
        if m:
            end = balanced(text, m.end() - 1)
            inner = text[m.end():end - 1]
            name = re.match(r'\s*"([^"]+)"', inner).group(1)
            node, pos = Node('argument', name, inner[inner.index(',') + 1:]), end
        else:
            m = IDENT.match(text, pos)
            call = m.group(0)
            pos = m.end()
            while text[pos].isspace():
                pos += 1
            assert text[pos] == '(', text[pos - 30:pos + 30]
            pos = balanced(text, pos)
            node = Node('ref', call)
    while True:
        k = pos
        while k < len(text) and text[k].isspace():
            k += 1
        if k >= len(text) or text[k] != '.':
            return node, pos
        k += 1
        while text[k].isspace():
            k += 1
        m = IDENT.match(text, k)
        method = m.group(0)
        k = m.end()
        while text[k].isspace():
            k += 1
        if text[k] != '(':
            return node, pos
        end = balanced(text, k)
        if method == 'then':
            child, _ = parse_chain(text[k + 1:end - 1])
            node.children.append(child)
        elif method == 'executes':
            node.executes = True
        pos = end


def load_tree():
    methods = {}
    root = None
    for path in JAVA.rglob('*.java'):
        text = strip_comments(path.read_text(encoding='utf-8'))
        cls = path.stem
        for m in re.finditer(r'LiteralArgumentBuilder<CommandSourceStack>\s+(\w+)\s*\(\s*\)\s*\{', text):
            start = m.end()
            r = text.index('return', start) + len('return')
            node, _ = parse_chain(text[r:])
            methods[(cls, m.group(1))] = node
        if path.name == 'YukkuriCommands.java':
            i = text.index('register(Commands.literal("yukkuri")')
            root, _ = parse_chain(text[i + len('register('):])
            root_cls = cls

    def resolve(node, cls):
        for i, child in enumerate(node.children):
            if child.kind == 'ref':
                parts = child.name.split('.')
                key = (parts[-2], parts[-1]) if len(parts) >= 2 else (cls, parts[-1])
                if key not in methods:
                    key = (cls, parts[-1])
                node.children[i] = methods[key]
                child = methods[key]
            resolve(child, cls)
    for (cls, _), node in methods.items():
        resolve(node, cls)
    resolve(root, root_cls)
    return root


def split_words(command):
    words, depth, current, quote = [], 0, '', False
    for ch in command:
        if ch == '"':
            quote = not quote
        if not quote and ch in '[{':
            depth += 1
        if not quote and ch in ']}':
            depth -= 1
        if ch == ' ' and depth == 0 and not quote:
            if current:
                words.append(current)
            current = ''
        else:
            current += ch
    if current:
        words.append(current)
    return words


def matches(node, words, i=0):
    """True when words[i:] walk some path below ``node`` to a node with executes."""
    if i == len(words):
        return node.executes
    for child in node.children:
        if child.kind == 'literal':
            if words[i] == child.name and matches(child, words, i + 1):
                return True
        else:
            w = child.width()
            j = min(len(words), i + w)
            if j > i and matches(child, words, j):
                return True
    return False


def yukkuri_commands(line):
    """`yukkuri ...` commands in a function line, including the ones inside command blocks."""
    out = []
    for m in re.finditer(r'(?<![\w:"/.#])yukkuri (?=[a-z_])[^\n]*', line):
        text = m.group(0)
        # a command block's Command:'...' ends at the closing quote
        if "',TrackOutput" in text:
            text = text[:text.index("',TrackOutput")]
        out.append(text.replace("\\'", "'"))
    return out


def command_block_commands(line):
    out = []
    for m in re.finditer(r"Command:'((?:[^'\\]|\\.)*)',TrackOutput", line):
        out.append(m.group(1).replace("\\'", "'").replace('\\\\', '\\'))
    return out


# ---------------------------------------------------------------------------- block simulation
BLOCK_LINE = re.compile(r'(setblock|fill) ((?:-?\d+ ){2}-?\d+(?: -?\d+ -?\d+ -?\d+)?) (\S+)(?: (outline|hollow|keep|replace|destroy))?$')
PASSABLE = ('minecraft:air', 'minecraft:short_grass', 'minecraft:fern', 'minecraft:stone_button', 'minecraft:oak_pressure_plate')


def simulate_blocks(pack, order):
    """Final block at every position after the build functions run in order (only absolute fill/setblock are read).

    A line `function mcylab:x` is expanded in place, like the real build does for stand-local helper functions."""
    blocks = {}

    def run(name, depth=0):
        assert depth < 6, name
        for line in pack[name].read_text(encoding='utf-8').splitlines():
            if line.startswith('function mcylab:') and line[len('function mcylab:'):] in pack:
                run(line[len('function mcylab:'):], depth + 1)
                continue
            m = BLOCK_LINE.match(line)
            if not m:
                # setblock lines carry block states / NBT after the block id: split them off
                m2 = re.match(r'setblock (-?\d+) (-?\d+) (-?\d+) ([a-z_:]+)', line)
                if m2:
                    x, y, z = (int(v) for v in m2.groups()[:3])
                    blocks[x, y, z] = line.split(' ', 4)[4]
                continue
            cmd, coords, block, mode = m.groups()
            values = [int(v) for v in coords.split()]
            if cmd == 'setblock':
                blocks[tuple(values)] = block
                continue
            x1, y1, z1, x2, y2, z2 = values
            for y in range(y1, y2 + 1):
                for z in range(z1, z2 + 1):
                    for x in range(x1, x2 + 1):
                        edge = x in (x1, x2) or y in (y1, y2) or z in (z1, z2)
                        if mode == 'outline' and not edge:
                            continue
                        if mode == 'keep' and (x, y, z) in blocks and blocks[x, y, z] != 'minecraft:air':
                            continue
                        blocks[x, y, z] = block
    for name in order:
        run(name)
    return blocks


def block_id(value):
    return re.split(r'[\[{]', value or 'minecraft:air', maxsplit=1)[0]


SOFT_IDS = {'minecraft:air', 'minecraft:short_grass', 'minecraft:tall_grass', 'minecraft:fern', 'minecraft:snow', 'minecraft:water',
            'minecraft:cave_air', 'minecraft:torch', 'minecraft:lantern'}
SOFT_SUFFIX = ('_slab', '_carpet', '_sign', '_button', '_pressure_plate')


def solid(blocks, pos):
    """Vanilla full blocks only; the mod's own props (nest, bowl, podium, burrow) are partial blocks."""
    bid = block_id(blocks.get(pos))
    return bid.startswith('minecraft:') and bid not in SOFT_IDS and not bid.endswith(SOFT_SUFFIX)


def check_world(blocks, halls, findings):
    """Buttons survive later fills; actors and visitors are never placed inside solid blocks; landings have a floor."""
    for x, y, z in museum_sites.CONTROLS:
        if block_id(blocks.get((x, y, z))) != 'minecraft:command_block':
            findings.append(f'control at {x} {y} {z}: command block overwritten or missing ({blocks.get((x, y, z))})')
        if block_id(blocks.get((x, y + 2, z))) != 'minecraft:stone_button':
            findings.append(f'control at {x} {y} {z}: button overwritten or missing ({blocks.get((x, y + 2, z))})')
        if not solid(blocks, (x, y + 1, z)):
            findings.append(f'control at {x} {y} {z}: the block under the button is not a full block ({blocks.get((x, y + 1, z))})')
        if solid(blocks, (x, y + 3, z)):
            findings.append(f'control at {x} {y} {z}: something stands on the button ({blocks.get((x, y + 3, z))})')
    for hall in halls:
        for ctx in hall.contexts:
            for key, spec in ctx.actors.items():
                cx, cy, cz = int(spec['x'] // 1), int(spec['y'] // 1), int(spec['z'] // 1)
                for dy in (0, 1):
                    if solid(blocks, (cx, cy + dy, cz)):
                        findings.append(f'actor {ctx.sid}.{key} at {spec["x"]} {spec["y"]} {spec["z"]}: inside {blocks.get((cx, cy + dy, cz))}')
                        break
                if not solid(blocks, (cx, int(spec['y'] // 1) - 1, cz)) and not ctx.water:
                    findings.append(f'actor {ctx.sid}.{key}: nothing to stand on at {cx} {int(spec["y"] // 1) - 1} {cz}')
            spots = [('landing', ctx.land)] + [(f'site {s["id"]}', s['land']) for s in ctx.sites]
            for label, (x, y, z, yaw) in spots:
                cx, cy, cz = int(x // 1), int(y // 1), int(z // 1)
                if solid(blocks, (cx, cy, cz)) or solid(blocks, (cx, cy + 1, cz)):
                    findings.append(f'{ctx.sid} {label} at {x} {y} {z}: not free ({blocks.get((cx, cy, cz))} / {blocks.get((cx, cy + 1, cz))})')
                if not solid(blocks, (cx, cy - 1, cz)):
                    findings.append(f'{ctx.sid} {label} at {x} {y} {z}: no floor ({blocks.get((cx, cy - 1, cz))})')


# ---------------------------------------------------------------------------- checks
def main():
    findings = []
    scratch = ROOT / '.local/experimental-map/_check'
    if scratch.exists():
        shutil.rmtree(scratch)
    scratch.mkdir(parents=True)
    try:
        halls = museum.build_layout()
        shown, _ = museum.validate(halls)
        world = scratch / 'saves' / museum.WORLD
        world.mkdir(parents=True)
        checks = museum.build_pack(world, halls, shown)
        for violation in museum_sites.VIOLATIONS:
            findings.append(f'text does not fit: {violation}')
        functions = world / 'datapacks/mcylab/data/mcylab/function'
        pack = {p.relative_to(functions).with_suffix('').as_posix(): p for p in functions.rglob('*.mcfunction')}
        tree = load_tree()
        stations = [c for h in halls for c in h.contexts]

        yukkuri_seen = set()
        for name, path in sorted(pack.items()):
            lines = path.read_text(encoding='utf-8').splitlines()
            if len(lines) > 20000:
                findings.append(f'{name}: {len(lines)} commands in one function')
            for number, line in enumerate(lines, 1):
                where = f'{name}:{number}'
                candidates = [line] + command_block_commands(line)
                for text in candidates:
                    head = text.split(' ', 1)[0]
                    if text and not text.startswith('#') and head not in KNOWN_HEADS:
                        findings.append(f'{where}: unknown command head {head!r}')
                    # text of boards and messages may contain the word; only real command lines are matched
                    for cmd in (yukkuri_commands(text) if head not in ('summon', 'tellraw', 'title', 'setblock', 'fill', 'give') else []):
                        words = split_words(cmd)
                        yukkuri_seen.add(' '.join(words[1:3]) if words[1:2] == ['museum'] else words[1])
                        if not matches_root(tree, words):
                            findings.append(f'{where}: command not in the Java command tree: {cmd[:120]}')
                    for ref in re.findall(r'(?:function|schedule clear) mcylab:([\w/]+)', text):
                        if ref not in pack:
                            findings.append(f'{where}: function mcylab:{ref} does not exist')
                    for ref in re.findall(r'schedule function mcylab:([\w/]+)', text):
                        if ref not in pack:
                            findings.append(f'{where}: scheduled function mcylab:{ref} does not exist')

        for ctx in stations:
            go = f'go/s/{ctx.sid}'
            if go not in pack:
                findings.append(f'station {ctx.sid}: no teleport function {go}')
            else:
                body = pack[go].read_text(encoding='utf-8')
                if 'tp @s' not in body:
                    findings.append(f'station {ctx.sid}: {go} does not teleport')
        order = ['build/spine'] + [f'build/hall_{h.id.lower()}' for h in halls] + ['build/biomes']
        check_world(simulate_blocks(pack, order), halls, findings)
        hall_text = {h.id: (functions / f'build/hall_{h.id.lower()}.mcfunction').read_text(encoding='utf-8') for h in halls}
        spine_text = (functions / 'build/spine.mcfunction').read_text(encoding='utf-8')
        for ctx in stations:
            text = hall_text[ctx.hall.id]
            if f'function mcylab:go/lobby' not in text.split(f'{ctx.number:02d} {ctx.title}')[0] and 'go/lobby' not in text:
                findings.append(f'station {ctx.sid}: no lobby button')
            if f'go/s/{ctx.sid}' not in spine_text:
                findings.append(f'station {ctx.sid}: not listed on the lobby station wall')
            if ctx.prev is None and ctx.next is None:
                findings.append(f'station {ctx.sid}: no neighbours')
            # remote sites need a deck that leads home: back to the station and to the lobby, once per site
            if ctx.sites:
                joined = '\n'.join(ctx.props)
                n_back = joined.count(f'function mcylab:go/s/{ctx.sid}')
                n_lobby = joined.count('function mcylab:go/lobby')
                for site in ctx.sites:
                    if site['visit'] not in ctx.functions:
                        findings.append(f'site {site["id"]}: visit function missing')
                    if not site.get('land'):
                        findings.append(f'site {site["id"]}: no landing')
                if n_back < len(ctx.sites) or n_lobby < len(ctx.sites):
                    findings.append(f'station {ctx.sid}: {len(ctx.sites)} sites but only {n_back} back / {n_lobby} lobby buttons on decks')
        for obj in ('museum', 'mus_at'):
            if f'scoreboard objectives add {obj} dummy' not in (functions / 'load.mcfunction').read_text(encoding='utf-8'):
                findings.append(f'scoreboard objective {obj} is never created')
        print(f'functions {len(pack)}, stations {len(stations)}, controls {checks["controls"]}, texts {checks["texts"]}, '
              f'distinct yukkuri command shapes {len(yukkuri_seen)}')
        for shape in sorted(yukkuri_seen):
            print('  yukkuri', shape)
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    if findings:
        print(f'{len(findings)} findings:')
        for finding in findings[:80]:
            print('  -', finding)
        raise SystemExit(1)
    print('museum pack check: OK')


def matches_root(tree, words):
    """`yukkuri` is the root literal; the words begin with it."""
    return words and words[0] == tree.name and matches(tree, words[1:], 0)


if __name__ == '__main__':
    main()
