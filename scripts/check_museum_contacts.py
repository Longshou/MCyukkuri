"""Check authored navigation targets and scene commands offline; never certify runtime arrival.

No game, compilation, desktop or browser. Reports/previews stay in .local.
"""
import json
import math
import re
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import build_museum_full as museum
from museum_dsl import BODY_GEOMETRY, GROWTH, Ctx
from museum_hat_boats import HAT_DATA, HAT_SCALE, configure

ROOT = Path(__file__).resolve().parents[1]


def surface(x, z):
    def inside(r):
        return r[0] <= x <= r[1] and r[2] <= z <= r[3]
    top = HAT_DATA['bowl']['freeboard']
    if inside(HAT_DATA['well']):
        return top - HAT_DATA['floor'] * HAT_DATA['squash']['bowl']
    if inside(HAT_DATA['opening']):
        return top - HAT_DATA['ledge'] * HAT_DATA['squash']['bowl']
    if any(inside(r) for r in HAT_DATA['brim_rects']):
        return top
    return math.nan


def footprint(x, z, radius):
    heights = [surface(x, z)] + [surface(x + radius * math.cos(i * math.pi / 4),
                                        z + radius * math.sin(i * math.pi / 4)) for i in range(8)]
    return max((h for h in heights if math.isfinite(h)), default=math.nan)


def replay(c, did, steps):
    actors = {c.tag(k): dict(key=k, pos=list(c.home(k)[:3]), yaw=c.home(k)[3], vehicle=None, seat=None) for k in c.actors}
    hulls, contacts, seats, snapshots = {}, [], [], []
    clock = 0

    def update():
        for tag, a in actors.items():
            if a['vehicle'] not in hulls:
                continue
            h = hulls[a['vehicle']]
            local = a['seat']
            if local is None:
                # All actual contact phases explicitly assign both seats. This is the owner's idle berth.
                spec = c.actors[a['key']]
                scale = GROWTH.get(spec['stage'], (None, None, 1))[2]
                back = BODY_GEOMETRY.get(spec['kind'], {'bounds': [[0, 0], [0, 0], [0, 5.9]]})['bounds'][2][1] / 16 * scale
                local = (0, HAT_DATA['bowl']['owner_y'], max(0, HAT_DATA['opening'][3] - back / (h['scale'] / 16)))
            turn = math.radians(180 - h['yaw'])
            x, y, z = local
            unit = h['scale'] / 16
            a['pos'] = [h['pos'][0] + (x * math.cos(turn) + z * math.sin(turn)) * unit,
                        h['pos'][1] + y * unit,
                        h['pos'][2] + (z * math.cos(turn) - x * math.sin(turn)) * unit]

    for delay, commands in steps:
        for kind, key, value, cmd in commands:
            tags = re.findall(r'@e\[tag=([^,\]]+)', cmd)
            if cmd.startswith('function ') and cmd.endswith('_clear_hats'):
                hulls.clear()
                for a in actors.values():
                    a['vehicle'] = a['seat'] = None
            if cmd.startswith('summon mcyukkuri:hat_boat '):
                parts = cmd.split()
                tag = re.findall(r'"(pm_[^"]+_hat)"', cmd)[-1]
                hulls[tag] = dict(pos=list(map(float, parts[2:5])), yaw=float(re.search(r'Rotation:\[([-\d.]+)f', cmd)[1]),
                                  scale=float(re.search(r'HullScale:([-\d.]+)f', cmd)[1]))
            if cmd.startswith('kill ') and tags:
                hulls.pop(tags[0], None)
            if cmd.startswith('ride ') and tags and tags[0] in actors:
                a = actors[tags[0]]
                a['vehicle'] = tags[1] if ' mount ' in cmd else None
                a['seat'] = None
            if cmd.startswith('tp ') and tags:
                parts = cmd.split()
                target = actors.get(tags[0], hulls.get(tags[0]))
                if target is not None and len(parts) >= 6:
                    target['pos'] = list(map(float, parts[2:5]))
                    target['yaw'] = float(parts[5])
                    if tags[0] in actors:
                        assert not target['vehicle'], (c.sid, did, 'teleport would dismount actor', tags[0])
            if cmd.startswith('yukkuri museum walk ') and tags:
                # This is a planned target only. Generated functions wait on pm_walking and
                # stop on pm_walk_failed; actual path success is tested in the running game.
                actors[tags[0]]['pos'] = list(map(float, cmd.split()[-3:]))
            if cmd.startswith('yukkuri museum face '):
                actors[tags[0]]['yaw'] = float(cmd.split()[-1])
            if cmd.startswith('yukkuri museum seat '):
                a = actors[tags[0]]
                assert a['vehicle'] in hulls, (c.sid, did, 'seat without boat', tags[0])
                h = hulls[a['vehicle']]
                x, z = map(float, cmd.split()[-2:])
                radius = c.width(a['key']) * HAT_DATA['delivery']['young_radius'] / (h['scale'] / 16)
                y = footprint(x, z, radius)
                assert math.isfinite(y) and math.isfinite(surface(x, z)), (c.sid, did, 'unsupported seat', x, z)
                a['seat'] = [x, y, z]
                seats.append(dict(actor=a['key'], tick=clock, local=[x, y, z]))
            update()
            if cmd.startswith('yukkuri museum contact '):
                a, b = [actors[tag] for tag in tags]
                dx, dy, dz = [b['pos'][i] - a['pos'][i] for i in range(3)]
                horizontal = math.hypot(dx, dz)
                body = (c.width(a['key']) + c.width(b['key'])) / 2
                assert body - .101 <= horizontal <= body + .101 and abs(dy) <= .5, (c.sid, did, 'contact gap', horizontal, body, dy)
                contact = cmd.split()[-1]
                a['yaw'] = math.degrees(math.atan2(-dx, dz))
                b['yaw'] = a['yaw'] if contact == 'rear' else a['yaw'] + 180
                contacts.append(dict(stand=c.sid, demo=did, row=c.row, tick=clock, kind=contact, distance=round(horizontal, 4),
                                     minimum=round(body - .1, 4), maximum=round(body + .1, 4),
                                     first=dict(a), second=dict(b)))
            if kind == 'action':
                snapshots.append(dict(stand=c.sid, demo=did, actor=key, action=value, tick=clock,
                                      pos=actors[c.tag(key)]['pos'][:], yaw=actors[c.tag(key)]['yaw']))
        clock += delay
    return dict(contacts=contacts, seats=seats, snapshots=snapshots)


