package physis.lab

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.run.{Intervention, Manifest, Physis}
import physis.models.shallow.Shallow
import physis.netcdf.{NcCompare, NcFile}

/** File → Export data without a window: the Lab writes the file the command line writes. */
class LabDatasetsSuite extends munit.FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("physis-lab-dataset"),
    teardown = dir =>
      val stream = Files.walk(dir)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
      finally stream.close()
  )

  /** The manifest of a shallow sea of 24 x 16 cells after 20 steps and a painted bump. */
  private def session(): Manifest =
    val run = Physis
      .model(Shallow)
      .overrides(Map("nx" -> 24.0, "ny" -> 16.0))
      .stepping(Shallow.stepping)
      .seed(1L)
      .build()
    val bump = Intervention.Pulse("height", 12.0, 8.0, 3.0, 1.0)
    val _ = run.intervene(bump)
    run.runSteps(20)
    run.manifest(run.time).copy(steps = Some(20L), digest = Some(run.digest))

  tmp.test("the dataset is the one the command line writes for the same manifest") { dir =>
    val manifest = session()
    val file = dir.resolve("lab.nc")
    val report = LabDatasets
      .save(Catalog.shallow, manifest, Catalog.registry, file, DatasetOptions(every = 5))
      .fold(e => fail(e), identity)
    assertEquals(report.records, 5)
    assertEquals(report.steps, 20L)
    assertEquals(report.bytes, Files.size(file))
    assertEquals(report.digest, manifest.digest.get)

    val json = report.manifest.getOrElse(fail("no manifest written"))
    val cli = dir.resolve("cli.nc")
    val err = new ByteArrayOutputStream()
    val code = physis.cli.Cli.execute(
      List("replay", json.toString, "--export", cli.toString, "--every", "5"),
      new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    assertEquals(code, 0, err.toString(StandardCharsets.UTF_8))
    val a = NcFile.read(file).fold(e => fail(e), identity)
    val b = NcFile.read(cli).fold(e => fail(e), identity)
    assertEquals(NcCompare.diff(a, b), None)
    assert(a.schema.variable("height").isDefined)
  }

  /** A beach run-up on a small grid, with a layer of water added by the Water level tool. */
  private def flood(): Manifest =
    val preset = Catalog.shallow.presets.find(_.name == "Beach run-up").get
    val run = Physis
      .model(Shallow)
      .overrides(preset.values + ("nx" -> 60.0) + ("ny" -> 16.0))
      .stepping(Shallow.stepping)
      .seed(1L)
      .build()
    val level = Catalog.shallow.tools.find(_.spec.name == "Water level").get
    val _ = run.intervene(level.make(physis.view.Dab(8.0, 6.0), 4.0, 0.5, false))
    run.runSteps(150)
    run.manifest(run.time).copy(steps = Some(150L), digest = Some(run.digest))

  tmp.test("a flooding run exports what the command line replays, and verifies") { dir =>
    val manifest = flood()
    val file = dir.resolve("flood.nc")
    val report = LabDatasets
      .save(Catalog.shallow, manifest, Catalog.registry, file, DatasetOptions(every = 50))
      .fold(e => fail(e), identity)
    assertEquals(report.digest, manifest.digest.get)
    val json = report.manifest.getOrElse(fail("no manifest written"))
    val cli = dir.resolve("cli.nc")
    val err = new ByteArrayOutputStream()
    val quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
    val code = physis.cli.Cli.execute(
      List("replay", json.toString, "--export", cli.toString, "--every", "50"),
      quiet,
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    assertEquals(code, 0, err.toString(StandardCharsets.UTF_8))
    val a = NcFile.read(file).fold(e => fail(e), identity)
    val b = NcFile.read(cli).fold(e => fail(e), identity)
    assertEquals(NcCompare.diff(a, b), None)
    assert(a.schema.variable("water").isDefined && a.schema.variable("dry").isDefined)
    val verify = physis.cli.Cli.execute(
      List("verify", file.toString),
      quiet,
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    assertEquals(verify, 0, err.toString(StandardCharsets.UTF_8))
  }

  tmp.test("the file verifies against the manifest inside it") { dir =>
    val file = dir.resolve("lab.nc")
    LabDatasets
      .save(Catalog.shallow, session(), Catalog.registry, file, DatasetOptions(5, single = true))
      .fold(e => fail(e), identity)
    val err = new ByteArrayOutputStream()
    val code = physis.cli.Cli.execute(
      List("verify", file.toString),
      new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    assertEquals(code, 0, err.toString(StandardCharsets.UTF_8))
  }

  tmp.test("the estimate is the size of the file") { dir =>
    val manifest = session()
    val options = DatasetOptions(every = 4, writeManifest = false)
    val estimate = LabDatasets
      .estimate(Catalog.shallow, manifest, Catalog.registry, 100.0, options)
      .fold(e => fail(e), identity)
    val file = dir.resolve("e.nc")
    LabDatasets
      .save(Catalog.shallow, manifest, Catalog.registry, file, options)
      .fold(e => fail(e), identity)
    assertEquals(estimate.bytes, Files.size(file))
    assertEquals(estimate.records, 6)
    assertEquals(estimate.seconds, Some(0.2))
    assert(estimate.text.startsWith("6 records, "), estimate.text)
  }

  test("the models with fields export them, the others their probes alone") {
    val withFields = Set("gray-scott", "wave", "physarum", "fluid", "convection", "shallow")
    for model <- Catalog.models do
      assertEquals(model.dataset.hasFields, withFields.contains(model.id), model.id)
  }

  tmp.test("a cancelled export leaves no file, and a bad option is refused") { dir =>
    val file = dir.resolve("c.nc")
    val result = LabDatasets.save(
      Catalog.shallow,
      session(),
      Catalog.registry,
      file,
      DatasetOptions(every = 1),
      cancelled = () => true
    )
    assertEquals(result.left.toOption, Some("cancelled"))
    assert(!Files.exists(file))
    assert(
      LabDatasets
        .save(Catalog.shallow, session(), Catalog.registry, file, DatasetOptions(every = 0))
        .isLeft
    )
  }
