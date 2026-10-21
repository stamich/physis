package physis.models.fluid

import physis.core.run.Physis

/**
 * The lid-driven cavity at Re = 100 against Ghia, Ghia and Shin (1982), and against the numpy
 * reference in tools/fluid_reference.py (`python tools/fluid_reference.py cavity`).
 */
class FluidCavitySuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  /** Heights (in units of the side, the lid at 1) and the published u along the centre line. */
  private val GhiaY =
    Array(1.0, 0.9766, 0.9688, 0.9609, 0.9531, 0.8516, 0.7344, 0.6172, 0.5, 0.4531, 0.2813, 0.1719,
      0.1016, 0.0703, 0.0625, 0.0547, 0.0)
  private val GhiaU =
    Array(1.0, 0.84123, 0.78871, 0.73722, 0.68717, 0.23151, 0.00332, -0.13641, -0.20581, -0.21090,
      -0.15662, -0.10150, -0.06434, -0.04775, -0.04192, -0.03717, 0.0)

  /** The profile of the numpy reference, 64 x 64 after 6 000 steps (its output, to 5 digits). */
  private val ReferenceU =
    Array(1.0, 0.84115, 0.78922, 0.73664, 0.68763, 0.22682, -0.00973, -0.14361, -0.20073,
      -0.20264, -0.14885, -0.09778, -0.06273, -0.04563, -0.04109, -0.03654, 0.0)

  private def cavity(n: Int, steps: Int) =
    val p = FluidParams(
      viscosity = n / 100.0,
      dyeDiffusion = 0.0,
      buoyancy = 0.0,
      lid = 1.0,
      inflow = 0.0,
      edge = FluidEdge.Box,
      nx = n,
      ny = n,
      tracers = 0
    )
    val r = Physis
      .model(Fluid)
      .withParams(p)
      .stepping(Fluid.stepping)
      .probe(Fluid.probes*)
      .build()
    r.runSteps(steps)
    r

  /** `u` on the vertical centre line (the face column n / 2), at the heights of the table. */
  private def centreline(s: FluidState, n: Int): Array[Double] =
    GhiaY.map { y =>
      val g = y * n - 0.5 // cell index, the first centre at 0.5
      if g <= -0.5 then 0.0
      else if g >= n - 0.5 then 1.0
      else if g < 0.0 then s.u(n / 2, 0) * (g + 0.5) / 0.5
      else if g > n - 1.0 then s.u(n / 2, n - 1) + (1.0 - s.u(n / 2, n - 1)) * (g - (n - 1.0)) / 0.5
      else
        val j = math.min(math.floor(g).toInt, n - 2)
        val t = g - j
        s.u(n / 2, j) * (1.0 - t) + s.u(n / 2, j + 1) * t
    }

  test("64 x 64 at Re = 100: u on the centre line is within 5 % of the lid speed of Ghia et al.") {
    val r = cavity(64, 4000)
    val e1 = Fluid.kineticEnergy.measure(r.state, 0.0)
    r.runSteps(500)
    val e2 = Fluid.kineticEnergy.measure(r.state, 0.0)
    assert(math.abs(e2 / e1 - 1.0) < 1e-3, s"not settled: $e1 -> $e2")
    val u = centreline(r.state, 64)
    val errors = u.zip(GhiaU).map((a, b) => math.abs(a - b))
    assert(errors.max < 0.05, f"largest difference ${errors.max}%.4f, profile ${u.mkString(" ")}")
    assert(Fluid.maxDivergence.measure(r.state, 0.0) < 1e-6)
  }

  test("64 x 64 agrees with the independent numpy implementation of the same scheme") {
    val r = cavity(64, 6000)
    val u = centreline(r.state, 64)
    val errors = u.zip(ReferenceU).map((a, b) => math.abs(a - b))
    assert(errors.max < 2e-3, f"largest difference ${errors.max}%.5f, profile ${u.mkString(" ")}")
  }

  test("refining the grid brings the profile closer to the table (32 x 32 is worse than 64 x 64)") {
    def worst(n: Int): Double =
      val r = cavity(n, 4000)
      centreline(r.state, n).zip(GhiaU).map((a, b) => math.abs(a - b)).max
    val coarse = worst(32)
    val fine = worst(64)
    assert(fine < coarse, f"32: $coarse%.4f, 64: $fine%.4f")
  }
