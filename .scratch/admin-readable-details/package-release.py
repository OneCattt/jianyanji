from pathlib import Path
import hashlib, json, shutil, tarfile, xml.etree.ElementTree as E, zipfile

root = Path.cwd()
build = root / 'artifacts/admin-readable-5f713d9'
source = build / 'source'
commit = '5f713d933ba7fb92b5e4bd651e5fe11bdd87c3f6'
previous = json.loads((root / '.scratch/admin-readable-details/production-preflight.json').read_text())
assert previous['services'] == ['active', 'active'] and previous['apiPaused'] is False
assert not (build / 'bundle').exists()
bundle = build / 'bundle'
(bundle / 'backend').mkdir(parents=True)
shutil.copy2(source / 'backend/target/jianyanji-api-0.1.0.jar', bundle / 'backend/jianyanji-api-0.1.0.jar')
shutil.copytree(source / 'dist', bundle / 'dist')
def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()
files = {p.relative_to(bundle).as_posix(): sha(p) for p in bundle.rglob('*') if p.is_file()}
migrations = {p.name: sha(p) for p in (source / 'backend/src/main/resources/db/migration').glob('*.sql')}
with zipfile.ZipFile(bundle / 'backend/jianyanji-api-0.1.0.jar') as jar:
    assert all(hashlib.sha256(jar.read('BOOT-INF/classes/db/migration/' + k)).hexdigest() == v for k, v in migrations.items())
    assert 'BOOT-INF/classes/com/jianyanji/identity/AdminUserLookupController.class' in jar.namelist()
counts = {k: 0 for k in ['tests', 'failures', 'errors', 'skipped']}
for p in (source / 'backend/target/surefire-reports').glob('TEST-*.xml'):
    result = E.parse(p).getroot()
    for k in counts:
        counts[k] += int(result.attrib[k])
assert counts['tests'] == 191 and counts['failures'] == counts['errors'] == 0
browser_path = source / '.scratch/admin-readable-details/packaged/browser-result.json'
browser = json.loads(browser_path.read_text())
assert len(browser['checks']) == 18 and browser['errors'] == []
http = json.loads((root / '.scratch/admin-readable-details/packaged-http-result.json').read_text())
assert len(http['checks']) == 14 and all(c['pass'] for c in http['checks'])
manifest = {
    'commit': commit, 'scope': ['backend', 'admin-frontend'],
    'expectedFrontendCommit': previous['frontendCommit'],
    'expectedBackendSha256': previous['backendSha256'],
    'files': files, 'migrations': migrations, 'newDatabaseMigrations': [],
    'configChanges': False, 'miniprogramPublished': False,
    'verification': {'java': counts, 'frontendPassed': 22, 'typecheck': 'passed',
                     'viteBuild': 'passed', 'browserChecks': 18, 'packagedHttpChecks': 14,
                     'viewportWidths': [1920, 1440, 1259, 1024, 390]}
}
(bundle / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
archive = root / 'artifacts/jianyanji-admin-search-5f713d9.tar.gz'
with tarfile.open(archive, 'w:gz') as tar:
    for p in bundle.iterdir():
        tar.add(p, arcname=p.name)
result = {'commit': commit, 'archive': str(archive), 'archiveSha256': sha(archive),
          'files': files, 'verification': manifest['verification']}
(root / '.scratch/admin-readable-details/bundle.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
shutil.copy2(browser_path, root / '.scratch/admin-readable-details/packaged-browser-result.json')
print(json.dumps(result, ensure_ascii=False, indent=2))
