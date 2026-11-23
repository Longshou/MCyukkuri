"""The review server's live playtest list and the verdict title field, over a real local socket (no game, no browser)."""
import json
from http.server import ThreadingHTTPServer
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest import mock
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent))
import playtest_list
import review_verdicts
import serve_review

PAYLOAD = dict(format=1, since=None, until='229582efaa93477abca5ea43863391410ad92db2', items=[
    dict(status='复测失败', title='PD-13', watch='看点', fresh=True,
         scenes=[dict(id='hat_crowd', label='PD-13 超载翻船', field='池塘与河湾')])])


class ServerCase(unittest.TestCase):
    patch_menu = True

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        patches = [mock.patch.object(review_verdicts, 'LOG', self.tmp/'verdicts.jsonl')]
        if self.patch_menu:
            patches.append(mock.patch.object(playtest_list, 'menu_payload', return_value=PAYLOAD))
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)
        server = ThreadingHTTPServer(('127.0.0.1', 0), serve_review.Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.base = f'http://127.0.0.1:{server.server_port}'

    def get(self, path):
        with urlopen(self.base + path) as response:
            return json.load(response)

    def post(self, body):
        request = Request(self.base + '/verdict', json.dumps(body).encode(), {'Content-Type': 'application/json'})
        with urlopen(request) as response:
            return json.load(response)


class ServeReviewTests(ServerCase):
    def test_playtest_serves_the_current_list_and_field_order(self):
        body = self.get('/playtest')
        self.assertEqual({k: v for k, v in body.items() if k != 'fields'}, PAYLOAD)
        self.assertEqual(body['fields'], [title for title, _ in playtest_list.load_museum()[2]])
        self.assertIn('池塘与河湾', body['fields'])

    def test_playtest_answers_bad_manifests_with_json(self):
        for error in (ValueError('bad json'), KeyError('commit')):
            with mock.patch.object(playtest_list, 'menu_payload', side_effect=error):
                with self.assertRaises(HTTPError) as caught:
                    self.get('/playtest')
            self.assertEqual(caught.exception.code, 500)
            self.assertIn('error', json.load(caught.exception))

    def test_playtest_reports_an_unreadable_list(self):
        with mock.patch.object(playtest_list, 'menu_payload', side_effect=FileNotFoundError('museum-runtime.json')):
            with self.assertRaises(HTTPError) as caught:
                self.get('/playtest')
        self.assertEqual(caught.exception.code, 500)
        self.assertIn('museum-runtime.json', json.load(caught.exception)['error'])

    def test_playtest_verdict_keeps_scene_runtime_and_title(self):
        ident = 'playtest.hat_crowd.' + PAYLOAD['until'][:9]
        self.post(dict(id=ident, verdict='fail', reasons=[], note='boat sank', title='PD-13 超载翻船'))
        self.assertEqual(self.get('/verdicts')[ident]['title'], 'PD-13 超载翻船')
        self.assertNotIn('playtest.hat_crowd.deadbeef0', self.get('/verdicts'))
        with self.assertRaises(HTTPError) as caught:
            self.post(dict(id=ident, verdict='pass', title='x' * 81))
        self.assertEqual(caught.exception.code, 400)


class LivePlaytestTests(ServerCase):
    """No menu_payload mock: /playtest reads the checkout's TODO and runtime manifest on every request."""
    patch_menu = False

    def setUp(self):
        super().setUp()
        (self.tmp/'docs').mkdir()
        (self.tmp/'.local/experimental-map').mkdir(parents=True)
        (self.tmp/'.local/experimental-map/museum-runtime.json').write_text(json.dumps(dict(commit=PAYLOAD['until'])))
        patch = mock.patch.object(playtest_list, 'ROOT', self.tmp)
        patch.start()
        self.addCleanup(patch.stop)

    def write_todo(self, text):
        (self.tmp/'docs/TODO.md').write_text(text, encoding='utf-8')

    def test_todo_change_shows_on_the_next_request(self):
        self.write_todo('- **2026-11-22 反馈**（x）：\n  - 甲演出：已接入待试玩。DN-11 看孩子顶嘴。\n')
        first = self.get('/playtest')
        self.assertEqual([row['scenes'][0]['label'][:5] for row in first['items']], ['DN-11'])
        self.assertEqual(first['until'], PAYLOAD['until'])
        self.write_todo('- **2026-11-22 反馈**（x）：\n  - 乙演出：复测失败。WT-06 看追猎。\n')
        second = self.get('/playtest')
        self.assertEqual([(row['status'], row['scenes'][0]['label'][:5]) for row in second['items']], [('复测失败', 'WT-06')])


if __name__ == '__main__':
    unittest.main(verbosity=2)
