import os
import shutil
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


BENCHMARK_SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "benchmark.sh"
TEST_TOKEN = "test-only-session-token"
TEST_KB_ID = "kb_benchmark_test"


class _ConsoleSessionStub(BaseHTTPRequestHandler):
    """模拟控制台检索端点：与 kb-rag-server 的 sa-token.token-name=satoken 一致，只认 satoken 请求头。"""

    received_headers = []

    def do_POST(self) -> None:  # noqa: N802 —— http.server 约定的方法名
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        type(self).received_headers.append(dict(self.headers))
        authorized = (
            self.path == f"/api/v1/kb/{TEST_KB_ID}/search"
            and self.headers.get("satoken") == TEST_TOKEN
        )
        self.send_response(200 if authorized else 401)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, format: str, *args: object) -> None:
        # 静默 http.server 默认的逐请求访问日志，避免污染测试输出
        pass


@unittest.skipUnless(shutil.which("bash") and shutil.which("curl"), "benchmark.sh needs bash and curl")
class BenchmarkScriptTest(unittest.TestCase):

    def setUp(self) -> None:
        _ConsoleSessionStub.received_headers = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _ConsoleSessionStub)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_sends_console_token_in_satoken_header(self) -> None:
        env = dict(
            os.environ,
            BASE_URL=f"http://127.0.0.1:{self.server.server_port}",
            KB_ID=TEST_KB_ID,
            TOKEN=TEST_TOKEN,
            TOTAL="2",
            CONCURRENCY="1",
        )

        result = subprocess.run(
            ["bash", str(BENCHMARK_SCRIPT)],
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(2, len(_ConsoleSessionStub.received_headers))
        for headers in _ConsoleSessionStub.received_headers:
            lowered = {name.lower(): value for name, value in headers.items()}
            self.assertEqual(TEST_TOKEN, lowered.get("satoken"))
            self.assertNotIn("authorization", lowered)


if __name__ == "__main__":
    unittest.main()
