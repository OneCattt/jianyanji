"""在既有服务器发布无数据库迁移的后台更新，校验产物与备份，失败仅回退应用。"""

import datetime
import gzip
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
import zipfile


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def sql(query):
    return subprocess.check_output(
        ["mysql", "--batch", "--skip-column-names", "jianyanji", "-e", query],
        universal_newlines=True,
    ).strip()


def counts():
    return {
        table: int(sql("SELECT COUNT(*) FROM `" + table + "`"))
        for table in sql("SHOW TABLES").splitlines()
    }


def financial_totals():
    columns = {
        "orders": "total_cents",
        "payments": "cents",
        "refunds": "total_cents",
        "company_receipts": "amount_cents,cash_refunded_cents,cash_held_cents",
        "wallet_lots": "issued_cents,remaining_cents,held_cents",
        "wallet_spend_allocations": "spent_cents,refunded_cents",
        "wallet_ledger": "delta_cents",
    }
    return {
        table: sql(
            "SELECT COUNT(*),"
            + ",".join("COALESCE(SUM(`" + c + "`),0)" for c in cols.split(","))
            + " FROM `" + table + "`"
        )
        for table, cols in columns.items()
    }


def https(path, expected=200, method="GET", data=None, headers=None):
    # 经过本机Nginx、真实证书及域名，输出不包含会话或请求正文。
    args = ["curl", "--resolve", "api.jianyanji.cn:443:127.0.0.1",
            "--silent", "--show-error", "--max-time", "15", "-X", method]
    for key, value in (headers or {}).items():
        args += ["-H", key + ": " + value]
    if data is not None:
        args += ["-H", "Content-Type: application/json", "--data-binary", "@-"]
    args += ["-w", "\n%{http_code}", "https://api.jianyanji.cn" + path]
    value = subprocess.check_output(args, input=data)
    body, status = value.rsplit(b"\n", 1)
    assert int(status) == expected, "HTTPS状态不符：" + path + " " + status.decode()
    return body


commit, archive_sha = sys.argv[1:3]
assert re.match(r"^[0-9a-f]{40}$", commit)
name = "admin-search-" + commit[:7]
base = Path("/opt/jianyanji")
stage = base / "releases" / name
archive = Path("/tmp/jianyanji-" + name + ".tar.gz")
assert digest(archive) == archive_sha, "上传压缩包校验失败"
assert not stage.exists(), "发布目录已存在，禁止覆盖已有发布目录"
stage.mkdir(mode=0o750)
with tarfile.open(str(archive), "r:gz") as bundle:
    for member in bundle.getmembers():
        assert not member.name.startswith("/") and ".." not in Path(member.name).parts
        assert member.isfile() or member.isdir(), "压缩包包含非普通文件"
    bundle.extractall(str(stage))
manifest = json.loads((stage / "manifest.json").read_text())
assert manifest["commit"] == commit
for path, expected in manifest["files"].items():
    assert digest(stage / path) == expected, "发布文件校验失败：" + path
previous = json.loads((base / "release.json").read_text())
assert previous["frontendCommit"] == manifest["expectedFrontendCommit"], "线上前端已变化"
jar = base / "backend/jianyanji-api-0.1.0.jar"
assert digest(jar) == manifest["expectedBackendSha256"], "线上后端已变化"
with zipfile.ZipFile(str(jar)) as old:
    prefix = "BOOT-INF/classes/db/migration/"
    migrations = {
        Path(path).name: hashlib.sha256(old.read(path)).hexdigest()
        for path in old.namelist() if path.startswith(prefix) and path.endswith(".sql")
    }
assert len(migrations) == 14 and len(manifest["migrations"]) == 14
assert all(manifest["migrations"].get(k) == v for k, v in migrations.items()), "历史迁移发生变化"
assert sql("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history WHERE success=1") == ",".join(str(i) for i in range(1, 15))
assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0") == "0"
version = subprocess.check_output(["/opt/java8/bin/java", "-version"], stderr=subprocess.STDOUT, universal_newlines=True)
assert 'version "1.8.' in version, "服务器必须使用JDK 8"
for service in ["jianyanji", "nginx"]:
    run(["systemctl", "is-active", "--quiet", service])
