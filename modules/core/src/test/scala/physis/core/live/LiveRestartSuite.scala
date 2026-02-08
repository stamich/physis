package physis.core.live

import physis.core.{Digest, Model, StepContext, Stepping}
import physis.core.param.{ParamSchema, ParamSpec}
import physis.core.random.SplittableRng
import physis.core.run.{Manifest, ModelRegistry, Physis}

/** Test model whose state size is a restart-only parameter. */
private final case class ChainParams(n: Int, rate: Double)

private object ChainParams:
  given schema: ParamSchema[ChainParams] = ParamSchema.derived[ChainParams](
    ParamSpec("n", default = 4, min = 1, max = 1000, doc = "cells", restart = true),
    ParamSpec("rate", default = 1.0, min = 0.0, max = 100.0)
  )

private object Chain extends Model[ChainParams]:
  type State = Array[Double]
  val id = "chain"
  val version = "1"
  val params: ParamSchema[ChainParams] = ChainParams.schema

  def init(p: ChainParams, rng: SplittableRng): Array[Double] =
    Array.tabulate(p.n)(i => rng.fork(i.toLong).nextDouble())

  def advance(state: Array[Double], p: ChainParams, dt: Double, ctx: StepContext): Unit =
    var i = 0
    while i < state.length do
      state(i) += p.rate * dt * (i + 1)
      i += 1
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: Array[Double]): Long = Digest.ofDoubles(state)

class LiveRestartSuite extends munit.FunSuite:
  private given StateExport[Array[Double]] with
    def size(state: Array[Double]): Int = state.length
    def copyInto(state: Array[Double], out: Array[Double]): Unit =
      System.arraycopy(state, 0, out, 0, state.length)

  private def builder = Physis.model(Chain).stepping(Stepping.Fixed(0.01)).seed(7L)

  private def live() = new LiveRun(builder, summon[StateExport[Array[Double]]], 1.0, true)

  private def await(run: LiveRun[?, ?])(p: Snapshot => Boolean): Snapshot =
    val deadline = System.currentTimeMillis() + 2000
    var found: Option[Snapshot] = None
    while found.isEmpty && System.currentTimeMillis() < deadline do
      run.latest().filter(p).foreach(s => found = Some(s))
      if found.isEmpty then Thread.sleep(2)
    found.getOrElse(fail("no matching snapshot in time"))

  private def replayDigest(m: Manifest): Long =
    Manifest.replay(m, ModelRegistry.of(Chain)) match
      case Right(replay) => replay.execute().finalDigest
      case Left(error) => fail(error)

  test("setParams refuses a restart-only parameter and changes nothing") {
    val run = builder.build()
    val result = run.setParams(Map("n" -> 9.0))
    assertEquals(result, Left("parameter 'n' requires a restart"))
    run.step()
    assertEquals(run.params.n, 4)
    assertEquals(run.events, Vector.empty)
  }

  test("setting a restart-only parameter to its current value is accepted") {
    val run = builder.build()
    assertEquals(run.setParams(Map("n" -> 4.0, "rate" -> 2.0)), Right(()))
  }

  test("restart-only parameters can still be set when the run is defined") {
    val run = builder.overrides(Map("n" -> 6.0)).build()
    assertEquals(run.state.length, 6)
  }

  test("a scheduled event may not change a restart-only parameter") {
    import scala.collection.immutable.VectorMap
    val events = Vector(physis.core.run.ParamEvent(3L, VectorMap("n" -> 9.0)))
    intercept[IllegalArgumentException](builder.events(events).build())
  }

  test("Restart rebuilds the run with a larger state, and the snapshot follows") {
    val run = live()
    run.start()
    try
      run.send(Command.StepOnce)
      run.send(Command.StepOnce)
      await(run)(s => s.step == 2L && s.stateSize == 4)
      run.send(Command.Restart(Map("n" -> 9.0), None))
      val s = await(run)(_.stateSize == 9)
      assertEquals(s.step, 0L)
      assertEquals(s.error, "")
      assertEquals(s.param("n"), 9.0)
    finally run.stop()
  }

  test("the manifest after a restart holds the new parameters, no old events, and replays") {
    val run = live()
    run.start()
    try
      run.send(Command.SetParams(Map("rate" -> 3.0)))
      run.send(Command.StepOnce)
      run.send(Command.Restart(Map("n" -> 8.0, "rate" -> 2.0), Some(11L)))
      run.send(Command.StepOnce)
      run.send(Command.SetParams(Map("rate" -> 5.0)))
      run.send(Command.StepOnce)
      run.send(Command.StepOnce)
      await(run)(s => s.step == 3L && s.stateSize == 8)
      val m = run.manifest()
      assertEquals(m.params("n"), 8.0)
      assertEquals(m.params("rate"), 2.0)
      assertEquals(m.seed, 11L)
      assertEquals(m.events.map(_.step), Vector(1L))
      assertEquals(m.steps, Some(3L))
      assertEquals(replayDigest(m), m.digest.get)
    finally run.stop()
  }

  test("a restart with invalid values reports an error and the old run continues") {
    val run = live()
    run.start()
    try
      run.send(Command.StepOnce)
      run.send(Command.Restart(Map("n" -> 0.0), None))
      val s = await(run)(_.error.nonEmpty)
      assert(s.error.contains("'n'"), s.error)
      assertEquals(s.stateSize, 4)
      assertEquals(s.step, 1L)
    finally run.stop()
  }

  test("Reset returns to the restarted definition") {
    val run = live()
    run.start()
    try
      run.send(Command.Restart(Map("n" -> 6.0), Some(3L)))
      run.send(Command.StepOnce)
      run.send(Command.Reset)
      val s = await(run)(x => x.stateSize == 6 && x.step == 0L)
      assertEquals(s.param("n"), 6.0)
      assertEquals(run.manifest().seed, 3L)
    finally run.stop()
  }
