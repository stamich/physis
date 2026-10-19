package physis.ode

import physis.core.{StepContext, Stepping}
import physis.core.random.SplittableRng
import physis.core.run.Physis
import physis.verify.{Allocation, Convergence}

class Rk4Suite extends munit.FunSuite:
  private val x = OdeState.component("x", 0)
  private val p = OscillatorParams(omega = 1.0)

  private def xAt(t: Double, h: Double): Double =
    Physis
      .model(Oscillator.model)
      .withParams(p)
      .stepping(Stepping.Fixed(h))
      .sampleEvery(t)
      .probe(x)
      .build()
      .runUntil(t)
      .last("x")

  test("RK4 is fourth order on the harmonic oscillator") {
    val steps = Seq(0.1, 0.05, 0.025, 0.0125)
    val errors = Convergence.errors(steps)(h => xAt(1.0, h))(Oscillator.exactX(p, 1.0))
    val order = Convergence.observedOrder(steps, errors)
    assertEqualsDouble(order, 4.0, 0.2)
  }

  test("RK4 is accurate over many periods") {
    val t = 20 * math.Pi
    assertEqualsDouble(xAt(t, 0.001), Oscillator.exactX(p, t), 1e-9)
  }

  test("advance does not allocate") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val model = Oscillator.model
    val state = model.init(p, SplittableRng(0L))
    val ctx = StepContext()
    val bytes = Allocation.perStep(10000) {
      model.advance(state, p, 1e-3, ctx)
    }
    assert(bytes < 1024L, s"allocated $bytes bytes in 10^4 steps")
  }

  test("an initial state of the wrong size is rejected") {
    val bad = OdeModel("bad", "1", Oscillator, OscillatorParams.schema, (_, _) => Array(1.0))
    intercept[IllegalArgumentException](bad.init(p, SplittableRng(0L)))
  }
