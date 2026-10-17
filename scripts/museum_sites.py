"""Shared building blocks of the museum map: labelled buttons, text panels and observation decks.

Everything here only returns command strings. ``build_museum_full.py`` writes them into the data pack.
Text widths use a CJK-aware unit (a wide character counts 2) so that every board and label can be
checked against the room it hangs in before the pack is written.
"""
import json
import sys
import unicodedata

sys.dont_write_bytecode = True
from museum_dsl import _q  # noqa: E402

CONTROLS = []          # (x, y, z) of every hidden command block, for the in-game self check
VIOLATIONS = []        # text that does not fit its board or label
TEXTS = []             # (kind, context, [line, ...]) of every text display, for the offline fit check

# Widths are in half-em units (an ASCII character is 1, a CJK character is 2).
LIMITS = {'button': (16, 3), 'wall': (30, 3), 'board': (46, 11), 'title': (34, 2), 'banner': (40, 4)}
YAW = {'south': 0, 'west': 90, 'north': 180, 'east': -90}


def units(text):
    return sum(2 if unicodedata.east_asian_width(ch) in 'WF' else 1 for ch in text)


def signed(argb):
    return argb - (1 << 32) if argb >= 1 << 31 else argb


def rich(parts):
    """parts: [(text, colour[, bold])] -> JSON text component (a list, so colours do not leak between parts)."""
    out = []
    for part in parts:
        comp = {'text': part[0], 'color': part[1] if len(part) > 1 else 'white'}
        if len(part) > 2 and part[2]:
            comp['bold'] = True
        out.append(comp)
    return json.dumps(out, ensure_ascii=False, separators=(',', ':'))


def check_text(kind, context, parts):
    limit, rows = LIMITS[kind]
    lines = ''.join(p[0] for p in parts).split('\n')
    if len(lines) > rows:
        VIOLATIONS.append((kind, context, 'too many lines', len(lines), rows))
    for line in lines:
        if units(line) > limit:
            VIOLATIONS.append((kind, context, 'line too wide', units(line), limit, line))
    TEXTS.append((kind, context, lines))


def display(x, y, z, parts, scale, kind, context, billboard='center', yaw=0, width=300, align='center', bg=0x66000000, tags=()):
    """A text_display. 'center' always faces the viewer, 'vertical' only turns around the vertical axis."""
    check_text(kind, context, parts)
    tag_list = ','.join(f'"{t}"' for t in ('pm_label',) + tuple(tags))
    return (f'summon minecraft:text_display {x} {y} {z} {{Tags:[{tag_list}],Rotation:[{float(yaw)}f,0f],text:{_q(rich(parts))},'
            f'billboard:"{billboard}",alignment:"{align}",line_width:{width},background:{signed(bg)},'
            f'transformation:{{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[{scale}f,{scale}f,{scale}f]}}}}')


def wrap(text, limit=14):
    """Split a caption into lines no wider than ``limit`` units."""
    lines, current = [], ''
    for ch in text:
        if units(current + ch) > limit:
            lines.append(current)
            current = ''
        current += ch
    return lines + ([current] if current else [])


def button(x, y, z, command, caption, hint='', *, nav=False, context='', scale=.42, color='white', label=True):
    """Hidden command block, andesite and a floor button at (x, y, z), plus a floating label. A press runs ``command``.

    ``nav`` runs the command as the nearest player (teleports, per-player messages)."""
    CONTROLS.append((x, y - 2, z))
    if nav:
        command = f'execute as @p[distance=..8] at @s run {command}'
    parts = [('\n'.join(wrap(caption)), color, True)]
    if hint:
        parts.append(('\n' + '\n'.join(wrap(hint, 16)), 'gray'))
    out = [f'setblock {x} {y - 2} {z} minecraft:command_block{{Command:{_q(command)},TrackOutput:0b}}',
           f'setblock {x} {y - 1} {z} minecraft:polished_andesite',
           f'setblock {x} {y} {z} minecraft:stone_button[face=floor]']
    if label:
        out.append(display(x + .5, y + .95, z + .5, parts, scale, 'button', context or caption))
    return out


