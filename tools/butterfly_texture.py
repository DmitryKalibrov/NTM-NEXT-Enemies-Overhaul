# Pixel texture for the flying glyphid (16 px/block, NTM glyphid look), run in Blender after butterfly_model.py:
#   exec(open(r"E:\ModPorting\ntm-next-enemies-overhaul\tools\butterfly_texture.py", encoding="utf-8").read())
# Packs every face into its own rect, paints it by role, writes glyphid_butterfly.png and the OBJ.
# Re-run tools/butterfly_anim.py afterwards: the skin weights live on the (re-packed) mesh vertices.
import bpy, bmesh, random, math
from mathutils import Vector
import numpy as np

ROOT = r"E:\ModPorting\ntm-next-enemies-overhaul"
ASSETS = ROOT + r"\common\src\main\resources\assets\ntm_enemies_overhaul"
NAME = "glyphid_tatterwing"
PX = 16; W = 256
col = bpy.data.collections["Butterfly"]
col.hide_viewport = False
objs = sorted(col.objects, key=lambda o: o.name)
rng = random.Random(4242)

def hexc(h, a=255): return np.array([int(h[i:i+2], 16) for i in (1, 3, 5)] + [a], dtype=float)
C = {k: hexc(v) for k, v in dict(
    g0="#36323a", g1="#4a4552", g2="#5e5868", g3="#6e6878", g4="#827a8c", crack="#b8ae9c",
    f0="#4a2c3c", f1="#5e384a", f2="#764a5e", f3="#8c5c70", f4="#a47488",
    b0="#c4b69c", b1="#ded2ba", b2="#9e927c", e0="#2a0a0c", e1="#5a1418", e2="#c86060").items()}
# leather of the wings: dark reddish-brown, a little see-through, capillaries and blotches more opaque
L = dict(l0=hexc("#6e4034", 205), l1=hexc("#7c4a3c", 196), l2=hexc("#8a5646", 188), l3=hexc("#5e362e", 214),
         vein=hexc("#4a1a20", 240), vein2=hexc("#6a2228", 235), edge=hexc("#3e2420", 245), blot=hexc("#54302a", 225))
STYLE = {"plate": (["g3", "g2", "g3", "g4", "g3"], "g1"), "plate_low": (["g1", "g2", "g1"], "g0"),
         "chitin": (["g2", "g3", "g2"], "g0"), "chitin_low": (["g1", "g2"], "g0"),
         "flesh": (["f2", "f3", "f2", "f3"], "f1"), "flesh_dark": (["f1", "f0", "f1"], "f0"),
         "bone": (["b1", "b0", "b1"], "b2"), "eye": (["e0", "e1", "e0"], "e0")}
KNEE = {o.name[:9]: Vector(o["knee"]) for o in objs if o.name.endswith("Arm") and o.name.startswith("Wing")}

def role(o, f):
    n, name = f.normal, o.name
    if "Skin" in name: return "leather"
    if name.endswith(("Plate", "Scapula")) or name == "HeadPlate": return "plate" if n.z > -0.2 else "plate_low"
    if name.startswith("Eye"): return "eye"
    if name.startswith("Wing") or name.startswith(("Stinger", "Mandible")) or name.endswith("Claw"): return "bone"
    if name.startswith("Leg") and name.endswith(("Coxa", "Knee", "Ankle")): return "flesh_dark"
    if name.startswith(("Leg", "Antenna")): return "chitin" if n.z > -0.3 else "chitin_low"
    return "flesh" if n.z > -0.3 else "flesh_dark"

# merge coplanar hull triangles so each flat face gets one clean rect
for o in objs:
    bm = bmesh.new(); bm.from_mesh(o.data)
    bmesh.ops.dissolve_limit(bm, angle_limit=math.radians(2), verts=bm.verts, edges=bm.edges)
    bm.to_mesh(o.data); bm.free()

def frame(f, knee=None):
    vs = [v.co.copy() for v in f.verts]
    if knee is not None:
        r = f.calc_center_median() - knee; r -= f.normal * r.dot(f.normal)
        ax = f.normal.cross(r.normalized())          # texture columns (capillaries) run out from the wing's knee
    else:
        ax = max(((vs[(i+1) % len(vs)] - vs[i]) for i in range(len(vs))), key=lambda e: e.length).normalized()
    return vs, (ax, f.normal.cross(ax))

knee_of = lambda o: KNEE.get(o.name[:9]) if "Skin" in o.name else None
rects, bms = [], {}
for o in objs:
    bm = bmesh.new(); bm.from_mesh(o.data); bm.faces.ensure_lookup_table(); bms[o.name] = bm
    for f in bm.faces:
        vs, axes = frame(f, knee_of(o))
        us = [(v - vs[0]).dot(axes[0]) for v in vs]; ws = [(v - vs[0]).dot(axes[1]) for v in vs]
        w = max(1, math.ceil((max(us) - min(us)) * PX)); h = max(1, math.ceil((max(ws) - min(ws)) * PX))
        rects.append(dict(o=o.name, fi=f.index, w=w, h=h, role=role(o, f)))
x = y = shelf = 0
for i in sorted(range(len(rects)), key=lambda i: -rects[i]["h"]):
    r = rects[i]
    if x + r["w"] > W: x, y, shelf = 0, y + shelf + 1, 0
    r["x"], r["y"] = x, y; x += r["w"] + 1; shelf = max(shelf, r["h"])
