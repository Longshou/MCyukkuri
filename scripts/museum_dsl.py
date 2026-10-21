"""Vocabulary of the playground scenarios: command-text helpers and real-individual spawn commands.

A scenario function runs ``positioned`` at its anchor (the block the individuals stand around), so every spawn
uses coordinates relative to it (``~dx ~ ~dz``). Nothing here touches the world; the functions only return
command strings that ``build_museum_full.py`` writes into the data pack.

Individuals are summoned the way the game would create them: ordinary AI, no NoAI, no invulnerability, no fixed
actor tag. Only the real starting state (hunger, tiredness, life stage, accessory, badge grade) is written into
their NBT; relationships, homes, conception and the like are set by ``yukkuri scenario ...`` afterwards.
"""
import hashlib
import json
import sys

sys.dont_write_bytecode = True

SCN = 'scn'                 # tag carried by every scenario individual, item and prop entity
MATURITY = 336000            # default loaded ticks from birth to maturity (family/GrowthTiming)
LIFESPAN = 1056000
# life stage -> (saved family stage, first age, next age, body scale)
STAGES = {'baby': ('BABY', 0, 72000, .18), 'child': ('JUVENILE', 72000, 216000, .40), 'teen': ('JUVENILE', 216000, 336000, .72)}


def _text(value, color='white'):
    return json.dumps({'text': value, 'color': color}, ensure_ascii=False, separators=(',', ':'))


