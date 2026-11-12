"""Run this once (double-click or `python start_editor.py`) and keep the window open.
Keep it in the same folder as the editor, the "bgm" folder and the "best hook" folder.

It serves the editor in your browser AND answers the editor's requests. Every time you pick
Auto BGM / Auto Hook SFX in the page, the page calls /api/library, this script re-scans both
folders (so newly added tracks are picked up with no restart), and the editor scores the files
and applies the result to the timeline by itself."""
import http.server, socketserver, json, os, sys, urllib.parse, webbrowser, threading

here = os.path.dirname(os.path.abspath(__file__)); os.chdir(here)
AUD = ('.mp3', '.wav', '.ogg', '.m4a', '.aac', '.flac', '.opus')
FOLDERS = {'bgm': 'bgm', 'sfx': 'best hook'}


def scan(d):
    """Return every audio file under folder d (sub-folders included) as {url, name, size, mtime}."""
    base = os.path.join(here, d)
    out = []
    if os.path.isdir(base):
        for root, _, files in os.walk(base):
            for f in files:
                if f.lower().endswith(AUD):
                    full = os.path.join(root, f)
                    rel = os.path.relpath(full, here).replace(os.sep, '/')
                    st = os.stat(full)
                    out.append({'url': urllib.parse.quote(rel), 'name': f,
                                'size': st.st_size, 'mtime': int(st.st_mtime)})
    return sorted(out, key=lambda x: x['url'].lower())


def library():
    return {k: scan(v) for k, v in FOLDERS.items()}


htmls = [f for f in sorted(os.listdir(here)) if f.lower().endswith('.html')]
page = next((f for f in htmls if 'editor' in f.lower()), None) or ('index.html' if 'index.html' in htmls else (htmls[0] if htmls else None))
if not page:
    sys.exit('No .html file found next to start_editor.py.')
PAGE_URL = '/' + urllib.parse.quote(page)


class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == '/api/library':
            body = json.dumps(library()).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path in ('', '/'):
            self.send_response(302); self.send_header('Location', PAGE_URL); self.end_headers(); return
        super().do_GET()


socketserver.TCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer.daemon_threads = True
srv = None
for port in range(8000, 8020):
    try:
        srv = socketserver.ThreadingTCPServer(('127.0.0.1', port), H); break
    except OSError:
        continue
if srv is None:
    sys.exit('Ports 8000-8019 are all busy. Close other servers and try again.')

lib = library()
json.dump(lib, open('library.json', 'w'))  # kept for compatibility; the editor now uses /api/library
url = 'http://localhost:%d%s' % (port, PAGE_URL)
print('Found %d music tracks and %d hook SFX.\nEditor: %s\nKeep this window open while editing. Ctrl+C to stop.'
      % (len(lib['bgm']), len(lib['sfx']), url))
threading.Timer(0.8, lambda: webbrowser.open(url)).start()
try:
    srv.serve_forever()
except KeyboardInterrupt:
    pass
