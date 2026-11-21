import copy
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from release import Target, notes, sync_github, publish_targets, release_tag


class FakeAPI:
    def __init__(self, github=False):
        self.github = github
        self.sha = 'a' * 40
        self.tag = None
        self.release = None
        self.assets = {}
        self.calls = []
        self.fail_finalize = False

    def repo(self, method, path='', body=None, **kwargs):
        self.calls.append((method, path))
        if path.startswith('/git/ref/tags/') or path.startswith('/tags/'):
            if self.tag is None:
                return None
            return {'object': {'type': 'commit', 'sha': self.tag}, 'commit': {'sha': self.tag}}
        if path in ('/git/refs', '/tags'):
            self.tag = body.get('sha', body.get('target'))
            return {}
        if path.startswith('/releases/assets/'):
            return self.assets[path.split('/')[-1]]
        if path.startswith('/releases/tags/'):
            return copy.deepcopy(self.release)
        if path.startswith('/releases?'):
            return [copy.deepcopy(self.release)] if self.release else []
        if path == '/releases':
            self.release = dict(body, id=1)
            return copy.deepcopy(self.release)
        if '/assets?' in path:
            return [dict(name=k, id=k, browser_download_url='https://pubcode.archuser.org/' + k) for k in self.assets]
        if method == 'POST' and path.endswith('/assets'):
            import re
            name = re.search(b'filename="([^"]+)"', body).group(1).decode()
            self.assets[name] = body.split(b'\r\n\r\n', 1)[1].rsplit(b'\r\n--', 1)[0]
            return {}
        if path == '/releases/1':
            if method == 'PATCH':
                if self.fail_finalize:
                    raise RuntimeError('simulated finalization failure')
                self.release.update(body)
            return copy.deepcopy(self.release)
        raise AssertionError((method, path))

    def request(self, method, url, body=None, **kwargs):
        if method == 'POST' and url.startswith('https://uploads.github.com/'):
            from urllib.parse import parse_qs, urlsplit
            self.assets[parse_qs(urlsplit(url).query)['name'][0]] = body
            return {}
        raise AssertionError((method, url))


