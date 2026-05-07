package physis.lab

import physis.core.{Model, Probe, Stepping}
import physis.core.live.{Command, LiveRun, StateExport}
import physis.core.param.{ParamSchema, Preset}
import physis.core.run.{Disc, Intervention, ModelRegistry, Op, Physis}
import physis.fx.{RunControls, ToolSpec}
import physis.models.boids.{Boids, BoidsParams, BoidsState, BoidsViews}
import physis.models.convection.{
  Convection,
  ConvectionParams,
  ConvectionPresets,
  ConvectionSides,
  ConvectionState,
  ConvectionViews
}
import physis.models.fluid.{Fluid, FluidParams, FluidPresets, FluidState, FluidViews}
import physis.models.grayscott.{GrayScott, GrayScottParams, GrayScottState, GrayScottViews}
import physis.models.lorenz.{Lorenz, LorenzParams, LorenzViews}
import physis.models.pendulum.{Pendulum, PendulumParams, PendulumViews}
import physis.models.physarum.{Physarum, PhysarumParams, PhysarumState, PhysarumViews}
import physis.models.wave.{Wave, WaveParams, WavePresets, WaveState, WaveViews}
import physis.ode.OdeState
import physis.view.{Dab, Frames, View}

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

/**
 * A pointer tool of a model: how it looks in the tool box and what one dab of it means.
 *
 * @param make the intervention for a dab: the dab, the brush radius, the tool's value, and
 *             whether the stroke was made with the right button (the erasing one)
 */
final case class LabTool(spec: ToolSpec, make: (Dab, Double, Double, Boolean) => Intervention)

