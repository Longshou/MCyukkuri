"""Offline cross-check of the playground data pack. No game, no compilation.

Generates the pack into a scratch directory below .local/experimental-map and then verifies, without running anything:

  * every `yukkuri ...` command is a path of the Java Brigadier tree, including commands wrapped by relay;
  * function and schedule targets exist, with smoke-only schedule clears allowed;
  * every scene has a direct jump, start, body and in-field vantage; all menu utilities have targets;
  * the lobby has a supported spawn and the generated map has no navigation blocks or displays;
  * scenes never fix, freeze or protect their individuals; retired commands and items cannot appear;
  * field bounds and anchors, the pond bank, den doorways and the footprints of authored buildings are checked.

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

JAVA = ROOT / 'src/main/java'
KNOWN_HEADS = {'kill', 'tp', 'tellraw', 'title', 'give', 'item', 'summon', 'fill', 'clone', 'setblock', 'data', 'execute', 'function', 'schedule', 'scoreboard',
               'forceload', 'gamerule', 'difficulty', 'time', 'weather', 'setworldspawn', 'tag', 'gamemode', 'say', 'fillbiome', 'yukkuri',
               'return', 'particle', 'tick', 'kill', 'damage'}


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
        for m in re.finditer(r'LiteralArgumentBuilder<(?:[\w.]+\.)?CommandSourceStack>\s+(\w+)\s*\(\s*\)\s*\{', text):
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
    """Mod commands in a function line, including those nested after execute."""
    return [match.group(0) for match in re.finditer(r'(?<![\w:"/.#])yukkuri (?=[a-z_])[^\n]*', line)]


def matches_root(tree, words):
    return words and words[0] == tree.name and matches(tree, words[1:])


# ---------------------------------------------------------------------------- syntax lint
def balanced_brackets(text):
    """Brackets and braces balance outside quotes (SNBT strings use single or double quotes)."""
    stack, quote, i = [], None, 0
    pairs = {')': '(', ']': '[', '}': '{'}
    while i < len(text):
        ch = text[i]
        if quote:
            if ch == '\\':
                i += 1
            elif ch == quote:
                quote = None
        elif ch in '"\'':
            quote = ch
        elif ch in '([{':
            stack.append(ch)
        elif ch in pairs:
            if not stack or stack.pop() != pairs[ch]:
                return False
        i += 1
    return not stack and quote is None


def json_after(prefix_pattern, line):
    m = re.match(prefix_pattern, line)
    return line[m.end():] if m else None


def lint_line(line, where, findings):
    """Cheap syntax checks that a game restart would otherwise reveal: balanced NBT, valid tellraw / title JSON."""
    if not line or line.startswith('#'):
        return
    if not balanced_brackets(line):
        findings.append(f'{where}: unbalanced brackets or quotes: {line[:100]}')
    for pattern in (r'.*?\brun tellraw \S+ ', r'tellraw \S+ ', r'.*?\brun title \S+ actionbar ', r'title \S+ actionbar '):
        rest = json_after(pattern, line)
        if rest is not None:
            try:
                json.loads(rest)
            except ValueError as error:
                findings.append(f'{where}: invalid JSON text ({error}): {rest[:80]}')
            break
    for m in re.finditer(r"text:'((?:[^'\\]|\\.)*)'", line):
        raw = m.group(1).replace("\\'", "'").replace('\\\\', '\\')
        try:
            json.loads(raw)
        except ValueError as error:
            findings.append(f'{where}: invalid text component in NBT ({error}): {raw[:80]}')


# ---------------------------------------------------------------------------- block layers
BLOCK_LINE = re.compile(r'(setblock|fill) ((?:-?\d+ ){2}-?\d+(?: -?\d+ -?\d+ -?\d+)?) (\S+)(?: (outline|hollow|keep|replace|destroy))?$')


def block_layers(pack, order):
    """Absolute setblock/fill operations in build order (``function mcylab:x`` lines are expanded in place)."""
    ops = []

    def run(name, depth=0):
        assert depth < 6, name
        for line in pack[name].read_text(encoding='utf-8').splitlines():
            if line.startswith('function mcylab:') and line[len('function mcylab:'):] in pack:
                run(line[len('function mcylab:'):], depth + 1)
                continue
            m = BLOCK_LINE.match(line)
            if m:
                cmd, coords, block, mode = m.groups()
                values = [int(v) for v in coords.split()]
                ops.append((cmd, values, block, mode))
                continue
            m2 = re.match(r'setblock (-?\d+) (-?\d+) (-?\d+) (\S+)', line)
            if m2:
                ops.append(('setblock', [int(v) for v in m2.groups()[:3]], m2.group(4), None))
    for name in order:
        run(name)
    return ops


def block_at(ops, pos):
    state = 'minecraft:air'
    x, y, z = pos
    for cmd, values, block, mode in ops:
        if cmd == 'setblock':
            if tuple(values) == pos:
                state = block
            continue
        x1, y1, z1, x2, y2, z2 = values
        if not (min(x1, x2) <= x <= max(x1, x2) and min(y1, y2) <= y <= max(y1, y2) and min(z1, z2) <= z <= max(z1, z2)):
            continue
        edge = x in (x1, x2) or y in (y1, y2) or z in (z1, z2)
        if mode == 'outline' and not edge:
            continue
        if mode == 'keep' and block_id(state) != 'minecraft:air':
            continue
        state = block
    return state


def block_id(value):
    return re.split(r'[\[{]', value or 'minecraft:air', maxsplit=1)[0]


# ---------------------------------------------------------------------------- checks
def check_geometry(findings):
    """Check field bounds, anchors, the pond shoreline and the cave mouths."""
    fields = museum.FIELDS
    for i, a in enumerate(fields):
        ax1, az1, ax2, az2 = a.rect()
        for b in fields[i + 1:]:
            bx1, bz1, bx2, bz2 = b.rect()
            if ax1 <= bx2 and bx1 <= ax2 and az1 <= bz2 and bz1 <= az2:
                findings.append(f'fields {a.id} and {b.id} overlap')
        for s in a.scenarios:
            x, z = s.anchor
            if not (a.x1 <= x <= a.x2 and a.z1 <= z <= a.z2):
                findings.append(f'scenario {s.id}: anchor {x},{z} outside field {a.id}')
    pond = museum.BY_ID['pond']
    bx, by, bz = pond.bank
    wx, wy, wz = pond.water
    if (bx + 1, bz) != (wx, wz) or by != wy + 1 or pond.depth(wx, wz) < 1 or pond.depth(bx, bz) != 0:
        findings.append(f'pond: bank {pond.bank} and water {pond.water} do not match')
    if pond.anchor != (bx, bz):
        findings.append('pond anchor is not the bank cell')
    # open water for the boat: a 9x5 patch of calm source cells east of the bank
    for dx in range(1, 10):
        for dz in range(-2, 3):
            if pond.depth(wx + dx - 1, wz + dz) < 1:
                findings.append(f'pond: no open water at {wx + dx - 1},{wz + dz}')
    dens = museum.BY_ID['den']
    solid = set()
    for c in dens.cave_cmds:
        w = c.split()
        if w[0] == 'fill':
            x1, y1, z1, x2, y2, z2 = (int(v) for v in w[1:7])
            kind = w[7]
            for x in range(min(x1, x2), max(x1, x2) + 1):
                for y in range(min(y1, y2), max(y1, y2) + 1):
                    for z in range(min(z1, z2), max(z1, z2) + 1):
                        if kind == 'minecraft:air':
                            solid.discard((x, y, z))
                        else:
                            solid.add((x, y, z))
    for name, (x, y, z) in dens.doorways.items():
        notch = (x, y, z + 1)
        for dy in range(4):
            if (notch[0], notch[1] + dy, notch[2]) in solid:
                findings.append(f'den {name}: notch column is not open at y+{dy}')
        if (x, y, z) in solid or (x, y + 1, z) in solid:
            findings.append(f'den {name}: doorway cell is not free')
        if (x, y + 2, z) not in solid or (x, y + 3, z) not in solid:
            findings.append(f'den {name}: the doorway has no two-block roof')
        if not ((x - 1, y, z + 1) in solid and (x + 1, y, z + 1) in solid):
            findings.append(f'den {name}: the notch is not flanked by earth')
    # the aligned 16x16x8 activity cell of the house: interior and anchor inside one cell
    house = museum.BY_ID['house']
    ix1, ix2 = house.cx - house.HX + 1, house.cx + house.HX - 1
    iz1, iz2 = house.cz - house.HZ + 1, house.cz + house.HZ - 1
    if ix1 // 16 != ix2 // 16 or iz1 // 16 != iz2 // 16:
        findings.append('house: the interior straddles two 16x16 activity cells')
    ax, az = house.anchor
    if not (ix1 <= ax <= ix2 and iz1 <= az <= iz2):
        findings.append('house: the anchor is outside the interior')
    for gid, _ in museum.GROUPS:
        if not [f for f in fields if f.group == gid]:
            findings.append(f'group {gid} has no field')
    for f in fields:
        if f.group not in dict(museum.GROUPS):
            findings.append(f'field {f.id}: unknown group {f.group}')


def main():
    findings = []
    scratch = ROOT / '.local/experimental-map/_check'
    if scratch.exists():
        shutil.rmtree(scratch)
    scratch.mkdir(parents=True)
    try:
        museum.validate()
        world = scratch / 'saves' / museum.WORLD
        world.mkdir(parents=True)
        _, texts = museum.build_pack(world)
        functions = world / 'datapacks/mcylab/data/mcylab/function'
        pack = {p.relative_to(functions).with_suffix('').as_posix(): p for p in functions.rglob('*.mcfunction')}
        tree = load_tree()
        check_geometry(findings)

        yukkuri_seen = set()
        for name, path in sorted(pack.items()):
            lines = path.read_text(encoding='utf-8').splitlines()
            for number, line in enumerate(lines, 1):
                where = f'{name}:{number}'
                lint_line(line, where, findings)
                head = line.split(' ', 1)[0]
                if line and not line.startswith('#') and head not in KNOWN_HEADS:
                    findings.append(f'{where}: unknown command head {head!r}')
                for cmd in (yukkuri_commands(line) if head not in ('summon', 'tellraw', 'title', 'setblock', 'fill', 'give') else []):
                    words = split_words(cmd)
                    if words[1:2] == ['relay']:
                        inner = words[2:]
                        # relay runs its argument as a root command (ScenarioCommands.relay): a mod command keeps its yukkuri head
                        if inner and inner[0] in ('status', 'household', 'human', 'settlement'):
                            findings.append(f'{where}: relayed mod command lost its yukkuri head (runs as an unknown root command): {" ".join(inner)[:80]}')
                        elif inner and inner[0] == 'yukkuri':
                            if not matches_root(tree, inner):
                                findings.append(f'{where}: relayed command not in the Java command tree: {" ".join(inner)[:120]}')
                        elif not inner or inner[0] not in ('data', 'execute'):
                            findings.append(f'{where}: relay wraps an unknown command: {" ".join(inner)[:80]}')
                    yukkuri_seen.add(' '.join(words[1:3]) if words[1:2] in (['scenario'], ['settlement'], ['museum']) else words[1])
                    if not matches_root(tree, words):
                        findings.append(f'{where}: command not in the Java command tree: {cmd[:120]}')
                for ref in re.findall(r'function mcylab:([\w/]+)', line):
                    if ref not in pack:
                        findings.append(f'{where}: function mcylab:{ref} does not exist')
                # A clear cancels smoke-runner functions the tool writes into the pack, never the shipped pack itself;
                # ``schedule clear`` of a missing id is a no-op, unlike ``schedule function``.
                for ref in re.findall(r'schedule clear mcylab:([\w/]+)', line):
                    if ref not in pack and not ref.startswith('smoke/'):
                        findings.append(f'{where}: schedule clear of a missing function mcylab:{ref}')
                for ref in re.findall(r'schedule function mcylab:([\w/]+)', line):
                    if ref not in pack:
                        findings.append(f'{where}: scheduled function mcylab:{ref} does not exist')
                # a scenario never fixes, freezes or protects its individuals
                for banned in ('nest_lint', 'food_scrap', 'settlement assign', 'settlement threat', 'settlement order'):
                    if banned in line:
                        findings.append(f'{where}: retired content ({banned})')
                if name.startswith(('scn/', 'start/')):
                    for banned in ('Invulnerable', 'pm_actor', 'pm_live', 'Silent'):
                        if banned in line:
                            findings.append(f'{where}: scenario fixes an individual ({banned})')
                    if re.search(r'\bNoAI\s*:\s*(?:1[bB]?|true)\b', line) and 'summon mcyukkuri:caretaker_' not in line:
                        findings.append(f'{where}: scenario disables yukkuri AI (NoAI)')

        check_menu(texts, findings)
        for need in ('load', 'tick', 'probe', 'outdated'):
            if need not in pack:
                findings.append(f'function {need} missing')
        load_text = (functions / 'load.mcfunction').read_text(encoding='utf-8')
        if f'scoreboard objectives add {museum.OBJ} dummy' not in load_text:
            findings.append('scoreboard objective is never created')
        if 'function mcylab:probe' not in load_text or 'prepare' not in load_text:
            findings.append('load does not probe the runtime before preparing the map')

        ops = block_layers(pack, ['build/lobby'])
        x, y, z, _ = museum.SPAWN
        if block_id(block_at(ops, (x, y - 1, z))) != 'minecraft:smooth_quartz' or any(
                block_id(block_at(ops, (x, feet, z))) != 'minecraft:air' for feet in (y, y + 1)):
            findings.append('lobby: the spawn needs a quartz floor and two free blocks')
        print(f'functions {len(pack)}, fields {len(museum.FIELDS)}, scenarios {len(museum.museum_scenarios.S)}, '
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
    print('playground pack check: OK')


def check_menu(texts, findings):
    handler = (JAVA / 'io/github/longshou/mcyukkuri/command/MuseumMenuHandler.java').read_text(encoding='utf-8')
    ui = (JAVA / 'io/github/longshou/mcyukkuri/client/ViewerScreen.java').read_text(encoding='utf-8')
    actions = r'"((?:util|extra|village|go)/[a-z0-9_]+)"'
    targets = set(re.findall(actions, handler))
    if targets != set(re.findall(actions, ui)):
        findings.append('museum UI and server disagree on utility actions')
    for name in targets | {'menu/ready', 'village/arrival'}:
        if name not in texts:
            findings.append(f'menu: function {name} missing')

    for s in museum.museum_scenarios.S:
        expected = (f'jump/{s.id}', f'jump/{s.id}_go', f'jump/field_{s.field}', f'start/{s.id}', f'scn/{s.id}', f'view/{s.id}')
        for name in expected:
            if name not in texts:
                findings.append(f'scenario {s.id}: function {name} missing')
        jump, setup, dispatcher, start, _, view = [texts.get(name, []) for name in expected]
        if (not any('forceload add' in line for line in jump)
                or not any(f'schedule function mcylab:{expected[2]} 1t' in line for line in jump)
                or any('tp ' in line or ':go/' in line for line in jump)
                or not any('unless loaded' in line for line in dispatcher)
                or not any(f'function mcylab:{expected[1]}' in line for line in dispatcher)
                or 'forceload remove ' not in (dispatcher[-1] if dispatcher else '')
                or setup[-2:] != [f'yukkuri museum menu_seed {s.id} {s.field}', f'function mcylab:start/{s.id}']
                or start[-1:] != [f'function mcylab:view/{s.id}']
                or not any(f'function mcylab:scn/{s.id}' in line for line in start)):
            findings.append(f'scenario {s.id}: jump must load, seed, start, view and release')
        position = re.match(r'execute positioned (\S+) (\S+) (\S+).* run tp @s ~ ~ ~ ', view[0]) if view else None
        if s.vantage is None or not position:
            findings.append(f'scenario {s.id}: missing vantage teleport')
        else:
            x, _, z = map(float, position.groups())
            field = museum.BY_ID[s.field]
            if not (field.x1 <= x < field.x2 + 1 and field.z1 <= z < field.z2 + 1):
                findings.append(f'scenario {s.id}: vantage {x},{z} outside field {s.field}')

    for f in museum.FIELDS:
        if not f.scenarios and not f.reserved and f.id != 'village':
            findings.append(f'field {f.id}: no scenes')
        if f'go/{f.id}' in texts:
            findings.append(f'field {f.id}: obsolete navigation function')
        if f'menu/field/{f.id}/clear' not in texts:
            findings.append(f'field {f.id}: no menu clear function')
        clear_name = 'reset' if f.id == 'village' else 'clear'
        clear = '\n'.join(texts.get(f'field/{f.id}/{clear_name}', ()))
        if 'yukkuri clear' not in clear or f'field/{f.id}/terrain' not in clear:
            findings.append(f'field {f.id}: clear does not sweep entities and rebuild the terrain')
        if any(re.match(r'(?:return\b|execute .*\b(?:if|unless)\b)', line) for line in clear.splitlines()):
            findings.append(f'field {f.id}: field reset must be unconditional')

    controls = re.compile(r'minecraft:(?:\w*_?command_block|\w+_button|\w+_sign|text_display)\b')
    for name, lines in texts.items():
        if name.startswith('build/') or name.endswith('/terrain'):
            if any(controls.search(line) for line in lines):
                findings.append(f'{name}: obsolete in-world controls')


if __name__ == '__main__':
    main()
