# ====================================================================================================== wild shelters
# Wait for all three real claims on one mound, then use the ordinary death path before the first stores come in.
_dm, _df, _dc = (uid('den_family', name) for name in ('mother', 'father', 'child'))


def _den_family_death():
    field = BY_ID['den']
    origin_x, origin_z = field.spots['family']
    father = sel('father', 64)
    # Let the family choose its own home and wait for a separation the attached mother can actually close.
    mother = f'@e[tag={tag("mother")},distance=1.5..5,limit=1]'
    for x, y, z in field.doorways.values():
        doorway = f'~{x - origin_x} ~{y - FEET} ~{z - origin_z}'
        claims = ' '.join(f'if data block {doorway} Residents[{{Id:{uuid_nbt(member)}}}]' for member in (_dm, _df, _dc))
        yield Retry(f'execute {claims} as {father} at @s if entity {mother} run kill @s', father, tries=80, every=20, first=400)


add('den_family', 'den', '一家三口住进土丘洞', '一家三口住进土丘洞',
    '父母和子油自己认领同一个土丘洞。三只刚入住、家里还缺粮时，场景让父油死亡。看母油目睹后的哀悼过程：先愣住再哭喊，'
    '起初转向遗体，依恋高且遗体近、路通时会走到旁边垂头抽泣。缓和时，母油为伴侣许诺照顾孩子，子油会按自己的性格对粑粑说话。'
    '随后慢慢出洞采草、存粮，走得比平时慢；'
    '最后表情恢复，回想一次后说「连你的那份一起油库里」。孩子吃到食物。洞口的草已吃过，外面还有草。',
    '入住约半分钟到一分钟，之后再看数分钟（可五倍速）', [
        yk('reimu', 0, 3, 'mother', uuid=_dm, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_df)}', **pair_ready()),
        yk('marisa', 2, 3, 'father', uuid=_df, yaw=180, kin=f'Partner:{uuid_nbt(_dm)}', **pair_ready()),
        # Starts above appetite so the child's first demand develops during the handoff.
        yk('reimu', 1, 4, 'child', stage='child', uuid=_dc, yaw=180, nutrition=80, kin=f'Mother:{uuid_nbt(_dm)},Father:{uuid_nbt(_df)}'),
        box(-7, 0, 1, 7, 1, 8, 'minecraft:air', 'replace minecraft:short_grass')],
    env=DAY, spot='family', vantage=(0, 12),
    late=list(_den_family_death()),
    note='用场景读数查看母油的 Load、Delivered 和孩子营养。若家中已有足够存粮，母油会留在窝里用现成的粮照料孩子。',
    reads=[('读家的识别与母油觅食', f'yukkuri status {sel("mother")} raw'),
           ('读孩子生活', f'yukkuri status {sel("child")} raw')])

_em, _ef, _ec, _eg = (uid('den_mold_exile', name) for name in ('healthy', 'molded', 'baby', 'grandparent'))
add('den_mold_exile', 'den', '油霉亲油离巢', '油霉亲油离巢',
    '健康亲油（冷淡、不友善）先吓一跳「霉菌先生」，鼓脸警告，再辱骂着上前推；年长亲油（依恋高）含泪道歉、不推。'
    '患病亲油（依恋高、胆小）先愣住，再央求「我还能油库里」，被推开后转身离开，途中两次回头、最后一次道别；赤油看见它转身时会哭喊「别走」，留在健康亲油照护下。'
    '患病亲油出了家的范围后先坐着哀悼一阵，之后回来央求一次，被拒后离开。',
    '约 3–4 分钟', [
        yk('reimu', 0, 1, 'healthy', uuid=_em, yaw=0, label='健康亲油', friendliness=24, boldness=35,
           attachment=28, skills={'mold_lore': 2}, kin=f'Partner:{uuid_nbt(_ef)}', **pair_ready()),
        yk('marisa', 0, 3, 'molded', uuid=_ef, lead=True, label='患油霉的亲油', attachment=80, boldness=30,
           kin=f'Mother:{uuid_nbt(_eg)},Partner:{uuid_nbt(_em)}', **pair_ready()),
        yk('reimu', -1, 1, 'grandparent', uuid=_eg, yaw=0, label='年长亲油', friendliness=86, attachment=90,
           skills={'mold_lore': 2}, **pair_ready()),
        yk('reimu', 0, 0, 'baby', stage='baby', uuid=_ec, yaw=0, label='赤油', nutrition=100,
           kin=f'Mother:{uuid_nbt(_em)},Father:{uuid_nbt(_ef)}'),
        box(-4, 0, 6, 23, 0, 13, 'minecraft:short_grass', 'keep')],
    late=[*[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('healthy', 'molded', 'grandparent', 'baby')],
          f'yukkuri scenario bond {sel("grandparent")} {sel("molded")}',
          # Stage 3 has the same visible mould as stage 5 and still harms the exile every 400 ticks.
          set_status('molded', 'health.mold', 3)],
    give=['mcyukkuri:mold_remover'], env=DAY, spot='family', vantage=(4, 16),
    note='等患病亲油回头被拒后，可用去霉药，看它能否重新认领原洞。',
    reads=[('读患病亲油与住所', f'yukkuri status {sel("molded")} raw'),
           ('读赤油与住所', f'yukkuri status {sel("baby")} raw')])


