"""Coarse per-cell selection silhouettes of the multi-cell facility blocks.

A facility part's outline is its slice of this box list, clipped to one block cell. The boxes are the visible shape,
not the whole cell: voxels are marked where they overlap a source box by more than nothing on an x/z grid of `qx`
units and a y grid of one unit, assigned to the cell that draws them (x -1..1, y 0..1, z -depth..depth), and merged
per cell into axis-aligned boxes. Y stays exact so thin horizontal surfaces (trampoline bed, tub bench) do not
inflate. Pass `qx=1` for block-style cubes already on the texel grid.
"""
import math


def facility_outline(boxes, depth, qx=4):
    """Outline boxes for a facility whose cells span x -1..1, y 0..1, z -depth..depth, in authoring units (16/block)."""
    per_cell = {}
    for x0, y0, z0, x1, y1, z1 in boxes:
        for i in range(math.floor(x0 / qx), math.ceil(x1 / qx)):
            if (i + 1) * qx <= x0 or i * qx >= x1:
                continue
            for j in range(math.floor(y0), math.ceil(y1)):
                if j + 1 <= y0 or j >= y1:
                    continue
                for k in range(math.floor(z0 / qx), math.ceil(z1 / qx)):
                    if (k + 1) * qx <= z0 or k * qx >= z1:
                        continue
                    cell = (math.floor((i * qx + qx / 2 + 8) / 16), math.floor((j + .5) / 16),
                            math.floor((k * qx + qx / 2 + 8) / 16))
                    if cell[0] in (-1, 0, 1) and cell[1] in (0, 1) and -depth <= cell[2] <= depth:
                        per_cell.setdefault(cell, set()).add((i, j, k))
    return [box for cell in sorted(per_cell, key=lambda c: (c[1], c[0], c[2]))
            for box in _merge(per_cell[cell], qx)]


def _merge(voxels, qx):
    """Greedy merge of one cell's voxels, x run then z run then y column, into [x0, y0, z0, x1, y1, z1] boxes."""
    left, boxes = set(voxels), []
    for i, j, k in sorted(voxels):
        if (i, j, k) not in left:
            continue
        x1 = i
        while (x1 + 1, j, k) in left:
            x1 += 1
        z1 = k
        while all((x, j, z1 + 1) in left for x in range(i, x1 + 1)):
            z1 += 1
        y1 = j
        while all((x, y1 + 1, z) in left for x in range(i, x1 + 1) for z in range(k, z1 + 1)):
            y1 += 1
        for x in range(i, x1 + 1):
            for y in range(j, y1 + 1):
                for z in range(k, z1 + 1):
                    left.discard((x, y, z))
        boxes.append([i * qx, j, k * qx, (x1 + 1) * qx, y1 + 1, (z1 + 1) * qx])
    return boxes
