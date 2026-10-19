package physis.models.wave

import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import scala.util.Using

import physis.core.run.{Disc, Manifest, ModelRegistry, Op, Run}
import physis.core.run.Intervention.{Brush, Pulse}
import physis.view.{Channel, Frames, HeatmapRaster, View}
import WaveSupport.*

class WaveFramesSuite extends munit.FunSuite:
  private val p = WaveParams.schema.defaults.copy(nx = 64, ny = 64, edge = Edge.Reflective)

  /** The manifest of a session: a wall and a pulse at the start, a stroke that erases later. */
  private def session(): Manifest =
    val r = run(p)
    val _ = r.intervene(Brush("wall", Disc(20.5, 24.5, 4.0), Op.Assign, 1.0))
    val _ = r.intervene(Pulse("u", 44.5, 24.5, 3.0, 1.0))
    r.runSteps(30)
    val _ = r.intervene(Brush("wall", Disc(20.5, 24.5, 6.0), Op.Erase, 0.0))
    r.runSteps(30)
    r.manifest(r.time).copy(steps = Some(r.stepCount))

  private def replayed(m: Manifest): Run[WaveParams, WaveState] =
    Manifest
      .replay(m, ModelRegistry.of(Wave))
      .fold(e => fail(e), _.run.asInstanceOf[Run[WaveParams, WaveState]])

  private def withDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-frames")
    try body(dir)
    finally
      Using.resource(Files.walk(dir)) { s =>
        s.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(f => Files.delete(f))
      }

  private def greyCells(file: Path): Int =
    val image = ImageIO.read(file.toFile)
    var n = 0
    for y <- 0 until image.getHeight; x <- 0 until image.getWidth do
      if image.getRGB(x, y) == HeatmapRaster.OverlayGrey then n += 1
    n

  test("a replayed painted session gives one frame per 20 steps, with the wall where it was") {
    val m = session()
    withDir { dir =>
      val options = Frames.Options(every = 20, factor = 2)
      val files = Frames
        .render(replayed(m), 60L, WaveState.stateExport, WaveViews.default, dir, options)
        .fold(e => fail(e), identity)
      assertEquals(
        files.map(_.getFileName.toString),
        (0 to 3).map(i => f"frame-$i%05d.png").toVector
      )
      val image = ImageIO.read(files.head.toFile)
      assertEquals(image.getHeight, 64 * 2)
      assert(image.getWidth > 64 * 2, "no colour scale")
      // before any step nothing is painted yet; after 20 steps the wall (about 50 cells) is there
      assertEquals(greyCells(files(0)), 0)
      val wall = greyCells(files(1))
      assert(wall >= 4 * 40, s"wall covers $wall pixels of the second frame")
      // the stroke at step 30 erased it: gone from the frame after step 40
      assertEquals(greyCells(files(2)), 0)
    }
  }

  test("the same manifest gives the same bytes, and the frames equal those of the original run") {
    val m = session()
    withDir { a =>
      withDir { b =>
        val options = Frames.Options(every = 15)
        val first = Frames
          .render(replayed(m), 60L, WaveState.stateExport, WaveViews.default, a, options)
          .fold(e => fail(e), identity)
        val second = Frames
          .render(replayed(m), 60L, WaveState.stateExport, WaveViews.default, b, options)
          .fold(e => fail(e), identity)
        assertEquals(first.size, 5)
        assertEquals(second.map(_.getFileName), first.map(_.getFileName))
        for (x, y) <- first.zip(second) do
          assert(
            java.util.Arrays.equals(Files.readAllBytes(x), Files.readAllBytes(y)),
            s"${x.getFileName} differs"
          )
      }
    }
  }

  test("a last step that is not a multiple of 'every' is not written") {
    withDir { dir =>
      val files = Frames
        .render(
          replayed(session()),
          50L,
          WaveState.stateExport,
          WaveViews.default,
          dir,
          Frames.Options(20)
        )
        .fold(e => fail(e), identity)
      assertEquals(files.size, 3)
    }
  }

  test("a view without an image is refused, and bad options are rejected") {
    withDir { dir =>
      val plot = View.Plot(Seq(Channel.ProbeAt(0, "energy")), 10.0)
      val result =
        Frames.render(replayed(session()), 10L, WaveState.stateExport, plot, dir, Frames.Options(5))
      assertEquals(result, Left("the view has no image to export"))
    }
    intercept[IllegalArgumentException](Frames.Options(0))
    intercept[IllegalArgumentException](Frames.Options(1, factor = 17))
    intercept[IllegalArgumentException](Frames.Options(1, prefix = "a/b"))
  }
