"""Independent check of the Physarum model's network formation (numpy).

It implements the same rules as physis.models.physarum.Physarum (sensing the trail as it stands,
turning, moving, depositing, then a 3 x 3 mean and fading) with its own random numbers, so it
agrees with the Java model statistically, not bit for bit. It is used to choose the thresholds
of the structure tests: from a uniform start the cells above twice the mean trail fall from many
small pieces to a few connected structures.

Usage:  python tools/physarum_reference.py
"""

import numpy as np
from scipy import ndimage


def structures(trail):
    """(coverage, number of 4-connected pieces, wrapping at the border) of cells above 2 x mean."""
    mask = trail > 2.0 * trail.mean()
    labels, count = ndimage.label(mask)
    parent = list(range(count + 1))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def join(a, b):
        if a and b:
            ra, rb = find(a), find(b)
            if ra != rb:
                parent[max(ra, rb)] = min(ra, rb)

    for a, b in zip(labels[:, 0], labels[:, -1]):
        join(a, b)
    for a, b in zip(labels[0, :], labels[-1, :]):
        join(a, b)
    pieces = len({find(k) for k in range(1, count + 1)})
    return mask.mean(), pieces


def run(n=2500, nx=128, ny=128, steps=600, seed=1, sensor_angle=0.7, sensor_dist=9.0,
        turn_angle=0.6, step_size=1.0, deposit=5.0, decay=0.1, diffusion=1.0,
        record=(0, 50, 100, 200, 300, 400, 600)):
    rng = np.random.default_rng(seed)
    x = rng.random(n) * nx
    y = rng.random(n) * ny
    heading = rng.random(n) * 2 * np.pi
    ux, uy = np.cos(heading), np.sin(heading)
    trail = np.zeros((ny, nx))
    cs, ss = np.cos(sensor_angle), np.sin(sensor_angle)
    ct, st = np.cos(turn_angle), np.sin(turn_angle)
    out = {}

    def sense(px, py):
        return trail[np.floor(py).astype(int) % ny, np.floor(px).astype(int) % nx]

    for step in range(steps + 1):
        if step in record:
            out[step] = structures(trail)
        if step == steps:
            break
        left = sense(x + sensor_dist * (ux * cs - uy * ss), y + sensor_dist * (ux * ss + uy * cs))
        ahead = sense(x + sensor_dist * ux, y + sensor_dist * uy)
        right = sense(x + sensor_dist * (ux * cs + uy * ss), y + sensor_dist * (uy * cs - ux * ss))
        coin = rng.random(n) < 0.5
        turn = np.zeros(n)
        keep = (ahead > left) & (ahead > right)
        rand = (ahead < left) & (ahead < right)
        turn = np.where(rand, np.where(coin, 1.0, -1.0), turn)
        turn = np.where(~keep & ~rand & (left < right), -1.0, turn)
        turn = np.where(~keep & ~rand & (right < left), 1.0, turn)
        s = turn * st
        nux, nuy = ux * ct - uy * s, ux * s + uy * ct
        norm = np.hypot(nux, nuy)
        ux, uy = np.where(turn != 0, nux / norm, ux), np.where(turn != 0, nuy / norm, uy)
        x = (x + step_size * ux) % nx
        y = (y + step_size * uy) % ny
        np.add.at(trail, (np.floor(y).astype(int) % ny, np.floor(x).astype(int) % nx), deposit)
        if diffusion > 0:
            mean = sum(np.roll(np.roll(trail, a, 0), b, 1) for a in (-1, 0, 1) for b in (-1, 0, 1)) / 9.0
            trail = (1 - diffusion) * trail + diffusion * mean
        trail *= 1 - decay
    return out


if __name__ == "__main__":
    for seed in (1, 2, 3, 4):
        r = run(seed=seed)
        print(f"seed {seed}:", "  ".join(f"{k}: {v[1]:3d} pieces {v[0]:.2f}" for k, v in r.items()))
