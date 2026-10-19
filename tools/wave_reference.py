"""Independent reference for the wave model of physis (numpy, double precision).

It repeats the Scala model's formulas with the same order of floating-point operations, but shares
no code with it: halos by np.pad, whole-array arithmetic, its own disc rasterization and FNV-1a
digest. The setups avoid exp so that the golden digests are bit-exact, not just close.

Usage (numpy is the only dependency):
    python tools/wave_reference.py fields > models/wave/src/test/resources/wave-box-64x64-200.csv
    python tools/wave_reference.py digest      # golden digests of 1 000 steps, 64 x 64
    python tools/wave_reference.py reflection  # energy left after a pulse met the border

Regenerating the golden data is a deliberate act: review the diff.
"""

import math
import struct
import sys

import numpy as np

DT = 0.5
LOG_R = 6.907755278982137
PERIODIC, REFLECTIVE, FREE, ABSORBING = 0, 1, 2, 3


def sin_turns(turns):
    """sin(2 pi turns) by the same folded Taylor series as Wave.sinTurns."""
    x = turns - math.floor(turns)
    if x > 0.5:
        x -= 1.0
    s = -1.0 if x < 0.0 else 1.0
    ax = abs(x)
    if ax > 0.25:
        ax = 0.5 - ax
    a = 2.0 * math.pi * ax
    a2 = a * a
    term = a
    total = a
    for k in range(1, 11):
        term = -term * a2 / ((2 * k) * float(2 * k + 1))
        total += term
    return s * total


def disc_cells(nx, ny, x, y, r, wrap=False):
    """Cells whose centre is within r of (x, y); cell (i, j) is centred at (i + .5, j + .5)."""
    r2 = r * r
    cells = []
    for j in range(ny):
        dy = j + 0.5 - y
        if wrap:
            dy -= ny * math.floor(dy / ny + 0.5)
        dy2 = dy * dy
        if dy2 > r2:
            continue
        for i in range(nx):
            dx = i + 0.5 - x
            if wrap:
                dx -= nx * math.floor(dx / nx + 0.5)
            if dx * dx + dy2 <= r2:
                cells.append((i, j))
    return cells


def pad(a, edge):
    """Halo of one cell: periodic, free (copy) or reflective (ghost = 0 - cell)."""
    p = np.zeros((a.shape[0] + 2, a.shape[1] + 2))
    p[1:-1, 1:-1] = a
    if edge == PERIODIC:
        p[1:-1, 0] = a[:, -1]
        p[1:-1, -1] = a[:, 0]
        p[0, 1:-1] = a[-1, :]
        p[-1, 1:-1] = a[0, :]
    elif edge == FREE:
        p[1:-1, 0] = a[:, 0]
        p[1:-1, -1] = a[:, -1]
        p[0, 1:-1] = a[0, :]
        p[-1, 1:-1] = a[-1, :]
    else:
        p[1:-1, 0] = 0.0 - a[:, 0]
        p[1:-1, -1] = 0.0 - a[:, -1]
        p[0, 1:-1] = 0.0 - a[0, :]
        p[-1, 1:-1] = 0.0 - a[-1, :]
    return p


def ramp_of(nx, ny, width):
    w = float(width)
    ramp = np.zeros((ny, nx))
    for j in range(ny):
        for i in range(nx):
            d = min(min(i, nx - 1 - i), min(j, ny - 1 - j)) + 0.5
            depth = (w - d) / w
            ramp[j, i] = depth * depth if depth > 0.0 else 0.0
    return ramp


