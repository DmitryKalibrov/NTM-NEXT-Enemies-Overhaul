"""Animates the flying glyphid in Blender: builds an armature (collection Preview) and keys every clip.

Run inside Blender after tools/butterfly_model.py: exec(open(r"...\\tools\\butterfly_anim.py", encoding="utf-8").read())
Rigid parts ride one bone each. The leather of the wings is skinned: every skin vertex follows its two nearest
wing bones (arm, finger halves, or the body where the web joins the flank), so the skin stretches and folds
with the fingers. One pose function drives every clip with the maths the in-game renderer will port.
Legs are placed by IK (femur/tibia two bones with the knee pole kept, the tarsus keeps its slope).
"""
import math

import bmesh
import bpy
from mathutils import Matrix, Vector
from mathutils.bvhtree import BVHTree

D = math.radians
col = bpy.data.collections["Butterfly"]
OBJ = {o.name: o for o in col.objects}
SIDES = (("L", -1), ("R", 1))
WINGS = [("WingFore" + side, s) for side, s in SIDES] + [("WingHind" + side, s) for side, s in SIDES]
BODY_PIVOT = Vector((0, -0.3, 1.4))
ABD_A, ABD_B = Vector((0, 0.0, 1.45)), Vector((0, 0.72, 1.07))    # abdomen joints on the arc
NECK = Vector((0, -0.9, 1.22))
# right-side leg joints (hip, knee, ankle, foot) as built
LEGS = [((0.14, -0.62, 1.18), (0.42, -0.85, 1.02), (0.36, -1.05, 0.62), (0.3, -1.2, 0.38)),
        ((0.16, -0.32, 1.12), (0.5, -0.35, 0.95), (0.46, -0.3, 0.5), (0.4, -0.22, 0.22)),
        ((0.15, -0.04, 1.08), (0.48, 0.15, 0.92), (0.42, 0.45, 0.52), (0.33, 0.68, 0.3))]
FOLD_TO = {"Fore": 94, "Hind": 102}     # finger angle (0 = straight out, 90 = straight back) when folded
CLIPS = [("hover", 0, 28), ("glide", 40, 80), ("dive", 90, 110), ("sting", 120, 146), ("climb", 156, 176),
         ("land", 186, 216), ("rest", 216, 256), ("takeoff", 266, 292), ("death", 302, 342)]


def smooth(a, b, t):
    x = min(1.0, max(0.0, (t - a) / (b - a)))
    return x * x * (3 - 2 * x)


def bump(a, b, t):
    return math.sin(math.pi * min(1.0, max(0.0, (t - a) / (b - a))))


def mix(a, b, w):
    return a + (b - a) * w


def rot_about(p, axis, deg):
    p = Vector(p)
    return Matrix.Translation(p) @ Matrix.Rotation(D(deg), 4, axis) @ Matrix.Translation(-p)


def basis(d, n):
    d = d.normalized(); n = (n - d * n.dot(d)).normalized()
    return Matrix((d, n, d.cross(n))).transposed()


def ik(H, K, A, F, target):
    """Body-frame foot target -> (femur, tibia, tarsus) matrices."""
    hv0, hv1 = (F - H).xy, (target - H).xy
    yaw = math.atan2(hv1.y, hv1.x) - math.atan2(hv0.y, hv0.x)
    Rz = rot_about(H, 'Z', math.degrees(yaw))
    tars = Rz.to_3x3() @ (F - A)
    A1 = target - tars
    L1, L2 = (K - H).length, (A - K).length
    v = A1 - H
    d = max(abs(L1 - L2) + 1e-4, min(v.length, L1 + L2 - 1e-4))
    dh = v.normalized()
    A1 = H + dh * d
    pole = Rz.to_3x3() @ (K - H)
    e = (pole - dh * pole.dot(dh)).normalized()
    a = (L1 * L1 - L2 * L2 + d * d) / (2 * d)
    K1 = H + dh * a + e * math.sqrt(max(0.0, L1 * L1 - a * a))
    n0, n1 = (K - H).cross(A - K), (K1 - H).cross(A1 - K1)
    femur = Matrix.Translation(H) @ (basis(K1 - H, n1) @ basis(K - H, n0).transposed()).to_4x4() @ Matrix.Translation(-H)
    tibia = Matrix.Translation(K1) @ (basis(A1 - K1, n1) @ basis(A - K, n0).transposed()).to_4x4() @ Matrix.Translation(-K)
    tarsus = Matrix.Translation(A1) @ Rz.to_3x3().to_4x4() @ Matrix.Translation(-A)
    return femur, tibia, tarsus


