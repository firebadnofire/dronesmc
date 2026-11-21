"""Validate the build contract and transport identity; standard library only."""
import hashlib
import json
import pathlib
import re
import struct
import subprocess
import sys
import zipfile


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def config(root=pathlib.Path('.')):
    text = (root / 'build.gradle').read_text()
    version = re.search(r"^version = '([^']+)'$", text, re.M).group(1)
    require(re.fullmatch(r'[0-9A-Za-z.+-]+', version), 'Unsafe project version')
    require(version.endswith('-beta'), 'Expected beta metadata; review CI contract before changing channel')
    require("minecraft 'net.minecraftforge:forge:1.12.2-14.23.5.2859'" in text,
            'Unexpected Minecraft/Forge baseline')
    return version


def inspect(jar, version):
    require(jar.name == 'dronesmc-' + version + '.jar', 'Unexpected distributable filename')
    with zipfile.ZipFile(jar) as archive:
        metadata = json.loads(archive.read('mcmod.info'))
        require(len(metadata) == 1 and metadata[0]['modid'] == 'dronesmc', 'Wrong mod ID')
        require(metadata[0]['version'] == version and metadata[0]['mcversion'] == '1.12.2',
                'Metadata version or Minecraft mismatch')
        require(('Implementation-Version: ' + version) in archive.read('META-INF/MANIFEST.MF').decode(),
                'Manifest version mismatch')
        entry = archive.read('dev/dronesmc/DronesMC.class')
        for value in (version, '[1.12.2]', 'required-after:forge@[14.23.5.2859,)'):
            require(value.encode() in entry, 'Compiled @Mod contract missing: ' + value)
        classes = [n for n in archive.namelist() if n.endswith('.class')]
        require(classes, 'No compiled classes')
        for name in classes:
            require(struct.unpack('>H', archive.read(name)[6:8])[0] == 52, 'Non-Java-8 class: ' + name)
        # Reobfuscated code uses SRG names; mapped development code uses these MCP names.
        entity = archive.read('dev/dronesmc/DroneEntity.class')
        require(b'func_' in entity and b'field_' in entity, 'No SRG reobfuscation evidence')
    return hashlib.sha256(jar.read_bytes()).hexdigest()


def verify(directory):
    directory = pathlib.Path(directory)
    record = json.loads((directory / 'build.json').read_text())
    jar = directory / ('dronesmc-' + record['version'] + '.jar')
    require(inspect(jar, record['version']) == record['sha256'], 'Artifact identity mismatch')
    expected = record['sha256'] + '  ' + jar.name + '\n'
    require((directory / 'SHA256SUMS').read_text() == expected, 'Checksum manifest mismatch')
    require(re.fullmatch('[0-9a-f]{40}', record['commit']), 'Invalid commit identity')
    return record, jar


def stage(directory):
    import shutil
    import xml.etree.ElementTree as ET
    version = config()
    jar = pathlib.Path('build/libs/dronesmc-' + version + '.jar')
    digest = inspect(jar, version)
    suites = [ET.parse(p).getroot() for p in pathlib.Path('build/test-results/test').glob('TEST-*.xml')]
    require(suites and sum(int(s.get('tests', 0)) for s in suites) > 0, 'No unit test evidence')
    require(all(int(s.get(k, 0)) == 0 for s in suites for k in ('failures', 'errors', 'skipped')),
            'Unit tests failed, errored or skipped')
    directory = pathlib.Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    require(not list(directory.iterdir()), 'Artifact staging directory must be empty')
    shutil.copyfile(jar, directory / jar.name)
    record = dict(version=version, sha256=digest, commit=subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], text=True).strip(), tests=sum(int(s.get('tests')) for s in suites))
    (directory / 'build.json').write_text(json.dumps(record, indent=2) + '\n')
    (directory / 'SHA256SUMS').write_text(digest + '  ' + jar.name + '\n')
    verify(directory)
    print(json.dumps(record))


if __name__ == '__main__':
    if sys.argv[1] == 'stage':
        stage(sys.argv[2])
    else:
        print(json.dumps(verify(sys.argv[2])[0]))
