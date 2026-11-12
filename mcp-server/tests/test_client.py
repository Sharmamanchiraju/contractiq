"""Tests for the ContractIQ API client, with the HTTP layer mocked by respx."""

from __future__ import annotations

import json
import logging

import httpx
import pytest

from contractiq_mcp.client import ContractIQClient, ContractIQError
from contractiq_mcp.config import CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS
from tests.conftest import PASSWORD, USERNAME, login_response


def bearer(request: httpx.Request) -> str:
    return request.headers.get("authorization", "")


# ---- success and token reuse ----

def test_logs_in_then_sends_the_token_and_returns_the_json(client, api):
    login = api.post("/api/v1/auth/login").mock(return_value=login_response("FAKE.TOKEN.A"))
    contract = api.get("/api/v1/contracts/7").mock(return_value=httpx.Response(200, json={"id": 7}))

    result = client.get("/api/v1/contracts/7", subject="Contract 7")

    assert result == {"id": 7}
    assert json.loads(login.calls.last.request.content) == {"username": USERNAME, "password": PASSWORD}
    assert bearer(contract.calls.last.request) == "Bearer FAKE.TOKEN.A"


def test_the_token_is_reused_for_later_calls(client, api):
    login = api.post("/api/v1/auth/login").mock(return_value=login_response())
    api.get("/api/v1/contracts/7").mock(return_value=httpx.Response(200, json={"id": 7}))

    client.get("/api/v1/contracts/7", subject="Contract 7")
    client.get("/api/v1/contracts/7", subject="Contract 7")
    client.get("/api/v1/contracts/7", subject="Contract 7")

    assert login.call_count == 1


def test_query_parameters_are_sent(client, api):
    api.post("/api/v1/auth/login").mock(return_value=login_response())
    route = api.get("/api/v1/contracts").mock(return_value=httpx.Response(200, json={"items": []}))

    client.get("/api/v1/contracts", subject="Contracts", params={"status": "ANALYZED", "size": 5})

    assert route.calls.last.request.url.params["status"] == "ANALYZED"
    assert route.calls.last.request.url.params["size"] == "5"


def test_post_sends_no_body_and_returns_the_json(client, api):
    api.post("/api/v1/auth/login").mock(return_value=login_response())
    route = api.post("/api/v1/contracts/3/analyze").mock(
        return_value=httpx.Response(202, json={"contractId": 3, "status": "ANALYZING"}))

    result = client.post("/api/v1/contracts/3/analyze", subject="Contract 3")

    assert result == {"contractId": 3, "status": "ANALYZING"}
    assert route.calls.last.request.content == b""


# ---- 401 then re-login ----

def test_a_401_triggers_one_re_login_and_one_retry(client, api):
    login = api.post("/api/v1/auth/login").mock(side_effect=[
        login_response("FAKE.TOKEN.OLD"), login_response("FAKE.TOKEN.NEW")])
    contract = api.get("/api/v1/contracts/7").mock(side_effect=[
        httpx.Response(401, json={"title": "Unauthorized"}), httpx.Response(200, json={"id": 7})])

    result = client.get("/api/v1/contracts/7", subject="Contract 7")

    assert result == {"id": 7}
    assert login.call_count == 2
    assert contract.call_count == 2
    assert bearer(contract.calls[0].request) == "Bearer FAKE.TOKEN.OLD"
    assert bearer(contract.calls[1].request) == "Bearer FAKE.TOKEN.NEW"


def test_the_new_token_is_used_afterwards_without_another_login(client, api):
    login = api.post("/api/v1/auth/login").mock(side_effect=[
        login_response("FAKE.TOKEN.OLD"), login_response("FAKE.TOKEN.NEW")])
    api.get("/api/v1/contracts/7").mock(side_effect=[
        httpx.Response(401), httpx.Response(200, json={"id": 7}), httpx.Response(200, json={"id": 7})])

    client.get("/api/v1/contracts/7", subject="Contract 7")
    client.get("/api/v1/contracts/7", subject="Contract 7")

    assert login.call_count == 2


def test_two_401s_in_a_row_is_an_error_not_a_loop(client, api):
    login = api.post("/api/v1/auth/login").mock(return_value=login_response())
    contract = api.get("/api/v1/contracts/7").mock(return_value=httpx.Response(401))

    with pytest.raises(ContractIQError, match="keeps rejecting"):
        client.get("/api/v1/contracts/7", subject="Contract 7")

    assert contract.call_count == 2
    assert login.call_count == 2


def test_a_rejected_login_gives_a_clear_message_without_the_password(client, api):
    api.post("/api/v1/auth/login").mock(return_value=httpx.Response(401, json={"title": "Unauthorized"}))

    with pytest.raises(ContractIQError) as error:
        client.get("/api/v1/contracts/7", subject="Contract 7")

    assert "CONTRACTIQ_USERNAME" in str(error.value)
    assert PASSWORD not in str(error.value)


@pytest.mark.parametrize("body", [{}, {"token": ""}, {"token": 123}, "not json"])
def test_an_unreadable_login_answer_is_an_error(client, api, body):
    response = httpx.Response(200, json=body) if not isinstance(body, str) else httpx.Response(200, text=body)
    api.post("/api/v1/auth/login").mock(return_value=response)

    with pytest.raises(ContractIQError, match="unreadable login"):
        client.get("/api/v1/contracts/7", subject="Contract 7")


