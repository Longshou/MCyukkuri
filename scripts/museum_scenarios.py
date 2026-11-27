"""Scenarios of the playground: one question each, answered by real individuals under their ordinary AI.

A scenario is a list of commands run positioned at the scenario's anchor block (see museum_dsl). It only creates the
individuals and props and sets their real starting state (hunger, tiredness, life stage, kin, home, conception, a
wound, a hat set down ...). After that nothing is scripted, fixed or invulnerable: the observer watches, speeds the
clock up, flies to the lead, or clears the field and starts again.

Fields hold the terrain (museum_fields.py); each scenario names its field and the spot of that field it stands around.
The ``TOPICS`` mapping at the end connects user concerns to scenarios; docs/TODO.md owns progress and completion criteria.
Scenario definitions and readouts live here; AGENTS.md covers navigation and shared operations.
"""
import re
import json
import math
import sys
import uuid
from itertools import pairwise
from pathlib import Path

sys.dont_write_bytecode = True
from museum_dsl import (yk, yk_on_nest, human, item, block, large_nest, box, shed, furniture, home, sel, sel_all, tag, say, uid, uuid_nbt, family, remains, aquatic_spawn, _text)  # noqa: E402
from museum_fields import FIELDS, BY_ID, FEET, den  # noqa: E402

# Scene numbers: the field's code and a two-digit index (``PD-03``), so a visitor can name a scene out loud. The file is the
# registry of them: its ``numbers`` entries are appended in field order and are never reordered or renumbered, so a scene
# keeps its number when other scenes are added, removed or the deck is reordered (a removed scene keeps its line and its
# number is not given to another one). Field codes are fixed there too, one per field.
NUMBERS = json.loads((Path(__file__).with_name('museum_scene_numbers.json')).read_text(encoding='utf-8'))

# Landed and fed, with newborn care finished. Crawling development determines whether it can jump.
CHILDHOOD_CRAWLING_READY = 'Version:2,Newborn:1b,Landed:1b,FirstMealDone:1b,FirstToiletDone:1b,Crawled:0b'
CHILDHOOD_HOPPING = 'Version:2,Newborn:1b,Landed:1b,FirstMealDone:1b,FirstToiletDone:1b,Crawled:1b,CrawlDistance:0.75d'
OWNED = 'NeoForgeData.HumanCommerce'
# A subadult in the fixed age interval, ready to live independently.
TEEN_FAMILY = ('Version:2,Initialized:1b,Stage:"JUVENILE",Age:276000L,Nourishment:276000L,Maturity:336000L,Lifespan:1056000L,AdultScale:1f,Scale:0.72f')
LATE_COMMANDS = ('yukkuri scenario home', 'yukkuri scenario twigs', 'yukkuri scenario birth', 'yukkuri scenario eggs', 'yukkuri scenario afloat',
                 'yukkuri scenario sumo')
PENDING = '修复中'     # a scenario whose helper behaviour is not wired in yet: the sign says so, the button and terrain stay
# A rider crushed into a block is never a pond outcome: whichever scene has a young on a hat brim, its smoke window forbids the death.
_POND_SMOKE_FORBID = r'\[body\][^\n]* death settled [^\n]*cause=inWall\b'


class Retry:
    """A late command that a refusal does not end: after ``first`` ticks it is tried every ``every`` ticks, at most ``tries`` times,
    until the game accepts it. ``guard`` is an entity selector that must match before an attempt is made (the way a refusal is
    kept off the chat: the attempt waits until the thing it needs, say a mother seated in her boat, is really there)."""
    def __init__(self, command, guard, tries=16, every=50, first=60):
        self.command, self.guard, self.tries, self.every, self.first = command, guard, tries, every, first