def main():
    halls = museum.build_layout()
    museum.validate(halls)
    results = []
    for _, _, _, stands in museum.museum_scenes.HALLS:
        for sid, _, _, color, builder in stands:
            for row in ('N', 'S'):
                c = Ctx(sid, row, 0, 0, color)
                c.meadow = (0, 66, 0)
                builder(c)
                configure(c)
                for did, _, steps in c.demos:
                    results.append(replay(c, did, steps))
    contacts = [v for r in results for v in r['contacts']]
    seats = [v for r in results for v in r['seats']]
    out = ROOT / '.local/reports/museum-contact'
    out.mkdir(parents=True, exist_ok=True)
    report = dict(scenes=len(results), contacts=len(contacts), seats=len(seats), checks=contacts,
                  scope='Planned navigation endpoints and command structure only; no proof of runtime arrival or interaction')
    (out / 'checks.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    render(contacts)
    print(f"Checked {len(results)} mirrored scenes, {len(contacts)} contacts, {len(seats)} supported seats")


def render(contacts):
    from PIL import Image, ImageDraw, ImageFont
    selected = [x for x in contacts if x['row'] == 'N' and (x['stand'], x['demo'], x['kind']) in {
        ('social', 'hello', 'nuzzle'), ('feeding', 'feed', 'feed'), ('feeding', 'toilet', 'rear'),
        ('house', 'egg', 'nuzzle'), ('date', 'date', 'nuzzle'), ('boatfamily', 'feed', 'feed')}]
    names = {'social': '相遇蹭蹭', 'feeding': '亲子照料', 'house': '家油孵化', 'date': '相亲', 'boatfamily': '船上递喂'}
    canvas = Image.new('RGB', (1440, 980), '#f0f2ec')
    draw = ImageDraw.Draw(canvas)
    font = lambda size: ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', size)
    draw.text((40, 25), '展馆接触位置检查', fill='#26483c', font=font(36))
    draw.text((42, 80), '计划导航终点 · 圆表示碰撞范围 · 抵达与真实行为需实机验证 · 非游戏截图', fill='#5f726a', font=font(21))
    for i, row in enumerate(selected):
        x, y = 35 + i % 3 * 470, 135 + i // 3 * 400
        draw.rounded_rectangle((x, y, x+450, y+375), 16, fill='#ffffff')
        draw.text((x+20, y+18), names[row['stand']] + (' · 后侧' if row['kind'] == 'rear' else ''), font=font(26), fill='#26483c')
        a, b = row['first'], row['second']
        center = [(a['pos'][j] + b['pos'][j]) / 2 for j in range(3)]
        scale = 245
        # Approximate individual radius from the checked pair; the chart explicitly denotes envelopes.
        radii = [(row['minimum'] + .1) / 2] * 2
        if row['stand'] in ('feeding', 'boatfamily'):
            radii = [.4, .072]
        if row['stand'] == 'house':
            radii = [.135, .135 * .43]
        for actor, radius, color in zip((a, b), radii, ('#e5c59e', '#b9d7ca')):
            px = x + 225 + (actor['pos'][0] - center[0]) * scale
            py = y + 195 + (actor['pos'][2] - center[2]) * scale
            r = radius * scale
            draw.ellipse((px-r, py-r, px+r, py+r), fill=color, outline='#587065', width=2)
            yaw = math.radians(actor['yaw'])
            dx, dz = -math.sin(yaw), math.cos(yaw)
            end = (px + dx * (r+14), py + dz * (r+14))
            draw.line((px, py, *end), fill='#2b4f42', width=3)
            draw.ellipse((end[0]-4, end[1]-4, end[0]+4, end[1]+4), fill='#2b4f42')
        draw.text((x+20, y+298), f"中心距 {row['distance']:.3f} 格", font=font(23), fill='#26483c')
        draw.text((x+20, y+334), f"接触范围 {row['minimum']:.3f}–{row['maximum']:.3f} 格", font=font(18), fill='#5f726a')
    out = ROOT / '.local/previews/museum-contact'
    out.mkdir(parents=True, exist_ok=True)
    canvas.save(out / 'layout.png')
    (out / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>展馆接触位置</title>'
                                   '<style>body{margin:0;background:#f0f2ec}img{width:100%;max-width:1440px}</style>'
                                   '<img src="layout.png" alt="展馆接触位置离线示意">', encoding='utf-8')


if __name__ == '__main__':
    main()
