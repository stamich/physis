package physis.lab

import java.nio.file.Files
import javax.imageio.ImageIO

import javafx.scene.Scene
import javafx.scene.control.MenuBar
import javafx.stage.Stage

import physis.core.live.Command

class LabAnimationUiSuite extends LabSuite:

  private def withWindow[A](model: String)(body: LabWindow => A): A =
    val window = LabFx.onFx(new LabWindow(Catalog.models))
    val stage = LabFx.onFx {
      val stage = new Stage()
      stage.setScene(new Scene(window.root, 900, 600))
      stage.show()
      window.open(model, startPaused = true)
      stage
    }
    try body(window)
    finally
      LabFx.onFx {
        window.close()
        stage.close()
      }

  private def step(window: LabWindow, n: Int): Unit =
    val live = LabFx.onFx(window.session.get.live)
    for _ <- 1 to n do live.send(Command.StepOnce)
    LabFx.await(s"$n steps drawn")(LabFx.onFx(window.loop.flatMap(_.snapshot).exists(_.step >= n)))

  fxTest("File has Save animation…, enabled only for a model with an image") {
    val window = LabFx.onFx(new LabWindow(Catalog.models))
    val names = LabFx.onFx {
      val bar = window.root.getTop.asInstanceOf[MenuBar]
      bar.getMenus.get(
        0
      ).getItems.toArray.toVector.map(_.asInstanceOf[javafx.scene.control.MenuItem].getText)
    }
    assert(names.contains("Save animation…"), names.toString)
    assert(LabFx.onFx(window.animationItem.isDisable), "enabled before a session")
    try
      for (id, enabled) <- Seq("fluid" -> true, "lorenz" -> false, "boids" -> false, "wave" -> true)
      do
        LabFx.onFx(window.open(id, startPaused = true))
        assertEquals(LabFx.onFx(!window.animationItem.isDisable), enabled, id)
    finally LabFx.onFx(window.close())
    assert(LabFx.onFx(window.animationItem.isDisable), "enabled without a session")
  }

  fxTest("the form starts from the model's suggestion and says what the file will be") {
    withWindow("fluid") { window =>
      step(window, 12)
      val (session, shot) = LabFx.onFx((window.session.get, window.loop.get.snapshot.get))
      val initial = LabAnimations.suggest(shot.step, session.model.animationDefaults, 128)
      val form = LabFx.onFx(new AnimationForm(session, shot, 800L, 4.0, initial))
      LabFx.onFx {
        assertEquals(form.options, initial)
        assertEquals(form.problem, None)
        assert(form.summary.getText.contains("frames of"), form.summary.getText)
        assert(
          form.summary.getText.contains("about 200 s") || form.summary.getText.contains(
            "about 3 min"
          ),
          form.summary.getText
        )
        form.fps.getEditor.setText("30")
        assertEquals(form.options.fps, 30)
        form.colorBar.setSelected(false)
        assert(!form.options.colorBar)
        form.manifest.setSelected(false)
        assert(!form.options.writeManifest)
      }
    }
  }

  fxTest("the form refuses too many frames and a bad number, and tells the listeners") {
    withWindow("fluid") { window =>
      step(window, 4)
      val (session, shot) = LabFx.onFx((window.session.get, window.loop.get.snapshot.get))
      val form =
        LabFx.onFx(new AnimationForm(session, shot, 1200L, 0.0, AnimationOptions(every = 4)))
      val seen = Vector.newBuilder[Boolean]
      LabFx.onFx {
        form.onValid(v => seen += v)
        form.every.getEditor.setText("1")
        assert(form.problem.exists(_.contains("the most is 600")), form.problem.toString)
        assert(form.summary.getText.contains("1201 frames"), form.summary.getText)
        form.every.getEditor.setText("0")
        assert(form.problem.exists(_.contains("every 1 step")), form.problem.toString)
        form.every.getEditor.setText("5")
        assertEquals(form.problem, None)
        assert(form.summary.getText.contains("241 frames"), form.summary.getText)
        assert(form.summary.getText.contains("time not known"), form.summary.getText)
      }
      val flags = seen.result()
      assertEquals(flags.head, true)
      assert(flags.contains(false) && flags.last, flags.toString)
    }
  }

  fxTest("saveAnimation writes the file and the status line says so") {
    withWindow("fluid") { window =>
      val file = Files.createTempFile("physis-lab-ui", ".png")
      try
        val early = LabFx.onFx(window.saveAnimation(file, AnimationOptions(every = 4)))
        assert(early.left.exists(_.contains("nothing has run yet")), early.toString)
        assert(LabFx.onFx(window.status.getText).startsWith("Could not save the animation"))
        step(window, 12)
        val report =
          LabFx.onFx(window.saveAnimation(file, AnimationOptions(every = 4, writeManifest = false)))
            .fold(e => fail(e), identity)
        assert(report.frames >= 4, report.toString)
        assert(
          LabFx.onFx(window.status.getText).startsWith("Saved"),
          LabFx.onFx(window.status.getText)
        )
        assert(ImageIO.read(file.toFile) != null)
      finally
        val _ = Files.deleteIfExists(file)
    }
  }
