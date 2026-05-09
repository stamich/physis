package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.run.Manifest

class CliConvectionSuite extends munit.FunSuite:
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
    Seq("--model", "convection", "--param", "nx=32", "--param", "ny=16")

  private def withDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-cli-convection")
    try body(dir)
    finally
      val stream = Files.walk(dir)
      try
        stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { f =>
          val _ = Files.deleteIfExists(f)
        }
      finally stream.close()

  test("run prints the probes of the layer, among them the Nusselt number") {
    val (code, csv, err) = go(Seq("run", "--steps", "40", "--sample-every", "1") ++ small*)
    assertEquals(code, 0, err)
    val header = csv.linesIterator.next().split(",").toVector
    for name <- Seq("kinetic_energy", "max_divergence", "nusselt", "nusselt_floor", "max_up") do
      assert(header.contains(name), header.toString)
  }

  test("a sweep over the Rayleigh number gives the same table on 1 and on 4 threads") {
    val grid = Seq("--sweep", "rayleigh=1200,3000", "--until", "800", "--sample-every", "10")
    val (c1, csv1, err1) = go(Seq("sweep", "--threads", "1") ++ small ++ grid*)
    val (c4, csv4, err4) = go(Seq("sweep", "--threads", "4") ++ small ++ grid*)
    assertEquals(c1, 0, err1)
    assertEquals(c4, 0, err4)
    assertEquals(csv4, csv1)
    val lines = csv1.linesIterator.toVector
    assertEquals(lines.size, 3)
    val nu = lines.head.split(",").indexOf("nusselt")
    assert(nu > 0, lines.head)
    val below = lines(1).split(",")(nu).toDouble
    val above = lines(2).split(",")(nu).toDouble
    assertEqualsDouble(below, 1.0, 0.01, "below the critical value nothing convects")
    assert(above > 1.3, s"above it the layer carries heat: $above")
  }

  test("animate gives the same bytes twice, and a replay of its manifest gives them again") {
    withDir { dir =>
      val a = dir.resolve("a.png")
      val b = dir.resolve("b.png")
      val c = dir.resolve("c.png")
      val m = dir.resolve("m.json")
      val args =
        Seq("--preset", "Painted heat", "--param", "nx=48", "--param", "ny=24") ++
          Seq("--steps", "60", "--every", "20")
      val base = Seq("--model", "convection")
      assertEquals(
        go(Seq("animate", a.toString, "--manifest", m.toString) ++ base ++ args*)._1,
        0
      )
      assertEquals(go(Seq("animate", b.toString) ++ base ++ args*)._1, 0)
      assert(java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b)))
      val manifest = Manifest.fromJson(Files.readString(m)).fold(e => fail(e), identity)
      assertEquals(manifest.model, "convection")
      assert(manifest.interventions.nonEmpty, "the painted heat is not recorded")
      assertEquals(manifest.steps, Some(60L))
      val (code, _, err) = go("replay", m.toString, "--animate", c.toString, "--every", "20")
      assertEquals(code, 0, err)
      assert(!err.contains("DIFFERS"), err)
      assert(java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(c)))
    }
  }

  test("frames writes the numbered pictures of the temperature") {
    withDir { dir =>
      val (code, out, err) =
        go(Seq("frames", dir.toString, "--steps", "20", "--every", "10") ++ small*)
      assertEquals(code, 0, err)
      assertEquals(out.linesIterator.size, 3)
      assert(javax.imageio.ImageIO.read(dir.resolve("frame-00000.png").toFile) != null)
    }
  }

  test("convection is listed and described with its presets and probes") {
    val (code, text, err) = go("describe", "convection")
    assertEquals(code, 0, err)
    for name <- Seq("Onset", "Quiet", "Rolls", "Plumes", "Painted heat", "nusselt", "rayleigh") do
      assert(text.contains(name), s"$name is missing from:\n$text")
  }
