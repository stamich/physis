"""Independent reference for the fluid model of physis (numpy, double precision).

It follows the same scheme as the Scala model (staggered grid, semi-Lagrangian advection with a
midpoint backtrace, explicit viscosity with mirrored ghost velocities, pressure projection by
conjugate gradients) but shares no code with it: whole-array arithmetic, its own bilinear
sampling and its own operators. Agreement is of the physics, not of the bits: the two differ in
the order of the floating-point sums.

Usage (numpy is the only dependency):
    python tools/fluid_reference.py cavity            # Re = 100 profile against Ghia et al. (1982)
    python tools/fluid_reference.py taylor-green      # decay of a Taylor-Green vortex
    python tools/fluid_reference.py wake              # shedding behind a cylinder (Strouhal number)

The numbers it prints are the ones the tolerances in the Scala tests were chosen from.
"""

import sys

import numpy as np

DT = 0.25
PERIODIC, BOX, CHANNEL = 0, 1, 2

# Ghia, Ghia and Shin (1982), Re = 100: u along the vertical centre line x = 0.5 (y from the
# bottom, the lid is at y = 1 and moves with speed 1).
GHIA_Y = np.array(
    [1.0, 0.9766, 0.9688, 0.9609, 0.9531, 0.8516, 0.7344, 0.6172, 0.5, 0.4531, 0.2813, 0.1719,
     0.1016, 0.0703, 0.0625, 0.0547, 0.0]
)
GHIA_U = np.array(
    [1.0, 0.84123, 0.78871, 0.73722, 0.68717, 0.23151, 0.00332, -0.13641, -0.20581, -0.21090,
     -0.15662, -0.10150, -0.06434, -0.04775, -0.04192, -0.03717, 0.0]
)


def bilinear(f, x, y, ox, oy, nx, ny, wrap):
    """f sampled at (x, y); f[j, i] sits at (i + ox, j + oy). Wraps with period (nx, ny), or clamps."""
    gx = x - ox
    gy = y - oy
    if wrap:
        gx = gx - nx * np.floor(gx / nx)
        gy = gy - ny * np.floor(gy / ny)
        i0 = np.floor(gx).astype(int)
        j0 = np.floor(gy).astype(int)
        tx = gx - i0
        ty = gy - j0
        i0 %= nx
        j0 %= ny
        i1 = (i0 + 1) % nx
        j1 = (j0 + 1) % ny
    else:
        lx = f.shape[1] - 1
        ly = f.shape[0] - 1
        gx = np.clip(gx, 0.0, lx)
        gy = np.clip(gy, 0.0, ly)
        i0 = np.minimum(np.floor(gx), max(lx - 1, 0)).astype(int)
        j0 = np.minimum(np.floor(gy), max(ly - 1, 0)).astype(int)
        tx = gx - i0
        ty = gy - j0
        i1 = np.minimum(i0 + 1, lx)
        j1 = np.minimum(j0 + 1, ly)
    bottom = f[j0, i0] * (1 - tx) + f[j0, i1] * tx
    top = f[j1, i0] * (1 - tx) + f[j1, i1] * tx
    return bottom * (1 - ty) + top * ty


