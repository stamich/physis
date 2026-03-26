package physis.models.physarum

import physis.core.run.Physis
import physis.view.Png

/**
 * Speed bars for the 0.5 features. They are set well below what a development machine does, so
 * that a slow CI machine passes but an accidental allocation, a lost inlining or a PNG writer
 * that went quadratic would not. The measured rates go to the test output.
 */
class SpeedSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def median(values: Array[Double]): Double = values.sorted.apply(values.length / 2)

  test("Physarum moves at least 5 million agents per second on 512 x 512 (median of 5)") {
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
    val m = median(rates)
    println(f"physarum: ${m / 1e6}%.1f million agent steps per second (512 x 512, n = $n)")
    assert(m >= 5e6, f"median ${m / 1e6}%.1f million agent steps per second")
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
    println(f"png: $m%.1f ms for 512 x 512")
    assert(m < 30.0, f"median $m%.1f ms")
  }
