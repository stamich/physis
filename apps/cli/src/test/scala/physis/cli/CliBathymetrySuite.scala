package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.run.{Intervention, Manifest}
import physis.netcdf.{Dim, NcFile, NcType, NcWriter, Schema, Var}

class CliBathymetrySuite extends munit.FunSuite:
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  /** A coast: 100 m of water in the west, rising to 10 m above the sea in the east. */
  private def writeFloor(path: Path, top: Double): Unit =
    val schema = Schema(
      Vector(Dim.fixed("y", 4), Dim.fixed("x", 6)),
      Vector.empty,
      Vector(Var("elevation", Vector("y", "x"), NcType.Float64))
    )
    val values = Array.tabulate(24)(k => if k % 6 < 3 then -100.0 else top * (k % 6 - 2) / 3.0)
    val out = new ByteArrayOutputStream()
    val w = new NcWriter(out, schema, 0, Map("elevation" -> values))
    w.finish()
    Files.write(path, out.toByteArray)
    ()

  private val sea = Seq(
    "--model",
    "shallow",
    "--param",
    "nx=48",
    "--param",
    "ny=16",
    "--param",
    "depth=100",
    "--param",
    "dx=2500",
    "--param",
    "nonlinear=1",
    "--param",
    "flooding=1",
    "--param",
    "start=1"
  )

  private def withDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-cli-floor")
    try body(dir)
    finally
      val stream = Files.walk(dir)
      try
        stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { f =>
          val _ = Files.deleteIfExists(f)
        }
      finally stream.close()

  test("--bathymetry puts the file's name and digest in the manifest, and verify accepts it") {
    withDir { dir =>
      val floor = dir.resolve("coast.nc")
      writeFloor(floor, 10.0)
      val data = dir.resolve("run.nc")
      val (code, _, err) = go(
        Seq(
          "export",
          data.toString,
          "--steps",
          "30",
          "--every",
          "10",
          "--bathymetry",
          floor.toString
        ) ++
          sea*
      )
      assertEquals(code, 0, err)
      val f = NcFile.read(data).fold(e => fail(e), identity)
      val m = Manifest.fromJson(f.text("physis_manifest").get).fold(e => fail(e), identity)
      val loads = m.interventions.map(_.intervention).collect { case l: Intervention.Load => l }
      assertEquals(loads.map(_.file), Vector(floor.toString))
      val (vcode, _, verr) = go("verify", data.toString)
      assertEquals(vcode, 0, verr)
      val water = f.record("water", f.records - 1).fold(e => fail(e), identity)
      assert(water.contains(0.0), "the east is dry land")
      assert(water.max > 99.0, "the west is sea")
    }
  }

  test("a replay refuses a floor that is not the file of the run") {
    withDir { dir =>
      val floor = dir.resolve("coast.nc")
      writeFloor(floor, 10.0)
      val manifest = dir.resolve("run.json")
      val (code, _, err) = go(
        Seq(
          "run",
          "--steps",
          "20",
          "--manifest",
          manifest.toString,
          "--bathymetry",
          floor.toString
        ) ++
          sea*
      )
      assertEquals(code, 0, err)
      val (same, _, serr) = go("replay", manifest.toString)
      assertEquals(same, 0, serr)
      writeFloor(floor, 12.0)
      val (changed, _, cerr) = go("replay", manifest.toString)
      assertEquals(changed, 1)
      assert(cerr.contains("not the file of the run"), cerr)
    }
  }

  test("a missing or unreadable floor is an error, not a crash") {
    withDir { dir =>
      val (code, _, err) =
        go(Seq("run", "--steps", "5", "--bathymetry", dir.resolve("no.nc").toString) ++ sea*)
      assertEquals(code, 1)
      assert(err.contains("cannot read"), err)
      val junk = dir.resolve("junk.nc")
      Files.write(junk, Array[Byte](1, 2, 3, 4))
      val (bad, _, berr) = go(Seq("run", "--steps", "5", "--bathymetry", junk.toString) ++ sea*)
      assertEquals(bad, 1)
      assert(berr.contains("junk.nc"), berr)
    }
  }
