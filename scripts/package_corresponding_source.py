#!/usr/bin/env python3
"""Package the tracked revision corresponding to a D2 CI APK build."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--output', default='dist')
args = parser.parse_args()
root = pathlib.Path(subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], text=True).strip())
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
# Fail if tracked source/build changes would make this archive differ from the build.
subprocess.run(['git', 'diff', '--exit-code', 'HEAD', '--', 'app/src', 'app/build.gradle.kts',
                'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'oneui-port', 'oneui-stub'], cwd=root, check=True)
out = pathlib.Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
archive = out / 'Kiosk-D2-Guardian-source.zip'
subprocess.run(['git', 'archive', '--format=zip', '--prefix=D2-source/', '-o', str(archive), revision],
               cwd=root, check=True)
with zipfile.ZipFile(archive) as z:
    required = ['LICENSE', 'THIRD_PARTY_NOTICES.txt', 'app/src/main/assets/LICENSE-AGPL-3.0.txt',
                'app/src/main/java/app/d2lock/MainActivity.kt', 'docs/SOURCE-DISTRIBUTION.md',
                'settings.gradle.kts', 'app/build.gradle.kts', '.github/workflows/android.yml']
    for name in required:
        if 'D2-source/' + name not in z.namelist():
            raise RuntimeError('Missing Corresponding Source component: ' + name)
    for name in z.namelist():
        if name.endswith(('.keystore', '.jks')) or name.endswith('/local.properties'):
            raise RuntimeError('Private build material unexpectedly tracked: ' + name)
    if z.testzip():
        raise RuntimeError('Corrupt source ZIP')
checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
(out / 'SOURCE-REVISION.txt').write_text(revision + '\n', encoding='utf-8')
(out / 'SOURCE-MANIFEST.json').write_text(json.dumps({
    'revision': revision,
    'archive': archive.name,
    'sha256': checksum,
    'source_url': 'https://github.com/SysopBP/SamsungLockD2-Public/tree/' + revision,
    'license': 'AGPL-3.0-only',
}, indent=2) + '\n', encoding='utf-8')
print('Packaged source revision ' + revision)
