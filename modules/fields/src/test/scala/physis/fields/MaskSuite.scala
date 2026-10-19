package physis.fields

import physis.core.run.{Disc, Op}
import physis.verify.Allocation

class MaskSuite extends munit.FunSuite:
  private val grid = Grid2D(32, 32)

  test("the count follows painting and erasing") {
    val m = new Mask(grid)
    assertEquals(m.count, 0)
    val n = m.paint(Disc(10.5, 10.5, 3.0), false, false, Op.Assign, 1.0)
    assertEquals(n, Brush.count(grid, Disc(10.5, 10.5, 3.0), false, false))
    assertEquals(m.count, n)
    assertEquals(m.paint(Disc(10.5, 10.5, 3.0), false, false, Op.Assign, 1.0), 0)
    val cleared = m.paint(Disc(10.5, 10.5, 1.0), false, false, Op.Erase, 1.0)
    assertEquals(cleared, 5)
    assertEquals(m.count, n - 5)
    assert(!m(10, 10) && m(10, 13))
    m.recount()
    assertEquals(m.count, n - 5)
    assertEquals(m.field.sum, (n - 5).toDouble)
  }

  test("assign zero clears, add needs a positive value, single cells can be set") {
    val m = new Mask(grid)
    m.paint(Disc(5.5, 5.5, 2.0), false, false, Op.Add, 1.0)
    val before = m.count
    assert(before > 0)
    assertEquals(m.paint(Disc(5.5, 5.5, 2.0), false, false, Op.Add, -1.0), 0)
    assertEquals(m.count, before)
    m.paint(Disc(5.5, 5.5, 2.0), false, false, Op.Assign, 0.0)
    assertEquals(m.count, 0)
    m(3, 4) = true
    m(3, 4) = true
    assertEquals(m.count, 1)
    m(3, 4) = false
    assertEquals(m.count, 0)
    m.paint(Disc(5.5, 5.5, 2.0), false, false, Op.Assign, 1.0)
    m.clear()
    assertEquals(m.count, 0)
    assertEquals(m.field.sum, 0.0)
  }

  test("painting wraps on periodic axes") {
    val m = new Mask(grid)
    m.paint(Disc(0.0, 16.0, 2.0), true, false, Op.Assign, 1.0)
    assert(m(31, 15) && m(0, 15))
  }

  test("a pulse has the stated peak, is symmetric and decays like a Gaussian") {
    val f = new Field2D(grid)
    Pulse.stamp(f, 16.5, 16.5, 2.0, 1.5, false, false)
    assertEqualsDouble(f(16, 16), 1.5, 1e-15)
    for d <- 1 to 6 do
      assertEquals(f(16 + d, 16), f(16 - d, 16))
      assertEquals(f(16, 16 + d), f(16, 16 - d))
      assertEquals(f(16 + d, 16), f(16, 16 + d))
      assertEqualsDouble(f(16 + d, 16), 1.5 * math.exp(-d * d / 8.0), 1e-15)
    // the volume of a Gaussian: amplitude * 2 pi sigma^2 (cells have unit area)
    assertEqualsDouble(f.sum, 1.5 * 2 * math.Pi * 4.0, 1e-3)
  }

  test("a pulse adds to what is there and wraps on periodic axes") {
    val f = new Field2D(grid)
    f.fill(1.0)
    Pulse.stamp(f, 0.0, 16.5, 1.5, 2.0, true, false)
    assertEqualsDouble(f(0, 16), 1.0 + 2.0 * math.exp(-0.25 / (2 * 2.25)), 1e-12)
    assertEquals(f(31, 16), f(0, 16))
    assertEquals(f(20, 16), 1.0)
  }

  test("painting and stamping allocate nothing after warm-up") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val m = new Mask(Grid2D(256, 256))
    val f = new Field2D(Grid2D(256, 256))
    val disc = Disc(100.2, 100.7, 9.0)
    val bytes = Allocation.perStep(1000) {
      val _ = m.paint(disc, true, true, Op.Assign, 1.0)
      val _ = Pulse.stamp(f, 100.2, 100.7, 3.0, 0.001, true, true)
    }
    assert(bytes < 1000, s"$bytes bytes over 1000 calls")
  }