def _q(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'"


def _label(x, y, z, value, scale=1.0):
    return (f'summon minecraft:text_display {x} {y} {z} {{Tags:["pm_label"],text:{_q(_text(value))},billboard:"center",alignment:"center",'
            f'line_width:300,background:1600000000,transformation:{{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],'
            f'scale:[{scale}f,{scale}f,{scale}f]}}}}')


def personality(intelligence=55, friendliness=70, boldness=55, attachment=60, self_control=70):
    """The five behavior axes in their authoritative saved order."""
    axes = (intelligence, friendliness, boldness, attachment, self_control)
    return ('Personality:{Version:1,Innate:[I;' + ','.join(str(v) for v in axes)
            + '],Experience:[I;0,0,0,0,0],ExperienceProgress:[I;0,0,0,0,0]}')


def uid(scenario, slot):
    """A fixed UUID (four signed ints) for one individual of one scenario, so that kin can be written into NBT before anything spawns."""
    digest = hashlib.md5(f'{scenario}/{slot}'.encode()).digest()
    return [int.from_bytes(digest[i:i + 4], 'big', signed=True) for i in range(0, 16, 4)]


def uuid_nbt(values):
    return '[I;' + ','.join(str(v) for v in values) + ']'


def family(stage, share=None, kin=''):
    """YukkuriFamily compound. ``kin`` is extra text such as ``Mother:[I;..],Father:[I;..]`` (real parents written into NBT)."""
    extra = (',' + kin) if kin else ''
    if stage in STAGES:
        name, start, end, scale = STAGES[stage]
        value = (start + end) // 2 if share is None else min(end - 1, max(start, int(MATURITY * share)))
        return (f'YukkuriFamily:{{Version:2,Initialized:1b,Stage:"{name}",Age:{value}L,Nourishment:{value}L,Maturity:{MATURITY}L,'
                f'Lifespan:{LIFESPAN}L,AdultScale:1f,Scale:{scale}f{extra}}}')
    return (f'YukkuriFamily:{{Version:2,Initialized:1b,Stage:"ADULT",Age:{MATURITY * 2}L,Nourishment:{MATURITY * 2}L,Maturity:{MATURITY}L,'
            f'Lifespan:{LIFESPAN}L,AdultScale:1f,Scale:1f{extra}}}')


def tag(name):
    return f'{SCN}_{name}'


def sel(name, radius=48):
    """The scenario individual with this tag (the function runs positioned at the anchor)."""
    return f'@e[tag={tag(name)},distance=..{radius},limit=1]'


def sel_all(name, radius=48):
    return f'@e[tag={tag(name)},distance=..{radius}]'


def yk(kind, dx, dz, name, *, dy=0, stage='adult', share=None, yaw=180, label=None, nutrition=90, energy=95, bladder=0, hygiene=100,
       rest_wait=1200, social_wait=2400, accessory=True, aquatic=False, grade=None, affection=None, boldness=55, lead=False,
       childhood=None, also=(), extra='', uuid=None, kin='', fam=None, form=None, congenital="NORMAL",
       intelligence=55, friendliness=70, attachment=60, self_control=70):
    """``summon`` for one real yukkuri. ``name`` is its selector tag (scn_<name>); ``label`` an optional visible custom name.
    grade: pet badge grade (1 bronze, 2 silver) written the way the shop writes a freshly issued badge.
    form: a subspecies kind (``SubspeciesLife.Kind``, e.g. LONGTAIL_CHEN) instead of NORMAL / AQUATIC_MARISA."""
    tags = [SCN, tag(name)] + ([tag('lead')] if lead else []) + [tag(t) for t in also]
    parts = ['Tags:[' + ','.join(f'"{t}"' for t in tags) + ']', 'PersistenceRequired:1b', f'Rotation:[{float(yaw)}f,0f]']
    if label:
        parts += [f'CustomName:{_q(_text(label))}', 'CustomNameVisible:1b']
    parts.append('YukkuriLife:{' + f'Nutrition:{nutrition}f,Energy:{energy}f,Hygiene:{hygiene}f,Bladder:{bladder}f,Accessory:{1 if accessory else 0}b,'
                 f'RestWait:{rest_wait},SocialWait:{social_wait},' + personality(intelligence, friendliness, boldness, attachment, self_control) + '}')
    parts.append('YukkuriFamily:{' + fam + (',' + kin if kin else '') + '}' if fam else family(stage, share, kin))
    if uuid:
        parts.append('UUID:' + uuid_nbt(uuid))
    parts.append('YukkuriSubspecies:{Initialized:1b,Kind:"' + (form or ('AQUATIC_MARISA' if aquatic else 'NORMAL')) + '",Congenital:"' + congenital + '",Deibu:0b}')
    if affection is not None:
        parts.append(f'YukkuriDailyLife:{{Affection:{affection},Boredom:20}}')
    if childhood:
        parts.append('YukkuriChildhood:{' + childhood + '}')
    if grade is not None:
        parts.append('YukkuriPet:{KnownSkills:63,Grade:' + str(grade) + ',Worn:1b,IssuedAt:0L,ExpiresAt:-1L,Source:3,'
                     'Cooldown:0,History:[],Permits:[]}')
    if extra:
        parts.append(extra)
    return f'summon mcyukkuri:{kind} ~{dx + .5} ~{dy} ~{dz + .5} {{' + ','.join(parts) + '}'


def remains(dx, dz, name, *, variant='reimu', food=6, capacity=6, rot=0, ident=None, partner=None, offered_to=None, kept=False,
            accessory=True, core_hurt=False):
    """Remains lying on the ground, in the form a real death leaves them (``RemainsEntity.snapshot``). ``rot`` is the decay clock
    (24000 stale, 72000 rotten); ``ident`` the dead one's UUID, which relatives recognise before making their own eating or guarding decision."""
    stage = 2 if rot >= 72000 else 1 if rot >= 24000 else 0
    data = [f'Variant:"{variant}"', f'Model:"{variant}"', f'FoodRemaining:{food}f', f'FoodCapacity:{capacity}f', f'Rot:{rot}L', f'RotStage:{stage}',
            f'Accessory:{1 if accessory else 0}b', f'Kept:{1 if kept else 0}b', 'RenderScale:1f', 'SourceScale:1f', 'DiedAt:0L',
            'Body:{CoreHurt:' + ('1b' if core_hurt else '0b') + '}']
    if ident:
        data.append('Identity:' + uuid_nbt(ident))
    if partner:
        data.append('Partner:' + uuid_nbt(partner))
    if offered_to:
        data += ['Offered:1b', 'OfferedTo:' + uuid_nbt(offered_to)]
    return (f'summon mcyukkuri:yukkuri_remains ~{dx + .5} ~ ~{dz + .5} {{Tags:["{SCN}","{tag(name)}"],'
            'Remains:{id:"mcyukkuri:yukkuri_remains",count:1,components:{"minecraft:custom_data":{' + ','.join(data) + '}}}}')


def human(kind, dx, dz, name, job, *, yaw=180, label=None):
    tags = ','.join(f'"{t}"' for t in (SCN, tag(name)))
    parts = [f'Tags:[{tags}]', 'PersistenceRequired:1b', f'Rotation:[{float(yaw)}f,0f]', f'HumanJob:{job}', 'HumanAttitude:1', 'HumanInitialized:1b']
    if label:
        parts += [f'CustomName:{_q(_text(label))}', 'CustomNameVisible:1b']
    return f'summon mcyukkuri:{kind} ~{dx + .5} ~ ~{dz + .5} {{' + ','.join(parts) + '}'


def item(item_id, count, dx, dz, name='item', dy=.05):
    """A loose stack on the ground, collected by ordinary pickup goals (or by the player)."""
    return (f'summon minecraft:item ~{dx + .5} ~{dy} ~{dz + .5} {{Tags:["{SCN}","{tag(name)}"],PickupDelay:0s,Motion:[0d,0d,0d],'
            f'Item:{{id:"{item_id}",count:{count}}}}}')


def block(dx, dy, dz, state):
    return f'setblock ~{dx} ~{dy} ~{dz} {state}'


def box(dx1, dy1, dz1, dx2, dy2, dz2, state, mode=''):
    return f'fill ~{dx1} ~{dy1} ~{dz1} ~{dx2} ~{dy2} ~{dz2} {state}' + (' ' + mode if mode else '')


def shed(dx, dz, half=2, height=3):
    """A small roofed shelter with a yukkuri nest in its middle (a nest needs a covered neighbouring standing cell)."""
    out = []
    for px in (dx - half, dx + half):
        for pz in (dz - half, dz + half):
            out.append(box(px, 0, pz, px, height - 1, pz, 'minecraft:oak_log'))
    out.append(box(dx - half, height, dz - half, dx + half, height, dz + half, 'minecraft:oak_planks'))
    out.append(block(dx, 0, dz, 'mcyukkuri:yukkuri_nest'))
    return out


def home(name, dx, dz):
    return f'yukkuri scenario home {sel(name)} ~{dx} ~ ~{dz}'


def say(text, color='aqua', radius=120):
    """Message to the players near the anchor (the scenario's own observer)."""
    return f'tellraw @a[distance=..{radius}] {_text(text, color)}'


def ticks(seconds):
    return int(seconds * 20)
