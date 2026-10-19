package physis.fields

import physis.verify.Allocation

class TrailOpsSuite extends munit.FunSuite:
  private val grid = Grid2D(20, 15)

  private def random(seed: Long): Field2D =
    val r = new java.util.Random(seed)
    val f = new Field2D(grid)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do f(i, j) = r.nextDouble()
    f

  test("diffusion keeps the total to 1e-12, for every mixing factor") {
    for k <- Seq(0.0, 0.3, 1.0) do
      val src = random(1L)
      val dst = new Field2D(grid)
      TrailOps.diffuse(src, dst, k)
      assertEqualsDouble(dst.sum, src.sum, 1e-12 * src.sum, s"k = $k")
  }

  test("a point source spreads to nine cells of a ninth each") {
    val src = new Field2D(grid)
    src(7, 5) = 9.0
    val dst = new Field2D(grid)
    TrailOps.diffuse(src, dst, 1.0)
    var nonzero = 0
    for j <- 0 until grid.ny; i <- 0 until grid.nx do
      if dst(i, j) != 0.0 then
        nonzero += 1
        assertEqualsDouble(dst(i, j), 1.0, 1e-15)
        assert(math.abs(i - 7) <= 1 && math.abs(j - 5) <= 1, s"($i, $j)")
    assertEquals(nonzero, 9)
  }

  test("a source in the corner spreads over the seam") {
    val src = new Field2D(grid)
    src(0, 0) = 9.0
    val dst = new Field2D(grid)
    TrailOps.diffuse(src, dst, 1.0)
    for (i, j) <- Seq((19, 14), (0, 14), (1, 14), (19, 0), (1, 0), (19, 1), (0, 1), (1, 1)) do
      assertEqualsDouble(dst(i, j), 1.0, 1e-15, s"($i, $j)")
    assertEqualsDouble(dst(0, 0), 1.0, 1e-15)
  }

  test("k = 0 copies, k in between mixes, a uniform field stays uniform") {
    val src = random(2L)
    val copy = new Field2D(grid)
    TrailOps.diffuse(src, copy, 0.0)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do assertEquals(copy(i, j), src(i, j))
    val full = new Field2D(grid)
    val half = new Field2D(grid)
    TrailOps.diffuse(src, full, 1.0)
    TrailOps.diffuse(src, half, 0.5)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do
      assertEqualsDouble(half(i, j), 0.5 * src(i, j) + 0.5 * full(i, j), 1e-15)
    val flat = new Field2D(grid)
    flat.fill(3.0)
    val out = new Field2D(grid)
    TrailOps.diffuse(flat, out, 1.0)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do assertEqualsDouble(out(i, j), 3.0, 1e-15)
  }

  test("the halo is neither read nor written") {
    val src = random(3L)
    src.data(grid.index(-1, 0)) = Double.NaN
    val dst = new Field2D(grid)
    dst.fillAll(7.0)
    TrailOps.diffuse(src, dst, 1.0)
    assert(!dst.sum.isNaN)
    assertEquals(dst.data(grid.index(-1, 0)), 7.0)
    assertEquals(dst.data(grid.index(grid.nx, grid.ny)), 7.0)
  }

  test("decay multiplies the total by the factor and leaves the halo alone") {
    val f = random(4L)
    f.data(grid.index(-1, -1)) = 5.0
    val before = f.sum
    TrailOps.decay(f, 0.9)
    assertEqualsDouble(f.sum, 0.9 * before, 1e-12)
    assertEquals(f.data(grid.index(-1, -1)), 5.0)
  }

  test("bad arguments are refused") {
    val a = new Field2D(grid)
    intercept[IllegalArgumentException](TrailOps.diffuse(a, a, 0.5))
    intercept[IllegalArgumentException](TrailOps.diffuse(a, new Field2D(Grid2D(4, 4)), 0.5))
    intercept[IllegalArgumentException](TrailOps.diffuse(a, new Field2D(grid), 1.5))
    intercept[IllegalArgumentException](TrailOps.decay(a, -0.1))
    intercept[IllegalArgumentException](TrailOps.decay(a, 1.1))
  }

  test("nothing allocates") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val a = random(5L)
    val b = new Field2D(grid)
    val bytes = Allocation.perStep(2000) {
      TrailOps.diffuse(a, b, 0.8)
      TrailOps.decay(b, 0.99)
      TrailOps.diffuse(b, a, 0.8)
    }
    assert(bytes < 2000, s"$bytes bytes over 2000 calls")
  }
