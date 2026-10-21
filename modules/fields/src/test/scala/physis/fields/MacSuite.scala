package physis.fields

import physis.verify.Allocation

class MacSuite extends munit.FunSuite:
  private val mac = new MacGrid(12, 9)

  private def faces(f: (Double, Double) => Double, g: Grid2D, ox: Double, oy: Double): Field2D =
    val out = new Field2D(g)
    for j <- 0 until g.ny; i <- 0 until g.nx do out(i, j) = f(i + ox, j + oy)
    out

  test("the face grids have one more column (u) or row (v) than the cells") {
    assertEquals((mac.uFaces.nx, mac.uFaces.ny), (13, 9))
    assertEquals((mac.vFaces.nx, mac.vFaces.ny), (12, 10))
    assertEquals(mac.cells.cells, 108)
  }

  test("the divergence of a curl is zero to rounding for any stream function") {
    val random = new java.util.Random(11L)
    val psi = new Field2D(Grid2D(mac.nx + 1, mac.ny + 1))
    for j <- 0 to mac.ny; i <- 0 to mac.nx do psi(i, j) = random.nextDouble() * 10.0 - 5.0
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    Mac.curl(psi, u, v)
    val div = new Field2D(mac.cells)
    Mac.divergence(u, v, div)
    assert(math.max(div.max, -div.min) < 1e-13, s"${div.max} ${div.min}")
    assert(u.max > 0.1, "the velocity is not trivially zero")
  }

  test("the divergence of u = x and v = y is 2 in every cell, and of a constant flow is 0") {
    val u = faces((x, _) => x, mac.uFaces, 0.0, 0.5)
    val v = faces((_, y) => y, mac.vFaces, 0.5, 0.0)
    val div = new Field2D(mac.cells)
    Mac.divergence(u, v, div)
    for j <- 0 until mac.ny; i <- 0 until mac.nx do assertEqualsDouble(div(i, j), 2.0, 1e-13)
    val c = faces((_, _) => 3.0, mac.uFaces, 0.0, 0.5)
    val d = faces((_, _) => -1.0, mac.vFaces, 0.5, 0.0)
    Mac.divergence(c, d, div)
    assertEquals(div.max, 0.0)
    assertEquals(div.min, 0.0)
  }

  test("the gradient of a linear pressure is constant on the inner faces, 0 on closed borders") {
    val p = new Field2D(mac.cells)
    for j <- 0 until mac.ny; i <- 0 until mac.nx do p(i, j) = 2.0 * i - 3.0 * j
    val gu = new Field2D(mac.uFaces)
    val gv = new Field2D(mac.vFaces)
    Mac.gradient(p, gu, gv, wrapX = false, wrapY = false)
    for j <- 0 until mac.ny; i <- 1 until mac.nx do assertEquals(gu(i, j), 2.0)
    for j <- 1 until mac.ny; i <- 0 until mac.nx do assertEquals(gv(i, j), -3.0)
    for j <- 0 until mac.ny do assertEquals((gu(0, j), gu(mac.nx, j)), (0.0, 0.0))
    for i <- 0 until mac.nx do assertEquals((gv(i, 0), gv(i, mac.ny)), (0.0, 0.0))
    // periodic: the border face joins the last cell to the first
    Mac.gradient(p, gu, gv, wrapX = true, wrapY = true)
    assertEquals(gu(0, 3), 2.0 * (0 - (mac.nx - 1)))
    assertEquals(gv(4, 0), -3.0 * (0 - (mac.ny - 1)))
  }

  test("a gradient field has no curl: the circulation round every cell corner is zero") {
    val random = new java.util.Random(12L)
    val p = new Field2D(mac.cells)
    for j <- 0 until mac.ny; i <- 0 until mac.nx do p(i, j) = random.nextDouble()
    val gu = new Field2D(mac.uFaces)
    val gv = new Field2D(mac.vFaces)
    Mac.gradient(p, gu, gv, wrapX = false, wrapY = false)
    for j <- 1 until mac.ny; i <- 1 until mac.nx do
      val circulation = (gv(i, j) - gv(i - 1, j)) - (gu(i, j) - gu(i, j - 1))
      assertEqualsDouble(circulation, 0.0, 1e-13)
  }

  test("the vorticity of a solid-body rotation is twice its rate, away from the borders") {
    val rate = 0.25
    val u = faces((_, y) => -rate * (y - 4.5), mac.uFaces, 0.0, 0.5)
    val v = faces((x, _) => rate * (x - 6.0), mac.vFaces, 0.5, 0.0)
    val w = new Field2D(mac.cells)
    Mac.vorticity(u, v, w, wrapX = false, wrapY = false)
    for j <- 0 until mac.ny; i <- 0 until mac.nx do assertEqualsDouble(w(i, j), 2.0 * rate, 1e-12)
  }

  test("the kinetic energy of a uniform flow is half its speed squared times the area") {
    val u = faces((_, _) => 3.0, mac.uFaces, 0.0, 0.5)
    val v = faces((_, _) => 4.0, mac.vFaces, 0.5, 0.0)
    assertEqualsDouble(Mac.kineticEnergy(u, v, mac.cells), 0.5 * 25.0 * mac.cells.cells, 1e-9)
  }

  test("bilinearAt reads the staggered position: a linear u is reproduced between its samples") {
    val u = faces((x, y) => 1.0 + 0.5 * x - 0.25 * y, mac.uFaces, 0.0, 0.5)
    val random = new java.util.Random(13L)
    for _ <- 1 to 300 do
      val x = random.nextDouble() * mac.nx
      val y = 0.5 + random.nextDouble() * (mac.ny - 1)
      val got =
        Sampler.bilinearAt(u, x, y, Mac.UOffsetX, Mac.UOffsetY, mac.nx, mac.ny, false, false)
      assertEqualsDouble(got, 1.0 + 0.5 * x - 0.25 * y, 1e-12)
    // at a sample position the sample is returned
    assertEquals(Sampler.bilinearAt(u, 3.0, 2.5, 0.0, 0.5, mac.nx, mac.ny, false, false), u(3, 2))
  }

  test("bilinearAt clamps at a border that does not wrap, and wraps on a periodic axis") {
    val v = faces((x, y) => x + 100.0 * y, mac.vFaces, 0.5, 0.0)
    val low = Sampler.bilinearAt(v, 5.5, -3.0, 0.5, 0.0, mac.nx, mac.ny, false, false)
    val high = Sampler.bilinearAt(v, 5.5, 50.0, 0.5, 0.0, mac.nx, mac.ny, false, false)
    assertEqualsDouble(low, 5.5, 1e-12)
    assertEqualsDouble(high, 5.5 + 100.0 * mac.ny, 1e-12)
    // periodic in x: positions a period apart agree, and the copy column is not read
    val u = faces((x, _) => math.sin(2.0 * math.Pi * x / mac.nx), mac.uFaces, 0.0, 0.5)
    for x <- Seq(0.3, 4.7, 11.9) do
      val a = Sampler.bilinearAt(u, x, 3.0, 0.0, 0.5, mac.nx, mac.ny, true, false)
      val b = Sampler.bilinearAt(u, x + mac.nx, 3.0, 0.0, 0.5, mac.nx, mac.ny, true, false)
      val c = Sampler.bilinearAt(u, x - mac.nx, 3.0, 0.0, 0.5, mac.nx, mac.ny, true, false)
      assertEqualsDouble(a, b, 1e-12)
      assertEqualsDouble(a, c, 1e-12)
    u(mac.nx, 3) = 1e9 // the copy of face 0 must not matter on a periodic axis
    val w = Sampler.bilinearAt(u, 11.9, 3.0, 0.0, 0.5, mac.nx, mac.ny, true, false)
    assert(math.abs(w) < 1.0, w)
  }

  test("operators and sampling allocate nothing") {
    assume(Allocation.supported)
    val u = faces((x, y) => x * y, mac.uFaces, 0.0, 0.5)
    val v = faces((x, y) => x - y, mac.vFaces, 0.5, 0.0)
    val div = new Field2D(mac.cells)
    var sink = 0.0
    val bytes = Allocation.perStep(200) {
      Mac.divergence(u, v, div)
      Mac.vorticity(u, v, div, wrapX = true, wrapY = false)
      sink += Sampler.bilinearAt(u, 3.3, 4.4, 0.0, 0.5, mac.nx, mac.ny, true, false)
      sink += Mac.kineticEnergy(u, v, mac.cells)
    }
    assert(bytes < 200L * 1, s"$bytes bytes in 200 rounds ($sink)")
  }
