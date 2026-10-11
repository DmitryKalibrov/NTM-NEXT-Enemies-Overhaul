"""Builds the flying glyphid ("butterfly") in Blender, collection Butterfly. Re-run after changing numbers.

Run inside Blender: exec(open(r"...\\tools\\butterfly_model.py", encoding="utf-8").read())
Blender space: -Y forward, Z up, 1 unit = 1 block. Every part is a convex hull; parts only meet at joints.
Wings are leathery: bony struts with knuckles, skin stretched between them in scallops, torn and holed,
and the two sides differ.
"""
import math
import random

import bmesh
import bpy
from mathutils import Vector

col = bpy.data.collections.get("Butterfly") or bpy.data.collections.new("Butterfly")
if col.name not in bpy.context.scene.collection.children:
    bpy.context.scene.collection.children.link(col)
for o in list(col.objects):
    bpy.data.objects.remove(o)


def material(name, rgba):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    m.diffuse_color = rgba
    return m


M = dict(plate=material("plate", (0.38, 0.36, 0.42, 1)), flesh=material("flesh", (0.45, 0.27, 0.36, 1)),
         skin=material("skin", (0.55, 0.36, 0.3, 1)), bone=material("bone", (0.82, 0.78, 0.68, 1)),
         eye=material("eye", (0.12, 0.05, 0.05, 1)))


def hull(name, pts, m):
    bm = bmesh.new()
    for p in pts:
        bm.verts.new(p)
    bmesh.ops.convex_hull(bm, input=bm.verts)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new(name, me)
    col.objects.link(o)
    me.materials.append(M[m])
    return o


def ell(c, r, n=10, m=6):
    c = Vector(c)
    return [c + Vector((r[0] * math.sin(math.pi * i / m) * math.cos(2 * math.pi * j / n),
                        r[1] * math.sin(math.pi * i / m) * math.sin(2 * math.pi * j / n),
                        r[2] * math.cos(math.pi * i / m))) for i in range(m + 1) for j in range(n)]


def frame(d):
    d = d.normalized()
    u = d.cross(Vector((0, 0, 1)))
    u = u.normalized() if u.length > 1e-3 else d.orthogonal().normalized()
    return d, u, d.cross(u)


def limb(name, a, b, radii, m, flat=1.0, bend=None, n=6):
    """Tapered segment a->b through rings [(t, r)], optionally bowed by `bend`."""
    a, b = Vector(a), Vector(b)
    d, u, v = frame(b - a)
    pts = []
    for t, r in radii:
        c = a.lerp(b, t) + (bend * math.sin(math.pi * t) if bend is not None else Vector())
        pts += [c + (u * math.cos(2 * math.pi * k / n) * flat + v * math.sin(2 * math.pi * k / n)) * max(r, 0.006) for k in range(n)]
    return hull(name, pts, m)


def knob(name, c, r, m="flesh"):
    return hull(name, ell(c, (r, r, r), 8, 4), m)


# ---------------------------------------------------------------- body: one arc from head to stinger
ARC = [(-0.78, 1.3), (-0.55, 1.42), (-0.28, 1.47), (0.0, 1.45), (0.26, 1.38), (0.5, 1.25), (0.72, 1.07), (0.9, 0.85),
       (1.04, 0.6), (1.12, 0.36)]
CORE = [0.2, 0.25, 0.26, 0.24, 0.25, 0.23, 0.2, 0.16, 0.12, 0.08]
SEG = ["Thorax0", "Thorax1", "Thorax2", "Abdomen0", "Abdomen1", "Abdomen2", "Abdomen3", "Abdomen4", "Abdomen5"]


def ring(c, d, r, rz, n=10, arc=(0, 2 * math.pi)):
    _, u, v = frame(d)
    if v.z < 0:
        v = -v
    a0, a1 = arc
    return [c + u * r * math.cos(a0 + (a1 - a0) * k / (n - 1)) + v * rz * math.sin(a0 + (a1 - a0) * k / (n - 1)) for k in range(n)]


