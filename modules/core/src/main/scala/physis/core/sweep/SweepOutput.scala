package physis.core.sweep

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.run.{Manifest, Run, RunResult}

/** Turns the results of a [[Sweep]] into files: a table, JSON Lines, and a manifest per run. */
object SweepOutput:

  /** Fixed columns after the axes: `ok`, then the run's end, then the probes, then `error`. */
  private val Fixed = Vector("ok", "time", "steps", "rejected", "digest")

  /** Names of the probes that appear in the results, in the order they first appear. */
  private def probeNames(results: Seq[PointResult[?]]): Vector[String] =
    results.iterator
      .flatMap(_.outcome.toOption.iterator.flatMap(_.finalProbes.keysIterator))
      .toVector
      .distinct

  private def axisText(grid: ParamGrid, point: GridPoint, axis: String): String =
    if axis == ParamGrid.SeedName then point.seed.fold("")(_.toString)
    else point.values.get(axis).fold("")(number)

  private def number(d: Double): String = java.lang.Double.toString(d)

  private def hex(digest: Long): String = f"$digest%016x"

  /**
   * One row per point, in grid order: `index`, one column per axis, `ok` (true or false),
   * `time`, `steps`, `rejected`, `digest` (16 hex digits), one column per probe with the value
   * at the end of the run, and `error`. Fields with a comma, quote or line break are quoted.
   */
  def csv(grid: ParamGrid, results: Vector[PointResult[?]]): String =
    val axes = grid.axes.map(_.name)
    val probes = probeNames(results)
    val out = new StringBuilder
    def row(cells: Seq[String]): Unit =
      val _ = out.append(cells.map(quote).mkString(",")).append('\n')
    row(("index" +: axes) ++ Fixed ++ probes :+ "error")
    for r <- results do
      val head = r.point.index.toString +: axes.map(axisText(grid, r.point, _))
      r.outcome match
        case Right(o) =>
          row(
            head ++ Seq("true", number(o.finalTime), o.acceptedSteps.toString) ++
              Seq(o.rejectedSteps.toString, hex(o.digest)) ++
              probes.map(p => o.finalProbes.get(p).fold("")(number)) :+ ""
          )
        case Left(error) =>
          row(head ++ Seq("false") ++ Seq.fill(Fixed.size - 1 + probes.size)("") :+ error)
    out.toString

  /**
   * One JSON object per line, in grid order:
   * `{"index":0,"params":{"F":0.02},"seed":1,"ok":true,"time":..,"steps":..,"rejected":..,
   * "digest":"..","probes":{..}}`, or `"ok":false` with an `"error"` text. Numbers that JSON
   * cannot hold (NaN, infinity) are written as `null`.
   */
  def jsonLines(results: Vector[PointResult[?]]): String =
    val out = new StringBuilder
    for r <- results do
      val p = r.point
      out.append("{\"index\":").append(p.index)
      out.append(",\"params\":{")
      out.append(p.values.map((k, v) => s"${string(k)}:${jsonNumber(v)}").mkString(","))
      out.append('}')
      p.seed.foreach(s => out.append(",\"seed\":").append(s))
      r.outcome match
        case Right(o) =>
          out.append(",\"ok\":true,\"time\":").append(jsonNumber(o.finalTime))
          out.append(",\"steps\":").append(o.acceptedSteps)
          out.append(",\"rejected\":").append(o.rejectedSteps)
          out.append(",\"digest\":").append(string(hex(o.digest)))
          out.append(",\"probes\":{")
          out.append(o.finalProbes.map((k, v) => s"${string(k)}:${jsonNumber(v)}").mkString(","))
          out.append('}')
        case Left(error) =>
          out.append(",\"ok\":false,\"error\":").append(string(error))
      out.append("}\n")
    out.toString

  /**
   * An `extract` for [[Sweep.run]] that keeps each run's manifest as JSON, with its step count
   * and final digest, so that [[writeManifests]] can store it and a replay can check it.
   */
  def manifestJson[P, S](until: Double): (Run[P, S], RunResult) => String =
    (run, _) => Manifest.toJson(run.manifest(until))

  /**
   * Writes `point-<index>.json` for every run that finished, into `dir` (created if needed).
   * Returns the files in grid order.
   */
  def writeManifests(
      dir: Path,
      results: Vector[PointResult[String]]
  ): Either[String, Vector[Path]] =
    try
      Files.createDirectories(dir)
      val width = math.max(4, (results.size - 1).toString.length)
      Right(results.flatMap { r =>
        r.outcome.toOption.map { o =>
          val file =
            dir.resolve(s"point-${r.point.index.toString.reverse.padTo(width, '0').reverse}.json")
          Files.writeString(file, o.extra, StandardCharsets.UTF_8)
          file
        }
      })
    catch case e: java.io.IOException => Left(s"could not write manifests to $dir: ${e.getMessage}")

  private def quote(field: String): String =
    if field.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + field.replace("\"", "\"\"") + "\""
    else field

  private def jsonNumber(d: Double): String = if d.isFinite then number(d) else "null"

  private def string(text: String): String =
    val out = new StringBuilder("\"")
    text.foreach {
      case '"' => out.append("\\\"")
      case '\\' => out.append("\\\\")
      case '\n' => out.append("\\n")
      case '\r' => out.append("\\r")
      case '\t' => out.append("\\t")
      case c if c < ' ' => out.append(f"\\u${c.toInt}%04x")
      case c => out.append(c)
    }
    out.append('"').toString
