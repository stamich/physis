"""Independent reference for the convection model of physis (numpy, double precision).

It follows the scheme of the Scala model (staggered grid, semi-Lagrangian advection with a
midpoint backtrace, explicit diffusion, pressure projection by conjugate gradients, the
temperature kept as the deviation theta from the conduction profile) but shares no code with it:
whole-array arithmetic, its own bilinear sampling and its own operators. Agreement is of the
physics, not of the bits.

Usage (numpy is the only dependency):
    python tools/convection_reference.py critical   # Ra_c of the continuous problem, two ways
    python tools/convection_reference.py growth     # growth rate of the scheme against Ra
    python tools/convection_reference.py nusselt    # Nu(Ra), periodic rolls, Pr = 1
    python tools/convection_reference.py rolls      # rolls in a box four depths wide, four seeds
    python tools/convection_reference.py ratios     # kinetic energy ratios the Scala tests use
    python tools/convection_reference.py onset      # time the Onset preset needs to show rolls

The numbers it prints are the ones the tolerances in the Scala tests were chosen from.
"""

import sys

import numpy as np

DT = 0.25
MAX_DIFFUSION = 0.8


def coefficients(ra, pr, ny):
    nu = MAX_DIFFUSION if pr >= 1 else MAX_DIFFUSION * pr
    kappa = MAX_DIFFUSION / pr if pr >= 1 else MAX_DIFFUSION
    accel = ra * nu * kappa / ny**3
    return nu, kappa, accel


def bilinear(f, x, y, ox, oy, nx, wrap_x):
    """f[j, i] sits at (i + ox, j + oy). x wraps with period nx or is clamped; y is clamped."""
    gx = x - ox
    gy = y - oy
    if wrap_x:
        gx = gx - nx * np.floor(gx / nx)
        i0 = np.floor(gx).astype(int)
        tx = gx - i0
        i0 %= nx
        i1 = (i0 + 1) % nx
    else:
        lx = f.shape[1] - 1
        gx = np.clip(gx, 0.0, lx)
        i0 = np.minimum(np.floor(gx), max(lx - 1, 0)).astype(int)
        tx = gx - i0
        i1 = np.minimum(i0 + 1, lx)
    ly = f.shape[0] - 1
    gy = np.clip(gy, 0.0, ly)
    j0 = np.minimum(np.floor(gy), max(ly - 1, 0)).astype(int)
    ty = gy - j0
    j1 = np.minimum(j0 + 1, ly)
    bottom = f[j0, i0] * (1 - tx) + f[j0, i1] * tx
    top = f[j1, i0] * (1 - tx) + f[j1, i1] * tx
    return bottom * (1 - ty) + top * ty