P = [Vector((0, y, z)) for y, z in ARC]
for k, name in enumerate(SEG):
    a, b = P[k], P[k + 1]
    d = b - a
    ra, rb = CORE[k], CORE[k + 1]
    mid = a.lerp(b, 0.5)
    # soft core: slightly waisted at the joints, fattest mid-segment
    hull(name, ring(a + d * 0.04, d, ra * 0.92, ra * 0.82) + ring(mid, d, max(ra, rb) * 1.04, max(ra, rb) * 0.92)
         + ring(b - d * 0.04, d, rb * 0.92, rb * 0.82), "flesh")
    # dorsal armour plate over the top 200 degrees, a hair shorter than the segment so plates never touch
    arcspan = (math.radians(-10), math.radians(190))
    w = 1.0 if k > 2 else 1.08
    hull(name + "Plate", ring(a + d * 0.1, d, ra * 1.12 * w, ra * 1.05, 9, arcspan)
         + ring(mid, d, max(ra, rb) * 1.2 * w, max(ra, rb) * 1.1, 9, arcspan)
         + ring(b - d * 0.1, d, rb * 1.1 * w, rb * 1.02, 9, arcspan), "plate")
# barbed stinger: long spike with a forked, hooked tip
tip0, tip1 = P[-1], Vector((0, 1.16, -0.12))
limb("Stinger", tip0, tip1, [(0, 0.075), (0.6, 0.04), (1, 0.0)], "bone", bend=Vector((0, 0.05, 0)))
for s in (-1, 1):
    limb("StingerBarb" + "LR"[s > 0], tip1.lerp(tip0, 0.28) + Vector((s * 0.01, 0, 0)),
         tip1.lerp(tip0, 0.12) + Vector((s * 0.09, 0.05, 0.06)), [(0, 0.022), (1, 0.0)], "bone")

# ---------------------------------------------------------------- head: helmet, compound eyes, mandibles, clubbed antennae
H = Vector((0, -1.02, 1.2))
limb("Neck", P[0] + Vector((0, 0.02, -0.02)), H + Vector((0, 0.12, 0.02)), [(0, 0.13), (1, 0.11)], "flesh")
hull("Head", ell(H, (0.22, 0.2, 0.18)) + [H + Vector((0, -0.24, -0.06))], "flesh")
hull("HeadPlate", [p for p in ell(H + Vector((0, 0.02, 0.03)), (0.25, 0.23, 0.2), 10, 6) if p.z >= H.z - 0.02]
     + [H + Vector((0, -0.25, 0.0))], "plate")
for s in (-1, 1):
    n = "LR"[s > 0]
    hull("Eye" + n, ell(H + Vector((s * 0.18, -0.1, 0.02)), (0.09, 0.1, 0.1), 8, 4), "eye")
    limb("Mandible" + n + "0", H + Vector((s * 0.1, -0.16, -0.1)), H + Vector((s * 0.16, -0.32, -0.16)), [(0, 0.04), (1, 0.03)], "bone")
    limb("Mandible" + n + "1", H + Vector((s * 0.16, -0.32, -0.16)), H + Vector((s * 0.05, -0.42, -0.2)), [(0, 0.03), (1, 0.004)], "bone")
    base = H + Vector((s * 0.09, -0.12, 0.15))
    pts = [base, base + Vector((s * 0.12, -0.25, 0.3)), base + Vector((s * 0.3, -0.45, 0.48)), base + Vector((s * 0.5, -0.55, 0.58))]
    for k in range(3):
        limb(f"Antenna{n}{k}", pts[k], pts[k + 1], [(0, 0.022 - k * 0.004), (1, 0.018 - k * 0.004)], "plate")
    hull("Antenna" + n + "Club", ell(pts[3] + (pts[3] - pts[2]).normalized() * 0.05, (0.045, 0.06, 0.045), 8, 4), "plate")

# ---------------------------------------------------------------- legs: under the thorax and wings, hanging in flight
# (hip, knee, ankle, foot) of the right side; front pair reaches forward, hind pair trails under the abdomen
LEGS = [((0.14, -0.62, 1.18), (0.42, -0.85, 1.02), (0.36, -1.05, 0.62), (0.3, -1.2, 0.38)),
        ((0.16, -0.32, 1.12), (0.5, -0.35, 0.95), (0.46, -0.3, 0.5), (0.4, -0.22, 0.22)),
        ((0.15, -0.04, 1.08), (0.48, 0.15, 0.92), (0.42, 0.45, 0.52), (0.33, 0.68, 0.3))]
