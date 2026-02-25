package physis.models.wave

import WaveSupport.*

class WaveSourceSuite extends munit.FunSuite:
  private val open = WaveParams.schema.defaults.copy(edge = Edge.Absorbing, nx = 128, ny = 128)

  test("amplitude 0 leaves the field at rest") {
    val r = run(open.copy(amplitude = 0.0))
    r.runSteps(300)
    assertEquals(r.state.u.max, 0.0)
    assertEquals(r.state.u.min, 0.0)
  }

  test("the source drives the centre cell at its frequency") {
    val f = 0.1
    val r = run(open.copy(amplitude = 1.0, frequency = f))
    r.runSteps(100)
    var crossings = 0
    var last = r.state.atSource
    val from = r.time
    r.runSteps(800)
    var steps = 0
    while steps < 800 do
      r.step()
      steps += 1
      val v = r.state.atSource
      if (v < 0.0) != (last < 0.0) then crossings += 1
      last = v
    val seconds = steps * Wave.Dt
    val expected = 2.0 * f * seconds
    assert(math.abs(crossings - expected) <= 4.0, s"$crossings crossings, expected $expected")
    assert(from > 0.0)
    assert(r.state.u.max > 0.0 && r.state.u.min < 0.0)
  }

  test("a wall on the source cell silences it") {
    import physis.core.run.{Disc, Op}
    import physis.core.run.Intervention.Brush
    val r = run(open.copy(amplitude = 1.0))
    val _ = r.intervene(Brush("wall", Disc(64.5, 64.5, 1.0), Op.Assign, 1.0))
    r.runSteps(200)
    assertEquals(r.state.atSource, 0.0)
  }

  test("changing the source live is an ordinary parameter event") {
    val r = run(open)
    r.runSteps(10)
    assertEquals(r.setParams(Map("amplitude" -> 1.0, "frequency" -> 0.3)), Right(()))
    r.runSteps(50)
    assert(r.state.u.max > 0.0)
    assertEquals(r.events.map(_.step), Vector(10L))
  }
