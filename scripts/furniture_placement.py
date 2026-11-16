"""Shared joined block states for authored furniture in structures and the museum."""


def joined(kind, cells, axis='x', wood=None):
    """Map (x, z) cells to the same joins that player-placed furniture computes. wood: the piece's wood (block property `wood`);
    left out, the state carries no wood and the block's default (oak) applies."""
    cells, out = set(cells), {}
    for x, z in sorted(cells):
        near = {'north': (x, z - 1), 'south': (x, z + 1), 'west': (x - 1, z), 'east': (x + 1, z)}
        props = {d: str(c in cells and (kind == 'wooden_table' or (d in ('west', 'east')) == (axis == 'x'))).lower()
                 for d, c in near.items()}
        if kind == 'wooden_bench':
            props['axis'] = axis
        if wood:
            props['wood'] = wood
        out[x, z] = props
    return out


def state(props):
    return '[' + ','.join(f'{k}={v}' for k, v in sorted(props.items())) + ']'
