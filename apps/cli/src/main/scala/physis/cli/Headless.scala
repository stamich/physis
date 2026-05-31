package physis.cli

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.{Model, Probe, Stepping}
import physis.core.live.StateExport
import physis.core.param.Preset
import physis.core.run.{
  Disc,
  Intervention,
  InterventionEvent,
  Manifest,
  Op,
  ParamEvent,
  Physis,
  Run
}
import physis.core.sweep.{ParamGrid, PointResult, Sweep, SweepOutput, SweepSetup}
import physis.models.convection.{
  Convection,
  ConvectionParams,
  ConvectionPresets,
  ConvectionState,
  ConvectionViews
}
import physis.models.shallow.{Shallow, ShallowParams, ShallowPresets, ShallowState, ShallowViews}
import physis.models.fluid.{Fluid, FluidParams, FluidPresets, FluidState, FluidViews}
import physis.models.boids.{Boids, BoidsParams, BoidsState, BoidsViews}
import physis.models.grayscott.{GrayScott, GrayScottParams, GrayScottState, GrayScottViews}
import physis.models.lorenz.{Lorenz, LorenzParams}
import physis.models.pendulum.{Pendulum, PendulumParams}
import physis.models.physarum.{Physarum, PhysarumParams, PhysarumState, PhysarumViews}
import physis.models.wave.{Wave, WaveParams, WaveState, WaveViews}
import physis.ode.OdeState
import physis.netcdf.{Attr, NcCompare, NcExport, NcFile}
import physis.view.{Frame, Frames, Montage, Png, View}

/**
 * Runs a model without a UI and prints its probes as CSV.
 *
 * {{{
 * ./gradlew :apps:cli:run --args="--model lorenz --until 20 --sample-every 0.05"
 * ./gradlew :apps:cli:run --args="--model double-pendulum --param theta1=90 --adaptive 1e-9 --manifest run.json"
 * ./gradlew :apps:cli:run --args="--replay run.json"
 * ./gradlew :apps:cli:run --args="--model gray-scott --param nx=64 --param ny=64 --until 3000 --sample-every 500"
 * ./gradlew :apps:cli:run --args="--model boids --param n=500 --until 10 --sample-every 1"
 * ./gradlew :apps:cli:run --args="--list"
 * ./gradlew :apps:cli:run --args="--model physarum --steps 600 --frames out --every 60 --scale 3"
 * ./gradlew :apps:cli:run --args="--model gray-scott --until 2000 --sweep F=0.02:0.06:5 --sweep k=0.05:0.07:5"
 * }}}
 *
 * A summary with the final state digest goes to standard error; a replay of a manifest prints
 * the same CSV and the same digest, and a manifest with parameter changes and strokes is replayed
 * with them. `--frames` writes numbered PNGs of the first image of the model's view; `--sweep`
 * runs the product of the given axes (see `ParamGrid`) on all cores, in grid order whatever the
 * number of threads, and prints a CSV with one row per point.
 */
