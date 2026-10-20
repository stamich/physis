package physis.core.live

import physis.core.{Digest, Model, StepContext, Stepping}
import physis.core.param.ParamSchema
import physis.core.random.SplittableRng
import physis.core.run.Physis
import physis.core.testing.{Decay, DecayParams, DecayState}

class LiveRunSuite extends munit.FunSuite:
  private val params = DecayParams(k = 0.5, y0 = 1.0)

  private given StateExport[DecayState] with
    def size(state: DecayState): Int = 1
    def copyInto(state: DecayState, out: Array[Double]): Unit = out(0) = state.y

  private def builder(dt: Double) =
    Physis.model(Decay).withParams(params).stepping(Stepping.Fixed(dt))

  private def live(
      startPaused: Boolean,
      speed: Double = 1.0,
      dt: Double = 0.001
  ): LiveRun[DecayParams, DecayState] =
    new LiveRun(builder(dt), summon[StateExport[DecayState]], speed, startPaused)

  /** Waits until a snapshot satisfies `p`, returning (time, step, publishedAt) copied out. */
  private def await(run: LiveRun[?, ?], timeoutMs: Long = 2000)(
      p: Snapshot => Boolean
  ): (Double, Long, Long) =
    val deadline = System.currentTimeMillis() + timeoutMs
    var found: Option[(Double, Long, Long)] = None
    while found.isEmpty && System.currentTimeMillis() < deadline do
      run.latest().filter(p).foreach(s => found = Some((s.time, s.step, s.publishedAt)))
      if found.isEmpty then Thread.sleep(2)
    found.getOrElse(fail("no matching snapshot in time"))

  private def withLive[A](run: LiveRun[DecayParams, DecayState])(body: => A): A =
    run.start()
    try body
    finally run.stop()

  test("a paused run does not advance") {
    val run = live(startPaused = true)
    withLive(run) {
      Thread.sleep(100)
      run.send(Command.SetSpeed(2.0))
      val (time, step, _) = await(run)(_.speed == 2.0)
      assertEquals(step, 0L)
      assertEquals(time, 0.0)
    }
  }

  test("StepOnce takes exactly one step while paused") {
    val run = live(startPaused = true)
    withLive(run) {
      run.send(Command.StepOnce)
      run.send(Command.StepOnce)
      run.send(Command.StepOnce)
      val (time, step, _) = await(run)(_.step == 3L)
      assertEqualsDouble(time, 0.003, 1e-15)
      Thread.sleep(50)
      assertEquals(run.latest().map(_.step).getOrElse(3L), 3L)
    }
  }

  test("simulated time follows the speed setting") {
    val run = live(startPaused = false, speed = 0.5)
    withLive(run) {
      val (t1, _, w1) = await(run)(_.time > 0.05)
      Thread.sleep(500)
      val (t2, _, w2) = await(run)(_.publishedAt > w1 + 400_000_000L)
      val rate = (t2 - t1) / ((w2 - w1) / 1e9)
      assert(math.abs(rate - 0.5) < 0.05, s"rate $rate")
    }
  }

  test("parameter changes go through the schema; errors reach the snapshot") {
    val run = live(startPaused = true)
    withLive(run) {
      run.send(Command.SetParams(Map("k" -> -1.0)))
      await(run)(_.error.nonEmpty)
      run.send(Command.SetParams(Map("k" -> 3.0)))
      run.send(Command.StepOnce)
      await(run)(s => s.error.isEmpty && s.param("k") == 3.0)
      run.send(Command.Reset)
      await(run)(s => s.step == 0L && s.param("k") == 0.5)
    }
  }

  test("a slow model does not stall publishing beyond the frame budget") {
    val run = new LiveRun(
      Physis.model(SlowDecay).withParams(params).stepping(Stepping.Fixed(0.01)),
      summon[StateExport[DecayState]],
      initialSpeed = 100.0,
      frameBudgetNanos = 8_000_000L
    )
    run.start()
    try
      val (_, _, first) = await(run)(_.step > 0)
      var last = first
      // A shared CI machine can stop any thread for tens of milliseconds, which is not the
      // stall this test is after. A real stall (publishing that waits for the model) shows in
      // every window, so the best of five windows is what must stay within the budget.
      val worstPerWindow = Vector.fill(5) {
        var worst = 0L
        val until = System.nanoTime() + 200_000_000L
        while System.nanoTime() < until do
          run.latest().foreach { s =>
            worst = math.max(worst, s.publishedAt - last)
            last = s.publishedAt
          }
          Thread.sleep(1)
        worst
      }
      // budget + one 2 ms step + scheduling slack
      val best = worstPerWindow.min
      assert(
        best < 40_000_000L,
        s"longest gap between snapshots in each window (ms): ${worstPerWindow.map(_ / 1e6).mkString(", ")}"
      )
    finally run.stop()
  }

  test("stop ends the simulation thread promptly") {
    val run = live(startPaused = false)
    run.start()
    await(run)(_.step > 0)
    val t0 = System.nanoTime()
    run.stop()
    val took = (System.nanoTime() - t0) / 1e6
    assert(!run.isAlive)
    assert(took < 100.0, s"stop took $took ms")
  }

/** Decay that busy-waits 2 ms per step. */
object SlowDecay extends Model[DecayParams]:
  type State = DecayState
  val id = "slow-decay"
  val version = "1"
  val params: ParamSchema[DecayParams] = DecayParams.schema

  def init(p: DecayParams, rng: SplittableRng): DecayState = new DecayState(p.y0)

  def advance(state: DecayState, p: DecayParams, dt: Double, ctx: StepContext): Unit =
    val end = System.nanoTime() + 2_000_000L
    while System.nanoTime() < end do ()
    state.y *= math.exp(-p.k * dt)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: DecayState): Long = Digest.ofDoubles(state.y)
