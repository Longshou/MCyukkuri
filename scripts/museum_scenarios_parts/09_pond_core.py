# ====================================================================================================== pond (anchor: the bank cell at feet level; water is one row down)
def captain(name='a', *, dx=0, dz=0, **kw):
    return yk('marisa', dx, dz, name, aquatic=True, yaw=-90, lead=True, **pair_ready(), **kw)


def pond(sid, title, hint, watch, wait, lines, note='', vantage=(-6, 6, -90), **kw):
    # Restore the entire shared field before actors spawn; specialised scenes may override it below.
    add(sid, 'pond', title, hint, watch, wait, [*BY_ID['pond'].biome_commands(), *lines],
        env=DAY, note=note, vantage=vantage, **kw)


def bank_dx(dz):
    """x offset (from the anchor bank cell) of the bank cell on the row dz south of it: the pond's shore is not straight."""
    pond_ = BY_ID['pond']
    z = pond_.pz + dz
    x = pond_.px - pond_.RX - 3
    while pond_.depth(x, z) == 0:
        x += 1
    return x - 1 - pond_.bank[0]


def water_at(dz, out):
    """Water position ``out`` blocks east of the bank cell's centre; fractional berths retain their real clearance."""
    return f'~{bank_dx(dz) + out + .5} ~-1 ~{dz + .5}'


def _bank_food(item_id='minecraft:apple'):
    """One real portion on the dry bank at each side of the pond (west, east, north, south), a block back from the rim: from
    anywhere on the water a hungry aquatic owner has food of hers within her meal search of some bank (FEATURES 食物)."""
    pond_ = BY_ID['pond']
    out = []
    for ux, uz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        x, z = pond_.px, pond_.pz
        while pond_.depth(x, z):
            x, z = x + ux, z + uz
        out.append(item(item_id, 1, x + ux - pond_.bank[0], z + uz - pond_.bank[2], 'food'))
    return out


def pond_rim_seagrass(every=2):
    """Real finite seagrass along the pond's shallow rim (depth 1), so the shore an aquatic family comes ashore on always has the
    water plant it picks from the bank above: vanilla never regrows it, and clearing the field puts the water back."""
    pond_ = BY_ID['pond']
    return [block(x - pond_.bank[0], -1, z - pond_.bank[2], 'minecraft:seagrass')
            for x in range(pond_.px - pond_.RX, pond_.px + pond_.RX + 1)
            for z in range(pond_.pz - pond_.RZ, pond_.pz + pond_.RZ + 1)
            if pond_.depth(x, z) == 1 and (x + z) % every == 0]


def afloat(owner, water, aboard=True, crew=None, food=0, middle=False, stick=False, facing=0):
    """`scenario afloat`: the owner sets her real hat down at that water position with ``food`` apples (0..8)
    (and, with ``stick``, one real stick to paddle with). The captain takes her seat first, then each young takes an admitted
    seat. A stick needs a crew argument (the owner's own selector when she is alone)."""
    tail = f' {food}' + (' middle' if middle else ' stick' if stick else '') if food or middle or stick else ''
    if facing:
        tail += f' {facing}'
    return f'yukkuri scenario afloat {sel(owner)} {water} {"true" if aboard else "false"}' + (f' {crew}' if crew else '') + tail


def afloat_now(owner, water, aboard=True, crew=None, **kw):
    """`afloat` in the same tick as the summons (not two seconds later): the owner has had no tick of her own, so her hat is never
    set down by her own place_prop while the setup seats her (the line is wrapped so it is not deferred like the others)."""
    return f'execute as {sel(owner)} run ' + afloat(owner, water, aboard, crew, **kw)


_HAT = '@e[type=mcyukkuri:placed_hat,distance=..48,limit=1,sort=nearest]'
_DESIRE_NOTE_EARLY = '读 aquatic.desire 看每个欲望的得分与来由。'


def _seat_family(size=.35, nourishment=147000, age=144000):
    """A real saved body and nourishment near a seat boundary; ordinary loaded ticks perform subsequent growth."""
    return f'Version:3,Initialized:1b,Age:{age}L,Nourishment:{nourishment}L,AdultScale:1f,Scale:{size}f'


def _stray_rain_verify(log):
    marisa = _uuid_text('stray_rain', 'rain_stray')
    damp_exit_collapse = (
        r'found rain stage=0 -> 1 shelter=(BlockPos\{[^\n]+?\})'
        rf'[\s\S]*?uuid={marisa}[^\n]* found leaving target=\1 [^\n]*reason=soaked_or_unsafe'
        rf'[\s\S]*?uuid={marisa}[^\n]* found exited shelter=\1'
        rf'[\s\S]*?uuid={marisa}[^\n]* shelter response started sky=RAIN stage=RUN cover='
        r'[\s\S]*?found rain stage=1 -> 2 shelter=\1'
        r'[\s\S]*?drop item=minecraft:paper count=1 source=shelter at=\1 reason=rain_collapse'
        r'[\s\S]*?found removed shelter=\1 [^\n]*reason=rain_collapse'
    )
    if not re.search(damp_exit_collapse, log):
        return 'the rain-scene Marisa did not leave her damp carton before it collapsed'
    walker = _uuid_text('stray_rain', 'wall_stray')
    if not re.search(rf'\[meal\][^\n]*uuid={walker}[^\n]* swallowed item=minecraft:apple\b', log):
        return 'the wall-side Marisa did not reach and eat her apple'


next(s for s in S if s.id == 'stray_rain').smoke['verify'] = _stray_rain_verify


def _stray_rubbish_verify(log):
    mouth = uid('stray_rubbish', 'open_stray')
    failure = _family_search_order(log, [
        (mouth, r'garbage sniff [^\n]*tipped=true'),
        (mouth, r'garbage nose_in '),
        (mouth, r'garbage pickup item=minecraft:apple [^\n]*holder=mouth [^\n]*marker=pull'),
        (mouth, r'swallowed item=minecraft:apple\b'),
    ])
    if failure:
        return failure
    bold = uid('stray_rubbish', 'bold_stray')
    travels = re.findall(rf'uuid={_uuid_of(bold)}[^\n]* garbage ram [^\n]*strokeTravel=([\d.]+)', log)
    if not travels or min(map(float, travels)) < .18:
        return f'expected at least .18 block of ram travel, found {travels}'
    braided = re.search(rf'uuid={_uuid_text("stray_rubbish", "bold_stray")} [^\n]*garbage pickup item=minecraft:apple [^\n]*holder=braid', log)
    return _family_search_order(log, [
        (bold, r'garbage ram [^\n]*tipped=false rock=strong'),
        (bold, r'garbage ram [^\n]*tipped=true'),
        (bold, r'garbage reroute [^\n]*why=knocked_over'),
        (bold, r'garbage nose_in '),
        (bold, r'garbage pickup item=minecraft:apple [^\n]*holder=(braid|mouth)'),
        *([(bold, r'braid handover item=minecraft:apple clip=braid_eat_bin tick=28\b')] if braided else []),
        (bold, r'swallowed item=minecraft:apple\b'),
    ])


next(s for s in S if s.id == 'stray_rubbish').smoke['verify'] = _stray_rubbish_verify


def _bank_pickup_retries(window):
    """A carrier twice takes the same child and fails immediately at hop range without approaching the hull."""
    held, refused = {}, {}
    events = r'\[(?:action|intent)\][^\n]*? uuid=(\S+) [^\n]*? (?:end id=grab outcome=DONE detail=mounted target=(\S+)|start id=(set_out|walk|beckon|hop)\b|end id=set_out result=(\S+) reasons=\[([^\]]*)\])'
    for carrier, child, action, result, reasons in re.findall(events, window):
        if child:
            held[carrier] = child
        elif action:
            if action != 'set_out' and carrier in held:
                refused.pop(carrier, None)
            held.pop(carrier, None)
        elif result:
            child = held.pop(carrier, None)
            if child and result == 'EXHAUSTED' and reasons == 'TOO_FAR:0':
                if refused.get(carrier) == child:
                    return f'{carrier} twice picked up {child} without approaching the hull'
                refused[carrier] = child
            else:
                refused.pop(carrier, None)


def _carried_boarding(window, owner):
    """The keeper boarded from the bank with a carried child, and it ended on the hull. A window where she never tried is a premise
    that did not happen, so the check fails instead of passing on nothing."""
    attempts = list(re.finditer(r'\[aquatic\][^\n]*uuid=' + owner + r' [^\n]* boarding from bank child=(\S+) hat=(\S+)', window))
    if not attempts:
        return 'keeper never boarded from the bank with a carried child (premise not exercised)'
    for attempt in attempts:
        child, hat = map(re.escape, attempt.groups())
        following = window[attempt.end():]
        contact = re.search(r'\[body\][^\n]*uuid=' + owner + r' [^\n]* mount host=' + hat + r' cause=hop_contact\b', following)
        release = re.search(r'\[body\][^\n]*uuid=' + child + r' [^\n]* dismount at=', following)
        if contact is None or release and release.start() < contact.start():
            return f'keeper failed to board {attempt[2]} with carried child {attempt[1]}'


def _overload_hull(window, scene, parent, exceptional, places=None):
    """What both overload scenes share: the family's hat was placed, every young took its real seat, and each logged capsize
    roll agrees with the logged load. Returns (failure, owner, boats, overloaded_growth). `places` names the seat each young's
    real state produces; by default all three are still crawling, so the largest takes the head and the rest fill the brim
    (user 2026-11-05)."""
    owner = _uuid_text(scene, parent)
    hulls = re.findall(r'\[hat\][^\n]* placed owner=' + owner + r' hat=(\S+)', window)
    if not hulls:
        return f'{parent}: hat was not placed', owner, None, False
    boat = re.escape(hulls[0])
    boats = '(?:' + '|'.join(map(re.escape, dict.fromkeys(hulls))) + ')'
    if places is None:
        places = ((1, 'CABIN'), (2, 'BRIM'), (3, 'HEAD')) if exceptional else ((2, 'BRIM'), (3, 'HEAD'))
    for child, place in places:
        if not re.search(r'\[hat\][^\n]*uuid=' + _uuid_text(scene, parent + str(child))
                         + r'[^\n]* scenario place hat=' + boat + r' place=' + place + r'\b', window):
            return f'{parent}{child}: missing real {place} placement', owner, boats, False
    rolls = re.findall(r'\[aquatic\][^\n]*uuid=' + boats + r'[^\n]* load changed event=(\w+) body=\S+ '
                       r'load=(\S+) capacity=(\S+) chance=(\S+) roll=(\S+) result=(\w+)', window)
    overloaded_growth = False
    for event, load, capacity, chance, roll, result in rolls:
        load, capacity, chance, roll = map(float, (load, capacity, chance, roll))
        expected = 0 if load <= capacity else 1 - capacity / load
        if abs(chance - expected) > 1e-9 or (result == 'capsize') != (roll < chance):
            return f'{parent}: overload chance or result disagrees with the logged load and roll', owner, boats, overloaded_growth
        overloaded_growth |= event == 'growth' and load > capacity
    return None, owner, boats, overloaded_growth


