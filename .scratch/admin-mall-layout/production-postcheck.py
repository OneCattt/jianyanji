import hashlib,json,subprocess
from pathlib import Path
base=Path('/opt/jianyanji')
r=json.loads((base/'release.json').read_text())
assert r['commit']=='64ddaf01025ddad0f3270ae85ddaf7a09efe1496'
backup=Path(r['backup'])
def sql(q):
 return subprocess.check_output(['mysql','--batch','--skip-column-names','jianyanji','-e',q],universal_newlines=True).strip()
counts={t:int(sql('SELECT COUNT(*) FROM `'+t+'`')) for t in sql('SHOW TABLES').splitlines()}
assert counts==json.loads((backup/'before-counts.json').read_text())
columns={'orders':'total_cents','payments':'cents','refunds':'total_cents','company_receipts':'amount_cents,cash_refunded_cents,cash_held_cents','wallet_lots':'issued_cents,remaining_cents,held_cents','wallet_spend_allocations':'spent_cents,refunded_cents','wallet_ledger':'delta_cents'}
financial={t:sql('SELECT COUNT(*),'+','.join('COALESCE(SUM(`'+c+'`),0)' for c in cols.split(','))+' FROM `'+t+'`') for t,cols in columns.items()}
assert financial==json.loads((backup/'before-financial-totals.json').read_text())
prices=sql("SELECT id,retail_cents,store_cents,principal_cents FROM skus WHERE product_type='regular' AND is_deleted=0 ORDER BY id")
assert prices+'\n'==(backup/'before-prices.tsv').read_text()
for path,expected in r['files'].items():
 assert hashlib.sha256((base/path).read_bytes()).hexdigest()==expected
services=[subprocess.check_output(['systemctl','is-active',s],universal_newlines=True).strip() for s in ['jianyanji','nginx']]
assert services==['active','active']
assert not Path('/etc/nginx/jianyanji/pause-api').exists()
migrations=sql('SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history WHERE success=1')
assert migrations==','.join(str(i) for i in range(1,15))
checks=[]
for path,expected in [('/admin',200),('/api/public/context',200),('/api/admin/orders',401),('/api/admin/pickups',401),('/api/admin/user-options',401),('/api/admin/user-labels?ids=1',401),('/api/admin/users/1/address-options',401)]:
 data=subprocess.check_output(['curl','--resolve','api.jianyanji.cn:443:127.0.0.1','--silent','--show-error','--max-time','15','-w','\n%{http_code}','https://api.jianyanji.cn'+path])
 body,code=data.rsplit(b'\n',1)
 assert int(code)==expected
 if path=='/api/public/context':assert json.loads(body.decode())['devAuth'] is False
 if path=='/admin':assert hashlib.sha256(body).hexdigest()==r['files']['dist/index.html']
 checks.append({'path':path,'status':int(code)})
print(json.dumps({'commit':r['commit'],'publishedAt':r['publishedAt'],'backup':r['backup'],'services':services,'apiPaused':False,'filesVerified':True,'businessCountsUnchanged':True,'financialTotalsUnchanged':True,'skuPricesUnchanged':True,'migrationVersions':migrations,'configChanges':False,'httpsChecks':checks,'productionBusinessLoginVerified':False,'miniprogramPublished':False},ensure_ascii=False,indent=2))