class PublisherTests(unittest.TestCase):
    def setUp(self):
        self.api = FakeAPI()
        self.record = dict(commit='a' * 40, version='0.1.1-beta', tests=14)
        self.files = {'dronesmc-0.1.1-beta.jar': b'binary', 'dronesmc-0.1.1-beta.jar.asc': b'signature',
                      'SHA256SUMS': b'checksum', 'SHA256SUMS.asc': b'signature'}
        self.target = Target(self.api, self.record, self.files)

    def test_draft_upload_verify_then_normal_release(self):
        with patch.object(self.target, 'asset_bytes', side_effect=lambda a: self.api.assets[a['name']]), \
             patch('release.subprocess.run'):
            release = self.target.stage()
            self.assertTrue(release['draft'])
            self.assertFalse(release['prerelease'])
            self.target.finalize(release)
            self.assertFalse(self.api.release['draft'])
            self.assertIn('Version: 0.1.1-beta', self.api.release['body'])
            self.assertEqual(self.api.tag, self.record['commit'])

    def test_tag_collision_never_retargets(self):
        self.api.tag = 'b' * 40
        with self.assertRaisesRegex(RuntimeError, 'collision'):
            self.target.stage()
        self.assertEqual(self.api.tag, 'b' * 40)
        self.assertIsNone(self.api.release)

    def test_publication_without_downloads_still_checks_inventory_and_finalizes(self):
        target = Target(self.api, self.record, self.files, verify_downloads=False)
        with patch.object(target, 'asset_bytes', side_effect=AssertionError('Download forbidden')):
            target.finalize(target.stage())
            self.assertFalse(self.api.release['draft'])
            target.finalize(target.stage())
            self.assertEqual(self.api.assets, self.files)
            del self.api.assets['SHA256SUMS']
            with self.assertRaisesRegex(RuntimeError, 'inventory mismatch'):
                target.check_assets(self.api.release)

    def test_changed_published_binary_is_rejected(self):
        with patch.object(self.target, 'asset_bytes', side_effect=lambda a: self.api.assets[a['name']]), \
             patch('release.subprocess.run'):
            release = self.target.stage()
            self.target.finalize(release)
            self.api.assets['dronesmc-0.1.1-beta.jar'] = b'other build'
            with self.assertRaisesRegex(RuntimeError, 'differs'):
                self.target.stage()

    def test_signature_variation_is_verified_not_overwritten(self):
        with patch.object(self.target, 'asset_bytes', side_effect=lambda a: self.api.assets[a['name']]), \
             patch('release.subprocess.run') as gpg:
            release = self.target.stage()
            self.target.finalize(release)
            self.api.assets['SHA256SUMS.asc'] = b'older valid signature'
            self.target.stage()
            self.assertEqual(self.api.assets['SHA256SUMS.asc'], b'older valid signature')
            self.assertTrue(gpg.called)

    def test_bad_signature_fails_closed(self):
        with patch.object(self.target, 'asset_bytes', side_effect=lambda a: self.api.assets[a['name']]), \
             patch('release.subprocess.run', side_effect=RuntimeError('invalid signature')):
            with self.assertRaisesRegex(RuntimeError, 'invalid signature'):
                self.target.stage()
            self.assertTrue(self.api.release['draft'])

    def test_partial_failure_can_resume(self):
        with patch.object(self.target, 'asset_bytes', side_effect=lambda a: self.api.assets[a['name']]), \
             patch('release.subprocess.run'):
            release = self.target.stage()
            self.api.fail_finalize = True
            with self.assertRaisesRegex(RuntimeError, 'finalization'):
                self.target.finalize(release)
            self.api.fail_finalize = False
            self.target.finalize(self.target.stage())
            self.assertFalse(self.api.release['draft'])

    def test_github_collision(self):
        self.api.github = True
        self.api.tag = 'b' * 40
        with self.assertRaisesRegex(RuntimeError, 'GitHub tag collision'):
            self.target.tag_check()

    def test_github_upload_and_download_then_normal_release(self):
        self.api.github = True
        with patch('release.subprocess.run'):
            release = self.target.stage()
            self.target.finalize(release)
        self.assertEqual(self.api.assets, self.files)
        self.assertFalse(self.api.release['draft'])

    def test_unexpected_asset_fails(self):
        self.api.assets['unmanaged.zip'] = b'unknown'
        with self.assertRaisesRegex(RuntimeError, 'Unexpected assets'):
            self.target.stage()

    def test_metadata_mismatch_fails(self):
        self.api.release = dict(id=1, tag_name=self.target.tag, name=self.target.title,
                                body='different commit', prerelease=False, draft=True)
        with self.assertRaisesRegex(RuntimeError, 'metadata mismatch'):
            self.target.stage()

    def test_release_notes_are_version_derived(self):
        self.record['version'] = '0.2.0-beta'
        self.assertIn('Version: 0.2.0-beta', notes(self.record))

    def test_release_uses_original_tag_case_on_both_platforms(self):
        for tag in ('v0.0.1', 'V0.0.1'):
            for github in (False, True):
                with self.subTest(tag=tag, github=github):
                    api = FakeAPI(github)
                    record = dict(self.record, release_tag=tag)
                    target = Target(api, record, self.files)
                    with patch.object(target, 'asset_bytes', side_effect=lambda a: api.assets[a['name']]), \
                         patch('release.subprocess.run'):
                        release = target.stage()
                        target.finalize(release)
                    self.assertEqual(release['tag_name'], tag)
                    self.assertIn('Tag: ' + tag, release['body'])
                    self.assertFalse(api.release['prerelease'])

    def test_tag_event_guard(self):
        for tag in ('v0.0.1', 'V0.0.1', 'v0.1.1-beta'):
            self.assertEqual(release_tag('push', 'refs/tags/' + tag), tag)
        for event, ref in [('push', 'refs/heads/main'), ('workflow_dispatch', 'refs/tags/v0.0.1'),
                           ('pull_request', 'refs/tags/V0.0.1'), ('push', 'refs/tags/build-123'),
                           ('push', 'refs/tags/v../unsafe')]:
            with self.subTest(event=event, ref=ref), self.assertRaises(RuntimeError):
                release_tag(event, ref)

    def test_annotated_github_tag_resolves_to_expected_commit(self):
        from unittest.mock import Mock
        api = Mock(github=True)
        ref = dict(object=dict(type='tag', sha='b' * 40))
        api.repo.side_effect = [ref, ref, dict(object=dict(type='commit', sha=self.record['commit']))]
        target = Target(api, dict(self.record, release_tag='V0.0.1'), self.files)
        target.tag_check()
        self.assertEqual(api.repo.call_args.args, ('GET', '/git/tags/' + 'b' * 40))

    def test_forgejo_draft_uses_authenticated_uuid_route(self):
        from unittest.mock import Mock
        api = Mock(github=False, base='https://pubcode.archuser.org/api/v1')
        api.request.return_value = b'original draft binary'
        asset = dict(id=42, name='dronesmc.jar', uuid='12345678-1234-1234-1234-123456789abc',
                     browser_download_url='https://pubcode.archuser.org/hidden/draft/url')
        target = Target(api, self.record, self.files)
        self.assertEqual(target.asset_bytes(asset), b'original draft binary')
        api.request.assert_called_once_with('GET',
            'https://pubcode.archuser.org/attachments/12345678-1234-1234-1234-123456789abc', raw=True)

    def test_missing_uuid_and_external_asset_fail_closed(self):
        from unittest.mock import Mock
        api = Mock(github=False)
        target = Target(api, self.record, self.files)
        for asset in (dict(name='bad.jar'), dict(name='bad.jar', uuid='../wrong'),
                      dict(name='external.jar', type='external')):
            with self.subTest(asset=asset), self.assertRaises(RuntimeError):
                target.asset_bytes(asset)
        api.request.assert_not_called()

    def test_second_target_staging_failure_prevents_finalization(self):
        from unittest.mock import Mock
        first, second = Mock(), Mock()
        first.check_assets.return_value = dict(self.files)
        second.stage.side_effect = RuntimeError('upload failed')
        with self.assertRaisesRegex(RuntimeError, 'upload failed'):
            publish_targets([first, second], self.files)
        first.finalize.assert_not_called()
        second.finalize.assert_not_called()

    def test_both_drafts_verified_before_first_finalize(self):
        from unittest.mock import Mock
        first, second = Mock(), Mock()
        first.check_assets.return_value = dict(self.files)
        second.check_assets.return_value = dict(self.files)
        def finalize(_):
            second.stage.assert_called_once()
            second.check_assets.assert_called_once()
        first.finalize.side_effect = finalize
        publish_targets([first, second], self.files)
        first.verify_published.assert_called_once()
        second.verify_published.assert_called_once()