class Convection:
    def __init__(self, nx, ny, ra, pr=1.0, periodic=True, kick=0.01, seed=1):
        self.nx, self.ny, self.periodic = nx, ny, periodic
        self.nu, self.kappa, self.accel = coefficients(ra, pr, ny)
        self.u = np.zeros((ny, nx + 1))
        self.v = np.zeros((ny + 1, nx))
        rng = np.random.default_rng(seed)
        self.th = kick * (2.0 * rng.random((ny, nx)) - 1.0)
        self.p = np.zeros((ny, nx))
        ou = np.zeros((ny, nx + 1))
        ov = np.zeros((ny + 1, nx))
        ou[:, 1:nx] = 1.0
        if periodic:
            ou[:, 0] = ou[:, nx] = 1.0
        ov[1:ny, :] = 1.0
        self.ou, self.ov = ou, ov
        self.ux, self.uy = np.meshgrid(np.arange(nx + 1), np.arange(ny) + 0.5)
        self.vx, self.vy = np.meshgrid(np.arange(nx) + 0.5, np.arange(ny + 1))
        self.cx, self.cy = np.meshgrid(np.arange(nx) + 0.5, np.arange(ny) + 0.5)

    # -- projection ------------------------------------------------------------------------
    def apply_b(self, q):
        if self.periodic:
            left, right = np.roll(q, 1, axis=1), np.roll(q, -1, axis=1)
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
        return (ou[:, :-1] * (q - left) + ou[:, 1:] * (q - right)
                + ov[:-1, :] * (q - down) + ov[1:, :] * (q - up))

    def project(self, tol=1e-9, max_iter=1000):
        b = (self.u[:, 1:] - self.u[:, :-1]) + (self.v[1:, :] - self.v[:-1, :])
        q = self.p
        r = b - self.apply_b(q)
        r -= r.mean()
        d = r.copy()
        rr = (r * r).sum()
        limit = tol * tol * r.size
        it = 0
        while it < max_iter and rr > limit:
            ad = self.apply_b(d)
            alpha = rr / (d * ad).sum()
            q += alpha * d
            r -= alpha * ad
            rr_new = (r * r).sum()
            d = r + (rr_new / rr) * d
            rr = rr_new
            it += 1
        if self.periodic:
            qp = np.pad(q, ((0, 0), (1, 1)), mode="wrap")
        else:
            qp = np.pad(q, ((0, 0), (1, 1)), mode="constant")
        qy = np.pad(q, ((1, 1), (0, 0)), mode="constant")
        self.u += self.ou * (qp[:, 1:] - qp[:, :-1])
        self.v += self.ov * (qy[1:, :] - qy[:-1, :])
        return it

    # -- the step --------------------------------------------------------------------------
    def read(self, f, x, y, ox, oy):
        return bilinear(f, x, y, ox, oy, self.nx, self.periodic)

    def advect(self, f, X, Y, ox, oy, dt):
        mx = X - 0.5 * dt * self.read(self.u, X, Y, 0.0, 0.5)
        my = Y - 0.5 * dt * self.read(self.v, X, Y, 0.5, 0.0)
        px = X - dt * self.read(self.u, mx, my, 0.0, 0.5)
        py = Y - dt * self.read(self.v, mx, my, 0.5, 0.0)
        return self.read(f, px, py, ox, oy)

    def diffuse_velocity(self, s):
        nx, ny = self.nx, self.ny
        up = np.zeros((ny + 2, nx + 3))
        up[1:-1, 1:-1] = self.u
        up[0, 1:-1] = -self.u[0, :]
        up[-1, 1:-1] = -self.u[ny - 1, :]
        vp = np.zeros((ny + 3, nx + 2))
        vp[1:-1, 1:-1] = self.v
        if self.periodic:
            up[1:-1, 0] = self.u[:, nx - 1]
            up[1:-1, -1] = self.u[:, 1]
            vp[1:-1, 0] = self.v[:, nx - 1]
            vp[1:-1, -1] = self.v[:, 0]
        else:
            vp[1:-1, 0] = -self.v[:, 0]
            vp[1:-1, -1] = -self.v[:, nx - 1]
        lu = up[1:-1, :-2] + up[1:-1, 2:] + up[:-2, 1:-1] + up[2:, 1:-1] - 4.0 * up[1:-1, 1:-1]
        lv = vp[1:-1, :-2] + vp[1:-1, 2:] + vp[:-2, 1:-1] + vp[2:, 1:-1] - 4.0 * vp[1:-1, 1:-1]
        self.u = np.where(self.ou != 0, self.u + s * lu, self.u)
        self.v = np.where(self.ov != 0, self.v + s * lv, self.v)

    def diffuse_theta(self, s):
        th = self.th
        pad = np.zeros((self.ny + 2, self.nx + 2))
        pad[1:-1, 1:-1] = th
        pad[0, 1:-1] = -th[0, :]
        pad[-1, 1:-1] = -th[-1, :]
        if self.periodic:
            pad[1:-1, 0] = th[:, -1]
            pad[1:-1, -1] = th[:, 0]
        else:
            pad[1:-1, 0] = th[:, 0]
            pad[1:-1, -1] = th[:, -1]
        lap = pad[1:-1, :-2] + pad[1:-1, 2:] + pad[:-2, 1:-1] + pad[2:, 1:-1] - 4.0 * th
        self.th = th + s * lap

    def step(self, dt=DT):
        nx, ny = self.nx, self.ny
        self.u[self.ou == 0] = 0.0
        self.v[self.ov == 0] = 0.0
        # buoyancy on the interior horizontal faces
        self.v[1:ny, :] += dt * self.accel * 0.5 * (self.th[:-1, :] + self.th[1:, :]) * self.ov[1:ny, :]
        limit = 4.0 / dt
        self.u = np.clip(self.u, -limit, limit)
        self.v = np.clip(self.v, -limit, limit)
        u_new = self.advect(self.u, self.ux, self.uy, 0.0, 0.5, dt)
        v_new = self.advect(self.v, self.vx, self.vy, 0.5, 0.0, dt)
        th_new = self.advect(self.th, self.cx, self.cy, 0.5, 0.5, dt)
        if self.periodic:
            u_new[:, nx] = u_new[:, 0]
        self.u, self.v, self.th = u_new, v_new, th_new
        self.u[self.ou == 0] = 0.0
        self.v[self.ov == 0] = 0.0
        self.th += dt / ny * 0.5 * (self.v[:-1, :] + self.v[1:, :])
        self.diffuse_velocity(self.nu * dt)
        self.diffuse_theta(self.kappa * dt)
        self.u[self.ou == 0] = 0.0
        self.v[self.ov == 0] = 0.0
        return self.project()

    # -- measurements ----------------------------------------------------------------------
    def kinetic_energy(self):
        a = 0.5 * (self.u[:, :-1] + self.u[:, 1:])
        b = 0.5 * (self.v[:-1, :] + self.v[1:, :])
        return 0.5 * (a * a + b * b).sum()

    def nusselt(self):
        w = 0.5 * (self.v[:-1, :] + self.v[1:, :])
        return 1.0 + self.ny * (w * self.th).mean() / self.kappa

    def nusselt_floor(self):
        return 1.0 - 2.0 * self.ny * self.th[0, :].mean()

    def nusselt_lid(self):
        return 1.0 + 2.0 * self.ny * self.th[-1, :].mean()

    def amplitude(self):
        """Size of the mode that convection grows: rms of the vertical velocity."""
        w = 0.5 * (self.v[:-1, :] + self.v[1:, :])
        return np.sqrt((w * w).mean())


