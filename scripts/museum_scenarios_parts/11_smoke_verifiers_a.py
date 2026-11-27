# Smoke expectations of the starts the land audit (P6) corrected: each names the subject the scene exists to show.
def _smoke(sid, ticks, expect, forbid=(), readout=()):
    scene = next(s for s in S if s.id == sid)
    scene.smoke = dict(ticks=ticks, expect=list(expect), forbid=[r'\[body\][^\n]* invariant\b', *forbid], readout=list(readout))


# The terminal specimen begins dying after 400 body ticks.
_smoke('body_mold', 360, [
    rf'uuid={_uuid_text("body_mold", "mold0")} [^\n]*line event=mold_self_aware\b',
    rf'uuid={_uuid_text("body_mold", "mold0")} [^\n]*course begun kind=terror strength=0\.3 length=\d+ about=null\b',
    rf'uuid={_uuid_text("body_mold", "mold0")} [^\n]*course goal behavior kind=terror stage=(?:onset|peak) behavior=shaken\b',
    rf'uuid={_uuid_text("body_mold", "mold1")} [^\n]*line event=mold_itch\b',
], forbid=[rf'uuid={_uuid_text("body_mold", "mold1")} [^\n]*course (?:begun|renewed) kind=terror\b'])

_LICK = [r'\[comfort\][^\n]* approach started', r'\[comfort\][^\n]* mouth contact started']
_smoke('hurt', 1800, _LICK)
_smoke('kindness_adoption', 720, [
    rf'\[kindness\][^\n]*uuid={_uuid_text("kindness_adoption", "foster")} [^\n]*bite handed peer={_uuid_text("kindness_adoption", "orphan")} clip=offer_bite\b',
    rf'\[meal\][^\n]*uuid={_uuid_text("kindness_adoption", "orphan")} [^\n]*swallowed item=[^\n]*feeder={_uuid_text("kindness_adoption", "foster")}\b',
    rf'\[family\][^\n]*uuid={_uuid_text("kindness_adoption", "foster")} [^\n]*foster accepted child={_uuid_text("kindness_adoption", "orphan")}\b',
])

# The soaker climbs into the tub unprompted, sits through one soak that uses up one level, and its one wound closes.
_juice_log = _uuid_text('juice_bath', 'soaker')
_smoke('juice_bath', 1800, [
    rf'uuid={_juice_log} [^\n]*tub soak started at=[^\n]* juice=3 ',
    rf'uuid={_juice_log} [^\n]*tub soak ended at=[^\n]* used=true juice=3 -> 2 health=4\.0 -> (?!4\.0 )\S+ wounds=1 -> 0 hygiene=(?!20\.0\b)\S+',
], forbid=[r'tub soak ended [^\n]* used=false'])

# The idle animal on the magma field takes real hot-floor damage, jumps out of each burn and reaches ordinary ground alive.
_smoke('magma_sole', 600, [
    r'\[body\][^\n]* hot floor jump escape=',
    r'\[body\][^\n]* hot floor escaped health=',
])

# Each nest lies beyond the other body in a one-cell lane, so both sleeping at home proves they got past each other; the
# encounter lines (begin, beats, end) of either name who yielded, where, and how long the pass took.
_corridor_logs = [_uuid_text(_corridor, slot) for slot in ('corridor_north', 'corridor_south')]
_dead_logs = [_uuid_text(_dead_end, slot) for slot in ('dead_north', 'dead_south')]


def _encounter_smoke(sid, ticks, logs, expect, forbid=()):
    who = rf'uuid=(?:{logs[0]}|{logs[1]})\b'
    _smoke(sid, ticks, [
        *expect,
        *[rf'\[sleep\][^\n]*uuid={log}\b[^\n]*begin source=life occupancy=INSIDE_SUPPORTED\b' for log in logs]],
        forbid=[rf'\[move\][^\n]*{who}[^\n]* encounter end [^\n]* result=(?:stuck|timeout|aborted)\b',
                rf'\[move\][^\n]*{who}[^\n]* ground path stopped reason=no_progress', *forbid])
    return who


_corridor_who = rf'uuid=(?:{_corridor_logs[0]}|{_corridor_logs[1]})\b'
_encounter_smoke('street_corridor', 1500, _corridor_logs, [
    rf'\[move\][^\n]*{_corridor_who}[^\n]* encounter begin keeper=#\d+ yielder=#\d+ place=\S+ cost=\(\S+,\S+\) reason=(?:nearer_place|stronger|tie|no_place) ',
    rf'\[move\][^\n]*{_corridor_who}[^\n]* encounter end keeper=#\d+ yielder=#\d+ result=passed '])
_dead_who = rf'uuid=(?:{_dead_logs[0]}|{_dead_logs[1]})\b'
_encounter_smoke('street_dead_end', 2200, _dead_logs, [
    rf'\[move\][^\n]*{_dead_who}[^\n]* encounter begin keeper=#\d+ yielder=#\d+ place=\S+ cost=\(\S+,\S+\) reason=nearer_place minor=false ',
    rf'\[move\][^\n]*{_dead_who}[^\n]* encounter end keeper=#\d+ yielder=#\d+ result=passed ',
    # The impatient yielder (self_control 10) stands waiting in the room mouth: one beat, its face shown or a logged holder's.
    rf'\[move\][^\n]*{_dead_who}[^\n]* encounter beat role=yielder beat=wait_impatient face=\w+ declared=\w+ (?:held_by=\S+ held_tier=\S+ held_face=\w+ )?line=\w+ clip=\w+ '],
    forbid=[rf'\[move\][^\n]*{_dead_who}[^\n]* encounter begin [^\n]* reason=(?:no_place|dead_end) ',
            rf'\[move\][^\n]*{_dead_who}[^\n]* encounter end [^\n]* result=no_place'])


def _encounter_verify(base_ticks, per_block, min_len=0, max_holds=1, who=None):
    """One decision per pair and meeting, begun before the bodies touched (gap > 1.5), no role flip (the only reversal within 300 ticks of
    an end is the logged dead end), at most one keeper stop, every full yield printed its face and line beat, each encounter ends within
    ``base_ticks + per_block * len`` of its begin (the trigger gap is 3.5 blocks, so that is first proximity to passing; ``len`` is the
    yielder's walk to its place), the yielder's walk is at least ``min_len`` (it is not sent into a dead end), no removed ladder action and no
    missing-route line for either body inside the pass (first begin .. 40 ticks after the last end; ordinary roaming after the pass is not the
    pass), and no walker moves more than 55 degrees off its facing in the same span (never backwards, resumed routes included)."""
    def verify(window, trace):
        ended, result, lengths, began, span = {}, {}, {}, 0, [10 ** 9, 0]
        for line in re.finditer(r'\[move\][^\n]* t=(\d+) [^\n]* encounter (begin|end) keeper=#(\d+) yielder=#(\d+) ([^\n]*)', window):
            now, kind, keeper, yielder, rest = line.groups()
            now = int(now)
            span = [min(span[0], now), max(span[1], now)]
            pair = (keeper, yielder)
            if kind == 'begin':
                began += 1
                reason = re.search(r'reason=(\w+)', rest).group(1)
                gap = float(re.search(r' gap=(-?[\d.]+)', rest).group(1))
                lengths[pair] = float(re.search(r' len=([\d.]+)', rest).group(1))
                if gap < 1.5 and reason != 'dead_end':
                    return f'keeper=#{keeper} yielder=#{yielder} began at gap {gap}: the bodies had already touched (a trigger that came too late)'
                if lengths[pair] < min_len and reason != 'dead_end':
                    return f'keeper=#{keeper} yielder=#{yielder} walks only {lengths[pair]} blocks (< {min_len}): sent into the dead end'
                for other in (pair, (yielder, keeper)):
                    if other in ended and now - ended[other] < 300 and not (other != pair and reason == 'dead_end' and result.get(other) == 'no_place'):
                        return f'keeper=#{keeper} yielder=#{yielder} decided again {now - ended[other]} ticks after the same pair' + (' with the roles reversed' if other != pair else '')
                if 'minor=false' in rest and not re.search(rf'encounter beat role=yielder beat=yield_notice face=\w+ line=\w+ keeper=#{keeper} yielder=#{yielder}', window):
                    return f'no yield_notice beat for keeper=#{keeper} yielder=#{yielder}'
            else:
                found = re.search(r'result=(\w+) cause=(\w+) ticks=(\d+) holds=(\d+)', rest)
                ended[pair] = now
                result[pair] = found.group(1)
                max_ticks = base_ticks + per_block * lengths.get(pair, 0)
                if int(found.group(3)) > max_ticks:
                    return f'encounter keeper=#{keeper} yielder=#{yielder} took {found.group(3)} ticks (> {max_ticks:.0f}: {base_ticks} + {per_block} per block of its {lengths.get(pair, 0):.1f}-block walk)'
                if int(found.group(4)) > max_holds:
                    return f'keeper stopped {found.group(4)} times in one encounter (stop and go)'
        if not began:
            return 'no encounter began'
        impatience_by_pair = {}
        for m in re.finditer(r'encounter beat role=(\w+) beat=wait_impatient face=(\w+) declared=(\w+)(?: held_by=(\S+) held_tier=\S+ held_face=\w+)? line=(\w+) clip=\w+ keeper=#(\d+) yielder=#(\d+)', window):
            role, shown, declared, held_by, _, keeper, yielder = m.groups()
            if shown != declared and not held_by:
                return f'wait_impatient ({role}) showed face={shown} instead of declared={declared} with no holder'
            key = (keeper, yielder, role)
            impatience_by_pair[key] = impatience_by_pair.get(key, 0) + 1
            if impatience_by_pair[key] > 1:
                return f'wait_impatient ({role}) fired {impatience_by_pair[key]} times in one encounter keeper=#{keeper} yielder=#{yielder}'
        if who:
            for line in window.splitlines():
                tick = re.search(r' t=(\d+) ', line)
                if not tick or not span[0] <= int(tick[1]) <= span[1] + 40 or not re.search(who, line):
                    continue
                if re.search(r'\[move\][^\n]* action=(?:keeps_line|backs_off|gives_way|no_alternative|waiting)\b', line):
                    return f'a removed ladder action at t={tick[1]} inside the pass: {line.strip()}'
                if '[approach]' in line and 'surface path unavailable' in line:
                    return f'a surface route was reported unavailable at t={tick[1]} inside the pass: {line.strip()}'
        return _slides(trace, span[0], span[1] + 40)
    return verify


def _slides(trace, first, last, who=None):
    """The first tick in [first, last] at which a walker (``who``, or any) moves more than 55 degrees off its facing: a backward or sideways slide."""
    for entry in trace:
        row = json.loads(entry.split('[trace] ', 1)[1])
        if row['k'] != 'st' or not first <= row['t'] <= last or who and row['u'] != who:
            continue
        v, yaw = row['st']['v'], math.radians(row['st']['by'])
        speed = math.hypot(v[0], v[2])
        if speed > .05 and (v[0] * -math.sin(yaw) + v[2] * math.cos(yaw)) / speed < math.cos(math.radians(55)):
            return f"{row['u']} moved {speed:.3f} blocks/tick more than 55 degrees off its facing at tick {row['t']} (a backward or sideways slide)"
    return None


_lane_sleeper, _lane_walker = _uuid_text(_lock_lane, 'lane_sleeper'), _uuid_text(_lock_lane, 'lane_walker')
_lane_who = rf'uuid=(?:{_lane_sleeper}|{_lane_walker})\b'
_smoke(_lock_lane, 2500, [
    rf'\[sleep\][^\n]*uuid={_lane_sleeper} [^\n]* begin source=fatigue full=true ',
    rf'\[move\][^\n]*uuid={_lane_walker} [^\n]* encounter begin keeper=#\d+ yielder=#\d+ place=\S+ cost=\(\S+,inf\) reason=locked_stays ',
    rf'\[move\][^\n]*uuid={_lane_walker} [^\n]* encounter beat role=yielder beat=yield_notice face=\w+ line=\w+ ',
    rf'\[move\][^\n]*uuid={_lane_walker} [^\n]* encounter end keeper=#\d+ yielder=#\d+ result=passed cause=placed ',
    rf'\[choreo\][^\n]*uuid={_lane_walker} [^\n]* beat=yield_giveup face=\w+ line=sulk_huff declared=\w+',
    rf'\[move\][^\n]*uuid={_lane_walker} [^\n]* encounter sulk speech tick=\d+ [^\n]* spoken=true',
    rf'\[move\][^\n]*uuid={_lane_walker} [^\n]* gave up walk target=\S+ blocker=#\d+ '],
    forbid=[rf'\[move\][^\n]*{_lane_who}[^\n]* encounter end [^\n]* result=(?:stuck|timeout|aborted|no_place)\b',
            rf'\[move\][^\n]*{_lane_who}[^\n]* ground path stopped reason=no_progress',
            rf'\[move\][^\n]*uuid={_lane_sleeper} [^\n]* encounter begin '])


def _lock_verify(window, trace):
    """The lane's lock is resolved by the walker alone within about 100 ticks of its first blocked window (ladder: plan round, ask, settle): it
    turns round before it walks (no sliding), both its notice and the sulk it keeps at the give-up show the faces they declare, the sulk's
    huff line is spoken after the give-up and inside that hold (not dropped by the notice's speech gap), the sleeper is
    never made to move, no blocked stretch of any pet lasts over 200 ticks, and the walker is never sent at the sleeper again while it still
    stands on the spot where it held the lane up - a wake alone changes nothing, only a real move (> YieldRule.STAYED) lifts the refusal (the
    window runs past the old 1200-tick expiry)."""
    blocked = sorted(int(m[1]) for m in re.finditer(rf'\[move\] \w+ #\d+ {_lane_who} t=(\d+)[^\n]* blocked by yukkuri ', window))
    begin = re.search(rf'\[move\] \w+ #\d+ uuid={_lane_walker} t=(\d+)[^\n]* encounter begin ', window)
    end = re.search(rf'\[move\] \w+ #\d+ uuid={_lane_walker} t=(\d+)[^\n]* encounter end ', window)
    if not blocked or not begin or not end:
        return 'the walker was never held up, or never began and ended an encounter'
    stretches, first = [], blocked[0]
    for before, now in pairwise(blocked):
        if now - before > 120:
            stretches.append((first, before))
            first = now
    stretches.append((first, blocked[-1]))
    if any(last - first > 200 for first, last in stretches):
        return f'a pet stayed blocked by a yukkuri for over 200 ticks: {stretches}'
    if int(end[1]) - blocked[0] > 130:
        return f'resolved {int(end[1]) - blocked[0]} ticks after the first blocked window (> 130)'
    if int(begin[1]) - blocked[0] > 70:
        return f'the pair was decided {int(begin[1]) - blocked[0]} ticks after the first blocked window (> 70)'
    face = re.search(rf'uuid={_lane_walker} [^\n]* encounter face role=yielder beat=yield_notice declared=(\w+) shown=(\w+) ', window)
    if not face or face[1] != face[2]:
        return f'the yield_notice beat did not show the face it declared ({face and face.groups()})'
    huff = re.search(rf'\[choreo\][^\n]*uuid={_lane_walker} [^\n]* beat=yield_giveup face=(\w+) line=sulk_huff declared=(\w+)', window)
    if not huff or huff[1] != huff[2]:
        return f'the give-up sulk did not show the face it declared ({huff and huff.groups()})'
    sulk = re.search(rf'uuid={_lane_walker} [^\n]* encounter sulk speech tick=(\d+)[^\n]* holdEnd=(\d+) spoken=(\w+)', window)
    if not sulk or sulk[3] != 'true':
        return f'the give-up sulk line was not spoken ({sulk and sulk.groups()})'
    if not int(end[1]) < int(sulk[1]) <= int(sulk[2]):
        return f'the sulk line was not spoken inside its hold after the give-up (t={sulk[1]}, holdEnd={sulk[2]}, end={end[1]})'
    if slide := _slides(trace, int(begin[1]), int(end[1]) + 40, _lane_walker):
        return slide
    gave = re.search(rf'\[move\] \w+ #\d+ uuid={_lane_walker} t=(\d+)[^\n]* gave up walk target=\(([-\d.]+),([-\d.]+),([-\d.]+)\) blocker=#\d+ at=', window)
    if not gave:
        return 'the walker never gave up the walk held up by the sleeper'
    gave_tick, errand = int(gave[1]), tuple(float(gave[g]) for g in (2, 3, 4))
    samples = []
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and row['u'] == _lane_sleeper:
            samples.append((row['t'], tuple(row['st']['p'])))
    stood = next((p for tick, p in reversed(samples) if tick <= gave_tick), None)
    if stood is None:
        return 'the sleeper left no trace position at the give-up'
    def moved_by(tick):
        return any(t <= tick and math.dist(p, stood) > 1.5 for t, p in samples)
    for m in re.finditer(rf'\[approach\] \w+ #\d+ uuid={_lane_walker} t=(\d+)[^\n]* surface route started [^\n]* destination=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)', window):
        tick = int(m[1])
        if tick <= gave_tick or moved_by(tick):
            continue
        if math.dist(tuple(float(m[g]) for g in (2, 3, 4)), errand) <= 2:
            return f'the walker was sent at the same target again at t={tick} while the sleeper stood where it held the lane up'
    for start, _ in stretches[1:]:
        if not moved_by(start):
            return f'the walker was held up by the sleeper again at t={start} while it stood where it held the lane up: {stretches}'
    return None


next(s for s in S if s.id == _lock_lane).smoke.update(verify=_lock_verify, verify_trace=True)


for _scene, _verify in (('street_corridor', _encounter_verify(50, 25, who=_corridor_who)),
                        ('street_dead_end', _encounter_verify(50, 25, min_len=5, who=_dead_who))):
    next(s for s in S if s.id == _scene).smoke.update(verify=_verify, verify_trace=True)

