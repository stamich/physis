"""Independent reference for the spherical shallow-water scheme of physis (numpy, double precision).

It follows the scheme of the Scala model with `geometry = 1` but shares no code with it: whole-array
arithmetic and its own operators. The grid is longitude-latitude, `nx` columns over 360 degrees and
`ny` rows over the band between -latmax and +latmax (row 0 is the north); the surface eta is at the
cell centres, the eastward velocity u on the faces between columns and the southward velocity v on the
faces between rows (positive v flows south, as in the plane model). The faces on the edge of the band
are walls; longitude wraps. The scheme per step:

  1. eta -= dt * divergence of the flux (h u dy through the vertical faces, h v R cos(lat) dlon through
     the horizontal ones, divided by the area of the cell), so sum(eta * area) is exact;
  2. (nonlinear) u and v are advected by the flow, semi-Lagrangian, midpoint rule, bilinear, in index
     space with the speeds divided by the local cell size;
  3. u += dt (-g d(eta)/dx - (f + u tan(lat) / R) v - r u), then v += dt (-g d(eta)/dy +
     (f + u tan(lat) / R) u - r v) with the new u;
  4. a zonal filter on the rows poleward of `polar` degrees: 1-2-1 smoothing of eta, u and v along the
     row, ceil(((cos polar / cos lat)^2 - 1) / 2) times, which keeps the step stable where the cells are
     much narrower than the step allows (a band to 89 degrees at 1 degree resolution).

Usage (numpy is the only dependency):
    python tools/shallow_sphere_reference.py kelvin   # the equatorial Kelvin wave travels at sqrt(g h)
    python tools/shallow_sphere_reference.py zonal    # Williamson et al. test 2, a steady zonal flow
    python tools/shallow_sphere_reference.py rh       # Williamson et al. test 6, the Rossby-Haurwitz wave
    python tools/shallow_sphere_reference.py polar    # the zonal filter lets the band reach the pole
    python tools/shallow_sphere_reference.py all

The numbers it prints are the ones the tolerances in the Scala tests were chosen from.
"""

import math
import sys

import numpy as np

G = 9.81
RADIUS = 6.371e6
OMEGA = 7.292e-5