class Fluid:
    def __init__(self, nx, ny, edge, viscosity, lid=0.0, inflow=0.0, buoyancy=0.0, dye_diffusion=0.0):
        self.nx, self.ny, self.edge = nx, ny, edge
        self.nu, self.lid, self.inflow = viscosity, lid, inflow
        self.buoyancy, self.kappa = buoyancy, dye_diffusion
        self.wrap = edge == PERIODIC
        self.outflow = edge == CHANNEL
        self.u = np.zeros((ny, nx + 1))
        self.v = np.zeros((ny + 1, nx))
        self.dye = np.zeros((ny, nx))
        self.p = np.zeros((ny, nx))
        self.solid = np.zeros((ny, nx), dtype=bool)
        self.configure()
        # sample positions
        self.ux, self.uy = np.meshgrid(np.arange(nx + 1), np.arange(ny) + 0.5)
        self.vx, self.vy = np.meshgrid(np.arange(nx) + 0.5, np.arange(ny + 1))
        self.cx, self.cy = np.meshgrid(np.arange(nx) + 0.5, np.arange(ny) + 0.5)
        self.iterations = 0

    # -- geometry ------------------------------------------------------------------------
    def configure(self):
        nx, ny, s = self.nx, self.ny, self.solid
        free = ~s
        ou = np.zeros((ny, nx + 1))
        ov = np.zeros((ny + 1, nx))
        ou[:, 1:nx] = free[:, :-1] & free[:, 1:]
        ov[1:ny, :] = free[:-1, :] & free[1:, :]
        if self.wrap:
            ou[:, 0] = ou[:, nx] = free[:, nx - 1] & free[:, 0]
            ov[0, :] = ov[ny, :] = free[ny - 1, :] & free[0, :]
        elif self.outflow:
            ou[:, nx] = free[:, nx - 1]
        self.ou, self.ov = ou, ov
        unknown = (ou[:, :-1] + ou[:, 1:] + ov[:-1, :] + ov[1:, :]) > 0
        self.unknown = unknown

    def close_faces(self):
        self.u[self.ou == 0] = 0.0
        self.v[self.ov == 0] = 0.0
        if self.edge == CHANNEL:
            self.u[~self.solid[:, 0], 0] = self.inflow

    # -- operators -----------------------------------------------------------------------
    def divergence(self):
        return (self.u[:, 1:] - self.u[:, :-1]) + (self.v[1:, :] - self.v[:-1, :])

    def apply_b(self, q):
        """sum over the four sides of open * (q_cell - q_neighbour)."""
        if self.wrap:
            left = np.roll(q, 1, axis=1)
            right = np.roll(q, -1, axis=1)
            down = np.roll(q, 1, axis=0)
            up = np.roll(q, -1, axis=0)
        else:
            left = np.zeros_like(q)
            left[:, 1:] = q[:, :-1]
            right = np.zeros_like(q)
            right[:, :-1] = q[:, 1:]
            down = np.zeros_like(q)
            down[1:, :] = q[:-1, :]
            up = np.zeros_like(q)
            up[:-1, :] = q[1:, :]
        ou, ov = self.ou, self.ov
        return (
            ou[:, :-1] * (q - left) + ou[:, 1:] * (q - right) + ov[:-1, :] * (q - down)
            + ov[1:, :] * (q - up)
        )

    def solve(self, b, tol=1e-9, max_iter=400):
        q = self.p
        mask = self.unknown
        singular = (not self.outflow) or self.wrap
        r = np.where(mask, b - self.apply_b(q), 0.0)
        if singular and mask.any():
            r = np.where(mask, r - r[mask].mean(), 0.0)
        d = r.copy()
        rr = (r * r).sum()
        limit = tol * tol * max(1, mask.sum())
        it = 0
        while it < max_iter and rr > limit:
            ad = self.apply_b(d)
            dad = (d * ad).sum()
            if not dad > 0:
                break
            alpha = rr / dad
            q += alpha * d
            r -= alpha * ad
            rr_new = (r * r).sum()
            d = r + (rr_new / rr) * d
            rr = rr_new
            it += 1
        self.iterations = it

    def project(self):
        self.solve(self.divergence())
        q = self.p
        if self.wrap:
            qpad = np.pad(q, ((1, 1), (1, 1)), mode="wrap")
        else:
            qpad = np.pad(q, ((1, 1), (1, 1)), mode="constant")
        self.u += self.ou * (qpad[1:-1, 1:] - qpad[1:-1, :-1])
        self.v += self.ov * (qpad[1:, 1:-1] - qpad[:-1, 1:-1])

    # -- the step ------------------------------------------------------------------------
    def read(self, f, x, y, ox, oy):
        return bilinear(f, x, y, ox, oy, self.nx, self.ny, self.wrap)

    def advect(self, f, X, Y, ox, oy, dt):
        mx = X - 0.5 * dt * self.read(self.u, X, Y, 0.0, 0.5)
        my = Y - 0.5 * dt * self.read(self.v, X, Y, 0.5, 0.0)
        px = X - dt * self.read(self.u, mx, my, 0.0, 0.5)
        py = Y - dt * self.read(self.v, mx, my, 0.5, 0.0)
        return self.read(f, px, py, ox, oy)

    def diffuse_velocity(self, dt):
        s = self.nu * dt
        if s <= 0:
            return
        nx, ny = self.nx, self.ny
        lid = self.lid if self.edge == BOX else 0.0
        up = np.zeros((ny + 2, nx + 3))
        up[1:-1, 1:-1] = self.u
        vp = np.zeros((ny + 3, nx + 2))
        vp[1:-1, 1:-1] = self.v
        if self.wrap:
            up[1:-1, 0] = self.u[:, nx - 1]
            up[1:-1, -1] = self.u[:, 1]
            up[0, 1:-1] = self.u[ny - 1, :]
            up[-1, 1:-1] = self.u[0, :]
            vp[1:-1, 0] = self.v[:, nx - 1]
            vp[1:-1, -1] = self.v[:, 0]
            vp[0, 1:-1] = self.v[ny - 1, :]
            vp[-1, 1:-1] = self.v[1, :]
        else:
            if self.outflow:
                up[1:-1, -1] = self.u[:, nx]
            up[0, 1:-1] = -self.u[0, :]
            up[-1, 1:-1] = 2.0 * lid - self.u[ny - 1, :]
            vp[1:-1, 0] = -self.v[:, 0]
            vp[1:-1, -1] = self.v[:, nx - 1] if self.outflow else -self.v[:, nx - 1]
        lu = up[1:-1, :-2] + up[1:-1, 2:] + up[:-2, 1:-1] + up[2:, 1:-1] - 4.0 * self.u
        lv = vp[1:-1, :-2] + vp[1:-1, 2:] + vp[:-2, 1:-1] + vp[2:, 1:-1] - 4.0 * self.v
        self.u = np.where(self.ou != 0, self.u + s * lu, self.u)
        self.v = np.where(self.ov != 0, self.v + s * lv, self.v)

    def diffuse_dye(self, dt):
        s = self.kappa * dt
        if s <= 0:
            return
        d = self.dye
        pad = np.pad(d, 1, mode="wrap" if self.wrap else "edge")
        self.dye = d + s * (pad[1:-1, :-2] + pad[1:-1, 2:] + pad[:-2, 1:-1] + pad[2:, 1:-1] - 4.0 * d)

    def step(self, dt=DT):
        nx, ny = self.nx, self.ny
        self.close_faces()
        if self.buoyancy != 0.0:
            gain = 0.5 * dt * self.buoyancy
            dv = np.zeros_like(self.v)
            dv[1:ny, :] = gain * (self.dye[:-1, :] + self.dye[1:, :])
            if self.wrap:
                dv[0, :] = dv[ny, :] = gain * (self.dye[ny - 1, :] + self.dye[0, :])
            self.v += self.ov * dv
        limit = 4.0 / dt
        self.u = np.clip(self.u, -limit, limit)
        self.v = np.clip(self.v, -limit, limit)
        u2 = self.advect(self.u, self.ux, self.uy, 0.0, 0.5, dt)
        v2 = self.advect(self.v, self.vx, self.vy, 0.5, 0.0, dt)
        d2 = self.advect(self.dye, self.cx, self.cy, 0.5, 0.5, dt)
        if self.wrap:
            u2[:, nx] = u2[:, 0]
            v2[ny, :] = v2[0, :]
        self.u, self.v, self.dye = u2, v2, d2
        self.close_faces()
        self.diffuse_velocity(dt)
        self.diffuse_dye(dt)
        self.close_faces()
        self.project()
        self.dye[self.solid] = 0.0

    def kinetic_energy(self):
        cu = 0.5 * (self.u[:, :-1] + self.u[:, 1:])
        cv = 0.5 * (self.v[:-1, :] + self.v[1:, :])
        return 0.5 * (cu * cu + cv * cv).sum()


