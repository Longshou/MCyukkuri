# ====================================================================================================== new since the last round (not yet played)
NEW = '待验证：新场景，尚未在游戏里实测。'
REMAINS_NEAR = '@e[type=mcyukkuri:yukkuri_remains,distance=..48,limit=1,sort=nearest]'
HARSH = 15                     # Low friendliness; fear and recognition remain separate.


# ---- injuries and remains (open ground: nothing to eat, nothing to hide behind)
add('body_mold', 'body', '油霉1／3／6／8级与去霉药', '油霉1／3／6／8级与去霉药',
    '四只灵梦分别为油霉 1／3／6／8 级，对应四档灰绿菌落；一级的认得油霉，惊叫后发抖，三级的初次遇到只摇身喊痒。先绕行看侧背面，再用去霉药核对真实阶段回退及原位菌落缩小。终末个体会自然进入濒死，完整皮面不应凭中枢损伤冒出创洞',
    '立即看外观；终末约 20 秒后濒死', [
        *[yk('reimu', -6 + 4 * i, 0, f'mold{i}', lead=i == 0, label=f'油霉{level}',
             uuid=uid('body_mold', f'mold{i}'), skills={'mold_lore': 2} if i == 0 else None,
             extra='YukkuriSkills:{Assessed:["mold_lore"]}' if i == 1 else '', **pair_ready())
          for i, level in enumerate((1, 3, 6, 8))],
        *[set_status(f'mold{i}', 'health.mold', level) for i, level in enumerate((1, 3, 6, 8))]],
    give=['mcyukkuri:mold_remover 8'], env=DAY, vantage=(0, 10),
    reads=[(f'读油霉{level}', f'yukkuri status {sel(f"mold{i}")} raw') for i, level in enumerate((1, 3, 6, 8))])

add('airborne', 'body', '抛起时普通油惊叫，飞行种不叫', '抛起时普通油惊叫，飞行种不叫',
    '左侧灵梦对照高抛与小跳，右侧蕾米莉亚和弗兰同样高抛；石墙隔开视线，避免捕食威胁打断普通组。普通高抛只感叹一次，小跳与两种原生会飞捕食种不说飞起感叹；落地后仍按普通规则活动',
    '生成后约 2–6 秒', [
        box(-10, -1, -4, 12, -1, 4, 'minecraft:hay_block'),
        box(1,0,-28,1,5,28,'minecraft:stone'),
        yk('reimu', -7, 0, 'high', label='普通·高抛', lead=True, **pair_ready()),
        yk('reimu', -3, 0, 'low', label='普通·小跳', **pair_ready()),
        yk('remilia', 5, 0, 'native_remilia', label='原生飞行·蕾米莉亚', **pair_ready()),
        yk('flandre', 10, 0, 'native_flandre', label='原生飞行·弗兰', **pair_ready())],
    late=[f'data modify entity {sel("high")} Motion set value [0.0d,0.9d,0.0d]',
          f'data modify entity {sel("low")} Motion set value [0.0d,0.25d,0.0d]',
          *[f'data modify entity {sel(name)} Motion set value [0.0d,0.9d,0.0d]' for name in ('native_remilia', 'native_flandre')]],
    env=DAY, vantage=(0, 14), note=NEW,
    reads=[(label, f'yukkuri status {sel(name)} raw') for name,label in
           (('high','读普通高抛'),('low','读普通小跳'),('native_remilia','读蕾米莉亚'),('native_flandre','读弗兰'))])

add('house_health', 'body', '家油赤油、子油、成体有多脆', '家油赤油、子油、成体有多脆',
    '三只家油分别处于赤油、子油和成体阶段，旁边是普通成体灵梦。先读各自血量，再空手攻击家油，观察幼体的脆弱程度；家油受击不显示伤口，仍会真实扣血、死亡并留下遗体',
    '读数后自行操作', [
        yk('house_reimu', -6, 0, 'house_baby', stage='baby', label='家油赤油', **pair_ready()),
        yk('house_reimu', -2, 0, 'house_child', stage='child', label='家油子油', **pair_ready()),
        yk('house_reimu', 2, 0, 'house_adult', label='家油成体', lead=True, **pair_ready()),
        yk('reimu', 6, 0, 'ordinary', label='普通成体', **pair_ready())],
    env=DAY, vantage=(0, 10), note=NEW,
    reads=[(title, f'yukkuri status {sel(name)}') for title, name in (
        ('读家油赤油', 'house_baby'), ('读家油子油', 'house_child'), ('读家油成体', 'house_adult'), ('读普通成体', 'ordinary'))])

_parts = (('头顶','top'),('正面','front'),('背面','back'),('侧面','side'),('底面','bottom'))
_wound_shapes = (('scratch','scratch','擦伤'),('tear','tear','刀割'),('dent','dent','钝击'),
                 ('puncture','tear','窄刺'),('thorn','scratch','细刺'),('impale','tear','尖锥'),
                 ('bite','tear','啃咬'),('blast','tear','爆裂'))
_wound_materials=[]
for i,(shape,kind,label) in enumerate(_wound_shapes):
    name=f'shape{i}'
    _wound_materials += [yk('reimu',-14+4*i,0,name,label=label+'1/2/3',nutrition=60,energy=95,lead=i==0,also=('wound_shapes',)),
        set_status(name,'marks.scars',0),
        *[set_status(name,'health.wound_at',f'{part}:{kind}:{grade}:{shape}')
          for part,grade in (('front',1),('side',2),('back',3))]]
for i,(species,extra,label) in enumerate((('reimu',{'congenital':'BEAN_BODY'},'豆形'),
        ('reimu',{'congenital':'SPARSE_HAIR'},'先天稀发'),('reimu',{'form':'WASA_REIMU'},'多毛后发'),
        ('marisa',{'aquatic':True},'水生与大帽'))):
    name=f'hair{i}'
    _wound_materials += [yk(species,-9+6*i,8,name,label=label,nutrition=60,energy=95,**extra),
        set_status(name,'marks.scars',0),set_status(name,'health.wound_at','top:dent:2:dent'),
        set_status(name,'health.wound_at','back:tear:3:bite')]
_wound_materials += [*[yk('reimu',-8+4*i,-8,f'part{i}',yaw=0,label=label,**pair_ready()) for i,(label,_) in enumerate(_parts)],
    *[set_status(f'part{i}','marks.scars',0) for i in range(5)],
    *[set_status(f'part{i}','health.wound_at',f'{part}:scratch:2:thorn') for i,(_,part) in enumerate(_parts)]]
add('wound_materials','body','伤口外观：八伤型、五部位与发面','伤口外观：八伤型、五部位与发面',
    '中排八种伤型每只正面一级、侧面二级、背面三级；北排五只分别在头顶、正面、背面、侧面、底面有二级细刺伤，比较锚点和动作贴合；南排豆形、先天稀发、多毛及水生大帽对照；绕行看皮面、发面和断发边，移动时看蒙皮；用橙汁闭伤后看同锚点留疤，再用面粉糊。三级裂口继续漏血，及时治疗。本场是外观初态，实际来源另看真实伤因场景',
    '先看后背三级，再看侧面与正面；可清场重开',_wound_materials,env=DAY,vantage=(0,15),
    give=['mcyukkuri:orange_juice 16','mcyukkuri:flour_paste 16','minecraft:spyglass'],
    reads=[('读八类伤型与锚点',f'execute as {sel_all("wound_shapes")} run yukkuri status @s raw')]
        + [(f'读{label}伤口',f'yukkuri status {sel(f"part{i}")} raw') for i,(label,_) in enumerate(_parts)]
        + [(f'读{label}',f'yukkuri status {sel(f"hair{i}")} raw') for i,label in enumerate(('豆形','先天稀发','多毛后发','水生大帽'))])
_thermal=[]
for i,(scope,grade) in enumerate((('sole',1),('sole',2),('sole',3),('whole',1),('whole',2),('whole',3))):
    name=f'thermal{i}'
    _thermal += [yk('reimu',-15+6*i,0,name,label=('足部' if scope=='sole' else '全身')+str(grade),nutrition=60,energy=95,lead=i==0),
        set_status(name,'marks.scars',0),set_status(name,'health.wound_at',f'{"bottom" if scope=="sole" else "front"}:scorch:{grade}:{scope}')]
for i,scope in enumerate(('sole','whole')):
    name=f'house_heat{i}'
    _thermal += [yk('house_reimu',-6+6*i,8,name,label='家油'+('足部' if i==0 else '全身'),nutrition=60,energy=95),
        set_status(name,'health.wound_at',f'{"bottom" if scope=="sole" else "front"}:scorch:1:{scope}')]
_thermal += [yk('reimu',6,8,'thermal_control',label='健康对照',nutrition=60,energy=95)]
add('thermal_body','body','烧伤外观：足底与全身1–3级','烧伤外观：足底与全身1–3级',
    '前排足部1/2/3级与全身1/2/3级，后排家油两种范围及健康对照。家油伤级初态为1但外观直接三级；读thermal_state区分真实等级和显示等级。全身仅横向干瘦，保持高度与深度，头发五官一起变化、头饰保尺寸；足烧只足底，不露核。用橙汁观察恢复与原位疤',
    '立即绕行；真实受火另用伤因场景',_thermal,env=DAY,vantage=(0,15),
    give=['mcyukkuri:orange_juice 12','mcyukkuri:flour_paste 12'],
    reads=[(f'读热损{i+1}',f'yukkuri status {sel(f"thermal{i}")} raw') for i in range(6)]
        + [('读家油足烧',f'yukkuri status {sel("house_heat0")} raw'),('读家油全身',f'yukkuri status {sel("house_heat1")} raw')])

_source_cases=(('cut',-12,0,'木剑刀割'),('blunt',-6,0,'空手钝击'),('arrow',0,0,'箭与三叉戟'),
               ('thorn',6,0,'仙人掌细刺'),('cone',12,0,'滴水石尖锥'),('blast',-12,10,'真实爆炸'),
               ('heat',-6,10,'火烧与热地板'),('friction',0,10,'真实滚拖摩擦'))
add('wound_sources','body','用真实武器和环境造成伤','用真实武器和环境造成伤',
    '目标从健康无伤开始，玩家用有限工具真实命中，读实际扣血、wound_sites/anchors的shape与伤级；弓箭和三叉戟用真实投射物，仙人掌/石笋按实际接触，爆炸用TNT。右后玻璃围区有饥饿捕食种和猎物，普通AI自行追咬，观察bite。热地板只足烧，着火整体焦枯；滚拖从空手潜行右键的照料菜单操作。一次MC命中不虚构逐齿逐刺落点',
    '先看右后追咬，再逐类手动操作。针和钩贴在各自刺出的伤口。扎针后走开超过 5 格等 20 秒再回来，看针仍在原处。双手空着右键个体拔针，听脱针声并看生存模式的原针从伤处落下，创造模式可在物品栏找到原针。在台上再点一次松绑。离开固定台使用吊钩需要顶板。剪发和剪饰物看头部掉落并听剪声。清场恢复地形与实体。',[
        *[yk('reimu',dx,dz,name,label=label,nutrition=90,energy=95,lead=i==0) for i,(name,dx,dz,label) in enumerate(_source_cases)],
        box(10,-1,7,20,5,17,'minecraft:glass','hollow'),
        yk('reimu',15,11,'bite_target',label='真实追咬猎物',nutrition=70,energy=95)],
    late=[yk('remilia',15,15,'bite_hunter',label='饥饿捕食种',nutrition=10,energy=95)],
    env=DAY,vantage=(0,17),give=['minecraft:wooden_sword','minecraft:stick','minecraft:bow','minecraft:arrow 16',
        'minecraft:trident','minecraft:sand 4','minecraft:cactus 4','minecraft:pointed_dripstone 4','minecraft:tnt 2','minecraft:redstone_torch 2',
        'minecraft:flint_and_steel','minecraft:magma_block 4','mcyukkuri:orange_juice 12',
        'mcyukkuri:pin_tool','mcyukkuri:hanging_hook','minecraft:shears','mcyukkuri:iron_enclosure','mcyukkuri:care_awl',
        'mcyukkuri:restraint_frame','mcyukkuri:yukkuri_carrier'],
    reads=[(f'读{label}',f'yukkuri status {sel(name)} raw') for name,_,_,label in _source_cases]
        + [('读真实咬伤',f'yukkuri status {sel("bite_target")} raw'),('读捕食交接',f'yukkuri status {sel("bite_hunter")} raw')])

_punishment_scene = 'punishment'
_punishment_targets = (('a3_freeze', 0, '胆小·僵住', 30, 57, 49),
                       ('a3_plead', 2, '惊慌·哭求', 25, 30, 83),
                       ('a3_defy', -2, '胆大·顶嘴', 80, 68, 0))
_punishment_press = [
    f'damage {sel(name)} 1 minecraft:player_attack by @s'
    for name, *_ in _punishment_targets
]
_punishment_click = json.dumps({'text': '[各打一下／再次施罚]', 'color': 'gold',
                               'clickEvent': {'action': 'run_command', 'value': '/function mcylab:extra/punishment_hit'}},
                              ensure_ascii=False)
add(_punishment_scene, 'body', '制裁反馈：顶嘴、僵住、哭求', '制裁反馈：顶嘴、僵住、哭求',
    '向前走上中央石台，点击聊天里的“各打一下／再次施罚”，或走到各格前亲手用锥子。三只同受一击，看初次回嘴、噤声和哭求；后排隔一层玻璃的三只缩脸、僵住或转向亲属，隔两层的对照看不见。',
    '等约两秒后再点同一按钮，看刚才还顶嘴的个体对同一玩家改为戒备；清场重开才重置关系', [
        box(-3, 0, -2, 3, 0, -2, 'minecraft:glass'),
        block(0, 1, -2, 'minecraft:glass'),
        box(-3, 0, -1, 3, 1, -1, 'minecraft:glass'),
        block(0, 2, -1, 'minecraft:polished_andesite_slab[type=bottom]'),
        *[box(x, 0, 0, x, 0, 4, 'minecraft:polished_andesite') for x in (-3, -1, 1, 3)],
        *[box(x, 1, 0, x, 1, 4, 'minecraft:glass') for x in (-3, -1, 1, 3)],
        *[box(x, 0, z, x, 1, z, 'minecraft:glass_pane[east=true,west=true]') for x in (-2, 0, 2) for z in (1, 3)],
        box(0, 0, 5, 0, 1, 5, 'minecraft:glass'),
        *[yk('reimu', x, 0, name, label=label, boldness=bold, self_control=control, friendliness=15 if x == -2 else 65,
             kin=kin_of(Mother=uid(_punishment_scene, 'a3_kin')) if x == -2 else '',
             lead=x == 0, uuid=uid(_punishment_scene, name))
          for name, x, label, bold, control, _ in _punishment_targets],
        *own('a3_plead'),
        yk('reimu', 2, 2, 'a3_shrink', label='旁观·缩脸', boldness=25, self_control=30, uuid=uid(_punishment_scene, 'a3_shrink')),
        yk('reimu', 0, 2, 'a3_still', label='旁观·噤声', boldness=45, self_control=80, uuid=uid(_punishment_scene, 'a3_still')),
        yk('reimu', -2, 2, 'a3_kin', label='旁观·看向孩子', boldness=30, attachment=80, uuid=uid(_punishment_scene, 'a3_kin')),
        yk('reimu', 0, 4, 'a3_screened', label='两层玻璃·对照', uuid=uid(_punishment_scene, 'a3_screened')),
        *[set_status(name, 'mood.unease', unease) for name, _, _, _, _, unease in _punishment_targets],
        set_status('a3_plead', 'health.health', 8),
        f'tellraw @s {_punishment_click}'],
    env=DAY, vantage=(0, -2, 0, 38), give=['mcyukkuri:care_awl', 'mcyukkuri:orange_juice 3'],
    reads=[(label, f'yukkuri status {sel(name)} raw') for name, _, label, *_ in _punishment_targets]
          + [('读哭求宠物的服从养成值', f'data get entity {sel("a3_plead")} YukkuriPet.Obedience')])

