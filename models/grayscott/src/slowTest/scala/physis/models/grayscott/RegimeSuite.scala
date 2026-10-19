package physis.models.grayscott

import physis.core.run.{Physis, Run}

/**
 * What the regimes of the (F, k) plane do on a 128 x 128 grid, with thresholds taken from the
 * independent simulation (tools/gray_scott_reference.py regimes). The runs start without noise,
 * as the reference does: noise changes how a pattern develops.
 */
class RegimeSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def start(f: Double, k: Double, seed: Long = 1L): Run[GrayScottParams, GrayScottState] =
    Physis
      .model(GrayScott)
      .stepping(GrayScott.stepping)
      .overrides(Map("nx" -> 128.0, "ny" -> 128.0, "noise" -> 0.0, "F" -> f, "k" -> k))
      .seed(seed)
      .build()

  test("mitosis: two spots at 8 000 steps that divide and spread to about 20 % coverage") {
    val run = start(0.0367, 0.0649)
    run.runSteps(8000)
    val early = run.state.coverage(0.2)
    assert(run.state.v.max > 0.3, s"v_max ${run.state.v.max}")
    assert(early > 0.001 && early < 0.02, s"coverage after 8000 steps: $early")
    run.runSteps(12000)
    val late = run.state.coverage(0.2)
    assert(late > 0.15 && late < 0.3, s"coverage after 20000 steps: $late")
  }

  test("F = 0.09, k = 0.06: the pattern dies out") {
    val run = start(0.09, 0.06)
    run.runSteps(8000)
    assert(run.state.v.max < 1e-3, s"v_max ${run.state.v.max}")
  }

  test("the other presets grow a pattern with their characteristic coverage") {
    val expected = Map(
      "Coral" -> (0.45, 0.57),
      "Maze" -> (0.36, 0.46),
      "Solitons" -> (0.18, 0.26),
      "Worms" -> (0.12, 0.19)
    )
    for preset <- GrayScott.presets if expected.contains(preset.name) do
      val run = start(preset.values("F"), preset.values("k"))
      run.runSteps(8000)
      val (low, high) = expected(preset.name)
      val coverage = run.state.coverage(0.2)
      assert(coverage > low && coverage < high, s"${preset.name}: coverage $coverage")
      assert(run.state.v.max > 0.25, s"${preset.name}: v_max ${run.state.v.max}")
  }

  test("u and v stay in [0, 1] for 5 000 steps with the default parameters") {
    val run = start(0.0367, 0.0649)
    var lowest = 1.0
    var highest = 0.0
    for _ <- 1 to 50 do
      run.runSteps(100)
      lowest = math.min(lowest, math.min(run.state.u.min, run.state.v.min))
      highest = math.max(highest, math.max(run.state.u.max, run.state.v.max))
    assert(lowest >= 0.0 && highest <= 1.0, s"range [$lowest, $highest]")
  }
