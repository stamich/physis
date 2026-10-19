package physis.models.grayscott

import physis.core.Stepping
import physis.core.live.Slice
import physis.core.run.{ModelRegistry, Physis}
import physis.fields.Stencils
import physis.verify.{Allocation, Determinism}

class GrayScottSuite extends munit.FunSuite:

  private val small = Map("nx" -> 32.0, "ny" -> 32.0)

  private def builder(overrides: Map[String, Double] = Map.empty) =
    Physis.model(GrayScott).stepping(GrayScott.stepping).overrides(small ++ overrides)

  test("defaults are the Mitosis regime on a 256 x 256 grid") {
    val p = GrayScott.params.defaults
    assertEquals(p, GrayScottParams(1.0, 0.5, 0.0367, 0.0649, 256, 256, 0.01))
    assertEquals(GrayScott.presets.head.values, Map("F" -> 0.0367, "k" -> 0.0649))
  }

  test("every preset is valid for the schema") {
    for preset <- GrayScott.presets do
      val result = GrayScott.params.fromValues(preset.values)
      assert(result.isRight, s"${preset.name}: $result")
    assertEquals(GrayScott.presets.map(_.name).distinct.size, GrayScott.presets.size)
  }

  test("grid size and noise need a restart, the rates do not") {
    val restart = GrayScott.params.specs.filter(_.restart).map(_.name)
    assertEquals(restart, Vector("nx", "ny", "noise"))
    val run = builder().build()
    assertEquals(run.setParams(Map("nx" -> 64.0)), Left("parameter 'nx' requires a restart"))
    assertEquals(run.setParams(Map("F" -> 0.03, "k" -> 0.06)), Right(()))
  }

  test("the initial state is a seeded square in a uniform field") {
    val state = builder(Map("noise" -> 0.0)).build().state
    // 32 / 10 = 3 cells wide, centred: columns and rows 14 to 16.
    for j <- 0 until 32; i <- 0 until 32 do
      val inside = i >= 14 && i <= 16 && j >= 14 && j <= 16
      assertEquals(state.u(i, j), if inside then 0.5 else 1.0, s"u($i, $j)")
      assertEquals(state.v(i, j), if inside then 0.25 else 0.0, s"v($i, $j)")
  }

  test("noise is reproducible, depends on the seed, and stays small") {
    def start(seed: Long) = builder(Map("noise" -> 0.1)).seed(seed).build().state
    assertEquals(GrayScott.digest(start(1L)), GrayScott.digest(start(1L)))
    assertNotEquals(GrayScott.digest(start(1L)), GrayScott.digest(start(2L)))
    val s = start(1L)
    assert(math.abs(s.u(15, 15) - 0.5) <= 0.05 && math.abs(s.v(15, 15) - 0.25) <= 0.05)
    assertEquals(s.u(0, 0), 1.0)
  }

  test("u = 1, v = 0 is a fixed point to the last bit") {
    val run = builder(Map("noise" -> 0.0)).build()
    run.state.u.fill(1.0)
    run.state.v.fill(0.0)
    val before = run.digest
    run.runSteps(200)
    assertEquals(run.digest, before)
  }

  /** Mode sin(kx i + 0.3) cos(ky j + 0.7) with kx = 2 pi mx / n on the 32 x 32 grid. */
  private def modeLambda(mx: Int, my: Int): (Double, (Int, Int) => Double) =
    val kx = 2.0 * math.Pi * mx / 32
    val ky = 2.0 * math.Pi * my / 32
    (Stencils.eigenvalue9(kx, ky), (i, j) => math.sin(kx * i + 0.3) * math.cos(ky * j + 0.7))

  test("pure diffusion multiplies a Fourier mode by (1 + dt D lambda) every step") {
    val (lambda, mode) = modeLambda(1, 1)
    val state = builder().build().state
    for j <- 0 until 32; i <- 0 until 32 do state.u(i, j) = mode(i, j)
    val steps = 40
    for _ <- 1 to steps do state.diffuseOnly(1.0, 0.5, 1.0)
    val factor = math.pow(1.0 + 1.0 * lambda, steps)
    var worst = 0.0
    for j <- 0 until 32; i <- 0 until 32 do
      worst = math.max(worst, math.abs(state.u(i, j) - factor * mode(i, j)))
    assertEqualsDouble(worst, 0.0, 1e-12)
  }

  test("pure diffusion conserves the total of u and v on the periodic grid") {
    val run = builder().seed(3L).build()
    val state = run.state
    val rng = new java.util.Random(4L)
    for j <- 0 until 32; i <- 0 until 32 do
      state.u(i, j) = rng.nextDouble()
      state.v(i, j) = rng.nextDouble()
    val (u0, v0) = (state.u.sum, state.v.sum)
    for _ <- 1 to 1000 do state.diffuseOnly(1.0, 0.5, 1.0)
    assertEqualsDouble(state.u.sum / u0, 1.0, 1e-12)
    assertEqualsDouble(state.v.sum / v0, 1.0, 1e-12)
  }

  test("the stability guard accepts D dt up to 1.25 and refuses more, at build and live") {
    assertEquals(builder().build().stepCount, 0L)
    val tooLong = intercept[IllegalArgumentException] {
      Physis.model(GrayScott).stepping(Stepping.Fixed(1.5)).overrides(small).build()
    }
    assert(tooLong.getMessage.contains("1.25"), tooLong.getMessage)
    // Du = 1.0 with dt = 1.2 is fine; Du = 1.1 would give 1.32.
    val run = Physis.model(GrayScott).stepping(Stepping.Fixed(1.2)).overrides(small).build()
    val result = run.setParams(Map("Du" -> 1.1))
    assert(result.left.exists(_.contains("stability limit")), result.toString)
    run.runSteps(5)
    assertEquals(run.params.Du, 1.0)
    assertEquals(run.events, Vector.empty)
    assertEquals(run.setParams(Map("Du" -> 1.0, "Dv" -> 1.0)), Right(()))
  }

  test("probes measure the seed at the start") {
    val result = builder(Map("noise" -> 0.0)).probe(GrayScott.probes*).build().runSteps(0)
    assertEquals(result.series.keys.toList, List("u_mean", "v_mean", "v_max", "coverage"))
    assertEquals(result.column("v_max").head, 0.25)
    assertEqualsDouble(result.column("v_mean").head, 9 * 0.25 / 1024, 1e-15)
    assertEqualsDouble(result.column("coverage").head, 9.0 / 1024, 1e-15)
    assertEqualsDouble(result.column("u_mean").head, (1015 + 9 * 0.5) / 1024, 1e-15)
  }

  test("the snapshot export copies u then v, with matching slices") {
    val run = builder().seed(2L).build()
    run.runSteps(10)
    val exporter = GrayScottState.stateExport
    val out = new Array[Double](exporter.size(run.state))
    exporter.copyInto(run.state, out)
    assertEquals(
      exporter.layout(run.state),
      Vector(Slice("u", 0, 32, 32), Slice("v", 1024, 32, 32))
    )
    for j <- 0 until 32; i <- 0 until 32 do
      assertEquals(out(j * 32 + i), run.state.u(i, j))
      assertEquals(out(1024 + j * 32 + i), run.state.v(i, j))
  }

  test("a run replays from its manifest") {
    val run = builder(Map("noise" -> 0.05)).seed(9L).build()
    run.runSteps(50)
    run.setParams(Map("F" -> 0.03, "k" -> 0.057))
    run.runSteps(50)
    val manifest = run.manifest(until = run.time).copy(steps = Some(run.stepCount))
    val replayed = Determinism.replayDigest(manifest, ModelRegistry.of(GrayScott))
    assertEquals(replayed, Right(run.digest))
  }

  test("a step does not allocate") {
    assume(Allocation.supported)
    val run = builder().seed(1L).build()
    val bytes = Allocation.perStep(2000)(run.step())
    assert(bytes < 2000L, s"allocated $bytes bytes in 2000 steps")
  }
