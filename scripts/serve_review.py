"""Serve review media and the read-only pose core on localhost; no browser control."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote,urlsplit
import argparse
import json
from threading import Lock
from pose_client import request

POSE_LOCK=Lock()

ROOT=Path(__file__).resolve().parents[1]
class Handler(SimpleHTTPRequestHandler):
    def do_POST(self):
        if self.path != '/pose':
            self.send_error(404);return
        try:
            value=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if value.get('op') not in ('register','render','release'):
                raise ValueError('Expected preview register/render request')
            with POSE_LOCK:
                result=request(value)
            status=200
        except (ValueError,RuntimeError) as error:
            result={'error':str(error)};status=400
        body=json.dumps(result).encode()
        self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_GET(self):
        if urlsplit(self.path).path=='/review-health':
            body=json.dumps({'project':'MCyukkuri','root':str(ROOT),'review':1}).encode()
            self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
        parsed=urlsplit(self.path)
        requested=unquote(parsed.path)
        aliases={'/':'/.local/previews/index.html','/preview':'/.local/previews/index.html',
                 '/preview/':'/.local/previews/index.html','/preview/review/index.html':'/.local/previews/models/index.html'}
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

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--port',type=int,default=8765);args=parser.parse_args()
    server=ThreadingHTTPServer(('127.0.0.1',args.port),partial(Handler,directory=str(ROOT)))
    print(f'http://127.0.0.1:{args.port}/.local/previews/index.html',flush=True)
    server.serve_forever()
