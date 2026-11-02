package physis.fields

/**
 * Semi-Lagrangian advection of the velocity on the staggered grid of a [[SphereGrid]], the
 * counterpart of [[Advect]] for cells that are not square: the flow is in m/s, and a sample moves
 * `u / (R cos φ dλ)` columns and `v / dy` rows a second, so a speed that takes a cell across at
 * the equator takes it much further along a row near the pole. The path back is followed in two
 * half steps (midpoint rule) in index space, which is the path in longitude and latitude, and
 * the value is read by bilinear interpolation, so the scheme is stable at any `dt`. Longitude
 * wraps. Nothing here allocates.
 */
object SphereAdvect:

  private def readU(f: Advect.Flow, x: Double, y: Double): Double =
    Sampler.bilinearAt(f.u, x, y, Mac.UOffsetX, Mac.UOffsetY, f.nx, f.ny, f.wrapX, f.wrapY)

  private def readV(f: Advect.Flow, x: Double, y: Double): Double =
    Sampler.bilinearAt(f.v, x, y, Mac.VOffsetX, Mac.VOffsetY, f.nx, f.ny, f.wrapX, f.wrapY)

  private def advect(
      f: Advect.Flow,
      g: SphereGrid,
      src: Field2D,
      dst: Field2D,
      ox: Double,
      oy: Double,
      dt: Double
  ): Unit =
    if src eq dst then throw new IllegalArgumentException("advect needs two different fields")
    val invDy = 1.0 / g.dy
    var j = 0
    while j < src.ny do
      var i = 0
      while i < src.nx do
        val x = i + ox
        val y = j + oy
        val mx = x - 0.5 * dt * readU(f, x, y) * g.invDx(y)
        val my = y - 0.5 * dt * readV(f, x, y) * invDy
        val px = x - dt * readU(f, mx, my) * g.invDx(my)
        val py = y - dt * readV(f, mx, my) * invDy
        dst(i, j) = Sampler.bilinearAt(src, px, py, ox, oy, f.nx, f.ny, f.wrapX, f.wrapY)
        i += 1
      j += 1

  /** The eastward velocity: `srcU` into `dstU`; the last face is a copy of the first. */
  def facesU(f: Advect.Flow, g: SphereGrid, srcU: Field2D, dstU: Field2D, dt: Double): Unit =
    advect(f, g, srcU, dstU, Mac.UOffsetX, Mac.UOffsetY, dt)
    var j = 0
    while j < dstU.ny do
      dstU(f.nx, j) = dstU(0, j)
      j += 1

  /** The southward velocity: `srcV` into `dstV`. */
  def facesV(f: Advect.Flow, g: SphereGrid, srcV: Field2D, dstV: Field2D, dt: Double): Unit =
    advect(f, g, srcV, dstV, Mac.VOffsetX, Mac.VOffsetY, dt)
