"""Entry point for the admin page.

A Python entry point rather than a shell command line, because the runtime
image has no shell to assemble one. uvicorn's host and port come from the
environment here instead of from arguments.
"""

import os

import uvicorn


def main():
    uvicorn.run(
        "app.main:app",
        host=os.environ.get("WEB_BIND", "0.0.0.0"),
        port=int(os.environ.get("WEB_PORT", "8080")),
        proxy_headers=True,
        # Behind a reverse proxy the real client address arrives in a header;
        # trusting every upstream is right here because the only upstream is
        # whatever the operator put in front of it.
        forwarded_allow_ips="*",
        log_level=os.environ.get("LOG_LEVEL", "info").lower(),
    )


if __name__ == "__main__":
    main()