class Sphere:
    def __init__(self, nx, ny, latmax=80.0, depth=4000.0, g=G, radius=RADIUS, omega=OMEGA,
                 nonlinear=True, friction=0.0, polar=None):
        self.nx, self.ny = nx, ny
        self.g, self.R, self.depth = g, radius, depth
        self.nonlinear, self.r = nonlinear, friction
        self.dlam = 2.0 * math.pi / nx
        self.dphi = 2.0 * math.radians(latmax) / ny
        top = math.radians(latmax)
        self.lat_c = top - (np.arange(ny) + 0.5) * self.dphi
        self.lat_f = top - np.arange(ny + 1) * self.dphi
        self.dy = radius * self.dphi
        self.dxc = radius * np.cos(self.lat_c) * self.dlam
        self.lenf = radius * np.cos(self.lat_f) * self.dlam
        self.area = radius ** 2 * self.dlam * (np.sin(self.lat_f[:-1]) - np.sin(self.lat_f[1:]))
        self.fc = 2.0 * omega * np.sin(self.lat_c)
        self.fv = 2.0 * omega * np.sin(self.lat_f)
        self.tanc = np.tan(self.lat_c) / radius
        self.tanf = np.tan(self.lat_f) / radius
        # 1 / (cell width) at y = 0, 0.5, 1, ..., ny (index space), for the advection
        ys = np.arange(2 * ny + 1) / 2.0
        self.y_table = ys
        self.invdx_table = 1.0 / (radius * np.cos(top - ys * self.dphi) * self.dlam)
        self.shape = np.ones((ny, nx))
        self.eta = np.zeros((ny, nx))
        self.u = np.zeros((ny, nx))        # u[j, i]: the face west of column i
        self.v = np.zeros((ny + 1, nx))    # v[j, i]: the face north of row j
        self.passes_c = np.zeros(ny, int)
        self.passes_f = np.zeros(ny + 1, int)
        if polar is not None:
            cp = math.cos(math.radians(polar))
            self.passes_c = np.maximum(0, np.ceil(0.5 * ((cp / np.cos(self.lat_c)) ** 2 - 1.0))).astype(int)
            self.passes_f = np.maximum(0, np.ceil(0.5 * ((cp / np.cos(self.lat_f)) ** 2 - 1.0))).astype(int)

    # --- measurements -------------------------------------------------------------------------
    def volume(self):
        return float(np.sum(self.eta * self.area[:, None]))

    def thickness(self):
        h = self.depth * self.shape + (self.eta if self.nonlinear else 0.0)
        return np.maximum(0.0, h)

    def energy(self):
        h = self.thickness()
        hu = 0.5 * (h + np.roll(h, 1, axis=1))
        hv = np.zeros((self.ny + 1, self.nx))
        hv[1:-1] = 0.5 * (h[1:] + h[:-1])
        pot = 0.5 * self.g * np.sum(self.eta ** 2 * self.area[:, None])
        kin = 0.5 * np.sum(hu * self.u ** 2 * self.area[:, None])
        kin += 0.5 * np.sum(hv * self.v ** 2 * (self.lenf * self.dy)[:, None])
        return pot + kin

    # --- one step -----------------------------------------------------------------------------
    def _continuity(self, dt):
        h = self.thickness()
        hu = 0.5 * (h + np.roll(h, 1, axis=1))
        hv = np.zeros((self.ny + 1, self.nx))
        hv[1:-1] = 0.5 * (h[1:] + h[:-1])
        fu = hu * self.u
        fv = hv * self.v
        div = ((np.roll(fu, -1, axis=1) - fu) * self.dy
               + fv[1:] * self.lenf[1:, None] - fv[:-1] * self.lenf[:-1, None]) / self.area[:, None]
        self.eta -= dt * div

    def _invdx(self, y):
        return np.interp(y, self.y_table, self.invdx_table)

    def _read_u(self, x, y):
        gx = np.mod(x, self.nx)
        gy = np.clip(y - 0.5, 0.0, self.ny - 1.0)
        return _bilinear(self.u, gx, gy, periodic=True)

    def _read_v(self, x, y):
        gx = np.mod(x - 0.5, self.nx)
        gy = np.clip(y, 0.0, float(self.ny))
        return _bilinear(self.v, gx, gy, periodic=True)

    def _velocity(self, x, y):
        return self._read_u(x, y) * self._invdx(y), self._read_v(x, y) / self.dy

    def _advect(self, dt):
        nx, ny = self.nx, self.ny
        xi = np.arange(nx)[None, :] * np.ones((ny, 1))
        yu = (np.arange(ny)[:, None] + 0.5) * np.ones((1, nx))
        new_u = self._advect_one(self.u, xi, yu, 0.0, 0.5, dt, ny)
        xv = (np.arange(nx)[None, :] + 0.5) * np.ones((ny + 1, 1))
        yv = np.arange(ny + 1)[:, None] * np.ones((1, nx))
        new_v = self._advect_one(self.v, xv, yv, 0.5, 0.0, dt, ny + 1)
        new_v[0] = 0.0
        new_v[ny] = 0.0
        self.u, self.v = new_u, new_v

    def _advect_one(self, src, x, y, ox, oy, dt, rows):
        ux, vy = self._velocity(x, y)
        mx = x - 0.5 * dt * ux
        my = y - 0.5 * dt * vy
        ux, vy = self._velocity(mx, my)
        px = x - dt * ux
        py = y - dt * vy
        gx = np.mod(px - ox, self.nx)
        gy = np.clip(py - oy, 0.0, rows - 1.0)
        return _bilinear(src, gx, gy, periodic=True)

    def _momentum(self, dt):
        g, R, r = self.g, self.R, self.r
        nx, ny = self.nx, self.ny
        v = self.v
        vat = 0.25 * (np.roll(v[:-1], 1, axis=1) + v[:-1] + np.roll(v[1:], 1, axis=1) + v[1:])
        fe = self.fc[:, None] + (self.u * self.tanc[:, None] if self.nonlinear else 0.0)
        self.u = self.u + dt * (-g * (self.eta - np.roll(self.eta, 1, axis=1)) / self.dxc[:, None]
                                - fe * vat - r * self.u)
        u = self.u
        uat = np.zeros((ny + 1, nx))
        ur = np.roll(u, -1, axis=1)
        uat[1:-1] = 0.25 * (u[:-1] + ur[:-1] + u[1:] + ur[1:])
        fe2 = self.fv[:, None] + (uat * self.tanf[:, None] if self.nonlinear else 0.0)
        dv = dt * (-g * (self.eta[1:] - self.eta[:-1]) / self.dy + fe2[1:-1] * uat[1:-1]
                   - r * self.v[1:-1])
        self.v = self.v.copy()
        self.v[1:-1] += dv

    def _filter(self):
        for j in range(self.ny):
            for _ in range(self.passes_c[j]):
                self.eta[j] = _smooth(self.eta[j])
                self.u[j] = _smooth(self.u[j])
        for j in range(1, self.ny):
            for _ in range(self.passes_f[j]):
                self.v[j] = _smooth(self.v[j])

    def step(self, dt):
        self._continuity(dt)
        if self.nonlinear:
            self._advect(dt)
        self._momentum(dt)
        if self.passes_c.any() or self.passes_f.any():
            self._filter()

    def run(self, seconds, dt=50.0):
        for _ in range(int(round(seconds / dt))):
            self.step(dt)


