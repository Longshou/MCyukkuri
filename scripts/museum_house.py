"""An isolated real house-yukkuri family, with finite loose resources and a real nest."""
from museum_dsl import _text


def configure(c):
    if c.sid != 'house':
        return
    c.actors.clear(); c.demos.clear(); c.buttons.clear(); c.props.clear(); c.functions.clear()
    x, z = -120, 300
    c.prop(f'forceload add {x-6} {z-6} {x+7} {z+6}')
    c.prop(f'fill {x-6} 65 {z-6} {x+7} 65 {z+6} minecraft:grass_block')
    c.prop(f'fill {x-2} 66 {z+1} {x+2} 67 {z+1} minecraft:oak_planks')
    c.prop(f'fill {x-2} 66 {z-1} {x-2} 67 {z+1} minecraft:oak_planks')
    c.prop(f'fill {x-2} 68 {z-1} {x+2} 68 {z+1} minecraft:oak_planks')
    c.prop(f'setblock {x-1} 66 {z} mcyukkuri:house_dust_nest{{Materials:0,Food:0,Arrivals:2,Entrance:"north"}}')
    for key, kind, px in [('court_marisa', 'house_marisa', x-.7), ('court_reimu', 'house_reimu', x+.5)]:
        c.actor(key, kind, 1, 1, 'B', '家油庭院双亲')
        c.actors[key].update(x=px, y=66, z=z-1.5, installable=True)
    actors = '@e[tag=room_house,type=#mcylab:yukkuri]'
    setup = ['function mcylab:install_new_actors',
             f'yukkuri museum live {actors} house']
    for key in c.actors:
        setup.append(f'yukkuri museum home @e[tag={c.tag(key)},limit=1] {x-1} 66 {z}')
    for item, count in [('nest_lint', 6), ('food_scrap', 12)]:
        setup.append(f'execute unless score #house_stock museum matches 1 run summon minecraft:item {x+2.5} 66.05 {z-2.5} '
                     f'{{Tags:["pm_house_court_supply"],PickupDelay:0s,Item:{{id:"mcyukkuri:{item}",count:{count}}}}}')
    setup += ['scoreboard players set #house_stock museum 1',
              'tellraw @a ' + _text('两只家油会真实拾取六份棉絮和十二份食屑。筑巢、入窝、产卵和孵化均按普通规则继续，按钮不预造卵。', 'gold')]
    c.function('house_begin', setup)
    c.function('house_visit', [f'forceload add {x-6} {z-6} {x+7} {z+6}',
                               f'tp @p {x+3.5} 66 {z-3.5} 35 15',
                               'schedule function mcylab:stand/house_begin 20t replace'])
    c.button('家油生态庭院', '真实拾取、筑巢和巢卵', 'house_visit')
    c.function('house_observe', [f'tp @p {x+1.5} 66 {z-3.5} 20 20'])
    c.button('继续观察巢卵', '保留同一巢及其真实库存', 'house_observe')
    c.label(5, 3, 1.5, '前往独立家油庭院：六份棉絮、十二份食屑；等待真实筑巢、产卵、孵化。', .3)
