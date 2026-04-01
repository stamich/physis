package physis.examples

import java.io.PrintStream

import physis.cli.Headless

/**
 * Runs a model without a UI and prints its probes as CSV: the same as `physis-cli` without a
 * subcommand, kept as the example it was in 0.1 to 0.5. The work is done by
 * [[physis.cli.Headless]] in `apps/cli`.
 *
 * {{{
 * ./gradlew :examples:run --args="--model lorenz --until 20 --sample-every 0.05"
 * ./gradlew :examples:run --args="--model physarum --steps 600 --frames out --every 60 --scale 3"
 * }}}
 */
object RunHeadless:
  val Usage: String = Headless.Usage

  type Options = Headless.Options

  /** Parses command-line arguments. `Left("help")` asks for the usage text. */
  def parse(args: List[String], options: Options = Headless.Options()): Either[String, Options] =
    Headless.parse(args, options)

  /** Runs with `options`, writing CSV to `out` and a summary to `err`; returns the exit code. */
  def run(options: Options, out: PrintStream, err: PrintStream): Int =
    Headless.run(options, out, err)

  def main(args: Array[String]): Unit = Headless.main(args)
