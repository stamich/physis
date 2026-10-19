package physis.models.lorenz

import scala.io.Source

import physis.core.Stepping
import physis.core.run.{Manifest, ModelRegistry, Physis}
import physis.verify.Determinism

class LorenzSuite extends munit.FunSuite:

  /** State at t = 1 from (1, 1, 1), from tools/lorenz_reference.py (mpmath, 40 digits). */
  private val golden: Vector[Double] =
    val source = Source.fromInputStream(getClass.getResourceAsStream("/lorenz-t1.csv"), "UTF-8")
    try
      val values = source.getLines().drop(1).next().split(',').map(_.trim.toDouble)
      values.toVector.drop(1)
    finally source.close()

  private def assertMatchesGolden(state: Seq[Double], tolerance: Double): Unit =
    state.zip(golden).foreach { (v, g) =>
      assert(math.abs(v - g) <= tolerance * math.abs(g), s"$v vs golden $g")
    }

  test("adaptive Dormand-Prince matches the high-precision reference at t = 1") {
    val run = Physis
      .model(Lorenz.model)
      .stepping(Stepping.Adaptive(rtol = 1e-12, atol = 1e-12, initialDt = 1e-4, maxDt = 0.1))
      .sampleEvery(1.0)
      .build()
    run.advanceTo(1.0)
    assertMatchesGolden(run.state.y.toVector, 1e-8)
  }

  test("fixed-step RK4 matches the reference at t = 1") {
    val run = Physis.model(Lorenz.model).stepping(Stepping.Fixed(1e-4)).sampleEvery(1.0).build()
    run.advanceTo(1.0)
    assertMatchesGolden(run.state.y.toVector, 1e-9)
  }

  test("defaults are the classic parameters") {
    assertEquals(Lorenz.model.params.defaults, LorenzParams(10.0, 28.0, 8.0 / 3.0, 0.0))
  }

  test("a seeded perturbation is reproducible and depends on the seed") {
    def start(seed: Long) =
      Physis
        .model(Lorenz.model)
        .overrides(Map("epsilon" -> 1e-6))
        .seed(seed)
        .build()
        .state
        .y
        .toVector
    assertEquals(start(1L), start(1L))
    assertNotEquals(start(1L), start(2L))
    assert(start(1L).forall(c => math.abs(c - 1.0) < 1e-4))
  }

  test("runs replay from their manifest") {
    val manifest = Physis
      .model(Lorenz.model)
      .overrides(Map("epsilon" -> 1e-3))
      .stepping(Stepping.Adaptive(1e-9, 1e-9, 1e-3, 0.05))
      .seed(5L)
      .build()
      .manifest(until = 5.0)
    val registry = ModelRegistry.of(Lorenz.model)
    assertEquals(Determinism.replayMatches(manifest, registry), Right(true))
    assert(Manifest.fromJson(Manifest.toJson(manifest)).contains(manifest))
  }

  test("probes read the coordinates") {
    val result = Physis.model(Lorenz.model).probe(Lorenz.probes*).build().runUntil(0.0)
    assertEquals(result.times, Vector(0.0))
    assertEquals(result.series.keys.toList, List("x", "y", "z"))
    assertEquals(result.last("z"), 1.0)
  }
