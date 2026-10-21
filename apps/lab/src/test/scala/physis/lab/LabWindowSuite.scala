package physis.lab

import javafx.scene.Scene
import javafx.stage.Stage

import physis.core.live.Command
import physis.view.{Direction, View}

class CatalogSuite extends munit.FunSuite:
  test("every catalog model is in the registry and builds its views") {
    for m <- Catalog.models do
      assert(Catalog.registry.get(m.id).isDefined, m.id)
      val session = m.open(seed = 1L, startPaused = true)
      session.view match
        case View.Layout(views, _) => assert(views.size >= 2)
        case other => fail(s"${m.id}: expected a layout, got $other")
      assertEquals(session.schema.names.toSet, session.initial.keySet)
  }

  test("Physarum offers food and trail tools whose strokes the model accepts") {
    import physis.core.run.{Intervention, Physis}
    import physis.models.physarum.Physarum
    assertEquals(
      Catalog.physarum.tools.map(_.spec.name),
      Vector("Paint food", "Erase food", "Paint trail")
    )
    assert(Catalog.physarum.wraps(Map.empty))
    val run = Physis
      .model(Physarum)
      .stepping(Physarum.stepping)
      .overrides(Map("n" -> 50.0, "nx" -> 64.0, "ny" -> 64.0))
      .build()
    for tool <- Catalog.physarum.tools; secondary <- Seq(false, true) do
      val iv = tool.make(physis.view.Dab(10.0, 12.0), 5.0, 3.0, secondary)
      assert(run.intervene(iv).isRight, s"${tool.spec.name}: $iv")
      assert(iv.isInstanceOf[Intervention.Brush])
  }

  test("the fluid offers smoke, pushes and walls whose strokes the model accepts") {
    import physis.core.run.{Intervention, Physis}
    import physis.models.fluid.Fluid
    assertEquals(
      Catalog.fluid.tools.map(_.spec.name),
      Vector("Smoke", "Push sideways", "Push up", "Wall", "Erase wall")
    )
    assert(Catalog.fluid.wraps(Map("edge" -> 0.0)))
    assert(!Catalog.fluid.wraps(Map("edge" -> 1.0)))
    val run = Physis
      .model(Fluid)
      .stepping(Fluid.stepping)
      .overrides(Map("nx" -> 64.0, "ny" -> 64.0))
      .build()
    for tool <- Catalog.fluid.tools; secondary <- Seq(false, true) do
      val iv = tool.make(physis.view.Dab(10.0, 12.0), 5.0, 1.0, secondary)
      assert(run.intervene(iv).isRight, s"${tool.spec.name}: $iv")
    val pushes = Catalog.fluid.tools.filter(_.spec.name.startsWith("Push")).map(_.make(
      physis.view.Dab(1.0, 1.0),
      5.0,
      1.0,
      true
    ))
    pushes.foreach {
      case Intervention.Pulse(_, _, _, _, amplitude) => assert(amplitude < 0.0)
      case other => fail(s"not a pulse: $other")
    }
  }

  test("the Lorenz view stacks the attractor above the coordinates") {
    Catalog.lorenz.open(1L, startPaused = true).view match
      case View.Layout(Seq(_: View.Trajectory, _: View.Plot), Direction.Vertical) => ()
      case other => fail(s"unexpected $other")
  }

class ScalaFxSuite extends LabSuite:
  fxTest("a ScalaFX stage can be created (the app's own code path)") {
    // PhysisLab builds its window with ScalaFX; this needs javafx-media on the classpath.
    val title = LabFx.onFx {
      val stage = new scalafx.stage.Stage()
      stage.title = "Physis Lab"
      stage.title.value
    }
    assertEquals(title, "Physis Lab")
  }

