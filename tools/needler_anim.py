"""Keyframes the Needler preview in Blender (collection Preview) from the Needler parts.

Run inside Blender: exec(open(r"...\\tools\\needler_anim.py", encoding="utf-8").read())
One pose function drives every clip with the maths the in-game renderer will port. Limbs are placed by IK
onto foot targets (femur/tibia solved as two bones with the knee pole kept, the tarsus keeps its angle to the
ground), so planted feet stay put. Prints overlap / miss / slide checks.
"""
import math

import bpy
from mathutils import Matrix, Vector
from mathutils.bvhtree import BVHTree

D = math.radians
SIDES = (("L", -1), ("R", 1))
# rest joints (right side; x mirrors): pivot, knee, ankle, foot -- same numbers the model was built from
RZ = 0.68
LEG_REST = [((0.38, 0.7, RZ), (1.1, 0.95, 1.75), (2.0, 0.55, 0.0)),
            ((0.38, 1.2, RZ), (1.15, 1.6, 1.75), (2.1, 1.3, 0.0)),
            ((0.3, 1.85, RZ - 0.05), (1.1, 2.35, 1.65), (2.0, 2.2, 0.0))]
ARM_REST = ((0.42, -0.45, 0.58), (1.55, -1.18, 1.45), (1.6, -1.88, 0.55), (1.62, -2.27, 0.0))
LEG_PARTS = ("Femur", "Knee", "Tibia", "Ankle", "Tarsus")       # Coxa stays with the body
ARM_PARTS = ("Upper", "Elbow", "Fore", "Wrist", "Sting")         # Shoulder stays with the body
# limb slots front to back: 0 = arm, 1..3 = legs
SLOTS = [(side, s, i) for side, s in SIDES for i in range(4)]
WALK = dict(T=40, duty=0.75, stride=0.6, h=0.25)
RUN = dict(T=14, duty=0.5, stride=0.7, h=0.3)
WAVE = {(side, i): ((3 - i) * 0.25 + (0.5 if side == "R" else 0.0)) % 1.0 for side, s, i in SLOTS}
TETRA = {(side, i): ((i % 2) * 0.5 + (0.5 if side == "R" else 0.0)) % 1.0 for side, s, i in SLOTS}
BODY_PIVOT = Vector((0, 0.5, 0.9))
# shield frame (see the model build): fan pleats fold about the shield normal through its centre
LEAN = D(20)
SU, SV, SN = Vector((1, 0, 0)), Vector((0, math.sin(LEAN), math.cos(LEAN))), Vector((0, -math.cos(LEAN), math.sin(LEAN)))
SHIELD_C = Vector((0, -0.35, 0.95)) + SV * 0.95
FAN_START, FAN_STEP = {"R": 78, "L": 102}, {"R": -13, "L": 13}
FAN_CLOSED = {"R": 80, "L": 100}                                    # closed fan: pleats bunch up toward the top
FAN_CLOSED_STEP = 4                                                # degrees between closed pleats
DETACH = ("R", 2)
MISSING = {"limp": {("R", 2), ("L", 3)}}
CLIPS = [("walk", 0, 80), ("run", 90, 122), ("leap", 130, 180), ("bite", 190, 222), ("aim", 230, 290),
         ("shoot", 300, 340), ("detach", 350, 410), ("limp", 420, 500), ("death", 510, 580)]


def smooth(a, b, t):
    x = min(1.0, max(0.0, (t - a) / (b - a)))
    return x * x * (3 - 2 * x)


def bump(a, b, t):
    """0 -> 1 -> 0 over [a, b]."""
    return math.sin(math.pi * min(1.0, max(0.0, (t - a) / (b - a))))


def rot_about(p, axis, deg):
    p = Vector(p)
    return Matrix.Translation(p) @ Matrix.Rotation(D(deg), 4, axis) @ Matrix.Translation(-p)


