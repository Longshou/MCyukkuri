"""Explicit art selections for all 1.21.1 damage IDs; not a runtime damage router."""
import html

# Shared appearances are intentional: a source ID does not imply a unique wound.
# Each conditional choice retains the information needed to select it honestly.
ROUTES = {
 'player_attack': ('玩家近战', [('刀刃命中', 'tear'), ('空手／钝器命中', 'dent')]),
 'mob_attack': ('生物近战', [('已确认的狼咬等咬击', 'bite'), ('实际持刃攻击', 'tear'), ('实际钝击', 'dent')]),
 'mob_attack_no_aggro': ('不激怒的生物近战', [('已确认的咬击', 'bite'), ('实际持刃攻击', 'tear'), ('实际钝击', 'dent')]),
 'arrow': ('箭矢', [('单次箭头刺入', 'puncture')]),
 'trident': ('三叉戟', [('实际单次实体命中，不虚构三次接触', 'puncture')]),
 'cactus': ('仙人掌', [('接触范围内的细刺痕', 'thorn')]),
 'sweet_berry_bush': ('甜浆果丛', [('移动穿过枝刺造成的擦损', 'scratch')]),
 'sting': ('蜂蜇', [('小刺口；后续毒素不重复造伤', 'puncture')]),
 'stalagmite': ('落到石笋', [('足底方向的尖锥穿压', 'impale')]),
 'falling_stalactite': ('落下的滴水石锥', [('来自上方的尖锥穿压', 'impale')]),
 'fall': ('摔落', [('落地实际接触面压陷', 'dent')]),
 'fly_into_wall': ('高速撞墙', [('碰撞面压陷', 'dent')]),
 'falling_block': ('落方块', [('方块实际接触面压陷', 'dent')]),
 'falling_anvil': ('落铁砧', [('实际承压面压陷', 'dent')]),
 'cramming': ('实体挤压', [('拥挤压迫；位置需真实接触上下文', 'dent')]),
 'explosion': ('爆炸', [('真实爆炸冲击', 'blast')]),
 'player_explosion': ('玩家引发的爆炸', [('真实爆炸冲击', 'blast')]),
 'fireworks': ('烟花爆炸', [('真实爆炸伤害，不自动添烧伤', 'blast')]),
 'bad_respawn_point': ('无效重生点爆炸', [('床／重生锚爆炸', 'blast')]),
 'hot_floor': ('热地板', [('足部接触', 'sole')]),
 'campfire': ('营火', [('足部接触', 'sole')]),
 'in_fire': ('处于火中', [('实际热损', 'whole_body')]),
 'on_fire': ('持续着火', [('实际持续热损', 'whole_body')]),
 'lava': ('岩浆', [('实际热损', 'whole_body')]),
 'fireball': ('火球命中', [('热损；后续爆炸另按真实事件', 'whole_body')]),
 'unattributed_fireball': ('无归属火球', [('实际热损', 'whole_body')]),
 'lightning_bolt': ('雷击', [('电击本身不推导实体破口', None), ('实际引燃并产生热损', 'whole_body')]),
 'mob_projectile': ('生物投射物', [('如潜影弹的接触冲击；不伪造箭孔', 'dent')]),
 'spit': ('羊驼唾沫', [('无尖硬物，不凭扣血制造机械缺口', None)]),
 'wind_charge': ('风弹', [('实际压力冲击', 'dent')]),
 'wither_skull': ('凋灵之首', [('直接撞击', 'dent'), ('另一次真实爆炸事件', 'blast'), ('后续凋零', None)]),
 'thrown': ('抛掷物', [('原版蛋／非烈焰人雪球为零伤害；其他需具体证据', None)]),
 'in_wall': ('墙内窒息', [('窒息不指示局部破口', None)]),
 'drown': ('溺水', [('不增加机械伤口', None)]),
 'starve': ('饥饿', [('营养损耗不增加机械伤口', None)]),
 'magic': ('魔法／毒素', [('按效果状态处理，无可证实的接触破口', None)]),
 'indirect_magic': ('间接魔法', [('按具体效果状态处理', None)]),
 'wither': ('凋零', [('状态损耗，不凭空挖洞', None)]),
 'dragon_breath': ('龙息', [('魔法伤害，不按火焰或刀刃处理', None)]),
 'dry_out': ('脱水', [('状态损耗，不当烧伤', None)]),
 'freeze': ('冰冻', [('冻伤状态，不当烧伤或切伤', None)]),
 'sonic_boom': ('音波', [('非接触音波，不虚构裂口', None)]),
 'thorns': ('荆棘附魔反伤', [('附魔反伤，无实体尖刺接触', None)]),
 'out_of_world': ('虚空', [('无局部伤口依据', None)]),
 'outside_border': ('世界边界', [('无局部伤口依据', None)]),
 'generic_kill': ('直接清除', [('系统处理，无局部伤口依据', None)]),
 'generic': ('泛用来源', [('缺少具体伤因时不猜；模组来源须单独登记', None)]),
}


