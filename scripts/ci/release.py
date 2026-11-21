"""Draft-first publication. Both targets stage before either is finalized.

Reruns preserve remote signatures when they authenticate the same bytes with the
same key. A published binary is never overwritten. No cross-API atomicity exists.
"""
import base64
import json
import os
import pathlib
import re
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

from artifact import require, verify

REPOSITORY = 'firebadnofire/dronesmc'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward API credentials to an attachment CDN.
        return None


class API:
    def __init__(self, base, token):
        require(base in ('https://pubcode.archuser.org/api/v1', 'https://api.github.com'), 'Unexpected API destination')
        require(token, 'Missing API token')
        self.base, self.token = base, token
        self.github = base == 'https://api.github.com'

    def request(self, method, path, body=None, missing=False, raw=False, content_type='application/json'):
        url = path if path.startswith('https://') else self.base + path
        require(urllib.parse.urlsplit(url).netloc in
                (('api.github.com', 'uploads.github.com') if self.github else ('pubcode.archuser.org',)),
                'Untrusted authenticated endpoint')
        if isinstance(body, dict):
            body = json.dumps(body).encode()
        req = urllib.request.Request(url, data=body, method=method, headers={
            'Authorization': 'Bearer ' + self.token, 'Accept': 'application/octet-stream' if raw else 'application/json',
            'Content-Type': content_type, 'User-Agent': 'DronesMC-CI/1.0'})
        try:
            with urllib.request.build_opener(NoRedirect).open(req, timeout=120) as response:
                data = response.read()
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            if raw and error.code in (301, 302, 303, 307, 308):
                target = error.headers['Location']
                require(target.startswith('https://'), 'Insecure asset redirect')
                # Public assets only; draft downloads must use API authentication at the origin.
                with urllib.request.urlopen(urllib.request.Request(target, headers={'User-Agent': 'DronesMC-CI/1.0'}), timeout=120) as response:
                    return response.read()
            detail = error.read().decode(errors='replace')[:1500].replace(self.token, '[redacted]')
            raise RuntimeError(f'{method} {urllib.parse.urlsplit(url).path}: HTTP {error.code}: {detail}') from None
        return data if raw else (json.loads(data) if data else None)

    def repo(self, method, path='', body=None, **kwargs):
        return self.request(method, '/repos/' + REPOSITORY + path, body, **kwargs)


def sync_github(api, sha):
    local = subprocess.check_output(['git', 'remote', 'get-url', 'origin'], text=True).strip()
    require(local in ('ssh://git@pubcode-ssh.archuser.org:2222/firebadnofire/dronesmc.git',
                      'https://pubcode.archuser.org/firebadnofire/dronesmc',
                      'https://pubcode.archuser.org/firebadnofire/dronesmc.git'), 'Unexpected source remote')
    require('GNU AFFERO GENERAL PUBLIC LICENSE' in pathlib.Path('LICENSE').read_text(), 'AGPL license missing')
    repo = api.repo('GET', missing=True)
    if repo is None:
        require(api.request('GET', '/user')['login'].lower() == 'firebadnofire', 'Token cannot create intended owner repository')
        repo = api.request('POST', '/user/repos', dict(name='dronesmc', private=False, auto_init=False,
                            description='AGPL-3.0 Minecraft Forge drone camera mod'))
    require(repo['full_name'].lower() == REPOSITORY and not repo['private'] and not repo.get('fork'),
            'GitHub destination must be the intended public non-fork repository')
    branch = api.repo('GET', '/branches/main', missing=True)
    require(branch is not None or repo.get('size', 0) == 0,
            'Existing GitHub repository has content but no main branch; inspect history manually')
    if branch is None:
        require(not api.repo('GET', '/branches?per_page=100'),
                'GitHub repository has other branches but no main; inspect history manually')
    if branch:
        # This check protects unrelated history even if the token can force-push.
        subprocess.run(['git', 'fetch', '--no-tags', 'https://github.com/' + REPOSITORY + '.git',
                        branch['commit']['sha']], check=True)
        subprocess.run(['git', 'merge-base', '--is-ancestor', branch['commit']['sha'], sha], check=True)
    with tempfile.TemporaryDirectory(prefix='dronesmc-git-') as temporary:
        askpass = pathlib.Path(temporary) / 'askpass.sh'
        askpass.write_text('#!/bin/sh\ncase "$1" in *Username*) printf "%s\\n" x-access-token ;; *) printf "%s\\n" "$GH_KEY" ;; esac\n')
        askpass.chmod(0o700)
        env = dict(os.environ, GIT_ASKPASS=str(askpass), GIT_TERMINAL_PROMPT='0')
        subprocess.run(['git', '-c', 'credential.helper=', 'push', 'https://github.com/' + REPOSITORY + '.git',
                        sha + ':refs/heads/main'], env=env, check=True)
    require(api.repo('GET', '/commits/' + sha)['sha'] == sha, 'GitHub source commit absent')
    license_file = api.repo('GET', '/contents/LICENSE?ref=' + sha)
    require('GNU AFFERO GENERAL PUBLIC LICENSE' in base64.b64decode(license_file['content']).decode(),
            'GitHub commit license mismatch')


