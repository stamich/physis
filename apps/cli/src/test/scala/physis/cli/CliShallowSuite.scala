package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.run.Manifest

class CliShallowSuite extends munit.FunSuite:
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
    Seq("--model", "shallow", "--param", "nx=48", "--param", "ny=32", "--param", "f0=0.0001")

  private def withDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-cli-shallow")
    try body(dir)
    finally
      val stream = Files.walk(dir)
      try
        stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { f =>
          val _ = Files.deleteIfExists(f)
        }
      finally stream.close()

  test("run prints the probes of the sea, among them the mass and the highest surface") {
    val (code, csv, err) = go(Seq("run", "--steps", "40", "--sample-every", "50") ++ small*)
    assertEquals(code, 0, err)
    val header = csv.linesIterator.next().split(",").toVector
    for name <- Seq("mass", "energy", "max_height", "min_height", "max_speed") do
      assert(header.contains(name), header.toString)
  }

  test("a sweep over gravity gives the same table on 1 and on 4 threads, and faster waves") {
    val grid = Seq("--sweep", "gravity=5,10", "--until", "3000", "--sample-every", "1000")
    val front = small ++ Seq("--param", "start=1")
    val (c1, csv1, err1) = go(Seq("sweep", "--threads", "1") ++ front ++ grid*)
    val (c4, csv4, err4) = go(Seq("sweep", "--threads", "4") ++ front ++ grid*)
    assertEquals(c1, 0, err1)
    assertEquals(c4, 0, err4)
    assertEquals(csv4, csv1)
    val lines = csv1.linesIterator.toVector
    assertEquals(lines.size, 3)
    val speed = lines.head.split(",").indexOf("max_speed")
    assert(speed > 0, lines.head)
    assert(lines(1).split(",")(speed).toDouble != lines(2).split(",")(speed).toDouble)
  }

  test("a sweep over beta runs the Rossby wave on a beta plane") {
    val grid = Seq("--sweep", "beta=0,0.00000000004", "--until", "2000", "--sample-every", "1000")
    val (code, csv, err) =
      go(
        Seq("sweep", "--model", "shallow", "--preset", "Rossby wave") ++
          Seq("--param", "nx=48", "--param", "ny=24") ++ grid*
      )
    assertEquals(code, 0, err)
    assertEquals(csv.linesIterator.size, 3)
  }

  test("animate gives the same bytes twice, and a replay of its manifest gives them again") {
    withDir { dir =>
      val a = dir.resolve("a.png")
      val b = dir.resolve("b.png")
      val c = dir.resolve("c.png")
      val m = dir.resolve("m.json")
      val args =
        Seq("--preset", "Harbour", "--param", "nx=60", "--param", "ny=40") ++
          Seq("--steps", "60", "--every", "20")
      val base = Seq("--model", "shallow")
      assertEquals(
        go(Seq("animate", a.toString, "--manifest", m.toString) ++ base ++ args*)._1,
        0
      )
      assertEquals(go(Seq("animate", b.toString) ++ base ++ args*)._1, 0)
      assert(java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b)))
      val manifest = Manifest.fromJson(Files.readString(m)).fold(e => fail(e), identity)
      assertEquals(manifest.model, "shallow")
      assert(manifest.interventions.nonEmpty, "the painted land and wave are not recorded")
      assertEquals(manifest.steps, Some(60L))
      val (code, _, err) = go("replay", m.toString, "--animate", c.toString, "--every", "20")
      assertEquals(code, 0, err)
      assert(!err.contains("DIFFERS"), err)
      assert(java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(c)))
    }
  }

  test("frames writes the numbered pictures of the surface") {
    withDir { dir =>
      val (code, out, err) =
        go(Seq("frames", dir.toString, "--steps", "20", "--every", "10") ++ small*)
      assertEquals(code, 0, err)
      assertEquals(out.linesIterator.size, 3)
      assert(javax.imageio.ImageIO.read(dir.resolve("frame-00000.png").toFile) != null)
    }
  }

  test("shallow is listed and described with its presets and probes") {
    val (code, text, err) = go("describe", "shallow")
    assertEquals(code, 0, err)
    for name <-
        Seq("Tsunami", "Adjustment", "Rossby wave", "Kelvin wave", "Harbour", "max_speed", "beta")
    do assert(text.contains(name), s"$name is missing from:\n$text")
  }
