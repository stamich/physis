package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.run.Manifest
import physis.netcdf.{AttrValue, NcFile}

class CliExportSuite extends munit.FunSuite:
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private val sea = Seq(
    "--model",
    "shallow",
    "--param",
    "nx=48",
    "--param",
    "ny=32",
    "--param",
    "start=1"
  )

  private def withDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-cli-export")
    try body(dir)
    finally
      val stream = Files.walk(dir)
      try
        stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { f =>
          val _ = Files.deleteIfExists(f)
        }
      finally stream.close()

  private def read(path: Path): NcFile = NcFile.read(path).fold(e => fail(e), identity)

  test("export writes a dataset of the sea, with its manifest inside, and prints the path") {
    withDir { dir =>
      val file = dir.resolve("sea.nc")
      val (code, out, err) =
        go(Seq("export", file.toString, "--steps", "40", "--every", "20") ++ sea*)
      assertEquals(code, 0, err)
      assertEquals(out.trim, file.toString)
      assert(err.contains("3 records"), err)
      val f = read(file)
      assertEquals(f.records, 3)
      assertEquals(f.schema.dim("x").flatMap(_.size), Some(48))
      assertEquals(f.schema.dim("y").flatMap(_.size), Some(32))
      val manifest = f.text("physis_manifest").getOrElse(fail("no manifest in the file"))
      val m = Manifest.fromJson(manifest).fold(e => fail(e), identity)
      assertEquals(m.model, "shallow")
      assertEquals(m.steps, Some(40L))
      assertEquals(m.params("nx"), 48.0)
    }
  }

  test("the default is a tenth of the run, and strokes are in the manifest and in the water") {
    withDir { dir =>
      val file = dir.resolve("sea.nc")
      val (code, _, err) = go(
        Seq("export", file.toString, "--steps", "50", "--stroke", "0:pulse:height:24:16:3:1.0") ++
          sea*
      )
      assertEquals(code, 0, err)
      val f = read(file)
      assertEquals(f.records, 11)
      val m = Manifest
        .fromJson(f.text("physis_manifest").get)
        .fold(e => fail(e), identity)
      assertEquals(m.interventions.size, 1)
      val first = f.record("height", 0).fold(e => fail(e), identity)
      assert(first.max > 0.0, "the painted rise is in the first record")
    }
  }

  test("--vars keeps the named variables and --float halves the fields") {
    withDir { dir =>
      val a = dir.resolve("a.nc")
      val b = dir.resolve("b.nc")
      val full = go(Seq("export", a.toString, "--steps", "20", "--every", "10") ++ sea*)
      assertEquals(full._1, 0, full._3)
      val some = go(
        Seq(
          "export",
          b.toString,
          "--steps",
          "20",
          "--every",
          "10",
          "--vars",
          "height,mass",
          "--float"
        ) ++ sea*
      )
      assertEquals(some._1, 0, some._3)
      val names = read(b).schema.vars.map(_.name).toSet
      assert(names.contains("height") && names.contains("mass"))
      assert(!names.contains("speed"))
      assert(Files.size(b) < Files.size(a) / 4)
      assertEquals(read(b).global("physis_precision"), Some(AttrValue.Text("float32")))
      val bad = go(Seq("export", b.toString, "--steps", "20", "--vars", "nope") ++ sea*)
      assertEquals(bad._1, 1)
      assert(bad._3.contains("no variable 'nope'"), bad._3)
    }
  }

  test("every model can be exported, and a model without fields writes its probes") {
    val small = Map(
      "lorenz" -> Seq.empty[String],
      "double-pendulum" -> Seq.empty,
      "gray-scott" -> Seq("--param", "nx=32", "--param", "ny=32"),
      "boids" -> Seq("--param", "n=20"),
      "wave" -> Seq("--param", "nx=64", "--param", "ny=64"),
      "physarum" -> Seq("--param", "nx=64", "--param", "ny=64", "--param", "n=100"),
      "fluid" -> Seq("--param", "nx=16", "--param", "ny=16"),
      "convection" -> Seq("--param", "nx=32", "--param", "ny=16"),
      "shallow" -> Seq("--param", "nx=24", "--param", "ny=16")
    )
    withDir { dir =>
      for (model, params) <- small do
        val file = dir.resolve(s"$model.nc")
        val (code, _, err) =
          go(Seq(
            "export",
            file.toString,
            "--model",
            model,
            "--steps",
            "4",
            "--every",
            "2"
          ) ++ params*)
        assertEquals(code, 0, s"$model: $err")
        val f = read(file)
        assertEquals(f.records, 3, model)
        assertEquals(f.text("physis_model"), Some(model))
        val fields = f.schema.vars.filter(_.dims.size == 3)
        val hasFields = !Set("lorenz", "double-pendulum", "boids").contains(model)
        assertEquals(fields.nonEmpty, hasFields, model)
    }
  }

  test("a replay of the manifest in a dataset writes the same data") {
    withDir { dir =>
      val a = dir.resolve("a.nc")
      val b = dir.resolve("b.nc")
      val m = dir.resolve("m.json")
      val args = Seq("--steps", "40", "--stroke", "0:pulse:height:24:16:3:1.0") ++ sea
      assertEquals(
        go(Seq("export", a.toString, "--every", "20", "--manifest", m.toString) ++ args*)._1,
        0
      )
      val (code, _, err) =
        go("replay", m.toString, "--export", b.toString, "--every", "20")
      assertEquals(code, 0, err)
      assert(!err.contains("DIFFERS"), err)
      val fa = read(a)
      val fb = read(b)
      assertEquals(fa.records, fb.records)
      for v <- fa.schema.vars do
        assertEquals(
          fa.values(v.name).toOption.map(_.toSeq),
          fb.values(v.name).toOption.map(_.toSeq),
          v.name
        )
    }
  }

  test("export needs a file, fixed steps and a model that can be run") {
    assertEquals(go("export")._1, 2)
    assertEquals(go("export", "x.nc", "--sweep", "k=1,2")._1, 2)
    assertEquals(go("export", "x.nc", "--model", "nope")._1, 1)
    assertEquals(go("export", "x.nc", "--model", "double-pendulum", "--adaptive", "1e-8")._1, 1)
  }
