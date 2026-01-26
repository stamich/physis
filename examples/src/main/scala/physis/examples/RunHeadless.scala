package physis.examples

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.{Model, Probe, Stepping}
import physis.core.run.{Manifest, Physis}
import physis.models.lorenz.{Lorenz, LorenzParams}
import physis.models.pendulum.{Pendulum, PendulumParams}
import physis.ode.OdeState

/**
 * Runs a model without a UI and prints its probes as CSV.
 *
 * {{{
 * ./gradlew :examples:run --args="--model lorenz --until 20 --sample-every 0.05"
 * ./gradlew :examples:run --args="--model double-pendulum --param theta1=90 --adaptive 1e-9 --manifest run.json"
 * ./gradlew :examples:run --args="--replay run.json"
 * }}}
 *
 * A summary with the final state digest goes to standard error; a replay of a manifest prints
 * the same CSV and the same digest.
 */
object RunHeadless:

  val Usage: String =
    """Usage: RunHeadless [options]
      |  --model <id>           lorenz | double-pendulum (default: lorenz)
      |  --param <name=value>   override a model parameter (repeatable)
      |  --until <t>            end time (default: 10)
      |  --sample-every <dt>    probe sampling interval (default: 0.1)
      |  --dt <dt>              fixed RK4 step (default: 0.001)
      |  --adaptive <tol>       Dormand-Prince with rtol = atol = tol instead of fixed steps
      |  --seed <n>             run seed (default: 0)
      |  --manifest <path>      write the run manifest to <path>
      |  --replay <path>        re-run the manifest at <path>; other options are ignored
      |  --help                 show this help""".stripMargin

  final case class Options(
      model: String = Lorenz.Id,
      params: Map[String, Double] = Map.empty,
      until: Double = 10.0,
      sampleEvery: Double = 0.1,
      dt: Double = 0.001,
      adaptiveTolerance: Option[Double] = None,
      seed: Long = 0L,
      manifest: Option[Path] = None,
      replay: Option[Path] = None,
      exactStepping: Option[Stepping] = None
  ):
    /** Stepping from a manifest when replaying, otherwise from --dt / --adaptive. */
    def stepping: Stepping = exactStepping.getOrElse(
      adaptiveTolerance match
        case Some(tol) =>
          Stepping.Adaptive(tol, tol, initialDt = math.min(1e-3, sampleEvery), maxDt = sampleEvery)
        case None => Stepping.Fixed(dt)
    )

  /** A model this example can run, with the probes it prints. */
  private final case class Entry[P](
      model: Model.Aux[P, OdeState],
      probes: P => Vector[Probe[OdeState]]
  )

  private val catalog: Map[String, Entry[?]] = Map(
    Lorenz.Id -> Entry[LorenzParams](Lorenz.model, _ => Lorenz.probes),
    Pendulum.Id -> Entry[PendulumParams](Pendulum.model, Pendulum.probes)
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
        number(v, "--dt").flatMap(d => parse(rest, options.copy(dt = d)))
      case "--adaptive" :: v :: rest =>
        number(v, "--adaptive").flatMap(d => parse(rest, options.copy(adaptiveTolerance = Some(d))))
      case "--seed" :: v :: rest =>
        v.toLongOption.toRight(s"--seed expects an integer, got '$v'")
          .flatMap(s => parse(rest, options.copy(seed = s)))
      case "--manifest" :: path :: rest => parse(rest, options.copy(manifest = Some(Path.of(path))))
      case "--replay" :: path :: rest => parse(rest, options.copy(replay = Some(Path.of(path))))
      case option :: Nil if option.startsWith("--") => Left(s"$option expects a value")
      case other :: _ => Left(s"unknown argument '$other'")

  /** Runs with `options`, writing CSV to `out` and a summary to `err`; returns the exit code. */
  def run(options: Options, out: PrintStream, err: PrintStream): Int =
    val resolved = options.replay match
      case Some(path) => fromManifest(path)
      case None => Right(options)
    resolved.flatMap(o => execute(o, out, err)) match
      case Right(()) => 0
      case Left(error) =>
        err.println(s"error: $error")
        1

  private def fromManifest(path: Path): Either[String, Options] =
    val text =
      try Right(Files.readString(path, StandardCharsets.UTF_8))
      catch case e: java.io.IOException => Left(s"cannot read $path: ${e.getMessage}")
    text.flatMap(Manifest.fromJson).flatMap { m =>
      catalog.get(m.model) match
        case None => Left(s"unknown model '${m.model}'")
        case Some(entry) if entry.model.version != m.modelVersion =>
          Left(s"model '${m.model}' is version ${entry.model.version}, manifest needs ${m.modelVersion}")
        case Some(_) =>
          Right(
            Options(
              model = m.model,
              params = m.params,
              until = m.until,
              sampleEvery = m.sampleEvery,
              seed = m.seed,
              exactStepping = Some(m.stepping)
            )
          )
    }

  private def execute(o: Options, out: PrintStream, err: PrintStream): Either[String, Unit] =
    catalog.get(o.model) match
      case None => Left(s"unknown model '${o.model}' (known: ${catalog.keys.toList.sorted.mkString(", ")})")
      case Some(entry) => executeEntry(entry.asInstanceOf[Entry[Any]], o, out, err)

  private def executeEntry(
      entry: Entry[Any],
      o: Options,
      out: PrintStream,
      err: PrintStream
  ): Either[String, Unit] =
    val schema = entry.model.params
    schema.withOverrides(schema.defaults, o.params).flatMap { p =>
      try
        val run = Physis
          .model(entry.model)
          .withParams(p)
          .stepping(o.stepping)
          .seed(o.seed)
          .sampleEvery(o.sampleEvery)
          .probe(entry.probes(p)*)
          .build()
        o.manifest.foreach { path =>
          Files.writeString(path, Manifest.toJson(run.manifest(o.until)), StandardCharsets.UTF_8)
        }
        val result = run.runUntil(o.until)
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
        Right(())
      catch case e: IllegalArgumentException => Left(e.getMessage)
    }

  private def number(text: String, option: String): Either[String, Double] =
    text.toDoubleOption.toRight(s"$option expects a number, got '$text'")
