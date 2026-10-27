package physis.lab

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

import javafx.scene.Scene
import javafx.stage.Stage

import physis.core.live.Command
import physis.core.run.{Disc, Intervention, Manifest, Op}
import physis.view.Frames

/** The whole path: paint in the Lab, run, File > Save animation, read the result back. */
class LabAnimationEndToEndSuite extends LabSuite:

  private def frameCountOf(bytes: Array[Byte]): Int =
    val at = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("acTL")
    assert(at > 0, "no acTL chunk: not an animated PNG")
    java.nio.ByteBuffer.wrap(bytes, at + 4, 4).getInt

  private def paintedAndSaved(id: String, stroke: Intervention): Unit =
    val window = LabFx.onFx(new LabWindow(Catalog.models))
    val stage = LabFx.onFx {
      val stage = new Stage()
      stage.setScene(new Scene(window.root, 900, 600))
      stage.show()
      window.open(id, startPaused = true)
      stage
    }
    val dir = Files.createTempDirectory("physis-lab-e2e")
    try
      val live = LabFx.onFx(window.session.get.live)
      live.send(Command.Intervene(stroke))
      for _ <- 1 to 16 do live.send(Command.StepOnce)
      LabFx.await("16 steps drawn")(
        LabFx.onFx(window.loop.flatMap(_.snapshot).exists(_.step >= 16L))
      )
      val file = dir.resolve(s"$id-animation.png")
      val report = LabFx
        .onFx(window.saveAnimation(file, AnimationOptions(every = 4, fps = 10)))
        .get(180, TimeUnit.SECONDS)
        .fold(e => fail(e), identity)

      // the file: readable, the expected number of frames, exactly the size reported
      val bytes = Files.readAllBytes(file)
      assertEquals(report.frames, Frames.frameCount(0L, 16L, 4L))
      assertEquals(frameCountOf(bytes), report.frames)
      assertEquals(report.bytes, bytes.length.toLong)
      val first = ImageIO.read(file.toFile)
      assert(first != null && first.getWidth > 0 && first.getHeight > 0, "frame 0 unreadable")

      // the manifest beside it: the same run, replaying to the digest the animation was made from
      val manifestFile = report.manifest.getOrElse(fail("no manifest written"))
      val manifest = Manifest
        .fromJson(Files.readString(manifestFile))
        .fold(e => fail(e), identity)
      assertEquals(manifest.steps, Some(16L))
      assertEquals(manifest.digest, Some(report.digest))
      assert(manifest.interventions.nonEmpty, "the painted stroke is in the manifest")
      val err = new ByteArrayOutputStream()
      val code = physis.cli.Cli.execute(
        List("replay", manifestFile.toString),
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8)
      )
      assertEquals(code, 0, err.toString(StandardCharsets.UTF_8))
      assert(!err.toString(StandardCharsets.UTF_8).contains("DIFFERS"))
      assert(
        LabFx.onFx(window.status.getText).startsWith("Saved"),
        LabFx.onFx(window.status.getText)
      )
    finally
      LabFx.onFx {
        window.shutdown()
        stage.close()
      }
      val walk = Files.walk(dir)
      try
        walk.sorted(java.util.Comparator.reverseOrder[java.nio.file.Path]()).forEach { f =>
          val _ = Files.deleteIfExists(f)
        }
      finally walk.close()

  fxTest("a painted fluid session is saved as an animation that replays to its digest") {
    paintedAndSaved("fluid", Intervention.Brush("dye", Disc(40.0, 40.0, 8.0), Op.Assign, 1.0))
  }

  fxTest(
    "a session with a raised sea surface is saved as an animation that replays to its digest"
  ) {
    paintedAndSaved("shallow", Intervention.Pulse("height", 40.0, 40.0, 4.0, 1.0))
  }

  fxTest("a painted convection session is saved as an animation that replays to its digest") {
    paintedAndSaved("convection", Intervention.Brush("heat", Disc(40.0, 10.0, 5.0), Op.Assign, 0.5))
  }
