"""Scene vocabulary for the showcase museum: stand-local coordinates, actors and scripted steps.

A stand is a small stage (11 wide, 6 deep) that opens onto the hall aisle. Positions are given in the
viewer's frame so a scene reads the same in the north row and in the south row of a hall:

    u  across the stage, 0 (viewer's left) .. 11 (viewer's right)
    v  depth, 0 at the stage front edge, growing away from the viewer (actors normally stand at v=3)
    d  facing: F toward the viewer, B away, L toward the viewer's left, R toward the viewer's right

Nothing here touches the world; a ``Ctx`` only collects data that ``build_museum_full.py`` turns into
a data pack. Every scene command is a tuple ``(kind, actor_key, name, command)`` so the generator can
check clips, speech lines and delays before it writes a single file.
"""
import math
import sys
import json
from pathlib import Path

sys.dont_write_bytecode = True
from museum_actions import ACTION_META  # noqa: E402

STAGE_Y = 66

# viewer frame -> yaw / cardinal name, per row (north row: viewer in the south; south row: viewer in the north)
YAW = {'N': {'F': 0, 'B': 180, 'R': -90, 'L': 90}, 'S': {'F': 180, 'B': 0, 'R': 90, 'L': -90}}
CARDINAL = {'N': {'F': 'south', 'B': 'north', 'R': 'east', 'L': 'west'}, 'S': {'F': 'north', 'B': 'south', 'R': 'west', 'L': 'east'}}

# growth presets: (stage, nourishment ratio of maturity, scale).  A huge maturity keeps the ratio fixed for the whole visit.
GROWTH = {'baby': ('BABY', .15, .18), 'child': ('JUVENILE', .5, .40), 'teen': ('JUVENILE', .9, .72)}
BIG = 1_000_000_000
BODY_GEOMETRY = json.loads((Path(__file__).resolve().parents[1] /
                           'src/main/resources/assets/mcyukkuri/yukkuri_presentation/body_geometry.json').read_text(encoding='utf-8'))['variants']


def ticks(action, minimum=20):
    """Time a clip needs; looping clips get two cycles so the loop is seen."""
    seconds, loops = ACTION_META[action]
    if loops:
        return max(minimum, min(200, math.ceil(seconds * 20) * 2))
    return max(minimum, math.ceil(seconds * 20))


