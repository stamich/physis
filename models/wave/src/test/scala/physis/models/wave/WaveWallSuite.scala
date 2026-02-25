package physis.models.wave

import physis.core.run.{Disc, Op}
import physis.core.run.Intervention.{Brush, Pulse}
import WaveSupport.*

class WaveWallSuite extends munit.FunSuite:
  private val open = WaveParams.schema.defaults.copy(edge = Edge.Absorbing, nx = 256, ny = 256)

  test("an echo from a wall arrives after 2 d / c, within 2 cells") {
    val r = run(open)
    // a vertical wall of discs, its face at x = 138.5
    for k <- 0 until 36 do
      val _ = r.intervene(Brush("wall", Disc(141.0, 60.5 + 4.0 * k, 2.5), Op.Assign, 1.0))
    val _ = r.intervene(Pulse("u", 100.5, 128.5, 3.0, 1.0))
    val observer = (80, 128)
    // path: source -> wall face -> observer
    val expected = ((138.5 - 100.5) + (138.5 - 80.5)) / open.c
    var bestTime = 0.0
    var best = 0.0
    var steps = 0
    while steps * Wave.Dt < 140.0 do
      r.step()
      steps += 1
      val t = steps * Wave.Dt
      if t > 60.0 then
        val v = math.abs(r.state.u(observer._1, observer._2))
        if v > best then
          best = v
          bestTime = t
    assert(best > 1e-3, s"no echo found, best $best")
    assert(math.abs(bestTime - expected) <= 2.0, s"echo at $bestTime, expected $expected")
  }

  test("cells of a wall stay at zero while waves hit them") {
    val r = run(open.copy(edge = Edge.Reflective))
    val _ = r.intervene(Brush("wall", Disc(130.5, 128.5, 10.0), Op.Assign, 1.0))
    val _ = r.intervene(Pulse("u", 100.5, 128.5, 3.0, 1.0))
    r.runSteps(150)
    var touched = 0
    for j <- 0 until 256; i <- 0 until 256 do
      if r.state.wall(i, j) then
        touched += 1
        assertEquals(r.state.u(i, j), 0.0)
        assertEquals(r.state.previous(i, j), 0.0)
    assert(touched > 250)
    assertEquals(touched, r.state.wall.count)
    assert(r.state.u.max > 0.0)
  }

  test("painting a wall over a moving wave removes the wave under it, erasing opens the cells") {
    val r = run(open)
    val _ = r.intervene(Pulse("u", 128.5, 128.5, 6.0, 1.0))
    r.runSteps(10)
    assert(r.state.u(128, 128) != 0.0)
    val _ = r.intervene(Brush("wall", Disc(128.5, 128.5, 4.0), Op.Assign, 1.0))
    r.step()
    assertEquals(r.state.u(128, 128), 0.0)
    assert(r.state.wall(128, 128))
    val _ = r.intervene(Brush("wall", Disc(128.5, 128.5, 4.0), Op.Erase, 0.0))
    r.runSteps(8)
    assert(!r.state.wall(128, 128))
    assertEquals(r.state.wall.count, 0)
    assert(r.state.u(128, 128) != 0.0)
  }
