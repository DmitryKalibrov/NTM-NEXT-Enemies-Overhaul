"""Keyframes the glybnik preview in Blender (collection Preview) from the Glybnik parts.

Run inside Blender: exec(open(r"...\\tools\\glybnik_anim.py", encoding="utf-8").read())
Every clip is computed from one pose function with the same maths the in-game renderer will use;
legs are placed by IK onto claw targets, so feet stay planted. Prints overlap / miss / slide checks.
"""
import math

import bpy
from mathutils import Matrix, Vector
from mathutils.bvhtree import BVHTree

P = 1 / 16
HIP_Z = 11 * P
D = math.radians
J = dict(hip=10 * P, knee=21 * P, ankle=31 * P, claw=42 * P)
L1, L2 = 11 * P, 10 * P
L3 = math.hypot(11 + 8 * math.cos(D(20)), 8 * math.sin(D(20))) * P
DELTA = math.atan2(-8 * math.sin(D(20)), 11 + 8 * math.cos(D(20)))

# (hip y, yaw, lift, knee, ankle) per slot, front to back; claw 20
STANCE = [(-5, -25, 0, 30, -19.7), (-1, -8, 20, 30, 25.7), (5, 14, 20, 30, 25.7), (11, 40, 10, 30, 2.1)]
YAW_MIN = [-45, -13, -45, -45]          # second pair would touch the head plate further forward
HOME_SHIFT = [3, 3, 0, -4]              # claw homes (1/16, + = back): stride within YAW_MIN and reach
FOLD = (-15, 110, 110, 60)              # lift, knee, ankle, claw when hidden
T, DUTY, STRIDE, STEP_H = 32, 0.75, 12 * P, 6 * P
SPEED = STRIDE / (DUTY * T)
DETACH = ("R", 1)
BODY_PIVOT = (0, 2 * P, 20 * P)
CLIPS = [("walk", 0, 64), ("detach", 75, 135), ("hide", 145, 205), ("bash", 215, 255), ("spores", 265, 325),
         ("limp", 340, 420), ("collapsed", 430, 530), ("hide_spores", 540, 620)]
# legs already torn off in a clip: only the thigh stump stays and twitches
MISSING = {"limp": {("R", 1), ("R", 2)}, "collapsed": {("R", 0), ("R", 1), ("R", 2), ("L", 3)}}
LIMP_T = 44                              # slower, uneven cycle with legs missing
COLLAPSE_ROLL = 30                       # lying on the side that lost its legs (+ = right side down)
SLOTS = [(side, s, i) for side, s in (("L", -1), ("R", 1)) for i in range(4)]
OFFSET = {(side, i): ((3 - i) * 0.25 + (0.5 if side == "R" else 0.0)) % 1.0 for side, s, i in SLOTS}
LEG_PARTS = ("Upper", "Knee", "Shin", "Ankle", "Blade", "ClawJoint", "Claw")


def rot_about(p, axis, deg):
    p = Vector(p)
    return Matrix.Translation(p) @ Matrix.Rotation(D(deg), 4, axis) @ Matrix.Translation(-p)


def smooth(a, b, t):
    x = min(1.0, max(0.0, (t - a) / (b - a)))
    return x * x * (3 - 2 * x)


def chain(s, y, yaw, lift, knee, ankle, claw=20.0):
    pt = lambda k: (s * J[k], 0, HIP_Z)
    up = Matrix.Translation((0, y, 0)) @ rot_about(pt("hip"), 'Z', s * yaw) @ rot_about(pt("hip"), 'Y', -s * lift)
    sh = up @ rot_about(pt("knee"), 'Y', s * knee)
    bl = sh @ rot_about(pt("ankle"), 'Y', s * ankle)
    cl = bl @ rot_about(pt("claw"), 'Y', s * claw)
    return {"Upper": up, "Knee": sh, "Shin": sh, "Ankle": bl, "Blade": bl, "ClawJoint": cl, "Claw": cl}


