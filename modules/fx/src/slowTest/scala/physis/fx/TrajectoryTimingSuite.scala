package physis.fx

import physis.view.{Camera, Trail}

/** Frame preparation budget: projecting and binning a 20 000-point trail in under 4 ms. */
class TrajectoryTimingSuite extends munit.FunSuite:
  test("preparing 20 000 points into 32 colour bands takes less than 4 ms") {
    val n = 20_000
    val trail = new Trail(n)
    for i <- 0 until n do
      val t = i * 0.01
      trail.append(10 * math.sin(t), 10 * math.cos(1.3 * t), 25 + 10 * math.sin(0.7 * t))
    val camera = new Camera
    camera.viewport(1200, 800)
    val projector = Projector.of(camera)
    val geometry = new TrajectoryGeometry(n, bands = 32)
    // warm up the JIT, then take the median of repeated runs
    for _ <- 1 to 300 do geometry.prepare(trail, projector)
    val times = Vector.fill(101) {
      val t0 = System.nanoTime()
      geometry.prepare(trail, projector)
      System.nanoTime() - t0
    }.sorted
    val medianMs = times(50) / 1e6
    assert(medianMs < 4.0, f"median $medianMs%.2f ms")
    assertEquals((0 until 32).map(geometry.segments).sum, n - 1)
  }
