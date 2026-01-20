package physis.core

import physis.core.run.Physis
import physis.core.testing.{Decay, DecayParams}

/** Immutable decay y' = -y with the exact step, written as a pure simulation. */
object PureDecay extends Simulation[Double]:
  val initial: Double = 1.0
  def step(y: Double, dt: Double): Double = y * math.exp(-dt)

class SimulationSuite extends munit.FunSuite:
  private val model = Simulation.asModel("pure-decay", "1", PureDecay, y => Digest.ofDoubles(y))

  test("a pure simulation runs through the engine unchanged") {
    val y = Probe[Simulation.Box[Double]]("y")((s, _) => s.value)
    val result = Physis.model(model).stepping(Stepping.Fixed(0.01)).probe(y).build().runUntil(1.0)
    assertEqualsDouble(result.last("y"), math.exp(-1.0), 1e-12)
  }

  test("it matches the mutable model with the same arithmetic bit for bit") {
    val pure = Physis.model(model).stepping(Stepping.Fixed(0.01)).build().runUntil(1.0)
    val mutable = Physis
      .model(Decay)
      .withParams(DecayParams(k = 1.0, y0 = 1.0))
      .stepping(Stepping.Fixed(0.01))
      .build()
      .runUntil(1.0)
    assertEquals(pure.finalDigest, mutable.finalDigest)
  }

  test("the adapter has no parameters and steps with fixed dt only") {
    assertEquals(model.params.specs.size, 0)
    assert(!model.supports(Stepping.Adaptive(1e-6, 1e-9, 1e-3, 1.0)))
  }
