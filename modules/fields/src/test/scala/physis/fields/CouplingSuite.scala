package physis.fields

import physis.verify.Allocation

class CouplingSuite extends munit.FunSuite:
  private val grid = Grid2D(32, 24)

  private def linear(a: Double, b: Double, c: Double): Field2D =
    val f = new Field2D(grid)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do f(i, j) = a + b * (i + 0.5) + c * (j + 0.5)
    f

  private def random(seed: Long): Field2D =
    val r = new java.util.Random(seed)
    val f = new Field2D(grid)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do f(i, j) = r.nextDouble()
    f

  test("bilinear sampling reproduces a linear field to 1e-12 between the first and last centre") {
    val f = linear(1.5, 0.25, -0.75)
    val r = new java.util.Random(1L)
    for _ <- 1 to 500 do
      val x = 0.5 + r.nextDouble() * (grid.nx - 1)
      val y = 0.5 + r.nextDouble() * (grid.ny - 1)
      assertEqualsDouble(Sampler.bilinear(f, x, y), 1.5 + 0.25 * x - 0.75 * y, 1e-12)
  }

  test("at a cell centre the sample is the cell value, and nearest reads the cell it is in") {
    val f = random(2L)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do
      assertEqualsDouble(Sampler.bilinear(f, i + 0.5, j + 0.5), f(i, j), 1e-15)
      assertEquals(Sampler.nearest(f, i + 0.01, j + 0.99), f(i, j))
  }

  test("the domain is periodic: positions a period apart, or negative, read the same cells") {
    val f = random(3L)
    val r = new java.util.Random(4L)
    for _ <- 1 to 300 do
      val x = r.nextDouble() * grid.nx
      val y = r.nextDouble() * grid.ny
      val here = Sampler.bilinear(f, x, y)
      assertEqualsDouble(Sampler.bilinear(f, x + grid.nx, y - 2 * grid.ny), here, 1e-12)
      assertEqualsDouble(Sampler.bilinear(f, x - 5 * grid.nx, y + grid.ny), here, 1e-12)
      assertEquals(Sampler.nearest(f, x - 3 * grid.nx, y), Sampler.nearest(f, x, y))
  }

  test("across the seam the sample is a mix of the last and the first column") {
    val f = new Field2D(grid)
    for j <- 0 until grid.ny do
      f(grid.nx - 1, j) = 10.0
      f(0, j) = 20.0
    // x = 0 lies half way between the centres of column nx - 1 (at nx - 0.5) and column 0 (at 0.5)
    assertEqualsDouble(Sampler.bilinear(f, 0.0, 5.5), 15.0, 1e-12)
    assertEqualsDouble(Sampler.bilinear(f, grid.nx.toDouble, 5.5), 15.0, 1e-12)
    assertEqualsDouble(Sampler.bilinear(f, 0.25, 5.5), 17.5, 1e-12)
  }

  test("the halo does not matter") {
    val f = random(5L)
    val g = new Field2D(grid, f.data.clone())
    g.fillAll(Double.NaN)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do g(i, j) = f(i, j)
    assertEquals(Sampler.bilinear(g, 0.1, 0.2), Sampler.bilinear(f, 0.1, 0.2))
    assertEquals(Sampler.bilinear(g, 31.9, 23.9), Sampler.bilinear(f, 31.9, 23.9))
  }

  test("a deposit adds exactly its amount, also across the seam and with negative positions") {
    val points = Seq((10.3, 7.7), (0.0, 0.0), (31.99, 23.99), (-0.4, -0.4), (100.2, -50.6))
    for (x, y) <- points do
      val f = random(6L)
      val before = f.sum
      Deposit.bilinear(f, x, y, 2.5)
      assertEqualsDouble(f.sum - before, 2.5, 1e-12, s"($x, $y)")
      val g = random(6L)
      Deposit.nearest(g, x, y, 2.5)
      assertEqualsDouble(g.sum - before, 2.5, 1e-12, s"($x, $y)")
  }

  test("a deposit at a cell centre lands in that cell alone") {
    val f = new Field2D(grid)
    Deposit.bilinear(f, 4.5, 9.5, 3.0)
    assertEquals(f(4, 9), 3.0)
    assertEqualsDouble(f.sum, 3.0, 0.0)
    assertEquals(Sampler.bilinear(f, 4.5, 9.5), 3.0)
  }

  test("a deposit is shared by the weights the sample reads with") {
    val f = new Field2D(grid)
    Deposit.bilinear(f, 5.0, 6.25, 4.0)
    // x = 5.0 is half way between the centres of columns 3 and 4; y = 6.25 is a quarter of the
    // way from the centre of row 5 (at 5.5) to the centre of row 6 (at 6.5)... 6.25 - 0.5 = 5.75
    assertEqualsDouble(f(4, 5), 4.0 * 0.5 * 0.25, 1e-15)
    assertEqualsDouble(f(5, 5), 4.0 * 0.5 * 0.25, 1e-15)
    assertEqualsDouble(f(4, 6), 4.0 * 0.5 * 0.75, 1e-15)
    assertEqualsDouble(f(5, 6), 4.0 * 0.5 * 0.75, 1e-15)
  }

  test("nothing allocates") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val f = random(7L)
    var sink = 0.0
    val bytes = Allocation.perStep(5000) {
      sink += Sampler.bilinear(f, 12.3, 7.7) + Sampler.nearest(f, 3.3, 4.4)
      Deposit.bilinear(f, 31.9, 23.9, 1e-9)
      Deposit.nearest(f, 0.1, 0.1, 1e-9)
    }
    assert(bytes < 5000, s"$bytes bytes over 5000 calls")
    assert(!sink.isNaN)
  }
