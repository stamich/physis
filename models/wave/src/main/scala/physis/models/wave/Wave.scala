package physis.models.wave

import physis.core.{Digest, Model, Probe, StepContext, Stepping}
import physis.core.live.{Slice, StateExport}
import physis.core.param.{ParamSchema, ParamSpec}
import physis.core.random.SplittableRng
import physis.core.run.{Disc, Intervention, Op}
import physis.fields.{Boundaries, Brush, Field2D, Grid2D, Mask, Pulse, Stencils}

/**
 * Parameters of the wave equation.
 *
 * @param c         wave speed, in cells per unit time; the step is [[Wave.Dt]]
 * @param damping   uniform damping rate γ
 * @param edge      what happens at the border (needs a restart): 0 periodic, 1 reflective
 *                  (the wave is held at zero), 2 free (zero slope), 3 absorbing (a sponge layer)
 * @param sponge    width of the absorbing layer, in cells (used when `edge` is 3)
 * @param frequency frequency of the point source at the centre, in cycles per unit time
 * @param amplitude strength of the source; 0 switches it off
 * @param nx        grid columns (needs a restart)
 * @param ny        grid rows (needs a restart)
 */
final case class WaveParams(
    c: Double,
    damping: Double,
    edge: Int,
    sponge: Int,
    frequency: Double,
    amplitude: Double,
    nx: Int,
    ny: Int
)

object WaveParams:
  given schema: ParamSchema[WaveParams] = ParamSchema.derived[WaveParams](
    ParamSpec("c", default = 1.0, min = 0.05, max = 1.4, unit = "cells/t", doc = "Wave speed"),
    ParamSpec("damping", default = 0.0, min = 0.0, max = 0.2, unit = "1/t", doc = "Damping rate"),
    ParamSpec(
      "edge",
      default = 1,
      min = 0,
      max = 3,
      doc = "Border: 0 periodic, 1 reflective, 2 free, 3 absorbing",
      restart = true
    ),
    ParamSpec("sponge", default = 24, min = 4, max = 64, unit = "cells", doc = "Absorbing layer"),
    ParamSpec("frequency", default = 0.2, min = 0.01, max = 1.0, unit = "1/t", doc = "Source"),
    ParamSpec("amplitude", default = 0.0, min = 0.0, max = 2.0, doc = "Source strength (0: off)"),
    ParamSpec("nx", default = 256, min = 64, max = 1024, doc = "Grid columns", restart = true),
    ParamSpec("ny", default = 256, min = 64, max = 1024, doc = "Grid rows", restart = true)
  )

/** The four borders of [[WaveParams.edge]]. */
object Edge:
  val Periodic = 0
  val Reflective = 1
  val Free = 2
  val Absorbing = 3

/**
 * State of a wave run: the field at two time levels (swapped by reference, never copied), the
 * wall mask and the absorbing profile.
 */
