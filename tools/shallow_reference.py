"""Independent reference for the shallow-water model of physis (numpy, double precision).

It follows the scheme of the Scala model (a staggered grid, the height in flux form, a
forward-backward step with the Coriolis terms taking the newest u for v, the Coriolis
parameter f = f0 + beta (y_mid - y), bottom friction, a quadratic sponge strip) but shares no
code with it: whole-array arithmetic and its own operators. Like the model it draws north at
the top: row 0 is the northern edge, y and v run south, and f grows toward the north (so a
hump swirls clockwise for f > 0). It has the linear equations only,
which is what the checks below need. Agreement is of the physics, not of the bits.

Usage (numpy is the only dependency):
    python tools/shallow_reference.py seiche      # period of the first seiche of a closed basin
    python tools/shallow_reference.py front       # speed of a gravity wave and mass conservation
    python tools/shallow_reference.py adjustment  # geostrophic adjustment: Rossby radius, height
    python tools/shallow_reference.py rossby      # phase drift of a Rossby wave against omega
    python tools/shallow_reference.py kelvin      # speed and one-sidedness of a Kelvin wave
    python tools/shallow_reference.py shoaling    # Green's law on a shelf
    python tools/shallow_reference.py sponge      # what an open sea leaves of a wave
    python tools/shallow_reference.py energy      # range of the energy of waves in a closed basin
    python tools/shallow_reference.py all

The numbers it prints are the ones the tolerances in the Scala tests were chosen from.
"""

import math
import sys

import numpy as np

G = 9.81
DT = 50.0
SPONGE_STRENGTH = 0.1


