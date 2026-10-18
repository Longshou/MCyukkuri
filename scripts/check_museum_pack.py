"""Offline cross-check of the playground data pack. No game, no compilation.

Generates the pack into a scratch directory below .local/experimental-map and then verifies, without running anything:

  * every `yukkuri ...` command in any function (and in every command block) is a path of the Brigadier tree that the
    Java sources register (literals and argument counts are parsed from the Java command classes);
  * every `function mcylab:x`, `schedule function` and `schedule clear` target exists in the pack;
  * every scenario has a start function and a body and a button on its field's deck; every deck and every field gate has a way back to the
    lobby; every deck has clear (unconditional), lobby, reading, time, weather, speed, advance, observe and mode buttons;
    every field teleports to its deck and belongs to a theme group listed in the lobby;
  * the lobby holds teleport buttons only (and the guide book): no time, weather, speed, spawn, clear or scenario control;
  * relayed readings (``yukkuri relay ...``) wrap a command that exists; no retired item (nest lint, food scrap) or retired command is used;
  * scenario functions never fix, freeze or protect an individual (NoAI, Invulnerable, pm_actor, pm_live);
  * the field layout: no overlap between fields, decks and gates, anchors inside their field, pond bank and water agree with the
    terrain, every den has a free doorway cell under a roof and an open notch;
  * every button survives the later fills, stands on a full block and has nothing on top; deck landings have a floor;
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
               'return', 'particle', 'tick', 'kill'}


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
    """Fields do not overlap each other or any deck; every anchor lies inside its field; the pond's bank and water agree; the cave mouths are free."""
    fields = museum.FIELDS
    for i, a in enumerate(fields):
        ax1, az1, ax2, az2 = a.rect()
        for b in fields[i + 1:]:
            bx1, bz1, bx2, bz2 = b.rect()
            if ax1 <= bx2 and bx1 <= ax2 and az1 <= bz2 and bz1 <= az2:
                findings.append(f'fields {a.id} and {b.id} overlap')
        for b in fields:
            for what, (dx1, dz1, dx2, dz2) in (('deck', b.deck_rect()), ('gate', b.gate_rect())):
                if what == 'gate' and b is a:
                    continue
                if ax1 <= dx2 and dx1 <= ax2 and az1 <= dz2 and dz1 <= az2:
                    findings.append(f'field {a.id} overlaps the {what} of {b.id}')
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
        museum.CONTROLS.clear()
        museum_sites.TEXTS.clear()
        museum.validate()
        world = scratch / 'saves' / museum.WORLD
        world.mkdir(parents=True)
        checks, shown, texts = museum.build_pack(world)
        for violation in museum_sites.VIOLATIONS:
            findings.append(f'text does not fit: {violation}')
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
                candidates = [line] + command_block_commands(line)
                for text in candidates:
                    head = text.split(' ', 1)[0]
                    if text and not text.startswith('#') and head not in KNOWN_HEADS:
                        findings.append(f'{where}: unknown command head {head!r}')
                    for cmd in (yukkuri_commands(text) if head not in ('summon', 'tellraw', 'title', 'setblock', 'fill', 'give') else []):
                        words = split_words(cmd)
                        if words[1:2] == ['relay']:
                            inner = words[2:]
                            if inner and inner[0] in ('status', 'household', 'human', 'settlement'):
                                inner = ['yukkuri'] + inner
                                if not matches_root(tree, inner):
                                    findings.append(f'{where}: relayed command not in the Java command tree: {" ".join(inner)[:120]}')
                            elif not inner or inner[0] not in ('data', 'execute'):
                                findings.append(f'{where}: relay wraps an unknown command: {" ".join(inner)[:80]}')
                        yukkuri_seen.add(' '.join(words[1:3]) if words[1:2] in (['scenario'], ['settlement']) else words[1])
                        if not matches_root(tree, words):
                            findings.append(f'{where}: command not in the Java command tree: {cmd[:120]}')
                    for ref in re.findall(r'(?:function|schedule clear) mcylab:([\w/]+)', text):
                        if ref not in pack:
                            findings.append(f'{where}: function mcylab:{ref} does not exist')
                    for ref in re.findall(r'schedule function mcylab:([\w/]+)', text):
                        if ref not in pack:
                            findings.append(f'{where}: scheduled function mcylab:{ref} does not exist')
                # a scenario never fixes, freezes or protects its individuals (only the nine clip actors do)
                for banned in ('nest_lint', 'food_scrap', 'settlement assign', 'settlement threat', 'settlement order'):
                    if banned in line:
                        findings.append(f'{where}: retired content ({banned})')
                if name.startswith(('scn/', 'start/')):
                    for banned in ('NoAI', 'Invulnerable', 'pm_actor', 'pm_live', 'Silent'):
                        if banned in line:
                            findings.append(f'{where}: scenario fixes an individual ({banned})')

        stations = museum_scenarios_all()
        lobby = '\n'.join(texts['build/lobby'])
        for s in stations:
            for need in (f'start/{s.id}', f'scn/{s.id}'):
                if need not in pack:
                    findings.append(f'scenario {s.id}: function {need} missing')
            field = museum.BY_ID[s.field]
            deck_text = '\n'.join(texts[f'build/field_{field.id}_do'])
            if f'function mcylab:start/{s.id}' not in deck_text:
                findings.append(f'scenario {s.id}: no button on the {field.id} deck')
        for f in museum.FIELDS:
            if not f.scenarios and f.id not in ('seasons', 'clips'):
                findings.append(f'field {f.id}: no scenario')
            if lobby.count(f'function mcylab:go/{f.id}') < 1:
                findings.append(f'lobby: no teleport button to {f.id}')
            deck_text = '\n'.join(texts[f'build/field_{f.id}_do'])
            for need in (f'function mcylab:field/{f.id}/clear', 'function mcylab:go/lobby', 'function mcylab:util/time_noon', 'function mcylab:util/speed_1x',
                         'function mcylab:util/speed_5x', 'function mcylab:util/speed_20x', 'function mcylab:util/sprint_1200', 'function mcylab:util/sprint_24000',
                         'function mcylab:util/weather_clear', 'function mcylab:util/weather_rain', 'function mcylab:util/weather_thunder',
                         'function mcylab:util/observe_on', 'function mcylab:util/spectator', f'function mcylab:read/{f.id}/lead', 'function mcylab:util/lead'):
                if need not in deck_text:
                    findings.append(f'field {f.id}: deck has no button for {need}')
            # the gate south of the field leads back to the deck and to the lobby (the deck itself has a lobby button too)
            gate_text = deck_text.rsplit('minecraft:smooth_stone', 1)[-1]
            if f'function mcylab:go/{f.id}' not in gate_text or 'function mcylab:go/lobby' not in gate_text:
                findings.append(f'field {f.id}: the gate has no way back (deck and lobby)')
            if f'go/{f.id}' not in pack:
                findings.append(f'field {f.id}: no teleport function')
            elif 'tp @s' not in pack[f'go/{f.id}'].read_text(encoding='utf-8'):
                findings.append(f'field {f.id}: go/{f.id} does not teleport')
            clear = pack[f'field/{f.id}/clear'].read_text(encoding='utf-8')
            if 'yukkuri clear' not in clear or f'field/{f.id}/terrain' not in clear:
                findings.append(f'field {f.id}: clear does not sweep entities and rebuild the terrain')
            if 'return' in clear or 'unless' in clear or 'if ' in clear:
                findings.append(f'field {f.id}: clear has a condition (it must never refuse)')
        # the lobby: teleports and the guide only
        lobby_commands = set()
        for line in texts['build/lobby']:
            lobby_commands.update(command_block_commands(line))
        for command in lobby_commands:
            if not re.search(r'run function mcylab:(go/[a-z_]+|guide)$', command):
                findings.append(f'lobby: a control that is not a teleport or the guide: {command}')
        for banned in ('util/', 'start/', 'field/', 'scn/', 'read/', 'extra/', 'season/', 'clip/', 'selfcheck'):
            if any(banned in c for c in lobby_commands):
                findings.append(f'lobby: a control that reaches {banned} (time, weather, speed, spawn, clear, reading ...)')
        for need in ('load', 'tick', 'probe', 'outdated', 'util/clear_all'):
            if need not in pack:
                findings.append(f'function {need} missing')
        load_text = (functions / 'load.mcfunction').read_text(encoding='utf-8')
        if f'scoreboard objectives add {museum.OBJ} dummy' not in load_text:
            findings.append('scoreboard objective is never created')
        if 'function mcylab:probe' not in load_text or 'prepare' not in load_text:
            findings.append('load does not probe the runtime before preparing the map')

        # buttons survive later fills; the block under each button is full; nothing stands on it
        order = ['build/lobby'] + [f'build/field_{f.id}_do' for f in museum.FIELDS]
        ops = block_layers(pack, order)
        for x, y, z in museum_sites.CONTROLS:
            if block_id(block_at(ops, (x, y, z))) != 'minecraft:command_block':
                findings.append(f'control at {x} {y} {z}: command block overwritten or missing ({block_at(ops, (x, y, z))})')
            if block_id(block_at(ops, (x, y + 2, z))) != 'minecraft:stone_button':
                findings.append(f'control at {x} {y} {z}: button overwritten or missing ({block_at(ops, (x, y + 2, z))})')
            if block_id(block_at(ops, (x, y + 1, z))) != 'minecraft:polished_andesite':
                findings.append(f'control at {x} {y} {z}: the block under the button changed')
            if block_id(block_at(ops, (x, y + 3, z))) != 'minecraft:air':
                findings.append(f'control at {x} {y} {z}: something stands on the button')
        controls = list(museum_sites.CONTROLS)
        if len(set(controls)) != len(controls):
            findings.append('two buttons share one cell')
        # every deck landing and the spawn have a floor and free space
        for f in museum.FIELDS:
            x, y, z = [int(v // 1) for v in pack_land(pack, f.id)]
            if block_id(block_at(ops, (x, y - 1, z))) == 'minecraft:air' or block_id(block_at(ops, (x, y, z))) != 'minecraft:air':
                findings.append(f'field {f.id}: the deck landing {x} {y} {z} has no floor or is not free')
        print(f'functions {len(pack)}, fields {len(museum.FIELDS)}, scenarios {len(stations)}, controls {checks["controls"]}, texts {checks["texts"]}, '
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


def museum_scenarios_all():
    import museum_scenarios
    return museum_scenarios.S


def pack_land(pack, field_id):
    m = re.match(r'tp @s (\S+) (\S+) (\S+) ', pack[f'go/{field_id}'].read_text(encoding='utf-8'))
    return float(m.group(1)), float(m.group(2)), float(m.group(3))


def matches_root(tree, words):
    """`yukkuri` is the root literal; the words begin with it."""
    return words and words[0] == tree.name and matches(tree, words[1:], 0)


if __name__ == '__main__':
    main()