def build_coverage(registry, styles):
    ids=[value for group in registry['groups'] for value in group['damage_types']]
    if len(ids)!=47 or len(set(ids))!=47 or set(ids)!=set(ROUTES):
        raise ValueError('Minecraft source coverage mismatch')
    rows=[]
    for ident,(label,choices) in ROUTES.items():
        row=dict(damage_type=ident,label=label,levels=[])
        for grade in [1,2,3]:
            entries=[]
            for condition,style in choices:
                entry=dict(condition=condition,style=style,external_injury=style is not None)
                if style in styles:
                    entry['decal']=f'{style}-{grade}.png'
                    entry['mesh_templates']=[f'{model}-{site}-{style}-{grade}-normal.json.gz' for model,site in [('reimu','hair'),('marisa','hair'),('reimu','skin')]]
                    entry['placement']='Use actual hit/contact coordinates and direction; templates demonstrate appearance only.'
                elif style in ['sole','whole_body']:
                    entry['decal']=f'thermal-{grade}.png'
                    entry['thermal_samples']=[f'{model}-{style}-{grade}' for model in ['reimu','marisa']]
                elif style is None:
                    entry['surface']='keep_existing_surface'
                    entry['reason']='This cause does not establish a mechanical opening. State effects remain separate; no blank injury object is created.'
                else:raise ValueError(style)
                entries.append(entry)
            row['levels'].append(dict(grade=grade,choices=entries))
        rows.append(row)
    return dict(format='mcyukkuri.authored-source-coverage.v1',runtime_selected=False,source_count=47,level_count=141,
                grading='Project injury levels, not fixed Minecraft damage amounts; use accepted loss and actual mechanism.',sources=rows)


def preview_table(coverage,labels):
    rows=[]
    for item in coverage['sources']:
        cells=[]
        for level in item['levels']:
            variants=[]
            for choice in level['choices']:
                style=choice['style'];text=html.escape(choice['condition'])
                if style is None:variants.append(f'<p>{text}<br><small>不新增外部伤口；保留原表面</small></p>')
                else:
                    label=labels.get(style,{'sole':'足部烧伤','whole_body':'全身烧伤'}.get(style,style))
                    variants.append(f'<figure><img loading="lazy" src="sources/{style}-{level["grade"]}.png"><figcaption>{html.escape(label)} · {text}</figcaption></figure>')
            cells.append('<td>'+''.join(variants)+'</td>')
        rows.append(f'<tr><th>{html.escape(item["label"])}<br><code>{item["damage_type"]}</code></th>'+''.join(cells)+'</tr>')
    return '<section id="source-coverage"><h2>MC 1.21.1：47 种来源 × 3 级</h2><p>同一外形可以复用。条件分支只在真实事件成立时选用；不叠加虚构伤害。无外部破口依据的来源在三级中均明确不新增伤口，状态演出不属于伤口贴图。</p><table><thead><tr><th>来源</th><th>1级</th><th>2级</th><th>3级</th></tr></thead><tbody>'+''.join(rows)+'</tbody></table></section>'
