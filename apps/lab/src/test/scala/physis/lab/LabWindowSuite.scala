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
      for id <- Seq(Catalog.lorenz.id, Catalog.pendulum.id, Catalog.lorenz.id) do
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
