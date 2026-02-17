package physis.lab

import physis.core.{Model, Probe, Stepping}
import physis.core.live.{LiveRun, StateExport}
import physis.core.param.{ParamSchema, Preset}
import physis.core.run.{ModelRegistry, Physis}
import physis.fx.RunControls
import physis.models.boids.{Boids, BoidsParams, BoidsState, BoidsViews}
import physis.models.grayscott.{GrayScott, GrayScottParams, GrayScottState, GrayScottViews}
import physis.models.lorenz.{Lorenz, LorenzParams, LorenzViews}
import physis.models.pendulum.{Pendulum, PendulumParams, PendulumViews}
import physis.ode.OdeState
import physis.view.View

/**
 * The speeds a model can run at, in simulated time per second.
 *
 * @param min     the slowest speed of the slider
 * @param max     the fastest speed of the slider
 * @param default the speed a session starts at
 * @param text    how a speed is shown next to the slider
 */
final case class SpeedRange(min: Double, max: Double, default: Double, text: Double => String):
  require(min > 0.0 && min < max && default >= min && default <= max, s"bad speed range $this")

object SpeedRange:
  /** 0.01x to 100x, starting at 1x. */
  val Default: SpeedRange = SpeedRange(0.01, 100.0, 1.0, RunControls.speedText)

/** A model as the Lab offers it: how to start a live session of it. */
trait LabModel:
  def id: String
  def title: String

  /** Named parameter sets for the preset picker. */
  def presets: Vector[Preset] = Vector.empty

  /** The speeds of the slider. */
  def speedRange: SpeedRange = SpeedRange.Default

  /** Whether the model has randomness, so that a seed field is useful. */
  def usesSeed: Boolean = false

  /** Starts nothing yet: builds a paused-or-running live session with the given seed. */
  def open(seed: Long, startPaused: Boolean = false): LabSession

/** What the Lab needs from one running model. */
final class LabSession(
    val model: LabModel,
    val live: LiveRun[?, ?],
    val view: View,
    val schema: ParamSchema[?],
    val initial: Map[String, Double],
    val presets: Vector[Preset] = Vector.empty,
    val speedRange: SpeedRange = SpeedRange.Default,
    val usesSeed: Boolean = false
)

/** A catalog entry for an ODE model. */
final class OdeEntry[P](
    val title: String,
    model: Model.Aux[P, OdeState],
    params: P,
    stepping: Stepping,
    probes: P => Vector[Probe[OdeState]],
    view: P => View,
    speed: Double
) extends LabModel:
  def id: String = model.id

  override def speedRange: SpeedRange = SpeedRange.Default.copy(default = speed)

  def open(seed: Long, startPaused: Boolean): LabSession =
    val builder =
      Physis.model(model).withParams(params).stepping(stepping).seed(seed).probe(probes(params)*)
    val live = new LiveRun(builder, summon[StateExport[OdeState]], speed, startPaused)
    LabSession(
      this,
      live,
      view(params),
      model.params,
      model.params.toMap(params).toMap,
      speedRange = speedRange
    )

/** A catalog entry for a model with a field or particle state, with presets and a seed. */
final class ModelEntry[P, S](
    val title: String,
    model: Model.Aux[P, S],
    stepping: Stepping,
    probes: Vector[Probe[S]],
    view: View,
    exporter: StateExport[S],
    override val presets: Vector[Preset],
    override val speedRange: SpeedRange
) extends LabModel:
  def id: String = model.id

  override def usesSeed: Boolean = true

  def open(seed: Long, startPaused: Boolean): LabSession =
    val params = model.params.defaults
    val builder =
      Physis.model(model).withParams(params).stepping(stepping).seed(seed).probe(probes*)
    val live = new LiveRun(builder, exporter, speedRange.default, startPaused)
    LabSession(
      this,
      live,
      view,
      model.params,
      model.params.toMap(params).toMap,
      presets,
      speedRange,
      usesSeed = true
    )

/** Models available in Physis Lab. */
object Catalog:
  val lorenz: LabModel = new OdeEntry[LorenzParams](
    "Lorenz system",
    Lorenz.model,
    Lorenz.model.params.defaults,
    Stepping.Fixed(0.002),
    _ => Lorenz.probes,
    _ => LorenzViews.default,
    speed = 1.0
  )

  val pendulum: LabModel = new OdeEntry[PendulumParams](
    "Double pendulum",
    Pendulum.model,
    PendulumParams.chaotic,
    Stepping.Fixed(0.0005),
    Pendulum.probes,
    PendulumViews.default,
    speed = 1.0
  )

  /** One step is one unit of time, so the speed is the number of steps per second. */
  val grayScott: LabModel = new ModelEntry[GrayScottParams, GrayScottState](
    "Gray–Scott reaction–diffusion",
    GrayScott,
    GrayScott.stepping,
    GrayScott.probes,
    GrayScottViews.default,
    GrayScottState.stateExport,
    GrayScott.presets,
    SpeedRange(10.0, 4000.0, 600.0, s => f"$s%.0f steps/s")
  )

  /** One step is 1/60 time unit, so 1x is 60 steps per second. */
  val boids: LabModel = new ModelEntry[BoidsParams, BoidsState](
    "Boids",
    Boids,
    Boids.stepping,
    Boids.probes,
    BoidsViews.default,
    BoidsState.stateExport,
    Boids.presets,
    SpeedRange(0.1, 20.0, 1.0, RunControls.speedText)
  )

  /** In menu order; the first one opens at start. */
  val models: Vector[LabModel] = Vector(lorenz, pendulum, grayScott, boids)

  /** Models by id, for replaying manifests. */
  val registry: ModelRegistry =
    ModelRegistry.of(Lorenz.model, Pendulum.model, GrayScott, Boids)
