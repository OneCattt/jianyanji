"""打包实际提交的后台产物，不读取工作区小程序内容。"""
from pathlib import Path
import hashlib, json, shutil, subprocess, sys, tarfile, xml.etree.ElementTree as E, zipfile
root = Path.cwd()
commit = sys.argv[1]
assert subprocess.check_output(['git','rev-parse',commit],text=True).strip() == commit
build = root / ('artifacts/admin-mall-' + commit[:7])
source = build / 'source'
previous = json.loads((root / '.scratch/admin-mall-layout/production-preflight.json').read_text())
assert previous['services'] == ['active','active'] and previous['apiPaused'] is False
bundle = build / 'bundle'
assert not bundle.exists()
(bundle / 'backend').mkdir(parents=True)
shutil.copy2(source / 'backend/target/jianyanji-api-0.1.0.jar', bundle / 'backend/jianyanji-api-0.1.0.jar')
shutil.copytree(source / 'dist', bundle / 'dist')
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
files = {p.relative_to(bundle).as_posix(): sha(p) for p in bundle.rglob('*') if p.is_file()}
migrations = {p.name: sha(p) for p in (source / 'backend/src/main/resources/db/migration').glob('*.sql')}
assert len(migrations) == 14
with zipfile.ZipFile(bundle / 'backend/jianyanji-api-0.1.0.jar') as jar:
    assert all(hashlib.sha256(jar.read('BOOT-INF/classes/db/migration/'+k)).hexdigest()==v for k,v in migrations.items())
    with zipfile.ZipFile(root / 'artifacts/admin-readable-5f713d9/source/backend/target/jianyanji-api-0.1.0.jar') as old:
        def classes(archive): return {n:hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist() if n.startswith('BOOT-INF/classes/') and not n.endswith('/')}
        assert classes(jar) == classes(old), '后端逻辑/资源与当前线上基线不同，须重新核查'
counts = {k:0 for k in ['tests','failures','errors','skipped']}
for p in (source / 'backend/target/surefire-reports').glob('TEST-*.xml'):
    result=E.parse(p).getroot()
    for k in counts:counts[k]+=int(result.attrib[k])
assert counts == {'tests':191,'failures':0,'errors':0,'skipped':13}
browser=json.loads((root/'.scratch/admin-mall-layout/packaged-browser-result.json').read_text())
assert len(browser['checks'])==19 and not browser['errors']
assert all(not c['overflow'] and not c['headings'] and not c['errors'] for c in browser['checks'])
manifest={'commit':commit,'scope':['backend','admin-frontend'],'expectedFrontendCommit':previous['frontendCommit'],'expectedBackendSha256':previous['backendSha256'],'files':files,'migrations':migrations,'newDatabaseMigrations':[],'configChanges':False,'miniprogramPublished':False,'verification':{'java':counts,'javaVersion':'1.8.0_452','backendClassesUnchanged':True,'frontendPassed':22,'typecheck':'passed','viteBuild':'passed','packagedBrowserChecks':19,'browserData':'只读模拟API'}}
(bundle/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
archive=root/('artifacts/jianyanji-admin-search-'+commit[:7]+'.tar.gz')
with tarfile.open(archive,'w:gz') as tar:
    for p in bundle.iterdir():tar.add(p,arcname=p.name)
result={'commit':commit,'archive':str(archive),'archiveSha256':sha(archive),'files':files,'verification':manifest['verification']}
(root/'.scratch/admin-mall-layout/bundle.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
