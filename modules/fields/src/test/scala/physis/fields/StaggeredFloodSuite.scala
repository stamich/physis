package physis.fields

class StaggeredFloodSuite extends munit.FunSuite:
  private val nx = 10
  private val ny = 6
  private val mac = new MacGrid(nx, ny, 2.0)

  private def cells(f: (Int, Int) => Double): Field2D =
    val c = new Field2D(mac.cells)
    for j <- 0 until ny; i <- 0 until nx do c(i, j) = f(i, j)
    c

  test("the depth of the water is the surface over the bed, never below 0") {
    val eta = cells((i, _) => if i < 5 then 0.0 else 1.0)
    val bed = cells((i, _) => if i < 5 then -2.0 else 1.5)
    val out = new Field2D(mac.cells)
    Staggered.waterDepth(eta, bed, out)
    assertEquals(out(2, 3), 2.0)
    assertEquals(out(7, 3), 0.0) // the surface stands below the bed: dry
  }

  test("over a flat bed the depth of a face is the mean of its two cells, as meanToU") {
    val bed = cells((_, _) => -3.0)
    val eta = cells((i, j) => 0.1 * i + 0.01 * j)
    val depth = new Field2D(mac.uFaces)
    val wet = new Field2D(mac.uFaces)
    Staggered.sillDepthU(eta, bed, 1e-6, wrapX = false, depth, wet)
    for j <- 0 until ny; i <- 1 until nx do
      assertEqualsDouble(depth(i, j), 3.0 + 0.5 * (eta(i - 1, j) + eta(i, j)), 1e-12)
      assertEquals(wet(i, j), 1.0)
    for j <- 0 until ny do
      assertEquals(depth(0, j), 0.0)
      assertEquals(wet(nx, j), 0.0)
  }

  test("a wrapping axis has a face between the last cell and the first") {
    val bed = cells((_, _) => -1.0)
    val eta = cells((_, _) => 0.0)
    val depth = new Field2D(mac.uFaces)
    val wet = new Field2D(mac.uFaces)
    Staggered.sillDepthU(eta, bed, 1e-6, wrapX = true, depth, wet)
    assertEquals(depth(0, 2), 1.0)
    assertEquals(depth(nx, 2), 1.0)
    assertEquals(wet(0, 2), 1.0)
  }

  test(
    "water does not stand over a sill higher than its surface: a lake at rest is dry at the shore"
  ) {
    // bed: -2 under the sea, rising to +0.5 on the land; a dry cell keeps eta equal to its bed
    val bed = cells((i, _) => if i < 5 then -2.0 else 0.5)
    val eta = cells((i, _) => if i < 5 then 0.0 else 0.5)
    val depth = new Field2D(mac.uFaces)
    val wet = new Field2D(mac.uFaces)
    Staggered.sillDepthU(eta, bed, 1e-6, wrapX = false, depth, wet)
    assertEquals(wet(3, 2), 1.0) // sea to sea
    assertEquals(depth(5, 2), 0.0) // the shore: the sea surface (0) is below the land (0.5)
    assertEquals(wet(5, 2), 0.0)
    assertEquals(wet(7, 2), 0.0) // land to land
  }

  test("a surface above the land puts water over the sill, half of the excess at the face") {
    val bed = cells((i, _) => if i < 5 then -2.0 else 0.5)
    val eta = cells((i, _) => if i < 5 then 1.5 else 0.5)
    val depth = new Field2D(mac.uFaces)
    val wet = new Field2D(mac.uFaces)
    Staggered.sillDepthU(eta, bed, 1e-6, wrapX = false, depth, wet)
    assertEqualsDouble(depth(5, 2), 0.5 * (1.0 + 0.0), 1e-12)
    assertEquals(wet(5, 2), 1.0)
  }

  test("a face with less water than the threshold is dry") {
    val bed = cells((_, _) => 0.0)
    val eta = cells((i, _) => if i == 4 then 0.004 else 0.0)
    val depth = new Field2D(mac.uFaces)
    val wet = new Field2D(mac.uFaces)
    Staggered.sillDepthU(eta, bed, 0.01, wrapX = false, depth, wet)
    assertEquals(wet(5, 1), 0.0)
    Staggered.sillDepthU(eta, bed, 0.001, wrapX = false, depth, wet)
    assertEquals(wet(5, 1), 1.0)
  }

  test("the horizontal faces work the same way, and the top and bottom ones are dry") {
    val bed = cells((_, j) => if j < 3 then -2.0 else 0.5)
    val eta = cells((_, j) => if j < 3 then 1.5 else 0.5)
    val depth = new Field2D(mac.vFaces)
    val wet = new Field2D(mac.vFaces)
    Staggered.sillDepthV(eta, bed, 1e-6, depth, wet)
    assertEqualsDouble(depth(4, 3), 0.5, 1e-12)
    assertEquals(wet(4, 3), 1.0)
    assertEquals(wet(4, 0), 0.0)
    assertEquals(wet(4, ny), 0.0)
    assertEquals(depth(4, 1), 3.5)
  }

  test("the limiter cuts what a cell gives away to what it holds, and leaves inflow alone") {
    for wrapX <- Seq(false, true) do
      val water = cells((i, j) => if i == 4 && j == 2 then 0.1 else 1.0)
      val u = new Field2D(mac.uFaces)
      val v = new Field2D(mac.vFaces)
      // a lot leaves cell (4, 2) to the east and the south, and some comes in from the west
      u(5, 2) = 40.0
      v(4, 3) = 40.0
      u(4, 2) = 1.0
      val scale = new Field2D(mac.cells)
      val dt = 0.5
      Staggered.limitOutflow(u, v, water, dt, 2.0, wrapX, scale)
      val out = u(5, 2) + v(4, 3)
      assertEqualsDouble(out * dt, 0.1 * 2.0, 1e-12) // all it holds: depth times cell size / dt
      assertEquals(u(4, 2), 1.0) // inflow to (4, 2) is the outflow of (3, 2), which has room
      assertEqualsDouble(u(5, 2) / v(4, 3), 1.0, 1e-12)
  }

  test("after the limiter no cell ends up with negative water, at any flux") {
    val rng = new java.util.Random(7)
    for wrapX <- Seq(false, true) do
      val water = cells((_, _) => rng.nextDouble() * 0.3)
      val u = new Field2D(mac.uFaces)
      val v = new Field2D(mac.vFaces)
      for j <- 0 until ny; i <- 0 to nx do u(i, j) = (rng.nextDouble() - 0.5) * 50.0
      if wrapX then for j <- 0 until ny do u(nx, j) = u(0, j)
      else
        for j <- 0 until ny do
          u(0, j) = 0.0
          u(nx, j) = 0.0
      for j <- 1 until ny; i <- 0 until nx do v(i, j) = (rng.nextDouble() - 0.5) * 50.0
      val scale = new Field2D(mac.cells)
      val dt = 0.7
      Staggered.limitOutflow(u, v, water, dt, 2.0, wrapX, scale)
      val div = new Field2D(mac.cells)
      Mac.divergence(u, v, div)
      for j <- 0 until ny; i <- 0 until nx do
        assert(water(i, j) - dt * div(i, j) >= -1e-12, s"cell ($i,$j) went below 0")
      if wrapX then for j <- 0 until ny do assertEquals(u(nx, j), u(0, j))
  }