_leak_subjects = ('v0', 'v1', 'v2', 'v3', 'v4')
_leak_actors = [rf'uuid={_uuid_text("wound_leak", name)} [^\n]*' for name in (*_leak_subjects, 'healthy_grazer')]
add('wound_leak', 'body', '伤口漏馅，橙汁止漏', '伤口漏馅，橙汁止漏',
    '从２级背部刺伤侧后方看馅粒外落、泪眼和喊痛。橙汁闭伤补血止漏。另有轻伤、大裂口、多处大裂口和低血露核对照；右侧无伤灵梦照常吃草',
    '先等 10 秒读血量，再用橙汁；随后再等 10 秒确认止漏', [
        *[yk('reimu', -8 + 4 * i, 0, name, yaw=180, lead=i == 1, label=label, nutrition=60, energy=95,
             uuid=uid('wound_leak', name)) for i, (name, label) in
          enumerate(zip(_leak_subjects, ('轻伤', '二级背部刺伤', '三级大伤口', '多处大伤口', '低血露核')))],
        *[set_status(f'v{i}', 'marks.scars', 0) for i in range(5)],
        *[set_status(f'v{i}', 'health.wound_at', f'front:tear:{min(3, i + 1)}') for i in (0, 2, 3, 4)],
        set_status('v1', 'health.wound_at', 'back:tear:2:puncture'),
        set_status('v3', 'health.wound_at', 'back:tear:3'), set_status('v4','health.health',3),
        block(12, -1, 7, 'minecraft:grass_block'), block(12, 0, 7, 'minecraft:short_grass'),
        yk('reimu', 11, 7, 'healthy_grazer', label='无伤吃草', nutrition=75, picky=0, uuid=uid('wound_leak', 'healthy_grazer'))],
    give=['mcyukkuri:flour_paste 5', 'mcyukkuri:orange_juice 5'], note=NEW, env=DAY, vantage=(0, 10),
    reads=[('读二级裂口', f'yukkuri status {sel("v1")}'), ('读三级裂口', f'yukkuri status {sel("v2")}'),
           ('读多处裂口', f'yukkuri status {sel("v3")}'), ('读漏馅露核',f'yukkuri status {sel("v4")} raw')],
    smoke=dict(ticks=480, commands=[
        *[(270, f'yukkuri scenario care_click {sel(name)} orange_juice') for name in ('v1', 'v4')],
        *[(460, f'execute if entity @e[tag={tag(name)},nbt={{Health:10.0f}},distance=..48] run say MCY_LEAK_FULL_HEALTH_{name}')
          for name in ('v1', 'v4', 'healthy_grazer')],
    ], expect=[
        rf'\[body\][^\n]*{_leak_actors[1]} wound leak outlets=1 health=10\.0 -> 9\.5 loss=0\.5',
        rf'\[speech\][^\n]*{_leak_actors[1]} cue event=wounded spoken=true',
        rf'\[body\][^\n]*{_leak_actors[2]} wound leak outlets=1 health=9\.0 -> 8\.0 loss=1\.0',
        rf'\[body\][^\n]*{_leak_actors[3]} wound leak outlets=2 health=10\.0 -> 9\.0 loss=1\.0',
        rf'\[body\][^\n]*{_leak_actors[4]} wound leak outlets=1 health=3\.0 -> 2\.0 loss=1\.0',
        *[rf'\[body\][^\n]*{_leak_actors[i]} orange juice cleared 1 wound\(s\)' for i in (1, 4)],
        rf'\[meal\][^\n]*{_leak_actors[5]} swallowed item=minecraft:short_grass count=1 nutrition=75\.0 -> 95\.0',
        *[f'MCY_LEAK_FULL_HEALTH_{name}' for name in ('v1', 'v4', 'healthy_grazer')],
    ], forbid=[rf'\[body\][^\n]*{_leak_actors[i]} wound leak ' for i in (0, 5)]))

add('treatment_severe', 'body', '重伤不自愈，橙汁治好留疤', '重伤不自愈，橙汁治好留疤',
    '四类三级伤、旧包扎裂伤、烤足共六只：自然等 40 秒三级伤仍在，旧包扎仍漏血。橙汁一剂清伤补满血，三级伤在原处必留疤，烤足也恢复；满血有伤照样能治。先读 wound_anchors，治后对照 scar_sites，面粉糊只去疤',
    '先读初值；可推进 800 tick，再逐只用橙汁核对', [
        *[yk('reimu', -10 + 4 * i, 0, f'v{i}', label=label, lead=i == 0, also=(f'treatment_pair{i // 2}',), **pair_ready(),
             extra='YukkuriBody:{Bandage:1200}' if i == 4 else '')
          for i, label in enumerate(('裂伤3·低血', '凹伤3·满血', '擦伤3·低血', '烧伤3·满血', '旧包扎', '烤足'))],
        *[set_status(f'v{i}', 'marks.scars', 0) for i in range(6)],
        *[set_status(f'v{i}', 'health.wound_at', f'side:{kind}:3')
          for i, kind in enumerate(('tear', 'dent', 'scratch', 'scorch', 'tear'))],
        set_status('v0', 'health.health', 6), set_status('v2', 'health.health', 6),
        set_status('v5', 'health.wound_at', 'bottom:scorch:3')],
    env=DAY, give=['mcyukkuri:flour_paste 6', 'mcyukkuri:orange_juice 8'],
    vantage=(0, 12), reads=[(f'读{label}', f'execute as {sel_all(f"treatment_pair{i}")} run yukkuri status @s raw')
                           for i, label in enumerate(('裂伤与凹伤', '擦伤与烧伤', '旧包扎与烤足'))]
                          + [('读旧包扎值', f'data get entity {sel("v4")} YukkuriBody.Bandage')])

add('treatment_scars', 'body', '伤口自愈留疤，面粉糊去疤', '伤口自愈留疤，面粉糊去疤',
    '北排五只：前两只都有疤 1、三级凹伤、血 6：左先面粉糊，只去疤不改伤血；右先橙汁，清伤补满血、保留旧疤并新增三级伤原位疤，再交换用药。第三只二级裂伤概率留疤；第四只健康无疤，两药都无效；第五只有油霉2级与轻伤，橙汁可治伤但油霉等级不变。'
    '南排是干净、脏污、疤痕外观对照和一只一级裂伤：衣食充足时自然闭合，再按原伤级概率留疤；面粉糊只去已有疤痕，不治未闭合伤口',
    '先读数再用药；自然愈合约 40 秒。核对扣料请切生存，创造模式不耗物品', [
        yk('reimu', -6, 0, 'paste_first', label='先去疤', lead=True, nutrition=60, energy=100),
        yk('reimu', -2, 0, 'orange_first', label='先治伤', nutrition=60, energy=100),
        *[command for name in ('paste_first', 'orange_first') for command in (
            set_status(name, 'marks.scars', 0), set_status(name, 'marks.scars', 1), set_status(name, 'health.wound_at', 'side:dent:3'),
            set_status(name, 'health.health', 6))],
        yk('reimu', 2, 0, 'healing', label='伤后留疤', **pair_ready()),
        set_status('healing', 'marks.scars', 0), set_status('healing', 'health.wound_at', 'front:tear:2'),
        yk('reimu', 6, 0, 'healthy', label='无伤无疤', **pair_ready()),
        set_status('healthy', 'marks.scars', 0),
        yk('reimu', 10, 0, 'moldy', label='油霉对照', **pair_ready()),
        set_status('moldy', 'health.mold', 2), set_status('moldy', 'health.wound_at', 'side:dent:1'),
        yk('reimu', -6, 6, 'clean', label='干净'), yk('reimu', -2, 6, 'dirty', label='脏污'), yk('reimu', 2, 6, 'scarred', label='疤痕'),
        yk('reimu', 6, 6, 'light_tear', label='一级裂伤', **pair_ready()),
        *[set_status(name, 'marks.scars', 0) for name in ('clean', 'dirty', 'scarred', 'light_tear')],
        set_status('dirty', 'marks.dirt', 3), set_status('scarred', 'marks.scars', 1), set_status('light_tear', 'health.wound_at', 'front:tear:1')],
    env=DAY, give=['mcyukkuri:flour_paste 8', 'mcyukkuri:orange_juice 8'], vantage=(0, 14),
    reads=[(caption, f'yukkuri status {sel(name)} raw') for caption, name in (
        ('读先去疤', 'paste_first'), ('读先治伤', 'orange_first'), ('读伤后留疤', 'healing'), ('读无效对照', 'healthy'), ('读油霉对照', 'moldy'),
        ('读一级伤留疤', 'light_tear'), ('读预置疤痕', 'scarred'))])

add('treatment_care', 'body', '照料员用橙汁、面粉糊治伤', '照料员用橙汁、面粉糊治伤',
    '街上居民把伤重的流浪油圈在街边两格的矮围栏里（2×2，两只都在栏内，伤者不会再走开），自己蹲在旁边照料。伤者有三级裂口、血6、旧疤1。NPC手持橙汁约1秒、挥手交互，随后换面粉糊重复；清伤回血留疤后去疤。每剂先从库存预留到手上，成功消耗，未用完归还，最终余量8→6；可在举药时轻击伤者核对中断不误耗',
    '约 10–40 秒', [
        # A two-cell fence pen holds the wounded stray and its carer together: a street resident never follows a patient past the two-block care reach.
        box(-1, 0, -1, 2, 0, -1, 'minecraft:oak_fence'), box(-1, 0, 2, 2, 0, 2, 'minecraft:oak_fence'),
        box(-1, 0, -1, -1, 0, 2, 'minecraft:oak_fence'), box(2, 0, -1, 2, 0, 2, 'minecraft:oak_fence'),
        street_trial('treatment_care', 'reimu', 0, 0, 'patient', label='伤者', lead=True, **pair_ready()),
        set_status('patient', 'health.wound_at', 'front:tear:3'),
        set_status('patient', 'health.health', 6),
        set_status('patient', 'marks.scars', 0), set_status('patient', 'marks.scars', 1),
        human('caretaker_brother', 1, 1, 'carer', 0, label='照料员')],
    env=DAY, vantage=(0, 10), reads=[('读伤者', f'yukkuri status {sel("patient")} raw'),
                                    ('读医疗余量', f'data get entity {sel("carer")} Medicine')])

add('core_exposed', 'body', '深伤露出中枢，瞄准才打中', '深伤露出中枢，瞄准才打中',
    '前排依次是低血多处深伤、厚后发深伤、满血爆裂、低血小伤、低血完整皮面、低血窄刺、低血尖锥、低血烧伤；后排是头顶深伤。仅满足真实大缺损与低血通道者露出唯一中央中枢，窄刺/尖锥/烧伤不自动露核。绕到后发与头顶看连续创缘；玩家瞄准创口及其外缘一圈宽松范围即算命中中枢（头发、帽饰不挡），远离创口的旁侧只受普通伤，橙汁闭口并原位留疤',
    '先绕行看外观；再手动瞄准，清场后可重复', [
        yk('reimu', -14, 0, 'exposed', label='低血多处深伤', lead=True, **pair_ready()),
        set_status('exposed', 'health.wound_at', 'front:dent:3'), set_status('exposed', 'health.health', 3),
        set_status('exposed', 'health.wound_at', 'back:dent:3'),
        yk('marisa', -10, 0, 'struck', label='厚后发·绕背瞄准', **pair_ready()),
        set_status('struck', 'health.wound_at', 'back:dent:3'), set_status('struck', 'health.health', 3),
        yk('reimu', -6, 0, 'deep_full', label='满血爆裂·不露核', **pair_ready()),
        set_status('deep_full', 'health.wound_at', 'front:tear:3:blast'),
        yk('reimu', -2, 0, 'small', label='低血小裂口', **pair_ready()),
        set_status('small', 'health.wound_at', 'front:tear:2'), set_status('small', 'health.health', 3),
        yk('reimu', 2, 0, 'closed', label='低血完整皮面', **pair_ready()), set_status('closed', 'health.health', 3),
        *[command for name,dx,kind,shape,label in (('pointed',6,'tear','puncture','低血窄刺'),
                ('cone',10,'tear','impale','低血尖锥'),('burnt',14,'scorch','whole','低血烧伤'))
          for command in (yk('reimu',dx,0,name,label=label,**pair_ready()),
              set_status(name,'health.wound_at',f'front:{kind}:3:{shape}'),set_status(name,'health.health',3))],
        yk('reimu',0,7,'hairline',label='头顶深伤',**pair_ready()),
        set_status('hairline','health.wound_at','top:dent:3'),set_status('hairline','health.health',3)],
    give=['mcyukkuri:orange_juice 12','mcyukkuri:flour_paste 12'], note=NEW, vantage=(0, 16),
    reads=[(caption, f'yukkuri status {sel(name)} raw') for caption, name in (
        ('读中枢外露', 'exposed'), ('读厚后发', 'struck'), ('读满血深伤', 'deep_full'), ('读小裂口', 'small'), ('读完整皮面', 'closed'),
        ('读窄刺', 'pointed'), ('读尖锥', 'cone'), ('读烧伤', 'burnt'), ('读头顶深伤', 'hairline'))])

add('magma_sole', 'body', '踩岩浆块烫脚，跳着逃开', '踩岩浆块烫脚，跳着逃开',
    '左边位于半径4格岩浆块区中心，右边普通石头对照；不预置伤口。热地板伤害沿用玩家相同的原版判定与受伤冷却；每次实际受伤跳起，高度约为此前高跳的一半，并连续逃向安全地面。健康个体应活着离开；离开后橙汁治伤，核对脚底疤和烤足',
    '先观察数秒；离开过快可清场重开', [
        box(-13, -1, -4, -5, -1, 4, 'minecraft:magma_block'),
        box(3, -1, -2, 7, -1, 2, 'minecraft:stone'),
        yk('reimu', -9, 0, 'hot', label='半径4格岩浆块', lead=True, **pair_ready()),
        yk('reimu', 5, 0, 'cool', label='普通石头', **pair_ready()),
        set_status('hot', 'marks.scars', 0), set_status('cool', 'marks.scars', 0)],
    env=DAY, give=['mcyukkuri:orange_juice 4'], note=NEW, vantage=(0, 10),
    reads=[('读脚底灼伤', f'yukkuri status {sel("hot")} raw'), ('读普通地面对照', f'yukkuri status {sel("cool")} raw')])

