package physis.models.fluid

import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import scala.util.Using

import physis.core.run.{Disc, Op}
import physis.core.run.Intervention.{Brush, Pulse}
import physis.view.{Channel, Frames, View}
import FluidSupport.*

class FluidFramesSuite extends munit.FunSuite:
  private val p = params(edge = 1, nx = 48, ny = 32, viscosity = 0.05, tracers = 100)

  private def session(): FluidRun =
    val r = run(p)
    val _ = r.intervene(Brush("dye", Disc(14.5, 16.5, 4.0), Op.Assign, 1.0))
    val _ = r.intervene(Pulse("u", 12.5, 16.5, 3.0, 1.5))
    r

  private def withDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-anim")
    try body(dir)
    finally
      Using.resource(Files.walk(dir)) { s =>
        s.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(f => Files.delete(f))
      }

  private def int(b: Array[Byte], at: Int): Int =
    ((b(at) & 0xff) << 24) | ((b(at + 1) & 0xff) << 16) | ((b(at + 2) & 0xff) << 8) | (b(
      at + 3
    ) & 0xff)

  /** The (type, data) pairs of a PNG file. */
  private def chunks(file: Array[Byte]): Vector[(String, Array[Byte])] =
    var at = 8
    val out = Vector.newBuilder[(String, Array[Byte])]
    while at < file.length do
      val n = int(file, at)
      out += ((new String(file, at + 4, 4, "US-ASCII"), file.slice(at + 8, at + 8 + n)))
      at += 12 + n
    out.result()

  private def idat(file: Array[Byte]): Vector[Byte] =
    chunks(file).filter(_._1 == "IDAT").flatMap(_._2).toVector

  test("an animation has one frame per 'every' steps, each equal to the still frame") {
    withDir { dir =>
      val options = Frames.Options(every = 10, fps = 8, loops = 3)
      val stills = Frames
        .render(session(), 40L, FluidState.stateExport, FluidViews.lab, dir, options)
        .fold(e => fail(e), identity)
      val file = dir.resolve("wake.png")
      val n = Frames
        .animation(session(), 40L, FluidState.stateExport, FluidViews.lab, file, options)
        .fold(e => fail(e), identity)
      assertEquals(n, 5)
      assertEquals(stills.size, 5)
      val bytes = Files.readAllBytes(file)
      val cs = chunks(bytes)
      val ac = cs.find(_._1 == "acTL").get._2
      assertEquals(int(ac, 0), 5)
      assertEquals(int(ac, 4), 3)
      assertEquals(cs.count(_._1 == "fcTL"), 5)
      // the first frame is the still image, the others carry the same compressed pixels
      assertEquals(idat(bytes), idat(Files.readAllBytes(stills.head)))
      val rest = cs.filter(_._1 == "fdAT").map(_._2.drop(4).toVector)
      assertEquals(rest, stills.tail.map(s => idat(Files.readAllBytes(s))).toVector)
      val image = ImageIO.read(file.toFile)
      assertEquals(image.getHeight, 32)
    }
  }

  test("the same run gives the same bytes, and a view without an image is refused") {
    withDir { dir =>
      val a = dir.resolve("a.png")
      val b = dir.resolve("b.png")
      val options = Frames.Options(every = 10)
      for f <- Seq(a, b) do
        Frames
          .animation(session(), 30L, FluidState.stateExport, FluidViews.lab, f, options)
          .fold(e => fail(e), identity)
      assert(java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b)))
      val plot = View.Plot(Seq(Channel.ProbeAt(0, "kinetic_energy")), 10.0)
      assertEquals(
        Frames.animation(session(), 10L, FluidState.stateExport, plot, a, options),
        Left("the view has no image to export")
      )
    }
    intercept[IllegalArgumentException](Frames.Options(1, fps = 0))
    intercept[IllegalArgumentException](Frames.Options(1, loops = -1))
  }

  test("progress counts the frames, and the total is known before the first step") {
    withDir { dir =>
      val seen = Vector.newBuilder[(Int, Int)]
      val r = session()
      val n = Frames
        .animation(
          r,
          40L,
          FluidState.stateExport,
          FluidViews.lab,
          dir.resolve("p.png"),
          Frames.Options(every = 10),
          progress = (done, total) => seen += ((done, total))
        )
        .fold(e => fail(e), identity)
      assertEquals(n, 5)
      assertEquals(seen.result(), (1 to 5).map(k => (k, 5)).toVector)
    }
  }

  test("cancelling stops the run before its next step and leaves no file") {
    withDir { dir =>
      val file = dir.resolve("c.png")
      val r = session()
      var stop = false
      val result = Frames.animation(
        r,
        400L,
        FluidState.stateExport,
        FluidViews.lab,
        file,
        Frames.Options(every = 10),
        progress = (done, _) => if done == 2 then stop = true,
        cancelled = () => stop
      )
      assertEquals(result, Left(Frames.Cancelled))
      assertEquals(r.stepCount, 10L, "it ran on after the cancel")
      assert(!Files.exists(file), "a cancelled animation left its file")
    }
  }

  test("a refused animation does not delete a file that was already there") {
    withDir { dir =>
      val file = dir.resolve("keep.png")
      Files.writeString(file, "mine")
      val plot = View.Plot(Seq(Channel.ProbeAt(0, "kinetic_energy")), 10.0)
      val result =
        Frames.animation(session(), 10L, FluidState.stateExport, plot, file, Frames.Options(5))
      assert(result.isLeft)
      assertEquals(Files.readString(file), "mine")
    }
  }

  test("frameCount is the number of frames render writes, from any starting step") {
    for (from, steps, every) <- Seq(
        (0L, 40L, 10L),
        (0L, 45L, 10L),
        (0L, 0L, 5L),
        (7L, 40L, 10L),
        (10L, 40L, 10L),
        (0L, 3L, 10L)
      )
    do
      val expected = (from +: ((from + 1) to steps)).count(k => k == from || k % every == 0)
      assertEquals(Frames.frameCount(from, steps, every), expected, s"$from $steps $every")
  }
