package physis.lab

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.run.{Manifest, ModelRegistry}
import physis.netcdf.{Attr, NcExport}
import physis.view.Frames

/**
 * What File → Export data asks of the user: how often to write the state and in which
 * precision.
 *
 * @param every         steps between two records; the first record is the state before any step
 * @param single        whether the fields are written as 32-bit floats instead of 64-bit
 * @param writeManifest whether the manifest is written next to the file, as `name.json`
 */
final case class DatasetOptions(
    every: Long,
    single: Boolean = false,
    writeManifest: Boolean = true
):
  /** The options of the exporter, with the manifest of the session stored in the file. */
  def forManifest(manifest: Manifest): NcExport.Options =
    NcExport.Options(
      every,
      single,
      None,
      Vector(Attr.text("physis_manifest", Manifest.toJson(manifest.copy(digest = None))))
    )

/** What a saved dataset came to. */
final case class DatasetReport(
    records: Int,
    steps: Long,
    bytes: Long,
    digest: Long,
    manifest: Option[Path]
):
  /** One line for the status bar. */
  def summary(path: Path): String =
    val size =
      if bytes >= 10L * 1024 * 1024 then f"${bytes / 1048576.0}%.0f MB"
      else f"${bytes / 1024.0}%.0f KB"
    val where = manifest.fold("")(m => s", manifest ${m.getFileName}")
    s"Saved ${path.getFileName}: $records records of $steps steps, $size$where"

/**
 * Saves a live session as a NetCDF dataset without a window: the manifest of the session (its
 * parameter changes and strokes at the steps they were made) is replayed headless and the fields
 * and probes are written every so many steps. The result depends only on the manifest and the
 * options, so it holds the data `physis-cli replay <manifest> --export` writes.
 */
object LabDatasets:

  /** The manifest next to a dataset: `run.nc` gives `run.json`. */
  def manifestPath(dataset: Path): Path = LabAnimations.manifestPath(dataset)

  /** The largest file the Lab writes; the reader of `physis-cli verify` stops at 2 GB. */
  val MaxBytes: Long = 1L << 30

  /** The options the dialog opens with for a session of `steps` steps: some twenty records. */
  def suggest(steps: Long): DatasetOptions = DatasetOptions(math.max(1L, steps / 20))

  /** What a dataset of `steps` steps will be. */
  final case class Estimate(records: Int, bytes: Long, seconds: Option[Double]):
    /** One line for the dialog. */
    def text: String =
      val size =
        if bytes >= 10L * 1024 * 1024 then f"${bytes / 1048576.0}%.0f MB"
        else f"${bytes / 1024.0}%.0f KB"
      val time = seconds.fold("time not known yet")(t =>
        if t < 1.5 then "about a second"
        else if t < 90 then f"about $t%.0f s"
        else f"about ${t / 60}%.0f min"
      )
      s"$records records, $size, $time"

  /** Why `options` cannot be used for a session of `steps` steps, if they cannot. */
  def check(options: DatasetOptions): Option[String] =
    if options.every < 1L then Some("write a record every 1 step or more") else None

  /**
   * The estimate for `steps` steps of the session, from the size of the file the replayed run
   * would write, and how long it takes if the session ran at `stepsPerSecond` (0: not known).
   */
  def estimate(
      model: LabModel,
      manifest: Manifest,
      registry: ModelRegistry,
      stepsPerSecond: Double,
      options: DatasetOptions
  ): Either[String, Estimate] =
    val steps = manifest.steps.getOrElse(0L)
    check(options) match
      case Some(why) => Left(why)
      case None =>
        for
          replay <- Manifest.replay(manifest, registry)
          bytes <- model.dataset.bytes(
            replay.run,
            steps,
            options.forManifest(manifest.copy(steps = Some(steps)))
          )
          _ <- Either.cond(
            bytes <= MaxBytes,
            (),
            f"that would be ${bytes / 1048576.0}%.0f MB; the most is ${MaxBytes >> 20} MB: " +
              s"write a record every ${options.every * 2} steps or more"
          )
        yield Estimate(
          Frames.frameCount(0L, steps, options.every),
          bytes,
          if stepsPerSecond > 0.0 then Some(steps / stepsPerSecond) else None
        )

  /**
   * Replays `manifest` and writes the dataset to `path`. `progress` gets (records written,
   * records in all); when `cancelled` turns true the file is removed and the result is
   * `Left(Frames.Cancelled)`. Runs on the calling thread.
   */
  def save(
      model: LabModel,
      manifest: Manifest,
      registry: ModelRegistry,
      path: Path,
      options: DatasetOptions,
      progress: (Int, Int) => Unit = (_, _) => (),
      cancelled: () => Boolean = () => false
  ): Either[String, DatasetReport] =
    val steps = manifest.steps.getOrElse(0L)
    if steps < 1L then Left("nothing has run yet: start the session first")
    else
      for
        _ <- check(options).toLeft(())
        replay <- Manifest.replay(manifest, registry)
        dataset = model.dataset
        exportOptions = options.forManifest(manifest.copy(steps = Some(steps)))
        bytes <- dataset.bytes(replay.run, steps, exportOptions)
        _ <- Either.cond(
          bytes <= MaxBytes,
          (),
          f"that would be ${bytes / 1048576.0}%.0f MB; the most is ${MaxBytes >> 20} MB: " +
            s"write a record every ${options.every * 2} steps or more"
        )
        records <- dataset.write(replay.run, steps, path, exportOptions, progress, cancelled)
        report <- finish(replay.run.digest, records, steps, manifest, path, options)
      yield report

  private def finish(
      digest: Long,
      records: Int,
      steps: Long,
      manifest: Manifest,
      path: Path,
      options: DatasetOptions
  ): Either[String, DatasetReport] =
    if manifest.digest.exists(_ != digest) then
      val _ = Files.deleteIfExists(path)
      Left(f"the replay ended at a different state ($digest%016x) than the session")
    else
      try
        val written =
          if options.writeManifest then
            val file = manifestPath(path)
            Files.writeString(file, Manifest.toJson(manifest), StandardCharsets.UTF_8)
            Some(file)
          else None
        Right(DatasetReport(records, steps, Files.size(path), digest, written))
      catch
        case e: java.io.IOException => Left(s"could not write next to $path: ${e.getMessage}")
