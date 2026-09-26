import sys
from common import *

# Buildings stand +Y up, with the ground at their bottom: every part's
# origin is its centre, and a structure design stacks them on "ground"
# nodes. Doors and open fronts face +Z.

parts = []

def hullbox(w, h, d, at=(0, 0, 0)):
    return {"mesh": box(w, h, d), "offset": v(*at)}

def building(id, title, mass, desc, mesh, model, hull=None, solid=True, crash=40.0, modules=(), nodes=None, **kw):
    extra = dict(kw)
    if hull: extra["hull"] = hull
    if not solid: extra["solid"] = False
    parts.append(part(id, title, "structure", mass, desc, mesh, model,
                      nodes if nodes is not None else [node("ground", (0, -mesh["height"] / 2 if mesh["type"] == "box" else -mesh["height"] / 2, 0), (0, -1, 0), size=0)],
                      list(modules), crash=crash, strength=5.0e7, **extra))

# --- the launch complex ----------------------------------------------------------

# The launch tower: a lattice of four legs and cross-bracing, forty metres
# high, with service arms swung out toward the pad and a lamp on top.
H = 40.0
legs = [piece(box(0.6, H, 0.6), at=(sx * 2.6, 0, sz * 2.6), tint="accent") for sx in (-1, 1) for sz in (-1, 1)]
braces = []
for k in range(8):
    y = -H / 2 + 2.5 + k * 5.0
    braces += [piece(box(5.8, 0.3, 0.3), at=(0, y, sz * 2.6), tint="accent") for sz in (-1, 1)]
    braces += [piece(box(0.3, 0.3, 5.8), at=(sx * 2.6, y, 0), tint="accent") for sx in (-1, 1)]
    braces += [piece(box(0.25, 6.4, 0.25), at=(sx * 2.6, y + 2.5, 0), rot=(45, 0, 0), tint="dark") for sx in (-1, 1)]
arms = [piece(box(1.4, 1.0, 9.0), at=(0, y, 7.2), tint="metal") for y in (4.0, 12.0)]
arms += [piece(box(1.8, 2.2, 1.8), at=(0, y + 1.6, 11.2), tint="dark") for y in (4.0, 12.0)]
building("struct-launch-tower", "Launch Tower", 180000,
         "A forty-metre service tower beside the pad, its arms swung out toward the rocket.",
         box(6.0, H, 6.0),
         compound(*legs, *braces, *arms,
                  piece(box(6.2, 0.4, 6.2), at=(0, H / 2 - 0.2, 0), tint="dark"),
                  piece(cyl(0.12, 6.0), at=(0, H / 2 + 3.0, 0), tint="metal"),
                  piece(sphere(0.35), at=(0, H / 2 + 6.1, 0), tint="light")),
         hull=[hullbox(6.0, H, 6.0)],
         modules=[{"type": "lamp", "draw": 0.0}])

# Lightning masts round the pad.
building("struct-lightning-mast", "Lightning Mast", 12000,
         "A fifty-metre mast to take the lightning before a rocket does.",
         cyl(0.5, 50.0),
         compound(piece(cone(0.6, 0.15, 50.0), tint="metal"),
                  piece(cyl(0.05, 4.0), at=(0, 27.0, 0), tint="dark"),
                  piece(sphere(0.2), at=(0, 25.2, 0), tint="light")),
         modules=[{"type": "lamp", "draw": 0.0}])

# The assembly building: a great hall, doors on its south face.
building("struct-assembly", "Assembly Building", 2.0e6,
         "Where rockets are stacked: a hall fifty metres high, with doors to match.",
         box(60.0, 50.0, 70.0),
         compound(piece(box(60.0, 50.0, 70.0), tint="body"),
                  piece(box(22.0, 44.0, 0.4), at=(-10.0, -3.0, 35.1), tint="dark"),
                  piece(box(24.0, 1.2, 0.5), at=(-10.0, 19.4, 35.2), tint="metal"),
                  piece(box(20.0, 12.0, 0.4), at=(18.0, 0.0, 35.1), tint="accent"),
                  piece(box(61.0, 1.0, 71.0), at=(0, 25.2, 0), tint="metal"),
                  *[piece(box(0.3, 48.0, 0.4), at=(x, 0, 35.15), tint="metal") for x in (-28.0, 8.0, 28.0)]),
         hull=[hullbox(60.0, 50.0, 70.0)])

# The control centre: low, windowed, dishes on the roof.
building("struct-control-centre", "Control Centre", 400000,
         "Where launches are watched from: a long low building, windows all along its front, dishes on the roof.",
         box(34.0, 8.0, 18.0),
         compound(piece(box(34.0, 8.0, 18.0), tint="body"),
                  piece(box(32.0, 2.2, 0.3), at=(0, 1.2, 9.05), tint="glass"),
                  piece(box(34.6, 0.6, 18.6), at=(0, 4.2, 0), tint="dark"),
                  piece(cyl(0.3, 3.0), at=(10.0, 5.5, -3.0), tint="metal"),
                  piece(lathe([(0.0, 0.0), (2.2, 0.6), (2.4, 0.9), (0.0, 0.3)]), at=(10.0, 7.2, -3.0), rot=(-35, 0, 0), tint="light"),
                  piece(cyl(0.2, 2.0), at=(-8.0, 5.0, -4.0), tint="metal"),
                  piece(lathe([(0.0, 0.0), (1.2, 0.35), (1.3, 0.5), (0.0, 0.2)]), at=(-8.0, 6.2, -4.0), rot=(-40, 30, 0), tint="light")),
         hull=[hullbox(34.0, 8.0, 18.0)])