final class WaveState(val grid: Grid2D, val edge: Int):
  private var now: Field2D = new Field2D(grid)
  private var before: Field2D = new Field2D(grid)

  /** Cells held at zero: rigid walls. */
  val wall: Mask = new Mask(grid)

  /** Where the point source sits: the cell containing the centre of the grid. */
  val sourceX: Int = grid.nx / 2
  val sourceY: Int = grid.ny / 2

  /** Wave speed of the latest step, which the energy needs. */
  var c: Double = 1.0

  /** Whether distances and brushes wrap around the border. */
  val periodic: Boolean = edge == Edge.Periodic

  private val boundaries: Boundaries = edge match
    case Edge.Periodic => Boundaries.periodic
    case Edge.Free => Boundaries.neumann
    case _ => Boundaries.dirichlet(0.0)

  // Quadratic ramp of the sponge, 0 in the interior and 1 at the border, in storage layout.
  private val ramp = new Array[Double](grid.storageSize)
  private var rampWidth = 0

  /** The wave at the latest time level. */
  def u: Field2D = now

  /** The wave one level earlier. */
  def previous: Field2D = before

  /** Wave value at the source cell. */
  def atSource: Double = now(sourceX, sourceY)

  /** Rebuilds the sponge ramp for a layer of `width` cells; a no-op if it is already built. */
  def setSponge(width: Int): Unit =
    if width != rampWidth then
      rampWidth = width
      val w = width.toDouble
      var j = 0
      while j < grid.ny do
        var i = 0
        while i < grid.nx do
          val d = math.min(math.min(i, grid.nx - 1 - i), math.min(j, grid.ny - 1 - j)) + 0.5
          val depth = (w - d) / w
          ramp(grid.index(i, j)) = if depth > 0.0 then depth * depth else 0.0
          i += 1
        j += 1

  /**
   * One leapfrog step of size `dt` of `u_tt + γ u_t = c² ∇²u + s(t) δ`, with
   * `γ = damping + σ(x)` where `σ` is the sponge (zero unless the edge is absorbing).
   *
   * Per cell, in this order of operations (the reference repeats it):
   * {{{
   * lap  = (((u[-1] + u[+1]) + u[-s]) + u[+s] - 4 u) / h²
   * a    = dt/2 · (damping + sponge·ramp)
   * next = (2 u - (1 - a)·previous + c² dt² · lap) / (1 + a)
   * }}}
   * then the source adds `dt² · amplitude · sin(2π f t)` at its cell, and wall cells are
   * set to zero. The old level is overwritten by the new one, then the two swap.
   */
  def step(p: WaveParams, dt: Double, time: Double): Unit =
    c = p.c
    boundaries.fill(now)
    val u = now.data
    val old = before.data
    val walls = wall.field.data
    val stride = grid.stride
    val inv = 1.0 / (grid.h * grid.h)
    val c2dt2 = p.c * p.c * dt * dt
    val half = 0.5 * dt
    val sponge =
      if edge == Edge.Absorbing then
        setSponge(p.sponge)
        3.0 * p.c * Wave.LogReflection / p.sponge
      else 0.0
    val base = p.damping
    var j = 0
    while j < grid.ny do
      var k = grid.index(0, j)
      val end = k + grid.nx
      while k < end do
        val uk = u(k)
        val lap = ((((u(k - 1) + u(k + 1)) + u(k - stride)) + u(k + stride)) - 4.0 * uk) * inv
        val a = half * (base + sponge * ramp(k))
        val next = ((2.0 * uk - (1.0 - a) * old(k)) + c2dt2 * lap) / (1.0 + a)
        old(k) = if walls(k) != 0.0 then 0.0 else next
        k += 1
      j += 1
    if p.amplitude != 0.0 then
      val k = grid.index(sourceX, sourceY)
      if walls(k) == 0.0 then
        old(k) += dt * dt * p.amplitude * Wave.sinTurns(p.frequency * time)
    val swap = now
    now = before
    before = swap

  /** Total energy of the latest two levels; conserved without damping and with a closed edge. */
  def energy: Double =
    boundaries.fill(before)
    val a = now.data
    val b = before.data
    val stride = grid.stride
    val inv = 1.0 / (grid.h * grid.h)
    var kinetic = 0.0
    var potential = 0.0
    var j = 0
    while j < grid.ny do
      var k = grid.index(0, j)
      val end = k + grid.nx
      while k < end do
        val v = (a(k) - b(k)) / dt
        kinetic += v * v
        val minusLap =
          (4.0 * b(k) - (((b(k - 1) + b(k + 1)) + b(k - stride)) + b(k + stride))) * inv
        potential += a(k) * minusLap
        k += 1
      j += 1
    0.5 * (kinetic + c * c * potential) * grid.h * grid.h

  private def dt: Double = Wave.Dt

  /** Largest absolute value of the wave. */
  def maxAbs: Double = math.max(now.max, -now.min)

  /** Sets the cells of `disc` of the wave to zero where there is a wall. */
  private def clearWalls(disc: Disc): Unit =
    val n = now.data
    val o = before.data
    val w = wall.field.data
    Brush.foreachCell(grid, disc, periodic, periodic) { (i, j) =>
      val k = grid.index(i, j)
      if w(k) != 0.0 then
        n(k) = 0.0
        o(k) = 0.0
    }

  /** Paints walls or the wave, see [[Wave.intervene]]. */
  def applyBrush(target: String, disc: Disc, op: Op, value: Double): Unit =
    target match
      case "wall" =>
        val _ = wall.paint(disc, periodic, periodic, op, value)
        clearWalls(disc)
      case _ =>
        val _ = Brush(now, disc, periodic, periodic, op, value)
        val _ = Brush(before, disc, periodic, periodic, op, value)
        clearWalls(disc)

  /** Adds a Gaussian bump to both levels, so that it starts at rest. */
  def applyPulse(x: Double, y: Double, sigma: Double, amplitude: Double): Unit =
    val _ = Pulse.stamp(now, x, y, sigma, amplitude, periodic, periodic)
    val _ = Pulse.stamp(before, x, y, sigma, amplitude, periodic, periodic)
    clearWalls(Disc(x, y, Pulse.Reach * sigma))

object WaveState:
  /** Live runs export the wave, then the wall mask, as two slices of the grid's size. */
  given stateExport: StateExport[WaveState] with
    def size(state: WaveState): Int = 2 * state.grid.cells

    def copyInto(state: WaveState, out: Array[Double]): Unit =
      state.u.copyInteriorInto(out, 0)
      state.wall.field.copyInteriorInto(out, state.grid.cells)

    override def layout(state: WaveState): IndexedSeq[Slice] =
      val g = state.grid
      Vector(Slice("u", 0, g.nx, g.ny), Slice("wall", g.cells, g.nx, g.ny))

