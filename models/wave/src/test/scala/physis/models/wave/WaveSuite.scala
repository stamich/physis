package physis.models.wave

import physis.core.Stepping
import physis.core.run.{Physis, Run}
import physis.verify.Allocation

class WaveSuite extends munit.FunSuite:
  private val small = WaveParams.schema.defaults.copy(nx = 64, ny = 64)

  private def run(p: WaveParams, stepping: Stepping = Wave.stepping): Run[WaveParams, WaveState] =
    Physis.model(Wave).withParams(p).stepping(stepping).probe(Wave.probes*).build()

  test("the library sine and the series agree") {
    var t = -3.0
    while t < 3.0 do
      assertEqualsDouble(Wave.sinTurns(t), math.sin(2 * math.Pi * t), 1e-13, s"t = $t")
      t += 0.0137
  }

  test("the stability guard accepts C up to 1/sqrt(2) and refuses more, at build") {
    assertEquals(Wave.check(small.copy(c = 1.4), Wave.stepping), Right(()))
    assertEquals(Wave.check(small.copy(c = 1.4142), Stepping.Fixed(0.5)), Right(()))
    val refused = Wave.check(small.copy(c = 1.4), Stepping.Fixed(0.6))
    assert(refused.left.exists(_.contains("stability limit")), refused.toString)
    val e = intercept[IllegalArgumentException](run(small.copy(c = 1.4), Stepping.Fixed(0.6)))
    assert(e.getMessage.contains("Courant"), e.getMessage)
  }

  test("raising c live past the limit is refused with a message and changes nothing") {
    val r = run(small.copy(c = 1.0), Stepping.Fixed(0.55))
    r.runSteps(3)
    val answer = r.setParams(Map("c" -> 1.4))
    assert(answer.left.exists(_.contains("Courant")), answer.toString)
    assertEquals(r.setParams(Map("c" -> 1.2)), Right(()))
    r.step()
    assertEquals(r.params.c, 1.2)
  }

  test("a plane pulse on a periodic grid travels at c") {
    val p = small.copy(nx = 256, ny = 64, edge = Edge.Periodic, c = 1.0)
    val r = run(p)
    val sigma = 6.0
    for k <- 0 until 2 do
      val level = if k == 0 then r.state.u else r.state.previous
      // right-moving f(x - c t); the older level is at t = -dt
      val shift = if k == 0 then 0.0 else p.c * Wave.Dt
      for j <- 0 until 64; i <- 0 until 256 do
        val x = i + 0.5 - 64.0 + shift
        level(i, j) = math.exp(-x * x / (2 * sigma * sigma))
    val steps = 80
    r.runSteps(steps)
    var num = 0.0
    var den = 0.0
    for i <- 0 until 256 do
      val v = r.state.u(i, 10)
      num += v * (i + 0.5)
      den += v
    val travelled = num / den - 64.0
    assertEqualsDouble(travelled, steps * Wave.Dt * p.c, 0.01 * steps * Wave.Dt * p.c)
  }

  test("a step allocates nothing, with the source on and an absorbing edge") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val p = small.copy(edge = Edge.Absorbing, amplitude = 1.0, damping = 0.01)
    val r = run(p)
    val bytes = Allocation.perStep(10000)(r.step())
    assert(bytes < 10000, s"$bytes bytes over 10000 steps")
  }
