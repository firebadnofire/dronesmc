import hashlib
import io
import json
import pathlib
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import smoke


class Process:
    def __init__(self, log, text, exit_early=False):
        self.log = log
        self.log.write(text)
        self.log.flush()
        self.stdin = io.StringIO()
        self.exit_code = 1 if exit_early else None
        self.terminated = False

    def poll(self):
        return self.exit_code

    def wait(self, timeout):
        if not self.terminated:
            assert self.stdin.getvalue() == 'stop\n'
            self.log.write('Stopping server\nSaving chunks\n')
            self.log.flush()
        self.exit_code = 0
        return 0

    def terminate(self):
        self.terminated = True


class SmokeTests(unittest.TestCase):
    def simulate(self, log, error=None, exit_early=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            jar = root / 'dronesmc-0.1.1-beta.jar'
            jar.write_bytes(b'test binary')
            record = dict(commit='a' * 40, version='0.1.1-beta', sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
            process = None

            def launch(*args, **kwargs):
                nonlocal process
                process = Process(kwargs['stdout'], log, exit_early)
                return process

            clock = iter(range(0, 10000, 5))
            with patch('smoke.verify', return_value=(record, jar)), \
                 patch('sys.argv', ['smoke.py', str(root), str(root / 'result')]), \
                 patch('smoke.subprocess.run', return_value=SimpleNamespace(stderr='version "1.8.0_472"')), \
                 patch('smoke.subprocess.Popen', side_effect=launch), \
                 patch('smoke.urllib.request.urlopen', return_value=io.BytesIO(b'installer')), \
                 patch('smoke.time.monotonic', side_effect=lambda: next(clock)), \
                 patch('smoke.time.sleep'):
                if error:
                    with self.assertRaisesRegex(RuntimeError, error):
                        smoke.main()
                else:
                    smoke.main()
            result = json.loads((root / 'result/result.json').read_text())
            if error:
                self.assertFalse(result['passed'])
                self.assertIsNotNone(process.poll())
            return result

    def test_independent_success_phases(self):
        result = self.simulate('Attempting connection with missing mods [minecraft, dronesmc] at SERVER\n'
                               'DronesMC initialized; physical drone entity and control channel registered\nDone (0.9s)!\n')
        self.assertTrue(all(result[k] for k in ('startup', 'mod_loaded', 'observation', 'shutdown', 'passed')))
        self.assertGreaterEqual(result['healthy_seconds'], 10)

    def test_world_ready_without_loaded_mod_fails(self):
        result = self.simulate('Done (0.9s)!\n', 'loaded/initialized evidence absent')
        self.assertTrue(result['startup'])
        self.assertFalse(result['observation'])

    def test_startup_timeout_cleans_up(self):
        self.simulate('Starting Forge\n', 'timed out')

    def test_unexpected_exit_fails(self):
        self.simulate('Done (0.9s)!\n', 'terminated unexpectedly', exit_early=True)

    def test_loader_errors_fail(self):
        self.simulate('LoaderException: failed to initialize\n', 'startup/runtime error')


if __name__ == '__main__':
    unittest.main()
