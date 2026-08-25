#!/usr/bin/env python3
"""
dev_server.py — Local development server for App² subapps.
Serves static files and provides a local disk bridge for readFile, writeFile, and preferences,
so testing in your laptop's browser reads and writes real files (e.g. data/entries.json) on disk.
"""

import os
import sys
import json
import urllib.parse
from http.server import HTTPServer, SimpleHTTPRequestHandler

PORT = 8888
WORKSPACE_DIR = os.path.dirname(os.path.abspath(__file__))
PREFS_FILE = os.path.join(WORKSPACE_DIR, '.dev_preferences.json')

class App2DevHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=WORKSPACE_DIR, **kwargs)

    def _send_cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')

    def do_OPTIONS(self):
        self.send_response(204)
        self._send_cors()
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == '/__bridge/readFile':
            params = urllib.parse.parse_qs(parsed.query)
            name = params.get('name', [''])[0]
            file_path = os.path.normpath(os.path.join(WORKSPACE_DIR, name))
            if not file_path.startswith(WORKSPACE_DIR) or not os.path.isfile(file_path):
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain; charset=utf-8')
                self._send_cors()
                self.end_headers()
                self.wfile.write(b'')
                return
            try:
                with open(file_path, 'rb') as f:
                    content = f.read()
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain; charset=utf-8')
                self._send_cors()
                self.end_headers()
                self.wfile.write(content)
            except Exception as e:
                self.send_response(500)
                self._send_cors()
                self.end_headers()
                self.wfile.write(str(e).encode('utf-8'))
            return

        elif parsed.path == '/__bridge/loadState':
            params = urllib.parse.parse_qs(parsed.query)
            key = params.get('key', [''])[0]
            fallback = params.get('fallback', [''])[0]
            prefs = {}
            if os.path.isfile(PREFS_FILE):
                try:
                    with open(PREFS_FILE, 'r', encoding='utf-8') as f:
                        prefs = json.load(f)
                except Exception:
                    pass
            val = prefs.get(key, fallback)
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; charset=utf-8')
            self._send_cors()
            self.end_headers()
            self.wfile.write(str(val).encode('utf-8'))
            return

        return super().do_GET()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        content_len = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_len)

        if parsed.path == '/__bridge/writeFile':
            params = urllib.parse.parse_qs(parsed.query)
            name = params.get('name', [''])[0]
            file_path = os.path.normpath(os.path.join(WORKSPACE_DIR, name))
            if not file_path.startswith(WORKSPACE_DIR):
                self.send_response(403)
                self.end_headers()
                return
            os.makedirs(os.path.dirname(file_path), exist_ok=True)
            with open(file_path, 'wb') as f:
                f.write(body)
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; charset=utf-8')
            self._send_cors()
            self.end_headers()
            self.wfile.write(b'true')
            return

        elif parsed.path == '/__bridge/saveState':
            params = urllib.parse.parse_qs(parsed.query)
            key = params.get('key', [''])[0]
            val = body.decode('utf-8')
            prefs = {}
            if os.path.isfile(PREFS_FILE):
                try:
                    with open(PREFS_FILE, 'r', encoding='utf-8') as f:
                        prefs = json.load(f)
                except Exception:
                    pass
            prefs[key] = val
            with open(PREFS_FILE, 'w', encoding='utf-8') as f:
                json.dump(prefs, f, indent=2)
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; charset=utf-8')
            self._send_cors()
            self.end_headers()
            self.wfile.write(b'true')
            return

        self.send_response(404)
        self.end_headers()

if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else PORT
    server = HTTPServer(('0.0.0.0', port), App2DevHandler)
    print(f"🚀 DFKI Hours dev server running at: http://localhost:{port}")
    print(f"📁 Reading and writing real files in: {WORKSPACE_DIR}")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServer stopped.")