# ---------------------------------------------------------------- bones: pivot and the rigid parts riding them
BONES, PARTS = {}, {}


def bone(name, pivot, parts):
    BONES[name] = Vector(pivot)
    for p in parts:
        PARTS[p] = name


WING = {}
for w, s in WINGS:
    arm = OBJ[w + "Arm"]
    sh, kn, trail = Vector(arm["shoulder"]), Vector(arm["knee"]), Vector(arm["trail"])
    fingers = []
    k = 0
    while w + f"Finger{k}b" in OBJ:
        fb = OBJ[w + f"Finger{k}b"]
        fingers.append((Vector(fb["mid"]), Vector(fb["tip"])))
        k += 1
    WING[w] = dict(s=s, sh=sh, kn=kn, trail=trail, fingers=fingers)
    bone(w + "Arm", sh, [w + "Arm", w + "Knee", w + "Spur"])
    for k, (mid, tip) in enumerate(fingers):
        bone(w + f"F{k}a", kn, [w + f"Finger{k}a", w + f"Knuckle{k}"])
        bone(w + f"F{k}b", mid, [w + f"Finger{k}b"])
LEG = {}
for i, joints in enumerate(LEGS):
    for side, s in SIDES:
        n = f"Leg{side}{i}"
        h, k, a, f = [Vector((s * p[0], p[1], p[2])) for p in joints]
        LEG[n] = (h, k, a, f)
        bone(n + "F", h, [n + "Femur", n + "Knee"])
        bone(n + "T", k, [n + "Tibia", n + "Ankle"])
        bone(n + "S", a, [n + "Tarsus", n + "Claw"])
bone("abdB", ABD_B, [n for n in OBJ if n.startswith(("Abdomen3", "Abdomen4", "Abdomen5", "Stinger"))])
bone("abdA", ABD_A, [n for n in OBJ if n.startswith(("Abdomen0", "Abdomen1", "Abdomen2"))])
bone("head", NECK, [n for n in OBJ if n.startswith(("Head", "Eye", "Mandible", "Antenna"))])
SKINS = [n for n in OBJ if "Skin" in n]
bone("body", BODY_PIVOT, [n for n in OBJ if n not in PARTS and n not in SKINS])


def skin_weights(name):
    """Per vertex: the two nearest wing bones by distance to their segment, inverse-square weighted."""
    w = next(x for x in WING if name.startswith(x))
    g = WING[w]
    segs = [(w + "Arm", g["sh"], g["kn"]), ("body", g["sh"], g["trail"])]
    for k, (mid, tip) in enumerate(g["fingers"]):
        segs += [(w + f"F{k}a", g["kn"], mid), (w + f"F{k}b", mid, tip)]
    out = []
    for v in OBJ[name].data.vertices:
        ds = []
        for b, p0, p1 in segs:
            d = p1 - p0
            t = max(0.0, min(1.0, (v.co - p0).dot(d) / d.length_squared))
            ds.append((((p0 + d * t) - v.co).length, b))
        ds.sort()
        (d0, b0), (d1, b1) = ds[0], ds[1]
        w0, w1 = 1 / (d0 * d0 + 1e-4), 1 / (d1 * d1 + 1e-4)
        out.append(((b0, w0 / (w0 + w1)), (b1, w1 / (w0 + w1))))
    return out


# ---------------------------------------------------------------- pose


