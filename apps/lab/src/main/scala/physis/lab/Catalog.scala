package physis.lab

import physis.core.{Model, Probe, Stepping}
import physis.core.live.{LiveRun, StateExport}
import physis.core.param.ParamSchema
import physis.core.run.{ModelRegistry, Physis}
import physis.models.lorenz.{Lorenz, LorenzParams, LorenzViews}
import physis.models.pendulum.{Pendulum, PendulumParams, PendulumViews}
import physis.ode.OdeState
import physis.view.View

/** A model as the Lab offers it: how to start a live session of it. */
trait LabModel:
  def id: String
  def title: String

  /** Starts nothing yet: builds a paused-or-running live session with the given seed. */
  def open(seed: Long, startPaused: Boolean = false): LabSession

/** What the Lab needs from one running model. */
final class LabSession(
    val model: LabModel,
    val live: LiveRun[?, ?],
    val view: View,
    val schema: ParamSchema[?],
    val initial: Map[String, Double]
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

  def open(seed: Long, startPaused: Boolean): LabSession =
    val builder =
      Physis.model(model).withParams(params).stepping(stepping).seed(seed).probe(probes(params)*)
    val live = new LiveRun(builder, summon[StateExport[OdeState]], speed, startPaused)
    LabSession(this, live, view(params), model.params, model.params.toMap(params).toMap)

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

  /** In menu order; the first one opens at start. */
  val models: Vector[LabModel] = Vector(lorenz, pendulum)

  /** Models by id, for replaying manifests. */
  val registry: ModelRegistry = ModelRegistry.of(Lorenz.model, Pendulum.model)
