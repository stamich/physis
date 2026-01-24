package physis.ode

import physis.core.{Digest, Model, StepContext, Stepping}
import physis.core.param.ParamSchema
import physis.core.random.SplittableRng

/** Turns an [[OdeSystem]] into a core [[Model]]. */
object OdeModel:

  /**
   * An ODE model integrated with RK4 under [[Stepping.Fixed]] and with Dormand-Prince 5(4)
   * under [[Stepping.Adaptive]]. The integrator follows from the run's stepping policy, so a
   * manifest's stepping fully determines how it is replayed.
   *
   * @param initial initial state vector for given parameters; may use the seeded `rng`
   */
  def apply[P](
      id: String,
      version: String,
      system: OdeSystem[P],
      params: ParamSchema[P],
      initial: (P, SplittableRng) => Array[Double]
  ): Model.Aux[P, OdeState] =
    new OdeSystemModel[P](id, version, system, params, initial)

private final class OdeSystemModel[P](
    val id: String,
    val version: String,
    system: OdeSystem[P],
    val params: ParamSchema[P],
    initial: (P, SplittableRng) => Array[Double]
) extends Model[P]:
  type State = OdeState

  private val rk4 = Rk4(system)
  private val dopri = new DormandPrince54(system)

  override def supports(stepping: Stepping): Boolean = true

  def init(p: P, rng: SplittableRng): OdeState =
    val y0 = initial(p, rng)
    require(
      y0.length == system.dimension,
      s"model '$id': initial state has ${y0.length} components, system has ${system.dimension}"
    )
    new OdeState(y0.clone())

  def advance(state: OdeState, p: P, dt: Double, ctx: StepContext): Unit =
    if ctx.adaptive then
      ctx.taken = dopri.step(ctx.time, state.y, p, dt, ctx.rtol, ctx.atol)
      ctx.suggestedNext = dopri.lastSuggested
      ctx.rejected += dopri.lastRejected
    else
      rk4.step(ctx.time, state.y, p, dt)
      ctx.taken = dt
      ctx.suggestedNext = dt

  def digest(state: OdeState): Long = Digest.ofDoubles(state.y)