def _overload_tradeoff(window, scene='hat_crowd', families=None):
    """The overloaded boat (FEATURES 载重, 超载断亲): checks each staged family's verdict (DRIVE_TEARFUL / DRIVE_COLD) and
    child response branch (plead / stare / sulk). Every beat logs shown==declared, unaware siblings never get PARTING,
    and cut children never settle back inside the 16-block banned range."""
    if families is None:
        families = (
            ('normal', 'DRIVE_TEARFUL', 'plead', None, None),
            ('cold', 'DRIVE_COLD', 'plead', None, None),
        )
    for parent, verdict, branch, far_actor, places in families:
        failure, owner, boats, overloaded_growth = _overload_hull(window, scene, parent, False, places)
        if failure:
            return failure
        if not overloaded_growth:
            return f'{parent}: no real growth into overload'
        capsized = re.search(r'\[hat\][^\n]*uuid=' + boats + r' t=(\d+)[^\n]* capsized cause=too_heavy\b[^\n]*thrown=[1-9]', window)
        if capsized is None:
            return f'{parent}: no overload capsize in this random run (premise not exercised)'
        child = _uuid_text(scene, parent + '3')
        drawn = re.search(r'\[family\][^\n]*uuid=' + owner + r' t=(\d+)[^\n]* overload verdict child=(\S+) verdict=(\w+) ', window[capsized.start():])
        if drawn is None:
            return f'{parent}: no overload verdict after the capsize'
        if drawn[2] != child or drawn[3] != verdict:
            return f'{parent}: verdict {drawn[3]} for {drawn[2]}, expected {verdict} for the largest young {child}'
        after = window[capsized.start() + drawn.end():]
        beat = re.search(r'\[family\][^\n]*uuid=' + owner + r' t=(\d+) dim=\S+ pos=([^,]+), [^,]+, (\S+) overload beat start child=' + child + r' verdict=' + verdict, after)
        if beat is None:
            return f'{parent}: the verdict was never played out (no beat start)'
        centre = (int(beat[2]), int(beat[3]))
        failure = _driven_out_order(window, after, scene, parent, verdict, branch, owner, child, centre, far_actor=far_actor)
        if failure:
            return failure
    return None


def _driven_out_order(window, after, scene, parent, verdict, branch, owner, child, centre, far_actor=None):
    """The order of the cut child's departure (FEATURES 超载断亲): her verdict beat -> the order -> the child's stat branch
    (plead / stare / sulk) -> it turns and walks -> it is gone (distance and sight) -> only then the cut and the parting feeling
    of the parent and perceiving siblings. Every beat logged with its declared and its shown face must show the declared one, the
    child never calls for the parent it was driven from, and never settles back inside the banned range."""
    def at(pattern, text=after):
        found = re.search(pattern, text)
        return (int(found[1]), found) if found else (None, None)
    ordered, _ = at(r'\[family\][^\n]*uuid=' + child + r' t=(\d+)[^\n]* driven out ordered parent=' + owner + r' verdict=' + verdict)
    if ordered is None:
        return f'{parent}: the child was never ordered out'
    begins, _ = at(r'\[family\][^\n]*uuid=' + child + r' t=(\d+)[^\n]* driven out begins parent=' + owner)
    if begins is None:
        return f'{parent}: the ordered child never began its departure (it drowned, or never stood on dry ground)'
    turned, _ = at(r'\[choreo\][^\n]*uuid=' + child + r' t=(\d+)[^\n]* beat=turn_away face=')
    departed, found = at(r'\[family\][^\n]*uuid=' + child + r' t=(\d+)[^\n]* driven out departed parent=' + owner
                         + r' verdict=' + verdict + r' distance=(\S+) sight=(true|false)')
    if turned is None or departed is None:
        return f'{parent}: the child began to leave but never turned and got away (turned={turned} departed={departed})'
    distance, seen = float(found[2]), found[3] == 'true'
    if not (begins >= ordered and turned >= begins and departed >= turned):
        return f'{parent}: wrong order ordered={ordered} begins={begins} turned={turned} departed={departed}'
    if distance < 8 or seen and distance < 16:
        return f'{parent}: the cut fell while the child was still near or in sight (distance={distance} sight={seen})'
    cut, _ = at(r'\[family\][^\n]*uuid=' + owner + r' t=(\d+)[^\n]* parent dismissed child=' + child + r'[^\n]* direction=both')
    if cut is None or cut < departed:
        return f'{parent}: the tie was cut {"never" if cut is None else "before"} the child was gone (cut={cut} departed={departed})'
    if len(re.findall(r'\[family\][^\n]*uuid=' + owner + r'[^\n]* parent dismissed child=[^\n]* reason=boat_overload', window)) != 1:
        return f'{parent}: must cut exactly one child at the one capsize'
    later = after[re.search(r'\[family\][^\n]*uuid=' + child + r' t=\d+[^\n]* driven out ordered', after).start():]
    if re.search(r'\[aquatic\][^\n]*uuid=' + owner + r'[^\n]* rescue start faller=' + child, later):
        return f'{parent}: the driven child was regathered after the order'
    if re.search(r'\[family\][^\n]*uuid=' + child + r'[^\n]*(?:child separation lost=(?:true|false)|missing kin call)', later):
        return f'{parent}: the driven child called for or looked for the parent that drove it out'
    if re.search(r'\[speech\][^\n]*uuid=' + child + r'[^\n]* line event=(?:lost_parent|caregiver|wait_family)\b', later):
        return f'{parent}: the driven child spoke a parent-seeking line (lost_parent/caregiver/wait_family) after the order'
    # stat branch & rebuff proof
    if branch == 'plead':
        if not re.search(r'\[choreo\][^\n]*uuid=' + child + r'[^\n]* beat=plead face=teary line=mold_plea declared=teary', later):
            return f'{parent}: pleader child did not play beat=plead face=teary line=mold_plea'
        # the rebuff is asserted on the pleader really standing at her (within mouth reach), never on where it was ordered from
        reached = re.search(r'\[family\][^\n]*uuid=' + child + r' t=\d+[^\n]* driven out pleader at parent driver=' + owner + r' distance=(\S+)', later)
        if reached is None:
            gave_up = re.search(r'\[family\][^\n]*uuid=' + child + r' t=\d+[^\n]* driven out pleader gave up short of parent[^\n]*', later)
            return f'{parent}: the pleader never reached her ({gave_up[0][-60:] if gave_up else "no pleader at parent line"})'
        rebuff_beat = 'rebuff_shove face=angry line=none declared=angry' if verdict == 'DRIVE_COLD' else 'rebuff_turn_back face=cry line=none declared=cry'
        if not re.search(r'\[choreo\][^\n]*uuid=' + owner + r'[^\n]* beat=' + rebuff_beat, later[reached.end():]):
            return f'{parent}: parent did not rebuff the pleader standing at her ({rebuff_beat}, reached at distance {reached[1]})'
    if branch == 'stare' and not re.search(r'\[choreo\][^\n]*uuid=' + child + r'[^\n]* beat=stare face=teary line=none declared=teary', later):
        return f'{parent}: freeze child did not play beat=stare face=teary line=none'
    if branch == 'sulk' and not re.search(r'\[choreo\][^\n]*uuid=' + child + r'[^\n]* beat=turn_away face=hmph line=sulk_huff declared=hmph', later):
        return f'{parent}: sulk child did not play beat=turn_away face=hmph line=sulk_huff'
    # the order is answered at once (social orders pre-empt the child's occupants)
    if begins - ordered > 60:
        return f'{parent}: the child began {begins - ordered} ticks after the order (limit 60)'
    # the tearful parent's parting feeling begins at the order, everyone else's from real separation, never before the child is gone
    for early in re.finditer(r'\[emotion\][^\n]*uuid=(\S+) t=(\d+)[^\n]* course begun kind=parting[^\n]* about=' + child, later):
        if early[1] == owner and verdict == 'DRIVE_TEARFUL':
            if int(early[2]) < ordered:
                return f'{parent}: her parting feeling began at {early[2]}, before the order at {ordered}'
        elif int(early[2]) < departed:
            return f'{parent}: someone began the parting feeling at {early[2]}, before the child was gone at {departed}'
    family_uuids = '|'.join([owner] + [_uuid_text(scene, parent + str(i)) for i in (1, 2)])
    for early in re.finditer(r'\[speech\][^\n]*uuid=(?:' + family_uuids + r') t=(\d+)[^\n]* line event=parting_(?:gaze|over)\b', after):
        if int(early[1]) < departed:
            return f'{parent}: parting line fired at {early[1]}, before the child was gone at {departed}'
    sib2 = _uuid_text(scene, parent + '2')
    sib2_dead = re.search(r'\[body\][^\n]*uuid=' + sib2 + r' t=(\d+)[^\n]* death settled', window)
    cries = re.findall(r'\[family\][^\n]* t=(\d+) [^\n]*driven out (?:sibling cries|sibling missed departure) child=' + child, later)
    if not cries and not (sib2_dead and int(sib2_dead[1]) <= turned):
        return f'{parent}: no sibling reacted when the child turned to go'
    if any(int(t) < begins for t in cries):
        return f'{parent}: a sibling cried before the child began to leave'
    # the staged far sibling (beyond sight and hearing, out of her family range) misses the departure and stays silent about it
    if far_actor:
        far = _uuid_text(scene, far_actor)
        if not re.search(r'\[family\][^\n]*uuid=' + far + r' t=\d+[^\n]* driven out sibling missed departure child=' + child + r' reason=out_of_range', later):
            return f'{parent}: the far sibling did not log missing the departure (out_of_range)'
        if re.search(r'uuid=' + far + r'[^\n]*(?:driven out sibling (?:cries|parts)|beat=sibling_cry|beat=parting_begins|course begun kind=parting)', later):
            return f'{parent}: the far sibling reacted to a departure it never perceived'
    # witness rule: a sibling that missed the departure must never get PARTING about this child
    for missed in re.finditer(r'\[family\][^\n]*uuid=(\S+) t=\d+[^\n]* driven out sibling missed departure child=' + child, later):
        sib = missed[1]
        if re.search(r'\[family\][^\n]*uuid=' + sib + r'[^\n]* driven out sibling parts child=' + child, later) \
                or re.search(r'\[emotion\][^\n]*uuid=' + sib + r'[^\n]* course begun kind=parting[^\n]* about=' + child, later):
            return f'{parent}: sibling {sib} missed departure yet received PARTING about {child}'
    # banned range: once departed, the cut child must never claim a home/roof within 16 blocks of the family's home
    after_dep = later[re.search(r'\[family\][^\n]*uuid=' + child + r' t=\d+[^\n]* driven out departed', later).start():]
    for home in re.finditer(r'\[home\][^\n]*uuid=' + child + r'[^\n]* assigned kind=\w+[^\n]*home=GlobalPos\[[^,]+, BlockPos\{x=(-?\d+), y=-?\d+, z=(-?\d+)\}\]', after_dep):
        dx, dz = int(home[1]) - centre[0], int(home[2]) - centre[1]
        if dx * dx + dz * dz < 16 * 16:
            return f'{parent}: cut child assigned home ({home[1]},{home[2]}) within 16 blocks of old home {centre}'
    # the severance is mutual and the child is a stray that lives on: both sides' queries and identity, then within 2 minutes of the
    # departure (the child has left) it does something of its own outside the old range (not the mourning that used to pin it in place)
    if not re.search(r'\[identity\][^\n]*uuid=' + child + r' t=(\d+)[^\n]* changed from=\w+ to=stray cause=severed', after_dep):
        return f'{parent}: the severed child never became a stray (no identity changed to=stray cause=severed after it left)'
    if re.search(r'\[family\][^\n]*uuid=' + child + r'[^\n]*(?:child separation lost=|missing kin call)', after_dep):
        return f'{parent}: the severed child still looked for the parent after it left'
    lived = None
    for change in re.finditer(r'\[status\][^\n]*uuid=' + child + r' t=(\d+) dim=\S+ pos=(-?\d+), -?\d+, (-?\d+) behavior\.behavior: \S+ -> (\w+)', after_dep):
        if int(change[1]) > departed + 2400:
            break
        if change[4] in ('idle', 'mourn', 'mourn_listless', 'mold_leave', 'mold_look_back', 'mold_plead') or change[4].startswith('water'):
            continue
        if (int(change[2]) - centre[0]) ** 2 + (int(change[3]) - centre[1]) ** 2 >= 16 * 16:
            lived = change
            break
    if lived is None:
        return (f'{parent}: the severed child did no stray activity outside the old range within 2400 ticks of its departure '
                f'(departed at {departed}, parting beat at {turned})')
    if verdict == 'DRIVE_TEARFUL':
        parting, _ = at(r'\[choreo\][^\n]*uuid=' + owner + r' t=(\d+)[^\n]* beat=parting_begins face=', later)
        if parting is None or parting < departed:
            return f'{parent}: the tearful parent showed no parting feeling after the child was gone (parting={parting})'
    # each beat's shown face is the declared one (including verdict): no reaction layer painted over a performance
    beats = re.findall(r'\[choreo\][^\n]*uuid=(?:' + owner + '|' + child + r') [^\n]* beat=(\w+) face=(\S+) line=\S+ declared=(\S+)', after)
    wrong = [f'{name} shown={shown} declared={declared}' for name, shown, declared in beats if shown != declared]
    if wrong:
        return f'{parent}: a beat showed another face than the declared one: {wrong[:3]}'
    if not beats:
        return f'{parent}: no beat was logged'
    if departed - turned > 80 and not any(name == 'walk_out' for name, _, _ in beats):
        return f'{parent}: the walk out (beat=walk_out, its face shown) was never logged'
    return None
