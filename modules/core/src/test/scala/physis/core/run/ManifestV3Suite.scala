package physis.core.run

import physis.core.Stepping
import physis.core.run.Intervention.Brush
import physis.core.testing.{Decay, DecayParams, PokableDecay}

class ManifestV3Suite extends munit.FunSuite:
  private val registry = ModelRegistry.of(Decay, PokableDecay)
  private val poke = Brush("y", Disc(1.5, 2.5, 3.0), Op.Assign, 10.0)
  private val bump = Brush("y", Disc(0, 0, 1), Op.Add, 0.25)

  private def session() =
    val run = Physis
      .model(PokableDecay)
      .withParams(DecayParams(k = 0.5, y0 = 2.0))
      .stepping(Stepping.Fixed(0.1))
      .build()
    run.runSteps(4)
    run.intervene(poke)
    run.runSteps(3)
    run.setParams(Map("k" -> 1.5))
    run.intervene(bump)
    run.runSteps(5)
    run

  test("a manifest with interventions is written as format 3 and round-trips") {
    val run = session()
    val manifest = run.manifest(until = run.time).copy(steps = Some(run.stepCount))
    val text = Manifest.toJson(manifest)
    assert(text.contains("\"format\": 3"), text)
    assertEquals(Manifest.fromJson(text), Right(manifest))
  }

  test("replaying the manifest reproduces the digest") {
    val run = session()
    val manifest = run.manifest(until = run.time).copy(steps = Some(run.stepCount))
    val replay = Manifest.fromJson(Manifest.toJson(manifest)).flatMap(Manifest.replay(_, registry))
    val result = replay.map(_.execute())
    assertEquals(result.map(_.finalDigest), Right(run.digest))
  }

  test("without interventions the manifest is still written as format 2") {
    val run = Physis.model(Decay).stepping(Stepping.Fixed(0.1)).build()
    run.runSteps(2)
    val text = Manifest.toJson(run.manifest(until = run.time))
    assert(text.contains("\"format\": 2"), text)
    assert(!text.contains("interventions"), text)
  }

  test("format 1 and format 2 files still read, with no interventions") {
    val v2 =
      """{"format": 2, "physis": "0.3.0", "model": "decay", "modelVersion": "1",
        | "params": {"k": 1.0, "y0": 1.0}, "stepping": {"kind": "fixed", "dt": 0.01},
        | "seed": 0, "until": 1.0, "sampleEvery": 0.1, "git": null,
        | "events": [], "steps": null, "digest": null}""".stripMargin
    val v1 = v2.replace("\"format\": 2", "\"format\": 1")
    List(v1, v2).foreach { text =>
      val parsed = Manifest.fromJson(text)
      assertEquals(parsed.map(_.interventions), Right(Vector.empty))
      assert(parsed.flatMap(Manifest.replay(_, registry)).isRight, text)
    }
  }

  test("a newer format is refused") {
    val text = Manifest.toJson(session().manifest(1.0)).replace("\"format\": 3", "\"format\": 4")
    assert(Manifest.fromJson(text).left.exists(_.contains("unsupported manifest format 4")))
  }

  test("replay of a model that does not support interventions is refused, not skipped") {
    val manifest = session().manifest(1.0).copy(model = "decay", modelVersion = "1")
    val answer = Manifest.replay(manifest, registry)
    assert(
      answer.left.exists(_.contains("does not support interventions")),
      answer.left.toOption.toString
    )
  }
