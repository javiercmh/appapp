#!/usr/bin/env python3
"""Local Starter Demo workspace server. Use --mock-ai for offline AI simulation."""
import argparse
import base64
import binascii
import json
import shutil
import time
import uuid
from http.server import HTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit


class DevServer(HTTPServer):
    def __init__(self, address, workspace, mock_ai=False):
        self.workspace = Path(workspace).resolve()
        self.mock_ai = mock_ai
        self.requests = {}
        super().__init__(address, Handler)


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(args[2].workspace), **kwargs)

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def send_head(self):
        # Always serve current source after edits, including files changed within one second.
        if 'If-Modified-Since' in self.headers:
            del self.headers['If-Modified-Since']
        name = unquote(urlsplit(self.path).path)
        target = self.workspace_path(name.lstrip('/')) if name != '/' else self.server.workspace
        if target.is_dir():
            for name in ('index.html', 'index.htm'):
                index = target / name
                if index.exists() and not index.resolve().is_relative_to(self.server.workspace):
                    raise PermissionError('Index escapes workspace')
        return super().send_head()

    def do_HEAD(self):
        try:
            self.validate_origin()
            path = unquote(urlsplit(self.path).path)
            if path != '/':
                self.workspace_path(path.lstrip('/'))
            super().do_HEAD()
        except (PermissionError, ValueError):
            self.send_error(403)

    def workspace_path(self, name):
        relative = Path(name)
        if not name or relative.is_absolute() or '..' in relative.parts:
            raise PermissionError('Invalid workspace path')
        path = (self.server.workspace / relative).resolve()
        if path == self.server.workspace or not path.is_relative_to(self.server.workspace):
            raise PermissionError('Path escapes workspace')
        return path

    def reply(self, value, status=200, raw=False):
        content = value.encode('utf-8') if raw else json.dumps(value).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'text/plain; charset=utf-8' if raw else 'application/json')
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def files(self):
        result = []
        for path in sorted(self.server.workspace.rglob('*')):
            if path.is_file() and path.resolve().is_relative_to(self.server.workspace):
                stat = path.stat()
                result.append(dict(name=path.relative_to(self.server.workspace).as_posix(),
                                   size=stat.st_size, lastModified=int(stat.st_mtime * 1000)))
        return result

    def do_GET(self):
        self.handle_request(False)

    def do_POST(self):
        self.handle_request(True)

    def validate_origin(self):
        host = self.headers.get('Host', '')
        if host not in {f'localhost:{self.server.server_port}', f'127.0.0.1:{self.server.server_port}'}:
            raise PermissionError('Invalid host')
        origin = self.headers.get('Origin')
        if origin and origin != f'http://{host}':
            raise PermissionError('Invalid origin')

    def handle_request(self, post):
        parsed = urlsplit(self.path)
        params = parse_qs(parsed.query, keep_blank_values=True)
        param = lambda key, default='': params.get(key, [default])[0]
        try:
            # Reject cross-origin writes, including browser requests to localhost.
            self.validate_origin()
            if parsed.path == '/__bridge/info' and not post:
                return self.reply(dict(server='app2-demo-dev', mockAi=self.server.mock_ai))
            if not parsed.path.startswith('/__bridge/'):
                if post:
                    return self.reply('Not found', 404)
                if unquote(parsed.path) != '/':
                    self.workspace_path(unquote(parsed.path).lstrip('/'))
                return super().do_GET()
            action = parsed.path.removeprefix('/__bridge/')
            body = b''
            if post:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 <= length <= 32 * 1024 * 1024:
                    return self.reply('Request too large', 413)
                body = self.rfile.read(length)
            if action == 'ai' and post:
                command = json.loads(body)
                if not isinstance(command, dict):
                    raise ValueError('Expected AI command object')
                return self.ai(command)
            if action in {'readFile', 'writeFile', 'writeFileBase64', 'deleteFile'}:
                path = self.workspace_path(param('name'))
                if action == 'readFile' and not post:
                    return self.reply(path.read_text('utf-8') if path.is_file() else '', raw=True)
                if action == 'deleteFile' and post:
                    path.unlink(missing_ok=True)
                    return self.reply(True)
                if action in {'writeFile', 'writeFileBase64'} and post:
                    if action == 'writeFileBase64':
                        text = body.decode('utf-8')
                        if text.startswith('data:'):
                            header, text = text.split(',', 1)
                            if not header.endswith(';base64'):
                                raise ValueError('Expected base64 data URL')
                        body = base64.b64decode(text, validate=True)
                    else:
                        body.decode('utf-8')
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(body)
                    return self.reply(True)
            if action in {'loadState', 'saveState', 'removeState'}:
                path = self.workspace_path('data/dev-preferences.json')
                prefs = json.loads(path.read_text('utf-8')) if path.exists() else {}
                if action == 'loadState' and not post:
                    return self.reply(prefs.get(param('key'), param('fallback')), raw=True)
                if post and action in {'saveState', 'removeState'}:
                    if action == 'saveState':
                        prefs[param('key')] = body.decode('utf-8')
                    else:
                        prefs.pop(param('key'), None)
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(json.dumps(prefs, indent=2), 'utf-8')
                    return self.reply(True)
            if action == 'listFiles' and not post:
                return self.reply(self.files())
            if action == 'storageStats' and not post:
                files = self.files()
                disk = shutil.disk_usage(self.server.workspace)
                return self.reply(dict(usableSpaceBytes=disk.free, totalSpaceBytes=disk.total,
                                       appFilesCount=len(files), appFilesBytes=sum(f['size'] for f in files)))
            self.reply('Not found', 404)
        except PermissionError as error:
            self.reply(str(error), 403)
        except (ValueError, UnicodeError, binascii.Error) as error:
            self.reply(str(error), 400)
        except OSError as error:
            self.reply(str(error), 500)

    def ai(self, command):
        def fail(code, message):
            self.reply(dict(error=dict(code=code, message=message)), 400)
        if not self.server.mock_ai:
            return fail('provider', 'Development mock is disabled')
        action = command.get('action')
        if action == 'providers':
            return self.reply(dict(providers=[dict(id='chatgpt', name='Development mock', connected=True)]))
        if action == 'models':
            if command.get('providerId') != 'chatgpt':
                return fail('provider', 'Unknown provider')
            return self.reply(dict(models=[dict(id='development-mock', name='Development mock (offline)', reasoningLevels=[])]))
        if action == 'start':
            data = command.get('data') or {}
            if not isinstance(data, dict):
                return fail('invalid_request', 'Expected request data object')
            if data.get('providerId') != 'chatgpt' or data.get('model') != 'development-mock' or not isinstance(data.get('input'), str) or data.get('reasoningEffort'):
                return fail('invalid_request', 'Select the development mock model without reasoning effort')
            if self.server.requests:
                return fail('request_in_progress', 'A request is running or awaiting acknowledgement')
            request_id = 'mock-' + uuid.uuid4().hex
            self.server.requests[request_id] = time.monotonic() + 1.5
            return self.reply(request_id)
        request_id = command.get('requestId')
        if not isinstance(request_id, str):
            return fail('invalid_request', 'Expected request ID')
        ready = self.server.requests.get(request_id)
        if ready is None:
            return fail('not_found', 'Request not found')
        if action == 'result':
            return self.reply('A subapp is a small web application running inside App².' if time.monotonic() >= ready else None)
        if action in {'cancel', 'acknowledge'}:
            if action == 'acknowledge' and time.monotonic() < ready:
                return fail('invalid_request', 'Request is still running')
            del self.server.requests[request_id]
            return self.reply(True)
        return fail('invalid_request', 'Unknown action')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('port', nargs='?', type=int, default=8888)
    parser.add_argument('--mock-ai', action='store_true', help='Enable deterministic offline AI responses')
    args = parser.parse_args()
    with DevServer(('127.0.0.1', args.port), Path(__file__).parent, args.mock_ai) as server:
        print(f'Starter Demo: http://localhost:{server.server_port}', flush=True)
        print(f'Reading and writing real files in: {server.workspace}', flush=True)
        print(f'Development mock AI: {"enabled (no ChatGPT usage)" if args.mock_ai else "disabled"}', flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print('\nServer stopped.')
