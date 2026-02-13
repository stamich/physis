"""Independent reference for the Gray-Scott model of physis (numpy, double precision).

It repeats the Java model's formulas with the same order of floating-point operations, but shares
no code with it: periodic boundaries by np.roll, whole-array arithmetic, and its own FNV-1a digest.

Usage (numpy is the only dependency):
    python tools/gray_scott_reference.py fields > models/grayscott/src/test/resources/gray-scott-64x64-100.csv
    python tools/gray_scott_reference.py digest     # golden digest of 1 000 steps, 64 x 64
    python tools/gray_scott_reference.py regimes    # coverage and max v of the presets

Regenerating the golden data is a deliberate act: review the diff.
"""

import struct
import sys

import numpy as np

DU, DV, F, K = 1.0, 0.5, 0.0367, 0.0649
DT = 1.0


def laplacian9(a):
    """Isotropic 9-point Laplacian, unit spacing, periodic: operands added in the Java order."""
    left = np.roll(a, 1, axis=1)
    right = np.roll(a, -1, axis=1)
    up = np.roll(a, 1, axis=0)
    down = np.roll(a, -1, axis=0)
    edges = ((left + right) + up) + down
    corners = ((np.roll(up, 1, axis=1) + np.roll(up, -1, axis=1)) + np.roll(down, 1, axis=1)) + np.roll(down, -1, axis=1)
    return ((0.2 * edges + 0.05 * corners) - a) * 1.0


def initial(nx, ny):
    """u = 1, v = 0 with a centred square (noise 0), as in GrayScott.init."""
    u = np.ones((ny, nx))
    v = np.zeros((ny, nx))
    side = max(2, min(nx, ny) // 10)
    x0 = (nx - side) // 2
    y0 = (ny - side) // 2
    u[y0:y0 + side, x0:x0 + side] = 0.5
    v[y0:y0 + side, x0:x0 + side] = 0.25
    return u, v


def step(u, v, du=DU, dv=DV, f=F, k=K, dt=DT):
    lu = laplacian9(u)
    lv = laplacian9(v)
    uvv = (u * v) * v
    d_u = (du * lu - uvv) + f * (1.0 - u)
    d_v = (dv * lv + uvv) - (f + k) * v
    return u + dt * d_u, v + dt * d_v


def run(nx, ny, steps, **parameters):
    u, v = initial(nx, ny)
    for _ in range(steps):
        u, v = step(u, v, **parameters)
    return u, v


def digest(u, v):
    """FNV-1a over the little-endian bytes of every double, u then v, rows first (as Digest)."""
    h = 0xCBF29CE484222325
    for value in np.concatenate([u.ravel(), v.ravel()]):
        for byte in struct.pack("<d", float(value)):
            h = ((h ^ byte) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h - (1 << 64) if h >= (1 << 63) else h


def main() -> None:
    mode = sys.argv[1] if len(sys.argv) > 1 else "fields"
    if mode == "fields":
        u, v = run(64, 64, 100)
        print("j,i,u,v")
        for j in range(64):
            for i in range(64):
                print(f"{j},{i},{float(u[j, i])!r},{float(v[j, i])!r}")
    elif mode == "digest":
        u, v = run(64, 64, 1000)
        print(digest(u, v))
    elif mode == "regimes":
        for name, f, k in [("mitosis", 0.0367, 0.0649), ("coral", 0.0545, 0.0620), ("maze", 0.0290, 0.0570),
                           ("solitons", 0.0300, 0.0620), ("worms", 0.0540, 0.0629), ("extinct", 0.09, 0.06)]:
            u, v = run(128, 128, 8000, f=f, k=k)
            print(f"{name:9s} F={f} k={k}: coverage(v>0.2)={(v > 0.2).mean():.4f} "
                  f"v_max={v.max():.6f} v_mean={v.mean():.6f} u_min={u.min():.4f} u_max={u.max():.4f}")


if __name__ == "__main__":
    main()
