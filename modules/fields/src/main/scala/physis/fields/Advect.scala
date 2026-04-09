package physis.fields

/**
 * Semi-Lagrangian advection on the staggered grid: every sample asks where the flow brought it
 * from during the last `dt` and takes the value found there. The path is followed backwards in
 * two half steps (midpoint rule), so the position error is of second order in `dt`; the value
 * is read by bilinear interpolation, which never creates a new maximum or minimum, so the
 * scheme is stable at any `dt` (at the price of some smoothing).
 *
 * Velocities are in cells per unit of time. `u` and `v` are the face fields of a [[MacGrid]].
 * An axis that wraps keeps its first and last face equal. Nothing here allocates.
 */
object Advect:

  /** The velocity at a point is read at the staggered positions; this holds a flow's shape. */
  final class Flow(
      val u: Field2D,
      val v: Field2D,
      val nx: Int,
      val ny: Int,
      val wrapX: Boolean,
      val wrapY: Boolean
  )

  private def readU(f: Flow, x: Double, y: Double): Double =
    Sampler.bilinearAt(f.u, x, y, Mac.UOffsetX, Mac.UOffsetY, f.nx, f.ny, f.wrapX, f.wrapY)

  private def readV(f: Flow, x: Double, y: Double): Double =
    Sampler.bilinearAt(f.v, x, y, Mac.VOffsetX, Mac.VOffsetY, f.nx, f.ny, f.wrapX, f.wrapY)

  /**
   * Advects the field `src`, whose samples sit at `(i + ox, j + oy)`, into `dst`. `src` and `dst`
   * must be different fields of the same shape.
   */
  private def advect(
      f: Flow,
      src: Field2D,
      dst: Field2D,
      ox: Double,
      oy: Double,
      dt: Double
  ): Unit =
    if src eq dst then throw new IllegalArgumentException("advect needs two different fields")
    val inv = dt / src.grid.h
    var j = 0
    while j < src.ny do
      var i = 0
      while i < src.nx do
        val x = i + ox
        val y = j + oy
        val mx = x - 0.5 * inv * readU(f, x, y)
        val my = y - 0.5 * inv * readV(f, x, y)
        val px = x - inv * readU(f, mx, my)
        val py = y - inv * readV(f, mx, my)
        dst(i, j) = Sampler.bilinearAt(src, px, py, ox, oy, f.nx, f.ny, f.wrapX, f.wrapY)
        i += 1
      j += 1

  /** A field at the cell centres (dye, temperature) from `src` into `dst`. */
  def cells(f: Flow, src: Field2D, dst: Field2D, dt: Double): Unit =
    advect(f, src, dst, 0.5, 0.5, dt)

  /** The horizontal velocity: `srcU` into `dstU`. On a periodic axis the last face is a copy. */
  def facesU(f: Flow, srcU: Field2D, dstU: Field2D, dt: Double): Unit =
    advect(f, srcU, dstU, Mac.UOffsetX, Mac.UOffsetY, dt)
    if f.wrapX then
      var j = 0
      while j < dstU.ny do
        dstU(f.nx, j) = dstU(0, j)
        j += 1

  /** The vertical velocity: `srcV` into `dstV`. On a periodic axis the last face is a copy. */
  def facesV(f: Flow, srcV: Field2D, dstV: Field2D, dt: Double): Unit =
    advect(f, srcV, dstV, Mac.VOffsetX, Mac.VOffsetY, dt)
    if f.wrapY then
      var i = 0
      while i < dstV.nx do
        dstV(i, f.ny) = dstV(i, 0)
        i += 1
