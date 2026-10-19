package physis.core

import physis.core.param.ParamSchema
import physis.core.random.SplittableRng

/**
 * The simplest way to write a model: an immutable state and a pure step function.
 *
 * Good for small models and teaching. It allocates a new state every step, so performance-
 * sensitive models implement [[Model]] directly; [[Simulation.asModel]] lets both run through
 * the same engine.
 */
trait Simulation[S]:
  def initial: S
  def step(s: S, dt: Double): S

object Simulation:
  /** Mutable holder that turns an immutable state into an engine-owned one. */
  final class Box[S](var value: S)

  /**
   * Adapts a pure simulation to a [[Model]] without parameters and with fixed steps.
   *
   * @param fingerprint bit-exact fingerprint of a state, used for reproducibility checks
   */
  def asModel[S](
      id: String,
      version: String,
      simulation: Simulation[S],
      fingerprint: S => Long
  ): Model.Aux[Unit, Box[S]] =
    val modelId = id
    val modelVersion = version
    new Model[Unit]:
      type State = Box[S]
      val id: String = modelId
      val version: String = modelVersion
      val params: ParamSchema[Unit] = ParamSchema.unit

      def init(p: Unit, rng: SplittableRng): Box[S] = Box(simulation.initial)

      def advance(state: Box[S], p: Unit, dt: Double, ctx: StepContext): Unit =
        state.value = simulation.step(state.value, dt)
        ctx.taken = dt
        ctx.suggestedNext = dt

      def digest(state: Box[S]): Long = fingerprint(state.value)
