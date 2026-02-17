package physis.lab

import javafx.scene.Scene
import javafx.stage.Stage

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
          Catalog.boids.id,
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
      // the preset is part of the run's parameters until the next reset, so it is kept
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
