"""Headless live player checks. Build with scripts/build_preview_hub.py first."""
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from html.parser import HTMLParser
import json
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

    def pose(self, value, packed=False):
        headers = {'Content-Type': 'application/json'}
        if packed:
            headers['Accept'] = 'application/octet-stream'
        with urlopen(Request(self.base+'/pose', json.dumps(value).encode(), headers), timeout=60) as response:
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
        for path, controls in [
            ('/', {'filters', 'catalog', 'status'}),
            ('/player.html?entry=leak', {'view', 'variant', 'zoom', 'play', 'time', 'speed', 'caption', 'verdict'}),
            ('/.local/previews/inbox/index.html', set()),
        ]:
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
        clips = {}
        for model in self.data['models']:
            with self.subTest(model=model), self.actor(model) as actor:
                clips[model] = set(actor['clips'])
                listed = {entry['clip'] for entry in entries
                          if entry['category'] == 'performances' and entry.get('model') == model}
                self.assertEqual(listed, clips[model])
        for entry in entries:
            if 'steps' in entry:
                for model in entry['variants']:
                    self.assertLessEqual({step['clip'] for step in entry['steps']}, clips[model])
            if entry.get('model'):
                self.assertIn(entry['clip'], clips[entry['model']])
            if entry['category'] == 'props':
                self.assertEqual(entry['tag'] == 'look-only', bool(entry['image']))
            if media := entry.get('audio') or entry.get('image'):
                self.assertTrue(((ROOT if media.startswith('/') else OUT)/media.lstrip('/')).is_file(), media)

    def test_five_live_moment_timelines_and_transport_parity(self):
        script = """
import {readFileSync} from 'node:fs';
import {buildTimeline, sampleTimeline, textureFace} from './preview/review/timeline.mjs';
const {data, id, clips} = JSON.parse(readFileSync(0));
const entry = data.entries.find(row => row.id === id);
const {track, duration} = buildTimeline(entry, clips);
const times = track.flatMap(step => [step.start, (step.start + step.end) / 2]);
times.push(duration);
const previews = times.map(time => {
    const sample = sampleTimeline(track, clips, time, 'auto');
    return {...sample, texture: textureFace(data, data.models.reimu, sample.face, time)};
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
                    if source:
                        entry = next(e for e in self.data['entries'] if e['id'] == ident)
                        self.assertEqual(entry['loop'], runtime[source]['loop'])
                        self.assertEqual([{k: v for k, v in step.items() if k != 'caption'}
                                          for step in entry['steps']], runtime[source]['steps'])
                    probes = subprocess.run(['node', '--input-type=module', '-e', script], check=True, cwd=ROOT,
                                            input=json.dumps(dict(data=self.data, id=ident, clips=actor['clips'])),
                                            capture_output=True, text=True, encoding='utf-8')
                    samples = json.loads(probes.stdout)
                    frames = []
                    for sample in samples:
                        self.assertTrue(sample['caption'])
                        self.assertIn(sample['texture'], self.data['models']['reimu']['textures'])
                        payload = dict(op='render', id=actor['id'], action=sample['clip'],
                                       time=sample['time'], intensity=sample['intensity'],
                                       expression=sample['texture'], scale=1)
                        frames.append(self.pose(payload, packed=True))
                    self.assertGreater(len(set(frames)), 1)
                    json_vertices = self.pose(payload)['vertices']
                    self.assertEqual(frames[-1], struct.pack(f'<{len(json_vertices)}f', *json_vertices))
                    if ident == 'leak':
                        self.assertEqual([sample['face'] for sample in samples[:-1:2]],
                                         ['leak_startle', 'leak_flow', 'side_teary'])
            payload.update(action='idle', expression='neutral')
            start = time.perf_counter()
            for frame in range(20):
                self.pose(dict(payload, time=frame/20), packed=True)
            print(f"20 live Reimu frames over HTTP in {time.perf_counter() - start:.2f}s", flush=True)

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
