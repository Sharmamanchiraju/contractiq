"""Tests for list_expiring_contracts: the client-side expiry scan, its window, its cap and its reporting."""

from __future__ import annotations

import re
from datetime import date

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from contractiq_mcp import server
from tests.conftest import call_tool, login_response, page_of, summary_item

TODAY = date(2026, 6, 1)
MISSING = object()  # marks a contract whose analysis returns 404


@pytest.fixture(autouse=True)
def fixed_today(monkeypatch):
    monkeypatch.setattr(server, "_today", lambda: TODAY)


class FakeBackend:
    """Serves paged contract lists per status and analyses per contract, and counts analysis fetches."""

    def __init__(self, api):
        self.by_status: dict[str, list[int]] = {}
        self.expiry: dict[int, object] = {}  # id -> ISO string, None (no date), or MISSING
        self.analysis_fetches: list[int] = []
        self.failing_ids: set[int] = set()  # analysis requests for these ids answer HTTP 500
        api.post("/api/v1/auth/login").mock(return_value=login_response())
        api.get("/api/v1/contracts").mock(side_effect=self._list)
        api.get(url__regex=r"/api/v1/contracts/\d+/analysis").mock(side_effect=self._analysis)

    def add(self, status: str, *ids_with_expiry):
        for contract_id, expiry in ids_with_expiry:
            self.by_status.setdefault(status, []).append(contract_id)
            self.expiry[contract_id] = expiry

    def _list(self, request: httpx.Request) -> httpx.Response:
        params = request.url.params
        ids = self.by_status.get(params["status"], [])
        page, size = int(params["page"]), int(params["size"])
        chunk = ids[page * size:(page + 1) * size]
        items = [summary_item(i, params["status"], title=f"Title {i}") for i in chunk]
        return httpx.Response(200, json=page_of(items, page=page, size=size, total=len(ids)))

    def _analysis(self, request: httpx.Request) -> httpx.Response:
        contract_id = int(re.search(r"/contracts/(\d+)/analysis", request.url.path).group(1))
        self.analysis_fetches.append(contract_id)
        if contract_id in self.failing_ids:
            return httpx.Response(500)
        expiry = self.expiry[contract_id]
        if expiry is MISSING:
            return httpx.Response(404)
        return httpx.Response(200, json={"contractId": contract_id, "version": 1, "provider": "mock",
                                         "createdAt": "2026-01-01T00:00:00Z",
                                         "result": {"expiryDate": expiry, "summary": "s"}})


@pytest.fixture
def backend(api, wired):
    return FakeBackend(api)


def ids(result):
    return [c["contract_id"] for c in result["expiring_contracts"]]


# ---- window and ordering ----

def test_returns_only_contracts_expiring_within_the_window_soonest_first(backend):
    backend.add("ANALYZED", (1, "2026-07-15"), (2, "2026-06-10"), (3, "2026-12-31"))
    backend.add("APPROVED", (4, "2026-06-01"), (5, "2026-06-30"))

    result = call_tool("list_expiring_contracts", days=45)

    assert ids(result) == [4, 2, 5, 1]
    assert [c["days_remaining"] for c in result["expiring_contracts"]] == [0, 9, 29, 44]
    assert result["expiring_contracts"][0]["expiry_date"] == "2026-06-01"
    assert result["within_days"] == 45 and result["as_of"] == "2026-06-01"


def test_the_last_day_of_the_window_is_included_and_the_day_after_is_not(backend):
    backend.add("ANALYZED", (1, "2026-07-01"), (2, "2026-07-02"))

    assert ids(call_tool("list_expiring_contracts", days=30)) == [1]


def test_already_expired_contracts_are_not_listed(backend):
    backend.add("ANALYZED", (1, "2026-05-31"), (2, "2025-01-01"), (3, "2026-06-02"))

    assert ids(call_tool("list_expiring_contracts", days=10)) == [3]


