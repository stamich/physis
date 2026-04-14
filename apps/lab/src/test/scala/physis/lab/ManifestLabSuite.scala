package physis.lab

import java.nio.file.Files

import physis.core.live.Command

class LabManifestsSuite extends munit.FunSuite:
  test("a live Lab session with parameter changes replays headless to the same digest") {
    val session = Catalog.lorenz.open(seed = 7L, startPaused = true)
    val live = session.live
    live.start()
    try
      def steps(n: Int): Unit = for _ <- 1 to n do live.send(Command.StepOnce)
      steps(200)
      live.send(Command.SetParams(Map("rho" -> 35.0)))
      steps(150)
      live.send(Command.SetParams(Map("sigma" -> 12.0, "beta" -> 2.0)))
      steps(100)
      live.send(Command.SetParams(Map("rho" -> 20.0)))
      steps(50)
      val deadline = System.currentTimeMillis() + 10_000
      while live.manifest().steps.forall(_ < 500L) do
        assert(System.currentTimeMillis() < deadline, "steps not taken")
        Thread.sleep(10)
      val file = Files.createTempFile("physis-lab", ".json")
      val saved = LabManifests.save(live, file).fold(e => fail(e), identity)
      assertEquals(saved.events.size, 3)
      val report = LabManifests.replay(file, Catalog.registry).fold(e => fail(e), identity)
      assertEquals(report.steps, 500L)
      assertEquals(report.matches, Some(true), report.summary)
      assert(report.summary.contains("matches"))
      Files.delete(file)
    finally live.stop()
  }

  test("a Gray-Scott session with three changes and a reseed replays to the same digest") {
    val session = Catalog.grayScott.open(seed = 3L, startPaused = true)
    val live = session.live
    live.start()
    try
      def steps(n: Int): Unit = for _ <- 1 to n do live.send(Command.StepOnce)
      steps(40)
      live.send(Command.SetParams(Map("F" -> 0.03, "k" -> 0.057)))
      steps(30)
      live.send(Command.Restart(Map("nx" -> 64.0, "ny" -> 64.0), Some(9L)))
      steps(25)
      live.send(Command.SetParams(Map("F" -> 0.0545, "k" -> 0.062)))
      steps(25)
      live.send(Command.SetParams(Map("Dv" -> 0.4)))
      steps(20)
      live.send(Command.SetParams(Map("Du" -> 0.9)))
      steps(10)
      val deadline = System.currentTimeMillis() + 20_000
      while live.manifest().steps.forall(_ < 80L) do
        assert(System.currentTimeMillis() < deadline, "steps not taken")
        Thread.sleep(10)
      val file = Files.createTempFile("physis-lab-gs", ".json")
      val saved = LabManifests.save(live, file).fold(e => fail(e), identity)
      assertEquals(saved.seed, 9L)
      assertEquals(saved.events.size, 3)
      val report = LabManifests.replay(file, Catalog.registry).fold(e => fail(e), identity)
      assertEquals(report.steps, 80L)
      assertEquals(report.matches, Some(true), report.summary)
      Files.delete(file)
    finally live.stop()
  }

  private def awaitSteps(live: physis.core.live.LiveRun[?, ?], n: Long): Unit =
    val deadline = System.currentTimeMillis() + 30_000
    while live.manifest().steps.forall(_ < n) do
      assert(System.currentTimeMillis() < deadline, "steps not taken")
      Thread.sleep(10)

  test("walls and a pulse in Wave replay headless to the same digest") {
    import physis.core.run.{Disc, Intervention, Op}
    val live = Catalog.wave.open(seed = 1L, startPaused = true).live
    live.start()
    try
      def steps(n: Int): Unit = for _ <- 1 to n do live.send(Command.StepOnce)
      steps(10)
      live.send(Command.Intervene(Intervention.Brush(
        "wall",
        Disc(120.0, 100.0, 5.0),
        Op.Assign,
        1.0
      )))
      live.send(Command.Intervene(Intervention.Pulse("u", 60.0, 128.0, 4.0, 1.0)))
      steps(40)
      live.send(Command.SetParams(Map("damping" -> 0.01)))
      live.send(Command.Intervene(Intervention.Brush(
        "wall",
        Disc(120.0, 100.0, 3.0),
        Op.Erase,
        0.0
      )))
      steps(30)
      awaitSteps(live, 80L)
      val file = Files.createTempFile("physis-lab-wave", ".json")
      val saved = LabManifests.save(live, file).fold(e => fail(e), identity)
      assertEquals(saved.interventions.size, 3)
      assertEquals(saved.events.size, 1)
      val report = LabManifests.replay(file, Catalog.registry).fold(e => fail(e), identity)
      assertEquals(report.steps, 80L)
      assertEquals(report.interventions, 3)
      assertEquals(report.matches, Some(true), report.summary)
      assert(report.summary.contains("3 strokes"), report.summary)
      Files.delete(file)
    finally live.stop()
  }

  test("smoke, a push and a wall in the fluid replay headless to the same digest") {
    import physis.core.run.{Disc, Intervention, Op}
    val live = Catalog.fluid.open(seed = 3L, startPaused = true).live
    live.start()
    try
      def steps(n: Int): Unit = for _ <- 1 to n do live.send(Command.StepOnce)
      steps(5)
      live.send(Command.Intervene(Intervention.Brush("dye", Disc(60.0, 40.0, 8.0), Op.Assign, 1.0)))
      live.send(Command.Intervene(Intervention.Pulse("u", 50.0, 40.0, 3.0, 1.5)))
      steps(20)
      live.send(Command.SetParams(Map("viscosity" -> 0.05)))
      live.send(Command.Intervene(Intervention.Brush(
        "wall",
        Disc(90.0, 40.0, 5.0),
        Op.Assign,
        1.0
      )))
      steps(15)
      awaitSteps(live, 40L)
      val file = Files.createTempFile("physis-lab-fluid", ".json")
      val saved = LabManifests.save(live, file).fold(e => fail(e), identity)
      assertEquals(saved.interventions.size, 3)
      val report = LabManifests.replay(file, Catalog.registry).fold(e => fail(e), identity)
      assertEquals(report.steps, 40L)
      assertEquals(report.matches, Some(true), report.summary)
      Files.delete(file)
    finally live.stop()
  }

  test("food and trail strokes in Physarum replay headless to the same digest") {
    import physis.core.run.{Disc, Intervention, Op}
    val live = Catalog.physarum.open(seed = 5L, startPaused = true).live
    live.start()
    try
      def steps(n: Int): Unit = for _ <- 1 to n do live.send(Command.StepOnce)
      steps(10)
      live.send(Command.Intervene(Intervention.Brush(
        "food",
        Disc(100.0, 100.0, 12.0),
        Op.Assign,
        5.0
      )))
      steps(20)
      live.send(Command.SetParams(Map("foodWeight" -> 3.0)))
      live.send(Command.Intervene(Intervention.Brush(
        "trail",
        Disc(30.0, 200.0, 8.0),
        Op.Add,
        40.0
      )))
      steps(20)
      awaitSteps(live, 50L)
      val file = Files.createTempFile("physis-lab-physarum", ".json")
      val saved = LabManifests.save(live, file).fold(e => fail(e), identity)
      assertEquals(saved.interventions.size, 2)
      val report = LabManifests.replay(file, Catalog.registry).fold(e => fail(e), identity)
      assertEquals(report.steps, 50L)
      assertEquals(report.matches, Some(true), report.summary)
      Files.delete(file)
    finally live.stop()
  }

  test("a stroke in Gray-Scott replays to the same digest") {
    import physis.core.run.{Disc, Intervention, Op}
    val live = Catalog.grayScott.open(seed = 4L, startPaused = true).live
    live.start()
    try
      def steps(n: Int): Unit = for _ <- 1 to n do live.send(Command.StepOnce)
      steps(20)
      live.send(Command.Intervene(Intervention.Brush("v", Disc(40.0, 40.0, 6.0), Op.Assign, 0.5)))
      steps(40)
      awaitSteps(live, 60L)
      val file = Files.createTempFile("physis-lab-gs-stroke", ".json")
      val saved = LabManifests.save(live, file).fold(e => fail(e), identity)
      assertEquals(saved.interventions.size, 1)
      val report = LabManifests.replay(file, Catalog.registry).fold(e => fail(e), identity)
      assertEquals(report.matches, Some(true), report.summary)
      assert(report.summary.contains("1 stroke,"), report.summary)
      Files.delete(file)
    finally live.stop()
  }

  test("replay reports unreadable files and a changed digest") {
    assert(LabManifests.replay(
      java.nio.file.Path.of("/nonexistent/m.json"),
      Catalog.registry
    ).isLeft)
    val r = ReplayReport("lorenz", 10, 1.0, digest = 1L, expected = Some(2L))
    assertEquals(r.matches, Some(false))
    assert(r.summary.contains("DIFFERS"))
    assertEquals(r.copy(expected = None).matches, None)
  }

