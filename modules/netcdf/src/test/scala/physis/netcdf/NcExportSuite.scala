package physis.netcdf

import java.nio.file.{Files, Path}

import physis.core.{Probe, Stepping}
import physis.core.live.ExportSpec
import physis.core.run.{Physis, Run}
import physis.models.lorenz.Lorenz
import physis.models.shallow.{Shallow, ShallowExport, ShallowParams, ShallowState}
import physis.ode.OdeState

class NcExportSuite extends munit.FunSuite:
  private val Nx = 24
  private val Ny = 16
  private val spec = ShallowExport.spec(Shallow.params.defaults)

  private def sea(): Run[ShallowParams, ShallowState] =
    Physis
      .model(Shallow)
      .stepping(Shallow.stepping)
      .overrides(Map("nx" -> Nx.toDouble, "ny" -> Ny.toDouble, "start" -> 1.0))
      .build()

  private def withTemp[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-nc-export")
    try body(dir.resolve("run.nc"))
    finally
      Seq(dir.resolve("run.nc"), dir).foreach(f => Files.deleteIfExists(f))

  private def dump(
      steps: Long,
      options: NcExport.Options,
      path: Path,
      probes: Vector[Probe[ShallowState]] = Shallow.probes
  ): Either[String, Int] =
    NcExport.write(sea(), steps, ShallowState.stateExport, spec, probes, path, options)

  private def read(path: Path): NcFile = NcFile.read(path).fold(e => fail(e), identity)

  test("a sea is written with its axes, fields, probes and digests, and reads back") {
    withTemp { path =>
      assertEquals(dump(20, NcExport.Options(every = 10), path), Right(3))
      val f = read(path)
      assertEquals(f.records, 3)
      assertEquals(f.values("time").toOption.map(_.toSeq), Some(Seq(0.0, 500.0, 1000.0)))
      assertEquals(f.values("step").toOption.map(_.toSeq), Some(Seq(0.0, 10.0, 20.0)))
      assertEquals(f.schema.dim("x").flatMap(_.size), Some(Nx))
      assertEquals(f.schema.dim("y").flatMap(_.size), Some(Ny))
      val x = f.values("x").fold(e => fail(e), identity)
      assertEquals(x.head, 10_000.0)
      assertEquals(x.last, (Nx - 0.5) * 20_000.0)
      assertEquals(f.schema.variable("y").get.attr("positive"), Some(AttrValue.Text("down")))
      assertEquals(
        f.schema.variable("height").get.attr("standard_name"),
        Some(AttrValue.Text("sea_surface_height_above_mean_sea_level"))
      )
      assertEquals(f.schema.variable("height").get.dims, Vector("time", "y", "x"))
      assertEquals(f.text("physis_model"), Some("shallow"))
      assertEquals(f.text("physis_steps"), Some("20"))
      assertEquals(f.text("physis_every"), Some("10"))
      assertEquals(f.text("Conventions"), Some("CF-1.8"))
      for name <- Seq("mass", "energy", "max_height", "min_height", "max_speed") do
        assert(f.schema.variable(name).isDefined, name)
    }
  }

  test("the last record is the state of the run at that step, and its digest is the run's") {
    withTemp { path =>
      assertEquals(dump(20, NcExport.Options(every = 10), path), Right(3))
      val f = read(path)
      val twin = sea()
      twin.runSteps(20)
      val heights = f.record("height", 2).fold(e => fail(e), identity)
      val direct = new Array[Double](ShallowState.stateExport.size(twin.state))
      ShallowState.stateExport.copyInto(twin.state, direct)
      assertEquals(heights.toSeq, direct.slice(0, Nx * Ny).toSeq)
      val hi = f.record("digest_hi", 2).fold(e => fail(e), identity)(0).toLong
      val lo = f.record("digest_lo", 2).fold(e => fail(e), identity)(0).toLong
      assertEquals((hi << 32) | (lo & 0xffffffffL), twin.digest)
      assertEquals(
        f.record("mass", 2).fold(e => fail(e), identity)(0),
        Shallow.mass.measure(twin.state, twin.time)
      )
      assert(heights.exists(_ != 0.0), "the front has moved the water")
    }
  }

  test("the same run gives the same bytes twice") {
    withTemp { a =>
      withTemp { b =>
        assertEquals(dump(20, NcExport.Options(every = 5), a), Right(5))
        assertEquals(dump(20, NcExport.Options(every = 5), b), Right(5))
        assertEquals(Files.readAllBytes(a).toSeq, Files.readAllBytes(b).toSeq)
      }
    }
  }

  test("single precision halves the fields and rounds them") {
    withTemp { path =>
      assertEquals(dump(10, NcExport.Options(every = 10, single = true), path), Right(2))
      val single = read(path)
      assertEquals(single.schema.variable("height").get.tpe, NcType.Float32)
      assertEquals(single.text("physis_precision"), Some("float32"))
      withTemp { full =>
        assertEquals(dump(10, NcExport.Options(every = 10), full), Right(2))
        val double = read(full)
        val a = single.values("height").fold(e => fail(e), identity)
        val b = double.values("height").fold(e => fail(e), identity)
        assertEquals(a.toSeq, b.map(x => x.toFloat.toDouble).toSeq)
        assert(Files.size(path) < Files.size(full))
      }
    }
  }

  test("only the named variables are written, and an unknown name is refused") {
    withTemp { path =>
      val only = NcExport.Options(every = 10, only = Some(Set("height", "mass")))
      assertEquals(dump(10, only, path), Right(2))
      val f = read(path)
      val data = f.schema.vars.map(_.name).toSet
      assert(data.contains("height") && data.contains("mass"))
      assert(!data.contains("speed") && !data.contains("energy"))
      val bad = dump(10, NcExport.Options(every = 10, only = Some(Set("nope"))), path)
      assert(bad.left.exists(_.contains("no variable 'nope'")), bad.toString)
    }
  }

  test("the size is known before the file is written") {
    withTemp { path =>
      val run = sea()
      val plan = NcExport
        .plan(
          run.state,
          run.stepCount,
          20,
          "shallow",
          run.model.version,
          ShallowState.stateExport,
          spec,
          Shallow.probes,
          NcExport.Options(every = 10)
        )
        .fold(e => fail(e), identity)
      assertEquals(plan.records, 3)
      assertEquals(dump(20, NcExport.Options(every = 10), path), Right(3))
      assertEquals(plan.bytes, Files.size(path))
    }
  }

  test("a cancelled write stops and leaves no file") {
    withTemp { path =>
      var written = 0
      val result = NcExport.write(
        sea(),
        200,
        ShallowState.stateExport,
        spec,
        Shallow.probes,
        path,
        NcExport.Options(every = 10),
        progress = (n, _) => written = n,
        cancelled = () => written >= 2
      )
      assertEquals(result, Left(NcExport.Cancelled))
      assert(!Files.exists(path))
    }
  }

  test("a model without fields writes its probes alone, and extra attributes are kept") {
    withTemp { path =>
      val run = Physis.model(Lorenz.model).stepping(Stepping.Fixed(0.001)).build()
      val options = NcExport.Options(every = 25, attrs = Vector(Attr.text("note", "hello")))
      val result = NcExport.write(
        run,
        100,
        OdeState.stateExport,
        ExportSpec.probesOnly("Lorenz attractor"),
        Lorenz.probes,
        path,
        options
      )
      assertEquals(result, Right(5))
      val f = read(path)
      assert(f.schema.dim("x").isEmpty && f.schema.dim("y").isEmpty)
      assertEquals(f.text("note"), Some("hello"))
      val twin = Physis.model(Lorenz.model).stepping(Stepping.Fixed(0.001)).build()
      twin.runSteps(100)
      assertEquals(
        f.record("z", 4).fold(e => fail(e), identity)(0),
        Lorenz.z.measure(twin.state, twin.time)
      )
      assertEquals(f.values("step").toOption.map(_.toSeq), Some(Seq(0.0, 25.0, 50.0, 75.0, 100.0)))
    }
  }

  test("records are counted from the step the run is at") {
    assertEquals(NcExport.recordCount(0, 20, 10), 3)
    assertEquals(NcExport.recordCount(0, 0, 10), 1)
    assertEquals(NcExport.recordCount(5, 20, 10), 3)
    assertEquals(NcExport.recordCount(0, 25, 10), 3)
    intercept[IllegalArgumentException](NcExport.recordCount(0, 10, 0))
  }
