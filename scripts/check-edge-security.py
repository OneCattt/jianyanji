#!/usr/bin/env python3
"""用真实 Nginx、隔离端口和假后端验证部署防护，不访问业务数据库或云服务。"""
import argparse
import concurrent.futures
import http.client
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
ARGS = argparse.ArgumentParser(description=__doc__)
ARGS.add_argument("--nginx", default=shutil.which("nginx"), help="待验证的 Nginx 可执行文件")
OPTIONS = ARGS.parse_args()
if not OPTIONS.nginx:
    ARGS.error("请安装 Nginx 或通过 --nginx 指定可执行文件")
NGINX = str(Path(OPTIONS.nginx).resolve())


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class Backend(http.server.BaseHTTPRequestHandler):
    """仅回显入口观察值，不执行登录、订单或支付逻辑。"""
    def do_GET(self):
        if self.headers.get("Content-Length"):
            self.rfile.read(int(self.headers["Content-Length"]))
        body = json.dumps(dict(self.headers)).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_POST = do_GET

    def log_message(self, *args):
        pass


class EdgeSecurityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="jyj-edge-test-")
        cls.directory = Path(cls.temp.name)
        cls.guard = cls.directory / "guard"
        cls.guard.mkdir()
        cls.http_port, cls.https_port = free_port(), free_port()
        cls.backend = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Backend)
        threading.Thread(target=cls.backend.serve_forever, daemon=True).start()
        for path in (ROOT / "deploy/nginx").glob("*.conf"):
            text = path.read_text().replace("/etc/nginx/jianyanji", str(cls.guard))
            (cls.guard / path.name).write_text(text)
        (cls.directory / "logs").mkdir()
        cert, key = cls.directory / "cert.pem", cls.directory / "key.pem"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-subj", "/CN=example.com", "-keyout", str(key), "-out", str(cert)
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        template = (ROOT / "deploy/nginx.conf").read_text()
        replacements = {
            "/etc/nginx/jianyanji": str(cls.guard),
            "/etc/letsencrypt/live/example.com/fullchain.pem": str(cert),
            "/etc/letsencrypt/live/example.com/privkey.pem": str(key),
            "/var/log/nginx": str(cls.directory / "logs"),
            "listen 80": f"listen 127.0.0.1:{cls.http_port}",
            "listen 443": f"listen 127.0.0.1:{cls.https_port}",
            "http://127.0.0.1:3100": f"http://127.0.0.1:{cls.backend.server_port}",
        }
        for before, after in replacements.items():
            template = template.replace(before, after)
        cls.conf = cls.directory / "nginx.conf"
        cls.conf.write_text(
            "daemon off;\nmaster_process off;\nworker_processes 1;\n"
            f"pid {cls.directory}/nginx.pid;\nerror_log {cls.directory}/startup.log;\n"
            "events { worker_connections 512; }\nhttp {\n" + template + "\n}\n"
        )
        cls.command = [NGINX, "-p", str(cls.directory) + "/", "-c", str(cls.conf)]
        subprocess.run(cls.command + ["-t"], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.backend.shutdown()
        cls.backend.server_close()
        cls.temp.cleanup()

    def setUp(self):
        for marker in ("pause-api", "pause-assets"):
            (self.guard / marker).unlink(missing_ok=True)
        (self.guard / "blocked-ips.conf").write_text("# 测试默认不封禁\n")
        self.start()

    def start(self):
        self.process = subprocess.Popen(self.command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                self.fail((self.directory / "startup.log").read_text())
            try:
                with socket.create_connection(("127.0.0.1", self.https_port), timeout=0.1):
                    return
            except OSError:
                time.sleep(0.025)
        self.fail("隔离 Nginx 启动超时")

    def stop(self):
        self.process.terminate()
        self.process.wait(timeout=5)

    def tearDown(self):
        if self.process.poll() is None:
            self.stop()

    def request(self, path="/api/public/context", method="GET", headers=None, body=None, secure=True):
        actual_headers = {"Host": "example.com", **(headers or {})}
        if secure:
            connection = http.client.HTTPSConnection(
                "127.0.0.1", self.https_port, timeout=5, context=ssl._create_unverified_context())
        else:
            connection = http.client.HTTPConnection("127.0.0.1", self.http_port, timeout=5)
        try:
            connection.request(method, path, body=body, headers=actual_headers)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def burst(self, path, count, method="GET"):
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            return list(pool.map(lambda _: self.request(path, method), range(count)))

    def assert_limited(self, responses):
        statuses = [item[0] for item in responses]
        self.assertIn(200, statuses)
        self.assertIn(429, statuses)
        self.assertTrue(set(statuses) <= {200, 429}, statuses)
        response = next(item for item in responses if item[0] == 429)
        self.assertEqual(json.loads(response[2])["code"], "TOO_MANY_REQUESTS")
        self.assertEqual(response[1]["Retry-After"], "60")
        self.assertEqual(response[1]["Cache-Control"], "no-store")
        self.assertEqual(len(json.loads(response[2])["requestId"]), 32)

    def test_proxy_headers_and_host(self):
        status, _, body = self.request("/api/public/context?token=DO_NOT_LOG", headers={
            "X-Real-IP": "198.51.100.1", "X-Forwarded-For": "203.0.113.1",
            "X-Forwarded-Proto": "http", "Forwarded": "for=203.0.113.1"})
        self.assertEqual(status, 200)
        headers = {key.lower(): value for key, value in json.loads(body).items()}
        self.assertEqual(headers["x-real-ip"], "127.0.0.1")
        self.assertEqual(headers["x-forwarded-for"], "127.0.0.1")
        self.assertEqual(headers["x-forwarded-proto"], "https")
        self.assertNotIn("forwarded", headers)
        self.assertEqual(headers["host"], "example.com")
        status, headers, _ = self.request("/admin", secure=False)
        self.assertEqual(status, 301)
        self.assertEqual(headers["Location"], "https://example.com/admin")
        for secure in (False, True):
            with self.assertRaises((http.client.RemoteDisconnected, ConnectionResetError)):
                self.request(headers={"Host": "evil.invalid"}, secure=secure)
        self.stop()
        access = (self.directory / "logs/jianyanji-access.log").read_text()
        self.assertNotIn("DO_NOT_LOG", access)
        for line in access.splitlines():
            json.loads(line)

    def test_admin_login_burst(self):
        self.assert_limited(self.burst("/api/admin/login", 20, "POST"))

    def test_wechat_login_burst(self):
        self.assert_limited(self.burst("/api/auth/wechat", 45, "POST"))

    def test_api_read_burst(self):
        self.assert_limited(self.burst("/api/public/context", 65))

    def test_api_write_burst(self):
        self.assert_limited(self.burst("/api/orders", 35, "POST"))

    def test_local_asset_burst(self):
        self.assert_limited(self.burst("/assets/example.jpg", 100))

    def test_pause_switches_preserve_payment_callback(self):
        env = {**os.environ, "JYJ_GUARD_DIR": str(self.guard)}
        def switch(action):
            subprocess.run(["bash", str(ROOT / "deploy/traffic-guard.sh"), action], env=env,
                           check=True, stdout=subprocess.DEVNULL)
        switch("pause-api")
        for path in ("/api/public/context", "/api/admin/login", "/api/auth/wechat"):
            status, _, body = self.request(path)
            self.assertEqual(status, 503)
            self.assertEqual(json.loads(body)["code"], "SERVICE_PAUSED")
        self.assertEqual(self.request("/api/payments/wechat/notify", "POST")[0], 200)
        self.assertEqual(self.request("/assets/example.jpg")[0], 200)
        switch("pause-assets")
        self.assertEqual(self.request("/assets/example.jpg")[0], 503)
        self.assertEqual(self.request("/admin")[0], 200)
        self.assertTrue(all(item[0] == 200 for item in self.burst("/api/payments/wechat/notify", 25, "POST")))
        switch("resume-api")
        switch("resume-assets")
        self.assertEqual(self.request()[0], 200)
        self.assertEqual(self.request("/assets/example.jpg")[0], 200)
        self.assertEqual(self.request("/api/auth/dev", "POST")[0], 404)

    def test_ip_block(self):
        self.stop()
        (self.guard / "blocked-ips.conf").write_text("deny 127.0.0.1;\n")
        self.start()
        self.assertEqual(self.request()[0], 403)
        self.assertEqual(self.request("/assets/example.jpg")[0], 403)

    def test_large_body(self):
        self.assertEqual(self.request("/api/orders", "POST", body=b"x" * 131073)[0], 413)


if __name__ == "__main__":
    unittest.main(argv=[__file__], verbosity=2)
