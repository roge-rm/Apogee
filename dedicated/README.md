# Apogee dedicated server

This is a standalone, persistent Apogee world. Players join it from the game's
**Join a Game** screen, fly, log off, and find their craft where they left them.

It runs exactly the same simulation a phone runs when it hosts a game. The only
differences are that this one has no client of its own, writes its world to
disk, and has an admin control channel.

## Running it with Docker

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
| `web` | the admin page. It owns no game state, and talks to the server over a Unix socket in the `control` volume. |

You can stop, restart or remove the admin page from the compose file
completely, and the game won't notice.

### Networking

Host networking is the default, because that's what lets a phone find the
server by itself. Discovery is a UDP broadcast, and broadcast doesn't cross a
bridge network.

If you're on Docker Desktop, or you want the ports mapped explicitly:

```sh
docker compose -f docker-compose.yml -f docker-compose.bridge.yml up --build
```

That costs you auto-discovery and nothing else. Players just type the address
instead.

## Running it without Docker

```sh
./gradlew :dedicated:installDist
APOGEE_STATE_DIR=./state dedicated/build/install/apogee-server/bin/apogee-server
```

Or straight from Gradle: `./gradlew :dedicated:run`.

The Android app is left out of the build automatically when there's no Android
SDK, so a server-only checkout needs nothing but a JDK.

## The images

Both are distroless: a runtime, the application, and nothing else. There's no
shell, no package manager and no coreutils.

| | size | |
|---|---|---|
| `apogee-server` | ~71 MB | linked JRE (43 MB) + application (5 MB) |
| `apogee-web` | ~63 MB | Python 3.11 + FastAPI |

The server links its own Java runtime with `jlink` instead of shipping a JRE.
`jdeps` says it loads three modules (`java.base`, `java.instrument` and
`jdk.unsupported`), so a stock JRE was 159 MB for a small fraction of itself.
The module list is worked out during the build from the jars that were just
made, so a new dependency that needs another module gets caught then, not at
startup.

Having no shell means two things worth knowing:

- `docker exec <container> sh` won't work. The server image carries a static
  busybox for its healthcheck, so `docker exec apogee-server /bin/busybox sh`
  gets you in when you need it. The web image has no way in like that, so
  restart it and read the logs.
- Both entry points run their interpreter directly, because the usual wrappers
  (Gradle's start script, `sh -c uvicorn …`) are shell scripts. Extra JVM
  options go in `JAVA_TOOL_OPTIONS`, which the JVM reads from the environment
  by itself.

## Capacity

`./gradlew :core:tickBenchmark` measures simulation cost against craft count.
On a current desktop core:

| craft | ms/tick | of the 16.7 ms budget |
|---|---|---|
| 50 | 0.65 | 3.9% |
| 200 | 2.47 | 14.8% |

It's linear, about 0.012 ms per craft, so one core carries something like 1,300
at 60 Hz. An idle server costs about 3% of a core and 80 MB.

## Configuration

Everything is an environment variable, because the server is meant to live in
a container, and that's how settings arrive there.

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

`$APOGEE_STATE_DIR/world.json` is the whole universe: every craft, where it is,
how much fuel it has, what stage it's on and who owns it. I made it JSON on
purpose, so an operator can read it, diff it, and hand-edit a craft out of a
hole it has got itself into.

Saves are written to a temporary file and renamed, so an interrupted save
leaves the previous world as it was. One backup (`world.json.bak`) is kept, and
the server falls back to it by itself if the live file won't parse.

The file records the part catalogue's hash. Loading a world built with
different parts is allowed but reported, and any craft whose parts don't exist
any more is skipped and named in the log, instead of quietly dropped.

## The control channel

It's a Unix domain socket, not a network port. The channel can stop the server
and kick players, and putting it on the network would mean the only thing
between an open port and a stranger is a password somebody left at the
default. A socket in a directory has filesystem permissions instead, and the
web admin reaches it by sharing a volume, not by being trusted.

One tab-separated line goes in, and one line of JSON comes out. You can use it
by hand:

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

`dedicated/src/main/kotlin/com/rm/apogee/dedicated/ControlServer.kt` is the one
place this list is kept up to date.
