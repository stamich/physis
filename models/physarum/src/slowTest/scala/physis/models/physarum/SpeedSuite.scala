package physis.models.physarum

import physis.core.run.Physis
import physis.verify.SpeedReport
import physis.view.Png

/**
 * Speed bars for the 0.5 features. They are set below what the CI runners measured (Physarum
 * 31.5 and 16.1 million agent steps per second on two runs, a 512 x 512 PNG in 0.9 to 1.6 ms), so that a slow run passes but
 * an accidental allocation, a lost inlining or a PNG writer that went quadratic would not. The
 * measured rates go to the test output and to `build/speed`.
 */
class SpeedSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def median(values: Array[Double]): Double = values.sorted.apply(values.length / 2)

  test("Physarum moves at least 12 million agents per second on 512 x 512 (best of 5 windows)") {
    val n = 200000
    val run = Physis
      .model(Physarum)
      .stepping(Physarum.stepping)
      .overrides(Map("n" -> n.toDouble, "nx" -> 512.0, "ny" -> 512.0))
      .seed(1L)
      .build()
    run.runSteps(50) // warm-up: class loading and JIT
    val rates = Array.fill(5) {
      val steps = 40
      val start = System.nanoTime()
      run.runSteps(steps)
      n.toDouble * steps / ((System.nanoTime() - start) / 1e9)
    }
    // Runners differ by a factor of two from one run to the next (31.5 and 16.1 million were
    // measured), so the best window is compared with a bar below the slower of the two.
    val m = rates.max
    println(SpeedReport.record("physarum agent steps 512x512", m / 1e6, "million/s"))
    val windows = rates.map(r => f"${r / 1e6}%.1f").mkString(", ")
    assert(m >= 12e6, f"best ${m / 1e6}%.1f million agent steps per second (windows: $windows)")
  }

  test("a 512 x 512 PNG is written in under 30 ms (median of 9)") {
    val pixels = Array.tabulate(512 * 512) { k =>
      val x = k % 512
      val y = k / 512
      0xff000000 | ((x & 0xff) << 16) | ((y & 0xff) << 8) | ((x ^ y) & 0xff)
    }
    for _ <- 1 to 20 do Png.encode(pixels, 512, 512)
    val times = Array.fill(9) {
      val start = System.nanoTime()
      val bytes = Png.encode(pixels, 512, 512)
      assert(bytes.length > 512 * 512 * 3)
      (System.nanoTime() - start) / 1e6
    }
    val m = median(times)
    println(SpeedReport.record("png 512x512", m, "ms"))
    assert(m < 30.0, f"median $m%.1f ms")
  }