def wing_pose(mats, w, flap, sweep, fold, curl):
    """flap: degrees up; sweep: degrees back; fold 0..1 bunches the fingers back along the body; curl bends tips down."""
    g = WING[w]
    s, sh, kn = g["s"], g["sh"], g["kn"]
    R = rot_about(sh, 'Z', s * sweep) @ rot_about(sh, 'Y', -s * flap)
    mats[w + "Arm"] = R
    to = FOLD_TO["Fore" if "Fore" in w else "Hind"]
    for k, (mid, tip) in enumerate(g["fingers"]):
        ang = math.degrees(math.atan2(tip.y - kn.y, s * (tip.x - kn.x)))
        Fa = R @ rot_about(kn, 'Z', s * (to - ang) * fold * (0.8 + 0.2 * k / max(1, len(g["fingers"]) - 1)))
        d = tip - mid
        axis = Vector((0, 0, 1)).cross(d).normalized()
        mats[w + f"F{k}a"] = Fa
        mats[w + f"F{k}b"] = Fa @ Matrix.Translation(mid) @ Matrix.Rotation(D(curl * (0.7 + 0.3 * k / 3)), 4, axis) @ Matrix.Translation(-mid)


def flap_cycle(p, up=40.0, down=-35.0):
    """Fast downstroke (p 0..0.4), slow upstroke with the fingers half folded and the tips trailing."""
    if p < 0.4:
        flap = mix(up, down, smooth(0, 0.4, p))
    else:
        flap = mix(down, up, smooth(0.4, 1.0, p))
    return flap, 8 * math.sin(2 * math.pi * p), 0.3 * bump(0.4, 1.0, p), 25 * bump(0.4, 1.0, p)


