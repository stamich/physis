package physis.fields

import physis.verify.Allocation

class PoissonSuite extends munit.FunSuite:
  private def field(grid: Grid2D)(f: (Int, Int) => Double): Field2D =
    val q = new Field2D(grid)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do q(i, j) = f(i, j)
    q

  private def noSolid(grid: Grid2D): Field2D = new Field2D(grid)

  private def maxDiff(a: Field2D, b: Field2D): Double =
    var m = 0.0
    for j <- 0 until a.ny; i <- 0 until a.nx do m = math.max(m, math.abs(a(i, j) - b(i, j)))
    m

  /** Error of the periodic solve for the exact solution sin(kx) sin(ky), k = 2 pi / n. */
  private def periodicError(n: Int): Double =
    val grid = Grid2D(n, n)
    val k = 2.0 * math.Pi / n
    val exact = field(grid)((i, j) => math.sin(k * (i + 0.5)) * math.sin(k * (j + 0.5)))
    val b = field(grid)((i, j) => 2.0 * k * k * exact(i, j))
    val poisson = new Poisson(grid, wrapX = true, wrapY = true, outflowRight = false)
    poisson.configure(noSolid(grid))
    val q = new Field2D(grid)
    val iterations = poisson.solve(b, q, 1e-13, 5000)
    assert(iterations > 0 && poisson.residual <= 1e-13, s"n = $n: ${poisson.residual}")
    maxDiff(q, exact)

  test("periodic: the error against the continuous solution falls four times per doubling") {
    val e16 = periodicError(16)
    val e32 = periodicError(32)
    val e64 = periodicError(64)
    assertEqualsDouble(e16 / e32, 4.0, 0.25)
    assertEqualsDouble(e32 / e64, 4.0, 0.15)
  }

  test("closed box: a cosine mode is an exact eigenvector, so it is solved to rounding") {
    val grid = Grid2D(24, 16)
    val exact = field(grid)((i, j) =>
      math.cos(math.Pi * (i + 0.5) / grid.nx) * math.cos(math.Pi * (j + 0.5) / grid.ny)
    )
    val lx = 2.0 - 2.0 * math.cos(math.Pi / grid.nx)
    val ly = 2.0 - 2.0 * math.cos(math.Pi / grid.ny)
    val b = field(grid)((i, j) => (lx + ly) * exact(i, j))
    val poisson = new Poisson(grid, wrapX = false, wrapY = false, outflowRight = false)
    poisson.configure(noSolid(grid))
    val q = new Field2D(grid)
    poisson.solve(b, q, 1e-14, 5000)
    assert(maxDiff(q, exact) < 1e-10, maxDiff(q, exact))
  }

  test("a block of solid cells: the equations hold on the other cells and the solid stay 0") {
    val grid = Grid2D(32, 24)
    val solid = field(grid)((i, j) => if i >= 10 && i < 16 && j >= 8 && j < 20 then 1.0 else 0.0)
    val poisson = new Poisson(grid, wrapX = false, wrapY = false, outflowRight = false)
    poisson.configure(solid)
    val random = new java.util.Random(5L)
    val b = field(grid)((i, j) => if solid(i, j) != 0.0 then 0.0 else random.nextDouble() - 0.5)
    val q = new Field2D(grid)
    poisson.solve(b, q, 1e-12, 5000)
    val bq = new Field2D(grid)
    poisson(q, bq)
    // b has a mean, which the singular system cannot match: compare up to that constant
    var sum = 0.0
    var cells = 0
    for j <- 0 until grid.ny; i <- 0 until grid.nx if solid(i, j) == 0.0 do
      sum += b(i, j)
      cells += 1
    val mean = sum / cells
    for j <- 0 until grid.ny; i <- 0 until grid.nx do
      if solid(i, j) != 0.0 then
        assertEquals(q(i, j), 0.0)
        assertEquals(bq(i, j), 0.0)
      else assertEqualsDouble(bq(i, j), b(i, j) - mean, 1e-9)
    assertEquals(poisson.activeCells, cells)
  }

  test("with an outflow border the operator is symmetric and positive, and solves exactly") {
    val grid = Grid2D(20, 6)
    val poisson = new Poisson(grid, wrapX = false, wrapY = false, outflowRight = true)
    poisson.configure(noSolid(grid))
    val random = new java.util.Random(6L)
    val x = field(grid)((_, _) => random.nextDouble() - 0.5)
    val y = field(grid)((_, _) => random.nextDouble() - 0.5)
    val bx = new Field2D(grid)
    val by = new Field2D(grid)
    poisson(x, bx)
    poisson(y, by)
    var xBy = 0.0
    var yBx = 0.0
    var xBx = 0.0
    for j <- 0 until grid.ny; i <- 0 until grid.nx do
      xBy += x(i, j) * by(i, j)
      yBx += y(i, j) * bx(i, j)
      xBx += x(i, j) * bx(i, j)
    assertEqualsDouble(xBy, yBx, 1e-12)
    assert(xBx > 0.0)
    val b = field(grid)((_, _) => random.nextDouble() - 0.5)
    val q = new Field2D(grid)
    poisson.solve(b, q, 1e-13, 5000)
    val check = new Field2D(grid)
    poisson(q, check)
    assert(maxDiff(check, b) < 1e-10, maxDiff(check, b))
  }

  test("a warm start from the solution needs no iteration, and a solve is repeatable") {
    val grid = Grid2D(32, 32)
    val random = new java.util.Random(7L)
    val raw = field(grid)((_, _) => random.nextDouble() - 0.5)
    val mean = raw.mean
    val b = field(grid)((i, j) => raw(i, j) - mean)
    val poisson = new Poisson(grid, wrapX = true, wrapY = true, outflowRight = false)
    poisson.configure(noSolid(grid))
    val q1 = new Field2D(grid)
    val n1 = poisson.solve(b, q1, 1e-10, 5000)
    val q2 = new Field2D(grid)
    val n2 = poisson.solve(b, q2, 1e-10, 5000)
    assertEquals(n1, n2)
    assertEquals(q1.digest, q2.digest)
    assertEquals(poisson.solve(b, q1, 1e-8, 5000), 0)
  }

  test("a solve allocates nothing") {
    assume(Allocation.supported)
    val grid = Grid2D(48, 48)
    val random = new java.util.Random(8L)
    val raw = field(grid)((_, _) => random.nextDouble() - 0.5)
    val b = field(grid)((i, j) => raw(i, j) - raw.mean)
    val poisson = new Poisson(grid, wrapX = true, wrapY = false, outflowRight = false)
    poisson.configure(noSolid(grid))
    val q = new Field2D(grid)
    val bytes = Allocation.perStep(20) {
      q.fillAll(0.0)
      val _ = poisson.solve(b, q, 1e-9, 500)
    }
    assert(bytes < 20L * 100, s"$bytes bytes in 20 solves")
  }

  test("a field of the wrong shape and a non-positive operator are refused") {
    val poisson = new Poisson(Grid2D(8, 8), wrapX = true, wrapY = true, outflowRight = false)
    intercept[IllegalArgumentException](poisson.configure(new Field2D(Grid2D(9, 8))))
    intercept[IllegalArgumentException](
      poisson.solve(new Field2D(Grid2D(9, 8)), new Field2D(Grid2D(8, 8)), 1e-6, 10)
    )
  }