/** A model as the Lab offers it: how to start a live session of it. */
trait LabModel:
  def id: String
  def title: String

  /** Named parameter sets for the preset picker. */
  def presets: Vector[Preset] = Vector.empty

  /** The speeds of the slider. */
  def speedRange: SpeedRange = SpeedRange.Default

  /**
   * What applying `preset` means, given the parameters now in force. Usually one parameter
   * change; a model whose presets also paint restarts instead, with the painting as part of the
   * new definition.
   */
  def presetCommand(preset: Preset, current: Map[String, Double]): Command =
    Command.SetParams(preset.values)

  /** Whether the model has randomness, so that a seed field is useful. */
  def usesSeed: Boolean = false

  /** What Save animation suggests for this model. */
  def animationDefaults: AnimationDefaults = AnimationDefaults()

  /** Pointer tools; the tool box is shown when there are any. */
  def tools: Vector[LabTool] = Vector.empty

  /** Whether strokes wrap across the border, given the session's current parameters. */
  def wraps(params: Map[String, Double]): Boolean = false

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
    val usesSeed: Boolean = false,
    val tools: Vector[LabTool] = Vector.empty,
    val exporter: Option[StateExport[?]] = None,
    val animation: AnimationDefaults = AnimationDefaults()
):
  /** Whether strokes wrap across the border, given the current parameters. */
  def wraps(params: Map[String, Double]): Boolean = model.wraps(params)

  /**
   * Whether the session can make pictures of its state without a window (Save image, Save
   * animation): it has a state exporter and its view holds a heatmap. The ODE models do not.
   */
  def hasImage: Boolean = exporter.isDefined && Frames.imageOf(view).isDefined

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
    override val speedRange: SpeedRange,
    override val tools: Vector[LabTool] = Vector.empty,
    wrap: Map[String, Double] => Boolean = _ => false,
    painted: Map[String, (Int, Int) => Vector[Intervention]] = Map.empty,
    seeded: Boolean = true,
    animation: AnimationDefaults = AnimationDefaults()
) extends LabModel:
  def id: String = model.id

  override def animationDefaults: AnimationDefaults = animation

  override def usesSeed: Boolean = seeded

  override def presetCommand(preset: Preset, current: Map[String, Double]): Command =
    painted.get(preset.name) match
      case None => Command.SetParams(preset.values)
      case Some(paint) =>
        val values = current ++ preset.values
        val nx = values.getOrElse("nx", 0.0).toInt
        val ny = values.getOrElse("ny", 0.0).toInt
        Command.Restart(values, None, Some(paint(nx, ny)))

  override def wraps(params: Map[String, Double]): Boolean = wrap(params)

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
      usesSeed = seeded,
      tools = tools,
      exporter = Some(exporter),
      animation = animation
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
    SpeedRange(10.0, 4000.0, 600.0, s => f"$s%.0f steps/s"),
    tools = Vector(
      LabTool(
        ToolSpec("Paint v", Some("v"), 0.5, (0.0, 1.0), (1.0, 64.0), 6.0),
        (d, r, v, secondary) =>
          Intervention.Brush("v", Disc(d.x, d.y, r), if secondary then Op.Erase else Op.Assign, v)
      ),
      LabTool(
        ToolSpec("Erase v", None, 0.0, (0.0, 0.0), (1.0, 64.0), 8.0),
        (d, r, _, _) => Intervention.Brush("v", Disc(d.x, d.y, r), Op.Erase, 0.0)
      )
    ),
    wrap = _ => true,
    animation = AnimationDefaults(every = 50, fps = 15)
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
    SpeedRange(0.1, 20.0, 1.0, RunControls.speedText),
    tools = Vector(
      LabTool(
        ToolSpec("Disturb", Some("strength"), 0.5, (0.05, Boids.MaxDisturb), (10.0, 400.0), 80.0),
        (d, r, v, _) => Intervention.Disturb(Disc(d.x, d.y, r), v)
      )
    ),
    wrap = _ => true
  )

  /** One step is half a time unit, so 1x of the slider is 2 steps per time unit. */
  val wave: LabModel = new ModelEntry[WaveParams, WaveState](
    "Waves",
    Wave,
    Wave.stepping,
    Wave.probes,
    WaveViews.default,
    WaveState.stateExport,
    WavePresets.all.map(p => Preset(p.name, p.params, p.doc)),
    SpeedRange(20.0, 2000.0, 300.0, s => f"$s%.0f steps/s"),
    tools = Vector(
      LabTool(
        ToolSpec("Wall", None, 0.0, (0.0, 0.0), (1.0, 40.0), 3.0),
        (d, r, _, secondary) =>
          Intervention.Brush(
            "wall",
            Disc(d.x, d.y, r),
            if secondary then Op.Erase else Op.Assign,
            1.0
          )
      ),
      LabTool(
        ToolSpec("Erase wall", None, 0.0, (0.0, 0.0), (1.0, 40.0), 6.0),
        (d, r, _, _) => Intervention.Brush("wall", Disc(d.x, d.y, r), Op.Erase, 0.0)
      ),
      LabTool(
        ToolSpec("Pulse", Some("amplitude"), 1.0, (-2.0, 2.0), (2.0, 30.0), 10.0),
        (d, r, v, _) => Intervention.Pulse("u", d.x, d.y, math.max(1.0, r / 2.5), v)
      )
    ),
    wrap = p => p.getOrElse("edge", 0.0) == 0.0,
    painted = WavePresets.all.map(p => p.name -> p.interventions).toMap,
    seeded = false,
    animation = AnimationDefaults(every = 4, fps = 20)
  )

  /** One step is one time unit, so the speed is the number of steps per second. */
  val physarum: LabModel = new ModelEntry[PhysarumParams, PhysarumState](
    "Physarum",
    Physarum,
    Physarum.stepping,
    Physarum.probes,
    PhysarumViews.default,
    PhysarumState.stateExport,
    Physarum.presets,
    SpeedRange(5.0, 600.0, 60.0, s => f"$s%.0f steps/s"),
    tools = Vector(
      LabTool(
        ToolSpec("Paint food", Some("food"), 5.0, (0.1, 20.0), (1.0, 64.0), 8.0),
        (d, r, v, secondary) =>
          Intervention.Brush(
            "food",
            Disc(d.x, d.y, r),
            if secondary then Op.Erase else Op.Assign,
            v
          )
      ),
      LabTool(
        ToolSpec("Erase food", None, 0.0, (0.0, 0.0), (1.0, 64.0), 10.0),
        (d, r, _, _) => Intervention.Brush("food", Disc(d.x, d.y, r), Op.Erase, 0.0)
      ),
      LabTool(
        ToolSpec("Paint trail", Some("trail"), 20.0, (1.0, 200.0), (1.0, 64.0), 6.0),
        (d, r, v, secondary) =>
          Intervention.Brush(
            "trail",
            Disc(d.x, d.y, r),
            if secondary then Op.Erase else Op.Add,
            v
          )
      )
    ),
    wrap = _ => true,
    animation = AnimationDefaults(every = 5, fps = 15)
  )

  /**
   * One step is a quarter of a time unit. A dab has no direction, so pushing is two tools, one
   * for each component of the velocity; the right button pushes the other way.
   */
  val fluid: LabModel = new ModelEntry[FluidParams, FluidState](
    "Fluids",
    Fluid,
    Fluid.stepping,
    Fluid.probes,
    FluidViews.lab,
    FluidState.stateExport,
    FluidPresets.all.map(p => Preset(p.name, p.params, p.doc)),
    SpeedRange(5.0, 600.0, 60.0, s => f"$s%.0f steps/s"),
    tools = Vector(
      LabTool(
        ToolSpec("Smoke", Some("dye"), 1.0, (0.1, 1.0), (1.0, 40.0), 5.0),
        (d, r, v, secondary) =>
          Intervention.Brush(
            "dye",
            Disc(d.x, d.y, r),
            if secondary then Op.Erase else Op.Assign,
            v
          )
      ),
      LabTool(
        ToolSpec("Push sideways", Some("speed"), 1.0, (0.1, 3.0), (2.0, 40.0), 8.0),
        (d, r, v, secondary) =>
          Intervention.Pulse("u", d.x, d.y, math.max(1.0, r / 2.5), if secondary then -v else v)
      ),
      LabTool(
        ToolSpec("Push up", Some("speed"), 1.0, (0.1, 3.0), (2.0, 40.0), 8.0),
        (d, r, v, secondary) =>
          Intervention.Pulse("v", d.x, d.y, math.max(1.0, r / 2.5), if secondary then -v else v)
      ),
      LabTool(
        ToolSpec("Wall", None, 0.0, (0.0, 0.0), (1.0, 40.0), 3.0),
        (d, r, _, secondary) =>
          Intervention.Brush(
            "wall",
            Disc(d.x, d.y, r),
            if secondary then Op.Erase else Op.Assign,
            1.0
          )
      ),
      LabTool(
        ToolSpec("Erase wall", None, 0.0, (0.0, 0.0), (1.0, 40.0), 6.0),
        (d, r, _, _) => Intervention.Brush("wall", Disc(d.x, d.y, r), Op.Erase, 0.0)
      )
    ),
    wrap = p => p.getOrElse("edge", 0.0) == 0.0,
    painted = FluidPresets.all.map(p => p.name -> p.interventions).toMap,
    animation = AnimationDefaults(every = 4, fps = 20)
  )

  /**
   * One step is a quarter of a time unit and the layer needs thousands of them to settle, so the
   * speed starts high. Heat is painted as a deviation from the conduction profile: a hot spot
   * adds warmth where the fluid is, a cool spot takes it away, and the right button resets the
   * place to the conduction profile.
   */
  val convection: LabModel = new ModelEntry[ConvectionParams, ConvectionState](
    "Convection",
    Convection,
    Convection.stepping,
    Convection.probes,
    ConvectionViews.lab,
    ConvectionState.stateExport,
    ConvectionPresets.all.map(p => Preset(p.name, p.params, p.doc)),
    SpeedRange(20.0, 1000.0, 300.0, s => f"$s%.0f steps/s"),
    tools = Vector(
      LabTool(
        ToolSpec("Heat spot", Some("warmth"), 0.5, (0.05, 1.0), (1.0, 20.0), 4.0),
        (d, r, v, secondary) =>
          Intervention.Brush(
            "heat",
            Disc(d.x, d.y, r),
            if secondary then Op.Erase else Op.Assign,
            v
          )
      ),
      LabTool(
        ToolSpec("Cool spot", Some("coolness"), 0.5, (0.05, 1.0), (1.0, 20.0), 4.0),
        (d, r, v, secondary) =>
          Intervention.Brush(
            "heat",
            Disc(d.x, d.y, r),
            if secondary then Op.Erase else Op.Assign,
            -v
          )
      ),
      LabTool(
        ToolSpec("Wall", None, 0.0, (0.0, 0.0), (1.0, 20.0), 3.0),
        (d, r, _, secondary) =>
          Intervention.Brush(
            "wall",
            Disc(d.x, d.y, r),
            if secondary then Op.Erase else Op.Assign,
            1.0
          )
      ),
      LabTool(
        ToolSpec("Erase wall", None, 0.0, (0.0, 0.0), (1.0, 20.0), 6.0),
        (d, r, _, _) => Intervention.Brush("wall", Disc(d.x, d.y, r), Op.Erase, 0.0)
      )
    ),
    wrap = p => p.getOrElse("sides", 0.0) == ConvectionSides.Periodic.toDouble,
    painted = ConvectionPresets.all.map(p => p.name -> p.interventions).toMap,
    animation = AnimationDefaults(every = 100, fps = 20)
  )

  /** In menu order; the first one opens at start. */
  val models: Vector[LabModel] =
    Vector(lorenz, pendulum, grayScott, boids, wave, physarum, fluid, convection)

  /** Models by id, for replaying manifests. */
  val registry: ModelRegistry =
    ModelRegistry.of(
      Lorenz.model,
      Pendulum.model,
      GrayScott,
      Boids,
      Wave,
      Physarum,
      Fluid,
      Convection
    )
