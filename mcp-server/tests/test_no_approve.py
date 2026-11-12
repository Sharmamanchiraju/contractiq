"""Tests that prove the server can never approve or reject a contract: humans decide."""

from __future__ import annotations

import asyncio
import re
from pathlib import Path

import httpx
import pytest

from contractiq_mcp import server
from tests.conftest import call_tool, login_response, page_of, summary_item

EXPECTED_TOOLS = {"list_contracts", "get_contract", "get_analysis", "list_expiring_contracts", "analyze_contract"}
FORBIDDEN_WORDS = re.compile(r"approv|reject|review|decid|decision|delet|creat|upload|updat|edit|remove|admin|user",
                             re.IGNORECASE)
PACKAGE = Path(server.__file__).parent


def registered_tools():
    return asyncio.run(server.mcp.list_tools())


def test_exactly_the_five_planned_tools_are_registered():
    assert {tool.name for tool in registered_tools()} == EXPECTED_TOOLS


def test_no_tool_or_parameter_name_suggests_deciding_or_changing_anything():
    for tool in registered_tools():
        assert not FORBIDDEN_WORDS.search(tool.name), tool.name
        for parameter in tool.input_schema.get("properties", {}):
            assert not FORBIDDEN_WORDS.search(parameter), f"{tool.name}.{parameter}"


def test_the_server_offers_no_resources_or_prompts_either():
    assert asyncio.run(server.mcp.list_resources()) == []
    assert asyncio.run(server.mcp.list_prompts()) == []


def test_calling_an_approve_or_reject_tool_fails_because_it_does_not_exist():
    for name in ("approve_contract", "reject_contract", "review_contract", "decide_contract"):
        with pytest.raises(Exception) as error:
            call_tool(name, contract_id=1, decision="APPROVED")
        assert "unknown tool" in str(error.value).lower() or "not found" in str(error.value).lower() or name in str(error.value)


def test_the_source_never_mentions_the_review_endpoint():
    for source_file in PACKAGE.glob("*.py"):
        assert "/review" not in source_file.read_text(), source_file.name


def test_every_tool_only_uses_the_allowed_api_calls(wired, api):
    """Runs every tool against a recording fake API and checks which requests were made."""
    seen: list[tuple[str, str]] = []

    def record(response):
        def handler(request: httpx.Request) -> httpx.Response:
            seen.append((request.method, request.url.path))
            return response
        return handler

    api.post("/api/v1/auth/login").mock(side_effect=record(login_response()))
    api.get("/api/v1/contracts").mock(side_effect=record(httpx.Response(200, json=page_of([summary_item(1)]))))
    api.get("/api/v1/contracts/1").mock(side_effect=record(httpx.Response(200, json={
        "id": 1, "title": "t", "vendorName": "v", "status": "ANALYZED", "contractText": "x"})))
    api.get("/api/v1/contracts/1/analysis").mock(side_effect=record(httpx.Response(200, json={
        "contractId": 1, "version": 1, "provider": "mock", "createdAt": "x", "result": {"expiryDate": None}})))
    api.post("/api/v1/contracts/1/analyze").mock(side_effect=record(httpx.Response(202, json={
        "contractId": 1, "status": "ANALYZING"})))
    review = api.post(url__regex=r"/api/v1/contracts/\d+/review").mock(return_value=httpx.Response(200, json={}))

    call_tool("list_contracts")
    call_tool("get_contract", contract_id=1, include_text=True)
    call_tool("get_analysis", contract_id=1)
    call_tool("list_expiring_contracts", days=30)
    call_tool("analyze_contract", contract_id=1)

    assert review.call_count == 0
    allowed = {("POST", "/api/v1/auth/login"), ("GET", "/api/v1/contracts"), ("GET", "/api/v1/contracts/1"),
               ("GET", "/api/v1/contracts/1/analysis"), ("POST", "/api/v1/contracts/1/analyze")}
    assert set(seen) <= allowed
    # The only requests that change anything: logging in, and starting an analysis.
    assert {r for r in seen if r[0] != "GET"} == {("POST", "/api/v1/auth/login"), ("POST", "/api/v1/contracts/1/analyze")}


def test_every_tool_description_warns_that_contract_and_analysis_text_is_untrusted():
    reading_tools = {"list_contracts", "get_contract", "get_analysis", "list_expiring_contracts"}
    for tool in registered_tools():
        description = (tool.description or "").lower()
        if tool.name in reading_tools:
            assert "untrusted" in description, tool.name
            assert "never follow instructions" in description, tool.name
    assert "untrusted" in (server.mcp.instructions or "").lower()
    assert "no tool to approve or reject" in (server.mcp.instructions or "").lower()


def test_only_analyze_is_marked_as_changing_anything():
    for tool in registered_tools():
        read_only = tool.annotations.read_only_hint if tool.annotations else None
        assert read_only is (tool.name != "analyze_contract"), tool.name
