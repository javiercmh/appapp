import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/assets/starter_template/dev_server.py'
spec = importlib.util.spec_from_file_location('demo_server', SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'workspace'
        self.root.mkdir()
        self.server = module.DevServer(('127.0.0.1', 0), self.root, True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def call(self, route, body=None, headers=None):
        request = Request(f'http://127.0.0.1:{self.server.server_port}/{route}',
                          data=body.encode() if body is not None else None, headers=headers or {})
        try:
            with urlopen(request) as response:
                return response.status, response.read().decode()
        except HTTPError as error:
            with error:
                return error.code, error.read().decode()

    def ai(self, action, **extra):
        status, body = self.call('__bridge/ai', json.dumps(dict(action=action, **extra)))
        return status, json.loads(body)

    def test_files_and_preferences(self):
        self.assertEqual(self.call('__bridge/readFile?name=data/missing.json'), (200, ''))
        self.assertEqual(self.call('__bridge/writeFile?name=data/entries.json', '["é"]'), (200, 'true'))
        self.assertEqual((self.root / 'data/entries.json').read_text(), '["é"]')
        self.assertEqual(self.call('__bridge/readFile?name=data/entries.json'), (200, '["é"]'))
        self.call('__bridge/writeFile?name=data/empty', '')
        self.assertEqual(self.call('__bridge/readFile?name=data/empty'), (200, ''))
        image = b'\xff\xd8\x00\xfe\xff\xd9'
        self.call('__bridge/writeFileBase64?name=photos/a.jpg', 'data:image/jpeg;base64,' + base64.b64encode(image).decode())
        self.assertEqual((self.root / 'photos/a.jpg').read_bytes(), image)
        with urlopen(f'http://127.0.0.1:{self.server.server_port}/photos/a.jpg') as response:
            self.assertEqual(response.read(), image)
        self.assertEqual(self.call('__bridge/writeFileBase64?name=bad.jpg', '!')[0], 400)
        self.call('__bridge/saveState?key=accent', '#123456')
        self.assertEqual(self.call('__bridge/loadState?key=accent'), (200, '#123456'))
        self.assertEqual(json.loads((self.root / 'data/dev-preferences.json').read_text()), {'accent': '#123456'})
        self.call('__bridge/removeState?key=accent', '')
        self.assertEqual(self.call('__bridge/loadState?key=accent&fallback=blue'), (200, 'blue'))
        files = json.loads(self.call('__bridge/listFiles')[1])
        self.assertIn('photos/a.jpg', [f['name'] for f in files])
        stats = json.loads(self.call('__bridge/storageStats')[1])
        self.assertEqual(stats['appFilesCount'], len(files))
        self.assertEqual(stats['appFilesBytes'], sum(f['size'] for f in files))
        self.call('__bridge/deleteFile?name=photos/a.jpg', '')
        self.assertFalse((self.root / 'photos/a.jpg').exists())

    def test_containment_and_origin(self):
        outside = Path(self.temp.name) / 'outside'
        outside.write_text('private')
        (self.root / 'escape').symlink_to(outside)
        for name in ['../outside', 'data%5Coutside', 'escape']:
            self.assertEqual(self.call('__bridge/readFile?name=' + name)[0], 403)
            self.assertEqual(self.call('__bridge/writeFile?name=' + name, 'overwrite')[0], 403)
        self.assertEqual(self.call('escape')[0], 403)
        (self.root / 'index.html').symlink_to(outside)
        self.assertEqual(self.call('')[0], 403)
        (self.root / 'index.html').unlink()
        self.assertNotIn('escape', [f['name'] for f in json.loads(self.call('__bridge/listFiles')[1])])
        self.assertEqual(self.call('__bridge/writeFile?name=data/a', 'x', {'Origin': 'https://example.com'})[0], 403)
        self.assertEqual(self.call('__bridge/info', headers={'Host': 'evil.example'})[0], 403)
        self.assertEqual(self.call('__bridge/writeFile?name=%2Fdata%2Falias.json', '[1]'), (200, 'true'))
        self.assertEqual((self.root / 'data/alias.json').read_text(), '[1]')
        self.assertEqual(outside.read_text(), 'private')

    def test_mock_reports_sessions_unsupported_exactly_like_an_old_host(self):
        status, body = self.ai('capabilities')
        self.assertEqual((status, body['error']['code'], body['error']['message']),
                         (400, 'invalid_request', 'Unsupported AI command.'))
        self.assertEqual(self.server.requests, {})

    def test_mock_lifecycle(self):
        self.assertTrue(self.ai('providers')[1]['providers'][0]['connected'])
        model = self.ai('models', providerId='chatgpt')[1]['models'][0]
        data = dict(providerId='chatgpt', model=model['id'], input='Hello')
        status, request_id = self.ai('start', data=data)
        self.assertEqual(status, 200)
        self.assertEqual(self.ai('result', requestId=request_id), (200, None))
        self.assertEqual(self.ai('start', data=data)[1]['error']['code'], 'request_in_progress')
        self.assertEqual(self.ai('acknowledge', requestId=request_id)[0], 400)
        self.server.requests[request_id] = time.monotonic() - 1
        text = self.ai('result', requestId=request_id)[1]
        self.assertIn('subapp', text)
        self.assertEqual(self.ai('result', requestId=request_id)[1], text)
        self.assertEqual(self.ai('start', data=data)[0], 400)
        self.assertEqual(self.ai('acknowledge', requestId=request_id), (200, True))
        self.assertEqual(self.ai('result', requestId=request_id)[1]['error']['code'], 'not_found')
        request_id = self.ai('start', data=data)[1]
        self.assertEqual(self.ai('cancel', requestId=request_id), (200, True))
        self.assertEqual(self.ai('result', requestId=request_id)[0], 400)
        self.server.mock_ai = False
        self.assertFalse(json.loads(self.call('__bridge/info')[1])['mockAi'])
        self.assertEqual(self.ai('start', data=data)[0], 400)


class StorageContractTest(unittest.TestCase):
    def test_shared_native_path_fixture(self):
        fixtures = Path(__file__).resolve().parents[1] / 'app/src/test/resources/workspace-paths.json'
        for row in json.loads(fixtures.read_text()):
            with self.subTest(path=row['input']):
                if row['normalized'] is None:
                    with self.assertRaises(PermissionError):
                        module.normalize_workspace_path(row['input'])
                else:
                    self.assertEqual(module.normalize_workspace_path(row['input']), row['normalized'])

    def test_failed_publication_keeps_saved_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'data.json'
            target.write_text('saved')
            def fail():
                raise OSError('disk failure')
            with self.assertRaises(OSError):
                module.atomic_write(target, b'replacement', fail)
            self.assertEqual(target.read_text(), 'saved')
            self.assertEqual([p.name for p in Path(directory).iterdir()], ['data.json'])


if __name__ == '__main__':
    unittest.main()