def rest(side, s, i):
    """(pivot, knee, ankle, foot) of a limb in the body frame."""
    m = lambda p: Vector((s * p[0], p[1], p[2]))
    if i == 0:
        H, K, A, F = map(m, ARM_REST)
        return H + Vector((s * 0.08, 0, 0)), K, A, F
    h, k, f = map(m, LEG_REST[i - 1])
    out = Vector((s, 0, 0))
    return h + out * 0.08, k, k.lerp(f, 0.74) + Vector((0, 0, 0.1)) + out * 0.04, f


def basis(d, n):
    d = d.normalized(); n = (n - d * n.dot(d)).normalized()
    return Matrix((d, n, d.cross(n))).transposed()


def ik(H, K, A, F, target):
    """Body-frame foot target -> (femur, tibia, tarsus) matrices and the reached foot position."""
    hv0, hv1 = (F - H).xy, (target - H).xy
    yaw = math.atan2(hv1.y, hv1.x) - math.atan2(hv0.y, hv0.x)
    Rz = rot_about(H, 'Z', math.degrees(yaw))
    tars = Rz.to_3x3() @ (F - A)
    A1 = target - tars
    L1, L2 = (K - H).length, (A - K).length
    v = A1 - H
    d = max(abs(L1 - L2) + 1e-4, min(v.length, L1 + L2 - 1e-4))
    dh = v.normalized()
    A1 = H + dh * d                                                # out of reach: the foot falls short
    pole = Rz.to_3x3() @ (K - H)
    e = (pole - dh * pole.dot(dh)).normalized()
    a = (L1 * L1 - L2 * L2 + d * d) / (2 * d)
    K1 = H + dh * a + e * math.sqrt(max(0.0, L1 * L1 - a * a))
    n0, n1 = (K - H).cross(A - K), (K1 - H).cross(A1 - K1)
    femur = Matrix.Translation(H) @ (basis(K1 - H, n1) @ basis(K - H, n0).transposed()).to_4x4() @ Matrix.Translation(-H)
    tibia = Matrix.Translation(K1) @ (basis(A1 - K1, n1) @ basis(A - K, n0).transposed()).to_4x4() @ Matrix.Translation(-K)
    tarsus = Matrix.Translation(A1) @ Rz.to_3x3().to_4x4() @ Matrix.Translation(-A)
    return femur, tibia, tarsus, A1 + tars


def step_offset(phase, g):
    """Foot offset in the body frame over one cycle: (dy, dz); forward is -y."""
    if phase < g["duty"]:
        return -g["stride"] / 2 + g["stride"] * phase / g["duty"], 0.0
    k = (phase - g["duty"]) / (1 - g["duty"])
    return g["stride"] / 2 - g["stride"] * (k * k * (3 - 2 * k)), g["h"] * math.sin(math.pi * k)


def gait(t, g, offsets, missing=(), scale=1.0):
    speed = g["stride"] * scale / (g["duty"] * g["T"])
    root = Vector((0, -speed * t, 0))
    targets, lifted = {}, {"L": 0.0, "R": 0.0}
    for side, s, i in SLOTS:
        if (side, i) in missing:
            continue
        dy, dzf = step_offset((t / g["T"] + offsets[(side, i)]) % 1.0, g)
        targets[(side, i)] = root + HOME[(side, i)] + Vector((0, dy * scale, dzf))
        lifted[side] += dzf / g["h"]
    return root, targets, lifted


col = bpy.data.collections["Needler"]
prev = bpy.data.collections.get("Preview") or bpy.data.collections.new("Preview")
if prev.name not in bpy.context.scene.collection.children:
    bpy.context.scene.collection.children.link(prev)
for o in list(prev.objects):
    bpy.data.objects.remove(o)
for a in list(bpy.data.actions):
    bpy.data.actions.remove(a)

REST = {(side, i): rest(side, s, i) for side, s, i in SLOTS}
HOME = {k: REST[k][3].copy() for k in REST}
LIMB_NAMES = {(side, i): [("Arm" + side + p) if i == 0 else f"Leg{side}{i - 1}{p}" for p in (ARM_PARTS if i == 0 else LEG_PARTS)]
              for side, s, i in SLOTS}
