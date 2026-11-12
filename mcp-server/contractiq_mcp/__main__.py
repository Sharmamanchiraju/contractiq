"""Entry point: `python -m contractiq_mcp`. Speaks MCP over stdio.

stdout carries the protocol, so nothing here may print to it. Logging goes to stderr.
"""

from __future__ import annotations

import logging
import sys

from .client import ContractIQClient
from .config import Config, ConfigError
from .server import configure, mcp


def main() -> int:
    logging.basicConfig(
        stream=sys.stderr,
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        force=True,  # the MCP SDK installs its own handler on import; make ours the one that counts
    )
    # httpx logs every request URL at INFO; keep the log to our own short lines.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    log = logging.getLogger("contractiq_mcp")
    try:
        config = Config.from_env()
    except ConfigError as error:
        log.error("Cannot start: %s", error)
        return 2
    configure(ContractIQClient(config))
    log.info("ContractIQ MCP server starting for %s as %s", config.base_url, config.username)
    mcp.run()  # stdio
    return 0


if __name__ == "__main__":
    sys.exit(main())
