package physis.core.live

import physis.core.Stepping
import physis.core.run.{Manifest, ModelRegistry, Physis}
import physis.core.testing.{Decay, DecayParams, DecayState}

class LiveManifestSuite extends munit.FunSuite:
  private given StateExport[DecayState] with
    def size(state: DecayState): Int = 1
    def copyInto(state: DecayState, out: Array[Double]): Unit = out(0) = state.y

  private val builder =
    Physis.model(Decay).withParams(DecayParams(k = 0.5, y0 = 1.0)).stepping(Stepping.Fixed(0.001))

  private val registry = ModelRegistry.of(Decay)

  private def replayDigest(m: Manifest): Long =
    val parsed = Manifest.fromJson(Manifest.toJson(m)).fold(e => fail(e), identity)
    val replay = Manifest.replay(parsed, registry).fold(e => fail(e), identity)
    val _ = replay.execute()
    replay.run.digest

  private def awaitStep(live: LiveRun[?, ?], step: Long): Unit =
    val deadline = System.currentTimeMillis() + 2000
    var reached = false
    while !reached do
      live.latest().foreach(s => reached = s.step >= step)
      if !reached then
        assert(System.currentTimeMillis() < deadline, s"step $step not reached")
        Thread.sleep(2)

  test("a scripted session replays from its manifest bit for bit") {
    val live = new LiveRun(builder, summon[StateExport[DecayState]], startPaused = true)
    live.start()
    try
      for _ <- 1 to 25 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("k" -> 2.0)))
      for _ <- 1 to 10 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("k" -> 0.1, "y0" -> 4.0)))
      for _ <- 1 to 5 do live.send(Command.StepOnce)
      awaitStep(live, 40)
      val m = live.manifest()
      assertEquals(m.steps, Some(40L))
      assertEquals(m.events.map(_.step), Vector(25L, 35L))
      assertEquals(m.params("k"), 0.5)
      assertEquals(replayDigest(m), m.digest.get)
    finally live.stop()
  }

  test("a free-running session with changes replays bit for bit") {
    val live = new LiveRun(builder, summon[StateExport[DecayState]], initialSpeed = 1.0)
    live.start()
    try
      Thread.sleep(60)
      live.send(Command.SetParams(Map("k" -> 3.0)))
      Thread.sleep(60)
      live.send(Command.SetSpeed(0.25))
      live.send(Command.SetParams(Map("k" -> 0.2)))
      Thread.sleep(60)
      live.send(Command.Pause)
      Thread.sleep(20)
      val m = live.manifest()
      assert(m.steps.exists(_ > 0L))
      assertEquals(m.events.size, 2)
      assertEquals(replayDigest(m), m.digest.get)
      // Taking the manifest does not disturb the session.
      assertEquals(live.manifest(), m)
    finally live.stop()
  }

  test("the manifest is still available after stop") {
    val live = new LiveRun(builder, summon[StateExport[DecayState]], startPaused = true)
    live.start()
    live.send(Command.StepOnce)
    awaitStep(live, 1)
    live.stop()
    assertEquals(live.manifest().steps, Some(1L))
  }
