package physis.cli

import java.io.PrintStream

import scala.annotation.tailrec

import physis.core.BuildInfo

/** What a command line asks for, before any model is touched. */
sealed trait Plan
object Plan:
  /** The usage text, of one command if named. */
  final case class Help(command: Option[String]) extends Plan
  case object Version extends Plan

  /** The parameters, presets and probes of one model. */
  final case class Describe(model: String) extends Plan

  /** Run the engine with these (checked) options of [[Headless]]. */
  final case class Engine(args: List[String]) extends Plan

/**
 * The `physis-cli` command: `physis-cli <command> [options]`.
 *
 * A command is a front for the options of [[Headless]]: it says which of them make sense
 * together, takes the main argument by position (`frames out`, `replay run.json`) and turns it
 * into the option the engine knows. A misplaced option is an error that names the command, and
 * a usage error is exit code 2; a run that fails is 1.
 */
object Cli:
  /** The options a command takes: those with a value, and those without. */
  private final case class Spec(valued: Set[String], flags: Set[String] = Set.empty):
    def ++(other: Spec): Spec = Spec(valued ++ other.valued, flags ++ other.flags)

  private val model = Spec(Set("--model", "--preset", "--param", "--seed"))
  private val time = Spec(Set("--until", "--steps", "--sample-every", "--dt", "--adaptive"))
  private val image = Spec(Set("--every", "--scale"), Set("--no-color-bar", "--compress"))

  private val strokes = Spec(Set("--stroke", "--manifest"))
  private val movie = Spec(Set("--fps", "--loops"))

  private val runSpec = model ++ time ++ strokes
  private val framesSpec = model ++ time ++ image ++ strokes
  private val animateSpec = framesSpec ++ movie
  private val sweepSpec = model ++ time ++
    Spec(
      Set("--sweep", "--threads", "--out", "--jsonl", "--manifests", "--montage", "--scale"),
      Set("--compress", "--progress", "--quiet", "--dry-run", "--fail-fast")
    )
  private val montageSpec = sweepSpec ++ Spec(Set("--montage-columns"))
  private val replaySpec = animateSpec ++ Spec(Set("--frames", "--animate"))

  val Usage: String =
    """Usage: physis-cli <command> [options]
      |
      |Commands
      |  run                 run a model and print its probes as CSV
      |  frames <dir>        write numbered PNGs of a run into <dir>
      |  animate <file>      write the frames of a run as one animated PNG
      |  sweep               run a grid of parameters, one run per point, and print a CSV
      |  montage <png>       like sweep, and tile the final images of all points into <png>
      |  replay <manifest>   re-run a manifest, with its changes and strokes
      |  list                list the models with their parameters
      |  describe <model>    one model in full: parameters, presets, probes
      |
      |  --version           print the version
      |  --help              print this text; 'physis-cli <command> --help' the options
      |
      |Exit codes: 0 done, 1 the run or a sweep point failed, 2 the command line is wrong.""".stripMargin

  private val Common: String =
    """  --model <id>           lorenz | double-pendulum | gray-scott | boids | wave | physarum | fluid |
      |                         convection | shallow (default: lorenz)
      |  --preset <name>        start from a preset of the model; --param is applied after it
      |  --param <name=value>   override a model parameter (repeatable)
      |  --seed <n>             run seed (default: 0)
      |  --until <t>            end time (default: 10)
      |  --steps <n>            run n steps instead of up to a time (fixed steps only)
      |  --sample-every <dt>    probe sampling interval (default: 0.1)
      |  --dt <dt>              fixed step (default: the model's own)
      |  --adaptive <tol>       Dormand-Prince with rtol = atol = tol instead of fixed steps""".stripMargin

  private val Strokes: String =
    """  --stroke <spec>        a stroke at a step, recorded in the manifest (repeatable):
      |                         STEP:brush:TARGET:X:Y:R:OP:VALUE (OP is set, add or erase),
      |                         STEP:pulse:TARGET:X:Y:SIGMA:AMPLITUDE, STEP:disturb:X:Y:R:STRENGTH
      |  --manifest <path>      write the manifest of the run, with the strokes, to <path>""".stripMargin

  private val Pictures: String =
    """  --scale <n>            pixels per cell, 1 to 16 (default: 1)
      |  --compress             deflate the PNG data (smaller files, same pixels)""".stripMargin

  private val SweepOptions: String =
    """  --sweep <axis>         name=a:b:n | name=log:a:b:n | name=a,b,c | seed=a..b (repeatable)
      |  --threads <n>          runs at the same time (default: one per processor)
      |  --out <path>           write the CSV to <path> instead of standard output
      |  --jsonl <path>         also write one JSON object per point
      |  --manifests <dir>      also write point-NNNN.json, a manifest per point
      |  --quiet                no "# done/total" lines on standard error
      |  --dry-run              print the axes and the number of points and run nothing
      |  --fail-fast            check every point first and run nothing if one is refused""".stripMargin

  /** The text of `physis-cli <command> --help`. */
  def usageOf(command: String): String = command match
    case "run" =>
      s"Usage: physis-cli run [options]\n\nPrints the probes of a run as CSV.\n$Common\n$Strokes"
    case "frames" =>
      s"Usage: physis-cli frames <dir> [options]\n\nNumbered PNGs of the first image of the view " +
        s"(gray-scott, wave, physarum, fluid, convection, shallow); one path per line.\n$Common\n$Strokes\n" +
        "  --every <n>            steps between frames (default: a tenth of the run)\n" +
        s"  --no-color-bar         leave out the colour scale at the right\n$Pictures"
    case "animate" =>
      s"Usage: physis-cli animate <file> [options]\n\nThe frames of a run as one animated PNG; " +
        s"the file name is printed.\n$Common\n$Strokes\n" +
        "  --every <n>            steps between frames (default: a tenth of the run)\n" +
        "  --fps <n>              frames per second, 1 to 65535 (default: 12)\n" +
        "  --loops <n>            times it plays, 0 for ever (default: 0)\n" +
        s"  --no-color-bar         leave out the colour scale at the right\n$Pictures"
    case "sweep" =>
      s"Usage: physis-cli sweep --sweep <axis> [--sweep <axis>...] [options]\n\n" +
        s"One run per point of the product of the axes, results in grid order whatever the " +
        s"number of threads.\n$Common\n$SweepOptions"
    case "montage" =>
      s"Usage: physis-cli montage <png> --sweep <axis> [...] [options]\n\n" +
        s"A sweep that also tiles the final images of all points into one PNG, the last axis " +
        s"along a row.\n$Common\n$SweepOptions\n" +
        s"  --montage-columns <n>  tiles per row instead\n$Pictures"
    case "replay" =>
      "Usage: physis-cli replay <manifest> [--frames <dir> | --animate <file>] [options]\n\n" +
        "Re-runs a manifest with its parameter changes and strokes and prints the CSV.\n" +
        "  --frames <dir>         write frames instead\n" +
        "  --animate <file>       write an animated PNG instead\n" +
        "  --every <n>, --scale <n>, --no-color-bar, --compress, --fps <n>, --loops <n>  " +
        "as for animate\n" +
        "  --stroke <spec>        more strokes (they end the digest check), --manifest <path> " +
        "records the result"
    case "list" => "Usage: physis-cli list\n\nLists the models with their parameters."
    case "describe" =>
      "Usage: physis-cli describe <model>\n\nParameters with range and unit, presets, probes, " +
        "the step and whether the model has an image."
    case _ => Usage

  /** Reads the command line. `Left` is a usage error. */
  def parse(args: List[String]): Either[String, Plan] = args match
    case Nil => Left("no command given")
    case ("--help" | "-h" | "help") :: _ => Right(Plan.Help(None))
    case "--version" :: _ => Right(Plan.Version)
    case command :: ("--help" | "-h") :: _ if Commands.contains(command) =>
      Right(Plan.Help(Some(command)))
    case "run" :: rest => checked("run", rest, runSpec).map(Plan.Engine(_))
    case "frames" :: rest =>
      positional("frames", "a directory", rest).flatMap { (dir, more) =>
        checked("frames", more, framesSpec).map(a => Plan.Engine("--frames" :: dir :: a))
      }
    case "animate" :: rest =>
      positional("animate", "a PNG file", rest).flatMap { (file, more) =>
        checked("animate", more, animateSpec).map(a => Plan.Engine("--animate" :: file :: a))
      }
    case "sweep" :: rest => sweep("sweep", rest, sweepSpec).map(a => Plan.Engine(progress(a)))
    case "montage" :: rest =>
      positional("montage", "a PNG file", rest).flatMap { (png, more) =>
        sweep("montage", more, montageSpec)
          .map(a => Plan.Engine("--montage" :: png :: progress(a)))
      }
    case "replay" :: rest =>
      positional("replay", "a manifest file", rest).flatMap { (file, more) =>
        checked("replay", more, replaySpec).map(a => Plan.Engine("--replay" :: file :: a))
      }
    case "list" :: Nil => Right(Plan.Engine(List("--list")))
    case "list" :: _ => Left("list takes no arguments")
    case "describe" :: id :: Nil if !id.startsWith("-") => Right(Plan.Describe(id))
    case "describe" :: _ => Left("describe takes one model: physis-cli describe <model>")
    case other :: _ if other.startsWith("-") =>
      Left(s"unknown option '$other' (a command comes first)")
    case other :: _ => Left(s"unknown command '$other'")

  /** A sweep reports its progress unless asked to be quiet; --quiet is not an engine option. */
  private def progress(args: List[String]): List[String] =
    if args.contains("--quiet") then args.filterNot(_ == "--quiet")
    else "--progress" :: args

  val Commands: Vector[String] =
    Vector("run", "frames", "animate", "sweep", "montage", "replay", "list", "describe")

  private def sweep(command: String, args: List[String], spec: Spec): Either[String, List[String]] =
    checked(command, args, spec).flatMap { a =>
      if a.contains("--sweep") then Right(a)
      else Left(s"$command needs at least one --sweep axis, e.g. --sweep F=0.02:0.06:5")
    }

  /** The first argument, which must not be an option, and the rest. */
  private def positional(
      command: String,
      what: String,
      args: List[String]
  ): Either[String, (String, List[String])] = args match
    case first :: rest if !first.startsWith("--") => Right((first, rest))
    case _ => Left(s"$command needs $what first: physis-cli $command <...> [options]")

  /** `args` if every option in it belongs to `spec`. */
  private def checked(
      command: String,
      args: List[String],
      spec: Spec
  ): Either[String, List[String]] =
    @tailrec def loop(rest: List[String]): Either[String, Unit] = rest match
      case Nil => Right(())
      case flag :: tail if spec.flags.contains(flag) => loop(tail)
      case option :: _ :: tail if spec.valued.contains(option) => loop(tail)
      case option :: Nil if spec.valued.contains(option) => Left(s"$option expects a value")
      case option :: _ if option.startsWith("--") => Left(s"$command does not take $option")
      case other :: _ => Left(s"unexpected argument '$other' for $command")
    loop(args).map(_ => args)

  /** Runs the command line; returns the exit code. */
  def execute(args: List[String], out: PrintStream, err: PrintStream): Int =
    parse(args) match
      case Left(error) =>
        err.println(s"error: $error")
        err.println(Usage)
        2
      case Right(Plan.Help(command)) =>
        out.println(command.fold(Usage)(usageOf))
        0
      case Right(Plan.Version) =>
        out.println(s"physis-cli ${BuildInfo.version}")
        0
      case Right(Plan.Describe(id)) =>
        Headless.describe(id, out) match
          case Right(()) => 0
          case Left(error) =>
            err.println(s"error: $error")
            1
      case Right(Plan.Engine(engineArgs)) =>
        Headless.parse(engineArgs) match
          case Left(error) =>
            err.println(s"error: $error")
            2
          case Right(options) => Headless.run(options, out, err)
