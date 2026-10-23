package physis.models.convection

import physis.core.run.Physis
import physis.verify.SpeedReport

/**
 * The speed of the convection model on 256 x 128 cells (periodic sides, Ra 100 000, rolls that
 * have started to form), as cell updates per second and solver iterations per step. The
 * pressure solver is the conjugate gradient of the fluid, warm-started from the previous step.
 * The bars are set far below what a CI runner reaches, so that a slow runner passes but a solver
 * that stopped converging would not; the numbers go to the test output and to `build/speed`.
 */
class ConvectionSpeedSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val Nx = 256
  private val Ny = 128

  private def layer() =
    val p = ConvectionParams(
      rayleigh = 100_000.0,
      prandtl = 1.0,
      sides = ConvectionSides.Periodic,
      nx = Nx,
      ny = Ny,
      kick = 0.01
    )
    Physis.model(Convection).withParams(p).stepping(Convection.stepping).seed(1L).build()

  test("the layer updates cells fast enough on 256 x 128, and the solver stays within its cap") {
    val r = layer()
    r.runSteps(300) // warm-up: class loading, JIT, and the first overturn
    var iterations = 0L
    var counted = 0L
    var maxIterations = 0
    val windows = Array.fill(5) {
      val steps = 40
      val start = System.nanoTime()
      var k = 0
      while k < steps do
        r.step()
        iterations += r.state.iterations
        counted += 1
        maxIterations = math.max(maxIterations, r.state.iterations)
        k += 1
      Nx.toDouble * Ny * steps / ((System.nanoTime() - start) / 1e9)
    }
    val best = windows.max
    val perStep = iterations.toDouble / counted
    println(SpeedReport.record("convection cell updates 256x128", best / 1e6, "million/s"))
    println(SpeedReport.record(
      "convection solver iterations per step 256x128",
      perStep,
      "iterations"
    ))
    val all = windows.map(w => f"${w / 1e6}%.2f").mkString(", ")
    assert(best >= 0.1e6, f"best ${best / 1e6}%.2f million cell updates per second (windows: $all)")
    assert(maxIterations < Convection.MaxIterations, s"the solver hit its cap of $maxIterations")
  }

  test("the default 96 x 48 layer takes under 40 ms a step") {
    val p = Convection.params.defaults
    val r = Physis.model(Convection).withParams(p).stepping(Convection.stepping).seed(1L).build()
    r.runSteps(300)
    val start = System.nanoTime()
    r.runSteps(100)
    val ms = (System.nanoTime() - start) / 1e6 / 100.0
    println(SpeedReport.record("convection step 96x48", ms, "ms"))
    assert(ms < 40.0, f"$ms%.1f ms a step")
  }
