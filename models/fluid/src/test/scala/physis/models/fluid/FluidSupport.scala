package physis.models.fluid

import physis.core.Stepping
import physis.core.run.{Physis, Run}
import physis.fields.{Field2D, Grid2D, Mac}

/** Helpers shared by the fluid suites. */
object FluidSupport:
  type FluidRun = Run[FluidParams, FluidState]

  def run(p: FluidParams, stepping: Stepping = Fluid.stepping): FluidRun =
    Physis.model(Fluid).withParams(p).stepping(stepping).probe(Fluid.probes*).build()

  def params(
      edge: Int,
      nx: Int,
      ny: Int,
      viscosity: Double = 0.1,
      dyeDiffusion: Double = 0.0,
      buoyancy: Double = 0.0,
      lid: Double = 0.0,
      inflow: Double = 0.0
  ): FluidParams = FluidParams(viscosity, dyeDiffusion, buoyancy, lid, inflow, edge, nx, ny)

  /**
   * Sets a periodic Taylor-Green vortex of speed `speed` and one wavelength per domain, from a
   * stream function so that it is free of divergence to rounding.
   */
  def taylorGreen(s: FluidState, speed: Double): Unit =
    val nx = s.mac.nx
    val ny = s.mac.ny
    val k = 2.0 * math.Pi / nx
    val psi = new Field2D(Grid2D(nx + 1, ny + 1))
    for j <- 0 to ny; i <- 0 to nx do
      psi(i, j) = speed / k * math.sin(k * i) * math.sin(2.0 * math.Pi / ny * j)
    Mac.curl(psi, s.u, s.v)
    for j <- 0 until ny do s.u(nx, j) = s.u(0, j)
    for i <- 0 until nx do s.v(i, ny) = s.v(i, 0)

  /** A square of dye, `size` cells on a side, with its lower left corner at `(x, y)`. */
  def dyeSquare(s: FluidState, x: Int, y: Int, size: Int): Unit =
    for j <- y until y + size; i <- x until x + size do s.dye(i, j) = 1.0
