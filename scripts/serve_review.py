"""Serve review media, live poses and verdicts on localhost."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote,urlsplit
import argparse
from base64 import b64decode
import json
import re
from threading import Lock
from pose_client import request
from review_models import register_runtime
import review_verdicts

POSE_LOCK=Lock()

ROOT=Path(__file__).resolve().parents[1]
class Handler(SimpleHTTPRequestHandler):
    def respond(self, body, status=200, content_type='application/json'):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
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
            if not isinstance(value, dict) or value.get('op') not in ('register', 'render', 'release'):
                raise ValueError('Expected preview register/render request')
            packed = value['op'] == 'render' and self.headers.get('Accept') == 'application/octet-stream'
            if packed:
                value['vertexFormat'] = 'float32le'
            with POSE_LOCK:
                if value['op'] == 'register' and 'modelId' in value:
                    ident = value['modelId']
                    if not isinstance(ident, str) or not re.fullmatch(r'[a-z0-9_]+', ident):
                        raise ValueError('Invalid runtime model id')
                    registered = register_runtime(ident)
                    model = registered['model']
                    clips = {**model['clips'], **model.get('behavior_clips', {})}
                    result = dict(id=registered['id'], clips={name: {
                        'duration': clip['duration'], 'loop': clip.get('loop', False),
                        'faces': clip.get('expression_spans', [])} for name, clip in clips.items()})
                else:
                    result = request(value)
        except (ValueError, RuntimeError, FileNotFoundError) as error:
            self.respond_json({'error': str(error)}, 400)
            return
        if packed:
            self.respond(b64decode(result['vertices']), content_type='application/octet-stream')
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
        if requested in aliases:
            target=aliases[requested]+('?' + parsed.query if parsed.query else '')
            self.send_response(302);self.send_header('Location',target);self.end_headers();return
        if requested.startswith('/.local/previews/_'):
            self.send_error(404);return
        if not requested.startswith(('/.local/previews/','/preview/','/src/main/resources/assets/','/art/','/standards/','/docs/')):
            self.send_error(404);return
        target=(ROOT/requested.lstrip('/')).resolve()
        allowed = [ROOT / p for p in ('.local/previews', 'preview', 'src/main/resources/assets', 'art', 'standards', 'docs')]
        private = ROOT / '.local/previews'
        if (not any(target.is_relative_to(base) for base in allowed) or target.is_dir()
                or target.is_relative_to(private) and target.relative_to(private).parts[0].startswith('_')):
            self.send_error(404);return
        super().do_GET()
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
