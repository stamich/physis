package physis.core.run

import scala.collection.immutable.VectorMap

import physis.core.Stepping
import physis.core.testing.{Decay, DecayParams}

class ParamEventSuite extends munit.FunSuite:
  private val params = DecayParams(k = 0.7, y0 = 2.0)

  private def builder() =
    Physis.model(Decay).withParams(params).stepping(Stepping.Fixed(0.01)).sampleEvery(0.1)

  test("a parameter change takes effect from the next step") {
    val run = builder().build()
    run.advanceTo(0.5)
    assertEquals(run.setParams(Map("k" -> 2.0)), Right(()))
    run.advanceTo(1.0)
    val expected = params.y0 * math.exp(-0.7 * 0.5) * math.exp(-2.0 * 0.5)
    assertEqualsDouble(run.state.y, expected, 1e-12)
    assertEquals(run.params, DecayParams(k = 2.0, y0 = 2.0))
    assertEquals(run.initialParams, params)
  }

  test("changes are recorded as events at the step they apply to") {
    val run = builder().build()
    run.advanceTo(0.5)
    run.setParams(Map("k" -> 2.0))
    run.advanceTo(0.8)
    run.setParams(Map("k" -> 0.1))
    run.advanceTo(1.0)
    assertEquals(
      run.events,
      Vector(ParamEvent(50L, VectorMap("k" -> 2.0)), ParamEvent(80L, VectorMap("k" -> 0.1)))
    )
  }

  test("several changes before the next step merge into one event, in schema order") {
    val run = builder().build()
    run.setParams(Map("y0" -> 3.0))
    run.setParams(Map("k" -> 1.5))
    run.step()
    assertEquals(run.events, Vector(ParamEvent(0L, VectorMap("k" -> 1.5, "y0" -> 3.0))))
  }

  test("invalid values are rejected and change nothing") {
    val run = builder().build()
    run.advanceTo(0.2)
    val result = run.setParams(Map("k" -> -1.0))
    assert(result.isLeft, result.toString)
    assert(run.setParams(Map("nope" -> 1.0)).isLeft)
    run.advanceTo(0.4)
    assertEquals(run.events, Vector.empty)
    assertEquals(run.params, params)
  }

  test("scheduled events replay a run with changes bit for bit") {
    val live = builder().build()
    live.runSteps(30)
    live.setParams(Map("k" -> 4.0))
    live.runSteps(30)
    live.setParams(Map("k" -> 0.2, "y0" -> 9.0))
    live.runSteps(40)

    // Same whole steps, events applied at the recorded step indices.
    val replay = builder().events(live.events).build()
    replay.runSteps(100)
    assertEquals(replay.digest, live.digest)
    assertEquals(replay.events, live.events)
  }

  test("invalid scheduled events are rejected at build time") {
    intercept[IllegalArgumentException](
      builder().events(Seq(ParamEvent(10L, VectorMap("k" -> -3.0)))).build()
    )
    intercept[IllegalArgumentException](
      builder().events(Seq(ParamEvent(-1L, VectorMap("k" -> 1.0)))).build()
    )
  }

  test("step() takes exactly one whole step") {
    val run = builder().build()
    run.step()
    run.step()
    assertEquals(run.stepCount, 2L)
    assertEqualsDouble(run.time, 0.02, 1e-15)
  }

  test("runSteps samples before and after") {
    val run =
      builder().probe(physis.core.Probe[physis.core.testing.DecayState]("y")((s, _) => s.y)).build()
    val result = run.runSteps(10)
    assertEquals(result.times.size, 2)
    assertEquals(result.acceptedSteps, 10L)
    assertEqualsDouble(result.last("y"), Decay.exact(params, 0.1), 1e-12)
  }