def ik(s, y, lift, target):
    """Body-frame claw target -> (yaw, knee, ankle) with the hip lift fixed; the knee joint stays up."""
    hx, hy = target.x - s * J["hip"], target.y - y
    yaw = math.degrees(math.atan2(hy, s * hx))
    u, w = math.hypot(hx, hy), target.z - HIP_Z
    lr = D(lift)
    ku, kw = L1 * math.cos(lr), L1 * math.sin(lr)
    du, dw = u - ku, w - kw
    d = max(abs(L2 - L3) + 1e-4, min(math.hypot(du, dw), L2 + L3 - 1e-4))
    a1 = math.atan2(dw, du) + math.acos(max(-1, min(1, (L2 * L2 + d * d - L3 * L3) / (2 * L2 * d))))
    au, aw = ku + L2 * math.cos(a1), kw + L2 * math.sin(a1)
    a2 = math.atan2(w - aw, u - au) - DELTA
    return yaw, math.degrees(lr - a1), math.degrees(a1 - a2)


def step_offset(phase):
    """Claw offset in the body frame over one walk cycle: (dy, dz); forward is -y."""
    if phase < DUTY:
        return -STRIDE / 2 + STRIDE * phase / DUTY, 0.0
    k = (phase - DUTY) / (1 - DUTY)
    return STRIDE / 2 - STRIDE * (k * k * (3 - 2 * k)), STEP_H * math.sin(math.pi * k)


col = bpy.data.collections["Glybnik"]
prev = bpy.data.collections["Preview"]
for o in list(prev.objects):
    bpy.data.objects.remove(o)
for a in list(bpy.data.actions):
    bpy.data.actions.remove(a)

HOME = {(side, i): chain(s, STANCE[i][0] * P, *STANCE[i][1:])["Claw"] @ Vector((s * 50 * P, 0, HIP_Z))
        + Vector((0, HOME_SHIFT[i] * P, 0)) for side, s, i in SLOTS}
STATIC = [o.name for o in col.objects if not o.name.startswith(("Leg", "Mandible", "Pod"))]
PODS = sorted(o.name for o in col.objects if o.name.startswith("Pod"))
HULL = ("PlateHead", "PlateBackR", "PlateBackL", "PlateRear", "Body", "Maw")
POD_BASE = {}
for n in PODS:
    bb = [Vector(c) for c in bpy.data.objects[n].bound_box]
    c = sum(bb, Vector()) / 8
    POD_BASE[n] = Vector((c.x, c.y, min(v.z for v in bb)))
POD_PHASE = {n: (1 - (POD_BASE[n].z * 16 - 20) / 35) * math.pi for n in PODS}

objs = {}


def mk(key, src):
    o = bpy.data.objects.new("pv_" + key, bpy.data.objects[src].data)
    o.rotation_mode = "QUATERNION"
    prev.objects.link(o)
    objs[key] = o


for n in STATIC + PODS:
    mk(n, n)
for side in "LR":
    mk("Mandible" + side, "Mandible" + side)
    mk("Mandible%sHook" % side, "Mandible%sHook" % side)
for side, s, i in SLOTS:
    for p in LEG_PARTS:
        mk(f"{side}{i}{p}", f"Leg{side}{p}")


