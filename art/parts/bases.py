import sys
from common import *

W = 4.0      # module width and depth, m
H = 2.6      # module height, m
HH = H / 2

def shell(extra=(), tint="body", band=True):
    """A module's box: walls, plinth, roof trim, a window band all round."""
    pieces = [
        piece(box(W, H - 0.2, W), tint=tint),
        piece(box(W + 0.1, 0.12, W + 0.1), at=(0, -HH + 0.06, 0), tint="dark"),
        piece(box(W - 0.2, 0.12, W - 0.2), at=(0, HH - 0.06, 0), tint="metal"),
    ]
    if band:
        for rot, at in (((0, 0, 0), (0, 0.35, W / 2 + 0.01)), ((0, 180, 0), (0, 0.35, -W / 2 - 0.01)),
                        ((0, 90, 0), (W / 2 + 0.01, 0.35, 0)), ((0, -90, 0), (-W / 2 - 0.01, 0.35, 0))):
            pieces.append(piece(box(2.6, 0.45, 0.03), at=at, rot=rot, tint="glass"))
    return compound(*(pieces + list(extra)))

MODULE_NODES = [node("bottom", (0, -HH, 0), (0, -1, 0)), node("top", (0, HH, 0), (0, 1, 0))]

parts = []

parts.append(part(
    "base-foundation", "Foundation", "base", 900,
    "Levelling feet for a base: set it down on ground no steeper than ten degrees, found the base, and its jacks reach down to hold it level. A founded base cannot be pushed or tipped - though what hits it can still break it.",
    box(4.2, 0.6, 4.2),
    compound(
        piece(box(4.0, 0.12, 4.0), at=(0, 0.24, 0), tint="metal"),
        *[piece(box(4.2, 0.3, 0.3), at=(0, 0.05, s * 1.95), tint="dark") for s in (-1, 1)],
        *[piece(box(0.3, 0.3, 4.2), at=(s * 1.95, 0.05, 0), tint="dark") for s in (-1, 1)],
        *[piece(cyl(0.12, 0.5), at=(sx * 1.85, -0.05, sz * 1.85), tint="metal") for sx in (-1, 1) for sz in (-1, 1)],
        *[piece(cyl(0.34, 0.08), at=(sx * 1.85, -0.26, sz * 1.85), tint="dark") for sx in (-1, 1) for sz in (-1, 1)],
        *[piece(box(0.25, 0.08, 0.06), at=(sx * 2.1, 0.12, sz * 1.2), tint="accent") for sx in (-1, 1) for sz in (-1, 1)],
    ),
    [node("top", (0, 0.3, 0), (0, 1, 0)), node("bottom", (0, -0.3, 0), (0, -1, 0), size=0)],
    [{"type": "foundation", "travel": 1.0, "maxSlope": 10.0}],
    crash=22.0, strength=900000.0,
))

parts.append(part(
    "base-core", "Base Core", "base", 3200,
    "The heart of a base: control, a little power in reserve and a few panels on the roof, room for four. Set it down on a foundation, found it, and build out from its connectors.",
    box(W, H, W),
    shell([
        piece(cyl(0.05, 1.6), at=(1.4, HH + 0.8, 1.4), tint="metal"),
        piece(lathe([(0.0, 0.0), (0.45, 0.12), (0.5, 0.2), (0.0, 0.08)]), at=(1.4, HH + 1.5, 1.4), rot=(-30, 0, 0), tint="light"),
        piece(box(1.0, 1.9, 0.06), at=(0, -0.3, W / 2 + 0.02), tint="dark"),
        piece(box(0.3, 0.12, 0.05), at=(0, 0.75, W / 2 + 0.05), tint="light"),
        *[piece(box(1.5, 0.05, 1.1), at=(x, HH + 0.1, -1.1), rot=(-12, 0, 0), tint="glass") for x in (-0.85, 0.85)],
    ]),
    MODULE_NODES,
    [{"type": "command", "crewCapacity": 4, "reactionTorque": 0.0}, {"type": "battery", "capacity": 400.0},
     {"type": "solarPanel", "chargeRate": 2.0}],
    crash=16.0, strength=900000.0,
))