_doll_marisa = _uuid_text(_doll_carry, 'doll_marisa')
_doll_reimu = _uuid_text(_doll_carry, 'doll_reimu')
_KEEPSAKE_BEAT_RE = re.compile(
    r'\[keepsake\][^\n]*uuid=([0-9a-f-]{36}) t=(\d+)[^\n]*beat=(\w+) shown=(\w+) declared=(\w+)'
    r'(?: held_by=(\S+)(?: tier=(\w+))? held_face=(\w+))? line=(\w+) where=(\w+) dolls=(\d+)(.*)')


def _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face):
    """A beat shows what it declared, unless a logged live hold or course stage owns the shown face: then shown must be its own face."""
    if shown == declared:
        return None
    if not held_by:
        return f'face mismatch on {beat}: shown={shown} declared={declared}'
    if held_face != shown:
        return (f'face mismatch on {beat}: shown={shown} declared={declared} '
                f'held_by={held_by} tier={tier} held_face={held_face}')
    if tier is not None and tier not in ('SHORT', 'MEDIUM', 'LONG'):
        return f'{beat}: held_by={held_by} with an unknown tier={tier}'
    return None


def _keepsake_set_down_problem(tail):
    """The set-down beat must name the item that really left her mouth and the spot computed from its position."""
    m = re.search(r'\bitem=([0-9a-f-]{36}) at=\(([-0-9.]+),([-0-9.]+),([-0-9.]+)\) bed=[-0-9, ]+ '
                  r'bedDist=([0-9.]+) hollow=(true|false)', tail)
    if not m:
        return f'set_down does not name the released item, its landed position and the computed spot: {tail}'
    if m[6] != 'false':
        return f'set_down landed inside the nest hollow: {tail}'
    if float(m[5]) > 1.2:
        return f'set_down landed {m[5]} from the bed rim (>1.2): {tail}'
    return None


def _verify_doll_carry(window):
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    marisa_beats = []
    pick_tick = None
    set_tick = None
    for m in beats:
        u, t, beat, shown, declared, held_by, tier, held_face, line, where, dolls, tail = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return problem
        if int(dolls) != 1:
            return f'doll conservation broken on {beat}: dolls={dolls}'
        if u == _doll_reimu and beat in ('notice', 'pick_up', 'carry', 'set_down'):
            return f'low-drive Reimu unexpectedly performed keepsake beat={beat}'
        if u == _doll_marisa:
            marisa_beats.append((int(t), beat, where, tail))
            if beat == 'pick_up' and pick_tick is None:
                pick_tick = int(t)
            if beat == 'set_down' and set_tick is None:
                set_tick = int(t)
                if where != 'bed':
                    return f'set_down where={where}, expected bed'
                problem = _keepsake_set_down_problem(tail)
                if problem:
                    return problem
    names = [b[1] for b in marisa_beats]
    want = ['notice', 'pick_up', 'carry', 'set_down']
    idx = 0
    for n in names:
        if idx < len(want) and n == want[idx]:
            idx += 1
    if idx < len(want):
        return f'expected beat order {want}, saw {names}'
    if pick_tick is not None and set_tick is not None and set_tick - pick_tick > 600:
        return f'carry took {set_tick - pick_tick} ticks (>600)'


_smoke(_doll_carry, 1200, [
    rf'\[keepsake\][^\n]*uuid={_doll_marisa} [^\n]*beat=notice shown=sparkle declared=sparkle line=toy where=ground dolls=1\b',
    rf'\[keepsake\][^\n]*uuid={_doll_marisa} [^\n]*beat=pick_up shown=happy declared=happy line=mine where=mouth dolls=1\b',
    rf'\[keepsake\][^\n]*uuid={_doll_marisa} [^\n]*beat=carry shown=\w+ declared=\w+ line=none where=mouth dolls=1\b',
    rf'\[keepsake\][^\n]*uuid={_doll_marisa} [^\n]*beat=set_down shown=satisfied declared=satisfied line=thing_san where=bed dolls=1\b',
])
next(s for s in S if s.id == _doll_carry).smoke.update(verify=_verify_doll_carry)

_int_fright = _uuid_text(_doll_interrupt, 'int_fright')
_int_hungry = _uuid_text(_doll_interrupt, 'int_hungry')


def _verify_doll_interrupt(window):
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    for m in beats:
        _, _, beat, shown, declared, held_by, tier, held_face, _, _, dolls, tail = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return problem
        if int(dolls) != 2:
            return f'doll conservation broken on {beat}: dolls={dolls}'
        if beat == 'set_down':
            problem = _keepsake_set_down_problem(tail)
            if problem:
                return problem
    hungry_picks = [int(m[2]) for m in beats if m[1] == _int_hungry and m[3] == 'pick_up']
    if len(hungry_picks) < 2:
        return f'expected interrupted carrier to re-fetch after recovery (>=2 pick_up), saw {len(hungry_picks)}'
    hungry_sets = [int(m[2]) for m in beats if m[1] == _int_hungry and m[3] == 'set_down']
    if not hungry_sets:
        return 'interrupted carrier never completed set_down after re-fetching'


_smoke(_doll_interrupt, 1600, [
    rf'\[keepsake\][^\n]*uuid={_int_fright} [^\n]*beat=pick_up ',
    rf'\[body\][^\n]*uuid={_int_fright} [^\n]*mouth drop item=mcyukkuri:marisa_doll count=1 purpose=KEEPSAKE ',
    rf'\[keepsake\][^\n]*uuid={_int_hungry} [^\n]*beat=pick_up ',
    rf'\[body\][^\n]*uuid={_int_hungry} [^\n]*mouth drop item=mcyukkuri:marisa_doll count=1 purpose=KEEPSAKE ',
    rf'\[keepsake\][^\n]*uuid={_int_hungry} [^\n]*beat=set_down ',
])
next(s for s in S if s.id == _doll_interrupt).smoke.update(
    verify=_verify_doll_interrupt,
    commands=[
        (90, f'execute as {sel("int_hungry")} run yukkuri status set @s needs.nutrition 35'),
        (100, yk('remilia', 0, 9, 'tmp_pred', yaw=180, nutrition=100, energy=100)),
        (115, f'data merge entity {sel("tmp_pred")} {{NoAI:1b}}'),
        (116, f'tp {sel("tmp_pred")} ~ ~50 ~'),
    ])

_comfort_reimu = _uuid_text(_doll_comfort, 'comfort_reimu')
_bold_reimu = _uuid_text(_doll_comfort, 'bold_reimu')
_paired_marisa = _uuid_text(_doll_comfort, 'paired_marisa')
_paired_peer = _uuid_text(_doll_comfort, 'paired_peer')


def _verify_doll_comfort_sleep(window):
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    # Verify that no actor ever starts a doll nuzzle (beat=ask) when its own companion choice had eligible > 0.
    choice_re = re.compile(r'\[daily\][^\n]*uuid=([0-9a-f-]{36}) t=(\d+)[^\n]*companion choice [^\n]*checked=\[([^\]]*)\][^\n]*eligible=(\d+)\b')
    eligible_at = {}
    for cm in choice_re.finditer(window):
        eligible_at[(cm[1], int(cm[2]))] = (cm[3].strip(), int(cm[4]))
    reimu_beats = []
    done_ok = False
    sleep_ok = False
    for m in beats:
        u, t, beat, shown, declared, held_by, tier, held_face, line, where, dolls, tail = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return problem
        if int(dolls) != 2:
            return f'doll conservation broken on {beat}: dolls={dolls}'
        if u == _bold_reimu:
            return f'bold unattached Reimu unexpectedly performed keepsake beat={beat}'
        if beat == 'ask':
            # The ask must carry the actor's own same-tick peer scan: a missing or empty scan is no evidence that no peer was eligible.
            scan = eligible_at.get((u, int(t)))
            if scan is None:
                return f'actor {u} doll ask at t={t} has no same-tick companion choice line'
            checked, eligible = scan
            if not checked:
                return f'actor {u} doll ask at t={t} has an empty checked= peer scan'
            if eligible > 0:
                return f'actor {u} started doll nuzzle while eligible peer count={eligible}'
        if u == _paired_marisa and beat in ('ask', 'rub', 'done'):
            return f'paired Marisa nuzzled doll instead of eligible peer (beat={beat})'
        if u == _comfort_reimu:
            reimu_beats.append(beat)
            if beat == 'done':
                cm = re.search(r'\bcomfort=(\d+)->(\d+)\b', tail)
                if not cm:
                    return f'done beat missing comfort before->after: {tail}'
                before, after = int(cm[1]), int(cm[2])
                if before - after != min(before, 15):
                    return f'doll nuzzle comfort was {before}->{after}, expected -15'
                done_ok = True
            if beat == 'sleep':
                sm = re.search(r'\bdollDist=([0-9.]+) yawDiff=([0-9.]+)\b', tail)
                if not sm:
                    return f'sleep beat missing dollDist/yawDiff: {tail}'
                if float(sm[1]) > 1.5:
                    return f'sleep dollDist={sm[1]} (>1.5)'
                if float(sm[2]) > 15.0:
                    return f'sleep yawDiff={sm[2]} (>15 deg; not facing doll)'
                sleep_ok = True
    want = ['ask', 'rub', 'done', 'sleep']
    idx = 0
    for n in reimu_beats:
        if idx < len(want) and n == want[idx]:
            idx += 1
    if idx < len(want):
        return f'expected comfort_reimu beat order {want}, saw {reimu_beats}'
    if not done_ok or not sleep_ok:
        return f'missing validated done ({done_ok}) or sleep ({sleep_ok}) beat'


_smoke(_doll_comfort, 1600, [
    rf'\[keepsake\][^\n]*uuid={_comfort_reimu} [^\n]*beat=ask shown=open_smile declared=open_smile line=want_nuzzle where=ground dolls=2\b',
    rf'\[keepsake\][^\n]*uuid={_comfort_reimu} [^\n]*beat=rub shown=closed_smile declared=closed_smile line=nuzzle where=ground dolls=2\b',
    rf'\[keepsake\][^\n]*uuid={_comfort_reimu} [^\n]*beat=done shown=satisfied declared=satisfied line=nuzzle_done where=ground dolls=2 comfort=\d+->\d+\b',
    rf'\[keepsake\][^\n]*uuid={_comfort_reimu} [^\n]*beat=sleep shown=sleep declared=sleep line=sleep where=(?:ground|bed) dolls=2\b',
    rf'\[daily\][^\n]*uuid={_paired_marisa} [^\n]*session reserved kind=NUZZLE companion={_paired_peer}\b',
])
next(s for s in S if s.id == _doll_comfort).smoke.update(verify=_verify_doll_comfort_sleep)

_talk_reimu = _uuid_text(_doll_talk, 'talk_reimu')
_clever_marisa = _uuid_text(_doll_talk, 'clever_marisa')
_dull_marisa = _uuid_text(_doll_talk, 'dull_marisa')


def _verify_doll_talk(window):
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    by_actor = {_talk_reimu: [], _dull_marisa: []}
    holds = {}
    for m in beats:
        u, t, beat, shown, declared, held_by, tier, held_face, line, where, dolls, tail = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return problem
        if int(dolls) != 3:
            return f'doll conservation broken on {beat}: dolls={dolls}'
        if u == _clever_marisa and beat in ('address', 'offer', 'eat_on'):
            return f'clever Marisa unexpectedly reacted to other-kind doll: beat={beat}'
        if u in by_actor:
            by_actor[u].append(beat)
            if beat == 'offer':
                om = re.search(r'\bhold=(\d+) food=(\d+)\b', tail)
                if not om:
                    return f'offer beat missing hold/food: {tail}'
                if int(om[2]) != 1:
                    return f'bite count not conserved on offer: food={om[2]}'
                holds[u] = int(om[1])
            if beat == 'eat_on':
                em = re.search(r'\bfood=(\d+)\b', tail)
                if not em or int(em[1]) != 1:
                    return f'bite count not conserved on eat_on: {tail}'
    want = ['address', 'offer', 'eat_on']
    for label, u in (('talk_reimu', _talk_reimu), ('dull_marisa', _dull_marisa)):
        idx = 0
        for n in by_actor[u]:
            if idx < len(want) and n == want[idx]:
                idx += 1
        if idx < len(want):
            return f'{label} expected beat order {want}, saw {by_actor[u]}'
    if holds.get(_dull_marisa, 999) >= holds.get(_talk_reimu, 0):
        return f'expected lower selfControl hold < higher selfControl hold, saw dull={holds.get(_dull_marisa)} clever={holds.get(_talk_reimu)}'


_smoke(_doll_talk, 1200, [
    rf'\[keepsake\][^\n]*uuid={_talk_reimu} [^\n]*beat=address shown=happy declared=happy line=doll_talk where=ground dolls=3\b',
    rf'\[keepsake\][^\n]*uuid={_talk_reimu} [^\n]*beat=offer shown=happy declared=happy line=share where=mouth dolls=3 hold=\d+ food=1\b',
    rf'\[keepsake\][^\n]*uuid={_talk_reimu} [^\n]*beat=eat_on shown=satisfied declared=satisfied line=none where=mouth dolls=3 food=1\b',
    rf'\[keepsake\][^\n]*uuid={_dull_marisa} [^\n]*beat=address shown=happy declared=happy line=doll_talk where=ground dolls=3\b',
    rf'\[keepsake\][^\n]*uuid={_dull_marisa} [^\n]*beat=offer shown=happy declared=happy line=share where=mouth dolls=3 hold=\d+ food=1\b',
    rf'\[keepsake\][^\n]*uuid={_dull_marisa} [^\n]*beat=eat_on shown=satisfied declared=satisfied line=none where=mouth dolls=3 food=1\b',
])
next(s for s in S if s.id == _doll_talk).smoke.update(verify=_verify_doll_talk)

_friend_reimu = _uuid_text(_friend_scene, 'friend_reimu')
_friend_marisa = _uuid_text(_friend_scene, 'friend_marisa')


def _verify_doll_comfort_friend(window):
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    rubs, dones = [], []
    for m in beats:
        u, t, beat, shown, declared, held_by, tier, held_face, line, where, dolls, tail = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return problem
        if int(dolls) != 1:
            return f'doll conservation broken on {beat}: dolls={dolls}'
        if u != _friend_reimu:
            continue
        if beat == 'rub':
            rubs.append(int(t))
        elif beat == 'done':
            dones.append(int(t))
    hand = list(re.finditer(r'\[daily\][^\n]*uuid=' + _friend_reimu + r' t=(\d+)[^\n]*'
                            r'doll comfort interrupted friend=' + _friend_marisa
                            + r' contactTicks=(\d+) affectionNeed=\d+ handedOver=true\b', window))
    if len(hand) != 1:
        return f'expected one doll-comfort handover to the friend, saw {len(hand)}'
    at, contacts = int(hand[0][1]), int(hand[0][2])
    if contacts <= 0:
        return 'the body had not begun rubbing the doll when the friend appeared'
    if contacts >= 60:
        return f'the doll comfort ran to completion before the friend was noticed: contactTicks={contacts}'
    if not any(t < at for t in rubs):
        return f'the doll was never rubbed before the friend appeared at t={at}'
    if any(t < at for t in dones):
        return f'the doll comfort paid out before the friend appeared (done at {min(dones)} < {at})'
    stop = re.search(r'\[daily\][^\n]*uuid=' + _friend_reimu + r' [^\n]*session stopped kind=NUZZLE companion='
                     r'(?!' + _friend_marisa + r')\S+ elapsed=\d+ contactTicks=(\d+)\b', window)
    if not stop:
        return 'the interrupted doll session was never stopped in the log'
    if stop[1] != str(contacts):
        return f'the stopped doll session reports contactTicks={stop[1]}, the handover {contacts}'
    if not re.search(r'\[daily\][^\n]*uuid=' + _friend_reimu + r' [^\n]*greet face turn to='
                     + _friend_marisa + r'\b', window):
        return 'the body never turned to the friend'


_smoke(_friend_scene, 540, [
    rf'\[keepsake\][^\n]*uuid={_friend_reimu} [^\n]*beat=ask shown=open_smile declared=open_smile line=want_nuzzle where=ground dolls=1\b',
    rf'\[keepsake\][^\n]*uuid={_friend_reimu} [^\n]*beat=rub shown=closed_smile declared=closed_smile line=nuzzle where=ground dolls=1\b',
    rf'\[daily\][^\n]*uuid={_friend_reimu} [^\n]*doll comfort interrupted friend={_friend_marisa} contactTicks=\d+ affectionNeed=\d+ handedOver=true\b',
    rf'\[daily\][^\n]*uuid={_friend_reimu} [^\n]*greet face turn to={_friend_marisa} ',
])
next(s for s in S if s.id == _friend_scene).smoke.update(
    verify=_verify_doll_comfort_friend,
    commands=[
        (45, block(3, 0, 4, 'minecraft:oak_fence_gate[facing=west,open=true]')),
    ])

_giveup_kid = _uuid_text(_giveup, 'giveup_kid')


def _verify_doll_talk_giveup(window):
    giveups = list(re.finditer(r'\[keepsake\][^\n]*uuid=' + _giveup_kid
                               + r' t=(\d+)[^\n]*talk gave up unaddressed elapsed=(\d+) gap=(\d+)\b', window))
    if len(giveups) != 1:
        return f'expected exactly one unaddressed talk give-up, saw {len(giveups)}'
    elapsed, gap = int(giveups[0][2]), int(giveups[0][3])
    if not 1 <= elapsed < 15:
        return f'the talk was given up at elapsed={elapsed}, not while it was still turning to the doll'
    if gap != 720:
        return f'the repeat gap after the unaddressed exit was {gap}, expected 720 at attachment 80'
    if re.search(r'\[keepsake\][^\n]*uuid=' + _giveup_kid + r' [^\n]*beat=address\b', window):
        return 'the child addressed the doll again inside the window'


_smoke(_giveup, 200, [
    rf'\[keepsake\][^\n]*uuid={_giveup_kid} [^\n]*talk gave up unaddressed elapsed=\d+ gap=720\b',
])
next(s for s in S if s.id == _giveup).smoke.update(
    verify=_verify_doll_talk_giveup,
    commands=[
        (6, f'execute as {sel("giveup_kid")} run yukkuri status set @s needs.nutrition 25'),
    ])
