package physis.models.fluid

import physis.core.run.{Disc, Op, Physis}

/**
 * Flow past a cylinder in a channel, against `python tools/fluid_reference.py wake`: above a
 * Reynolds number of about 47 the wake becomes unstable and sheds a street of vortices, which
 * the `shedding` probe (the vertical velocity in the wake) sees as an oscillation.
 */
class FluidWakeSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val Nx = 160
  private val Ny = 64
  private val Diameter = 14.0

  private def channel(re: Double) =
    val p = FluidParams(
      viscosity = Diameter / re,
      dyeDiffusion = 0.0,
      buoyancy = 0.0,
      lid = 0.0,
      inflow = 1.0,
      edge = FluidEdge.Channel,
      nx = Nx,
      ny = Ny
    )
    val r = Physis
      .model(Fluid)
      .withParams(p)
      .stepping(Fluid.stepping)
      .probe(Fluid.probes*)
      .build()
    // a little off the axis: the staircase circle is symmetric, and the street would be slow to start
    val cylinder = Disc(0.2 * Nx, Ny / 2 + 0.3, Diameter / 2)
    val painted = r.state.wall.paint(cylinder, false, false, Op.Assign, 1.0)
    assert(painted > 100, s"cylinder of $painted cells")
    r.state.wallsChanged()
    r

  /** Peak-to-peak size and Strouhal number of the probe over the second half of the run. */
  private def measure(re: Double, steps: Int): (Double, Double, Int) =
    val r = channel(re)
    val signal = new Array[Double](steps)
    for k <- 0 until steps do
      r.step()
      signal(k) = Fluid.shedding.measure(r.state, 0.0)
    val half = signal.drop(steps / 2)
    val mean = half.sum / half.length
    val rises = (1 until half.length).filter(k => half(k - 1) < mean && half(k) >= mean)
    val amplitude = half.max - half.min
    val period =
      if rises.size >= 2 then (rises.last - rises.head).toDouble / (rises.size - 1) * Fluid.Dt
      else Double.NaN
    (amplitude, Diameter / period, rises.size)

  test("Re = 150: the wake sheds a vortex street with a Strouhal number near the reference") {
    val (amplitude, strouhal, periods) = measure(150.0, 3000)
    assert(amplitude > 0.3, f"the probe swings only $amplitude%.3f")
    assert(periods >= 4, s"$periods periods")
    // reference (numpy, 160 x 64, D = 14): 0.218. The channel is narrow (22 % blockage), which
    // raises the number over the 0.16 to 0.19 of an unbounded flow.
    assert(strouhal > 0.17 && strouhal < 0.27, f"Strouhal number $strouhal%.4f")
  }

  test("Re = 20: below the critical number the wake stays steady") {
    val (amplitude, _, _) = measure(20.0, 2000)
    assert(amplitude < 0.05, f"the probe swings $amplitude%.4f")
  }
