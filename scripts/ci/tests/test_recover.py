import io
import pathlib
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import Mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from recover import extract, validate_source_run


class RecoveryTests(unittest.TestCase):
    def api(self, **updates):
        run = dict(event='push', workflow_id='main.yml', prettyref='v0.0.1', commit_sha='a' * 40)
        run.update(updates)
        api = Mock()
        api.repo.side_effect = [run, [dict(name='build', status='success'),
                                      dict(name='minecraft-smoke', status='success')]]
        return api

    def test_original_tag_run_is_accepted(self):
        self.assertEqual(validate_source_run(self.api(), '918', 'v0.0.1', 'a' * 40)['commit_sha'], 'a' * 40)

    def test_wrong_event_workflow_tag_commit_rejected(self):
        for updates in (dict(event='pull_request'), dict(workflow_id='other.yml'),
                        dict(prettyref='main'), dict(commit_sha='b' * 40)):
            with self.subTest(updates=updates), self.assertRaises(RuntimeError):
                validate_source_run(self.api(**updates), '918', 'v0.0.1', 'a' * 40)

    def test_failed_smoke_run_rejected(self):
        api = self.api()
        run = dict(event='push', workflow_id='main.yml', prettyref='v0.0.1', commit_sha='a' * 40)
        api.repo.side_effect = [run, [dict(name='build', status='success'),
                                     dict(name='minecraft-smoke', status='failure')]]
        with self.assertRaisesRegex(RuntimeError, 'minecraft-smoke job did not pass'):
            validate_source_run(api, '918', 'v0.0.1')

    def test_bad_run_id_rejected_before_api(self):
        api = Mock()
        for identifier in ('../918', '0', '', 'abc'):
            with self.subTest(identifier=identifier), self.assertRaises(RuntimeError):
                validate_source_run(api, identifier, 'v0.0.1')
        api.repo.assert_not_called()

    def test_zip_path_checks_and_original_bytes(self):
        for name in ('result.json', '../escape', '/absolute', 'C:/absolute', 'folder\\escape'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                buffer = io.BytesIO()
                with zipfile.ZipFile(buffer, 'w') as archive:
                    member = zipfile.ZipInfo('placeholder')
                    # Windows normalizes backslashes in ZipInfo's constructor.
                    member.filename = name
                    archive.writestr(member, b'original')
                target = pathlib.Path(temporary) / 'evidence'
                if name == 'result.json':
                    extract(buffer.getvalue(), target)
                    self.assertEqual((target / name).read_bytes(), b'original')
                    with self.assertRaisesRegex(RuntimeError, 'already exists'):
                        extract(buffer.getvalue(), target)
                else:
                    with self.assertRaisesRegex(RuntimeError, 'Unsafe recovery ZIP'):
                        extract(buffer.getvalue(), target)
                    self.assertFalse(target.exists())