class Scenario:
    def __init__(self, sid, field, title, hint, watch, wait, lines, give=(), env=None, spot=None, retry='按 M，在「实用」里重开',
                 asks='', note='', sub=None, vantage=(0, 14), reads=(), late=(), at_spawn=(), smoke=None, capture=None):
        self.id, self.field, self.title, self.hint = sid, field, title, hint
        # self.number is set once every scenario exists (``_number_scenes``), so that the pond tour order is known.
        self.watch, self.wait, self.retry, self.asks, self.note = watch, wait, retry, asks, note
        self.lines, self.give, self.env = surface_demo_lines(sid,lines), list(give), env or {}
        # settings that need the individuals to stand on the ground (a conception, a boat, a landing-dependent survey) run two seconds after the start hold is released;
        # `at_spawn` is only for commands that need no grounded body (a home works on a just-summoned one)
        deferred = [i for i, line in enumerate(self.lines) if line.startswith(LATE_COMMANDS)]
        self.late_lines = [self.lines[i] for i in deferred]
        # ``at_spawn``: commands run in the same tick as the summons, so the setting is in force before the first plan
        # (a home a scene gives its bodies, which would otherwise first head for the nearest free nest)
        self.lines = [line for i, line in enumerate(self.lines) if i not in deferred] + surface_demo_lines(sid, at_spawn)
        f = BY_ID[field]
        self.anchor = f.spots[spot] if spot else f.anchor
        self.plot = f.plot_rect(*self.anchor)
        self.sub = sub                 # name of the plot cleared before this scenario (default: the whole field)
        self.vantage = vantage         # (dx, dz[, yaw[, pitch[, dy]]]), using terrain height unless dy is given
        self.reads = list(reads)       # (caption, command) readouts that belong to this scenario
        self.smoke = smoke             # optional {ticks, expect, forbid}; runner only, never emitted into the pack
        if field == 'pond' and self.smoke is not None:
            self.smoke = {**self.smoke, 'forbid': [*self.smoke.get('forbid', ()), _POND_SMOKE_FORBID]}
        self.capture = capture if capture is not None else dict(shots=(1, 40, 200, 600), sequences=((60, 160, 2),))
        self.late = self.late_lines + surface_demo_lines(sid,late)   # commands that need the individuals to stand on the ground first: run two seconds after the release
        self.layout_ticks = max([40] + [40 + r.first + (r.tries - 1) * 20 for r in self.late if isinstance(r, Retry) and r.command.startswith('summon ')])

    @property
    def label(self):
        """How the scene is written wherever the player reads it: its number, then the name."""
        return f'{self.number} {self.title}'

    @property
    def tag(self):
        return PENDING if self.note.startswith(PENDING) else ('待验证' if self.note.startswith('待验证') else '')

    def body(self):
        """Commands of ``scn/<id>``: heading, the scenario itself, items for the player."""
        head = [say(f'【{self.label}】看点：{self.watch}', 'aqua'), say(f'预计：{self.wait}。没反应：{self.retry}。', 'yellow')]
        if self.note:
            head.append(say(f'注意：{self.note}', 'red'))
        return head + self.lines + [f'give @s {g}' for g in self.give]


def surface_demo_lines(sid, lines):
    """Preset skin demonstrations face the observer; explicit body-region comparisons retain their regions."""
    result = []
    comparisons = {'wound_materials', 'thermal_body', 'wound_sources', 'wound_leak', 'hatless_monster', 'core_exposed'}
    for line in (part for entry in lines for part in (entry.splitlines() if isinstance(entry, str) else [entry])):
        if not isinstance(line,str):
            result.append(line)
            continue
        match = re.match(r'(yukkuri status set .+?) (marks\.(?:dirt|scars)|health\.mold) ',line)
        if match:
            result.append(match[1] + ' marks.face true')
        if sid not in comparisons and ' health.wound_at ' in line:
            line = re.sub(r'( health.wound_at )(side|back|top):',r'\1front:',line)
        result.append(line)
    return result


def own(name):
    """Make the executing player the owner of this scenario pet (a companion), the way the shop writes it."""
    return [f'data modify entity {sel(name)} {OWNED} set value {{Use:"companion"}}',
            f'data modify entity {sel(name)} {OWNED}.Owner set from entity @s UUID']


def pair_ready():
    return dict(nutrition=100, energy=100, hygiene=100)


S = []


def add(*args, **kw):
    scenario = Scenario(*args, **kw)
    S.append(scenario)
    BY_ID[scenario.field].scenarios.append(scenario)
    return scenario


def _uuid_text(sid, slot):
    return str(uuid.UUID(bytes=b''.join((n & 0xffffffff).to_bytes(4, 'big') for n in uid(sid, slot))))


DAY = {'time': 'noon'}
LEAD_ANY = 'tag @e[type=#mcylab:yukkuri,distance=..70,limit=1,sort=random] add scn_lead'


def kin_of(**parents):
    return ','.join(f'{role}:{uuid_nbt(u)}' for role, u in parents.items())


def set_status(name, key, value):
    return f'yukkuri status set {sel(name)} {key} {value}'

_PARTS_DIR = Path(__file__).with_name('museum_scenarios_parts')
_PARTS = (
    '01_wild_shelters.py',
    '02_vagrants_foraging.py',
    '03_settlements_meadow.py',
    '04_family_growth.py',
    '05_weather_predators.py',
    '06_house_town_street.py',
    '07_town_park_shop.py',
    '08_new_round_colony.py',
    '09_pond_core.py',
    '10_pond_life_topics.py',
    '11_smoke_verifiers_a.py',
    '12_smoke_verifiers_b.py',
    '13_fates_and_ends.py',
)
for _part_name in _PARTS:
    _part_path = _PARTS_DIR / _part_name
    exec(compile(_part_path.read_text(encoding='utf-8'), str(_part_path), 'exec'), globals())
del _part_name, _part_path
