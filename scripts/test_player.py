"""Headless live player checks. Build with scripts/build_preview_hub.py first."""
from contextlib import contextmanager
from base64 import b64decode
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from html.parser import HTMLParser
import json
from math import ceil, floor
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.parse import urljoin
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'.local/previews'
LAUNCH = """
import _thread
import sys
import threading
import time
import serve_review
import review_verdicts
from pathlib import Path

review_verdicts.LOG = Path(sys.argv.pop(1))
shutdown = review_verdicts.LOG.with_name('stop')

def stop():
    while not shutdown.exists():
        time.sleep(0.05)
    _thread.interrupt_main()

threading.Thread(target=stop, daemon=True).start()
serve_review.main()
"""


def fitted_sections(model_id):
    from build_review_catalog import ASSETS
    from placement_fit import fitted

    model = fitted(ASSETS/f'yukkuri/{model_id}.json')
    return {part['name']: (part['start'], part['count']) for part in model['parts']
            if not part.get('anatomy') and not part['name'].startswith('face_')}


def surface_depths(vertices, spans, select):
    """Sample posed triangles on lateral rays spaced 1/20 model unit apart."""
    hits = {}
    for start, count in spans:
        for i in range(start, start + count, 3):
            a, b, c = vertices[i:i+3]
            u = [end - origin for end, origin in zip(b, a)]
            v = [end - origin for end, origin in zip(c, a)]
            det = u[0]*v[1] - u[1]*v[0]
            if abs(det) < 1e-8:
                continue
            for x in range(ceil(20*min(a[0], b[0], c[0])), floor(20*max(a[0], b[0], c[0])) + 1):
                for y in range(ceil(20*min(a[1], b[1], c[1])), floor(20*max(a[1], b[1], c[1])) + 1):
                    dx, dy = x/20 - a[0], y/20 - a[1]
                    s = (dx*v[1] - dy*v[0])/det
                    t = (u[0]*dy - u[1]*dx)/det
                    if s < 0 or t < 0 or s + t > 1:
                        continue
                    z = a[2] + s*u[2] + t*v[2]
                    hits[x, y] = select(z, hits.get((x, y), z))
    return hits


def drawn_spacing(first, second):
    return min(first[ray] - second[ray] for ray in first.keys() & second.keys())


def lateral_vertices(raw, spacing):
    return [(z, y, x-spacing) for x, y, z, *_ in struct.iter_unpack('<8f', raw)]


class Document(HTMLParser):
    void = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta',
            'param', 'source', 'track', 'wbr'}

    def __init__(self):
        super().__init__()
        self.tags, self.ids, self.assets = [], set(), []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag not in self.void:
            self.tags.append(tag)
        if 'id' in attrs:
            assert attrs['id'] not in self.ids, attrs['id']
            self.ids.add(attrs['id'])
        if tag == 'script' and 'src' in attrs:
            self.assets.append(attrs['src'])
        if tag == 'link' and attrs['rel'] == 'stylesheet':
            self.assets.append(attrs['href'])

    def handle_endtag(self, tag):
        assert self.tags.pop() == tag, tag


class PlayerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads((OUT/'entries.json').read_text(encoding='utf-8'))
        scratch = ROOT/'.local/tmp'
        scratch.mkdir(parents=True, exist_ok=True)
        cls.tmp = Path(tempfile.mkdtemp(prefix='test-player-', dir=scratch))
        log = (cls.tmp/'server.log').open('w', encoding='utf-8')
        cls.addClassCleanup(log.close)
        cls.verdicts = cls.tmp/'verdicts.jsonl'
        cls.server = subprocess.Popen([sys.executable, '-u', '-c', LAUNCH, str(cls.verdicts), '--port', '0'],
                                      cwd=ROOT/'scripts', stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                      stderr=log, text=True, encoding='utf-8')
        cls.addClassCleanup(cls.stop_server)
        url = cls.server.stdout.readline()
        if not url.startswith('http://127.0.0.1:'):
            raise RuntimeError((cls.tmp/'server.log').read_text(encoding='utf-8'))
        cls.base = url.strip().split('/.local', 1)[0]
        print(f"Testing review server PID {cls.server.pid} at {cls.base}", flush=True)

    @classmethod
    def stop_server(cls):
        (cls.tmp/'stop').touch()
        try:
            code = cls.server.wait(timeout=15)
        except subprocess.TimeoutExpired:
            cls.server.terminate()
            cls.server.wait()
            raise
        finally:
            cls.server.stdout.close()
        if code:
            raise RuntimeError((cls.tmp/'server.log').read_text(encoding='utf-8'))
        print(f"Stopped review server PID {cls.server.pid}", flush=True)

    def request_pose(self, value, accept):
        headers = {'Content-Type': 'application/json', 'Accept': accept}
        return urlopen(Request(self.base+'/pose', json.dumps(value).encode(), headers), timeout=60)

    def pose(self, value, packed=False):
        with self.request_pose(value, 'application/octet-stream' if packed else 'application/json') as response:
            if packed:
                self.assertEqual(response.headers['Content-Type'], 'application/octet-stream')
                return response.read()
            return json.load(response)

    @contextmanager
    def actor(self, model):
        record = self.pose(dict(op='register', modelId=model))
        try:
            yield record
        finally:
            self.pose(dict(op='release', id=record['id']))

    def test_hub_and_player_html(self):
        player_controls = {'view', 'variant', 'zoom', 'play', 'time', 'speed', 'caption', 'paper', 'mute', 'verdict'}
        pages = [('/', {'filters', 'catalog', 'status'}), ('/.local/previews/inbox/index.html', set())]
        pages.extend((f'/player.html?entry={ident}', player_controls)
                     for ident in ('leak', 'moment-behavior_sunbathe', 'moment-behavior_call_child'))
        for path, controls in pages:
            with urlopen(self.base+path) as response:
                parser = Document()
                parser.feed(response.read().decode('utf-8'))
                parser.close()
                self.assertEqual(parser.tags, [])
                self.assertLessEqual(controls, parser.ids)
                for asset in parser.assets:
                    with urlopen(urljoin(response.url, asset)) as resource:
                        self.assertEqual(resource.status, 200)
        with urlopen(self.base+'/preview/review/timeline.mjs') as response:
            self.assertIn(response.headers.get_content_type(), ('text/javascript', 'application/javascript'))
            with urlopen(Request(response.url, method='HEAD')) as headers:
                self.assertEqual(headers.headers['Content-Length'], response.headers['Content-Length'])
                self.assertEqual(headers.read(), b'')

    def test_entries_resolve_to_runtime_assets(self):
        from build_review_catalog import ASSETS, read
        from build_player_entries import moment_entry_id
        from preview_inventory import current_items

        entries = self.data['entries']
        self.assertEqual(len(entries), len({entry['id'] for entry in entries}))
        self.assertEqual({entry['category'] for entry in entries}, {'performances', 'models', 'props', 'sounds'})
        variants = set(read(ASSETS/'yukkuri_presentation/living.json')['variants'])
        variants.update(read(ASSETS/'yukkuri_presentation/subspecies.json'))
        self.assertEqual(set(self.data['models']), variants)
        self.assertEqual({e['model'] for e in entries if e['category'] == 'models'}, variants)
        items = set(current_items()) - set(read(ROOT/'preview/hub/categories.json')['excludeItems'])
        self.assertEqual({e['id'] for e in entries if e['category'] == 'props'}, {'item-'+i for i in items})
        sounds = set()
        for event in read(ASSETS/'sounds.json').values():
            for sound in event['sounds']:
                name = sound['name'] if isinstance(sound, dict) else sound
                sounds.add('/src/main/resources/assets/'+name.replace(':', '/sounds/')+'.ogg')
        self.assertEqual({e['audio'] for e in entries if e['category'] == 'sounds'}, sounds)
        by_id = {entry['id']: entry for entry in entries}
        authored = read(ASSETS/'yukkuri_presentation/moments.json')['moments']
        for ident, row in authored.items():
            entry = by_id[moment_entry_id(ident)]
            self.assertEqual(entry['moment'], ident)
            self.assertEqual(entry['tag'], 'look-only')
            self.assertEqual(entry['loop'], row['loop'])
            self.assertEqual([{k: v for k, v in step.items() if k != 'caption'}
                              for step in entry['steps']], row['steps'])
            self.assertTrue(entry['variants'])
        clips = {}
        for model in self.data['models']:
            with self.subTest(model=model), self.actor(model) as actor:
                clips[model] = set(actor['clips'])
                listed = {entry['clip'] for entry in entries
                          if entry['category'] == 'performances' and entry.get('model') == model and 'clip' in entry}
                self.assertEqual(listed, clips[model])
        for entry in entries:
            if 'steps' in entry:
                for model in [entry['model']] if 'model' in entry else entry['variants']:
                    self.assertLessEqual({step['clip'] for step in entry['steps']}, clips[model])
            elif entry.get('model'):
                self.assertIn(entry['clip'], clips[entry['model']])
            if 'partner' in entry:
                partner = entry['partner']
                self.assertIn(partner['clip'], clips[partner['model']])
            if entry['category'] == 'props':
                self.assertEqual(entry['tag'] == 'look-only', bool(entry['image']))
            if media := entry.get('audio') or entry.get('image'):
                self.assertTrue(((ROOT if media.startswith('/') else OUT)/media.lstrip('/')).is_file(), media)

    def test_five_live_moment_timelines_and_transport_parity(self):
        script = """
import {readFileSync} from 'node:fs';
import {buildTimeline, emoteFrame, leakSteps, sampleTimeline, textureFace} from './preview/review/timeline.mjs';
const {data, id, clips, leak} = JSON.parse(readFileSync(0));
const entry = data.entries.find(row => row.id === id);
if (leak) entry.steps = leakSteps(entry.steps, leak);
const {track, duration} = buildTimeline(entry, clips, data.momentClips);
const times = track.flatMap(step => [step.start, (step.start + step.end) / 2]);
times.push(duration);
const previews = times.map(time => {
    const sample = sampleTimeline(track, clips, time, 'auto');
    return {...sample, texture: textureFace(data, data.models.reimu, sample.face, time),
            cue: emoteFrame(sample, time, duration, leak)};
});
console.log(JSON.stringify(previews));
"""
        from build_review_catalog import ASSETS, read
        runtime = read(ASSETS/'yukkuri_presentation/moments.json')['moments']
        moments = {'leak': None, 'wet_relief': 'wet_relief', 'rain_huddle': 'behavior_rain_huddle_left',
                   'thunder_cower': 'behavior_thunder_cower', 'wake_stretch': 'behavior_wake'}
        with self.actor('reimu') as actor:
            for ident, source in moments.items():
                with self.subTest(moment=ident):
                    entry = next(e for e in self.data['entries'] if e['id'] == ident)
                    if source:
                        self.assertEqual(entry['loop'], runtime[source]['loop'])
                        self.assertEqual([{k: v for k, v in step.items() if k != 'caption'}
                                          for step in entry['steps']], runtime[source]['steps'])
                    leak = self.pose(dict(op='leak', bodyWidth=actor['actor']['width'], **entry['leak'])) if 'leak' in entry else None
                    cue = entry['speech']['reimu']
                    self.assertTrue(cue['text'])
                    self.assertGreater(cue['duration'], 0)
                    with urlopen(self.base + cue['audio']) as voice:
                        self.assertEqual(voice.read(4), b'OggS')
                    probes = subprocess.run(['node', '--input-type=module', '-e', script], check=True, cwd=ROOT,
                                            input=json.dumps(dict(data=self.data, id=ident, clips=actor['clips'],
                                                                  leak=leak)),
                                            capture_output=True, text=True, encoding='utf-8')
                    samples = json.loads(probes.stdout)
                    frames = []
                    for sample in samples:
                        self.assertTrue(sample['caption'])
                        self.assertIn(sample['texture'], self.data['models']['reimu']['textures'])
                        payload = dict(op='render', id=actor['id'], action=sample['clip'],
                                       time=sample['time'], intensity=sample['intensity'],
                                       expression=sample['texture'], scale=1, emote=sample['cue'])
                        headers = {'Content-Type': 'application/json', 'Accept': 'application/octet-stream'}
                        with urlopen(Request(self.base+'/pose', json.dumps(payload).encode(), headers)) as response:
                            sample['symbol'] = response.headers['X-Pose-Emote']
                            frames.append(response.read())
                    self.assertGreater(len(set(frames)), 1)
                    result = self.pose(payload)
                    self.assertEqual(result['emote'], samples[-1]['symbol'])
                    json_vertices = result['vertices']
                    self.assertEqual(frames[-1], struct.pack(f'<{len(json_vertices)}f', *json_vertices))
                    if ident == 'leak':
                        self.assertEqual([sample['face'] for sample in samples[:-1:2]],
                                         ['leak_startle', 'leak_flow', 'side_teary'])
                        self.assertEqual([sample['symbol'] for sample in samples[:-1:2]], ['exclaim', '', 'sweat'])
                    if ident in ('rain_huddle', 'thunder_cower', 'wet_relief'):
                        self.assertIn(samples[0]['symbol'], ('sweat', 'shiver'))
                        self.assertEqual(samples[-1]['symbol'], '')
            payload.update(action='idle', expression='neutral')
            start = time.perf_counter()
            for frame in range(20):
                self.pose(dict(payload, time=frame/20), packed=True)
            print(f"20 live Reimu frames over HTTP in {time.perf_counter() - start:.2f}s", flush=True)

    def test_effects_geometry_and_lifetimes(self):
        with self.actor('reimu') as actor, self.actor('house_marisa') as house:
            self.assertAlmostEqual(actor['actor']['emoteHeight'], .99)
            self.assertAlmostEqual(house['actor']['emoteHeight'], .47)
            self.assertGreater(house['actor']['voicePitch'], actor['actor']['voicePitch'])
            self.assertGreater(self.pose(dict(op='leak', stage='baby', selfControl=50, stimulus='joy', seed=1,
                                              pressure=65, bodyWidth=.15))['beat']['puddleAt'], 40)
            probe = dict(op='leak', stage='adult', selfControl=50, stimulus='drop', seed=1,
                         pressure=65, bodyWidth=actor['actor']['width'])
            leak = self.pose(probe)
            self.assertEqual(leak['beat']['flowAt'], 8)
            self.assertEqual(leak['beat']['puddleAt'], 38)
            self.assertNotIn('beat', self.pose(dict(probe, stimulus='scare')))
            self.assertEqual([leak['traceOpacity'][i] for i in (0, 600, 700, 800)], [255, 255, 128, 0])
            render = dict(op='render', id=actor['id'], action='idle', time=0, expression='side_teary', scale=1,
                          leakStream=True, outlet=actor['actor']['outlet'], paperCover=actor['actor']['paperAccessories'])
            frame = self.pose(render)
            self.assertNotIn('leak_stream', self.pose(dict(render, leakStream=False, vertexFormat='float32le')))
            packed = self.pose(dict(render, vertexFormat='float32le'))
            for field in ('vertices', 'leak_stream', 'paper_top', 'paper_under'):
                self.assertGreater(len(frame[field]), 0)
                self.assertEqual(len(frame[field]) % 24, 0)
                self.assertEqual(b64decode(packed[field]), struct.pack(f'<{len(frame[field])}f', *frame[field]))
            x, y, z = frame['outlet']
            event = dict(op='floorEffect', tick=38, sourceId=1, scale=1,
                         position=dict(x=-x/16, y=y/16, z=-z/16+leak['puddleReach']), direction=dict(x=0, y=0, z=1))
            puddle = self.pose(dict(event, reason='excited_leak', amount=leak['beat']['width']))
            self.assertEqual(puddle['texture'], 'textures/effects/leak_puddle.png')
            self.assertEqual(puddle['flights'], [])
            self.assertEqual(puddle['traces'][0]['until'], 1838)
            self.assertEqual([puddle['opacity'][i] for i in (1200, 1500, 1799)], [255, 128, 0])
            self.assertAlmostEqual(puddle['traces'][0]['halfX'], .24)
            filling = self.pose(dict(event, reason='vomit', amount=1))
            self.assertEqual(filling, self.pose(dict(event, reason='vomit', amount=1)))
            self.assertEqual(filling['texture'], 'textures/props/leaked_filling.png')
            self.assertEqual(len(filling['flights']), 3)
            self.assertTrue(all(2 <= len(f['path']) <= 13 for f in filling['flights']))
            self.assertEqual(filling['traces'][-1]['until'] - filling['traces'][-1]['tick'], 600)
            self.assertEqual([filling['opacity'][i] for i in (500, 550, 599)], [255, 128, 3])

    def test_raw_clip_preserves_authored_root_rotation(self):
        script = """
import {buildTimeline, sampleTimeline} from './preview/review/timeline.mjs';
import {readFileSync} from 'node:fs';
const clips = JSON.parse(readFileSync(0));
const {track} = buildTimeline({clip: 'hat_jostle'}, clips);
console.log(JSON.stringify(sampleTimeline(track, clips, 0.16, 'auto')));
"""
        with self.actor('reimu') as actor:
            sample = json.loads(subprocess.check_output(['node', '--input-type=module', '-e', script],
                                input=json.dumps(actor['clips']), cwd=ROOT, text=True))
            payload = dict(op='render', id=actor['id'], action=sample['clip'], time=sample['time'],
                           expression=sample['face'], scale=1)
            raw = self.pose(payload)['root_pose']
            self.assertGreater(abs(raw[3]) + abs(raw[5]), 12)
            self.assertEqual(self.pose(dict(payload, intensity=sample.get('intensity')))['root_pose'], raw)

    def test_surface_marks_follow_pose_and_scrub(self):
        from build_review_catalog import ASSETS, read

        with self.actor('reimu') as actor:
            render = dict(op='render', id=actor['id'], action='idle', time=0,
                          expression='neutral', scale=1, leakAge=-1)
            self.assertNotIn('surfaceMark', self.pose(render))
            flow = self.pose(dict(render, action='brace', expression='leak_flow'))['surfaceMark']
            self.assertEqual(flow['opacity'], 255)
            payload = dict(render, action='startle', time=.12, leakAge=700)
            frame = self.pose(payload)
            wet = frame['surfaceMark']
            self.assertEqual(wet['texture'], 'textures/props/body_wounds/leak_wet.png')
            self.assertEqual(wet['opacity'], 128)
            self.assertNotEqual(wet['vertices'], flow['vertices'])
            self.assertEqual(wet['vertices'][3::8], flow['vertices'][3::8])
            self.assertEqual(wet['vertices'][4::8], flow['vertices'][4::8])
            self.assertNotIn('surfaceMark', self.pose(dict(payload, leakAge=800)))
            self.assertEqual(self.pose(payload)['surfaceMark'], wet)

            entry = next(e for e in self.data['entries'] if e['id'] == 'strike_scratches')
            self.assertNotIn('scratch', entry['steps'][0])
            strike = entry['steps'][1]
            payload = dict(render, action=strike['clip'], time=.15, expression=strike['faces'][0][1],
                           scratch=strike['scratch'])
            impact = self.pose(payload)['surfaceMark']
            self.assertEqual(impact['texture'], 'textures/wounds/hair/scratch-2.png')
            self.assertEqual(impact['opacity'], 255)
            self.assertNotEqual(impact['vertices'], self.pose(dict(payload, time=.3))['surfaceMark']['vertices'])
            # The cheek below the eye and the side hair each receive ink.
            idle = self.pose(dict(payload, action='idle', time=0, expression='neutral'))['surfaceMark']['vertices']
            points = list(zip(idle[0::8], idle[1::8], idle[2::8]))
            self.assertTrue(any(p[1] < 3 and p[2] < -3 for p in points))
            self.assertTrue(any(p[0] > 4 and p[2] > -2 for p in points))
            profile = read(ASSETS/'yukkuri_presentation/hair_wounds.json')['profiles']['scratch']
            self.assertFalse(profile[str(strike['scratch']['grade'])]['open'])
            for value in (wet, impact):
                self.assertGreater(len(value['vertices']), 0)
                self.assertEqual(len(value['vertices']) % 24, 0)
                with urlopen(self.base+'/src/main/resources/assets/mcyukkuri/'+value['texture']) as source:
                    self.assertEqual(source.headers.get_content_type(), 'image/png')
            for payload in (dict(render, leakAge=700), payload):
                packed = self.pose(dict(payload, vertexFormat='float32le'))
                actor_bytes = b64decode(packed['vertices'])
                mark = packed['surfaceMark']
                with self.request_pose(payload, 'application/octet-stream') as response:
                    metadata = json.loads(response.headers['X-Pose-Surface-Mark'])
                    self.assertEqual(metadata, {k: mark[k] for k in ('texture', 'opacity')} | {'offset': len(actor_bytes)})
                    self.assertEqual(response.read(), actor_bytes + b64decode(mark['vertices']))

    def test_live_attachments(self):
        from build_review_catalog import ASSETS, read

        entries = {entry['id']: entry for entry in self.data['entries']}
        held = entries['held_kanroken']['held']
        prop = self.pose(dict(op='prop', **held))
        submitted = [v for mesh in prop['meshes'] for v in mesh['vertices']]
        original = read(ASSETS/'yukkuri_props/kanroken.json')['vertices']
        self.assertEqual(struct.pack(f'<{len(submitted)}f', *submitted), struct.pack(f'<{len(original)}f', *original))
        with self.actor('myon') as actor:
            payload = dict(op='render', id=actor['id'], action='waddle', time=.1, expression='neutral', scale=1,
                           held=dict(held, mouthBone=actor['actor']['mouthBone']))
            first = self.pose(payload)['held']
            second = self.pose(dict(payload, time=.4))['held']
            self.assertNotEqual(first, second)
            self.assertLess(first[2][3], -4)
            self.assertAlmostEqual(sum(row[0]**2 for row in first), 1, places=5)
            with self.request_pose(payload, 'application/octet-stream') as response:
                self.assertEqual(json.loads(response.headers['X-Pose-Attachments']), {'held': first})
                self.assertEqual(response.read(), b64decode(self.pose(dict(payload, vertexFormat='float32le'))['vertices']))

        worn = entries['worn_damaged_hat']['hat']
        hat = self.pose(dict(op='prop', **worn))
        intact = self.pose(dict(op='prop', **{**worn, 'damage': 0}))
        self.assertEqual(hat['fit'], intact['fit'])
        self.assertEqual([mesh['texture'] for mesh in hat['meshes'][:-1]],
                         [mesh['texture'] for mesh in intact['meshes']])
        self.assertNotEqual(hat['meshes'][0]['vertices'], intact['meshes'][0]['vertices'])
        caps = hat['meshes'][-1]
        self.assertEqual(caps['texture'], 'mcyukkuri/textures/props/hat_damage_inner.png')
        self.assertEqual(len(caps['vertices']), 8 * len(caps['colors']))
        self.assertGreater(len(caps['colors']), 0)
        for texture in {mesh['texture'] for mesh in hat['meshes'] + prop['meshes']}:
            with urlopen(self.base+'/src/main/resources/assets/'+texture) as response:
                self.assertEqual(response.headers.get_content_type(), 'image/png')
        with self.actor('marisa') as actor:
            payload = dict(op='render', id=actor['id'], action='hat_jostle', time=.1, expression='neutral', scale=1)
            fit = dict(hat['fit'], accessories=actor['actor']['paperAccessories'])
            frame = self.pose(dict(payload, hat=fit))
            self.assertNotEqual(frame['hat'], self.pose(dict(payload, hat=fit, time=.4))['hat'])
            self.assertGreater(frame['vertices'].count(0), self.pose(payload)['vertices'].count(0))
            covered = self.pose(dict(payload, hat=dict(fit, outline=hat['outline']),
                                     paperCover=fit['accessories'], vertexFormat='float32le'))
            self.assertTrue(covered['paper_top'] and covered['paper_under'])
            grip = self.pose(dict(payload, action='braid_item_hold', held=dict(joint='item')))['held']
            self.assertGreater(grip[0][3], 5)

    def test_drawn_peer_nuzzle_contact(self):
        entries = {e['id']: e for e in self.data['entries']}
        self.assertNotIn('parent_child_contact', entries)
        entry = entries['peer_nuzzle']
        spec = entry['partner']
        parts = fitted_sections(entry['model'])
        cheek = [parts['rounded_body_surface']]
        left_clip, right_clip = entry['steps'][0]['clip'], spec['clip']
        with self.actor(entry['model']) as left, self.actor(spec['model']) as right:
            duration = left['clips'][left_clip]['duration']
            for seconds in (0, 1.5, 1.9, duration - .001, duration):
                clock = seconds % duration
                face = next(face for start, end, face in left['clips'][left_clip]['faces'] if start <= clock < end)
                peer = dict(id=right['id'], action=right_clip, scale=spec['scale'], expression=face)
                payload = dict(op='render', id=left['id'], action=left_clip, time=seconds,
                               expression=face, scale=1, partner=peer)
                with self.request_pose(payload, 'application/octet-stream') as response:
                    split = int(response.headers['X-Pose-Partner-Offset'])
                    spacing = float(response.headers['X-Pose-Partner-Spacing'])
                    raw = response.read()
                first = lateral_vertices(raw[:split], 0)
                second = lateral_vertices(raw[split:], spacing)
                all_gap = drawn_spacing(surface_depths(first, parts.values(), min),
                                        surface_depths(second, parts.values(), max))
                cheek_gap = drawn_spacing(surface_depths(first, cheek, min), surface_depths(second, cheek, max))
                print(f'Peer nuzzle {seconds:.3f}s spacing={spacing:.6f} cheek gap={cheek_gap:.6f} '
                      f'whole mesh gap={all_gap:.6f} model units', flush=True)
                with self.subTest(seconds=seconds):
                    self.assertGreaterEqual(all_gap, -1e-5, 'Rendered body, hair or bow penetrates the other actor')
                    self.assertLessEqual(cheek_gap, .005, 'Rendered cheeks are apart')
                if seconds == 1.5:
                    packed = self.pose(dict(payload, vertexFormat='float32le'))
                    self.assertEqual(spacing, packed['partnerSpacing'])
                    self.assertEqual(raw, b64decode(packed['vertices']) + b64decode(packed['partnerVertices']))
                    # Removing the partner faces at the same stand makes the loose meshes intersect.
                    unpressed = [self.pose(dict(op='render', id=actor['id'], action=clip, time=seconds,
                                                expression=face, scale=scale), packed=True)
                                 for actor, clip, scale in ((left, left_clip, 1), (right, right_clip, spec['scale']))]
                    unpressed_gap = drawn_spacing(surface_depths(lateral_vertices(unpressed[0], 0), parts.values(), min),
                                                  surface_depths(lateral_vertices(unpressed[1], spacing), parts.values(), max))
                    print(f'Without partner faces the 1.500s mesh gap is {unpressed_gap:.6f} model units', flush=True)
                    self.assertLess(unpressed_gap, -.05)

    def verdict(self, data, content_type='application/json'):
        body = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False).encode('utf-8')
        with urlopen(Request(self.base+'/verdict', body, {'Content-Type': content_type}), timeout=15) as response:
            return json.load(response)

    def latest(self):
        with urlopen(self.base+'/verdicts', timeout=15) as response:
            return json.load(response)

    def test_verdicts_concurrent_append_and_latest(self):
        self.assertEqual(self.latest(), {})

        def submit(index):
            result = self.verdict(dict(id=f'look-{index % 7}', verdict='pass' if index % 2 else 'fail',
                                       note=f'第 {index} 条\n外观'))
            self.assertIn(result['id'], self.latest())
            return result

        with ThreadPoolExecutor(max_workers=8) as pool:
            submitted = list(pool.map(submit, range(42)))
        records = [json.loads(line) for line in self.verdicts.read_text(encoding='utf-8').splitlines()]
        self.assertCountEqual(records, submitted)
        latest = {record['id']: record for record in records}
        self.assertEqual(self.latest(), latest)
        for record in records:
            self.assertEqual(datetime.fromisoformat(record['ts']).utcoffset().total_seconds(), 0)
        self.assertEqual(self.verdict(dict(id='art.test_1', verdict='pass', note='𠮷' * 500))['note'], '𠮷' * 500)
        self.assertEqual(self.verdict(dict(id='art.test_1', verdict='fail'))['note'], '')
        self.assertEqual(self.latest()['art.test_1']['verdict'], 'fail')
        from review_verdicts import LOG
        self.assertEqual(LOG, ROOT/'.local/reports/verdicts.jsonl')
        subprocess.run(['git', 'check-ignore', '-q', str(LOG.relative_to(ROOT))], cwd=ROOT, check=True)

        cli = """
from pathlib import Path
import sys
import preview
import review_verdicts
review_verdicts.LOG = Path(sys.argv.pop(1))
sys.exit(preview.main())
"""
        result = subprocess.check_output([sys.executable, '-c', cli, str(self.verdicts), 'verdicts'],
                                         cwd=ROOT/'scripts', encoding='utf-8')
        self.assertEqual(json.loads(result), self.latest())

    def test_verdicts_reject_invalid_payloads(self):
        before = self.latest()
        good = dict(id='leak', verdict='pass', note='')
        for broken in (dict(good, id='../leak'), dict(good, id='_leak'), dict(good, id='x'*129),
                       dict(good, id=1), dict(good, verdict='ok'), dict(good, verdict=['pass']),
                       dict(good, note='字'*501), dict(good, note=None), [], b'{"id":'):
            with self.subTest(payload=broken), self.assertRaises(HTTPError) as error:
                self.verdict(broken)
            self.assertEqual(error.exception.code, 400)
            self.assertIn('error', json.load(error.exception))
        with self.assertRaises(HTTPError) as error:
            self.verdict(good, 'text/plain')
        self.assertEqual(error.exception.code, 415)
        error.exception.close()
        self.assertEqual(self.latest(), before)

if __name__ == '__main__':
    unittest.main(verbosity=2)