class Ctx:
    """Everything one stand needs to describe itself."""

    def __init__(self, sid, row, x0, z0, color):
        self.sid, self.row, self.x0, self.z0, self.color = sid, row, x0, z0, color
        self.sx = x0 + 2
        self.actors = {}       # key -> spec
        self.props = []        # world commands run once at build time
        self.demos = []        # (demo id, caption, steps)
        self.buttons = []      # (caption, hint, function name)
        self.functions = {}    # extra function name -> command lines
        self.stop_extra = []   # commands run by this stand's stop function
        self.stop_before = []  # cancel server-driven scenes before actor positions are restored
        self.water = False
        self.meadow = None     # (x, y, z) of the outdoor meadow, set by the generator for the settlement stand
        self.number, self.title, self.question, self.hall = 0, sid, '', None   # set by the generator
        self.yard_cast = False    # the cast lives in a courtyard (real families): never reset by the museum
        self.kind = 'view'     # 'view' glass front, 'open' fence with a gate (visitors walk onto the stage), 'portal' nothing on stage
        self.guard_live = False   # a demo button refuses while real behaviour runs here (otherwise it resets first)
        self.batch = True      # included in the lobby "reset all" (False for real families, yards and boats)
        self.reset_life = False   # restore life stats of the stage cast on reset
        self.sites = []        # remote yards: dict(id, caption, hint, land, rect, load, release)
        self.regions = []      # (x1, z1, x2, z2) rectangles outside the hall that must be loaded while building
        self.env = {}          # time / weather / biome presets of this stand
        self.info = {}         # what to watch / what to press / how long / what if nothing happens

    # ---------------------------------------------------------------- geometry
    def xz(self, u, v):
        """Continuous position. u is measured across the stage (cell bu spans u in [bu, bu+1)); v is counted in cells
        from the front row, so v=3 is the middle of cell bv=3. Always floats: an integer in a command means block centre."""
        v = v + .5
        if self.row == 'N':
            return round(float(self.sx + u), 3), round(float(self.z0 + 8 - v), 3)
        return round(float(self.sx + 11 - u), 3), round(float(self.z0 + 24 + v), 3)

    def cell(self, bu, bv):
        """Block coordinates of the stage cell (bu 0..10, bv 0..5)."""
        if self.row == 'N':
            return self.sx + bu, self.z0 + 7 - bv
        return self.sx + 10 - bu, self.z0 + 24 + bv

    def yaw(self, d):
        return YAW[self.row][d] if isinstance(d, str) else round(d, 3)

    def facing(self, d):
        return CARDINAL[self.row][d]

    def tag(self, key):
        return f'pm_{self.sid}_{key}'

    def sel(self, key):
        return f'@e[tag={self.tag(key)}]'

    # ---------------------------------------------------------------- actors
    def actor(self, key, kind, u, v, d, name, grade=0, stage='adult', aquatic=False, job=None, named=True, y=STAGE_Y):
        """kind: a yukkuri model (reimu ...), or caretaker_brother / caretaker_sister / oniisan."""
        human = kind.startswith('caretaker') or kind == 'oniisan'
        x, z = self.xz(u, v)
        self.actors[key] = dict(key=key, sid=self.sid, kind=kind, human=human, x=x, y=y, z=z, yaw=self.yaw(d), name=name, grade=grade,
                                stage=stage, aquatic=aquatic, job=job, named=named, model=kind, row=self.row,
                                tag=self.tag(key), room=self.sid)
        return key

    def ring(self, bu0, bv0, bu1, bv1, gates=None, block='minecraft:oak_fence', gate_block='minecraft:oak_fence_gate', dy=0, height=1):
        """Fence ring over stage cells with explicit connections (setblock/fill never connect fences by themselves).
        gates: {(bu, bv): direction} -- the gate panel faces that direction."""
        gates = gates or {}
        cells = {}
        for bu in range(bu0, bu1 + 1):
            for bv in range(bv0, bv1 + 1):
                if bu in (bu0, bu1) or bv in (bv0, bv1):
                    cells[self.cell(bu, bv)] = (bu, bv)
        gate_xz = {self.cell(*g): d for g, d in gates.items()}
        for (x, z) in cells:
            if (x, z) in gate_xz:
                for h in range(height):
                    self.props.append(f'setblock {x} {STAGE_Y + dy + h} {z} {gate_block}[facing={self.facing(gate_xz[(x, z)])}]')
                continue
            flags = []
            for name, (dx, dz) in (('north', (0, -1)), ('south', (0, 1)), ('east', (1, 0)), ('west', (-1, 0))):
                if (x + dx, z + dz) in cells:
                    flags.append(f'{name}=true')
            for h in range(height):
                self.props.append(f'setblock {x} {STAGE_Y + dy + h} {z} {block}[{",".join(flags)}]')

    def home(self, key):
        a = self.actors[key]
        return (a['x'], a['y'], a['z'], a['yaw'])

    # ---------------------------------------------------------------- commands
    def A(self, key, action):
        return ('action', key, action, f'yukkuri action {self.sel(key)} {action}')

    def S(self, key, event):
        return ('say', key, event, f'yukkuri say {self.sel(key)} {event}')

    def raw(self, command):
        return ('raw', None, None, command)

    def FACE(self, key, d):
        if not self.actors[key]['human']:
            return self.raw(f'yukkuri museum face {self.sel(key)} {self.yaw(d)}')
        return self.raw(f'execute as {self.sel(key)} at @s run tp @s ~ ~ ~ {self.yaw(d)} 0')

    def width(self, key):
        a = self.actors[key]
        scale = GROWTH[a['stage']][2] if a['stage'] in GROWTH else .43 if a['stage'] == 'house_baby' else 1
        base = .27 if a['kind'].startswith('house_') else .8 if a['kind'] in ('remilia', 'flandre') else .75
        if a['kind'] in BODY_GEOMETRY:
            bounds = BODY_GEOMETRY[a['kind']]['bounds'][0]
            base = (bounds[1] - bounds[0]) / 16
        return base * scale

    def contact_distance(self, first, second):
        """Same standing envelope as FamilyFeedingContact; the renderer closes the mouth gap."""
        return round((self.width(first) + self.width(second)) / 2 + .05, 4)

    def CONTACT(self, first, second, kind='nuzzle'):
        """Explicit partner for the production contact pose, without granting care/relationship rewards."""
        assert kind in ('nuzzle', 'feed', 'rear')
        return ('contact', first, (second, kind),
                f'yukkuri museum contact @e[tag={self.tag(first)},limit=1] @e[tag={self.tag(second)},limit=1] {kind}')

    def contact_clips(self, first, second, kind):
        parent = ('boat_nuzzle' if self.water else 'nuzzle') if kind == 'nuzzle' else (
            'nuzzle' if kind == 'rear' else 'boat_feed_young' if self.water else 'eat')
        return [(first, parent), (second, 'nuzzle' if kind == 'nuzzle' else 'brace' if kind == 'rear' else 'eat')]

    def centre(self):
        return self.xz(5.5, 3)

    def near(self, radius=40):
        """Selector of the players standing at this stand. Scheduled functions run at the world spawn, so the
        origin is written into the selector instead of relying on the executing position."""
        x, z = self.centre()
        return f'@a[x={x},y={STAGE_Y},z={z},distance=..{radius}]'

    def NOTE(self, text):
        return self.raw(f'title {self.near()} actionbar ' + _text(text, 'gold'))

    def SAY(self, text, color='aqua'):
        return self.raw(f'tellraw {self.near()} ' + _text(text, color))

    def PROGRESS(self, seen, total, label=''):
        text = f'已观察 {seen} / {total} 秒' + (f' · {label}' if label else '')
        return self.raw(f'title {self.near()} actionbar ' + _text(text, 'yellow'))

    def live_run(self, setup, seconds, note=None, label='', every=15):
        """Steps of one real-behaviour observation window. The window only reports progress; nothing is stopped
        or rolled back at its end, so the result of the real behaviour stays on the stage."""
        steps = [(every * 20, list(setup) + ([self.SAY(note)] if note else []) + [self.PROGRESS(0, seconds, label)])]
        for seen in range(every, seconds, every):
            steps.append((every * 20, [self.PROGRESS(seen, seconds, label)]))
        return steps

    def BADGE(self, key, grade):
        return ('badge', key, grade, f'execute as {self.sel(key)} unless data entity @s YukkuriPet{{Grade:{grade}}} '
                                     f'run data modify entity @s YukkuriPet.Grade set value {grade}')

    def FX(self, particle, u, v, dy=1.0, spread=(.25, .25, .25), speed=.02, count=8):
        x, z = self.xz(u, v)
        return self.raw(f'particle minecraft:{particle} {x} {STAGE_Y + dy} {z} {spread[0]} {spread[1]} {spread[2]} {speed} {count}')

    def CARD(self, name, text, color='white'):
        return self.raw(f'data merge entity @e[tag=pm_{self.sid}_card_{name},limit=1] {{text:{_q(_text(text, color))}}}')

    def walk(self, key, u0, u1, v, d, steps=7, carry=None, carry_dy=1.05, step_ticks=4, y=STAGE_Y, v1=None, carry_du=0):
        """One navigation request; generated step functions wait for physical arrival."""
        v1 = v if v1 is None else v1
        x1, z1 = self.xz(u1, v1)
        return [(2, [self.raw(f'yukkuri museum walk {self.sel(key)} {x1} {y} {z1}')])]

    def LIVE(self, key, need='observe'):
        return self.raw(f'yukkuri museum live {self.sel(key)} {need}')

    def HOME(self, key, bu, bv):
        x, z = self.cell(bu, bv)
        return self.raw(f'yukkuri museum home @e[tag={self.tag(key)},limit=1] {x} {STAGE_Y} {z}')

    def PARENT(self, parent, child, first_toilet=False):
        return self.raw(f'yukkuri museum parent @e[tag={self.tag(parent)},limit=1] '
                        f'@e[tag={self.tag(child)},limit=1] {str(first_toilet).lower()}')

    def SUPPLY(self, name, item, u, v, count=1):
        """Finite real world items. Their ordinary goals own pickup, carrying and consumption."""
        x, z = self.xz(u, v)
        return self.raw(f'summon minecraft:item {x} {STAGE_Y + .05} {z} '
                        f'{{Tags:["pm_supply","pm_{self.sid}_fx","{self.sid}_{name}"],PickupDelay:0s,'
                        f'Item:{{id:"{item}",count:{count}}},Motion:[0d,0d,0d]}}')

    # ---------------------------------------------------------------- scenes
    def seq(self, key, *actions):
        return [(ticks(a), [self.A(key, a)]) for a in actions]

    def demo(self, did, caption, steps):
        self.demos.append((did, caption, steps))

    def button(self, caption, hint, fname, nav=False):
        """A floor button of the stand's first row. ``nav`` runs the function as the nearest player (teleports)."""
        self.buttons.append((caption, hint, fname, nav))

    def function(self, name, lines):
        self.functions[name] = lines

    def prop(self, command):
        self.props.append(command)

    def block(self, bu, bv, block, dy=0, state=''):
        x, z = self.cell(bu, bv)
        self.props.append(f'setblock {x} {STAGE_Y + dy} {z} {block}{state}')

    def block_facing(self, bu, bv, block, d, dy=0, extra=''):
        x, z = self.cell(bu, bv)
        self.props.append(f'setblock {x} {STAGE_Y + dy} {z} {block}[facing={self.facing(d)}{extra}]')

    def fill_cells(self, bu0, bv0, bu1, bv1, block, dy=0):
        x0, z0 = self.cell(bu0, bv0)
        x1, z1 = self.cell(bu1, bv1)
        self.props.append(f'fill {min(x0, x1)} {STAGE_Y + dy} {min(z0, z1)} {max(x0, x1)} {STAGE_Y + dy} {max(z0, z1)} {block}')

    def label(self, u, v, dy, text, scale=.45):
        x, z = self.xz(u, v)
        self.props.append(_label(x, STAGE_Y + dy, z, text, scale))

    def card(self, name, u, v, dy, text, color='yellow'):
        x, z = self.xz(u, v)
        self.props.append(f'summon minecraft:text_display {x} {STAGE_Y + dy} {z} {{Tags:["pm_label","pm_{self.sid}_card_{name}"],text:{_q(_text(text, color))},'
                          f'billboard:"center",alignment:"center",line_width:200,background:1073741824,transformation:{{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],'
                          f'right_rotation:[0f,0f,0f,1f],scale:[.7f,.7f,.7f]}}}}')

    def item(self, item, u, v, dy, scale=.9):
        x, z = self.xz(u, v)
        self.props.append(f'summon minecraft:item_display {x} {STAGE_Y + dy} {z} {{Tags:["pm_prop"],item:{{id:"{item}",count:1}},item_display:"fixed",'
                          f'billboard:"vertical",transformation:{{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[{scale}f,{scale}f,{scale}f]}}}}')

    def basket(self, u, v):
        """Floor-standing carrier: its fixed-display scale must fit an adult, not an inventory icon."""
        import json
        from pathlib import Path
        mesh = json.loads((Path(__file__).resolve().parents[1] /
                           'art/props/runtime-peripheral/selected/yukkuri_carrier/yukkuri_carrier.mesh.json').read_text(encoding='utf-8'))
        ys = mesh['vertices'][1::8]
        scale = 2.4  # item fixed transform is .75, leaving a full-size adult opening
        self.item('mcyukkuri:yukkuri_carrier', u, v, round((max(ys) - min(ys)) / 32 * .75 * scale, 5), scale)
        self.props[-1] = self.props[-1].replace('billboard:"vertical"', 'billboard:"fixed"')

    def water_floor(self):
        self.water = True


