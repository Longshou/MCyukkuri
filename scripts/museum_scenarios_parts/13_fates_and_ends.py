_STEPS_IN = r'\[action\][^\n]* start id=step_in\b'                                       # an owner wading out to her hat
_STEM_DRAW = dict(kind='starve', who=r'aftermath reserved ', match=r'accepted damage cause=starve\b.* stem_depletion=true\b',
                  why='the marked hit is an actual draw by the growing stem')
_FATES = {
    'stray_junk_headwear': [dict(kind='death', who=f'uuid={_uuid_text("stray_junk_headwear", "covered_stray")} ',
                                why='the smoke kills only the covered control to inspect its real remains')],
    'remains_forms': [dict(kind='death', match=r'cause=(?:starve|inFire|anvil|mob|freeze|drown)\b|internal=(?:old_age|mold)\b|core hurt \(mold\)',
                           why='all live actors are the eight death-form victims and the starved baby buried for its marker'),
                      dict(kind='drown', why='the pool victim is deliberately killed by drowning')],
    'den_family': [dict(kind='death', who=f'uuid={_uuid_text("den_family", "father")} ', match=r'cause=genericKill\b',
                        why='the smoke kills the father to widow the mother')],
    'family_absence': [dict(kind='death', who=f'uuid={_absent_uuid} ', match=r'cause=genericKill\b',
                            why='the ridge hides the forager at the moment of death')],
    'widow_repair': [dict(kind='death', match=r'cause=genericKill\b',
                          why='the scene button ends the original partner through the real death path')],
    'settle_pit_famine': [dict(kind='starve', who=r'pit slave ', why='an empty pit: the slave at nutrition 0 really starves')],
    'aftermath_growth': [_STEM_DRAW,
                         dict(kind='starve', who=_MD11_UNFED_MOTHER,
                              match=r'\bstarvation damage accepted=true\b|\baccepted damage cause=starve\b(?![^\n]*\bstem_depletion=true\b)',
                              why='unfed stem mother'),
                         dict(kind='death', who=_MD11_UNFED_MOTHER,
                              match=r'\bdeath settled into remains; cause=starve\b[^\n]* stem_depletion=false\b',
                              why='unfed stem mother')],
    'aftermath_depletion': [_STEM_DRAW, dict(kind='death', who=r'aftermath reserved ', match=r'stem_depletion=true\b', why='she dies of the stem')],
    'growth': [dict(kind='death', who=r'lifespan reached', match=r'internal=old_age\b', why='the fifth starts one tick before its lifespan')],
    'leaf_swaddle_neglect_stages': [dict(kind='death', match=r'internal=swaddle_retention\b',
                                         why='the end of the row starts in the lethal stage with little health: it dies of retention')],
    'sacrifice': [dict(kind='death', who=f'uuid={_uuid_text("sacrifice", "parent")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                       why='the parent that draws the hunt may be killed by its strikes or bleed to death from the wounds it took shielding the child')],
    'hat_push': [dict(kind='drown', who=_THROWN, why='the fast boat capsizes the far hat and throws its rider in')],
    'hat_current': [dict(kind='drown', who=_CURRENT_HEAD, match=_CURRENT_IMMERSION + r'[^\n]* loss=1\.0\b',
                         why='after the grounded captain goes under, the shed head young may take one hit before stepping onto a dry tread')],
    'hat_grounded_capsized': [dict(kind='drown', who=_THROWN, why='falling water and a hard rock throw the crews in')],
    'shake_capsize': [dict(kind='drown', who=_THROWN, why='three shakes capsize the hat and throw every rider in')],
    'self_rescue': [dict(kind='drown', who=r'scenario afloat prepared [^\n]*owner_aboard=false', why='the owner is pushed off the bank')],
    'shore_capsized_far': [dict(kind='drown', who=_STEPS_IN, why='her hat is beyond the bank reach: she wades in and swims')],
    'worn_hat_boat': [dict(kind='drown', who=f'uuid={_uuid_text("worn_hat_boat", "c")} ', why='the young drops into the water')],
    'cargo': [dict(kind='drown', who=_THROWN, why='the shaken full hat capsizes and throws its owner in'),
              dict(kind='drown', who=_STEPS_IN, why='the empty hat\'s owner starts ashore and wades out to reclaim it')],
    'hat_sever_tearful': [dict(kind='drown', why='overloaded hat capsizes and throws the family in')],
    'hat_sever_cold': [dict(kind='drown', why='overloaded hat capsizes and throws the family in'),
                       dict(kind='death', who='uuid=(?:' + '|'.join(_uuid_text('hat_sever_cold', f'cold{n}') for n in (2, 3)) + ') ',
                            match=r'category=drowned\b',
                            why='the capsize throws the cold family in and she drops her largest; the water decides for her young')],
    'hat_sever_freeze': [dict(kind='drown', why='overloaded hat capsizes and throws the family in')],
    'hat_sever_sulk': [dict(kind='drown', why='overloaded hat capsizes and throws the family in'),
                       dict(kind='death', who='uuid=(?:' + '|'.join(_uuid_text('hat_sever_sulk', f'sulk{n}') for n in (2, 3)) + ') ',
                            match=r'category=drowned\b',
                            why='the capsize throws the cold family in and she drops her largest; the water decides for her young')],
    'hat_load_warning': [dict(kind='drown', why='overloaded hats capsize and throw the families in'),
                         dict(kind='death', match=r'category=drowned\b', why='the scene keeps real water damage: survival is not guaranteed')],
    'rescue_child': [dict(kind='drown', who=f'uuid=(?:{_uuid_text("rescue_child", "c1")}|{_uuid_text("rescue_child", "k1")}) ',
                          why='both young are shoved off the bank to be rescued')],
    'rescue_no_seat': [dict(kind='drown', who=f'uuid={_uuid_text("rescue_no_seat", "c1")} ', why='the newborn floats in open water')],
    'birth_hat_own': [dict(kind='drown', who=_THROWN, why='the father\'s hat is shaken over'),
                      dict(kind='drown', who=r'birth landed [^\n]*outcome=WATER\b', why='a newborn may land in the water and be rescued')],
}
for _sid, _allow in _FATES.items():
    next(s for s in S if s.id == _sid).smoke['allow_fate'] = _allow


# Observation contracts only. Each path has ordered (emitter selector, seen predicate) steps;
# named regex groups bind identities and $subject is the actor selected by each.
def _scene_end(sid, final, *paths, forbid=()):
    scene = next(s for s in S if s.id == sid)
    paths = [path if isinstance(path, dict) else dict(steps=path) for path in paths]
    scene.smoke['milestones'] = paths
    scene.smoke['end'] = dict(final=final, paths=paths, forbid=forbid, bounds=scene.plot)
    views = scene.capture.get('views')
    if views is None:
        views = {'': {key: scene.capture[key] for key in ('follow', 'offset') if key in scene.capture}}
        scene.capture['views'] = views
    for view in views.values():
        view.setdefault('criteria', scene.watch)


_DRY = 'alive=true and w=false and distress=false and iwr=false'
_ASHORE = _DRY + ' and veh=false and att.kind=free and g=true'

_scene_end('den_mold_child_exile', [(n, _DRY) for n in ('mother', 'father', 'child', 'baby')],
           [('child', 'seen:settlement:mold exile protest'), ('child', 'seen:settlement:mold exile departed')])
_scene_end('den_mold_keep', [(n, _DRY) for n in ('mother', 'father', 'child', 'baby')],
           *[[(n, 'seen:body:mold caught from=')] for n in ('mother', 'father', 'baby')])

_scene_end(_sweet_box, [(name, _DRY) for name, *_ in _sweet_cast])
_scene_end('park_picnic', [('picnic_pet', _DRY), ('picnic_stray', _DRY)])
_scene_end(_park_aid, [('park_waiter', _DRY), ('park_local', _DRY)])

for _choice, _cast in (('choice_nest', ('nest_declare', 'nest_yield', 'nest_peer_west', 'nest_peer_east')),
                       ('choice_food', ('food_demand', 'food_ask')), ('choice_tool', ('tool_plead', 'tool_withdraw')),
                       ('choice_toilet', ('floor_dirty', 'toilet_use'))):
    _scene_end(_choice, [(name, _DRY) for name in _cast])
_scene_end('choice_snatch', [(name, _DRY) for name in ('food_snatch', 'food_polite', 'serving_food_snatch', 'serving_food_polite')],
           [('food_snatch', 'seen:badge:beg transfer.*result=snatched'),
            ('food_snatch', 'seen:meal:swallowed item=minecraft:apple')],
           [('food_polite', 'seen:badge:beg refusal.*response=LEAVE'),
            ('serving_food_polite', 'seen:meal:swallowed item=minecraft:apple')])

for _beg_scene in ('wild', 'wild_beg_refuse', 'wild_beg_snatch', 'wild_beg_help'):
    _scene_end(_beg_scene, [('pet', _DRY), ('wild', _DRY)])

_scene_end('food_behind_glass', [('glass_food_eater', _DRY)])
_scene_end('food_sill_window', [('sill_beggar', _DRY)])
_scene_end('stray_wind', [('wind_stray', _ASHORE), ('wind_plain', _ASHORE)],
           [('wind_stray', 'seen:habitat:gathered item=minecraft:paper count=1'),
            ('wind_stray', 'seen:habitat:paper bedding placed'),
            ('wind_stray', 'seen:habitat:paper cover draped source=bedding.*count=1'),
            ('wind_stray', 'seen:habitat:paper cover drop item=minecraft:paper count=1')])
_scene_end('stray_paper', [('bold_paper', _ASHORE), ('tired_paper', _ASHORE)],
           [('bold_paper', f'seen:habitat:gathered item=minecraft:paper count=1 source={_loose_paper_id} left=0'),
            ('bold_paper', 'seen:habitat:paper bedding placed')],
           [('tired_paper', 'seen:habitat:gathered item=minecraft:paper count=1.*left=1'),
            ('tired_paper', 'seen:habitat:paper bedding placed'),
            ('tired_paper', 'seen:habitat:gathered item=minecraft:paper count=1.*left=0'),
            ('tired_paper', 'seen:habitat:paper cover draped source=bedding.*count=2'),
            ('tired_paper', 'seen:sleep:begin source=life')])
_scene_end('stray_paper_young',
           [(name, _ASHORE) for name in ('devoted_parent', 'devoted_baby', 'self_parent', 'self_baby')],
           [('devoted_parent', 'seen:habitat:wind cover paper approach'),
            ('devoted_parent', 'seen:habitat:baby paper cover approach'),
            ('devoted_parent', 'seen:habitat:paper cover draped on child='),
            ('devoted_parent', 'seen:habitat:gathered item=minecraft:paper count=1'),
            ('devoted_parent', 'seen:habitat:paper cover draped source=bedding.*count=3'),
            ('devoted_parent', 'seen:habitat:paper cover drop item=minecraft:paper count=3'),
            ('devoted_parent', 'seen:temperature:fire rest')],
           [('devoted_baby', 'seen:habitat:paper cover drop item=minecraft:paper count=1'),
            ('devoted_baby', f'seen:temperature:fire rest .*shield_parent={_devoted_parent_txt}')],
           [('self_parent', 'seen:habitat:wind cover paper approach'),
            ('self_parent', 'seen:habitat:paper cover kept for self'),
            ('self_parent', 'seen:habitat:paper cover draped source=bedding.*count=3')])

for _exile_scene, _witnesses in (('colony_mold_exile', ['leader', 'witness']),
                                 ('den_mold_exile', ['healthy', 'grandparent', 'baby'])):
    _scene_end(_exile_scene, [('molded', _ASHORE), *((name, _DRY) for name in _witnesses)],
               [('molded', 'seen:settlement:mold exile protest'),
                ('molded', 'seen:settlement:mold exile departed'),
                ('molded', 'seen:settlement:mold exile appeals'),
                ('molded', 'seen:settlement:mold exile refused return'),
                ('molded', 'seen:settlement:mold exile withdrew after refusal')])

_scene_end('home_pane', [('bold',_ASHORE),('timid',_ASHORE)],
           [('bold','seen:action:start id=bash'),('bold','seen:action:hit id=bash'),
            ('bold','seen:home:pane opened'),('world','seen:home-claim:.*GATHER -> HOLD')])
_scene_end('home_open', [('bold',_ASHORE),('timid',_ASHORE)],
           [('world','seen:home-claim:.*GATHER -> HOLD')])
_scene_end('den_move', [('mother', _DRY), ('father', _DRY), ('child', _DRY)],
           [('actors', 'seen:habitat:relocate begin.*reason=flooded'),
            ('actors', 'seen:habitat:relocate arrived'),
            ('actors', 'seen:home-claim:declared.*spoken=true')])
_scene_end('home_greedy', [('greedy', _DRY)],
           [('greedy', 'seen:home-claim:occupation chosen.*kind=bed'),
            ('greedy', 'seen:home-claim:declared.*spoken=true')])

_scene_end('den_family', [('mother', _DRY), ('child', _ASHORE), ('father', 'alive=false')],
           [('mother', 'seen:habitat:home chosen'), ('mother', 'seen:forage:begin role='),
            ('mother', 'seen:forage:deposit home=')])
_scene_end('forage', [('mother', _DRY), ('father', _DRY), ('child', _DRY)],
           [('father', 'seen:forage:begin role=FAMILY'),
            ('mother', f'seen:family:home watch started kin={_forage_father}'),
            ('father', 'seen:forage:deposit home=.* moved=[1-9]')],
           [('child', 'seen:family:welcome home started')],
           [('mother', f'seen:family:feed handed to meal child={_uuid_text("forage", "child")}'),
            ('child', f'seen:meal:swallowed item=.* feeder={_uuid_text("forage", "mother")}')])
_scene_end('family_absence', [('absent', 'alive=false'), ('stayer', _DRY), ('older', _DRY), ('baby', _DRY)],
           [('absent', 'seen:forage:begin role=FAMILY phase=OUT reason=already_out'),
            ('absent', 'seen:forage:phase=OUT -> GATHER'), ('absent', 'seen:body:death settled into remains'),
            ('stayer', f'seen:family:kin overdue kin={_absent_uuid}'), ('stayer', f'seen:family:missing kin call kin={_absent_uuid}'),
            ('stayer', f'seen:family:kin absence kin={_absent_uuid} phase=SEARCH destination=\\('),
            ('stayer', f'seen:family:missing kin found remains kin={_absent_uuid}'),
            ('stayer', r'seen:status:behavior.animation: .* -> bereaved'),
            ('stayer', 'seen:forage:begin role=FAMILY phase=OUT reason=sole_caregiver'),
            ('stayer', r'seen:forage:goal active phase=OUT .* pace=(?:1\.0[1-9]|1\.[1-4])'),
            ('stayer', 'seen:forage:deposit home=.* moved=[1-9]')],
           [('older', f'seen:family:kin overdue kin={_absent_uuid}'),
            ('older', f'seen:family:missing kin call kin={_absent_uuid}')],
           [('baby', f'seen:family:missing kin call kin={_absent_uuid}'),
            ('baby', f'seen:family:kin absence kin={_absent_uuid} phase=MOVED_ON')],
           [('stayer', f'seen:family:feed handed to meal child={_uuid_text("family_absence", "baby")}'),
            ('baby', f'seen:meal:swallowed item=.* feeder={_uuid_text("family_absence", "stayer")}')],
           forbid=[('baby', f'seen:family:kin absence kin={_absent_uuid} phase=SEARCH'),
                   ('stayer', 'seen:forage:begin role=FAMILY phase=OUT reason=shared_home')])
_scene_end('settle_snow', [('lead', _DRY)], [('actors', 'seen:habitat:home chosen')])
_scene_end('settle_pit_slave', [('slave', _DRY)],
           [('slave', 'seen:settlement:pit slave back in pit'),
            ('slave', 'seen:settlement:pit slave eats droppings')],
           forbid=[('slave', 'seen:mind:stage uneasy -> sweetened|non-yukkuri onset|died of non-yukkuri')])
_scene_end('settle_pit_famine', [('slave', 'alive=true and need.nut=0 and hp<10')],
           [('slave', 'seen:settlement:pit slave hungry reason=no_droppings'),
            ('slave', 'seen:needs:starvation damage')])
_scene_end('sleep', [('a', _DRY)],
           [('a', 'seen:home:bed crossing .*direction=in .*opening=true'),
            ('a', 'seen:sleep:begin source=life occupancy=INSIDE_SUPPORTED'),
            ('a', 'seen:sleep:end source=life.*stretched=true'),
            ('a', 'seen:home:bed crossing .*direction=out .*opening=true')])
_scene_end('birth_land_caught', [('mother', _DRY), ('father', _DRY + ' and hold.hat.state=worn'),
                                 ('child_of:mother', _ASHORE)],
           [('child_of:mother', 'seen:family:birth landed .*outcome=(?:HAT|GROUND)')],
           dict(each='caught_child_of:mother', steps=[
               ('$subject', 'seen:family:birth landed .*outcome=HAT'),
               ('father', r'seen:family:carry put down .*cause=release .*child=$subject\b')],
               post=[('$subject', _ASHORE)]),
           dict(each='caught_child_of:mother', steps=[
               ('$subject', 'seen:family:birth landed .*outcome=HAT'),
               ('father', r'seen:family:carry put down .*cause=release .*child=$subject\b'),
               ('father', 'seen:action:end id=take_prop outcome=DONE detail=prop=hat worn=true')]))
_scene_end('birth_stem_caught', [('mother', _DRY), ('father', _DRY), ('child_of:mother', _DRY)],
           [('child_of:mother', 'seen:family:birth landed .*outcome=(?:HAT|GROUND)')])
_scene_end('birth_nest_safe', [('mother', _ASHORE), ('child_of:mother', _ASHORE)],
           [('mother', 'seen:family:delivery complete')],
           [('child_of:mother', 'seen:family:birth landed .*outcome=(?:CUSHION|GROUND)')])
# No artificial survival requirement for the exposed damage controls: the wetness boundary and real damage are the path.
_scene_end('rain_open', [('a', _DRY), ('b', _DRY)],
           [('a', 'seen:weather:shelter response started')],
           [('wet_ordinary', 'seen:needs:soaking damage')], [('wet_control', 'seen:needs:soaking damage')])
_scene_end('rain_huddle', [('shy', _DRY), ('mate', _DRY), ('alone', _DRY)],
           [('shy', 'seen:weather:huddle sought partner='), ('shy', 'seen:weather:huddle pressed partner='),
            ('shy', 'seen:weather:thunder clap heard .*stage=HUDDLE')],
           [('alone', 'seen:weather:sheltered alone pose=tremble')])
_scene_end('aftermath_growth', [('affected_growth', _DRY),
                               ('source_growth', _DRY), ('source_burden', _DRY)],
           [('affected_growth', 'seen:family:aftermath reserved'), ('affected_growth', 'seen:family:stem sprouted'),
            ('affected_growth', 'seen:family:stem ripe')],
           dict(steps=[('affected_burden', 'seen:family:aftermath reserved'), ('affected_burden', 'seen:family:stem sprouted'),
                       ('affected_burden', 'seen:family:stem ripe')],
                post=[('affected_burden', _DRY)]))
_scene_end('aftermath_depletion', [('affected_depletion', 'alive=false'), ('source_depletion', _DRY)],
           [('affected_depletion', 'seen:family:aftermath reserved'),
            ('affected_depletion', 'seen:family:stem sprouted'),
            ('affected_depletion', 'seen:family:stem drew body'),
            ('affected_depletion', 'seen:body:death settled.*stem_depletion=true'),
            ('affected_depletion', 'seen:body:stem remains.*count=5 absent=0 placement=body')])
# World phase events belong to the same captured group; arrival must name this scene's leader.
_scene_end('colony_migrate', [('lead', _DRY), ('migrant_baby', _ASHORE)],
           [('world', r'seen:settlement:migration phase=DISCUSS group=(?P<group>\S+)'),
            ('world', r'seen:settlement:migration phase=PREPARE group=$group\b'),
            ('world', r'seen:settlement:migration phase=MARCH group=$group\b'),
            ('world', r'seen:settlement:migration pivot arrived group=$group .*leader=$lead\b')],
           dict(each='migrant_baby', steps=[
               ('parent_of:$subject', r'seen:family:carry pickup .*purpose=MIGRATE child=$subject childStage=BABY\b'),
               ('parent_of:$subject', r'seen:family:carry put down reason=arrived_destination .*purpose=MIGRATE child=$subject\b'),
               ('$subject', r'seen:settlement:migration reached .*dependent=true')],
               post=[('$subject', _ASHORE)]))
_scene_end('carry_home', [(f'{kind}_baby', _ASHORE) for kind in ('marisa', 'reimu', 'chen')],
           *[dict(each=f'{kind}_baby', autonomous_home=True, steps=[
              (f'{kind}_parent', r'seen:family:^carry pickup hold=\S+ .*child=$subject\b'),
              (f'{kind}_parent', r'seen:family:carry (?:set down started reason=arrived_home child=$subject\b|release target child=$subject placement=nest|lowering hold=\S+ child=$subject\b)'),
              (f'{kind}_parent', r'seen:family:carry put down reason=\S+ .*cause=release .*child=$subject .*nest=(?:INSIDE_SUPPORTED|PARTIAL)')],
              post=[('$subject', _ASHORE + f' and nest_of.{kind}_parent=true and nest=INSIDE_SUPPORTED')])
             for kind in ('marisa', 'reimu', 'chen')])
next(s for s in S if s.id == 'carry_home').smoke['end']['carried_min'] = 2
_scene_end(_walk_home, [(child, _ASHORE) for child in ('baby_a', 'baby_b', 'small_child')],
           *[dict(each=child, steps=[
               ('mother', rf'seen:family:^carry pickup .*purpose=RETRIEVE child=$subject\b'),
               ('mother', rf'seen:family:carry put down .*cause=release purpose=RETRIEVE child=$subject .*nest=(?:INSIDE_SUPPORTED|PARTIAL)')],
               post=[('$subject', _ASHORE + ' and nest_of.mother=true and nest=INSIDE_SUPPORTED')])
             for child in ('baby_a', 'baby_b')],
           dict(each='small_child', autonomous_home=True, steps=[
               ('mother', r'seen:family:^carry pickup .*purpose=RETRIEVE child=$subject\b'),
               ('mother', r'seen:family:carry put down .*cause=release purpose=RETRIEVE child=$subject .*nest=(?:INSIDE_SUPPORTED|PARTIAL)')],
               post=[('$subject', _ASHORE + ' and nest_of.mother=true and nest=INSIDE_SUPPORTED')]))
# The contract: every child ends delivered and calm at the nest. A gathered child is carried there and set down (a second trip for
# the second one is an ordinary retrieve and counts); a child the parent left behind walks there by itself, is fetched, or is declared
# dead. Within 40 ticks of the put-down the child is on the nest (rim or cushion), not OUTSIDE (a frightened baby may sit on the rim),
# and it stays at the nest for 80% of the ticks 100-300 after the put-down. The scene ends before the hungry predator rests up and
# hunts again (about 1900 ticks after its return), so that second sortie is not judged here; the end only needs both children
# alive, free and on the ground (nobody left in a mouth or brim).
# The bold Reimu carries both (a second trip counts); Marisa's 4.5 s brim lift may be cut short by the dive, so a child she could not
# take must walk to the nest by itself; the timid parent takes one and leaves the other.
for _flight_id, _carried, _cause, _nest in (
        (_flight_home, 2, 'release', '(?:INSIDE_SUPPORTED|PARTIAL)'),
        (_flight_hat, 1, '(?:brim_hop|release)', '(?:INSIDE_SUPPORTED|PARTIAL|ON_RIM)'),
        (_flight_timid, 1, 'release', '(?:INSIDE_SUPPORTED|PARTIAL)')):
    _scene_end(_flight_id, [(child, _ASHORE) for child in ('baby_a', 'baby_b')],
               *[dict(each=child, walks_home=_carried < 2, post_within=40,
                      hold=dict(after=300, span=200, share=.8, clauses=[('$subject', 'nest!=OUTSIDE')]), steps=[
                   ('mother', rf'seen:family:^carry pickup .*purpose=(?:PROTECT|RETRIEVE) child=$subject\b'),
                   ('mother', rf'seen:family:carry put down .*cause={_cause} purpose=(?:PROTECT|RETRIEVE) child=$subject .*nest={_nest}')],
                   post=[('$subject', _ASHORE + ' and nest!=OUTSIDE')])
                 for child in ('baby_a', 'baby_b')])
    next(s for s in S if s.id == _flight_id).smoke['end']['carried_min'] = _carried
_scene_end('hat_push', [('a', 'alive=true and distress=false'), ('fast', 'alive=true and distress=false')],
           [('hat_of:fast', 'seen:hat:capsized cause=impact .*count=1')],
           forbid=[('hat_of:a', 'seen:hat:capsized')])
_scene_end('hat_current', [('a', _DRY), ('child', _DRY)],
           dict(steps=[('a', 'seen:body:dismount .*cause=grounded'),
                       ('a', 'seen:headwear:received loose accessory')], post=[('a', _ASHORE), ('child', _ASHORE)]))
_scene_end('shake_capsize', [('a', _DRY + ' and place=cabin'), ('k1', _DRY), ('k2', _DRY)],
           [('a', 'seen:body:dismount .*cause=thrown'), ('a', 'seen:action:end id=right outcome=DONE'),
            ('a', 'seen:body:mount .*cause=climb')])
_scene_end('self_rescue', [('a', _DRY + ' and place=cabin')],
           [('a', 'seen:water:escape start'), ('a', 'seen:body:mount .*cause=climb'),
            ('a', 'seen:water:escape done')])
_scene_end('shore_capsized', [('a', _DRY)],
           dict(steps=[('a', 'seen:action:end id=beckon outcome=DONE detail=at_bank'),
                       ('a', 'seen:action:end id=right outcome=DONE'), ('a', 'seen:headwear:reclaim done how=worn')],
                post=[('a', _ASHORE + ' and hold.hat.state=worn')]))
# Overloaded families retain real risk; no prescribed survival for a deliberate capsize. Surviving young
# must still be safe (ORPHAN remains independent); the load response itself is the observable path.
_scene_end('hat_sever_tearful', [('normal', 'alive=true and distress=false'), ('normal2', 'alive=true'), ('normal3', 'alive=true'), ('normal_far', 'alive=true')],
           [('normal', 'seen:family:overload verdict .*verdict=DRIVE_TEARFUL'), ('normal', 'seen:family:overload beat start .*verdict=DRIVE_TEARFUL'),
            ('normal', 'seen:family:parent dismissed child=')])
_scene_end('hat_sever_cold', [('cold', 'alive=true and distress=false')],
           [('cold', 'seen:family:overload verdict .*verdict=DRIVE_COLD'), ('cold', 'seen:family:overload beat start .*verdict=DRIVE_COLD'),
            ('cold', 'seen:family:parent dismissed child=')])
_scene_end('hat_sever_freeze', [('freeze', 'alive=true and distress=false'), ('freeze2', 'alive=true'), ('freeze3', 'alive=true')],
           [('freeze', 'seen:family:overload verdict .*verdict=DRIVE_TEARFUL'), ('freeze', 'seen:family:overload beat start .*verdict=DRIVE_TEARFUL'),
            ('freeze', 'seen:family:parent dismissed child=')])
_scene_end('hat_sever_sulk', [('sulk', 'alive=true and distress=false')],
           [('sulk', 'seen:family:overload verdict .*verdict=DRIVE_COLD'), ('sulk', 'seen:family:overload beat start .*verdict=DRIVE_COLD'),
            ('sulk', 'seen:family:parent dismissed child=')])
_scene_end('hat_load_warning', [('early', _DRY), ('kind', 'alive=true and distress=false')],
           [('early', 'seen:aquatic:load heavy .*reason=unload_ashore'),
            ('early', 'seen:intent:end id=come_ashore result=DONE')],
           [('kind', 'seen:family:overload keeps children')])
_scene_end('hat_swap', [(name, _DRY) for name in ('land_worn', 'aqua_worn', 'land_owner', 'aqua_owner', 'kid_owner')],
           [('aqua_owner', 'seen:headwear:reclaim done how=worn')])
_scene_end('sail', [('a', _DRY)],
           dict(steps=[('a', 'seen:aquatic:stick taken'), ('a', 'seen:aquatic:set_out complete'),
                       ('a', 'seen:action:end id=paddle outcome=DONE'),
                       ('a', 'seen:intent:end id=come_ashore result=DONE'), ('a', 'seen:meal:swallowed item=minecraft:apple')],
                post=[('a', _ASHORE + ' and hold.hat.state=worn')]))
_scene_end('afloat_family', [('a', _DRY), ('c1', _DRY)],
           dict(steps=[('a', 'seen:family:carry grip .*hold=braid'), ('c1', 'seen:body:seat landed .*cause=braid_set_down'),
                       ('a', 'seen:aquatic:set_out complete .*aboard=1/1')],
                post=[('c1', _DRY + ' and aboard.a=true')]))
_scene_end('afloat_stick', [('a', _DRY + ' and hold.kit.item=minecraft:stick')],
           [('a', 'seen:aquatic:stick target chosen'), ('a', 'seen:aquatic:stick taken .*stowed_in=hat')])
_scene_end('kid_boat_play', [('a', _DRY), ('c1', _DRY), ('c2', _DRY)],
           [('c1', 'seen:aquatic:play kind=(?:kid_brim_splash|kid_mimic_paddle) start spot=brim'),
            ('c1', 'seen:aquatic:play (?:return inside|stays on brim).*reason=fear:thunder')],
           [('c2', 'seen:aquatic:play kind=hat_cradle start spot=head reason=head_dry')])
_scene_end('rescue_child', [('a', _DRY), ('c1', _DRY), ('k1', _DRY)],
           *[[('a', f'seen:family:carry grip .*uuid={_uuid_text("rescue_child", child)}'),
              ('a', f'seen:aquatic:rescue end result=DONE outcome=(?:rescued|set_down) faller={_uuid_text("rescue_child", child)}')]
             for child in ('c1', 'k1')])
_scene_end('rescue_no_seat', [('a', _DRY), ('child_of:a', _DRY)],
           dict(steps=[('a', f'seen:family:carry grip .*uuid={_uuid_text("rescue_no_seat", "c1")}'),
            ('a', 'seen:action:end id=hop outcome=DONE'), ('a', 'seen:action:end id=release outcome=DONE detail=released'),
            ('a', f'seen:aquatic:rescue end result=DONE outcome=set_down faller={_uuid_text("rescue_no_seat", "c1")}')],
                post=[('a', _ASHORE + ' and hold.hat.state=worn and carry_empty=true'), ('child_of:a', _ASHORE)]))
_scene_end('birth_boat_missed', [('mother', _DRY), ('child_of:mother', _DRY)],
           [('mother', 'seen:aquatic:birth shore return'), ('mother', 'seen:action:end id=hop outcome=DONE'),
            ('mother', 'seen:family:delivery complete')],
           dict(each='newborn_of:mother', steps=[('$subject', 'seen:family:birth landed .*outcome=(?:HAT|GROUND|NEST)'),
                                                ('$subject', 'seen:meal:swallowed .*prepared=true')],
                post=[('mother', _ASHORE), ('$subject', _DRY + ' and near.parents<=4')]))
_scene_end('birth_hat_own', [('mother', _DRY), ('father', _DRY), ('child_of:mother', _DRY)],
           [('mother', 'seen:aquatic:birth route=own_hat reason=fit'), ('mother', 'seen:family:delivery complete')],
           [('father', 'seen:aquatic:guard start'), ('father', 'seen:aquatic:guard arrive')],
           [('father', 'seen:aquatic:stick taken .*stowed_in=hat')],
           [('child_of:mother', 'seen:meal:swallowed .*prepared=true')])
_scene_end('first_voyage', [('c1', _DRY)],
           dict(steps=[('c1', 'seen:aquatic:first_voyage start'), ('c1', 'seen:aquatic:first_voyage rounded'),
                       ('c1', 'seen:childhood:sailing learned how=first_voyage'), ('c1', 'seen:aquatic:first_voyage success')],
                post=[('c1', _ASHORE + ' and hold.hat.state=worn')]))
_scene_end('family_search', [('search_parent', _DRY), ('search_child', _DRY), ('lost_mother', _DRY), ('lost_child', _DRY)],
           [('search_parent', 'seen:family:child call started'), ('search_child', 'seen:family:child heard parent call'),
            ('search_parent', 'seen:family:child search reunited')],
           [('lost_mother', 'seen:family:glass vigil started'), ('lost_child', 'seen:family:glass child at stand'),
            ('lost_mother', 'seen:family:glass farewell'), ('lost_mother', 'seen:family:parent dismissed')])
_scene_end('family_search_pane', [('pane_parent', _DRY), ('pane_child', _DRY)],
           [('pane_parent', 'seen:family:glass vigil started'), ('pane_child', 'seen:family:glass child at stand'),
            ('pane_parent', 'seen:action:break id=bash'), ('pane_parent', 'seen:family:glass crossed'),
            ('pane_parent', 'seen:family:child search reunited')])
