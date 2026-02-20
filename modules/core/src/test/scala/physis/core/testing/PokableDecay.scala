package physis.core.testing

import physis.core.Model
import physis.core.param.ParamSchema
import physis.core.random.SplittableRng
import physis.core.run.{Intervention, Op}
import physis.core.StepContext

/**
 * Decay with interventions: a brush on target "y" assigns, adds or erases the value, so tests
 * can see when an edit lands relative to steps and parameter changes.
 */
object PokableDecay extends Model[DecayParams]:
  type State = DecayState
  val id = "pokable-decay"
  val version = "1"
  val params: ParamSchema[DecayParams] = DecayParams.schema

  def init(p: DecayParams, rng: SplittableRng): DecayState = Decay.init(p, rng)

  def advance(state: DecayState, p: DecayParams, dt: Double, ctx: StepContext): Unit =
    Decay.advance(state, p, dt, ctx)

  def digest(state: DecayState): Long = Decay.digest(state)

  override def accepts(iv: Intervention): Either[String, Unit] = iv match
    case Intervention.Brush("y", _, _, _) => Right(())
    case other => Left(s"pokable-decay cannot take $other")

  override def intervene(
      state: DecayState,
      p: DecayParams,
      iv: Intervention
  ): Either[String, Unit] =
    iv match
      case Intervention.Brush("y", _, op, value) =>
        op match
          case Op.Assign => state.y = value
          case Op.Add => state.y += value
          case Op.Erase => state.y = 0.0
        Right(())
      case other => Left(s"pokable-decay cannot take $other")