def _mold_family(sid, keep):
    names = ('mother', 'father', 'child', 'baby')
    ids = {n: uid(sid, n) for n in names}
    mother, father, child, baby = (_uuid_text(sid, n) for n in names)
    kin = kin_of(Mother=ids['mother'], Father=ids['father'])
    unaware = 'YukkuriSkills:{Assessed:["mold_lore"]}'
    aware = {'mold_lore': 2}
    needs = dict(nutrition=100, energy=100, hygiene=30 if keep else 95, rest_wait=220 if keep else 1200)
    if keep:
        spots = ((-1, -3), (1, -3), (0, -3), (0, -2))
    else:
        spots = ((-1.5, 4), (1.5, 4.2), (0, 5.2), (3, 5))
    title = '舍不得赶走，全家染上油霉' if keep else '油霉子油被亲油狠心赶走'
    lines = [
        yk('reimu', *spots[0], 'mother', uuid=ids['mother'], yaw=0, label='母油', skills=aware, adult_scale=.88 if keep else 1,
           intelligence=50, friendliness=70 if keep else 25, boldness=40 if keep else 60,
           attachment=85 if keep else 30, self_control=35 if keep else 40, kin=kin_of(Partner=ids['father']), **needs),
        yk('marisa', *spots[1], 'father', uuid=ids['father'], yaw=0, label='父油', adult_scale=.88 if keep else 1,
           intelligence=55 if keep else 60, friendliness=70 if keep else 50, boldness=55 if keep else 45,
           attachment=60 if keep else 50, self_control=70 if keep else 65, kin=kin_of(Partner=ids['mother']),
           skills=None if keep else aware, extra=unaware if keep else '', **needs),
        yk('reimu', *spots[2], 'child', uuid=ids['child'], yaw=0, label='有斑点的赤油' if keep else '有斑点的子油',
           stage='baby' if keep else 'child', lead=True, kin=kin,
           attachment=(40, 85), boldness=(30, 65), friendliness=(30, 60), self_control=50, extra=unaware, **needs),
        yk('marisa', *spots[3], 'baby', uuid=ids['baby'], label='赤油', stage='baby', kin=kin, extra=unaware, **needs),
        *[home(n, 0, -1) for n in names],
        box(-4, 0, 6, 12, 0, 13, 'minecraft:short_grass', 'keep')]
    late = [f'yukkuri scenario bond {sel(n)} {sel("child")}' for n in names[:2]] + [set_status('child', 'health.mold', 4)]
    expect = [rf'uuid={who} [^\n]*mold child verdict child={child} verdict={verdict}\b'
              for who, verdict in ((mother, 'KEEP' if keep else 'DRIVE_COLD'), (father, 'KEEP' if keep else 'DRIVE_TEARFUL'))]
    if keep:
        x, z = BY_ID['den'].spots['family']

        def cell(dx, dz):
            return f'{((x + dx) << 38) | (((z + dz) & 0x3ffffff) << 12) | FEET}L'

        # The burrow renderer uses the catalog's large_wild_nest on this supported 3×3 floor.
        nest = cell(0, -3)
        floor = [nest] + [cell(dx, dz) for dx in range(-1, 2) for dz in range(-4, -1) if (dx, dz) != (0, -3)]
        lines.insert(0, block(0, 0, -1, f'mcyukkuri:forest_burrow[facing=south]{{Condition:100,LargeBeds:[L;{nest}],'
                              f'BedMaterials:[{{Pos:{nest},Materials:[{{id:"minecraft:oak_leaves",count:1}},{{id:"minecraft:short_grass",count:1}}]}}],'
                              f'NaturalBeds:[L;{",".join(floor)}],NaturalPile:{cell(-2, -4)},NaturalPileStand:{cell(-1, -4)}}}'))
        watch = '两位亲油舍不得赶走有斑点的赤油。留在洞内，看四口在野生大草窝各自的睡位躺好，白天醒来，入夜再睡。到第二夜结束时，全家会因接触陆续染病。醒来后，认得油霉的母油会惊叫，不认得的只喊痒。途中可用去霉药中断传染。'
        first_dawn, second_night, last_dawn = 1200, 2600, 4800  # Body ticks from the late setup.
        for when, clock in ((first_dawn, 'dawn'), (second_night, 'night'), (last_dawn, 'dawn')):
            late.append(Retry(f'function mcylab:util/time_{clock}', sel('mother'), tries=1, first=when))
        shared_bed = [x, FEET, z - 3]
        bed = re.escape(f'BlockPos{{x={x}, y={FEET}, z={z-3}}}')
        expect += [rf'uuid={who} [^\n]*home chosen [^\n]*bed={bed}' for who in (mother, father, child, baby)]
        expect += [rf'uuid={who} [^\n]*line event=keep_child\b' for who in (mother, father)]
        expect += [rf'uuid=(?:{father}|{baby}) [^\n]*line event=mold_itch\b',
                   rf'uuid={mother} [^\n]*line event=mold_self_aware\b']
        forbid = [r'mold exile departed']
    else:
        watch = '洞口母油冷着脸，父油哭着让孩子走。看子油怎样回应推挤：会顶嘴咒骂，也会含泪回头道别。亲油一路跟着推到孩子出了家门范围，才断绝照护，治好后也不再相认。'
        expect += [rf'mold exile push subject={child}\b', rf'uuid={child} [^\n]*mold exile departed scope=family\b',
                   rf'uuid={child} [^\n]*mold exile answer=(CURSE|FAREWELL|SHRUG)\b', r'line event=drive_child_tearful\b',
                   *[rf'uuid={who} [^\n]*parent dismissed child={child}\b' for who in (mother, father)]]
        forbid = [r'mold exile deferred reason=dependent_or_carry']
    add(sid, 'den', title, title, watch, '约 4 分钟，可倍速' if keep else '约 1 分钟', lines, late=late,
        give=['mcyukkuri:mold_remover'], env={'time': 'night'} if keep else DAY, spot='family',
        vantage=(0, -1, 180, 24, 0) if keep else (0, 8, 180, 8),
        reads=[('读全家身体与住所', 'yukkuri status @e[tag=scn,distance=..48] raw')],
        smoke=dict(ticks=40 + last_dawn + 200 if keep else 1400, expect=expect, forbid=forbid))

    if keep:
        def verify_nights(window, trace):
            family = {who: {} for who in (mother, father, child, baby)}
            slept = {who: set() for who in family}
            positions = {}
            woke = set()
            setup = None
            for line in trace:
                row = json.loads(line.split('[trace] ', 1)[1])
                if row['k'] == 'win' and row['scene'] == sid:
                    setup = row['start'] + 40
                if setup is None or row['k'] != 'st' or row['u'] not in family:
                    continue
                who, tick = row['u'], row['t'] - setup
                state = family[who]
                state.update(row['st'])
                if state.get('sleeping') and state.get('nest') == 'INSIDE_SUPPORTED' and state.get('home') == shared_bed:
                    slept[who].add(tick)
                    x, _, z = state['p']
                    for px, pz, width, reach in positions.setdefault(tick, []):
                        if max(abs(x-px), abs(z-pz)) < (width + state['sleepWidth']) / 2 or math.hypot(x-px,z-pz) < reach + state['sleepReach']:
                            return f'Overlapping sleepers in DN-12 at +{tick}'
                    positions[tick].append((x,z,state['sleepWidth'],state['sleepReach']))
                if first_dawn < tick < second_night and state.get('sleeping') is False:
                    woke.add(who)
            if setup is None:
                return 'Missing DN-12 scene clock'
            together = set.intersection(*slept.values())
            # See the first night's sleep and a full twelve-sample contact interval on the longer second night.
            for start, dawn, required in ((0, first_dawn, 100), (second_night, last_dawn, 1200)):
                streak = 0
                for tick in range(start, dawn):
                    streak = streak + 1 if tick in together else 0
                    if streak >= required:
                        break
                else:
                    return f'Before dawn +{dawn}: missing {required} consecutive ticks asleep together in the shared nest'
            if woke != family.keys():
                return 'A family member never woke between the two nights'
            infected = {child}
            for hit in re.finditer(r'\[body\][^\n]*?uuid=([\w-]+) t=(\d+) [^\n]*?mold caught from=([\w-]+)', window):
                who, tick, source = hit.groups()
                if who in family and setup <= int(tick) < setup + last_dawn:
                    if source not in infected or who in infected:
                        return f'Invalid family contact at t={tick}: {source} to {who}'
                    infected.add(who)
            if infected != family.keys():
                return 'Missing contact infection by second dawn: ' + ', '.join(sorted(family.keys() - infected))
        # Dig one edge after the family's second dawn. Check the core and both real drops before a resident can collect them.
        smoke = S[-1].smoke
        smoke.update(verify_trace=True, verify=verify_nights, commands=[
            (5000, 'setblock ~1 ~-1 ~-4 minecraft:air'),
            (5001, 'execute if block ~ ~ ~-1 mcyukkuri:forest_burrow unless data block ~ ~ ~-1 LargeBeds[0] '
                   'unless data block ~ ~ ~-1 Residents[{Bed:0}] positioned ~0.5 ~ ~-2.5 '
                   'if entity @e[type=item,distance=..2,nbt={Item:{id:"minecraft:oak_leaves",count:1}}] '
                   'if entity @e[type=item,distance=..2,nbt={Item:{id:"minecraft:short_grass",count:1}}] '
                   'run say MCY_BURROW_SALVAGED')])
        smoke['expect'].append(r'MCY_BURROW_SALVAGED')
    else:
        x, z = BY_ID['den'].spots['family']

        def verify_departure(window, trace):
            departure = re.search(rf'uuid={child} t=(\d+) [^\n]*mold exile departed scope=family', window)
            if not departure:
                return 'The child never left the family'
            if not re.search(rf'\[action\][^\n]* end id=push [^\n]*target={child} moved=(?!0\.000\b)\d+\.\d+\b', window[:departure.start()]):
                return 'The shove did not move the child before it left'
            protest = re.search(rf'uuid={child} [^\n]*mold exile protest\b', window)
            if protest and re.search(rf'carry pickup [^\n]*purpose=RETRIEVE child={child}\b', window[protest.end():departure.start()]):
                return 'A guardian carried the departing child home'
            for parent in (mother, father):
                if not re.search(rf'uuid={parent} [^\n]*mold child verdict child={child} verdict=DRIVE_', window[:departure.start()]):
                    return f'The child left before guardian {parent} chose to drive it out'
            if re.search(rf'uuid={child} [^\n]*home chosen [^\n]* home=BlockPos\{{x={x}, y={FEET}, z={z-1}\}}', window[departure.end():]):
                return 'The sick child reclaimed its old den'
            for line in trace:
                row = json.loads(line.split('[trace] ', 1)[1])
                if row['k'] == 'st' and row['u'] == child and 'p' in row['st'] and row['t'] >= int(departure[1]):
                    px, py, pz = map(math.floor, row['st']['p'])
                    if x-2 <= px <= x+2 and z-5 <= pz < z and py == FEET:
                        return 'The sick child walked back inside its old den'
        S[-1].smoke.update(verify_trace=True, verify=verify_departure)


