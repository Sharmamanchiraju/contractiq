"""A small client for the ContractIQ REST API.

It logs in with the configured account to get a JWT, keeps the token in memory only, logs in again
shortly before the token expires, and logs in once more if the API answers 401. Every HTTP call has
a timeout. Failures become a ContractIQError with a short fixed message: never a stack trace,
a raw response body, a token or a password.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from typing import Any

import httpx

from .config import CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS, Config

log = logging.getLogger(__name__)

# Log in this many seconds before the token's stated expiry, so a call never starts with a dead token.
TOKEN_EXPIRY_SKEW_SECONDS = 60.0


class ContractIQError(Exception):
    """A failure with a message that is safe to show to the user and the AI assistant.

    `status` is the HTTP status when the failure was an HTTP answer, otherwise None.
    """

    def __init__(self, message: str, status: int | None = None) -> None:
        super().__init__(message)
        self.status = status


class ContractIQClient:
    def __init__(
        self,
        config: Config,
        *,
        http: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._config = config
        self._clock = clock
        self._http = http or httpx.Client(
            base_url=config.base_url,
            timeout=httpx.Timeout(READ_TIMEOUT_SECONDS, connect=CONNECT_TIMEOUT_SECONDS),
            follow_redirects=False,
        )
        self._lock = threading.Lock()
        self._token: str | None = None
        self._expires_at = 0.0

    # ---- public calls ----

    def get(
        self,
        path: str,
        *,
        subject: str,
        params: dict[str, Any] | None = None,
        not_found: str | None = None,
    ) -> Any:
        """GET a JSON resource.

        `subject` names it in error messages, e.g. "Contract 7". `not_found`, if given, replaces the
        default message for a 404 (useful when a 404 has a more specific meaning).
        """
        return self._request("GET", path, subject=subject, params=params, not_found=not_found)

    def post(self, path: str, *, subject: str) -> Any:
        """POST with no body and return the JSON answer."""
        return self._request("POST", path, subject=subject)

    # ---- authentication ----

    def _token_for_request(self) -> str:
        with self._lock:
            if self._token is None or self._clock() >= self._expires_at:
                self._login_locked()
            assert self._token is not None
            return self._token

    def _relogin_after_401(self, rejected_token: str) -> str:
        with self._lock:
            # Another thread may already have logged in again while this one was waiting.
            if self._token is None or self._token == rejected_token:
                self._login_locked()
            assert self._token is not None
            return self._token

    def _login_locked(self) -> None:
        self._token = None
        try:
            response = self._http.post(
                "/api/v1/auth/login",
                json={"username": self._config.username, "password": self._config.password},
            )
        except httpx.TimeoutException:
            raise ContractIQError("ContractIQ did not answer the login in time.") from None
        except httpx.TransportError:
            raise ContractIQError("Could not reach the ContractIQ API to log in.") from None
        log.info("ContractIQ login -> HTTP %s", response.status_code)
        if response.status_code == 401:
            raise ContractIQError(
                "ContractIQ rejected the login. Check CONTRACTIQ_USERNAME and CONTRACTIQ_PASSWORD."
            )
        if response.status_code != 200:
            raise ContractIQError(f"ContractIQ login failed (HTTP {response.status_code}).")
        try:
            body = response.json()
            token = body["token"]
            expires_in = float(body.get("expiresInSeconds", 0))
        except (ValueError, KeyError, TypeError):
            raise ContractIQError("ContractIQ returned an unreadable login response.") from None
        if not isinstance(token, str) or not token:
            raise ContractIQError("ContractIQ returned an unreadable login response.")
        self._token = token
        self._expires_at = self._clock() + max(expires_in - TOKEN_EXPIRY_SKEW_SECONDS, 0.0)

    # ---- requests ----

    def _request(
        self,
        method: str,
        path: str,
        *,
        subject: str,
        params: dict[str, Any] | None = None,
        not_found: str | None = None,
    ) -> Any:
        token = self._token_for_request()
        response = self._send(method, path, token, params)
        if response.status_code == 401:
            # The token was rejected (expired early, or the server restarted with a new secret). Log in
            # once more and retry once. A second 401 is an error, not a loop.
            token = self._relogin_after_401(token)
            response = self._send(method, path, token, params)
            if response.status_code == 401:
                raise ContractIQError(
                    "ContractIQ keeps rejecting this account's token. Check the account and its role."
                )
        return self._read(response, subject, not_found)

    def _send(self, method: str, path: str, token: str, params: dict[str, Any] | None) -> httpx.Response:
        try:
            response = self._http.request(
                method, path, params=params, headers={"Authorization": f"Bearer {token}"}
            )
        except httpx.TimeoutException:
            raise ContractIQError("ContractIQ did not answer in time. Try again.") from None
        except httpx.TransportError:
            raise ContractIQError("Could not reach the ContractIQ API.") from None
        log.info("ContractIQ %s %s -> HTTP %s", method, path, response.status_code)
        return response

    @staticmethod
    def _read(response: httpx.Response, subject: str, not_found: str | None = None) -> Any:
        status = response.status_code
        if 200 <= status < 300:
            try:
                return response.json()
            except ValueError:
                raise ContractIQError("ContractIQ returned an unreadable response.") from None
        if status == 400:
            raise ContractIQError("ContractIQ rejected the request as not valid.", status)
        if status == 403:
            raise ContractIQError("This account is not allowed to do that.", status)
        if status == 404:
            raise ContractIQError(
                not_found or f"{subject} was not found, or it belongs to another organization.", status
            )
        if status == 409:
            raise ContractIQError(
                f"{subject} is not in a state that allows this "
                "(for example an analysis is already running, or it has already been decided).",
                status,
            )
        if status >= 500:
            raise ContractIQError(f"ContractIQ had a server error (HTTP {status}). Try again later.", status)
        raise ContractIQError(f"ContractIQ gave an unexpected answer (HTTP {status}).", status)

    def close(self) -> None:
        self._http.close()