def _load_warning_choice(window):
    """Both kinds of parent at the load warning (FEATURES 载重): the ordinary parent reads her hull's load warning and takes the
    family ashore to unload before it goes over; the keeper (low intelligence, high friendliness) ignores it and keeps going
    until it capsizes, then keeps all her young. The `early` family's young grow only into the warning: she must turn for the
    bank and land them, with no overload capsize and nobody dismissed."""
    for parent, exceptional in (('early', False), ('kind', True)):
        failure, owner, boats, overloaded_growth = _overload_hull(window, 'hat_load_warning', parent, exceptional)
        if failure:
            return failure
        capsized = re.search(r'\[hat\][^\n]*uuid=' + boats + r'[^\n]* capsized cause=too_heavy\b[^\n]*thrown=[1-9]', window)
        dismissals = list(re.finditer(r'\[family\][^\n]*uuid=' + owner + r'[^\n]* parent dismissed child=(\S+)', window))
        if exceptional:
            if not overloaded_growth:
                return f'{parent}: no real growth into overload'
            if capsized is None:
                return f'{parent}: no overload capsize in this random run'
            if dismissals or not re.search(r'\[family\][^\n]*uuid=' + owner + r'[^\n]* overload keeps children', window[capsized.end():]):
                return 'exceptional parent did not keep all children after overload'
            if re.search(r'\[aquatic\][^\n]*uuid=' + owner + r'[^\n]* load heavy ', window):
                return 'exceptional parent reacted to the load warning'
            failure = _carried_boarding(window, owner)
            if failure:
                return failure
            continue
        if parent == 'early' and capsized is not None:
            return 'early: the hull capsized from overload though its young grow only into the warning'
        if parent == 'early' and len(re.findall(r'\[action\][^\n]*uuid=' + _uuid_text('hat_load_warning', 'early2')
                                                + r' [^\n]* refused id=hop [^\n]*detail=no_spot', window)) > 1:
            return 'early2: hopped for a hull with no spot more than once'
        # in time: the load warning sends her ashore, the family lands, and nobody is dismissed
        heavy = re.search(r'\[aquatic\][^\n]*uuid=' + owner + r'[^\n]* load heavy hat=' + boats + r'[^\n]* reason=unload_ashore', window)
        if heavy is None:
            return f'{parent}: neither unloaded at the load warning nor capsized'
        if not re.search(r'\[intent\][^\n]*uuid=' + owner + r'[^\n]* end id=come_ashore result=DONE\b', window[heavy.end():]):
            return f'{parent}: turned for the bank at the load warning but never landed the family'
        if dismissals:
            return f'{parent}: dismissed a young without an overload capsize'
        # the trade-off seen without a capsize: the young her full hull left on the bank gets her look back from the hull
        left = re.search(r'\[aquatic\][^\n]*uuid=' + owner + r' [^\n]* set_out left_ashore=\{[^}]*=hull_full', window)
        if left and not re.search(r'\[family\][^\n]*uuid=' + owner + r' [^\n]* overload beat start child=\S+ verdict=KEEP ferry=true', window[left.end():]):
            return f'{parent}: set out leaving a young ashore for a full hull without looking back at it'
    return None


def _escape_needs_place(window):
    """A climb that ended rest_place_lost while the body held no place on the hull (its last attach is not on_host) is a failed
    climb: an `escape done support=aboard` right after it is a false success. A body still on the host's spot is settled."""
    attach = {}
    for line in window.splitlines():
        who = re.search(r'\[body\][^\n]* uuid=(\S+) t=\d+ [^\n]* attach [^\n]*current=(\S+)', line)
        if who:
            attach[who[1]] = who[2]
        lost = re.search(r'\[action\][^\n]* uuid=(\S+) t=(\d+) [^\n]* end id=climb outcome=BLOCKED [^\n]*detail=rest_place_lost', line)
        if lost and not attach.get(lost[1], '').startswith('on_host:'):
            done = re.search(r'\[water\][^\n]* uuid=' + lost[1] + r' t=(\d+) [^\n]* escape done [^\n]*support=aboard',
                             window[window.index(line):])
            if done and int(done[1]) - int(lost[2]) <= 3:
                return 'escape done support=aboard right after a climb that held no place (rest_place_lost): ' + lost[1]
    return None


# Nourishment (ticks) at which young 2 and the head young stand at their start sizes 0.20 and 0.355 on the size curve (family.json
# growth, maturity 336000: 0.25 -> 0.20, then linear to 0.80 at 1.0), so the world's own growth has nothing to add at the start.
_YOUNG2_NOURISHMENT, _HEAD_NOURISHMENT = 84000, 149100
# Growth steps for an overload: the head young to 0.50, young 2 to 0.29, then the head young on in 5000-tick steps (each a roll
# for the hull's load, 10 ticks apart). Nourishment (child index, ticks).
_OVERLOAD_STEPS = ((3, 210000), (2, 120000), (3, 215000), (3, 220000), (3, 225000), (3, 230000), (3, 235000))
# Reliable juvenile growth steps for overload severance: child 3 to 0.500..0.524 (load ratio 2.09..2.65, chance 0.52..0.62
# per step, >99.98% across 10 rolls, while staying <= 0.524 so ParentCarry never refuses too_big even when tired); child 2
# stays at 0.286 (0.37 of capacity on its own after child 3 is cut).
_CROWD_OVERLOAD_STEPS = ((3, 210000), (2, 120000), (3, 211250), (3, 212500), (3, 213750), (3, 215000),
                         (3, 216250), (3, 217500), (3, 218750), (3, 220000))
# One step into the warning only: the head young to 0.375 (load 0.96 of capacity).
_WARNING_STEPS = ((3, 157500),)
# The growth steps start after the smoke's chunk-reload check (tick 60 + 400 unloaded ticks) so the saved riders do not change under it.
_GROWTH_START = 500


def _overload_growth(parent, steps, first=_GROWTH_START):
    """The overload's cause: each step is one real growth step of a young (its own nourishment curve and collision rule), run at a
    tick the scene chooses instead of the world's 100-tick interval; a step the box refuses is tried again (Retry)."""
    return [Retry(f'yukkuri scenario grow {sel(parent + str(child))} {nourishment}', sel(parent + str(child)), tries=4, every=20,
                  first=first + 10 * i)
            for i, (child, nourishment) in enumerate(steps)]


def _overload_family(scene, parent, dz, cabin, label, out=6, stick=True, child3_traits=None,
                     head_childhood=CHILDHOOD_CRAWLING_READY, **traits):
    """Young start at sizes 0.16 (cabin, only with `cabin`), 0.20 and 0.355: just under the load warning for the ordinary parent
    (head 0.355 + brim 0.20: 0.83 of capacity; at 0.18 young 2 takes the cabin instead), so her own load rule seats them. The
    growth is then staged by `_overload_growth` (`_OVERLOAD_STEPS`: a load over twice the hull's capacity with no warning in
    between; young 2 stays under that capacity on its own, so the parent that lost its largest stops overloading while the
    exceptional one keeps all three and overloading; `_WARNING_STEPS`: only the head young grows, the hull sits in its warning
    (0.96) and never overloads). The hat is empty: these scenes show the load, not the larder (`hat_larder`). `traits` pin the
    parent's personality; `child3_traits` optionally pin the largest child's personality; `head_childhood` is the real learned
    state of the head young (the severance scenes send out one that has already learnt to crawl, so it walks out and lives on)."""
    return [yk('marisa', bank_dx(dz), dz, parent, aquatic=True, yaw=-90, lead=True,
               uuid=uid(scene, parent), label=label, **traits, **pair_ready()),
            *[yk('marisa', bank_dx(dz + offset) - 1, dz + offset, parent + str(i), aquatic=True,
                 fam=_seat_family(size, nourishment, age),
                 childhood=head_childhood if i == 3 else CHILDHOOD_CRAWLING_READY,
                 uuid=uid(scene, parent + str(i)), also=(parent + '_crew',),
                 kin=f'Mother:{uuid_nbt(uid(scene, parent))}', label=f'孩子{i}',
                 **(child3_traits if i == 3 and child3_traits else {}), **pair_ready())
              for i, offset, size, nourishment, age in ((1, -1, .16, 22909, 36000), (2, 0, .20, _YOUNG2_NOURISHMENT, 76000),
                                                       (3, 1, .355, _HEAD_NOURISHMENT, 72000)) if cabin or i != 1],
            afloat_now(parent, water_at(dz, out), True, sel_all(parent + '_crew'), stick=stick)]


def _babies():
    return [yk('marisa', bank_dx(dz), dz, name, stage='baby', aquatic=True, childhood=CHILDHOOD_CRAWLING_READY,
               fam=_seat_family(size, nourishment, 36000), also=('crew',), **pair_ready())
            for name, dz, size, nourishment in (('k1', 1, .16, 22909), ('k2', -1, .19, 68727))]


