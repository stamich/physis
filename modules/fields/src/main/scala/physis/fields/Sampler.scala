package physis.fields

/**
 * Reads a field at positions between its cells, on a periodic domain: the way an agent senses
 * what lies at its place. Positions are in cells; cell `(i, j)` has its centre at
 * `(i + 0.5, j + 0.5)`, as in [[Brush]]. Neither the halo nor a boundary condition is involved:
 * the domain wraps, so the result does not depend on what the halo holds. No call allocates.
 */
object Sampler:

  /** `v` brought into `0 until n` by adding or removing whole periods. */
  def wrap(v: Double, n: Int): Double =
    val w = v - n * math.floor(v / n)
    // rounding can leave w equal to n for a tiny negative v
    if w >= n then 0.0 else w

  /** Index `i` brought into `0 until n`; `i` may be one period outside. */
  def wrapIndex(i: Int, n: Int): Int = if i < 0 then i + n else if i >= n then i - n else i

  /** The value of the cell that contains `(x, y)`. */
  def nearest(f: Field2D, x: Double, y: Double): Double =
    val i = math.min(f.nx - 1, math.floor(wrap(x, f.nx)).toInt)
    val j = math.min(f.ny - 1, math.floor(wrap(y, f.ny)).toInt)
    f.data(f.grid.index(i, j))

  /**
   * The value at `(x, y)` interpolated linearly in both directions between the centres of the
   * four nearest cells. It reproduces any field that is linear in x and y exactly (away from
   * the seam, where the domain wraps) and equals the cell value at a cell centre.
   */
  def bilinear(f: Field2D, x: Double, y: Double): Double =
    val gx = wrap(x, f.nx) - 0.5
    val gy = wrap(y, f.ny) - 0.5
    val fi = math.floor(gx)
    val fj = math.floor(gy)
    val tx = gx - fi
    val ty = gy - fj
    val i0 = wrapIndex(fi.toInt, f.nx)
    val j0 = wrapIndex(fj.toInt, f.ny)
    val i1 = wrapIndex(i0 + 1, f.nx)
    val j1 = wrapIndex(j0 + 1, f.ny)
    val g = f.grid
    val d = f.data
    val bottom = d(g.index(i0, j0)) * (1.0 - tx) + d(g.index(i1, j0)) * tx
    val top = d(g.index(i0, j1)) * (1.0 - tx) + d(g.index(i1, j1)) * tx
    bottom * (1.0 - ty) + top * ty
