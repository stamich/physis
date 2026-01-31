package physis.view

import physis.verify.Allocation

class TrailSuite extends munit.FunSuite:
  test("keeps the newest points in order after wrapping around") {
    val t = new Trail(4)
    for i <- 1 to 6 do t.append(i.toDouble, -i.toDouble, 10.0 * i, 100.0 * i)
    assertEquals(t.size, 4)
    assertEquals((0 until 4).map(t.x), Vector(3.0, 4.0, 5.0, 6.0))
    assertEquals((0 until 4).map(t.y), Vector(-3.0, -4.0, -5.0, -6.0))
    assertEquals(t.z(0), 30.0)
    assertEquals(t.value(3), 600.0)
    assertEquals((t.lastX, t.lastY, t.lastZ), (6.0, -6.0, 60.0))
  }

  test("ages run from 1 (oldest) to 0 (newest) within [0, 1]") {
    val t = new Trail(5)
    t.append(0, 0)
    assertEquals(t.age(0), 0.0)
    for i <- 1 to 9 do t.append(i, i)
    assertEquals((0 until 5).map(t.age), Vector(1.0, 0.75, 0.5, 0.25, 0.0))
  }

  test("out-of-range indices fail; clear empties the trail") {
    val t = new Trail(3)
    t.append(1, 1)
    intercept[IndexOutOfBoundsException](t.x(1))
    t.clear()
    assert(t.isEmpty)
    intercept[IndexOutOfBoundsException](t.x(0))
  }

  test("appending allocates nothing") {
    assume(Allocation.supported, "allocation measurement unavailable")
    val t = new Trail(1000)
    var v = 0.0
    val bytes = Allocation.perStep(100_000) {
      v += 1.0
      t.append(v, v, v, v)
    }
    assert(bytes < 1024, s"allocated $bytes bytes")
  }

class ViewBoundsSuite extends munit.FunSuite:
  private def frame(b: ViewBounds, values: Double*): Unit =
    b.begin()
    values.foreach(b.include(0, _))
    b.end()

  test("grows at once and shrinks smoothly towards the data") {
    val b = new ViewBounds(1, shrink = 0.5)
    assert(b.isEmpty(0))
    frame(b, -1.0, 1.0)
    assertEquals((b.min(0), b.max(0)), (-1.0, 1.0))
    frame(b, -4.0, 0.5)
    assertEquals((b.min(0), b.max(0)), (-4.0, 0.75))
    frame(b, 0.0, 0.5)
    assertEquals((b.min(0), b.max(0)), (-2.0, 0.625))
    for _ <- 1 to 60 do frame(b, 0.0, 0.5)
    assertEqualsDouble(b.min(0), 0.0, 1e-9)
    assertEqualsDouble(b.max(0), 0.5, 1e-9)
  }

  test("constant data still gives a usable range; NaN is ignored") {
    val b = new ViewBounds(2)
    frame(b, 3.0, 3.0, Double.NaN)
    assertEquals((b.min(0), b.max(0)), (2.5, 3.5))
    assertEquals((b.min(1), b.max(1)), (-0.5, 0.5))
    assert(b.isEmpty(1))
  }

  test("union spans several channels") {
    val b = new ViewBounds(2, shrink = 1.0)
    b.begin()
    b.include(0, 1.0)
    b.include(0, 2.0)
    b.include(1, -5.0)
    b.include(1, 0.0)
    b.end()
    assertEquals(b.union(Seq(0, 1)), (-5.0, 2.0))
  }