_HULL_READS = [('读漂浮、触发与扶正', f'yukkuri status {sel("a")} raw'), ('读帽子状态', f'data get entity {_HAT}')]
_HULL_READOUT = [f'yukkuri status {sel("a")} raw', f'data get entity {_HAT} Riders', f'data get entity {_HAT} Pos', f'data get entity {_HAT} Motion', f'data get entity {_HAT} Inverted']


def push_smooth(log):
    """The light contact slides a hull away without capsizing it; only the fast impact capsizes, and bank contact stays stopped."""
    capsized = re.findall(r'\[hat\][^\n]* uuid=(\S+) t=\d+[^\n]* capsized\b', log)
    if len(capsized) != 1:
        return 'expected only the fast impact to capsize'
    travel = re.compile(r'\[hat\][^\n]* uuid=(\S+) t=\d+[^\n]* travel at=\([^)]*\) delta=\(([-\d.E]+),[^)]*\)[^\n]* impact=([-\d.E]+)')
    by_hat = {}
    for hat, dx, impact in travel.findall(log):
        by_hat.setdefault(hat, []).append((float(dx), float(impact)))
    pushes = [[next_dx for next_dx, _ in rows[i + 1:i + 13]] for rows in by_hat.values()
              for i, (_, impact) in enumerate(rows) if 0 < impact < .16 and len(rows[i + 1:i + 13]) == 12]
    if not any(all(dx <= 0 for dx in push) and sum(push) <= -.1 for push in pushes):
        return 'light contact must slide the hull at least 0.1 blocks away without coming back'
    for rows in by_hat.values():
        for i in range(1, len(rows)):
            if rows[i - 1][0] < 0 and rows[i][1] > .16 and any(abs(dx) > 1e-9 for dx, _ in rows[i + 1:i + 6]):
                return 'bank contact rebounded instead of staying stopped'
    return None


pond('hat_push', '木船轻碰只推开，猛撞才翻', '木船轻碰只推开，猛撞才翻',
     '两顶载人的帽船旁各有一艘真实木船：近处木船低速碰帽，只推开或碰停；远处木船快速撞帽，帽子先猛烈摇摆再翻覆。正常划船撞岸或碰另一顶帽不会翻覆',
     '约 30 秒',
     [captain(dx=bank_dx(0)), yk('marisa', bank_dx(5), 5, 'fast', aquatic=True, yaw=-90, label='猛撞边界', **pair_ready()),
      afloat_now('a', water_at(0, 3)), afloat_now('fast', water_at(5, 3))],
     late=[f'execute as {sel("a")} on vehicle at @s run summon minecraft:boat ~1.8 ~ ~ {{Type:"oak",Tags:["scn_prop","low_push"]}}',
           f'execute as {sel("fast")} on vehicle at @s run summon minecraft:boat ~2 ~ ~ {{Type:"oak",Tags:["scn_prop","fast_push"]}}',
           Retry('data merge entity @e[tag=low_push,limit=1] {Motion:[-0.08d,0d,0d]}', sel('a'), tries=1, first=40),
           Retry('data merge entity @e[tag=fast_push,limit=1] {Motion:[-0.65d,0d,0d]}', sel('fast'), tries=1, first=40)],
     note='木船只在初态给速度，之后按真实碰撞运动；读 trigger=impact、value 与 capsizes。近处帽船 capsizes=0，远处帽船只记一次猛撞翻覆。',
     reads=_HULL_READS + [('读猛撞船主', f'yukkuri status {sel("fast")} raw')],
     capture=dict(shots=(1, 40, 300, 600), sequences=((60, 200, 2),)),
     smoke=dict(ticks=600,
                expect=[r'\[hat\][^\n]* capsized cause=impact value=[^\n]*count=1'],
                forbid=[r'\[hat\][^\n]* capsized cause=(?:shake|turbulence)\b', r'\[body\][^\n]* invariant\b'],
                verify=push_smooth,
                readout=_HULL_READOUT + [f'yukkuri status {sel("fast")} raw']))


def flow_channel(*lanes, lake=()):
    """Gravel stream beds (one per ``lanes`` row, each three wide) and still lakes (``lake`` rows) on one low grassy plateau;
    fluid updates supply all flow and turbulence. Each stream's water starts at the plateau edge, so a hull centred one cell in
    has its whole brim (radius .89) over flowing water and no longer overlaps the west bank; downstream the same one-block flow
    still shallows out and strands it. The dry bank stays one cell west of the water."""
    x = bank_dx(0)
    rows = (*lanes, *lake)
    lo, hi = min(rows) - 4, max(rows) + 4
    out = [box(x, -3, lo, x + 13, -1, hi, 'minecraft:dirt'),
           box(x, -1, lo, x + 13, -1, hi, 'minecraft:grass_block'),
           box(x, 0, lo, x + 13, 4, hi, 'minecraft:air')]
    for z in rows:
        out += [box(x, -2, z - 1, x + 9, -2, z + 1, 'minecraft:gravel'),
                box(x, -1, z - 1, x + 9, 4, z + 1, 'minecraft:air'),
                *([box(x, -1, z - 1, x + 9, -1, z + 1, 'minecraft:water')] if z in lake else
                  [block(x, -1, zz, 'minecraft:water') for zz in (z - 1, z, z + 1)])]
    return out


def lane_at(dz, out):
    """Water position ``out`` blocks east of the plateau edge on row ``dz`` (the plateau is flat, unlike the natural shore)."""
    return f'~{bank_dx(0) + out + .5} ~-1 ~{dz + .5}'


_CURRENT_HEAD = r'\[hat\][^\n]* uuid=(\S+) [^\n]* scenario place [^\n]*place=HEAD\b'
_CURRENT_IMMERSION = r'\[water\][^\n]* damage cause=drown\b'


def current_drift(log):
    points = [float(x) for x in re.findall(r'\[hat\][^\n]* travel at=\(([-\d.]+),', log)]
    if len(points) < 2 or max(points[:200]) - points[0] < 3:
        return 'hat must drift at least 3 blocks downstream in 200 ticks'
    # The young that cannot hop onto the brim rides the captain's head (it is its real place): at grounding it leaves the hat with
    # her, not by itself.
    head = re.search(_CURRENT_HEAD, log)
    if head is None:
        return 'the young must be seated on the head (it cannot hop there itself)'
    captain = re.search(r'\[hat\][^\n]* left rider=(\S+)[^\n]*', log)
    grounding = captain and re.search(rf'\[body\][^\n]*uuid={captain[1]}[^\n]* dismount [^\n]*cause=grounded'
                                        rf'[^\n]*passengers=\[{head[1]}', log)
    if grounding is None:
        return 'the captain must leave the grounded hat still carrying her head young'
    # Her eyes go under in the shoal, so the young on her head falls in with her (rule); it must then get out alive.
    if re.search(rf'\[body\][^\n]*uuid={head[1]}[^\n]* death settled', log):
        return 'the head young must not die after falling in with the captain'
    ashore = r'\[water\][^\n]*uuid={}[^\n]* escape done [^\n]*support=stand='
    after_grounding = log[grounding.end():]
    if not re.search(ashore.format(captain[1]), after_grounding):
        return 'the captain must reach her own dry stand after grounding'
    shed = re.search(rf'\[family\][^\n]*uuid={captain[1]}[^\n]* head rider shed child={head[1]} '
                     r'cause=(?:carrier_eyes_under|carrier_fell_in)\b', after_grounding)
    stand = shed and re.compile(ashore.format(head[1])).search(after_grounding, shed.end())
    if shed and stand is None:
        return 'the shed head young must reach its own dry stand'
    hits = list(re.finditer(_CURRENT_IMMERSION, log))
    if hits:
        hit = hits[0]
        if (len(hits) > 1 or f'uuid={head[1]} ' not in hit[0] or stand is None
                or not grounding.end() + shed.end() <= hit.start() < grounding.end() + stand.start()):
            return 'only the shed head young may take one immersion hit before its first dry stand'
    back = re.search(r'\[headwear\][^\n]* received loose accessory', log)
    if back and re.search(r'\[action\][^\n]* start id=pick\b', log[:back.start()]):
        return 'the owner went for loose items before taking her hat back'


pond('hat_current', '帽子顺缓流漂到浅滩搁浅', '帽子顺缓流漂到浅滩搁浅',
     '浅溪上游一排水源形成同向水流，浅滩两侧的石阶通向草岸；船主乘帽随水流缓慢漂动，碰到岸边只停住。没有落水、流向冲突或猛撞时不会翻船',
     '约 30 秒',
     [*flow_channel(0),
      *(box(bank_dx(0) + 2, -1, z, bank_dx(0) + 9, -1, z, f'minecraft:cobblestone_stairs[facing={facing}]')
        for z, facing in ((-2, 'north'), (2, 'south'))),
      captain(dx=bank_dx(0) - 1, social_wait=100),
      yk('marisa', bank_dx(1) - 1, 1, 'child', stage='child', aquatic=True, fam=_seat_family(), childhood=CHILDHOOD_CRAWLING_READY,
         **pair_ready()),
      afloat('a', water_at(0, 1), True, sel('child'), food=4, stick=True)],
     vantage=(bank_dx(0) + 11, -3, 40, 18),
     note='观察船长带着头顶的子油离帽、自己走上干岸（头顶子油不会自己跳，随亲体离帽），四份苹果和一根木棍各掉出，帽主接触头饰即戴回；帽船搁浅不算翻覆。倒扣边界与载人翻覆见“落水掀翻帽子与帽子搁浅”。',
     reads=_HULL_READS + [('读子油', f'yukkuri status {sel("child")} raw')],
     capture=dict(shots=(1, 40, 300, 600, 900), sequences=((60, 200, 2), (220, 1100, 20))),
     smoke=dict(ticks=1100,
                expect=[r'\[hat\][^\n]* scenario afloat prepared[^\n]*young=1[^\n]*food=4',
                        r'\[body\][^\n]* dismount [^\n]*cause=grounded',
                        r'\[hat\][^\n]* stores spilled [^\n]*cause=grounded', r'\[hat\][^\n]* kit dropped cause=grounded',
                        r'\[hat\][^\n]* pickup cause=grounded',
                        r'\[headwear\][^\n]* received loose accessory'], verify=current_drift, gating_checks=['END'],
                forbid=[r'\[hat\][^\n]* capsized\b', r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* cause=drown',
                        r'\[body\][^\n]* death settled into remains'],
                readout=_HULL_READOUT + [f'yukkuri status {sel("child")} raw',
                    'execute as @e[type=minecraft:item,distance=..48] run data get entity @s Item']))


