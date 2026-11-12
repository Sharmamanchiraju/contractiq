"""Tests for environment configuration."""

import pytest

from contractiq_mcp.config import Config, ConfigError


def env(**overrides):
    values = {
        "CONTRACTIQ_BASE_URL": "http://localhost:8080",
        "CONTRACTIQ_USERNAME": "uploader",
        "CONTRACTIQ_PASSWORD": "FAKE-password",
    }
    values.update(overrides)
    return values


def test_reads_the_three_variables():
    config = Config.from_env(env())

    assert config.base_url == "http://localhost:8080"
    assert config.username == "uploader"
    assert config.password == "FAKE-password"


def test_a_trailing_slash_on_the_url_is_removed():
    assert Config.from_env(env(CONTRACTIQ_BASE_URL="http://localhost:8080/")).base_url == "http://localhost:8080"


def test_missing_variables_are_all_named():
    with pytest.raises(ConfigError) as error:
        Config.from_env({"CONTRACTIQ_BASE_URL": "http://localhost:8080"})

    assert "CONTRACTIQ_USERNAME" in str(error.value)
    assert "CONTRACTIQ_PASSWORD" in str(error.value)
    assert "CONTRACTIQ_BASE_URL" not in str(error.value)


def test_blank_values_count_as_missing():
    with pytest.raises(ConfigError, match="CONTRACTIQ_PASSWORD"):
        Config.from_env(env(CONTRACTIQ_PASSWORD="   "))


@pytest.mark.parametrize("bad_url", ["localhost:8080", "ftp://host", "not a url", "http://"])
def test_an_invalid_url_is_rejected(bad_url):
    with pytest.raises(ConfigError, match="http"):
        Config.from_env(env(CONTRACTIQ_BASE_URL=bad_url))


def test_the_password_is_hidden_from_repr_and_str():
    config = Config.from_env(env(CONTRACTIQ_PASSWORD="FAKE-super-secret-value"))

    assert "FAKE-super-secret-value" not in repr(config)
    assert "FAKE-super-secret-value" not in str(config)


def test_the_error_message_never_contains_the_password():
    with pytest.raises(ConfigError) as error:
        Config.from_env(env(CONTRACTIQ_PASSWORD="FAKE-secret", CONTRACTIQ_BASE_URL="nope"))

    assert "FAKE-secret" not in str(error.value)
