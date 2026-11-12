"""End-to-end tests: the real server process, speaking MCP over stdio to the SDK's own client, with a
real local HTTP server standing in for ContractIQ. These prove that stdout carries only protocol
messages (the server would not even initialize otherwise) and that stderr, where logs go, never
contains tokens, passwords or contract text.
"""

from __future__ import annotations

import asyncio
import json
import os
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

SERVER_DIR = Path(__file__).resolve().parent.parent
TOKEN = "FAKE.STDIO.TOKEN-abc123"
PASSWORD = "FAKE-stdio-password-xyz789"
SECRET_TEXT = "SECRET stdio contract text about pricing"
EXPECTED_TOOLS = {"list_contracts", "get_contract", "get_analysis", "list_expiring_contracts", "analyze_contract"}


class FakeContractIQ(BaseHTTPRequestHandler):
    """A tiny stand-in for the ContractIQ API. Records what it was asked, keeping no secrets in logs."""

    requests: list[tuple[str, str, str | None, bytes]] = []

    def log_message(self, format, *args):  # keep test output quiet
        pass

    def _send(self, status: int, body: dict):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _record(self, body: bytes = b""):
        FakeContractIQ.requests.append((self.command, self.path, self.headers.get("Authorization"), body))

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length)
        self._record(body)
        if self.path == "/api/v1/auth/login":
            self._send(200, {"token": TOKEN, "tokenType": "Bearer", "expiresInSeconds": 1800})
        elif self.path == "/api/v1/contracts/1/analyze":
            self._send(202, {"contractId": 1, "status": "ANALYZING"})
        else:
            self._send(404, {"title": "Not Found"})

    def do_GET(self):
        self._record()
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self._send(401, {"title": "Unauthorized"})
        elif self.path.startswith("/api/v1/contracts?"):
            self._send(200, {"items": [{"id": 1, "title": "Supplies", "vendorName": "Acme", "status": "ANALYZED"}],
                             "page": 0, "size": 20, "totalElements": 1, "totalPages": 1})
        elif self.path == "/api/v1/contracts/1":
            self._send(200, {"id": 1, "title": "Supplies", "vendorName": "Acme", "status": "ANALYZED",
                             "contractText": SECRET_TEXT, "createdAt": "x", "updatedAt": "y"})
        else:
            self._send(404, {"title": "Not Found"})


@pytest.fixture
def fake_api():
    FakeContractIQ.requests = []
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeContractIQ)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()


def server_env(base_url: str) -> dict[str, str]:
    env = {k: v for k, v in os.environ.items() if not k.startswith("CONTRACTIQ_")}
    env.update({"CONTRACTIQ_BASE_URL": base_url, "CONTRACTIQ_USERNAME": "mcp-uploader",
                "CONTRACTIQ_PASSWORD": PASSWORD})
    return env


async def run_session(base_url: str, stderr_file, scenario):
    params = StdioServerParameters(command=sys.executable, args=["-m", "contractiq_mcp"],
                                   env=server_env(base_url), cwd=str(SERVER_DIR))
    async with stdio_client(params, errlog=stderr_file) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            return await scenario(session)


def text_of(result) -> str:
    return result.content[0].text


def test_the_real_server_speaks_clean_mcp_over_stdio_and_logs_nothing_sensitive(fake_api, tmp_path):
    stderr_path = tmp_path / "server-stderr.log"

    async def scenario(session: ClientSession):
        tools = {tool.name for tool in (await session.list_tools()).tools}
        listed = await session.call_tool("list_contracts", {"status": "ANALYZED"})
        detail = await session.call_tool("get_contract", {"contract_id": 1, "include_text": True})
        analysis = await session.call_tool("analyze_contract", {"contract_id": 1})
        missing = await session.call_tool("get_contract", {"contract_id": 99})
        unknown = await session.call_tool("approve_contract", {"contract_id": 1})
        return tools, listed, detail, analysis, missing, unknown

    with open(stderr_path, "w") as stderr_file:
        tools, listed, detail, analysis, missing, unknown = asyncio.run(
            run_session(fake_api, stderr_file, scenario))

    # Protocol and tools.
    assert tools == EXPECTED_TOOLS
    assert not listed.is_error and json.loads(text_of(listed))["contracts"][0]["id"] == 1
    assert not detail.is_error and json.loads(text_of(detail))["untrusted_contract_text"] == SECRET_TEXT
    assert not analysis.is_error and json.loads(text_of(analysis))["status"] == "ANALYZING"
    # A clear error, not a stack trace.
    assert missing.is_error and "Contract 99 was not found" in text_of(missing)
    assert "Traceback" not in text_of(missing)
    # There is no approve tool to call.
    assert unknown.is_error

    # What the server sent to the API: one login with the account, then bearer-authenticated calls.
    login_calls = [r for r in FakeContractIQ.requests if r[1] == "/api/v1/auth/login"]
    assert len(login_calls) == 1
    assert json.loads(login_calls[0][3]) == {"username": "mcp-uploader", "password": PASSWORD}
    assert all(r[2] == f"Bearer {TOKEN}" for r in FakeContractIQ.requests if r[1] != "/api/v1/auth/login")
    assert not any("/review" in r[1] for r in FakeContractIQ.requests)

    # Logs go to stderr and contain nothing sensitive (and no httpx request lines).
    log = stderr_path.read_text()
    assert "ContractIQ login -> HTTP 200" in log, log
    assert TOKEN not in log
    assert PASSWORD not in log
    assert SECRET_TEXT not in log
    assert "HTTP Request:" not in log
    assert "Traceback" not in log