_snatch_thief_a = _uuid_text(_doll_snatch, 'snatch_thief_a')
_snatch_victim_a = _uuid_text(_doll_snatch, 'snatch_victim_a')
_snatch_thief_b = _uuid_text(_doll_snatch, 'snatch_thief_b')
_snatch_victim_b = _uuid_text(_doll_snatch, 'snatch_victim_b')


def _verify_doll_snatch(window):
    """Both lanes: the taker claims and takes the doll it may not take in its mouth, and the robbed body shows the reaction of its
    own temper and answers by its own nature. The bold child's puff is answered by a puff back that interrupts the carry, so that
    taker puts the doll down where it stands (and may take it home later, its own business); the timid child flees and its taker
    carries the doll home to the nest. One claim and one grab each, each face as declared or as the logged hold that refused it, doll count 2 throughout, and the pair's own
    numbers start the bout at the first scan after the start hold."""
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    releases = [int(m[1]) for m in re.finditer(r'\[scenario\][^\n]*\bt=(\d+)[^\n]*\bhold released\b', window)
                if any(u in m[0] for u in (_snatch_thief_a, _snatch_victim_a, _snatch_thief_b, _snatch_victim_b))]
    if not releases:
        return 'no hold release line to time the claim'
    released = max(releases)
    for m in beats:
        u, _, beat, shown, declared, held_by, tier, held_face, _, _, dolls, _ = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return f'{u}: {problem}'
        if int(dolls) != 2:
            return f'doll conservation broken on {beat}: dolls={dolls}'

    def lane(thief, victim, robbed_face, decision, answer_face, answer_line, homeward):
        # (tick, beat, face, line, where, tail); every beat once, so a second grab would show here too.
        mine = [(int(m[2]), m[3], m[4], m[9], m[10], m[12]) for m in beats if m[1] == thief]
        theirs = [(int(m[2]), m[3], m[4], m[9], m[10], m[12]) for m in beats if m[1] == victim]
        for name in ('claim', 'grab'):
            rows = [r for r in mine if r[1] == name]
            if len(rows) != 1:
                return f'{thief}: expected one {name} beat, saw {[r[1] for r in mine]}'
        for name in ('robbed', 'answer'):
            rows = [r for r in theirs if r[1] == name]
            if len(rows) != 1:
                return f'{victim}: expected one {name} beat, saw {[r[1] for r in theirs]}'
        claim, grab = (next(r for r in mine if r[1] == n) for n in ('claim', 'grab'))
        robbed, answer = (next(r for r in theirs if r[1] == n) for n in ('robbed', 'answer'))
        if not claim[0] < grab[0] <= robbed[0] < answer[0]:
            return f'{thief}/{victim}: beat order {[r[0] for r in (claim, grab, robbed, answer)]} is not claim/grab/answer'
        if not 0 <= claim[0] - released <= 200:
            return f'{thief}: claimed {claim[0] - released} ticks after the hold release, expected within 200'
        if grab[0] - claim[0] > 240:
            return f'{thief}: the bout took {grab[0] - claim[0]} ticks from claim to grab (>240)'
        if answer[0] - robbed[0] > 40:
            return f'{victim}: answer came {answer[0] - robbed[0]} ticks after the robbed beat (the 30-tick delay was missed)'
        if (claim[2], claim[3], claim[4]) != ('smug', 'doll_snatch_claim', 'peer') or f'victim={victim}' not in claim[5]:
            return f'{thief}: claim is not the smug doll_snatch_claim over {victim}: {claim}'
        if grab[2] != 'smug' or grab[3] != 'none' or grab[4] != 'ground' or f'victim={victim}' not in grab[5]:
            return f'{thief}: grab is not the silent pickup of the doll beside {victim}: {grab}'
        if (robbed[2], robbed[3], robbed[4]) != (robbed_face, 'doll_stolen', 'ground'):
            return f'{victim}: robbed is not the {robbed_face} doll_stolen reaction: {robbed}'
        if (answer[2], answer[3], answer[4]) != (answer_face, answer_line, 'peer') or f'decision={decision}' not in answer[5]:
            return f'{victim}: answer is not {decision} seen as {answer_face}/{answer_line}: {answer}'
        if not re.search(rf'\[keepsake\][^\n]*uuid={victim} [^\n]*answer speech decision={decision} event={answer_line} spoken=true\b', window):
            return f'{victim}: answer line {answer_line} was not spoken for decision={decision}'
        if homeward:
            for name in ('leave', 'set_down'):
                rows = [r for r in mine if r[1] == name]
                if len(rows) != 1:
                    return f'{thief}: expected one {name} beat, saw {[r[1] for r in mine]}'
            leave, down = (next(r for r in mine if r[1] == n) for n in ('leave', 'set_down'))
            if not answer[0] < leave[0] < down[0]:
                return f'{thief}: beat order {[r[0] for r in (answer, leave, down)]} is not answer/leave/set_down'
            if not 40 <= leave[0] - grab[0] <= 50:
                return f'{thief}: leave came {leave[0] - grab[0]} ticks after the grab, expected the 45-tick delay'
            if down[0] - grab[0] > 600:
                return f'{thief}: carry took {down[0] - grab[0]} ticks (>600)'
            if (leave[2], leave[3], leave[4]) != ('happy', 'none', 'mouth'):
                return f'{thief}: leave does not show the doll still carried: {leave}'
            if (down[2], down[3], down[4]) != ('satisfied', 'thing_san', 'bed'):
                return f'{thief}: set_down is not the doll left at its own nest: {down}'
            return _keepsake_set_down_problem(down[5])
        if any(r[1] == 'leave' for r in mine):
            return f'{thief}: kept carrying the doll home after the puff that interrupted it'
        drops = [int(m[1]) for m in re.finditer(
            rf'\[body\][^\n]*uuid={thief} t=(\d+)[^\n]*mouth drop item=mcyukkuri:reimu_doll count=1 purpose=KEEPSAKE ', window)]
        prompt = [d for d in drops if 0 < d - answer[0] <= 40]
        if len(prompt) != 1:
            return f'{thief}: expected one interrupted doll drop within 40 ticks of the answer, saw {len(prompt)} of {len(drops)}'
        # What it does with the dropped doll later (notices it, takes it home, or loses its grip again when it gets too hungry to
        # carry) is its own business: only the take-home beats and their order are checked.
        order = {name: i for i, name in enumerate(('notice', 'pick_up', 'carry', 'set_down'))}
        late = [r for r in mine if r[1] in order]
        names = [r[1] for r in late]
        if any(order[b] < order[a] for a, b in zip(names, names[1:])):
            return f'{thief}: the take-home beats after the drop are out of order: {names}'
        if late and late[0][0] <= prompt[0]:
            return f'{thief}: a {late[0][1]} beat came before the drop'
        down = next((r for r in late if r[1] == 'set_down'), None)
        if down is not None:
            if (down[2], down[3], down[4]) != ('satisfied', 'thing_san', 'bed'):
                return f'{thief}: set_down is not the doll left at its own nest: {down}'
            return _keepsake_set_down_problem(down[5])

    return (lane(_snatch_thief_a, _snatch_victim_a, 'angry', 'puff_back', 'cheek_puff', 'doll_snatch_defy', homeward=False)
            or lane(_snatch_thief_b, _snatch_victim_b, 'teary', 'flee', 'surprise', 'doll_snatch_flee', homeward=True))


_smoke(_doll_snatch, 6400, [
    r'\[social\][^\n]*bout reserved kind=BULLY peer=',
    rf'\[keepsake\][^\n]*uuid={_snatch_thief_a} [^\n]*beat=claim shown=smug declared=smug line=doll_snatch_claim where=peer ',
    rf'\[keepsake\][^\n]*uuid={_snatch_thief_a} [^\n]*beat=grab shown=smug declared=smug line=none where=ground dolls=2 ',
    rf'\[social\][^\n]*uuid={_snatch_victim_a} [^\n]*aftermath what=doll taker={_snatch_thief_a} face=angry ',
    rf'\[keepsake\][^\n]*uuid={_snatch_victim_a} [^\n]*beat=robbed shown=angry declared=angry line=doll_stolen where=ground ',
    rf'\[keepsake\][^\n]*uuid={_snatch_victim_a} [^\n]*beat=answer shown=cheek_puff declared=cheek_puff line=doll_snatch_defy where=peer ',
    rf'\[keepsake\][^\n]*uuid={_snatch_victim_a} [^\n]*answer speech decision=puff_back event=doll_snatch_defy spoken=true\b',
    rf'\[body\][^\n]*uuid={_snatch_thief_a} [^\n]*mouth drop item=mcyukkuri:reimu_doll count=1 purpose=KEEPSAKE ',
    rf'\[social\][^\n]*uuid={_snatch_victim_b} [^\n]*aftermath what=doll taker={_snatch_thief_b} face=teary ',
    rf'\[keepsake\][^\n]*uuid={_snatch_victim_b} [^\n]*beat=robbed shown=teary declared=teary line=doll_stolen where=ground ',
    rf'\[keepsake\][^\n]*uuid={_snatch_victim_b} [^\n]*beat=answer shown=surprise declared=surprise line=doll_snatch_flee where=peer dolls=2 decision=flee',
    rf'\[keepsake\][^\n]*uuid={_snatch_victim_b} [^\n]*answer speech decision=flee event=doll_snatch_flee spoken=true\b',
    rf'\[keepsake\][^\n]*uuid={_snatch_thief_b} [^\n]*beat=set_down shown=satisfied declared=satisfied line=thing_san where=bed ',
], forbid=[r'\[social\][^\n]*bully strike peer=', r'\[social\][^\n]*self defense strike '])
next(s for s in S if s.id == _doll_snatch).smoke.update(verify=_verify_doll_snatch)

_take_sleep_a = _uuid_text(_doll_take, 'take_sleep_a')
_take_sleep_b = _uuid_text(_doll_take, 'take_sleep_b')
_take_owner = _uuid_text(_doll_take, 'take_owner')
_take_stray = _uuid_text(_doll_take, 'take_stray')
_TOOL_OPERATOR = '2d4c7192-2a6d-4da4-9f94-c82721b9de87'


def _verify_doll_take(window):
    """Each of the four stations answers once: the two sleepers find the doll gone when they wake (one sad, one puzzled, neither
    blaming the player), the owner only tears up, the stranger names the theft and loses regard."""
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    for m in beats:
        u, _, beat, shown, declared, held_by, tier, held_face, _, _, _, _ = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return f'{u}: {problem}'
    seen = {}
    for m in beats:
        seen.setdefault(m[1], []).append(m)
    expect = {
        _take_sleep_a: [('sleep', 'sleep', 'sleep', 'ground', 4), ('pickup_missed', 'sad', 'none', 'ground', 0)],
        _take_sleep_b: [('sleep', 'sleep', 'sleep', 'ground', 4), ('pickup_missed', 'confused', 'none', 'ground', 0)],
        _take_owner: [('pickup_owner', 'teary', 'none', 'ground', 1)],
        _take_stray: [('pickup_taken', 'angry', 'treasure_stolen', 'ground', 0)],
    }
    for who, want in expect.items():
        got = [(m[3], m[4], m[9], m[10], int(m[11])) for m in seen.get(who, ())]
        if got != want:
            return f'{who}: beats {got}, expected {want}'
        for m in seen[who]:
            if m[3] == 'pickup_missed' and not re.search(r'\bat=\([-0-9.]+,[-0-9.]+,[-0-9.]+\)', m[12]):
                return f'{who}: pickup_missed does not name where it looked: {m[12]}'
            if m[3].startswith('pickup_') and m[3] != 'pickup_missed' and f'player={_TOOL_OPERATOR}' not in m[12]:
                return f'{who}: {m[3]} does not name the player who took it: {m[12]}'


_smoke(_doll_take, 1200, [
    rf'\[keepsake\][^\n]*uuid={_take_sleep_a} [^\n]*beat=sleep shown=sleep declared=sleep line=sleep where=ground ',
    rf'\[keepsake\][^\n]*uuid={_take_sleep_b} [^\n]*beat=sleep shown=sleep declared=sleep line=sleep where=ground ',
    rf'\[keepsake\][^\n]*uuid={_take_sleep_a} [^\n]*beat=pickup_missed shown=sad declared=sad line=none where=ground dolls=0 ',
    rf'\[keepsake\][^\n]*uuid={_take_sleep_b} [^\n]*beat=pickup_missed shown=confused declared=confused line=none where=ground dolls=0 ',
    rf'\[keepsake\][^\n]*uuid={_take_owner} [^\n]*beat=pickup_owner shown=teary declared=teary line=none where=ground dolls=1 ',
    rf'\[keepsake\][^\n]*uuid={_take_stray} [^\n]*beat=pickup_taken shown=angry declared=angry line=treasure_stolen where=ground dolls=0 ',
], forbid=[rf'\[keepsake\][^\n]*uuid={_take_owner} [^\n]*line=treasure_stolen'])
_take_commands = [(100, 'execute positioned ~2.6 ~ ~6.5 run yukkuri scenario tool_operator enter'),
                  *((101, f'execute as @p[name=MuseumOperator,distance=..48] run {line}') for line in own('take_owner'))]
# The operator moves onto the doll it is about to click. Each take is dropped again at once 34 blocks off the row, inside the
# field but outside every sleeper's 32-block count, so the taken doll never lies beside a sleeper and the sleepers still find
# nothing beside them when they wake.
for _i, _name in enumerate(('take_doll_a', 'take_doll_b', 'take_doll_c', 'take_doll_d')):
    _base = 140 + 10 * _i
    _take_commands += [(_base, f'execute at {sel(_name)} run yukkuri scenario tool_operator move'),
                       (_base + 1, f'yukkuri scenario tool_operator pick_up {sel(_name)}'),
                       (_base + 2, f'execute at {sel("take_sleep_a")} positioned ~34 ~ ~34 run yukkuri scenario tool_operator move'),
                       (_base + 3, 'yukkuri scenario tool_operator drop')]
_take_commands += [(240, 'yukkuri scenario tool_operator leave')]
next(s for s in S if s.id == _doll_take).smoke.update(verify=_verify_doll_take, commands=_take_commands)

# The two regressions under the snatch scene use the same bout and strike without a doll or a held treasure.
_bully_thief = _uuid_text('bully_strike', 'bully_thief')
_bully_victim = _uuid_text('bully_strike', 'bully_victim')


def _verify_bully_strike(window):
    """One ordinary bully bout is the point; the window outlasts the bout cooldown, so the pinned pair may well do it again."""
    reserved = re.findall(r'\[social\][^\n]*bout reserved kind=BULLY peer=\S+', window)
    if not reserved:
        return 'no BULLY bout was reserved'
    if not re.search(rf'\[social\][^\n]*uuid={_bully_thief} [^\n]*bully strike peer={_bully_victim} accepted=true ', window):
        return 'the bout did not land its strike'
_smoke('bully_strike', 6000, [rf'\[social\][^\n]*bout reserved kind=BULLY peer={_bully_victim}',
                              rf'\[social\][^\n]*uuid={_bully_thief} [^\n]*bully strike peer={_bully_victim} '])
next(s for s in S if s.id == 'bully_strike').smoke.update(verify=_verify_bully_strike)

_treasure_thief = _uuid_text('treasure_bully', 'treasure_thief')
_treasure_victim = _uuid_text('treasure_bully', 'treasure_victim')
_smoke('treasure_bully', 6000, [
    rf'\[social\][^\n]*bout reserved kind=BULLY peer={_treasure_victim}',
    rf'\[social\][^\n]*uuid={_treasure_thief} [^\n]*bully strike peer={_treasure_victim} ',
    rf'\[treasure\][^\n]*uuid={_treasure_thief} [^\n]*snatched victim={_treasure_victim} item=minecraft:diamond .*victimHeld=0',
    rf'\[social\][^\n]*uuid={_treasure_victim} [^\n]*aftermath what=treasure taker={_treasure_thief} face=teary ',
])

_park_waiter_log = _uuid_text(_park_aid, 'park_waiter')
_park_local_log = _uuid_text(_park_aid, 'park_local')
_park_offer = rf'\[kindness\][^\n]*uuid={_park_local_log} [^\n]*carton offered peer={_park_waiter_log} shelter=(.+) clip=look_around\b'
_park_bite = rf'\[kindness\][^\n]*uuid={_park_local_log} [^\n]*bite handed peer={_park_waiter_log} clip=offer_bite\b'
_park_swallow = rf'\[meal\][^\n]*uuid={_park_waiter_log} [^\n]*swallowed item=minecraft:apple count=1 [^\n]*feeder={_park_local_log}\b'


