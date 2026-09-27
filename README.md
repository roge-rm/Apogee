# Apogee

Apogee is a physics sim for Android 8.1+.

Build vehicles out of a variety of parts and take them wherever you want - on land, in the air, in space, in (and under) the sea.
Make rovers, planes, rockets, drones, balloons and airships, ships and submarines. Build bases on land, in the air, and on the water.

Play in sandbox mode and explore freely with a variety of bases on different bodies and stock vehicles to take there. Or play in career mode and explore the solar system for yourself, unlocking new parts with the various feats you achieve and building your way up from nothing. 

Do it with friends, too. Apogee is multiplayer at its core, includes a standalone server (easily run with docker) but also runs one
on your phone every time you play a game. Build things together, explore the solar system, come to each other's rescue and show off what you can do.

The game is complete enough to play and test so I am opening it up to the world to join me in doing so. Build things, break things, tell me what works or what doesn't. 

Join me in the #apogee channel **[on my discord](https://discord.gg/9Wun47jGC6)** to discuss the game or report any bugs.
<br>You can also add an issue here for me to look at.

Enjoy!
Dan

---

## What's in it

**A solar system.** A sun and its planets and moons, each with its own gravity, air, weather and ground. Days, tides and launch windows come round the way they should. Terra is home, with the Cape's launch complex, an airfield, a harbour and a coast to explore, and every other world with solid ground can be landed on.

**Building.** Vehicle Assembly puts parts together node by node, with symmetry, staging, action groups, saved assemblies you can drop back in, and a stats card that tells you what you've made: its Δv and thrust, whether it hovers or floats, how high it'll go. Saved craft sort themselves into rockets, planes, rovers, boats, submarines, rotorcraft, airships and bases, each with a picture.

**Flying anything.**
- Rockets with staging, gimballed engines, stability assist, planned burns and an autopilot for burns and landings.
- Planes with flaps, cruise control, and an approach cue and landing lights at the runway.
- Rovers and haulers with suspension, steering, hitches and a winch.
- Boats with hulls that take on water, keels, rudders and sails, and submarines with ballast, pressure hulls and sonar.
- Helicopters and drones on rotors, and balloons and airships on gas.
- A keeper core that holds a craft still in the air or on the water.

**The sea.** Waves that move what floats on them, storms, tides, currents out in the open ocean, and a deep world underneath with canyons, vents and places to find.

**Bases.** Found a base on any solid world, or on a platform floating on the sea or in the sky. Bases have pads to launch from, power, and industry that digs ore and water and makes propellant from them.

**Crew.** Named astronauts who fly your craft, walk on other worlds, climb ladders, plant flags and can be rescued.

**A career, or free play.** The career starts from nothing. You earn insight with feats (the first time into orbit, the first landing on another world, a hover held by hand, a kilometre sailed on the wind alone) and spend it on a tech tree. Free play has every part and every craft from the start.

**Save points.** In your own world you can take a save point, go back to it, or revert a flight to its launch.

**Playing together.** Host a game on your network, join someone else's, or run a dedicated server for a persistent world everyone comes back to.

---

## Building it

You need a JDK (17 or newer, which Gradle needs to run) and, for the app, the Android SDK.

```sh
./gradlew :app:assembleDebug
```

That builds the debug APK in `app/build/outputs/apk/debug/`.

The tests:

```sh
./gradlew --continue :core:test :net:test :server:test :dedicated:test :app:testDebugUnitTest
```

The app is left out of the build when there's no Android SDK, so a checkout for the server alone needs nothing but a JDK.

## Running a server

A dedicated server is a persistent world that players join from the game's **Join a Game** screen. How to run it, with Docker or without, is in [dedicated/README.md](dedicated/README.md).

## How it's laid out

| | |
|---|---|
| `core` | The simulation: the solar system, physics, parts and craft, the sea and the weather, bases, crew and the career. It's plain Kotlin with no Android in it, so it's tested on its own. |
| `net` | How clients and servers talk, and a client's prediction of the craft it's flying. |
| `server` | The game server, the same one whether a phone hosts or a dedicated server runs it. |
| `dedicated` | The standalone server, with its admin channel. |
| `web-admin` | The dedicated server's admin page. |
| `app` | The Android app: the renderer, the sound, the flight and builder screens. |
| `art` | The icon and the tools for the parts' pictures. |

The server is in charge of every world. The app runs its own copy of the craft you're flying to hide the lag, and the server's word always wins.
