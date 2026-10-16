"""Serve the read-only review page on localhost; no browser control and no writes."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote,urlsplit
import argparse
import json

ROOT=Path(__file__).resolve().parents[1]
class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if urlsplit(self.path).path=='/review-health':
            body=json.dumps({'project':'MCyukkuri','root':str(ROOT),'review':1}).encode()
            self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
        requested=unquote(urlsplit(self.path).path)
        if requested in ('/', '/preview/review/index.html'):
            self.send_response(302);self.send_header('Location','/.local/previews/index.html');self.end_headers();return
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
