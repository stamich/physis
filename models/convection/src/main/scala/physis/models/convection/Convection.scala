package physis.models.convection

import physis.core.{Digest, Model, Probe, StepContext, Stepping}
import physis.core.live.{Slice, StateExport}
import physis.core.param.{ParamSchema, ParamSpec}
import physis.core.random.SplittableRng
import physis.core.run.{Disc, Intervention, Op}
import physis.fields.{
  Advect,
  Boundaries,
  Boundary,
  Brush,
  Diffuse,
  Field2D,
  Mac,
  MacGrid,
  Mask,
  Projection,
  Pulse,
  Scalar
}

/**
 * Parameters of the convection.
 *
 * @param rayleigh the Rayleigh number `Ra = g α ΔT H³ / (ν κ)`: how hard the heating pushes
 *                 against viscosity and heat diffusion (convection starts above about 1708)
 * @param prandtl  the Prandtl number `Pr = ν / κ`, the ratio of the two diffusions
 * @param sides    the left and right borders (needs a restart): 0 periodic, 1 insulated walls
 * @param nx       grid columns (needs a restart)
 * @param ny       grid rows, the depth `H` of the layer in cells (needs a restart)
 * @param kick     size of the random temperature perturbation the run starts with (needs a restart)
 */
final case class ConvectionParams(
    rayleigh: Double,
    prandtl: Double,
    sides: Int,
    nx: Int,
    ny: Int,
    kick: Double
)

object ConvectionParams:
  given schema: ParamSchema[ConvectionParams] = ParamSchema.derived[ConvectionParams](
    ParamSpec(
      "rayleigh",
      default = 4000.0,
      min = 100.0,
      max = 1_000_000.0,
      doc = "Rayleigh number: convection starts above about 1708"
    ),
    ParamSpec(
      "prandtl",
      default = 1.0,
      min = 0.1,
      max = 10.0,
      doc = "Prandtl number: viscosity over heat diffusion"
    ),
    ParamSpec(
      "sides",
      default = 0,
      min = 0,
      max = 1,
      doc = "Left and right borders: 0 periodic, 1 insulated walls",
      restart = true
    ),
    ParamSpec("nx", default = 96, min = 16, max = 512, doc = "Grid columns", restart = true),
    ParamSpec(
      "ny",
      default = 48,
      min = 16,
      max = 256,
      doc = "Grid rows: the depth of the layer",
      restart = true
    ),
    ParamSpec(
      "kick",
      default = 0.01,
      min = 0.0,
      max = 0.5,
      doc = "Random temperature perturbation at the start",
      restart = true
    )
  )

/** The borders of [[ConvectionParams.sides]]. */
object ConvectionSides:
  val Periodic = 0
  val Walls = 1

/**
 * State of a layer of fluid between a hot floor (row 0) and a cold lid (row `ny - 1`).
 *
 * The temperature is measured in units of the difference `ΔT` between floor and lid, and the
 * state keeps only its deviation `θ` from the conduction profile `T_c(y) = 1 - y / H`, which is
 * 0 on both plates. Then `θ` has the same condition (0) on both and the equations are
 *
 * {{{
 *   ∂θ/∂t + u·∇θ = κ ∇²θ + w / H          (the flow brings warmer fluid up: + w / H)
 *   ∂u/∂t + u·∇u = -∇p + ν ∇²u + a θ ŷ,   a = Ra ν κ / H³
 * }}}
 *
 * The part of the buoyancy that belongs to `T_c` depends on height only and is balanced by the
 * pressure, so it is left out. Velocity is on the staggered grid, `θ` at the cell centres; the
 * plates are no-slip walls. Cells painted solid keep `θ = 0` and the flow goes around them.
 */
