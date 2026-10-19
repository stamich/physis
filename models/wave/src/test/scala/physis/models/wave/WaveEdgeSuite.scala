package physis.models.wave

import physis.core.run.{Disc, Op}
import physis.core.run.Intervention.Brush
import WaveSupport.*

class WaveEdgeSuite extends munit.FunSuite:
  private def pulseRun(edge: Int, n: Int = 128, sponge: Int = 24): WaveRun =
    val p = WaveParams.schema.defaults.copy(edge = edge, nx = n, ny = n, sponge = sponge)
    val r = run(p)
    val _ = r.intervene(Brush("u", Disc(n / 2.0, n / 2.0, 5.0), Op.Assign, 1.0))
    r.step()
    r

  private def energy(r: WaveRun): Double = Wave.energy.measure(r.state, r.time)

  test("closed edges keep the energy of a pulse that has crossed the grid") {
    for edge <- Seq(Edge.Periodic, Edge.Reflective, Edge.Free) do
      val r = pulseRun(edge)
      val e0 = energy(r)
      r.runSteps(500)
      assert(energy(r) / e0 > 0.99, s"edge $edge kept ${energy(r) / e0}")
  }

  test("the sponge takes up a pulse that met the border: less than 3 % is left") {
    val r = pulseRun(Edge.Absorbing)
    val e0 = energy(r)
    r.runSteps(500)
    val left = energy(r) / e0
    assert(left < 0.03, s"absorbing edge kept $left")
  }

  test("a wider sponge absorbs at least as much") {
    def left(width: Int): Double =
      val r = pulseRun(Edge.Absorbing, sponge = width)
      val e0 = energy(r)
      r.runSteps(500)
      energy(r) / e0
    assert(left(32) <= left(8) + 1e-12)
  }

  test("a reflective edge flips the sign of an echo, a free edge keeps it") {
    def echo(edge: Int): Double =
      val p = WaveParams.schema.defaults.copy(edge = edge, nx = 128, ny = 256)
      val r = run(p)
      // a plane pulse: a stripe across the whole height, at rest, splits into two halves
      for j <- 0 until 256; i <- 18 until 22 do
        r.state.u(i, j) = 1.0
        r.state.previous(i, j) = 1.0
      var best = 0.0
      var steps = 0
      while steps * Wave.Dt < 66.0 do
        r.step()
        steps += 1
        val v = r.state.u(40, 128)
        if steps * Wave.Dt >= 54.0 && math.abs(v) > math.abs(best) then best = v
      best
    val reflective = echo(Edge.Reflective)
    val free = echo(Edge.Free)
    assert(reflective < -0.3, s"reflective echo $reflective")
    assert(free > 0.3, s"free echo $free")
  }

  test("on a periodic grid a pulse at the left edge is also at the right edge") {
    val p = WaveParams.schema.defaults.copy(edge = Edge.Periodic, nx = 128, ny = 128)
    val r = run(p)
    val _ = r.intervene(
      physis.core.run.Intervention.Pulse("u", 1.0, 64.5, 3.0, 1.0)
    )
    r.step()
    assert(r.state.u(127, 64) > 0.1)
    assertEquals(r.state.u(127, 64), r.state.u(2, 64))
  }

  test("changing the border needs a restart") {
    val r = run(WaveParams.schema.defaults.copy(nx = 64, ny = 64))
    val answer = r.setParams(Map("edge" -> 3.0))
    assert(answer.left.exists(_.contains("requires a restart")), answer.toString)
  }