def frame_pose(clip, t):
    fwd = dz = pitch = roll = mand = 0.0
    root = Vector((0, 0, 0))
    targets = {k: HOME[k].copy() for k in HOME}
    pods = {n: (1.0, 1.0) for n in PODS}
    fly, fold, fk, ground = None, 0.0, {}, False
    if clip == "walk":
        root = Vector((0, -SPEED * t, 0))
        lifted = {"L": 0.0, "R": 0.0}
        for side, s, i in SLOTS:
            dy, dzf = step_offset((t / T + OFFSET[(side, i)]) % 1.0)
            targets[(side, i)] = root + HOME[(side, i)] + Vector((0, dy, dzf))
            lifted[side] += dzf / STEP_H
        roll = 2.5 * (lifted["R"] - lifted["L"])          # leans away from the lifted legs
        dz = -0.6 * P * (lifted["L"] + lifted["R"])       # dips while legs are in the air
        pitch = 0.8 * math.sin(2 * math.pi * t / (T / 2))
    elif clip == "detach":
        pull = smooth(0, 10, t)
        rec = math.exp(-max(0, t - 10) / 12) if t >= 10 else 0.0
        roll = 5 * pull * (1 if t < 10 else 0) - 7 * rec * math.sin(max(0, t - 10) * 0.45 + 1.0)
        dz = -1.5 * P * rec
        mand = 22 * rec
        if t < 10:
            targets[DETACH] += Vector((4 * P * pull + 0.6 * P * math.sin(t * 2.7), 0.8 * P * math.sin(t * 3.1), 1.5 * P * pull + 1 * P))
        else:
            fly = t - 10
        # neighbours re-plant to carry the load: lift, step outward, put down
        for nb, d0 in ((("R", 0), 12), (("R", 2), 15), (("L", 1), 18)):
            k = (t - d0) / 8
            sx = 1 if nb[0] == "R" else -1
            targets[nb] += Vector((sx * 2.5 * P * smooth(0, 1, k), 0, 4 * P * math.sin(math.pi * k) if 0 <= k <= 1 else 0))
    elif clip == "hide":
        sink = smooth(0, 14, t) * (1 - smooth(46, 60, t))   # settles on planted legs first...
        fold = smooth(10, 22, t) * (1 - smooth(38, 50, t))  # ...then tucks the legs (unfolds before rising)
        dz = -12 * P * sink
        mand = -12 * sink
        for n in PODS:
            pods[n] = (1 - 0.3 * sink, 1.0)
    elif clip == "bash":
        w = smooth(0, 12, t) * (1 - smooth(12, 16, t))
        k = smooth(12, 16, t) * (1 - smooth(20, 40, t))
        pitch, fwd, dz, mand = -10 * w + 12 * k, -2 * P * w + 5 * P * k, 1.5 * P * w - 0.5 * P * k, 35 * w - 10 * k
        step = smooth(11, 16, t) * (1 - smooth(24, 32, t))
        arc = math.sin(math.pi * smooth(11, 16, t)) + math.sin(math.pi * smooth(24, 32, t))
        for side in "LR":
            targets[(side, 0)] += Vector((0, 3 * P * w - 5 * P * k, 9 * P * w))   # rear up, slam down ahead
            targets[(side, 1)] += Vector((0, -3 * P * step, 3 * P * arc))        # steps with the lunge
            late = smooth(14, 19, t) * (1 - smooth(27, 35, t))
            late_arc = math.sin(math.pi * smooth(14, 19, t)) + math.sin(math.pi * smooth(27, 35, t))
            targets[(side, 3)] += Vector((0, -4 * P * late, 3 * P * late_arc))   # hind legs follow
    elif clip == "spores":
        dz = 1.2 * P * math.sin(2 * math.pi * t / 10) - 1.5 * P * smooth(40, 44, t) * (1 - smooth(56, 60, t))
        pitch = 1.5 * math.sin(2 * math.pi * t / 10 + 1)
        dz += pump_pods(pods, t, (46, 54), stop=(40, 44))
    elif clip == "limp":
        # same wave over the legs that are left, slower; the body sags and lists to the maimed side
        root = Vector((0, -SPEED * 0.55 * t, 0))
        lifted = {"L": 0.0, "R": 0.0}
        for side, s, i in SLOTS:
            if (side, i) in MISSING[clip]:
                continue
            ph = (t / LIMP_T + OFFSET[(side, i)]) % 1.0
            dy, dzf = step_offset(ph)
            targets[(side, i)] = root + HOME[(side, i)] + Vector((0, dy * 0.7, dzf))
            lifted[side] += dzf / STEP_H
        roll = 6 + 4 * lifted["L"] - 2 * lifted["R"] + 1.5 * math.sin(2 * math.pi * t / LIMP_T * 2)
        dz = -2 * P - 0.8 * P * (lifted["L"] + lifted["R"])
        pitch = 2 + 1.2 * math.sin(2 * math.pi * t / (LIMP_T / 2))
    elif clip == "collapsed":
        # can't walk: lies on its side, the remaining legs paw at the air, roars and puffs spores
        roar = sum(smooth(r, r + 4, t) * (1 - smooth(r + 10, r + 16, t)) for r in (14, 62))
        roll = COLLAPSE_ROLL + 2.5 * roar * math.sin(t * 2.9) + 1.0 * math.sin(t * 0.21)
        pitch = -12 * roar + 1.0 * math.sin(t * 0.17)
        mand = 42 * roar + 6 * math.sin(t * 0.4) ** 2
        for side, s, i in SLOTS:
            if (side, i) in MISSING[clip]:
                continue
            y, yaw, lift, knee, ankle = STANCE[i]
            k = i * 1.3 + (0 if side == "L" else 2.1)
            weak = 1 - 0.5 * roar
            fk[(side, i)] = (yaw + 14 * weak * math.sin(t * 0.33 + k), lift - 15 + 10 * weak * math.sin(t * 0.27 + k * 1.7),
                             knee + 18 * weak * math.sin(t * 0.41 + k * 0.6), ankle + 20 * weak * math.sin(t * 0.37 + k), 20 + 15 * math.sin(t * 0.3 + k))
        pump_pods(pods, t, (36, 44, 86, 94))
        ground = True
    elif clip == "hide_spores":
        # tucked in like a rock, it keeps pumping its pods and puffs spores, then stands up
        sink = smooth(0, 14, t) * (1 - smooth(66, 80, t))
        fold = smooth(10, 22, t) * (1 - smooth(58, 70, t))
        dz = -12 * P * sink + 0.5 * P * math.sin(2 * math.pi * t / 10) * sink
        mand = -12 * sink
        pump_pods(pods, t, (34, 42, 50), stop=(56, 60), start=(18, 26))
    B = Matrix.Translation(root + Vector((0, -fwd, dz))) @ rot_about(BODY_PIVOT, 'X', pitch) @ rot_about(BODY_PIVOT, 'Y', roll)
    if ground:   # rest the hull on the ground instead of the legs
        low = min((B @ v.co).z for n in HULL for v in bpy.data.objects[n].data.vertices)
        B = Matrix.Translation((0, 0, -low)) @ B
    return B, mand, targets, pods, fly, fold, fk, MISSING.get(clip, set())