class LabManifestUiSuite extends LabSuite:
  fxTest("save and replay from the window report a matching digest") {
    val window = LabFx.onFx(new LabWindow(Catalog.models))
    val stage = LabFx.onFx {
      val stage = new javafx.stage.Stage()
      stage.setScene(new javafx.scene.Scene(window.root, 900, 600))
      stage.show()
      window.open(Catalog.pendulum.id)
      stage
    }
    try
      LabFx.await("pendulum running")(
        LabFx.onFx(window.loop.flatMap(_.snapshot).exists(_.step > 100))
      )
      LabFx.onFx(window.loop.foreach(_.setParam("g", 5.0)))
      LabFx.await("parameter change applied")(
        LabFx.onFx(window.loop.flatMap(_.snapshot).exists(_.param("g") == 5.0))
      )
      LabFx.onFx(window.controls.foreach(_.togglePause()))
      val file = Files.createTempFile("physis-lab-ui", ".json")
      val saved = LabFx.onFx(window.saveManifest(file)).fold(e => fail(e), identity)
      assertEquals(saved.events.size, 1)
      assert(LabFx.onFx(window.status.getText).startsWith("Saved"))
      val future = LabFx.onFx(window.replayManifest(file))
      val report =
        future.get(60, java.util.concurrent.TimeUnit.SECONDS).fold(e => fail(e), identity)
      assertEquals(report.matches, Some(true))
      LabFx.await("status shows the replay")(LabFx.onFx(window.status.getText).contains("matches"))
      Files.delete(file)
    finally
      LabFx.onFx {
        window.close()
        stage.close()
      }
  }
