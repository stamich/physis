package physis.fields

import physis.core.run.{Disc, Op}
import physis.verify.Allocation

class BrushSuite extends munit.FunSuite:
  private val grid = Grid2D(64, 64)

  private def mark(disc: Disc, wrapX: Boolean, wrapY: Boolean): Array[Boolean] =
    val hit = new Array[Boolean](grid.nx * grid.ny)
    Brush.foreachCell(grid, disc, wrapX, wrapY)((i, j) => hit(j * grid.nx + i) = true)
    hit

  test("the area matches pi r squared on average over positions") {
    val big = Grid2D(256, 256)
    for r <- Seq(8.0, 12.0, 24.0) do
      var total = 0.0
      var n = 0
      for a <- 0 until 20; b <- 0 until 20 do
        total += Brush.count(big, Disc(100 + a / 20.0, 100 + b / 20.0, r), false, false)
        n += 1
      val mean = total / n
      assertEqualsDouble(mean / (math.Pi * r * r), 1.0, 0.005, s"r = $r")
  }

  test("a single disc is within 2 percent of the area from r = 16") {
    val big = Grid2D(256, 256)
    for r <- Seq(16.0, 20.5, 32.0, 50.25) do
      val area = Brush.count(big, Disc(128.3, 127.9, r), false, false)
      assertEqualsDouble(area / (math.Pi * r * r), 1.0, 0.02, s"r = $r")
  }

  test("a cell is in when its centre is within the radius") {
    val hit = mark(Disc(10.5, 10.5, 2.0), false, false)
    def at(i: Int, j: Int) = hit(j * grid.nx + i)
    assert(at(10, 10) && at(12, 10) && at(8, 10) && at(10, 12) && at(10, 8))
    assert(!at(13, 10) && !at(12, 12) && !at(7, 10))
    assertEquals(hit.count(identity), 13)
  }

  test("a disc on a cell corner and on a centre are both symmetric") {
    for (x, y) <- Seq((20.0, 20.0), (20.5, 20.5), (20.0, 20.5)) do
      val hit = mark(Disc(x, y, 6.3), false, false)
      for i <- 0 until 64; j <- 0 until 64 do
        val mi = (2 * x - 1 - i).round.toInt
        val mj = (2 * y - 1 - j).round.toInt
        if mi >= 0 && mi < 64 && mj >= 0 && mj < 64 then
          assertEquals(hit(j * 64 + i), hit(mj * 64 + mi), s"($x, $y) cell ($i, $j)")
  }

  test("periodic axes wrap at all four edges, closed edges clip") {
    val edges = Seq((0.2, 30.0), (63.8, 30.0), (30.0, 0.2), (30.0, 63.8))
    for (x, y) <- edges do
      val wrapped = Brush.count(grid, Disc(x, y, 5.0), true, true)
      val interior = Brush.count(grid, Disc(32.0, 32.0, 5.0), true, true)
      assert(math.abs(wrapped - interior) <= 6, s"($x, $y): $wrapped vs $interior")
      val clipped = Brush.count(grid, Disc(x, y, 5.0), false, false)
      assert(clipped < wrapped && clipped > 0, s"($x, $y): $clipped vs $wrapped")
    val hit = mark(Disc(0.5, 10.5, 2.0), true, false)
    assert(hit(10 * 64 + 63) && hit(10 * 64 + 62) && hit(10 * 64 + 0) && hit(10 * 64 + 1))
    assert(!hit(9 * 64 + 63 - 2))
  }

  test("a disc larger than a periodic grid visits every cell once") {
    val small = Grid2D(8, 8)
    val visits = new Array[Int](64)
    Brush.foreachCell(small, Disc(3.0, 3.0, 100.0), true, true)((i, j) => visits(j * 8 + i) += 1)
    assert(visits.forall(_ == 1), visits.mkString(","))
  }

  test("set, add and erase act on the covered cells only, with clamping") {
    val f = new Field2D(grid)
    val disc = Disc(10.5, 10.5, 3.0)
    val n = Brush(f, disc, false, false, Op.Assign, 0.75, 0.0, 1.0)
    assertEquals(n, Brush.count(grid, disc, false, false))
    assertEqualsDouble(f.sum, 0.75 * n, 1e-12)
    Brush(f, disc, false, false, Op.Add, 0.5, 0.0, 1.0)
    assertEquals(f.max, 1.0)
    assertEqualsDouble(f.sum, 1.0 * n, 1e-12)
    Brush(f, Disc(10.5, 10.5, 1.0), false, false, Op.Erase, 123.0, 0.0, 1.0)
    assertEquals(f(10, 10), 0.0)
    assertEquals(f(10, 13), 1.0)
    assertEquals(f(30, 30), 0.0)
    Brush(f, disc, false, false, Op.Add, -5.0, 0.0, 1.0)
    assertEquals(f.max, 0.0)
  }

  test("the halo is never touched") {
    val f = new Field2D(grid)
    f.fillAll(7.0)
    Brush(f, Disc(0.0, 0.0, 4.0), false, false, Op.Assign, 1.0)
    Brush(f, Disc(0.0, 0.0, 4.0), true, true, Op.Assign, 1.0)
    for i <- -1 to grid.nx do
      assertEquals(f(i, -1), 7.0)
      assertEquals(f(i, grid.ny), 7.0)
    for j <- -1 to grid.ny do
      assertEquals(f(-1, j), 7.0)
      assertEquals(f(grid.nx, j), 7.0)
  }

  test("a dab allocates nothing after warm-up") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val f = new Field2D(Grid2D(256, 256))
    val disc = Disc(100.3, 120.7, 12.0)
    val bytes = Allocation.perStep(2000) {
      val _ = Brush(f, disc, true, true, Op.Add, 0.001, 0.0, 1.0)
    }
    assert(bytes < 2000, s"$bytes bytes over 2000 dabs")
  }