def release_tag(event, ref):
    require(event == 'push' and ref.startswith('refs/tags/'), 'Publication requires a pushed v* or V* tag')
    tag = ref[len('refs/tags/'):]
    require(re.fullmatch(r'[vV][A-Za-z0-9._+-]*', tag) and '..' not in tag and not tag.endswith('.'),
            'Publication requires a safe v* or V* tag')
    return tag


def notes(record):
    tag_line = '\nTag: ' + record['release_tag'] if 'release_tag' in record else ''
    return ('Repository: ' + REPOSITORY + '\nCommit: ' + record['commit'] + '\nBranch: main' + tag_line + '\nVersion: ' +
            record['version'] + '\nBuild system: Gradle\nJava: 8\nMinecraft: 1.12.2\nForge: 14.23.5.2859\nLicense: AGPL-3.0\n\n' +
            f"Validation: Gradle build/reobfJar and {record['tests']} unit tests passed; exact-JAR Forge dedicated-server "
            'startup, loaded-mod initialization, >=10 seconds healthy after world readiness, and graceful shutdown passed. '
            'SHA-256 and detached GPG signatures verified.\n\nBeta software, published as an ordinary release intentionally. '
            'Runtime smoke testing does not verify rendering, flight controls, multiplayer synchronization, or full modpack compatibility.\n')