parts.append(part(
    "base-habitat", "Habitat Module", "base", 2600,
    "Living space for six: bunks, galley, a window on the world outside. Brought in on a flatbed and joined at a connector.",
    box(W, H, W),
    shell([
        piece(box(3.0, 0.25, 0.9), at=(0, HH + 0.12, 0.8), tint="light"),
        piece(box(1.0, 1.9, 0.06), at=(0, -0.3, -W / 2 - 0.02), tint="dark"),
    ], tint="body"),
    MODULE_NODES,
    [],
    crash=16.0, strength=900000.0,
))

def depot(id, title, resource, capacity, mass, desc, spheres):
    tanks = []
    if spheres:
        tanks = [piece(sphere(0.95), at=(sx * 0.95, 0.0, sz * 0.95), tint="metal") for sx in (-1, 1) for sz in (-1, 1)]
    else:
        tanks = [piece(cyl(0.9, 3.6), at=(sx * 0.95, -0.1, 0), rot=(90, 0, 0), tint="metal") for sx in (-1, 1)]
    frame = [piece(box(0.18, H - 0.2, 0.18), at=(sx * 1.9, 0, sz * 1.9), tint="dark") for sx in (-1, 1) for sz in (-1, 1)]
    frame += [piece(box(W, 0.14, W), at=(0, -HH + 0.07, 0), tint="dark"), piece(box(W, 0.1, W), at=(0, HH - 0.05, 0), tint="dark")]
    stripe = [piece(box(0.06, 0.4, 1.2), at=(sx * 2.0, 0.9, 0), tint="accent") for sx in (-1, 1)]
    parts.append(part(id, title, "base", mass, desc, box(W, H, W), compound(*(frame + tanks + stripe)), MODULE_NODES,
                      [{"type": "tank", "resource": resource, "capacity": float(capacity)}], crash=14.0, strength=900000.0))

depot("base-depot", "Propellant Depot", "propellant", 2400, 1800,
      "Twelve tonnes of propellant in store: what a base fills its craft from, and what a tanker tops up.", spheres=False)
depot("base-mono-depot", "Monopropellant Depot", "monopropellant", 800, 1200,
      "Monopropellant for thrusters, kept in four spheres.", spheres=True)

parts.append(part(
    "base-battery", "Power Module", "base", 2200,
    "Batteries racked floor to ceiling: what keeps a base's lamps lit and pumps running through the night.",
    box(W, H, W),
    shell([
        *[piece(box(0.5, 1.8, 3.0), at=(x, -0.2, 0), tint="dark") for x in (-1.2, -0.4, 0.4, 1.2)],
        *[piece(box(0.06, 0.1, 0.1), at=(x + 0.2, 0.75, 1.5), tint="light") for x in (-1.2, -0.4, 0.4, 1.2)],
    ], band=False),
    MODULE_NODES,
    [{"type": "battery", "capacity": 6000.0}],
    crash=14.0, strength=900000.0,
))

parts.append(part(
    "base-solar", "Solar Array", "base", 450,
    "Two wings of solar cells on a mast, for the top of a module. Power by day, none by night: a base wants batteries too.",
    box(0.6, 3.2, 0.6),
    compound(
        piece(cyl(0.12, 3.2), tint="metal"),
        piece(box(3.6, 0.06, 1.8), at=(-2.0, 1.2, 0), rot=(0, 0, 8), tint="glass"),
        piece(box(3.6, 0.06, 1.8), at=(2.0, 1.2, 0), rot=(0, 0, -8), tint="glass"),
        piece(box(0.5, 0.2, 0.2), at=(0, 1.2, 0), tint="dark"),
    ),
    [node("bottom", (0, -1.6, 0), (0, -1, 0))],
    [{"type": "solarPanel", "chargeRate": 8.0}],
    crash=10.0, drag=1.2,
))

