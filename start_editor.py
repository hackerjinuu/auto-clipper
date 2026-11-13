"""Run this once (double-click or `python start_editor.py`) and keep the window open.
Keep it in the same folder as the editor, the "bgm" folder, the "best hook" folder, the "transitionsfx" folder and the "broll" folder.

It serves the editor in your browser AND answers the editor's requests. Every time you pick
Auto BGM / Auto Hook SFX in the page, the page calls /api/library, this script re-scans both
folders (so newly added tracks are picked up with no restart), and the editor scores the files
and applies the result to the timeline by itself."""
import http.server, socketserver, json, os, re, sys, urllib.parse, webbrowser, threading

here = os.path.dirname(os.path.abspath(__file__)); os.chdir(here)
AUD = ('.mp3', '.wav', '.ogg', '.m4a', '.aac', '.flac', '.opus')
TRN = AUD + ('.mp4', '.mov', '.webm', '.m4v')   # transition SFX may be audio files or mp4 clips with sound
VIS = ('.mp4', '.mov', '.webm', '.m4v', '.gif', '.png', '.jpg', '.jpeg', '.webp')   # B-roll video + images
FOLDERS = {'bgm': 'bgm', 'sfx': 'best hook'}


def scan(d, exts=None):
    """Return every audio file under folder d (sub-folders included) as {url, name, size, mtime}."""
    exts = exts or AUD
    base = os.path.join(here, d)
    out = []
    if os.path.isdir(base):
        for root, _, files in os.walk(base):
            for f in files:
                if f.lower().endswith(exts):
                    full = os.path.join(root, f)
                    rel = os.path.relpath(full, here).replace(os.sep, '/')
                    st = os.stat(full)
                    out.append({'url': urllib.parse.quote(rel), 'name': f,
                                'size': st.st_size, 'mtime': int(st.st_mtime)})
    return sorted(out, key=lambda x: x['url'].lower())


def broll_dir():
    """Find the B-roll folder next to this script: broll / b-roll / B-Roll / b_roll / b roll ..."""
    for n in sorted(os.listdir(here)):
        if os.path.isdir(os.path.join(here, n)) and ''.join(c for c in n.lower() if c.isalnum()) in ('broll', 'brolls'):
            return n
    return None


def scan_broll():
    d = broll_dir(); out = []
    if d:
        for root, _, files in os.walk(os.path.join(here, d)):
            for f in files:
                if f.lower().endswith(VIS):
                    full = os.path.join(root, f)
                    rel = os.path.relpath(full, here).replace(os.sep, '/')
                    st = os.stat(full)
                    out.append({'url': urllib.parse.quote(rel), 'name': f, 'size': st.st_size, 'mtime': int(st.st_mtime)})
    return d, sorted(out, key=lambda x: x['name'].lower())


def trans_dir():
    """transitionsfx folder (also accepts the spelling 'transationsfx', 'transitions sfx', ...)."""
    for n in sorted(os.listdir(here)):
        if os.path.isdir(os.path.join(here, n)) and re.fullmatch(r'trans[a-z]*sfx', ''.join(c for c in n.lower() if c.isalnum())):
            return n
    return None


def library():
    out = {k: scan(v) for k, v in FOLDERS.items()}
    out['trans'] = scan(trans_dir() or 'transitionsfx', TRN)
    return out


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
        if path == '/api/broll':
            d, files = scan_broll()
            body = json.dumps({'folder': d or 'broll', 'files': files}).encode()
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
_bd, _bf = scan_broll()
print('Found %d music tracks, %d hook SFX, %d transition SFX and %d B-roll clips (folder: %s).\nEditor: %s\nKeep this window open while editing. Ctrl+C to stop.'
      % (len(lib['bgm']), len(lib['sfx']), len(lib['trans']), len(_bf), _bd or 'none - create a folder named "broll"', url))
threading.Timer(0.8, lambda: webbrowser.open(url)).start()
try:
    srv.serve_forever()
except KeyboardInterrupt:
    pass