def pose(clip, t):
    """Bone matrices in the armature (Blender body) space for a clip at tick t."""
    pitch = roll = yaw = dz = 0.0
    cA = cB = nod = 0.0
    wings = {}          # w -> (flap, sweep, fold, curl)
    feet = {}           # leg -> body-frame foot target
    ground = None       # leg -> ground-frame foot target (landed)
    drop = 0.0

    def all_wings(fore, hind=None):
        for w, s in WINGS:
            wings[w] = fore if "Fore" in w else (hind or fore)

    def dangle(sway=0.0, tuck=0.0):
        for n, (h, k, a, f) in LEG.items():
            i = int(n[4])
            feet[n] = f.lerp(Vector((f.x * 0.6, f.y + 0.25, f.z + 0.3)), tuck) + Vector((0, 0.04 * math.sin(sway + i), 0.03 * math.sin(sway * 1.3 + i)))

    if clip == "hover":
        p = t / 14
        all_wings(flap_cycle(p % 1), flap_cycle((p - 0.15) % 1, 30, -30))
        dz = 0.08 * math.sin(2 * math.pi * (p + 0.25))
        pitch = -12 + 3 * math.sin(2 * math.pi * p)
        cA, cB = -8 + 4 * math.sin(2 * math.pi * p * 0.5), -10 + 5 * math.sin(2 * math.pi * p * 0.5 + 1)
        dangle(t * 0.3)
    elif clip == "glide":
        # circling: wings held out, slow shallow strokes, banked into the turn
        f = 6 + 6 * math.sin(2 * math.pi * t / 20)
        all_wings((f, -4, 0.0, 6), (f * 0.8, 2, 0.0, 8))
        roll, yaw = 22 + 2 * math.sin(t * 0.2), 3 * math.sin(2 * math.pi * t / 40)
        pitch = -4
        cA, cB = -6, -12 + 3 * math.sin(t * 0.25)
        dangle(t * 0.15, 0.4)
    elif clip == "dive":
        # stoop: nose down, wings swept back and half folded, tail swung forward so the stinger leads
        k = smooth(0, 8, t)
        trem = 1.5 * math.sin(t * 2.3)
        all_wings((mix(20, 28, k) + trem, mix(0, 34, k), mix(0, 0.42, k), mix(0, 12, k)),
                  (mix(15, 22, k) - trem, mix(0, 40, k), mix(0, 0.5, k), mix(0, 15, k)))
        pitch = mix(-12, 62, k)
        cA, cB = mix(-8, -32, k), mix(-10, -48, k)
        nod = mix(0, -20, k)
        dangle(t * 0.3, k)
    elif clip == "sting":
        # impact: the tail rams the stinger in, it shakes stuck, rips it out, then beats hard to climb away
        ram = smooth(0, 3, t) * (1 - smooth(12, 17, t))
        out = smooth(12, 17, t)
        shake = 4 * math.sin(t * 3.1) * smooth(3, 4, t) * (1 - smooth(11, 13, t))
        beat = flap_cycle(max(0.0, t - 16) / 9 % 1, 55, -45) if t > 16 else (60 * out, 0, 0, 0)
        diving = (28, 34, 0.42, 12)
        wf = tuple(mix(d, b, out) for d, b in zip(diving, beat))
        all_wings(wf, tuple(x * 0.85 for x in wf))
        pitch = mix(mix(62, 40, smooth(0, 3, t)), -20, out) + shake
        cA, cB = mix(-32, -38, ram) * (1 - out) + -8 * out, mix(-48, -72, ram) * (1 - out) + -12 * out
        nod = -20 * (1 - out) + 8 * ram
        dz = -0.1 * ram
        # front legs clutch the victim while the stinger is in
        dangle(t * 0.5, 0.3 * (1 - out))
        for side, s in SIDES:
            h, k, a, f = LEG[f"Leg{side}0"]
            feet[f"Leg{side}0"] = feet[f"Leg{side}0"].lerp(Vector((s * 0.15, -1.25, 0.55)), ram)
    elif clip == "climb":
        p = t / 10
        all_wings(flap_cycle(p % 1, 55, -48), flap_cycle((p - 0.15) % 1, 45, -40))
        pitch = -28 + 4 * math.sin(2 * math.pi * p)
        dz = 0.1 * math.sin(2 * math.pi * (p + 0.25))
        cA, cB = -4, -6
        dangle(t * 0.4, 0.5)
    elif clip in ("land", "rest", "takeoff"):
        # folded at rest: the wings stand up over the back and close like a leaf, the legs stand on the ground
        if clip == "land":
            k = smooth(8, 24, t)
            stand = smooth(0, 10, t)
            beat = flap_cycle((t / 12) % 1, 30, -20) if t < 10 else (30, 0, 0, 0)
        elif clip == "rest":
            k, stand, beat = 1.0, 1.0, (30, 0, 0, 0)
        else:
            k = 1 - smooth(0, 10, t)
            stand = 1 - smooth(12, 20, t)
            beat = flap_cycle(max(0.0, t - 9) / 9 % 1, 55, -45) if t > 9 else (30, 0, 0, 0)
        folded = (36, 14, 0.9, -12)    # the arm already rises 40 degrees: +42 stands it upright on its own side
        breath = 1.0 * math.sin(t * 0.3) if clip == "rest" else 0.0
        wf = tuple(mix(b, f, k) for b, f in zip(beat, folded))
        all_wings((wf[0] + breath, wf[1], wf[2], wf[3]), (wf[0] - 6 + breath, wf[1] + 8, wf[2], wf[3]))
        pitch = mix(-12, 2, stand) + (-15 * bump(10, 26, t) if clip == "takeoff" else 0)
        cA, cB = mix(-8, -4, k) + 1.5 * math.sin(t * 0.21), mix(-10, -6, k)
        dangle(t * 0.3)
        ground = {n: Vector((f.x * 1.8, f.y, 0.0)) for n, (h, kk, a, f) in LEG.items()}
        ground = (ground, stand)
    elif clip == "death":
        # the wings crumple, it drops onto its side and curls its legs
        fall = smooth(4, 18, t)
        crumple = smooth(0, 14, t)
        twitch = math.exp(-max(0.0, t - 18) / 10) * math.sin(t * 1.9)
        for w, s in WINGS:
            ri = {"WingForeL": 0.0, "WingForeR": 0.15, "WingHindL": 0.3, "WingHindR": 0.1}[w]   # each wing crumples its own way
            low = -55 if "Hind" in w else 5          # hind wings sprawl under the fore wings
            wings[w] = (mix(30, low + 20 * ri, fall) + 5 * twitch, mix(0, 20 + 25 * ri, crumple), mix(0, 0.7 - ri, crumple), mix(0, 30, crumple))
        roll, pitch = 65 * smooth(10, 26, t) + 3 * twitch, mix(-12, 10, fall)
        cA, cB = -10 - 25 * crumple, -15 - 35 * crumple
        nod = -15 * fall
        drop = fall
        dangle(t * 0.5, crumple)

    B = Matrix.Translation((0, 0, dz)) @ rot_about(BODY_PIVOT, 'Z', yaw) @ rot_about(BODY_PIVOT, 'X', pitch) \
        @ rot_about(BODY_PIVOT, 'Y', roll)
    if drop > 0:
        low = min((B @ v.co).z for n in ("Thorax0", "Thorax1", "Thorax2", "HeadPlate") for v in OBJ[n].data.vertices)
        B = Matrix.Translation((0, 0, -low * drop)) @ B
    mats = {"body": Matrix.Identity(4)}
    mats["abdA"] = rot_about(ABD_A, 'X', cA)
    mats["abdB"] = mats["abdA"] @ rot_about(ABD_B, 'X', cB)
    mats["head"] = rot_about(NECK, 'X', nod)
    for w, (flap, sweep, fold, curl) in wings.items():
        wing_pose(mats, w, flap, sweep, fold, curl)
    Binv = B.inverted()
    for n, (h, k, a, f) in LEG.items():
        tgt = feet[n]
        if isinstance(ground, tuple):
            g, wgt = ground
            tgt = tgt.lerp(Binv @ g[n], wgt)
        fe, ti, ta = ik(h, k, a, f, tgt)
        mats[n + "F"], mats[n + "T"], mats[n + "S"] = fe, ti, ta
    return {b: B @ m for b, m in mats.items()}


