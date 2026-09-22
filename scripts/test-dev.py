#!/usr/bin/env python3
"""Isolated launcher contract tests; no Docker or real database is started."""
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parent.parent


class DevLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="publisher-launcher-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        shutil.copy(ROOT / "scripts/dev", self.root / "scripts/dev")
        self.bin = self.root / "jdk/bin"
        self.bin.mkdir(parents=True)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            self.port = sock.getsockname()[1]
        self.executable(self.root / "mvnw", '#!/bin/sh\nexit "${BUILD_EXIT:-0}"\n')
        # A real local HTTP process substitutes only Java/database boot for lifecycle tests.
        self.executable(self.bin / "java", """#!/usr/bin/env python3
import http.server, os, sys
if '-version' in sys.argv:
    print('Picked up JAVA_TOOL_OPTIONS: test-only', file=sys.stderr)
    print('openjdk version "17.0.1"', file=sys.stderr)
    sys.exit(0)
if os.environ.get('JAVA_EXIT'):
    sys.exit(int(os.environ['JAVA_EXIT']))
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}')
    def log_message(self, *args): pass
http.server.HTTPServer(('127.0.0.1', int(os.environ['SERVER_PORT'])), Handler).serve_forever()
""")
        self.env = {**os.environ, "JAVA_HOME": str(self.bin.parent),
                    "SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": str(self.port),
                    "PUBLISHER_SECURITY_MODE": "DISABLED", "DB_URL": "jdbc:postgresql://test/example",
                    "DB_USERNAME": "test", "DB_PASSWORD": "test"}

    @staticmethod
    def executable(path, content):
        path.write_text(content)
        path.chmod(0o755)

    def run_launcher(self, **changes):
        result = subprocess.run([str(self.root / "scripts/dev"), "run"],
                                env={**self.env, **changes}, capture_output=True, text=True, timeout=20)
        return result.returncode, result.stdout + result.stderr

    def test_missing_configuration_exits(self):
        code, output = self.run_launcher(DB_PASSWORD="")
        self.assertNotEqual(code, 0)
        self.assertIn("必须同时设置", output)

    def test_missing_dependency_exits(self):
        tools = self.root / "tools"
        tools.mkdir()
        for command in ("bash", "dirname", "python3", "head", "grep"):
            (tools / command).symlink_to(shutil.which(command))
        code, output = self.run_launcher(PATH=str(tools))
        self.assertNotEqual(code, 0)
        self.assertIn("缺少命令：curl", output)

    def test_invalid_port_and_unsafe_bind_exit(self):
        for changes in ({"SERVER_PORT": "0"}, {"SERVER_PORT": "65536"},
                        {"SERVER_PORT": "abc"}, {"SERVER_ADDRESS": "0.0.0.0"}):
            code, _ = self.run_launcher(**changes)
            self.assertNotEqual(code, 0)

    def test_occupied_port_is_not_taken_over(self):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", self.port))
            sock.listen()
            code, output = self.run_launcher()
            self.assertNotEqual(code, 0)
            self.assertIn("已被其他进程占用", output)

    def test_build_failure_exits(self):
        code, _ = self.run_launcher(BUILD_EXIT="2")
        self.assertEqual(code, 2)

    def test_application_failure_exits(self):
        code, output = self.run_launcher(JAVA_EXIT="1")
        self.assertNotEqual(code, 0)
        self.assertIn("应用启动失败", output)

    def test_start_health_repeat_and_shutdown(self):
        log = self.root / "run.log"
        with log.open("w") as output:
            process = subprocess.Popen([str(self.root / "scripts/dev"), "run"],
                                       env=self.env, stdout=output, stderr=subprocess.STDOUT)
            try:
                for _ in range(100):
                    if "应用已就绪" in log.read_text():
                        break
                    self.assertIsNone(process.poll(), log.read_text())
                    time.sleep(0.1)
                self.assertIn("应用已就绪", log.read_text())
                code, text = self.run_launcher()
                self.assertEqual(code, 0, text)
                self.assertIn("已经运行", text)
            finally:
                process.terminate()
                process.wait(timeout=10)
        self.assertEqual(process.returncode, 143)
        with socket.socket() as sock:
            self.assertNotEqual(sock.connect_ex(("127.0.0.1", self.port)), 0)


if __name__ == "__main__":
    unittest.main()
