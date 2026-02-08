package physis.fields

/**
 * Finite-difference Laplacians on a [[Field2D]].
 *
 * Both read the halo of `src`, so the caller fills it first with [[Boundaries.fill]]; they write
 * the interior of `dst` and nothing else, and allocate nothing. `src` and `dst` must be
 * different fields of the same shape.
 *
 * The order of the floating-point operations is part of the contract (it is spelled out in each
 * method), so results are bit-identical on every platform and the independent reference
 * implementation in `tools/` can repeat them exactly.
 */
object Stencils:

  /**
   * Second-order 5-point Laplacian: `(w + e + s + n - 4 c) / h^2`, evaluated as
   * `(((w + e) + s) + n - 4 c) * (1 / h^2)`.
   */
  def laplacian5(src: Field2D, dst: Field2D): Unit =
    check(src, dst)
    val g = src.grid
    val inv = 1.0 / (g.h * g.h)
    val a = src.data
    val out = dst.data
    val stride = g.stride
    var j = 0
    while j < g.ny do
      var k = g.index(0, j)
      val end = k + g.nx
      while k < end do
        val sum = ((a(k - 1) + a(k + 1)) + a(k - stride)) + a(k + stride)
        out(k) = (sum - 4.0 * a(k)) * inv
        k += 1
      j += 1

  /**
   * Isotropic 9-point Laplacian with weights 0.2 for the four edge neighbours, 0.05 for the four
   * corner neighbours and -1 for the centre, divided by `h^2`. Evaluated as
   * `((0.2 * (((w + e) + s) + n) + 0.05 * (((sw + se) + nw) + ne)) - c) * (1 / h^2)`.
   *
   * It is the kernel of the Gray-Scott literature; the weights sum to zero, and its Fourier
   * symbol is [[eigenvalue9]].
   */
  def laplacian9(src: Field2D, dst: Field2D): Unit =
    check(src, dst)
    val g = src.grid
    val inv = 1.0 / (g.h * g.h)
    val a = src.data
    val out = dst.data
    val stride = g.stride
    var j = 0
    while j < g.ny do
      var k = g.index(0, j)
      val end = k + g.nx
      while k < end do
        val edges = ((a(k - 1) + a(k + 1)) + a(k - stride)) + a(k + stride)
        val corners =
          ((a(k - stride - 1) + a(k - stride + 1)) + a(k + stride - 1)) + a(k + stride + 1)
        out(k) = ((0.2 * edges + 0.05 * corners) - a(k)) * inv
        k += 1
      j += 1

  /**
   * Eigenvalue of [[laplacian5]] for the periodic mode with wave numbers `(kx, ky)` in radians
   * per cell: `(2 cos kx + 2 cos ky - 4) / h^2`.
   */
  def eigenvalue5(kx: Double, ky: Double, h: Double = 1.0): Double =
    (2.0 * math.cos(kx) + 2.0 * math.cos(ky) - 4.0) / (h * h)

  /**
   * Eigenvalue of [[laplacian9]]: `(-1 + 0.4 (cos kx + cos ky) + 0.2 cos kx cos ky) / h^2`. It
   * lies in `[-1.6 / h^2, 0]` and reaches its minimum at `kx = ky = pi`.
   */
  def eigenvalue9(kx: Double, ky: Double, h: Double = 1.0): Double =
    val cx = math.cos(kx)
    val cy = math.cos(ky)
    (-1.0 + 0.4 * (cx + cy) + 0.2 * cx * cy) / (h * h)

  /** Most negative eigenvalue of [[laplacian9]] for unit spacing, the base of stability limits. */
  val MinEigenvalue9: Double = -1.6

  private def check(src: Field2D, dst: Field2D): Unit =
    // No require(..., s"...") here: its by-name message would allocate a closure on every call.
    if src.grid != dst.grid then
      throw new IllegalArgumentException(s"shape mismatch: ${src.grid} vs ${dst.grid}")
    if src eq dst then
      throw new IllegalArgumentException("source and destination must be different fields")