# ---------------------------------------------------------------- armature + preview objects
prev = bpy.data.collections.get("Preview") or bpy.data.collections.new("Preview")
if prev.name not in bpy.context.scene.collection.children:
    bpy.context.scene.collection.children.link(prev)
for o in list(prev.objects):
    bpy.data.objects.remove(o)
for a in list(bpy.data.armatures):
    bpy.data.armatures.remove(a)
for a in list(bpy.data.actions):
    bpy.data.actions.remove(a)
arm_data = bpy.data.armatures.new("Rig")
rig = bpy.data.objects.new("Rig", arm_data)
prev.objects.link(rig)
win = bpy.context.window_manager.windows[0]
area = next(a for a in win.screen.areas if a.type == "VIEW_3D")
with bpy.context.temp_override(window=win, area=area, active_object=rig, object=rig, selected_objects=[rig]):
    bpy.context.view_layer.objects.active = rig
    bpy.ops.object.mode_set(mode="EDIT")
    for b, p in BONES.items():
        eb = arm_data.edit_bones.new(b)
        eb.head, eb.tail = p, p + Vector((0, 0, 0.12))
    bpy.ops.object.mode_set(mode="OBJECT")
REST = {b: rig.data.bones[b].matrix_local.copy() for b in BONES}
for pb in rig.pose.bones:
    pb.rotation_mode = "QUATERNION"

for n, src in OBJ.items():
    o = bpy.data.objects.new("pv_" + n, src.data)
    prev.objects.link(o)
    o.vertex_groups.clear()
    if n in SKINS:
        for vi, ((b0, w0), (b1, w1)) in enumerate(skin_weights(n)):
            for b, wt in ((b0, w0), (b1, w1)):
                g = o.vertex_groups.get(b) or o.vertex_groups.new(name=b)
                g.add([vi], wt, "REPLACE")
    else:
        g = o.vertex_groups.new(name=PARTS[n])
        g.add(list(range(len(src.data.vertices))), 1.0, "REPLACE")
    mod = o.modifiers.new("Rig", "ARMATURE")
    mod.object = rig
    o.parent = rig


