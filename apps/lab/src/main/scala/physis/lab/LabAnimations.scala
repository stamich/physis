package physis.lab

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.live.StateExport
import physis.core.run.{Manifest, ModelRegistry, Run}
import physis.view.Frames

/**
 * What File → Save animation asks of the user: how often to take a picture and how the file is
 * made. Everything is checked the way [[physis.view.Frames.Options]] checks it.
 *
 * @param every         steps between two pictures
 * @param fps           pictures per second when the animation plays
 * @param scale         pixels per cell
 * @param loops         times it plays; 0 is for ever
 * @param colorBar      whether the colour scale is drawn at the right
 * @param writeManifest whether the manifest is written next to the file, as `name.json`
 */
final case class AnimationOptions(
    every: Long,
    fps: Int = 12,
    scale: Int = 1,
    loops: Int = 0,
    colorBar: Boolean = true,
    writeManifest: Boolean = true
):
  /** The options of the frame writer; fails like it for a value out of range. */
  def frames: Frames.Options =
    Frames.Options(every, scale, colorBar, compress = false, fps = fps, loops = loops)

/** What a saved animation came to. */
final case class AnimationReport(
    frames: Int,
    steps: Long,
    bytes: Long,
    digest: Long,
    manifest: Option[Path]
):
  /** One line for the status bar. */
  def summary(path: Path): String =
    val kb = f"${bytes / 1024.0}%.0f"
    val where = manifest.fold("")(m => s", manifest ${m.getFileName}")
    s"Saved ${path.getFileName}: $frames frames of $steps steps, $kb KB$where"

/**
 * Saves a live session as an animated PNG without a window: the manifest of the session (its
 * parameter changes and strokes at the steps they were made) is replayed headless and a picture
 * is taken every so many steps. The result depends only on the manifest and the options, so it
 * is the file `physis-cli replay <manifest> --animate` writes.
 */
object LabAnimations:

  /** The manifest next to an animation: `run.png` gives `run.json`. */
  def manifestPath(animation: Path): Path =
    val name = animation.getFileName.toString
    val dot = name.lastIndexOf('.')
    animation.resolveSibling((if dot > 0 then name.substring(0, dot) else name) + ".json")

  /** The frames an animation of `steps` steps takes with these options. */
  def frameCount(steps: Long, options: AnimationOptions): Int =
    Frames.frameCount(0L, steps, options.every)

  /**
   * Replays `manifest` and writes the animation to `path`. `progress` gets (frames written,
   * frames in all); when `cancelled` turns true the file is removed and the result is
   * `Left(Frames.Cancelled)`. Runs on the calling thread.
   */
  def save(
      session: LabSession,
      manifest: Manifest,
      registry: ModelRegistry,
      path: Path,
      options: AnimationOptions,
      progress: (Int, Int) => Unit = (_, _) => (),
      cancelled: () => Boolean = () => false
  ): Either[String, AnimationReport] =
    val steps = manifest.steps.getOrElse(0L)
    if !session.hasImage then Left("this model has no image to save as an animation")
    else if steps < 1L then Left("nothing has run yet: start the session first")
    else
      for
        exporter <- session.exporter.toRight("this model has no image to save as an animation")
        frameOptions <- scala.util.Try(options.frames).toEither.left.map(_.getMessage)
        replay <- Manifest.replay(manifest, registry)
        report <- write(
          replay.run.asInstanceOf[Run[Any, Any]],
          exporter.asInstanceOf[StateExport[Any]],
          session,
          manifest,
          steps,
          path,
          options,
          frameOptions,
          progress,
          cancelled
        )
      yield report

  private def write(
      run: Run[Any, Any],
      exporter: StateExport[Any],
      session: LabSession,
      manifest: Manifest,
      steps: Long,
      path: Path,
      options: AnimationOptions,
      frameOptions: Frames.Options,
      progress: (Int, Int) => Unit,
      cancelled: () => Boolean
  ): Either[String, AnimationReport] =
    Frames.animation(run, steps, exporter, session.view, path, frameOptions, progress, cancelled)
      .flatMap { frames =>
        val digest = run.digest
        if manifest.digest.exists(_ != digest) then
          Files.deleteIfExists(path)
          Left(f"the replay ended at a different state ($digest%016x) than the session")
        else
          try
            val written =
              if options.writeManifest then
                val file = manifestPath(path)
                Files.writeString(file, Manifest.toJson(manifest), StandardCharsets.UTF_8)
                Some(file)
              else None
            Right(AnimationReport(frames, steps, Files.size(path), digest, written))
          catch
            case e: java.io.IOException =>
              Left(s"could not write next to $path: ${e.getMessage}")
      }
