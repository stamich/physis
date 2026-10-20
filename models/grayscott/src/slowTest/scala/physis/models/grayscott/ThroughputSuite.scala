package physis.models.grayscott

import physis.core.run.Physis
import physis.verify.SpeedReport

/**
 * The Lab runs Gray-Scott at up to 4 000 steps/s on its 256 x 256 grid, which is about 260
 * million cell updates per second. This checks a much lower bar, so that a slow CI machine does
 * not fail it but an accidental allocation or a lost inlining in the stencil would.
 */
class ThroughputSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  test("at least 15 million cell updates per second on 256 x 256 (median of 5)") {
    val run = Physis
      .model(GrayScott)
      .stepping(GrayScott.stepping)
      .overrides(Map("nx" -> 256.0, "ny" -> 256.0))
      .seed(1L)
      .build()
    run.runSteps(300) // warm-up: class loading and JIT
    val cells = 256.0 * 256.0
    val rates = Array.fill(5) {
      val steps = 200
      val start = System.nanoTime()
      run.runSteps(steps)
      cells * steps / ((System.nanoTime() - start) / 1e9)
    }.sorted
    val median = rates(2)
    println(SpeedReport.record("gray-scott 256x256 cell updates", median / 1e6, "million/s"))
    assert(median >= 15e6, f"median ${median / 1e6}%.1f million cell updates per second")
  }