def tree_eval(o, dg):
    ev = o.evaluated_get(dg)
    me = ev.to_mesh()
    t = BVHTree.FromPolygons([ev.matrix_world @ v.co for v in me.vertices], [p.vertices[:] for p in me.polygons])
    ev.to_mesh_clear()
    return t


SOLID = [n for n in OBJ if PARTS.get(n) in ("body", "abdA", "abdB", "head")]
bad = {}
scene = bpy.context.scene
for clip, f0, f1 in CLIPS:
    for f in range(f0, f1 + 1):
        mats = pose(clip, f - f0)
        for b, m in mats.items():
            pb = rig.pose.bones[b]
            pb.matrix_basis = REST[b].inverted() @ m @ REST[b]
            pb.keyframe_insert("location", frame=f)
            pb.keyframe_insert("rotation_quaternion", frame=f)
        if (f - f0) % 3:
            continue
        # check: wing bones and skins against the body, legs against the body; a joint may touch what it grows from
        scene.frame_set(f)
        dg = bpy.context.evaluated_depsgraph_get()
        T = {n: tree_eval(bpy.data.objects["pv_" + n], dg) for n in OBJ}
        for n in OBJ:
            if n in SOLID or n.endswith(("Coxa", "Scapula")):
                continue
            for o in SOLID:
                if "Skin" in n and ("Trail" in n or "Front" in n) and o.startswith(("Thorax", "Abdomen0")):
                    continue
                if n.endswith(("Arm", "Femur")) and (o.startswith("Thorax") or o == n[:5] + "Coxa"):
                    continue
                if o.endswith("Scapula") and n.startswith(o[:-7]) and (n.endswith("Arm") or "Trail" in n or "Front" in n):
                    continue
                if T[n].overlap(T[o]):
                    bad.setdefault(clip, set()).add((n, o))
        for (wa, sa), (wb, sb) in ((WINGS[0], WINGS[2]), (WINGS[1], WINGS[3]), (WINGS[0], WINGS[1])):
            for a in [n for n in SKINS if n.startswith(wa)]:
                for b in [n for n in SKINS if n.startswith(wb)]:
                    if T[a].overlap(T[b]):
                        bad.setdefault(clip, set()).add((a, b))
for fc in rig.animation_data.action.fcurves:
    for kp in fc.keyframe_points:
        kp.interpolation = "LINEAR"
scene.timeline_markers.clear()
for clip, f0, f1 in CLIPS:
    scene.timeline_markers.new(clip, frame=f0)
scene.frame_start, scene.frame_end = 0, CLIPS[-1][2]
scene.render.fps = 20
col.hide_viewport = True
prev.hide_viewport = False
scene.frame_set(0)
bpy.ops.wm.save_mainfile()
print("overlaps:", {c: len(v) for c, v in bad.items()}, {c: sorted(v)[:12] for c, v in bad.items()})

# ---------------------------------------------------------------- export for the game
import struct

BONE_LIST = list(BONES)
GROUPS = sorted(n for n in OBJ if n not in SKINS)
fmtv = lambda v: "{%s}" % ", ".join("%.5f" % c for c in v)
wing_rows = []
for w, s in WINGS:
    g = WING[w]
    wing_rows.append('new Wing("%s", %d, new double[]%s, new double[]%s, new double[]%s, new double[][]{%s}, new double[][]{%s})' % (
        w, s, fmtv(g["sh"]), fmtv(g["kn"]), fmtv(g["trail"]), ", ".join(fmtv(m) for m, t in g["fingers"]),
        ", ".join(fmtv(t) for m, t in g["fingers"])))