add('learned_holder', 'body', '目击工具后躲开持用者', '目击工具后躲开持用者',
    '在个体面前使用转子，间隔十秒再让它看见一次。空手靠近目击者也会引起退避。封闭隔间里的对照可以走动，始终看不到工具接触。可把食物放在离持用者八格以外，观察极饿时恢复觅食。',
    '站在目标前使用转子，读旁观者的对象关系', [
        yk('reimu',0,0,'target',label='工具接触对象',lead=True),
        yk('reimu',2,1,'witness',label='看得见的旁观者',boldness=0,uuid=uid('learned_holder','witness')),
        box(-8,-1,-1,-4,3,3,'minecraft:stone','hollow'),
        block(-6,3,1,'minecraft:sea_lantern'),
        yk('reimu',-6,1,'screened',label='隔墙对照',boldness=0,uuid=uid('learned_holder','screened')),
    ], env=DAY,vantage=(0,8),give=['mcyukkuri:care_vibrator 2','minecraft:apple 4'],
    reads=[('读旁观者关系',f'yukkuri status {sel("witness")} raw'),('读隔墙对照',f'yukkuri status {sel("screened")} raw')])

_hook_scene = 'hook_kin_rescue'
add(_hook_scene, 'body', '亲属走近吊钩尝试救援', '亲属走近吊钩尝试救援',
    '在透明高顶下的孩子身上使用吊钩，然后留在五格内并给亲油让开通路。亲油按胆量、恐惧与友善决定是否上前。这处钩位偏高，观察亲油走到下方、抬头尝试、够不到时含泪退让。够到钩身才会松开钩子。',
    '用吊钩挂起孩子，观察亲油走近后如何尝试', [
        box(-8,3,-8,8,3,8,'minecraft:glass'),
        yk('reimu',0,0,'hook_child',label='孩子',lead=True,
           uuid=uid(_hook_scene,'hook_child'),kin=f'Mother:{uuid_nbt(uid(_hook_scene,"hook_parent"))}'),
        yk('reimu',2,0,'hook_parent',label='亲油',uuid=uid(_hook_scene,'hook_parent'),boldness=100,friendliness=100),
    ], env=DAY,vantage=(0,-8,0,20,0),give=['mcyukkuri:hanging_hook'],  # on the ground under the glass, not lifted onto it
    reads=[('读孩子状态',f'yukkuri status {sel("hook_child")} raw'),('读亲油状态',f'yukkuri status {sel("hook_parent")} raw')])

add('learned_fire', 'body', '烫伤后看见火会退开', '烫伤后看见火会退开',
    '左边先踩一小块岩浆块，右边从普通地面开始。等左边跳离后，在安全地面的边上点燃营火。比较两个个体会不会在接触营火前退开。',
    '等左边脱离热地板，再点燃旁边的营火', [
        block(-5,-1,0,'minecraft:magma_block'),
        block(-3,0,0,'minecraft:campfire[lit=false]'),
        block(5,0,0,'minecraft:campfire[lit=false]'),
        yk('reimu',-5,0,'burnt',label='先烫伤',lead=True,uuid=uid('learned_fire','burnt'),**pair_ready()),
        yk('reimu',7,0,'unburnt',label='未烫伤对照',uuid=uid('learned_fire','unburnt'),**pair_ready()),
    ],env=DAY,vantage=(0,8),give=['minecraft:flint_and_steel','mcyukkuri:orange_juice'],
    reads=[('读烫伤',f'yukkuri status {sel("burnt")} raw'),('读对照',f'yukkuri status {sel("unburnt")} raw')])

add('core_filling', 'body', '遗体取出中枢馅，伤者吃下', '遗体取出中枢馅，伤者吃下',
    '一只有正面深伤的灵梦身体耗尽（核心完好）留下空壳，2 秒后割出独立中枢馅，遗体创腔保留但不再画核心；旁边重伤又饿的魔理沙吃下它：血 +4、营养 +10、不安下降。普通食用量为零仍可取一次核心，搬起放下不能让核心重新出现',
    '约 10–30 秒', [
        yk('reimu', 0, 0, 'dead', **pair_ready()),
        set_status('dead', 'health.wound_at', 'front:dent:3'),
        yk('marisa', 5, 0, 'hurt', lead=True, nutrition=30, energy=95),
        set_status('hurt', 'health.wound_at', 'side:dent:3'), set_status('hurt', 'health.health', 4),
        f'damage {sel("dead")} 100 minecraft:generic'],
    late=[f'yukkuri body core {REMAINS_NEAR}'], note=NEW, vantage=(0, 10), reads=[('读受伤者', f'yukkuri status {sel("hurt")}')])

_rot = [uid('remains_rot', k) for k in ('fresh', 'stale', 'rotten')]
add('remains_rot', 'body', '遗体腐败发臭，旁人走开', '遗体腐败发臭，旁人走开',
    '三具灵梦遗体：新鲜／陈旧（偏平、霉斑）／腐坏（塌陷、冒烟），臭味半径 5／9／14 格；旁边吃饱的油闻到会说“臭”并走开、不安上涨。放着不管约 20 分钟变陈、60 分钟腐坏',
    '2–5 秒后走开', [
        remains(-16, 0, 'fresh', ident=_rot[0]), remains(0, 0, 'stale', ident=_rot[1], rot=24000), remains(16, 0, 'rotten', ident=_rot[2], rot=72000),
        yk('reimu', -14, 0, 'o1', lead=True, **pair_ready()), yk('marisa', 2, 0, 'o2', **pair_ready()), yk('chen', 18, 0, 'o3', **pair_ready())],
    note=NEW, vantage=(0, 14), reads=[('读闻臭者', f'yukkuri status {sel("o2")}')])

# Each victim dies of one real cause: a vanilla damage type through the ordinary hurt path, or the body's own internal cause
# (terminal mould, the end of its lifespan). The pool holds a drowning and two remains with a water clock already running; the dry
# control keeps the same clock on land, where it does not advance. A starved baby is already buried (the same burial as a shovel,
# by someone standing north of it) so its marker can be read at once; the shovel buries the others.
OLD_AGE_FAMILY = f'Version:2,Initialized:1b,Stage:"ADULT",Age:{1056000 - 200}L,Nourishment:{1056000 - 200}L,Maturity:336000L,Lifespan:1056000L,AdultScale:1f,Scale:1f'
_forms = [('old', -20, '寿终'), ('starve', -14, '饿死'), ('fire', -8, '烧死'), ('anvil', -2, '压死'), ('struck', 4, '打死'),
          ('frozen', 10, '冻死'), ('mold', 16, '油霉病死')]
_form_lines = [box(-8, -1, 7, 8, -1, 13, 'minecraft:water')]
for _name, _x, _label in _forms:
    _form_lines.append(yk('reimu', _x, 0, _name, label=_label, lead=_name == 'old', fam=OLD_AGE_FAMILY if _name == 'old' else None, **pair_ready()))
_form_lines += [
    f'damage {sel("starve")} 100 minecraft:starve', f'damage {sel("fire")} 100 minecraft:in_fire',
    f'damage {sel("anvil")} 100 minecraft:falling_anvil', f'damage {sel("struck")} 100 minecraft:mob_attack',
    f'damage {sel("frozen")} 100 minecraft:freeze', set_status('mold', 'health.mold', 8),
    yk('reimu', 0, 10, 'drowned', dy=-1, label='溺死', **pair_ready()), f'damage {sel("drowned")} 100 minecraft:drown',
    remains(-5, 10, 'soaking', dy=-1, soak=5400, cause='minecraft:drown', category='drowned', food=0),
    remains(5, 10, 'melting', dy=-1, soak=23400, cause='minecraft:mob_attack', category='violent'),
    remains(12, -10, 'dry_control', soak=23400, cause='minecraft:mob_attack', category='violent'),
    yk('reimu', -3, -7, 'buried_baby', stage='baby', **pair_ready()), f'damage {sel("buried_baby")} 100 minecraft:starve']
_buried_remains = '@e[type=mcyukkuri:yukkuri_remains,distance=..2,limit=1,sort=nearest]'
_buried_at = 'execute positioned ~-3 ~ ~-7'
_legacy_remains_path = 'Remains.components."minecraft:custom_data".FactoryOwner'
add('remains_forms', 'body', '八种死因遗体，溶化与立碑', '八种死因遗体，溶化与立碑',
    '七只灵梦各死于真实死因：寿命（约10秒）、饥饿、着火、落砧、近战击打、冻伤、终末油霉（约25秒内）；南边水池里一只溺死。'
    '遗体按存下的死因类别换死亡脸、姿态和表层：寿终凹缝眼灰白、饿死干瘪、烧死焦黑带灰、压死压扁带馅渍、打死歪倒垂舌且身下渗馅、冻死挺圆结霜、病死发黄起霉点、溺死白眼泡胀冷白滴水。'
    '水池另有两具已泡水的遗体：左边溺死遗体约30–60秒后泡软（更胀、发白斑）；右边被打死后落水、尚有6份余量，约30–60秒后溶化成沉底残渣，余量6→0、不能取核。'
    '浮在水面时只有实际浸水的 tick 计入水钟，漂起的瞬间不计，所以时间会比 30 秒长。'
    '岸上同样水钟的打死遗体不再前进，仍为泡软、余量6。用“读遗体”核对 category、water(soak)、food 和 core。'
    '观察点前方约 5 格偏右，一只饿死的赤油已安葬：小土冢头端立着小石碑，正面朝北对着你；右键土冢或石碑，像告示牌一样读出“无名之墓”、种类与年龄段、死因和安葬日。'
    '给了一把铁锹：在草地上右键其他遗体即可安葬，石碑正面朝向你，生前有名字（这里是头顶的标签）的写作“某某之墓”。退出重进后读到的仍是同一死者',
    '约 30–60 秒；泡软与溶化也可等真实浸水 5／20 分钟', _form_lines,
    give=['minecraft:iron_shovel'], note=NEW, vantage=(0, 12),
    late=[f'{_buried_at} run data modify entity {_buried_remains} {_legacy_remains_path} set value '
          + uuid_nbt(uid('remains_forms', 'legacy_factory')),
          f'{_buried_at} rotated 0 0 run yukkuri body bury {_buried_remains}'],
    reads=[('读遗体', 'yukkuri body info @e[type=mcyukkuri:yukkuri_remains,distance=..40,sort=nearest]')],
    smoke=dict(ticks=200, expect=[r'\[remains\][^\n]*legacy factory claim cleared',
                                 r'\[remains\][^\n]* buried by \S+ grave=[^\n]* facing=north [^\n]* stage=baby category=starved'],
               forbid=[r'\[body\][^\n]* invariant\b']),
    capture=dict(shots=(1, 30, 60, 100, 200, 400), sequences=()))

_carrion_lines = []
for _label, _x, _z, _friend, _control, _bold in (
        ('guard', -18, 0, 95, 95, 95), ('eat', 18, 0, 10, 10, 15), ('refuse', 0, -20, 95, 95, 10)):
    _dead = uid('carrion', _label)
    _carrion_lines += [
        remains(_x, _z, 'body_' + _label, ident=_dead, food=6),
        yk('reimu', _x + 5, _z, 'starving_' + _label, label='饿油', nutrition=2, energy=95,
           friendliness=10, self_control=10, boldness=50, lead=_label == 'guard'),
        yk('chen', _x + 1, _z - 2, 'mate_' + _label, label={'guard':'大胆护尸','eat':'食尸倾向','refuse':'胆小噗咕护尸'}[_label],
           nutrition=2, energy=95, friendliness=_friend, self_control=_control, boldness=_bold,
           kin=f'Partner:{uuid_nbt(_dead)}')]
add('carrion', 'body', '饿极：护尸、食尸或拒食', '饿极：护尸、食尸或拒食',
    '三组同样饥饿，各有有限遗体和食尸者。伴侣只改友善、自制、胆量：左大胆护尸走近攻击，右较愿食尸，北边胆小拒食但在尸边噗咕。读decision、score、last和实际Goal；攻击须真实接触，目标停止食尸后短暂观察再结束，吃空留壳、搬运不补资源',
    '约5–20秒，长期饥饿和伤势可能改变选择', _carrion_lines, note=NEW, vantage=(0, 14),
    reads=[(f'读{role}·{label}', f'yukkuri status {sel(role + "_" + label)} raw')
           for label in ('guard','eat','refuse') for role in ('mate','starving')])

_hunt_lines = []
for _label, _x, _friend, _control in (('willing', -16, 10, 10), ('reluctant', 16, 95, 95)):
    _hunt_lines += [
        yk('reimu', _x, 0, 'hunter_' + _label, label='低克制' if _label == 'willing' else '高克制',
           uuid=uid('cannibal_hunt', 'hunter_' + _label), yaw=-90, nutrition=8, energy=95,
           friendliness=_friend, self_control=_control, boldness=60, lead=_label == 'willing'),
        yk('marisa', _x + 2, 0, 'prey_' + _label, accessory=False, boldness=40, energy=0, nutrition=100,
           uuid=uid('cannibal_hunt', 'prey_' + _label))]
add('cannibal_hunt', 'body', '饿油捕食没头饰的同类', '饿油捕食没头饰的同类',
    '两只灵梦的营养都是8，各自面前有一只正因疲倦入睡的失饰魔理沙。左边的灵梦友善和自制都低，饥饿压过鄙视，会追咬取食；魔理沙惊醒后朝远离它的方向跑。右边的灵梦友善和自制都高，会放弃猎食。读猎食判分、伤口和营养，吃下咬出的馅料后营养才会上升',
    '约10–60秒；饥饿继续加深时意愿也会改变', _hunt_lines, note=NEW, vantage=(0, 12),
    reads=[(f'读{role}·{label}', f'yukkuri status {sel(role + "_" + label)} raw')
           for label in ('willing','reluctant') for role in ('hunter','prey')],
    smoke=dict(ticks=900, expect=[
        rf'\[cannibal\][^\n]*uuid={_uuid_text("cannibal_hunt", "hunter_reluctant")} [^\n]* decision=hunt_unwilling target={_uuid_text("cannibal_hunt", "prey_reluctant")}\b',
        rf'\[threat\][^\n]*uuid={_uuid_text("cannibal_hunt", "prey_willing")} [^\n]* struck stance=FLEE\b',
        rf'\[threat\][^\n]*uuid={_uuid_text("cannibal_hunt", "prey_willing")} [^\n]* flight away stand=',
    ], forbid=[r'\[body\][^\n]* invariant\b',
               rf'\[cannibal\][^\n]*uuid={_uuid_text("cannibal_hunt", "hunter_reluctant")} [^\n]* started mode=HUNT\b'],
    verify_trace=True))


def _verify_cannibal_hunt(window, trace):
    prey = _uuid_text('cannibal_hunt', 'prey_willing')
    hunter = _uuid_text('cannibal_hunt', 'hunter_willing')
    handoff = re.search(rf'\[cannibal\][^\n]*uuid={hunter} [^\n]* hunt meal handed off prey={prey}\b', window)
    if not handoff:
        return 'The willing hunter took no meal from its prey'
    after_handoff = window[handoff.end():]
    swallow = re.search(rf'\[meal\][^\n]*uuid={hunter} [^\n]* swallowed bodyFood portions=[\d.]+ odor=\d+ nutrition=([\d.]+) -> ([\d.]+)',
                        after_handoff)
    if not swallow or float(swallow[2]) <= float(swallow[1]):
        return 'The willing hunter did not gain nutrition by swallowing its meal after the handoff'
    before_swallow = window[:handoff.end() + swallow.start()]
    if re.search(rf'\[sanction\][^\n]*uuid={prey} [^\n]* case opened reason=hatless [^\n]* accuser={hunter}\b', before_swallow):
        return 'The willing hunter opened a hatless sanction before eating its prey meal'
    hit = re.search(rf'\[threat\][^\n]*uuid={prey} t=(\d+)[^\n]* struck stance=FLEE attacker={hunter}\b', window)
    if not hit:
        return 'The willing hunter never made its prey flee'
    tick = int(hit[1])
    positions = {}
    running = False
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and tick <= row['t'] <= tick + 104 and row['u'] in (prey, hunter):
            state = row['st']
            positions.setdefault(row['t'], {})[row['u']] = state['p']
            if row['u'] == prey and state['behavior'] == 'flee' and state['animation'] == 'run':
                running = True
    distances = [math.dist(bodies[prey], bodies[hunter]) for bodies in positions.values() if len(bodies) == 2]
    if not running or not distances or max(distances) <= distances[0] + 1:
        return 'Struck prey must show the run clip and gain ground on its attacker'


