"""Independent reference for the flooding shallow-water scheme of physis (numpy, double precision).

It follows the scheme of the Scala model with `flooding = 1` in one dimension (a channel along x
with walls at both ends) but shares no code with it: whole-array arithmetic and its own
operators. The scheme: the surface eta and the bed z at the cell centres, the velocity u on the
faces; a dry cell keeps eta == z; the depth over the sill of a face is the mean of the depths on
both sides above the higher bed, and the face carries flow when either side has more than `eps`
of water over it; the outflow of every cell is cut to the water it holds (a positivity limiter,
so the volume is exact); the height is updated from the old flow, the velocity is advected by
itself (semi-Lagrangian, midpoint rule, linear interpolation), then pushed by the slope of the
new surface; faces without water are stopped. Agreement with the Scala model is of the physics
and of the scheme, to rounding and to the treatment of the walls.

Usage (numpy is the only dependency):
    python tools/shallow_flood_reference.py rest      # a lake at rest against a beach stays at rest
    python tools/shallow_flood_reference.py runup     # a solitary wave on a 1:19.85 beach (Synolakis)
    python tools/shallow_flood_reference.py thacker   # the water in a parabolic bowl moves as a whole
    python tools/shallow_flood_reference.py all

The numbers it prints are the ones the tolerances in the Scala tests were chosen from.
"""

import math
import sys

import numpy as np

G = 9.81


def sech2(x):
    return 1.0 / np.cosh(x) ** 2


class Channel:
    """n cells of size dx between two walls; bed z (n,), surface eta (n,), velocity u (n+1,)."""

    def __init__(self, z, dx, eps, g=G):
        self.z = np.array(z, float)
        self.n = len(self.z)
        self.dx, self.eps, self.g = dx, eps, g
        self.eta = np.maximum(0.0, self.z)
        self.u = np.zeros(self.n + 1)
        self.faces = np.arange(self.n + 1, dtype=float)

    def sill(self, eta):
        zm = np.maximum(self.z[:-1], self.z[1:])
        a = np.maximum(0.0, eta[:-1] - zm)
        b = np.maximum(0.0, eta[1:] - zm)
        return 0.5 * (a + b), np.maximum(a, b) > self.eps

    def step(self, dt):
        n, dx = self.n, self.dx
        # the height, from the old flow, with the outflow of every cell cut to its water
        depth, wet = self.sill(self.eta)
        flux = np.zeros(n + 1)
        flux[1:n] = np.where(wet, depth * self.u[1:n], 0.0)
        water = np.maximum(0.0, self.eta - self.z)
        out = np.maximum(0.0, flux[1:]) + np.maximum(0.0, -flux[:-1])
        with np.errstate(divide="ignore", invalid="ignore"):
            scale = np.where(out * dt > water * dx, water * dx / (out * dt), 1.0)
        cut = flux.copy()
        cut[1:n] = np.where(
            flux[1:n] > 0.0, flux[1:n] * scale[:-1], np.where(flux[1:n] < 0.0, flux[1:n] * scale[1:], 0.0)
        )
        self.eta = np.maximum(self.z, self.eta - dt / dx * (cut[1:] - cut[:-1]))
        # the flow carries itself along
        inv = dt / dx
        mid = self.faces - 0.5 * inv * self.u
        u_mid = np.interp(mid, self.faces, self.u)
        back = self.faces - inv * u_mid
        self.u = np.interp(back, self.faces, self.u)
        self.u[0] = self.u[n] = 0.0
        # the slope of the new surface; faces without water stop
        _, wet = self.sill(self.eta)
        push = -self.g * (self.eta[1:] - self.eta[:-1]) / dx
        self.u[1:n] = np.where(wet, self.u[1:n] + dt * push, 0.0)

    def volume(self):
        return float(np.sum(self.eta - np.maximum(0.0, self.z)) * self.dx)