def _smooth(row):
    return 0.25 * np.roll(row, 1) + 0.5 * row + 0.25 * np.roll(row, -1)


def _bilinear(f, gx, gy, periodic):
    rows, cols = f.shape
    i0 = np.floor(gx).astype(int)
    tx = gx - i0
    i0 = np.mod(i0, cols)
    i1 = np.mod(i0 + 1, cols)
    j0 = np.minimum(np.floor(gy).astype(int), rows - 2) if rows > 1 else np.zeros_like(i0)
    j0 = np.maximum(j0, 0)
    j1 = np.minimum(j0 + 1, rows - 1)
    ty = gy - j0
    a = f[j0, i0] * (1 - tx) + f[j0, i1] * tx
    b = f[j1, i0] * (1 - tx) + f[j1, i1] * tx
    return a * (1 - ty) + b * ty


# --- the tests ----------------------------------------------------------------------------------
def zonal_flow(s, u0, h0):
    """Williamson test 2 with alpha = 0: u = u0 cos(lat), h = h0 - (R omega u0 + u0^2/2) sin^2(lat) / g."""
    k = s.R * OMEGA * u0 + 0.5 * u0 ** 2
    s.eta[:] = (-k * np.sin(s.lat_c) ** 2 / s.g)[:, None]
    s.u[:] = (u0 * np.cos(s.lat_c))[:, None]
    s.v[:] = 0.0
    return lambda lat: -k * np.sin(lat) ** 2 / s.g


def rossby_haurwitz(s, h0, n=4, w=7.848e-6, k=7.848e-6):
    """Williamson test 6: wave number n; returns nothing, sets eta and u, v (v is southward)."""
    R, om, g = s.R, OMEGA, s.g

    def uv(lat, lam):
        c, sn = np.cos(lat), np.sin(lat)
        u = R * w * c + R * k * c ** (n - 1) * (n * sn ** 2 - c ** 2) * np.cos(n * lam)
        vn = -R * k * n * c ** (n - 1) * sn * np.sin(n * lam)
        return u, -vn

    def height(lat, lam):
        c = np.cos(lat)
        a = (w / 2.0) * (2.0 * om + w) * c ** 2 + 0.25 * k ** 2 * c ** (2 * n) * (
            (n + 1) * c ** 2 + (2 * n ** 2 - n - 2) - 2 * n ** 2 * c ** (-2))
        b = (2.0 * (om + w) * k / ((n + 1) * (n + 2))) * c ** n * (
            (n ** 2 + 2 * n + 2) - (n + 1) ** 2 * c ** 2)
        cc = 0.25 * k ** 2 * c ** (2 * n) * ((n + 1) * c ** 2 - (n + 2))
        return (R ** 2 * (a + b * np.cos(n * lam) + cc * np.cos(2 * n * lam))) / g

    lam_c = (np.arange(s.nx) + 0.5) * s.dlam
    lam_u = np.arange(s.nx) * s.dlam
    LC, LATC = np.meshgrid(lam_c, s.lat_c)
    s.eta[:] = height(LATC, LC) - 0.0
    LU, LATU = np.meshgrid(lam_u, s.lat_c)
    s.u[:] = uv(LATU, LU)[0]
    LV, LATV = np.meshgrid(lam_c, s.lat_f)
    s.v[:] = uv(LATV, LV)[1]
    s.v[0] = 0.0
    s.v[-1] = 0.0
    return height


def phase(row, m):
    return np.angle(np.fft.rfft(row)[m])


