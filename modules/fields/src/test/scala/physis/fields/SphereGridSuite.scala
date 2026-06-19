package physis.fields

class SphereGridSuite extends munit.FunSuite:
  private val R = 6.371e6

  test("the cells add up to the area of the band, whatever the resolution") {
    for (nx, ny, lat) <- Seq((36, 16, 80.0), (180, 80, 80.0), (360, 178, 89.0), (8, 4, 30.0)) do
      val g = new SphereGrid(nx, ny, R, lat)
      val total = g.area.sum * nx
      assertEqualsDouble(total, g.bandArea, 1e-12 * g.bandArea, s"$nx x $ny")
      assertEqualsDouble(g.bandArea, 4.0 * math.Pi * R * R * math.sin(math.toRadians(lat)), 1.0)
  }

  test("a band to the pole would be the whole sphere: 89.99 degrees is almost 4 pi R squared") {
    val g = new SphereGrid(72, 90, R, 89.99)
    assertEqualsDouble(g.area.sum * 72, 4.0 * math.Pi * R * R, 1e-6 * 4.0 * math.Pi * R * R)
  }

  test("the rows are symmetric about the equator, north first") {
    val g = new SphereGrid(40, 30, R, 75.0)
    for j <- 0 until g.ny do
      assertEqualsDouble(g.latC(j), -g.latC(g.ny - 1 - j), 1e-14)
      assertEqualsDouble(g.dxC(j), g.dxC(g.ny - 1 - j), 1e-6)
      assertEqualsDouble(g.area(j), g.area(g.ny - 1 - j), 1e-9 * g.area(j))
    assert(g.latC(0) > 0.0 && g.latC(g.ny - 1) < 0.0)
    assertEqualsDouble(g.latF(0), g.latMax, 1e-14)
    assertEqualsDouble(g.latF(g.ny), -g.latMax, 1e-14)
    assertEqualsDouble(g.latDegC(0), 75.0 - 0.5 * 150.0 / 30, 1e-12)
  }

  test("a cell is as high as it is anywhere and narrows as the cosine of its latitude") {
    val g = new SphereGrid(360, 160, R, 80.0)
    assertEqualsDouble(g.dy, R * math.toRadians(1.0), 1e-6)
    for j <- 0 until g.ny do
      assertEqualsDouble(g.dxC(j), R * math.cos(g.latC(j)) * math.toRadians(1.0), 1e-6)
      assertEqualsDouble(g.lenF(j), R * math.cos(g.latF(j)) * g.dlam, 1e-6)
      // the exact area is within a hair of the width times the height
      assertEqualsDouble(g.area(j), g.dxC(j) * g.dy, 2e-4 * g.area(j))
    assertEquals(g.dxMin, g.dxC(0))
    assertEqualsDouble(g.dxMin / g.dxC(80), math.cos(math.toRadians(79.5)), 1e-3)
  }

  test("one over the width is exact at the half rows and linear between them") {
    val g = new SphereGrid(90, 40, R, 70.0)
    for j <- 0 until g.ny do assertEqualsDouble(g.invDx(j + 0.5), 1.0 / g.dxC(j), 1e-15)
    for j <- 0 to g.ny do assertEqualsDouble(g.invDx(j.toDouble), 1.0 / g.lenF(j), 1e-15)
    assertEqualsDouble(g.invDx(3.25), 0.5 * (g.invDx(3.0) + g.invDx(3.5)), 1e-15)
    assertEquals(g.invDx(-5.0), g.invDx(0.0))
    assertEquals(g.invDx(99.0), g.invDx(g.ny.toDouble))
  }

  test("longitudes count east from 180 west and rows south from the north edge") {
    val g = new SphereGrid(36, 18, R, 80.0)
    assertEqualsDouble(g.lonDegC(0), -175.0, 1e-12)
    assertEqualsDouble(g.lonDegC(35), 175.0, 1e-12)
    assertEquals(g.columnOf(-175.0), 0)
    assertEquals(g.columnOf(0.0), 18)
    assertEquals(g.columnOf(185.0), 0)
    assertEquals(g.columnOf(-180.0), 0)
    assertEquals(g.rowOf(80.0), 0)
    assertEquals(g.rowOf(-80.0), 17)
    assertEquals(g.rowOf(0.1), 8)
    assertEquals(g.rowOf(95.0), 0)
    assertEquals(g.rowOf(-95.0), 17)
  }

  test("tan over R is the metric term of the flow") {
    val g = new SphereGrid(36, 18, R, 60.0)
    for j <- 0 until g.ny do assertEqualsDouble(g.tanC(j), math.tan(g.latC(j)) / R, 1e-18)
    for j <- 0 to g.ny do assertEqualsDouble(g.tanF(j), math.tan(g.latF(j)) / R, 1e-18)
  }

  test("a band that is not on the sphere is refused") {
    intercept[IllegalArgumentException](new SphereGrid(2, 10, R, 80.0))
    intercept[IllegalArgumentException](new SphereGrid(36, 18, R, 90.0))
    intercept[IllegalArgumentException](new SphereGrid(36, 18, R, 0.0))
    intercept[IllegalArgumentException](new SphereGrid(36, 18, -1.0, 60.0))
  }
