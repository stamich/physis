package physis.ode

import physis.core.{Digest, Model, StepContext}
import physis.core.param.ParamSchema
import physis.core.random.SplittableRng

/**
 * A second-order system whose acceleration depends on positions only: d^2q/dt^2 = a(q; p).
 *
 * Its state vector is `y = (q_0 .. q_{dof-1}, v_0 .. v_{dof-1})`. Systems of this kind (gravity,
 * springs, potentials) have a symplectic integrator, [[VelocityVerlet]], whose energy error stays
 * bounded over long runs instead of drifting.
 *
 * Systems whose acceleration depends on velocities (the double pendulum, friction) do not fit:
 * use [[OdeSystem]] with RK4 or Dormand-Prince instead.
 */
trait SeparableSystem[P]:
  /** Degrees of freedom: number of position coordinates. */
  def dof: Int

  /**
   * Writes a(q; p) into `a`. Reads only `q(0 until dof)`; `q` may be the full state vector.
   * Must not allocate and must not modify `q`.
   */
  def acceleration(q: Array[Double], p: P, a: Array[Double]): Unit

/**
 * Velocity Verlet (kick-drift-kick) step: second order, symplectic, time-reversible.
 *
 * Reuses the acceleration from the end of the previous step when positions are unchanged, so
 * each step costs one acceleration evaluation. Allocates nothing per step. Not thread-safe.
 */
final class VelocityVerlet[P](system: SeparableSystem[P]):
  private val d = system.dof
  private val a = new Array[Double](d)
  private val cachedQ = new Array[Double](d)
  private var cacheValid = false

  /** Advances `y = (q, v)` in place by `h`. */
  def step(y: Array[Double], p: P, h: Double): Unit =
    if !(cacheValid && positionsCached(y)) then system.acceleration(y, p, a)
    val half = 0.5 * h
    var i = 0
    while i < d do
      y(d + i) = y(d + i) + half * a(i)
      y(i) = y(i) + h * y(d + i)
      i += 1
    system.acceleration(y, p, a)
    i = 0
    while i < d do
      y(d + i) = y(d + i) + half * a(i)
      cachedQ(i) = y(i)
      i += 1
    cacheValid = true

  private def positionsCached(y: Array[Double]): Boolean =
    var i = 0
    var same = true
    while same && i < d do
      same = y(i) == cachedQ(i)
      i += 1
    same

/** Turns a [[SeparableSystem]] into a core [[Model]] integrated with [[VelocityVerlet]]. */
object SeparableModel:

  /**
   * A model with fixed steps only (a symplectic integrator loses its structure under adaptive
   * step sizes).
   *
   * @param initial initial `(q, v)` vector of length `2 * dof`
   */
  def apply[P](
      id: String,
      version: String,
      system: SeparableSystem[P],
      params: ParamSchema[P],
      initial: (P, SplittableRng) => Array[Double]
  ): Model.Aux[P, OdeState] =
    new SeparableSystemModel[P](id, version, system, params, initial)

private final class SeparableSystemModel[P](
    val id: String,
    val version: String,
    system: SeparableSystem[P],
    val params: ParamSchema[P],
    initial: (P, SplittableRng) => Array[Double]
) extends Model[P]:
  type State = OdeState

  private val verlet = new VelocityVerlet(system)

  def init(p: P, rng: SplittableRng): OdeState =
    val y0 = initial(p, rng)
    require(
      y0.length == 2 * system.dof,
      s"model '$id': initial state has ${y0.length} components, expected ${2 * system.dof}"
    )
    new OdeState(y0.clone())

  def advance(state: OdeState, p: P, dt: Double, ctx: StepContext): Unit =
    verlet.step(state.y, p, dt)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: OdeState): Long = Digest.ofDoubles(state.y)
