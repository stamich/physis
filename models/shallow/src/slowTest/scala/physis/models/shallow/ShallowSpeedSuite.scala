package physis.models.shallow

import physis.core.run.Physis
import physis.verify.SpeedReport

/**
 * The speed of the shallow-water model on 256 x 256 cells, as cell updates per second, for the
 * linear step and for the nonlinear one (open sea, a front that has started to move). The bars
 * are set far below what a CI runner reaches, so that a slow runner passes but a step that
 * started to allocate or to do work per cell it should not would not; the numbers go to the test
 * output and to `build/speed`.
 */
class ShallowSpeedSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val N = 256

  private def sea(nonlinear: Int, edges: Int) =
    val p = ShallowParams(
      gravity = 9.81,
      depth = 4000.0,
      dx = 20_000.0,
      f0 = 1e-4,
      beta = 2e-11,
      friction = 0.0,
      nonlinear = nonlinear,
      edges = edges,
      sponge = 10,
      seabed = 1,
      start = 1,
      amp = 0.2,
      nx = N,
      ny = N
    )
    Physis.model(Shallow).withParams(p).stepping(Shallow.stepping).seed(1L).build()

  private def best(r: physis.core.run.Run[ShallowParams, ShallowState]): Double =
    r.runSteps(100) // warm-up: class loading and JIT
    val windows = Array.fill(5) {
      val steps = 50
      val start = System.nanoTime()
      r.runSteps(steps.toLong)
      N.toDouble * N * steps / ((System.nanoTime() - start) / 1e9)
    }
    windows.max

  test("the linear step updates cells fast enough on 256 x 256") {
    val rate = best(sea(nonlinear = 0, edges = Shallow.Basin))
    println(SpeedReport.record("shallow linear cell updates 256x256", rate / 1e6, "million/s"))
    assert(rate >= 0.5e6, f"${rate / 1e6}%.2f million cell updates per second")
  }

  test("the nonlinear step updates cells fast enough on 256 x 256") {
    val rate = best(sea(nonlinear = 1, edges = Shallow.OpenSea))
    println(SpeedReport.record("shallow nonlinear cell updates 256x256", rate / 1e6, "million/s"))
    assert(rate >= 0.2e6, f"${rate / 1e6}%.2f million cell updates per second")
  }

  test("the default sea takes under 40 ms a step") {
    val r = Physis
      .model(Shallow)
      .withParams(Shallow.params.defaults)
      .stepping(Shallow.stepping)
      .seed(1L)
      .build()
    r.runSteps(200)
    val start = System.nanoTime()
    r.runSteps(100)
    val ms = (System.nanoTime() - start) / 1e6 / 100.0
    println(SpeedReport.record("shallow step 128x96", ms, "ms"))
    assert(ms < 40.0, f"$ms%.1f ms a step")
  }