def test_an_unreachable_api_gives_a_clear_tool_error_not_a_crash(tmp_path):
    async def scenario(session: ClientSession):
        return await session.call_tool("list_contracts", {})

    with open(tmp_path / "err.log", "w") as stderr_file:
        result = asyncio.run(run_session("http://127.0.0.1:1", stderr_file, scenario))

    assert result.is_error
    assert "Could not reach" in text_of(result)
    assert "Traceback" not in text_of(result)
    assert "Traceback" not in (tmp_path / "err.log").read_text()


def test_starting_without_configuration_fails_clearly_with_nothing_on_stdout():
    env = {k: v for k, v in os.environ.items() if not k.startswith("CONTRACTIQ_")}

    completed = subprocess.run([sys.executable, "-m", "contractiq_mcp"], cwd=SERVER_DIR, env=env,
                               capture_output=True, text=True, timeout=30, input="")

    assert completed.returncode == 2
    assert completed.stdout == ""
    assert "CONTRACTIQ_BASE_URL" in completed.stderr
    assert "CONTRACTIQ_PASSWORD" in completed.stderr


def test_a_bad_base_url_fails_at_startup_and_never_prints_the_password():
    env = server_env("not-a-url")

    completed = subprocess.run([sys.executable, "-m", "contractiq_mcp"], cwd=SERVER_DIR, env=env,
                               capture_output=True, text=True, timeout=30, input="")

    assert completed.returncode == 2
    assert completed.stdout == ""
    assert "CONTRACTIQ_BASE_URL must be an http(s) URL" in completed.stderr
    assert PASSWORD not in completed.stderr


def test_every_line_the_server_writes_to_stdout_is_a_json_rpc_message(fake_api):
    """Speaks raw JSON-RPC to the process. The SDK client tolerates stray lines, so this checks
    stdout directly: any print() or log line on stdout would fail the JSON parse below."""
    from mcp.types import LATEST_PROTOCOL_VERSION

    proc = subprocess.Popen([sys.executable, "-m", "contractiq_mcp"], cwd=SERVER_DIR, env=server_env(fake_api),
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    lines: list[str] = []

    def read_stdout():
        for line in proc.stdout:
            lines.append(line)

    reader = threading.Thread(target=read_stdout, daemon=True)
    reader.start()
    requests = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": LATEST_PROTOCOL_VERSION, "capabilities": {},
            "clientInfo": {"name": "stdout-test", "version": "0"}}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "list_contracts", "arguments": {}}},
    ]
    try:
        for request in requests:
            proc.stdin.write(json.dumps(request) + "\n")
            proc.stdin.flush()
        import time
        deadline = time.monotonic() + 20
        answered: set[int] = set()
        while time.monotonic() < deadline and not {1, 2, 3} <= answered:
            time.sleep(0.05)
            for line in list(lines):
                try:
                    answered.add(json.loads(line).get("id"))
                except ValueError:
                    pass
        proc.stdin.close()
        proc.wait(timeout=15)
        reader.join(timeout=5)
    finally:
        if proc.poll() is None:
            proc.kill()

    assert {1, 2, 3} <= answered, f"server did not answer all requests; stdout was: {lines!r}"
    assert lines, "the server wrote nothing to stdout"
    for line in lines:
        message = json.loads(line)  # a stray print() would raise here
        assert message.get("jsonrpc") == "2.0", line