final class ConvectionState(val mac: MacGrid, val sides: Int):
  private val nx = mac.nx
  private val ny = mac.ny

  /** Whether the left and right borders wrap. */
  val periodic: Boolean = sides == ConvectionSides.Periodic

  private val us = Array(new Field2D(mac.uFaces), new Field2D(mac.uFaces))
  private val vs = Array(new Field2D(mac.vFaces), new Field2D(mac.vFaces))
  private val flows = Array(
    new Advect.Flow(us(0), vs(0), nx, ny, periodic, false),
    new Advect.Flow(us(1), vs(1), nx, ny, periodic, false)
  )
  private var cur = 0
  private val scratchU = new Field2D(mac.uFaces)
  private val scratchV = new Field2D(mac.vFaces)
  private val scratchC = new Field2D(mac.cells)

  /** The temperature deviation `θ`. */
  val theta: Scalar =
    new Scalar(
      mac.cells,
      Boundaries(
        if periodic then Boundary.Periodic else Boundary.Neumann,
        Boundary.Dirichlet(0.0)
      )
    )

  /** Cells that are solid. */
  val wall: Mask = new Mask(mac.cells)

  /** The projection, which owns the pressure. */
  val projection: Projection = new Projection(mac, periodic, false, false)

  /** Solver iterations of the latest step. */
  var iterations: Int = 0

  /** The thermal diffusivity of the latest step, which the Nusselt number is measured against. */
  private var kappa: Double = Convection.MaxDiffusion

  projection.configure(wall.field)

  /** The horizontal velocity, on the vertical faces. */
  def u: Field2D = us(cur)

  /** The vertical velocity, on the horizontal faces. */
  def v: Field2D = vs(cur)

  /** The temperature deviation at the cell centres. */
  def deviation: Field2D = theta.field

  /** The temperature `T = θ + T_c` of cell `(i, j)`, 1 at the floor and 0 at the lid. */
  def temperature(i: Int, j: Int): Double = theta.field(i, j) + 1.0 - (j + 0.5) / ny

  /** Call after the wall mask changed. */
  def wallsChanged(): Unit = projection.configure(wall.field)

  private def closeFaces(): Unit = projection.closeFaces(u, v)

  /** Accelerates `v` by `dt · a · θ` on the open faces, with `θ` averaged from the two cells. */
  private def lift(accel: Double, dt: Double): Unit =
    val f = v
    val t = theta.field
    val open = projection.poisson.openV
    val gain = 0.5 * dt * accel
    var j = 1
    while j < ny do
      var i = 0
      while i < nx do
        if open(i, j) != 0.0 then f(i, j) += gain * (t(i, j - 1) + t(i, j))
        i += 1
      j += 1

  private def clamp(f: Field2D, limit: Double): Unit =
    val data = f.data
    var j = 0
    while j < f.ny do
      var k = f.grid.index(0, j)
      val end = k + f.nx
      while k < end do
        data(k) = math.max(-limit, math.min(limit, data(k)))
        k += 1
      j += 1

  /** Viscous diffusion of the velocity, `u += ν dt ∇²u`, with no-slip plates (mirrored halo). */
  private def diffuseVelocity(s: Double): Unit =
    if s > 0.0 then
      val uf = u
      val vf = v
      var j = 0
      while j < ny do
        if periodic then
          uf(-1, j) = uf(nx - 1, j)
          uf(nx + 1, j) = uf(1, j)
        j += 1
      var i = 0
      while i <= nx do
        uf(i, -1) = -uf(i, 0)
        uf(i, ny) = -uf(i, ny - 1)
        i += 1
      j = 0
      while j <= ny do
        if periodic then
          vf(-1, j) = vf(nx - 1, j)
          vf(nx, j) = vf(0, j)
        else
          vf(-1, j) = -vf(0, j)
          vf(nx, j) = -vf(nx - 1, j)
        j += 1
      Diffuse.stepOpen(uf, scratchU, projection.poisson.openU, s)
      Diffuse.stepOpen(vf, scratchV, projection.poisson.openV, s)
      uf.copyFrom(scratchU)
      vf.copyFrom(scratchV)

  /** The source of the deviation: `θ += dt · w / H`, with `w` at the cell centres. */
  private def heatFromFlow(dt: Double): Unit =
    val t = theta.field
    val f = v
    val gain = dt / ny
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        t(i, j) += gain * 0.5 * (f(i, j) + f(i, j + 1))
        i += 1
      j += 1

  private def clearHeatInWalls(): Unit =
    if wall.count > 0 then
      val t = theta.field
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          if wall(i, j) then t(i, j) = 0.0
          i += 1
        j += 1

  /**
   * One step of size `dt`: buoyancy, advection of the velocity and of `θ`, the heat the flow
   * carries up, viscosity and heat diffusion, then the projection.
   */
  def step(p: ConvectionParams, dt: Double): Unit =
    val nu = Convection.viscosity(p)
    kappa = Convection.diffusivity(p)
    closeFaces()
    lift(Convection.acceleration(p), dt)
    val limit = Convection.MaxCellsPerStep / dt
    clamp(u, limit)
    clamp(v, limit)
    val from = cur
    val to = 1 - cur
    val flow = flows(from)
    Advect.facesU(flow, us(from), us(to), dt)
    Advect.facesV(flow, vs(from), vs(to), dt)
    theta.advect(flow, dt)
    cur = to
    closeFaces()
    heatFromFlow(dt)
    diffuseVelocity(nu * dt)
    theta.diffuse(kappa * dt)
    closeFaces()
    iterations = projection.project(u, v, Convection.Tolerance, Convection.MaxIterations)
    clearHeatInWalls()

  /** Paints walls or heat, see [[Convection.intervene]]. */
  def applyBrush(target: String, disc: Disc, op: Op, value: Double): Unit =
    target match
      case "wall" =>
        val _ = wall.paint(disc, periodic, false, op, value)
        wallsChanged()
        closeFaces()
        clearHeatInWalls()
      case _ =>
        val _ = Brush(theta.field, disc, periodic, false, op, value)
        clearHeatInWalls()

  /** Adds a Gaussian of heat (`amplitude` negative cools) centred at `(x, y)` in cells. */
  def applyPulse(x: Double, y: Double, sigma: Double, amplitude: Double): Unit =
    val _ = Pulse.stamp(theta.field, x, y, sigma, amplitude, periodic, false)
    clearHeatInWalls()

  /** Puts a random deviation of up to `±size` into every cell. */
  def perturb(rng: SplittableRng, size: Double): Unit =
    val t = theta.field
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        t(i, j) = size * (2.0 * rng.nextDouble() - 1.0)
        i += 1
      j += 1

  /** Writes the vorticity at the cell centres, row by row, from `offset`. */
  def vorticityInto(out: Array[Double], offset: Int): Unit =
    Mac.vorticity(u, v, scratchC, periodic, false)
    scratchC.copyInteriorInto(out, offset)

  /** Writes the speed at the cell centres, row by row, from `offset`. */
  def speedInto(out: Array[Double], offset: Int): Unit =
    val f = u
    val g = v
    var k = offset
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        val a = 0.5 * (f(i, j) + f(i + 1, j))
        val b = 0.5 * (g(i, j) + g(i, j + 1))
        out(k) = math.sqrt(a * a + b * b)
        k += 1
        i += 1
      j += 1

  /** Writes the temperature `θ + T_c`, row by row, from `offset`. */
  def temperatureInto(out: Array[Double], offset: Int): Unit =
    var k = offset
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        out(k) = temperature(i, j)
        k += 1
        i += 1
      j += 1

  /** Kinetic energy `½ ∫ |velocity|²`. */
  def kineticEnergy: Double = Mac.kineticEnergy(u, v, mac.cells)

  /** Largest divergence of any cell. */
  def maxDivergence: Double = projection.maxDivergence(u, v)

  /**
   * The Nusselt number from the heat the flow carries, `Nu = 1 + H ⟨w θ⟩ / κ`, with `⟨ ⟩` the
   * average over all cells and `w` at the cell centres: the heat transported divided by what
   * conduction alone would carry. It is 1 at rest and grows with the vigour of the convection.
   */
  def nusselt: Double =
    val t = theta.field
    val f = v
    var sum = 0.0
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        sum += 0.5 * (f(i, j) + f(i, j + 1)) * t(i, j)
        i += 1
      j += 1
    1.0 + ny * (sum / (nx * ny)) / kappa

  /**
   * The Nusselt number at the floor from the temperature gradient there, `Nu = 1 - 2 H ⟨θ⟩₀`,
   * with `⟨θ⟩₀` the average of the first row (the plate is half a cell below it and has θ = 0).
   */
  def nusseltFloor: Double =
    val t = theta.field
    var sum = 0.0
    var i = 0
    while i < nx do
      sum += t(i, 0)
      i += 1
    1.0 - 2.0 * ny * sum / nx

  /** The Nusselt number at the lid, `Nu = 1 + 2 H ⟨θ⟩` of the last row. */
  def nusseltLid: Double =
    val t = theta.field
    var sum = 0.0
    var i = 0
    while i < nx do
      sum += t(i, ny - 1)
      i += 1
    1.0 + 2.0 * ny * sum / nx

  /** The largest upward velocity of any face. */
  def maxUp: Double =
    val f = v
    var m = 0.0
    var j = 0
    while j <= ny do
      var i = 0
      while i < nx do
        m = math.max(m, f(i, j))
        i += 1
      j += 1
    m