def capsized_crew(log):
    """Each rider of the loaded hat leaves it into the water by a logged cause: the brim's hard-rock fall, then the capsize throw."""
    crew = {_uuid_text('hat_grounded_capsized', slot) for slot in ('a', 'child')}
    left = {}
    for line in log.splitlines():
        dismount = re.search(r'\[body\][^\n]* uuid=(\S+)[^\n]* dismount [^\n]*cause=(brim_hard_rock|thrown)', line)
        if dismount and dismount[1] in crew:
            left[dismount[1]] = (dismount[2], int(re.search(r' t=(\d+) ', line)[1]))
    if sorted(cause for cause, _ in left.values()) != ['brim_hard_rock', 'thrown']:
        return f'each rider must leave the hull by a real cause, saw {left}'
    for uuid, (cause, tick) in left.items():
        entries = [int(m[1]) for m in re.finditer(r'\[water\][^\n]* uuid=' + uuid + r'[^\n]* t=(\d+)[^\n]* entered at=', log)]
        if not any(entry >= tick for entry in entries):
            return f'rider {uuid} ({cause}) left the hull without entering the water'
    return None


def _grounded_smoke():
    """Three hats on three lanes, each told apart by its owner: the loaded hat (capsize, thrown riders, grounding), the empty
    inverted hat (grounding) and the hat under the waterfall (turbulence)."""
    a, child, inverted, falling = (_uuid_text('hat_grounded_capsized', slot) for slot in ('a', 'child', 'inverted', 'falling'))
    return dict(
        ticks=1800,
        expect=[rf'\[hat\][^\n]*uuid={a}[^\n]* scenario afloat prepared [^\n]*owner_aboard=true[^\n]*young=1[^\n]*food=4',
                rf'\[hat\][^\n]*uuid={inverted}[^\n]* scenario afloat prepared [^\n]*owner_aboard=false',
                rf'\[hat\][^\n]*uuid={falling}[^\n]* scenario afloat prepared [^\n]*owner_aboard=true[^\n]*young=0',
                rf'\[hat\][^\n]* capsized cause=(?:turbulence|impact)[^\n]*thrown=1\b[^\n]*owner={a}',
                r'\[body\][^\n]* dismount [^\n]*cause=brim_hard_rock',
                r'\[body\][^\n]* dismount [^\n]*cause=thrown',
                rf'\[hat\][^\n]* stores spilled by={a} cause=(?:turbulence|impact)',
                rf'\[hat\][^\n]* pickup cause=grounded [^\n]*owner={a}\b',
                rf'\[headwear\][^\n]*uuid={a}[^\n]* received loose accessory',
                rf'\[hat\][^\n]* capsized cause=turbulence value=[^\n]*falling=(?:true|false)[^\n]*owner={falling}\b',
                rf'\[hat\][^\n]* pickup cause=grounded [^\n]*owner={inverted} inverted=true'],
        forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* death settled into remains',
                rf'\[hat\][^\n]* capsized cause=(?:shake|impact)\b[^\n]*owner={falling}\b',
                r'\[body\][^\n]* cause=drown'],
        verify=capsized_crew,
        readout=_HULL_READOUT + [f'yukkuri status {sel("child")} raw',
                                 'execute as @e[type=minecraft:item,distance=..48] run data get entity @s Item'])


_GC = 'hat_grounded_capsized'
pond(_GC, '落水掀翻帽子与帽子搁浅', '落水掀翻帽子与帽子搁浅',
     '浅溪边三条并排的水道。中间一条：船主带着一只子油和四份苹果坐在帽上随流漂下（不带木棍，只随水漂），帽子所在一格落下一片水把它掀翻：两名乘员被甩进水里，余粮漂散；倒扣的帽体继续漂到水浅处落底，变成帽主的头饰，她游过去碰到就戴上。'
     '一侧水道里一顶倒扣的空帽顺流漂到下游浅水，水不再托住帽体时变成一件真实头饰，帽主在干岸上。另一侧是静水湖：船主坐在帽上，头顶落下一片真实的水冲击水面，帽子先猛烈摇摆再翻覆，船主落水',
     '约 1 分钟',
     [*flow_channel(0, 4, lake=(-4,)),
      captain(dx=bank_dx(0) - 5, social_wait=100, uuid=uid(_GC, 'a')),
      yk('marisa', bank_dx(1) - 5, 1, 'child', stage='child', aquatic=True, fam=_seat_family(), uuid=uid(_GC, 'child'), **pair_ready()),
      afloat('a', lane_at(0, 1), True, sel('child'), food=4),
      yk('marisa', bank_dx(0) - 5, 4, 'inverted', aquatic=True, yaw=-90, social_wait=0, uuid=uid(_GC, 'inverted'), label='倒扣空帽的帽主', **pair_ready()),
      afloat('inverted', lane_at(4, 1), False),
      yk('marisa', bank_dx(0) - 5, -4, 'falling', aquatic=True, yaw=-90, uuid=uid(_GC, 'falling'), label='瀑流下的船主', **pair_ready()),
      afloat('falling', lane_at(-4, 4))],
     vantage=(bank_dx(0) + 12, -2, 74, 20),
     late=[block(bank_dx(0) + 1, 2, 0, 'minecraft:water'), block(bank_dx(0) + 4, 2, -4, 'minecraft:water'),
           f'execute at {sel("inverted")} run data merge entity {_HAT} {{Roll:180f,Inverted:1b,Motion:{{Phase:"INVERTED"}}}}'],
     note='中间水道的翻覆来自场景里真实落下的一格水，不是脚本改帽子状态；帽体撞上硬石时帽檐上的子油先按帽檐自身的落石判定被甩下（dismount cause=brim_hard_rock），稍后帽子才因撞击翻覆把舱内的帽主甩出（capsized cause=impact thrown=1），两名乘员各经一次真实原因落水后，帽体才搁浅成物品。帽主可能先游回去把帽子扶正，所以这条水道不要求落底时仍倒扣；倒扣空帽的搁浅看另一条水道（pickup cause=grounded inverted=true）。'
          '静水湖的帽子被落水冲翻：读 hat.float 的 trigger=turbulence、falling、value、capsizes 与 inverted，日志只记一次触发。水与地形在清场后恢复。',
     reads=_HULL_READS + [('读子油', f'yukkuri status {sel("child")} raw'), ('读倒扣空帽的帽主', f'yukkuri status {sel("inverted")} raw'),
                          ('读瀑流下的船主', f'yukkuri status {sel("falling")} raw')],
     smoke=_grounded_smoke())


def water_aftermath(window):
    """A supported landing and re-entry are valid; a rapid hull reversal or a pause restart is not."""
    grounded, escapes = {}, {}
    for line in window.splitlines():
        entity = re.search(r'\[(hat|intent|incident|action|water)\] .*?#(\d+) uuid=\S+ t=(\d+)', line)
        if entity is None:
            continue
        kind, who, tick = entity[1], entity[2], int(entity[3])
        if kind == 'hat':
            if ' entered ground from afloat ' in line:
                grounded[who] = tick
            elif ' entered afloat from ground ' in line and who in grounded and tick - grounded[who] <= 20:
                return f'hull #{who} returned afloat {tick - grounded[who]} ticks after entering ground'
        elif kind == 'incident' and ' close id=water#' in line:
            escapes.pop(who, None)
        elif kind == 'water' and (' entered at=' in line or re.search(r'phase=(?:IDLE|RECOVER) -> IMPACT ', line)):
            escapes.pop(who, None)
        elif kind == 'action' and re.search(r'outcome=INTERRUPTED why=(?:hurt|fright|command)\b', line):
            escapes.pop(who, None)
        elif kind == 'intent' and ' start id=escape_water ' in line:
            if who in escapes:
                return f'escape #{who} restarted after {tick - escapes[who]} ticks without a new water entry or interruption'
            escapes[who] = tick


def _seat_load_aftermath(window):
    """The hull was full by load (past the ordinary parent's load limit, its load warning), not by missing places, and the same
    child is set down ashore and lives."""
    refusal = re.search(r'refused id=release [^\n]*NO_ROOM[^\n]*hull_full load=(\S+) capacity=\S+ limit=(\S+)', window)
    if refusal is None:
        return 'no load-full release refusal'
    if float(refusal[1]) <= float(refusal[2]):
        return 'the logged load does not exceed her load limit'
    return water_aftermath(window)


def seat_unload_result(window):
    """The ferry's child is set down ashore and the whole family lands with her (FEATURES 水生魔理沙, 登船 and 落水救助): every own
    young's last hull event is leaving it, she wears her emptied hat again before the rescue ends, and no young of hers is logged
    lost while she ferries them (from the faller's grip to the rescue's end)."""
    problem = _seat_load_aftermath(window)
    if problem:
        return problem
    parent, faller = _uuid_text('rescue_no_seat', 'a'), _uuid_text('rescue_no_seat', 'c1')
    # Every rider but the parent herself: only she is named by a fixed UUID, the crew's are drawn when they spawn.
    aboard = {}
    for event in re.finditer(r'\[hat\][^\n]* (boarded|left) rider=(\S+)', window):
        aboard[event[2]] = event[1] == 'boarded'
    stuck = sorted(rider for rider, on in aboard.items() if on and rider != parent)
    if stuck:
        return f'{len(stuck)} young of hers still ride the hull at the end: {stuck}'
    grip = re.search(rf'carry grip [^\n]*uuid={faller}', window)
    end = re.search(rf'rescue end result=DONE outcome=set_down faller={faller}', window)
    if grip is None or end is None:
        return 'no grip of the faller followed by its set-down rescue end'
    ferry = window[grip.end():end.start()]
    if not re.search(rf'\[headwear\][^\n]*uuid={parent}[^\n]* custody DEPLOYED -> WORN\b', ferry):
        return 'she did not take her emptied hat back before the rescue ended'
    if re.search(r'child separation lost=true', ferry):
        return 'a child was logged as separated and lost while she ferried the family'
    return None

_WATER_SMOKE_FORBID = [r'\[action\][^\n]* end id=swim outcome=INTERRUPTED why=(?:water_pause|escape_stop)\b']
def _water_readouts(*bodies):
    """Each named body's own status (water distress phase, aims, its hat's float detail and its incident's outcome), then the hat's riders."""
    return [f'yukkuri status {sel(b)} raw' for b in bodies] + [f'data get entity {_HAT} Riders']
_WATER_ESCAPE = [r'\[intent\][^\n]* start id=escape_water\b',
                 r'\[action\][^\n]* start id=swim owner=escape_water\b',
                 r'\[intent\][^\n]* end id=escape_water result=(?:DONE|EXHAUSTED|LOST)\b']

pond('shake_capsize', '右击晃翻帽子，船主游回扶正', '右击晃翻帽子，船主游回扶正',
     '空手反复右击水面的帽子：单击只会摇一下，三秒内三次摇晃后先剧烈摇摆再翻覆，所有乘员沿倾斜方向被甩进水里，帽子倒扣浮着（不再是容器）。随后船主慢慢游回帽边（很弱的挣扎前进），咬住帽檐按一段动作推正帽子（播咬檐推帽的扶正动作，不是挣扎），再爬回帽里；泡水伤照常扣血但不打断游、扶正与爬；两只赤油不会攀爬，只能朝岸挣扎',
     '约 1 分钟',
     [captain(dx=bank_dx(0)), *_babies(),
      afloat_now('a', water_at(0, 3), True, sel_all('crew'), food=4, stick=True)],
     note='载人帽有四份粮与木棍；日志 capsized → righted → mount cause=climb，粮与木棍各漂出一次。扶正是一段咬檐推帽动作，水伤照常扣血。读 hat.float 的 trigger、value、capsizes、inverted 与 righting。',
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读赤油一', f'yukkuri status {sel("k1")} raw'),
            ('读赤油二', f'yukkuri status {sel("k2")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=1800, commands=[(tick, f'yukkuri scenario shake {_HAT}') for tick in (100, 110, 120)],
                expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=true[^\n]*young=2\b',
                        r'\[hat\][^\n]* shake player=[^\n]*count=',
                        r'\[aquatic\][^\n]* rocked hat=',
                        r'\[water\][^\n]* phase=\w+ -> RECOVER',
                        r'\[hat\][^\n]* capsized cause=shake value=3.0[^\n]*thrown=[1-9]',
                        r'\[hat\][^\n]* kit dropped cause=capsized', *_WATER_ESCAPE],
                forbid=_WATER_SMOKE_FORBID + [r'\[body\][^\n]* death settled\b', r'\[needs\][^\n]* starvation damage accepted=true'],
                verify=water_aftermath, readout=_HULL_READOUT))

