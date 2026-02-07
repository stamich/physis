package physis.core.run

import physis.core.{Digest, Model, StepContext, Stepping}
import physis.core.live.{Command, LiveRun, StateExport}
import physis.core.param.ParamSchema
import physis.core.random.SplittableRng
import physis.core.testing.{Decay, DecayParams, DecayState}

/** A decay model that refuses k > 1, like an explicit scheme refuses an unstable step. */
private object GuardedDecay extends Model[DecayParams]:
  type State = DecayState
  val id = "guarded-decay"
  val version = "1"
  val params: ParamSchema[DecayParams] = DecayParams.schema

  override def check(p: DecayParams, stepping: Stepping): Either[String, Unit] =
    if p.k > 1.0 then Left(s"k = ${p.k} is above the limit 1") else Right(())

  def init(p: DecayParams, rng: SplittableRng): DecayState = DecayState(p.y0)

  def advance(state: DecayState, p: DecayParams, dt: Double, ctx: StepContext): Unit =
    state.y *= math.exp(-p.k * dt)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: DecayState): Long = Digest.ofDoubles(state.y)

class StabilityGuardSuite extends munit.FunSuite:
  private def builder() =
    Physis.model(GuardedDecay).stepping(Stepping.Fixed(0.01))

  private given StateExport[DecayState] with
    def size(state: DecayState): Int = 1
    def copyInto(state: DecayState, out: Array[Double]): Unit = out(0) = state.y

  test("a model without a check accepts everything the schema accepts") {
    val run = Physis.model(Decay).stepping(Stepping.Fixed(0.01)).build()
    assertEquals(run.setParams(Map("k" -> 100.0)), Right(()))
  }

  test("initial parameters that fail the check refuse to build") {
    val e = intercept[IllegalArgumentException](builder().overrides(Map("k" -> 2.0)).build())
    assert(e.getMessage.contains("above the limit"), e.getMessage)
  }

  test("a scheduled event that fails the check refuses to build") {
    val events = Vector(ParamEvent(5L, scala.collection.immutable.VectorMap("k" -> 3.0)))
    val e = intercept[IllegalArgumentException](builder().events(events).build())
    assert(e.getMessage.contains("step 5"), e.getMessage)
  }

  test("a live change that fails the check is refused and the run is unchanged") {
    val run = builder().build()
    run.runSteps(10)
    val before = run.params
    val result = run.setParams(Map("k" -> 5.0))
    assert(result.isLeft, result.toString)
    run.runSteps(10)
    assertEquals(run.params, before)
    assertEquals(run.events, Vector.empty)
    assertEquals(run.setParams(Map("k" -> 0.5)), Right(()))
  }

  test("a refused change never reaches the manifest of a live session") {
    val live = new LiveRun(builder(), summon[StateExport[DecayState]], 1.0, true)
    live.start()
    try
      live.send(Command.SetParams(Map("k" -> 4.0)))
      live.send(Command.StepOnce)
      live.send(Command.StepOnce)
      val deadline = System.currentTimeMillis() + 2000
      var error = ""
      while error.isEmpty && System.currentTimeMillis() < deadline do
        live.latest().foreach(s => error = s.error)
        if error.isEmpty then Thread.sleep(2)
      assert(error.contains("above the limit"), error)
      assertEquals(live.manifest().events, Vector.empty)
    finally live.stop()
  }

  test("Digest.ofRange equals the digest of the copied range and differs outside it") {
    val values = Array.tabulate(20)(i => i * 0.25)
    assertEquals(Digest.ofRange(values, 5, 12), Digest.ofDoubles(values.slice(5, 12)))
    assertEquals(Digest.ofRange(values, 0, values.length), Digest.ofDoubles(values))
    val changed = values.clone()
    changed(3) = 99.0
    assertEquals(Digest.ofRange(changed, 5, 12), Digest.ofRange(values, 5, 12))
    assertNotEquals(Digest.ofRange(changed, 0, 20), Digest.ofRange(values, 0, 20))
  }

  test("chained folds equal one digest of the concatenation") {
    val a = Array(1.0, 2.0, 3.0)
    val b = Array(4.0, 5.0)
    val chained = Digest.fold(Digest.fold(Digest.start, a, 0, 3), b, 0, 2)
    assertEquals(chained, Digest.ofDoubles(a ++ b))
  }
