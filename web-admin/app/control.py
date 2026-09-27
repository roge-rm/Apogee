"""The client half of the dedicated server's control channel.

Requests are one tab-separated line, and replies are one line of JSON. See
dedicated/src/main/kotlin/com/rm/apogee/dedicated/ControlServer.kt, which is
the other end and the one place the command list is kept up to date.

There's a new connection per call. The server accepts as many as it's asked
for and serves them inline, and a short-lived connection means a page that
hangs can't wedge the socket the next page needs.
"""

import json
import os
import socket
import threading

DEFAULT_SOCKET = os.environ.get("APOGEE_CONTROL_SOCKET", "/run/apogee/control.sock")
DEFAULT_TIMEOUT = float(os.environ.get("CONTROL_TIMEOUT", "10"))


class ServerDown(Exception):
    """The server isn't listening.

    That isn't an error in itself. The container restarts, and a stop from the
    admin page takes the socket away on purpose. Every page shows this as a
    state, not a failure.
    """


class ControlError(Exception):
    """The server answered, and the answer was no."""


def _escape(value):
    """Tabs separate arguments, so an argument with one in it has to escape it."""
    return str(value).replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


class Control:
    def __init__(self, path=DEFAULT_SOCKET, timeout=DEFAULT_TIMEOUT):
        self.path = path
        self.timeout = timeout
        # Serialised, because the server handles requests on its own loop.
        # Several browser tabs polling at once would queue up in the kernel
        # anyway, and this keeps the failure a clean timeout instead of a pile
        # of half-open sockets.
        self._lock = threading.Lock()

    def call(self, *args):
        line = "\t".join(_escape(a) for a in args) + "\n"
        with self._lock:
            try:
                connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                connection.settimeout(self.timeout)
                connection.connect(self.path)
            except FileNotFoundError as error:
                # The socket only exists while the server is up, so this is what
                # a restart, a crash and "never started" all look like.
                raise ServerDown("It is not running, or is still starting up.") from error
            except (ConnectionRefusedError, PermissionError) as error:
                raise ServerDown(f"Its control socket is there but not usable: {error}") from error
            except OSError as error:
                raise ServerDown(str(error)) from error

            with connection:
                connection.sendall(line.encode("utf-8"))
                chunks = []
                while True:
                    chunk = connection.recv(65536)
                    if not chunk:
                        break
                    chunks.append(chunk)
                    if b"\n" in chunk:
                        break

        raw = b"".join(chunks).decode("utf-8", "replace").strip()
        if not raw:
            raise ServerDown("The server closed the connection without answering.")

        try:
            reply = json.loads(raw)
        except json.JSONDecodeError as error:
            raise ControlError(f"Unreadable reply: {raw[:200]}") from error

        if not reply.get("ok"):
            raise ControlError(reply.get("error", "refused"))
        return reply

    # --- the command list ---------------------------------------------------

    def status(self):
        return self.call("status")

    def players(self):
        return self.call("players").get("players", [])

    def log(self, count=120):
        return self.call("log", count).get("lines", [])

    def save(self):
        return self.call("save")

    def chat(self, text):
        return self.call("chat", text)

    def kick(self, name):
        return self.call("kick", name)

    def stop(self):
        return self.call("stop")
