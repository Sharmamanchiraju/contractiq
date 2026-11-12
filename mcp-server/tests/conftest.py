"""Shared test helpers: a fake ContractIQ API built with respx, and fixtures for the client."""

from __future__ import annotations

import httpx
import pytest
import respx

from contractiq_mcp.client import ContractIQClient
from contractiq_mcp.config import Config

BASE_URL = "http://contractiq.test"
USERNAME = "mcp-uploader"
PASSWORD = "FAKE-test-password-do-not-use"


class FakeClock:
    """A clock the test moves by hand, so token expiry can be tested without waiting."""

    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def login_response(token: str = "FAKE.JWT.TOKEN-1", expires_in: int = 1800) -> httpx.Response:
    return httpx.Response(200, json={"token": token, "tokenType": "Bearer", "expiresInSeconds": expires_in})


@pytest.fixture
def config() -> Config:
    return Config(base_url=BASE_URL, username=USERNAME, password=PASSWORD)


@pytest.fixture
def api():
    """The fake API. Routes are added by each test; unmatched calls raise instead of going out."""
    with respx.mock(base_url=BASE_URL, assert_all_called=False) as router:
        yield router


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def client(config, api, clock) -> ContractIQClient:
    # Created while the respx mock is active, so its HTTP calls are intercepted.
    instance = ContractIQClient(config, clock=clock)
    yield instance
    instance.close()


# ---- helpers for tests that call the MCP tools ----

import asyncio  # noqa: E402
import json  # noqa: E402

from contractiq_mcp import server  # noqa: E402


@pytest.fixture
def wired(client):
    """Points the MCP tools at the test client (and the fake API), and clears it afterwards."""
    server.configure(client)
    yield client
    server.configure(None)


def call_tool(name: str, **arguments):
    """Calls a tool the way a client would and returns its JSON result. Raises ToolError on failure."""
    result = asyncio.run(server.mcp.call_tool(name, arguments))
    return json.loads(result.content[0].text)


def summary_item(contract_id: int, status: str = "ANALYZED", title: str = "A title", vendor: str = "Acme"):
    return {"id": contract_id, "title": title, "vendorName": vendor, "status": status,
            "analysisErrorCode": None, "createdBy": 1, "createdAt": "2026-01-01T00:00:00Z",
            "updatedAt": "2026-01-02T00:00:00Z", "version": 1}


def page_of(items, page=0, size=20, total=None):
    total = len(items) if total is None else total
    pages = (total + size - 1) // size
    return {"items": items, "page": page, "size": size, "totalElements": total, "totalPages": pages}
