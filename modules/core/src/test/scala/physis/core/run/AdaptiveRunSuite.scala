package physis.core.run

import physis.core.{Model, Probe, StepContext, Stepping}
import physis.core.param.ParamSchema
import physis.core.random.SplittableRng
import physis.core.testing.{CappedDecay, Decay, DecayParams, DecayState}

/** Records the context it is given, to check what the run passes to models. */
final class RecordingModel extends Model[Unit]:
  type State = Array[Double]
  val id = "recording"
  val version = "1"
  val params: ParamSchema[Unit] = ParamSchema.unit
  var seenAdaptive = false
  var seenRtol = 0.0
  var seenAtol = 0.0

  override def supports(stepping: Stepping): Boolean = true

  def init(p: Unit, rng: SplittableRng): Array[Double] = Array(0.0)

  def advance(state: Array[Double], p: Unit, dt: Double, ctx: StepContext): Unit =
    seenAdaptive = ctx.adaptive
    seenRtol = ctx.rtol
    seenAtol = ctx.atol
    state(0) += dt
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: Array[Double]): Long = java.lang.Double.doubleToRawLongBits(state(0))

class AdaptiveRunSuite extends munit.FunSuite:
  private val y = Probe[DecayState]("y")((s, _) => s.y)
  private val params = DecayParams(k = 0.7, y0 = 2.0)
  private val adaptive = Stepping.Adaptive(rtol = 1e-6, atol = 1e-9, initialDt = 0.4, maxDt = 1.0)

  private def cappedRun() =
    Physis
      .model(CappedDecay(maxAccepted = 0.05))
      .withParams(params)
      .stepping(adaptive)
      .probe(y)
      .sampleEvery(0.1)
      .build()

  test("rejections are counted") {
    val result = cappedRun().runUntil(1.0)
    assert(result.rejectedSteps > 0, s"rejected = ${result.rejectedSteps}")
  }

  test("adaptive runs land exactly on sample times") {
    val result = cappedRun().runUntil(1.0)
    assertEquals(result.times.size, 11)
    result.times.zipWithIndex.foreach { (t, k) => assertEquals(t, k * 0.1) }
    result.times.zip(result.column("y")).foreach { (t, v) =>
      assertEqualsDouble(v, Decay.exact(params, t), 1e-10)
    }
  }

  test("tolerances reach the model through the step context") {
    val model = RecordingModel()
    Physis.model(model).stepping(adaptive).build().advanceTo(0.1)
    assert(model.seenAdaptive)
    assertEquals(model.seenRtol, 1e-6)
    assertEquals(model.seenAtol, 1e-9)

    Physis.model(model).stepping(Stepping.Fixed(0.01)).build().advanceTo(0.1)
    assert(!model.seenAdaptive)
  }

  test("a model that cannot step adaptively is rejected at build time") {
    val error = intercept[IllegalArgumentException](
      Physis.model(Decay).stepping(adaptive).build()
    )
    assert(error.getMessage.contains("does not support adaptive stepping"), error.getMessage)
  }

  test("adaptive runs are reproducible") {
    assertEquals(cappedRun().runUntil(1.0).finalDigest, cappedRun().runUntil(1.0).finalDigest)
  }
