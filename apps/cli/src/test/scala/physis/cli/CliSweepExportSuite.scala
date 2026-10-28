package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.netcdf.{NcCompare, NcFile}

class CliSweepExportSuite extends munit.FunSuite:
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("physis-sweep-export"),
    teardown = dir =>
      val stream = Files.walk(dir)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
      finally stream.close()
  )

  private val sea = Seq(
    "--model",
    "shallow",
    "--param",
    "nx=24",
    "--param",
    "ny=16",
    "--param",
    "start=1"
  )

  tmp.test("a sweep writes one dataset per point, each the replay of its manifest") { dir =>
    val sets = dir.resolve("sets")
    val manifests = dir.resolve("manifests")
    val (code, csv, err) = go(
      ("sweep" +: sea) ++ Seq(
        "--sweep",
        "depth=2000,4000",
        "--until",
        "600",
        "--export",
        sets.toString,
        "--every",
        "4",
        "--manifests",
        manifests.toString
      )*
    )
    assertEquals(code, 0, err)
    assert(csv.linesIterator.size >= 3, csv)
    val files = Files.list(sets).sorted().toArray.map(_.asInstanceOf[Path]).toVector
    assertEquals(files.map(_.getFileName.toString), Vector("point-0000.nc", "point-0001.nc"))
    val first = NcFile.read(files.head).fold(e => fail(e), identity)
    val second = NcFile.read(files(1)).fold(e => fail(e), identity)
    assertEquals(first.records, second.records)
    assert(NcCompare.diff(first, second).isDefined, "different depths give different data")

    // the dataset of a point is what replaying its manifest writes
    val again = dir.resolve("again.nc")
    val (c2, _, e2) = go(
      "replay",
      manifests.resolve("point-0000.json").toString,
      "--export",
      again.toString,
      "--every",
      "4"
    )
    assertEquals(c2, 0, e2)
    assertEquals(
      NcCompare.diff(first, NcFile.read(again).fold(e => fail(e), identity)),
      None
    )
    // and verify accepts it
    assertEquals(go("verify", files.head.toString)._1, 0)
  }

  tmp.test("a sweep with a stroke-free manifest needs no --manifests to export") { dir =>
    val sets = dir.resolve("sets")
    val (code, _, err) = go(
      ("sweep" +: sea) ++ Seq("--sweep", "depth=3000", "--until", "600", "--export", sets.toString)*
    )
    assertEquals(code, 0, err)
    assert(Files.exists(sets.resolve("point-0000.nc")))
    assert(!Files.exists(dir.resolve("manifests")))
  }

  tmp.test("a dry run writes nothing") { dir =>
    val sets = dir.resolve("sets")
    val (code, _, _) = go(
      ("sweep" +: sea) ++ Seq("--sweep", "depth=2000,4000", "--dry-run", "--export", sets.toString)*
    )
    assertEquals(code, 0)
    assert(!Files.exists(sets))
  }
