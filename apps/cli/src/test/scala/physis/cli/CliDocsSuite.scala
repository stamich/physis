package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** The examples in docs/cli.md run as written, in order, with their files in a temp directory. */
class CliDocsSuite extends munit.FunSuite:
  private val docs = Path.of("..", "..", "docs", "cli.md")

  /** The `physis-cli ...` lines of the bash blocks, a trailing backslash joining lines. */
  private def commands(text: String): Vector[List[String]] =
    val lines = text.linesIterator.toVector
    val blocks = Vector.newBuilder[String]
    var inBash = false
    var pending = ""
    for line <- lines do
      if line.startsWith("```") then
        inBash = line.trim == "```bash"
        pending = ""
      else if inBash then
        if line.endsWith("\\") then pending = pending + line.dropRight(1) + " "
        else
          val whole = (pending + line).trim
          pending = ""
          if whole.startsWith("physis-cli ") then blocks += whole
    blocks.result().map(_.split("\\s+").toList.drop(1))

  private val fileOptions =
    Set("--out", "--jsonl", "--manifests", "--manifest", "--frames", "--animate")

  /** The same arguments with every file or directory moved into `dir`. */
  private def inside(dir: Path, args: List[String]): List[String] =
    def moved(name: String): String = dir.resolve(name).toString
    val main = args match
      case ("frames" | "animate" | "montage" | "replay") :: first :: rest =>
        args.head :: moved(first) :: rest
      case _ => args
    def go(rest: List[String]): List[String] = rest match
      case option :: path :: tail if fileOptions.contains(option) =>
        option :: moved(path) :: go(tail)
      case other :: tail => other :: go(tail)
      case Nil => Nil
    go(main)

  test("the examples of docs/cli.md run with exit code 0") {
    assert(Files.exists(docs), s"${docs.toAbsolutePath.normalize} is not there")
    val all = commands(Files.readString(docs))
    assert(all.size >= 10, s"only ${all.size} examples found")
    val dir = Files.createTempDirectory("physis-cli-docs")
    try
      for args <- all do
        val out = new ByteArrayOutputStream()
        val err = new ByteArrayOutputStream()
        val code = Cli.execute(
          inside(dir, args),
          new PrintStream(out, true, StandardCharsets.UTF_8),
          new PrintStream(err, true, StandardCharsets.UTF_8)
        )
        assertEquals(
          code,
          0,
          s"physis-cli ${args.mkString(" ")}: ${err.toString(StandardCharsets.UTF_8)}"
        )
    finally
      val stream = Files.walk(dir)
      try
        stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { f =>
          val _ = Files.deleteIfExists(f)
        }
      finally stream.close()
  }

  test("every command of the parser has a section in the documentation") {
    val text = Files.readString(docs)
    for command <- Cli.Commands do
      assert(text.contains(s"`$command"), s"$command is not documented")
    assert(
      text.contains("`--dry-run`") && text.contains("`--fail-fast`") && text.contains("`--quiet`")
    )
  }