object ConvectionState:
  /** Live runs export the temperature, its deviation, the vorticity, the speed and the walls. */
  given stateExport: StateExport[ConvectionState] with
    def size(state: ConvectionState): Int = 5 * state.mac.cells.cells

    def copyInto(state: ConvectionState, out: Array[Double]): Unit =
      val n = state.mac.cells.cells
      state.temperatureInto(out, 0)
      state.deviation.copyInteriorInto(out, n)
      state.vorticityInto(out, 2 * n)
      state.speedInto(out, 3 * n)
      state.wall.field.copyInteriorInto(out, 4 * n)

    override def layout(state: ConvectionState): IndexedSeq[Slice] =
      val g = state.mac.cells
      val n = g.cells
      Vector(
        Slice("temperature", 0, g.nx, g.ny),
        Slice("deviation", n, g.nx, g.ny),
        Slice("vorticity", 2 * n, g.nx, g.ny),
        Slice("speed", 3 * n, g.nx, g.ny),
        Slice("wall", 4 * n, g.nx, g.ny)
      )

/**
 * Rayleigh–Bénard convection: a layer of fluid heated from below and cooled from above, in the
 * Boussinesq approximation, advanced with the fixed step [[Dt]] by the scheme of the fluid model
 * (semi-Lagrangian advection, explicit diffusion, pressure projection). Lengths are in cells and
 * the depth of the layer is `ny` cells. A step allocates nothing.
 *
 * The two diffusions are set from the Prandtl number so that the larger of them is the largest
 * the explicit step allows (0.8 cells²/t at [[Dt]]), which gives the quickest dynamics; the
 * buoyancy `a = Ra ν κ / H³` then makes the Rayleigh number what is asked for.
 */