_mold_family('den_mold_child_exile', False)
_mold_family('den_mold_keep', True)


# The teen's independence is armed in NBT (Force); each family has its den as a starting home, and the farewell or departure is
# ordinary. The warm pair starts at the back of its mound with a grazed clearing beyond the door. The cool pair starts in
# the earthen den 32 blocks east. The far parent is taken to a closed stone-brick room outside the child's hearing range.
_indep = [box(-12, 0, -12, 12, 0, 14, 'minecraft:air', 'replace minecraft:short_grass')]
for _lane, _dx, _teen_dx, _parent_z, _teen_z, _attach, _label in (('warm', 0, 0, -4, -3, 85, '亚成·依恋高'),
                                                               ('cool', 32, 1, -2, -3, 20, '亚成·依恋低'),
                                                               ('far', -18, 1, 3, 4, 60, '亚成·亲油不在')):
    _pm, _pc = uid('indep_near', f'{_lane}_parent'), uid('indep_near', f'{_lane}_teen')
    _indep += [yk('reimu', _dx, _parent_z, f'{_lane}_parent', uuid=_pm, yaw=180, label='亲油', attachment=_attach, **pair_ready()),
               yk('reimu', _dx + _teen_dx, _teen_z, f'{_lane}_teen', stage='teen', uuid=_pc, yaw=180, lead=_lane == 'warm', nutrition=100, energy=100,
                  attachment=_attach, label=_label, kin=f'Mother:{uuid_nbt(_pm)}', extra='YukkuriIndependence:{Force:1b}'),
               f'yukkuri scenario kin {sel(f"{_lane}_parent")} {sel(f"{_lane}_teen")} false',
               *[f'yukkuri scenario home {sel(f"{_lane}_{who}")} ~{_dx} ~ ~-1' for who in ('parent', 'teen')]]
