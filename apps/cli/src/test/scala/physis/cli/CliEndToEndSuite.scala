package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

/** Every command through `Cli.execute`, with real files in a temporary directory. */
class CliEndToEndSuite extends munit.FunSuite:
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private def inTempDir(body: Path => Unit): Unit =
    val dir = Files.createTempDirectory("physis-cli-e2e")
    try body(dir)
    finally
      val stream = Files.walk(dir)
      try
        stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { f =>
          val _ = Files.deleteIfExists(f)
        }
      finally stream.close()

  private val gs = Seq("--model", "gray-scott", "--param", "nx=32", "--param", "ny=32")
  private val grid = Seq("--sweep", "F=0.03,0.05", "--sweep", "k=0.06,0.065", "--until", "20")

  test("montage writes the CSV, the JSON lines and one sheet with a tile for every point") {
    inTempDir { dir =>
      val csv = dir.resolve("map.csv")
      val jsonl = dir.resolve("map.jsonl")
      val png = dir.resolve("map.png")
      val (code, out, err) = go(
        Seq(
          "montage",
          png.toString,
          "--out",
          csv.toString,
          "--jsonl",
          jsonl.toString
        ) ++ gs ++ grid*
      )
      assertEquals(code, 0, err)
      assertEquals(out, "") // the CSV went to the file
      assertEquals(Files.readAllLines(csv).size, 5)
      val lines = Files.readAllLines(jsonl)
      assertEquals(lines.size, 4)
      assert(lines.stream().allMatch(_.startsWith("{")))
      val sheet = ImageIO.read(png.toFile)
      assertEquals((sheet.getWidth, sheet.getHeight), (32 * 2 + 1, 32 * 2 + 1)) // two by two, 1 gap
      assert(err.contains("# montage of 4 pictures"), err)
      val (c2, _, e2) = go(
        Seq("montage", png.toString, "--scale", "2", "--montage-columns", "4") ++ gs ++ grid*
      )
      assertEquals(c2, 0, e2)
      val wide = ImageIO.read(png.toFile)
      assertEquals((wide.getWidth, wide.getHeight), ((32 * 4 + 3) * 2, 32 * 2))
    }
  }

  test("standard output holds only the CSV, standard error only the notes") {
    val (code, out, err) = go(Seq("sweep") ++ gs ++ grid*)
    assertEquals(code, 0, err)
    assert(out.linesIterator.forall(!_.startsWith("#")), out)
    assert(err.linesIterator.forall(_.startsWith("#")), err)
  }

  test("every command refuses a wrong command line with exit code 2 and prints the usage") {
    val wrong = Seq(
      Seq("run", "--frames", "out"),
      Seq("frames"),
      Seq("frames", "out", "--sweep", "k=1,2"),
      Seq("sweep"),
      Seq("montage", "m.png"),
      Seq("replay"),
      Seq("replay", "m.json", "--sweep", "k=1,2"),
      Seq("list", "--all"),
      Seq("describe"),
      Seq("sweep", "--sweep", "k=1,2", "--threads", "0", "--model", "gray-scott")
    )
    for args <- wrong do
      val (code, out, err) = go(args*)
      assertEquals(code, 2, s"${args.mkString(" ")}: $err")
      assertEquals(out, "")
      assert(err.startsWith("error: "), err)
  }

  test("a run that cannot be done is exit code 1") {
    inTempDir { dir =>
      assertEquals(go("run", "--model", "nope")._1, 1)
      assertEquals(go("describe", "nope")._1, 1)
      assertEquals(go("replay", dir.resolve("none.json").toString)._1, 1)
      val (c, _, err) = go(
        "montage",
        dir.resolve("m.png").toString,
        "--model",
        "lorenz",
        "--sweep",
        "rho=20,28",
        "--until",
        "1"
      )
      assertEquals(c, 1)
      assert(err.contains("has no image for a montage"), err)
      assert(!Files.exists(dir.resolve("m.png")))
      // an axis the model refuses is found before any run starts
      val (c3, _, e3) = go("sweep", "--model", "lorenz", "--sweep", "rho=-5,28", "--until", "1")
      assertEquals(c3, 1)
      assert(e3.startsWith("error: axis 'rho'"), e3)
      assertEquals(go("sweep", "--model", "lorenz", "--sweep", "rho", "--until", "1")._1, 1)
    }
  }