class Target:
    def __init__(self, api, record, files, verify_downloads=True):
        self.api, self.record, self.files = api, record, files
        self.verify_downloads = verify_downloads
        self.sha = record['commit']
        self.tag = record.get('release_tag', 'build-' + self.sha[:12])
        self.title = 'Development Build ' + self.sha[:12]
        self.body = notes(record)

    def tag_check(self):
        api = self.api
        if api.github:
            tag = api.repo('GET', '/git/ref/tags/' + self.tag, missing=True)
            if tag is None:
                api.repo('POST', '/git/refs', {'ref': 'refs/tags/' + self.tag, 'sha': self.sha})
            tag = api.repo('GET', '/git/ref/tags/' + self.tag)
            obj = tag['object']
            for _ in range(10):
                if obj['type'] != 'tag':
                    break
                obj = api.repo('GET', '/git/tags/' + obj['sha'])['object']
            require(obj['type'] == 'commit' and obj['sha'] == self.sha, 'GitHub tag collision')
        else:
            tag = api.repo('GET', '/tags/' + self.tag, missing=True)
            if tag is None:
                api.repo('POST', '/tags', {'tag_name': self.tag, 'target': self.sha})
            require(api.repo('GET', '/tags/' + self.tag)['commit']['sha'] == self.sha, 'Forgejo tag collision')

    def metadata(self, release):
        require(release['tag_name'] == self.tag and release['name'] == self.title and
                release['body'].replace('\r\n', '\n') == self.body and release['prerelease'] is False,
                'Release metadata mismatch; refusing to modify existing release')

    def asset_bytes(self, asset):
        if self.api.github:
            return self.api.repo('GET', '/releases/assets/' + str(asset['id']), raw=True)
        # Release browser URLs cannot resolve draft attachments. The UUID route
        # supports API authentication and enforces draft-release write access.
        require(asset.get('type', 'attachment') == 'attachment', 'External release assets are not supported')
        try:
            attachment = str(uuid.UUID(asset['uuid']))
        except (KeyError, ValueError, TypeError, AttributeError):
            raise RuntimeError('Forgejo asset is missing a valid attachment UUID: ' + asset['name']) from None
        origin = self.api.base.rsplit('/api/v1', 1)[0]
        return self.api.request('GET', origin + '/attachments/' + attachment, raw=True)

    def check_assets(self, release):
        assets = self.api.repo('GET', f"/releases/{release['id']}/assets?per_page=100&limit=100")
        inventory = {a['name']: a for a in assets}
        require(len(inventory) == len(assets) and set(inventory) == set(self.files), 'Remote asset inventory mismatch')
        if not self.verify_downloads:
            # Publication policy explicitly skips remote byte/signature verification.
            # Preserve existing assets on retries; never replace published binaries.
            for name, asset in inventory.items():
                if not name.endswith('.asc') and 'size' in asset:
                    require(asset['size'] == len(self.files[name]), 'Remote asset size differs: ' + name)
            return dict(self.files)
        with tempfile.TemporaryDirectory(prefix='dronesmc-verify-') as temporary:
            directory = pathlib.Path(temporary)
            downloaded = {}
            for name, asset in inventory.items():
                data = self.asset_bytes(asset)
                downloaded[name] = data
                if name.endswith('.asc'):
                    (directory / name).write_bytes(data)
                else:
                    require(data == self.files[name], 'Remote binary/checksum differs: ' + name)
                    (directory / name).write_bytes(data)
            for name in self.files:
                if not name.endswith('.asc'):
                    try:
                        subprocess.run(['gpg', '--batch', '--verify', str(directory / (name + '.asc')),
                                        str(directory / name)], check=True, capture_output=True)
                    except subprocess.CalledProcessError as error:
                        raise RuntimeError('Remote signature verification failed for ' + name + ': ' +
                                           error.stderr.decode(errors='replace')[-1500:]) from None
        return downloaded

    def stage(self):
        self.tag_check()
        release = self.api.repo('GET', '/releases/tags/' + self.tag, missing=True)
        if release is None:
            # GitHub documents the tag endpoint for published releases; list drafts
            # with authenticated API access so a partial-run draft can be resumed.
            for page in range(1, 1001):
                candidates = self.api.repo('GET', f'/releases?per_page=100&limit=100&page={page}')
                matching = [r for r in candidates if r['tag_name'] == self.tag]
                require(len(matching) <= 1, 'Duplicate tag releases')
                if matching:
                    release = matching[0]
                    break
                if len(candidates) < 100:
                    break
            else:
                raise RuntimeError('Release listing exceeded 1000 pages; inspect repository before retry')
        if release is None:
            release = self.api.repo('POST', '/releases', dict(tag_name=self.tag, target_commitish=self.sha,
                        name=self.title, body=self.body, draft=True, prerelease=False))
        self.metadata(release)
        rid = release['id']
        assets = self.api.repo('GET', f'/releases/{rid}/assets?per_page=100&limit=100')
        inventory = {a['name']: a for a in assets}
        require(len(inventory) == len(assets) and set(inventory) <= set(self.files), 'Unexpected assets')
        for name, data in self.files.items():
            if name in inventory:
                continue
            require(release['draft'], 'Published release is incomplete; manual recovery required')
            if self.api.github:
                url = 'https://uploads.github.com/repos/' + REPOSITORY + f'/releases/{rid}/assets?name=' + urllib.parse.quote(name)
                self.api.request('POST', url, data, content_type='application/octet-stream')
            else:
                boundary = uuid.uuid4().hex
                body = (f'--{boundary}\r\nContent-Disposition: form-data; name="attachment"; filename="{name}"\r\n'
                        'Content-Type: application/octet-stream\r\n\r\n').encode() + data + f'\r\n--{boundary}--\r\n'.encode()
                self.api.repo('POST', f'/releases/{rid}/assets', body, content_type='multipart/form-data; boundary=' + boundary)
        self.check_assets(release)
        return release

    def finalize(self, release):
        if release['draft']:
            self.api.repo('PATCH', f"/releases/{release['id']}", dict(draft=False, prerelease=False))
        self.verify_published(release)

    def verify_published(self, release):
        self.tag_check()
        completed = self.api.repo('GET', f"/releases/{release['id']}")
        self.metadata(completed)
        require(completed['draft'] is False, 'Release still a draft')
        self.check_assets(completed)