def cmd_kelvin():
    nx, ny = 180, 80
    s = Sphere(nx, ny, latmax=80.0, depth=4000.0, nonlinear=False)
    c = math.sqrt(G * 4000.0)
    # a bump on the equator, 20 degrees wide (rows ny/2 - 1, ny/2 straddle the equator)
    lam = (np.arange(nx) + 0.5) * s.dlam
    lat = s.lat_c[:, None]
    s.eta[:] = 1.0 * np.exp(-((lam[None, :] - math.pi) ** 2 + 4.0 * lat ** 2) / (2 * math.radians(10) ** 2))
    prev = None
    t_end = 0.4 * 2 * math.pi * RADIUS / c
    s.run(t_end)
    eq = 0.5 * (s.eta[ny // 2 - 1] + s.eta[ny // 2])
    # east and west moving crests: find the largest value east of the start and compare distance
    east = np.argmax(np.where(np.arange(nx) > nx // 2, eq, -1))
    dist = (east + 0.5 - nx / 2) * s.dlam * RADIUS
    print(f"kelvin: crest at {math.degrees((east + 0.5) * s.dlam) - 180:.1f} deg east of the start, "
          f"speed {dist / t_end:.2f} m/s against c = {c:.2f} ({dist / t_end / c:.4f})")


def cmd_zonal():
    h0 = 2.94e4 / G
    u0 = 2.0 * math.pi * RADIUS / (12 * 86400.0)
    for nx, ny in ((180, 80), (90, 40)):
        s = Sphere(nx, ny, latmax=80.0, depth=h0, nonlinear=True)
        exact = zonal_flow(s, u0, h0)
        e0 = s.eta.copy()
        v0 = s.volume()
        w = s.area[:, None] * np.ones((1, nx))
        for day in (1, 5):
            s.run(86400.0 * (1 if day == 1 else 4))
            err = np.sqrt(np.sum(w * (s.eta - e0) ** 2)) / np.sqrt(np.sum(w * (e0 + h0) ** 2))
            print(f"zonal {nx}x{ny}: day {day}: l2 error of h {err:.3e}, volume drift "
                  f"{(s.volume() - v0) / abs(v0):.2e}, max |v| {abs(s.v).max():.3f} m/s")


def cmd_rh():
    h0 = 8000.0
    nx, ny = 180, 80
    s = Sphere(nx, ny, latmax=80.0, depth=h0, nonlinear=True)
    height = rossby_haurwitz(s, h0)
    s.eta -= 0.0
    lat45 = int(np.argmin(abs(s.lat_c - math.radians(45))))
    p0 = phase(s.eta[lat45] , 4)
    om, n, w, k = OMEGA, 4, 7.848e-6, 7.848e-6
    nu = (n * (3 + n) * w - 2 * om) / ((1 + n) * (2 + n))
    for days in (1, 2, 3):
        s.run(86400.0)
        p = phase(s.eta[lat45], 4)
        shift = -(p - p0) / 4.0  # eastward shift in radians of longitude
        shift = (shift + math.pi / 4) % (math.pi / 2) - math.pi / 4
        print(f"rh: day {days}: pattern moved {math.degrees(shift):.2f} deg east, theory "
              f"{math.degrees(nu * 86400 * days):.2f} deg ({shift / (nu * 86400 * days):.3f})")


def cmd_polar():
    nx = 360
    for ny, latmax, polar in ((170, 85.0, None), (176, 88.0, None), (176, 88.0, 70.0), (178, 89.0, 70.0)):
        s = Sphere(nx, ny, latmax=latmax, depth=4000.0, nonlinear=True, polar=polar)
        rng = np.random.default_rng(1)
        s.eta[:] = 0.2 * np.exp(-((np.arange(nx)[None, :] - nx / 2) ** 2 + (np.arange(ny)[:, None] - ny / 2) ** 2) / 50.0)
        s.eta += 1e-3 * rng.standard_normal(s.eta.shape)
        e0, v0 = s.energy(), s.volume()
        ok, k = True, 0
        for k in range(800):
            s.step(50.0)
            if not np.isfinite(s.eta).all() or abs(s.eta).max() > 1e3:
                ok = False
                break
        print(f"polar: band {latmax} polar {polar}: "
              + (f"stable, energy {s.energy() / e0:.3f} of the start, volume drift {(s.volume() - v0) / abs(v0):.1e}"
                 if ok else f"blew up at step {k}"))


if __name__ == "__main__":
    cmds = {"kelvin": cmd_kelvin, "zonal": cmd_zonal, "rh": cmd_rh, "polar": cmd_polar}
    want = sys.argv[1] if len(sys.argv) > 1 else "all"
    for name in (cmds if want == "all" else [want]):
        cmds[name]()