for i, joints in enumerate(LEGS):
    for s in (-1, 1):
        n = f"Leg{'LR'[s > 0]}{i}"
        h, k, a, f = [Vector((s * p[0], p[1], p[2])) for p in joints]
        out = Vector((s, 0, 0))
        knob(n + "Coxa", h, 0.08)
        limb(n + "Femur", h + out * 0.04, k, [(0, 0.065), (0.45, 0.08), (1, 0.055)], "plate", flat=0.8, bend=Vector((0, 0, 0.03)))
        knob(n + "Knee", k, 0.06)
        limb(n + "Tibia", k, a, [(0, 0.05), (0.3, 0.055), (1, 0.035)], "plate", flat=0.85)
        knob(n + "Ankle", a, 0.038)
        limb(n + "Tarsus", a, f, [(0, 0.032), (1, 0.022)], "plate")
        limb(n + "Claw", f, f + (f - a).normalized() * 0.08 + Vector((0, -0.03, -0.06)), [(0, 0.022), (1, 0.0)], "bone")

# ---------------------------------------------------------------- wings: leathery, torn, asymmetric


def wing(name, s, shoulder, knee, fingers, sag, seed, trail, tears=(), holes=(), blade=(0.16, 0.2, 0.07)):
    """Dragon/bat-like leather wing. A bony arm rises from the shoulder blade to a big knee joint on the leading
    edge; fingers [(angle, length)] fan out from the knee (angle 0 = straight out sideways, 90 = straight back) and
    droop by `sag` per block. Skin hangs between neighbouring fingers in uneven scallops, between the arm and the
    first finger, and from the last finger back to the body at `trail`. tears: panels ripped deep; holes: panels
    with a hole torn through. Mirrored by s (-1 left, +1 right)."""
    rng = random.Random(seed)
    sh, kn = Vector(shoulder), Vector(knee)
    hull(name + "Scapula", ell(sh + Vector((0, 0, -0.02)), blade, 8, 4), "plate")
    arm = limb(name + "Arm", sh, kn, [(0, 0.075), (0.5, 0.085), (1, 0.065)], "bone", bend=Vector((0, -0.06, 0.05)))
    arm["shoulder"], arm["knee"], arm["trail"] = tuple(sh), tuple(kn), tuple(trail)
    knob(name + "Knee", kn, 0.1, "bone")
    hull(name + "Spur", [kn + Vector((s * 0.02, -0.05, 0.06)) + v for v in ell((0, 0, 0), (0.035, 0.035, 0.035), 6, 3)]
         + [kn + Vector((s * 0.12, -0.22, 0.2))], "bone")            # the knee's hooked claw
    tips, mids = [], []
    for k, (ang, L) in enumerate(fingers):
        a = math.radians(ang)
        dirv = Vector((s * math.cos(a), math.sin(a), 0))
        side = Vector((-dirv.y, dirv.x, 0))
        tip = kn + dirv * L + side * rng.uniform(-0.15, 0.15) + Vector((0, 0, -sag * L))
        mid = kn.lerp(tip, rng.uniform(0.48, 0.58)) + Vector((0, 0, rng.uniform(-0.04, 0.04)))
        tips.append(tip)
        mids.append(mid)
        limb(f"{name}Finger{k}a", kn, mid, [(0, 0.05), (1, 0.038)], "bone")
        knob(f"{name}Knuckle{k}", mid, 0.045, "bone")
        fb = limb(f"{name}Finger{k}b", mid, tip, [(0, 0.034), (1, 0.008)], "bone")
        fb["mid"], fb["tip"] = tuple(mid), tuple(tip)            # joints for the animation rig
    th = 0.022
    skin = lambda nm, pts: hull(nm, [p + Vector((0, 0, dz)) for p in pts for dz in (0, th)], "skin")
    toward = lambda p: (kn - p).normalized()
    # between the arm and the first finger: a narrow leading-edge web
    skin(name + "SkinFront", [sh.lerp(kn, 0.25), kn, mids[0].lerp(tips[0], 0.3), sh.lerp(kn, 0.25).lerp(mids[0], 0.4)])
    for k in range(len(tips) - 1):
        a0, a1, m0, m1 = tips[k], tips[k + 1], mids[k], mids[k + 1]
        span = (a1 - a0).length
        e1, e2 = a0.lerp(a1, rng.uniform(0.25, 0.4)), a0.lerp(a1, rng.uniform(0.6, 0.75))
        d1, d2 = rng.uniform(0.16, 0.3) * span, rng.uniform(0.16, 0.3) * span
        if k in tears:
            d1 += 0.38 * span
            d2 += 0.1 * span
        s1, s2 = e1 + toward(e1) * d1, e2 + toward(e2) * d2
        m = m0.lerp(m1, 0.5).lerp(kn, 0.15)
        if k in holes:
            skin(f"{name}Skin{k}a", [kn, m0.lerp(kn, 0.5), m1.lerp(kn, 0.5)])
            skin(f"{name}Skin{k}b", [m0, a0, s1, m0.lerp(s1, 0.45)])
            skin(f"{name}Skin{k}c", [m1, m1.lerp(s2, 0.45), s2, a1])
        else:
            skin(f"{name}Skin{k}a", [kn, m0, a0, s1, m])
            skin(f"{name}Skin{k}b", [kn, m, s1, s2])
            skin(f"{name}Skin{k}c", [kn, s2, a1, m1])
    # the last finger to the body: the web joins the flank, hanging in a deep curve
    t = Vector(trail)
    last = tips[-1]
    curve = last.lerp(t, 0.5) + (kn - last.lerp(t, 0.5)).normalized() * 0.3 * (last - t).length
    skin(name + "SkinTrailA", [kn, mids[-1], last, curve])
    skin(name + "SkinTrailB", [kn, curve, t, sh.lerp(kn, 0.2)])


