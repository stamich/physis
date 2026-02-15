package physis.models.grayscott

import physis.core.live.{Command, LiveRun}
import physis.core.run.{Manifest, ModelRegistry, Physis}

/** Seeded runs (with noise) must also be identical everywhere: the random path is pinned too. */
class SeededGoldenSuite extends munit.FunSuite:

  private def builder(overrides: Map[String, Double]) =
    Physis.model(GrayScott).stepping(GrayScott.stepping).overrides(overrides)

  test("golden digest: 64 x 64, seed 3, default noise, 500 steps") {
    val run = builder(Map("nx" -> 64.0, "ny" -> 64.0)).seed(3L).build()
    run.runSteps(500)
    assertEquals(run.digest, 677347728812595093L)
  }

  test("a live session with 3 changes and a reseed replays to the same digest") {
    val live = new LiveRun(
      builder(Map("nx" -> 64.0, "ny" -> 64.0)).seed(1L),
      GrayScottState.stateExport,
      1.0,
      true
    )
    live.start()
    try
      for _ <- 1 to 30 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("F" -> 0.03, "k" -> 0.057)))
      for _ <- 1 to 30 do live.send(Command.StepOnce)
      live.send(Command.Restart(Map.empty, Some(9L)))
      for _ <- 1 to 20 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("F" -> 0.0545, "k" -> 0.062)))
      for _ <- 1 to 20 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("Dv" -> 0.4)))
      for _ <- 1 to 20 do live.send(Command.StepOnce)
      live.send(Command.SetParams(Map("Du" -> 0.9)))
      for _ <- 1 to 20 do live.send(Command.StepOnce)
      val deadline = System.currentTimeMillis() + 5000
      var done = false
      while !done do
        live.latest().foreach(s => done = s.step >= 80L)
        if !done then
          assert(System.currentTimeMillis() < deadline, "80 steps not reached")
          Thread.sleep(2)
      val saved = live.manifest()
      assertEquals(saved.seed, 9L)
      assertEquals(saved.events.size, 3)
      assertEquals(saved.steps, Some(80L))
      val parsed = Manifest.fromJson(Manifest.toJson(saved)).fold(e => fail(e), identity)
      val replay = Manifest.replay(parsed, ModelRegistry.of(GrayScott)).fold(e => fail(e), identity)
      assertEquals(replay.execute().finalDigest, saved.digest.get)
    finally live.stop()
  }