object Headless:

  val Usage: String =
    """Usage: RunHeadless [options]
      |  --model <id>           lorenz | double-pendulum | gray-scott | boids | wave | physarum | fluid |
      |                         convection | shallow (default: lorenz)
      |  --list                 list the models with their parameters and exit
      |  --preset <name>        start from a preset of the model (see describe); --param
      |                         values are applied after it
      |  --param <name=value>   override a model parameter (repeatable)
      |  --until <t>            end time (default: 10)
      |  --steps <n>            run n steps instead of up to a time (fixed steps only)
      |  --sample-every <dt>    probe sampling interval (default: 0.1)
      |  --dt <dt>              fixed step (default: the model's own: 0.001 for the ODE models,
      |                         1 for gray-scott and physarum, 0.5 for wave, 1/60 for boids)
      |  --adaptive <tol>       Dormand-Prince with rtol = atol = tol instead of fixed steps
      |  --seed <n>             run seed (default: 0)
      |  --manifest <path>      write the run manifest to <path> (also for frames and animations)
      |  --stroke <spec>        a stroke at a step, recorded in the manifest (repeatable):
      |                         STEP:brush:TARGET:X:Y:R:OP:VALUE (OP is set, add or erase),
      |                         STEP:pulse:TARGET:X:Y:SIGMA:AMPLITUDE, STEP:disturb:X:Y:R:STRENGTH
      |  --replay <path>        re-run the manifest at <path>, with its parameter changes and
      |                         strokes; other options are ignored except --frames, --animate,
      |                         --manifest and --stroke (more strokes end the digest check)
      |
      |Frames (gray-scott, wave, physarum, fluid, convection, shallow): numbered PNGs of the first image of the view
      |  --frames <dir>         write the frames into <dir> instead of printing a CSV
      |  --animate <file>       write the frames as one animated PNG instead
      |  --fps <n>              frames per second of the animation, 1 to 65535 (default: 12)
      |  --loops <n>            times the animation plays, 0 for ever (default: 0)
      |  --every <n>            steps between frames (default: a tenth of the run)
      |  --scale <n>            pixels per cell, 1 to 16 (default: 1)
      |  --no-color-bar         leave out the colour scale at the right
      |  --compress             deflate the PNG data (smaller files, same pixels)
      |
      |Datasets (all models): a NetCDF file of the run, read by xarray, Panoply and ParaView
      |  --export <file.nc>     write time, step, a digest of the state, the fields and the
      |                         probes every --every steps instead of printing a CSV; the
      |                         manifest is inside the file
      |  --vars <a,b,...>       only these fields and probes
      |  --float                fields as 32-bit floats (half the size)
      |
      |Sweeps: the product of the axes, one run per point, results in grid order
      |  --sweep <axis>         name=a:b:n | name=log:a:b:n | name=a,b,c | seed=a..b (repeatable)
      |  --threads <n>          runs at the same time (default: one per processor)
      |  --out <path>           write the CSV to <path> instead of standard output
      |  --jsonl <path>         also write one JSON object per point
      |  --progress             print "# done/total" to standard error as points finish
      |  --dry-run              print the grid (axes and number of points) and run nothing
      |  --fail-fast            check every point first (parameters and the model's own
      |                         limits) and run nothing if one is refused
      |  --manifests <dir>      also write point-NNNN.json, a manifest per point that replays
      |  --montage <path>       also tile the final images of all points into one PNG (the last
      |                         axis along a row; --scale and --compress apply)
      |  --montage-columns <n>  tiles per row instead
      |  --help                 show this help""".stripMargin

  final case class Options(
      model: String = Lorenz.Id,
      params: Map[String, Double] = Map.empty,
      until: Double = 10.0,
      sampleEvery: Double = 0.1,
      dt: Option[Double] = None,
      adaptiveTolerance: Option[Double] = None,
      seed: Long = 0L,
      manifest: Option[Path] = None,
      replay: Option[Path] = None,
      exactStepping: Option[Stepping] = None,
      list: Boolean = false,
      preset: Option[String] = None,
      expectedDigest: Option[Long] = None,
      progress: Boolean = false,
      dryRun: Boolean = false,
      failFast: Boolean = false,
      steps: Option[Long] = None,
      events: Vector[ParamEvent] = Vector.empty,
      interventions: Vector[InterventionEvent] = Vector.empty,
      frames: Option[Path] = None,
      animate: Option[Path] = None,
      dataset: Option[Path] = None,
      vars: Option[Set[String]] = None,
      single: Boolean = false,
      fps: Int = 12,
      loops: Int = 0,
      strokes: Vector[InterventionEvent] = Vector.empty,
      every: Option[Long] = None,
      scale: Int = 1,
      colorBar: Boolean = true,
      compress: Boolean = false,
      sweep: Vector[String] = Vector.empty,
      threads: Option[Int] = None,
      out: Option[Path] = None,
      jsonl: Option[Path] = None,
      manifests: Option[Path] = None,
      montage: Option[Path] = None,
      montageColumns: Option[Int] = None
  ):
    /**
     * Stepping from a manifest when replaying, otherwise from --adaptive or --dt, otherwise the
     * model's own `default`.
     */
    def steppingFor(default: Stepping): Stepping = exactStepping.getOrElse(
      adaptiveTolerance match
        case Some(tol) =>
          Stepping.Adaptive(tol, tol, initialDt = math.min(1e-3, sampleEvery), maxDt = sampleEvery)
        case None => dt.map(Stepping.Fixed(_)).getOrElse(default)
    )

  /** A model this example can run, with the probes it prints and the step it is meant for. */
  private final case class Entry[P, S](
      model: Model.Aux[P, S],
      probes: P => Vector[Probe[S]],
      stepping: Stepping,
      image: Option[Image[S]] = None,
      presets: Vector[Preset] = Vector.empty,
      painted: Map[String, (Int, Int) => Vector[Intervention]] = Map.empty
  )

  /** What frames need of a model: how its state is exported and what its view is. */
  private final case class Image[S](exporter: StateExport[S], view: View)

  private val ode: Stepping = Stepping.Fixed(0.001)

  private val catalog: Map[String, Entry[?, ?]] = Map(
    Lorenz.Id -> Entry[LorenzParams, OdeState](Lorenz.model, _ => Lorenz.probes, ode),
    Pendulum.Id -> Entry[PendulumParams, OdeState](Pendulum.model, Pendulum.probes, ode),
    GrayScott.id -> Entry[GrayScottParams, GrayScottState](
      GrayScott,
      _ => GrayScott.probes,
      GrayScott.stepping,
      Some(Image(GrayScottState.stateExport, GrayScottViews.default)),
      GrayScott.presets
    ),
    Boids.id -> Entry[BoidsParams, BoidsState](
      Boids,
      _ => Boids.probes,
      Boids.stepping,
      Some(Image(BoidsState.stateExport, BoidsViews.default)),
      Boids.presets
    ),
    Wave.id -> Entry[WaveParams, WaveState](
      Wave,
      _ => Wave.probes,
      Wave.stepping,
      Some(Image(WaveState.stateExport, WaveViews.default))
    ),
    Physarum.id -> Entry[PhysarumParams, PhysarumState](
      Physarum,
      _ => Physarum.probes,
      Physarum.stepping,
      Some(Image(PhysarumState.stateExport, PhysarumViews.default)),
      Physarum.presets
    ),
    Fluid.id -> Entry[FluidParams, FluidState](
      Fluid,
      _ => Fluid.probes,
      Fluid.stepping,
      Some(Image(FluidState.stateExport, FluidViews.lab)),
      FluidPresets.all.map(p => Preset(p.name, p.params, p.doc)),
      FluidPresets.all.map(p => p.name -> p.interventions).toMap
    ),
    Convection.id -> Entry[ConvectionParams, ConvectionState](
      Convection,
      _ => Convection.probes,
      Convection.stepping,
      Some(Image(ConvectionState.stateExport, ConvectionViews.lab)),
      ConvectionPresets.all.map(p => Preset(p.name, p.params, p.doc)),
      ConvectionPresets.all.map(p => p.name -> p.interventions).toMap
    ),
    Shallow.id -> Entry[ShallowParams, ShallowState](
      Shallow,
      _ => Shallow.probes,
      Shallow.stepping,
      Some(Image(ShallowState.stateExport, ShallowViews.lab)),
      ShallowPresets.all.map(p => Preset(p.name, p.params, p.doc)),
      ShallowPresets.all.map(p => p.name -> p.interventions).toMap
    )
  )

  def main(args: Array[String]): Unit =
    val code = parse(args.toList) match
      case Left("help") =>
        Console.out.println(Usage)
        0
      case Left(error) =>
        Console.err.println(s"error: $error")
        Console.err.println(Usage)
        2
      case Right(options) => run(options, Console.out, Console.err)
    if code != 0 then sys.exit(code)

  /** Parses command-line arguments. `Left("help")` asks for the usage text. */
  def parse(args: List[String], options: Options = Options()): Either[String, Options] =
    args match
      case Nil => Right(options)
      case "--help" :: _ => Left("help")
      case "--list" :: rest => parse(rest, options.copy(list = true))
      case "--preset" :: name :: rest => parse(rest, options.copy(preset = Some(name)))
      case "--no-color-bar" :: rest => parse(rest, options.copy(colorBar = false))
      case "--compress" :: rest => parse(rest, options.copy(compress = true))
      case "--progress" :: rest => parse(rest, options.copy(progress = true))
      case "--dry-run" :: rest => parse(rest, options.copy(dryRun = true))
      case "--fail-fast" :: rest => parse(rest, options.copy(failFast = true))
      case "--model" :: id :: rest => parse(rest, options.copy(model = id))
      case "--param" :: assignment :: rest =>
        assignment.split("=", 2) match
          case Array(name, value) if name.nonEmpty =>
            number(value, s"--param $name").flatMap { v =>
              parse(rest, options.copy(params = options.params + (name -> v)))
            }
          case _ => Left(s"--param expects name=value, got '$assignment'")
      case "--until" :: v :: rest =>
        number(v, "--until").flatMap(d => parse(rest, options.copy(until = d)))
      case "--sample-every" :: v :: rest =>
        number(v, "--sample-every").flatMap(d => parse(rest, options.copy(sampleEvery = d)))
      case "--dt" :: v :: rest =>
        number(v, "--dt").flatMap(d => parse(rest, options.copy(dt = Some(d))))
      case "--adaptive" :: v :: rest =>
        number(v, "--adaptive").flatMap(d => parse(rest, options.copy(adaptiveTolerance = Some(d))))
      case "--seed" :: v :: rest =>
        v.toLongOption.toRight(s"--seed expects an integer, got '$v'")
          .flatMap(s => parse(rest, options.copy(seed = s)))
      case "--steps" :: v :: rest =>
        whole(v, "--steps", 0L).flatMap(n => parse(rest, options.copy(steps = Some(n))))
      case "--every" :: v :: rest =>
        whole(v, "--every", 1L).flatMap(n => parse(rest, options.copy(every = Some(n))))
      case "--scale" :: v :: rest =>
        whole(v, "--scale", 1L).flatMap(n => parse(rest, options.copy(scale = n.toInt)))
      case "--threads" :: v :: rest =>
        whole(v, "--threads", 1L).flatMap(n => parse(rest, options.copy(threads = Some(n.toInt))))
      case "--frames" :: path :: rest => parse(rest, options.copy(frames = Some(Path.of(path))))
      case "--animate" :: path :: rest => parse(rest, options.copy(animate = Some(Path.of(path))))
      case "--export" :: path :: rest => parse(rest, options.copy(dataset = Some(Path.of(path))))
      case "--vars" :: names :: rest =>
        parse(
          rest,
          options.copy(vars = Some(names.split(",").map(_.trim).filter(_.nonEmpty).toSet))
        )
      case "--float" :: rest => parse(rest, options.copy(single = true))
      case "--fps" :: v :: rest =>
        whole(v, "--fps", 1L)
          .filterOrElse(_ <= 65535L, s"--fps must be at most 65535, got $v")
          .flatMap(n => parse(rest, options.copy(fps = n.toInt)))
      case "--loops" :: v :: rest =>
        whole(v, "--loops", 0L)
          .filterOrElse(_ <= 65535L, s"--loops must be at most 65535, got $v")
          .flatMap(n => parse(rest, options.copy(loops = n.toInt)))
      case "--stroke" :: spec :: rest =>
        stroke(spec).flatMap(e => parse(rest, options.copy(strokes = options.strokes :+ e)))
      case "--sweep" :: axis :: rest => parse(rest, options.copy(sweep = options.sweep :+ axis))
      case "--montage" :: path :: rest => parse(rest, options.copy(montage = Some(Path.of(path))))
      case "--montage-columns" :: v :: rest =>
        whole(v, "--montage-columns", 1L)
          .flatMap(n => parse(rest, options.copy(montageColumns = Some(n.toInt))))
      case "--out" :: path :: rest => parse(rest, options.copy(out = Some(Path.of(path))))
      case "--jsonl" :: path :: rest => parse(rest, options.copy(jsonl = Some(Path.of(path))))
      case "--manifests" :: path :: rest =>
        parse(rest, options.copy(manifests = Some(Path.of(path))))
      case "--manifest" :: path :: rest => parse(rest, options.copy(manifest = Some(Path.of(path))))
      case "--replay" :: path :: rest => parse(rest, options.copy(replay = Some(Path.of(path))))
      case option :: Nil if option.startsWith("--") => Left(s"$option expects a value")
      case other :: _ => Left(s"unknown argument '$other'")

  /**
   * Checks a dataset written by `export` against the run that should have made it: the manifest
   * inside the file is replayed, with the same `--every`, precision and variables, and every
   * value of the replay must equal the file's, bit for bit. Returns the exit code: 0 if the
   * data agrees, 1 if it does not or the file cannot be checked.
   */
  def verify(path: Path, out: PrintStream, err: PrintStream): Int =
    val temp = Files.createTempFile("physis-verify", ".nc")
    try
      val outcome: Either[String, String] =
        for
          file <- NcFile.read(path)
          json <- file
            .text("physis_manifest")
            .toRight("the file has no manifest (physis_manifest): not written by physis-cli export")
          m <- Manifest.fromJson(json)
          every <- file
            .text("physis_every")
            .flatMap(_.toLongOption)
            .toRight("the file does not say how often it was written (physis_every)")
          base = Options(
            dataset = Some(temp),
            every = Some(every),
            single = file.text("physis_precision").contains("float32"),
            vars = file.text("physis_vars").map(_.split(",").toSet)
          )
          o <- fromParsed(m, base)
          replayErr = new java.io.ByteArrayOutputStream()
          code = run(
            o,
            new PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(replayErr, true, StandardCharsets.UTF_8)
          )
          _ <- Either.cond(
            code == 0,
            (),
            s"the replay failed: ${replayErr.toString(StandardCharsets.UTF_8).trim}"
          )
          again <- NcFile.read(temp)
          _ <- NcCompare.diff(file, again).fold[Either[String, Unit]](Right(()))(why =>
            Left(s"DIFFERS: $why")
          )
        yield s"verified: ${file.records} records, ${file.schema.vars.size} variables, " +
          s"identical to a replay of the manifest (${file.text("physis_model").getOrElse("?")}, " +
          s"written by ${file.text("source").getOrElse("?")})"
      outcome match
        case Right(message) =>
          out.println(message)
          0
        case Left(why) =>
          err.println(if why.startsWith("DIFFERS") then s"# $why" else s"error: $why")
          1
    finally
      val _ = Files.deleteIfExists(temp)

  /** Runs with `options`, writing CSV to `out` and a summary to `err`; returns the exit code. */
  def run(options: Options, out: PrintStream, err: PrintStream): Int =
    val resolved = options.replay match
      case Some(path) => fromManifest(path, options)
      case None => Right(options)
    resolved.flatMap(o => checkStrokes(o).toLeft(o)) match
      case Left(error) if resolved.isRight =>
        err.println(s"error: $error")
        2
      case other =>
        other.flatMap(o => execute(o, out, err)) match
          case Right(code) => code
          case Left(error) =>
            err.println(s"error: $error")
            1

  private def fromManifest(path: Path, options: Options): Either[String, Options] =
    val text =
      try Right(Files.readString(path, StandardCharsets.UTF_8))
      catch case e: java.io.IOException => Left(s"cannot read $path: ${e.getMessage}")
    text.flatMap(Manifest.fromJson).flatMap(m => fromParsed(m, options))

  /** The options that replay `m`, with the output options of `options`. */
  private def fromParsed(m: Manifest, options: Options): Either[String, Options] =
    catalog.get(m.model) match
      case None => Left(s"unknown model '${m.model}'")
      case Some(entry) if entry.model.version != m.modelVersion =>
        Left(
          s"model '${m.model}' is version ${entry.model.version}, manifest needs ${m.modelVersion}"
        )
      case Some(_) =>
        Right(
          Options(
            model = m.model,
            params = m.params,
            until = m.until,
            sampleEvery = m.sampleEvery,
            seed = m.seed,
            exactStepping = Some(m.stepping),
            steps = m.steps,
            events = m.events,
            interventions = m.interventions,
            frames = options.frames,
            animate = options.animate,
            dataset = options.dataset,
            vars = options.vars,
            single = options.single,
            fps = options.fps,
            loops = options.loops,
            manifest = options.manifest,
            strokes = options.strokes,
            expectedDigest = if options.strokes.isEmpty then m.digest else None,
            every = options.every,
            scale = options.scale,
            colorBar = options.colorBar,
            compress = options.compress
          )
        )

  private def execute(o: Options, out: PrintStream, err: PrintStream): Either[String, Int] =
    if o.list then
      list(out)
      Right(0)
    else
      catalog.get(o.model) match
        case None =>
          Left(unknownModel(o.model))
        case Some(found) =>
          val entry = found.asInstanceOf[Entry[Any, Any]]
          withPreset(entry, o).flatMap(withPreset => executeEntry(entry, withPreset, out, err))

  /** `o` with the values of its preset below its own `--param` values. */
  private def withPreset(entry: Entry[Any, Any], o: Options): Either[String, Options] =
    o.preset match
      case None => Right(o)
      case Some(name) =>
        entry.presets.find(_.name.equalsIgnoreCase(name)) match
          case Some(preset) =>
            val params = preset.values ++ o.params
            def size(name: String): Int =
              params
                .get(name)
                .orElse(entry.model.params.specs.find(_.name == name).map(_.default))
                .fold(0)(_.toInt)
            val painted = entry.painted
              .get(preset.name)
              .fold(Vector.empty[InterventionEvent])(paint =>
                paint(size("nx"), size("ny")).map(InterventionEvent(0L, _))
              )
            Right(o.copy(params = params, interventions = painted ++ o.interventions))
          case None =>
            val known = entry.presets.map(_.name)
            Left(
              s"model '${o.model}' has no preset '$name'" +
                (if known.isEmpty then " (it has none)" else s" (known: ${known.mkString(", ")})")
            )

  private def paramLines(entry: Entry[?, ?]): Vector[String] =
    entry.model.params.specs.toVector.map { p =>
      val restart = if p.restart then ", needs a restart" else ""
      val doc = if p.doc.isEmpty then "" else s" - ${p.doc}"
      s"  ${p.name} = ${p.default} [${p.min}, ${p.max}] ${p.unit}$restart$doc"
    }

  /** Every model with its parameters. */
  def list(out: PrintStream): Unit =
    for (id, entry) <- catalog.toList.sortBy(_._1) do
      out.println(s"$id (version ${entry.model.version})")
      paramLines(entry).foreach(out.println)

  /** One model in full: parameters, presets, probes, stepping and whether it has an image. */
  def describe(id: String, out: PrintStream): Either[String, Unit] =
    catalog.get(id).toRight(unknownModel(id)).map { found =>
      val entry = found.asInstanceOf[Entry[Any, Any]]
      out.println(s"$id (version ${entry.model.version})")
      out.println(s"step: ${entry.stepping}")
      out.println("parameters:")
      paramLines(entry).foreach(out.println)
      out.println("presets:" + (if entry.presets.isEmpty then " none" else ""))
      for p <- entry.presets do
        val values = p.values.toList.sortBy(_._1).map((k, v) => s"$k=$v").mkString(", ")
        val doc = if p.doc.isEmpty then "" else s" - ${p.doc}"
        out.println(s"  ${p.name}: $values$doc")
      val probes = entry.probes(entry.model.params.defaults).map(pr => s"${pr.name} [${pr.unit}]")
      out.println(s"probes: ${probes.mkString(", ")}")
      out.println(
        if entry.image.isDefined then "image: yes (frames and montages)"
        else "image: no (the view is not a field)"
      )
    }

  private def unknownModel(id: String): String =
    s"unknown model '$id' (known: ${catalog.keys.toList.sorted.mkString(", ")})"

  private def executeEntry(
      entry: Entry[Any, Any],
      o: Options,
      out: PrintStream,
      err: PrintStream
  ): Either[String, Int] =
    val schema = entry.model.params
    schema.withOverrides(schema.defaults, o.params).flatMap { p =>
      try
        if o.sweep.nonEmpty then sweep(entry, o, out, err)
        else
          (o.dataset, o.animate, o.frames) match
            case (Some(file), _, _) => dataset(entry, o, p, file, out, err)
            case (None, Some(file), _) => animation(entry, o, p, file, out, err)
            case (None, None, Some(dir)) => frames(entry, o, p, dir, out, err)
            case (None, None, None) => runAndPrint(entry, o, p, out, err)
      catch case e: IllegalArgumentException => Left(e.getMessage)
    }

  private def build(entry: Entry[Any, Any], o: Options, p: Any, probes: Boolean): Run[Any, Any] =
    Physis
      .model(entry.model)
      .withParams(p)
      .stepping(o.steppingFor(entry.stepping))
      .seed(o.seed)
      .sampleEvery(o.sampleEvery)
      .events(o.events)
      .interventions((o.interventions ++ o.strokes).sortBy(_.step))
      .probe((if probes then entry.probes(p) else Vector.empty)*)
      .build()

  private def runAndPrint(
      entry: Entry[Any, Any],
      o: Options,
      p: Any,
      out: PrintStream,
      err: PrintStream
  ): Either[String, Int] =
    val run = build(entry, o, p, probes = true)
    val result = o.steps match
      case Some(n) => run.runSteps(n)
      case None => run.runUntil(o.until)
    o.manifest.foreach { path =>
      val m = run.manifest(o.until).copy(steps = o.steps, digest = Some(result.finalDigest))
      Files.writeString(path, Manifest.toJson(m), StandardCharsets.UTF_8)
    }
    val names = result.series.keys.toVector
    out.println(("t" +: names).mkString(","))
    result.times.indices.foreach { i =>
      val row = result.times(i) +: names.map(name => result.series(name)(i))
      out.println(row.map(d => java.lang.Double.toString(d)).mkString(","))
    }
    err.println(
      f"# model ${o.model}, t = ${result.finalTime}%s, accepted ${result.acceptedSteps}%d, " +
        f"rejected ${result.rejectedSteps}%d, final digest ${result.finalDigest}%016x"
    )
    Right(digestCheck(o, result.finalDigest, err))

  /** 0, or 1 and a message when a replay did not end at the digest the manifest recorded. */
  private def digestCheck(o: Options, digest: Long, err: PrintStream): Int =
    o.expectedDigest match
      case Some(expected) if expected != digest =>
        err.println(
          f"# digest DIFFERS: the replay ended at $digest%016x, the manifest recorded $expected%016x"
        )
        1
      case _ => 0

  /** Numbered PNGs of the model's view, printed one path per line. */
  private def frames(
      entry: Entry[Any, Any],
      o: Options,
      p: Any,
      dir: Path,
      out: PrintStream,
      err: PrintStream
  ): Either[String, Int] =
    entry.image.toRight(s"model '${o.model}' has no image to export").flatMap { image =>
      val fixed: Either[String, Double] = o.steppingFor(entry.stepping) match
        case Stepping.Fixed(dt) => Right(dt)
        case _ => Left("frames need fixed steps")
      fixed.flatMap { dt =>
        val run = build(entry, o, p, probes = false)
        val steps = o.steps.getOrElse(math.round(o.until / dt))
        val options = Frames.Options(
          o.every.getOrElse(math.max(1L, steps / 10)),
          o.scale,
          o.colorBar,
          o.compress
        )
        Frames.render(run, steps, image.exporter, image.view, dir, options).map { files =>
          files.foreach(f => out.println(f.toString))
          val last = Frames.frame(Frames.snapshot(run, image.exporter), image.view, options)
          val range = last.fold("")(f => s", colours of the last frame from ${f.lo} to ${f.hi}")
          err.println(s"# ${files.size} frames of ${o.model} in $dir, $steps steps$range")
          writeManifest(o, run, steps, dt)
          digestCheck(o, run.digest, err)
        }
      }
    }

  /** The manifest of a finished frames or animation run, if `--manifest` asked for one. */
  private def writeManifest(o: Options, run: Run[Any, Any], steps: Long, dt: Double): Unit =
    o.manifest.foreach { path =>
      val m = run.manifest(steps * dt).copy(steps = Some(steps), digest = Some(run.digest))
      Files.writeString(path, Manifest.toJson(m), StandardCharsets.UTF_8)
    }

  /** One animated PNG of the model's view; its path is printed. */
  private def animation(
      entry: Entry[Any, Any],
      o: Options,
      p: Any,
      file: Path,
      out: PrintStream,
      err: PrintStream
  ): Either[String, Int] =
    entry.image.toRight(s"model '${o.model}' has no image to export").flatMap { image =>
      val fixed: Either[String, Double] = o.steppingFor(entry.stepping) match
        case Stepping.Fixed(dt) => Right(dt)
        case _ => Left("animations need fixed steps")
      fixed.flatMap { dt =>
        val run = build(entry, o, p, probes = false)
        val steps = o.steps.getOrElse(math.round(o.until / dt))
        val options = Frames.Options(
          o.every.getOrElse(math.max(1L, steps / 10)),
          o.scale,
          o.colorBar,
          o.compress,
          fps = o.fps,
          loops = o.loops
        )
        Frames.animation(run, steps, image.exporter, image.view, file, options).map { n =>
          out.println(file.toString)
          val seconds = n.toDouble / o.fps
          err.println(
            f"# animation of ${o.model} in $file: $n frames, $steps steps, ${o.fps} fps " +
              f"($seconds%.1f s), " + (if o.loops == 0 then "loops for ever"
                                       else s"plays ${o.loops} times")
          )
          writeManifest(o, run, steps, dt)
          digestCheck(o, run.digest, err)
        }
      }
    }

  /**
   * The run as a NetCDF dataset: fields, probes, time and a digest of the state at every
   * `--every` steps, with the manifest of the run in the file. Its path is printed.
   */
  private def dataset(
      entry: Entry[Any, Any],
      o: Options,
      p: Any,
      file: Path,
      out: PrintStream,
      err: PrintStream
  ): Either[String, Int] =
    Datasets.of(o.model).toRight(s"model '${o.model}' cannot be exported").flatMap { data =>
      val fixed: Either[String, Double] = o.steppingFor(entry.stepping) match
        case Stepping.Fixed(dt) => Right(dt)
        case _ => Left("datasets need fixed steps")
      fixed.flatMap { dt =>
        val run = build(entry, o, p, probes = false)
        val steps = o.steps.getOrElse(math.round(o.until / dt))
        val every = o.every.getOrElse(math.max(1L, steps / 10))
        // nothing has run yet, so the run has recorded no event or stroke: the manifest is
        // told the ones that are scheduled, as the replay of the file will need them
        val manifest = run
          .manifest(steps * dt)
          .copy(
            steps = Some(steps),
            events = o.events,
            interventions = (o.interventions ++ o.strokes).sortBy(_.step)
          )
        val options = NcExport.Options(
          every,
          o.single,
          o.vars,
          Vector(Attr.text("physis_manifest", Manifest.toJson(manifest)))
        )
        NcExport.write(run, steps, data.exporter, data.spec(p), entry.probes(p), file, options)
          .map { n =>
            out.println(file.toString)
            err.println(
              s"# dataset of ${o.model} in $file: $n records, $steps steps, every $every" +
                (if o.single then ", single precision" else "")
            )
            writeManifest(o, run, steps, dt)
            digestCheck(o, run.digest, err)
          }
      }
    }

  /** The first problem of the strokes of `o` for its model, if any. */
  private def checkStrokes(o: Options): Option[String] =
    catalog.get(o.model).flatMap { entry =>
      o.strokes.iterator
        .map { e =>
          e.intervention.validate
            .toLeft(())
            .flatMap(_ => entry.model.accepts(e.intervention))
            .left
            .map(m => s"stroke at step ${e.step}: $m")
        }
        .collectFirst { case Left(m) => m }
    }

  /** Reads `STEP:brush:...`, `STEP:pulse:...` or `STEP:disturb:...`. */
  def stroke(spec: String): Either[String, InterventionEvent] =
    def fail(why: String): Either[String, Nothing] = Left(s"--stroke '$spec': $why")
    def num(text: String, what: String): Either[String, Double] =
      text.toDoubleOption.filter(d => !d.isNaN && !d.isInfinite) match
        case Some(d) => Right(d)
        case None => fail(s"$what must be a number, got '$text'")
    def brush(target: String, x: String, y: String, r: String, op: String, value: Option[String]) =
      for
        cx <- num(x, "x")
        cy <- num(y, "y")
        cr <- num(r, "r")
        operation <- Op.parse(op).left.map(m => s"--stroke '$spec': $m")
        v <- value match
          case Some(text) => num(text, "value")
          case None if operation == Op.Erase => Right(0.0)
          case None => fail(s"a $op stroke needs a value")
      yield Intervention.Brush(target, Disc(cx, cy, cr), operation, v)
    def pulse(target: String, x: String, y: String, sigma: String, amplitude: String) =
      for
        cx <- num(x, "x")
        cy <- num(y, "y")
        sd <- num(sigma, "sigma")
        a <- num(amplitude, "amplitude")
      yield Intervention.Pulse(target, cx, cy, sd, a)
    def disturb(x: String, y: String, r: String, strength: String) =
      for
        cx <- num(x, "x")
        cy <- num(y, "y")
        cr <- num(r, "r")
        k <- num(strength, "strength")
      yield Intervention.Disturb(Disc(cx, cy, cr), k)
    val shape =
      "expected STEP:brush:TARGET:X:Y:R:OP:VALUE, STEP:pulse:TARGET:X:Y:SIGMA:AMPLITUDE or " +
        "STEP:disturb:X:Y:R:STRENGTH"
    val parts = spec.split(":", -1).toList
    val made: Either[String, Intervention] = parts.drop(1) match
      case "brush" :: target :: x :: y :: r :: op :: Nil => brush(target, x, y, r, op, None)
      case "brush" :: target :: x :: y :: r :: op :: value :: Nil =>
        brush(target, x, y, r, op, Some(value))
      case "pulse" :: target :: x :: y :: sigma :: amplitude :: Nil =>
        pulse(target, x, y, sigma, amplitude)
      case "disturb" :: x :: y :: r :: strength :: Nil => disturb(x, y, r, strength)
      case _ => fail(shape)
    for
      iv <- made
      step <- parts.head.toLongOption.filter(_ >= 0L) match
        case Some(n) => Right(n)
        case None => fail(s"the step must be a whole number from 0, got '${parts.head}'")
      _ <- iv.validate.toLeft(()).left.map(m => s"--stroke '$spec': $m")
    yield InterventionEvent(step, iv)

  /** What a sweep keeps of each run besides its probes. */
  private final case class Extra(manifest: String, picture: Option[Frame])

  /** The product of the axes as a CSV, in grid order; exit code 1 when a point failed. */
  private def sweep(
      entry: Entry[Any, Any],
      o: Options,
      out: PrintStream,
      err: PrintStream
  ): Either[String, Int] =
    if o.montage.isDefined && entry.image.isEmpty then
      Left(s"model '${o.model}' has no image for a montage")
    else
      ParamGrid.parse(o.sweep, entry.model.params).flatMap { grid =>
        if o.interventions.nonEmpty && grid.axes.exists(a => a.name == "nx" || a.name == "ny") then
          Left("a sweep over nx or ny cannot keep what the preset painted for one size")
        else if o.dryRun then dryRun(grid, o, out)
        else
          (if o.failFast then checkPoints(entry, o, grid) else Right(()))
            .flatMap(_ => runGrid(entry, o, grid, out, err))
      }

  /** What a sweep would run: the axes and the number of points. */
  private def dryRun(grid: ParamGrid, o: Options, out: PrintStream): Either[String, Int] =
    out.println(s"# sweep of ${o.model}: ${grid.size} points, nothing run")
    for axis <- grid.axes do
      out.println(s"# ${axis.name}: ${axis.values.map(java.lang.Double.toString).mkString(", ")}")
    Right(0)

  /** Every point against the schema and the model's own limits, before any run starts. */
  private def checkPoints(
      entry: Entry[Any, Any],
      o: Options,
      grid: ParamGrid
  ): Either[String, Unit] =
    val schema = entry.model.params
    val stepping = o.steppingFor(entry.stepping)
    grid.points.iterator
      .map { point =>
        schema
          .withOverrides(schema.defaults, o.params ++ point.values)
          .flatMap(p => entry.model.check(p, stepping))
          .left
          .map(e => s"point ${point.index} (${describePoint(point.values)}): $e")
      }
      .collectFirst { case Left(error) => error }
      .toLeft(())

  private def describePoint(values: scala.collection.Map[String, Double]): String =
    values.map((k, v) => s"$k=$v").mkString(", ")

  private def runGrid(
      entry: Entry[Any, Any],
      o: Options,
      grid: ParamGrid,
      out: PrintStream,
      err: PrintStream
  ): Either[String, Int] =
    val setup = SweepSetup[Any, Any](
      entry.model,
      entry.probes,
      o.steppingFor(entry.stepping),
      o.until,
      if o.until > 0.0 then o.until else 1.0,
      o.params,
      o.seed,
      o.interventions.sortBy(_.step)
    )
    val threads = o.threads.getOrElse(Sweep.defaultThreads)
    val tile = Frames.Options(every = 1, colorBar = false)
    val done = new java.util.concurrent.atomic.AtomicInteger(0)
    val results = Sweep.run(setup, grid, threads) { (run, result) =>
      if o.progress then err.println(s"# ${done.incrementAndGet()}/${grid.size}")
      val manifest =
        if o.manifests.isDefined then
          Manifest.toJson(run.manifest(o.until).copy(digest = Some(result.finalDigest)))
        else ""
      val picture = entry.image
        .filter(_ => o.montage.isDefined)
        .flatMap(i => Frames.frame(Frames.snapshot(run, i.exporter), i.view, tile))
      Extra(manifest, picture)
    }
    try
      val csv = SweepOutput.csv(grid, results)
      o.out match
        case Some(path) => Files.writeString(path, csv, StandardCharsets.UTF_8)
        case None => out.print(csv)
      o.jsonl.foreach(path =>
        Files.writeString(path, SweepOutput.jsonLines(results), StandardCharsets.UTF_8)
      )
      val manifests = results.map(r =>
        PointResult(r.point, r.outcome.map(o => o.copy(extra = o.extra.manifest)))
      )
      val written = o.manifests match
        case Some(dir) => SweepOutput.writeManifests(dir, manifests).map(_.size)
        case None => Right(0)
      written.flatMap(_ => writeMontage(grid, results, o, err)).map { _ =>
        val failed = results.filter(_.outcome.isLeft)
        failed.foreach(r =>
          err.println(s"# point ${r.point.index}: ${r.outcome.left.getOrElse("")}")
        )
        err.println(
          s"# sweep of ${o.model}: ${grid.size} points on $threads threads, ${failed.size} failed"
        )
        if failed.isEmpty then 0 else 1
      }
    catch case e: java.io.IOException => Left(s"cannot write sweep output: ${e.getMessage}")

  /** Tiles the final pictures of a sweep into the `--montage` file, if one was asked for. */
  private def writeMontage(
      grid: ParamGrid,
      results: Vector[PointResult[Extra]],
      o: Options,
      err: PrintStream
  ): Either[String, Unit] =
    o.montage match
      case None => Right(())
      case Some(path) =>
        val columns = o.montageColumns.getOrElse(
          if grid.axes.size >= 2 then grid.axes.last.values.size
          else math.ceil(math.sqrt(grid.size.toDouble)).toInt
        )
        val tiles = results.map(_.outcome.toOption.flatMap(_.extra.picture))
        Montage.tile(tiles, columns).map { m =>
          Png.write(path, m.pixels, m.width, m.height, o.scale, o.compress)
          err.println(
            s"# montage of ${tiles.count(_.isDefined)} pictures in $path, " +
              s"${m.width * o.scale} x ${m.height * o.scale} px, $columns to a row"
          )
        }

  private def number(text: String, option: String): Either[String, Double] =
    text.toDoubleOption.toRight(s"$option expects a number, got '$text'")

  private def whole(text: String, option: String, least: Long): Either[String, Long] =
    text.toLongOption
      .toRight(s"$option expects an integer, got '$text'")
      .filterOrElse(_ >= least, s"$option must be at least $least, got $text")