parts.append(part(
    "base-connector", "Base Connector", "base", 300,
    "A wide hatch for the side of a base module. Bring another module's connector up to it, near enough lined up, and they draw together and join into one base.",
    box(1.8, 0.3, 1.8),
    compound(
        piece(box(1.8, 0.2, 1.8), at=(0, -0.05, 0), tint="metal"),
        piece(box(1.3, 0.24, 1.3), at=(0, 0.02, 0), tint="dark"),
        *[piece(box(0.16, 0.06, 0.16), at=(sx * 0.78, 0.12, sz * 0.78), tint="light") for sx in (-1, 1) for sz in (-1, 1)],
    ),
    [node("mount", (0, -0.15, 0), (0, -1, 0), size=0, kind="surface")],
    [{"type": "dockingPort", "kind": "port", "size": 3, "faceOffset": 0.15, "captureRange": 1.5, "captureAngle": 20.0,
      "captureSpeed": 1.0, "pull": 60000.0, "turn": 150000.0, "latchSeconds": 1.0, "latchRange": 0.3, "latchAngle": 8.0, "undockImpulse": 2000.0}],
    crash=14.0,
))

parts.append(part(
    "base-corridor", "Corridor", "base", 700,
    "A walkway between two modules. Put a connector on each end.",
    box(2.0, 2.2, 3.0),
    compound(
        piece(box(2.0, 2.0, 3.0), tint="body"),
        piece(box(2.1, 0.1, 3.0), at=(0, -1.05, 0), tint="dark"),
        piece(box(1.8, 0.1, 2.8), at=(0, 1.05, 0), tint="metal"),
        *[piece(box(0.03, 0.4, 2.0), at=(sx * 1.01, 0.3, 0), tint="glass") for sx in (-1, 1)],
    ),
    [node("front", (0, 0, 1.5), (0, 0, 1)), node("back", (0, 0, -1.5), (0, 0, -1)), node("bottom", (0, -1.1, 0), (0, -1, 0))],
    [],
    crash=14.0, strength=600000.0,
))

parts.append(part(
    "base-pad", "Pad Deck", "base", 24000,
    "A twelve-metre slab to launch from and land on. Founded on its own or joined to a base, it is a launch site: craft set down on it fill up from the base's stores, and a craft standing on it can be refuelled.",
    box(12.0, 0.6, 12.0),
    compound(
        piece(box(12.0, 0.5, 12.0), at=(0, -0.05, 0), tint="metal"),
        piece(box(11.6, 0.04, 11.6), at=(0, 0.21, 0), tint="body"),
        *[piece(box(10.0, 0.02, 0.3), at=(0, 0.24, z), tint="accent") for z in (-5.0, 5.0)],
        *[piece(box(0.3, 0.02, 10.0), at=(x, 0.24, 0), tint="accent") for x in (-5.0, 5.0)],
        piece(lathe([(2.0, 0.0), (2.2, 0.0), (2.2, 0.02), (2.0, 0.02)], 24), at=(0, 0.23, 0), tint="light"),
        *[piece(box(0.25, 0.12, 0.25), at=(sx * 5.8, 0.3, sz * 5.8), tint="light") for sx in (-1, 1) for sz in (-1, 1)],
    ),
    [node("side", (6.0, 0, 0), (1, 0, 0)), node("bottom", (0, -0.3, 0), (0, -1, 0)),
     node("corner-1", (4.0, 0.3, 4.0), (0, 1, 0)), node("corner-2", (-4.0, 0.3, 4.0), (0, 1, 0))],
    [{"type": "foundation", "travel": 0.5, "maxSlope": 5.0}, {"type": "launchPad", "launchCharge": 50.0},
     {"type": "pump", "rate": 30.0, "draw": 3.0}, {"type": "lamp", "draw": 0.2, "reach": 0.0}],
    crash=25.0, strength=2000000.0,
))