def publish_targets(targets, files):
    try:
        staged = []
        for target in targets:
            release = target.stage()
            downloaded = target.check_assets(release)
            if not staged:
                # Keep the first target's verified signatures on partial-run recovery.
                # GPG signatures can legitimately differ across independent signing runs.
                for name in files:
                    if name.endswith('.asc'):
                        files[name] = downloaded[name]
            else:
                require(downloaded == files, 'Platforms have different signed assets; manual recovery required')
            staged.append(release)
        for target, release in zip(targets, staged):
            target.finalize(release)
        for target, release in zip(targets, staged):
            target.verify_published(release)
    except Exception:
        print('Publication incomplete. One target may already be public. Rerun the same commit with the same signing key; '
              'verified existing assets will be retained. Never retarget tags or replace binaries.', flush=True)
        raise


def main():
    recovery_tag = os.environ.get('RECOVERY_RELEASE_TAG')
    if recovery_tag:
        require(os.environ['GITHUB_EVENT_NAME'] in ('workflow_dispatch', 'push') and
                os.environ['GITHUB_REF'] == 'refs/heads/main', 'Recovery requires trusted main workflow code')
        tag = release_tag('push', 'refs/tags/' + recovery_tag)
    else:
        tag = release_tag(os.environ['GITHUB_EVENT_NAME'], os.environ['GITHUB_REF'])
    record, jar = verify('ci-dist')
    checked_out = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    tagged = subprocess.check_output(['git', 'rev-parse', 'refs/tags/' + tag + '^{commit}'], text=True).strip()
    require(record['commit'] == tagged, 'Release tag differs from tested build')
    if recovery_tag:
        from recover import validate_source_run
        require(checked_out == subprocess.check_output(
            ['git', 'rev-parse', 'refs/remotes/origin/main'], text=True).strip(), 'Recovery checkout must be current main')
        validate_source_run(API('https://pubcode.archuser.org/api/v1', os.environ['RELEASE_TOKEN']),
                            os.environ['RECOVERY_RUN_ID'], tag, record['commit'])
    else:
        require(checked_out == tagged, 'Release checkout differs from tested build')
    subprocess.run(['git', 'merge-base', '--is-ancestor', tagged, 'refs/remotes/origin/main'], check=True)
    record['release_tag'] = tag
    require(os.environ['GITHUB_REPOSITORY'].lower() == REPOSITORY, 'Unexpected repository')
    result = json.loads(pathlib.Path('smoke-result/result.json').read_text())
    require(result.get('passed') is True and all(result.get(k) is True for k in
            ('startup', 'mod_loaded', 'observation', 'shutdown')) and result['sha256'] == record['sha256'] and
            result['commit'] == record['commit'] and result['version'] == record['version'] and result['healthy_seconds'] >= 10,
            'Smoke evidence does not match artifact')
    names = (jar.name, jar.name + '.asc', 'SHA256SUMS', 'SHA256SUMS.asc')
    files = {name: (pathlib.Path('ci-dist') / name).read_bytes() for name in names}
    for name in (jar.name, 'SHA256SUMS'):
        subprocess.run(['gpg', '--batch', '--verify', 'ci-dist/' + name + '.asc', 'ci-dist/' + name], check=True)
    subprocess.run(['sha256sum', '--check', '--strict', 'SHA256SUMS'], cwd='ci-dist', check=True)
    github = API('https://api.github.com', os.environ['GH_KEY'])
    forgejo = API('https://pubcode.archuser.org/api/v1', os.environ['RELEASE_TOKEN'])
    # Recovery runs from current trusted main while retaining the original tag.
    sync_github(github, checked_out)
    print('Remote download verification disabled by publication policy; checking metadata and asset inventory only.', flush=True)
    targets = [Target(forgejo, record, files, verify_downloads=False),
               Target(github, record, files, verify_downloads=False)]
    publish_targets(targets, files)
    print('Both normal releases published; metadata and asset inventories verified: ' + tag)


if __name__ == '__main__':
    main()