def rest():
    n, dx, d = 400, 0.25, 1.0
    x = (np.arange(n) + 0.5) * dx
    z = np.where(x < 50.0, -d, -d + 0.05 * (x - 50.0))
    ch = Channel(z, dx, 1e-4)
    for _ in range(2000):
        ch.step(0.05)
    print(f"lake at rest on a beach: max |u| = {np.abs(ch.u).max():.1e}, "
          f"max |eta - rest| = {np.abs(ch.eta - np.maximum(0.0, z)).max():.1e}")


def runup(dx=0.25, dt=0.05, height=0.0185, slope=1 / 19.85, d=1.0, length=100.0):
    """A solitary wave of height `height` in water of depth `d` on a beach of the given slope.

    The tank is `length` m long, the beach starts in the middle and the crest of the wave stands
    at a quarter of the length, as in `Shallow.WaveStart` and `Shallow.BeachToe`.
    """
    n = int(round(length / dx))
    x = (np.arange(n) + 0.5) * dx
    toe, x0 = 0.5 * length, 0.25 * length
    z = np.where(x <= toe, -d, -d + slope * (x - toe))
    ch = Channel(z, dx, 1e-4)
    w = math.sqrt(4.0 * d**3 / (3.0 * height))
    ch.eta = np.maximum(height * sech2((x - x0) / w), z)
    c = math.sqrt(G * (d + height))
    e = height * sech2((np.arange(n + 1) * dx - x0) / w)
    ch.u = c * e / (d + e)
    ch.u[0] = ch.u[n] = 0.0
    top = 0.0
    volume = ch.volume()
    t = 0.0
    while t < 40.0:
        ch.step(dt)
        t += dt
        wet = ch.eta - ch.z > ch.eps
        if wet.any():
            top = max(top, float(ch.eta[wet].max()))
    return top / d, abs(ch.volume() - volume), 2.831 * math.sqrt(1.0 / slope) * (height / d) ** 1.25


def thacker(dx, dt=None, h0=1.0, a=10.0, b=2.0, periods=1.0):
    """The water in a parabolic bowl `z = h0 ((x / a)^2 - 1)` moves as a whole (Thacker 1981).

    The surface stays flat and the depth is `h0 (1 - ((x - c) / a)^2)` where it is positive, with
    the centre at `c = b cos(w t)` and `w = sqrt(2 g h0) / a`. After one period it is back."""
    length = 3.0 * a
    n = int(round(length / dx))
    x = (np.arange(n) + 0.5) * dx - 0.5 * length
    z = h0 * ((x / a) ** 2 - 1.0)
    w = math.sqrt(2.0 * G * h0) / a
    dt = dt or 0.2 * dx
    ch = Channel(z, dx, 1e-4)

    def exact(t):
        depth = np.maximum(0.0, h0 - h0 * ((x - b * math.cos(w * t)) / a) ** 2)
        return depth

    ch.eta = exact(0.0) + z
    steps = int(round(periods * 2.0 * math.pi / w / dt))
    volume = ch.volume()
    for _ in range(steps):
        ch.step(dt)
    depth = ch.eta - ch.z
    want = exact(steps * dt)
    shore = np.where(depth > ch.eps)[0]
    shore_want = np.where(want > 0.0)[0]
    return {
        "dx": dx,
        "l1": float(np.abs(depth - want).sum() / want.sum()),
        "shore_cells": (int(shore.min() - shore_want.min()), int(shore.max() - shore_want.max())),
        "volume_error": abs(ch.volume() - volume),
    }


def main(what):
    if what in ("rest", "all"):
        rest()
    if what in ("runup", "all"):
        for dx, dt in ((0.5, 0.1), (0.25, 0.05)):
            top, drift, pred = runup(dx=dx, dt=dt)
            print(f"runup dx {dx}: R/d = {top:.4f}, Synolakis {pred:.4f}, ratio {top / pred:.3f}, "
                  f"volume drift {drift:.1e}")
    if what in ("thacker", "all"):
        for dx in (0.2, 0.1):
            print("thacker", thacker(dx))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "all")