pond('self_rescue', '落水后游回自己的帽子', '落水后游回自己的帽子',
     '水生魔理沙的帽子在离岸 12 格的水面上，她自己下水：她朝自己的帽子游（一阵用力一阵歇，花体力），嘴或辫梢够到帽檐就停，咬住帽檐、按片段节拍把自己拉进帽腔坐好；在帽里不再算落水',
     '约 30 秒',
     [captain(dx=bank_dx(0)), afloat_now('a', water_at(0, 12), False)],
     note='长泳消耗体力；向帽子游超过 600 tick 或用尽办法才转向近岸。日志：[intent] start id=escape_water → [method] pick method=own_hat → [action] end id=swim … detail=grip → [action] start id=climb（rise、climbRise、grip）→ [body] mount cause=climb → [intent] end result=DONE。读 water.distress 的阶段，读 hat.float 看固定水线与翻覆状态。',
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=1800, expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=false',
                                   # After the start hold she reclaims the hat at once (walk in, swim): the same path shore_capsized_far asserts.
                                   r'\[action\][^\n]* start id=step_in\b', r'\[intent\][^\n]* start id=escape_water\b',
                                   r'\[action\][^\n]* start id=swim owner=drive:aquatic\b',
                                   r'\[intent\][^\n]* end id=resolve_own_hat result=DONE\b', r'\[water\][^\n]* phase=\w+ -> RECOVER',
                                   r'\[action\][^\n]* end id=climb outcome=DONE\b',
                                   r'\[method\][^\n]* pick intent=escape_water method=(?:own_hat|right_own_hat)\b'],
                forbid=_WATER_SMOKE_FORBID, verify=water_aftermath, readout=_water_readouts('a')))

# A shallow runnel drains west from the pond onto a level bed. Reimu is wet and still in the last flowing cell.
pond('shake_ashore', '落水灵梦爬上岸甩水', '落水灵梦爬上岸甩水',
     '普通灵梦浑身湿透，还在池边浅水沟的末端。看它自己划上有实地承托的干岸，站稳喘匀后甩一次水，湿度减半。',
     '约 20–40 秒',
     [box(bank_dx(0) - 9, -1, 0, bank_dx(0), -1, 0, 'minecraft:air'),
      *[block(bank_dx(0) + 1 - level, -1, 0, f'minecraft:water[level={level}]') for level in range(1, 8)]],
     # Reimu falls into the last flowing cell after the start hold: a body held there would be carried out of the water by the current
     # before the player looks, and the escape from the water is the beat.
     late=[yk('reimu', bank_dx(0) - 6.45, 0, 'a', dy=-1, yaw=90, lead=True, **pair_ready()),
           set_status('a', 'needs.wetness', 32)], note=NEW, vantage=(bank_dx(0) - 9, 3, -135, 15),
     reads=[('读湿度与甩水', f'yukkuri status {sel("a")} raw')],
     capture=dict(shots=(1, 40, 400, 800), sequences=((40, 800, 4),), observe=False,
                  follow='@e[tag=scn_a,limit=1]', offset=(-1.6, 1.0, 2.2)),
     smoke=dict(ticks=1200, expect=[*_WATER_ESCAPE, r'\[water\][^\n]* escape done [^\n]*support=stand=',
                                   r'\[weather\][^\n]* shaking water off wetness=[^\n]* after=water\b',
                                   r'\[weather\][^\n]* shook the water off wetness='],
                forbid=_WATER_SMOKE_FORBID, readout=_water_readouts('a')))

def _ledge_spit():
    """A 3 x 5 sandstone spit out in the pond (stand y 64) and, hard against its east side, a broad bank one block higher (stand y 65):
    water on three sides and a full-block ledge on the fourth, with an apple on the bank."""
    x0 = bank_dx(0) + 3
    return [box(x0, -1, -2, x0 + 2, -1, 2, 'minecraft:sandstone'),
            box(x0 + 3, -1, -5, x0 + 12, 0, 5, 'minecraft:sandstone'),
            box(x0, 0, -2, x0 + 2, 3, 2, 'minecraft:air'),
            box(x0 + 3, 1, -5, x0 + 12, 4, 5, 'minecraft:air'),
            item('minecraft:apple', 1, x0 + 8, 0, 'food', dy=1.05)]


# A hungry body on a spit, its food on the bank a full block above: the stand it walks to lies at the food's height, so it steps up.
pond('ledge_spit', '沙洲上的饿帕秋莉够堤上的苹果', '沙洲上的饿帕秋莉够堤上的苹果',
     '饿着的帕秋莉站在水中一块 3×5 的沙洲上，三面是水，东面是比沙洲高一格的堤岸，堤上有一个苹果。看她直接走到堤边、踏上堤岸去吃苹果，而不是在沙洲上原地"没有路"地发呆',
     '约 10–20 秒',
     [*_ledge_spit(), yk('patchouli', bank_dx(0) + 4, 0, 'a', yaw=-90, nutrition=5, energy=90, boldness=55)],
     note=NEW, vantage=(bank_dx(0) - 3, 3, -90, 15),
     reads=[('读营养与体力', f'yukkuri status {sel("a")} raw')],
     smoke=dict(ticks=1200, expect=[r'\[meal\][^\n]* swallowed item=minecraft:apple '],
                forbid=_WATER_SMOKE_FORBID + [r'\[meal\][^\n]* food approach refused'], readout=_water_readouts('a')))

def reclaim_facing_once(window):
    """The bank reclaim turns to the hat once: a turn Face has already completed is never issued again (the beckon that comes
    after it is the real proof, and a loop in front of it keeps it from starting)."""
    turns = len(re.findall(r'\[action\][^\n]* start id=face\b', window))
    if turns > 3:
        return f'the facing was issued {turns} times: a turn already completed is issued again'
    return water_aftermath(window)


pond('shore_capsized', '岸边用辫子拨回漂远的帽子', '岸边用辫子拨回漂远的帽子',
     '船主的帽子倒扣漂在离岸不到三格的静水里：她站在岸边面向帽子，侧身探出岸沿用辫梢一下一下拨水（片段的水下拨动段才推动帽子，其余时间帽子顺水面的阻力和真实水流滑行），把帽子拨到辫梢／嘴够得着的地方后咬住帽檐推正，再收回戴上；三格内不下水',
     '约 20–40 秒',
     [captain(dx=bank_dx(0)), afloat_now('a', water_at(0, 2.5), False)],
     late=[f'data merge entity {_HAT} {{Roll:180f,Inverted:1b,Motion:{{Phase:"INVERTED"}}}}'],
     note='倒扣是帽子真实的漂浮姿态（帽冠朝上），不是被测环节。日志：[action] start id=beckon … wet=false stroke=2..22/32（每周期只在这 20 tick 推动帽子）→ 帽檐被拨到够得着的地方 → '
          '[action] end id=beckon outcome=DONE detail=at_bank → [action] start id=right … push= → [hat] righted cause=reclaim → '
          '[headwear] reclaim done how=worn；三格内不下水，不出现 step_in／swim（三格外的远帽见“远处的帽子游过去扶正”）；水伤不打断，死亡、帽子离开或玩家干预会终止。',
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=2400,
                expect=[r'\[action\][^\n]* start id=beckon[^\n]*wet=false',
                        r'\[action\][^\n]* end id=beckon outcome=DONE\b[^\n]*detail=at_bank',
                        r'\[action\][^\n]* start id=right[^\n]*push=', r'\[hat\][^\n]* righted cause=reclaim',
                        r'\[headwear\][^\n]* reclaim done how=worn',
                        r'\[intent\][^\n]* end id=resolve_own_hat result=DONE\b'],
                forbid=[r'\[action\][^\n]* start id=step_in\b', r'\[action\][^\n]* start id=swim\b',
                        r'\[headwear\][^\n]* reclaim waiting',
                        r'\[body\][^\n]* death settled into remains', r'\[body\][^\n]* cause=drown'],
                verify=reclaim_facing_once, readout=_HULL_READOUT + [f'data get entity {_HAT} Pos']))

pond('shore_capsized_far', '远处的帽子游过去扶正', '远处的帽子游过去扶正',
     '离岸 8 格的水面上，船主的帽子倒扣漂着，岸边拨水够不着（三格外）：她从岸上走进水里（普通走路跨过岸沿落水，不跳跃），游到帽檐外侧，咬住帽檐按一段片段的推帽标记把帽子推正，帽子正过来后再爬进去；扶正时播咬檐推帽的动作，泡水伤不打断',
     '约 30–60 秒',
     [captain(dx=bank_dx(0)), afloat_now('a', water_at(0, 8), False)],
     late=[f'data merge entity {_HAT} {{Roll:180f,Inverted:1b,Motion:{{Phase:"INVERTED"}}}}'],
     note='倒扣是帽子真实的漂浮姿态（帽冠朝上），不是被测环节。日志：拨水够不着（三格外）就 [action] start id=step_in（普通走路跨过岸沿落水）→ '
          '[intent] start id=escape_water（同一 reclaim 的子意图）→ [method] pick method=right_own_hat → [action] start id=swim … grip → '
          '[action] start id=right … push= → [hat] righted cause=self_rescue → [action] start id=climb → [body] mount cause=climb → [action] end id=climb outcome=DONE → [intent] end id=resolve_own_hat result=DONE；到船长座位才完成；接触后轻摆与水伤不中断；死亡、帽子离开或玩家干预会终止。',
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=2400, expect=[r'\[action\][^\n]* start id=step_in\b',
                                   r'\[intent\][^\n]* start id=escape_water\b',
                                   r'\[action\][^\n]* start id=swim owner=drive:aquatic\b',
                                   r'\[action\][^\n]* start id=right[^\n]*push=', r'\[hat\][^\n]* righted cause=self_rescue',
                                   r'\[body\][^\n]* mount [^\n]*cause=climb',
                                   r'\[action\][^\n]* end id=climb outcome=DONE\b',
                                   r'\[intent\][^\n]* end id=resolve_own_hat result=DONE\b'],
                forbid=[*_WATER_SMOKE_FORBID, r'\[headwear\][^\n]* reclaim waiting',
                        r'\[body\][^\n]* death settled into remains'], verify=water_aftermath, readout=_HULL_READOUT))

