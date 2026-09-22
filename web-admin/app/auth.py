"""Password gate for the admin page.

One shared password, held in the environment, checked in constant time. There
are no accounts because there is one operator: the person who wrote the
compose file. Anything more would be a user database to back up and no more
secure for it.
"""

import hmac
import os
import secrets

COOKIE = "apogee_admin"
_TOKENS = set()


def _password():
    """The configured password, or refuse to start without one.

    Called at startup as well as at login, so a misconfigured deployment fails
    immediately rather than serving an open admin panel.
    """
    password = os.environ.get("ADMIN_PASSWORD", "")
    if not password:
        raise RuntimeError(
            "ADMIN_PASSWORD is not set. The admin page can stop the server "
            "and kick players, so it will not run without one."
        )
    return password


def check(candidate):
    # compare_digest rather than == so a wrong password does not leak how much
    # of it was right through timing.
    return hmac.compare_digest(candidate or "", _password())


def issue():
    token = secrets.token_urlsafe(32)
    _TOKENS.add(token)
    return token


def valid(token):
    return bool(token) and token in _TOKENS


def revoke(token):
    _TOKENS.discard(token)


def secure_cookies():
    return os.environ.get("HTTPS", "0") not in ("0", "false", "no", "")