next(s for s in S if s.id == 'cannibal_hunt').smoke['verify'] = _verify_cannibal_hunt



_lw = {k: uid('last_words', k) for k in ('dying', 'mate', 'kid')}
add('last_words', 'body', '濒死灵梦对家人留遗言', '濒死灵梦对家人留遗言',
    '约 6 秒后灵梦中枢受损、濒死 4 秒：先说临终台词，再对伴侣和孩子说遗言；亲属含泪围拢；死后孩子得到“家人去世”（不安 +30）',
    '约 10 秒（6 秒后触发，等亲缘登记）', [
        yk('reimu', 0, 0, 'dying', uuid=_lw['dying'], lead=True, kin=kin_of(Partner=_lw['mate']), **pair_ready()),
        yk('marisa', 3, 1, 'mate', uuid=_lw['mate'], kin=kin_of(Partner=_lw['dying']), **pair_ready()),
        yk('reimu', -3, 1, 'kid', stage='child', uuid=_lw['kid'], nutrition=95, kin=kin_of(Mother=_lw['dying'], Father=_lw['mate']))],
    late=[Retry(set_status('dying', 'health.core', 'damage'), sel('dying'), tries=1, first=80)],
    note=NEW, vantage=(0, 10), reads=[('读孩子', f'yukkuri status {sel("kid")}')])

_offering_lines = []
for _label, _x, _friend, _nutrition, _health in (
        ('desperate', -20, 95, 0, 4), ('reluctant', 0, 30, 0, 4), ('ordinary', 20, 95, 8, 10)):
    _parent, _child = uid('offering', _label + '_parent'), uid('offering', _label + '_child')
    _offering_lines += [
        yk('reimu', _x, 0, 'parent_' + _label, uuid=_parent, label=_label, lead=_label == 'desperate',
           nutrition=12, energy=95, friendliness=_friend, boldness=70),
        yk('reimu', _x + 3, 1, 'child_' + _label, stage='child', uuid=_child,
           nutrition=_nutrition, energy=95, kin=kin_of(Mother=_parent)),
        set_status('child_' + _label, 'health.health', _health)]
_offering_lines += [yk('reimu', -17, 4, 'other_child', stage='child', label='同亲另一只子油', nutrition=20, energy=95,
                       kin=kin_of(Mother=uid('offering','desperate_parent')))]
add('offering', 'body', '绝境亲油请孩子吃了自己', '绝境亲油请孩子吃了自己',
    '三组均无食物：左高友善亲油与濒饿低血孩子，另有同亲子油可共享献身遗体；中间只降低亲油友善；右边孩子一般饥饿。连续六次检查绝境孩子才按概率决定，拒绝冷却60秒；投喂或真实照护可中止。自然献身后两子共扣有限余额，吞咽才加营养',
    '约30–35秒首次抽签；未中可重开，孩子可能因真实缺粮死亡', _offering_lines,
    give=['minecraft:apple 4'], note=NEW, vantage=(0, 12),
    reads=[(f'读{role}·{label}', f'yukkuri status {sel(role + "_" + label)} raw')
           for label in ('desperate','reluctant','ordinary') for role in ('parent','child')]
          + [('读同亲子油',f'yukkuri status {sel("other_child")} raw')])

# ---- predators: a parent drawing the chase, Youmu standing her ground
_guard_parent, _guard_child = uid('sacrifice', 'parent'), uid('sacrifice', 'child')
add('sacrifice', 'weather', '亲油引开捕食种保护孩子', '亲油引开捕食种保护孩子',
    '芙兰先选小灵梦，亲油决定帮助后靠近、噗咕挑衅，芙兰转怒追亲油，亲油再逃跑；读protection阶段与真实目标，子油按亲密度和友善度分支说话，保留真实拒绝及危险。转怒后为战斗态：俯冲命中1.5伤、两次命中至少隔2秒、不产生食物；亲油被杀后芙兰原地稳住约5秒；读predator的mode、rest。'
    '低意愿对照：生成后立刻点“亲油友善降到10”，仍有机会引开追击；读 behavior.protection 的 chance、decision，不能以一次仍追亲油判失败',
    '约 30 秒内；概率行为可重新生成对比', [
        yk('reimu', 0, 0, 'parent', uuid=_guard_parent, yaw=-90, lead=True, label='亲油', friendliness=90, boldness=60, attachment=60, **pair_ready()),
        yk('reimu', 3, 1, 'child', stage='child', uuid=_guard_child, yaw=-90, label='子油', nutrition=95, kin=kin_of(Mother=_guard_parent)),
        yk('flandre', 12, 0, 'pred', yaw=90, label='捕食者', nutrition=100, energy=100), set_status('pred', 'needs.nutrition', 10),
        # A child raised by her is close to her (the worry reaction needs affinity >= 40).
        f'yukkuri status relate {sel("child")} {sel("parent")} 80', f'yukkuri status relate {sel("parent")} {sel("child")} 80'],
    env=DAY, spot='pred', vantage=(6, 14), note=NEW,
    reads=[('读亲油反应', f'yukkuri status {sel("parent")} raw'), ('读护子追击', f'yukkuri status {sel("pred")} raw'),
           ('读被护子油', f'yukkuri status {sel("child")} raw'), ('亲油友善降到10（低意愿）', set_status('parent', 'personality.friendliness', 10))])

MYON = 'YukkuriMyon:{SpeechQuirk:"%s",Kanroken:%s,Initialized:1b}'
add('myon_brave', 'weather', '持棍妖梦迎战饥饿芙兰', '持棍妖梦迎战饥饿芙兰',
    '持棍妖梦接触且有视线才打：1.5伤、间隔1.6秒。胆小的个体避战，退战比例=0.65−0.0055×胆量；读myon_combat与predator（芙兰受击转战斗态，俯冲命中1.5伤、间隔不少于2秒，拉开时妖梦够不着，战斗不增加其饱食）。旁边灵梦照常逃，可用按钮调整双方胆量后重观；战斗到死亡不应卡死',
    '约 30 秒', [
        yk('myon', 0, 0, 'myon', yaw=-90, lead=True, extra=MYON % ('miyo', '1b'), **pair_ready()),
        yk('reimu', 0, 3, 'a', yaw=-90, **pair_ready()),
        yk('flandre', 12, 0, 'pred', yaw=90, nutrition=100, energy=100), set_status('pred', 'needs.nutrition', 10)],
    env=DAY, spot='pred', vantage=(6, 14), note=NEW, reads=[('读妖梦', f'yukkuri status {sel("myon")} raw'), ('读捕食反击', f'yukkuri status {sel("pred")} raw'),
        ('妖梦胆量20',set_status('myon','personality.boldness',20)),('妖梦胆量90',set_status('myon','personality.boldness',90)),
        ('捕食种胆量20',set_status('pred','personality.boldness',20)),('捕食种胆量90',set_status('pred','personality.boldness',90))])


# ---- headwear and recognition
def hat(damage, mend=0):
    return f'YukkuriHeadwear:{{Damage:{damage},Mend:{mend}}}'


add('hat_damage', 'headwear', '头饰破了，安心时自己长好', '头饰破了，安心时自己长好',
    '四只灵梦：完好／轻损（毛边小缺口）／撕裂（缺一大块）／撕裂但一直不安。安心时头饰一级级自己长好，每升一级开心说台词；不安的那只不自愈',
    '轻损→完好、撕裂→轻损各约 2.5 分钟（可倍速）', [
        yk('reimu', -5, 0, 'ok', yaw=180, lead=True, label='完好', **pair_ready()),
        yk('reimu', -2, 0, 'frayed', yaw=180, label='轻损', extra=hat(1), **pair_ready()),
        yk('reimu', 1, 0, 'torn', yaw=180, label='撕裂', extra=hat(2, 6000), **pair_ready()),
        yk('reimu', 4, 0, 'stuck', yaw=180, label='不安', extra=hat(2), **pair_ready()),
        set_status('stuck', 'mood.unease', 70)],
    env=DAY, note=NEW, vantage=(0, 8), reads=[('读撕裂头饰', f'yukkuri status {sel("torn")} raw')])

add('hat_shatter', 'headwear', '头饰碎成几块，自己舔回拼好', '头饰碎成几块，自己舔回拼好',
    '2 秒后灵梦的头饰碎成 2–3 块落在身边（哭）：它自己走过去一块块舔起，凑齐后头上是拼起来的碎饰，约 5 分钟长合成撕裂的头饰，再慢慢修好。你也可以捡碎片右键还给它',
    '拾碎片 10–40 秒；拼合约 5 分钟（可倍速）', [
        yk('reimu', 0, 0, 'owner', yaw=180, lead=True, **pair_ready())],
    env=DAY, late=[set_status('owner', 'accessory.state', 'shatter')], note=NEW, vantage=(0, 8),
    reads=[('读头饰', f'yukkuri status {sel("owner")} raw')])

add('hatless_kin', 'headwear', '失饰子油：笨亲油认不出', '失饰子油：笨亲油认不出',
    '两位亲油都凶悍、胆大且低自制，北边智力 40、南边 80。前者认不出失饰子油，鄙视后可能发起欺负和驱赶；后者仍认亲。读 known、last、accessory.sanction 与孩子位置，亲缘不豁免识别',
    '首次鄙视约 10–30 秒；自然升级有概率，可观察 1–3 分钟', [
        yk('reimu', 0, -5, 'mom_blind', yaw=90, label='凶悍亲油·智力40', intelligence=40, friendliness=0, boldness=95, self_control=0, **pair_ready()),
        yk('chen', 3, -5, 'kid_blind', stage='child', nutrition=90, energy=95, yaw=-90, lead=True, accessory=False),
        f'yukkuri scenario kin {sel("mom_blind")} {sel("kid_blind")} false',
        yk('reimu', 0, 5, 'mom_sees', yaw=90, label='凶悍亲油·智力80', intelligence=80, friendliness=0, boldness=95, self_control=0, **pair_ready()),
        yk('chen', 3, 5, 'kid_sees', stage='child', nutrition=90, energy=95, yaw=-90, accessory=False),
        f'yukkuri scenario kin {sel("mom_sees")} {sel("kid_sees")} false'],
    env=DAY, note=NEW, vantage=(-8, 0, -90), reads=[('读低智力亲油', f'yukkuri status {sel("mom_blind")} raw'), ('读高智力亲油', f'yukkuri status {sel("mom_sees")} raw'), ('读被驱赶孩子', f'yukkuri status {sel("kid_blind")} raw')])

add('hatless_scorn', 'headwear', '没头饰：被鄙视、被凶的欺负', '没头饰：被鄙视、被凶的欺负',
    '围栏里没戴头饰的灵梦（胆大、友善、健康）：智力普通的旁观者认不出它，按性格与轮次依次初见蔑视、鼓腮嘲弄、转身冷斥或向旁人低声议论，'
    '多轮后转入厌倦冷待；它首轮含泪，反复被嘲时鼓腮回嘴，凶悍成体升级发起欺负。高智力的橙（90）认得出，不鄙视也不躲。观察双方血量、实际 Goal、轮次事件及反应读数',
    '首轮鄙视约 10–30 秒出现，之后每 30–40 秒演进；欺负自然升级有概率，约 1–3 分钟', [
        # The pen keeps wandering actors in sight and leaves ground for a retreat.
        *(block(x, 0, z, 'minecraft:oak_fence') for x in range(-3, 4) for z in range(-3, 4) if abs(x) == 3 or abs(z) == 3),
        box(-1, 0, -5, 1, 0, -5, 'minecraft:oak_planks'),
        # Friendly to bystanders, still bold enough to answer back and defend itself after a hit.
        yk('reimu', 0, 0, 'bare', uuid=uid('hatless_scorn', 'bare'), yaw=-90, lead=True, accessory=False, label='胆大失饰者',
           friendliness=70, boldness=95, self_control=15, **pair_ready()),
        yk('alice', 2, -2, 'proud', uuid=uid('hatless_scorn', 'proud'), yaw=90, label='骄傲旁观者',
           intelligence=65, friendliness=50, boldness=65, self_control=70, **pair_ready()),
        yk('reimu', 2, 2, 'friendly', uuid=uid('hatless_scorn', 'friendly'), yaw=90, label='友善旁观者',
           intelligence=45, friendliness=80, boldness=50, self_control=60, **pair_ready()),
        yk('chen', -2, -2, 'timid', uuid=uid('hatless_scorn', 'timid'), yaw=-90, label='胆小旁观者',
           intelligence=45, friendliness=55, boldness=15, self_control=55, **pair_ready()),
        yk('marisa', 2, 0, 'b1', uuid=uid('hatless_scorn', 'b1'), yaw=90, label='凶悍欺负者', friendliness=0, boldness=80, self_control=0, **pair_ready()),
        yk('chen', -2, 1, 'c', yaw=-90, label='高智力', **pair_ready()), set_status('c', 'personality.intelligence', 90)],
    env=DAY, note=NEW, vantage=(0, -5, 0, 23),
    reads=[('读失饰者（含欺凌伤口）', f'yukkuri status {sel("bare")} raw'), ('读欺负者', f'yukkuri status {sel("b1")} raw')])

add('hatless_monster', 'headwear', '失饰重伤才像怪物，普通伤不算', '失饰重伤才像怪物，普通伤不算',
    '左北失饰灵梦有三处面部二级凹伤，左南只有一处背部三级严重凹伤（凹伤不漏馅，主角撑得过观察时间），两者均符合怪物阈值。读 face_wounds、severe_wounds、grotesque 与观察者 contacts。'
    '右边远处的失饰灵梦前后各一处二级伤且低血量：面部不足三伤，也没有三级严重伤，仍是普通鄙视，读 face_wounds=1、severe_wounds=0、grotesque=false；低血量本身不构成怪物',
    '约 10–60 秒；每个观察者每 30–40 秒重复', [
        yk('reimu', 0, -5, 'victim', yaw=-90, lead=True, accessory=False, label='面部三伤', **pair_ready()),
        set_status('victim', 'health.wound_at', 'front:dent:2'),
        set_status('victim', 'health.wound_at', 'front:dent:2'),
        set_status('victim', 'health.wound_at', 'front:dent:2'),
        yk('marisa', 3, -5, 'timid', yaw=90, intelligence=40, **pair_ready()),
        yk('reimu', 0, 5, 'severe', yaw=-90, accessory=False, label='背部严重伤', **pair_ready()),
        set_status('severe', 'health.wound_at', 'back:dent:3'),
        yk('marisa', 3, 5, 'severe_observer', yaw=90, intelligence=40, **pair_ready()),
        yk('reimu', 20, 0, 'single', yaw=-90, accessory=False, label='两处普通伤', **pair_ready()),
        set_status('single', 'health.wound_at', 'front:dent:2'),
        set_status('single', 'health.wound_at', 'back:dent:2'), set_status('single', 'health.health', 3),
        yk('chen', 23, 0, 'observer', yaw=90, intelligence=40, **pair_ready())],
    env=DAY, note=NEW, vantage=(10, 16, 180),
    reads=[('读面部三伤', f'yukkuri status {sel("victim")} raw'), ('读单处严重伤', f'yukkuri status {sel("severe")} raw'),
           ('读左北反应', f'yukkuri status {sel("timid")} raw'), ('读左南反应', f'yukkuri status {sel("severe_observer")} raw'),
           ('读两处普通伤', f'yukkuri status {sel("single")} raw'), ('读普通鄙视', f'yukkuri status {sel("observer")} raw')])

