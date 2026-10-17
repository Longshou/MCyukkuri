"""Finite real aquatic fixtures. Movement, cargo and rescue belong to ordinary goals."""
import json
from pathlib import Path
from museum_dsl import STAGE_Y, _text

HAT_DATA = json.loads((Path(__file__).resolve().parents[1] /
    'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))
HAT_SCALE = HAT_DATA['aquatic_scale']
SURFACE_Y = round(STAGE_Y - 1 / 9 + .025, 6)


def configure(c):
    if not c.water:
        return
    c.batch = False      # boats and crews are real: only their own safe-return button resets them
    c.demos.clear()
    c.buttons.clear()
    c.stop_before.clear()
    c.stop_extra.clear()
    c.props.clear()
    c.fill_cells(1, 1, 9, 5, 'minecraft:stone', dy=-2)
    c.fill_cells(1, 1, 9, 5, 'minecraft:water', dy=-1)
    c.fill_cells(0, 0, 10, 0, 'minecraft:smooth_quartz', dy=-1)
    for key in list(c.actors):
        if not c.actors[key]['aquatic']:
            del c.actors[key]
    parent = {'boatlife': 'sailor', 'voyage': 'dad', 'rescue': 'mom'}[c.sid]
    if c.sid == 'boatlife':
        for i in range(4):
            c.actor(f'young{i}', 'marisa', 2 + i, 0, 'B', f'真实载荷赤油 {i + 1}', stage='baby', aquatic=True)
            c.actors[f'young{i}']['installable'] = True
    for i, spec in enumerate(c.actors.values()):
        spec['x'], spec['z'] = c.xz(1.5 + i * 1.3, 0)
        spec['y'] = STAGE_Y
    bx, bz = c.cell(5, 0)
    wx, wz = c.cell(5, 1)
    bank = f'{bx} {STAGE_Y} {bz}'
    water = f'{wx} {STAGE_Y - 1} {wz}'

    def selector(key):
        return f'@e[tag={c.tag(key)},limit=1]'

    def fixture(name, caption, mode, owner, others):
        fname = f'{c.sid}_{name}'
        near = c.near()
        busy = _text('上一轮水生演出还在进行：等船和幼体安全上岸，或按“安全回岸重置”。', 'yellow')
        lines = ['function mcylab:install_new_actors',
                 f'execute if entity @e[tag=room_{c.sid},tag=pm_museum_aquatic] run return run tellraw {near} {busy}']
        for item, count in [('stick', 2), ('apple', 8)]:
            tag = f'pm_{c.sid}_aquatic_stock_{item}'
            lines.append(f'execute unless entity @e[tag={tag}] run summon minecraft:item {bx + .5} {STAGE_Y + .05} {bz + .5} '
                         f'{{Tags:["pm_aquatic_supply","room_{c.sid}","{tag}"],PickupDelay:0s,Item:{{id:"minecraft:{item}",count:{count}}}}}')
        lines += [f'execute store result score #aquatic museum run yukkuri museum aquatic {mode} {selector(owner)} {others} {bank} {water}',
                  f'execute unless score #aquatic museum matches 1 run tellraw {near} '
                  + _text('准备失败：演员、真实物资或干岸/水面不满足条件（原因见上一行）。先按“安全回岸重置”再试。', 'red'),
                  f'execute if score #aquatic museum matches 1 run tellraw {near} '
                  + _text('已设置初始条件，后续航行、照护与救援由真实行为决定；请耐心等待。', 'gold')]
        c.function(fname, lines)
        c.button(caption, '真实持续行为', fname)

    if c.sid == 'rescue':
        fixture('climb', '亲船上岸', 'shore', parent, selector('kid'))
        fixture('self', '落水自救', 'self', 'kid', selector('kid'))
        fixture('braid', '亲油救援', 'braid', parent, selector('kid'))
    elif c.sid == 'boatlife':
        fixture('sail', '备粮划桨', 'sail', parent, selector(parent))
        fixture('shore', '返岸收帽', 'shore', parent, selector(parent))
        fixture('low', '两幼体低载荷', 'low', parent, f'@e[tag=room_{c.sid},tag=pm_low_load]')
        # Actor setup tags are applied at invocation, after the cast has spawned.
        c.functions[f'{c.sid}_low'][1:1] = [f'tag {selector(f"young{i}")} add pm_low_load' for i in range(2)]
        fixture('high', '四幼体高载荷', 'high', parent, f'@e[tag=room_{c.sid},type=#mcylab:yukkuri,tag=!{c.tag(parent)}]')
    elif c.sid == 'voyage':
        fixture('first', '真实第一次出航', 'voyage', parent, selector('kid'))
    c.function(f'{c.sid}_safe_stop', [f'function mcylab:stop/{c.sid}'])
    c.button('安全回岸重置', '未安全上岸会保留现场', f'{c.sid}_safe_stop')
    c.label(5.5, 5, 2.8, '真实船、真实幼体与库存：请等待它们自主行动', .5)