# -- the continuous problem ------------------------------------------------------------------
def critical_rayleigh(free_slip=False, n=120):
    """Smallest Ra at which a mode of wave number k grows, minimised over k, from the linear
    problem  (D^2 - k^2)^2 W = k^2 Ra Theta,  (D^2 - k^2) Theta = -W  on (0, 1)
    by second-order finite differences. Pr plays no role at the onset of steady convection.
    Free-slip walls: W = W'' = 0. Rigid walls: W = W' = 0 (the ghost value beside the wall
    equals the value inside)."""
    h = 1.0 / n
    m = n - 1
    eye = np.eye(m)
    d2 = (np.diag(-2.0 * np.ones(m)) + np.diag(np.ones(m - 1), 1) + np.diag(np.ones(m - 1), -1)) / h**2
    if free_slip:
        d4 = d2 @ d2
    else:
        d4 = (np.diag(6.0 * np.ones(m)) - 4.0 * (np.diag(np.ones(m - 1), 1) + np.diag(np.ones(m - 1), -1))
              + np.diag(np.ones(m - 2), 2) + np.diag(np.ones(m - 2), -2))
        d4[0, 0] += 1.0
        d4[-1, -1] += 1.0
        d4 /= h**4

    def ra_of(k):
        a = d4 - 2.0 * k * k * d2 + k**4 * eye
        mu = np.linalg.eigvals((d2 - k * k * eye) @ a).real
        ra = -mu / (k * k)
        ra = ra[ra > 0]
        return float(ra.min()) if ra.size else np.inf

    ks = np.linspace(1.5, 4.5, 61)
    ras = np.array([ra_of(k) for k in ks])
    i = int(np.argmin(ras))
    return float(ras[i]), float(ks[i])