def test_contracts_without_a_usable_expiry_date_are_skipped_and_counted(backend):
    backend.add("ANALYZED", (1, None), (2, "not a date"), (3, "2026-02-30"), (4, MISSING), (5, "2026-06-05"))

    result = call_tool("list_expiring_contracts", days=30)

    assert ids(result) == [5]
    assert result["contracts_scanned"] == 5
    assert result["contracts_without_expiry_date"] == 4


def test_only_analyzed_and_approved_contracts_are_scanned(backend, api):
    backend.add("ANALYZED", (1, "2026-06-05"))
    backend.add("APPROVED", (2, "2026-06-06"))
    backend.add("UPLOADED", (3, "2026-06-07"))
    backend.add("REJECTED", (4, "2026-06-08"))

    result = call_tool("list_expiring_contracts", days=30)

    assert ids(result) == [1, 2]
    assert sorted(backend.analysis_fetches) == [1, 2]


def test_an_empty_system_gives_an_empty_untruncated_result(backend):
    result = call_tool("list_expiring_contracts", days=30)

    assert result["expiring_contracts"] == []
    assert result["contracts_scanned"] == 0
    assert result["scan_truncated"] is False
    assert "scan_note" not in result


def test_titles_are_returned_under_untrusted_fields_only(backend):
    backend.add("ANALYZED", (1, "2026-06-05"))

    contract = call_tool("list_expiring_contracts", days=30)["expiring_contracts"][0]

    assert contract["untrusted_title"] == "Title 1"
    assert "title" not in contract


# ---- the cap of 100 ----

def test_the_scan_is_capped_at_100_and_says_so(backend):
    backend.add("ANALYZED", *[(i, "2026-06-10") for i in range(1, 121)])
    backend.add("APPROVED", *[(i, "2026-06-10") for i in range(1000, 1005)])

    result = call_tool("list_expiring_contracts", days=30)

    assert result["contracts_scanned"] == 100
    assert len(backend.analysis_fetches) == 100
    assert result["scan_truncated"] is True
    assert "100 of 125" in result["scan_note"]
    assert "older contracts" in result["scan_note"]
    assert len(result["expiring_contracts"]) == 100


def test_the_scan_pages_through_the_list_until_the_cap(backend, api):
    backend.add("ANALYZED", *[(i, None) for i in range(1, 61)])
    backend.add("APPROVED", *[(i, None) for i in range(100, 140)])

    result = call_tool("list_expiring_contracts", days=30)

    # 60 + 40 = exactly 100 candidates: all scanned, nothing cut short.
    assert result["contracts_scanned"] == 100
    assert result["scan_truncated"] is False


def test_exactly_100_analyzed_contracts_plus_more_approved_is_reported_as_truncated(backend):
    backend.add("ANALYZED", *[(i, None) for i in range(1, 101)])
    backend.add("APPROVED", *[(i, "2026-06-10") for i in range(500, 505)])

    result = call_tool("list_expiring_contracts", days=30)

    assert result["contracts_scanned"] == 100
    assert result["scan_truncated"] is True
    assert "100 of 105" in result["scan_note"]


# ---- validation and errors ----

@pytest.mark.parametrize("days", [0, -1, 366, 10_000])
def test_days_must_be_between_1_and_365(backend, days):
    with pytest.raises(ToolError, match="between 1 and 365"):
        call_tool("list_expiring_contracts", days=days)
    assert backend.analysis_fetches == []


def test_a_failure_while_scanning_is_reported_not_swallowed(backend, api):
    backend.add("ANALYZED", (1, "2026-06-05"), (2, "2026-06-06"))
    backend.failing_ids.add(2)

    with pytest.raises(ToolError, match="server error"):
        call_tool("list_expiring_contracts", days=30)


def test_a_timeout_while_listing_is_a_clear_error(wired, api):
    api.post("/api/v1/auth/login").mock(return_value=login_response())
    api.get("/api/v1/contracts").mock(side_effect=httpx.ReadTimeout("x"))

    with pytest.raises(ToolError, match="did not answer in time"):
        call_tool("list_expiring_contracts", days=30)