object Convection extends Model[ConvectionParams]:
  type State = ConvectionState
  val id = "convection"
  val version = "0.7.0"
  val params: ParamSchema[ConvectionParams] = ConvectionParams.schema

  /** The step the model is meant for. */
  val Dt: Double = 0.25

  val stepping: Stepping = Stepping.Fixed(Dt)

  /** Longest distance a fluid parcel may travel in one step, in cells. */
  val MaxCellsPerStep: Double = 4.0

  /** Largest `ν dt` or `κ dt` for which the explicit step is stable (the limit is 0.25). */
  val MaxDiffusionNumber: Double = 0.2

  /** The larger diffusion, in cells²/t. */
  val MaxDiffusion: Double = MaxDiffusionNumber / Dt

  val Tolerance: Double = 1e-9
  val MaxIterations: Int = 1000

  /** Kinematic viscosity `ν`, in cells²/t. */
  def viscosity(p: ConvectionParams): Double =
    if p.prandtl >= 1.0 then MaxDiffusion else MaxDiffusion * p.prandtl

  /** Thermal diffusivity `κ`, in cells²/t. */
  def diffusivity(p: ConvectionParams): Double =
    if p.prandtl >= 1.0 then MaxDiffusion / p.prandtl else MaxDiffusion

  /** The buoyancy `g α ΔT` in cells/t² that gives the Rayleigh number of `p`. */
  def acceleration(p: ConvectionParams): Double =
    val h = p.ny.toDouble
    p.rayleigh * viscosity(p) * diffusivity(p) / (h * h * h)

  override def check(p: ConvectionParams, stepping: Stepping): Either[String, Unit] =
    stepping match
      case Stepping.Fixed(dt) =>
        if MaxDiffusion * dt > MaxDiffusionNumber + 1e-12 then
          Left(
            f"the diffusions need dt <= $Dt%.2f for the explicit step; got $dt%.3f"
          )
        else Right(())
      case _: Stepping.Adaptive => Right(())

  override def supports(stepping: Stepping): Boolean = stepping match
    case _: Stepping.Fixed => true
    case _: Stepping.Adaptive => false

  def init(p: ConvectionParams, rng: SplittableRng): ConvectionState =
    val state = new ConvectionState(new MacGrid(p.nx, p.ny), p.sides)
    state.perturb(rng, p.kick)
    state

  def advance(state: ConvectionState, p: ConvectionParams, dt: Double, ctx: StepContext): Unit =
    state.step(p, dt)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: ConvectionState): Long =
    state.wall.field.digestInto(
      state.projection.pressure.digestInto(
        state.deviation.digestInto(state.v.digestInto(state.u.digestInto(Digest.start)))
      )
    )

  override def accepts(iv: Intervention): Either[String, Unit] = iv match
    case Intervention.Brush("wall" | "heat", _, _, _) => Right(())
    case Intervention.Pulse("heat", _, _, _, _) => Right(())
    case Intervention.Brush(target, _, _, _) =>
      Left(s"convection has no field '$target' to paint (use 'wall' or 'heat')")
    case Intervention.Pulse(target, _, _, _, _) =>
      Left(s"convection has no field '$target' to pulse (use 'heat')")
    case _: Intervention.Disturb => Left("convection has no agents to disturb")

  override def intervene(
      state: ConvectionState,
      p: ConvectionParams,
      iv: Intervention
  ): Either[String, Unit] =
    accepts(iv).map { _ =>
      iv match
        case Intervention.Brush(target, disc, op, value) =>
          state.applyBrush(target, disc, op, value)
        case Intervention.Pulse(_, x, y, sigma, amplitude) =>
          state.applyPulse(x, y, sigma, amplitude)
        case _ => ()
    }

  /** Kinetic energy of the flow. */
  val kineticEnergy: Probe[ConvectionState] =
    Probe[ConvectionState]("kinetic_energy", "cells⁴/t²")((s, _) => s.kineticEnergy)

  /** Largest divergence of any cell: how well the projection did. */
  val maxDivergence: Probe[ConvectionState] =
    Probe[ConvectionState]("max_divergence", "1/t")((s, _) => s.maxDivergence)

  /** Heat carried by the flow over the heat conduction would carry: 1 at rest. */
  val nusselt: Probe[ConvectionState] = Probe[ConvectionState]("nusselt")((s, _) => s.nusselt)

  /** The same from the temperature gradient at the floor. */
  val nusseltFloor: Probe[ConvectionState] =
    Probe[ConvectionState]("nusselt_floor")((s, _) => s.nusseltFloor)

  /** The same from the temperature gradient at the lid. */
  val nusseltLid: Probe[ConvectionState] =
    Probe[ConvectionState]("nusselt_lid")((s, _) => s.nusseltLid)

  /** Largest upward velocity: how hard the plumes rise. */
  val maxUp: Probe[ConvectionState] =
    Probe[ConvectionState]("max_up", "cells/t")((s, _) => s.maxUp)

  val probes: Vector[Probe[ConvectionState]] =
    Vector(kineticEnergy, maxDivergence, nusselt, nusseltFloor, nusseltLid, maxUp)