H = y + shelf
for o in objs:
    bm = bms[o.name]; uv = bm.loops.layers.uv.verify()
    for r in (r for r in rects if r["o"] == o.name):
        f = bm.faces[r["fi"]]; vs, axes = frame(f, knee_of(o))
        us = [(v - vs[0]).dot(axes[0]) for v in vs]; ws = [(v - vs[0]).dot(axes[1]) for v in vs]
        for loop, u, t in zip(f.loops, us, ws):
            loop[uv].uv = ((r["x"] + (u - min(us)) * PX) / W, (r["y"] + (t - min(ws)) * PX) / H)
    bm.to_mesh(o.data); bm.free()

img = np.zeros((H, W, 4))
for r in rects:
    x0, y0, w, h = r["x"], r["y"], r["w"], r["h"]
    if r["role"] == "leather":
        for py in range(y0, y0 + h):
            for px in range(x0, x0 + w):
                img[py, px] = L[rng.choice(("l0", "l1", "l1", "l2", "l3"))]
        for _ in range(w * h // 120):                                                  # blotches
            bx, by = x0 + rng.randrange(0, w), y0 + rng.randrange(0, h)
            img[by:min(by + 2, y0 + h), bx:min(bx + 2, x0 + w)] = L["blot"]
        # capillaries: wandering lines out from the knee (texture columns), branching now and then
        px = x0 + rng.randrange(1, 4)
        while px < x0 + w - 1:
            cx = px
            for py in range(y0, y0 + h):
                img[py, cx] = L["vein"]
                if rng.random() < 0.25: cx = min(x0 + w - 1, max(x0, cx + rng.choice((-1, 1))))
                if rng.random() < 0.06 and py + 3 < y0 + h:                            # a branch peels off
                    bx = cx
                    for k in range(1, rng.randrange(3, 7)):
                        bx = min(x0 + w - 1, max(x0, bx + rng.choice((-1, 1))))
                        if py + k < y0 + h: img[py + k, bx] = L["vein2"]
            px += rng.randrange(4, 8)
        if w > 2 and h > 2:
            img[y0, x0:x0 + w] = L["edge"]; img[y0 + h - 1, x0:x0 + w] = L["edge"]
        continue
    base, border = STYLE[r["role"]]
    for py in range(y0, y0 + h):
        for px in range(x0, x0 + w):
            edge = px in (x0, x0 + w - 1) or py in (y0, y0 + h - 1)
            img[py, px] = C[border] if edge and w > 2 and h > 2 else C[rng.choice(base)]
    if r["role"] == "plate" and w > 4 and h > 4:
        for _ in range(w * h // 60):                                                 # pitting
            img[y0 + rng.randrange(1, h - 1), x0 + rng.randrange(1, w - 1)] = C["g1"]
        for _ in range(max(1, w * h // 400)):                                        # cracks
            py = y0 + rng.randrange(1, h - 1); px0 = x0 + rng.randrange(1, max(2, w - 5))
            img[py, px0:min(px0 + rng.randrange(2, 6), x0 + w - 1)] = C["crack"]
    if r["role"] == "eye" and w > 2 and h > 2:
        img[y0 + 1, x0 + 1] = C["e2"]                                                  # glint
    if r["role"] in ("chitin", "chitin_low") and w > 3:
        for px in range(x0 + 2, x0 + w - 1, 5): img[y0:y0 + h, px] = C["g1"]         # segment seams
    if r["role"] == "bone" and h > 3 and w > 3:
        for _ in range(max(1, w * h // 50)):                                         # bony grain
            img[y0 + rng.randrange(1, h - 1), x0 + rng.randrange(1, w - 1)] = C["b2"]
    if r["role"] == "flesh" and h > 3:
        for py in range(y0 + 2, y0 + h - 1, 3): img[py, x0 + 1:x0 + w - 1] = C["f1"]  # sinews

png = NAME + ".png"
im = bpy.data.images.get(png)
if im: bpy.data.images.remove(im)
im = bpy.data.images.new(png, W, H, alpha=True)
im.pixels = (img / 255.0).ravel().tolist()
im.filepath_raw = ASSETS + r"\textures\entity\\" + png; im.file_format = "PNG"; im.save()

def tex_mat(name, blend):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    m.use_nodes = True; nt = m.node_tree
    bsdf = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
    tex = next((n for n in nt.nodes if n.type == "TEX_IMAGE"), None) or nt.nodes.new("ShaderNodeTexImage")
    tex.image = im; tex.interpolation = "Closest"
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    if blend:
        nt.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
        try: m.surface_render_method = "BLENDED"
        except Exception: m.blend_method = "BLEND"
    return m
opaque, leather = tex_mat("butterfly", False), tex_mat("butterfly_leather", True)
for o in objs:
    o.data.materials.clear(); o.data.materials.append(leather if "Skin" in o.name else opaque)

# NTM meshes take triangles and quads only: split the larger hull faces (UVs ride along)
for o in objs:
    bm = bmesh.new(); bm.from_mesh(o.data)
    bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 4])
    bm.to_mesh(o.data); bm.free()

# OBJ export (Blender -Y forward / Z up -> OBJ +Z forward / Y up, like the other glyphids)
for o in bpy.context.scene.objects: o.select_set(False)
for o in objs: o.select_set(True)
win = bpy.context.window_manager.windows[0]
area = next(a for a in win.screen.areas if a.type == "VIEW_3D")
with bpy.context.temp_override(window=win, area=area):
    bpy.ops.wm.obj_export(filepath=ASSETS + r"\models\obj\\" + NAME + ".obj", export_selected_objects=True,
                          export_materials=False, export_uv=True, export_normals=True)
if bpy.data.collections.get("Preview"): col.hide_viewport = True
bpy.ops.wm.save_mainfile()
print("texture", W, "x", H, "faces", len(rects))