_indep += [box(38, 0, 1, 42, 3, 5, 'minecraft:stone_bricks'), box(39, 0, 2, 41, 2, 4, 'minecraft:air')]
add('indep_near', 'den', '亚成离巢：亲油在就告别', '亚成离巢：亲油在就告别',
    '三只亚成同时到了独立的时候。中间的洞里（依恋高）：亚成走到亲油跟前含泪说要去找自己的家，亲油哭着「要常回来看看」，两只贴脸好一会儿，'
    '亚成说「我走了」后离开，途中两次回头、最后喊「要保重」。送别时亲油会抽泣，孩子走远后则含泪微笑看着道路。右边（依恋低）：亚成爽快告别，亲油祝福，'
    '亚成鞠个躬就走，不回头，亲油看一眼就回去过日子。左边亲油被关在远处，约 3 分钟后亚成不告别直接离开。之后各自去找空洞或挖穴，亲缘与好感保留',
    '约 3 分钟内离开（可倍速）', _indep, env=DAY, spot='indep', retry='先“清空本场地”（洞口格要空）', vantage=(0, 16),
    # The far parent leaves only once the home is hers: a teen whose parents own another home is not yet of the family den.
    late=[f'tp {sel("far_parent")} ~40 ~ ~3'],
    reads=[('读依恋高的亚成', f'data get entity {sel("warm_teen")} YukkuriIndependence'),
           ('读依恋低的亚成', f'data get entity {sel("cool_teen")} YukkuriIndependence'),
           ('读不告别亚成', f'data get entity {sel("far_teen")} YukkuriIndependence'),
           ('读依恋高亲油的情绪', f'yukkuri status {sel("warm_parent")} raw')])

# A waterlogged floor makes the old den unsafe while its doorway remains dry enough to leave.
_sm, _sf, _sc = uid('den_move', 'mother'), uid('den_move', 'father'), uid('den_move', 'child')
add('den_move', 'den', '石洞进水，一家自行搬家', '石洞进水，一家自行搬家',
    '一家三口先住在小石洞。洞底进水，但洞口仍干燥；看亲油带子油沿地面去东边空土洞，到了新家鼓脸说出住家宣言。'
    '读三只的住所，确认一家都住到新洞；走进洞口可看领地反应。',
    '约 1 分钟', [
        # Six floor cells leave four beds after the doorway and store are reserved.
        box(0, 0, -6, 0, 1, -6, 'minecraft:air'),
        yk('reimu', -1, 1, 'mother', uuid=_sm, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_sf)}', boldness=15, **pair_ready()),
        yk('marisa', 1, 1, 'father', uuid=_sf, yaw=180, kin=f'Partner:{uuid_nbt(_sm)}', boldness=15, **pair_ready()),
        yk('reimu', 0, 2, 'child', stage='teen', uuid=_sc, yaw=180, nutrition=100, energy=100, boldness=15,
           kin=f'Mother:{uuid_nbt(_sm)},Father:{uuid_nbt(_sf)}', fam=TEEN_FAMILY),
        # Leave a qualified dry den and close the other two demonstration caves.
        box(-38, 0, -5, -34, 1, 0, 'minecraft:stone'), box(-20, 0, -5, -16, 1, 0, 'minecraft:stone'),
        ],
    late=[*[Retry(f'yukkuri scenario home {sel(n)} ~ ~ ~-1', sel(n), tries=1, first=1)
            for n in ('mother', 'father', 'child')],
          Retry(block(0, -1, -1, 'minecraft:oak_slab[type=top,waterlogged=true]'), sel('mother'), tries=1, first=30)],
    env=DAY, spot='small', vantage=(8, 14), reads=[('读搬家一家', 'yukkuri status @e[tag=scn,distance=..48] raw')],
    smoke=dict(ticks=2200, expect=[r'\[habitat\][^\n]* relocate begin .*movers=3 .*reason=flooded',
                                   r'\[habitat\][^\n]* relocate end .*movers=3 moved=3',
                                   r'\[home-claim\][^\n]* declared site=.*spoken=true'],
               forbid=[r'\[habitat\][^\n]* relocate begin .*reason=(?!flooded\b)',
                       r'\[habitat\][^\n]* relocate failed']))