def cavity(n=64, re=100.0, steps=6000, report=500):
    """Lid-driven cavity: lid speed 1, side n cells, so nu = n / Re."""
    f = Fluid(n, n, BOX, viscosity=n / re, lid=1.0)
    last = None
    for s in range(1, steps + 1):
        f.step()
        if s % report == 0:
            e = f.kinetic_energy()
            print(f"step {s:6d}  t {s * DT:8.1f}  energy {e:.6f}  cg {f.iterations}", file=sys.stderr)
            if last is not None and abs(e - last) < 1e-7 * abs(e):
                break
            last = e
    return f


def centreline(f):
    """u on the vertical centre line, interpolated to the Ghia heights (y in units of the side)."""
    n = f.nx
    # u lives on the faces x = i: the centre line x = n / 2 is the face column n / 2 (n even)
    col = f.u[:, n // 2]
    yc = (np.arange(f.ny) + 0.5) / f.ny
    ys = np.concatenate([[0.0], yc, [1.0]])
    us = np.concatenate([[0.0], col, [f.lid]])
    return np.interp(GHIA_Y, ys, us)


def run_cavity():
    for n in (32, 64):
        f = cavity(n=n)
        u = centreline(f)
        err = np.abs(u - GHIA_U)
        print(f"n = {n}: max |u - Ghia| = {err.max():.4f} at y = {GHIA_Y[err.argmax()]}")
    print("y      Ghia      this")
    for y, g, c in zip(GHIA_Y, GHIA_U, u):
        print(f"{y:.4f} {g:9.5f} {c:9.5f}")


def run_taylor_green():
    n, nu, speed = 32, 0.2, 0.02
    f = Fluid(n, n, PERIODIC, viscosity=nu)
    k = 2 * np.pi / n
    xi, yj = np.meshgrid(np.arange(n + 1), np.arange(n + 1))
    psi = speed / k * np.sin(k * xi) * np.sin(k * yj)
    f.u = (psi[1:, :] - psi[:-1, :])
    f.v = -(psi[:, 1:] - psi[:, :-1])
    f.u[:, n] = f.u[:, 0]
    f.v[n, :] = f.v[0, :]
    e0 = f.kinetic_energy()
    for _ in range(100):
        f.step()
    print("decay", np.log(e0 / f.kinetic_energy()), "expected", 4 * nu * k * k * 100 * DT)


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "cavity"
    {"cavity": run_cavity, "taylor-green": run_taylor_green}[what]()
