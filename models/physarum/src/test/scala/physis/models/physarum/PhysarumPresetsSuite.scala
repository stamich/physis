package physis.models.physarum

import physis.core.run.Physis
import physis.view.{Channel, Direction, View}

class PhysarumPresetsSuite extends munit.FunSuite:

  private def run(preset: String, seed: Long) =
    val p = Physarum.presets.find(_.name == preset).get
    Physis
      .model(Physarum)
      .stepping(Physarum.stepping)
      .overrides(Map("n" -> 2500.0, "nx" -> 128.0, "ny" -> 128.0) ++ p.values)
      .seed(seed)
      .build()

  test("every preset is valid for the schema and passes the model's checks") {
    for preset <- Physarum.presets do
      val result = Physarum.params.fromValues(preset.values)
      assert(result.isRight, s"${preset.name}: $result")
      result.foreach { p =>
        val checked = Physarum.check(p, Physarum.stepping)
        assert(checked.isRight, s"${preset.name}: $checked")
      }
    assertEquals(Physarum.presets.map(_.name), Vector("Network", "Mesh", "Spots"))
  }

  // Thresholds from tools/physarum_reference.py, 2 500 agents on 128 x 128 after 600 steps:
  // Network 1 piece covering 9 to 12 percent; Mesh 1 to 4 pieces covering 17 to 21 percent;
  // Spots 39 to 42 separate pieces covering 12 to 13 percent.
  test("Network joins into a few structures that cover a tenth of the world") {
    for seed <- 1L to 2L do
      val r = run("Network", seed)
      r.runSteps(600)
      assert(r.state.structures <= 5, s"seed $seed: ${r.state.structures} pieces")
      assert(r.state.coverage < 0.15, s"seed $seed: coverage ${r.state.coverage}")
  }

  test("Mesh joins into a few structures that cover more of the world") {
    for seed <- 1L to 2L do
      val r = run("Mesh", seed)
      r.runSteps(600)
      assert(r.state.structures <= 6, s"seed $seed: ${r.state.structures} pieces")
      assert(r.state.coverage > 0.14, s"seed $seed: coverage ${r.state.coverage}")
  }

  test("Spots stays in many separate pieces") {
    for seed <- 1L to 2L do
      val r = run("Spots", seed)
      r.runSteps(600)
      assert(r.state.structures >= 20, s"seed $seed: ${r.state.structures} pieces")
  }

  test("the default view is the trail over two plots, whose channels are the right probes") {
    PhysarumViews.default match
      case View.Layout(
            Seq(
              View.Layers(heat, dots),
              View.Layout(Seq(View.Plot(m, _, _), View.Plot(s, _, _)), _)
            ),
            Direction.Vertical
          ) =>
        assertEquals(heat.slice, "trail")
        assertEquals((dots.x, dots.y), ("x", "y"))
        assertEquals(m, Seq(PhysarumViews.mass))
        assertEquals(s, Seq(PhysarumViews.structures))
      case other => fail(s"unexpected view $other")
    val names = Physarum.probes.map(_.name)
    def probeIndex(c: Channel): Int = c match
      case Channel.ProbeAt(i, _) => i
      case other => fail(s"unexpected channel $other")
    assertEquals(names(probeIndex(PhysarumViews.mass)), "trail_mass")
    assertEquals(names(probeIndex(PhysarumViews.structures)), "structures")
  }