def _verify_park_aid(window, trace):
    stage = re.search(rf'\[scenario\][^\n]*uuid={_park_waiter_log} [^\n]*rolled=\{{attachment=(\d+)\}}', window)
    if not stage:
        return 'Missing seeded waiter attachment'
    staying = int(stage[1]) >= 75
    bite, swallow, offer = (list(re.finditer(pattern, window)) for pattern in (_park_bite, _park_swallow, _park_offer))
    if not (len(bite) == len(swallow) == len(offer) == 1 and bite[0].start() < swallow[0].start() < offer[0].start()):
        return 'Expected one linked bite, swallow and carton gesture in order'
    core = re.escape(offer[0][1])
    outcome, clip = ('stay', 'droop') if staying else ('follow', 'look_around')
    decisions = list(re.finditer(rf'\[kindness\][^\n]*uuid={_park_waiter_log} t=(\d+)[^\n]*carton decision=(\w+) local=(\S+) shelter=(.+) clip=(\w+)', window))
    if len(decisions) != 1:
        return f'Expected one waiter decision for the carton gesture, saw {len(decisions)}'
    decision = decisions[0]
    if decision.start() <= offer[0].start() or decision.groups()[1:] != (outcome, _park_local_log, offer[0][1], clip):
        return f'The waiter did not choose {outcome} after the gesture to the same carton'
    after = window[decision.end():]
    settled = re.search(rf'\[habitat\][^\n]*uuid={_park_waiter_log} [^\n]*found settled shelter={core} kind=CARDBOARD occupants=1\b', after)
    ended = re.search(rf'\[pet\][^\n]*uuid={_park_waiter_log} [^\n]*vigil ended in invited cover shelter={core} local={_park_local_log} former={_uuid_text(_park_aid, "former_owner")}\b', after)
    if not staying and not (settled and ended and settled.start() < ended.start()):
        return 'The waiter did not settle as the claimant at the offered core before ending the vigil'
    if staying and (settled or ended):
        return 'The waiter declined and then settled at the invited core'
    frames = [json.loads(line.split('[trace] ', 1)[1]) for line in trace]
    offered_at = int(re.search(r'\bt=(\d+)', offer[0][0])[1])
    answered_at = int(decision[1])
    # A goal stays active between trace deltas, including when it started before the look-back.
    goal_changes = [(row['t'], row['st']['goals']) for row in frames if row['k'] == 'st'
                    and row['u'] == _park_waiter_log and row['t'] < answered_at and 'goals' in row['st']]
    goal_changes.append((answered_at, ()))
    if any(max(start, offered_at) < end and 'ForageGoal@4' in goals
           for (start, goals), (end, _) in pairwise(goal_changes)):
        return 'Ordinary foraging took MOVE before the waiter answered the live gesture'
    clips = [row['st']['animation'] for row in frames if row['k'] == 'st' and row['u'] == _park_waiter_log
             and row['t'] >= answered_at and 'animation' in row['st']]
    if staying:
        if 'droop' not in clips or not any(clip != 'droop' for clip in clips[clips.index('droop') + 1:]):
            return 'The waiter did not show the refusal and resume ordinary activity'


def _park_aid_vigil_readout(readout):
    values = re.findall(r'has the following entity data: (.+)', readout)
    if len(values) < 4 or values[1].replace(' ', '') != _park_former:
        return 'The surviving waiter needs its original former owner'
    attachment = int(values[2]) + int(values[3])
    staying = attachment >= 75
    if values[0] != ('1b' if staying else '0b'):
        return 'The saved vigil must reflect the staged waiter’s choice and actual settlement'
    if staying:
        if len(values) != 5 or not any('Dimension: "minecraft:overworld"' in entry
                                      and re.search(rf'\bPos: {_park_carton_core}L\b', entry)
                                      for entry in re.findall(r'\{[^{}]+\}', values[4])):
            return 'The active vigil must save the declined carton’s dimension and core'
    elif len(values) != 4:
        return 'A completed vigil must clear its carton answers'


_smoke(_park_aid, 500, [_park_bite, _park_swallow, _park_offer], readout=[
    f'data get entity {_park_waiter} YukkuriPet.Waits', f'data get entity {_park_waiter} YukkuriPet.FormerOwner',
    f'data get entity {_park_waiter} YukkuriLife.Personality.Innate[3]',
    f'data get entity {_park_waiter} YukkuriLife.Personality.Experience[3]',
    f'data get entity {_park_waiter} YukkuriPet.VigilAnswers', 'data get block ~10 ~ ~-4 Residents',
])
next(s for s in S if s.id == _park_aid).smoke.update(verify=_verify_park_aid, verify_trace=True,
                                                    verify_readout=_park_aid_vigil_readout, gating_checks=['END'])

next(s for s in S if s.id == 'hurt').smoke['verify'] = _two_lickers
_smoke('birth_nest_safe', 3000, [r'\[family\][^\n]* birth landed [^\n]*outcome=CUSHION\b'])
_smoke('birth_nest_stem', 3000, [r'\[family\][^\n]* stem site facing ', r'\[family\][^\n]* birth landed [^\n]*outcome=CUSHION\b'])


def _alice_share_home(window):
    homes = re.findall(r'\[habitat\] (?:alice|reimu) [^\n]* home chosen [^\n]*?home=(BlockPos\{[^}]*\})', window)
    if len(homes) < 2:
        return f'alice and reimu did not both choose a home: {homes}'
    return None if len(set(homes)) == 1 else f'the partners chose different homes: {homes}'


_smoke('alice_chores', 600, [r'\[habitat\] alice [^\n]* home chosen', r'\[habitat\] reimu [^\n]* home chosen'])
next(s for s in S if s.id == 'alice_chores').smoke['verify'] = _alice_share_home
# A hunting body keeps its hunt face: no teary face is ever shown for a hunt beat, and the chase beat shows the stare.
_HUNT_NOT_TEARY = [r'\[predator\][^\n]* beat=\w+ face=(?:teary\w*|surprise|happy) ']
_smoke('sacrifice', 1200, [r'\[grief\][^\n]* protection diverted [^\n]*response=protect_child_worry',
                           r'\[predator\][^\n]* beat=predator_chase face=(?:angry_stare|down_angry|angry) line=none'],
       forbid=_HUNT_NOT_TEARY)
_smoke('pred_range', 1800, [r'\[predator\] flandre [^\n]* prey selected [^\n]* distance=(?:4[2-9]|5[0-4])\.',
                            r'\[predator\] flandre [^\n]* prey bite [^\n]* accepted=true',
                            r'\[predator\] flandre [^\n]* prey bite [^\n]* alive=false',
                            r'\[predator\] flandre [^\n]* remains feeding via=(?:eat_pause|scavenge) [^\n]* accepted=true',
                            r'\[predator\][^\n]* beat=predator_chase face=(?:angry_stare|down_angry|angry) line=none',
                            r'\[predator\][^\n]* beat=predator_dive face=(\w+) line=none declared=\1 ',
                            r'\[predator\][^\n]* beat=predator_feed face=(\w+) line=none declared=\1 '],
       forbid=_HUNT_NOT_TEARY)
next(s for s in S if s.id == 'pred_range').smoke.update(
    verify=_range_pursuit, verify_trace=True,
    allow_fate=[dict(kind='death', who=f'uuid={_uuid_text("pred_range", "range_prey")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                     why='the far hunt in this scene ends with the prey killed by the predator\'s bite or the wound it left, then eaten')])
_smoke('colony_hunt', 2400, [r'\[settlement\][^\n]* event=hunt_call [^\n]*detail=party \d+ dest '])
for _exile_scene, _scope in (('colony_mold_exile', 'colony'), ('den_mold_exile', 'family')):
    # The exile mourns its lost home (a grief course) before it comes back to ask: the refusal comes later than before.
    _smoke(_exile_scene, 4800, [
        r'\[settlement\][^\n]* mold witnessed [^\n]* response=drive',
        rf'\[settlement\][^\n]* mold exile departed scope={_scope}\b',
        r'\[settlement\][^\n]* mold exile refused return\b',
        r'\[settlement\][^\n]* mold exile withdrew after refusal\b',
        r'\[settlement\] marisa [^\n]* mold exile answer=FAREWELL\b',
        *([rf'\[family\][^\n]*uuid={_uuid_text("den_mold_exile", "molded")} [^\n]* mold exile child stays '
           rf'child={_uuid_text("den_mold_exile", "baby")} healthy_parent={_uuid_text("den_mold_exile", "healthy")}\b',
           r'\[settlement\][^\n]* mold witnessed [^\n]* fond=true\b',
           r'\[settlement\][^\n]* mold witnessed [^\n]* harsh=true\b',
           r'\[settlement\][^\n]* mold exile protest [^\n]* answer=plead lookBacks=2\b',
           r'\[settlement\][^\n]* mold exile looked back left=0\b',
           rf'\[family\][^\n]*uuid={_uuid_text("den_mold_exile", "baby")} [^\n]* mold exile child '
           rf'(?:cries parent={_uuid_text("den_mold_exile", "molded")}\b|missed departure parent={_uuid_text("den_mold_exile", "molded")} reason=(?:out_of_sight|out_of_range)\b)',
           r'\[emotion\][^\n]* course begun kind=grief\b'] if _scope == 'family' else []),
    ], readout=[f'yukkuri status {sel("molded")} raw'])
_smoke('colony_ration', 3600, [r'\[settlement\][^\n]* event=ration [^\n]*detail=relief \d+/\d+ to [1-9]\d* short households',
                               r'\[settlement\][^\n]* event=ration_fetched '])


# Subjects of the scenes merged in the curation round: each merged lane or variant shows up in the log.
def _distinct(pattern, count, what):
    def verify(window):
        found = set(re.findall(pattern, window))
        return None if len(found) >= count else f'{what}: {len(found)} of {count} ({sorted(found)})'
    return verify


def _den_mold_exile_verify(window):
    subject = rf'\[settlement\][^\n]*uuid={_uuid_text("den_mold_exile", "molded")} [^\n]* mold exile '
    for event in ('appeals', 'return plea spoken=true', 'withdrew after refusal'):
        count = len(re.findall(subject + event + r'\b', window))
        if count != 1:
            return f'expected one exile event {event}, found {count}'
    if re.search(subject + r'withdrew without answer\b', window):
        return 'exile withdrew again without another appeal'


next(s for s in S if s.id == 'den_mold_exile').smoke['verify'] = _den_mold_exile_verify


_smoke('social_identity', 440, [
    rf'\[status\] {species} #[^\n]* identity observed kind={kind}\b'
    for species, kind in (('reimu', 'pet'), ('marisa', 'stray'), ('patchouli', 'wild_settlement'), ('chen', 'wild_family'))] + [
        r'\[social\] marisa #[^\n]* human food request [^\n]* line=human_food_demand\b',
        r'\[social\] patchouli #[^\n]* human retreat [^\n]* moving=true\b',
        r'\[social\] chen #[^\n]* human watch '],
    readout=[f'yukkuri status {sel("identity_" + kind)} raw' for kind in ('pet', 'stray', 'settlement', 'family')] +
            [f'data get entity @e[tag={tag("identity_stray")},distance=..24,limit=1] LastFoodRequestNutrition'])
_human_contact_smoke = next(s for s in S if s.id == 'social_identity').smoke


def _one_human_food_request(log):
    requests = re.findall(r'\[social\] marisa #[^\n]* human food request target=', log)
    return None if len(requests) == 1 else f'expected one voiced request from Marisa, found {len(requests)}'


_human_contact_smoke['verify'] = _one_human_food_request
for _sid, _outcome, _beg_checks in (
    ('wild', 'shared', [r' beg transfer [^\n]*result=shared received=minecraft:apple retained=minecraft:apple',
                        r'\[meal\] reimu [^\n]*swallowed item=minecraft:apple count=1',
                        r'\[meal\] marisa [^\n]*swallowed item=minecraft:apple count=1']),
    ('wild_beg_refuse', 'refused', [r' beg refusal [^\n]*response=PESTER', r' beg circled ',
                                  r'\[meal\] reimu [^\n]*swallowed item=minecraft:apple count=1']),
    ('wild_beg_snatch', 'snatched', [r' beg transfer [^\n]*result=snatched received=minecraft:apple retained=minecraft:air',
                                    r' beg grudge [^\n]*closeness=-?\d+->-\d+ fear=\d+->\d+',
                                    r'\[threat\][^\n]* marisa [^\n]* response started source=',
                                    r'\[meal\] marisa [^\n]*swallowed item=minecraft:apple count=1']),
    ('wild_beg_help', 'asked_help', [r' beg help reached human=',
                                     r'\[npc-care\][^\n]* action=pet_help [^\n]*attitude=CARING',
                                     r'\[npc-care\][^\n]* action=pet_comfort ',
                                     r'\[threat\][^\n]* marisa [^\n]* response started source=',
                                     r'\[meal\] reimu [^\n]*swallowed item=minecraft:apple count=1']),
):
    _smoke(_sid, 1200, [
        rf'\[status\] reimu [^\n]* identity observed kind={"npc_companion" if _sid == "wild_beg_help" else "pet"}\b',
        r'\[status\] marisa [^\n]* identity observed kind=wild_family\b',
        r'\[badge\] marisa [^\n]* beg social stand ',
        r'\[speech\] marisa [^\n]* cue event=beg_ask spoken=true',
        rf'\[wild-event\][^\n]*event=ended [^\n]*card=beg_bite [^\n]*elapsed=(?:[4-9]\d\d|1[01]\d\d|1200) detail={_outcome}\b',
        *_beg_checks,
    ], readout=[f'yukkuri status {sel(kind)} raw' for kind in ('pet', 'wild')])
    _beg_smoke = next(s for s in S if s.id == _sid).smoke
    _beg_smoke['gating_checks'] = ['LEDGER', 'END', 'PROGRESS']
    if _outcome == 'snatched':
        def _beg_grudge(window):
            changes = re.findall(r'\[badge\] reimu [^\n]* beg grudge [^\n]*closeness=(-?\d+)->(-?\d+) fear=(\d+)->(\d+)', window)
            if len(changes) != 1 or not all(int(c1) == max(-100, int(c0) - 20) and int(q1) > int(q0)
                                          for c0, c1, q0, q1 in changes):
                return f'expected one directed grudge from a completed snatch, got {changes}'
        _beg_smoke['verify'] = _beg_grudge
_smoke('badge', 1200, [r'\[badge\] marisa [^\n]*badge sight [^\n]*grade=4\b',
                     r'\[wild-event\][^\n]*event=ended [^\n]*card=envy [^\n]*detail=envy_\w+'],
    readout=[f'yukkuri status {sel(kind)} raw' for kind in ('pet', 'wild')])
_smoke('puff_response', 650, [r'\[social\][^\n]*puff response=(?:flee|ignore|puff_back|attack) [^\n]*esteem=[^\n]*wariness=[^\n]*insistence=[^\n]*combat='],
    readout=[f'yukkuri status {sel(f"puff_target{i}")} raw' for i in range(2)])
def _lead_turns(window):
    """At a turn, willingness favors the patient pet. A miss either steps aside onto ground the log names or rejoins at once.
    Whether a step aside reaches
    its goal depends on the owner's walk, so only the decision is asserted."""
    turns = {'reimu': [], 'marisa': []}
    for line in window.splitlines():
        found = re.search(r'\[lesson\] (reimu|marisa) #[^\n]* lead turn owner=\S+ waits=(true|false) aside=(true|false)', line)
        if found is not None:
            turns[found[1]].append((found[2] == 'true', found[3] == 'true'))
    if not turns['reimu']:
        return 'the patient pet never met a turn on the lead'
    if not any(waited for waited, aside in turns['reimu']):
        return f'the patient pet never waited at a turn: {turns["reimu"]}'
    if not turns['marisa']:
        return 'the impatient pet never met a turn on the lead'
    if all(waited for waited, aside in turns['marisa']):
        return f'the impatient pet waited at every turn: {turns["marisa"]}'
    return None


_smoke('training_walk', 2300, [
    r'\[lesson\] reimu [^\n]*lead turn owner=[^ ]+ waits=true',
    r'\[lesson\] marisa [^\n]*lead turn owner=[^ ]+ waits=(?:true|false) aside=(?:true|false)',
], readout=[f'yukkuri status {sel(n)} raw' for n in ('walk_practised', 'walk_novice')])
next(s for s in S if s.id == 'training_walk').smoke['verify'] = _lead_turns
def _counting_experience(window):
    answers = re.findall(r'\[lesson\][^\n]*uuid=(\S+) t=(\d+)[^\n]* study answered [^\n]*success=(true|false)', window)
    awards = []
    for actor, tick, before, after, old, new in re.findall(
            r'\[upbringing\][^\n]*uuid=(\S+) t=(\d+)[^\n]* cause=(?:counted|learned_counting) trait=INTELLIGENCE'
            r' value=(\d+) -> (\d+) remainder=(-?\d+) -> (-?\d+)', window):
        if 4 * (int(after) - int(before)) + int(new) - int(old) != 1:
            return f'{actor} at {tick}: expected one adult counting unit'
        awards.append((actor, tick))
    successes = [(actor, tick) for actor, tick, success in answers if success == 'true']
    if sorted(successes) != sorted(awards):
        return f'counting successes {successes} differ from intelligence awards {awards}'


_smoke('training_study', 1750, [
    r'\[lesson\][^\n]*study answered skill=COUNTING source=food count=6 success=false',
    r'\[lesson\][^\n]*study answered skill=COUNTING source=food count=3 success=true',
], readout=[f'data get entity {sel("counter")} YukkuriSkills', f'data get entity {sel("counter")} YukkuriLife.Personality',
            'data get block ~6 ~-1 ~2 Items'])
next(s for s in S if s.id == 'training_study').smoke['verify'] = _counting_experience
_smoke('balance_relation', 600, [r'\[status\] reimu [^\n]* identity observed kind=wild_family\b'],
    readout=[f'yukkuri status {sel(f"fear{fear}")} raw' for fear, _ in _BALANCE_FEAR])
next(s for s in S if s.id == 'balance_relation').smoke['verify'] = _distinct(
    r'\[status\] reimu #\d+ uuid=(\S+)[^\n]* identity observed kind=wild_family\b', 3, 'relation fixtures')


# The widowed mother mourns at the den first (a grief course's peak), so her first trip comes a couple of minutes after the death.
_smoke('den_family', 9000, [r'\[habitat\] reimu [^\n]* home chosen', r'\[habitat\] marisa [^\n]* home chosen',
                            r'\[forage\] reimu [^\n]* begin role=',
                            rf'\[emotion\][^\n]*uuid={_uuid_text("den_family", "mother")} [^\n]* course begun kind=grief\b',
                            rf'\[emotion\][^\n]*uuid={_uuid_text("den_family", "mother")} [^\n]* course stage kind=grief stage=easing\b',
                            rf'\[emotion\][^\n]*uuid={_uuid_text("den_family", "mother")} [^\n]* mourn focus target=remains[^\n]* approach=true\b',
                            rf'\[emotion\][^\n]*uuid={_uuid_text("den_family", "mother")} [^\n]* grief vow event=vow_partner_young [^\n]*spoken=true\b',
                            rf'\[emotion\][^\n]*uuid={_uuid_text("den_family", "child")} [^\n]* grief vow event=(?:vow_to_father|vow_blame_parent) [^\n]*lost=FATHER spoken=true\b'],
       readout=[f'yukkuri status {sel("mother")} raw'])


