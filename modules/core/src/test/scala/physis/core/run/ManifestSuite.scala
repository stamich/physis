package physis.core.run

import scala.collection.immutable.VectorMap

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

import physis.core.{BuildInfo, Stepping}
import physis.core.testing.{CappedDecay, Decay, DecayParams}

class ManifestSuite extends munit.ScalaCheckSuite:

  private val tricky: Gen[Char] = Gen.oneOf('"', '\\', '/', '\n', '\t', '\r', '\u0001', 'ł', '€', ' ')
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
    yield Manifest(physis, model, version, VectorMap.from(names.zip(values)), s, seed, until, every, git)

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
    assert(Manifest.replay(m.copy(model = "nope"), registry).left.exists(_.contains("unknown model")))
    assert(Manifest.replay(m.copy(modelVersion = "2"), registry).left.exists(_.contains("version")))
  }

  test("replay refuses invalid parameters") {
    val m = Physis.model(Decay).build().manifest(until = 1.0)
    assert(Manifest.replay(m.copy(params = VectorMap("k" -> -5.0)), registry).isLeft)
  }

  test("model ids in a registry are unique") {
    intercept[IllegalArgumentException](ModelRegistry.of(Decay, Decay))
  }
