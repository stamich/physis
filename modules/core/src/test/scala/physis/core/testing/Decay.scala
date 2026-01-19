package physis.core.testing

import physis.core.{Digest, Model, StepContext}
import physis.core.param.{ParamSchema, ParamSpec}
import physis.core.random.SplittableRng

/** Exponential decay y' = -k y, advanced with the exact solution: a model with no numerical error. */
final case class DecayParams(k: Double, y0: Double)

object DecayParams:
  given schema: ParamSchema[DecayParams] = ParamSchema.derived[DecayParams](
    ParamSpec("k", default = 1.0, min = 0.0, max = 100.0, unit = "1/s"),
    ParamSpec("y0", default = 1.0, min = -1e6, max = 1e6)
  )

final class DecayState(var y: Double)

/** Fixed-step test model. */
object Decay extends Model[DecayParams]:
  type State = DecayState
  val id = "decay"
  val version = "1"
  val params: ParamSchema[DecayParams] = DecayParams.schema

  def init(p: DecayParams, rng: SplittableRng): DecayState = DecayState(p.y0)

  def advance(state: DecayState, p: DecayParams, dt: Double, ctx: StepContext): Unit =
    state.y *= math.exp(-p.k * dt)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: DecayState): Long = Digest.ofDoubles(state.y)

  def exact(p: DecayParams, t: Double): Double = p.y0 * math.exp(-p.k * t)

/**
 * Adaptive test model: never accepts a step longer than `maxAccepted`, counting each halving as
 * a rejection, and suggests growing the step by 1.5x.
 */
final class CappedDecay(maxAccepted: Double) extends Model[DecayParams]:
  type State = DecayState
  val id = "capped-decay"
  val version = "1"
  val params: ParamSchema[DecayParams] = DecayParams.schema

  override def supports(stepping: physis.core.Stepping): Boolean = true

  def init(p: DecayParams, rng: SplittableRng): DecayState = DecayState(p.y0)

  def advance(state: DecayState, p: DecayParams, dt: Double, ctx: StepContext): Unit =
    var h = dt
    var rejected = 0
    while h > maxAccepted do
      h /= 2
      rejected += 1
    state.y *= math.exp(-p.k * h)
    ctx.taken = h
    ctx.rejected += rejected
    ctx.suggestedNext = h * 1.5

  def digest(state: DecayState): Long = Digest.ofDoubles(state.y)