class Shallow:
    """h: depth at rest (scalar or array of shape (ny, nx)); edges 0 channel, 1 basin."""

    def __init__(self, nx, ny, dx, h, f0=0.0, beta=0.0, r=0.0, edges=1, dt=DT, g=G, sponge=0):
        self.nx, self.ny, self.dx, self.g, self.dt = nx, ny, dx, g, dt
        self.r = r
        self.wrap = edges == 0
        self.h = np.full((ny, nx), float(h)) if np.isscalar(h) else np.array(h, float)
        self.eta = np.zeros((ny, nx))
        self.u = np.zeros((ny, nx + 1))
        self.v = np.zeros((ny + 1, nx))
        self.ou = np.zeros((ny, nx + 1))
        self.ov = np.zeros((ny + 1, nx))
        self.ou[:, 1:nx] = 1.0
        self.ov[1:ny, :] = 1.0
        if self.wrap:
            self.ou[:, 0] = 1.0
            self.ou[:, nx] = 1.0
        yu = (np.arange(ny) + 0.5) * dx
        yv = np.arange(ny + 1) * dx
        mid = 0.5 * ny * dx
        self.fu = (f0 + beta * (mid - yu))[:, None]
        self.fv = (f0 + beta * (mid - yv))[:, None]
        self.sponge = None
        if sponge > 0:
            self.sponge = self._sponge(sponge)

    def _sponge(self, w):
        nx, ny = self.nx, self.ny

        def keep(a, b):
            d = np.minimum(a, b)
            return np.where(d >= w, 1.0, 1.0 - SPONGE_STRENGTH * (w - d) ** 2 / w**2)

        xc = np.arange(nx) + 0.5
        yc = np.arange(ny) + 0.5
        xu = np.arange(nx + 1.0)
        yv = np.arange(ny + 1.0)
        kc = keep(np.minimum(xc, nx - xc)[None, :], np.minimum(yc, ny - yc)[:, None])
        ku = keep(np.minimum(xu, nx - xu)[None, :], np.minimum(yc, ny - yc)[:, None])
        kv = keep(np.minimum(xc, nx - xc)[None, :], np.minimum(yv, ny - yv)[:, None])
        return kc, ku, kv

    def thickness(self):
        h = self.h
        hu = np.zeros((self.ny, self.nx + 1))
        hu[:, 1:self.nx] = 0.5 * (h[:, 1:] + h[:, :-1])
        if self.wrap:
            hu[:, 0] = 0.5 * (h[:, 0] + h[:, -1])
            hu[:, self.nx] = hu[:, 0]
        hv = np.zeros((self.ny + 1, self.nx))
        hv[1:self.ny, :] = 0.5 * (h[1:, :] + h[:-1, :])
        return hu, hv

    def v_at_u(self):
        v = self.v
        zero = np.zeros((self.ny + 1, 1))
        left = np.concatenate([v[:, -1:] if self.wrap else zero, v], axis=1)
        right = np.concatenate([v, v[:, :1] if self.wrap else zero], axis=1)
        return 0.25 * (left[:-1] + right[:-1] + left[1:] + right[1:])

    def u_at_v(self):
        pad = np.zeros((1, self.nx + 1))
        u = np.concatenate([pad, self.u, pad], axis=0)
        return 0.25 * (u[:-1, :-1] + u[:-1, 1:] + u[1:, :-1] + u[1:, 1:])

    def step(self):
        dt, dx, g = self.dt, self.dx, self.g
        hu, hv = self.thickness()
        fx = self.ou * hu * self.u
        fy = self.ov * hv * self.v
        self.eta = self.eta - dt / dx * ((fx[:, 1:] - fx[:, :-1]) + (fy[1:, :] - fy[:-1, :]))
        deta = np.zeros((self.ny, self.nx + 1))
        deta[:, 1:self.nx] = self.eta[:, 1:] - self.eta[:, :-1]
        if self.wrap:
            deta[:, 0] = self.eta[:, 0] - self.eta[:, -1]
            deta[:, self.nx] = deta[:, 0]
        u = self.u + dt * (-g * deta / dx - self.fu * self.v_at_u() - self.r * self.u)
        self.u = self.ou * u
        if self.wrap:
            self.u[:, self.nx] = self.u[:, 0]
        dv = np.zeros((self.ny + 1, self.nx))
        dv[1:self.ny, :] = self.eta[1:, :] - self.eta[:-1, :]
        v = self.v + dt * (-g * dv / dx + self.fv * self.u_at_v() - self.r * self.v)
        self.v = self.ov * v
        if self.sponge is not None:
            kc, ku, kv = self.sponge
            self.eta = self.eta * kc
            self.u = self.u * ku
            self.v = self.v * kv

    def energy(self):
        hu, hv = self.thickness()
        potential = self.g * (self.eta**2).sum()
        ku = hu[:, :-1] * self.u[:, :-1] ** 2
        kv = hv[1:-1] * self.v[1:-1] ** 2
        return 0.5 * (potential + (ku * self.ou[:, :-1]).sum() + kv.sum()) * self.dx**2


def cells(nx, ny):
    x = np.arange(nx) + 0.5
    y = np.arange(ny) + 0.5
    return np.meshgrid(x, y)


def seiche():
    nx, ny, dx, depth = 200, 4, 10_000.0, 4000.0
    s = Shallow(nx, ny, dx, depth)
    x = np.arange(nx) + 0.5
    s.eta[:] = np.cos(np.pi * x / nx)[None, :]
    c = math.sqrt(G * depth)
    period = 2 * nx * dx / c
    series = []
    for _ in range(int(3 * period / DT)):
        s.step()
        series.append(s.eta[1, 0])
    zc = np.where(np.diff(np.sign(np.array(series))) != 0)[0]
    measured = 2 * (zc[-1] - zc[0]) / (len(zc) - 1) * DT
    print(f"seiche period {measured:.0f} s, expected 2L/c = {period:.0f} s, "
          f"ratio {measured / period:.4f}")


