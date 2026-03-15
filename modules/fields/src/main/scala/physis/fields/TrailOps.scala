package physis.fields

/**
 * The two operations that turn a deposited trail into a field: spreading it over the
 * neighbouring cells and letting it fade. Both work on the interior of a periodic grid and
 * never read the halo. Nothing here allocates.
 */
object TrailOps:

  /**
   * Writes into `dst` the trail `src` spread over the 3 x 3 neighbourhood of every cell:
   * `dst = (1 - k) * src + k * mean3x3(src)`. With `k = 1` it is the plain 3 x 3 mean, with
   * `k = 0` a copy. The domain wraps, and every cell gives away and receives the same total,
   * so the sum over the grid is kept (up to rounding). `src` and `dst` must be two fields.
   */
  def diffuse(src: Field2D, dst: Field2D, k: Double): Unit =
    // Plain ifs: the by-name message of `require` would allocate a closure on every call.
    if src.grid != dst.grid then
      throw new IllegalArgumentException(s"shape mismatch: ${src.grid} vs ${dst.grid}")
    if src eq dst then throw new IllegalArgumentException("diffuse needs a separate destination")
    if !(k >= 0.0 && k <= 1.0) then
      throw new IllegalArgumentException(s"mixing factor must be in [0, 1], got $k")
    val g = src.grid
    val s = src.data
    val d = dst.data
    val nx = g.nx
    val ny = g.ny
    val keep = 1.0 - k
    val share = k * (1.0 / 9.0)
    var j = 0
    while j < ny do
      val up = g.index(0, if j == 0 then ny - 1 else j - 1)
      val mid = g.index(0, j)
      val down = g.index(0, if j == ny - 1 then 0 else j + 1)
      var i = 0
      while i < nx do
        val l = if i == 0 then nx - 1 else i - 1
        val r = if i == nx - 1 then 0 else i + 1
        val rowUp = (s(up + l) + s(up + i)) + s(up + r)
        val rowMid = (s(mid + l) + s(mid + i)) + s(mid + r)
        val rowDown = (s(down + l) + s(down + i)) + s(down + r)
        d(mid + i) = keep * s(mid + i) + share * ((rowUp + rowMid) + rowDown)
        i += 1
      j += 1

  /** Multiplies every interior cell by `factor`, which is `1 - decay rate` of a step. */
  def decay(f: Field2D, factor: Double): Unit =
    if !(factor >= 0.0 && factor <= 1.0) then
      throw new IllegalArgumentException(s"decay factor must be in [0, 1], got $factor")
    val g = f.grid
    val d = f.data
    var j = 0
    while j < g.ny do
      var k = g.index(0, j)
      val end = k + g.nx
      while k < end do
        d(k) *= factor
        k += 1
      j += 1
