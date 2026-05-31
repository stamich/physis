package physis.cli

import physis.core.live.{ExportSpec, StateExport}
import physis.models.boids.{Boids, BoidsParams, BoidsState}
import physis.models.convection.{Convection, ConvectionExport, ConvectionParams, ConvectionState}
import physis.models.fluid.{Fluid, FluidExport, FluidParams, FluidState}
import physis.models.grayscott.{GrayScott, GrayScottExport, GrayScottParams, GrayScottState}
import physis.models.lorenz.{Lorenz, LorenzParams}
import physis.models.pendulum.{Pendulum, PendulumParams}
import physis.models.physarum.{Physarum, PhysarumExport, PhysarumParams, PhysarumState}
import physis.models.shallow.{Shallow, ShallowExport, ShallowParams, ShallowState}
import physis.models.wave.{Wave, WaveExport, WaveParams, WaveState}
import physis.ode.OdeState

/**
 * What it takes to write a model's run as a dataset: how its state is exported and which of
 * it are fields, in which units. Models without fields (the ODE models, the flock) write their
 * probes alone.
 */
private[cli] object Datasets:

  /** The export of a model with parameters `P` and state `S`. */
  final case class Data[P, S](exporter: StateExport[S], spec: P => ExportSpec)

  private def probesOnly[P](title: String): P => ExportSpec = _ => ExportSpec.probesOnly(title)

  private val all: Map[String, Data[?, ?]] = Map(
    Lorenz.Id -> Data[LorenzParams, OdeState](OdeState.stateExport, probesOnly("Lorenz attractor")),
    Pendulum.Id -> Data[PendulumParams, OdeState](
      OdeState.stateExport,
      probesOnly("Double pendulum")
    ),
    GrayScott.id -> Data[GrayScottParams, GrayScottState](
      GrayScottState.stateExport,
      _ => GrayScottExport.spec
    ),
    Boids.id -> Data[BoidsParams, BoidsState](BoidsState.stateExport, probesOnly("Boids flock")),
    Wave.id -> Data[WaveParams, WaveState](WaveState.stateExport, _ => WaveExport.spec),
    Physarum.id -> Data[PhysarumParams, PhysarumState](
      PhysarumState.stateExport,
      _ => PhysarumExport.spec
    ),
    Fluid.id -> Data[FluidParams, FluidState](FluidState.stateExport, _ => FluidExport.spec),
    Convection.id -> Data[ConvectionParams, ConvectionState](
      ConvectionState.stateExport,
      ConvectionExport.spec
    ),
    Shallow.id -> Data[ShallowParams, ShallowState](ShallowState.stateExport, ShallowExport.spec)
  )

  /** The export of the model called `id`, if there is such a model. */
  def of(id: String): Option[Data[Any, Any]] =
    all.get(id).map(_.asInstanceOf[Data[Any, Any]])
