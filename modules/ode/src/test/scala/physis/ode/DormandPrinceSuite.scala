package physis.ode

import physis.core.{Model, StepContext, Stepping}
import physis.core.random.SplittableRng
import physis.core.run.{Physis, RunResult}
import physis.verify.{Allocation, Convergence}

class DormandPrinceSuite extends munit.FunSuite:
  private val y = OdeState.component("y", 0)
  private val oscillatorParams = OscillatorParams(omega = 1.0)
  private val decay: Model.Aux[DecayRate, OdeState] =
    OdeModel("exp-decay", "1", ExpDecay, DecayRate.schema, (_, _) => Array(1.0))

  private def adaptive(tol: Double, initialDt: Double = 1e-3) =
    Stepping.Adaptive(rtol = tol, atol = tol, initialDt = initialDt, maxDt = 1.0)

  private def solve[P](model: Model.Aux[P, OdeState], p: P, until: Double, tol: Double): RunResult =
    Physis
      .model(model)
      .withParams(p)
      .stepping(adaptive(tol))
      .sampleEvery(until)
      .probe(y)
      .build()
      .runUntil(until)

  private val tolerances = Seq(1e-6, 1e-7, 1e-8, 1e-9, 1e-10)

  test("global error on exponential decay stays within 10x the tolerance") {
    tolerances.foreach { tol =>
      val error = math.abs(solve(decay, DecayRate(1.0), 5.0, tol).last("y") - math.exp(-5.0))
      assert(Convergence.withinTolerance(error, tol), s"tol = $tol, error = $error")
    }
  }

  test("global error on the oscillator over one period stays within 10x the tolerance") {
    val period = 2 * math.Pi
    tolerances.foreach { tol =>
      val x = solve(Oscillator.model, oscillatorParams, period, tol).last("y")
      val error = math.abs(x - Oscillator.exactX(oscillatorParams, period))
      assert(Convergence.withinTolerance(error, tol), s"tol = $tol, error = $error")
    }
  }

  test("a too large first step is rejected and counted") {
    val result = Physis
      .model(Oscillator.model)
      .withParams(oscillatorParams)
      .stepping(adaptive(1e-10, initialDt = 1.0))
      .sampleEvery(1.0)
      .build()
      .runUntil(1.0)
    assert(result.rejectedSteps > 0, s"rejected = ${result.rejectedSteps}")
  }

  test("adaptive steps are economical at high accuracy") {
    val result = solve(Oscillator.model, oscillatorParams, 10.0, 1e-9)
    val error = math.abs(result.last("y") - Oscillator.exactX(oscillatorParams, 10.0))
    assert(error < 1e-7, s"error = $error")
    assert(result.acceptedSteps < 1000L, s"steps = ${result.acceptedSteps}")
  }

  test("adaptive runs are reproducible") {
    val a = solve(Oscillator.model, oscillatorParams, 10.0, 1e-8).finalDigest
    val b = solve(Oscillator.model, oscillatorParams, 10.0, 1e-8).finalDigest
    assertEquals(a, b)
  }

  test("advance does not allocate") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val model = Oscillator.model
    val state = model.init(oscillatorParams, SplittableRng(0L))
    val ctx = StepContext()
    ctx.adaptive = true
    ctx.rtol = 1e-8
    ctx.atol = 1e-8
    val bytes = Allocation.perStep(10000) {
      model.advance(state, oscillatorParams, 1e-2, ctx)
      ctx.time += ctx.taken
    }
    assert(bytes < 1024L, s"allocated $bytes bytes in 10^4 steps")
  }
