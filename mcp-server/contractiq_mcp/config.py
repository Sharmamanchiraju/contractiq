"""Configuration read from environment variables.

The password is held in a field that is hidden from repr(), so it cannot leak through logging
or an error message that prints the config object.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from urllib.parse import urlparse

ENV_BASE_URL = "CONTRACTIQ_BASE_URL"
ENV_USERNAME = "CONTRACTIQ_USERNAME"
ENV_PASSWORD = "CONTRACTIQ_PASSWORD"

CONNECT_TIMEOUT_SECONDS = 5.0
READ_TIMEOUT_SECONDS = 30.0


class ConfigError(Exception):
    """The environment is missing something or has an invalid value. The message is safe to show."""


@dataclass(frozen=True)
class Config:
    base_url: str
    username: str
    password: str = field(repr=False)

    @staticmethod
    def from_env(env: dict[str, str] | None = None) -> "Config":
        """Reads and checks the three required variables, or raises ConfigError naming what is wrong."""
        source = os.environ if env is None else env
        values = {name: (source.get(name) or "").strip() for name in (ENV_BASE_URL, ENV_USERNAME, ENV_PASSWORD)}
        missing = [name for name, value in values.items() if not value]
        if missing:
            raise ConfigError("Missing required environment variable(s): " + ", ".join(missing))
        parsed = urlparse(values[ENV_BASE_URL])
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ConfigError(f"{ENV_BASE_URL} must be an http(s) URL such as http://localhost:8080")
        return Config(
            base_url=values[ENV_BASE_URL].rstrip("/"),
            username=values[ENV_USERNAME],
            password=values[ENV_PASSWORD],
        )