pond('worn_hat_boat', '戴着的帽子摘下当船自救', '戴着的帽子摘下当船自救',
     '戴着帽子、已经初航过的水生魔理沙子油从岸旁水面上方落下：她先把自己的帽子摘下放到身旁的开阔水面上，帽子展开漂着，再游到帽檐、咬住爬进帽里坐好；高友善亲油只在附近面向她含泪围观，不再把她救上亲船。回到自帽后没有木棍也用辫子划水',
     '约 30–90 秒',
     [yk('marisa', bank_dx(3), 3, 'p', aquatic=True, friendliness=100, uuid=uid('worn_hat_boat', 'p'), **pair_ready()),
      yk('marisa', bank_dx(0) + 3, 0, 'c', dy=1, stage='child', aquatic=True, yaw=-90, lead=True, skills={'sailing': 2},
         uuid=uid('worn_hat_boat', 'c'), kin=f'Mother:{uuid_nbt(uid("worn_hat_boat", "p"))}', **pair_ready()),
      afloat('p', water_at(3, 4), True, sel('p'))],
     vantage=(bank_dx(0) - 2, 1, -90, 18),
     note='谁会这样做由能力数据 hat_boat 决定（只有水生魔理沙），还要戴着帽子、会攀爬、已会操帽。日志：[intent] start id=escape_water → [method] pick method=hat_boat → [action] start id=place_prop … prop=hat → [hat] placed … water=true → [action] start id=climb → [body] mount cause=climb → [intent] end result=DONE；亲油记 rescue refused reason=can_sail protected=true 与 watch start，不能出现 rescue start。自帽上的后续 paddle 记 tool=braid。泡水伤照常扣血，但不打断摘帽、游与爬',
     reads=[('读子油', f'yukkuri status {sel("c")} raw'), ('读亲油', f'yukkuri status {sel("p")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=2400, expect=[r'\[aquatic\][^\n]* rescue refused [^\n]*reason=can_sail protected=true\b',
                                   r'\[aquatic\][^\n]* watch start [^\n]*reason=can_sail\b',
                                   r'\[action\][^\n]* start id=place_prop\b', r'\[hat\][^\n]* placed [^\n]*water=true\b',
                                   r'\[action\][^\n]* start id=climb\b', r'\[body\][^\n]* mount [^\n]*cause=climb\b',
                                   r'\[action\][^\n]* start id=paddle\b[^\n]*tool=braid\b'],
                forbid=_WATER_SMOKE_FORBID + [r'\[aquatic\][^\n]* rescue start\b', r'\[intent\][^\n]* start id=rescue\b']))

pond('cargo', '帽里存粮取食，空帽打下', '帽里存粮取食，空帽打下',
     '近岸的饱腹船主帽里已有八份苹果，用手中食物右击它应提示已满，手中不扣、帽子不晃。另一只饿船主有三份苹果，会原地低头取粮吃，数量下降时帽底贴图缩小。手持食物右击放一份、潜行最多八份，只扣实际收下的数量；不同食物合计最多八份。贴图贴在帽底、少量靠船头，食物不占位也不增重。空手摇到翻覆后，余粮按种类散成水上物品，可捡起核对数量。另一顶没有乘员的空帽装着八份苹果，用左键连打：每打一下晃一下，三秒内第三下直接变成一顶可拾取的帽子、八份粮全掉出',
     '约 1 分钟',
     [yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=-90, lead=True, nutrition=48, energy=100, hygiene=100, label='饿船主'),
      yk('marisa', bank_dx(5), 5, 'full', aquatic=True, yaw=0, label='满粮船主', **pair_ready()),
      yk('marisa', bank_dx(-5) - 5, -5, 'empty', aquatic=True, label='空帽船主', **pair_ready()),
      afloat_now('a', water_at(0, 3), True, sel('a'), food=3),
      afloat_now('full', water_at(5, 3), True, sel('full'), food=8),
      afloat_now('empty', water_at(-5, 3), False, sel('empty'), food=8)],
     late=[f'execute at {sel("empty")} run tag {_HAT} add empty_hull'],
     give=['minecraft:apple 8', 'minecraft:bread 4'],
     note='先给近岸满粮帽放一份，看拒绝与手中余量；再给饿船主补粮，看帽底贴图换档、原地取食。读 hat.float 的 stores、tier、eats、refusals；读 Motion.Stores 核对按种类保存，重载后余量一致。'
          '日志：[hat] stores put / stores refused reason=full / stores taken use=eat → [meal] swallowed；翻覆只有一条 stores spilled，逐种核对余量；空帽左键打三下是 `pickup cause=hit_empty`，每下一条 `hit ... count=`。' + _DESIRE_NOTE_EARLY,
     reads=[('读饿船主储粮', f'yukkuri status {sel("a")} raw'), ('读满粮船主', f'yukkuri status {sel("full")} raw'),
            ('读帽子存档', f'data get entity {_HAT}')],
     smoke=dict(ticks=1200, commands=[(tick, f'execute as {sel("full")} on vehicle run yukkuri scenario shake @s') for tick in range(600, 700)]
                + [(tick, 'yukkuri scenario hit @e[tag=empty_hull,limit=1]') for tick in (70, 80, 90)],
                expect=[r'\[hat\][^\n]* stores put by=scenario [^\n]*accepted=3[^\n]*total=0 -> 3 tier=2',
                        r'\[hat\][^\n]* stores put by=scenario [^\n]*accepted=8[^\n]*total=0 -> 8 tier=3',
                        r'\[hat\][^\n]* stores taken [^\n]*use=eat [^\n]*total=3 -> 2 tier=1',
                        r'\[hat\][^\n]* stores spilled [^\n]*cause=shake [^\n]*kinds=\{minecraft:apple=8\} -> \{\} total=8 -> 0',
                        r'\[hat\][^\n]* hit player=[^\n]*count=3\b',
                        r'\[hat\][^\n]* pickup cause=hit_empty[^\n]*count=1'],
                forbid=[r'\[body\][^\n]* invariant\b'],
                readout=[f'yukkuri status {sel("a")} raw', f'yukkuri status {sel("full")} raw']))

_TEARFUL = dict(intelligence=55, friendliness=20, attachment=65, self_control=45)
_COLD = dict(intelligence=55, friendliness=20, attachment=15, self_control=40)
_PLEAD_KID = dict(attachment=75, friendliness=60, boldness=45, self_control=70)
_FREEZE_KID = dict(attachment=45, friendliness=60, boldness=35, self_control=70)
_SULK_KID = dict(attachment=20, friendliness=60, boldness=45, self_control=70)

_SEVER_FORBID = [r'\[body\][^\n]* invariant\b', r'\[hat\][^\n]* capsized cause=(?:shake|turbulence)\b',
                 r'\[action\][^\n]* refused id=hop [^\n]*detail=no_spot',
                 r'\[needs\][^\n]* starvation damage accepted=true',
                 r'\[body\][^\n]* death settled[^\n]*cause=(?!.*(?:drown|water))']
_SEVER_EXPECT = [r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=2\b[^\n]*food=0\b',
                 r'\[hat\][^\n]* scenario place [^\n]*place=HEAD\b',
                 r'\[hat\][^\n]* scenario place [^\n]*place=BRIM\b',
                 r'\[hat\][^\n]* capsized cause=too_heavy\b[^\n]*thrown=[1-9]\b',
                 r'\[water\][^\n]* phase=(?:STRUGGLE|EXHAUSTED) -> RECOVER\b']
# The head seat is the overflow for a young that cannot hop onto the brim by itself, and one that can hop always takes the brim
# (PlacedHatEntity.places). The driven-out young here has already learnt to crawl, so it hops and sits on the brim; the smaller
# young that has not learnt yet rides on the mother's head.
_SEVER_SEATS = ((2, 'HEAD'), (3, 'BRIM'))


def _severance_scene(sid, title, watch, parent, label, parent_traits, child3_traits, verdict, branch, *, out=3.5, first=120, far_sibling=False):
    # The driven-out young is the one that has already learnt to crawl: it walks out at its own gait and can live as a stray at
    # once (a newborn that never practised crawling stays where it is put, and the severance shows nothing).
    actors = [*_overload_family(sid, parent, 0, False, label, out=out, stick=True, child3_traits=child3_traits,
                                head_childhood=CHILDHOOD_HOPPING, **parent_traits)]
    far_actor = None
    if far_sibling:
        far_actor = 'normal_far'
        # Third young 16 blocks west of the straight west bank (inside parent's 24-block search, beyond 12-block sight and
        # behind a 3-high stone wall) with water and apples so it stays put and misses the departure.
        actors += [
            yk('marisa', bank_dx(0) - 16, 0, far_actor, aquatic=True, fam=_seat_family(.20, _YOUNG2_NOURISHMENT, 76000),
               childhood=CHILDHOOD_CRAWLING_READY, uuid=uid(sid, far_actor), kin=f'Mother:{uuid_nbt(uid(sid, parent))}',
               label='远处的孩子', **pair_ready()),
            box(bank_dx(0) - 14, 0, -2, bank_dx(0) - 14, 2, 2, 'minecraft:stone'),
            box(bank_dx(0) - 17, 0, -1, bank_dx(0) - 17, 1, 1, 'minecraft:oak_fence'),
            box(bank_dx(0) - 17, 0, -2, bank_dx(0) - 15, 1, -2, 'minecraft:oak_fence'),
            box(bank_dx(0) - 17, 0, 2, bank_dx(0) - 15, 1, 2, 'minecraft:oak_fence'),
            block(bank_dx(0) - 16, 0, -1, 'mcyukkuri:waterer{Water:8}'),
            item('minecraft:apple', 2, bank_dx(0) - 16, 1, 'far_food'),
        ]
    families = ((parent, verdict, branch, far_actor, _SEVER_SEATS),)
    pond(sid, title, title, watch, '约 2 分钟，建议倍速', actors,
         late=[f'execute as {sel(parent)} on vehicle run tag @s add seat_reload',
               *_overload_growth(parent, _CROWD_OVERLOAD_STEPS, first=first)],
         vantage=(-7, 7, -135, 16),
         capture=dict(shots=(1, 300, 1800, 4600), sequences=((200, 4600, 200), (800, 2000, 4))),
         note='单户超载断亲：西岸直岸居中，右侧水面翻船，左侧干地走远。日志依次 overload verdict -> overload beat start -> driven out ordered -> driven out begins -> 分支 beat -> turn_away -> departed -> parent dismissed -> parting_begins（含泪亲油）。',
         reads=[(f'读亲油（{label}）', f'yukkuri status {sel(parent)} raw'),
                ('读最大孩子', f'yukkuri status {sel(parent + "3")} raw'),
                ('读载重帽', 'data get entity @e[tag=seat_reload,limit=1]')],
         smoke=dict(ticks=7200,
                    expect=list(_SEVER_EXPECT),
                    forbid=list(_SEVER_FORBID),
                    verify=lambda window, s=sid, f=families: _overload_tradeoff(window, s, f) or _escape_needs_place(window) or _bank_pickup_retries(window),
                    readout=[f'yukkuri status {sel(who)} raw' for who in (parent, parent + '2', parent + '3')]
                            + ['data get entity @e[tag=seat_reload,limit=1] Riders',
                               'data get entity @e[tag=seat_reload,limit=1] Motion',
                               'data get entity @e[tag=seat_reload,limit=1] Inverted']))