def front():
    nx, ny, dx, depth = 400, 4, 20_000.0, 4000.0
    s = Shallow(nx, ny, dx, depth)
    x = np.arange(nx) + 0.5
    s.eta[:] = np.exp(-((x - 100) ** 2) / (2 * 6.0**2))[None, :]
    mass = s.eta.sum()
    c = math.sqrt(G * depth)
    steps = 300
    for _ in range(steps):
        s.step()
    right = np.argmax(s.eta[1, 150:]) + 150
    travelled = (right - 100) * dx
    print(f"crest moved {travelled / 1000:.0f} km in {steps * DT:.0f} s: "
          f"{travelled / (steps * DT):.1f} m/s against c = {c:.1f} m/s "
          f"(ratio {travelled / (steps * DT) / c:.4f}); crest height {s.eta[1, right]:.3f}")
    print(f"mass drift {abs(s.eta.sum() - mass):.2e}")


def adjustment():
    depth, dx, f0 = 200.0, 10_000.0, 1e-4
    nx, ny = 4, 600
    s = Shallow(nx, ny, dx, depth, f0=f0, edges=0)
    y = np.arange(ny) + 0.5
    j0 = ny // 2
    a = 0.2
    s.eta[:] = (a * 0.5 * (1 - np.tanh((y - j0) / 3.0)))[:, None]
    mean = np.zeros(ny)
    n = 0
    for k in range(12000):
        s.step()
        if k >= 6000 and k % 10 == 0:
            mean += s.eta[:, 0]
            n += 1
    mean /= n
    radius = math.sqrt(G * depth) / f0 / dx
    for lo, hi in [(6, 60), (10, 90)]:
        js = np.arange(j0 + lo, j0 + hi)
        slope = np.polyfit((js - j0) * dx, np.log(mean[js]), 1)[0]
        print(f"fitted Rossby radius {-1 / slope / dx:.1f} cells (rows {lo}..{hi}), "
              f"expected {radius:.1f}")
    print(f"height at the front {0.5 * (mean[j0 - 1] + mean[j0]):.4f}, expected {a / 2}")
    u = 0.5 * (s.u[:, 0] + s.u[:, 1])
    print(f"max |u| at the end {abs(u).max():.4f} m/s (a balanced front: g a / (2 f R) "
          f"= {G * a / (2 * f0 * math.sqrt(G * depth) / f0):.4f})")


def rossby_setup(nx=64, ny=32, dx=50_000.0, depth=500.0, f0=1e-4, beta=2e-11, amp=0.1):
    s = Shallow(nx, ny, dx, depth, f0=f0, beta=beta, edges=0)
    lx, ly = nx * dx, ny * dx
    k, l = 2 * math.pi / lx, math.pi / ly
    xc = (np.arange(nx) + 0.5) * dx
    yc = (np.arange(ny) + 0.5) * dx
    x, y = np.meshgrid(xc, yc)
    s.eta = amp * np.sin(k * x) * np.sin(l * y)
    xu, yu = np.meshgrid(np.arange(nx + 1) * dx, yc)
    s.u = (G / s.fu) * amp * np.sin(k * xu) * l * np.cos(l * yu)
    xv, yv = np.meshgrid(xc, np.arange(ny + 1) * dx)
    s.v = -(G / s.fv) * amp * k * np.cos(k * xv) * np.sin(l * yv)
    s.u *= s.ou
    s.v *= s.ov
    return s, k, l, x, y, f0, depth


def rossby():
    s, k, l, x, y, f0, depth = rossby_setup()
    omega = -2e-11 * k / (k * k + l * l + f0**2 / (G * depth))

    def phase(e):
        a = (e * np.sin(k * x) * np.sin(l * y)).sum()
        b = (e * np.cos(k * x) * np.sin(l * y)).sum()
        return math.atan2(b, a), math.hypot(a, b)

    p0, m0 = phase(s.eta)
    print(f"omega {omega:.4e} 1/s, period {2 * math.pi / abs(omega) / DT:.0f} steps")
    for step in range(1, 16001):
        s.step()
        if step % 4000 == 0:
            p, m = phase(s.eta)
            expected = -omega * step * DT
            print(f"step {step}: phase {p - p0:.4f}, expected {expected:.4f}, "
                  f"ratio {(p - p0) / expected:.4f}, amplitude {m / m0:.4f}")


