package physis.fields

import physis.verify.Allocation

class DiffuseSuite extends munit.FunSuite:
  private val n = 32

  private def cosine(grid: Grid2D): Field2D =
    val f = new Field2D(grid)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do
      f(i, j) = math.cos(2.0 * math.Pi * (i + 0.5) / grid.nx)
    f

  test("a cosine mode on a periodic grid decays by the exact discrete factor") {
    val grid = Grid2D(n, 8)
    val f = cosine(grid)
    val out = new Field2D(grid)
    val s = 0.2
    val factor = 1.0 - s * (2.0 - 2.0 * math.cos(2.0 * math.Pi / n))
    for _ <- 1 to 50 do
      Boundaries.periodic.fill(f)
      Diffuse.step(f, out, s)
      f.copyFrom(out)
    val expected = math.pow(factor, 50.0)
    for i <- 0 until n do
      assertEqualsDouble(f(i, 3), expected * math.cos(2.0 * math.Pi * (i + 0.5) / n), 1e-12)
  }

  test("the total is conserved with periodic and with Neumann borders") {
    for b <- Seq(Boundaries.periodic, Boundaries.neumann) do
      val grid = Grid2D(20, 12)
      val f = new Field2D(grid)
      val r = new java.util.Random(4L)
      for j <- 0 until 12; i <- 0 until 20 do f(i, j) = r.nextDouble()
      val before = f.sum
      val out = new Field2D(grid)
      for _ <- 1 to 300 do
        b.fill(f)
        Diffuse.step(f, out, 0.2)
        f.copyFrom(out)
      assertEqualsDouble(f.sum, before, 1e-9)
  }

  test("stepOpen leaves the closed samples as they are") {
    val grid = Grid2D(10, 10)
    val f = new Field2D(grid)
    val open = new Field2D(grid)
    val out = new Field2D(grid)
    for j <- 0 until 10; i <- 0 until 10 do
      f(i, j) = (i * 7 + j * 3) % 5
      open(i, j) = if i == 4 then 0.0 else 1.0
    Boundaries.neumann.fill(f)
    Diffuse.stepOpen(f, out, open, 0.2)
    for j <- 0 until 10 do assertEquals(out(4, j), f(4, j))
    assert(out(5, 5) != f(5, 5) || out(3, 5) != f(3, 5))
  }

  test("a step allocates nothing") {
    assume(Allocation.supported)
    val grid = Grid2D(64, 64)
    val f = cosine(grid)
    val out = new Field2D(grid)
    val bytes = Allocation.perStep(100)(Diffuse.step(f, out, 0.1))
    assert(bytes < 100L, s"$bytes bytes")
  }