parts.append(part(
    "base-floodlight", "Floodlight Mast", "base", 180,
    "A six-metre mast with a lamp head, lit at night while the base has power.",
    box(0.4, 6.0, 0.4),
    compound(
        piece(cyl(0.1, 6.0), tint="metal"),
        piece(box(0.9, 0.35, 0.5), at=(0, 2.9, 0.15), rot=(-25, 0, 0), tint="dark"),
        piece(box(0.8, 0.26, 0.04), at=(0, 2.85, 0.42), rot=(-25, 0, 0), tint="light"),
        piece(cyl(0.3, 0.1), at=(0, -2.95, 0), tint="dark"),
    ),
    [node("bottom", (0, -3.0, 0), (0, -1, 0), size=0, kind="surface")],
    [{"type": "lamp", "draw": 0.3, "reach": 22.0}],
    crash=10.0, drag=1.0,
))

# The flatbed is a part for craft built lying down: its +Y is the way it
# drives, +Z the sky - as the rover chassis is.
FW, FL = 3.2, 7.0
parts.append(part(
    "base-flatbed", "Flatbed", "base", 1400,
    "A long low deck on eight wheel mounts, for carrying a module: a cab at the front, a release clamp in the middle to hold the load and set it down.",
    box(FW, FL, 0.4),
    compound(
        piece(box(FW - 0.4, FL, 0.3), at=(0, 0, -0.05), tint="dark"),
        piece(box(FW, FL - 0.2, 0.06), at=(0, 0, 0.17), tint="metal"),
        *[piece(box(0.12, FL - 0.2, 0.22), at=(sx * (FW / 2 - 0.06), 0, 0.28), tint="accent") for sx in (-1, 1)],
        piece(box(FW, 0.2, 0.5), at=(0, -FL / 2 + 0.1, 0.1), tint="dark"),
        *[piece(box(0.3, 0.06, 0.12), at=(sx * 1.2, -FL / 2 - 0.02, 0.2), tint="light") for sx in (-1, 1)],
    ),
    [node("cab", (0, 2.7, 0.2), (0, 0, 1), size=0, kind="surface"),
     node("deck", (0, -0.7, 0.2), (0, 0, 1), size=0, kind="surface"),
     node("back", (0, -FL / 2, 0), (0, -1, 0), size=1),
     *[node(f"wheel-{k + 1}", (sx * (FW / 2 + 0.12), y, -0.15), (0, 0, -1), size=0, kind="surface")
       for k, (y, sx) in enumerate((y, sx) for y in (2.5, 0.8, -1.1, -2.8) for sx in (1, -1))]],
    [],
    crash=16.0, strength=1200000.0,
))

parts.append(part(
    "base-release-clamp", "Release Clamp", "base", 180,
    "Holds a load on a flatbed. Stage it and it lets go, staying on the flatbed while the load drops free on its own feet.",
    box(1.6, 0.3, 1.6),
    compound(
        piece(box(1.6, 0.2, 1.6), at=(0, -0.05, 0), tint="dark"),
        *[piece(box(1.3, 0.1, 0.14), at=(0, 0.1, z), tint="accent") for z in (-0.5, 0.5)],
        *[piece(box(0.14, 0.14, 0.14), at=(sx * 0.65, 0.12, 0), tint="metal") for sx in (-1, 1)],
    ),
    [node("mount", (0, -0.15, 0), (0, -1, 0), size=0, kind="surface"), node("top", (0, 0.15, 0), (0, 1, 0), size=0)],
    [{"type": "decoupler", "ejectionImpulse": 0.0, "stays": True}],
    crash=16.0, strength=1200000.0,
))

write(sys.argv[1], parts)
print(len(parts), "parts")
