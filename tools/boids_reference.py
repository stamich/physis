"""Independent check of the Boids model's order-disorder behaviour (numpy, O(n^2) neighbours).

It implements the same rules as physis.models.boids.Boids with brute-force minimum-image
neighbours and its own random numbers, so it agrees with the Java model statistically, not bit for
bit. It is used to choose the thresholds of the Vicsek tests: alignment only, 500 boids in a
400 x 300 world, polarization after 600 steps for low and high wander noise.

Usage:  python tools/boids_reference.py
"""

import numpy as np


def polarization(vx, vy):
    return np.hypot(vx.sum(), vy.sum()) / np.hypot(vx, vy).sum()


def run(n=500, width=400.0, height=300.0, steps=600, seed=1, perception=40.0, w_align=1.0,
        w_sep=0.0, w_coh=0.0, sep_dist=14.0, max_speed=4.0, min_speed=1.5, max_force=0.15,
        wander=0.1, record=(0, 100, 200, 300, 400, 600)):
    rng = np.random.default_rng(seed)
    x = rng.random(n) * width
    y = rng.random(n) * height
    heading = rng.random(n) * 2 * np.pi
    speed = min_speed + (max_speed - min_speed) * rng.random(n)
    vx, vy = speed * np.cos(heading), speed * np.sin(heading)
    out = {}
    for step in range(steps + 1):
        if step in record:
            out[step] = polarization(vx, vy)
        if step == steps:
            break
        dx = x[None, :] - x[:, None]
        dy = y[None, :] - y[:, None]
        dx -= width * np.round(dx / width)
        dy -= height * np.round(dy / height)
        d2 = dx * dx + dy * dy
        near = (d2 <= perception ** 2) & ~np.eye(n, dtype=bool)
        m = near.sum(axis=1)
        has = m > 0
        inv = np.where(has, 1.0 / np.maximum(m, 1), 0.0)
        mean_vx = (near * vx[None, :]).sum(axis=1) * inv
        mean_vy = (near * vy[None, :]).sum(axis=1) * inv
        coh_x = (near * dx).sum(axis=1) * inv
        coh_y = (near * dy).sum(axis=1) * inv
        close = near & (d2 < sep_dist ** 2) & (d2 > 0)
        safe = np.where(d2 > 0, d2, 1.0)
        sep_x = -(close * dx / safe).sum(axis=1)
        sep_y = -(close * dy / safe).sum(axis=1)
        sx = w_sep * sep_x * sep_dist + w_align * (mean_vx - vx) / max_speed + w_coh * coh_x / perception
        sy = w_sep * sep_y * sep_dist + w_align * (mean_vy - vy) / max_speed + w_coh * coh_y / perception
        sx, sy = np.where(has, sx, 0.0), np.where(has, sy, 0.0)
        length = np.hypot(sx, sy)
        scale = np.where(length > 1.0, 1.0 / np.maximum(length, 1e-300), 1.0)
        nvx = vx + max_force * sx * scale
        nvy = vy + max_force * sy * scale
        if wander > 0:
            angle = (rng.random(n) - 0.5) * wander
            c, s = np.cos(angle), np.sin(angle)
            nvx, nvy = c * nvx - s * nvy, s * nvx + c * nvy
        sp = np.hypot(nvx, nvy)
        factor = np.where(sp > max_speed, max_speed / sp, np.where(sp < min_speed, min_speed / np.maximum(sp, 1e-300), 1.0))
        vx, vy = nvx * factor, nvy * factor
        x = (x + vx) % width
        y = (y + vy) % height
    return out


if __name__ == "__main__":
    for wander in (0.1, 1.0, 2.0, 2.5, 3.0):
        runs = [run(seed=s, wander=wander) for s in (1, 2, 3)]
        print(f"wander {wander}:", "; ".join(" ".join(f"{k}:{v:.2f}" for k, v in r.items()) for r in runs))