# The propellant farm: three spheres on legs, a pipe rack between them.
spheres = []
for k, x in enumerate((-12.0, 0.0, 12.0)):
    spheres.append(piece(sphere(5.0), at=(x, 1.5, 0), tint="light"))
    spheres += [piece(cyl(0.3, 3.5), at=(x + sx * 3.2, -3.25, sz * 3.2), tint="metal") for sx in (-1, 1) for sz in (-1, 1)]
building("struct-propellant-farm", "Propellant Farm", 600000,
         "Three great spheres of propellant, feeding the pads.",
         box(36.0, 13.0, 11.0),
         compound(*spheres,
                  piece(box(30.0, 0.6, 0.6), at=(0, -3.5, 5.0), tint="metal"),
                  piece(box(30.0, 0.6, 0.6), at=(0, -4.5, 5.0), tint="accent")),
         hull=[hullbox(34.0, 10.0, 10.0, at=(0, 1.5, 0))])

# A floodlight tower.
building("struct-floodlight", "Floodlight Tower", 8000,
         "A twenty-metre tower of lamps, lighting the pads at night.",
         box(1.2, 20.0, 1.2),
         compound(piece(box(0.8, 20.0, 0.8), tint="metal"),
                  piece(box(4.0, 1.6, 0.6), at=(0, 9.2, 0.6), rot=(-20, 0, 0), tint="dark"),
                  *[piece(box(0.8, 0.6, 0.1), at=(x, 9.2, 0.95), rot=(-20, 0, 0), tint="light") for x in (-1.4, -0.45, 0.45, 1.4)]),
         hull=[hullbox(1.2, 20.0, 1.2)],
         modules=[{"type": "lamp", "draw": 0.0, "reach": 60.0, "aim": 30.0}])

# --- the airfield ----------------------------------------------------------------

# A hangar, open to the south: walls and a roof to taxi under.
HW, HH, HD = 40.0, 14.0, 30.0
building("struct-hangar", "Hangar", 500000,
         "Room for a few aeroplanes out of the weather: walls on three sides, open to the apron.",
         box(HW, HH, HD),
         compound(piece(box(HW, 0.8, HD), at=(0, HH / 2 - 0.4, 0), tint="metal"),
                  piece(box(HW, 2.4, HD * 0.9), at=(0, HH / 2 + 0.8, -HD * 0.05), rot=(0, 0, 0), tint="body"),
                  piece(box(HW, HH, 0.6), at=(0, 0, -HD / 2 + 0.3), tint="body"),
                  *[piece(box(0.6, HH, HD), at=(sx * (HW / 2 - 0.3), 0, 0), tint="body") for sx in (-1, 1)],
                  piece(box(HW, 1.4, 0.7), at=(0, HH / 2 - 1.1, HD / 2 - 0.35), tint="accent"),
                  *[piece(box(0.5, 0.3, 0.3), at=(x, HH / 2 - 1.6, HD / 2), tint="light") for x in (-12.0, 0.0, 12.0)]),
         hull=[hullbox(HW, 0.8, HD, at=(0, HH / 2 - 0.4, 0)),
               hullbox(HW, HH, 0.6, at=(0, 0, -HD / 2 + 0.3)),
               hullbox(0.6, HH, HD, at=(-(HW / 2 - 0.3), 0, 0)),
               hullbox(0.6, HH, HD, at=(HW / 2 - 0.3, 0, 0))],
         modules=[{"type": "lamp", "draw": 0.0, "reach": 18.0}])

# The control tower: a shaft with a glass cab on top.
building("struct-control-tower", "Control Tower", 250000,
         "Twenty-four metres up, a cab with windows all round looks down the runway.",
         box(8.0, 24.0, 8.0),
         compound(piece(box(4.0, 18.0, 4.0), at=(0, -3.0, 0), tint="body"),
                  piece(box(8.0, 4.0, 8.0), at=(0, 8.0, 0), tint="glass"),
                  piece(box(8.6, 0.8, 8.6), at=(0, 10.4, 0), tint="dark"),
                  piece(box(8.4, 0.6, 8.4), at=(0, 5.7, 0), tint="dark"),
                  piece(cyl(0.1, 2.0), at=(2.5, 11.8, 2.5), tint="metal"),
                  piece(sphere(0.25), at=(2.5, 12.9, 2.5), tint="light")),
         hull=[hullbox(4.0, 18.0, 4.0, at=(0, -3.0, 0)), hullbox(8.0, 6.0, 8.0, at=(0, 9.0, 0))],
         modules=[{"type": "lamp", "draw": 0.0}])

