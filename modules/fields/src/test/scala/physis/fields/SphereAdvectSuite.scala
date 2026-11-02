package physis.fields

class SphereAdvectSuite extends munit.FunSuite:
  private val R = 6.371e6
  private val nx = 36
  private val ny = 20
  private val g = new SphereGrid(nx, ny, R, 70.0)
  private val mac = new MacGrid(nx, ny, g.dy)

  private def flow(u: Field2D, v: Field2D) = new Advect.Flow(u, v, nx, ny, true, false)

  test("with no flow the velocity stays where it is") {
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    val src = new Field2D(mac.uFaces)
    val dst = new Field2D(mac.uFaces)
    for j <- 0 until ny; i <- 0 until nx do src(i, j) = math.sin(0.7 * i) + j
    for j <- 0 until ny do src(nx, j) = src(0, j)
    SphereAdvect.facesU(flow(u, v), g, src, dst, 100.0)
    for j <- 0 until ny; i <- 0 to nx do assertEqualsDouble(dst(i, j), src(i, j), 1e-12)
    val srcV = new Field2D(mac.vFaces)
    val dstV = new Field2D(mac.vFaces)
    for j <- 0 to ny; i <- 0 until nx do srcV(i, j) = math.cos(0.3 * i) * j
    SphereAdvect.facesV(flow(u, v), g, srcV, dstV, 100.0)
    for j <- 0 to ny; i <- 0 until nx do assertEqualsDouble(dstV(i, j), srcV(i, j), 1e-12)
  }

  test("a flow of one cell a second moves the pattern one column a second, at every latitude") {
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    // u = the width of the cell: one column a second on every row
    for j <- 0 until ny; i <- 0 to nx do u(i, j) = g.dxC(j)
    val src = new Field2D(mac.uFaces)
    for j <- 0 until ny; i <- 0 until nx do src(i, j) = math.sin(0.7 * i) + 0.1 * j
    for j <- 0 until ny do src(nx, j) = src(0, j)
    val dst = new Field2D(mac.uFaces)
    SphereAdvect.facesU(flow(u, v), g, src, dst, 1.0)
    for j <- 0 until ny; i <- 0 to nx do
      val from = (i - 1 + nx) % nx
      assertEqualsDouble(dst(i, j), src(from, j), 1e-9, s"($i,$j)")
    for j <- 0 until ny do assertEquals(dst(nx, j), dst(0, j))
  }

  test("the same speed in m/s moves a pattern further along a row near the pole") {
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    val speed = g.dxC(ny / 2) // one column a second at the equator
    for j <- 0 until ny; i <- 0 to nx do u(i, j) = speed
    val src = new Field2D(mac.uFaces)
    for j <- 0 until ny; i <- 0 to nx do src(i, j) = i.toDouble % nx
    val dst = new Field2D(mac.uFaces)
    val dt = 0.25
    SphereAdvect.facesU(flow(u, v), g, src, dst, dt)
    // far from the seam, a column of the source moves by speed * dt / dxC(row) columns
    val equator = ny / 2
    val pole = 1
    val shiftEq = src(20, equator) - dst(20, equator)
    val shiftPole = src(20, pole) - dst(20, pole)
    assertEqualsDouble(shiftEq, dt * speed / g.dxC(equator), 1e-6)
    assertEqualsDouble(shiftPole, dt * speed / g.dxC(pole), 1e-3)
    assert(shiftPole > 1.5 * shiftEq)
  }

  test("two fields that are the same are refused") {
    val u = new Field2D(mac.uFaces)
    val v = new Field2D(mac.vFaces)
    intercept[IllegalArgumentException](SphereAdvect.facesU(flow(u, v), g, u, u, 1.0))
  }