def _den_family_verify(log):
    mother, father, child = (_uuid_text('den_family', name) for name in ('mother', 'father', 'child'))

    def events(actor, category, message):
        pattern = rf'\[{category}\] \w+ #\d+ uuid={actor} t=(\d+) [^\n]*? {message}'
        return [(int(match[1]), match.groups()[1:]) for match in re.finditer(pattern, log)]

    death = events(father, 'body', r'death settled into remains; cause=genericKill\b')
    if not death:
        return 'den family: missing scripted father death'
    death_tick = death[0][0]
    homes = []
    for actor in (mother, father, child):
        claims = [values[0] for tick, values in events(actor, 'habitat', r'home chosen old=[^\n]*? home=(BlockPos\{[^}]+\})')
                  if tick <= death_tick]
        homes.append(claims[-1] if claims else None)
    if not homes[0] or len(set(homes)) != 1:
        return 'den family: parents and child did not claim one home before the death'

    focus = events(mother, 'emotion', r'mourn focus target=remains distance=(\S+) approach=true attached=true yawError=(\S+)')
    if len(focus) != 1:
        return f'den family: expected one maternal approach to the remains, saw {focus}'
    distance, yaw_error = map(float, focus[0][1])
    if not (0 < distance <= 6 and yaw_error <= 15):
        return f'den family: maternal approach started at {distance} blocks with yaw error {yaw_error}'
    end = events(mother, 'emotion', r'mourn approach ended result=(?:arrived|blocked|timeout) distance=(\S+) yawError=(\S+)')
    if len(end) != 1:
        return f'den family: expected one approach ending, saw {end}'
    remaining, yaw_error = map(float, end[0][1])
    if not (0 <= remaining < distance and yaw_error <= 15):
        return f'den family: mother went from {distance} to {remaining} blocks with final yaw error {yaw_error}'
    if re.search(r'\[emotion\][^\n]* mourn focus [^\n]* approach=true attached=false\b', log):
        return 'den family: an unattached mourner started walking'

    outings = events(mother, 'forage', r'begin role=FAMILY phase=OUT\b')
    departures = [tick for tick, _ in outings if tick > death_tick]
    if any(tick < death_tick for tick, _ in outings):
        # The same outing can resume gathering if she already reached the food when grief paused it.
        departures += [tick for tick, _ in events(mother, 'forage', r'goal active phase=(?:OUT|GATHER)\b') if tick > death_tick]
    departures.sort()
    if not departures:
        return 'den family: mother did not start or resume an outing after father death'
    if events(mother, 'family', rf'witnessed kin death dead={father}\b'):
        grief = events(mother, 'status', r'behavior\.animation: [^\n]*? -> bereaved\b')
        if not any(death_tick <= tick < departures[0] for tick, _ in grief):
            return 'den family: mother left before playing witnessed bereavement'

    deposits = events(mother, 'forage', rf'deposit home={re.escape(homes[0])} moved=[1-9]\d*\b')
    if not any(tick > departures[0] for tick, _ in deposits):
        return 'den family: no positive deposit from the mother after her departure'
    if not any(tick > death_tick for tick, _ in events(child, 'meal', r'swallowed item=\S+')):
        return 'den family: child did not eat after father death'
    # The young grieves its father before it complains about the corpse smell: the complaint may only start after the
    # course fades (mind/EmotionCourse.mourning() is false from there on).
    complaints = [tick for tick, _ in events(child, 'cannibal', r'started mode=AVOID\b')]
    faded = [tick for tick, _ in events(child, 'emotion', r'course stage kind=grief stage=fading\b')]
    if complaints and (not faded or complaints[0] < faded[0]):
        return f'den family: child complained about the smell at {complaints[0]} before its grief faded at {faded}'
    return None


next(s for s in S if s.id == 'den_family').smoke['verify'] = _den_family_verify

def _struck_speech(window):
    events = {'FLEE': ('hurt_flee',), 'FREEZE': ('hurt_freeze',), 'PUFF_FIGHT': ('hurt_defy', 'puff'),
              'PLEAD': ('hurt_tame_plea', 'hurt_plea', 'plea_spare'), 'ENDURE': ('hurt_endure',)}
    strikes = re.findall(r'\[threat\][^\n]* uuid=(\S+) t=(\d+)[^\n]* struck stance=(\w+) attacker=\S+ loss=(\S+) [^\n]* identity=(\w+) [^\n]*?dialogue=(\S*)', window)
    if not strikes:
        return 'Missing struck stance and spoken line'
    for actor, tick, stance, loss, identity, key in strikes:
        lines = re.findall(rf'\[speech\][^\n]* uuid={actor} t={tick} [^\n]* line event=(\S+) key=(\S+)', window)
        matching = [(event, line) for event, line in lines if any(event in group for group in events.values()) or event == 'afraid']
        allowed = events[stance]
        if stance == 'PLEAD':
            # An unhurt plea asks to be spared; only a kept animal may ask a keeper it trusts to treat the wound.
            allowed = ('plea_spare',) if float(loss) == 0 else ('hurt_plea',) \
                if identity in ('wild_family', 'wild_settlement', 'stray', 'house') else ('hurt_tame_plea', 'hurt_plea')
        if len(matching) != 1 or matching[0] != lines[-1] or matching[0][0] not in allowed or matching[0][1] != key:
            return f'{actor} at {tick} chose {stance} but its speech was {lines}'


_food_choice_smoke = next(s for s in S if s.id == 'choice_food').smoke
_food_choice_counts = _food_choice_smoke['verify']
_demanding_stray = sel('food_demand')
_food_choice_smoke['commands'] = [
    (1, 'scoreboard players set #choice_food_struck museum 0'),
    # Sample spawn support at +1 and +20 before a blow can lift the beggar.
    # askForFood sets LastFoodRequestNutrition only after delivering the request.
    *((tick, f'execute if score #choice_food_struck museum matches 0 if data entity {_demanding_stray} LastFoodRequestNutrition '
             f'store success score #choice_food_struck museum run damage {_demanding_stray} 1 minecraft:mob_attack by {sel("apple_holder")}')
      for tick in range(24, _food_choice_smoke['ticks'], 20)),
]


def _verify_struck_food_choice(log):
    if problem := _food_choice_counts(log):
        return problem
    actor = _uuid_text(_ugly, 'food_demand')
    request_tick, human = re.search(rf'\[social\][^\n]* uuid={actor} t=(\d+) [^\n]* human food request target=(\S+)', log).groups()
    voice = re.search(rf'\[speech\][^\n]* uuid={actor} t={request_tick} [^\n]* line event=human_food_demand [^\n]* until=(\d+)', log)
    hits = re.findall(rf'\[threat\][^\n]* uuid={actor} t=(\d+)[^\n]* struck stance=(\w+) attacker={human} [^\n]* identity=stray\b', log)
    if not voice or len(hits) != 1 or not int(request_tick) < int(hits[0][0]) < int(voice[1]):
        return f'the addressed human must strike during the voiced demand: request={request_tick}, hits={hits}'
    if hits[0][1] not in ('FLEE', 'PUFF_FIGHT'):
        return f'the struck bold stray must flee or puff and fight, got {hits}'
    return _struck_speech(log)


_food_choice_smoke['verify'] = _verify_struck_food_choice