class Wave:
    def __init__(self, nx, ny, edge, c=1.0, damping=0.0, sponge=24, frequency=0.2, amplitude=0.0):
        self.nx, self.ny, self.edge = nx, ny, edge
        self.c, self.damping, self.sponge = c, damping, sponge
        self.frequency, self.amplitude = frequency, amplitude
        self.now = np.zeros((ny, nx))
        self.before = np.zeros((ny, nx))
        self.wall = np.zeros((ny, nx))
        self.ramp = ramp_of(nx, ny, sponge) if edge == ABSORBING else np.zeros((ny, nx))
        self.time = 0.0
        self.sx, self.sy = nx // 2, ny // 2

    def paint_wall(self, x, y, r):
        wrap = self.edge == PERIODIC
        for i, j in disc_cells(self.nx, self.ny, x, y, r, wrap):
            self.wall[j, i] = 1.0
            self.now[j, i] = 0.0
            self.before[j, i] = 0.0

    def assign_u(self, x, y, r, value):
        wrap = self.edge == PERIODIC
        for i, j in disc_cells(self.nx, self.ny, x, y, r, wrap):
            if self.wall[j, i] == 0.0:
                self.now[j, i] = value
                self.before[j, i] = value

    def step(self):
        u, old = self.now, self.before
        p = pad(u, self.edge)
        left, right = p[1:-1, :-2], p[1:-1, 2:]
        below, above = p[:-2, 1:-1], p[2:, 1:-1]
        lap = ((((left + right) + below) + above) - 4.0 * u) * 1.0
        c2dt2 = self.c * self.c * DT * DT
        half = 0.5 * DT
        sponge = 3.0 * self.c * LOG_R / self.sponge if self.edge == ABSORBING else 0.0
        a = half * (self.damping + sponge * self.ramp)
        nxt = ((2.0 * u - (1.0 - a) * old) + c2dt2 * lap) / (1.0 + a)
        new = np.where(self.wall != 0.0, 0.0, nxt)
        if self.amplitude != 0.0 and self.wall[self.sy, self.sx] == 0.0:
            new[self.sy, self.sx] += DT * DT * self.amplitude * sin_turns(self.frequency * self.time)
        self.before = u
        self.now = new
        self.time += DT

    def energy(self):
        b = pad(self.before, self.edge)
        minus_lap = (4.0 * self.before - (((b[1:-1, :-2] + b[1:-1, 2:]) + b[:-2, 1:-1]) + b[2:, 1:-1])) * 1.0
        kinetic = float(np.sum(((self.now - self.before) / DT) ** 2))
        potential = float(np.sum(self.now * minus_lap))
        return 0.5 * (kinetic + self.c * self.c * potential)

    def digest(self):
        h = 0xCBF29CE484222325
        for field in (self.now, self.before, self.wall):
            for v in field.reshape(-1):
                bits = struct.unpack("<Q", struct.pack("<d", float(v)))[0]
                for shift in range(0, 64, 8):
                    h = ((h ^ ((bits >> shift) & 0xFF)) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
        return h - (1 << 64) if h >= (1 << 63) else h


def box(edge):
    """The setup of the tests: a wall of overlapping discs, a bump, damping and a source."""
    w = Wave(64, 64, edge, c=1.0, damping=0.02, sponge=16, frequency=0.2, amplitude=0.5)
    for k in range(12):
        w.paint_wall(40.5, 8.5 + 4.0 * k, 2.6)
    w.assign_u(20.3, 28.7, 5.0, 1.0)
    return w


def run(w, steps):
    for _ in range(steps):
        w.step()
    return w


def main(argv):
    what = argv[1] if len(argv) > 1 else "fields"
    if what == "fields":
        w = run(box(REFLECTIVE), 200)
        print("j,i,u")
        for j in range(64):
            for i in range(64):
                print(f"{j},{i},{float(w.now[j, i])!r}")
    elif what == "digest":
        for name, edge in (("box-reflective", REFLECTIVE), ("box-absorbing", ABSORBING)):
            print(name, run(box(edge), 1000).digest())
    elif what == "reflection":
        for name, edge in (("reflective", REFLECTIVE), ("free", FREE), ("absorbing", ABSORBING)):
            w = Wave(256, 256, edge, c=1.0, sponge=24)
            for (i, j) in disc_cells(256, 256, 128.0, 128.0, 6.0):
                w.now[j, i] = w.before[j, i] = 1.0
            e0 = w.energy()
            run(w, 700)
            print(f"{name}: energy left {w.energy() / e0:.6f}")
    else:
        sys.exit("usage: wave_reference.py fields|digest|reflection")


if __name__ == "__main__":
    main(sys.argv)
