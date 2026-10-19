package physis.models.wave

import physis.core.run.{Manifest, ModelRegistry}
import WaveSupport.*

class WavePresetsSuite extends munit.FunSuite:
  private def build(preset: WavePreset, n: Int = 128): WaveRun =
    val values = preset.params ++ Map("nx" -> n.toDouble, "ny" -> n.toDouble)
    val p = Wave.params.fromValues(values).fold(e => fail(e), identity)
    val r = run(p)
    preset.interventions(n, n).foreach(iv => assertEquals(r.intervene(iv), Right(()), iv.toString))
    r

  test("every preset is valid for the schema and the stability guard") {
    assertEquals(WavePresets.all.map(_.name).distinct.size, 5)
    WavePresets.all.foreach { preset =>
      val values = preset.params ++ Map("nx" -> 256.0, "ny" -> 256.0)
      val p = Wave.params.fromValues(values)
      assert(p.isRight, s"${preset.name}: $p")
      assertEquals(Wave.check(p.toOption.get, Wave.stepping), Right(()), preset.name)
      assert(preset.doc.nonEmpty)
    }
  }

  test("every preset paints only what the wave model accepts, on small and large grids") {
    for preset <- WavePresets.all; n <- Seq(64, 256) do
      preset.interventions(n, n).foreach(iv =>
        assertEquals(Wave.accepts(iv), Right(()), preset.name)
      )
  }

  test("every preset runs, stays finite and replays from its manifest") {
    val registry = ModelRegistry.of(Wave)
    WavePresets.all.foreach { preset =>
      val r = build(preset)
      r.runSteps(200)
      assert(r.state.u.max.isFinite && r.state.u.min.isFinite, preset.name)
      val manifest = r.manifest(r.time).copy(steps = Some(r.stepCount))
      val replay =
        Manifest.fromJson(Manifest.toJson(manifest)).flatMap(Manifest.replay(_, registry))
      assertEquals(replay.map(_.execute().finalDigest), Right(r.digest), preset.name)
    }
  }

  test("the double slit has a wall with two openings") {
    val r = build(WavePresets.doubleSlit, 256)
    r.step()
    val column = (0 until 256).map(j => r.state.wall(174, j))
    val openings = column.sliding(2).count(w => w(0) && !w(1))
    assertEquals(openings, 2)
    assert(column.head && column.last)
  }

  test("the waveguide keeps a pulse inside its channel") {
    val r = build(WavePresets.waveguide, 256)
    r.runSteps(100)
    var inside = 0.0
    var outside = 0.0
    for j <- 0 until 256; i <- 60 until 200 do
      val a = math.abs(r.state.u(i, j))
      if math.abs(j - 128) < 12 then inside = math.max(inside, a)
      else if math.abs(j - 128) >= 17 then outside = math.max(outside, a)
    assert(inside > 0.01, s"inside $inside")
    assert(outside < 1e-3 * inside, s"inside $inside outside $outside")
  }
