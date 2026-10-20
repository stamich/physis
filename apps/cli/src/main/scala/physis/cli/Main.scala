package physis.cli

/** The `physis-cli` command; see [[Cli]]. */
object Main:
  def main(args: Array[String]): Unit =
    val code = Cli.execute(args.toList, Console.out, Console.err)
    if code != 0 then sys.exit(code)