class LabWindowSuite extends LabSuite:
  private def frames(w: LabWindow): Long = LabFx.onFx(w.loop.map(_.stats.frames).getOrElse(0L))

  fxTest("the Lab renders each model and switches without leaking threads") {
    val before = LabFx.simThreads
    val window = LabFx.onFx(new LabWindow(Catalog.models))
    val stage = LabFx.onFx {
      val scene = new Scene(window.root, 1000, 700)
      window.installShortcuts(scene)
      val stage = new Stage()
      stage.setScene(scene)
      stage.show()
      stage
    }
    try
      for id <- Seq(
          Catalog.lorenz.id,
          Catalog.grayScott.id,
          Catalog.wave.id,
          Catalog.boids.id,
          Catalog.physarum.id,
          Catalog.pendulum.id
        )
      do
        LabFx.onFx(window.open(id))
        LabFx.await(s"120 frames of $id")(frames(window) >= 120)
        LabFx.await(s"$id advanced")(
          LabFx.onFx(window.loop.flatMap(_.snapshot).exists(_.time > 0.5))
        )
        assertEquals(LabFx.simThreads, before + 1, s"simulation threads with $id open")
        assertEquals(LabFx.onFx(window.session.map(_.model.id)), Some(id))
      // keyboard shortcut: space pauses
      LabFx.onFx(window.controls.foreach(_.togglePause()))
      LabFx.await("paused")(LabFx.onFx(window.loop.flatMap(_.snapshot).exists(_.paused)))
    finally
      LabFx.onFx {
        window.close()
        stage.close()
      }
    LabFx.await("simulation thread stopped")(LabFx.simThreads == before)
  }

  private def withWindow(id: String)(body: LabWindow => Unit): Unit =
    val before = LabFx.simThreads
    val window = LabFx.onFx(new LabWindow(Catalog.models))
    val stage = LabFx.onFx {
      val stage = new Stage()
      stage.setScene(new Scene(window.root, 1000, 700))
      stage.show()
      stage
    }
    try
      LabFx.onFx(window.open(id))
      LabFx.await(s"$id running")(frames(window) >= 30)
      body(window)
    finally
      LabFx.onFx {
        window.close()
        stage.close()
      }
    LabFx.await("simulation thread stopped")(LabFx.simThreads == before)

  private def latest(w: LabWindow)(f: physis.core.live.Snapshot => Boolean): Boolean =
    LabFx.onFx(w.loop.flatMap(_.snapshot).exists(f))

  fxTest("painting on Gray-Scott is one recorded intervention per dab, counted in the tool box") {
    withWindow(Catalog.grayScott.id) { window =>
      val box = LabFx.onFx(window.toolBox.get)
      assertEquals(LabFx.onFx(box.selected), None)
      LabFx.onFx(box.select(Some("Paint v")))
      val painter = LabFx.onFx(window.paintables.head)
      LabFx.onFx {
        painter.onStroke(
          physis.fx.StrokeBatch(
            false,
            Vector(physis.view.Dab(10.5, 10.5), physis.view.Dab(14.5, 10.5)),
            true
          )
        )
      }
      LabFx.await("two interventions")(latest(window)(_.interventions == 2))
      val ivs = LabFx.onFx(window.session.get.live.manifest().interventions)
      assertEquals(ivs.size, 2)
      assertEquals(LabFx.onFx(box.counter.getText), "2 strokes recorded")
      // switched off, a stroke changes nothing
      LabFx.onFx(box.select(None))
      LabFx.onFx(painter.onStroke(physis.fx.StrokeBatch(
        false,
        Vector(physis.view.Dab(3.0, 3.0)),
        true
      )))
      assertEquals(LabFx.onFx(window.session.get.live.manifest().interventions.size), 2)
    }
  }

  fxTest("a wave preset restarts with its walls painted at step 0, and Reset keeps them") {
    withWindow(Catalog.wave.id) { window =>
      LabFx.onFx(window.applyPreset("Double slit"))
      LabFx.await("preset restarted")(latest(window)(s =>
        s.param("edge") == 3.0 && s.interventions > 10
      ))
      val manifest = LabFx.onFx(window.session.get.live.manifest())
      assert(manifest.interventions.nonEmpty)
      assert(manifest.interventions.forall(_.step == 0L), "painting happened after step 0")
      val painted = manifest.interventions.size
      LabFx.onFx(window.controls.foreach(_.togglePause()))
      LabFx.await("paused")(latest(window)(_.paused))
      // a stroke with the Wall tool is one more intervention; Reset returns to the preset's
      val box = LabFx.onFx(window.toolBox.get)
      LabFx.onFx(box.select(Some("Wall")))
      LabFx.onFx(
        window.paintables.head
          .onStroke(physis.fx.StrokeBatch(false, Vector(physis.view.Dab(40.5, 40.5)), true))
      )
      // a paused run applies the stroke at the next step
      LabFx.onFx(window.session.get.live.send(Command.StepOnce))
      LabFx.await("stroke recorded")(latest(window)(_.interventions == painted + 1))
      LabFx.onFx(window.session.get.live.send(Command.Reset))
      LabFx.onFx(window.session.get.live.send(Command.StepOnce))
      LabFx.await("reset")(latest(window)(s => s.step == 1L && s.interventions == painted))
    }
  }

  fxTest("a preset is one parameter change, and a reseed starts the run again") {
    withWindow(Catalog.grayScott.id) { window =>
      LabFx.await("some steps")(latest(window)(_.step > 600))
      LabFx.onFx(window.applyPreset("Maze"))
      LabFx.await("preset applied")(latest(window)(s => s.param("F") == 0.029))
      val events = LabFx.onFx(window.session.get.live.manifest().events)
      assertEquals(events.size, 1)
      LabFx.onFx(window.reseed(5L))
      LabFx.await("run started again")(latest(window)(_.step < 300))
      assertEquals(LabFx.onFx(window.session.get.live.manifest().seed), 5L)
      // a reseed keeps the parameters the user has chosen, here the preset
      assertEquals(LabFx.onFx(window.loop.get.snapshot.get.param("F")), 0.029)
    }
  }

  fxTest("restart-only parameters wait for Apply and then change the size of the run") {
    withWindow(Catalog.boids.id) { window =>
      val n = LabFx.onFx(window.session.get.initial("n")).toInt
      LabFx.await("first frames")(latest(window)(_.stateSize == 4 * n))
      LabFx.onFx {
        val spinner = window.panel.get.controls("n").head
          .asInstanceOf[javafx.scene.control.Spinner[Integer]]
        spinner.getValueFactory.setValue(Integer.valueOf(300))
      }
      assertEquals(LabFx.onFx(window.panel.get.pendingRestart), Map("n" -> 300.0))
      Thread.sleep(200)
      assert(latest(window)(_.stateSize == 4 * n), "the run changed before Apply")
      LabFx.onFx(window.panel.get.applyButton.fire())
      LabFx.await("300 boids")(latest(window)(_.stateSize == 4 * 300))
      assertEquals(LabFx.onFx(window.panel.get.pendingRestart), Map.empty[String, Double])
    }
  }

  fxTest("Save image writes the field as the Lab draws it, with a colour scale and no text") {
    withWindow(Catalog.grayScott.id) { window =>
      LabFx.await("frames")(latest(window)(_.step > 100))
      val file = java.nio.file.Files.createTempFile("physis-lab-image", ".png")
      try
        val (pixels, size, range, result) = LabFx.onFx {
          val h = window.heatmaps.head
          (h.lastPixels.clone(), h.gridSize, h.range, window.saveImage(file, 2))
        }
        val (nx, ny) = size
        assert(result.exists(_.contains(file.getFileName.toString)), result.toString)
        assertEquals(LabFx.onFx(window.status.getText), result.fold(identity, identity))
        val image = javax.imageio.ImageIO.read(file.toFile)
        assertEquals(image.getHeight, ny * 2)
        assert(image.getWidth > nx * 2, s"no colour scale: ${image.getWidth} wide")
        for y <- 0 until ny * 2; x <- 0 until nx * 2 do
          assertEquals(image.getRGB(x, y), pixels((y / 2) * nx + x / 2) | 0xff000000, s"($x, $y)")
        assert(result.exists(_.contains("colours from")), result.toString)
        assert(range._1 < range._2 || range._1 == range._2)
        assert(LabFx.onFx(window.saveImage(file, 0)).isLeft)
        assert(LabFx.onFx(window.saveImage(file, 9)).isLeft)
      finally
        val _ = java.nio.file.Files.deleteIfExists(file)
    }
  }

  fxTest("a fluid preset restarts with its cylinder and dye painted at step 0 and tracers seeded") {
    withWindow(Catalog.fluid.id) { window =>
      LabFx.onFx(window.applyPreset("Cylinder wake"))
      LabFx.await("wake started")(latest(window)(s =>
        s.param("edge") == 2.0 && s.param("tracers") == 3000.0 && s.interventions > 10
      ))
      val manifest = LabFx.onFx(window.session.get.live.manifest())
      assert(manifest.interventions.forall(_.step == 0L), "painting happened after step 0")
      val slices = LabFx.onFx(window.loop.get.snapshot.get.slices.map(_.name))
      assertEquals(slices, Vector("dye", "vorticity", "speed", "wall", "x", "y"))
    }
  }

  fxTest("Save image of the wake has the cylinder in flat grey, the tracers and a colour scale") {
    withWindow(Catalog.fluid.id) { window =>
      LabFx.onFx(window.applyPreset("Cylinder wake"))
      LabFx.await("wake drawn")(
        latest(window)(_.step > 20) && LabFx.onFx(window.heatmaps.head.gridSize == (192, 72))
      )
      val file = java.nio.file.Files.createTempFile("physis-lab-wake", ".png")
      try
        val (pixels, result) = LabFx.onFx {
          val h = window.heatmaps.head
          (h.lastPixels.clone(), window.saveImage(file, 2))
        }
        assert(result.isRight, result.toString)
        val image = javax.imageio.ImageIO.read(file.toFile)
        assertEquals(image.getHeight, 72 * 2)
        assert(image.getWidth > 192 * 2, s"no colour scale: ${image.getWidth} wide")
        for y <- 0 until 72 * 2; x <- 0 until 192 * 2 do
          assertEquals(image.getRGB(x, y), pixels((y / 2) * 192 + x / 2) | 0xff000000, s"($x, $y)")
        val grey = pixels.count(_ == physis.view.HeatmapRaster.OverlayGrey)
        assert(grey > 100 && grey < 250, s"$grey cells of the cylinder")
      finally
        val _ = java.nio.file.Files.deleteIfExists(file)
    }
  }

  fxTest("a fluid session started from a preset is saved and replays to the same digest") {
    withWindow(Catalog.fluid.id) { window =>
      LabFx.onFx(window.applyPreset("Smoke plume"))
      LabFx.await("plume running")(latest(window)(s => s.param("buoyancy") == 0.1 && s.step > 60))
      LabFx.onFx(window.controls.foreach(_.togglePause()))
      LabFx.await("paused")(latest(window)(_.paused))
      val file = java.nio.file.Files.createTempFile("physis-lab-plume", ".json")
      try
        val saved =
          LabFx.onFx(LabManifests.save(window.session.get.live, file)).fold(e => fail(e), identity)
        assert(saved.interventions.nonEmpty)
        val report = LabManifests.replay(file, Catalog.registry).fold(e => fail(e), identity)
        assertEquals(report.matches, Some(true), report.summary)
      finally
        val _ = java.nio.file.Files.deleteIfExists(file)
    }
  }

  fxTest("Save image says so when the view has no image") {
    withWindow(Catalog.lorenz.id) { window =>
      val file = java.nio.file.Files.createTempFile("physis-lab-image", ".png")
      try
        val result = LabFx.onFx(window.saveImage(file, 1))
        assertEquals(result, Left("this model has no image to save"))
        assertEquals(LabFx.onFx(window.status.getText), "this model has no image to save")
      finally
        val _ = java.nio.file.Files.deleteIfExists(file)
    }
  }

  fxTest("pointing at a heatmap cell shows its value in the bottom bar") {
    withWindow(Catalog.grayScott.id) { window =>
      LabFx.await("frames")(latest(window)(_.step > 100))
      LabFx.onFx(window.cell.setText(""))
      val heatmaps = LabFx.onFx(window.heatmaps)
      assertEquals(heatmaps.size, 2)
      LabFx.onFx {
        val h = heatmaps.head
        h.onCell(h.cellAt(3, 4))
      }
      assert(LabFx.onFx(window.cell.getText).startsWith("u[3, 4] = "))
    }
  }
