package physis.core.run

import scala.collection.immutable.VectorMap

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

import physis.core.{BuildInfo, Stepping}
import physis.core.testing.{CappedDecay, Decay, DecayParams}

class ManifestSuite extends munit.ScalaCheckSuite:

  private val tricky: Gen[Char] =
    Gen.oneOf('"', '\\', '/', '\n', '\t', '\r', '\u0001', 'ł', '€', ' ')
  private val text: Gen[String] =
    Gen.listOf(Gen.frequency(5 -> Gen.alphaNumChar, 1 -> tricky)).map(_.mkString)
  private val finite: Gen[Double] = Gen.oneOf(
    Gen.choose(-1e6, 1e6),
    Arbitrary.arbitrary[Double].suchThat(d => !d.isNaN && !d.isInfinite)
  )
  private val positive: Gen[Double] = Gen.choose(1e-12, 1e3)
  private val stepping: Gen[Stepping] = Gen.oneOf(
    positive.map(Stepping.Fixed(_)),
    for
      rtol <- positive
      atol <- positive
      dt0 <- positive
      dtMax <- positive
    yield Stepping.Adaptive(rtol, atol, dt0, dtMax)
  )
  private val manifests: Gen[Manifest] =
    for
      physis <- text
      model <- text
      version <- text
      names <- Gen.listOf(text).map(_.distinct)
      values <- Gen.listOfN(names.size, finite)
      s <- stepping
      seed <- Arbitrary.arbitrary[Long]
      until <- finite
      every <- positive
      git <- Gen.option(text)
      events <- Gen.listOf(
        for
          step <- Gen.choose(0L, 1_000_000L)
          name <- text
          value <- finite
        yield ParamEvent(step, VectorMap(name -> value))
      )
      steps <- Gen.option(Gen.choose(0L, Long.MaxValue))
      digest <- Gen.option(Arbitrary.arbitrary[Long])
    yield Manifest(
      physis,
      model,
      version,
      VectorMap.from(names.zip(values)),
      s,
      seed,
      until,
      every,
      git,
      events.toVector,
      steps,
      digest
    )

  property("JSON round-trips exactly") {
    forAll(manifests) { m =>
      Manifest.fromJson(Manifest.toJson(m)) == Right(m)
    }
  }

  test("malformed JSON is rejected with a position") {
    val result = Manifest.fromJson("""{"physis": "0.1.0", "model": }""")
    assert(result.left.exists(_.contains("offset")), result.toString)
  }

  test("missing fields are reported by name") {
    val result = Manifest.fromJson("""{"physis": "0.1.0"}""")
    assertEquals(result, Left("missing field 'model'"))
  }

  private val registry = ModelRegistry.of(Decay, CappedDecay(0.05))

  test("a run describes itself") {
    val run = Physis.model(Decay).withParams(DecayParams(0.7, 2.0)).seed(11L).build()
    val m = run.manifest(until = 3.0)
    assertEquals(m.model, "decay")
    assertEquals(m.modelVersion, "1")
    assertEquals(m.params, VectorMap("k" -> 0.7, "y0" -> 2.0))
    assertEquals(m.seed, 11L)
    assertEquals(m.until, 3.0)
    assertEquals(m.physis, BuildInfo.version)
  }

  test("replay reproduces the final state bit for bit") {
    val run = Physis
      .model(CappedDecay(0.05))
      .withParams(DecayParams(0.3, 5.0))
      .stepping(Stepping.Adaptive(1e-6, 1e-9, 0.2, 1.0))
      .seed(3L)
      .build()
    val manifest = run.manifest(until = 2.0)
    val original = run.runUntil(2.0)
    val replayed = Manifest.fromJson(Manifest.toJson(manifest))
      .flatMap(Manifest.replay(_, registry))
      .map(_.execute())
    assertEquals(replayed.map(_.finalDigest), Right(original.finalDigest))
  }

  test("replay refuses unknown models and other versions") {
    val m = Physis.model(Decay).build().manifest(until = 1.0)
    assert(Manifest.replay(
      m.copy(model = "nope"),
      registry
    ).left.exists(_.contains("unknown model")))
    assert(Manifest.replay(m.copy(modelVersion = "2"), registry).left.exists(_.contains("version")))
  }

  test("replay refuses invalid parameters") {
    val m = Physis.model(Decay).build().manifest(until = 1.0)
    assert(Manifest.replay(m.copy(params = VectorMap("k" -> -5.0)), registry).isLeft)
  }

  test("model ids in a registry are unique") {
    intercept[IllegalArgumentException](ModelRegistry.of(Decay, Decay))
  }

  test("a format-1 manifest from Physis 0.1 still reads and replays") {
    val text = scala.io.Source.fromResource("manifest-format1.json").mkString
    val manifest = Manifest.fromJson(text)
    assertEquals(manifest.map(_.events), Right(Vector.empty))
    assertEquals(manifest.map(_.steps), Right(None))
    val replayed = manifest.flatMap(Manifest.replay(_, registry)).map(_.execute().finalTime)
    assertEquals(replayed, Right(3.0))
  }

  test("an unknown future format is refused") {
    val text = Manifest.toJson(Physis.model(Decay).build().manifest(1.0))
      .replace("\"format\": 2", "\"format\": 99")
    assert(Manifest.fromJson(text).left.exists(_.contains("unsupported manifest format 99")))
  }

  test("parameter changes are written to the manifest and replayed") {
    val run =
      Physis.model(Decay).withParams(DecayParams(0.7, 2.0)).stepping(Stepping.Fixed(0.01)).build()
    run.runSteps(30)
    run.setParams(Map("k" -> 3.0))
    run.runSteps(30)
    run.setParams(Map("y0" -> 7.0, "k" -> 0.5))
    run.runSteps(30)
    run.setParams(Map("k" -> 1.0))
    run.runSteps(30)
    val manifest = run.manifest(until = run.time).copy(steps = Some(run.stepCount))
    assertEquals(manifest.events.map(_.step), Vector(30L, 60L, 90L))
    val replayed = Manifest.fromJson(Manifest.toJson(manifest))
      .flatMap(Manifest.replay(_, registry))
      .map(_.execute().finalDigest)
    assertEquals(replayed, Right(run.digest))
  }

  test("a manifest with a step count replays exactly that many whole steps") {
    val run =
      Physis.model(CappedDecay(0.05)).stepping(Stepping.Adaptive(1e-6, 1e-9, 0.2, 1.0)).build()
    var i = 0
    while i < 37 do
      run.step()
      i += 1
    val manifest =
      run.manifest(until = run.time).copy(steps = Some(run.stepCount), digest = Some(run.digest))
    val replayed = Manifest.replay(manifest, registry).map(_.execute())
    assertEquals(replayed.map(_.acceptedSteps), Right(37L))
    assertEquals(replayed.map(r => Option(r.finalDigest)), Right(manifest.digest))
  }
