import json
def v(x, y, z): return {"x": float(x), "y": float(y), "z": float(z)}
def box(w, h, d): return {"type": "box", "width": float(w), "height": float(h), "depth": float(d)}
def cyl(r, h): return {"type": "cylinder", "radius": float(r), "height": float(h)}
def cone(rb, rt, h): return {"type": "cone", "bottomRadius": float(rb), "topRadius": float(rt), "height": float(h)}
def sphere(r): return {"type": "sphere", "radius": float(r)}
def prim(mesh): return {"type": "primitive", "mesh": mesh}
def piece(mesh_or_model, at=(0,0,0), rot=None, tint=None, **kw):
    model = mesh_or_model if mesh_or_model.get("type") in ("primitive","lathe","compound","tank","fin","loft","tyre","prop","noseCone") else prim(mesh_or_model)
    p = {"model": model, "offset": v(*at)}
    if rot: p["rotation"] = v(*rot)
    if tint: p["tint"] = tint
    p.update(kw)
    return p
def lathe(profile, segments=20): return {"type": "lathe", "profile": [[float(a), float(b)] for a, b in profile], "segments": segments}
def compound(*pieces): return {"type": "compound", "pieces": list(pieces)}
def node(id, at, direction, size=3, kind=None):
    n = {"id": id, "position": v(*at), "direction": v(*direction), "size": size}
    if kind: n["kind"] = kind
    return n
def part(id, title, category, mass, desc, mesh, model, nodes, modules=(), crash=15.0, drag=1.0, **kw):
    p = {"id": id, "title": title, "category": category, "dryMass": float(mass), "description": desc,
         "crashTolerance": float(crash), "dragCoefficient": float(drag), "mesh": mesh, "model": model,
         "attachNodes": nodes, "modules": list(modules)}
    p.update(kw)
    return p
def write(path, parts):
    with open(path, "w") as f:
        json.dump(parts, f, indent=2)
        f.write("\n")
