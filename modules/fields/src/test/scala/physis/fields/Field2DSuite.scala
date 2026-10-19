package physis.fields

import physis.core.Digest
import physis.verify.Allocation

class Field2DSuite extends munit.FunSuite:
  private val grid = Grid2D(4, 3)

  /** Interior cell (i, j) holds 10 j + i; the halo holds -1. */
  private def numbered(): Field2D =
    val f = new Field2D(grid)
    f.fillAll(-1.0)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do f(i, j) = 10.0 * j + i
    f

  test("storage includes a halo of one cell on every side") {
    assertEquals(grid.stride, 6)
    assertEquals(grid.rows, 5)
    assertEquals(grid.storageSize, 30)
    assertEquals(grid.cells, 12)
  }

  test("index is row-major and starts the interior one row and one column in") {
    assertEquals(grid.index(0, 0), 7)
    assertEquals(grid.index(3, 0), 10)
    assertEquals(grid.index(0, 1), 13)
    assertEquals(grid.index(-1, -1), 0)
    assertEquals(grid.index(grid.nx, grid.ny), 29)
  }

  test("apply and update address cells, halo included") {
    val f = numbered()
    assertEquals(f(2, 1), 12.0)
    assertEquals(f(-1, 0), -1.0)
    assertEquals(f(grid.nx, grid.ny), -1.0)
    f(-1, 0) = 5.0
    assertEquals(f.data(grid.index(-1, 0)), 5.0)
  }

  test("a grid needs at least one cell and a positive size") {
    intercept[IllegalArgumentException](Grid2D(0, 3))
    intercept[IllegalArgumentException](Grid2D(3, 3, 0.0))
    intercept[IllegalArgumentException](new Field2D(grid, new Array[Double](5)))
  }

  test("fill sets the interior and leaves the halo") {
    val f = numbered()
    f.fill(2.5)
    assertEquals(f(0, 0), 2.5)
    assertEquals(f(3, 2), 2.5)
    assertEquals(f(-1, 0), -1.0)
    assertEquals(f(0, -1), -1.0)
    assertEquals(f(4, 0), -1.0)
  }

  test("sum, mean, min and max look at the interior only") {
    val f = numbered()
    val expected = (for j <- 0 until 3; i <- 0 until 4 yield 10.0 * j + i).sum
    assertEquals(f.sum, expected)
    assertEquals(f.mean, expected / 12)
    assertEquals(f.min, 0.0)
    assertEquals(f.max, 23.0)
    f(-1, -1) = 1e9
    f(grid.nx, grid.ny) = -1e9
    assertEquals(f.max, 23.0)
    assertEquals(f.min, 0.0)
  }

  test("the sum is compensated") {
    val g = Grid2D(1000, 1)
    val f = new Field2D(g)
    f.fill(0.1)
    f(0, 0) = 1e8
    // Naive summation loses about 1e-9 here; the compensated sum is within a few ulps.
    assertEqualsDouble(f.sum, 1e8 + 99.9, 1e-12 * 1e8)
  }

  test("copyFrom copies everything, halo included, and checks the shape") {
    val a = numbered()
    val b = new Field2D(grid)
    b.copyFrom(a)
    assertEquals(b.data.toVector, a.data.toVector)
    intercept[IllegalArgumentException](b.copyFrom(new Field2D(Grid2D(3, 3))))
  }

  test("copyInteriorInto writes the interior row-major at the offset") {
    val out = Array.fill(16)(99.0)
    numbered().copyInteriorInto(out, 2)
    assertEquals(
      out.toVector,
      Vector(99.0, 99.0, 0, 1, 2, 3, 10, 11, 12, 13, 20, 21, 22, 23, 99.0, 99.0)
    )
  }

  test("copyInteriorInto does not allocate") {
    assume(Allocation.supported)
    val f = numbered()
    val out = new Array[Double](grid.cells)
    // Less than one byte per step: a fixed overhead of a few dozen bytes in the whole
    // measurement is not a per-step allocation, a boxed value or a closure would be 16+ per step.
    val bytes = Allocation.perStep(20000)(f.copyInteriorInto(out, 0))
    assert(bytes < 20000L, s"allocated $bytes bytes in 20000 steps")
  }

  test("the digest covers every interior cell and ignores the halo") {
    val f = numbered()
    val reference = f.digest
    val flat = for j <- 0 until 3; i <- 0 until 4 yield 10.0 * j + i
    assertEquals(reference, Digest.ofDoubles(flat*))
    f(-1, 1) = 123.0
    f(grid.nx, 0) = 456.0
    assertEquals(f.digest, reference)
    for j <- 0 until grid.ny; i <- 0 until grid.nx do
      val g = numbered()
      g(i, j) += 1e-9
      assertNotEquals(g.digest, reference, s"cell ($i, $j)")
  }

  test("digestInto chains fields") {
    val a = numbered()
    val b = numbered()
    b.fill(7.0)
    val chained = b.digestInto(a.digestInto(Digest.start))
    val flat = (for j <- 0 until 3; i <- 0 until 4 yield 10.0 * j + i) ++ Seq.fill(12)(7.0)
    assertEquals(chained, Digest.ofDoubles(flat*))
  }