for s, side in ((-1, "L"), (1, "R")):
    m = lambda p: (s * p[0], p[1], p[2])
    if side == "R":
        wing("WingForeR", s, m((0.2, -0.52, 1.68)), m((1.1, -0.8, 2.45)), [(-28, 2.5), (2, 2.9), (30, 2.6), (58, 2.0)], 0.32, 11,
             trail=m((0.3, -0.26, 1.5)), tears=(1,))
        wing("WingHindR", s, m((0.22, -0.1, 1.64)), m((0.85, 0.3, 2.05)), [(35, 1.6), (62, 1.9), (88, 1.5)], 0.3, 12,
             trail=m((0.3, 0.72, 1.22)), holes=(0,), blade=(0.13, 0.09, 0.06))
    else:
        # the left wing took more damage: a finger snapped short, two rips and a hole
        wing("WingForeL", s, m((0.2, -0.52, 1.68)), m((1.05, -0.78, 2.4)), [(-31, 2.35), (0, 2.7), (27, 1.7), (56, 2.1)], 0.34, 21,
             trail=m((0.3, -0.26, 1.5)), tears=(0, 2), holes=(1,))
        wing("WingHindL", s, m((0.22, -0.1, 1.64)), m((0.88, 0.32, 2.0)), [(37, 1.7), (64, 1.65), (90, 1.55)], 0.32, 22,
             trail=m((0.3, 0.72, 1.22)), tears=(1,), blade=(0.13, 0.09, 0.06))
print("parts", len(col.objects))


# ---------------------------------------------------------------- self-check: parts may only meet at their joints
from mathutils.bvhtree import BVHTree


def _tree(o):
    bm = bmesh.new()
    bm.from_mesh(o.data)
    t = BVHTree.FromBMesh(bm)
    bm.free()
    return t


def _chain(n):
    for p in ("WingForeL", "WingForeR", "WingHindL", "WingHindR", "Antenna", "Mandible", "Stinger"):
        if n.startswith(p):
            return p + (n[len(p)] if p in ("Antenna", "Mandible") else "")
    return n[:5] if n.startswith("Leg") else "body"


# a limb's root may sit in the body part it grows from
_ROOTS = (("Scapula", "Thorax"), ("Arm", "Thorax"), ("Coxa", "Thorax"), ("SkinTrail", "Thorax"), ("SkinTrail", "Abdomen"),
          ("SkinFront", "Thorax"), ("Stinger", "Abdomen5"), ("Antenna", "HeadPlate"), ("Antenna", "Head"), ("Mandible", "Head"))
_objs = list(col.objects)
_T = {o.name: _tree(o) for o in _objs}
_bad = []
for i, a in enumerate(_objs):
    for b in _objs[i + 1:]:
        if _chain(a.name) == _chain(b.name) or not _T[a.name].overlap(_T[b.name]):
            continue
        if any((x.endswith(r) or x.startswith(r) or r in x) and y.startswith(body) for r, body in _ROOTS
               for x, y in ((a.name, b.name), (b.name, a.name))):
            continue
        _bad.append((a.name, b.name))
print("overlaps:", len(_bad), _bad[:30])
