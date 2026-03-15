package physis.models.physarum

import physis.core.run.Physis

/**
 * Thresholds from `tools/physarum_reference.py` (numpy, same rules, its own random numbers):
 * 2 500 agents on 128 x 128, default rules. In eight reference runs the cells above twice the
 * mean trail form 27 to 70 pieces after 10 to 20 steps (at least 27 after 20) and 1 to 3 after
 * 600 steps, covering 9 to 12 percent of the grid. The Java model agrees statistically, not bit
 * for bit, so the checks are wide.
 */
class PhysarumReferenceSuite extends munit.FunSuite:

  private def run(seed: Long) =
    Physis
      .model(Physarum)
      .stepping(Physarum.stepping)
      .overrides(Map("n" -> 2500.0, "nx" -> 128.0, "ny" -> 128.0))
      .seed(seed)
      .build()

  test("from a uniform start many small pieces become a few connected structures") {
    for seed <- 1L to 3L do
      val r = run(seed)
      r.runSteps(20)
      val early = r.state.structures
      assert(early >= 20, s"seed $seed: $early pieces after 20 steps")
      r.runSteps(580)
      val late = r.state.structures
      assert(late >= 1 && late <= 5, s"seed $seed: $late pieces after 600 steps")
      val cover = r.state.coverage
      assert(cover > 0.04 && cover < 0.2, s"seed $seed: coverage $cover")
  }

  test("the structure measure counts pieces across the seam once") {
    val r = run(1L)
    val s = r.state
    val d = s.trail
    // two cells that touch only over the seam, far from anything else: one piece, not two
    d.fill(0.0)
    d(0, 10) = 100.0
    d(127, 10) = 100.0
    assertEquals(s.structures, 1)
    d(64, 64) = 100.0
    assertEquals(s.structures, 2)
    assertEqualsDouble(s.coverage, 3.0 / (128 * 128), 1e-15)
  }
