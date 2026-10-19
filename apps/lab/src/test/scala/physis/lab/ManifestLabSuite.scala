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
