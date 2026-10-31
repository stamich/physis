package physis.models.fluid

import physis.agents.ParticleSet
import physis.core.{Digest, Model, Probe, StepContext, Stepping}
import physis.core.live.{Slice, StateExport}
import physis.core.param.{ParamSchema, ParamSpec}
import physis.core.random.SplittableRng
import physis.core.run.{Disc, Intervention, Op}
import physis.fields.{
  Advect,
  Boundaries,
  Brush,
  Diffuse,
  Field2D,
  Mac,
  MacGrid,
  Mask,
  Projection,
  Pulse,
  Sampler
}

/**
 * Parameters of the flow.
 *
 * @param viscosity    kinematic viscosity ν, in cells² per unit time
 * @param dyeDiffusion diffusion of the dye, in cells² per unit time
 * @param buoyancy     upward acceleration per unit of dye (smoke rises when positive)
 * @param lid          speed of the top wall of the closed box, in cells per unit time
 * @param inflow       speed of the flow entering a channel from the left
 * @param edge         the border (needs a restart): 0 periodic, 1 closed box, 2 channel
 * @param nx           grid columns (needs a restart)
 * @param ny           grid rows (needs a restart)
 * @param tracers      particles carried by the flow, to show where it goes (needs a restart)
 */
final case class FluidParams(
    viscosity: Double,
    dyeDiffusion: Double,
    buoyancy: Double,
    lid: Double,
    inflow: Double,
    edge: Int,
    nx: Int,
    ny: Int,
    tracers: Int
)

object FluidParams:
  given schema: ParamSchema[FluidParams] = ParamSchema.derived[FluidParams](
    ParamSpec(
      "viscosity",
      default = 0.1,
      min = 0.0,
      max = 0.8,
      unit = "cells²/t",
      doc = "Kinematic viscosity"
    ),
    ParamSpec(
      "dyeDiffusion",
      default = 0.0,
      min = 0.0,
      max = 0.8,
      unit = "cells²/t",
      doc = "Diffusion of the dye"
    ),
    ParamSpec(
      "buoyancy",
      default = 0.0,
      min = -4.0,
      max = 4.0,
      unit = "cells/t²",
      doc = "Lift of the dye (smoke rises when positive)"
    ),
    ParamSpec("lid", default = 0.0, min = -2.0, max = 2.0, unit = "cells/t", doc = "Top lid speed"),
    ParamSpec(
      "inflow",
      default = 0.0,
      min = 0.0,
      max = 2.0,
      unit = "cells/t",
      doc = "Speed of the flow entering a channel"
    ),
    ParamSpec(
      "edge",
      default = 1,
      min = 0,
      max = 2,
      doc = "Border: 0 periodic, 1 closed box, 2 channel (inflow left, open right)",
      restart = true
    ),
    ParamSpec("nx", default = 128, min = 16, max = 512, doc = "Grid columns", restart = true),
    ParamSpec("ny", default = 128, min = 16, max = 512, doc = "Grid rows", restart = true),
    ParamSpec(
      "tracers",
      default = 0,
      min = 0,
      max = 20000,
      doc = "Particles carried by the flow",
      restart = true
    )
  )

/** The borders of [[FluidParams.edge]]. */
object FluidEdge:
  val Periodic = 0
  val Box = 1
  val Channel = 2

/**
 * State of a flow: the velocity on the staggered grid and the dye at the cell centres, each in
 * two copies that take turns as source and destination of the advection (swapped by reference,
 * never copied), the wall mask and the projection with its pressure.
 */
