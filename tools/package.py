#!/usr/bin/env python3
"""Build and sign locally. The dedicated signing identity stays outside the source archive."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import zipfile

project = Path(__file__).resolve().parent.parent
workspace = project.parent.parent
java_home = Path('/Users/dmitry_baranov/Library/Java/JavaVirtualMachines/ms-25.0.4.1/Contents/Home')
android_home = Path('/Users/dmitry_baranov/Library/Android/sdk')
outputs = workspace/'outputs/HorizonTV'
outputs.mkdir(exist_ok=True)
apk = outputs/'horizon-tv-1.0.3.apk'
if '--source-only' not in sys.argv:
    signing = project / 'signing'
    signing.mkdir(exist_ok=True, mode=0o700)
    config_file = signing / 'identity.json'
    if config_file.exists():
        config = json.loads(config_file.read_text())
    else:
        config = {'alias': 'horizon', 'password': secrets.token_urlsafe(36)}
        config_file.write_text(json.dumps(config))
        config_file.chmod(0o600)
    env = dict(os.environ, HORIZON_PASSWORD=config['password'])
    key = signing / 'horizon-release.p12'
    if not key.exists():
        subprocess.run([str(java_home/'bin/keytool'), '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12', '-alias', config['alias'], '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Horizon TV Local, O=Horizon TV, C=RU', '-storepass:env', 'HORIZON_PASSWORD', '-keypass:env', 'HORIZON_PASSWORD'], env=env, check=True)
        key.chmod(0o600)
    subprocess.run(['zsh', str(project/'build.sh'), ':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleRelease', '--offline'], cwd=project, check=True)
    outputs = workspace/'outputs/HorizonTV'
    outputs.mkdir(exist_ok=True)
    apk = outputs/'horizon-tv-1.0.3.apk'
    unsigned = project/'app/build/outputs/apk/release/app-release-unsigned.apk'
    aligned = project/'app/build/outputs/apk/release/app-release-aligned.apk'
    tools = android_home/'build-tools/36.0.0'
    subprocess.run([str(tools/'zipalign'), '-f', '-p', '4', str(unsigned), str(aligned)], check=True)
    subprocess.run([str(tools/'apksigner'), 'sign', '--ks', str(key), '--ks-key-alias', config['alias'], '--ks-pass', 'env:HORIZON_PASSWORD', '--key-pass', 'env:HORIZON_PASSWORD', '--out', str(apk), str(aligned)], env=dict(env, JAVA_HOME=str(java_home)), check=True)
    verification = subprocess.run([str(tools/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], env=dict(env, JAVA_HOME=str(java_home)), check=True, capture_output=True, text=True).stdout
    (project/'signature-verification.txt').write_text(verification)
if not apk.exists():
    raise SystemExit('Build the signed APK before preparing its source archive')
release_metadata = {
    'version': '1.0.3',
    'size_mb': round(apk.stat().st_size / 1048576, 2),
    'size_bytes': apk.stat().st_size,
    'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'download': 'https://github.com/Lynatick/tv/releases/download/v1.0.3/horizon-tv-1.0.3.apk',
}
(project/'docs/release.json').write_text(json.dumps(release_metadata, indent=2) + '\n')
shutil.copyfile(project/'README-RU.md', outputs/'Инструкция.md')
archive = outputs/'horizon-tv-1.0.3-source.zip'
with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as z:
    for path in project.rglob('*'):
        relative = path.relative_to(project)
        if any(part in {'build', '.git', '.gradle', '.avd', 'signing', '__pycache__', '.module-cache'} for part in relative.parts):
            continue
        if not path.is_file() or path.name in {'local.properties', '.DS_Store'} or path.suffix in {'.log', '.jar'} and relative.parts[0]=='tools':
            continue
        if path.name.startswith('github-') and path.suffix == '.json' or path.name in {'signature-verification.txt', 'ui-latest.xml'}:
            continue
        if len(relative.parts) == 1 and path.suffix == '.png' and path.name != 'icon-original.png':
            continue
        z.write(path, Path('HorizonTV')/relative)
shutil.copyfile(project/'app/build/reports/lint-results-release.txt', outputs/'lint-report.txt')
checksums = ''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in (apk, archive))
(outputs/'SHA256SUMS.txt').write_text(checksums)
print('Signed APK:', apk)
print('Source archive:', archive)
print(checksums)
