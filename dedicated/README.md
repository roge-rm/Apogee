# Apogee dedicated server

A standalone, persistent Apogee world. Players join it from the game's
**Join a Game** screen, fly, log off, and find their craft where they left it.

It runs exactly the same simulation a phone runs when it hosts a game. The
only differences are that this one has no client of its own, writes its world
to disk, and exposes an admin control channel.

## Running it with Docker

```sh
cp .env.example .env        # set ADMIN_PASSWORD
docker compose up --build
```

Then open `http://<this machine>:8080` for the admin page. The game itself
listens on 45678.

Two containers:

| | |
|---|---|
| `server` | the game. Keeps its world in the `state` volume. |
| `web` | the admin page. Owns no game state; talks to the server over a Unix socket in the `control` volume. |

The admin page can be stopped, restarted or removed from the compose file
entirely and the game will not notice.

### Networking

Host networking is the default, because that is what lets a phone find the
server by itself — discovery is a UDP broadcast, and broadcast does not cross
a bridge network.

If you are on Docker Desktop, or want ports mapped explicitly:

```sh
docker compose -f docker-compose.yml -f docker-compose.bridge.yml up --build
```

That costs auto-discovery and nothing else. Players type the address instead.

## Running it without Docker

```sh
./gradlew :dedicated:installDist
APOGEE_STATE_DIR=./state dedicated/build/install/apogee-server/bin/apogee-server
```

Or straight from Gradle: `./gradlew :dedicated:run`.

The Android app is left out of the build automatically when no Android SDK is
present, so a server-only checkout needs nothing but a JDK.

## The images

Both are distroless: a runtime, the application, and nothing else. No shell,
no package manager, no coreutils.

| | size | |
|---|---|---|
| `apogee-server` | ~71 MB | linked JRE (43 MB) + application (5 MB) |
| `apogee-web` | ~63 MB | Python 3.11 + FastAPI |

The server links its own Java runtime with `jlink` rather than shipping a
JRE. `jdeps` reports it loads three modules — `java.base`, `java.instrument`
and `jdk.unsupported` — so a stock JRE was 159 MB for a small fraction of
itself. The module list is computed during the build from the jars that were
just produced, so a new dependency that needs another module is caught then
rather than at startup.

There being no shell has two consequences worth knowing:

- `docker exec <container> sh` will not work. The server image carries a
  static busybox for its healthcheck, so `docker exec apogee-server
  /bin/busybox sh` gets you in when you need it. The web image has no such
  escape hatch — restart it and read the logs.
- Both entry points invoke their interpreter directly, because the usual
  wrappers (Gradle's start script, `sh -c uvicorn …`) are shell scripts.
  Extra JVM options go in `JAVA_TOOL_OPTIONS`, which the JVM reads from the
  environment on its own.

## Capacity

`./gradlew :core:tickBenchmark` measures simulation cost against craft count.
On a current desktop core:

| craft | ms/tick | of the 16.7 ms budget |
|---|---|---|
| 50 | 0.65 | 3.9% |
| 200 | 2.47 | 14.8% |

Linear, about 0.012 ms per craft, so one core carries on the order of 1,300
at 60 Hz. An idle server costs about 3% of a core and 80 MB.

## Configuration

Everything is an environment variable, because the intended home is a
container where that is how configuration arrives.

| Variable | Default | |
|---|---|---|
| `APOGEE_SERVER_NAME` | `Apogee Server` | what players see in their list |
| `APOGEE_PORT` | `45678` | what clients try first |
| `APOGEE_STATE_DIR` | `./state` | where the world lives |
| `APOGEE_CONTROL_SOCKET` | *(none)* | admin channel; unset means no channel |
| `APOGEE_AUTOSAVE_SECONDS` | `60` | `0` disables autosave |
| `APOGEE_LAN_DISCOVERY` | `1` | announce on the local network |
| `APOGEE_TICK_HZ` | `60` | simulation rate |
| `APOGEE_SNAPSHOT_HZ` | `20` | how often clients are updated |

## The world file

`$APOGEE_STATE_DIR/world.json` is the whole universe: every craft, where it
is, how much fuel it has, what stage it is on and who owns it. It is JSON on
purpose — an operator should be able to read it, diff it, and hand-edit a
craft out of a hole it has got itself into.

Saves are written to a temporary file and renamed, so an interrupted save
leaves the previous world intact. One backup (`world.json.bak`) is kept, and
the server falls back to it automatically if the live file will not parse.

The file records the part catalogue's hash. Loading a world built with
different parts is allowed but reported, and any craft whose parts no longer
exist is skipped and named in the log rather than silently dropped.

## The control channel

A Unix domain socket, not a network port. The channel can stop the server and
kick players; putting it on the network would mean the only thing between an
open port and a stranger is a password somebody left at the default. A socket
in a directory has filesystem permissions instead, and the web admin reaches
it by sharing a volume rather than by being trusted.

One tab-separated line in, one line of JSON out. Usable by hand:

```sh
printf 'status\n' | socat - UNIX-CONNECT:/run/apogee/control.sock
```

| Command | |
|---|---|
| `status` | name, port, players, craft, uptime, last save |
| `players` | who is connected and where they are |
| `log <n>` | the last *n* log lines |
| `save` | write the world now |
| `chat <text>` | announce to everyone flying |
| `kick <name>` | disconnect a player |
| `stop` | save and shut down |

`dedicated/src/main/kotlin/com/rm/apogee/dedicated/ControlServer.kt` is the
only document of record for this list.
