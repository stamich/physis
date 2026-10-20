package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import physis.core.run.Physis
import physis.models.physarum.{Physarum, PhysarumState, PhysarumViews}
import physis.view.{Frames, Png}

class CliFramesSuite extends munit.FunSuite:
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private val small =
    Seq("--model", "physarum", "--param", "n=500", "--param", "nx=64", "--param", "ny=64")

  private def cleanUp(dir: Path): Unit =
    val stream = Files.list(dir)
    try stream.forEach(f =>
        val _ = Files.deleteIfExists(f)
      )
    finally stream.close()
    val _ = Files.deleteIfExists(dir)

  test("frames writes a picture before the first step and after every n steps, one path a line") {
    val dir = Files.createTempDirectory("physis-cli-frames")
    try
      val (code, out, err) =
        go(Seq("frames", dir.toString, "--steps", "20", "--every", "10", "--seed", "3") ++ small*)
      assertEquals(code, 0, err)
      val paths = out.linesIterator.toVector
      assertEquals(
        paths.map(p => Path.of(p).getFileName.toString),
        Vector(
          "frame-00000.png",
          "frame-00001.png",
          "frame-00002.png"
        )
      )
      assert(paths.forall(p => Files.size(Path.of(p)) > 0))
      assert(err.contains("3 frames of physarum"), err)
    finally cleanUp(dir)
  }

  test("a frame is exactly the picture of the same run drawn by the view module") {
    val dir = Files.createTempDirectory("physis-cli-frames")
    try
      val (code, _, err) =
        go(Seq("frames", dir.toString, "--steps", "20", "--every", "10", "--seed", "3") ++ small*)
      assertEquals(code, 0, err)
      val run = Physis
        .model(Physarum)
        .stepping(Physarum.stepping)
        .overrides(Map("n" -> 500.0, "nx" -> 64.0, "ny" -> 64.0))
        .seed(3L)
        .build()
      run.runSteps(10)
      val options = Frames.Options(every = 10, colorBar = true)
      val frame = Frames
        .frame(Frames.snapshot(run, PhysarumState.stateExport), PhysarumViews.default, options)
        .getOrElse(fail("no frame"))
      val expected = Png.encode(frame.pixels, frame.width, frame.height)
      assertEquals(Files.readAllBytes(dir.resolve("frame-00001.png")).toVector, expected.toVector)
    finally cleanUp(dir)
  }

  test("the same command twice writes the same bytes, and --scale and --no-color-bar change size") {
    val a = Files.createTempDirectory("physis-cli-frames")
    val b = Files.createTempDirectory("physis-cli-frames")
    val c = Files.createTempDirectory("physis-cli-frames")
    try
      val common = Seq("--steps", "10", "--every", "10", "--seed", "3") ++ small
      assertEquals(go(Seq("frames", a.toString) ++ common*)._1, 0)
      assertEquals(go(Seq("frames", b.toString) ++ common*)._1, 0)
      assertEquals(
        Files.readAllBytes(a.resolve("frame-00001.png")).toVector,
        Files.readAllBytes(b.resolve("frame-00001.png")).toVector
      )
      val plain = ImageIO.read(a.resolve("frame-00001.png").toFile)
      assertEquals(
        go(Seq("frames", c.toString, "--scale", "2", "--no-color-bar") ++ common*)._1,
        0
      )
      val big = ImageIO.read(c.resolve("frame-00001.png").toFile)
      assertEquals(big.getHeight, 64 * 2)
      assertEquals(big.getWidth, 64 * 2) // no colour bar
      assertEquals(plain.getHeight, 64)
      assert(plain.getWidth > 64, "the colour bar widens the picture")
    finally
      cleanUp(a)
      cleanUp(b)
      cleanUp(c)
  }

  test("a model without an image says so, with exit code 1 and nothing on standard output") {
    val dir = Files.createTempDirectory("physis-cli-frames")
    try
      val (code, out, err) = go("frames", dir.toString, "--model", "lorenz", "--steps", "10")
      assertEquals(code, 1)
      assertEquals(out, "")
      assert(err.contains("model 'lorenz' has no image to export"), err)
    finally cleanUp(dir)
  }