add('home_greedy', 'den', '贪心占丝绒窝，可驱离', '贪心占丝绒窝，可驱离',
    '草窝和空丝绒窝都在屋檐下。胆大、低友善的魔理沙已有草窝，却自己走向丝绒窝并说住家宣言。'
    '等它到新窝后走近，观察转身、警告、鼓脸；停在近处还可能被轻撞。拒绝事件、抱起或贴近驱赶时，'
    '它会按性格骂回去、道别或哼一声。看魔理沙跺完脚再转身退走，离开时仍带怒容。直接打它时看它如何应对这一击。',
    '约 30 秒', [
        box(1, -1, 1, 9, 3, 8, 'minecraft:oak_planks', 'hollow'),
        block(5, 0, 8, 'minecraft:oak_door[facing=south,half=lower]'),
        block(5, 1, 8, 'minecraft:oak_door[facing=south,half=upper]'),
        block(3, 0, 3, 'mcyukkuri:straw_nest[facing=south]'),
        yk('marisa', 3, 5, 'greedy', label='魔理沙', boldness=100, friendliness=0, self_control=0, lead=True, **pair_ready()),
        ],
    late=[f'yukkuri scenario home {sel("greedy")} ~3 ~ ~3',
          block(7, 0, 3, 'mcyukkuri:velvet_nest[facing=south]')],
    env=DAY, spot='big', vantage=(5, 10, 180),
    capture=dict(shots=(40,), sequences=((65, 320, 4), (360, 480, 4)),
                 views={'': dict(position=(8.6, 1.2, 3.3), look_at=(6.9, .6, 5.0),
                                 criteria='固定机位从正前方看住家宣言：跳进、挺胸抬头、左右大幅转身俯视、两连跳、哼一声后得意定格，'
                                          '全程嚣张骄傲表情、不鼓脸；来客出现后才转向它鼓脸。')}),
    reads=[('读占领阶段','yukkuri homeclaim status'),('读魔理沙', f'yukkuri status {sel("greedy")} raw')],
    smoke=dict(ticks=900, expect=[r'\[home-claim\][^\n]* occupation chosen .*kind=bed .*previous=ResourceKey',
                                  r'\[home-claim\][^\n]* declared site=.*spoken=true'],
               forbid=[r'occupation_refused', r'never_arrived']))

# A previously learned skill as initial state. The resident decides whether to seal during the outing.
_seal_skill = {'den_seal': 2}
_seal_assessed = 'YukkuriSkills:{Assessed:["den_seal"]}'


def _screen_crossing(window, trace, residents, prepared=False):
    sealed = re.search(r'\[home\][^\n]* t=(\d+) [^\n]* den seal completed ', window)
    removed = re.search(r'\[home\] t=(\d+) [^\n]* den barrier removed pos=(-?\d+), (-?\d+), (-?\d+) [^\n]* age=(\d+) ', window)
    if not removed or not prepared and not sealed:
        return 'Missing a complete closed-screen interval'
    start = int(removed[1]) - int(removed[5]) if prepared else int(sealed[1])
    if start >= int(removed[1]):
        return 'Missing a complete closed-screen interval'
    # These south-facing dens have their one-cell notch at the removed screen's Z coordinate.
    mouth_x, mouth_z = int(removed[2]) + .5, int(removed[4]) + .5
    previous = {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] != 'st' or row['u'] not in residents or not start <= row['t'] < int(removed[1]):
            continue
        x, _, z = row['st']['p']
        last = previous.get(row['u'])
        if last and (last[1] >= mouth_z) != (z >= mouth_z):
            crossing_x = last[0] + (x - last[0]) * (mouth_z - last[1]) / (z - last[1])
            if abs(crossing_x - mouth_x) <= .5:
                return f"Resident {row['u']} crossed the sealed mouth at tick {row['t']}"
        previous[row['u']] = (x, z)
    if set(previous) != set(residents):
        return 'Missing resident positions while sealed'