JAVA = """package com.hbm.enemies.glyphid;

/** Generated by tools/butterfly_anim.py from blender/glyphid_butterfly.blend, do not edit. Blender space. */
public final class TatterwingRig {

    /** Bones and the point each turns about. */
    public static final String[] BONES = {%s};
    public static final double[][] PIVOT = {%s};
    /** Rigid mesh groups and the bone each rides (skins come from the skin file instead). */
    public static final String[] GROUPS = {%s};
    public static final int[] GROUP_BONE = {%s};

    public record Wing(String name, int side, double[] shoulder, double[] knee, double[] trail, double[][] mids, double[][] tips) {}

    public static final Wing[] WINGS = {%s};
    /** Right-side leg joints (hip, knee, ankle, foot) as built, front to back. */
    public static final double[][][] LEGS = {%s};
    /** Vertices of the thorax and head plate: a dead body rests on the lowest of them. */
    public static final double[][] BELLY = {%s};

    private TatterwingRig() {}
}
""" % (", ".join('"%s"' % b for b in BONE_LIST), ", ".join(fmtv(BONES[b]) for b in BONE_LIST),
       ", ".join('"%s"' % g for g in GROUPS), ", ".join(str(BONE_LIST.index(PARTS[g])) for g in GROUPS),
       ",\n            ".join(wing_rows),
       ", ".join("{%s}" % ", ".join(fmtv(j) for j in leg) for leg in LEGS),
       ", ".join(fmtv(v.co) for n in ("Thorax0", "Thorax1", "Thorax2", "HeadPlate") for v in OBJ[n].data.vertices))
open(r"E:\ModPorting\ntm-next-enemies-overhaul\common\src\main\java\com\hbm\enemies\glyphid\TatterwingRig.java", "w",
     encoding="utf-8").write(JAVA)
# skin file: vertices (Blender space, two bones and the first weight) and triangle corners (vertex, u, v flipped like obj2mesh)
verts, corners = [], []
for n in SKINS:
    me = OBJ[n].data
    base = len(verts)
    for v, ((b0, w0), (b1, w1)) in zip(me.vertices, skin_weights(n)):
        verts.append((v.co.x, v.co.y, v.co.z, BONE_LIST.index(b0), BONE_LIST.index(b1), w0))
    uv = me.uv_layers.active.data
    for p in me.polygons:
        loops = list(p.loop_indices)
        for i in range(1, len(loops) - 1):
            for li in (loops[0], loops[i], loops[i + 1]):
                corners.append((base + me.loops[li].vertex_index, uv[li].uv.x, 1.0 - uv[li].uv.y))
out = bytearray(struct.pack(">i", len(verts)))
for x, y, z, b0, b1, w0 in verts:
    out += struct.pack(">fffBBf", x, y, z, b0, b1, w0)
out += struct.pack(">i", len(corners) // 3)
for vi, u, v in corners:
    out += struct.pack(">iff", vi, u, v)
import os
skin_path = r"E:\ModPorting\ntm-next-enemies-overhaul\common\src\main\resources\assets\ntm_enemies_overhaul\models\rig"
os.makedirs(skin_path, exist_ok=True)
open(skin_path + r"\glyphid_tatterwing_skin.bin", "wb").write(out)
print("exported", len(BONE_LIST), "bones", len(GROUPS), "groups", len(verts), "skin verts", len(corners) // 3, "tris")
# reference poses for tools/check_tatterwing.sh: one line per bone, "clip t bone" + the 16 matrix cells row by row
REF_CASES = [("hover", 5), ("glide", 13), ("dive", 4), ("dive", 15), ("sting", 2), ("sting", 9), ("sting", 20), ("climb", 7),
             ("land", 12), ("rest", 17), ("takeoff", 14), ("death", 25)]
with open(r"E:\ModPorting\ntm-next-enemies-overhaul\tools\tatterwing_pose_ref.txt", "w") as ref:
    for c, t in REF_CASES:
        for b, m in pose(c, t).items():
            ref.write("%s %d %s %s\n" % (c, t, b, " ".join("%.6f" % x for r in m for x in r)))