# ---- refresh before expiry ----

def test_the_token_is_refreshed_shortly_before_it_expires(client, api, clock):
    login = api.post("/api/v1/auth/login").mock(side_effect=[
        login_response("FAKE.TOKEN.1", expires_in=300), login_response("FAKE.TOKEN.2", expires_in=300)])
    contract = api.get("/api/v1/contracts/7").mock(return_value=httpx.Response(200, json={"id": 7}))

    client.get("/api/v1/contracts/7", subject="Contract 7")
    clock.advance(239)  # 300 s lifetime minus the 60 s safety margin is 240 s: still valid
    client.get("/api/v1/contracts/7", subject="Contract 7")
    assert login.call_count == 1
    clock.advance(2)  # now past 240 s
    client.get("/api/v1/contracts/7", subject="Contract 7")

    assert login.call_count == 2
    assert bearer(contract.calls.last.request) == "Bearer FAKE.TOKEN.2"
    assert contract.call_count == 3  # refreshed up front: no 401 round trip was needed


# ---- error mapping ----

@pytest.mark.parametrize("status,fragment", [
    (404, "Contract 7 was not found"),
    (403, "not allowed"),
    (409, "not in a state that allows this"),
    (400, "not valid"),
    (500, "server error (HTTP 500)"),
    (503, "server error (HTTP 503)"),
    (418, "unexpected answer (HTTP 418)"),
])
def test_http_errors_become_short_clear_messages(client, api, status, fragment):
    api.post("/api/v1/auth/login").mock(return_value=login_response())
    api.get("/api/v1/contracts/7").mock(return_value=httpx.Response(status, json={
        "title": "x", "detail": "RAW-SERVER-DETAIL-SECRET", "trace": "at com.contractiq.Foo.bar(Foo.java:1)"}))

    with pytest.raises(ContractIQError) as error:
        client.get("/api/v1/contracts/7", subject="Contract 7")

    assert fragment in str(error.value)
    assert "RAW-SERVER-DETAIL-SECRET" not in str(error.value)
    assert "com.contractiq" not in str(error.value)


def test_a_success_with_a_body_that_is_not_json_is_an_error(client, api):
    api.post("/api/v1/auth/login").mock(return_value=login_response())
    api.get("/api/v1/contracts/7").mock(return_value=httpx.Response(200, text="<html>nope</html>"))

    with pytest.raises(ContractIQError, match="unreadable response"):
        client.get("/api/v1/contracts/7", subject="Contract 7")


# ---- timeouts and network failures ----

def test_a_timeout_on_a_call_is_a_clear_error(client, api):
    api.post("/api/v1/auth/login").mock(return_value=login_response())
    api.get("/api/v1/contracts/7").mock(side_effect=httpx.ReadTimeout("secret internal detail"))

    with pytest.raises(ContractIQError, match="did not answer in time") as error:
        client.get("/api/v1/contracts/7", subject="Contract 7")

    assert "secret internal detail" not in str(error.value)
    assert error.value.__cause__ is None


def test_a_timeout_on_login_is_a_clear_error(client, api):
    api.post("/api/v1/auth/login").mock(side_effect=httpx.ConnectTimeout("x"))

    with pytest.raises(ContractIQError, match="login in time"):
        client.get("/api/v1/contracts/7", subject="Contract 7")


def test_a_connection_failure_is_a_clear_error(client, api):
    api.post("/api/v1/auth/login").mock(return_value=login_response())
    api.get("/api/v1/contracts/7").mock(side_effect=httpx.ConnectError("refused"))

    with pytest.raises(ContractIQError, match="Could not reach"):
        client.get("/api/v1/contracts/7", subject="Contract 7")


def test_every_call_has_a_timeout(config):
    real = ContractIQClient(config)
    try:
        timeout = real._http.timeout
        assert timeout.connect == CONNECT_TIMEOUT_SECONDS
        assert timeout.read == READ_TIMEOUT_SECONDS
        assert timeout.write is not None and timeout.pool is not None
    finally:
        real.close()


def test_redirects_are_not_followed(config):
    real = ContractIQClient(config)
    try:
        assert real._http.follow_redirects is False
    finally:
        real.close()


# ---- nothing sensitive in logs ----

def test_logs_never_contain_the_token_or_the_password(client, api, caplog):
    caplog.set_level(logging.DEBUG)
    api.post("/api/v1/auth/login").mock(return_value=login_response("FAKE.SECRET.TOKEN-XYZ"))
    api.get("/api/v1/contracts/7").mock(return_value=httpx.Response(200, json={"id": 7, "contractText": "SECRET TEXT"}))

    client.get("/api/v1/contracts/7", subject="Contract 7")

    logged = caplog.text
    # Prove logging was captured at all, so the checks below cannot pass by accident.
    assert "ContractIQ GET /api/v1/contracts/7 -> HTTP 200" in logged
    assert "ContractIQ login -> HTTP 200" in logged
    assert "FAKE.SECRET.TOKEN-XYZ" not in logged
    assert PASSWORD not in logged
    assert "SECRET TEXT" not in logged
