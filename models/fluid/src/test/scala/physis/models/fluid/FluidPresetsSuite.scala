package physis.models.fluid

import physis.core.run.{Manifest, ModelRegistry}
import FluidSupport.*

class FluidPresetsSuite extends munit.FunSuite:
  private def paramsOf(preset: FluidPreset): FluidParams =
    Fluid.params.fromValues(preset.params).fold(e => fail(e), identity)

  private def build(preset: FluidPreset): FluidRun =
    val p = paramsOf(preset)
    val r = run(p)
    preset
      .interventions(p.nx, p.ny)
      .foreach(iv => assertEquals(r.intervene(iv), Right(()), iv.toString))
    r

  test("every preset is valid for the schema and the stability guard") {
    assertEquals(FluidPresets.all.map(_.name).distinct.size, 4)
    FluidPresets.all.foreach { preset =>
      val p = Fluid.params.fromValues(preset.params)
      assert(p.isRight, s"${preset.name}: $p")
      assertEquals(Fluid.check(p.toOption.get, Fluid.stepping), Right(()), preset.name)
      assert(preset.doc.nonEmpty)
    }
  }

  test("every preset paints only what the fluid accepts, on small and large grids") {
    for preset <- FluidPresets.all; n <- Seq(32, 256) do
      preset.interventions(n, n).foreach(iv =>
        assertEquals(Fluid.accepts(iv), Right(()), preset.name)
      )
  }

  test("every preset runs, stays finite and free of divergence, and replays from its manifest") {
    val registry = ModelRegistry.of(Fluid)
    FluidPresets.all.foreach { preset =>
      val r = build(preset)
      r.runSteps(100)
      val s = r.state
      assert(Fluid.kineticEnergy.measure(s, 0.0).isFinite, preset.name)
      assert(s.dye.max.isFinite && s.dye.min >= -1e-9, preset.name)
      assert(Fluid.maxDivergence.measure(s, 0.0) < 1e-5, preset.name)
      val manifest = r.manifest(r.time).copy(steps = Some(r.stepCount))
      val replay =
        Manifest.fromJson(Manifest.toJson(manifest)).flatMap(Manifest.replay(_, registry))
      assertEquals(replay.map(_.execute().finalDigest), Right(r.digest), preset.name)
    }
  }

  test("the cavity is driven by its lid and the plume rises") {
    val cavity = build(FluidPresets.cavity)
    cavity.runSteps(200)
    assert(Fluid.kineticEnergy.measure(cavity.state, 0.0) > 1.0)
    val plume = build(FluidPresets.smokePlume)
    def height(r: FluidRun): Double =
      var sy = 0.0
      var sm = 0.0
      for j <- 0 until 128; i <- 0 until 96 do
        sy += r.state.dye(i, j) * (j + 0.5)
        sm += r.state.dye(i, j)
      sy / sm
    plume.step()
    val before = height(plume)
    plume.runSteps(200)
    assert(height(plume) > before + 3.0, s"${height(plume)} vs $before")
  }

  test("the cylinder wake has a solid cylinder, an inflow and dye upstream") {
    val r = build(FluidPresets.cylinderWake)
    r.step()
    val s = r.state
    assert(s.wall.count > 100)
    assert(s.wall(38, 36))
    assertEquals(s.u(0, 10), 1.0)
    assert(s.dye.max > 0.5)
  }

  test("the vortex pair keeps its circulation: its enstrophy stays within a factor of the start") {
    val r = build(FluidPresets.vortexPair)
    r.step()
    val e0 = Fluid.enstrophy.measure(r.state, 0.0)
    assert(e0 > 0.0)
    r.runSteps(200)
    val e1 = Fluid.enstrophy.measure(r.state, 0.0)
    assert(e1 > 0.05 * e0 && e1 < 2.0 * e0, s"$e0 -> $e1")
  }
