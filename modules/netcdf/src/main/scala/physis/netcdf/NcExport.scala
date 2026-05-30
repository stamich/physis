package physis.netcdf

import java.io.{BufferedOutputStream, IOException, OutputStream}
import java.nio.file.{Files, Path}

import scala.collection.immutable.ArraySeq

import physis.core.{BuildInfo, Probe}
import physis.core.live.{ExportSpec, FieldMeta, Slice, StateExport}
import physis.core.run.Run

/**
 * Turns a run into a NetCDF file: a record at the start and after every so many steps, each
 * with the time, the step, a digest of the state, the fields of the model's [[ExportSpec]] and
 * the values of the probes. Fields are written on the grid `time, y, x`, where `x` and `y` are
 * the centres of the cells in the unit of the model. The file is written a record at a time, so
 * memory holds one record whatever the length of the run.
 *
 * The digest of the state (`Run.digest`, 64 bits) is in two 32-bit variables, `digest_hi` and
 * `digest_lo`, so that a file can be checked against a replay of the run that made it.
 */
object NcExport:

  /**
   * @param every  steps between two records; the first record is the state before any step
   * @param single whether the fields are written as 32-bit floats (half the size) instead of
   *               64-bit
   * @param only   the names of the variables to write, among the fields and the probes; all if
   *               empty
   * @param attrs  more global attributes, for example the manifest of the run
   */
  final case class Options(
      every: Long,
      single: Boolean = false,
      only: Option[Set[String]] = None,
      attrs: Vector[Attr] = Vector.empty
  ):
    require(every >= 1, s"export: 'every' must be at least 1, got $every")

  /** The message of a write that was stopped by its cancel flag. */
  val Cancelled: String = "cancelled"

  /** Names the writer uses itself. */
  private val Reserved = Set("time", "step", "digest_hi", "digest_lo", "x", "y")

  /**
   * How many records a run that is now at step `from` writes up to step `steps`: the one now,
   * and one at every multiple of `every` after it.
   */
  def recordCount(from: Long, steps: Long, every: Long): Int =
    require(every >= 1, s"export: 'every' must be at least 1, got $every")
    if steps <= from then 1 else (1L + steps / every - from / every).toInt

  /** What is written: the structure of the file, and where each part comes from. */
  final case class Plan[S](
      schema: Schema,
      fields: Vector[(FieldMeta, Slice)],
      probes: Vector[(String, Probe[S])],
      records: Int
  ):
    /** The size of the file in bytes. */
    def bytes: Long = NcWriter.size(schema, records)

  private def probeVariable(name: String, taken: Set[String]): String =
    if taken.contains(name) then s"probe_$name" else name

  /** Works out the file for a run in state `state` at step `from`, written until `steps`. */
  def plan[S](
      state: S,
      from: Long,
      steps: Long,
      modelId: String,
      modelVersion: String,
      exporter: StateExport[S],
      spec: ExportSpec,
      probes: Vector[Probe[S]],
      options: Options
  ): Either[String, Plan[S]] =
    val slices = exporter.layout(state)
    val wanted = options.only
    val fieldNames = spec.fields.map(_.name)
    val probeSources = probes.map(p => p.name)
    val unknown = wanted.map(_.diff((fieldNames ++ probeSources).toSet)).getOrElse(Set.empty)
    if unknown.nonEmpty then
      Left(
        s"no variable ${unknown.toVector.sorted.map(n => s"'$n'").mkString(", ")} " +
          s"(available: ${(fieldNames ++ probeSources).mkString(", ")})"
      )
    else
      val chosen = spec.fields.filter(f => wanted.forall(_.contains(f.name)))
      val located = chosen.map(f =>
        slices.find(_.name == f.slice).toRight(s"the state has no slice '${f.slice}'").map(f -> _)
      )
      located.collectFirst { case Left(why) => why } match
        case Some(why) => Left(why)
        case None =>
          val fields = located.collect { case Right(x) => x }
          val sizes = fields.map((_, s) => (s.width, s.height)).distinct
          if sizes.size > 1 then Left("the fields are not all on one grid")
          else
            val taken = (Reserved ++ chosen.map(_.name)).toSet
            val chosenProbes = probes.filter(p => wanted.forall(_.contains(p.name)))
            val named = chosenProbes.map(p => probeVariable(p.name, taken) -> p)
            val badProbe = named.map(_._1).find(n => !Schema.validName(n))
            if badProbe.isDefined then Left(s"bad variable name '${badProbe.get}'")
            else
              val records = recordCount(from, steps, options.every)
              val tpe = if options.single then NcType.Float32 else NcType.Float64
              val grid = sizes.headOption
              val dims = Vector(Dim.record("time")) ++
                grid.toVector.flatMap((w, h) => Vector(Dim.fixed("y", h), Dim.fixed("x", w)))
              val space =
                if grid.isDefined then
                  Vector(
                    Cf.axis("x", "X", spec.lengthUnits, "x of the centre of the cell"),
                    Cf.axis(
                      "y",
                      "Y",
                      spec.lengthUnits,
                      if spec.yDown then "y of the centre of the cell, running south"
                      else "y of the centre of the cell",
                      down = spec.yDown
                    )
                  )
                else Vector.empty
              val fieldVars = fields.map { (f, _) =>
                Var(
                  f.name,
                  Vector("time", "y", "x"),
                  tpe,
                  Vector(Attr.text("long_name", f.longName), Attr.text("units", f.units)) ++
                    f.standardName.map(Attr.text("standard_name", _)).toVector
                )
              }
              val probeVars = named.map((n, p) =>
                Cf.data(n, Vector("time"), NcType.Float64, p.name.replace('_', ' '), p.unit)
              )
              val vars = Vector(
                Cf.time("time", spec.timeUnits),
                Var(
                  "step",
                  Vector("time"),
                  NcType.Int32,
                  Vector(Attr.text("long_name", "steps taken"))
                ),
                Var(
                  "digest_hi",
                  Vector("time"),
                  NcType.Int32,
                  Vector(Attr.text("long_name", "digest of the state, upper 32 bits"))
                ),
                Var(
                  "digest_lo",
                  Vector("time"),
                  NcType.Int32,
                  Vector(Attr.text("long_name", "digest of the state, lower 32 bits"))
                )
              ) ++ space ++ fieldVars ++ probeVars
              val globals = Vector(
                Cf.conventions,
                Attr.text("title", spec.title),
                Attr.text("source", s"Physis ${BuildInfo.version}"),
                Attr.text("physis_model", modelId),
                Attr.text("physis_model_version", modelVersion),
                Attr.text("physis_steps", steps.toString),
                Attr.text("physis_every", options.every.toString),
                Attr.text("physis_precision", if options.single then "float32" else "float64")
              ) ++ options.attrs
              Schema(dims, globals, vars).validate.map(s => Plan(s, fields, named, records))

  /**
   * Runs `run` on to step `steps`, writing the file at `path` (made, with its folder, if need
   * be). `progress` is called with (records written, records in all) after every record; when
   * `cancelled` turns true the run stops before its next step, the file is removed and the
   * result is `Left(Cancelled)`. A file is also removed when anything else goes wrong. Returns
   * the number of records.
   */
  def write[P, S](
      run: Run[P, S],
      steps: Long,
      exporter: StateExport[S],
      spec: ExportSpec,
      probes: Vector[Probe[S]],
      path: Path,
      options: Options,
      progress: (Int, Int) => Unit = (_, _) => (),
      cancelled: () => Boolean = () => false
  ): Either[String, Int] =
    plan(
      run.state,
      run.stepCount,
      steps,
      run.model.id,
      run.model.version,
      exporter,
      spec,
      probes,
      options
    ).flatMap { p =>
      var out: OutputStream = null
      var created = false
      def drop(): Unit =
        if out != null then
          try out.close()
          catch case _: IOException => ()
          out = null
        if created then
          try
            val _ = Files.deleteIfExists(path)
          catch case _: IOException => ()
      try
        Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
        out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 16)
        created = true
        val axes = axisValues(p, spec)
        val writer = new NcWriter(out, p.schema, p.records, axes)
        val state = new Array[Double](exporter.size(run.state))
        val sizes = Vector.fill(4)(1) ++ p.fields.map((_, s) => s.length) ++
          Vector.fill(p.probes.size)(1)
        val record = sizes.map(n => new Array[Double](n)).toArray
        var failure: Option[String] = None
        def next(): Unit =
          exporter.copyInto(run.state, state)
          record(0)(0) = run.time
          record(1)(0) = run.stepCount.toDouble
          val d = run.digest
          record(2)(0) = (d >>> 32).toInt.toDouble
          record(3)(0) = d.toInt.toDouble
          var f = 0
          while f < p.fields.size do
            val slice = p.fields(f)._2
            System.arraycopy(state, slice.offset, record(4 + f), 0, slice.length)
            f += 1
          var q = 0
          while q < p.probes.size do
            record(4 + p.fields.size + q)(0) = p.probes(q)._2.measure(run.state, run.time)
            q += 1
          writer.add(ArraySeq.unsafeWrapArray(record))
          progress(writer.written, p.records)
        next()
        while failure.isEmpty && run.stepCount < steps do
          if cancelled() then failure = Some(Cancelled)
          else
            run.step()
            if run.stepCount % options.every == 0 then next()
        if failure.isEmpty && cancelled() then failure = Some(Cancelled)
        failure match
          case Some(why) =>
            drop()
            Left(why)
          case None =>
            writer.finish()
            out.close()
            out = null
            Right(p.records)
      catch
        case e: IOException =>
          drop()
          Left(s"cannot write the dataset to $path: ${e.getMessage}")
        case e: IllegalArgumentException =>
          drop()
          Left(s"cannot write the dataset: ${e.getMessage}")
    }

  /** The values of the axes `x` and `y`: the centres of the cells. */
  private def axisValues(p: Plan[?], spec: ExportSpec): Map[String, Array[Double]] =
    p.schema.fixedVars.map { v =>
      val n = p.schema.dim(v.name).flatMap(_.size).getOrElse(0)
      v.name -> Array.tabulate(n)(i => (i + 0.5) * spec.dx)
    }.toMap