def pump_pods(pods, t, bursts, stop=None, start=None):
    """Pods pump in a wave from the top down, then jump on each burst. Returns the body kick."""
    on = 1.0 if start is None else smooth(*start, t)
    off = 1.0 if stop is None else 1 - smooth(*stop, t)
    for n in PODS:
        pulse = max(0.0, math.sin(2 * math.pi * t / 10 - POD_PHASE[n])) * on * off
        burst = sum(0.9 * math.exp(-((t - tb) / 1.8) ** 2) - 0.35 * math.exp(-((t - tb + 3) / 1.5) ** 2) for tb in bursts)
        up = 1 + 0.55 * pulse + burst
        pods[n] = (up, 1 - 0.15 * (up - 1))
    return sum(1.0 * P * math.exp(-((t - tb) / 1.8) ** 2) for tb in bursts)


detach_m0 = {}


def apply(clip, t):
    B, mand, targets, pods, fly, fold, fk, missing = frame_pose(clip, t)
    Binv = B.inverted()
    mats = {n: B for n in STATIC}
    for n in PODS:
        up, sd = pods[n]
        b = POD_BASE[n]
        mats[n] = B @ Matrix.Translation(b) @ Matrix.Diagonal((sd, sd, up, 1.0)) @ Matrix.Translation(-b)
    for side, s in (("L", -1), ("R", 1)):
        m = B @ rot_about((s * 8 * P, -19 * P, 8.5 * P), 'Z', s * mand)
        mats["Mandible" + side] = m
        mats["Mandible%sHook" % side] = m
    for side, s, i in SLOTS:
        y, yaw, lift, _k, _a = STANCE[i]
        if (side, i) in missing:
            # torn off at the knee: the thigh stump jerks now and then, the rest is gone
            jerk = math.sin(t * 0.9 + i) ** 8
            up = chain(s, y * P, yaw + 10 * jerk, lift - 30 + 12 * jerk, 0, 0)["Upper"]
            mats[f"{side}{i}Upper"] = B @ up
            gone = B @ up @ Matrix.Translation((s * J["knee"], 0, HIP_Z)) @ Matrix.Scale(1e-4, 4)
            for p in LEG_PARTS[1:]:
                mats[f"{side}{i}{p}"] = gone
            continue
        claw = 20.0
        if (side, i) in fk:
            yaw, lift, knee, ankle, claw = fk[(side, i)]
            yaw = max(yaw, YAW_MIN[i])
            ch = chain(s, y * P, yaw, lift, knee, ankle, claw)
            for p in LEG_PARTS:
                mats[f"{side}{i}{p}"] = B @ ch[p]
            continue
        yaw, knee, ankle = ik(s, y * P, lift, Binv @ targets[(side, i)])
        yaw = max(yaw, YAW_MIN[i])
        if fold > 0:
            fl, fkn, fa, fc = FOLD
            lift += (fl - lift) * fold
            knee += (fkn - knee) * fold
            ankle += (fa - ankle) * fold
            claw += (fc - claw) * fold
        if clip == "detach" and (side, i) == DETACH and fly is not None:
            knee = 30 + 25 * math.exp(-fly / 8) * math.sin(fly * 1.9)    # the stump twitches
        ch = chain(s, y * P, yaw, lift, knee, ankle, claw)
        for p in LEG_PARTS:
            mats[f"{side}{i}{p}"] = B @ ch[p]
    if fly is not None:
        side, i = DETACH
        if not detach_m0:
            for p in LEG_PARTS[1:]:
                detach_m0[p] = mats[f"{side}{i}{p}"].copy()
        tau = min(fly, 16)
        off = Vector((0.09 * tau, -0.02 * tau, 0.14 * tau - 0.012 * tau * tau))
        c = detach_m0["Blade"] @ Vector((36 * P, 0, HIP_Z))
        spin = rot_about(c, 'Y', 22 * tau) @ rot_about(c, 'X', 9 * tau)
        for p in LEG_PARTS[1:]:
            mats[f"{side}{i}{p}"] = Matrix.Translation(off) @ spin @ detach_m0[p]
    return mats, targets


