"""Verify shipped core components and emit a reproducible size inventory."""
import hashlib
import io
import json
import pathlib
import sys
import zipfile

root = pathlib.Path(sys.argv[1])
apk = root / 'Kiosk-D2-Guardian-debug.apk'
module = root / 'Kiosk-D2-Guardian-KernelSU-OPTIONAL.zip'
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    assert 'AndroidManifest.xml' in z.namelist()
    assert 'classes.dex' in z.namelist()
    assert 'META-INF/xposed/java_init.list' in z.namelist()
    assert 'app.d2lock.xposed.GuardianXposedBridge' in z.read('META-INF/xposed/java_init.list').decode()
    assert any(b'CredentialObserver' in z.read(n) for n in z.namelist() if n.endswith('.dex'))
    baseline = json.loads(pathlib.Path('D2_FEATURE_BASELINE.json').read_text())
    dex = b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
    for name in baseline['required_apk_classes']:
        assert name.encode() in dex, 'Missing feature class: ' + name
    for abi in baseline['required_native_abis']:
        assert 'lib/' + abi + '/libdexkit.so' in z.namelist(), 'Missing DexKit ABI: ' + abi
    apk_entries = [{'name': i.filename, 'bytes': i.file_size, 'compressed': i.compress_size} for i in z.infolist()]
with zipfile.ZipFile(module) as z:
    assert z.testzip() is None
    for name in ('module.prop', 'customize.sh', 'service.sh', 'common.sh', 'boot-completed.sh', 'action.sh', 'uninstall.sh', 'skip_mount', 'payload/PLACE_APK_HERE.txt', 'LICENSE', 'THIRD_PARTY_NOTICES.txt'):
        assert name in z.namelist(), name
    assert not any(n.startswith('system/') or n.endswith('.apk') for n in z.namelist())
    module_entries = [{'name': i.filename, 'bytes': i.file_size, 'sha256': hashlib.sha256(z.read(i)).hexdigest()} for i in z.infolist()]
(root / 'PACKAGE-INVENTORY.json').write_text(json.dumps({'apk': apk_entries, 'module': module_entries}, indent=2))
print('APK entry point, diagnostic observer, optional bridge and ZIP integrity verified')
