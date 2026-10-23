package physis.fields

/**
 * One explicit diffusion step with the 5-point Laplacian, `out = f + s · ∇²f`, where `s` is
 * `κ dt / h²` (unit cells: `κ dt`). The halo of `f` must hold the boundary condition (see
 * [[Boundaries]]); `out` and `f` must be different fields of the same shape. The step is stable
 * for `s <= 0.25`. Nothing here allocates.
 */
object Diffuse:

  /** Every interior cell of `f`. */
  def step(f: Field2D, out: Field2D, s: Double): Unit =
    val fd = f.data
    val od = out.data
    val stride = f.grid.stride
    var j = 0
    while j < f.ny do
      var k = f.grid.index(0, j)
      val end = k + f.nx
      while k < end do
        val c = fd(k)
        od(k) = c + s * ((((fd(k - 1) + fd(k + 1)) + fd(k - stride)) + fd(k + stride)) - 4.0 * c)
        k += 1
      j += 1

  /**
   * Only the samples where `open` is not 0; the others are copied. This is how the faces of a
   * staggered velocity are diffused: a face that is a wall keeps its value.
   */
  def stepOpen(f: Field2D, out: Field2D, open: Field2D, s: Double): Unit =
    val fd = f.data
    val od = out.data
    val op = open.data
    val stride = f.grid.stride
    var j = 0
    while j < f.ny do
      var k = f.grid.index(0, j)
      val end = k + f.nx
      while k < end do
        val c = fd(k)
        od(k) =
          if op(k) != 0.0 then
            c + s * ((((fd(k - 1) + fd(k + 1)) + fd(k - stride)) + fd(k + stride)) - 4.0 * c)
          else c
        k += 1
      j += 1
