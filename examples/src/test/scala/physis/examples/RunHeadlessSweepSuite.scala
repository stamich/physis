package physis.examples

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import scala.util.Using

import physis.core.run.{Disc, Manifest, Op, Physis}
import physis.core.run.Intervention.Brush
import physis.models.wave.Wave
import physis.view.HeatmapRaster

class RunHeadlessSweepSuite extends munit.FunSuite:

  private def capture(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = RunHeadless.parse(args.toList) match
      case Right(options) =>
        RunHeadless.run(
          options,
          new PrintStream(out, true, StandardCharsets.UTF_8),
          new PrintStream(err, true, StandardCharsets.UTF_8)
        )
      case Left(error) => fail(s"parse failed: $error")
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private def go(args: Seq[String]): (Int, String, String) = capture(args*)

  private def withDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-cli")
    try body(dir)
    finally
      Using.resource(Files.walk(dir)) { s =>
        s.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(f => Files.delete(f))
      }

  private val small = Seq("--model", "physarum", "--param", "n=200", "--param", "nx=64")
  private val smaller = small ++ Seq("--param", "ny=64")

  test("--list names every model with its parameters") {
    val (code, text, _) = capture("--list")
    assertEquals(code, 0)
    for id <- Seq("lorenz", "double-pendulum", "gray-scott", "boids", "wave", "physarum") do
      assert(text.linesIterator.exists(_.startsWith(s"$id (version ")), id)
    assert(text.contains("  sensorDist = 9.0 [1.0, 30.0] cells"), text)
    assert(text.contains("needs a restart"), text)
  }

  test("a sweep prints one row per point in grid order, the same for any number of threads") {
    val axes = Seq("--sweep", "decay=0.05,0.1,0.2", "--sweep", "seed=1..2", "--until", "20")
    val (one, csv1, err1) = go(smaller ++ axes ++ Seq("--threads", "1"))
    val (four, csv4, _) = go(smaller ++ axes ++ Seq("--threads", "4"))
    assertEquals((one, four), (0, 0))
    assertEquals(csv4, csv1)
    val lines = csv1.linesIterator.toVector
    assertEquals(lines.size, 7)
    assert(lines.head.startsWith("index,decay,seed,ok,"), lines.head)
    assertEquals(lines.tail.map(_.takeWhile(_ != ',')), (0 to 5).map(_.toString).toVector)
    assert(err1.contains("6 points on 1 threads, 0 failed"), err1)
  }

  test("one invalid point does not stop the others, and the exit code says so") {
    val (code, csv, err) = go(
      smaller ++ Seq("--sweep", "sensorDist=1,9", "--sweep", "stepSize=3", "--until", "5")
    )
    assertEquals(code, 1)
    val rows = csv.linesIterator.toVector.tail
    assertEquals(rows.size, 2)
    assert(rows(0).contains(",false,") && rows(0).contains("stepSize"), rows(0))
    assert(rows(1).contains(",true,"), rows(1))
    assert(err.contains("# point 0:") && err.contains("1 failed"), err)
  }

  test("bad axes, parameters and options are reported") {
    assert(go(smaller ++ Seq("--sweep", "nope=1:2:3"))._3.contains("nope"))
    assertEquals(go(smaller ++ Seq("--sweep", "decay=0.1:0.9:3"))._1, 1)
    assertEquals(go(smaller ++ Seq("--sweep", "decay=0.1", "--param", "bogus=1"))._1, 1)
    assert(RunHeadless.parse(List("--frames")).isLeft)
    assert(RunHeadless.parse(List("--scale", "x")).isLeft)
    assert(RunHeadless.parse(List("--every", "0")).isLeft)
    assert(RunHeadless.parse(List("--threads", "0")).isLeft)
    assertEquals(RunHeadless.parse(List("--steps", "-1")).isLeft, true)
  }

  test("a manifest written by a sweep replays to the digest in its row") {
    withDir { dir =>
      val (code, csv, _) = go(
        smaller ++ Seq("--sweep", "decay=0.05,0.2", "--until", "30", "--manifests", dir.toString)
      )
      assertEquals(code, 0)
      val lines = csv.linesIterator.toVector
      val digestAt = lines.head.split(",").indexOf("digest")
      assert(digestAt > 0, lines.head)
      for k <- 0 to 1 do
        val file = dir.resolve(f"point-$k%04d.json")
        assert(Files.exists(file), file.toString)
        val (replayCode, _, summary) = capture("--replay", file.toString)
        assertEquals(replayCode, 0, summary)
        assert(summary.contains("final digest " + lines(k + 1).split(",")(digestAt)), summary)
    }
  }

  test("--out and --jsonl write files instead of printing") {
    withDir { dir =>
      val csv = dir.resolve("sweep.csv")
      val jsonl = dir.resolve("sweep.jsonl")
      val (code, printed, _) = go(
        smaller ++ Seq(
          "--sweep",
          "decay=0.1,0.2",
          "--until",
          "5",
          "--out",
          csv.toString,
          "--jsonl",
          jsonl.toString
        )
      )
      assertEquals(code, 0)
      assertEquals(printed, "")
      assertEquals(Files.readAllLines(csv).size, 3)
      val lines = Files.readAllLines(jsonl)
      assertEquals(lines.size, 2)
      assert(lines.get(0).startsWith("{\"index\":0,"), lines.get(0))
    }
  }

  test("--steps runs whole steps") {
    val (code, csv, summary) = go(smaller ++ Seq("--steps", "25"))
    assertEquals(code, 0, summary)
    assertEquals(csv.linesIterator.toVector.size, 3)
    assert(summary.contains("accepted 25"), summary)
  }

  test("frames: numbered PNGs, one per requested step, the same bytes every time") {
    withDir { a =>
      withDir { b =>
        val args = smaller ++ Seq("--steps", "40", "--every", "20", "--scale", "2", "--seed", "3")
        val (codeA, listA, errA) = go(args ++ Seq("--frames", a.toString))
        val (codeB, _, _) = go(args ++ Seq("--frames", b.toString))
        assertEquals((codeA, codeB), (0, 0))
        val names = (0 to 2).map(i => f"frame-$i%05d.png").toVector
        assertEquals(listA.linesIterator.toVector.map(Path.of(_).getFileName.toString), names)
        assert(errA.contains("3 frames of physarum") && errA.contains("colours"), errA)
        for name <- names do
          assert(
            java.util.Arrays.equals(
              Files.readAllBytes(a.resolve(name)),
              Files.readAllBytes(b.resolve(name))
            ),
            name
          )
        val image = ImageIO.read(a.resolve(names.head).toFile)
        assertEquals(image.getHeight, 64 * 2)
        assert(image.getWidth > 64 * 2, "no colour scale")
        val plain = go(smaller ++ Seq("--steps", "0", "--frames", a.resolve("p").toString))
        assertEquals(plain._1, 0, plain._3)
      }
    }
  }

  test("frames of a replayed painted wave session show the wall until it is erased") {
    withDir { dir =>
      val p = Wave.params.defaults.copy(nx = 64, ny = 64)
      val run = Physis.model(Wave).withParams(p).stepping(Wave.stepping).build()
      val _ = run.intervene(Brush("wall", Disc(30.5, 30.5, 5.0), Op.Assign, 1.0))
      run.runSteps(20)
      val _ = run.intervene(Brush("wall", Disc(30.5, 30.5, 8.0), Op.Erase, 0.0))
      run.runSteps(20)
      val manifest = run.manifest(run.time).copy(steps = Some(run.stepCount))
      val file = dir.resolve("session.json")
      Files.writeString(file, Manifest.toJson(manifest), StandardCharsets.UTF_8)
      val frames = dir.resolve("frames")
      val (code, listed, err) =
        capture("--replay", file.toString, "--frames", frames.toString, "--every", "10")
      assertEquals(code, 0, err)
      val paths = listed.linesIterator.toVector.map(Path.of(_))
      assertEquals(paths.size, 5)
      def grey(path: Path): Int =
        val image = ImageIO.read(path.toFile)
        var n = 0
        for y <- 0 until image.getHeight; x <- 0 until image.getWidth do
          if image.getRGB(x, y) == HeatmapRaster.OverlayGrey then n += 1
        n
      assertEquals(grey(paths(0)), 0)
      assert(grey(paths(1)) > 50, s"${grey(paths(1))} wall pixels after 10 steps")
      assert(grey(paths(2)) > 50)
      assertEquals(grey(paths(3)), 0)
    }
  }

  test("a montage tiles the final images, each equal to the image of the single run") {
    withDir { dir =>
      val base = Seq("--model", "gray-scott", "--param", "nx=32", "--param", "ny=32")
      val montage = dir.resolve("montage.png")
      val (code, csv, err) = go(
        base ++ Seq(
          "--until",
          "40",
          "--sweep",
          "F=0.03,0.05",
          "--sweep",
          "k=0.06,0.065,0.07",
          "--montage",
          montage.toString
        )
      )
      assertEquals(code, 0, err)
      assertEquals(csv.linesIterator.size, 7)
      assert(err.contains("montage of 6 pictures") && err.contains("3 to a row"), err)
      val sheet = ImageIO.read(montage.toFile)
      assertEquals((sheet.getWidth, sheet.getHeight), (3 * 32 + 2, 2 * 32 + 1))
      // point 4 is F = 0.05, k = 0.065: column 1, row 1
      val frames = dir.resolve("single")
      val (single, _, singleErr) = go(
        base ++ Seq(
          "--param",
          "F=0.05",
          "--param",
          "k=0.065",
          "--steps",
          "40",
          "--every",
          "40",
          "--no-color-bar",
          "--frames",
          frames.toString
        )
      )
      assertEquals(single, 0, singleErr)
      val tile = ImageIO.read(frames.resolve("frame-00001.png").toFile)
      assertEquals((tile.getWidth, tile.getHeight), (32, 32))
      for y <- 0 until 32; x <- 0 until 32 do
        assertEquals(sheet.getRGB(33 + x, 33 + y), tile.getRGB(x, y), s"($x, $y)")
      val (wide, _, wideErr) = go(
        base ++ Seq(
          "--until",
          "5",
          "--sweep",
          "F=0.03,0.05,0.04",
          "--montage",
          montage.toString,
          "--montage-columns",
          "2",
          "--scale",
          "2"
        )
      )
      assertEquals(wide, 0, wideErr)
      val sheet2 = ImageIO.read(montage.toFile)
      assertEquals((sheet2.getWidth, sheet2.getHeight), ((2 * 32 + 1) * 2, (2 * 32 + 1) * 2))
    }
  }

  test("a montage needs a model with an image") {
    val (code, _, err) = capture(
      "--model",
      "lorenz",
      "--until",
      "1",
      "--sweep",
      "rho=20,28",
      "--montage",
      "/tmp/never-written.png"
    )
    assertEquals(code, 1)
    assert(err.contains("no image for a montage"), err)
    assert(RunHeadless.parse(List("--montage-columns", "0")).isLeft)
  }

  test("a model without an image says so") {
    val (code, _, err) = capture("--model", "lorenz", "--frames", "/tmp/never-written")
    assertEquals(code, 1)
    assert(err.contains("has no image to export"), err)
    val boids = capture(
      "--model",
      "boids",
      "--param",
      "n=20",
      "--steps",
      "2",
      "--frames",
      "/tmp/never-written"
    )
    assertEquals(boids._1, 1)
    assert(boids._3.contains("no image"), boids._3)
  }
