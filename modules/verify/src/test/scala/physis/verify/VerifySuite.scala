package physis.verify

import physis.core.{Digest, Model, StepContext, Stepping}
import physis.core.param.ParamSchema
import physis.core.random.SplittableRng
import physis.core.run.{ModelRegistry, Physis}

/** Decay with step-keyed noise, for the determinism checks. */
object NoisyDecay extends Model[Unit]:
  type State = Array[Double]
  val id = "noisy-decay"
  val version = "1"
  val params: ParamSchema[Unit] = ParamSchema.unit
  def init(p: Unit, rng: SplittableRng): Array[Double] = Array(1.0 + rng.nextDouble())
  def advance(state: Array[Double], p: Unit, dt: Double, ctx: StepContext): Unit =
    state(0) = state(0) * math.exp(-dt) + 1e-6 * ctx.rng.nextGaussian()
    ctx.taken = dt
    ctx.suggestedNext = dt
  def digest(state: Array[Double]): Long = Digest.ofDoubles(state)

class VerifySuite extends munit.FunSuite:

  test("observedOrder recovers a known power law") {
    val steps = Seq(0.1, 0.05, 0.025, 0.0125)
    assertEqualsDouble(
      Convergence.observedOrder(steps, steps.map(h => 3.0 * math.pow(h, 4))),
      4.0,
      1e-12
    )
    assertEqualsDouble(Convergence.observedOrder(steps, steps.map(h => 0.5 * h * h)), 2.0, 1e-12)
  }

  test("observedOrder of forward Euler on y' = -y is about 1") {
    def euler(h: Double): Double =
      val n = math.round(1.0 / h).toInt
      var y = 1.0
      var i = 0
      while i < n do
        y += h * -y
        i += 1
      y
    val steps = Seq(0.01, 0.005, 0.0025, 0.00125)
    val order = Convergence.observedOrder(steps, Convergence.errors(steps)(euler)(math.exp(-1.0)))
    assertEqualsDouble(order, 1.0, 0.05)
  }

  test("withinTolerance") {
    assert(Convergence.withinTolerance(5e-9, 1e-9))
    assert(!Convergence.withinTolerance(2e-8, 1e-9))
  }

  test("drift is the largest relative deviation") {
    assertEqualsDouble(Conservation.drift(Seq(2.0, 2.1, 1.8, 2.05)), 0.1, 1e-12)
    assertEqualsDouble(Conservation.drift(Seq(0.0, 0.5, -0.25)), 0.5, 1e-12)
  }

  test("bounded accepts oscillation and rejects secular growth") {
    val n = 1000
    val oscillating = (0 until n).map(i => 1.0 + 1e-6 * math.sin(i * 0.37))
    val drifting = (0 until n).map(i => 1.0 + 1e-6 * i)
    assert(Conservation.bounded(oscillating))
    assert(!Conservation.bounded(drifting))
  }

  test("replays of the same manifest match, including step-keyed randomness") {
    val registry = ModelRegistry.of(NoisyDecay)
    val run = Physis.model(NoisyDecay).stepping(Stepping.Fixed(0.01)).seed(42L).build()
    val manifest = run.manifest(until = 1.0)
    val original = run.runUntil(1.0).finalDigest
    assertEquals(Determinism.replayMatches(manifest, registry), Right(true))
    assertEquals(Determinism.replayDigest(manifest, registry), Right(original))
    assertNotEquals(
      Determinism.replayDigest(manifest.copy(seed = 43L), registry),
      Right(original)
    )
  }

  test("allocation is measured") {
    assume(Allocation.supported, "per-thread allocation not supported")
    var sink: Array[Double] = null
    val allocating = Allocation.perStep(10000) { sink = new Array[Double](16) }
    assert(allocating >= 10000L * 16 * 8, s"allocating = $allocating")
    assert(sink != null)

    val buffer = new Array[Double](16)
    val quiet = Allocation.perStep(10000) {
      var i = 0
      while i < buffer.length do
        buffer(i) = buffer(i) * 0.5 + 1.0
        i += 1
    }
    assert(quiet < 1024L, s"quiet = $quiet")
  }
