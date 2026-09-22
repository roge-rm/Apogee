# The Apogee server's web admin.
#
# Owns no game state whatsoever. Every page is a rendering of what the server
# said over the control socket, and every button is one control command - so
# this container can be restarted, or left out of the compose file entirely,
# without the game noticing.

# Python 3.11 specifically, to match the interpreter in the distroless runtime
# below. Site-packages are copied across wholesale, and a version mismatch
# would leave compiled extensions unimportable.
FROM python:3.11-slim-bookworm AS build

COPY web-admin/requirements.txt /requirements.txt

# --target rather than a venv: a venv carries an interpreter and activation
# scripts that the runtime image neither has nor needs.
RUN pip install --no-cache-dir --target /packages -r /requirements.txt \
 && find /packages -name '__pycache__' -type d -prune -exec rm -rf {} + \
 && find /packages -name '*.dist-info' -type d -prune -exec rm -rf {} +


# Distroless: a Python interpreter, glibc, and nothing else. No shell, no pip,
# no package manager.
FROM gcr.io/distroless/python3-debian12

COPY --from=build /packages /packages
COPY web-admin/app /app/app

ENV PYTHONPATH=/packages:/app \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    APOGEE_CONTROL_SOCKET=/run/apogee/control.sock \
    WEB_PORT=8080 \
    WEB_BIND=0.0.0.0

WORKDIR /app
EXPOSE 8080/tcp

# The same uid as the server container. The two pass a Unix socket between
# them through a shared volume, and the socket's file permissions are the only
# access control it has.
USER 10002:10002

# No shell, so no `sh -c`: the host and port are read from the environment by
# serve.py instead of being interpolated into a command line.
ENTRYPOINT ["/usr/bin/python3.11", "-m", "app.serve"]