MANDIBLES = sorted(o.name for o in col.objects if o.name.startswith("Mandible"))
PLEATS = sorted(o.name for o in col.objects if o.name.startswith("Gill") and not o.name.startswith("GillBase"))
PORTS = sorted(o.name for o in col.objects if o.name.startswith("Port"))
MOVING = set(MANDIBLES) | set(PLEATS) | set(PORTS) | {n for v in LIMB_NAMES.values() for n in v}
STATIC = [o.name for o in col.objects if o.name not in MOVING and o.name != "Needle"]
BODY = [n for n in STATIC if n.startswith(("Thorax", "Abdomen", "Head", "Carapace", "Womb", "Tail"))]
PORT_BASE = {n: min((v.co for v in bpy.data.objects[n].data.vertices), key=lambda c: c.dot(SN)) for n in PORTS}
def _uv_angle(name):
    """True angle of a pleat around the shield centre (the shield is an ellipse, so not the build angle)."""
    vs = bpy.data.objects[name].data.vertices
    c = sum((v.co for v in vs), Vector()) / len(vs) - SHIELD_C
    a = math.degrees(math.atan2(c.dot(SV), c.dot(SU)))
    return a + 360 if a < -90 else a          # left fan runs 90..270 without wrapping


PLEAT_ANGLE = {n: _uv_angle(n[:7] + "a") for n in PLEATS}

objs = {}
for n in STATIC + sorted(MOVING):
    o = bpy.data.objects.new("pv_" + n, bpy.data.objects[n].data)
    o.rotation_mode = "QUATERNION"
    prev.objects.link(o)
    objs[n] = o