def _home_greedy_retreat(window, trace):
    if problem := _struck_speech(window):
        return problem
    answers = re.findall(r'\[home-claim\][^\n]* uuid=(\S+) t=(\d+) [^\n]* driven off member=\1 answer=CURSE', window)
    if len(answers) != 1:
        return f'Expected one curse from the claimant, found {answers}'
    actor, start = answers[0]
    start = int(start)
    stage = re.search(rf'\[home-claim\][^\n]* HOLD -> RETREAT [^\n]*site=[^\n]* BlockPos'
                      rf'\{{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}} [^\n]*members=\[{actor}\]', window)
    end = re.search(rf'\[home-claim\] t=(\d+)[^\n]* ended claim=[^\n]*members=\[{actor}\][^\n]* result=driven_off', window)
    if not stage or not end:
        return 'Missing start or end of the same claimant’s retreat'
    end = int(end[1])
    frames = {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and row['u'] == actor and row['t'] >= start:
            frames[row['t']] = row['st']
    motions = Path(__file__).parents[1] / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json'
    stamp = json.loads(motions.read_text(encoding='utf-8'))['models']['marisa']['clips']['stamp']
    stamps = [t for t, frame in frames.items() if t < end and frame['animation'] == 'stamp']
    if stamps != list(range(start, start + round(stamp['duration'] * 20))) or len({tuple(frames[t]['p']) for t in stamps}) != 1:
        return 'The claimant must show one complete stationary stamp'
    first_step = next((frame for t, frame in frames.items() if start + len(stamps) <= t < end
                       and frame['p'] != frames[start]['p']), None)
    if not first_step or (first_step['behavior'], first_step['animation'], first_step['face']) != ('home_claim_retreat', 'waddle', 'angry'):
        return 'The claimant’s first moving retreat frame must be an angry waddle'
    x, y, z = map(int, stage.groups())
    if end not in frames or math.dist(frames[end]['p'], (x + .5, y, z + .5)) < 16:
        return 'The claimant ended the retreat before reaching the 16-block exit radius'
    # The velvet nest is two cells east and five north of the south-facing, left-hinged door.
    if problem := _oak_door_crossing(window, frames, actor, start, end, x - 2, y, z + 5, inward=False):
        return problem
    flight = [frame['p'] for t, frame in frames.items() if t > end and frame['behavior'] == 'flee']
    if not flight or not any(math.dist(flight[0], pos) > .5 for pos in flight):
        return 'The claimant did not move during panic'


def _oak_door_crossing(window, frames, actor, start, end, door_x, y, door_z, inward):
    def progress(pz):
        return door_z + 1 - pz if inward else pz - door_z

    boxes = (json.loads(row) for row in re.findall(r'MCYGEOMETRY (\{[^\n]+\})', window))
    box = next((row['box'] for row in boxes if row['uuid'] == actor), None)
    if box is None:
        return 'Missing collision width of the walker'
    radius = (box[3] - box[0]) / 2
    cleared = next((t for t in range(start, end + 1) if t in frames and progress(frames[t]['p'][2]) >= 1 + radius), None)
    if cleared is None or start not in frames or progress(frames[start]['p'][2]) >= -radius:
        return 'The walker did not reach the opposite side of the doorway'
    crossing = []
    for t in range(start, cleared + 1):
        if t not in frames:
            return 'Missing frame during the doorway crossing'
        px, py, pz = frames[t]['p']
        if frames[t]['veh'] is not None or (t > start and math.dist(frames[t-1]['p'], frames[t]['p']) > 1):
            return 'The walker was carried or jumped across part of the approach'
        if progress(pz) <= -radius:
            crossing.clear()  # The whole body backed out. The next approach must cross both halves on its own.
        elif door_z - radius + 1e-7 < pz < door_z + 1 + radius - 1e-7:
            if px - radius < door_x - 1e-7 or px + radius > door_x + 13/16 + 1e-7 or abs(py - y) > 1e-6:
                return 'The walker crossed outside the clear gap between the jamb and oak leaf'
            if inward and frames[t]['behavior'] != 'return_home':
                return 'The pet gave up walking home before its trailing edge cleared the door'
            crossing.append(pz)
    if not any(door_z <= pz < door_z + .5 for pz in crossing) or not any(door_z + .5 <= pz < door_z + 1 for pz in crossing):
        return 'The walker skipped part of the door cell'


def _pet_door_return(window, trace):
    x, z = BY_ID['house'].anchor
    bed = rf'BlockPos\{{x={x}, y={FEET}, z={z + 4}\}}'
    home_tick = re.search(rf'\[home\][^\n]* uuid={_return_pet} t=(\d+) [^\n]*assigned kind=care [^\n]*{bed}', window)
    sleep_tick = re.search(rf'\[sleep\][^\n]* uuid={_return_pet} t=(\d+) [^\n]*begin source=life occupancy=INSIDE_SUPPORTED bed={bed}', window)
    if not home_tick or not sleep_tick:
        return 'The returning pet needs its own assigned indoor bed and must fall asleep in it'
    frames = {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['u'] == _return_pet and row['k'] == 'st':
            frames[row['t']] = row['st']
    return _oak_door_crossing(window, frames, _return_pet, int(home_tick[1]), int(sleep_tick[1]), x, FEET, z + 7, inward=True)


def _return_pet_identity(readout):
    if not re.search(r'identity:\{[^}]*kind:"pet"[^}]*owner:"2d4c7192-2a6d-4da4-9f94-c82721b9de87"', readout):
        return 'The returning animal must still belong to the staged player'


_smoke('pet_door_return', 700, ['MCY_PET_RETURN_OPEN', 'MCY_PET_RETURN_OWNED'], readout=[f'yukkuri status {sel("return_pet")} raw'])
next(s for s in S if s.id == 'pet_door_return').smoke.update(verify=_pet_door_return, verify_trace=True, verify_readout=_return_pet_identity, commands=[
    (1, 'yukkuri scenario tool_operator enter'),
    (2, f'execute store result score #return_owner museum run yukkuri scenario owner_at {sel("return_pet")} away'),
    (3, 'execute if score #return_owner museum matches 1 run say MCY_PET_RETURN_OWNED'),
    (4, 'yukkuri scenario tool_operator leave'),
    (40, 'execute if block ~ ~ ~7 minecraft:oak_door[facing=south,hinge=left,half=lower,open=true] '
         'if block ~ ~1 ~7 minecraft:oak_door[facing=south,hinge=left,half=upper,open=true] '
         'if block ~-1 ~ ~7 minecraft:oak_planks if block ~1 ~ ~7 minecraft:oak_planks run say MCY_PET_RETURN_OPEN'),
])


def _resident_house_chain(window):
    window = '\n'.join(window.splitlines())
    founders = list(re.finditer(
        r'\[house\] house_\w+ #[0-9]+ uuid=([\w-]+) t=\d+ [^\n]* a house \w+ moved in at (\d+), (\d+), (\d+) \(entrance',
        window))
    if len(founders) != 1:
        return f'Expected one first founder, saw {len(founders)}'
    founder = founders[0]
    site = tuple(map(int, founder.groups()[1:]))
    if not (_cottage_x + 1 <= site[0] <= _cottage_x + 7 and site[1] == FEET
            and _cottage_z + 8 <= site[2] <= _cottage_z + 11):
        return f'Founder arrived outside the cottage interior at {site}'
    pos = rf'BlockPos\{{x={site[0]}, y={site[1]}, z={site[2]}\}}'
    actions = re.findall(rf'\[house\][^\n]*uuid={founder[1]} [^\n]*? ((?:pickup|nest founded|deposit) [^\n]*)',
                         window[founder.end():])
    expected = [r'pickup source=.* item=mcyukkuri:nest_lint count=1 feeding=false',
                rf'nest founded home=ResourceKey\[minecraft:dimension / minecraft:overworld\] {pos} identity=.*',
                rf'deposit home={pos} item=mcyukkuri:nest_lint count=1 food=0 lint=1']
    if len(actions) < 3 or not all(re.fullmatch(pattern, line) for pattern, line in zip(expected, actions)):
        return f'Founder {founder[1]} must lift one lint and commit it at the new nest at {site}'


def _resident_house_readings(readout):
    readings = re.split(r'MCYSMOKE_READOUT \d+ \d+\n', readout)[1:]
    if len(readings) != 6:
        return 'Expected six paired household readings'
    companions = set()
    for index, reading in enumerate(readings):
        stock = re.findall(r'region piles=(\d+) units=([\d.]+); within 12 blocks lint=(\d+) crumbs=(\d+)', reading)
        activity = re.findall(r'activity dust=(\d+)/600 settle=(\d+)/1200 swept=false cooldown=(\d+) recent=(\w+) roofed=true', reading)
        if len(stock) != 2 or len(activity) != 2:
            return f'Missing paired roofed readings at snapshot {index}'
        if stock[1][:2] != ('0', '0.0') or activity[1] != ('0', '0', '0', 'false'):
            return f'The empty control accrued stock or activity at snapshot {index}'
        if index == 0 and activity[0] != ('0', '0', '0', 'false'):
            return 'The occupied cottage must start without activity'
        if index < 5 and (float(stock[0][1]) != index or stock[0][2:] != (str(index), '0')
                          or 'resident house_' in reading or 'materials=' in reading):
            return f'Expected {index} lint, zero crumbs and no lodger before arrival at snapshot {index}'
        if f'has the following entity data: {_caretaker_home}L' not in reading:
            return f'The live caretaker is missing from its authored rug at snapshot {index}'
        spawning = 'true' if 0 < index < 5 else 'false'
        if f'doMobSpawning is currently set to: {spawning}' not in reading:
            return f'Unexpected world spawning rule at snapshot {index}'
        pet = re.search(r'has the following entity data: (\[I;[^\]]+\])', reading)
        owner = re.search(r'identity:\{[^}]*kind:"npc_companion"[^}]*owner:"([\da-f-]+)"', reading)
        if not pet or not owner:
            return f'Missing yard companion or custodian at snapshot {index}'
        companions.add((pet[1], owner[1]))
    if len(companions) != 1:
        return 'The original yard companion must keep its custodian'


_resident_house_scene = next(s for s in S if s.id == 'resident_house_lint')
_resident_house_scene.smoke = dict(ticks=4700, readout_at=(20, 800, 1420, 2060, 2680),
    readout=[_occupied_house, _empty_house,
             'execute positioned ~14.5 ~ ~6.5 if block ~ ~ ~ minecraft:red_carpet run data get entity '
             '@e[type=mcyukkuri:caretaker_sister,tag=mcy_yard_owner,distance=..0.75,limit=1] HumanHome',
             'gamerule doMobSpawning', 'execute if block ~14 ~ ~3 mcyukkuri:yukkuri_nest run data get block ~14 ~ ~3 Resident',
             f'data get entity {_yard_pet_selector} UUID', f'yukkuri status {_yard_pet_selector} raw'],
    expect=[r'\[npc-care\][^\n]*action=yard_pet result=spawned owner='],
    forbid=[r'\[body\][^\n]* invariant\b'],
    verify=_resident_house_chain, verify_readout=_resident_house_readings)


# In headless smoke, stand an unrelated peer near the home as a visitor would. The residents still run ordinary AI.
for _sid, _at, _dx, _dz in (('home_greedy', 360, 7.7, 4.6), ('den_family', 6000, 1, -2)):
    _intruder = uid(_sid, 'territory_intruder')
    _spec = next(s for s in S if s.id == _sid).smoke
    _spec['commands'] = [(_at, yk('reimu', _dx, _dz, 'territory_intruder', uuid=_intruder, boldness=0, extra='NoAI:1b'))]
    _spec['expect'] += [rf'\[home-claim\][^\n]* territory {beat} target={_uuid_text(_sid, "territory_intruder")}\b'
                        for beat in ('warn', 'chase')]
    if _sid == 'home_greedy':
        _spec['ticks'] = 1510
        _spec['commands'] += [
            (25, 'execute positioned ~5.5 ~ ~10.5 run yukkuri scenario tool_operator enter'),
            # The visitor opens the existing door before asking the claimant to leave.
            (697, 'execute positioned ~5.5 ~ ~9.5 run yukkuri scenario tool_operator move'),
            (698, 'yukkuri scenario shop_door_click ~5 ~ ~8 south'),
            (699, 'execute if block ~5 ~ ~8 minecraft:oak_door[facing=south,half=lower,hinge=left,open=true] '
                  'if block ~5 ~1 ~8 minecraft:oak_door[facing=south,half=upper,hinge=left,open=true] '
                  'if block ~4 ~ ~8 minecraft:oak_planks if block ~6 ~ ~8 minecraft:oak_planks run say MCYCLAIM_DOOR_OPEN'),
            (700, 'execute positioned ~8 ~ ~2.65 run yukkuri scenario tool_operator move'),
            (702, f'yukkuri scenario care_click {sel("greedy")} decline_event'),
            (1310, f'execute at {sel("greedy")} positioned ~ ~ ~2 run yukkuri scenario tool_operator move'),
            (1312, f'damage {sel("greedy")} 1 minecraft:player_attack by @e[type=minecraft:player,name=MuseumOperator,distance=..64,limit=1]'),
            # Environmental damage triggers panic even when the player's blow selected defiance.
            (1365, f'damage {sel("greedy")} 1 minecraft:in_fire'),
            (1415, 'yukkuri scenario tool_operator leave'),
        ]
        _spec['expect'] += [r'MCYCLAIM_DOOR_OPEN', r'\[home-claim\][^\n]* driven off member=[^ ]+ answer=CURSE',
                            r'\[home-claim\][^\n]* curse stamp spoken=true',
                            r'\[threat\] marisa [^\n]* struck stance=(?:PUFF_FIGHT|FLEE) [^\n]* dialogue=dialogue\.']
        _spec.setdefault('forbid', []).extend([
            r'struck stance=FLEE [^\n]*(?:v[13]_wounded|v1_afraid)\b',
            r'\[speech\] marisa [^\n]* line event=badge_envy(?:_snark)?\b',
        ])
        _spec.update(verify=_home_greedy_retreat, verify_trace=True)

_warm_parent = _uuid_text('indep_near', 'warm_parent')
_warm_teen = _uuid_text('indep_near', 'warm_teen')
_smoke('indep_near', 7200, [
    r'\[independence\][^\n]* farewell started',
    r'\[independence\][^\n]* farewell exchange [^\n]* attachment=85 parentAttachment=85 nuzzleTicks=[1-9]\d* lookBacks=2\b',
    r'\[independence\][^\n]* farewell exchange [^\n]* attachment=20 parentAttachment=20 nuzzleTicks=0 lookBacks=0\b',
    r'\[independence\][^\n]* see off answer [^\n]* attached=true\b',
    r'\[independence\][^\n]* see off answer [^\n]* attached=false\b',
    rf'\[independence\][^\n]*uuid={_warm_teen} [^\n]* farewell rub started parent={_warm_parent} ticks=75\b',
    rf'\[independence\][^\n]*uuid={_warm_teen} [^\n]* farewell rub released parent={_warm_parent} ticks=75 planned=75\b',
    r'\[independence\][^\n]* looked back [^\n]* count=2/2\b',
    rf'\[emotion\][^\n]*uuid={_warm_parent} [^\n]* course begun kind=parting\b',
    rf'\[emotion\][^\n]*uuid={_warm_parent} [^\n]* course goal behavior kind=parting stage=peak behavior=parting_gaze\b',
    rf'\[speech\][^\n]*uuid={_warm_parent} [^\n]* line event=parting_gaze\b'],
    forbid=[rf'\[speech\][^\n]*uuid={_warm_parent} [^\n]* line event=mourn_sob\b'],
    readout=[f'yukkuri status {sel("warm_parent")} raw'])


def _indep_near_verify(log):
    departed = _distinct(r'\[independence\] \w+ #\d+ uuid=(\S+)[^\n]* departed ', 3, 'teens that left home')(log)
    if departed:
        return departed
    course = rf'\[emotion\][^\n]*uuid={_warm_parent} [^\n]* course '
    start = re.search(course + r'begun kind=parting\b', log)
    end = re.search(course + r'ended kind=parting\b', log)
    if not start or not end or end.start() <= start.end():
        return 'indep near: the warm parent did not finish parting'
    if re.search(rf'\[status\][^\n]*uuid={_warm_parent} [^\n]* behavior\.animation: [^\n]* -> droop\b',
                 log[start.end():end.start()]):
        return 'indep near: the warm parent drooped during parting'


next(s for s in S if s.id == 'indep_near').smoke['verify'] = _indep_near_verify
_forage_mother, _forage_father = (_uuid_text('forage', n) for n in ('mother', 'father'))
_smoke('forage', 6000, [r'\[forage\][^\n]* begin role=FAMILY', r'\[forage\][^\n]* deposit home=[^\n]* moved=[1-9]',
                       rf'\[duty\][^\n]*uuid={_forage_mother} [^\n]* claimed kind=WATCH',
                       rf'\[family\][^\n]*uuid={_forage_mother} [^\n]* home watch started kin={_forage_father} stance=PATROL',
                       rf'\[duty\][^\n]*uuid={_forage_mother} [^\n]* released kind=WATCH end=DONE why=kin_returned',
                       r'\[family\][^\n]* welcome home started ',
                       rf'\[family\][^\n]* feed handed to meal child={_uuid_text("forage", "child")}\b'])
_absent_uuid = _uuid_text('family_absence', 'absent')
_stayer_uuid, _older_uuid = (_uuid_text('family_absence', n) for n in ('stayer', 'older'))
_smoke('family_absence', 7200, [rf'\[family\][^\n]* kin overdue kin={_absent_uuid}\b',
                               rf'\[family\][^\n]* missing kin call kin={_absent_uuid}\b',
                               rf'\[duty\][^\n]*uuid={_stayer_uuid} [^\n]* claimed kind=WATCH',
                               rf'\[duty\][^\n]*uuid={_stayer_uuid} [^\n]* released kind=WATCH end=REPLACED',
                               rf'\[duty\][^\n]*uuid={_stayer_uuid} [^\n]* claimed kind=SEARCH',
                               rf'\[family\][^\n]*uuid={_stayer_uuid} [^\n]* kin search waypoint kin={_absent_uuid} index=1/',
                               rf'\[family\][^\n]*uuid={_older_uuid} [^\n]* home watch started kin={_absent_uuid} stance=THRESHOLD'],
       forbid=[rf'\[family\][^\n]* witnessed kin death dead={_absent_uuid}\b'])


def _forage_watch(log):
    actor = rf'[^\n]* uuid={_forage_mother} [^\n]* '
    father = rf'\[forage\][^\n]* uuid={_forage_father} [^\n]* '
    outing = re.search(father + r'begin role=FAMILY phase=OUT\b', log)
    deposit = re.compile(father + r'deposit home=[^\n]* moved=[1-9]').search(log, outing.end()) if outing else None
    if not deposit:
        return 'the father must leave on a family trip and bring food home'
    release_pattern = re.compile(rf'\[duty\]{actor}released kind=WATCH\b[^\n]*')
    for claim in re.finditer(rf'\[duty\]{actor}claimed kind=WATCH\b', log[:deposit.start()]):
        release = release_pattern.search(log, claim.end())
        if not release:
            return 'the mother did not release her home watch'
        watched = log[claim.end():release.start()]
        if re.search(rf'\[habitat\]{actor}gathered item=|\[forage\]{actor}(?:food search started|begin role=)', watched):
            return 'the mother started foraging during her home watch'
        if release.start() < outing.start():
            continue
        if not re.search(rf'\[family\]{actor}home watch started kin={_forage_father} stance=PATROL\b', watched) or 'end=DONE why=kin_returned' not in release[0]:
            return 'the mother must patrol for the foraging father and finish when he returns'
        stations = re.findall(rf'\[family\]{actor}home watch station [^\n]* at=(\S+) mouthDistance=([\d.]+) result=ARRIVED', watched)
        if len({at for at, _ in stations}) < 2 or any(float(distance) < 2 for _, distance in stations):
            return f'the mother must visit at least two patrol stands two or more blocks from the mouth, saw {stations}'
        return None
    return "the mother never claimed a home watch during the father's first forage trip"


def _absence_grief_timing(log):
    stayer = _uuid_of(_stayer)
    found = re.search(rf'\[family\][^\n]* uuid={stayer} t=(\d+) [^\n]* missing kin found remains kin={_absent_uuid}', log)
    trip = re.search(rf'\[forage\][^\n]* uuid={stayer} t=(\d+) [^\n]* begin role=FAMILY phase=OUT', log)
    if not found or not trip or int(trip[1]) - int(found[1]) < 100:
        return 'the survivor must complete the 100-tick bereavement before its first outing'


next(s for s in S if s.id == 'forage').smoke['verify'] = _forage_watch
next(s for s in S if s.id == 'family_absence').smoke['verify'] = _absence_grief_timing
for _sid in ('forage', 'family_absence'):
    next(s for s in S if s.id == _sid).smoke['gating_checks'] = ('END',)
_taste_picky_uuid = _uuid_text('graze', 'taste_picky')
_smoke('graze', 1800, [
    *(rf'\[meal\][^\n]*uuid={_uuid_text("graze", f"taste_{name}")}[^\n]*swallowed item=mcyukkuri:oil_grain_budget[^\n]*verdict={verdict}\b'
      for name, _, _, _, _, verdict in _taste_actors if verdict != 'REFUSE'),
    rf'\[meal\][^\n]*uuid={_taste_picky_uuid}[^\n]*food_complain item=mcyukkuri:oil_grain_budget[^\n]*verdict=REFUSE\b',
    r'MCYGRAZE_PICKY_REMAINS'],
    forbid=[rf'\[meal\][^\n]*uuid={_taste_picky_uuid}[^\n]*swallowed '],
    readout=[f'execute if entity @e[type=minecraft:item,tag={tag("taste_grain_picky")},nbt={{Item:{{id:"mcyukkuri:oil_grain_budget",count:1}}}}] run say MCYGRAZE_PICKY_REMAINS'])
_smoke('food_behind_glass', 260, [r'\[action\][^\n]* end id=glass_press outcome=DONE', r'MCYGLASS_APPLE_REMAINS'],
       forbid=[rf'\[meal\][^\n]* uuid={_uuid_of(_glass_food_eater)} [^\n]* pickup '],
       readout=[f'execute if entity @e[type=minecraft:item,tag={tag("glass_apple")},nbt={{Item:{{id:"minecraft:apple",count:1}}}}] run say MCYGLASS_APPLE_REMAINS',
                'gamerule mobGriefing true'])
next(s for s in S if s.id == 'food_behind_glass').smoke['commands'] = [(1, 'gamerule mobGriefing false')]
next(s for s in S if s.id == 'food_behind_glass').smoke['verify'] = lambda log: _family_search_order(log, [
    (_glass_food_eater, 'food behind glass .*kind=loose'),
    (_glass_food_eater, 'start id=glass_press'), (_glass_food_eater, 'end id=glass_press outcome=DONE'),
], [(_glass_food_eater, 'start id=glass_press')])
_sill_operator = '@e[type=minecraft:player,name=MuseumOperator,distance=..60,limit=1]'
_smoke('food_sill_window', 900, [
    rf'\[meal\][^\n]* uuid={_uuid_of(_sill_beggar)} [^\n]*food behind glass target=\S+ kind=held [^\n]* look=true\b',
    rf'\[meal\][^\n]* uuid={_uuid_of(_sill_beggar)} [^\n]*begging at a pane too high to press'],
    forbid=[rf'\[action\][^\n]* uuid={_uuid_of(_sill_beggar)} [^\n]*start id=glass_press'])
next(s for s in S if s.id == 'food_sill_window').smoke['commands'] = [
    # The holder is in place before the stray's first plan; a stray that has already wandered sideways sees only wall.
    (3, f'execute at {sel("sill_anchor")} run yukkuri scenario tool_operator enter'),
    (4, f'item replace entity {_sill_operator} hotbar.0 with minecraft:apple'),
    (5, 'yukkuri scenario tool_operator select 0'),
    (880, 'yukkuri scenario tool_operator leave')]
_smoke('meal_content', 900, [r'\[meal\][^\n]* full nutrition=[^\n]*content=true'])
next(s for s in S if s.id == 'meal_content').smoke['verify'] = _meal_content_verify
_smoke('meet', 2400, [r'\[social\][^\n]* greet [^\n]*why=first_meet', r'\[daily\][^\n]* session result kind=NUZZLE'])
_step_low, _step_high = (_uuid_of(_SHAPES[name]) for name in ('low', 'high'))
_smoke('nuzzle_shapes', 2400, [
    rf'\[daily\][^\n]*uuid={_uuid_of(_SHAPES["wide"])} [^\n]*session result kind=NUZZLE\b',
    rf'\[daily\][^\n]*uuid={_step_low} [^\n]*session result kind=NUZZLE\b',
    rf'\[daily\][^\n]*uuid={_uuid_of(_SHAPES["wide"])} [^\n]*peer rub started partner={_uuid_of(_SHAPES["small"])} mode=LEVEL\b',
    (rf'\[daily\][^\n]*(?:uuid={_step_low} [^\n]*peer rub started partner={_step_high}'
     rf'|uuid={_step_high} [^\n]*peer rub started partner={_step_low}) mode=LEVEL\b'),
])
_SUMO_PUSHED_FACES = {'ashamed', 'surprise', 'teary_pant', 'angry'}


def _verify_marisa_sumo(window):
    """All three pairs complete sumo, pushed-back faces vary across the four allowed expressions with shown==declared and no
    consecutive duplicates per subject, and winner/loser speak their sumo lines."""
    done = re.findall(r'\[social\][^\n]* sumo completed winner=([\w-]+) loser=([\w-]+) [^\n]*loserFace=(\w+)', window)
    if len(done) < 3:
        return f'expected at least 3 completed sumo bouts, saw {len(done)}'
    beats = re.findall(r'\[choreo\][^\n]*uuid=([\w-]+) [^\n]*beat=(sumo_(?:pushed_back|push_out|win)) face=(\w+) line=(\w+) declared=(\w+)', window)
    pushed_faces = []
    per_actor = {}
    for who, beat, shown, line, declared in beats:
        if shown != declared:
            return f'{who} {beat} showed {shown}, declared {declared}'
        if beat in ('sumo_pushed_back', 'sumo_push_out'):
            if shown not in _SUMO_PUSHED_FACES:
                return f'{who} {beat} used unexpected pushed face {shown}'
            pushed_faces.append(shown)
            per_actor.setdefault(who, []).append((beat, shown))
    if len(set(pushed_faces)) < 2:
        return f'sumo pushed faces showed no variety: {pushed_faces}'
    for who, seq in per_actor.items():
        for (b1, f1), (b2, f2) in zip(seq, seq[1:]):
            # Within one bout (pushed_back -> pushed_back or pushed_back -> push_out), consecutive faces must not repeat.
            if b1 == 'sumo_pushed_back' and f1 == f2:
                return f'{who} repeated consecutive sumo pushed face {f1} across {b1}->{b2}'
    if not re.search(r'\[social\][^\n]* sumo speech role=winner event=sumo_win spoken=true\b', window):
        return 'no sumo winner spoke sumo_win'
    if not re.search(r'\[social\][^\n]* sumo speech role=loser event=sumo_lose spoken=true\b', window):
        return 'no sumo loser spoke sumo_lose'


_smoke('marisa_sumo', 1800, [
    r'\[social\][^\n]* sumo completed winner=',
    r'\[social\][^\n]* sumo pushed_back face=',
])
next(s for s in S if s.id == 'marisa_sumo').smoke['verify'] = _verify_marisa_sumo
# The north claim leaves both a quarrel record and one of them walks up to make up and is answered; the hostile south pair has its
# record too but neither approaches.
_qn = [_uuid_text('quarrel_makeup', n) for n in ('claimer', 'rival')]
_qs = [_uuid_text('quarrel_makeup', n) for n in ('foe_claimer', 'foe_rival')]
_smoke('quarrel_makeup', 2400, [
    rf'\[social\][^\n]*uuid=(?:{_qn[0]}|{_qn[1]}) [^\n]*quarrel recorded peer=(?:{_qn[0]}|{_qn[1]})\b',
    rf'\[social\][^\n]*uuid=(?:{_qn[0]}|{_qn[1]}) [^\n]*reconcile approach peer=(?:{_qn[0]}|{_qn[1]}) affinity=',
    rf'\[social\][^\n]*uuid=(?:{_qn[0]}|{_qn[1]}) [^\n]*reconcile answered peer=(?:{_qn[0]}|{_qn[1]}) accepted=true\b',
    rf'\[social\][^\n]*uuid=(?:{_qs[0]}|{_qs[1]}) [^\n]*quarrel recorded peer=(?:{_qs[0]}|{_qs[1]})\b',
], forbid=[rf'\[social\][^\n]*uuid=(?:{_qs[0]}|{_qs[1]}) [^\n]*reconcile (?:approach|answered)\b'])
# The timid dull stray pauses 80 ticks and the bold clever one 8 at the strange apple, the starving one (nutrition 5, below the
# no-pause line even after its first apple) not at all; the starving one eats first, and the other two, both fed enough to stand
# at one apple, stage a food contest over it (a verdict is logged) instead of queueing.
_strange = {name: _uuid_text('stray_strange_food', name) for name in ('timid', 'bold', 'starving')}
_smoke('stray_strange_food', 900, [
    rf'\[meal\][^\n]*uuid={_strange["timid"]} [^\n]*strange_food_pause item=minecraft:apple ticks=(?:[7-9]\d|80) ',
    rf'\[meal\][^\n]*uuid={_strange["bold"]} [^\n]*strange_food_pause item=minecraft:apple ticks=(?:[89]|1\d) ',
    rf'\[meal\][^\n]*uuid={_strange["starving"]} [^\n]*swallowed item=minecraft:apple',
    r'\[social\][^\n]* contest verdict prize=food ',
], forbid=[rf'\[meal\][^\n]*uuid={_strange["starving"]} [^\n]*strange_food_pause'])
# Marisa keeps the tired Reimu company; the unknown timid Chen gives none.
_company_friend = _uuid_text('company_visit', 'friend')
_smoke('company_visit', 900, [
    rf'\[kindness\][^\n]*uuid={_company_friend} [^\n]*kept company peer={_uuid_text("company_visit", "weak")}\b',
], forbid=[rf'\[kindness\][^\n]*uuid={_uuid_text("company_visit", "stranger")} [^\n]*(?:offer act=COMPANY|kept company)\b'])
_wide_greeter = _uuid_of(_CLOSE_GREETERS['wide'])
_bean_greeter = _uuid_of(_CLOSE_GREETERS['bean'])
_smoke('greet_close', 620, [
    rf'\[social\][^\n]* greet from={_wide_greeter} to={_bean_greeter} why=first_meet',
    rf'\[social\][^\n]* greet answer from={_bean_greeter} to={_wide_greeter} why=first_meet answer=greet'])
_sukkiri_beats = (('build', 1, 0), ('su', 2, 30), ('su_again', 2, 52), ('release', 3, 70))


def _sukkiri_timing(log, *, alice=False):
    start = re.search(r'(?:act timeline target=\S+|courtship contact started partner=\S+) loopTicks=(\d+) requiredTicks=(\d+)', log)
    if not start:
        return 'missing contact timeline'
    loop, required = map(int, start.groups())
    minimum, maximum, cycle = (100, 160, 20) if alice else (100, 600, 32)
    if not minimum <= loop <= maximum or loop % cycle or required != loop + 130:
        return f'invalid contact timeline: loop={loop}, total={required}, cycle={cycle}'
    release = re.search(r'act release effect target=\S+ ticks=(\d+)' if alice
                        else r'courtship release partner=\S+ tick=(\d+)', log)
    finish = re.search(r'contact ended target=[^\n]* ticks=(\d+)' if alice
                       else r'courtship contact ended partner=[^\n]* ticks=(\d+)', log)
    if not release or not finish:
        return 'missing release or contact end'
    if int(release[1]) != loop + 70 or int(finish[1]) != required:
        return f'contact timeline mismatch: loop={loop}, release={release[1]}, finish={finish[1]}'
    if alice:
        released = re.search(r'released attacker=\S+ settled=\w+ completed=true', log)
        if not released or released.start() <= release.start() or released.start() >= finish.start():
            return 'held partner did not release at contact completion'
    return None


_smoke('alice_hunt', 1200, [r'\[agitation\] alice [^\n]* phase=IDLE -> ALICE_WATCH',
    r'\[family\] alice [^\n]* pressure reason=sukkiri pentUp=9[\d.]+ -> 0\.0\b',
    r'\[agitation\][^\n]* phase=ALICE_APPROACH -> ALICE_ACT',
    r'\[agitation\][^\n]* act release effect target=\S+ ticks=\d+ droplets=8 hearts=2\b',
    r'\[agitation\][^\n]* outcome (?:target=|refused)', r'\[agitation\][^\n]* contact ended target=',
    *(rf'\[agitation\][^\n]* finale beat=beat_{beat} line=heat_relief_{line} admitted=true ticks=\d+ climaxTick={tick}\b'
      for beat, line, tick in _sukkiri_beats)],
    forbid=[r'\[agitation\][^\n]* contact interrupted target=[^\n]* settled=true'])
next(s for s in S if s.id == 'alice_hunt').smoke['verify'] = lambda log: _sukkiri_timing(log, alice=True)
# The shared nest releases the young it may no longer hold as each one grows: three of the four cross BABY_END inside the window,
# and each released child then claims one of the two free ordinary nests standing beside the shared one.
def _family_nests_release(log):
    released = set(re.findall(r'\[home\] \w+ #\d+ uuid=(\S+)[^\n]* growth released shared nest ', log))
    if len(released) < 3:
        return f'young released from the shared nest: {len(released)} of 3 ({sorted(released)})'
    bedded = set(re.findall(r'\[home\] \w+ #\d+ uuid=(\S+)[^\n]* released child claimed a free nest ', log))
    left = sorted(released - bedded)
    return f'{len(left)} released young stayed bedless ({left})' if left else None


_smoke('family_nests', 4200, [r'\[home\][^\n]* growth released shared nest ', r'\[home\][^\n]* released child claimed a free nest '])
next(s for s in S if s.id == 'family_nests').smoke['verify'] = _family_nests_release
next(s for s in S if s.id == 'family_nests').smoke['readout'] = ['yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..48] raw']
_pair_release = [
    *(rf'\[family\][^\n]* courtship beat=beat_{beat} line=heat_relief_{line} [^\n]*climaxTick={tick} fatherSpoke=true motherSpoke=true\b'
      for beat, line, tick in _sukkiri_beats),
    r'\[family\][^\n]* courtship release partner=\S+ tick=\d+ requiredTicks=\d+ droplets=8 hearts=2\b',
    r'\[family\][^\n]* courtship release tail partner=\S+ tick=\d+ requiredTicks=\d+ droplets=4\b']
_smoke('adult_pairing', 1800, [r'\[family\][^\n]* courtship acceptance started ', *_pair_release,
                                 r'\[family\][^\n]* courtship contact ended [^\n]*conceived=true'])
next(s for s in S if s.id == 'adult_pairing').smoke['readout'] = [f'yukkuri status {sel("mother")} raw',
                                                                  f'yukkuri status {sel("father")} raw']
next(s for s in S if s.id == 'adult_pairing').smoke['verify'] = _sukkiri_timing
# The scene's own button ends the original partner; the widow may accept the candidate only once the ledger holds that death.
_smoke('widow_repair', 3600, [r'\[family\][^\n]* court refused reason=partner_alive\b',
                                r'\[family\] reimu[^\n]* witnessed kin death [^\n]*mate=true',
                                r'\[body\][^\n]* death settled[^\n]*cause=genericKill\b',
                                r'\[family\][^\n]* partnership formed [^\n]*allowWidow=true', *_pair_release,
                                r'\[family\][^\n]* courtship contact ended [^\n]*conceived=true'])
next(s for s in S if s.id == 'widow_repair').smoke['commands'] = [(600, f'kill {sel("old_partner")}')]
next(s for s in S if s.id == 'widow_repair').smoke['readout'] = [f'yukkuri status {sel("widow")} raw',
                                                                 f'yukkuri status {sel("new_partner")} raw']
_smoke('aftermath_growth', 3600, [r'\[family\][^\n]* stem drain stage', r'\[family\][^\n]* stem sprouted ',
                                r'\[family\][^\n]* stem ripe [^\n]*fruit=3\b'])

# MD-11's reservation logs the live UUID and the two pens' distinct starting reserves before either stem draws.
_MD11_STEM_START = (r'\[family\] reimu #\d+ uuid=(?P<actor>[0-9a-f-]{36}) [^\n]* '
                    r'stem drain stage 0->1 brood=(?P<brood>[0-9a-f-]{36}) unborn=3 nutrition=')
_MD11_UNFED_MOTHER = _MD11_STEM_START + r'55\.0 energy=60\.0\b'


def _aftermath_growth_verify(window):
    for lane, mother in (('growth', _MD11_STEM_START + r'100\.0 energy=100\.0\b'), ('burden', _MD11_UNFED_MOTHER)):
        start = re.search(mother, window)
        if start is None:
            return f'{lane}: stem mother not identified at reservation'
        prefix = rf'\[(?:family|meal)\] reimu #\d+ uuid={start["actor"]} [^\n]* '
        brood = start['brood']
        growing_window = window[start.end():]
        sprout = re.search(prefix + rf'stem sprouted brood={brood} elapsed=1 duration=(\d+) fruit=3\b', growing_window)
        if sprout is None:
            return f'{lane}: three-fruit stem did not sprout'
        growing = growing_window[sprout.end():]
        ripe = re.search(prefix + rf'stem ripe brood={brood} elapsed={sprout[1]} duration={sprout[1]} fruit=3\b', growing)
        if ripe is None:
            return f'{lane}: the sprouted stem did not ripen'
        for before, after in re.findall(prefix + r'swallowed item=minecraft:apple count=1 nutrition=([\d.]+) -> ([\d.]+)',
                                        growing[:ripe.start()]):
            if float(after) <= float(before):
                return f'{lane}: a swallowed apple did not restore nutrition ({before} -> {after})'
    return None


next(s for s in S if s.id == 'aftermath_growth').smoke['verify'] = _aftermath_growth_verify
next(s for s in S if s.id == 'aftermath_growth').smoke['readout'] = [f'yukkuri status {sel("affected_growth")} raw',
                                                                   f'yukkuri status {sel("affected_burden")} raw']
next(s for s in S if s.id == 'aftermath_growth').smoke['gating_checks'] = ('END', 'FATE')
_smoke('aftermath_depletion', 3600, [r'\[family\][^\n]* aftermath reserved ', r'\[family\][^\n]* stem sprouted ',
                                    r'\[family\][^\n]* stem depletion warning ',
                                    r'\[family\][^\n]* stem drew body [^\n]*health=[\d.]+->0\.0\b',
                                    r'\[body\][^\n]* death settled[^\n]* stem_depletion=true',
                                    r'\[body\][^\n]* stem remains [^\n]*progress=0\.\d+ count=5 absent=0 placement=body'])
next(s for s in S if s.id == 'aftermath_depletion').smoke['readout'] = [
    f'yukkuri status {sel("affected_depletion")} raw', 'yukkuri body info @e[type=mcyukkuri:yukkuri_remains,distance=..40,limit=1,sort=nearest]']
next(s for s in S if s.id == 'aftermath_depletion').smoke['gating_checks'] = ('END', 'FATE')
_smoke('growth', 200, [r'\[growth\][^\n]* stage=BABY -> JUVENILE', r'\[growth\][^\n]* age boundary [^\n]*subadult=true',
                       r'\[growth\][^\n]* stage=JUVENILE -> ADULT', r'\[growth\][^\n]* age boundary [^\n]*elder=true',
                       r'\[growth\][^\n]* lifespan reached'])
_smoke('family_search', 7200, [r'\[family\][^\n]* child search reunited', r'\[family\][^\n]* parent dismissed ',
                               r'\[family\][^\n]* child call started ', r'\[family\][^\n]* child heard parent call ',
                               r'\[family\][^\n]* glass vigil started ', r'\[family\][^\n]* glass farewell '])
next(s for s in S if s.id == 'family_search').smoke['verify'] = _family_call_verify
_smoke('family_search_pane', 2400, [r'\[family\][^\n]* glass vigil started ', r'\[action\][^\n]* break id=bash ',
                                    r'\[family\][^\n]* glass crossed ', r'\[family\][^\n]* child search reunited '])
next(s for s in S if s.id == 'family_search_pane').smoke['verify'] = _family_pane_verify
_smoke('rain_open', 1200, [r'\[weather\][^\n]* shelter response started', r'\[needs\][^\n]* soaking damage '])
_smoke('treatment_scars', 1600, [r'\[body\][^\n]* wound healing ', r'\[body\][^\n]* scar (?:formed|not formed) from '])
_smoke('treatment_care', 900, [
    rf'\[npc-care\][^\n]* action=prepare_dose target={_uuid_text("treatment_care","patient")} treatment=ORANGE_JUICE\b',
    rf'\[npc-care\][^\n]* action=treatment target={_uuid_text("treatment_care","patient")} treatment=ORANGE_JUICE [^\n]*result=applied',
    rf'\[npc-care\][^\n]* action=treatment target={_uuid_text("treatment_care","patient")} treatment=FLOUR_PASTE [^\n]*result=applied',
])
_smoke('wound_materials', 400, [], forbid=[r'\[body\][^\n]* death settled'])
_smoke('wound_sources', 490, [
    r'\[interaction\][^\n]* operation started; [^\n]* pose=PIN duration=untimed reserved_item=mcyukkuri:pin_tool count=1',
    r'\[interaction\][^\n]* operation ended; [^\n]* pose=PIN ',
    r'\[interaction\][^\n]* binding returned to world; [^\n]* item=mcyukkuri:pin_tool count=1',
    r'\[interaction\][^\n]* operation started; [^\n]* pose=BOX duration=400 reserved_item=mcyukkuri:iron_enclosure count=1',
    r'\[interaction\][^\n]* binding returned to world; [^\n]* item=mcyukkuri:iron_enclosure count=1',
    r'\[interaction\][^\n]* binding returned to world; [^\n]* item=mcyukkuri:hanging_hook count=1',
    r'\[interaction\][^\n]* shear; contact=[^\n]* above_feet=0\.[1-9]',
    r'\[headwear\][^\n]* accessory destroyed burned=false cause=cut pieces=[23]',
    r'\[interaction\][^\n]* action=CUT_ACCESSORY success=true',
], forbid=[r'\[interaction\][^\n]* operation ended; [^\n]* pose=PIN [^\n]* reason=(?:operator_unreachable|timeout)'])
_floor_pin = 'mcyukkuri:pin_tool[minecraft:custom_data={BDFloor:1b}]'
_floor_pin_drop = '@e[type=item,nbt={Item:{id:"mcyukkuri:pin_tool",count:1,components:{"minecraft:custom_data":{BDFloor:1b}}}},limit=1]'
_pin_player = '@p[name=MuseumOperator,distance=..40]'
_tool_stand = 'attributes:[{id:"minecraft:generic.movement_speed",base:0.0d}]'
next(s for s in S if s.id == 'wound_sources').smoke['commands'] = [
    (4, f'execute at {sel("blunt")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("blunt")} enclose'),
    (5, f'execute at {sel("cut")} positioned ~ ~ ~1 run yukkuri scenario tool_operator enter'),
    (5, f'item replace entity {_pin_player} hotbar.0 with {_floor_pin}'),
    (6, f'yukkuri scenario shop_stock_click {sel("cut")}'),
    (7, 'yukkuri scenario tool_operator leave'),
    (8, f'execute at {sel("arrow")} run setblock ~ ~3 ~ minecraft:glass'),
    (9, f'execute at {sel("arrow")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("arrow")} hook_hang'),
    (10, f'execute at {sel("thorn")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("thorn")} cut_left'),
    (12, f'execute at {sel("cone")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("cone")} cut_accessory'),
    # A vanilla data edit round-trips entity NBT without touching the pin or wound fields.
    (15, f'data modify entity {sel("cut")} CustomNameVisible set value 0b'),
    (45, f'execute at {sel("heat")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("heat")} pierce'),
    (425, f'execute if data entity {sel("cut")} YukkuriOperations.PinWound '
          f'if data entity {sel("cut")} YukkuriOperations.PendingReturn.components."minecraft:custom_data".BDFloor '
          f'unless entity {_floor_pin_drop} run say MCY_BD07_PIN_STILL_ATTACHED'),
    (426, f'execute at {sel("cut")} positioned ~ ~ ~1 run yukkuri scenario tool_operator enter'),
    (426, f'item replace entity {_pin_player} hotbar.1 with mcyukkuri:yukkuri_carrier'),
    (426, 'yukkuri scenario tool_operator select 1'),
    (426, f'yukkuri scenario shop_stock_click {sel("cut")}'),
    (426, 'yukkuri scenario tool_operator select 0'),
    (427, f'yukkuri scenario shop_stock_click {sel("cut")}'),
    (442, f'execute at {_floor_pin_drop} run yukkuri scenario tool_operator move'),
    (443, f'yukkuri scenario tool_operator pick_up {_floor_pin_drop}'),
    (444, f'execute if items entity {_pin_player} hotbar.0 {_floor_pin} unless entity {_floor_pin_drop} '
          'run say MCY_BD07_SURVIVAL_PIN_RECOVERED'),
    (445, yk('reimu', -5, 5, 'tool_creative', extra=_tool_stand,
             uuid=uid('wound_sources', 'tool_creative'))),
    (464, f'execute at {sel("tool_creative")} positioned ~ ~ ~1 run yukkuri scenario tool_operator move'),
    (464, f'gamemode creative {_pin_player}'),
    (464, f'item replace entity {_pin_player} hotbar.1 with mcyukkuri:pin_tool'),
    (464, 'yukkuri scenario tool_operator select 1'),
    (465, f'yukkuri scenario shop_stock_click {sel("tool_creative")}'),
    (466, f'execute if data entity {sel("tool_creative")} YukkuriOperations.PinWound '
          f'unless data entity {sel("tool_creative")} YukkuriOperations.PendingReturn '
          f'if items entity {_pin_player} hotbar.1 mcyukkuri:pin_tool run say MCY_BD07_CREATIVE_PIN_RETAINED'),
    (467, 'yukkuri scenario tool_operator select 2'),
    (468, f'yukkuri scenario shop_stock_click {sel("tool_creative")}'),
    (469, f'execute unless data entity {sel("tool_creative")} YukkuriOperations.PinWound '
          f'if items entity {_pin_player} hotbar.1 mcyukkuri:pin_tool run say MCY_BD07_CREATIVE_PIN_EXTRACTED'),
    (470, 'yukkuri scenario tool_operator leave'),
]

_staff_tool_cases = (('awl', 'care_awl', -8), ('pin', 'pin_tool', 6))
_wound_smoke = next(s for s in S if s.id == 'wound_sources').smoke
for case, tool, x in _staff_tool_cases:
    fixture = 'tool_' + case
    operator = 'staff_' + case
    _wound_smoke['commands'].extend([
        (18, yk('reimu', x, 5, fixture, extra=_tool_stand, uuid=uid('wound_sources', fixture))),
        (18, f'summon mcyukkuri:caretaker_brother ~{x + .5} ~ ~6.5 '
             f'{{Tags:["scn","{tag(operator)}"],{_tool_stand},PersistenceRequired:1b,HumanInitialized:1b,HumanJob:2,'
             f'HandItems:[{{id:"mcyukkuri:{tool}",count:1,components:{{"minecraft:custom_data":{{A1:"{case}"}}}}}},{{}}]}}'),
        (55, f'yukkuri scenario staff_tool {sel(operator)} use {sel(fixture)}'),
    ])
    if case != 'awl':
        condition = f'Item:{{id:"mcyukkuri:{tool}",components:{{"minecraft:custom_data":{{A1:"{case}"}}}}}}'
        item_selector = f'@e[type=minecraft:item,nbt={{{condition}}},distance=..5,limit=1]'
        hand = f'HandItems:[{{id:"mcyukkuri:{tool}",count:1,components:{{"minecraft:custom_data":{{A1:"{case}"}}}}}}]'
        pickup = 220
        _wound_smoke['commands'].extend([
            (56, f'execute if entity {sel(operator)} unless data entity {sel(operator)} HandItems[0].id '
                 f'run say MCYA1_HAND_EMPTY_{case}'),
            (pickup, f'execute at {sel(fixture)} run yukkuri scenario staff_tool {sel(operator)} pickup {item_selector}'),
            (pickup + 1, f'execute at {sel(fixture)} if entity @e[tag={tag(operator)},nbt={{{hand}}}] '
                         f'unless entity {item_selector} run say MCYA1_BACK_IN_HAND_{case}'),
        ])
        _wound_smoke['expect'].extend([f'MCYA1_HAND_EMPTY_{case}', f'MCYA1_BACK_IN_HAND_{case}'])
_wound_smoke['commands'].extend([
    (56, f'yukkuri scenario staff_tool {sel("staff_awl")} use {sel("tool_awl")}'),
    (100, yk('reimu', 0, 5, 'tool_frame', extra=_tool_stand, uuid=uid('wound_sources', 'tool_frame'),
             boldness=80, self_control=25)),
    (114, 'execute positioned ~1.5 ~ ~4.5 run yukkuri scenario tool_operator enter'),
    *[(115, f'execute as @p[distance=..8] run {command}') for command in own('tool_frame')],
    (116, 'item replace entity @p[distance=..8] hotbar.0 with mcyukkuri:restraint_frame'),
    (117, 'yukkuri scenario shop_block_click ~ ~-1 ~4'),
    (118, 'item replace entity @p[distance=..8] hotbar.1 with mcyukkuri:yukkuri_carrier'),
    (119, 'yukkuri scenario tool_operator select 1'),
    (120, f'yukkuri scenario shop_stock_click {sel("tool_frame")}'),
    (144, 'yukkuri scenario shop_block_click ~ ~ ~4'),
    (145, 'item replace entity @p[distance=..8] hotbar.2 with mcyukkuri:pin_tool[minecraft:custom_data={BD07:1b}]'),
    (145, 'yukkuri scenario tool_operator select 2'),
    (146, f'yukkuri scenario shop_stock_click {sel("tool_frame")}'),
    (147, f'yukkuri scenario care_click {sel("tool_frame")} empty_hand'),
    (150, f'yukkuri scenario shop_stock_click {sel("tool_frame")}'),
    (151, f'execute if data entity {sel("tool_frame")} YukkuriOperations.Table '
          f'unless data entity {sel("tool_frame")} YukkuriOperations.PendingReturn '
          'if entity @e[type=item,nbt={Item:{id:"mcyukkuri:pin_tool",count:1,components:{"minecraft:custom_data":{BD07:1b}}}}] '
          'run say MCY_BD07_PIN_OUT_TABLE_HELD'),
    (168, f'execute at {sel("tool_frame")} positioned ~1 ~ ~ run yukkuri scenario tool_click {sel("tool_frame")} hook_hang'),
    (169, 'yukkuri scenario tool_operator select 3'),
    (170, f'yukkuri scenario shop_stock_click {sel("tool_frame")}'),
    (171, 'yukkuri scenario tool_operator leave'),
    (185, f'yukkuri scenario care_click {sel("tool_pin")} orange_juice'),
])
_wound_smoke['commands'].sort(key=lambda command: command[0])
_wound_smoke['expect'].extend([
    'MCY_BD07_PIN_OUT_TABLE_HELD',
    'MCY_BD07_PIN_STILL_ATTACHED',
    'MCY_BD07_SURVIVAL_PIN_RECOVERED',
    'MCY_BD07_CREATIVE_PIN_RETAINED',
    'MCY_BD07_CREATIVE_PIN_EXTRACTED',
    r'\[interaction\][^\n]* interaction refused; [^\n]* action=capture reason=pinned',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_frame")} [^\n]* interaction refused; [^\n]* action=release reason=owner',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_frame")} [^\n]* pose=PIN elapsed=\d+ remaining=0 reason=released',
    rf'\[body\][^\n]* uuid={_uuid_text("wound_sources", "tool_awl")} [^\n]* accepted damage cause=mob [^\n]* loss=2\.0',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_awl")} [^\n]* tool used; [^\n]* action=PIERCE item=mcyukkuri:care_awl hand_count=1 -> 1 damage=0 -> 1',
    r'\[body\][^\n]* accepted damage cause=player [^\n]* loss=2\.0',
    r'\[interaction\][^\n]* tool refused; [^\n]* action=pierce reason=busy',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_frame")} [^\n]* tool used; [^\n]* action=HOOK_HANG [^\n]* pose=FIXED',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_frame")} [^\n]* table unstrapped; [^\n]* reason=released',
    rf'\[body\][^\n]* uuid={_uuid_text("wound_sources", "tool_pin")} [^\n]* orange juice cleared 1 wound\(s\)',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_pin")} [^\n]* operation ended; [^\n]* pose=PIN [^\n]* reason=wound_closed',
])