def measure_growth(ra, nx=48, ny=24, pr=1.0, steps=8000, kick=1e-3):
    """Exponential growth rate of the vertical velocity in units of kappa / H^2, fitted where the
    amplitude is between 2e-4 and a tenth of its final value: after the first transient of the
    random start and before the convection saturates."""
    c = Convection(nx, ny, ra, pr, True, kick)
    t, a = [], []
    for n in range(steps):
        c.step()
        if n % 25 == 0:
            t.append(n * DT)
            a.append(c.amplitude())
    t, a = np.array(t), np.array(a)
    window = (a > 2e-4) & (a < 0.1 * a.max()) & (t > t[-1] * 0.1)
    if window.sum() < 5:  # decaying: use the second half, where the slowest mode is left
        window = t > t[-1] * 0.5
    return np.polyfit(t[window], np.log(a[window]), 1)[0] * ny**2 / c.kappa


def run_critical():
    for free, name in ((True, "free-slip"), (False, "rigid")):
        ra, k = critical_rayleigh(free)
        print(f"{name}: Ra_c = {ra:.1f} at k = {k:.3f}")
    print("exact: rigid 1707.76 at k = 3.117, free-slip 27 pi^4 / 4 = %.1f at k = pi/sqrt(2) = %.3f"
          % (27 * np.pi**4 / 4, np.pi / np.sqrt(2)))


def run_growth():
    # nx / ny = 2 gives the wavelength 2 H (k = pi / H, a little above the critical 3.117 / H)
    for ra in (1200, 1500, 1700, 1900, 2200, 2600, 3500):
        print(f"Ra = {ra}: growth rate {measure_growth(ra):+.3f} kappa/H^2")


def run_nusselt(pr=1.0):
    for ra in (3000, 5000, 10000, 20000, 50000):
        ny = 24
        c = Convection(2 * ny, ny, ra, pr, True, 1e-2)
        n_steps = 16000
        for _ in range(n_steps):
            c.step()
        vals = []
        for _ in range(400):
            c.step()
            vals.append((c.nusselt(), c.nusselt_floor(), c.nusselt_lid()))
        v = np.mean(vals, axis=0)
        print(f"Ra = {ra}: Nu volume {v[0]:.3f}  floor {v[1]:.3f}  lid {v[2]:.3f}")


def run_rolls():
    """Rolls in a periodic box four depths wide settle to two pairs: a wavelength of 2.0 depths
    (the linear theory gives 2 pi / 3.117 = 2.016)."""
    for seed in (1, 2, 3, 4):
        c = Convection(96, 24, 3000, 1.0, True, 1e-2, seed)
        for _ in range(14000):
            c.step()
        w = c.v[12, :]
        s = np.sign(w)
        changes = int((s != np.roll(s, 1)).sum())
        print(f"seed {seed}: {changes} sign changes of w at mid-depth, wavelength "
              f"{2 * 96 / changes / 24:.2f} H, Nu {c.nusselt():.3f}")


def run_ratios():
    """Kinetic energy at 3000 steps over that at 2000 steps in a layer two depths wide."""
    for ra in (1200, 1500, 2200):
        c = Convection(48, 24, ra, 1.0, True, 1e-2)
        ke = {}
        for n in range(1, 3001):
            c.step()
            if n in (2000, 3000):
                ke[n] = c.kinetic_energy()
        print(f"Ra = {ra}: KE(3000) / KE(2000) = {ke[3000] / ke[2000]:.3f}")


def run_onset():
    for ra in (2500, 3000):
        c = Convection(96, 48, ra, 1.0, True, 1e-3)
        for n in range(1, 40001):
            c.step()
            if n % 2000 == 0:
                print(f"Ra {ra}: step {n}: rms w {c.amplitude():.3e}  Nu {c.nusselt():.3f}")


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "critical"
    {"critical": run_critical, "growth": run_growth, "nusselt": run_nusselt, "rolls": run_rolls,
     "ratios": run_ratios, "onset": run_onset}[what]()
