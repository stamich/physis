package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

class CliSuite extends munit.FunSuite:
  /** Exit code, standard output and standard error of one command line. */
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private def engine(args: String*): List[String] = Cli.parse(args.toList) match
    case Right(Plan.Engine(a)) => a
    case other => fail(s"expected engine options, got $other")

  test("each command turns its main argument into the engine's option") {
    assertEquals(engine("run", "--model", "boids"), List("--model", "boids"))
    assertEquals(
      engine("frames", "out", "--model", "wave", "--scale", "2"),
      List("--frames", "out", "--model", "wave", "--scale", "2")
    )
    assertEquals(
      engine("sweep", "--sweep", "F=0.02:0.06:5", "--threads", "2"),
      List("--progress", "--sweep", "F=0.02:0.06:5", "--threads", "2")
    )
    assertEquals(
      engine("montage", "map.png", "--sweep", "k=0.05,0.06", "--montage-columns", "2"),
      List("--montage", "map.png", "--progress", "--sweep", "k=0.05,0.06", "--montage-columns", "2")
    )
    assertEquals(engine("sweep", "--quiet", "--sweep", "k=1,2"), List("--sweep", "k=1,2"))
    assertEquals(engine("replay", "run.json"), List("--replay", "run.json"))
    assertEquals(engine("list"), List("--list"))
  }

  test("a usage error names the command and the option, and the exit code is 2") {
    def error(args: String*): String = Cli.parse(args.toList).left.getOrElse(fail("no error"))
    assert(error().contains("no command"))
    assert(error("fly").contains("unknown command 'fly'"))
    assert(error("--model", "boids").contains("a command comes first"))
    assert(error("run", "--sweep", "F=0.1,0.2").contains("run does not take --sweep"))
    assert(error("frames", "--model", "wave").contains("frames needs a directory"))
    assert(error("frames", "out", "--montage", "m.png").contains("frames does not take"))
    assert(error("sweep", "--model", "boids").contains("at least one --sweep"))
    assert(error("montage", "--sweep", "k=1,2").contains("montage needs a PNG file"))
    assert(error("replay").contains("replay needs a manifest file"))
    assert(error("run", "--until").contains("--until expects a value"))
    assert(error("run", "stray").contains("unexpected argument 'stray'"))
    assert(error("list", "x").contains("list takes no arguments"))
    for bad <- Seq(Seq("fly"), Seq("run", "--sweep", "x=1"), Seq("run", "--until", "soon")) do
      val (code, _, err) = go(bad*)
      assertEquals(code, 2, err)
      assert(err.startsWith("error: "), err)
  }

  test("help and version are printed with exit code 0") {
    val (code, out, _) = go("--help")
    assertEquals(code, 0)
    assertEquals(out.trim, Cli.Usage)
    for command <- Cli.Commands do
      val (c, text, _) = go(command, "--help")
      assertEquals(c, 0, command)
      assert(text.contains(s"physis-cli $command"), text)
    val (vc, version, _) = go("--version")
    assertEquals(vc, 0)
    assert(version.startsWith("physis-cli "), version)
  }

  test("run prints the probes as CSV and the digest, like the engine") {
    val (code, csv, summary) =
      go("run", "--model", "lorenz", "--until", "1", "--sample-every", "0.5")
    assertEquals(code, 0, summary)
    assertEquals(csv.linesIterator.next(), "t,x,y,z")
    assert(summary.contains("final digest"), summary)
  }

  test("an error from the engine is exit code 1 for a run and 2 for a bad value") {
    assertEquals(go("run", "--model", "nope")._1, 1)
    assertEquals(go("run", "--param", "rho")._1, 2)
  }

  test("list names every model and describe gives one in full") {
    val (code, listing, _) = go("list")
    assertEquals(code, 0)
    val models =
      Vector(
        "lorenz",
        "double-pendulum",
        "gray-scott",
        "boids",
        "wave",
        "physarum",
        "fluid",
        "convection"
      )
    for id <- models do assert(listing.contains(s"$id (version "), id)
    for id <- models do
      val (c, text, err) = go("describe", id)
      assertEquals(c, 0, err)
      val lines = text.linesIterator.toVector
      assert(lines.head.startsWith(s"$id (version "), lines.head)
      assertEquals(lines.count(_ == "parameters:"), 1)
      assert(lines.exists(_.startsWith("presets:")), text)
      assert(lines.exists(_.startsWith("probes: ")), text)
      assert(lines.exists(_.startsWith("image: ")), text)
  }

  test("describe shows presets, the units of probes and the missing image of an ODE model") {
    val (_, physarum, _) = go("describe", "physarum")
    assert(physarum.contains("Network:"), physarum)
    assert(physarum.contains("Mesh:") && physarum.contains("Spots:"), physarum)
    assert(physarum.contains("trail_mass ["), physarum)
    assert(physarum.contains("image: yes"), physarum)
    val (_, lorenz, _) = go("describe", "lorenz")
    assert(lorenz.contains("presets: none"), lorenz)
    assert(lorenz.contains("image: no"), lorenz)
    val (code, _, err) = go("describe", "nope")
    assertEquals(code, 1)
    assert(err.contains("unknown model 'nope' (known: "), err)
    assertEquals(go("describe")._1, 2)
    assertEquals(go("describe", "a", "b")._1, 2)
  }

  test("the models that have presets list them under presets") {
    for id <- Seq("gray-scott", "boids", "physarum") do
      val (_, text, _) = go("describe", id)
      val presetLines = text.linesIterator
        .dropWhile(_ != "presets:")
        .drop(1)
        .takeWhile(_.startsWith("  "))
        .toVector
      assert(presetLines.nonEmpty, id)
  }

  test("a preset sets its values, and --param after it wins") {
    val base = Seq("run", "--model", "gray-scott", "--param", "nx=64", "--param", "ny=64")
    val short = Seq("--until", "50", "--sample-every", "50")
    val (c1, viaPreset, e1) = go((base ++ Seq("--preset", "Coral") ++ short)*)
    assertEquals(c1, 0, e1)
    // the same run with the values spelled out gives the same numbers
    val (c2, spelled, e2) = go(
      (base ++ Seq("--param", "F=0.0545", "--param", "k=0.062") ++ short)*
    )
    assertEquals(c2, 0, e2)
    assertEquals(viaPreset, spelled)
    // the name is not case sensitive, and an explicit --param is applied after the preset
    val (_, lower, _) = go((base ++ Seq("--preset", "coral") ++ short)*)
    assertEquals(lower, viaPreset)
    val (_, mixed, _) = go((base ++ Seq("--preset", "Coral", "--param", "k=0.05") ++ short)*)
    assertNotEquals(mixed, viaPreset)
    assert(e1.contains("final digest"), e1)
  }

  test("a preset gives the digest of a run built with the same values") {
    import physis.core.run.Physis
    import physis.models.physarum.Physarum
    val preset = Physarum.presets.find(_.name == "Mesh").get
    val reference = Physis
      .model(Physarum)
      .stepping(Physarum.stepping)
      .overrides(Map("n" -> 500.0, "nx" -> 64.0, "ny" -> 64.0) ++ preset.values)
      .seed(3L)
      .build()
      .runSteps(30)
    val (code, _, summary) = go(
      "run",
      "--model",
      "physarum",
      "--preset",
      "Mesh",
      "--param",
      "n=500",
      "--param",
      "nx=64",
      "--param",
      "ny=64",
      "--seed",
      "3",
      "--steps",
      "30"
    )
    assertEquals(code, 0, summary)
    assert(summary.contains(f"final digest ${reference.finalDigest}%016x"), summary)
  }

  test("an unknown preset is an error that lists the known ones") {
    val (code, _, err) = go("run", "--model", "gray-scott", "--preset", "Nope", "--until", "1")
    assertEquals(code, 1)
    assert(err.contains("has no preset 'Nope' (known: "), err)
    assert(err.contains("Coral"), err)
    val (c2, _, e2) = go("run", "--model", "lorenz", "--preset", "Any")
    assertEquals(c2, 1)
    assert(e2.contains("(it has none)"), e2)
  }
