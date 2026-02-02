package physis.fx

import physis.core.{Digest, Model, StepContext}
import physis.core.live.StateExport
import physis.core.param.{ParamSchema, ParamSpec, Scale}
import physis.core.random.SplittableRng

/** Test model: a point rotating at angular speed `omega` on a circle of radius `r`. */
final case class SpinParams(omega: Double, r: Double, n: Int, on: Boolean)

object SpinParams:
  given schema: ParamSchema[SpinParams] = ParamSchema.derived[SpinParams](
    ParamSpec("omega", default = 1.0, min = 0.0, max = 100.0, unit = "rad/s", doc = "Speed"),
    ParamSpec("r", default = 1.0, min = 1e-3, max = 1e3, unit = "m", scale = Scale.Log),
    ParamSpec("n", default = 3.0, min = 1.0, max = 10.0),
    ParamSpec("on", default = 1.0, min = 0.0, max = 1.0)
  )

final class SpinState(var angle: Double, var r: Double)

object SpinState:
  given StateExport[SpinState] with
    def size(state: SpinState): Int = 3
    def copyInto(state: SpinState, out: Array[Double]): Unit =
      out(0) = state.r * math.cos(state.angle)
      out(1) = state.r * math.sin(state.angle)
      out(2) = state.angle

object Spin extends Model[SpinParams]:
  type State = SpinState
  val id = "spin"
  val version = "1"
  val params: ParamSchema[SpinParams] = SpinParams.schema

  def init(p: SpinParams, rng: SplittableRng): SpinState = new SpinState(0.0, p.r)

  def advance(state: SpinState, p: SpinParams, dt: Double, ctx: StepContext): Unit =
    state.angle += p.omega * dt
    state.r = p.r
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: SpinState): Long = Digest.ofDoubles(state.angle, state.r)
