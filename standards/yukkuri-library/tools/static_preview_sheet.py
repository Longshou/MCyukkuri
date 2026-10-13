"""Write a static orthographic PNG sheet from assembled exports for user review.

Pure Python (zlib only): reads assembled/<name>/<name>.bbmodel, animation-rig.json and the
neutral texture, draws flat-shaded views with a depth buffer. It does not start a browser,
renderer or the project, and it is not a rendering or motion acceptance check. Hidden
default parts (puff, anatomy, contact) are skipped. Like runtime/render.js and Minecraft,
+X is drawn on the right of the front view. Views are yaw:pitch in degrees; yaw 0 looks at
the face (-Z), yaw 90 at the +X side, yaw 35 from the front-right.

  python tools/static_preview_sheet.py previews/current/marisa-braid.png marisa reimu:0:0
"""
from pathlib import Path
import argparse, json, math, struct, sys, zlib

R = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(R), str(R/'ecology/house-yukkuri')]
from mesh_geometry import vertices  # cubes and the rounded meshes

def texture_file(out, name):
    """Neutral atlas next to the export, else the selected game texture."""
    for p in (out/f'{name}.png', out/'textures/neutral.png', R.parents[1]/f'src/main/resources/assets/mcyukkuri/textures/entity/{name}/neutral.png'):
        if p.exists(): return p
    raise FileNotFoundError(name)

