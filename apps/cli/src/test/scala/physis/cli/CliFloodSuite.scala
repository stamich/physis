package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.netcdf.{AttrValue, NcFile}

class CliFloodSuite extends munit.FunSuite:
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private val beach = Seq("--model", "shallow", "--preset", "Beach run-up")

  private def withDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-cli-flood")
    try body(dir)
    finally
      val stream = Files.walk(dir)
      try
        stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { f =>
          val _ = Files.deleteIfExists(f)
        }
      finally stream.close()

  test("a sweep over the wave height is a table of run-up: a higher wave runs higher") {
    val (code, csv, err) =
      go(
        Seq("sweep", "--sweep", "amp=4,8,12", "--until", "50000", "--sample-every", "10000") ++
          beach*
      )
    assertEquals(code, 0, err)
    val rows = csv.linesIterator.toVector.map(_.split(",", -1).toVector)
    val at = rows.head.indexOf("max_runup")
    assert(at > 0, rows.head.toString)
    val runup = rows.tail.map(_(at).toDouble)
    assertEquals(runup.size, 3)
    assert(runup(0) <= runup(1) && runup(1) <= runup(2), s"run-up $runup in\n$csv")
    assert(runup(2) > 0.0, s"the highest wave should reach the shore:\n$csv")
    val depth = rows.head.indexOf("max_inundation")
    assert(rows.tail.map(_(depth).toDouble).last > 0.0, "the deepest water over the shore")
  }

  test("a dataset of a flood has the depth of the water and where it is dry, with units") {
    withDir { dir =>
      val file = dir.resolve("flood.nc")
      val (code, _, err) =
        go(Seq("export", file.toString, "--steps", "300", "--every", "100") ++ beach*)
      assertEquals(code, 0, err)
      val f = NcFile.read(file).fold(e => fail(e), identity)
      for name <- Seq("water", "dry", "max_runup", "wet_fraction") do
        assert(f.schema.variable(name).isDefined, s"no variable $name")
      assertEquals(
        f.schema.variable("water").flatMap(_.attr("units")),
        Some(AttrValue.Text("m"))
      )
      val dry = f.record("dry", 0).fold(e => fail(e), identity)
      assert(dry.contains(1.0) && dry.contains(0.0), "land and sea in the first record")
      val (vcode, _, verr) = go("verify", file.toString)
      assertEquals(vcode, 0, verr)
    }
  }
