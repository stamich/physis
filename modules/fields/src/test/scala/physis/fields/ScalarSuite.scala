package physis.fields

import physis.verify.Allocation

class ScalarSuite extends munit.FunSuite:
  private val mac = new MacGrid(24, 16)

  private def uniform(a: Double, b: Double): Advect.Flow =
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    u.fillAll(a)
    v.fillAll(b)
    new Advect.Flow(u, v, mac.nx, mac.ny, true, true)

  test("a mode between two cold walls decays by the exact discrete factor") {
    val s = new Scalar(mac.cells, Boundaries(Boundary.Periodic, Boundary.Dirichlet(0.0)))
    for j <- 0 until mac.ny; i <- 0 until mac.nx do
      s.field(i, j) = math.sin(math.Pi * (j + 0.5) / mac.ny)
    val step = 0.2
    val factor = 1.0 - step * (2.0 - 2.0 * math.cos(math.Pi / mac.ny))
    for _ <- 1 to 80 do s.diffuse(step)
    val expected = math.pow(factor, 80.0)
    for j <- 0 until mac.ny; i <- 0 until mac.nx do
      assertEqualsDouble(s.field(i, j), expected * math.sin(math.Pi * (j + 0.5) / mac.ny), 1e-12)
  }

  test("insulated walls keep the total") {
    val s = new Scalar(mac.cells, Boundaries.neumann)
    val r = new java.util.Random(3L)
    for j <- 0 until mac.ny; i <- 0 until mac.nx do s.field(i, j) = r.nextDouble()
    val before = s.field.sum
    for _ <- 1 to 400 do s.diffuse(0.2)
    assertEqualsDouble(s.field.sum, before, 1e-9)
  }

  test("a whole number of cells of uniform flow shifts a periodic scalar exactly") {
    val s = new Scalar(mac.cells, Boundaries.periodic)
    val r = new java.util.Random(5L)
    for j <- 0 until mac.ny; i <- 0 until mac.nx do s.field(i, j) = r.nextDouble()
    val before = new Field2D(mac.cells)
    before.copyFrom(s.field)
    s.advect(uniform(2.0, -1.0), 1.0)
    for j <- 0 until mac.ny; i <- 0 until mac.nx do
      val si = ((i - 2) % mac.nx + mac.nx) % mac.nx
      val sj = ((j + 1) % mac.ny + mac.ny) % mac.ny
      assertEqualsDouble(s.field(i, j), before(si, sj), 1e-12)
  }

  test("a scalar with no diffusion is left as it is") {
    val s = new Scalar(mac.cells, Boundaries.periodic)
    s.field(3, 3) = 1.0
    val digest = s.field.digest
    s.diffuse(0.0)
    assertEquals(s.field.digest, digest)
  }

  test("advection and diffusion allocate nothing") {
    assume(Allocation.supported)
    val s = new Scalar(mac.cells, Boundaries(Boundary.Periodic, Boundary.Dirichlet(0.0)))
    s.field(5, 5) = 1.0
    val flow = uniform(0.6, 0.3)
    val bytes = Allocation.perStep(100) {
      s.advect(flow, 0.5)
      s.diffuse(0.1)
    }
    assert(bytes < 100L, s"$bytes bytes")
  }
