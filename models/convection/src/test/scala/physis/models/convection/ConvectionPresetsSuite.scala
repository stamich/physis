package physis.models.convection

import physis.core.run.{Manifest, ModelRegistry}
import ConvectionSupport.*

class ConvectionPresetsSuite extends munit.FunSuite:
  private def paramsOf(preset: ConvectionPreset): ConvectionParams =
    Convection.params.fromValues(preset.params).fold(e => fail(e), identity)

  private def build(preset: ConvectionPreset): ConvectionRun =
    val p = paramsOf(preset)
    val r = run(p)
    preset
      .interventions(p.nx, p.ny)
      .foreach(iv => assertEquals(r.intervene(iv), Right(()), iv.toString))
    r

  test("every preset is valid for the schema and the stability guard") {
    assertEquals(ConvectionPresets.all.map(_.name).distinct.size, 5)
    ConvectionPresets.all.foreach { preset =>
      val p = Convection.params.fromValues(preset.params)
      assert(p.isRight, s"${preset.name}: $p")
      assertEquals(Convection.check(p.toOption.get, Convection.stepping), Right(()), preset.name)
      assert(preset.doc.nonEmpty)
    }
  }

  test("every preset paints only what the model accepts, on small and large grids") {
    for preset <- ConvectionPresets.all; n <- Seq(32, 256) do
      preset.interventions(n, n).foreach(iv =>
        assertEquals(Convection.accepts(iv), Right(()), preset.name)
      )
  }

  test("every preset runs, stays finite and free of divergence, and replays from its manifest") {
    val registry = ModelRegistry.of(Convection)
    ConvectionPresets.all.foreach { preset =>
      val r = build(preset)
      r.runSteps(100)
      val s = r.state
      assert(Convection.kineticEnergy.measure(s, 0.0).isFinite, preset.name)
      assert(s.deviation.max.isFinite && s.deviation.min.isFinite, preset.name)
      assert(Convection.maxDivergence.measure(s, 0.0) < 1e-5, preset.name)
      val manifest = r.manifest(r.time).copy(steps = Some(r.stepCount))
      val replay =
        Manifest.fromJson(Manifest.toJson(manifest)).flatMap(Manifest.replay(_, registry))
      assertEquals(replay.map(_.execute().finalDigest), Right(r.digest), preset.name)
    }
  }

  test(
    "the painted spots are in the layer at the first step: hot near the floor, cold near the lid"
  ) {
    val r = build(ConvectionPresets.paintedHeat)
    r.step() // the interventions are applied at the start of the step
    val s = r.state
    assert(s.deviation(33, 4) > 0.3, s"hot ${s.deviation(33, 4)}")
    assert(s.deviation(62, 43) < -0.3, s"cold ${s.deviation(62, 43)}")
  }

  test("the periodic presets wrap and the closed ones do not") {
    assert(build(ConvectionPresets.rolls).state.periodic)
    assert(!build(ConvectionPresets.plumes).state.periodic)
  }