def _family_screen():
    sid = 'den_barrier'
    names = ['mother', 'father', 'child', 'learner']
    ids = {name: uid(sid, name) for name in names}
    mother, father = (_uuid_text(sid, n) for n in names[:2])
    kin = kin_of(Mother=ids['mother'], Father=ids['father'])
    lines = [
        box(-12, 0, -8, 12, 0, 6, 'minecraft:air', 'replace minecraft:short_grass'),
        box(-11, 0, 7, 11, 0, 17, 'minecraft:short_grass', 'keep'),
        yk('reimu', 0, -5, 'mother', uuid=ids['mother'], label='会封洞的母油', lead=True, energy=40,
           nutrition=100, intelligence=90, self_control=100,
           boldness=20, attachment=95, kin=kin_of(Partner=ids['father']), skills=_seal_skill),
        yk('marisa', 0, 11, 'father', uuid=ids['father'], label='出门采草的父油', energy=100, nutrition=86, boldness=96,
           kin=kin_of(Partner=ids['mother']), extra=_seal_assessed),
        yk('marisa', 1, -4, 'child', stage='child', uuid=ids['child'], nutrition=96, kin=kin)]
    expect = [
        rf'\[forage\][^\n]*uuid={father}\b[^\n]* begin role=FAMILY phase=OUT',
        rf'\[forage\][^\n]*uuid={father}\b[^\n]* phase=GATHER -> BACK [^\n]* gathered=[1-9]\d* carried=[1-9]',
        rf'\[duty\][^\n]*uuid={mother}\b[^\n]* claimed kind=SEAL',
        rf'\[home\][^\n]*uuid={mother}\b[^\n]* den seal completed [^\n]* twigs 2 -> 1 danger=null absent=\[{father}\]',
        rf'\[duty\][^\n]*uuid={mother}\b[^\n]* released kind=SEAL end=DONE',
        rf'den call caller={father} callee={mother} relation=PARTNER',
        rf'\[forage\][^\n]*uuid={father}\b[^\n]* deposit home=[^\n]* moved=[1-9]']
    reads = [('读留守母油', f'yukkuri status {sel("mother")} raw'), ('读采草父油', f'yukkuri status {sel("father")} raw')]
    title = '父油出门采草，母油封洞口，父油回来呼唤'
    watch = '开场时父油已到南边草地，母油和孩子在洞里。看母油压枝封洞、亚成子油观察示范，再看父油采草回来呼唤伴侣、母油应声拆屏、父油把草送进粮堆。'
    learner = _uuid_text(sid, 'learner')
    lines.append(yk('reimu', -1, -3, 'learner', stage='teen', uuid=ids['learner'], nutrition=100, intelligence=96,
                    attachment=100, kin=kin, extra=_seal_assessed,
                    fam=TEEN_FAMILY.replace('Nourishment:276000L', 'Nourishment:160000L')))
    reads.append(('读亚成封洞技能', f'data get entity {sel("learner")} YukkuriSkills'))
    expect.extend([(rf'\[childhood\][^\n]*uuid={learner}\b[^\n]* den seal demonstration parent={mother}\b[^\n]* roll=[\d.]+ learned=(true|false)'
                    rf'|\[childhood\][^\n]*uuid={mother}\b[^\n]* den seal demonstration missed learner={learner}\b reason=(out_of_sight|asleep|away)\b'),
                   rf'den call answered by={mother}\b',
                   rf'den barrier removed [^\n]* reason=answered_call [^\n]*twig_returned=true opener={mother}\b'])
    residents = {_uuid_text(sid, n) for n in names}

    def first_handoff(window, trace):
        opened = re.search(r'\[home\] t=\d+ [^\n]* den barrier removed [^\n]+', window)
        if not opened or 'reason=answered_call' not in opened[0] or f'opener={mother}' not in opened[0]:
            return 'The mother did not open the first screen for the returning father'
        call = window.find(f'den call caller={father} callee={mother} relation=PARTNER')
        answer = window.find(f'den call answered by={mother}', call)
        if not 0 <= call < answer < opened.start():
            return 'Missing the partner call and answer before the first screen opened'
        return _screen_crossing(window, trace, residents)

    add(sid, 'den', title, title, watch, '约 1–2 分钟，可倍速',
        [*lines, *[home(n, 0, -1) for n in names], f'yukkuri scenario twigs {sel("mother")} 2'],
        late=[f'yukkuri scenario forage_out {sel("father")}'],
        env=DAY, spot='family', vantage=(3, 10), reads=reads,
        smoke=dict(ticks=3900, expect=expect, verify_trace=True, verify=first_handoff,
                   forbid=[r'\[body\][^\n]* invariant\b', r'den seal aborted reason=(path_stuck|cells_changed)']))


_family_screen()