# Two pens with a hedge between them, so that neither pair sees or reaches the other: the timid child has the long pen to run in.
_puff_pens = [
    box(-13, 0, -4, 9, 0, -4, 'minecraft:oak_fence[east=true,west=true]'),
    box(-13, 0, 4, 9, 0, 4, 'minecraft:oak_fence[east=true,west=true]'),
    box(-13, 0, -3, -13, 0, 3, 'minecraft:oak_fence[north=true,south=true]'),
    box(9, 0, -3, 9, 0, 3, 'minecraft:oak_fence[north=true,south=true]'),
    box(0, 0, -4, 0, 1, 4, 'minecraft:oak_leaves[persistent=true]'),
]
_puff_pairs = []
for _i, (_x, _label, _stats) in enumerate((
        (-5, '胆小子油', dict(stage='child', friendliness=(30, 70), boldness=(5, 30), self_control=60)),
        (3, '成体', dict(friendliness=(0, 95), boldness=(50, 95), self_control=(0, 60))))):
    _puff_pairs += [
        yk('reimu', _x, 0, f'puff_target{_i}', uuid=uid('puff_response', f'puff_target{_i}'), label=_label, accessory=False,
           yaw=-90, nutrition=90, energy=95, lead=_i == 0, **_stats),
        yk('marisa', _x + 3, 0, f'puff_source{_i}', label='凶悍的制裁者', yaw=90,
           intelligence=30, friendliness=0, boldness=95, self_control=0, **pair_ready())]
add('puff_response', 'headwear', '被噗咕：逃、无视、回嘴或反击', '被噗咕：逃、无视、回嘴或反击',
    '两个围栏各有一只没了头饰的灵梦和一只凶悍的魔理沙，中间隔着树篱。魔理沙认出失饰者后鼓脸宣告制裁。'
    '左栏的胆小子油体型小，多半转身逃到围栏另一头；右栏成体的友善与胆量每次重开都不同，会无视、鼓脸回嘴或冲上去反击。',
    '约 1–3 分钟；魔理沙没宣告时重开', _puff_pens + _puff_pairs,
    env=DAY, vantage=(-2, 13, 180, 22),
    reads=[(f'读{label}的回应', f'yukkuri status {sel(f"puff_target{i}")} raw') for i, label in enumerate(('子油', '成体'))])
# ---- species traits and rare forms
add('species_fatigue', 'species', '妖梦、灵梦、帕琪体力消耗对照', '妖梦、灵梦、帕琪体力消耗对照',
    '妖梦、灵梦和帕秋莉均成年、营养100、精力95，各有两份真实苹果并用玻璃隔开。按 species.fatigue 的 steps 对照代谢 cost：同为20次时应为18／20／25；睡眠会暂停清醒代谢，其他活动额外耗能不计入cost，不要求同一秒达到同次数',
    '默认间隔下约3分钟，精力耗尽前读20次；可用「实用」页的推进', [
        box(-3, 0, -4, -3, 2, 4, 'minecraft:glass'), box(3, 0, -4, 3, 2, 4, 'minecraft:glass'),
        *[command for species,dx,name,label in (('myon',-7,'myon','妖梦·0.9'),('reimu',0,'neutral','灵梦·1'),('patchouli',7,'frail','帕秋莉·1.25'))
          for command in (yk(species,dx,0,name,label=label,nutrition=100,energy=95,lead=name=='myon'),item('minecraft:apple',2,dx,2))]],
    env=DAY, spot='race', vantage=(0, 12),
    reads=[(label,f'yukkuri status {sel(name)} raw') for name,label in
           (('myon','读妖梦代谢'),('neutral','读中性代谢'),('frail','读体弱代谢'))])

add('myon_stick', 'species', '妖梦捡木棍或折树枝当剑', '妖梦捡木棍或折树枝当剑',
    '两只还没有楼观剑的妖梦：左边脚下有根木棍，马上捡起来当楼观剑（开心台词）；右边只有一棵树，过一阵从树上折一根（平均约 20 秒）。两只口癖不同',
    '立即／约 20 秒', [
        yk('myon', -4, 0, 'm1', yaw=180, lead=True, extra=MYON % ('miyo', '0b'), **pair_ready()), item('minecraft:stick', 1, -4, 0, name='stick'),
        yk('myon', 4, 0, 'm2', yaw=180, extra=MYON % ('kinbao', '0b'), **pair_ready()),
        box(6, 0, 1, 6, 3, 1, 'minecraft:oak_log'), box(5, 3, 0, 7, 4, 2, 'minecraft:oak_leaves[persistent=true]', 'keep')],
    env=DAY, spot='race', note=NEW, vantage=(0, 10), reads=[('读折枝妖梦', f'yukkuri status {sel("m2")} raw')])

add('rare_forms', 'species', '稀有形态：长尾橙、红眼妖梦等', '稀有形态：长尾橙、红眼妖梦等',
    '从左到右：普通橙、长尾橙、圆尾橙；普通妖梦、红眼妖梦；普通魔理沙、暖桌魔理沙。长尾、圆尾只是外观；红眼妖梦被聚落成员看见是凶兆；暖桌魔理沙的冬夏见右边两个场景',
    '随意', [
        yk('chen', -9, 0, 'chen', yaw=180, lead=True, label='普通橙', **pair_ready()),
        yk('chen', -6, 0, 'longtail', yaw=180, form='LONGTAIL_CHEN', label='长尾橙', **pair_ready()),
        yk('chen', -3, 0, 'roundtail', yaw=180, form='ROUNDTAIL_CHEN', label='圆尾橙', **pair_ready()),
        yk('myon', 0, 0, 'myon', yaw=180, label='妖梦', **pair_ready()),
        yk('myon', 3, 0, 'redeye', yaw=180, form='REDEYE_MYON', label='红眼妖梦', **pair_ready()),
        yk('marisa', 6, 0, 'marisa', yaw=180, label='魔理沙', **pair_ready()),
        yk('marisa', 9, 0, 'kotatsu', yaw=180, form='KOTATSU_MARISA', label='暖桌魔理沙', **pair_ready())],
    env=DAY, spot='lineup', note=NEW, vantage=(0, 8), reads=[('读红眼妖梦', f'yukkuri status {sel("redeye")} raw')])


def kotatsu(sid, title, hint, biome, watch, roofed=False):
    lines = [f'fillbiome ~-12 56 ~-12 ~12 100 ~12 {biome}']
    if roofed:
        lines += [*shed(-4, 5), *shed(6, 5)]
    rest = 0 if roofed else 1200
    lines += [yk('marisa', 0, 0, 'kotatsu', yaw=180, lead=True, form='KOTATSU_MARISA', label='暖桌魔理沙', rest_wait=rest, **pair_ready()),
              yk('marisa', 3, 0, 'plain', yaw=180, label='普通魔理沙', rest_wait=rest, **pair_ready())]
    if roofed:
        lines += [home('kotatsu', -4, 5), home('plain', 6, 5)]
    season = '夏' if roofed else '冬'   # the summer kotatsu sleeps under its shed
    add(sid, 'species', title, hint, watch, '约 1–3 分钟', lines, env=DAY, spot='kotatsu', note=NEW, vantage=(0, 12),
        reads=[(f'读暖桌（{season}）', f'yukkuri status {sel("kotatsu")} raw'), (f'读对照（{season}）', f'yukkuri status {sel("plain")} raw')])


kotatsu('kotatsu_winter', '暖桌魔理沙在雪原不怕冷', '暖桌魔理沙在雪原不怕冷', 'minecraft:snowy_plains',
        '雪原：普通魔理沙觉得冷（needs.temperature=cold），暖桌魔理沙不冷、也不进洞冬居（identity.kotatsu=winter_active），时不时说“暖和”')
kotatsu('kotatsu_summer', '暖桌魔理沙在沙漠躲热', '暖桌魔理沙在沙漠躲热', 'minecraft:desert',
        '沙漠：暖桌魔理沙嫌热，白天也回棚下睡（先避暑）；普通魔理沙照常活动，不另说话', roofed=True)

# A clear cold (autumn taiga) noon on the kotatsu plot with no heat source: the chilled one settles in its own sunny spot.
add('sunbathe', 'species', '寒冷晴天怕冷的魔理沙晒太阳', '寒冷晴天怕冷的魔理沙晒太阳',
    '寒冷的针叶林（秋）晴天正午、附近没有火和暖桌：普通魔理沙觉得冷（needs.temperature=cold），在阳光下停下、压扁闭眼、脸朝上晒太阳（behavior=sunbathe，日志 refuge route selected … sun=true），'
    '一轮晒完暖和了圆回来，约一分钟内不再找太阳；树荫下不算阳光，阴天、下雨和夜里也不晒；冬天雪原太冷，晒太阳不够，只能找真正的热源',
    '约 10–30 秒', [
        'fillbiome ~-12 56 ~-12 ~12 100 ~12 minecraft:taiga',
        box(-4, 0, -3, -4, 3, -3, 'minecraft:spruce_log'), box(-6, 4, -5, -2, 5, -1, 'minecraft:spruce_leaves[persistent=true]', 'keep'),
        yk('marisa', 0, 1, 'basker', yaw=180, lead=True, **pair_ready())],
    env=DAY, spot='kotatsu', note=NEW, vantage=(0, 8),
    reads=[('读晒太阳', f'yukkuri status {sel("basker")} raw')],
    capture=dict(shots=(1, 40, 200, 400, 600), sequences=((40, 600, 6),), follow='@e[tag=scn_basker,limit=1]', offset=(0, .9, 2.8)),
    smoke=dict(ticks=900, expect=[r'\[temperature\][^\n]* refuge route selected warming=true [^\n]*sun=true',
                                  r'\[temperature\][^\n]* refuge stopped warming=true sun=true [^\n]*restTicks=(?:1\d\d|[2-9]\d\d)'],
               forbid=[r'\[body\][^\n]* invariant\b']))

add('patchouli_vomit', 'species', '饿帕琪狼吞后呕吐', '饿帕琪狼吞后呕吐',
    '四只饿坏的帕琪（营养 10）面前各有苹果：饿极狼吞或两餐挨太近时，约四成概率把馅吐出来（苦脸、馅 -1、不安 +3）；同样饿的灵梦照常吃',
    '约 10–60 秒', [
        *[yk('patchouli', -4 + 2 * i, 0, f'p{i}', yaw=180, lead=i == 0, nutrition=10, energy=95) for i in range(4)],
        *[item('minecraft:apple', 3, -4 + 2 * i, 2, name=f'apple{i}') for i in range(4)],
        yk('reimu', 6, 0, 'reimu', yaw=180, nutrition=10, energy=95), item('minecraft:apple', 3, 6, 2, name='apple_r')],
    env=DAY, spot='patchouli', note=NEW, vantage=(0, 10), reads=[('读帕琪', f'yukkuri status {sel("p0")} raw')])

add('chen_speed', 'species', '橙和灵梦赛跑抢苹果', '橙和灵梦赛跑抢苹果',
    '同样饿的橙和灵梦离一个苹果一样远（约 7 格）：橙移动速度是 1.2 倍，先到；读数 species.move_speed 橙 0.216、灵梦 0.18',
    '约 10 秒，可再按一次', [
        yk('chen', -3, -3, 'chen', yaw=180, lead=True, nutrition=30, energy=95),
        yk('reimu', 3, -3, 'reimu', yaw=180, nutrition=30, energy=95),
        item('minecraft:apple', 1, 0, 3)],
    env=DAY, spot='race', note=NEW, vantage=(0, 10), reads=[('读橙速度', f'yukkuri status {sel("chen")} raw')])

add('marisa_sumo', 'species', '魔理沙相扑，总赢帕琪和橙', '魔理沙相扑，总赢帕琪和橙',
    '三对要好的成体。生成时立即按普通相扑规则确定首局，之后的靠近、推挤、胜负与冷却照常发生；魔理沙×帕琪、魔理沙×橙交手时，总是魔理沙把对方推出去（力气 1.1 对 0.8／0.75）',
    '首局立即；之后约 10–60 秒', [
        yk('marisa', -8, 0, 'm1', yaw=-90, lead=True, **pair_ready()), yk('marisa', -6, 0, 'm2', yaw=90, **pair_ready()),
        yk('marisa', -1, 0, 'm3', yaw=-90, **pair_ready()), yk('patchouli', 1, 0, 'pa', yaw=90, **pair_ready()),
        yk('marisa', 6, 0, 'm4', yaw=-90, **pair_ready()), yk('chen', 8, 0, 'ch', yaw=90, **pair_ready()),
        *[f'yukkuri scenario bond {sel(a)} {sel(b)}' for a, b in (('m1', 'm2'), ('m3', 'pa'), ('m4', 'ch'))],
        *[f'yukkuri scenario sumo {sel(a)} {sel(b)}' for a, b in (('m1', 'm2'), ('m3', 'pa'), ('m4', 'ch'))]],
    env=DAY, spot='sumo', note=NEW, vantage=(0, 10))

# Alice's housekeeping needs the forage field's burrow
_ac = {k: uid('alice_chores', k) for k in ('alice', 'reimu')}
add('alice_chores', 'forage', '爱丽丝和灵梦一起做家务', '爱丽丝和灵梦一起做家务',
    '同一个空洞里住着一对伴侣爱丽丝和灵梦（没有孩子，不外出）：两只都会割草、铺窝、捡垫料、清理污物；爱丽丝家务快 1.5 倍，读数 species.chores 涨得更快',
    '约 3 分钟对比', [
        yk('alice', 0, 3, 'alice', uuid=_ac['alice'], yaw=180, lead=True, kin=kin_of(Partner=_ac['reimu']), nutrition=90, energy=95),
        yk('reimu', 2, 3, 'reimu', uuid=_ac['reimu'], yaw=180, kin=kin_of(Partner=_ac['alice']), nutrition=90, energy=95)],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('alice', 'reimu')],
    env=DAY, spot='forage', vantage=(0, 16), note=NEW,
    reads=[('读爱丽丝家务', f'yukkuri status {sel("alice")} raw'), ('读灵梦家务', f'yukkuri status {sel("reimu")} raw')])

# ---- home declaration: one real opening, with an open-door control for the same caller.
def home_pane_result(window):
    protected = window.find('HOME_PANE_GRIEF_OFF_CHECK')
    if protected < 0 or 'start id=bash' in window[:protected]:
        return 'mobGriefing=false window missing or allowed a bash'
    if 'HOME_PANE_FULL_GLASS_INTACT' not in window:
        return 'full glass did not survive'
    hits = re.findall(r'\[action\][^\n]* hit id=bash block=([^\n]*?) hits=(\d+)', window)
    broken = re.findall(r'\[action\][^\n]* break id=bash block=([^\n]*?) hits=(\d+)', window)
    if len(broken) != 1:
        return f'expected one broken pane, observed {len(broken)}'
    if not hits or max(int(n) for _, n in hits) != int(broken[0][1]):
        return 'break was not preceded by its real strokes'
    if any(block != broken[0][0] for block, _ in hits):
        return 'strokes and break name different panes'
    if window.find('GATHER -> HOLD') < window.find('break id=bash'):
        return 'claim declared before opening its route'
    if re.search(r'\[action\][^\n]* start id=bash', window[window.find('break id=bash') + 1:]):
        return 'a second bash started after the one opening was enough'


