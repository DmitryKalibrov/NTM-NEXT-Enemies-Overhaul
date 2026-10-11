"""Decompile NTM's .hbm_mesh back into a Wavefront OBJ (the inverse of obj2mesh.py).

usage: python mesh2obj.py model.hbm_mesh [more ...]   -> writes model.obj next to each
"""
import struct
import sys


def decompile(path):
    d = open(path, "rb").read()
    at = 0

    def i32():
        nonlocal at
        v = struct.unpack_from(">i", d, at)[0]
        at += 4
        return v

    def var():
        nonlocal at
        v = s = 0
        while True:
            b = d[at]
            at += 1
            v |= (b & 0x7F) << s
            s += 7
            if not b & 0x80:
                return (v >> 1) ^ -(v & 1)

    assert i32() == 1212304719 and i32() == 1
    tables = []
    for w in (3, 2, 3):
        n = i32()
        tables.append([struct.unpack_from(f">{w}f", d, at + i * w * 4) for i in range(n)])
        at += n * w * 4
    pos, uv, nrm = tables
    out = [f"v {x:.6f} {y:.6f} {z:.6f}" for x, y, z in pos]
    out += [f"vt {u:.6f} {1.0 - v:.6f}" for u, v in uv]
    out += [f"vn {x:.6f} {y:.6f} {z:.6f}" for x, y, z in nrm]
    for _ in range(i32()):
        nl = i32()
        out.append("o " + d[at:at + nl].decode())
        at += nl + 24
        p = u = n = fn = 0
        for _ in range(i32()):
            k = d[at]
            at += 1
            fn += var()
            corners = []
            for _ in range(k):
                p += var(); u += var(); n += var()
                corners.append(f"{p + 1}/{u + 1}/{n + 1}")
            # obj2mesh stores triangles with the third corner repeated
            if k == 4 and corners[2] == corners[3]:
                corners.pop()
            out.append("f " + " ".join(corners))
    assert at == len(d), "trailing data"
    dst = path[: -len(".hbm_mesh")] + ".obj"
    open(dst, "w", encoding="utf-8").write("\n".join(out) + "\n")
    return dst


if __name__ == "__main__":
    for src in sys.argv[1:]:
        print(decompile(src))