def _timeout_screen():
    sid = 'den_call_timeout'
    ids = {n: uid(sid, n) for n in ('mother', 'father', 'child')}
    mother, father = (_uuid_text(sid, n) for n in ('mother', 'father'))
    x, z = BY_ID['den'].spots['family']
    core = (x << 38) | (((z - 1) & 0x3ffffff) << 12) | FEET
    # This hand-off starts at the return: one real twig is already in the screen, one is still in stock.
    screen = f'mcyukkuri:den_barrier{{Den:{core}L,Owner:{uuid_nbt(ids["mother"])},AbsentParents:[{uuid_nbt(ids["father"])}]}}'
    title = '母油睡着，父油呼唤无应答后自己拆屏'
    add(sid, 'den', title, title, '父油衔草回到已封的洞口，按亲缘呼唤睡着的伴侣。看它等候、放下草、拆屏，再把草送回粮堆。',
        '约 1 分钟', [
            yk('reimu', 0, -5, 'mother', uuid=ids['mother'], label='疲倦的母油', energy=20, rest_wait=0,
               nutrition=100, kin=kin_of(Partner=ids['father']), skills=_seal_skill),
            yk('marisa', 0, 3, 'father', uuid=ids['father'], label='衔草回家的父油', lead=True, energy=100,
               attachment=25, self_control=30, nutrition=86, boldness=96, kin=kin_of(Partner=ids['mother']),
               extra='YukkuriForageLoad:{id:"minecraft:short_grass",count:4}'),
            yk('marisa', 1, -4, 'child', stage='child', uuid=ids['child'], nutrition=96,
               kin=kin_of(Mother=ids['mother'], Father=ids['father'])),
            *[home(n, 0, -1) for n in ids], f'yukkuri scenario twigs {sel("mother")} 1'],
        late=[block(0, 0, 0, screen), 'execute store result block ~ ~ ~ LaidAt long 1 run time query gametime'],
        env=DAY, spot='family', vantage=(3, 10),
        reads=[('读疲倦母油', f'yukkuri status {sel("mother")} raw'), ('读衔草父油', f'yukkuri status {sel("father")} raw')],
        smoke=dict(ticks=1800, expect=[rf'\[sleep\][^\n]*uuid={mother}\b[^\n]* begin ',
                                       rf'den call caller={father} callee={mother} relation=PARTNER',
                                       rf'uuid={father}\b[^\n]* den call unanswered waited=',
                                       rf'den barrier removed [^\n]* reason=self_opened [^\n]*twig_returned=true opener={father}\b',
                                       rf'\[forage\][^\n]*uuid={father}\b[^\n]* deposit home=[^\n]* moved=4\b'],
                   forbid=[r'\[body\][^\n]* invariant\b', r'den call answered by='], verify_trace=True,
                   verify=lambda w, tr: _screen_crossing(w, tr, {_uuid_text(sid, n) for n in ids}, prepared=True)))


_timeout_screen()


def _den_guard(name, spot):
    """The hunter arrives while the resident is in the five-by-five cavity behind the notch."""
    x0, door_z = BY_ID['den'].spots[spot]
    return f'@e[tag={tag(name)},x={x0 - 2},y={FEET},z={door_z - 5},dx=4,dy=1,dz=4]'


add('den_barrier_predator', 'den', '会封洞的灵梦躲避捕食种', '会封洞的灵梦躲避捕食种',
    '会封洞的灵梦带着 2 根洞口枝条。灵梦进洞后，南面出现饥饿蕾米莉亚。看它察觉危险后封洞，等附近没有捕食种再拆开。',
    '约 1–2 分钟，可倍速', [
        box(-9, 0, -5, 9, 0, 14, 'minecraft:air', 'replace minecraft:short_grass'),
        item('mcyukkuri:core_filling', 1, 0, 21, 'filling'),
        yk('reimu', 0, -1, 'wise', label='会封洞的灵梦', boldness=20, self_control=100, skills=_seal_skill, lead=True, **pair_ready()),
        home('wise', 0, -1), f'yukkuri scenario twigs {sel("wise")} 2'],
    late=[Retry(yk('remilia', 0, 12, 'hunter', label='饥饿捕食种', nutrition=48, energy=95, boldness=80),
                _den_guard('wise', 'indep'), tries=20, every=20, first=1)],
    env=DAY, spot='indep', vantage=(4, 12),
    reads=[('读灵梦', f'yukkuri status {sel("wise")} raw'), ('读捕食种', f'yukkuri status {sel("hunter")} raw')],
    smoke=dict(ticks=1800, expect=[r'\[home\][^\n]* den seal completed [^\n]* danger=[0-9a-f-]{36} absent=\[\]',
                                   r'den barrier removed [^\n]* reason=danger_passed [^\n]*twig_returned=true'],
               forbid=[r'\[body\][^\n]* invariant\b']))

_seal_threat_wise = _uuid_text('den_barrier_threat', 'wise')


def _seal_threat_press(_window, trace):
    actor = f'"u":"{_seal_threat_wise}","k":"st"'
    if not any(actor in line and '"behavior":"nest_press"' in line for line in trace):
        return 'Threatened Reimu never showed the den press'