def fan_matrix(n, open_, t):
    """Pleat folds toward the top of its side like a closed hand fan; closed pleats stack backwards."""
    side, k = n[4], int(n[5:7])
    closed = FAN_CLOSED[side] + FAN_CLOSED_STEP * k * (1 if side == "L" else -1)
    ang = (closed - PLEAT_ANGLE[n]) * (1 - open_)
    # gill tremble: each pleat quivers on its own rhythm, more when the fan is open
    ang += (0.4 + 1.2 * open_) * math.sin(t * 2.7 + k * 1.9 + (0.0 if side == "R" else 1.3))
    # pleats sit at depths 0/0.07 by parity, every other pair 0.14 deeper -> four layers 0.07 apart at all
    # times, so pleats that overlap while bunched never cut each other; the roots stay inside the base ring
    stack = Matrix.Translation(-SN * 0.14 * (k // 2 % 2))
    return stack @ Matrix.Translation(SHIELD_C) @ Matrix.Rotation(D(ang), 4, SN) @ Matrix.Translation(-SHIELD_C)


def frame_pose(clip, t):
    """Everything a clip sets at tick t. Targets are world-space unless body_targets says body frame."""
    fwd = dz = pitch = roll = 0.0
    root = Vector()
    targets = {k: HOME[k].copy() for k in HOME}
    body_targets, blend = {}, {}       # body-frame targets, and how far each is blended to its world target
    mand = (0.0, 0.0)                  # (outer, inner) opening in degrees
    fan, ports, fly, missing, ground = 0.0, {}, None, MISSING.get(clip, set()), 0.0
    if clip == "walk":
        root, targets, lifted = gait(t, WALK, WAVE)
        roll = 1.5 * (lifted["R"] - lifted["L"])
        dz = -0.03 * (lifted["L"] + lifted["R"]) + 0.015 * math.sin(4 * math.pi * t / WALK["T"])
        pitch = 0.8 * math.sin(4 * math.pi * t / WALK["T"])
        mand = (4 * math.sin(t * 0.3) ** 2, 6 * math.sin(t * 0.45) ** 2)
    elif clip == "run":
        root, targets, lifted = gait(t, RUN, TETRA)
        dz = 0.06 * abs(math.sin(2 * math.pi * t / RUN["T"])) - 0.08
        pitch = 4 + 2 * math.sin(2 * math.pi * t / (RUN["T"] / 2))
        roll = 2.5 * (lifted["R"] - lifted["L"])
        mand = (10, 14)
    elif clip == "leap":
        crouch = smooth(0, 10, t) * (1 - smooth(10, 13, t))
        air = smooth(10, 24, t)
        land = math.exp(-(t - 24) / 5) * smooth(23, 25, t) if t >= 23 else 0.0
        jump = 3.0
        root = Vector((0, -jump * air, 0))
        dz = -0.3 * crouch + 0.9 * bump(10, 24, t) - 0.28 * land
        pitch = -8 * crouch - 10 * bump(10, 18, t) + 12 * bump(18, 28, t)
        mand = (30 * smooth(4, 12, t) * (1 - smooth(26, 34, t)), 36 * smooth(6, 14, t) * (1 - smooth(26, 34, t)))
        tuck = bump(10, 26, t)
        after = Vector((0, -jump, 0)) if t >= 11 else Vector()
        for side, s, i in SLOTS:
            k = (side, i)
            if i == 0:
                # arms rear up while crouching, swing forward in flight, stab the ground ahead on landing
                up = smooth(2, 10, t) * (1 - smooth(18, 23, t))
                stab = smooth(20, 24, t) * (1 - smooth(34, 46, t))
                targets[k] = after + HOME[k] + Vector((0, -0.45 * stab, 0))
                if t < 26:
                    body_targets[k] = HOME[k] + Vector((0, 0.3 * up - 0.5 * stab, 1.1 * up + 0.35 * tuck * (1 - up)))
                    blend[k] = smooth(21, 25, t)
                continue
            targets[k] = after + HOME[k]
            if 11 <= t < 26:   # airborne: legs tuck in and ride with the body, then reach for the ground
                body_targets[k] = HOME[k].lerp(Vector((HOME[k].x * 0.92, HOME[k].y + 0.45, HOME[k].z + 0.12)), tuck)
                blend[k] = smooth(21, 25, t)
    elif clip == "bite":
        # lunges with the front, arms spread then hug in, both mandible pairs snap shut twice
        lunge = smooth(4, 9, t) * (1 - smooth(22, 30, t))
        pitch, fwd, dz = 9 * lunge, 0.25 * lunge, -0.1 * lunge
        snaps = sum(bump(s0, s0 + 5, t) for s0 in (9, 16))
        open_ = smooth(0, 6, t) * (1 - smooth(24, 30, t))
        mand = (38 * open_ - 34 * snaps, 44 * open_ - 40 * snaps)
        for side, s, i in SLOTS:
            if i == 0:
                hug = bump(6, 24, t)
                targets[(side, i)] = HOME[(side, i)] + Vector((-s * 0.5 * hug, -0.2 * hug, 0.45 * hug))
    elif clip == "aim":
        # stops, unfolds the radar fan, sweeps it left and right while the body tracks the target
        fan = smooth(4, 22, t)
        yaw_scan = 4 * math.sin(2 * math.pi * max(0.0, t - 24) / 24) * smooth(22, 28, t)
        roll = 1.2 * math.sin(t * 0.25)
        dz = -0.06 * smooth(0, 10, t)
        pitch = -3 * smooth(0, 10, t)
        root = Vector()
        fwd = 0.0
        for side, s, i in SLOTS:
            targets[(side, i)] = HOME[(side, i)]
        return finish(clip, t, root, fwd, dz, pitch, roll, targets, (body_targets, blend), mand, fan, ports, fly, missing, ground, yaw_scan)
    elif clip == "shoot":
        # radar open; three shots: each port group pumps and the body recoils backwards
        fan = 1.0
        shots = (8, 18, 28)
        rec = sum(math.exp(-max(0.0, t - s0) / 3) * (t >= s0) for s0 in shots)
        pitch, fwd, dz = -3 - 4 * rec, -0.06 * rec, -0.06 + 0.02 * rec
        for j, n in enumerate(PORTS):
            s0 = shots[j % 3]
            ports[n] = 1 + 0.7 * math.exp(-((t - s0) / 1.4) ** 2) - 0.25 * math.exp(-((t - s0 + 3) / 1.5) ** 2)
    elif clip == "detach":
        pull = smooth(0, 10, t)
        rec = math.exp(-max(0, t - 10) / 12) if t >= 10 else 0.0
        roll = 5 * pull * (t < 10) - 8 * rec * math.sin(max(0, t - 10) * 0.45 + 1.0)
        dz = -0.1 * rec
        mand = (30 * rec, 36 * rec)
        if t < 10:
            targets[DETACH] += Vector((0.25 * pull + 0.04 * math.sin(t * 2.7), 0.05 * math.sin(t * 3.1), 0.12 * pull + 0.06))
        else:
            fly = t - 10
        for nb, d0 in ((("R", 1), 12), (("R", 3), 15), (("L", 2), 18)):
            k = (t - d0) / 8
            sx = 1 if nb[0] == "R" else -1
            targets[nb] += Vector((sx * 0.15 * smooth(0, 1, k), 0, 0.25 * bump(0, 1, k)))
    elif clip == "limp":
        root, targets, lifted = gait(t, dict(WALK, T=52), WAVE, missing, scale=0.7)
        roll = 2.5 + 1.5 * lifted["L"] - 1.0 * lifted["R"] + 0.8 * math.sin(4 * math.pi * t / 52)
        dz = -0.12 - 0.04 * (lifted["L"] + lifted["R"])
        pitch = 1.5 + math.sin(4 * math.pi * t / 26)
    elif clip == "death":
        # legs buckle, the body drops onto its belly and tips over, limbs curl in and twitch out
        drop = smooth(4, 16, t)
        tip = smooth(14, 28, t)
        twitch = math.exp(-max(0.0, t - 20) / 14) * math.sin(t * 1.7)
        ground = drop
        roll, pitch = 10 * tip + 2 * twitch, 6 * drop
        mand = (40 * bump(0, 12, t) + 8 * twitch, 46 * bump(0, 12, t) + 10 * twitch)
        fan = 0.35 * bump(0, 10, t)
        for side, s, i in SLOTS:
            k = (side, i)
            curl = smooth(10 + 2 * i, 30 + 2 * i, t)
            body_targets[k] = HOME[k].lerp(Vector((HOME[k].x * 0.88, HOME[k].y + 0.3, 0.05)), curl) \
                + Vector((0, 0, 0.12 * twitch * (1 if (i + (side == "R")) % 2 else -1)))
            if curl == 0:
                body_targets.pop(k)
    return finish(clip, t, root, fwd, dz, pitch, roll, targets, (body_targets, blend), mand, fan, ports, fly, missing, ground, 0.0)


def finish(clip, t, root, fwd, dz, pitch, roll, targets, body_targets, mand, fan, ports, fly, missing, ground, yaw):
    B = Matrix.Translation(root + Vector((0, -fwd, dz))) @ rot_about(BODY_PIVOT, 'Z', yaw) \
        @ rot_about(BODY_PIVOT, 'X', pitch) @ rot_about(BODY_PIVOT, 'Y', roll)
    if ground > 0:   # rest the belly on the ground
        low = min((B @ v.co).z for n in BODY for v in bpy.data.objects[n].data.vertices)
        B = Matrix.Translation((0, 0, -low * ground)) @ B
    return dict(B=B, targets=targets, body_targets=body_targets, mand=mand, fan=fan, ports=ports, fly=fly, missing=missing)


detach_m0 = {}


def apply(clip, t):
    p = frame_pose(clip, t)
    B, Binv = p["B"], p["B"].inverted()
    mats = {n: B for n in STATIC}
    for n in PLEATS:
        mats[n] = B @ fan_matrix(n, p["fan"], t)
    for n in PORTS:
        k = p["ports"].get(n, 1.0); b = PORT_BASE[n]
        S = Matrix.Identity(3) + (k - 1) * Matrix((SN * SN.x, SN * SN.y, SN * SN.z)).transposed()
        mats[n] = B @ Matrix.Translation(b) @ S.to_4x4() @ Matrix.Translation(-b)
    for n in MANDIBLES:
        s = -1 if n[8] == "L" else 1
        inner = "In" in n
        piv = (s * 0.09, -1.0, 0.52) if inner else (s * 0.3, -0.86, 0.6)
        mats[n] = B @ rot_about(piv, 'Z', s * p["mand"][1 if inner else 0])
    reached = {}
    for side, s, i in SLOTS:
        k = (side, i)
        H, K, A, F = REST[k]
        names = LIMB_NAMES[k]
        if k in p["missing"]:
            # torn off at the knee: the femur stump hangs and jerks, the rest is gone
            jerk = math.sin(t * 0.9 + i) ** 8
            stump = rot_about(H, 'Y', -s * (35 - 15 * jerk)) @ rot_about(H, 'Z', s * 10 * jerk)
            mats[names[0]] = B @ stump
            gone = B @ stump @ Matrix.Translation(K) @ Matrix.Scale(1e-4, 4) @ Matrix.Translation(-K)
            for n in names[1:]:
                mats[n] = gone
            continue
        bt, blend = p["body_targets"]
        tgt = Binv @ p["targets"][k]
        if k in bt:
            tgt = bt[k].lerp(tgt, blend.get(k, 0.0))
        femur, tibia, tarsus, foot = ik(H, K, A, F, tgt)
        if clip == "detach" and k == DETACH and p["fly"] is not None:
            tibia = femur @ rot_about(K, 'Y', s * 25 * math.exp(-p["fly"] / 8) * math.sin(p["fly"] * 1.9))
        for n, m in zip(names, (femur, femur, tibia, tarsus, tarsus)):
            mats[n] = B @ m
        reached[k] = (B @ foot, B @ tgt)
    if p["fly"] is not None:
        names = LIMB_NAMES[DETACH]
        if not detach_m0:
            for n in names[1:]:
                detach_m0[n] = mats[n].copy()
        tau = min(p["fly"], 16)
        off = Vector((0.09 * tau, -0.02 * tau, 0.14 * tau - 0.012 * tau * tau))
        c = detach_m0[names[2]] @ REST[DETACH][1].lerp(REST[DETACH][2], 0.5)
        spin = rot_about(c, 'Y', 22 * tau) @ rot_about(c, 'X', 9 * tau)
        for n in names[1:]:
            mats[n] = Matrix.Translation(off) @ spin @ detach_m0[n]
    return mats, reached


def tree(src, m):
    me = bpy.data.objects[src].data
    return BVHTree.FromPolygons([m @ v.co for v in me.vertices], [q.vertices[:] for q in me.polygons])


ROOT_OK = {"Femur": ("Abdomen", "Thorax", "Coxa"), "Upper": ("Thorax", "Head", "Shoulder")}
bad, miss, slide, last = {}, {}, {}, {}
for clip, f0, f1 in CLIPS:
    detach_m0.clear(); last.clear()
    for f in range(f0, f1 + 1):
        t = f - f0
        mats, reached = apply(clip, t)
        for key, m in mats.items():
            o = objs[key]
            o.matrix_world = m
            o.keyframe_insert("location", frame=f)
            o.keyframe_insert("rotation_quaternion", frame=f)
            o.keyframe_insert("scale", frame=f)
        if f % 2:
            continue
        # overlaps: limb segments against the body, the fan and other limbs (roots may sit in the body)
        trees = {n: tree(n, mats[n]) for n in mats if not n.startswith(("Port",))}
        solid = [n for n in trees if n.startswith(("Thorax", "Abdomen", "Head", "Carapace", "Womb", "Tail", "Gill", "Coxa", "Shoulder"))
                 or n.endswith(("Coxa", "Shoulder"))]
        for (side, i), names in LIMB_NAMES.items():
            if (side, i) in MISSING.get(clip, ()) or (clip == "detach" and (side, i) == DETACH and t >= 10):
                continue
            for n in names:
                part = n[len("ArmR"):] if n.startswith("Arm") else n[len("LegR0"):]
                skip = ROOT_OK.get(part, ())
                for o in solid:
                    if o.startswith(skip) or o.endswith(skip):
                        continue
                    if trees[n].overlap(trees[o]):
                        bad.setdefault(clip, set()).add((n, o))
                for (s2, j), names2 in LIMB_NAMES.items():
                    if (s2, j) != (side, i) and abs(j - i) == 1 and s2 == side and names2[2] in trees and trees[n].overlap(trees[names2[2]]):
                        bad.setdefault(clip, set()).add((n, names2[2]))
        for n in PLEATS:
            for o in [o for o in trees if o.startswith(("Womb", "Leg", "Arm", "Head"))]:
                if trees[n].overlap(trees[o]):
                    bad.setdefault(clip, set()).add((n, o))
            for o in PLEATS:   # pleats of one fan must not cut each other (own a/b halves are one pleat)
                if o > n and o[:7] != n[:7] and trees[n].overlap(trees[o]):
                    bad.setdefault(clip, set()).add((n, o))
        for k, (foot, want) in reached.items():
            if clip in ("death",):
                continue
            err = (foot - want).length
            if err > miss.get(clip, (0,))[0]:
                miss[clip] = (round(err, 3), k, f)
            if k in last and foot.z < 0.02 and last[k].z < 0.02 and clip != "leap":
                slide[clip] = max(slide.get(clip, 0), (foot.xy - last[k].xy).length / 2)
            last[k] = foot
for o in objs.values():
    if o.animation_data and o.animation_data.action:
        for fc in o.animation_data.action.fcurves:
            for kp in fc.keyframe_points:
                kp.interpolation = "LINEAR"
scene = bpy.context.scene
scene.timeline_markers.clear()
for clip, f0, f1 in CLIPS:
    scene.timeline_markers.new(clip, frame=f0)
scene.frame_start, scene.frame_end = 0, CLIPS[-1][2]
scene.render.fps = 20
col.hide_viewport = True
prev.hide_viewport = False
scene.frame_set(0)
bpy.ops.wm.save_mainfile()
# rig data the in-game renderer needs, generated so the game always matches this preview
BELLY = sorted({tuple(round(c, 4) for c in v.co) for n in BODY for v in bpy.data.objects[n].data.vertices if v.co.z < 0.62})
fmt = lambda v: "{%s}" % ", ".join("%.4f" % c for c in v)
JAVA = """package com.hbm.enemies.glyphid;

/** Generated by tools/needler_anim.py from blender/glyphid_needler.blend, do not edit. Blender space. */
public final class NeedlerRig {

    /** Fan pleats (mesh groups GillXnn + a/b), true angle around the shield centre in degrees. */
    public static final String[] PLEATS = {%s};
    public static final double[] PLEAT_ANGLE = {%s};
    /** Needle ports: mesh group and the base point the tube pumps from. */
    public static final String[] PORTS = {%s};
    public static final double[][] PORT_BASE = {%s};
    /** Body vertices near the ground: the dead body rests on the lowest of them. */
    public static final double[][] BELLY = {%s};

    private NeedlerRig() {}
}
""" % (", ".join('"%s"' % n for n in PLEATS), ", ".join("%.3f" % PLEAT_ANGLE[n] for n in PLEATS),
       ", ".join('"%s"' % n for n in PORTS), ", ".join(fmt(PORT_BASE[n]) for n in PORTS), ", ".join(fmt(v) for v in BELLY))
RIG = r"E:\ModPorting\ntm-next-enemies-overhaul\common\src\main\java\com\hbm\enemies\glyphid\NeedlerRig.java"
open(RIG, "w", encoding="utf-8").write(JAVA)
print("rig:", len(PLEATS), "pleats", len(PORTS), "ports", len(BELLY), "belly points")
print("overlaps:", {c: sorted(v)[:8] for c, v in bad.items()}, {c: len(v) for c, v in bad.items()})
print("foot misses its target (blocks, limb, frame):", miss)
print("planted foot slides (blocks per tick):", {k: round(v, 3) for k, v in slide.items()})