final class FluidState(val mac: MacGrid, val edge: Int, tracerCount: Int):
  private val nx = mac.nx
  private val ny = mac.ny

  /** Whether the domain is periodic (both axes). */
  val periodic: Boolean = edge == FluidEdge.Periodic

  /** Whether the right border is open. */
  val outflow: Boolean = edge == FluidEdge.Channel

  private val us = Array(new Field2D(mac.uFaces), new Field2D(mac.uFaces))
  private val vs = Array(new Field2D(mac.vFaces), new Field2D(mac.vFaces))
  private val ds = Array(new Field2D(mac.cells), new Field2D(mac.cells))
  private val flows = Array(
    new Advect.Flow(us(0), vs(0), nx, ny, periodic, periodic),
    new Advect.Flow(us(1), vs(1), nx, ny, periodic, periodic)
  )
  private var cur = 0
  private val scratchU = new Field2D(mac.uFaces)
  private val scratchV = new Field2D(mac.vFaces)
  private val scratchC = new Field2D(mac.cells)

  /** Cells that are solid: the flow goes around them and the dye is removed. */
  val wall: Mask = new Mask(mac.cells)

  /** The projection, which owns the pressure. */
  val projection: Projection = new Projection(mac, periodic, periodic, outflow)

  /** The tracer particles (columns `x` and `y`, in cells), or none. */
  val particles: Option[ParticleSet] =
    if tracerCount > 0 then Some(ParticleSet(tracerCount, "x", "y")) else None

  private val tx: Array[Double] = particles.fold(new Array[Double](0))(_.column("x"))
  private val ty: Array[Double] = particles.fold(new Array[Double](0))(_.column("y"))

  /** Solver iterations of the latest step. */
  var iterations: Int = 0

  projection.configure(wall.field)

  /** The horizontal velocity, on the vertical faces. */
  def u: Field2D = us(cur)

  /** The vertical velocity, on the horizontal faces. */
  def v: Field2D = vs(cur)

  /** The dye, at the cell centres. */
  def dye: Field2D = ds(cur)

  /** Call after the wall mask changed, so that the projection sees the new geometry. */
  def wallsChanged(): Unit = projection.configure(wall.field)

  /** Closed faces to rest, then the inflow of a channel on its left border. */
  private def applyBoundaries(p: FluidParams): Unit =
    projection.closeFaces(u, v)
    if edge == FluidEdge.Channel then
      val f = u
      var j = 0
      while j < ny do
        if !wall(0, j) then f(0, j) = p.inflow
        j += 1

  /** Accelerates the vertical velocity where there is dye: `v += dt · buoyancy · dye`. */
  private def lift(p: FluidParams, dt: Double): Unit =
    if p.buoyancy != 0.0 then
      val f = v
      val d = dye
      val open = projection.poisson.openV
      val gain = 0.5 * dt * p.buoyancy
      var j = 0
      while j <= ny do
        val below = if j > 0 then j - 1 else ny - 1
        val above = if j < ny then j else 0
        var i = 0
        while i < nx do
          if open(i, j) != 0.0 then f(i, j) += gain * (d(i, below) + d(i, above))
          i += 1
        j += 1

  /** Keeps every face velocity within `limit`. */
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

  /**
   * Viscous diffusion of the velocity by one explicit step, `u += ν dt ∇²u`, with the no-slip
   * wall: beyond a wall the velocity is mirrored, `2 · wall - u`, where the wall moves with 0
   * or, for the top of a closed box, with the lid.
   */
  private def diffuseVelocity(p: FluidParams, dt: Double): Unit =
    val s = p.viscosity * dt
    if s > 0.0 then
      val uf = u
      val vf = v
      val lid = if edge == FluidEdge.Box then p.lid else 0.0
      var j = 0
      while j < ny do
        if periodic then
          uf(-1, j) = uf(nx - 1, j)
          uf(nx + 1, j) = uf(1, j)
        else if outflow then uf(nx + 1, j) = uf(nx, j)
        j += 1
      var i = 0
      while i <= nx do
        if periodic then
          uf(i, -1) = uf(i, ny - 1)
          uf(i, ny) = uf(i, 0)
        else
          uf(i, -1) = -uf(i, 0)
          uf(i, ny) = 2.0 * lid - uf(i, ny - 1)
        i += 1
      j = 0
      while j <= ny do
        if periodic then
          vf(-1, j) = vf(nx - 1, j)
          vf(nx, j) = vf(0, j)
        else
          vf(-1, j) = -vf(0, j)
          vf(nx, j) = if outflow then vf(nx - 1, j) else -vf(nx - 1, j)
        j += 1
      if periodic then
        i = 0
        while i < nx do
          vf(i, -1) = vf(i, ny - 1)
          vf(i, ny + 1) = vf(i, 1)
          i += 1
      Diffuse.stepOpen(uf, scratchU, projection.poisson.openU, s)
      Diffuse.stepOpen(vf, scratchV, projection.poisson.openV, s)
      uf.copyFrom(scratchU)
      vf.copyFrom(scratchV)

  /** Diffusion of the dye, `d += κ dt ∇²d`, with no flux through the border. */
  private def diffuseDye(p: FluidParams, dt: Double): Unit =
    val s = p.dyeDiffusion * dt
    if s > 0.0 then
      val d = dye
      (if periodic then Boundaries.periodic else Boundaries.neumann).fill(d)
      Diffuse.step(d, scratchC, s)
      d.copyFrom(scratchC)

  /** Removes the dye inside walls. */
  private def clearDyeInWalls(): Unit =
    if wall.count > 0 then
      val d = dye
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          if wall(i, j) then d(i, j) = 0.0
          i += 1
        j += 1

  /**
   * One step of size `dt`: forces (buoyancy), advection of velocity and dye, viscosity, then the
   * projection that makes the velocity free of divergence. The velocity is first limited to
   * [[Fluid.MaxCellsPerStep]] cells per step, so that a hand-painted burst cannot throw the
   * backtrace across the whole grid.
   */
  def step(p: FluidParams, dt: Double, stepNumber: Long): Unit =
    applyBoundaries(p)
    lift(p, dt)
    val limit = Fluid.MaxCellsPerStep / dt
    clamp(u, limit)
    clamp(v, limit)
    val from = cur
    val to = 1 - cur
    val flow = flows(from)
    Advect.facesU(flow, us(from), us(to), dt)
    Advect.facesV(flow, vs(from), vs(to), dt)
    Advect.cells(flow, ds(from), ds(to), dt)
    cur = to
    applyBoundaries(p)
    diffuseVelocity(p, dt)
    diffuseDye(p, dt)
    applyBoundaries(p)
    iterations = projection.project(u, v, Fluid.Tolerance, Fluid.MaxIterations)
    clearDyeInWalls()
    moveTracers(dt, stepNumber)

  /**
   * Paints walls or dye, see [[Fluid.intervene]]. New walls stop the flow through their faces at
   * once and remove the dye inside; erased walls leave the fluid at rest where they were.
   */
  def applyBrush(target: String, disc: Disc, op: Op, value: Double): Unit =
    target match
      case "wall" =>
        val _ = wall.paint(disc, periodic, periodic, op, value)
        wallsChanged()
        projection.closeFaces(u, v)
        clearDyeInWalls()
      case _ =>
        val _ = Brush(dye, disc, periodic, periodic, op, value)
        clearDyeInWalls()

  /**
   * Adds a Gaussian impulse of the horizontal (`"u"`) or vertical (`"v"`) velocity, or a puff of
   * dye (`"dye"`), centred at `(x, y)` in cell coordinates. The impulse is not free of
   * divergence; the next projection removes the part that is not a swirl.
   */
  def applyPulse(target: String, x: Double, y: Double, sigma: Double, amplitude: Double): Unit =
    target match
      case "u" => stampFaces(u, projection.poisson.openU, 0.0, 0.5, x, y, sigma, amplitude)
      case "v" => stampFaces(v, projection.poisson.openV, 0.5, 0.0, x, y, sigma, amplitude)
      case _ =>
        val _ = Pulse.stamp(dye, x, y, sigma, amplitude, periodic, periodic)
        clearDyeInWalls()

  /**
   * Adds the Gaussian to the open faces of `f`, whose samples sit at `(i + ox, j + oy)`. A face
   * is stamped with its own cell; the face on the far border is stamped with the last cell, and
   * on a periodic axis it is a copy of the first.
   */
  private def stampFaces(
      f: Field2D,
      open: Field2D,
      ox: Double,
      oy: Double,
      x: Double,
      y: Double,
      sigma: Double,
      amplitude: Double
  ): Unit =
    val k = -1.0 / (2.0 * sigma * sigma)
    val lastColumn = ox == 0.0
    Brush.foreachCellAt(mac.cells, x, y, Pulse.Reach * sigma, periodic, periodic) { (i, j) =>
      var dx = i + ox - x
      var dy = j + oy - y
      if periodic then
        dx -= nx * math.rint(dx / nx)
        dy -= ny * math.rint(dy / ny)
      if open(i, j) != 0.0 then f(i, j) += amplitude * math.exp(k * (dx * dx + dy * dy))
      if !periodic then
        if lastColumn && i == nx - 1 && open(nx, j) != 0.0 then
          val ex = nx - x
          f(nx, j) += amplitude * math.exp(k * (ex * ex + dy * dy))
        if !lastColumn && j == ny - 1 && open(i, ny) != 0.0 then
          val ey = ny - y
          f(i, ny) += amplitude * math.exp(k * (dx * dx + ey * ey))
    }
    if periodic then
      var a = 0
      while a < ny do
        u(nx, a) = u(0, a)
        a += 1
      var b = 0
      while b < nx do
        v(b, ny) = v(b, 0)
        b += 1

  /** Writes the vorticity `∂v/∂x - ∂u/∂y` at the cell centres, row by row, from `offset`. */
  def vorticityInto(out: Array[Double], offset: Int): Unit =
    Mac.vorticity(u, v, scratchC, periodic, periodic)
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

  /** A number in [0, 1) that depends only on `a`: the finaliser of splitmix64, plain arithmetic. */
  private def unit(a: Long): Double =
    var z = a + 0x9e3779b97f4a7c15L
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z = z ^ (z >>> 31)
    (z >>> 11).toDouble * (1.0 / 9007199254740992.0)

  /** Puts tracer `k` at a place that depends on `k` and the step only. */
  private def respawn(k: Int, stepNumber: Long): Unit =
    val base = stepNumber * 1000003L + k.toLong * 2L
    // the inflow of a channel feeds in at its left end; elsewhere a lost tracer starts anew
    val width = if outflow then 2.0 else nx.toDouble
    tx(k) = unit(base) * width
    ty(k) = unit(base + 1L) * ny

  private def solidAt(x: Double, y: Double): Boolean =
    wall(math.min(nx - 1, math.max(0, x.toInt)), math.min(ny - 1, math.max(0, y.toInt)))

  /**
   * Carries the tracers with the flow by a midpoint step. One that leaves the open end of a
   * channel, or sits in a wall, starts anew; one that would enter a wall stays where it is.
   */
  private def moveTracers(dt: Double, stepNumber: Long): Unit =
    val f = u
    val g = v
    var k = 0
    while k < tx.length do
      val x = tx(k)
      val y = ty(k)
      if solidAt(x, y) then respawn(k, stepNumber)
      else
        val mx = x + 0.5 * dt * Sampler.bilinearAt(f, x, y, 0.0, 0.5, nx, ny, periodic, periodic)
        val my = y + 0.5 * dt * Sampler.bilinearAt(g, x, y, 0.5, 0.0, nx, ny, periodic, periodic)
        var px = x + dt * Sampler.bilinearAt(f, mx, my, 0.0, 0.5, nx, ny, periodic, periodic)
        var py = y + dt * Sampler.bilinearAt(g, mx, my, 0.5, 0.0, nx, ny, periodic, periodic)
        if periodic then
          px -= nx * math.floor(px / nx)
          py -= ny * math.floor(py / ny)
        if outflow && px >= nx then respawn(k, stepNumber)
        else
          if !periodic then
            px = math.min(math.max(px, 0.0), nx - 1e-6)
            py = math.min(math.max(py, 0.0), ny - 1e-6)
          if !solidAt(px, py) then
            tx(k) = px
            ty(k) = py
      k += 1

  /** Scatters the tracers over the grid. */
  def seedTracers(rng: SplittableRng): Unit =
    var k = 0
    while k < tx.length do
      tx(k) = rng.nextDouble() * nx
      ty(k) = rng.nextDouble() * ny
      k += 1

  /** Kinetic energy `½ ∫ |velocity|²`. */
  def kineticEnergy: Double = Mac.kineticEnergy(u, v, mac.cells)

  /** Enstrophy `½ ∫ ω²`, with `ω` the vorticity. */
  def enstrophy: Double =
    Mac.vorticity(u, v, scratchC, periodic, periodic)
    var sum = 0.0
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        sum += scratchC(i, j) * scratchC(i, j)
        i += 1
      j += 1
    0.5 * sum * mac.cells.h * mac.cells.h

  /** Largest divergence of any cell. */
  def maxDivergence: Double = projection.maxDivergence(u, v)

  /** Total amount of dye. */
  def dyeMass: Double = dye.sum * mac.cells.h * mac.cells.h

  /**
   * The vertical velocity at the middle of the grid, the wake probe: behind an obstacle on the
   * left, a steady wake leaves it at 0 and a street of vortices makes it swing to and fro.
   */
  def shed: Double =
    val i = nx / 2
    val j = ny / 2
    0.5 * (v(i, j) + v(i, j + 1))

