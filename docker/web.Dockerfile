# The Apogee server's web admin.
#
# Owns no game state whatsoever. Every page is a rendering of what the server
# said over the control socket, and every button is one control command - so
# this container can be restarted, or left out of the compose file entirely,
# without the game noticing.

FROM python:3.13-slim

RUN groupadd --gid 10002 apogee \
 && useradd --uid 10002 --gid 10002 --no-create-home --shell /usr/sbin/nologin apogee

WORKDIR /app

COPY web-admin/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY web-admin/app /app/app

ENV APOGEE_CONTROL_SOCKET=/run/apogee/control.sock \
    WEB_PORT=8080 \
    WEB_BIND=0.0.0.0

EXPOSE 8080/tcp

USER apogee

# Shell form so WEB_BIND and WEB_PORT can come from the environment - with
# host networking there is no published port to change instead.
CMD ["sh", "-c", "exec uvicorn app.main:app --host \"$WEB_BIND\" --port \"$WEB_PORT\" --proxy-headers"]
