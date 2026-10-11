# Pixel texture for the Needler (16 px/block, NTM glyphid look), run in Blender:
#   exec(open(r"E:\ModPorting\ntm-next-enemies-overhaul\tools\needler_texture.py", encoding="utf-8").read())
# Packs every face into its own rect, paints it by role, writes glyphid_needler.png and the OBJ.
import bpy, bmesh, random, math
from mathutils import Vector
import numpy as np

ROOT = r"E:\ModPorting\ntm-next-enemies-overhaul"
ASSETS = ROOT + r"\common\src\main\resources\assets\ntm_enemies_overhaul"
NAME = "glyphid_needler"
PX = 16; W = 256
col = bpy.data.collections["Needler"]
col.hide_viewport = False
objs = sorted(col.objects, key=lambda o: o.name)
rng = random.Random(1337)

def hexc(h, a=255): return np.array([int(h[i:i+2], 16) for i in (1, 3, 5)] + [a], dtype=float)
C = {k: hexc(v) for k, v in dict(
    g0="#3e3a40", g1="#55505a", g2="#6a6470", g3="#7b7480", g4="#8e8692", crack="#c4baa8",
    f0="#4e3242", f1="#644052", f2="#7c5468", f3="#94687c", f4="#ac8090",
    b0="#c8bca4", b1="#e4d8c2", b2="#a89c86",
    k0="#141214", k1="#242024",
    p0="#5a1414", p1="#8a2222", p2="#b03a30", hole="#0a0606").items()}
# translucent womb membrane: pale, see-through, veins denser
C.update(w0=hexc("#b8ac6a", 190), w1=hexc("#cdc282", 110), w2=hexc("#ddd49a", 95), w3=hexc("#e9e2b4", 125),
         vein=hexc("#a07a50", 230))
# translucent "glass" of the radar fan: orange, alpha varies, ribs and rim more opaque
G = dict(g0=hexc("#e8822a", 70), g1=hexc("#f09a3c", 85), g2=hexc("#f8b860", 60),
         rib=hexc("#c86418", 180), rim=hexc("#ffd890", 210))
STYLE = {"plate": (["g3", "g2", "g3", "g4", "g3"], "g1"), "plate_low": (["g1", "g2", "g1"], "g0"),
         "chitin": (["g2", "g3", "g2"], "g0"), "chitin_low": (["g1", "g2"], "g0"),
         "flesh": (["f2", "f3", "f2", "f3"], "f1"), "flesh_dark": (["f1", "f0", "f1"], "f0"),
         "bone": (["b1", "b0", "b1"], "b2"), "womb": (["w1", "w2", "w1", "w3"], "w0"),
         "stripe": (["k0", "k1"], "k0"), "port": (["p1", "p2", "p1"], "p0"), "hole": (["hole"], "p0")}

def role(o, f):
    n, name = f.normal, o.name
    if name.startswith("GillBase"): return "flesh" if n.z > -0.3 else "flesh_dark"
    if name.startswith("Gill"): return "glass"
    if name == "Carapace": return "plate" if n.y < -0.2 else "plate_low"
    if name.startswith("Port"):
        # the outer cap of the tube is the face that points away from the shield the most
        return "hole" if f.index == o["cap"] else "port"
    if name.startswith("Womb"): return "stripe" if name.endswith("s") else "womb"
    if name.startswith(("Mandible", "Tail", "Needle")) or name.endswith("Sting"): return "bone"
    if name.endswith(("Coxa", "Knee", "Ankle", "Shoulder", "Elbow", "Wrist")): return "flesh_dark"
    if name.startswith(("Leg", "Arm")): return "chitin" if n.z > -0.3 else "chitin_low"
    if name == "Head": return "flesh" if n.z > -0.4 else "flesh_dark"
    return "flesh" if n.z > -0.3 else "flesh_dark"

# merge coplanar hull triangles so each flat face gets one clean rect
for o in objs:
    bm = bmesh.new(); bm.from_mesh(o.data)
    bmesh.ops.dissolve_limit(bm, angle_limit=math.radians(2), verts=bm.verts, edges=bm.edges)
    bm.to_mesh(o.data); bm.free()
    if o.name.startswith("Port"):
        # cap = the hexagon facing out of the shield (shield normal leans 20 deg back from -Y)
        out = (0, -math.cos(math.radians(20)), math.sin(math.radians(20)))
        o["cap"] = max((p for p in o.data.polygons if len(p.vertices) == 6), key=lambda p: p.normal.dot(out)).index