/**
 * The damped scalar wave equation on a grid with unit cell size, advanced by the leapfrog
 * scheme (second order in space and time) on the 5-point Laplacian with the fixed step
 * [[Dt]]. Two time levels are kept and swapped; a step allocates nothing.
 *
 * Stability: with Courant number `C = c dt / h`, leapfrog needs `C² · 8 ≤ 4` (the largest
 * magnitude of the 5-point symbol is `8 / h²`), i.e. `C ≤ 1/√2`. [[check]] enforces it, so a
 * slider cannot blow a run up.
 *
 * Walls are cells held at zero (staircase geometry, first order at curved walls). The
 * absorbing edge is a sponge layer of extra damping that ramps quadratically over the
 * outermost `sponge` cells; the very border is then held at zero.
 */
object Wave extends Model[WaveParams]:
  type State = WaveState
  val id = "wave"
  val version = "0.4.0"
  val params: ParamSchema[WaveParams] = WaveParams.schema

  /** The step the model is meant for. With it `C = c / 2`, so `c ≤ 1.4` is inside the limit. */
  val Dt: Double = 0.5

  val stepping: Stepping = Stepping.Fixed(Dt)

  /** Largest Courant number for which leapfrog is stable: 1/√2. */
  val CourantLimit: Double = math.sqrt(0.5)

  /** `ln(1/R)` for the reflection amplitude R = 10⁻³ the sponge is sized for. */
  private[wave] val LogReflection: Double = 6.907755278982137

  override def check(p: WaveParams, stepping: Stepping): Either[String, Unit] =
    stepping match
      case Stepping.Fixed(dt) =>
        // Stable iff (c dt)² · max|eigenvalue of the 5-point Laplacian| <= 4.
        val worst = -Stencils.eigenvalue5(math.Pi, math.Pi)
        val limit = 4.0
        val load = p.c * p.c * dt * dt * worst
        if load <= limit * (1.0 + 1e-12) then Right(())
        else
          val courant = p.c * dt
          Left(
            f"Courant number c·dt/h = $courant%.4f exceeds the stability limit " +
              f"${CourantLimit}%.4f of the leapfrog scheme; lower c or use a smaller step"
          )
      case _: Stepping.Adaptive => Right(())

  override def supports(stepping: Stepping): Boolean = stepping match
    case _: Stepping.Fixed => true
    case _: Stepping.Adaptive => false

  def init(p: WaveParams, rng: SplittableRng): WaveState =
    val state = new WaveState(Grid2D(p.nx, p.ny), p.edge)
    state.c = p.c
    state

  def advance(state: WaveState, p: WaveParams, dt: Double, ctx: StepContext): Unit =
    state.step(p, dt, ctx.time)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: WaveState): Long =
    state.wall.field.digestInto(state.previous.digestInto(state.u.digestInto(Digest.start)))

  override def accepts(iv: Intervention): Either[String, Unit] = iv match
    case Intervention.Brush("wall" | "u", _, _, _) => Right(())
    case Intervention.Pulse("u", _, _, _, _) => Right(())
    case Intervention.Brush(target, _, _, _) =>
      Left(s"wave has no field '$target' to paint (use 'wall' or 'u')")
    case Intervention.Pulse(target, _, _, _, _) =>
      Left(s"wave has no field '$target' to pulse (use 'u')")
    case _: Intervention.Disturb => Left("wave has no agents to disturb")
    case _: Intervention.Load => Left("wave reads no files")

  override def intervene(state: WaveState, p: WaveParams, iv: Intervention): Either[String, Unit] =
    accepts(iv).map { _ =>
      iv match
        case Intervention.Brush(target, disc, op, value) =>
          state.applyBrush(target, disc, op, value)
        case Intervention.Pulse(_, x, y, sigma, amplitude) =>
          state.applyPulse(x, y, sigma, amplitude)
        case _ => ()
    }

  /**
   * `sin(2π · turns)` from a Taylor series on [-π/2, π/2] after folding, using only plain
   * arithmetic: the library sine is not guaranteed to give the same bits on every platform, and
   * the source must replay identically. Accurate to about 1e-16.
   */
  private[wave] def sinTurns(turns: Double): Double =
    var x = turns - math.floor(turns)
    if x > 0.5 then x -= 1.0
    val s = if x < 0.0 then -1.0 else 1.0
    var ax = math.abs(x)
    if ax > 0.25 then ax = 0.5 - ax
    val a = 2.0 * math.Pi * ax
    val a2 = a * a
    var term = a
    var sum = a
    var k = 1
    while k <= 10 do
      term = -term * a2 / ((2 * k) * (2 * k + 1).toDouble)
      sum += term
      k += 1
    s * sum

  /** Total energy of the wave. */
  val energy: Probe[WaveState] = Probe[WaveState]("energy", "1")((s, _) => s.energy)

  /** Largest absolute wave value. */
  val maxAbs: Probe[WaveState] = Probe[WaveState]("max_abs")((s, _) => s.maxAbs)

  /** Mean wave value; constant on a closed grid without damping, forced otherwise. */
  val mean: Probe[WaveState] = Probe[WaveState]("mean")((s, _) => s.u.mean)

  /** Wave value at the source cell. */
  val source: Probe[WaveState] = Probe[WaveState]("source")((s, _) => s.atSource)

  val probes: Vector[Probe[WaveState]] = Vector(energy, maxAbs, mean, source)
