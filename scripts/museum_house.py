"""An isolated real house-yukkuri family, with finite loose resources and a real nest.

The yard is far to the south-west; the visitor is teleported to a raised deck outside its fence, with
buttons to go back to the station or the lobby, and a reset that replaces the family and nest.
"""
from museum_dsl import _text
import museum_sites

NS = 'mcylab'
X, Z = -120, 300


def configure(c):
    if c.sid != 'house':
        return
    c.actors.clear(); c.demos.clear(); c.buttons.clear(); c.props.clear(); c.functions.clear()
    c.kind = 'portal'
    c.batch = False
    c.yard_cast = True
    c.item('mcyukkuri:nest_lint', 3, 3, 1.0, .9)
    c.item('mcyukkuri:food_scrap', 5.5, 3, 1.0, .9)
    c.item('mcyukkuri:house_egg', 8, 3, 1.0, .9)
    x, z = X, Z
    x1, x2, z1, z2 = x - 8, x + 9, z - 8, z + 8
    near = f'@a[x={x},y=66,z={z},distance=..40]'
    c.regions.append((x1 - 2, z1 - 2, x2 + 8, z2 + 2))
    c.keep_loaded.append((x1, z1, x2 + 6, z2))     # nest building and hatching take long: the yard stays loaded
    c.stop_before.append(f'schedule clear {NS}:stand/house_begin')
    c.prop(f'forceload add {x1} {z1} {x2 + 6} {z2}')
    c.prop(f'fill {x1} 64 {z1} {x2 + 3} 64 {z2} minecraft:dirt')
    c.prop(f'fill {x1} 65 {z1} {x2} 65 {z2} minecraft:grass_block')
    c.prop(f'fill {x1 + 1} 66 {z1 + 1} {x2 - 1} 70 {z2 - 1} minecraft:air')
    ring = [(px, pz) for px in range(x1, x2 + 1) for pz in range(z1, z2 + 1) if px in (x1, x2) or pz in (z1, z2)]
    c.props.extend(museum_sites.fence_ring(ring, 66))
    c.prop(f'fill {x - 2} 66 {z + 1} {x + 2} 67 {z + 1} minecraft:oak_planks')
    c.prop(f'fill {x - 2} 66 {z - 1} {x - 2} 67 {z + 1} minecraft:oak_planks')
    c.prop(f'fill {x - 2} 68 {z - 1} {x + 2} 68 {z + 1} minecraft:oak_planks')
    nest = f'setblock {x - 1} 66 {z} mcyukkuri:house_dust_nest{{Materials:0,Food:0,Arrivals:2,Entrance:"north"}}'
    c.prop(nest)
    for key, kind, px in [('court_marisa', 'house_marisa', x - .7), ('court_reimu', 'house_reimu', x + .5)]:
        c.actor(key, kind, 1, 1, 'B', '家油庭院双亲')
        c.actors[key].update(x=px, y=66, z=z - 1.5, installable=True)
    # deck outside the east fence
    deck_lines, land = museum_sites.deck(x2 + 1, z - 4, x2 + 3, z + 4, 68, 'west', 'house deck')
    c.props.extend(deck_lines)
    c.prop(museum_sites.display(x, 71, z, [('家油庭院\n', 'gold', True), ('棉絮 · 食屑 · 巢 · 卵', 'white')], .9, 'banner', 'house yard'))

    actors = '@e[tag=room_house,type=#mcylab:yukkuri]'
    setup = [f'function {NS}:install_new_actors',
             f'yukkuri museum live {actors} house']
    for key in c.actors:
        setup.append(f'yukkuri museum home @e[tag={c.tag(key)},limit=1] {x - 1} 66 {z}')
    for item, count in [('nest_lint', 6), ('food_scrap', 12)]:
        setup.append(f'execute unless score #house_stock museum matches 1 run summon minecraft:item {x + 2.5} 66.05 {z - 2.5} '
                     f'{{Tags:["pm_house_court_supply"],PickupDelay:0s,Item:{{id:"mcyukkuri:{item}",count:{count}}}}}')
    setup += ['scoreboard players set #house_stock museum 1',
              f'tellraw {near} ' + _text('两只家油会真实拾取六份棉絮和十二份食屑；筑巢、入窝、产卵和孵化按普通规则继续，按钮不预造卵。', 'gold')]
    c.function('house_begin', setup)
    c.function('house_visit', [f'forceload add {x1} {z1} {x2 + 6} {z2}',
                               f'tp @s {land[0]} {land[1]} {land[2]} {land[3]} 15',
                               'tellraw @s ' + _text('家油庭院观察台：看两只家油拾絮、筑巢。回展台、回大厅、重置的按钮在台上。', 'aqua'),
                               f'schedule function {NS}:stand/house_begin 20t replace'])
    c.button('家油庭院', '去观察台开始', 'house_visit', nav=True)
    c.sites.append(dict(id='house', caption='家油庭院', hint='去观察台开始', land=land, visit='house_visit', release=None, button_done=True))
    c.function('house_gift', ['give @p mcyukkuri:food_scrap 8', 'give @p mcyukkuri:nest_lint 6',
                              f'tellraw {near} ' + _text('按Q把食屑或棉絮丢在家油附近：它们会走近、用嘴叼起，再搬回巢里。棉絮只在垫料未成时需要，食屑到巢里藏食满24份为止。', 'gold')])
    c.button('领食屑与棉絮', '手动丢在地上', 'house_gift')
    # Reset: end this yard's own family, nest and left-overs, then start a fresh pair and nest.
    box = f'x={x1},y=64,z={z1},dx={x2 - x1},dy=10,dz={z2 - z1}'
    c.function('house_renew', [f'schedule clear {NS}:stand/house_begin',
                               f'kill @e[type=#mcylab:yukkuri,{box}]', f'kill @e[type=minecraft:item,{box}]',
                               nest.replace('setblock', 'setblock', 1) + ' replace',
                               'scoreboard players set #house_stock museum 0',
                               f'scoreboard players set #actor_house_court_marisa museum 0', f'scoreboard players set #actor_house_court_reimu museum 0',
                               f'function {NS}:install_new_actors',
                               f'tellraw {near} ' + _text('家油庭院已重置：新的一对家油和空巢。回展台按“家油庭院”再开始。', 'aqua')])
    c.props += museum_sites.deck_controls(c, x2 + 2, 68, z, [
        (f'function {NS}:stand/house_gift', '领食屑棉絮', '自己丢地上'),
        (f'function {NS}:stand/house_renew', '重置家油庭院', '换新的一对和空巢')])