# ------------------------------------------------------------------ small text helpers (kept local to avoid a build dependency)
def _text(value, color='white'):
    import json
    return json.dumps({'text': value, 'color': color}, ensure_ascii=False, separators=(',', ':'))


def _q(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'"


def _label(x, y, z, value, scale=1.0):
    return (f'summon minecraft:text_display {x} {y} {z} {{Tags:["pm_label"],text:{_q(_text(value))},billboard:"center",alignment:"center",'
            f'line_width:300,background:1600000000,transformation:{{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],'
            f'scale:[{scale}f,{scale}f,{scale}f]}}}}')


# ------------------------------------------------------------------ entities
def _personality(boldness=55):
    return ('Personality:{Version:2,Innate:[I;55,55,' + str(boldness) + ',55,55,55],Experience:[I;0,0,0,0,0,0],Lessons:3,'
            'DispositionInnate:[I;30,40,60,70],DispositionExperience:[I;0,0,0,0],DispositionProgress:[I;0,0,0,0],'
            'NextExperience:[L;0L,0L,0L,0L],Players:[]}')


def _family(stage):
    if stage in GROWTH:
        name, ratio, scale = GROWTH[stage]
        value = int(BIG * ratio)
        return (f'YukkuriFamily:{{Version:2,Initialized:1b,Stage:"{name}",Age:{value}L,Nourishment:{value}L,Maturity:{BIG}L,'
                f'Lifespan:{2 * BIG}L,AdultScale:1f,Scale:{scale}f}}')
    if stage == 'house_baby':
        value = int(BIG * .15)
        return (f'YukkuriFamily:{{Version:2,Initialized:1b,Stage:"BABY",Age:{value}L,Nourishment:{value}L,Maturity:{BIG}L,'
                f'Lifespan:{2 * BIG}L,AdultScale:1f,Scale:.43f}}')
    return ('YukkuriFamily:{Version:2,Initialized:1b,Stage:"ADULT",Age:48000L,Nourishment:48000L,Maturity:24000L,'
            'Lifespan:2000000000L,AdultScale:1f,Scale:1f}')


def summon_command(spec):
    """/summon for a museum actor: fixed, invulnerable, persistent and (for yukkuri) a plain adult/young body."""
    tags = '["pm_actor","pm_' + spec['sid'] + '","' + spec['tag'] + '","room_' + spec['sid'] + '"]'
    name = spec['name']
    parts = [f'Tags:{tags}', 'PersistenceRequired:1b', 'NoAI:1b', 'Invulnerable:1b', f'Rotation:[{spec["yaw"]}f,0f]']
    if name:
        parts += [f'CustomName:{_q(_text(name))}', f'CustomNameVisible:{1 if spec["named"] else 0}b']
    kind = spec['kind']
    if spec['human']:
        job = spec.get('job')
        if job is not None:
            parts += [f'HumanJob:{job}', 'HumanAttitude:1', 'HumanInitialized:1b']
    else:
        pet = ('YukkuriPet:{Abilities:[I;70,60,70,60],Routines:[I;6,6,6],Grade:' + str(spec['grade']) + ',Worn:1b,IssuedAt:0L,ExpiresAt:-1L,Source:3,'
               'PedigreeSet:1b,Pedigree:65,Cooldown:0,History:[],Permits:[]}')
        parts += ['YukkuriLife:{Nutrition:90f,Energy:95f,Hygiene:100f,Bladder:0f,Accessory:1b,RestWait:1200,SocialWait:2400,' + _personality() + '}',
                  _family(spec['stage']),
                  'YukkuriSubspecies:{Initialized:1b,Kind:"' + ('AQUATIC_MARISA' if spec['aquatic'] else 'NORMAL') + '",Congenital:"NORMAL",Deibu:0b}', pet]
    return f'summon mcyukkuri:{kind} {spec["x"]} {spec["y"]} {spec["z"]} {{' + ','.join(parts) + '}'


def wild_command(kind, x, y, z, tags, name):
    """A real, free wild adult (normal AI) for the hands-on pen."""
    tag_list = ','.join(f'"{t}"' for t in tags)
    return (f'summon mcyukkuri:{kind} {x} {y} {z} {{Tags:[{tag_list}],PersistenceRequired:1b,CustomName:{_q(_text(name))},CustomNameVisible:1b,'
            'YukkuriLife:{Nutrition:90f,Energy:95f,Hygiene:100f,Bladder:0f,Accessory:1b,RestWait:1200,SocialWait:2400},'
            'YukkuriFamily:{Version:2,Initialized:1b,Stage:"ADULT",Age:48000L,Nourishment:48000L,Maturity:24000L,Lifespan:2000000000L,AdultScale:1f,Scale:1f},'
            'YukkuriSubspecies:{Initialized:1b,Kind:"NORMAL",Congenital:"NORMAL",Deibu:0b}}')
