"""Recover a failed publication from original CI artifacts, without rebuilding."""
import io
import os
import pathlib
import re
import subprocess
import zipfile

from artifact import require, verify


def validate_source_run(api, run_id, tag, commit=None):
    require(re.fullmatch(r'[1-9][0-9]*', str(run_id)), 'Invalid recovery run ID')
    run = api.repo('GET', '/actions/runs/' + str(run_id))
    require(run['event'] == 'push' and run['workflow_id'] == 'main.yml' and run['prettyref'] == tag,
            'Recovery source must be the original tag-push release workflow')
    require(re.fullmatch(r'[0-9a-f]{40}', run['commit_sha']), 'Invalid source commit')
    if commit:
        require(run['commit_sha'] == commit, 'Recovery run commit differs from transported artifact')
    jobs = api.repo('GET', '/actions/runs/' + str(run_id) + '/jobs')
    for name in ('build', 'minecraft-smoke'):
        matches = [job for job in jobs if job['name'] == name]
        require(len(matches) == 1 and matches[0]['status'] == 'success', 'Original ' + name + ' job did not pass')
    return run


def extract(data, destination):
    destination = pathlib.Path(destination)
    require(not destination.exists(), 'Recovery destination already exists: ' + str(destination))
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for member in archive.infolist():
            original = member.orig_filename
            path = pathlib.PurePosixPath(original)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in original and
                    ':' not in original, 'Unsafe recovery ZIP member: ' + original)
            require((member.external_attr >> 16) & 0o170000 != 0o120000, 'Recovery ZIP contains a symlink')
        destination.mkdir(parents=True)
        archive.extractall(destination)


def main():
    from release import API, release_tag
    api = API('https://pubcode.archuser.org/api/v1', os.environ['RELEASE_TOKEN'])
    run_id = os.environ['RECOVERY_RUN_ID']
    tag = release_tag('push', 'refs/tags/' + os.environ['RECOVERY_RELEASE_TAG'])
    run = validate_source_run(api, run_id, tag)
    # Main checkouts may omit tags even with full branch history. Fetch only the
    # requested immutable tag; do not force-update an existing local tag.
    subprocess.run(['git', 'fetch', '--no-tags', 'origin',
                    'refs/tags/' + tag + ':refs/tags/' + tag], check=True)
    artifacts = api.repo('GET', '/actions/runs/' + run_id + '/artifacts')
    for name, directory in (('distributable', 'ci-dist'), ('minecraft-evidence', 'smoke-result')):
        matches = [a for a in artifacts if a['name'] == name and not a['expired']]
        require(len(matches) == 1, 'Expected one unexpired original artifact: ' + name)
        data = api.repo('GET', '/actions/artifacts/' + str(matches[0]['id']) + '/zip', raw=True)
        extract(data, directory)
    record, _ = verify('ci-dist')
    require(record['commit'] == run['commit_sha'], 'Recovered artifact commit differs from source run')
    print('Recovered original tested artifact:', tag, record['commit'], record['sha256'])


if __name__ == '__main__':
    main()
