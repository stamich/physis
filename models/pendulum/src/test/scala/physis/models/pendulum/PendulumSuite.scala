package physis.models.pendulum

import physis.core.Stepping
import physis.core.run.{ModelRegistry, Physis}
import physis.verify.{Conservation, Determinism}

class PendulumSuite extends munit.FunSuite:

  private def stateAfter(p: PendulumParams, until: Double): Vector[Double] =
    val run = Physis.model(Pendulum.model).withParams(p).stepping(Stepping.Fixed(1e-3)).build()
    run.advanceTo(until)
    run.state.y.toVector

  private def separation(a: Vector[Double], b: Vector[Double]): Double =
    math.sqrt(a.zip(b).map((x, y) => (x - y) * (x - y)).sum)

  test("energy drift stays below 1e-6 over 10^5 RK4 steps (10 deg / 10 deg)") {
    val p = PendulumParams.nearLinear
    val result = Physis
      .model(Pendulum.model)
      .withParams(p)
      .stepping(Stepping.Fixed(1e-3))
      .sampleEvery(0.1)
      .probe(Pendulum.energy(p))
      .build()
      .runUntil(100.0)
    assertEquals(result.acceptedSteps, 100000L)
    val drift = Conservation.drift(result.column("energy"))
    assert(drift < 1e-6, s"drift = $drift")
  }

  test("energy drift probe starts at zero and stays small in the chaotic regime") {
    val p = PendulumParams.chaotic
    val result = Physis
      .model(Pendulum.model)
      .withParams(p)
      .stepping(Stepping.Fixed(1e-3))
      .probe(Pendulum.energyDrift(p))
      .build()
      .runUntil(20.0)
    val drift = result.column("energyDrift")
    assertEquals(drift.head, 0.0)
    assert(drift.map(math.abs).max < 1e-5, s"max drift = ${drift.map(math.abs).max}")
  }

  test("nearby chaotic pendulums diverge exponentially") {
    val p = PendulumParams.chaotic
    val nudged = p.copy(theta1 = p.theta1 + 1e-9)
    val initial = math.toRadians(1e-9)
    val d = separation(stateAfter(p, 20.0), stateAfter(nudged, 20.0))
    assert(d > 1e6 * initial, s"separation grew only from $initial to $d")
  }

  test("nearby near-linear pendulums stay together") {
    val p = PendulumParams.nearLinear
    val nudged = p.copy(theta1 = p.theta1 + 1e-9)
    val initial = math.toRadians(1e-9)
    val d = separation(stateAfter(p, 20.0), stateAfter(nudged, 20.0))
    assert(d < 1e3 * initial, s"separation grew from $initial to $d")
  }

  test("adaptive and fixed steps agree") {
    val p = PendulumParams.nearLinear
    val adaptive = Physis
      .model(Pendulum.model)
      .withParams(p)
      .stepping(Stepping.Adaptive(1e-10, 1e-10, 1e-3, 0.05))
      .build()
    adaptive.advanceTo(5.0)
    val d = separation(adaptive.state.y.toVector, stateAfter(p, 5.0))
    assert(d < 1e-7, s"difference = $d")
  }

  test("angles are given in degrees") {
    val state = Pendulum.model.init(PendulumParams.chaotic, physis.core.random.SplittableRng(0L))
    assertEqualsDouble(state.y(0), 2.0 * math.Pi / 3.0, 1e-15)
    assertEqualsDouble(state.y(1), -math.Pi / 18.0, 1e-15)
  }

  test("the outer bob hangs at -(l1 + l2) at rest") {
    val p = PendulumParams.chaotic.copy(theta1 = 0.0, theta2 = 0.0)
    val result =
      Physis.model(Pendulum.model).withParams(p).probe(Pendulum.probes(p)*).build().runUntil(0.0)
    assertEqualsDouble(result.last("x2"), 0.0, 1e-15)
    assertEqualsDouble(result.last("y2"), -2.0, 1e-15)
  }

  test("runs replay from their manifest") {
    val manifest = Physis.model(Pendulum.model).stepping(Stepping.Fixed(1e-3)).build().manifest(5.0)
    assertEquals(Determinism.replayMatches(manifest, ModelRegistry.of(Pendulum.model)), Right(true))
  }
