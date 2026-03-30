package physis.verify

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * Keeps the numbers that the speed tests measure, so that the bars can be set from what CI
 * really does and not from a guess. `record` writes `build/speed/<name>.txt` in the directory
 * the tests run in (the project's own); CI prints those files in the job summary.
 */
object SpeedReport:
  /** The directory the files go to, relative to the working directory. */
  val Directory: Path = Path.of("build", "speed")

  /** The line that `record` writes: `physarum agent steps: 31.4 million/s`. */
  def line(name: String, value: Double, unit: String): String =
    f"$name%s: $value%.2f $unit%s"

  /** Writes the line to `<dir>/<file name>.txt` and returns it. Failures to write are ignored. */
  def record(name: String, value: Double, unit: String, dir: Path = Directory): String =
    val text = line(name, value, unit)
    try
      val _ = Files.createDirectories(dir)
      val file = name.map(c => if c.isLetterOrDigit then c else '-').mkString
      val _ = Files.writeString(dir.resolve(s"$file.txt"), text + "\n", StandardCharsets.UTF_8)
    catch case _: java.io.IOException => ()
    text
