package physis.core.run

import physis.core.Stepping
import physis.core.run.Intervention.Brush
import physis.core.testing.{Decay, DecayParams, PokableDecay}

class InterventionSuite extends munit.FunSuite:
  private val params = DecayParams(k = 0.5, y0 = 2.0)
  private val poke = Brush("y", Disc(0, 0, 1), Op.Assign, 10.0)
  private val bump = Brush("y", Disc(0, 0, 1), Op.Add, 1.0)

  private def builder() =
    Physis.model(PokableDecay).withParams(params).stepping(Stepping.Fixed(0.1))

  test("an intervention lands before the next step and is recorded at that step index") {
    val run = builder().build()
    run.runSteps(3)
    assertEquals(run.intervene(poke), Right(()))
    assertEqualsDouble(run.state.y, params.y0 * math.exp(-0.05 * 3), 1e-12)
    run.step()
    assertEqualsDouble(run.state.y, 10.0 * math.exp(-0.05), 1e-15)
    assertEquals(run.interventions, Vector(InterventionEvent(3, poke)))
  }

  test("parameter changes of a step are applied before its interventions") {
    val run = builder().build()
    run.runSteps(2)
    assertEquals(run.intervene(poke), Right(()))
    assertEquals(run.setParams(Map("k" -> 2.0)), Right(()))
    run.step()
    assertEqualsDouble(run.state.y, 10.0 * math.exp(-0.2), 1e-15)
    assertEquals(run.events.map(_.step), Vector(2L))
    assertEquals(run.interventions.map(_.step), Vector(2L))
  }

  test("several interventions at one step apply in the order they were sent") {
    val run = builder().build()
    run.intervene(poke)
    run.intervene(bump)
    run.step()
    assertEqualsDouble(run.state.y, 11.0 * math.exp(-0.05), 1e-15)
    val other = builder().build()
    other.intervene(bump)
    other.intervene(poke)
    other.step()
    assertEqualsDouble(other.state.y, 10.0 * math.exp(-0.05), 1e-15)
  }

  test("a scheduled replay equals the live run") {
    val live = builder().build()
    live.runSteps(4)
    live.intervene(poke)
    live.runSteps(3)
    live.setParams(Map("k" -> 1.5))
    live.intervene(bump)
    live.runSteps(5)
    val replay = builder()
      .events(live.events)
      .interventions(live.interventions)
      .build()
    replay.runSteps(12)
    assertEquals(replay.digest, live.digest)
    assertEquals(replay.interventions, live.interventions)
  }

  test("a model without support refuses, and nothing is queued") {
    val run = Physis.model(Decay).withParams(params).stepping(Stepping.Fixed(0.1)).build()
    val answer = run.intervene(poke)
    assert(answer.left.exists(_.contains("does not support interventions")), answer.toString)
    run.step()
    assertEquals(run.interventions, Vector.empty)
  }

  test("invalid interventions and unknown targets are refused when sent") {
    val run = builder().build()
    assert(run.intervene(Brush("y", Disc(0, 0, -1), Op.Assign, 1)).isLeft)
    assert(run.intervene(Brush("z", Disc(0, 0, 1), Op.Assign, 1)).isLeft)
    run.step()
    assertEquals(run.interventions, Vector.empty)
  }

  test("building a replay with an unsupported intervention fails with a message") {
    val e = intercept[IllegalArgumentException] {
      Physis
        .model(Decay)
        .interventions(Vector(InterventionEvent(2, poke)))
        .build()
    }
    assert(e.getMessage.contains("does not support interventions"), e.getMessage)
  }
