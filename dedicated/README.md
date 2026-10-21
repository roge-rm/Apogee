# Apogee dedicated server

Host an Apogee world that players can return to. They join from Join a Game, fly and leave their craft saved on the server. The server runs without a player of its own.

## Running with Docker

From the repository root, copy `.env.example` to `.env`, set `ADMIN_PASSWORD` and start the containers:

```sh
cp .env.example .env        # set ADMIN_PASSWORD
docker compose up --build
```

Then open `http://<this machine>:8080` for the admin page. The game itself
listens on 45678.

There are two containers:

| | |
|---|---|
| `server` | the game. It keeps its world in the `state` volume. |
| `web` | the admin page. It talks to the server over a Unix socket in the `control` volume, and the game runs fine without it. |

### Networking

Host networking is the default, so phones can find the server on their own
(discovery is a UDP broadcast, which doesn't cross a bridge network). On Docker
Desktop, or to map the ports yourself:

```sh
docker compose -f docker-compose.yml -f docker-compose.bridge.yml up --build
```

Players then enter the server address manually.

## Running without Docker

```sh
./gradlew :dedicated:installDist
APOGEE_STATE_DIR=./state dedicated/build/install/apogee-server/bin/apogee-server
```

Or straight from Gradle: `./gradlew :dedicated:run`.

Without an Android SDK the app is left out, so this needs just a JDK.

## The images

The images contain the application and its runtime. The server image includes BusyBox for maintenance; the web image has no shell.

| | size | |
|---|---|---|
| `apogee-server` | ~71 MB | linked JRE (43 MB) + application (5 MB) |
| `apogee-web` | ~63 MB | Python 3.11 + FastAPI |

The server links its own Java runtime with `jlink`, from the modules the build
finds it needs.

- `docker exec <container> sh` won't work. Use `docker exec apogee-server
  /bin/busybox sh` for the server. For the web image, read the logs.
- Extra JVM options go in `JAVA_TOOL_OPTIONS`.

## Capacity

`./gradlew :core:tickBenchmark` measures simulation cost against craft count.
On a current desktop core:

| craft | ms/tick | of the 16.7 ms budget |
|---|---|---|
| 50 | 0.65 | 3.9% |
| 200 | 2.47 | 14.8% |

About 0.012 ms per craft, so one core carries around 1,300 at 60 Hz. An idle
server uses about 3% of a core and 80 MB.

## Configuration

Everything is an environment variable.

| Variable | Default | |
|---|---|---|
| `APOGEE_SERVER_NAME` | `Apogee Server` | what players see in their list |
| `APOGEE_PORT` | `45678` | what clients try first |
| `APOGEE_STATE_DIR` | `./state` | where the world lives |
| `APOGEE_CONTROL_SOCKET` | *(none)* | admin channel; unset means no channel |
| `APOGEE_AUTOSAVE_SECONDS` | `60` | `0` turns autosave off |
| `APOGEE_LAN_DISCOVERY` | `1` | announce on the local network |
| `APOGEE_TICK_HZ` | `60` | simulation rate |
| `APOGEE_SNAPSHOT_HZ` | `20` | how often clients get updated |

## The world file

`$APOGEE_STATE_DIR/world.json` is the whole world: every craft, where it is, its
fuel, its stage and its owner. It's JSON so you can read it and hand-edit it.

Saves go to a temporary file and are renamed, so an interrupted save leaves the
old world alone. One backup (`world.json.bak`) is kept, and the server falls back
to it if the live file won't parse. A craft whose parts no longer exist is
skipped and named in the log.

## The control channel

The admin channel uses a Unix socket protected by file permissions. Requests are one line of tab separated fields, with one line of JSON returned:

```sh
printf 'status\n' | socat - UNIX-CONNECT:/run/apogee/control.sock
```

| Command | |
|---|---|
| `status` | name, port, players, craft, uptime, last save |
| `players` | who's connected and where they are |
| `log <n>` | the last *n* log lines |
| `save` | write the world now |
| `chat <text>` | announce to everyone flying |
| `kick <name>` | disconnect a player |
| `stop` | save and shut down |

The full list is in `dedicated/src/main/kotlin/com/rm/apogee/dedicated/ControlServer.kt`.
