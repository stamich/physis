package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.netcdf.{AttrValue, NcFile}

/** The sphere from the command line: the dataset of a globe, and the arrival-time table. */
class CliSphereSuite extends munit.FunSuite:
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private def globe(nx: Int, ny: Int): Seq[String] =
    Seq(
      "--model",
      "shallow",
      "--param",
      "geometry=1",
      "--param",
      s"nx=$nx",
      "--param",
      s"ny=$ny",
      "--param",
      "latMax=80"
    )

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("physis-cli-sphere"),
    teardown = dir =>
      val stream = Files.walk(dir)
      try stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
      finally stream.close()
  )

  tmp.test("the dataset of a globe has the axes lon and lat in degrees, and verifies") { dir =>
    val file = dir.resolve("globe.nc")
    val bump = Seq("--stroke", "0:pulse:height:18:8:2:1.0")
    val (code, _, err) =
      go(Seq("export", file.toString, "--steps", "20", "--every", "10") ++ globe(36, 16) ++ bump*)
    assertEquals(code, 0, err)
    val f = NcFile.read(file).fold(e => fail(e), identity)
    assertEquals(f.schema.dim("lon").flatMap(_.size), Some(36))
    assertEquals(f.schema.dim("lat").flatMap(_.size), Some(16))
    assert(f.schema.variable("x").isEmpty && f.schema.variable("y").isEmpty)
    val lon = f.values("lon").fold(e => fail(e), identity)
    val lat = f.values("lat").fold(e => fail(e), identity)
    assertEqualsDouble(lon.head, -175.0, 1e-9)
    assertEqualsDouble(lon.last, 175.0, 1e-9)
    assertEqualsDouble(lat.head, 75.0, 1e-9)
    assertEqualsDouble(lat.last, -75.0, 1e-9)
    val units = f.schema.variable("lat").flatMap(_.attr("units"))
    assertEquals(units, Some(AttrValue.Text("degrees_north")))
    assertEquals(
      f.schema.variable("height").map(_.dims),
      Some(Vector("time", "lat", "lon"))
    )
    val (v, out, verr) = go("verify", file.toString)
    assertEquals(v, 0, verr)
    assert(out.startsWith("verified: "), out)
  }

  test("a sweep over the longitude of the watched point is a table of arrival times") {
    val args = Seq(
      "sweep",
      "--model",
      "shallow",
      "--preset",
      "Pacific tsunami",
      "--sweep",
      "watchLon=-170,-150,-130",
      "--until",
      "45000",
      "--sample-every",
      "45000"
    )
    val (code, csv, err) = go(args*)
    assertEquals(code, 0, err)
    val lines = csv.linesIterator.toVector
    assertEquals(lines.size, 4)
    val col = lines.head.split(",").indexOf("arrival")
    assert(col > 0, lines.head)
    val t = lines.tail.map(_.split(",")(col).toDouble)
    // the farther the point from Japan, the later the wave; none is -1, the wave has come
    assert(t.forall(_ > 0.0), t.toString)
    assert(t(0) < t(1) && t(1) < t(2), t.toString)
    // 40 degrees of longitude along 20 degrees north is some 4 000 km: at least 5 hours at 198 m/s
    assert(t(2) - t(0) > 3.0 * 3600.0, t.toString)
  }

  test("the Pacific tsunami preset runs from the command line") {
    val (code, _, err) =
      go("run", "--model", "shallow", "--preset", "Pacific tsunami", "--steps", "20")
    assertEquals(code, 0, err)
  }
