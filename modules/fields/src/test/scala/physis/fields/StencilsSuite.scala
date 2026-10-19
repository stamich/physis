package physis.fields

import physis.verify.{Allocation, Convergence}

class StencilsSuite extends munit.FunSuite:

  private val TwoPi = 2.0 * math.Pi

  /** A field holding `f(i, j)` on an nx by ny periodic grid of cell size h, halo filled. */
  private def sampled(nx: Int, ny: Int, h: Double = 1.0)(f: (Int, Int) => Double): Field2D =
    val field = new Field2D(Grid2D(nx, ny, h))
    for j <- 0 until ny; i <- 0 until nx do field(i, j) = f(i, j)
    Boundaries.periodic.fill(field)
    field

  private def maxAbsDiff(dst: Field2D, expected: (Int, Int) => Double): Double =
    var worst = 0.0
    for j <- 0 until dst.ny; i <- 0 until dst.nx do
      worst = math.max(worst, math.abs(dst(i, j) - expected(i, j)))
    worst

  test("5-point Laplacian of sin x sin y converges with order 2") {
    val sizes = Seq(16, 32, 64, 128)
    val errors = sizes.map { n =>
      val h = TwoPi / n
      def u(i: Int, j: Int): Double = math.sin((i + 0.5) * h) * math.sin((j + 0.5) * h)
      val src = sampled(n, n, h)(u)
      val dst = new Field2D(src.grid)
      Stencils.laplacian5(src, dst)
      maxAbsDiff(dst, (i, j) => -2.0 * u(i, j))
    }
    val order = Convergence.observedOrder(sizes.map(n => TwoPi / n), errors)
    assertEqualsDouble(order, 2.0, 0.1, s"errors: $errors")
  }

  test("5-point Laplacian scales with the cell size") {
    def u(i: Int, j: Int): Double = math.sin(0.3 * i) + math.cos(0.7 * j)
    val a = sampled(8, 8, 1.0)(u)
    val b = sampled(8, 8, 0.5)(u)
    val da = new Field2D(a.grid)
    val db = new Field2D(b.grid)
    Stencils.laplacian5(a, da)
    Stencils.laplacian5(b, db)
    assertEqualsDouble(maxAbsDiff(db, (i, j) => 4.0 * da(i, j)), 0.0, 1e-12)
  }

  /** Mode sin(kx i + 0.3) cos(ky j + 0.7) with kx = 2 pi mx / nx. */
  private def mode(nx: Int, ny: Int, mx: Int, my: Int): (Double, Double, (Int, Int) => Double) =
    val kx = TwoPi * mx / nx
    val ky = TwoPi * my / ny
    (kx, ky, (i, j) => math.sin(kx * i + 0.3) * math.cos(ky * j + 0.7))

  test("9-point Laplacian reproduces its closed-form eigenvalue on periodic modes") {
    for (nx, ny, mx, my) <- Seq((16, 16, 1, 2), (24, 12, 5, 3), (32, 32, 7, 0), (10, 14, 4, 6))
    do
      val (kx, ky, u) = mode(nx, ny, mx, my)
      val src = sampled(nx, ny)(u)
      val dst = new Field2D(src.grid)
      Stencils.laplacian9(src, dst)
      val lambda = Stencils.eigenvalue9(kx, ky)
      assertEqualsDouble(maxAbsDiff(dst, (i, j) => lambda * u(i, j)), 0.0, 1e-12, s"$nx x $ny")
  }

  test("5-point Laplacian reproduces its closed-form eigenvalue on periodic modes") {
    for (nx, ny, mx, my) <- Seq((16, 16, 1, 2), (24, 12, 5, 3), (32, 32, 7, 0))
    do
      val (kx, ky, u) = mode(nx, ny, mx, my)
      val src = sampled(nx, ny)(u)
      val dst = new Field2D(src.grid)
      Stencils.laplacian5(src, dst)
      val lambda = Stencils.eigenvalue5(kx, ky)
      assertEqualsDouble(maxAbsDiff(dst, (i, j) => lambda * u(i, j)), 0.0, 1e-12, s"$nx x $ny")
  }

  test("the 9-point eigenvalue is -1.6 at the checkerboard mode and never lower") {
    assertEqualsDouble(Stencils.eigenvalue9(math.Pi, math.Pi), -1.6, 1e-15)
    assertEquals(Stencils.MinEigenvalue9, -1.6)
    val steps = 0 to 200
    val lowest = steps.flatMap { a =>
      steps.map(b => Stencils.eigenvalue9(math.Pi * a / 200, math.Pi * b / 200))
    }.min
    assertEqualsDouble(lowest, -1.6, 1e-12)
    // Checkerboard on the grid: the stencil gives -1.6 times the pattern.
    val src = sampled(8, 8)((i, j) => if (i + j) % 2 == 0 then 1.0 else -1.0)
    val dst = new Field2D(src.grid)
    Stencils.laplacian9(src, dst)
    assertEqualsDouble(dst(0, 0), -1.6, 1e-14)
    assertEqualsDouble(dst(1, 0), 1.6, 1e-14)
  }

  test("both Laplacians annihilate a constant field, whatever the boundary") {
    for b <- Seq(Boundaries.periodic, Boundaries.neumann) do
      val src = new Field2D(Grid2D(9, 7))
      src.fill(3.25)
      b.fill(src)
      val dst = new Field2D(src.grid)
      dst.fillAll(1.0)
      Stencils.laplacian5(src, dst)
      assertEquals(dst.min, 0.0)
      assertEquals(dst.max, 0.0)
      Stencils.laplacian9(src, dst)
      assertEqualsDouble(dst.min, 0.0, 1e-14)
      assertEqualsDouble(dst.max, 0.0, 1e-14)
  }

  test("a stencil writes the interior of dst only") {
    val src = sampled(5, 5)((i, j) => (i * 7 + j * 3).toDouble)
    val dst = new Field2D(src.grid)
    dst.fillAll(-42.0)
    Stencils.laplacian9(src, dst)
    assertEquals(dst(-1, 0), -42.0)
    assertEquals(dst(5, 5), -42.0)
    assertEquals(dst(0, -1), -42.0)
  }

  test("a stencil rejects mismatched shapes and aliasing") {
    val a = new Field2D(Grid2D(4, 4))
    intercept[IllegalArgumentException](Stencils.laplacian5(a, new Field2D(Grid2D(5, 4))))
    intercept[IllegalArgumentException](Stencils.laplacian9(a, a))
  }

  test("stencils do not allocate") {
    assume(Allocation.supported)
    val src = sampled(16, 12)((i, j) => math.sin(0.1 * i) * math.cos(0.2 * j))
    val dst = new Field2D(src.grid)
    val bytes5 = Allocation.perStep(20000)(Stencils.laplacian5(src, dst))
    assert(bytes5 < 20000L, s"laplacian5 allocated $bytes5 bytes in 20000 steps")
    val bytes9 = Allocation.perStep(20000)(Stencils.laplacian9(src, dst))
    assert(bytes9 < 20000L, s"laplacian9 allocated $bytes9 bytes in 20000 steps")
  }