def kelvin():
    depth, dx, f0 = 200.0, 10_000.0, 1e-4
    nx, ny = 96, 400
    s = Shallow(nx, ny, dx, depth, f0=f0, edges=1)
    x, y = cells(nx, ny)
    y0 = 200.0
    s.eta = 0.1 * np.exp(-((x - 1.0) ** 2 + (y - y0) ** 2) / (2 * 3.0**2))
    c = math.sqrt(G * depth)
    south, north = [], []
    for _ in range(1100):
        s.step()
        south.append(s.eta[int(y0) + 150, 0])
        north.append(s.eta[int(y0) - 150, 0])
    south, north = np.array(south), np.array(north)
    k = south.argmax()
    print(f"Kelvin wave: peak {south.max():.4f} at step {k} -> speed "
          f"{150 * dx / (k * DT):.2f} m/s against c = {c:.2f} m/s")
    print(f"northwards, by step {k}: {north[:k + 1].max():.4f} "
          f"({north[:k + 1].max() / south.max():.2f} of the southward peak)")


def shoaling():
    nx, ny, dx = 400, 4, 20_000.0
    x = np.arange(nx) + 0.5
    depth = np.where(x < 120, 4000.0, np.where(x < 320, 4000.0 - (x - 120) / 200 * 3000.0, 1000.0))
    s = Shallow(nx, ny, dx, np.tile(depth, (ny, 1)))
    s.eta[:] = np.exp(-((x - 60) ** 2) / (2 * 6.0**2))[None, :]
    peak = {100: 0.0, 300: 0.0}
    for k in range(2200):
        s.step()
        for i in peak:
            peak[i] = max(peak[i], s.eta[1, i])
    ratio = peak[300] / peak[100]
    green = (depth[100] / depth[300]) ** 0.25
    print(f"amplitude {peak[100]:.4f} at depth {depth[100]:.0f} m, {peak[300]:.4f} at "
          f"{depth[300]:.0f} m: ratio {ratio:.4f}, Green's law {green:.4f}")


def sponge():
    nx = ny = 80
    for edges, w in [(1, 0), (1, 10)]:
        s = Shallow(nx, ny, 20_000.0, 4000.0, edges=edges, sponge=w)
        x, y = cells(nx, ny)
        s.eta = np.exp(-((x - 40) ** 2 + (y - 40) ** 2) / (2 * 3.0**2))
        e0 = s.energy()
        for _ in range(400):
            s.step()
        print(f"sponge width {w}: max |eta| {abs(s.eta).max():.4f}, "
              f"energy left {s.energy() / e0:.2e}")


def energy():
    for f0 in (0.0, 1e-4):
        s = Shallow(60, 50, 20_000.0, 4000.0, f0=f0)
        x, y = cells(60, 50)
        s.eta = np.exp(-((x - 30) ** 2 + (y - 25) ** 2) / (2 * 4.0**2))
        e0 = s.energy()
        ratios = []
        for _ in range(1500):
            s.step()
            ratios.append(s.energy() / e0)
        print(f"f0 {f0}: energy between {min(ratios):.4f} and {max(ratios):.4f} of the start")


COMMANDS = {
    "seiche": seiche,
    "front": front,
    "adjustment": adjustment,
    "rossby": rossby,
    "kelvin": kelvin,
    "shoaling": shoaling,
    "sponge": sponge,
    "energy": energy,
}

if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "seiche"
    if what == "all":
        for name, fn in COMMANDS.items():
            print(f"-- {name}")
            fn()
    else:
        COMMANDS[what]()
