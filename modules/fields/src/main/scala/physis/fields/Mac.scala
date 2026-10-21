package physis.fields

/**
 * The staggered ("MAC") layout of a flow on `nx` by `ny` cells: pressure and dye at the cell
 * centres, the horizontal velocity `u` on the vertical faces and the vertical velocity `v` on
 * the horizontal faces. Face `u(i, j)` lies between cells `(i - 1, j)` and `(i, j)` at the
 * position `(i, j + 0.5)`; face `v(i, j)` between `(i, j - 1)` and `(i, j)` at `(i + 0.5, j)`.
 * So `u` has `nx + 1` columns and `v` has `ny + 1` rows. On a periodic axis the last face is a
 * copy of the first.
 */
final class MacGrid(val nx: Int, val ny: Int, val h: Double = 1.0):
  /** The cells, for pressure, dye and anything else at the centres. */
  val cells: Grid2D = Grid2D(nx, ny, h)

  /** The vertical faces, which carry `u`. */
  val uFaces: Grid2D = Grid2D(nx + 1, ny, h)

  /** The horizontal faces, which carry `v`. */
  val vFaces: Grid2D = Grid2D(nx, ny + 1, h)

object Mac:
  /** Where the samples of `u` sit, relative to the lower left corner of their cell. */
  val UOffsetX: Double = 0.0
  val UOffsetY: Double = 0.5

  /** Where the samples of `v` sit. */
  val VOffsetX: Double = 0.5
  val VOffsetY: Double = 0.0

  /** `out = du/dx + dv/dy` in every cell: the net flow out of it, per unit area. */
  def divergence(u: Field2D, v: Field2D, out: Field2D): Unit =
    val g = out.grid
    val inv = 1.0 / g.h
    var j = 0
    while j < g.ny do
      var i = 0
      while i < g.nx do
        out(i, j) = ((u(i + 1, j) - u(i, j)) + (v(i, j + 1) - v(i, j))) * inv
        i += 1
      j += 1

  /**
   * The gradient of the cell field `p` on the faces: `gu(i, j) = (p(i, j) - p(i - 1, j)) / h`
   * and likewise `gv`. A face on the border has one cell only; it gets the difference with
   * the cell on the other side if the axis is periodic, and 0 otherwise.
   */
  def gradient(p: Field2D, gu: Field2D, gv: Field2D, wrapX: Boolean, wrapY: Boolean): Unit =
    val g = p.grid
    val inv = 1.0 / g.h
    var j = 0
    while j < g.ny do
      var i = 0
      while i <= g.nx do
        gu(i, j) =
          if i > 0 && i < g.nx then (p(i, j) - p(i - 1, j)) * inv
          else if wrapX then (p(0, j) - p(g.nx - 1, j)) * inv
          else 0.0
        i += 1
      j += 1
    j = 0
    while j <= g.ny do
      var i = 0
      while i < g.nx do
        gv(i, j) =
          if j > 0 && j < g.ny then (p(i, j) - p(i, j - 1)) * inv
          else if wrapY then (p(i, 0) - p(i, g.ny - 1)) * inv
          else 0.0
        i += 1
      j += 1

  /**
   * The velocity that a stream function `psi` (at the corners of the cells, `(nx + 1)` by
   * `(ny + 1)`) induces: `u = dpsi/dy`, `v = -dpsi/dx`, each as the difference of the two corners
   * of its face. Its divergence is zero to rounding, whatever `psi` is.
   */
  def curl(psi: Field2D, u: Field2D, v: Field2D): Unit =
    val inv = 1.0 / psi.grid.h
    var j = 0
    while j < u.ny do
      var i = 0
      while i < u.nx do
        u(i, j) = (psi(i, j + 1) - psi(i, j)) * inv
        i += 1
      j += 1
    j = 0
    while j < v.ny do
      var i = 0
      while i < v.nx do
        v(i, j) = -(psi(i + 1, j) - psi(i, j)) * inv
        i += 1
      j += 1

  private def centreU(u: Field2D, i: Int, j: Int): Double = 0.5 * (u(i, j) + u(i + 1, j))
  private def centreV(v: Field2D, i: Int, j: Int): Double = 0.5 * (v(i, j) + v(i, j + 1))

  /**
   * The vorticity `dv/dx - du/dy` at the cell centres, from the velocities averaged to the
   * centres and central differences (one-sided at a border that does not wrap).
   */
  def vorticity(
      u: Field2D,
      v: Field2D,
      out: Field2D,
      wrapX: Boolean,
      wrapY: Boolean
  ): Unit =
    val g = out.grid
    val nx = g.nx
    val ny = g.ny
    var j = 0
    while j < ny do
      val jm = if j > 0 then j - 1 else if wrapY then ny - 1 else 0
      val jp = if j < ny - 1 then j + 1 else if wrapY then 0 else ny - 1
      val dy = if jp == jm then 1.0 else if jp - jm == 2 || jm - jp == ny - 2 then 2.0 else 1.0
      var i = 0
      while i < nx do
        val im = if i > 0 then i - 1 else if wrapX then nx - 1 else 0
        val ip = if i < nx - 1 then i + 1 else if wrapX then 0 else nx - 1
        val dx = if ip == im then 1.0 else if ip - im == 2 || im - ip == nx - 2 then 2.0 else 1.0
        val dvdx = (centreV(v, ip, j) - centreV(v, im, j)) / (dx * g.h)
        val dudy = (centreU(u, i, jp) - centreU(u, i, jm)) / (dy * g.h)
        out(i, j) = dvdx - dudy
        i += 1
      j += 1

  /** The speed squared at the centres summed over the cells, times `h²` and one half. */
  def kineticEnergy(u: Field2D, v: Field2D, cells: Grid2D): Double =
    var sum = 0.0
    var j = 0
    while j < cells.ny do
      var i = 0
      while i < cells.nx do
        val a = centreU(u, i, j)
        val b = centreV(v, i, j)
        sum += a * a + b * b
        i += 1
      j += 1
    0.5 * sum * cells.h * cells.h