class SyncTests(unittest.TestCase):
    def test_unrelated_repository_without_main_is_rejected(self):
        from unittest.mock import Mock
        api = Mock()
        api.repo.side_effect = [dict(full_name='firebadnofire/dronesmc', private=False, fork=False, size=50), None]
        with patch('release.subprocess.check_output', return_value='https://pubcode.archuser.org/firebadnofire/dronesmc.git'), \
             patch('release.subprocess.run') as git:
            with self.assertRaisesRegex(RuntimeError, 'inspect history manually'):
                sync_github(api, 'a' * 40)
            git.assert_not_called()

    def test_divergent_main_cannot_be_pushed(self):
        from unittest.mock import Mock
        import subprocess
        api = Mock()
        api.repo.side_effect = [dict(full_name='firebadnofire/dronesmc', private=False, fork=False, size=50),
                                dict(commit=dict(sha='b' * 40))]
        with patch('release.subprocess.check_output', return_value='https://pubcode.archuser.org/firebadnofire/dronesmc.git'), \
             patch('release.subprocess.run', side_effect=[None, subprocess.CalledProcessError(1, 'merge-base')]) as git:
            with self.assertRaises(subprocess.CalledProcessError):
                sync_github(api, 'a' * 40)
            self.assertFalse(any('push' in call.args[0] for call in git.call_args_list))

    def test_source_sync_uses_askpass_without_token_in_command(self):
        from unittest.mock import Mock
        import base64
        api = Mock()
        api.repo.side_effect = [dict(full_name='firebadnofire/dronesmc', private=False, fork=False, size=0), None, [],
                                dict(sha='a' * 40), dict(content=base64.b64encode(b'GNU AFFERO GENERAL PUBLIC LICENSE').decode())]
        with patch('release.subprocess.check_output', return_value='https://pubcode.archuser.org/firebadnofire/dronesmc.git'), \
             patch('release.subprocess.run') as git, patch.dict('os.environ', {'GH_KEY': 'test-token'}):
            sync_github(api, 'a' * 40)
            command = git.call_args.args[0]
            self.assertIn('push', command)
            self.assertNotIn('test-token', ' '.join(command))
            self.assertNotIn('--force', command)

    def test_small_repository_with_other_branches_is_rejected(self):
        from unittest.mock import Mock
        api = Mock()
        api.repo.side_effect = [dict(full_name='firebadnofire/dronesmc', private=False, fork=False, size=0),
                                None, [dict(name='master')]]
        with patch('release.subprocess.check_output', return_value='https://pubcode.archuser.org/firebadnofire/dronesmc.git'), \
             patch('release.subprocess.run') as git:
            with self.assertRaisesRegex(RuntimeError, 'other branches but no main'):
                sync_github(api, 'a' * 40)
            git.assert_not_called()


if __name__ == '__main__':
    unittest.main()