SHIELD = Vector((0, -0.025, 1.84))   # shield centre: fan ribs run radially from it
def frame(f, radial=False):
    vs = [v.co.copy() for v in f.verts]
    if radial:
        r = f.calc_center_median() - SHIELD; r -= f.normal * r.dot(f.normal)
        ax = f.normal.cross(r.normalized())          # texture columns (ribs) then point along r
    else:
        ax = max(((vs[(i+1) % len(vs)] - vs[i]) for i in range(len(vs))), key=lambda e: e.length).normalized()
    return vs, (ax, f.normal.cross(ax))

rects, bms = [], {}
for o in objs:
    bm = bmesh.new(); bm.from_mesh(o.data); bm.faces.ensure_lookup_table(); bms[o.name] = bm
    for f in bm.faces:
        vs, axes = frame(f, o.name.startswith("Gill") and not o.name.startswith("GillBase"))
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
        f = bm.faces[r["fi"]]; vs, axes = frame(f, o.name.startswith("Gill") and not o.name.startswith("GillBase"))
        us = [(v - vs[0]).dot(axes[0]) for v in vs]; ws = [(v - vs[0]).dot(axes[1]) for v in vs]
        for loop, u, t in zip(f.loops, us, ws):
            loop[uv].uv = ((r["x"] + (u - min(us)) * PX) / W, (r["y"] + (t - min(ws)) * PX) / H)
    bm.to_mesh(o.data); bm.free()

img = np.zeros((H, W, 4))
for r in rects:
    x0, y0, w, h = r["x"], r["y"], r["w"], r["h"]
    if r["role"] == "glass":
        for py in range(y0, y0 + h):
            for px in range(x0, x0 + w):
                img[py, px] = G[rng.choice(("g0", "g1", "g1", "g2"))]
        for px in range(x0 + 3, x0 + w - 1, 6): img[y0:y0 + h, px] = G["rib"]      # radial ribs
        if w > 2 and h > 2:
            img[y0, x0:x0 + w] = G["rim"]; img[y0 + h - 1, x0:x0 + w] = G["rim"]
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
    if r["role"] == "hole" and w > 2 and h > 2:
        img[y0 + 1:y0 + h - 1, x0 + 1:x0 + w - 1] = C["hole"]
    if r["role"] in ("chitin", "chitin_low") and w > 3:
        for px in range(x0 + 2, x0 + w - 1, 5): img[y0:y0 + h, px] = C["g1"]         # segment seams
    if r["role"] == "flesh" and h > 3:
        for py in range(y0 + 2, y0 + h - 1, 3): img[py, x0 + 1:x0 + w - 1] = C["f1"]  # sinews
    if r["role"] == "womb" and w > 4 and h > 4:
        for _ in range(w * h // 90):                                                 # veins
            py = y0 + rng.randrange(1, h - 1); px0 = x0 + rng.randrange(1, w - 2)
            img[py, px0:min(px0 + rng.randrange(1, 4), x0 + w - 1)] = C["vein"]

png = NAME + ".png"
im = bpy.data.images.get(png)
if im: bpy.data.images.remove(im)
im = bpy.data.images.new(png, W, H, alpha=True)
im.pixels = (img / 255.0).ravel().tolist()
im.filepath_raw = ASSETS + r"\textures\entity\\" + png; im.file_format = "PNG"; im.save()

# preview materials: opaque + glass, both on the same image
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
opaque, glass = tex_mat("needler", False), tex_mat("needler_glass", True)
for o in objs:
    o.data.materials.clear(); o.data.materials.append(glass if o.name.startswith(("Gill", "Womb")) and not o.name.startswith("GillBase") else opaque)

# NTM meshes take triangles and quads only: split the larger hull faces (UVs ride along)
for o in objs:
    bm = bmesh.new(); bm.from_mesh(o.data)
    bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 4])
    bm.to_mesh(o.data); bm.free()

# OBJ export (Blender -Y forward / Z up -> OBJ +Z forward / Y up, like the Glybnik)
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