# The glass door is one pane column set in iron bars of the same face: an adult's brim, side hair and hat are wider and
# taller than one block, so at impact they meet that common face instead of entering stone jambs and a lintel.
for _sid, _window, _title in (
        ('home_pane', ('fill ~-2 ~ ~3 ~1 ~1 ~3 minecraft:iron_bars', 'fill ~ ~ ~3 ~ ~1 ~3 minecraft:glass_pane'),
         '成体撞碎玻璃板，入屋宣言'),
        ('home_open', ('setblock ~ ~ ~3 minecraft:air',), '野生一家进入敞口空屋宣言')):
    _bold, _timid = uid(_sid, 'bold'), uid(_sid, 'timid')
    add(_sid, 'forage', _title, _title,
        '空石屋南面只有一处落地入口（撞玻璃场景为铁栏杆窗中间一列两块玻璃板，敞口场景为一格空门）；'
        '野生成年魔理沙胆量100、成年女儿灵梦胆量0。家园事件自己选择入口：'
        '魔理沙走近玻璃板，转正，逐次用身体撞击，出现裂纹、碎屑、声音与轻微反冲；玻璃板碎后进入才宣言，洞口保持开放。'
        '亲属安抚会暂停撞击，安抚后保留已有裂纹继续。整块玻璃作旁边的封闭窗，始终保留。胆小女儿可以从已经打开的入口跟进。',
        '约 10–60 秒', [
            'fill ~-6 ~ ~-6 ~6 ~5 ~10 minecraft:air',
            'fill ~-3 ~-1 ~-3 ~3 ~2 ~3 minecraft:stone_bricks hollow',
            'setblock ~ ~ ~ minecraft:crafting_table',
            *_window,
            'setblock ~2 ~ ~3 minecraft:glass',
            yk('marisa',0,6,'bold',uuid=_bold,yaw=180,lead=True,boldness=100,**pair_ready()),
            yk('reimu',2,7,'timid',uuid=_timid,yaw=180,boldness=0,kin=kin_of(Mother=_bold),**pair_ready())],
        late=['yukkuri homeclaim start'], env=DAY, spot='graze', vantage=(0,11,180),
        reads=[('读家园阶段','yukkuri homeclaim status'),('读两只状态','yukkuri status @e[type=#mcylab:yukkuri,distance=..24] raw')],
        smoke=dict(ticks=1200, expect=[r'\[home-claim\][^\n]* GATHER -> HOLD'] +
                   ([r'\[action\][^\n]* start id=bash',r'\[action\][^\n]* hit id=bash',
                     r'\[home\][^\n]* pane opened block=.* hits='] if _sid=='home_pane' else []),
                   forbid=[r'\[body\][^\n]* invariant',r'family_collapsed',r'never_arrived'],
                   **({'verify':home_pane_result,
                       'commands':[(1,'gamerule mobGriefing false'),
                                   (10,'yukkuri homeclaim start'),
                                   (20,'execute if block ~ ~ ~3 minecraft:glass_pane run say HOME_PANE_GRIEF_OFF_CHECK'),
                                   (21,'gamerule mobGriefing true'),
                                   (1190,'execute if block ~2 ~ ~3 minecraft:glass run say HOME_PANE_FULL_GLASS_INTACT')]}
                      if _sid=='home_pane' else {})),
        # Fixed world views: the outside profile runs along the glass in front of the bars, clear of the stone;
        # the inside three-quarter view keeps crack stages in front of the actor; the far inside corner sees the window and
        # the declaration spot together. Never follows its yaw.
        capture=(dict(shots=(40,120,200,400,800,1200), sequences=((40,1200,8),), observe=False,
                      views={'side':dict(position=(-1.9,.6,3.8),look_at=(.5,.6,3.8),
                                         criteria='侧面约两格，检查魔理沙身体与帽檐不穿玻璃、蓄力、撞击接触和反冲。'),
                             'crack':dict(position=(-1.0,.85,1.7),look_at=(.5,.65,3.5625),
                                          criteria='看清撞击后裂纹增长，安慰期间裂纹保留，碎开一块玻璃板、真实进入空屋。'),
                             'home':dict(position=(2.6,1.3,-1.9),look_at=(.5,.4,.6),
                                         criteria='屋内远角同时看到窗和宣言处：破窗后魔理沙真实进屋，在工作台旁宣言。')}) if _sid=='home_pane'
                 else dict(shots=(40,80,120,200,400), sequences=((40,200,4),))))

# ---- colony rules: a village that is sure to have a leader (village_led), on the colony field's west plot
COLONY = BY_ID['colony']
COLONY_LEAD = '@e[tag=scn_lead,distance=..70]'

_mold_colony_x, _mold_colony_z = COLONY.spots['colony']
_mold_outer_den, _ = den(_mold_colony_x, _mold_colony_z + 28)
add('colony_mold_exile', 'colony', '聚落驱逐油霉成员', '聚落驱逐油霉成员',
    '看附近成员发现油霉后鼓脸施压，患病成员含泪转身、回头道别，离开聚落的日常活动范围后回来央求一次，再次受拒。',
    '约 1–2 分钟', [
        *_mold_outer_den,
        box(-8, 0, 8, 8, 0, 25, 'minecraft:short_grass', 'keep'),
        yk('patchouli', -2, 0, 'leader', label='族长', also=('mold_colony_member',), **pair_ready()),
        yk('chen', 0, 1, 'witness', yaw=0, label='近处成员', also=('mold_colony_member',),
           friendliness=24, boldness=32, skills={'mold_lore': 2}, **pair_ready()),
        yk('marisa', 0, 2, 'molded', lead=True, label='患油霉的成员', also=('mold_colony_member',),
           attachment=70, boldness=35, self_control=60, **pair_ready()),
        f'yukkuri scenario community {sel("leader")} {sel_all("mold_colony_member")}',
        *[f'yukkuri scenario bond {sel(n)} {sel("molded")}' for n in ('leader', 'witness')],
        set_status('molded', 'health.mold', 5)],
    give=['mcyukkuri:mold_remover'], env=DAY, spot='colony', vantage=(0, 28),
    note='等成员回头被拒后，可用去霉药，再看认识它的成员是否友好接纳。',
    reads=[('读成员名册', 'yukkuri settlement info'),
           ('读患病成员与住所', f'yukkuri status {sel("molded")} raw')])


def colony_loss_prompt(sid, members_read, rules_read):
    """A player-chosen, real death after the colony has reached the phase being observed."""
    x, z = COLONY.spots['colony']
    kill = f'/execute positioned {x}.0 {FEET} {z}.0 run kill @e[tag=scn_lead,distance=..140,limit=1,sort=nearest]'
    controls = [
        {'text': '要核验族长死亡后的交接，请先观察当前组织或行军阶段，再点 ', 'color': 'gray'},
        {'text': '[杀死族长]', 'color': 'red', 'bold': True,
         'clickEvent': {'action': 'run_command', 'value': kill}},
        {'text': '；随后点 ', 'color': 'gray'},
        {'text': '[读全体身份与实际Goal]', 'color': 'aqua',
         'clickEvent': {'action': 'run_command', 'value': f'/function mcylab:read/{sid}_{members_read}'}},
        {'text': ' 和 ', 'color': 'gray'},
        {'text': '[读族规、公仓与继任]', 'color': 'gold',
         'clickEvent': {'action': 'run_command', 'value': f'/function mcylab:read/{sid}_{rules_read}'}}
    ]
    if sid == 'colony_ration':
        controls += [{'text': '。若当天已盘点，族长死亡后点 ', 'color': 'gray'},
                     {'text': '[下一天同一时刻]', 'color': 'yellow',
                      'clickEvent': {'action': 'run_command', 'value': '/time add 24000'}}]
    return 'tellraw @s ' + json.dumps(controls, ensure_ascii=False, separators=(',', ':'))


def colony(sid, title, hint, watch, wait, late, env=DAY, lines=(), note='', reads=(), leader_loss=False, village='village_led'):
    reads = list(reads)
    controls = []
    if leader_loss:
        indexes = []
        for caption, command in [('读全体成员身份与实际Goal', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..110] raw'),
                                 ('读族规、公仓与继任', 'yukkuri settlement rules')]:
            index = next((i for i, (_, existing) in enumerate(reads) if existing == command), None)
            if index is None:
                index = len(reads)
                reads.append((caption, command))
            indexes.append(index)
        controls.append(colony_loss_prompt(sid, *indexes))
    population = [f'yukkuri settlement spawn {village} ~ ~ ~',
                  'tag @e[type=mcyukkuri:patchouli,distance=..40,limit=1,sort=nearest] add scn_lead']
    add(sid, 'colony', title, hint, watch, wait,
        [*lines, *population, *controls],
        env=env, spot='colony', vantage=(0, 26), late=late, note=NEW + note, reads=reads, retry='先“清空本场地”再按一次')


def _colony_meadow():
    """A real hunting ground: the leader picks a meadow 32-64 blocks out (CommonHunt.RADIUS), so the ring of the colony plot is
    carpeted with grass wherever the field reaches (only air cells; every clear restores the ground)."""
    px, pz = COLONY.spots['colony']
    x1, x2, z1, z2 = (max(-64, COLONY.x1 + 1 - px), min(64, COLONY.x2 - 1 - px), max(-64, COLONY.z1 + 1 - pz), min(64, COLONY.z2 - 1 - pz))
    rects = [(x1, z1, x2, min(z2, -32)), (x1, max(z1, 32), x2, z2), (x1, max(z1, -31), min(x2, -32), min(z2, 31)), (max(x1, 32), max(z1, -31), x2, min(z2, 31))]
    return [box(a, 0, b, c, 0, d, 'minecraft:short_grass', 'keep') for a, b, c, d in rects if a <= c and b <= d]


colony('colony_hunt', '族长召集狩猎，猎物上缴公仓', '族长召集狩猎，猎物上缴公仓',
       '有族长（帕琪）的村落：2 秒后族长召集公共狩猎，集合→出发→每只割 6 株草→返回；收获一部分上缴公仓（狩猎 1/3、家庭觅食 1/5）',
       '约 2–3 分钟', [Retry('yukkuri settlement rules hunt', COLONY_LEAD)],
       lines=_colony_meadow(),
       reads=[('读族规与公仓', 'yukkuri settlement rules')], leader_loss=True)
colony('colony_ration', '黄昏族长给缺粮户分粮', '黄昏族长给缺粮户分粮',
       '黄昏时族长清点公仓：只给自家粮堆不够一天的“短缺户”分粮，其余不分；读数里看 SHORT 户和分配记录',
       '约 1.5 分钟后（家家吃掉一些存粮，才有短缺户）', [Retry('yukkuri settlement rules ration', COLONY_LEAD, first=1800)], env={'time': 'dusk'}, leader_loss=True)
# The empty village 64 blocks east stands on the colony_dest plot.
_migration_young = '@e[type=#mcylab:yukkuri,distance=..40,sort=nearest,limit=4,nbt={YukkuriFamily:{Stage:"BABY"}}]'
_migration_babies = '@e[type=#mcylab:yukkuri,tag=scn_migrant_baby,distance=..110]'
colony('colony_migrate', '族群迁徙：全族搬去东边空村', '族群迁徙：全族搬去东边空村',
       '族长决定迁徙：讨论 30 秒→准备 30 秒→先锋（有魔理沙时由魔理沙担任，体弱的帕琪不领队）先行，其余稍后跟上。看几户亲油如何把四只赤油带到东边 64 格外的空聚落，落地后如何认领新窝。',
       '约 2–5 分钟', [f'tag {_migration_young} add scn_migrant_baby',
                       Retry('yukkuri settlement migrate', COLONY_LEAD)],
       lines=['yukkuri settlement site vacant village ~64 ~ ~'],
       note='赤油与亲油同住村里的野生住所。观察先锋是否先到、掉队时是否集合换路，以及亲油在哪儿放下赤油。全体到达地面后才更改聚落中心。',
       reads=[('读迁徙与压力（含先锋）', 'yukkuri settlement rules'),
              ('读族长新住所', 'yukkuri status @e[tag=scn_lead,limit=1,sort=nearest] raw'),
              ('读赤油随迁', f'yukkuri status {_migration_babies} raw'),
              ('读全体成员安置', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..110] raw'),
              ('读新聚落状态', 'execute positioned ~64 ~ ~ run yukkuri settlement site info')],
       leader_loss=True, village='village_led_babies')

def _carry_outcome_verify(log):
    # A physical refusal may recover after replanning; count each finished Release once, by its child.
    refusals, carried = {}, {}
    for tick, event, child in re.findall(r'\[family\][^\n]*? t=(\d+)[^\n]* (carry (?:pickup hold=|put down reason=|put down refused )[^\n]*?)child=([0-9a-f-]{36})', log):
        if event.startswith('carry put down refused '):
            refusals[child] = refusals.get(child, 0) + 1
            if refusals[child] > 2:
                return f'child {child} exceeded 2 release refusals'
        elif event.startswith('carry pickup '):
            carried[child] = int(tick)
        else:
            carried.pop(child, None)
    if carried:
        # A later family retrieval or nest-rim recovery can still be under way; measure this child's current hold, not the carrier's first pickup.
        end_tick = int(re.search(r'MCYSMOKE_END_TICK (\d+)', log)[1])
        stuck = {child: end_tick - tick for child, tick in carried.items() if end_tick - tick > 600}
        if stuck:
            return f'children held more than 600 ticks at end: {stuck}'
    return None


# Three real households with their nest, the babies ~8 blocks out: the RETRIEVE carry (ParentCarry.purposeFor), no march needed.
_carry_families = [('marisa', -10, 4, 1.2), ('reimu', 0, 3, 1.0), ('chen', 10, 2, 1.0)]
_carry_lines = []
for _kind, _fx, _n, _size in _carry_families:
    _par = uid('carry_home', _kind)
    _fam = f'Version:2,Initialized:1b,Stage:"ADULT",Age:672000L,Nourishment:672000L,Maturity:336000L,Lifespan:1056000L,AdultScale:{_size}f,Scale:{_size}f'
    _carry_lines += [*shed(_fx, 0), *large_nest(_fx, 0, 'mcyukkuri:large_yukkuri_nest'),
                     yk_on_nest(_kind, _fx, 0, f'{_kind}_parent', large=True, fam=_fam, yaw=0, uuid=_par, lead=_kind == 'reimu', nutrition=95),
                     home(f'{_kind}_parent', _fx, 0)]
    for _i in range(_n):
        _carry_lines += [yk(_kind, _fx - (_n - 1) / 2 * 1.2 + _i * 1.2, 8, f'{_kind}_baby', stage='baby', uuid=uid('carry_home', f'{_kind}_{_i}'),
                            yaw=180, nutrition=95, kin=f'Mother:{uuid_nbt(_par)}'),
                         home(f'{_kind}_baby', _fx, 0)]

