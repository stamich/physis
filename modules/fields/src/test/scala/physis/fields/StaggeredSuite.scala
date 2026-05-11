package physis.fields

import physis.verify.Allocation

class StaggeredSuite extends munit.FunSuite:
  private val nx = 12
  private val ny = 9

  private def faces(land: Mask, wrapX: Boolean): (Field2D, Field2D) =
    val mac = new MacGrid(nx, ny)
    val ou = new Field2D(mac.uFaces)
    val ov = new Field2D(mac.vFaces)
    Staggered.openFaces(land.field, ou, ov, wrapX)
    (ou, ov)

  test("without land every inner face is open and the border is closed") {
    val land = new Mask(Grid2D(nx, ny))
    val (ou, ov) = faces(land, wrapX = false)
    for j <- 0 until ny; i <- 0 to nx do
      assertEquals(ou(i, j), if i == 0 || i == nx then 0.0 else 1.0, s"u($i,$j)")
    for j <- 0 to ny; i <- 0 until nx do
      assertEquals(ov(i, j), if j == 0 || j == ny then 0.0 else 1.0, s"v($i,$j)")
  }

  test("a wrapping axis opens the left and right faces, never the top and bottom ones") {
    val land = new Mask(Grid2D(nx, ny))
    val (ou, ov) = faces(land, wrapX = true)
    for j <- 0 until ny do
      assertEquals(ou(0, j), 1.0)
      assertEquals(ou(nx, j), 1.0)
    for i <- 0 until nx do
      assertEquals(ov(i, 0), 0.0)
      assertEquals(ov(i, ny), 0.0)
  }

  test("land closes the four faces around it and nothing else") {
    val land = new Mask(Grid2D(nx, ny))
    land(5, 4) = true
    val (ou, ov) = faces(land, wrapX = false)
    assertEquals(ou(5, 4), 0.0)
    assertEquals(ou(6, 4), 0.0)
    assertEquals(ov(5, 4), 0.0)
    assertEquals(ov(5, 5), 0.0)
    assertEquals(ou(4, 4), 1.0)
    assertEquals(ou(7, 4), 1.0)
    assertEquals(ov(4, 4), 1.0)
    assertEquals(ov(6, 5), 1.0)
  }

  test("land on the left border also closes the wrapped face") {
    val land = new Mask(Grid2D(nx, ny))
    land(0, 3) = true
    val (ou, _) = faces(land, wrapX = true)
    assertEquals(ou(0, 3), 0.0)
    assertEquals(ou(nx, 3), 0.0)
    assertEquals(ou(0, 2), 1.0)
  }

  test("the means of a constant are that constant away from the border") {
    val cells = Grid2D(nx, ny)
    val c = new Field2D(cells)
    c.fill(3.0)
    val mac = new MacGrid(nx, ny)
    val mu = new Field2D(mac.uFaces)
    val mv = new Field2D(mac.vFaces)
    Staggered.meanToU(c, mu, wrapX = false)
    Staggered.meanToV(c, mv)
    for j <- 0 until ny; i <- 1 until nx do assertEqualsDouble(mu(i, j), 3.0, 1e-15)
    for j <- 1 until ny; i <- 0 until nx do assertEqualsDouble(mv(i, j), 3.0, 1e-15)
    for j <- 0 until ny do
      assertEquals(mu(0, j), 0.0)
      assertEquals(mu(nx, j), 0.0)
    Staggered.meanToU(c, mu, wrapX = true)
    for j <- 0 until ny; i <- 0 to nx do assertEqualsDouble(mu(i, j), 3.0, 1e-15)
  }

  test("meanToU averages the two neighbours of a face") {
    val c = new Field2D(Grid2D(nx, ny))
    for j <- 0 until ny; i <- 0 until nx do c(i, j) = i * 10.0 + j
    val mu = new Field2D(new MacGrid(nx, ny).uFaces)
    Staggered.meanToU(c, mu, wrapX = true)
    assertEqualsDouble(mu(4, 2), (30.0 + 2.0 + 40.0 + 2.0) / 2.0, 1e-12)
    assertEqualsDouble(mu(0, 2), (110.0 + 2.0 + 0.0 + 2.0) / 2.0, 1e-12)
    assertEquals(mu(nx, 2), mu(0, 2))
  }

  test("the four-point means give a uniform velocity back, with a wrapping side") {
    val mac = new MacGrid(nx, ny)
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    u.fill(2.0)
    v.fill(-5.0)
    val vu = new Field2D(mac.uFaces)
    val uv = new Field2D(mac.vFaces)
    Staggered.vToU(v, vu, wrapX = true)
    Staggered.uToV(u, uv)
    for j <- 0 until ny; i <- 0 to nx do assertEqualsDouble(vu(i, j), -5.0, 1e-15)
    for j <- 1 until ny; i <- 0 until nx do assertEqualsDouble(uv(i, j), 2.0, 1e-15)
  }

  test("beyond a border that does not wrap the missing faces count as zero") {
    val mac = new MacGrid(nx, ny)
    val v = new Field2D(mac.vFaces)
    v.fill(4.0)
    val vu = new Field2D(mac.uFaces)
    Staggered.vToU(v, vu, wrapX = false)
    assertEqualsDouble(vu(0, 3), 2.0, 1e-15)
    assertEqualsDouble(vu(nx, 3), 2.0, 1e-15)
    assertEqualsDouble(vu(5, 3), 4.0, 1e-15)
    val u = new Field2D(mac.uFaces)
    u.fill(4.0)
    val uv = new Field2D(mac.vFaces)
    Staggered.uToV(u, uv)
    assertEqualsDouble(uv(3, 0), 2.0, 1e-15)
    assertEqualsDouble(uv(3, ny), 2.0, 1e-15)
  }

  test("the four-point means pick the right faces") {
    val mac = new MacGrid(nx, ny)
    val v = new Field2D(mac.vFaces)
    for j <- 0 to ny; i <- 0 until nx do v(i, j) = i + 100.0 * j
    val vu = new Field2D(mac.uFaces)
    Staggered.vToU(v, vu, wrapX = false)
    assertEqualsDouble(vu(4, 2), (3 + 200 + 4 + 200 + 3 + 300 + 4 + 300) / 4.0, 1e-12)
    val u = new Field2D(mac.uFaces)
    for j <- 0 until ny; i <- 0 to nx do u(i, j) = i + 100.0 * j
    val uv = new Field2D(mac.vFaces)
    Staggered.uToV(u, uv)
    assertEqualsDouble(uv(4, 3), (4 + 200 + 5 + 200 + 4 + 300 + 5 + 300) / 4.0, 1e-12)
  }

  test("the divergence of the flux through open faces sums to zero: mass can only move") {
    for wrapX <- Seq(false, true) do
      val land = new Mask(Grid2D(nx, ny))
      land(3, 3) = true
      land(4, 3) = true
      land(8, 6) = true
      val (ou, ov) = faces(land, wrapX)
      val mac = new MacGrid(nx, ny, 5.0)
      val u = new Field2D(mac.uFaces)
      val v = new Field2D(mac.vFaces)
      val r = new java.util.Random(11L)
      for j <- 0 until ny; i <- 0 to nx do u(i, j) = (r.nextDouble() - 0.5) * ou(i, j)
      for j <- 0 to ny; i <- 0 until nx do v(i, j) = (r.nextDouble() - 0.5) * ov(i, j)
      if wrapX then for j <- 0 until ny do u(nx, j) = u(0, j)
      val div = new Field2D(mac.cells)
      Mac.divergence(u, v, div)
      assertEqualsDouble(div.sum, 0.0, 1e-12)
  }

  test("the operators do not allocate") {
    assume(Allocation.supported)
    val land = new Mask(Grid2D(nx, ny))
    val mac = new MacGrid(nx, ny)
    val ou = new Field2D(mac.uFaces)
    val ov = new Field2D(mac.vFaces)
    val c = new Field2D(mac.cells)
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    val tu = new Field2D(mac.uFaces)
    val tv = new Field2D(mac.vFaces)
    val bytes = Allocation.perStep(100) {
      Staggered.openFaces(land.field, ou, ov, wrapX = true)
      Staggered.meanToU(c, tu, wrapX = true)
      Staggered.meanToV(c, tv)
      Staggered.vToU(v, tu, wrapX = true)
      Staggered.uToV(u, tv)
    }
    assert(bytes < 100L, s"$bytes bytes")
  }
