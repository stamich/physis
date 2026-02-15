package physis.models.boids

import physis.core.live.{Command, LiveRun, Snapshot}
import physis.core.run.{Manifest, ModelRegistry, Physis}
import physis.verify.Allocation

class BoidsDeterminismSuite extends munit.FunSuite:

  private def builder(overrides: Map[String, Double]) =
    Physis.model(Boids).stepping(Boids.stepping).overrides(overrides)

  test("golden digest: 500 seeded boids after 300 steps are the same on every platform") {
    val run = builder(Map("n" -> 500.0)).seed(7L).build()
    run.runSteps(300)
    assertEquals(run.digest, -2786620802859775160L)
  }

  private def awaitStep(live: LiveRun[?, ?], step: Long, size: Int): Snapshot =
    val deadline = System.currentTimeMillis() + 5000
    var found: Option[Snapshot] = None
    while found.isEmpty do
      live.latest().filter(s => s.step >= step && s.stateSize == size).foreach(s => found = Some(s))
      if found.isEmpty then
        assert(System.currentTimeMillis() < deadline, s"step $step not reached")
        Thread.sleep(2)
    found.get

  test("a live session with 3 changes and a restart is saved and replays to the same digest") {
    val live = new LiveRun(builder(Map("n" -> 300.0)).seed(2L), BoidsState.stateExport, 1.0, true)
    live.start()
    try
      for _ <- 1 to 20 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("wCohesion" -> 2.0)))
      for _ <- 1 to 20 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("wander" -> 0.4, "perception" -> 55.0)))
      for _ <- 1 to 20 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("maxSpeed" -> 5.0)))
      for _ <- 1 to 20 do live.send(Command.StepOnce)
      // The restart changes the number of boids and the seed; the three changes above are gone.
      live.send(Command.Restart(Map("n" -> 400.0, "width" -> 500.0), Some(11L)))
      for _ <- 1 to 15 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("wAlignment" -> 2.0)))
      for _ <- 1 to 15 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("perception" -> 30.0, "wSeparation" -> 0.5)))
      for _ <- 1 to 15 do live.send(Command.StepOnce)
      awaitStep(live, 45L, 4 * 400)
      val saved = live.manifest()
      assertEquals(saved.params("n"), 400.0)
      assertEquals(saved.params("width"), 500.0)
      assertEquals(saved.seed, 11L)
      assertEquals(saved.steps, Some(45L))
      assertEquals(saved.events.size, 2)
      val parsed = Manifest.fromJson(Manifest.toJson(saved)).fold(e => fail(e), identity)
      val replay = Manifest.replay(parsed, ModelRegistry.of(Boids)).fold(e => fail(e), identity)
      assertEquals(replay.execute().finalDigest, saved.digest.get)
    finally live.stop()
  }

  test("a step does not allocate") {
    assume(Allocation.supported)
    val run = builder(Map("n" -> 500.0)).seed(1L).build()
    // Less than one byte per step: a fixed overhead of a few dozen bytes is not a per-step cost.
    val bytes = Allocation.perStep(1000)(run.step())
    assert(bytes < 1000L, s"allocated $bytes bytes in 1000 steps")
  }