def _carry_verify(log):
    if not re.search(r'\[family\] marisa[^\n]* brim hop child=[0-9a-f-]+ hops=[1-9]\d*\b', log):
        return 'Marisa never completed the brim hop move'
    for walk in re.finditer(r'\[family\] marisa[^\n]* brim hop walk child=([0-9a-f-]{36})\b', log):
        if not re.search(rf'carry pickup hold=brim [^\n]*child={walk[1]}\b', log[walk.end():]):
            return f'brim walk alternative never completed for {walk[1]}'
    # Detect doorway ping-pong, not a later real retrieval with another entry/exit.
    stalled = _bed_walk_verify(log, crossing_window=300) or _carry_outcome_verify(log)
    if stalled:
        return stalled
    grips = {}
    for child, seat in re.findall(r'carry grip child=\d+ uuid=([0-9a-f-]{36})\b[^\n]*brimLocal=\(([^)]+)\)', log):
        grips.setdefault(child, set()).add(seat)
    checked = 0
    for match in re.finditer(r'carry lowering hold=brim child=([0-9a-f-]{36})\b[^\n]*brimLocal=\(([^)]+)\)', log):
        child, seat = match.groups()
        if child not in grips:
            continue
        checked += 1
        if seat not in grips[child]:
            return f'brim-local seat changed for {child}: {grips[child]} -> {seat}'
        if not re.search(rf'carry put down [^\n]*hold=brim cause=release [^\n]*child={child}\b', log[match.end():]):
            return f'brim child {child} was not released after lowering'
    if not checked and re.search(r'carry pickup hold=brim\b', log):
        return 'expected a brim grip/release pair with a constant brim-local seat'
    return _carry_chains_verify(log) or _reimu_release_verify(log)


def _carry_chains_verify(log):
    # Distinct initial children carried home; autonomous STAY_HOME returns are checked by END.
    carried = set()
    for kind, _, count, _ in _carry_families:
        for i in range(count):
            child = _uuid_text('carry_home', f'{kind}_{i}')
            pickup = re.search(rf'carry pickup hold=\S+ [^\n]*child={child}\b', log)
            if re.search(rf'carry put down [^\n]*child={child}\b[^\n]*nest=OUTSIDE\b', log):
                return f'initial child {child} released OUTSIDE'
            if pickup:
                if not re.search(rf'carry put down reason=\S+ [^\n]*cause=release [^\n]*child={child}\b[^\n]*nest=(?:INSIDE_SUPPORTED|PARTIAL)\b', log[pickup.end():]):
                    return f'initial carried child {child} has no supported nest release'
                carried.add(child)
    return f'fewer than two distinct initial children carried home: {len(carried)}' if len(carried) < 2 else None


def _reimu_release_verify(log):
    # Reimu lets each baby out by her whole mouth_release clip (1.6 s = 32 ticks), not in the first ticks.
    if not re.search(r'\[family\] reimu #[^\n]* carry pickup hold=mouth\b', log):
        return None
    opened, quick, completed = {}, [], 0
    events = re.finditer(
        r'\[family\] reimu #\d+ uuid=(\S+) t=(\d+) [^\n]*?carry '
        r'(lowering hold=mouth|put down reason=\w+ hold=mouth cause=release purpose=\w+) '
        r'child=([0-9a-f-]{36})\b', log)
    for event in events:
        parent, tick, action, child = event.groups()
        tick = int(tick)
        key = parent, child
        if action.startswith('lowering'):
            # A resumed lowering begins its own clip; a later release belongs to this transaction.
            opened[key] = tick
        else:
            if key not in opened:
                return f'Reimu mouth release without its own lowering: parent={parent} child={child} tick={tick}'
            start = opened.pop(key)
            completed += 1
            if tick - start < 32:
                quick.append((child, start, tick, tick - start))
    if not completed:
        return 'expected a Reimu mouth release (carry lowering hold=mouth, then carry put down hold=mouth)'
    return f'Reimu released before her clip ended (ticks): {quick}' if quick else None


add('carry_home', 'family', '亲油把走远的赤油带回窝', '亲油把走远的赤油带回窝',
    '三家各有自己的大窝，赤油在约 8 格外：魔理沙（1.2 倍，4 只）、灵梦（3 只）、橙（2 只）各自走去，先把身边够得着、承托还放得下的孩子一只只都抬起，再一起带回窝内；魔理沙用辫子托上帽檐，灵梦口含，橙两尾各托一只，到窝后一只一只放下（灵梦张嘴放出约 1.6 秒）',
    '约 30 秒内开始抬起', _carry_lines, env=DAY, spot='carry_home', vantage=(.5, 14, 180, 25),
    note='孩子离窝超过 4 格即触发找回（purpose=RETRIEVE）；是否接起由亲油自己的照护决定，不指定 Goal。离开前一趟抬起几只看 carry pickup 的 aboard=（橙 2；魔理沙按帽檐空间再口含一只；灵梦按口腔容量），放不下的留给下一趟。可右键问候／开照料菜单、攻击被抬着的孩子（灵梦口里看不见的除外）：孩子不会因此被放下，也不会自己走；攻击亲油则每次受击掷一次摔落（概率＝伤害占最大生命的比例×（1−自制/100），日志 [family] carry hit drop chance= roll= dropped=），命中则最后抱上的一只在原处掉到地面。日志 [family] carry lift started／carry pickup hold=brim／mouth／tail_left／tail_right／carry put down。',
    reads=[('读承托状态（home.family_state 的 carry=）', 'yukkuri status @e[type=#mcylab:yukkuri,tag=scn,distance=..48] raw')],
    capture=dict(shots=(1, 40, 1200, 2400, 3600), sequences=((2, 1000, 4), (2500, 3300, 10)), observe=False,
                 views={kind: dict(follow=f'@e[tag=scn_{kind}_parent,limit=1]',
                                   offset={'marisa':(-2.2,1.15,1.2),'reimu':(-1.4,.65,.8),'chen':(-1.8,.9,-1)}[kind],
                                   criteria={'marisa':'看清魔理沙用辫子抬幼体、帽檐承托以及到窝放下；按当前阶段判断，不要求抬起前已经携带。',
                                             'reimu':'看清灵梦张嘴接幼体与到窝张嘴放出；闭嘴时口含幼体本来不可见，不能当成消失。',
                                             'chen':'看清橙的两条尾巴承托幼体、带回窝并放下；只要求此家庭出现在画面。'}[kind]) for kind, _, _, _ in _carry_families}),
    smoke=dict(ticks=3600,
               expect=[r'\[family\][^\n]* carry lift started\b',
                       r'\[family\][^\n]* carry pickup hold=(?:brim|mouth|tail_left|tail_right)\b',
                       r'\[family\][^\n]* carry put down reason=\S+ [^\n]*cause=release [^\n]*nest=(?:INSIDE_SUPPORTED|PARTIAL)\b'],
               forbid=[r'\[home\][^\n]* assignment refused\b', r'\[family\][^\n]* task stopped task=SEARCH target=null\b',
                       r'\[home\][^\n]*bed crossing[^\n]*opening=false source=walk', r'\[home\][^\n]*nest_not_inside\b',
                       r'\[home\][^\n]*reason=doorway_unreachable\b',
                       r'\[family\][^\n]* carry put down forced\b', r'\[family\][^\n]* cause=carry_release_blocked\b'],
               readout=['yukkuri status @e[type=#mcylab:yukkuri,tag=scn] raw'],
               verify=_carry_verify))

# Both families start outdoors with unmounted children and dry beds nearby.
_walk_home = 'family_walk_home'
_flight_home = 'family_flight_home'
_carry_nest = [*shed(0, 0), *large_nest(0, 0, 'mcyukkuri:large_yukkuri_nest')]
_carry_transport_forbid = [r'\[family\][^\n]* carry put down forced\b',
                           r'\[family\][^\n]* cause=carry_release_blocked\b',
                           r'\[family\][^\n]* carry put down [^\n]*cause=carry_fright\b']
_walk_parent = uid(_walk_home, 'mother')

add(_walk_home, 'family', '天晚了，亲油抱赤油和小子油回窝', '天晚了，亲油抱赤油和小子油回窝',
    '魔理沙和两只赤油、一只刚长成的子油离窝几格。看亲油把赤油放回家窝，再把小子油送进旁边的子油窝。',
    '约半分钟开始接抱', [*_carry_nest, *shed(10, 0), *large_nest(10, 0, 'mcyukkuri:large_yukkuri_nest'),
        yk('marisa', 0, 6, 'mother', uuid=_walk_parent, yaw=0, lead=True, rest_wait=60,
           fam='Version:2,Initialized:1b,Stage:"ADULT",Age:672000L,Nourishment:672000L,Maturity:336000L,Lifespan:1056000L,AdultScale:1.2f,Scale:1.2f'),
        *[yk('marisa', x, 8, name, stage='baby', uuid=uid(_walk_home, name), yaw=180,
             kin=kin_of(Mother=_walk_parent), childhood=CHILDHOOD_HOPPING)
          for x, name in [(-.8, 'baby_a'), (.8, 'baby_b')]],
        *[home(name, 0, 0) for name in ('mother', 'baby_a', 'baby_b')]],
    late=[Retry(yk('marisa', .1, 8.6, 'small_child', stage='child', share=.215, energy=60,
                   uuid=uid(_walk_home, 'small_child'), yaw=180, kin=kin_of(Mother=_walk_parent),
                   childhood=CHILDHOOD_HOPPING), sel('mother'), tries=1, first=1),
          Retry(home('small_child', 10, 0), sel('small_child'), tries=1, first=41)],
    env={'time': 'night'}, spot='carry_home', vantage=(.5, 13, 180, 20),
    note='看会跳的赤油在帽檐座位上转向落点、蹲身蓄力，再从帽檐跳下并落稳。没有安全跳路时由亲油用辫子托放。',
    reads=[('读亲油与幼体的承托', 'yukkuri status @e[tag=scn,distance=..48] raw')],
    smoke=dict(ticks=2200, expect=[r'\[family\][^\n]*carry pickup .*purpose=RETRIEVE .*childStage=BABY\b',
                                  r'\[action\][^\n]*uuid=(\S+) [^\n]* start id=hop owner=brim_release [^\n]*departure=worn_brim\b'
                                  r'[\s\S]*\[body\][^\n]*uuid=\1 [^\n]* dismount [^\n]*cause=hop_takeoff\b'
                                  r'[\s\S]*\[action\][^\n]*uuid=\1 [^\n]* end id=hop outcome=DONE [^\n]*legs=1 peak=(?!0\.00)'],
               forbid=_carry_transport_forbid))

_FLIGHT_FAM = {'reimu': None, 'marisa': None}
_FLIGHT_ROOST = (14, 34)


def _flight_scene(sid, kind, title, watch, wait, bold, attach, expect, note='', allow_fate=(), pred_z=20, baby_x=1.9, mother_z=5.0,
                  babies=None):
    """One household at its nest (homes known at the summon): in the default home scene the mother stands 5.0 blocks from the nest and
    each baby ~2.4 blocks to her side (inside the 4-block retrieve distance, clear of the shed's removed front posts and roof) with the
    hungry predator summoned 15 blocks from her (20 from the nest). In timid/hat the mother stands 7.5 blocks out with baby_a ~2.4 from
    her, baby_b ~3.6 from her (~10.5 from the nest, ~10.7 from the predator) and the predator 12.5/13.5 blocks from her (20/21 from the
    nest). The predator is summoned with them because with homes known earlier the babies hop home before anything happens; the 4 s
    scene-start hold lets the player watch from the first tick. The player sees the notice, the urgent run to the nearest baby, the lift,
    the run to the nest and the set-down (the hat scene may need the declared second trip when a dive cuts short the second brim lift),
    then the predator flies back to its roost."""
    parent = uid(sid, 'mother')
    fam = _FLIGHT_FAM[kind]
    # A beat line pairs the face shown with the face declared: any family beat where they differ fails the run.
    smoke = dict(ticks=1800, expect=expect, forbid=[*_carry_transport_forbid, r'\[family\][^\n]* beat=\w+ face=(\w+) declared=(?!\1\b)'])
    if allow_fate:
        smoke['allow_fate'] = list(allow_fate)
    px, pz = _FLIGHT_ROOST
    add(sid, 'family', title, title, watch, wait, [*_carry_nest,
            # No post in the front yard: a lift never loses its contact stand beside one.
            box(-2, 0, 2, -2, 2, 2, 'minecraft:air'), box(2, 0, 2, 2, 2, 2, 'minecraft:air'),
            # The predator's own roost, far on the side it flies away to: its return home after the hunt never picks the family's nest.
            box(px - 4, 0, pz - 4, px + 4, 12, pz + 4, 'minecraft:air'),
            box(px, 0, pz, px, 7, pz, 'minecraft:oak_log'),
            block(px, 8, pz, 'mcyukkuri:predator_nest'),
            block(px - 1, 7, pz, 'minecraft:oak_log[axis=x]'),
            box(px - 1, 9, pz - 1, px, 9, pz, 'minecraft:oak_leaves[persistent=true]'),
            yk(kind, 0, mother_z, 'mother', uuid=parent, yaw=0, lead=True, boldness=bold, attachment=attach, **({'fam': fam} if fam else {})),
            *[yk(kind, x, z, name, stage='baby', uuid=uid(sid, name),
                 kin=kin_of(Mother=parent), childhood=CHILDHOOD_HOPPING)
              for x, z, name in (babies or [(-baby_x, 3.5, 'baby_a'), (baby_x, 3.5, 'baby_b')])],
            yk('remilia', 0, pred_z, 'predator', uuid=uid(sid, 'predator'), yaw=180,
               label='饥饿的捕食种', nutrition=36, energy=38, boldness=40)],
        at_spawn=[home(name, 0, 0) for name in ('mother', 'baby_a', 'baby_b')],
        late=[Retry(f'yukkuri scenario home {sel("predator")} ~{px} ~8 ~{pz}', sel('predator'), tries=8, every=20, first=3)],
        env=DAY, spot='carry_home', vantage=(13, 9, 90, 20), note=note,
        reads=[('读威胁反应与幼体承托', 'yukkuri status @e[tag=scn,distance=..48] raw')],
        smoke=smoke)


# The predator has its own roost (a nest far on the other side): after the hunt it flies back to it (or perches on a far tree when
# the pursuit ended with energy to spare), so it never claims the family's nest.
_ROOST_ASSIGNED = r'\[home\] remilia [^\n]*assigned kind=care\b'
_PREDATOR_LEAVES = r'\[predator\] remilia [^\n]*activity started; task=(?:RETURN|PERCH)\b'
# A hunt that bit the left young ends in one of its visible stops: the kill and the meal, the mother stepping in front of it (taunt:
# a defender who turns the chase into a fight), the hunter's own exhaustion (it comes down at its rest line, energy 0-14, also after finishing a wounded prey
# it stooped at below the line), or the flight back to its roost.
_HUNT_ENDS = (r'\[predator\] remilia [^\n]*(?:activity started; task=(?:RETURN|PERCH)\b|taunt accepted [^\n]*shield='
              r'|eat pause started; reason=prey_kill\b|landing started; previous_task=(?:CHASE|DIVE) [^\n]*energy=(?:1[0-4]|\d)\.)')