account = pwd.getpwnam("jianyanji")
for directory, dirs, files in os.walk(str(stage)):
    os.chown(directory, 0, account.pw_gid)
    os.chmod(directory, 0o750)
    for filename in files:
        os.chown(os.path.join(directory, filename), 0, account.pw_gid)
        os.chmod(os.path.join(directory, filename), 0o640)

env = os.environ.copy()
for line in Path("/etc/jianyanji.env").read_text().splitlines():
    line = line.strip()
    if line and not line.startswith("#"):
        key, value = line.split("=", 1)
        env[key.strip()] = " ".join(shlex.split(value, comments=True))
assert env.get("DEV_AUTH", "false").lower() == "false"
assert env.get("APP_PRODUCTION", "").lower() == "true"
assert env.get("SEED_DEMO", "false").lower() != "true"
assert env.get("BOOTSTRAP_ADMIN", "false").lower() != "true"
assert not env.get("LEGACY_IMPORT_FILE") and env.get("MIGRATE_ONLY", "false").lower() != "true"

stamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime("%Y%m%dT%H%M%S%z")
backup = Path("/var/backups/jianyanji") / (stamp + "-" + name)
backup.mkdir(mode=0o700)
flag = Path("/etc/nginx/jianyanji/pause-api")
assert not flag.exists(), "API已在维护，停止本次部署"
flag.touch()
switched = False
try:
    https("/api/public/context", 503)
    run(["systemctl", "stop", "jianyanji"])
    before, money = counts(), financial_totals()
    (backup / "before-counts.json").write_text(json.dumps(before))
    (backup / "before-financial-totals.json").write_text(json.dumps(money))
    prices = sql("SELECT id,retail_cents,store_cents,principal_cents FROM skus WHERE product_type='regular' AND is_deleted=0 ORDER BY id")
    (backup / "before-prices.tsv").write_text(prices + "\n")
    with gzip.open(str(backup / "database.sql.gz"), "wb") as output:
        process = subprocess.Popen([
            "mysqldump", "--single-transaction", "--skip-lock-tables", "--skip-add-drop-table",
            "--no-tablespaces", "--set-gtid-purged=OFF", "--hex-blob",
            "--default-character-set=utf8mb4", "jianyanji",
        ], stdout=subprocess.PIPE)
        shutil.copyfileobj(process.stdout, output)
        assert process.wait() == 0, "数据库备份失败"
    run(["gzip", "-t", str(backup / "database.sql.gz")])
    assert (backup / "database.sql.gz").stat().st_size > 1000
    with tarfile.open(str(backup / "application.tar.gz"), "w:gz") as output:
        for relative in ["backend", "dist", "release.json"]:
            output.add(str(base / relative), arcname=relative)
        output.add("/etc/jianyanji.env", arcname="jianyanji.env")
        output.add("/etc/nginx/conf.d/jianyanji.conf", arcname="nginx.conf")
    for path in backup.iterdir():
        os.chmod(str(path), 0o600)
    print("数据库、原三档价格与旧应用备份完成：" + str(backup), flush=True)

    assert counts() == before, "备份期间业务记录发生变化"
    assert financial_totals() == money, "备份期间历史资金汇总发生变化"
    assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0") == "0"
    print("本次无数据库迁移，原结构及业务数据保持不变", flush=True)

    fresh = base / ("dist.next-" + name)
    shutil.copytree(str(stage / "dist"), str(fresh))
    # 保留旧哈希资源，避免已打开的后台页面加载资源失败。
    for path in (base / "dist/assets").iterdir():
        target = fresh / "assets" / path.name
        if path.is_file() and not target.exists():
            shutil.copy2(str(path), str(target))
    for directory, dirs, files in os.walk(str(fresh)):
        os.chown(directory, 0, account.pw_gid)
        os.chmod(directory, 0o750)
        for filename in files:
            os.chown(os.path.join(directory, filename), 0, account.pw_gid)
            os.chmod(os.path.join(directory, filename), 0o640)
    switched = True
    os.rename(str(base / "dist"), str(backup / "dist"))
    os.rename(str(fresh), str(base / "dist"))
    shutil.copy2(str(stage / "backend/jianyanji-api-0.1.0.jar"), str(jar) + ".next")
    os.chown(str(jar) + ".next", 0, account.pw_gid)
    os.chmod(str(jar) + ".next", 0o640)
    os.replace(str(jar) + ".next", str(jar))
    run(["systemctl", "start", "jianyanji"])
    context = None
    for attempt in range(50):
        try:
            with urllib.request.urlopen("http://127.0.0.1:3100/api/public/context", timeout=2) as response:
                context = json.loads(response.read().decode())
                break
        except (OSError, ValueError):
            time.sleep(1)
    assert context is not None and context["devAuth"] is False, "服务启动或生产模式验证失败"
    for path, expected in manifest["files"].items():
        assert digest(base / path) == expected, "线上文件哈希不符：" + path
    assert hashlib.sha256(https("/admin")).hexdigest() == manifest["files"]["dist/index.html"]
    for path, expected in manifest["files"].items():
        if path.startswith("dist/assets/"):
            assert hashlib.sha256(https("/" + path[5:])).hexdigest() == expected
    # API仍暂停；通过内部端口验证路由与匿名隔离，不写业务数据。
    checks = []
    for path, expected in [("/api/mini/storage", 401), ("/api/admin/storage", 401),
                           ("/api/pickups/1", 401), ("/api/wallet", 401), ("/api/offers", 200), ("/api/admin/product-options?kind=sku", 401),
                           ("/api/admin/product-options?kind=lot&userId=1", 401)]:
        try:
            with urllib.request.urlopen("http://127.0.0.1:3100" + path, timeout=5) as response:
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
        assert status == expected, "接口验证失败：" + path
        checks.append({"path": path, "status": status})
    for service in ["jianyanji", "nginx"]:
        run(["systemctl", "is-active", "--quiet", service])
    result = dict(previous)
    result.update(manifest)
    result.update({
        "frontendCommit": commit, "backendCommit": commit, "previousCommit": previous["commit"],
        "backup": str(backup), "publishedAt": datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
        "scope": ["backend", "admin-frontend"], "miniprogramPublished": False,
        "newDatabaseMigrations": [], "configChanges": False,
    })
    temporary = base / "release.json.next"
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    os.chown(str(temporary), 0, account.pw_gid)
    os.chmod(str(temporary), 0o640)
    os.replace(str(temporary), str(base / "release.json"))
    flag.unlink()
    public_context = json.loads(https("/api/public/context"))
    assert public_context["devAuth"] is False
    https("/api/admin/storage", 401)
    report = {
        "commit": commit, "release": str(stage), "backup": str(backup),
        "publishedAt": result["publishedAt"], "migrations": [],
        "previousMigrationsUnchanged": True, "businessRowsPreserved": True,
        "financialTotalsPreserved": True, "files": manifest["files"], "servicesActive": True,
        "httpsArtifactHashesVerified": True, "apiPaused": False, "checks": checks,
        "devAuth": False, "wechatConfigured": public_context.get("wechatConfigured"),
        "miniprogramPublished": False,
    }
    (stage / "deployment-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
except Exception:
    # 本脚本只允许既有迁移完全一致的产物；回退仅替换应用，绝不恢复或覆盖业务库。
    if switched:
        run(["systemctl", "stop", "jianyanji"])
        with tarfile.open(str(backup / "application.tar.gz"), "r:gz") as saved:
            for relative in ["backend/jianyanji-api-0.1.0.jar", "release.json"]:
                target = base / relative
                with saved.extractfile(relative) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                os.chown(str(target), 0, account.pw_gid)
                os.chmod(str(target), 0o640)
        if (backup / "dist").exists():
            if (base / "dist").exists():
                os.rename(str(base / "dist"), str(stage / "failed-dist"))
            os.rename(str(backup / "dist"), str(base / "dist"))
    run(["systemctl", "start", "jianyanji"])
    healthy = False
    for attempt in range(30):
        try:
            with urllib.request.urlopen("http://127.0.0.1:3100/api/public/context", timeout=2) as response:
                healthy = json.loads(response.read().decode()).get("devAuth") is False
            if healthy:
                break
        except (OSError, ValueError):
            time.sleep(1)
    if healthy and flag.exists():
        flag.unlink()
    print("发布失败，应用恢复结果=" + str(healthy) + "；未恢复或覆盖业务库。备份=" + str(backup), flush=True)
    raise
