package physis.fields

class SphereOpsSuite extends munit.FunSuite:
  private val R = 6.371e6

  private final class Rig(nx: Int, ny: Int, lat: Double):
    val g = new SphereGrid(nx, ny, R, lat)
    val mac = new MacGrid(nx, ny, g.dy)
    def cells = new Field2D(mac.cells)
    def uFace = new Field2D(mac.uFaces)
    def vFace = new Field2D(mac.vFaces)

  private def noise(k: Int): Double = math.sin(12.9898 * k + 78.233) * 0.5

  test("the flow out of all the cells is the flow through the walls, which is none") {
    val r = new Rig(48, 30, 80.0)
    val fu = r.uFace
    val fv = r.vFace
    var k = 0
    for j <- 0 until 30; i <- 0 until 48 do
      fu(i, j) = noise(k)
      k += 1
    for j <- 0 until 30 do fu(48, j) = fu(0, j)
    for j <- 1 until 30; i <- 0 until 48 do
      fv(i, j) = noise(k)
      k += 1
    val div = r.cells
    SphereOps.divergence(fu, fv, r.g, div)
    val total = SphereOps.integral(div, r.g)
    val scale = fu.data.map(math.abs).sum * r.g.dy + fv.data.map(math.abs).sum * r.g.lenF(15)
    assertEqualsDouble(total, 0.0, 1e-12 * scale)
    assert(div.data.exists(_ != 0.0))
  }

  test("a flux through a wall does not cancel: the total is that flux") {
    val r = new Rig(12, 8, 60.0)
    val fu = r.uFace
    val fv = r.vFace
    for i <- 0 until 12 do fv(i, 8) = 2.0 // out through the southern edge
    val div = r.cells
    SphereOps.divergence(fu, fv, r.g, div)
    assertEqualsDouble(SphereOps.integral(div, r.g), 2.0 * r.g.lenF(8) * 12, 1e-6)
  }

  test("a uniform eastward flux has no divergence, however the cells narrow") {
    val r = new Rig(36, 20, 70.0)
    val fu = r.uFace
    val fv = r.vFace
    for j <- 0 until 20; i <- 0 to 36 do fu(i, j) = 1.0
    val div = r.cells
    SphereOps.divergence(fu, fv, r.g, div)
    for j <- 0 until 20; i <- 0 until 36 do assertEquals(div(i, j), 0.0)
  }

  test("the gradient of a constant is zero and of a field linear in the distance is one") {
    val r = new Rig(36, 20, 70.0)
    val p = r.cells
    p.fill(5.0)
    val gu = r.uFace
    val gv = r.vFace
    SphereOps.gradientU(p, r.g, gu)
    SphereOps.gradientV(p, r.g, gv)
    assert(gu.data.forall(_ == 0.0) && gv.data.forall(_ == 0.0))
    // p = distance toward the south: the gradient toward the south is 1 on the inner faces
    for j <- 0 until 20; i <- 0 until 36 do p(i, j) = r.g.dy * (j + 0.5)
    SphereOps.gradientV(p, r.g, gv)
    for j <- 1 until 20; i <- 0 until 36 do assertEqualsDouble(gv(i, j), 1.0, 1e-12)
    for i <- 0 until 36 do assertEquals(gv(i, 0) + gv(i, 20), 0.0)
    // p = distance east along the row: the gradient is 1 on the faces that do not cross the seam
    for j <- 0 until 20; i <- 0 until 36 do p(i, j) = r.g.dxC(j) * (i + 0.5)
    SphereOps.gradientU(p, r.g, gu)
    for j <- 0 until 20; i <- 1 until 36 do assertEqualsDouble(gu(i, j), 1.0, 1e-12)
    for j <- 0 until 20 do
      assertEquals(gu(0, j), gu(36, j))
      assertEqualsDouble(gu(0, j), (p(0, j) - p(35, j)) / r.g.dxC(j), 1e-12)
  }

  test("a field that wraps has a gradient across the seam like any other face") {
    val r = new Rig(36, 20, 70.0)
    val p = r.cells
    for j <- 0 until 20; i <- 0 until 36 do p(i, j) = math.cos(r.g.dlam * (i + 0.5))
    val gu = r.uFace
    SphereOps.gradientU(p, r.g, gu)
    // the cells on both sides of the seam are at 5 and 355 degrees, where cos is the same
    for j <- 0 until 20 do assertEqualsDouble(gu(0, j), 0.0, 1e-12)
  }

  test("the vorticity of a solid rotation is twice the angular speed times sin(latitude)") {
    val r = new Rig(180, 80, 80.0)
    val u = r.uFace
    val v = r.vFace
    val u0 = 30.0
    for j <- 0 until 80; i <- 0 to 180 do u(i, j) = u0 * r.g.cosC(j)
    val zeta = r.cells
    SphereOps.vorticity(u, v, r.g, zeta)
    for j <- 1 until 79; i <- 0 until 180 do
      val exact = 2.0 * u0 / R * r.g.sinC(j)
      assertEqualsDouble(zeta(i, j), exact, 1e-3 * math.abs(2.0 * u0 / R) + 1e-12)
  }

  test("the edge of a southward flow that lies to the east spins clockwise, which is negative") {
    val r = new Rig(72, 36, 70.0)
    val u = r.uFace
    val v = r.vFace
    // southward flow v = +1 east of the middle, 0 west of it: seen from above (north up) the water
    // turns counter-clockwise where the flow to the east goes south: zeta = -dv/dx > 0 at the jump
    for j <- 1 until 36; i <- 36 until 72 do v(i, j) = 1.0
    val zeta = r.cells
    SphereOps.vorticity(u, v, r.g, zeta)
    assert(zeta(35, 18) < 0.0, "the edge of a southward jet that lies to the east spins clockwise")
  }

  test("the limit on the outflow keeps every cell at or above no water") {
    val r = new Rig(24, 12, 60.0)
    val fu = r.uFace
    val fv = r.vFace
    val water = r.cells
    water.fill(1.0)
    water(5, 5) = 1e-6
    for j <- 0 until 12; i <- 0 to 24 do fu(i, j) = if i % 2 == 0 then 3.0 else -3.0
    for j <- 1 until 12; i <- 0 until 24 do fv(i, j) = if (i + j) % 2 == 0 then 4.0 else -4.0
    val scale = r.cells
    val dt = 10.0
    SphereOps.limitOutflow(fu, fv, water, dt, r.g, scale)
    val div = r.cells
    SphereOps.divergence(fu, fv, r.g, div)
    for j <- 0 until 12; i <- 0 until 24 do
      assert(
        water(i, j) - dt * div(i, j) >= -1e-9,
        s"cell ($i,$j) is left with ${water(i, j) - dt * div(i, j)}"
      )
    assert(scale(5, 5) < 1.0)
  }

  test("the integral weighs the rows by their area") {
    val r = new Rig(36, 20, 70.0)
    val f = r.cells
    f.fill(1.0)
    assertEqualsDouble(SphereOps.integral(f, r.g), r.g.bandArea, 1e-12 * r.g.bandArea)
  }