def read_png(path):
    data = Path(path).read_bytes(); pos = 8; idat = b''; plte = trns = None
    while pos < len(data):
        n, = struct.unpack('>I', data[pos:pos+4]); t = data[pos+4:pos+8]; body = data[pos+8:pos+8+n]; pos += 12+n
        if t == b'IHDR': w, h, depth, ctype, _, _, inter = struct.unpack('>IIBBBBB', body)
        elif t == b'PLTE': plte = body
        elif t == b'tRNS': trns = body
        elif t == b'IDAT': idat += body
    assert depth == 8 and inter == 0 and ctype in (2, 3, 6), (path, depth, ctype, inter)
    ch = {2: 3, 6: 4, 3: 1}[ctype]; raw = zlib.decompress(idat); stride = w*ch; prev = bytearray(stride); rows = []; i = 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i+1:i+1+stride]); i += 1+stride
        for x in range(stride):
            a = line[x-ch] if x >= ch else 0; b = prev[x]; c = prev[x-ch] if x >= ch else 0
            if f == 1: line[x] = (line[x]+a) & 255
            elif f == 2: line[x] = (line[x]+b) & 255
            elif f == 3: line[x] = (line[x]+(a+b)//2) & 255
            elif f == 4:
                p = a+b-c; pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                line[x] = (line[x]+(a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        if ctype == 6: rows.append([tuple(line[x:x+4]) for x in range(0, stride, 4)])
        elif ctype == 2: rows.append([(*line[x:x+3], 255) for x in range(0, stride, 3)])
        else: rows.append([(*plte[k*3:k*3+3], trns[k] if trns and k < len(trns) else 255) for k in line])
    return w, h, rows

def write_png(path, rows):
    raw = b''.join(b'\x00'+bytes(c for px in row for c in px) for row in rows)
    chunk = lambda t, b: struct.pack('>I', len(b))+t+b+struct.pack('>I', zlib.crc32(t+b) & 0xffffffff)
    Path(path).write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR', struct.pack('>IIBBBBB', len(rows[0]), len(rows), 8, 2, 0, 0, 0))
                           + chunk(b'IDAT', zlib.compress(raw, 9))+chunk(b'IEND', b''))

def triangles(name):
    """name is a library character, or a .bbmodel path (relative to the library) with a sibling .rig.json."""
    if name.endswith('.bbmodel'):
        model = R/name; out, name = model.parent, model.stem; rig_path = out/f'{name}.rig.json'
        if not rig_path.exists(): rig_path = out/'animation-rig.json'
    else:
        out = R/'assembled'/name; rig_path = out/'animation-rig.json'
    bb = json.loads((out/f'{name}.bbmodel').read_text(encoding='utf-8-sig'))
    groups = json.loads(rig_path.read_text(encoding='utf-8-sig'))['groups']
    tw, th, tex = read_png(texture_file(out, name)); tris = []
    for e, g in zip(bb['elements'], groups):
        if g.startswith(('puff_', 'anatomy_')) or 'contact_' in g: continue
        v = vertices(e, tw)
        tris += [[v[i+k*8:i+k*8+8] for k in range(3)] for i in range(0, len(v), 24)]
    return tris, (tw, th, tex)

def draw(rows, x_offset, size, tris, texture, yaw, pitch, scale, center_y):
    W, H = size; tw, th, tex = texture; zbuf = [[1e9]*W for _ in range(H)]
    cy, sy, cp, sp = math.cos(math.radians(yaw)), math.sin(math.radians(yaw)), math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    def view(x, y, z):
        x, z = x*cy+z*sy, -x*sy+z*cy
        return x, y*cp-z*sp, y*sp+z*cp
    light = [-.35, .55, -.75]; n = math.sqrt(sum(a*a for a in light)); light = [a/n for a in light]
    for t in tris:
        P = [view(p[0], p[1]-center_y, p[2]) for p in t]
        if view(*t[0][5:8])[2] > 1e-6: continue
        sx = [W/2+p[0]*scale for p in P]; sy_ = [H/2-p[1]*scale for p in P]
        d = (sx[1]-sx[0])*(sy_[2]-sy_[0])-(sx[2]-sx[0])*(sy_[1]-sy_[0])
        if abs(d) < 1e-9: continue
        shade = .72+.28*max(0, sum(a*b for a, b in zip(t[0][5:8], light)))
        for py in range(max(0, int(min(sy_))), min(H-1, int(max(sy_))+1)+1):
            for px in range(max(0, int(min(sx))), min(W-1, int(max(sx))+1)+1):
                qx, qy = px+.5, py+.5
                a = ((sx[1]-qx)*(sy_[2]-qy)-(sx[2]-qx)*(sy_[1]-qy))/d; b = ((sx[2]-qx)*(sy_[0]-qy)-(sx[0]-qx)*(sy_[2]-qy))/d; c = 1-a-b
                if min(a, b, c) < -1e-6: continue
                z = a*P[0][2]+b*P[1][2]+c*P[2][2]
                if z >= zbuf[py][px]-1e-4: continue
                u = a*t[0][3]+b*t[1][3]+c*t[2][3]; v = a*t[0][4]+b*t[1][4]+c*t[2][4]
                r, g, bl, al = tex[min(th-1, max(0, int(v*th)))][min(tw-1, max(0, int(u*tw)))]
                if al < 128: continue
                zbuf[py][px] = z; rows[py][x_offset+px] = (int(r*shade), int(g*shade), int(bl*shade))
    ground = int(H/2+center_y*scale)
    if 0 <= ground < H:
        for px in range(W):
            if rows[ground][x_offset+px] == (234, 231, 222): rows[ground][x_offset+px] = (190, 182, 165)

def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('out'); ap.add_argument('panels', nargs='+', help='character[:yaw:pitch]; a bare character uses --views')
    ap.add_argument('--views', default='0:0,35:12,90:0,-90:0,180:0')
    ap.add_argument('--scale', type=float, default=22); ap.add_argument('--span', type=float, nargs=2, default=[24, 21])
    ap.add_argument('--center-y', type=float, default=7.6)
    a = ap.parse_args(); panels = []
    for p in a.panels:
        name, *angles = p.split(':')
        panels += [(name, float(angles[0]), float(angles[1]))] if angles else \
                  [(name, *map(float, v.split(':'))) for v in a.views.split(',')]
    W, H = int(a.span[0]*a.scale), int(a.span[1]*a.scale); rows = [[(234, 231, 222)]*(W*len(panels)) for _ in range(H)]
    cache = {}
    for i, (name, yaw, pitch) in enumerate(panels):
        if name not in cache: cache[name] = triangles(name)
        draw(rows, i*W, (W, H), *cache[name], yaw, pitch, a.scale, a.center_y)
        if i:
            for py in range(H): rows[py][i*W] = (205, 199, 188)
    write_png(R/a.out, rows); print('wrote', a.out, len(panels), 'views')

if __name__ == '__main__':
    main()