object FluidState:
  /** Live runs export the dye, the vorticity, the speed and the wall mask, each of the grid's size. */
  given stateExport: StateExport[FluidState] with
    def size(state: FluidState): Int = 4 * state.mac.cells.cells + state.particles.fold(0)(_.size)

    def copyInto(state: FluidState, out: Array[Double]): Unit =
      val n = state.mac.cells.cells
      state.dye.copyInteriorInto(out, 0)
      state.vorticityInto(out, n)
      state.speedInto(out, 2 * n)
      state.wall.field.copyInteriorInto(out, 3 * n)
      state.particles.foreach(_.copyInto(out, 4 * n))

    override def layout(state: FluidState): IndexedSeq[Slice] =
      val g = state.mac.cells
      val n = g.cells
      Vector(
        Slice("dye", 0, g.nx, g.ny),
        Slice("vorticity", n, g.nx, g.ny),
        Slice("speed", 2 * n, g.nx, g.ny),
        Slice("wall", 3 * n, g.nx, g.ny)
      ) ++ state.particles.fold(Vector.empty[Slice])(_.slices(4 * n))

/**
 * Incompressible flow on a staggered (MAC) grid with unit cells, advanced with the fixed step
 * [[Dt]] by the stable-fluids scheme: semi-Lagrangian advection (second-order backtrace),
 * explicit viscosity and a pressure projection solved by conjugate gradients. A step allocates
 * nothing. The scheme is stable at any speed; the price is numerical diffusion, about
 * `|u| h / 2`, so a flow with a small Reynolds number is represented much better than one
 * with a large number.
 */