def _wound_tool_binding(window):
    wounds = set(re.findall(r'\[body\][^\n]* uuid=([\w-]+) [^\n]* wound placed tear/puncture:[^\n]*; wound=([\w-]+)', window))
    attachments = re.findall(r'\[interaction\][^\n]* uuid=([\w-]+) [^\n]* binding wound; pose=(PIN|HOOK) wound=([\w-]+)', window)
    if (sorted(pose for _, pose, _ in attachments) != ['HOOK', 'HOOK', 'PIN', 'PIN', 'PIN', 'PIN']
            or len({wound for _, _, wound in attachments}) != 6
            or any((actor, wound) not in wounds for actor, _, wound in attachments)):
        return 'each of the four pins and two hooks must bind to its own committed puncture'
    pulls = re.findall(r'\[interaction\][^\n]* uuid=(\S+) [^\n]* operation ended; actor=2d4c7192-2a6d-4da4-9f94-c82721b9de87 '
                       r'pose=PIN elapsed=(\d+) remaining=0 reason=released', window)
    creative = _uuid_text('wound_sources', 'tool_creative')
    table = _uuid_text('wound_sources', 'tool_frame')
    if (len(pulls) != 3 or pulls[0][0] != table or pulls[2][0] != creative
            or pulls[1][0] in (table, creative) or int(pulls[1][1]) <= 400):
        return f'expected table, long-held floor and Creative extractions in order: {pulls}'
    for animal, _ in pulls:
        drops = re.findall(rf'\[interaction\][^\n]* uuid={animal} [^\n]* binding returned to world; [^\n]* item=mcyukkuri:pin_tool count=1 contact=', window)
        if len(drops) != int(animal != creative):
            return f'incorrect drop count for {animal}: {len(drops)}'
    for case, tool, _ in _staff_tool_cases[1:]:
        target = _uuid_text('wound_sources', 'tool_' + case)
        drops = re.findall(rf'\[interaction\][^\n]* uuid={target} [^\n]* binding returned to world; drop=([\w-]+) item=mcyukkuri:{tool} count=1', window)
        picked = re.findall(rf'\[interaction\][^\n]* tool picked up; drop=([\w-]+) item=mcyukkuri:{tool} hand_count=0 -> 1', window)
        if len(drops) != 1 or drops != picked:
            return f'the staff {case} must return one real drop and pick up that same drop'