_severance_scene('hat_sever_tearful', '超载断亲：含泪转身与远手足',
                 '翻船后含泪亲油赶走最大孩子，孩子哀求被转身拒绝后走远断亲，墙后远手足无反应',
                 'normal', '含泪·央求', _TEARFUL, _PLEAD_KID, 'DRIVE_TEARFUL', 'plead', out=8.0, first=80, far_sibling=True)
_severance_scene('hat_sever_cold', '超载断亲：冷淡推开',
                 '翻船后冷淡亲油赶走最大孩子，孩子哀求被推开后转身走远断亲',
                 'cold', '冷淡·央求', _COLD, _PLEAD_KID, 'DRIVE_COLD', 'plead')
_severance_scene('hat_sever_freeze', '超载断亲：僵住凝望',
                 '翻船后含泪亲油赶走最大孩子，孩子僵住凝望后转身走远断亲',
                 'freeze', '含泪·僵望', _TEARFUL, _FREEZE_KID, 'DRIVE_TEARFUL', 'stare')
_severance_scene('hat_sever_sulk', '超载断亲：赌气哼走',
                 '翻船后冷淡亲油赶走最大孩子，孩子赌气哼一声转身走远断亲',
                 'sulk', '冷淡·哼走', _COLD, _SULK_KID, 'DRIVE_COLD', 'sulk')

pond('hat_load_warning', '载重警戒，亲油靠岸卸人', '载重警戒，亲油靠岸卸人',
     '两位亲油各带孩子：北侧普通亲油看到帽船吃水下沉、多晃（载重警戒）就划回岸边卸下全家，再出航只带载得下的孩子；南侧低智力高友善的亲油不理会警戒，载着全家直到翻船，之后仍认全部孩子',
     '约 5 分钟，建议倍速',
     [*_overload_family('hat_load_warning', 'early', -4, False, '智55·友善70', intelligence=55, friendliness=70),
      *_overload_family('hat_load_warning', 'kind', 4, True, '智20·友善80', intelligence=20, friendliness=80)],
     late=[f'execute as {sel("early")} on vehicle run tag @s add seat_reload',
           *_overload_growth('early', _WARNING_STEPS), *_overload_growth('kind', _OVERLOAD_STEPS)],
     capture=dict(shots=(1, 600, 2400, 5200), sequences=((600, 5200, 200), (1400, 2600, 4))),
     note='本场景不断亲：北侧普通亲油演的是「卸人」这一取舍——先看到她皱眉哭一声再划回岸卸人，再出航时载不下的最大孩子留在岸上，她在帽上回头看它一眼，孩子按性格哭、哼或发火（日志 overload beat start … ferry=true → overload left ashore child answers）；南侧不理会警戒、翻船后全认，不演取舍。'
          '日志 [aquatic] load heavy … reason=unload_ashore → come_ashore 结束 DONE → set_out left_ashore（hull_full）；另一位记 capsized cause=too_heavy 与 overload keeps children。'
          '读 load changed 看载重、概率与抽值；不强制翻船或死亡，真实碰撞、拒绝和水伤保留，整窗不得有人饿死。',
     reads=[('读北侧普通亲油（只进警戒）', f'yukkuri status {sel("early")} raw'),
            ('读低智力高友善亲油', f'yukkuri status {sel("kind")} raw'),
            ('读低智力高友善亲油的最大孩子', f'yukkuri status {sel("kind3")} raw'),
            ('读载重帽', 'data get entity @e[tag=seat_reload,limit=1]')],
     smoke=dict(ticks=6000, reload=(60, '@e[tag=seat_reload,limit=1]'),
                expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=3\b[^\n]*food=0\b',
                        r'\[hat\][^\n]* scenario place [^\n]*place=CABIN\b[\s\S]*\[hat\][^\n]* scenario place [^\n]*place=HEAD\b[\s\S]*\[hat\][^\n]* scenario place [^\n]*place=BRIM\b',
                        r'\[hat\][^\n]* load warning ratio=[\s\S]*\[hat\][^\n]* capsized cause=too_heavy\b[^\n]*thrown=[1-9]\b',
                        r'\[water\][^\n]* phase=(?:STRUGGLE|EXHAUSTED) -> RECOVER\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[hat\][^\n]* capsized cause=(?:shake|turbulence)\b',
                        # the early family's brim young, left ashore by the load rule, may walk up once and find the hull taken
                        r'\[action\][^\n]* uuid=(?!' + _uuid_text('hat_load_warning', 'early2') + r')\S+ [^\n]* refused id=hop [^\n]*detail=no_spot',
                        r'\[needs\][^\n]* starvation damage accepted=true',
                        r'\[body\][^\n]* death settled[^\n]*cause=(?!.*(?:drown|water))'],
                verify=lambda window: _load_warning_choice(window) or _escape_needs_place(window) or _bank_pickup_retries(window),
                readout=[f'yukkuri status {sel(who)} raw'
                         for who in ('early', 'kind', 'early2', 'early3', 'kind1', 'kind2', 'kind3')]
                        + ['data get entity @e[tag=seat_reload,limit=1] Riders',
                           'data get entity @e[tag=seat_reload,limit=1] Motion',
                           'data get entity @e[tag=seat_reload,limit=1] Inverted']))

pond('hat_larder', '捞苹果存进帽里，再取食', '捞苹果存进帽里，再取食',
     '帽里一开始是空的：亲油划向水面漂着的苹果，叼起放进帽底，帽底的苹果图随存量变多。之后饿的孩子被喂或自己吃时，看她从帽里取粮',
     '约 2–3 分钟',
     [yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=-90, lead=True, uuid=uid('hat_larder', 'a'), label='帽里空粮', **pair_ready()),
      yk('marisa', bank_dx(-1) - 1, -1, 'k1', aquatic=True, fam=_seat_family(.16, 22909, 36000), childhood=CHILDHOOD_CRAWLING_READY,
         uuid=uid('hat_larder', 'k1'), also=('crew',), kin=f'Mother:{uuid_nbt(uid("hat_larder", "a"))}', label='孩子1', **pair_ready()),
      yk('marisa', bank_dx(1) - 1, 1, 'k2', aquatic=True, fam=_seat_family(.20, 84000, 76000), childhood=CHILDHOOD_CRAWLING_READY,
         uuid=uid('hat_larder', 'k2'), also=('crew',), kin=f'Mother:{uuid_nbt(uid("hat_larder", "a"))}', label='孩子2·饿',
         **{**pair_ready(), 'nutrition': 35}),
      item('minecraft:apple', 1, bank_dx(0) + 10, -1, name='apple1'),
      item('minecraft:apple', 1, bank_dx(0) + 11, 0, name='apple2'),
      item('minecraft:apple', 1, bank_dx(0) + 10, 1, name='apple3'),
      afloat_now('a', water_at(0, 6), True, sel_all('crew'), stick=True)],
     vantage=(bank_dx(4) - 1, 4, -125, 15),
     note='日志 [intent] start id=forage → [aquatic] forage taken … stored → [hat] stores put … total=0 -> 1 → 孩子吃或被喂时 stores taken … use=eat|feed；帽里存到两份就不再捞，翻覆才会散出。'
          '亲油自己不饿，捞到的苹果放进帽里而不是吃掉；一次没看到存粮就重开。',
     reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读饿孩子', f'yukkuri status {sel("k2")} raw'),
            ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=3600,
                expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=2\b[^\n]*food=0\b',
                        r'\[intent\][^\n]* start id=forage\b',
                        r'\[aquatic\][^\n]* forage taken item=\S+ stored\b',
                        r'\[hat\][^\n]* stores put [^\n]*total=0 -> 1\b',
                        r'\[hat\][^\n]* stores taken [^\n]*use=(?:eat|feed)\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[hat\][^\n]* stores spilled\b', r'\[needs\][^\n]* starvation damage accepted=true',
                        r'\[body\][^\n]* death settled\b'],
                readout=[f'yukkuri status {sel("a")} raw', f'yukkuri status {sel("k2")} raw', f'data get entity {_HAT} Motion']))

pond('hat_swap', '陆地帽与水生帽并排比大小', '陆地帽与水生帽并排比大小',
     '岸上并排站着戴帽的陆地魔理沙和水生魔理沙，另有两位成体与一只子油把各自的空帽放在面前的水面上。成体水生帽是陆地帽的 1.4 倍；子油帽按自己的体型缩小；戴着的与放下的同样大小；水生空帽倒扣漂着时仍同大，陆地帽落水会下沉，不作帽船',
     '约 30 秒',
     [yk('marisa', -4, -1, 'land_worn', yaw=-90, lead=True, label='陆地·戴着', **pair_ready()),
      yk('marisa', -4, 1, 'aqua_worn', aquatic=True, yaw=-90, label='水生·戴着', **pair_ready()),
      yk('marisa', bank_dx(-3), -3, 'land_owner', yaw=-90, label='陆地·放下', **pair_ready()),
      yk('marisa', bank_dx(3), 3, 'aqua_owner', aquatic=True, yaw=-90, label='水生·放下', **pair_ready()),
      yk('marisa', bank_dx(0), 0, 'kid_owner', stage='child', aquatic=True, fam=_seat_family(), yaw=-90,
         uuid=uid('hat_swap', 'kid_owner'), label='子油·放下', **pair_ready()),
      afloat('land_owner', water_at(-3, 3), False), afloat('aqua_owner', water_at(3, 3), False),
      afloat('kid_owner', water_at(0, 2), False)],
     vantage=(0, 8, 180, 15),
     late=[f'execute at {sel("aqua_owner")} run data merge entity {_HAT} {{Roll:180f,Inverted:1b,Motion:{{Phase:"INVERTED"}}}}'],
     note='两顶成体空帽相距 6 格，各在岸边 3 格外；子油空帽在两者之间，读 [hat] placed 的 scale 与 hullBox 核对实际帽体。水生空帽倒扣漂着（倒扣是它真实的漂浮姿态，不是被测环节）。'
          '水生放帽者从岸上先招帽靠岸、再咬檐推正、最后收回戴上（日志 [action] start id=beckon → end … at_bank → start id=right … push= → [hat] righted cause=reclaim → [headwear] reclaim done）；'
          '成体招不到就直接下水游去；尚不会出航的子油只在岸边真能够到时处理帽子，否则继续日常。陆地魔理沙没有这个欲望，陆地帽落水会下沉。',
     reads=[('读水生放帽者', f'yukkuri status {sel("aqua_owner")} raw'), ('读子油放帽者', f'yukkuri status {sel("kid_owner")} raw'),
            ('读陆地放帽者', f'yukkuri status {sel("land_owner")} raw'), ('读最近的帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=1800, expect=[r'\[hat\][^\n]* placed [^\n]* scale=0\.48999998[^\n]* hullBox=AABB'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* death settled into remains'],
                readout=[f'yukkuri status {sel(who)} raw' for who in ('aqua_owner', 'kid_owner', 'land_owner', 'aqua_worn', 'land_worn')]))
