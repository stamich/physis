package physis.models.boids

import physis.core.run.Physis

/**
 * Alignment only is the Vicsek model, which has an order-disorder transition in the noise. The
 * thresholds come from tools/boids_reference.py, an independent brute-force implementation of
 * the same rules: with wander 0.1 a flock of 500 reaches polarization 0.9 in about 300 steps;
 * with wander 2.5 it stays below 0.15.
 */
class VicsekSuite extends munit.FunSuite:

  private def alignmentOnly(wander: Double, seed: Long) =
    Physis
      .model(Boids)
      .stepping(Boids.stepping)
      .overrides(
        Map(
          "n" -> 500.0,
          "width" -> 400.0,
          "height" -> 300.0,
          "wSeparation" -> 0.0,
          "wCohesion" -> 0.0,
          "wAlignment" -> 1.0,
          "wander" -> wander
        )
      )
      .seed(seed)
      .build()

  test("with little noise a random crowd aligns: polarization from below 0.3 to above 0.9") {
    for seed <- Seq(1L, 2L, 3L) do
      val run = alignmentOnly(0.1, seed)
      val start = run.state.polarization
      run.runSteps(600)
      val end = run.state.polarization
      assert(start < 0.3, s"seed $seed: polarization at the start $start")
      assert(end > 0.9, s"seed $seed: polarization after 600 steps $end")
  }

  test("with strong noise the flock never orders: polarization stays below 0.3") {
    for seed <- Seq(1L, 2L, 3L) do
      val run = alignmentOnly(2.5, seed)
      for _ <- 1 to 6 do
        run.runSteps(100)
        val phi = run.state.polarization
        assert(phi < 0.3, s"seed $seed after ${run.stepCount} steps: polarization $phi")
  }

  test("polarization falls as the noise grows") {
    def after600(wander: Double): Double =
      val run = alignmentOnly(wander, 4L)
      run.runSteps(600)
      run.state.polarization
    val low = after600(0.1)
    val high = after600(2.0)
    assert(low > 0.9 && high < 0.3, s"low noise $low, high noise $high")
  }

  test("changing the noise live breaks an ordered flock up again") {
    val run = alignmentOnly(0.1, 5L)
    run.runSteps(600)
    assert(run.state.polarization > 0.9)
    assertEquals(run.setParams(Map("wander" -> 2.5)), Right(()))
    run.runSteps(400)
    assert(run.state.polarization < 0.3, s"polarization ${run.state.polarization}")
  }
