package physis.models.shallow

import physis.core.run.{Manifest, ModelRegistry}
import ShallowSupport.*

class ShallowPresetsSuite extends munit.FunSuite:
  private def paramsOf(preset: ShallowPreset): ShallowParams =
    Shallow.params.fromValues(preset.params).fold(e => fail(e), identity)

  private def build(preset: ShallowPreset): ShallowRun =
    val p = paramsOf(preset)
    val r = run(p)
    preset
      .interventions(p.nx, p.ny)
      .foreach(iv => assertEquals(r.intervene(iv), Right(()), iv.toString))
    r

  test("every preset is valid for the schema and the stability guard") {
    assertEquals(ShallowPresets.all.map(_.name).distinct.size, 5)
    ShallowPresets.all.foreach { preset =>
      val p = Shallow.params.fromValues(preset.params)
      assert(p.isRight, s"${preset.name}: $p")
      assertEquals(Shallow.check(p.toOption.get, Shallow.stepping), Right(()), preset.name)
      assert(preset.doc.nonEmpty)
    }
  }

  test("every preset paints only what the model accepts, on small and large grids") {
    for preset <- ShallowPresets.all; n <- Seq(32, 256) do
      preset.interventions(n, n).foreach(iv =>
        assertEquals(Shallow.accepts(iv), Right(()), preset.name)
      )
  }

  test("every preset runs, stays finite, and replays from its manifest") {
    val registry = ModelRegistry.of(Shallow)
    ShallowPresets.all.foreach { preset =>
      val r = build(preset)
      r.runSteps(100)
      val s = r.state
      assert(Shallow.energy.measure(s, 0.0).isFinite, preset.name)
      assert(s.eta.max.isFinite && s.eta.min.isFinite, preset.name)
      val manifest = r.manifest(r.time).copy(steps = Some(r.stepCount))
      val replay =
        Manifest.fromJson(Manifest.toJson(manifest)).flatMap(Manifest.replay(_, registry))
      assertEquals(replay.map(_.execute().finalDigest), Right(r.digest), preset.name)
    }
  }

  test("the closed and the wrapping presets keep their water") {
    for preset <- Seq(ShallowPresets.adjustment, ShallowPresets.rossby, ShallowPresets.kelvin) do
      val r = build(preset)
      r.step()
      val before = volume(r.state)
      r.runSteps(300)
      assertEqualsDouble(volume(r.state), before, 1e-9 * (math.abs(before) + 1e8), preset.name)
  }

  test("the tsunami starts as a ridge of 2 m and sends a crest east at about 713 km/h") {
    val r = build(ShallowPresets.tsunami)
    r.step()
    val s = r.state
    assertEqualsDouble(s.eta(40, 48), 1.84, 0.1)
    assert(s.eta.max < 2.0)
    r.runSteps(59)
    // numpy reference: 60 steps move the crest 28.5 cells, to column 68 or 69, at 0.92 m
    assert(s.eta(69, 48) > 0.7, s"crest ${s.eta(69, 48)}")
    assert(s.eta(100, 48) < 0.01, "nothing has reached the far side yet")
    assert(
      s.shape(140, 48) < 0.2 && s.shape(10, 48) == 1.0,
      "a shelf on the east, deep to the west"
    )
  }

  test("the adjustment preset is a front: a current builds along it") {
    val r = build(ShallowPresets.adjustment)
    r.runSteps(1500)
    assert(Shallow.maxSpeed.measure(r.state, 0.0) > 0.005)
    // the step lies across the middle: the northern half, at the top, is the raised one
    assert(r.state.eta(10, 0) > r.state.eta(10, 127))
  }

  test("the Rossby preset drifts west as the dispersion relation says") {
    val p = paramsOf(ShallowPresets.rossby)
    val r = run(p)
    val s = r.state
    val k = 2.0 * math.Pi / (p.nx * p.dx)
    val l = math.Pi / (p.ny * p.dx)
    val omega = -p.beta * k / (k * k + l * l + p.f0 * p.f0 / (p.gravity * p.depth))
    def phase(): Double =
      var a = 0.0
      var b = 0.0
      for j <- 0 until p.ny; i <- 0 until p.nx do
        val x = (i + 0.5) * p.dx
        val y = (j + 0.5) * p.dx
        a += s.eta(i, j) * math.sin(k * x) * math.sin(l * y)
        b += s.eta(i, j) * math.cos(k * x) * math.sin(l * y)
      math.atan2(b, a)
    val p0 = phase()
    for steps <- Seq(2000, 4000) do
      r.runSteps(steps - r.stepCount)
      val expected = -omega * steps * Shallow.Dt
      // numpy reference: 0.946 against 0.958, 1.967 against 1.916
      assertEqualsDouble(phase() - p0, expected, 0.1 * expected, s"after $steps steps")
  }

  test("the Kelvin preset sends its wave south along the west coast, not north") {
    val r = build(ShallowPresets.kelvin)
    var south = 0.0
    var north = 0.0
    for _ <- 1 to 400 do
      r.step()
      south = math.max(south, r.state.eta(0, 160))
      north = math.max(north, r.state.eta(0, 40))
    // numpy reference: 0.045 southwards, 0.021 northwards
    assert(south > 1.5 * north, s"south $south north $north")
    assert(south > 0.03)
  }

  test("the harbour has a breakwater with a gap and an island, and a wave front to the west") {
    val r = build(ShallowPresets.harbour)
    r.step()
    val s = r.state
    assert(s.land(66, 10), "breakwater at 55% of the width")
    assert(s.land(66, 70))
    assert(!s.land(66, 40), "the gap")
    assert(!s.land(66, 35) && !s.land(66, 45), "the gap is wider than the middle cell")
    assert(s.land(94, 40), "island at 78% of the width")
    assert(s.eta(14, 30) > 0.9, s"front ${s.eta(14, 30)}")
    assertEquals(s.eta(66, 10), 0.0)
  }
