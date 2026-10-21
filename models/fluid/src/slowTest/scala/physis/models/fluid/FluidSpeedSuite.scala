package physis.models.fluid

import physis.core.run.{Disc, Op, Physis}
import physis.verify.SpeedReport

/**
 * The speed of the fluid on 256 x 256 cells (a channel with a cylinder, 0.1 viscosity), as cell
 * updates per second and solver iterations per step. Two CI runs measured 0.25 and 0.24
 * million cell updates per second and 716 iterations per step (the same every time): the pressure solver is an unpreconditioned
 * conjugate gradient to a tolerance of 1e-9, so the iterations grow with the grid and a step
 * costs about a quarter of a second. The bars are set below that, so that a slow runner passes
 * but a solver that stopped converging would not. The numbers go to the test output and to
 * `build/speed`.
 */
class FluidSpeedSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val N = 256

  private def channel() =
    val p = FluidParams(
      viscosity = 0.1,
      dyeDiffusion = 0.0,
      buoyancy = 0.0,
      lid = 0.0,
      inflow = 1.0,
      edge = FluidEdge.Channel,
      nx = N,
      ny = N,
      tracers = 0
    )
    val r = Physis
      .model(Fluid)
      .withParams(p)
      .stepping(Fluid.stepping)
      .seed(1L)
      .build()
    val _ = r.state.wall.paint(Disc(0.2 * N, N / 2 + 0.3, 16.0), false, false, Op.Assign, 1.0)
    r.state.wallsChanged()
    r

  test("the fluid updates cells fast enough on 256 x 256, and the solver stays within its cap") {
    val r = channel()
    r.runSteps(150) // warm-up: class loading, JIT, and the start-up transient of the flow
    var iterations = 0L
    var counted = 0L
    var maxIterations = 0
    val windows = Array.fill(5) {
      val steps = 25
      val start = System.nanoTime()
      var k = 0
      while k < steps do
        r.step()
        iterations += r.state.iterations
        counted += 1
        maxIterations = math.max(maxIterations, r.state.iterations)
        k += 1
      N.toDouble * N * steps / ((System.nanoTime() - start) / 1e9)
    }
    val best = windows.max
    val perStep = iterations.toDouble / counted
    println(SpeedReport.record("fluid cell updates 256x256", best / 1e6, "million/s"))
    println(SpeedReport.record("fluid solver iterations per step 256x256", perStep, "iterations"))
    val all = windows.map(w => f"${w / 1e6}%.2f").mkString(", ")
    assert(best >= 0.1e6, f"best ${best / 1e6}%.2f million cell updates per second (windows: $all)")
    assert(maxIterations < Fluid.MaxIterations, s"the solver hit its cap of $maxIterations")
    assert(perStep < 800.0, f"$perStep%.1f iterations per step")
  }