object Fluid extends Model[FluidParams]:
  type State = FluidState
  val id = "fluid"
  val version = "0.6.0"
  val params: ParamSchema[FluidParams] = FluidParams.schema

  /** The step the model is meant for. */
  val Dt: Double = 0.25

  val stepping: Stepping = Stepping.Fixed(Dt)

  /** Longest distance a fluid parcel may travel in one step, in cells. */
  val MaxCellsPerStep: Double = 4.0

  /** Largest `ν dt` for which the explicit viscosity is stable (the limit is 0.25). */
  val MaxDiffusionNumber: Double = 0.2

  /** Root-mean-square residual of the pressure equation the projection stops at. */
  val Tolerance: Double = 1e-9

  /** Solver iterations after which a projection gives up. */
  val MaxIterations: Int = 1000

  override def check(p: FluidParams, stepping: Stepping): Either[String, Unit] =
    stepping match
      case Stepping.Fixed(dt) =>
        if p.viscosity * dt > MaxDiffusionNumber then
          Left(
            f"viscosity·dt = ${p.viscosity * dt}%.3f exceeds $MaxDiffusionNumber%.1f, the limit of " +
              "the explicit viscous step; lower the viscosity or use a smaller step"
          )
        else if p.dyeDiffusion * dt > MaxDiffusionNumber then
          Left(
            f"dyeDiffusion·dt = ${p.dyeDiffusion * dt}%.3f exceeds $MaxDiffusionNumber%.1f, the " +
              "limit of the explicit diffusion step"
          )
        else Right(())
      case _: Stepping.Adaptive => Right(())

  override def supports(stepping: Stepping): Boolean = stepping match
    case _: Stepping.Fixed => true
    case _: Stepping.Adaptive => false

  def init(p: FluidParams, rng: SplittableRng): FluidState =
    val state = new FluidState(new MacGrid(p.nx, p.ny), p.edge, p.tracers)
    state.seedTracers(rng)
    state

  def advance(state: FluidState, p: FluidParams, dt: Double, ctx: StepContext): Unit =
    state.step(p, dt, ctx.step)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: FluidState): Long =
    val fields = state.wall.field.digestInto(
      state.projection.pressure.digestInto(
        state.dye.digestInto(state.v.digestInto(state.u.digestInto(Digest.start)))
      )
    )
    state.particles.fold(fields) { ps =>
      Digest.fold(Digest.fold(fields, ps.columnAt(0), 0, ps.count), ps.columnAt(1), 0, ps.count)
    }

  override def accepts(iv: Intervention): Either[String, Unit] = iv match
    case Intervention.Brush("wall" | "dye", _, _, _) => Right(())
    case Intervention.Pulse("u" | "v" | "dye", _, _, _, _) => Right(())
    case Intervention.Brush(target, _, _, _) =>
      Left(s"fluid has no field '$target' to paint (use 'wall' or 'dye')")
    case Intervention.Pulse(target, _, _, _, _) =>
      Left(s"fluid has no field '$target' to pulse (use 'u', 'v' or 'dye')")
    case _: Intervention.Disturb => Left("fluid has no agents to disturb")
    case _: Intervention.Load => Left("fluid reads no files")

  override def intervene(
      state: FluidState,
      p: FluidParams,
      iv: Intervention
  ): Either[String, Unit] =
    accepts(iv).map { _ =>
      iv match
        case Intervention.Brush(target, disc, op, value) =>
          state.applyBrush(target, disc, op, value)
        case Intervention.Pulse(target, x, y, sigma, amplitude) =>
          state.applyPulse(target, x, y, sigma, amplitude)
        case _ => ()
    }

  /** Kinetic energy of the flow. */
  val kineticEnergy: Probe[FluidState] =
    Probe[FluidState]("kinetic_energy", "cells⁴/t²")((s, _) => s.kineticEnergy)

  /** Largest divergence of any cell: how well the projection did. */
  val maxDivergence: Probe[FluidState] =
    Probe[FluidState]("max_divergence", "1/t")((s, _) => s.maxDivergence)

  /** Enstrophy: how much the flow swirls. */
  val enstrophy: Probe[FluidState] =
    Probe[FluidState]("enstrophy", "cells²/t²")((s, _) => s.enstrophy)

  /** Total amount of dye. */
  val dyeMass: Probe[FluidState] = Probe[FluidState]("dye_mass")((s, _) => s.dyeMass)

  /** Vertical velocity at the middle of the grid: it swings when vortices are shed. */
  val shedding: Probe[FluidState] = Probe[FluidState]("shedding", "cells/t")((s, _) => s.shed)

  val probes: Vector[Probe[FluidState]] =
    Vector(kineticEnergy, maxDivergence, enstrophy, dyeMass, shedding)