# A beat line pairs the face the presentation arbiter showed with the face the beat declared (they must be equal).
_FLIGHT_BEAT = r'\[family\][^\n]* beat=%s face=(\w+) declared=\1 line=%s\b'
# The bound flight in order: armed at the decision, the pickup, (the look-back for a child left), the set-down inside the nest.
_FLIGHT_ORDER = (r'\[family\][^\n]* carry flight bound cause=\w+ leg=GATHER\b[\s\S]*\[family\][^\n]* carry pickup hold=%s purpose=PROTECT\b'
                 r'%s[\s\S]*\[family\][^\n]* carry set down started reason=reached_nest\b'
                 r'[\s\S]*\[family\][^\n]* carry put down reason=reached_nest\b')
_FLIGHT_LEAVES = r'[\s\S]*\[family\][^\n]* carry flight leaves child=\S+ left=1\b'
_flight_timid = 'family_flight_timid'
_flight_hat = 'family_flight_hat'
_flight_scene(_flight_home, 'reimu', '捕食种靠近，亲油抱起赤油躲回窝',
    '灵梦与两只赤油在窝外，南边饥饿的蕾米莉亚主动逼近。看亲油察觉捕食种后跑向最近的赤油、张口含进嘴里，再不减速地跑向第二只，随后径直退回窝边把孩子放到窝里；蕾米莉亚俯冲一轮后飞回远处自己的高巢，一家在窝里安定下来。',
    '约 5 秒开始接抱，15 秒内回窝放下', 75, 85,
    [r'\[family\][^\n]*carry flight choice .*gather=true',
     r'\[family\][^\n]*carry flight reaching child=\S+ distance=[1-9]',
     r'\[family\][^\n]*carry pickup hold=mouth purpose=PROTECT\b',
     r'\[family\][^\n]*carry flight pickup ended aboard=[12]\b',
     _ROOST_ASSIGNED, _PREDATOR_LEAVES,
     r'\[family\][^\n]*carry set down started reason=reached_nest\b',
     _FLIGHT_ORDER % ('mouth', ''), _FLIGHT_BEAT % ('pickup', 'none')],
    note='胆大又依恋的亲油（胆量 75、依恋 85）把两只都带走：两只开场都在约 2.4 格外，看跑向第二只时不减速、要抱的孩子站住等着；抱齐后直奔窝口放下（reached_nest）；捕食种有自己的高巢，俯冲后飞回，不占一家的窝。若俯冲落在抱第二只的途中，第二只留下、二趟再来接，也算正常结局。')
_flight_scene(_flight_timid, 'reimu', '捕食种靠近，胆小的亲油只带走一只',
    '灵梦与两只赤油在窝外，饥饿的蕾米莉亚靠近。亲油胆小又不太依恋（胆量与依恋 31），只来得及含起一只，就停步回头喊一声再逃回窝，另一只自己跟着跑。',
    '约 5 秒开始接抱，15 秒内回窝放下', 31, 31,
    [r'\[family\][^\n]*carry flight choice .*gather=true',
     r'\[family\][^\n]*carry flight reaching child=\S+ distance=\d',
     r'\[family\][^\n]*carry flight leaves child=\S+ left=1 aboard=1 attachment=31\b',
     r'\[family\][^\n]*carry flight pickup ended aboard=1 follow=\[[0-9a-f-]{36}\]',
     _ROOST_ASSIGNED,
     _HUNT_ENDS,
     _FLIGHT_ORDER % ('mouth', _FLIGHT_LEAVES), _FLIGHT_BEAT % ('abandon_look', 'protect_child_flee')],
    note='依恋低于 50 的亲油回头只喊害怕（protect_child_flee），威胁未贴近 4 格内时停步＝12＋依恋/5（31 约 18 tick，近 1 秒；贴近 4 格内停步为 0）；第一只在亲油身边约 2.4 格，第二只在它外侧、距亲油约 3.6 格（离窝约 10.5 格、距捕食种约 10.7 格），耐心只有 13 tick、第一只的口含抱起就要约 18 tick，所以她抱起第一只后不会再开始第二只；留下的孩子面临真实捕食风险。追猎一旦开始只在精力降到休息线 15 时才落地休息（可见的疲惫落地）。若亲油放下孩子后回头挡在追猎前（taunt），捕食种转为战斗去驱赶她：这是真实的阻止，留下的孩子可能被救下或只被咬伤后失血死，亲油自己也可能被杀。',
    # Patience 13 ticks: the mouth lift of the first baby (15-18 ticks) already outlasts it. The second baby lies beyond the first, on
    # the predator's side and ~10.5 blocks from the nest (~3.6 from her, ~10.7 from the predator: inside the 6-block gather range and
    # the 8-block look-back), so the hunt for it ends away from the nest door and the set-down young are not flushed out of the nest.
    mother_z=7.5, babies=[(-1.9, 6.0, 'baby_a'), (2.0, 10.5, 'baby_b')],
    allow_fate=[dict(kind='death', who=f'uuid={_uuid_text(_flight_timid, "baby_b")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                     why='the left-behind young is the hunt\'s prey: it dies of the hunter\'s bite or the bleeding wound'),
                dict(kind='death', who=f'uuid={_uuid_text(_flight_timid, "mother")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                     why='the mother who steps in front of the hunt to shield the left-behind young may be killed driving it off')])
_flight_scene(_flight_hat, 'marisa', '捕食种靠近，魔理沙把赤油抱上帽檐躲回窝',
    '魔理沙与两只赤油在窝外，饥饿的蕾米莉亚靠近。亲油用辫子把赤油托上帽檐，一只接一只（若俯冲打断第二只抬升，会走已声明的第二趟回接或由留下的赤油自行回窝），然后径直退回窝边放下。',
    '约 5 秒开始接抱，20 秒内回窝放下', 75, 85,
    [r'\[family\][^\n]*carry flight choice .*gather=true',
     r'\[family\][^\n]*carry flight reaching child=\S+ distance=[1-9]',
     r'\[family\][^\n]*carry lift started hold=brim [^\n]*purpose=PROTECT\b',
     r'\[family\][^\n]*carry flight pickup ended aboard=\d\b',
     _ROOST_ASSIGNED, _HUNT_ENDS,
     r'\[family\][^\n]*carry set down started reason=reached_nest\b', _FLIGHT_ORDER % ('brim', '')],
    mother_z=7.5, babies=[(-1.9, 6.0, 'baby_a'), (2.0, 10.5, 'baby_b')],
    allow_fate=[dict(kind='death', who=f'uuid={_uuid_text(_flight_hat, "baby_b")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                     why='young left behind by a brim lift that the dive cut short is at real risk from the hunting predator: it dies of the bite or the bleeding wound'),
                dict(kind='death', who=f'uuid={_uuid_text(_flight_hat, "mother")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                     why='the hunter that finished the young and ate it is still hungry and rested after its meal: it may hunt the mother next, a real new hunt')],
    note='帽檐路线：辫子托上帽檐（一只约 4.5 秒）后同样不减速地跑向下一只，抱齐后直奔窝口放下；俯冲落在抬升途中时，没抱上的一只自己走回或亲油二趟接回。', pred_z=21)

# ====================================================================================================== which concern each scenario answers
# Fresh NPC UUID on every start: confirmed death records are intentionally never reset by scene cleanup.
_custody_lines = [human('caretaker_brother', 0, -3, 'custody_owner', 1, label='NPC主人')]
for _kind, _dx, _role, _name, _label in (
        ('reimu', -6, 'companion', 'custody_pet', '带章宠物'),
        ('marisa', -3, 'stock', 'custody_stock', '库存个体'),
        ('alice', 0, 'input', 'custody_input', '旧档工厂输入'),
        ('chen', 3, 'lost', 'custody_lost', '可先装篮'),
        ('reimu', 6, 'companion', 'custody_buyer', '已归玩家')):
    _commerce = 'OwnerKind:"npc"' if _role == 'input' else f'Use:"{_role}",OwnerKind:"npc"'
    if _role == 'stock':
        _commerce += ',ForSaleSince:1L,StaleCuts:1,ShopCandidate:1b'
    _custody_lines += [yk(_kind, _dx, 2, _name, label=_label, grade=1 if _role in ('companion', 'lost') else None,
                          lead=_name == 'custody_pet', extra='NeoForgeData:{HumanCommerce:{' + _commerce + '}}'),
                       f'data modify entity {sel(_name)} {OWNED}.Owner set from entity {sel("custody_owner")} UUID']
    if _role == 'input':
        _custody_lines += [f'data merge entity {sel(_name)} '
                           '{NeoForgeData:{HumanCommerce:{Use:"input",FactoryStage:"observe",StageWork:25,'
                           'FactoryYoung:1b,HoldReason:"nutrition",FactoryCycles:3}}}']
_custody_lines += [*own('custody_buyer'),
                  f'yukkuri status relate {sel("custody_pet")} {sel("custody_owner")} 30',
                  yk('marisa', 9, 2, 'custody_unknown', label='UNKNOWN旧档',
                     extra='NeoForgeData:{HumanCommerce:{Owner:' + uuid_nbt(uid('custody_death', 'unseen_owner')) + ',Use:"future_use"}}')]
add('custody_death', 'town', 'NPC主人死了，宠物库存无主', 'NPC主人死了，宠物库存无主',
    '旧档工厂输入开场解除归属。先用篮子装“可先装篮”，再手动杀死NPC主人：现场宠物和库存转无主，玩家个体与UNKNOWN旧档保留归属。放出篮中个体，徽章仍保留',
    '工厂输入开场解除；其余在死亡后或从篮中放出时', _custody_lines, env=DAY, vantage=(0, 14), note=NEW,
    give=['mcyukkuri:yukkuri_carrier 2', 'mcyukkuri:sleep_spray 2', 'minecraft:emerald 16'],
    reads=[('读宠物归属', f'data get entity {sel("custody_pet")} NeoForgeData.HumanCommerce'),
           ('读旧厂输入归属', f'data get entity {sel("custody_input")} NeoForgeData.HumanCommerce'),
           ('读玩家归属', f'data get entity {sel("custody_buyer")} NeoForgeData.HumanCommerce'),
           ('读旧档归属', f'data get entity {sel("custody_unknown")} NeoForgeData.HumanCommerce')],
    smoke=dict(ticks=180, commands=[(60, f'kill {sel("custody_owner")}')],
               expect=[r'\[custody\] alice[^\n]*factory closure cleanup completed formerOwner=[a-f0-9-]+ identity=stray',
                       r'\[custody\] reimu[^\n]*owner death cleanup completed formerOwner=[a-f0-9-]+',
                       r'\[custody\] marisa[^\n]*owner death cleanup completed formerOwner=[a-f0-9-]+'],
               forbid=[r'\[custody\] alice[^\n]*owner death cleanup completed']))

# Fixed numerical fixtures; ordinary AI owns every action after these initial conditions.
add('balance_traits', 'meadow', '五属性20／50／80／90对照', '五属性20／50／80／90对照',
    '四只普通油的五属性分别为20、50、80、90。看学习概率、遵守规则概率、等待tick和任务段数；先教会具体技能，再观察执行',
    '读数立即可看；行为需实际互动', [
        *[yk('reimu', (n - 1.5) * 3, 0, f'axis{v}', label=f'五属性{v}', lead=n == 0,
             intelligence=v, friendliness=v, boldness=v, attachment=v, self_control=v, social_wait=0)
          for n, v in enumerate((20, 50, 80, 90))],
        item('minecraft:apple', 8, 0, 4)], env=DAY,
    give=['mcyukkuri:field_notebook', 'minecraft:cookie 16'],
    reads=[('读五属性与行为参数', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

# The three stand on an arc in front of the arrival point (vantage below): nine to ten blocks from it and at least eight apart, so a peer
# is never inside the six-block social scan and no floor food competes with the apple the player holds.
_BALANCE_ARC = ((0, -5), (-8, -8), (8, -8))
_BALANCE_FEAR = ((0, '0'), (45, '45'), (70, '70'))
add('balance_relation', 'meadow', '亲近玩家但对玩家的恐惧0／45／70', '亲近玩家但对玩家的恐惧0／45／70',
    '三只对玩家好感都是80，恐惧分别是 0／45／70（恰好落在代码分档 30／60 的两侧），彼此相距八格以上、地上没有苹果。按顺序试：'
    '①站住不动等它靠近→恐惧 0 自己走过来打招呼；45／70 先站着看几秒（胆子越大越快）再靠近问候。'
    '②朝一只走近（进到六格内）→45／70 在观察期里被逼近就后退，0 迎着走过来。'
    '③手里拿着苹果（十格内）→只有 0 会过来（<30）；45／70 不来。'
    '④右键递苹果→三只都吃（进食不看恐惧，吃完好感 +8、恐惧 −5）。'
    '⑤双手空着潜行右键，在「身体动作」找「轻抱」和「抚摸」→只有 0 可选（轻抱 <40、抚摸 <25，抚摸还需靠到 1.5 格内）；45／70 不显示。'
    '70 还有更细的差别：靠近到 1.5 格内会缩开、不能一起休闲／贴贴／舔伤（≥60）；45 只是谨慎。读“对象关系”看 closeness／fear／fear_band 真实值',
    '约30–60秒', [
        *[command for n, (fear, text) in enumerate(_BALANCE_FEAR)
          for command in (yk('reimu', *_BALANCE_ARC[n], f'fear{fear}', label=f'好感80 恐惧{text}',
                             lead=n == 0, nutrition=90),
                          f'yukkuri status relate {sel(f"fear{fear}")} @s 80',
                          f'yukkuri status fear {sel(f"fear{fear}")} @s {fear}')]],
    env=DAY, give=['minecraft:apple 6'],
    reads=[('读三只关系', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

add('balance_forms', 'meadow', '不足油与多毛灵梦的属性上下限', '不足油与多毛灵梦的属性上下限',
    '不足油仅含混发声，智力不超过25、友善不低于75；多毛友善和自制不超过25、依恋不低于75。正常灵梦作对照；真实学习仍可改善合法区间内的属性',
    '读数立即可看', [
        yk('reimu', -4, 0, 'deficient', congenital='BEAN_BODY', intelligence=90, friendliness=10, label='不足', lead=True),
        yk('reimu', 0, 0, 'wasa', form='WASA_REIMU', friendliness=90, attachment=10, self_control=90, label='多毛'),
        yk('reimu', 4, 0, 'ordinary', label='普通'), item('minecraft:apple', 6, 0, 4)], env=DAY,
    give=['mcyukkuri:field_notebook'],
    reads=[('读形态与五属性', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

add('balance_deibu', 'family', '吃得好压力大的亚成长成灵胖', '吃得好压力大的亚成长成灵胖',
    '两只亚成从成年前1 tick和累计23999 tick开始；高营养高压力者锁定灵胖，营养70者清零后普通成年。锁定后降压不撤回，扩体等待安全接缝',
    '年龄1 tick；体型在安全时变化', [
        *[command for n, nutrition in enumerate((90, 70))
          for command in (yk('reimu', n * 4 - 2, 0, f'deibu{n}', stage='teen', share=1, nutrition=nutrition,
                             label='满足条件' if n == 0 else '营养中断', lead=n == 0),
                          set_status(f'deibu{n}', 'mood.stress', 80),
                          f'data modify entity {sel(f"deibu{n}")} YukkuriSubspecies.DeibuExposure set value 23999')],
        item('minecraft:apple', 6, 0, 4)], env=DAY, spot='growth',
    note='累计23999 tick为明确设置的边界起点；完整持续一天可另在亚成期用倍速观察，未用脚本代替成长结算。',
    reads=[('读灵胖锁定与空间状态', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])
