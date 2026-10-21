package physis.fields

import physis.verify.Allocation

class ProjectionSuite extends munit.FunSuite:
  private val mac = new MacGrid(32, 24)
  private val Tol = 1e-11

  private def randomFaces(seed: Long): (Field2D, Field2D) =
    val r = new java.util.Random(seed)
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    for j <- 0 until u.ny; i <- 0 until u.nx do u(i, j) = r.nextDouble() - 0.5
    for j <- 0 until v.ny; i <- 0 until v.nx do v(i, j) = r.nextDouble() - 0.5
    (u, v)

  private def syncPeriodic(u: Field2D, v: Field2D, wrapX: Boolean, wrapY: Boolean): Unit =
    if wrapX then for j <- 0 until u.ny do u(mac.nx, j) = u(0, j)
    if wrapY then for i <- 0 until v.nx do v(i, mac.ny) = v(i, 0)

  private def energy(u: Field2D, v: Field2D): Double =
    var s = 0.0
    for j <- 0 until u.ny; i <- 0 until u.nx do s += u(i, j) * u(i, j)
    for j <- 0 until v.ny; i <- 0 until v.nx do s += v(i, j) * v(i, j)
    s

  private def maxDiff(a: Field2D, b: Field2D): Double =
    var m = 0.0
    for j <- 0 until a.ny; i <- 0 until a.nx do m = math.max(m, math.abs(a(i, j) - b(i, j)))
    m

  private def box(): Projection =
    val p = new Projection(mac, false, false, false)
    p.configure(new Field2D(mac.cells))
    p

  test("in a closed box random noise becomes free of divergence, with the walls at rest") {
    val p = box()
    val (u, v) = randomFaces(1L)
    p.closeFaces(u, v)
    assert(p.maxDivergence(u, v) > 0.1)
    val before = energy(u, v)
    val iterations = p.project(u, v, Tol, 5000)
    assert(iterations > 0)
    assert(p.maxDivergence(u, v) < 1e-6, p.maxDivergence(u, v))
    for j <- 0 until mac.ny do assertEquals((u(0, j), u(mac.nx, j)), (0.0, 0.0))
    for i <- 0 until mac.nx do assertEquals((v(i, 0), v(i, mac.ny)), (0.0, 0.0))
    assert(energy(u, v) < before, "a projection never adds energy")
  }

  test("projecting twice changes nothing: the second projection leaves the velocity as it is") {
    val p = box()
    val (u, v) = randomFaces(2L)
    p.closeFaces(u, v)
    p.project(u, v, Tol, 5000)
    val u2 = new Field2D(mac.uFaces)
    u2.copyFrom(u)
    p.project(u, v, Tol, 5000)
    assert(maxDiff(u, u2) < 1e-9, maxDiff(u, u2))
  }

  test("a flow that is free of divergence passes unchanged (periodic stream function)") {
    val p = new Projection(mac, true, true, false)
    p.configure(new Field2D(mac.cells))
    val psi = new Field2D(Grid2D(mac.nx + 1, mac.ny + 1))
    for j <- 0 to mac.ny; i <- 0 to mac.nx do
      psi(i, j) = math.sin(2 * math.Pi * i / mac.nx) * math.sin(2 * math.Pi * j / mac.ny)
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    Mac.curl(psi, u, v)
    val u0 = new Field2D(mac.uFaces)
    u0.copyFrom(u)
    p.project(u, v, Tol, 5000)
    assert(maxDiff(u, u0) < 1e-9, maxDiff(u, u0))
  }

  test("a gradient is removed completely (periodic)") {
    val p = new Projection(mac, true, true, false)
    p.configure(new Field2D(mac.cells))
    val phi = new Field2D(mac.cells)
    for j <- 0 until mac.ny; i <- 0 until mac.nx do
      phi(i, j) =
        math.cos(2 * math.Pi * (i + 0.5) / mac.nx) + math.sin(2 * math.Pi * (j + 0.5) / mac.ny)
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    Mac.gradient(phi, u, v, wrapX = true, wrapY = true)
    syncPeriodic(u, v, true, true)
    assert(energy(u, v) > 0.1)
    p.project(u, v, Tol, 5000)
    assert(energy(u, v) < 1e-14, energy(u, v))
  }

  test("a solid block: no flow into it, none inside, and no divergence around it") {
    val solid = new Field2D(mac.cells)
    for j <- 8 until 16; i <- 10 until 16 do solid(i, j) = 1.0
    val p = new Projection(mac, false, false, false)
    p.configure(solid)
    val (u, v) = randomFaces(3L)
    p.closeFaces(u, v)
    p.project(u, v, Tol, 5000)
    assert(p.maxDivergence(u, v) < 1e-6)
    for j <- 8 until 16 do
      assertEquals(u(10, j), 0.0)
      assertEquals(u(16, j), 0.0)
    for i <- 10 until 16 do
      assertEquals(v(i, 8), 0.0)
      assertEquals(v(i, 16), 0.0)
  }

  test("an inflow on the left leaves through the open right border") {
    val p = new Projection(mac, false, false, true)
    p.configure(new Field2D(mac.cells))
    val (u, v) = randomFaces(4L)
    p.closeFaces(u, v)
    for j <- 0 until mac.ny do u(0, j) = 1.0
    p.project(u, v, Tol, 5000)
    assert(p.maxDivergence(u, v) < 1e-6)
    var out = 0.0
    for j <- 0 until mac.ny do out += u(mac.nx, j)
    assertEqualsDouble(out, mac.ny.toDouble, 1e-5)
    for j <- 0 until mac.ny do assertEquals(u(0, j), 1.0)
  }

  test("projecting the same field twice from the same start gives the same bits") {
    def run(): (Long, Long) =
      val p = box()
      val (u, v) = randomFaces(5L)
      p.closeFaces(u, v)
      p.project(u, v, Tol, 5000)
      (u.digest, v.digest)
    assertEquals(run(), run())
  }

  test("a projection allocates nothing") {
    assume(Allocation.supported)
    val p = box()
    val (u, v) = randomFaces(6L)
    p.closeFaces(u, v)
    p.project(u, v, Tol, 5000)
    val bytes = Allocation.perStep(20) {
      p.closeFaces(u, v)
      p.project(u, v, Tol, 5000): Unit
    }
    assert(bytes < 20L * 100, s"$bytes bytes in 20 projections")
  }