next(s for s in S if s.id == 'wound_sources').smoke['verify'] = _wound_tool_binding
next(s for s in S if s.id == 'wound_sources').smoke['readout'] = [
    f'data get entity {sel("thorn")} YukkuriBody.HairLoss',
]

_fear_holder = '2d4c7192-2a6d-4da4-9f94-c82721b9de87'
_punishment_victim = r'\[punishment\][^\n]* uuid={uuid} [^\n]* victim actor={actor} incident=hurt response='
_punishment_witness = r'\[punishment\][^\n]* uuid={uuid} [^\n]* witness victim={victim} actor={actor} incident=hurt response={response} glass=true'
_smoke(_punishment_scene, 190, [
    _punishment_victim.format(uuid=_uuid_text(_punishment_scene, name), actor=_fear_holder) + response
    for name, response in (('a3_defy', 'DEFIANT'), ('a3_freeze', 'FREEZE'), ('a3_plead', 'PLEAD'))
], forbid=[rf'\[punishment\][^\n]* uuid={_uuid_text(_punishment_scene, "a3_screened")} '])
_punishment_smoke = next(s for s in S if s.id == _punishment_scene).smoke
_punishment_smoke['commands'] = [
    (44, 'execute positioned ~0.5 ~2.5 ~-0.5 run yukkuri scenario tool_operator enter'),
    (45, f'data modify entity {sel("a3_plead")} {OWNED}.Owner set from entity @p[name=MuseumOperator,distance=..16] UUID'),
    (46, 'execute as @e[type=minecraft:player,name=MuseumOperator,distance=..16,limit=1] at @s run function mcylab:extra/punishment_hit'),
    (47, 'execute as @e[type=minecraft:player,name=MuseumOperator,distance=..16,limit=1] at @s run function mcylab:extra/punishment_hit'),
    (90, 'execute as @e[type=minecraft:player,name=MuseumOperator,distance=..16,limit=1] at @s run function mcylab:extra/punishment_hit'),
    (145, 'yukkuri scenario tool_operator leave'),
]

def _verify_punishment(window):
    pet = _uuid_text(_punishment_scene, 'a3_plead')
    learning = re.findall(r'\[rules\][^\n]* uuid=(\S+) [^\n]* obedience reason=hurt owner=(\S+) shift=(-?\d+) -> (-?\d+)'
                          r' value=\d+ -> \d+ closeness=(-?\d+) fear=\d+', window)
    if learning != [(pet, _fear_holder, '0', '4', '0'), (pet, _fear_holder, '4', '8', '0')]:
        return f'Only the owned pet learns from the two accepted blows and keeps its closeness: {learning}'
    for victim, first in (('a3_freeze', 'FREEZE'), ('a3_plead', 'PLEAD'), ('a3_defy', 'DEFIANT')):
        hits = re.findall(_punishment_victim.format(uuid=_uuid_text(_punishment_scene, victim), actor=_fear_holder)
                          + r'(\w+) fear=(\d+) -> (\d+)', window)
        if len(hits) != 2 or int(hits[0][2]) == 0 or int(hits[1][1]) != int(hits[0][2]) or int(hits[1][2]) <= int(hits[1][1]):
            return f'{victim} must remember exactly two accepted blows from the same player: {hits}'
        if hits[0][0] != first:
            return f'{victim} must begin with {first}: {hits}'
        if victim == 'a3_defy' and hits[1][0] == 'DEFIANT':
            return f'{victim} must start defiant and become cautious: {hits}'
    for observer, victim, choice in (('a3_shrink', 'a3_plead', 'SHRINK'), ('a3_still', 'a3_freeze', 'FREEZE'),
                                     ('a3_kin', 'a3_defy', '(?:TURN_TO_KIN|PROTECT)')):
        pattern = _punishment_witness.format(uuid=_uuid_text(_punishment_scene, observer),
                                             victim=_uuid_text(_punishment_scene, victim), actor=_fear_holder, response=choice)
        if len(re.findall(pattern, window)) != 2:
            return f'{observer} must show {choice} through glass on both blows'
    parent, child = (_uuid_text(_punishment_scene, name) for name in ('a3_kin', 'a3_defy'))
    draw = rf'\[social\][^\n]* uuid={parent} t=(\d+)[^\n]* protection puff victim={child} attacker={_fear_holder} [^\n]* accepted=(true|false)'
    shown = rf'\[punishment\][^\n]* uuid={parent} t=(\d+)[^\n]* witness victim={child} actor={_fear_holder} incident=hurt response=PROTECT\b'
    draws = re.findall(draw, window)
    if len(draws) != 2 or [tick for tick, accepted in draws if accepted == 'true'] != re.findall(shown, window):
        return f'Expected one human-hit protective puff draw per blow and a puff for each accepted draw: {draws}'
    return _struck_speech(window)

_punishment_smoke['verify'] = _verify_punishment

_smoke('learned_holder', 500, [
    rf'\[threat\][^\n]* uuid={_uuid_text("learned_holder","witness")} [^\n]* fear learned holder={_fear_holder} [^\n]*incident=vibrate fear=0 -> 15',
    rf'\[threat\][^\n]* uuid={_uuid_text("learned_holder","witness")} [^\n]* fear learned holder={_fear_holder} [^\n]*incident=vibrate fear=15 -> 30',
    rf'\[threat\][^\n]* uuid={_uuid_text("learned_holder","witness")} [^\n]* learned retreat end result=away holder={_fear_holder}',
], forbid=[r'\[body\][^\n]* death settled',rf'\[threat\][^\n]* uuid={_uuid_text("learned_holder","screened")} [^\n]* fear learned'])
next(s for s in S if s.id == 'learned_holder').smoke['commands'] = [
    (44, f'execute at {sel("target")} positioned ~ ~ ~1 run yukkuri scenario tool_operator enter'),
    (46, f'execute at {sel("target")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("target")} vibrate'),
    (47, 'yukkuri scenario tool_operator leave'),
    (248, f'execute at {sel("target")} run tp {sel("witness")} ~2 ~ ~1'),
    (249, f'execute at {sel("target")} positioned ~ ~ ~1 run yukkuri scenario tool_operator enter'),
    (251, f'execute at {sel("target")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("target")} vibrate'),
    (255, f'execute at {sel("witness")} positioned ~ ~ ~1.2 run yukkuri scenario tool_operator move'),
    (450, 'yukkuri scenario tool_operator leave'),
]

_smoke('hook_kin_rescue', 380, [
    rf'\[interaction\][^\n]* uuid={_uuid_text(_hook_scene,"hook_child")} [^\n]* binding wound; pose=HOOK ',
    r'\[interaction\][^\n]* binding returned to world; [^\n]* item=mcyukkuri:hanging_hook count=1',
], forbid=[r'\[body\][^\n]* death settled'])

def _hook_rescue_attempt(window):
    child, parent = (_uuid_text(_hook_scene, role) for role in ('hook_child', 'hook_parent'))
    hook = re.search(rf'\[interaction\][^\n]* uuid={child} [^\n]* binding wound; pose=HOOK wound=([\w-]+)', window)
    if hook is None:
        return 'the child never hung from its own hook wound'
    identity = rf'\[social\][^\n]* uuid={parent} [^\n]* hook rescue '
    subject = rf'target={child} wound={hook[1]}'
    sequence = (identity + 'cue ' + subject + r'[^\n]*\n[\s\S]*?' +
                identity + 'attempt ' + subject + r'[^\n]*\n[\s\S]*?' +
                identity + 'end ' + subject + r' result=(?:released|no_reach)\b')
    if not re.search(sequence, window):
        return 'the parent did not approach and try this hook before release or giving up out of reach'

next(s for s in S if s.id == 'hook_kin_rescue').smoke['verify'] = _hook_rescue_attempt
next(s for s in S if s.id == 'hook_kin_rescue').smoke['commands'] = [
    (44, f'execute at {sel("hook_child")} positioned ~ ~ ~1.8 run yukkuri scenario tool_operator enter'),
    (46, f'execute at {sel("hook_child")} positioned ~ ~ ~1.8 run yukkuri scenario tool_click {sel("hook_child")} hook_hang'),
    (365, 'yukkuri scenario tool_operator leave'),
]

_smoke('learned_fire', 450, [
    r'\[body\][^\n]* burn remembered cause=hotFloor',
    r'\[body\][^\n]* hot floor escaped',
    rf'\[threat\][^\n]* uuid={_uuid_text("learned_fire","burnt")} [^\n]* learned retreat end result=away holder=null fire=',
], forbid=[r'\[body\][^\n]* death settled',rf'\[threat\][^\n]* uuid={_uuid_text("learned_fire","unburnt")} [^\n]* learned retreat'])
next(s for s in S if s.id == 'learned_fire').smoke['commands'] = [
    (125, f'execute at {sel("burnt")} run setblock ~2 ~ ~ minecraft:campfire'),
    (125, f'execute at {sel("unburnt")} run setblock ~2 ~ ~ minecraft:campfire'),
]
_scorn_executor = _uuid_text('hatless_scorn', 'drive_executor')
_scorn_meek = _uuid_text('hatless_scorn', 'drive_meek')
