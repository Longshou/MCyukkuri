"""Serve review media, live poses and verdicts on localhost."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qsl, quote, unquote, urlencode, urlsplit
import argparse
from base64 import b64decode
import json
import math
import re
from threading import Lock
from pose_client import request
from review_models import register_runtime
import review_verdicts

POSE_LOCK=Lock()

ROOT=Path(__file__).resolve().parents[1]
class Handler(SimpleHTTPRequestHandler):
    def respond(self, body, status=200, content_type='application/json', headers=()):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def respond_json(self, value, status=200):
        self.respond(json.dumps(value).encode(), status)

    def do_POST(self):
        if self.path == '/verdict':
            self.post_verdict()
            return
        if self.path != '/pose':
            self.send_error(404);return
        try:
            value = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
            if not isinstance(value, dict) or value.get('op') not in ('register', 'render', 'release', 'leak', 'floorEffect', 'prop'):
                raise ValueError('Expected preview pose request')
            packed = value['op'] == 'render' and self.headers.get('Accept') == 'application/octet-stream'
            if packed:
                value['vertexFormat'] = 'float32le'
            with POSE_LOCK:
                if value['op'] == 'register' and 'modelId' in value:
                    ident = value['modelId']
                    if not isinstance(ident, str) or not re.fullmatch(r'[a-z0-9_]+', ident):
                        raise ValueError('Invalid runtime model id')
                    scale = value.get('scale', 1)
                    if type(scale) not in (int, float) or not math.isfinite(scale) or scale <= 0:
                        raise ValueError('Invalid review body scale')
                    registered = register_runtime(ident, scale)
                    model = registered['model']
                    clips = {**model['clips'], **model.get('behavior_clips', {})}
                    result = dict(id=registered['id'], actor=registered['actor'], clips={name: {
                        'duration': clip['duration'], 'loop': clip.get('loop', False),
                        'faces': clip.get('expression_spans', [])} for name, clip in clips.items()})
                else:
                    result = request(value)
        except (ValueError, RuntimeError, FileNotFoundError) as error:
            self.respond_json({'error': str(error)}, 400)
            return
        if packed:
            vertices = b64decode(result['vertices'])
            headers = []
            if 'emote' in result:
                headers.append(('X-Pose-Emote', quote(result['emote'], safe='')))
            if 'partnerVertices' in result:
                headers.append(('X-Pose-Partner-Offset', str(len(vertices))))
                headers.append(('X-Pose-Partner-Spacing', str(result['partnerSpacing'])))
                vertices += b64decode(result['partnerVertices'])
            if 'surfaceMark' in result:
                mark = result['surfaceMark']
                headers.append(('X-Pose-Surface-Mark', json.dumps({
                    'offset': len(vertices), 'texture': mark['texture'], 'opacity': mark['opacity']
                }, separators=(',', ':'))))
                vertices += b64decode(mark['vertices'])
            attachments = {key: result[key] for key in ('held', 'hat') if key in result}
            if attachments:
                headers.append(('X-Pose-Attachments', json.dumps(attachments, separators=(',', ':'))))
            self.respond(vertices, content_type='application/octet-stream', headers=headers)
        else:
            self.respond_json(result)

    def post_verdict(self):
        if self.headers.get_content_type() != 'application/json':
            self.respond_json({'error': '审阅请求须使用 JSON。'}, 415)
            return
        try:
            length = int(self.headers.get('Content-Length', 0))
            if not 0 < length <= 16 * 1024:
                raise ValueError('无效的审阅请求长度。')
            record = review_verdicts.append(json.loads(self.rfile.read(length)), review_verdicts.LOG)
        except ValueError as error:
            self.respond_json({'error': str(error)}, 400)
        except OSError:
            self.respond_json({'error': '无法写入审阅记录。'}, 500)
        else:
            self.respond_json(record)

    def do_GET(self):
        parsed = urlsplit(self.path)
        if parsed.path == '/review-health':
            self.respond_json({'project':'MCyukkuri','root':str(ROOT),'review':1})
            return
        if parsed.path == '/verdicts':
            self.respond_json(review_verdicts.latest(review_verdicts.LOG))
            return
        requested=unquote(parsed.path)
        aliases={'/':'/.local/previews/index.html','/preview':'/.local/previews/index.html',
                 '/preview/':'/.local/previews/index.html','/player.html':'/.local/previews/player.html',
                 '/preview/review/index.html':'/.local/previews/models/index.html'}
        if requested in ('/player.html', aliases['/player.html']):
            query = dict(parse_qsl(parsed.query))
            if moment := query.pop('moment', None):
                from build_player_entries import moment_entry_id

                query['entry'] = moment_entry_id(moment)
                self.send_response(302)
                self.send_header('Location', aliases['/player.html'] + '?' + urlencode(query))
                self.end_headers()
                return
        if requested in aliases:
            target=aliases[requested]+('?' + parsed.query if parsed.query else '')
            self.send_response(302);self.send_header('Location',target);self.end_headers();return
        super().do_GET()

    def send_head(self):
        # GET and HEAD must check the same file that the static handler opens.
        target = Path(self.translate_path(self.path)).resolve()
        allowed = [ROOT / p for p in ('.local/previews', 'preview', 'src/main/resources/assets', 'art', 'standards', 'docs')]
        private = ROOT / '.local/previews'
        if (not any(target.is_relative_to(base) for base in allowed) or not target.is_file()
                or target.is_relative_to(private) and target.relative_to(private).parts[0].startswith('_')):
            self.send_error(404);return
        return super().send_head()
    def end_headers(self):
        self.send_header('Cache-Control','no-cache')
        super().end_headers()
    def log_message(self,format,*args):
        if len(args)>1 and str(args[1]) not in ('200','304'):
            super().log_message(format,*args)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--port',type=int,default=8766);args=parser.parse_args()
    server=ThreadingHTTPServer(('127.0.0.1',args.port),partial(Handler,directory=str(ROOT)))
    print(f'http://127.0.0.1:{server.server_port}/.local/previews/index.html', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()


if __name__ == '__main__':
    main()
