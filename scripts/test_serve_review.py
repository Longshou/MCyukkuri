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


class ServeReviewTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        log = Path(tmp.name)/'verdicts.jsonl'
        for patch in (mock.patch.object(review_verdicts, 'LOG', log),
                      mock.patch.object(playtest_list, 'menu_payload', return_value=PAYLOAD)):
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

    def test_playtest_serves_the_current_list(self):
        self.assertEqual(self.get('/playtest'), PAYLOAD)

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


if __name__ == '__main__':
    unittest.main(verbosity=2)
