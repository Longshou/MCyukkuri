"""The single expression-preset geometry-offset implementation."""
import copy
import hashlib
import json


def geometry_fingerprint(preset):
    """Identify exactly the preset inputs read by the geometry bake."""
    inputs = dict(geometry_offsets=preset.get('geometry_offsets', {}),
                  cheek_scale=preset.get('cheek_scale', 1))
    payload = json.dumps(inputs, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(payload.encode('utf-8')).hexdigest()


def profile_point(profile, y):
    for a, b in zip(profile, profile[1:]):
        if y <= b[0]:
            t = min(1, max(0, (y-a[0])/(b[0]-a[0])))
            return [a[k]+(b[k]-a[k])*t for k in range(1, 4)]
    return profile[-1][1:]


def preset_changes(preset):
    changes = copy.deepcopy(preset.get('geometry_offsets', {}))
    if preset.get('cheek_scale', 1) != 1:
        for slot in ('face_blush_left', 'face_blush_right'):
            changes[slot] = {'scale': [1, preset['cheek_scale'], 1]}
    return changes


def offset_points(points, center, delta, position_scale, dimensions, surface_z=None):
    scale = delta.get('scale', [1, 1, 1])
    position = delta.get('position', [0, 0, 0])
    for point in points:
        for axis in range(dimensions):
            point[axis] = (center[axis] + (point[axis]-center[axis])*scale[axis]
                           + position[axis]*position_scale[axis])
        if surface_z is not None:
            point[2] = surface_z(point[1])


def offset_element(element, delta, position_scale=(1, 1, 1), surface_z=None):
    """Offset a BBModel cube or mesh without changing its serialization."""
    patched = copy.deepcopy(element)
    if patched.get('type') == 'mesh':
        points = list(patched['vertices'].values())
        center = [sum(point[axis] for point in points)/len(points) for axis in range(3)]
    else:
        points = [patched['from'], patched['to']]
        center = [(a+b)/2 for a, b in zip(patched['from'], patched['to'])]
    offset_points(points, center, delta, position_scale, 2 if surface_z is not None else 3, surface_z)
    return patched


def offset_vertices(values, delta, position_scale):
    """Offset flattened runtime triangles in their serialized coordinate space."""
    result = values.copy()
    points = [result[index:index+3] for index in range(0, len(result), 8)]
    center = [(min(point[axis] for point in points)+max(point[axis] for point in points))/2
              for axis in range(3)]
    offset_points(points, center, delta, position_scale, 2)
    for index, point in zip(range(0, len(result), 8), points):
        result[index:index+2] = [round(value, 7) for value in point[:2]]
    return result