# A windsock: turned and filled by the wind, when it is drawn.
building("struct-windsock", "Windsock", 200,
         "Which way the wind blows, and how hard.",
         box(0.3, 7.0, 0.3),
         compound(piece(cyl(0.08, 7.0), tint="metal"),
                  piece(cone(0.45, 0.2, 3.0), at=(0, 3.2, 1.6), rot=(90, 0, 0), tint="accent")),
         solid=False)

# Runway lamps and paint: seen, not struck.
building("struct-runway-lamp", "Runway Lamp", 20,
         "An edge lamp.",
         box(0.3, 0.4, 0.3),
         compound(piece(cyl(0.08, 0.3), at=(0, -0.05, 0), tint="dark"),
                  piece(sphere(0.2), at=(0, 0.2, 0), tint="light")),
         solid=False, modules=[{"type": "lamp", "draw": 0.0}])
building("struct-paint-bar", "Runway Paint", 1,
         "A white bar of paint.",
         box(1.8, 0.04, 22.0),
         compound(piece(box(1.8, 0.04, 22.0), tint="light")),
         solid=False)
building("struct-paint-dash", "Centreline Paint", 1,
         "A dash of paint down the runway's middle.",
         box(0.9, 0.04, 12.0),
         compound(piece(box(0.9, 0.04, 12.0), tint="light")),
         solid=False)

# --- the harbour ---------------------------------------------------------------

# A section of jetty: a deck on piles, bollards along one edge.
JL, JW = 10.0, 5.0
building("struct-jetty", "Jetty Section", 60000,
         "Ten metres of jetty: a deck on piles, with bollards to tie up to.",
         box(JW, 12.0, JL),
         compound(piece(box(JW, 0.5, JL), at=(0, 5.75, 0), tint="body"),
                  *[piece(cyl(0.25, 11.5), at=(sx * (JW / 2 - 0.4), -0.25, sz * (JL / 2 - 0.6)), tint="dark") for sx in (-1, 1) for sz in (-1, 1)],
                  *[piece(cyl(0.18, 0.5), at=(-(JW / 2 - 0.3), 6.25, z), tint="metal") for z in (-3.0, 3.0)],
                  piece(box(0.12, 0.9, JL), at=(JW / 2 - 0.06, 6.45, 0), tint="accent")),
         hull=[hullbox(JW, 0.5, JL, at=(0, 5.75, 0)),
               *[hullbox(0.5, 11.5, 0.5, at=(sx * (JW / 2 - 0.4), -0.25, sz * (JL / 2 - 0.6))) for sx in (-1, 1) for sz in (-1, 1)]],
         nodes=[node("ground", (0, -6.0, 0), (0, -1, 0), size=0)])

# The boathouse: a shed with a big door to the water.
building("struct-boathouse", "Boathouse", 120000,
         "A shed for boats, its big door facing the water.",
         box(14.0, 7.0, 18.0),
         compound(piece(box(14.0, 5.0, 18.0), at=(0, -1.0, 0), tint="body"),
                  piece(box(14.6, 0.4, 18.6), at=(-3.6, 2.6, 0), rot=(0, 0, 18), tint="accent"),
                  piece(box(14.6, 0.4, 18.6), at=(3.6, 2.6, 0), rot=(0, 0, -18), tint="accent"),
                  piece(box(10.0, 4.4, 0.2), at=(0, -1.2, 9.05), tint="dark"),
                  piece(box(0.4, 0.3, 0.2), at=(0, 1.3, 9.1), tint="light")),
         hull=[hullbox(14.0, 5.0, 18.0, at=(0, -1.0, 0))],
         modules=[{"type": "lamp", "draw": 0.0, "reach": 12.0}])

# A quayside crane.
building("struct-crane", "Harbour Crane", 90000,
         "A crane at the head of the jetty, its jib out over the berth.",
         box(4.0, 22.0, 4.0),
         compound(piece(box(3.0, 2.0, 3.0), at=(0, -10.0, 0), tint="dark"),
                  piece(box(1.2, 18.0, 1.2), at=(0, 0.0, 0), tint="accent"),
                  piece(box(2.4, 2.2, 2.4), at=(0, 9.5, 0), tint="body"),
                  piece(box(1.0, 1.0, 20.0), at=(0, 10.5, 8.0), tint="accent"),
                  piece(box(1.0, 1.0, 6.0), at=(0, 10.5, -4.5), tint="accent"),
                  piece(box(1.6, 1.6, 1.6), at=(0, 9.6, -6.8), tint="dark"),
                  piece(cyl(0.04, 8.0), at=(0, 6.5, 17.0), tint="dark"),
                  piece(box(0.8, 0.5, 0.8), at=(0, 2.3, 17.0), tint="accent")),
         hull=[hullbox(3.0, 2.0, 3.0, at=(0, -10.0, 0)), hullbox(1.2, 18.0, 1.2), hullbox(2.4, 2.2, 2.4, at=(0, 9.5, 0))])

write(sys.argv[1], parts)
print(len(parts), "parts")
