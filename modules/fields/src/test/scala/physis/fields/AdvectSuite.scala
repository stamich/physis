package physis.fields

import physis.verify.Allocation

class AdvectSuite extends munit.FunSuite:
  private val mac = new MacGrid(32, 24)

  private def fill(f: Field2D)(g: (Int, Int) => Double): Field2D =
    for j <- 0 until f.ny; i <- 0 until f.nx do f(i, j) = g(i, j)
    f

  private def uniform(a: Double, b: Double, wrapX: Boolean, wrapY: Boolean): Advect.Flow =
    val u = fill(new Field2D(mac.uFaces))((_, _) => a)
    val v = fill(new Field2D(mac.vFaces))((_, _) => b)
    new Advect.Flow(u, v, mac.nx, mac.ny, wrapX, wrapY)

  private def random(seed: Long): Field2D =
    val r = new java.util.Random(seed)
    fill(new Field2D(mac.cells))((_, _) => r.nextDouble())

  test("a periodic field moved by a whole number of cells is shifted exactly") {
    val src = random(1L)
    val dst = new Field2D(mac.cells)
    Advect.cells(uniform(3.0, -2.0, true, true), src, dst, 1.0)
    for j <- 0 until mac.ny; i <- 0 until mac.nx do
      val si = ((i - 3) % mac.nx + mac.nx) % mac.nx
      val sj = ((j + 2) % mac.ny + mac.ny) % mac.ny
      assertEqualsDouble(dst(i, j), src(si, sj), 1e-12)
  }

  test("no flow leaves a field as it is") {
    val src = random(2L)
    val dst = new Field2D(mac.cells)
    Advect.cells(uniform(0.0, 0.0, false, false), src, dst, 0.7)
    assertEquals(dst.digest, src.digest)
  }

  test("a linear field moved by a fraction of a cell is moved exactly") {
    val src = fill(new Field2D(mac.cells))((i, j) => 2.0 * i + 5.0 * j)
    val dst = new Field2D(mac.cells)
    Advect.cells(uniform(0.5, 0.25, false, false), src, dst, 1.0)
    for j <- 1 until mac.ny; i <- 1 until mac.nx do
      assertEqualsDouble(dst(i, j), 2.0 * (i - 0.5) + 5.0 * (j - 0.25), 1e-12)
  }

  test("advection makes no new extremes, at any step size") {
    val src = random(3L)
    val u = fill(new Field2D(mac.uFaces))((i, j) => math.sin(0.3 * i + 0.2 * j) * 6.0)
    val v = fill(new Field2D(mac.vFaces))((i, j) => math.cos(0.25 * i - 0.4 * j) * 6.0)
    val flow = new Advect.Flow(u, v, mac.nx, mac.ny, false, false)
    val dst = new Field2D(mac.cells)
    for dt <- Seq(0.5, 2.0, 10.0) do
      Advect.cells(flow, src, dst, dt)
      assert(dst.max <= src.max + 1e-12 && dst.min >= src.min - 1e-12, s"dt = $dt")
  }

  test("u and v faces on a periodic axis stay equal at both ends") {
    val flow = uniform(1.3, 0.8, true, true)
    val su = fill(new Field2D(mac.uFaces))((i, j) => math.sin(0.4 * i) + 0.1 * j)
    val sv = fill(new Field2D(mac.vFaces))((i, j) => math.cos(0.3 * j) + 0.1 * i)
    for j <- 0 until mac.ny do su(mac.nx, j) = su(0, j)
    for i <- 0 until mac.nx do sv(i, mac.ny) = sv(i, 0)
    val du = new Field2D(mac.uFaces)
    val dv = new Field2D(mac.vFaces)
    Advect.facesU(flow, su, du, 0.9)
    Advect.facesV(flow, sv, dv, 0.9)
    for j <- 0 until mac.ny do assertEquals(du(mac.nx, j), du(0, j))
    for i <- 0 until mac.nx do assertEquals(dv(i, mac.ny), dv(i, 0))
  }

  test("a blob in a solid-body rotation turns by the right angle (second-order backtrace)") {
    val omega = 0.05
    val cx = 16.0
    val cy = 12.0
    val u = fill(new Field2D(mac.uFaces))((_, j) => -omega * (j + 0.5 - cy))
    val v = fill(new Field2D(mac.vFaces))((i, _) => omega * (i + 0.5 - cx))
    for j <- 0 until mac.ny do u(mac.nx, j) = -omega * (j + 0.5 - cy)
    for i <- 0 until mac.nx do v(i, mac.ny) = omega * (i + 0.5 - cx)
    val flow = new Advect.Flow(u, v, mac.nx, mac.ny, false, false)
    var a = fill(new Field2D(mac.cells))((i, j) =>
      val dx = i + 0.5 - (cx + 6.0)
      val dy = j + 0.5 - cy
      math.exp(-(dx * dx + dy * dy) / 8.0)
    )
    var b = new Field2D(mac.cells)
    val steps = 30
    for _ <- 1 to steps do
      Advect.cells(flow, a, b, 1.0)
      val t = a; a = b; b = t
    var m = 0.0
    var mx = 0.0
    var my = 0.0
    for j <- 0 until mac.ny; i <- 0 until mac.nx do
      m += a(i, j)
      mx += a(i, j) * (i + 0.5 - cx)
      my += a(i, j) * (j + 0.5 - cy)
    val angle = math.atan2(my / m, mx / m)
    assertEqualsDouble(angle, omega * steps, 0.03)
  }

  test("the same field and flow always give the same bits") {
    val flow = uniform(0.7, -0.4, true, false)
    val a = new Field2D(mac.cells)
    val b = new Field2D(mac.cells)
    Advect.cells(flow, random(4L), a, 0.8)
    Advect.cells(flow, random(4L), b, 0.8)
    assertEquals(a.digest, b.digest)
  }

  test("advecting two views of the same field is refused") {
    val f = random(5L)
    intercept[IllegalArgumentException](Advect.cells(uniform(1, 0, true, true), f, f, 1.0))
  }

  test("advection allocates nothing") {
    assume(Allocation.supported)
    val flow = uniform(0.7, -0.4, true, false)
    val src = random(6L)
    val dst = new Field2D(mac.cells)
    val su = new Field2D(mac.uFaces)
    val du = new Field2D(mac.uFaces)
    val bytes = Allocation.perStep(100) {
      Advect.cells(flow, src, dst, 0.8)
      Advect.facesU(flow, su, du, 0.8)
    }
    assert(bytes < 100L, s"$bytes bytes")
  }