def wall_label(x, y, z, parts, scale, face, kind='wall', context='', width=220):
    """A label hung on a wall. It uses a vertical billboard (turns around the vertical axis only) so that it is
    readable whichever way the visitor stands; ``face`` documents the wall side."""
    return display(x, y, z, parts, scale, kind, context, billboard='vertical', width=width)


def fence_ring(cells, y):
    """Oak fences over a set of (x, z) cells with explicit connections (fill/setblock never connect fences)."""
    cells = set(cells)
    out = []
    for (x, z) in sorted(cells):
        flags = [f'{name}=true' for name, (dx, dz) in (('north', (0, -1)), ('south', (0, 1)), ('east', (1, 0)), ('west', (-1, 0)))
                 if (x + dx, z + dz) in cells]
        out.append(f'setblock {x} {y} {z} minecraft:oak_fence[{",".join(flags)}]')
    return out


def deck(x1, z1, x2, z2, top, open_side, context):
    """Raised oak observation platform. ``top`` is the standing height; the platform is three blocks of planks
    below it. Railings run along every edge except ``open_side`` ('west'/'east'/'north'/'south'), which faces the
    yard. Returns (commands, (x, y, z, yaw) landing in the middle of the open edge)."""
    lines = [f'fill {x1} {top - 3} {z1} {x2} {top - 1} {z2} minecraft:oak_planks',
             f'fill {x1} {top} {z1} {x2} {top + 2} {z2} minecraft:air']
    edge = set()
    for x in range(x1, x2 + 1):
        for z in range(z1, z2 + 1):
            if x in (x1, x2) or z in (z1, z2):
                if (open_side == 'west' and x == x1) or (open_side == 'east' and x == x2) \
                        or (open_side == 'north' and z == z1) or (open_side == 'south' and z == z2):
                    continue
                edge.add((x, z))
    lines += fence_ring(edge, top)
    cx, cz = (x1 + x2) / 2 + .5, (z1 + z2) / 2 + .5
    land = {'west': (x1 + .5, top, cz), 'east': (x2 + .5, top, cz), 'north': (cx, top, z1 + .5), 'south': (cx, top, z2 + .5)}[open_side]
    facing = {'west': 'west', 'east': 'east', 'north': 'north', 'south': 'south'}[open_side]
    return lines, (land[0], land[1], land[2], YAW[facing])


BOARD_KEYS = (('watch', '看点', 'aqua'), ('press', '操作', 'green'), ('wait', '预计', 'yellow'), ('fail', '没反应？', 'red'))


def board_parts(info):
    """Text parts of a station board: what to watch / press / wait for / do if nothing happens, then EN and JA lines."""
    parts = []
    for key, label, color in BOARD_KEYS:
        prefix = label + ' '
        pad = units(prefix)
        lines = wrap(info[key], LIMITS['board'][0] - pad)
        for i, line in enumerate(lines):
            parts.append((prefix if i == 0 else ' ' * pad, color if i == 0 else 'gray', True))
            parts.append((line + '\n', 'white'))
    parts.append((info['en'] + '\n', 'gray'))
    parts.append((info['ja'], 'gray'))
    return parts


def deck_controls(c, x, y, z, extra=(), step=2):
    """Buttons in a column at (x, y) centred on z: back to the station, back to the lobby, then ``extra``
    entries (command, caption, hint[, nav]). Every remote courtyard offers both ways home."""
    items = [(f'function mcylab:go/s/{c.sid}', '回展台', f'{c.number:02d} {c.title}', True),
             ('function mcylab:go/lobby', '回大厅', '', True)]
    for entry in extra:
        items.append((entry + (False,))[:4] if len(entry) == 3 else entry)
    out = []
    first = z - step * (len(items) - 1) // 2
    for i, (command, caption, hint, nav) in enumerate(items):
        out += button(x, y, first + i * step, command, caption, hint, nav=nav, context=f'{c.sid} deck {caption}')
    return out