add('den_barrier_threat', 'den', '会封洞的灵梦被居民打回家封洞', '会封洞的灵梦被居民打回家封洞',
    '会封洞的灵梦叼着两份草，在洞外挨了居民一下，掉下草后立即转身逃回洞里，用洞口枝条封住。'
    '居民还在近旁时屏一直挡着；它自己忍过一阵后拆屏、把枝条还回粮堆。',
    '约 1 分钟，可倍速', [
        box(-9, 0, -7, 9, 0, 16, 'minecraft:air', 'replace minecraft:short_grass'),
        yk('reimu', 0, 2, 'wise', uuid=uid('den_barrier_threat', 'wise'), label='会封洞的灵梦',
           intelligence=85, boldness=15, self_control=100, skills=_seal_skill, lead=True,
           extra='YukkuriForageLoad:{id:"minecraft:short_grass",count:2}', **pair_ready()),
        # The resident starts well south of the mouth: it follows the panicking yukkuri slowly, so the doorway is clear while the screen goes up.
        human('oniisan', 3, 9, 'threat', 0, attitude=0, yaw=180, label='虐派居民'),
        set_status('wise', 'mood.unease', 60),
        home('wise', 0, -1), f'yukkuri scenario twigs {sel("wise")} 2'],
    late=[Retry(f'damage {sel("wise")} 1 minecraft:player_attack by {sel("threat")}', sel('wise'), tries=1, first=1)],
    env=DAY, spot='indep', vantage=(0, 14, 180),
    reads=[('读灵梦', f'yukkuri status {sel("wise")} raw'), ('读居民', f'yukkuri status {sel("threat")} raw')],
    smoke=dict(ticks=1900, expect=[
        rf'\[threat\][^\n]*uuid={_seal_threat_wise}\b[^\n]* struck stance=FLEE\b',
        rf'\[forage\][^\n]*uuid={_seal_threat_wise}\b[^\n]* drop item=minecraft:short_grass count=2 reason=hurt\b',
        rf'\[punishment\][^\n]*uuid={_seal_threat_wise}\b[^\n]* response=ESCAPE\b',
        rf'\[home\][^\n]*uuid={_seal_threat_wise}\b[^\n]* den seal decided [^\n]* eta=[\d.]+ need=[\d.]+ slack=[\d.]+ seals=true',
        rf'\[home\][^\n]*uuid={_seal_threat_wise}\b[^\n]* den seal completed [^\n]* placed=1 twigs 2 -> 1 danger=[0-9a-f-]{{36}} absent=\[\]',
        rf'\[home\][^\n]*den barrier removed [^\n]*owner={_seal_threat_wise}\b reason=danger_passed [^\n]*twig_returned=true opener={_seal_threat_wise}\b'],
        forbid=[r'\[body\][^\n]* invariant\b'], verify=_seal_threat_press, verify_trace=True))

_decoy = 'den_lead_away'
_decoy_x, _decoy_z = BY_ID['den'].spots['family']
_decoy_mother, _decoy_hunter = (_uuid_text(_decoy, n) for n in ('mother', 'hunter'))


def _decoy_distance(window, trace):
    chosen = re.search(rf'uuid={_decoy_mother} t=(\d+)[^\n]*home tactic stance=LEAD_AWAY', window)
    ended = re.search(rf'uuid={_decoy_mother} t=(\d+)[^\n]*lead away ended', window)
    if not chosen or not ended:
        return 'Missing a complete decoy episode'
    frames = {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and row['u'] in (_decoy_mother, _decoy_hunter) and int(chosen[1]) <= row['t'] <= int(ended[1]):
            frames.setdefault(row['t'], {})[row['u']] = row['st']['p']
    for pair in frames.values():
        if len(pair) == 2:
            mother, hunter = pair[_decoy_mother], pair[_decoy_hunter]
            if math.hypot(mother[0] - _decoy_x - .5, mother[2] - _decoy_z - .5) >= 12 and math.dist(mother, hunter) <= 16:
                return
    return 'The decoy never reached 12 blocks from the mouth with the hunter within 16'


add(_decoy, 'den', '母油把捕食种引离有孩子的洞', '母油把捕食种引离有孩子的洞',
    '两只赤油留在洞内。看灵梦留在洞外，朝远离家的地方跑，回身鼓脸喊话，把蕾米莉亚引走。',
    '约 1 分钟', [
        yk('reimu', 0, 3, 'mother', uuid=uid(_decoy, 'mother'), label='引开捕食种的母油', lead=True,
           intelligence=85, self_control=70, attachment=90, friendliness=80, boldness=50, skills=_seal_skill),
        *[yk('reimu', x, -4, name, uuid=uid(_decoy, name), stage='baby', kin=kin_of(Mother=uid(_decoy, 'mother')),
             childhood=CHILDHOOD_CRAWLING_READY) for x, name in ((-1, 'baby1'), (1, 'baby2'))],
        *[home(n, 0, -1) for n in ('mother', 'baby1', 'baby2')], f'yukkuri scenario twigs {sel("mother")} 2'],
    late=[Retry(yk('remilia', 0, 14, 'hunter', uuid=uid(_decoy, 'hunter'), label='饥饿捕食种', nutrition=48, energy=95, boldness=80),
                f'@e[tag={tag("mother")},x={_decoy_x-8},y={FEET},z={_decoy_z+1},dx=16,dy=2,dz=14]', first=1, every=20)],
    env=DAY, spot='family', vantage=(5, 13), reads=[('读引敌母油', f'yukkuri status {sel("mother")} raw')],
    smoke=dict(ticks=1000, expect=[rf'uuid={_decoy_mother}\b[^\n]* home tactic stance=LEAD_AWAY',
                                  rf'uuid={_decoy_mother}\b[^\n]* lead away taunt [^\n]*attacker={_decoy_hunter}',
                                  rf'uuid={_decoy_mother}\b[^\n]* lead away ended reason=(led_away|expired|threat_lost)'],
               verify_trace=True, verify=_decoy_distance,
               forbid=[rf'prey bite target=#\d+ uuid={_uuid_text(_decoy, n)}\b' for n in ('baby1', 'baby2')],
               allow_fate=[dict(kind='death', who=f'uuid={_decoy_mother} ', match=r'cause=mob\b', why='the decoy takes the real hunt')]))