def tree(src, m):
    me = bpy.data.objects[src].data
    return BVHTree.FromPolygons([m @ v.co for v in me.vertices], [p.vertices[:] for p in me.polygons])


bad, miss, slide, last = [], {}, {}, {}
for clip, f0, f1 in CLIPS:
    detach_m0.clear()
    last.clear()
    for f in range(f0, f1 + 1):
        mats, targets = apply(clip, f - f0)
        for key, m in mats.items():
            o = objs[key]
            o.matrix_world = m
            o.keyframe_insert("location", frame=f)
            o.keyframe_insert("rotation_quaternion", frame=f)
            o.keyframe_insert("scale", frame=f)
        plates = [tree(n, mats[n]) for n in ("PlateHead", "PlateBackR", "PlateBackL", "PlateRear")]
        for side, s, i in SLOTS:
            if (side, i) in MISSING.get(clip, ()):
                if any(tree("Leg%sUpper" % side, mats[f"{side}{i}Upper"]).overlap(pl) for pl in plates):
                    bad.append((clip, f, side, i, "stump"))
                continue
            if clip == "detach" and (side, i) == DETACH and f - f0 >= 10:
                continue
            tip = mats[f"{side}{i}Claw"] @ Vector((s * 50 * P, 0, HIP_Z))
            if clip not in ("hide", "hide_spores", "collapsed"):
                err = (tip - targets[(side, i)]).length * 16
                if err > miss.get(clip, (0,))[0]:
                    miss[clip] = (round(err, 2), side + str(i), f, round(targets[(side, i)].z * 16, 1))
            k = (side, i)
            if k in last and -0.05 < tip.z < 0.03 and last[k].z < 0.03:
                slide[clip] = max(slide.get(clip, 0), (tip.xy - last[k].xy).length * 16)
            last[k] = tip
            if any(tree("Leg%s%s" % (side, p), mats[f"{side}{i}{p}"]).overlap(pl)
                   for p in ("Upper", "Shin", "Blade") for pl in plates):
                bad.append((clip, f, side, i))
for o in objs.values():
    for fc in o.animation_data.action.fcurves:
        for k in fc.keyframe_points:
            k.interpolation = "LINEAR"
scene = bpy.context.scene
scene.timeline_markers.clear()
for clip, f0, f1 in CLIPS:
    scene.timeline_markers.new(clip, frame=f0)
scene.frame_start, scene.frame_end = 0, CLIPS[-1][2]
scene.render.fps = 20
scene.frame_set(0)
bpy.ops.wm.save_mainfile()
print("leg/plate overlaps:", len(bad), sorted({b[0] for b in bad}), bad[:6])
print("claw misses its target (1/16, leg, frame, target height):", miss)
print("claw slides on the ground (1/16 per tick):", {k: round(v, 2) for k, v in slide.items()})
