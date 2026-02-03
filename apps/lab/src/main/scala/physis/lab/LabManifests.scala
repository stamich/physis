package physis.lab

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.live.LiveRun
import physis.core.run.{Manifest, ModelRegistry}

/** Outcome of replaying a manifest headless. */
final case class ReplayReport(
    model: String,
    steps: Long,
    time: Double,
    digest: Long,
    expected: Option[Long]
):
  /** Whether the replay reproduced the recorded digest; `None` if the manifest has none. */
  def matches: Option[Boolean] = expected.map(_ == digest)

  /** One line for the status bar. */
  def summary: String =
    val d = f"0x$digest%016x"
    matches match
      case Some(true) => f"Replay of $model: $steps steps to t = $time%.3f, digest $d matches"
      case Some(false) =>
        f"Replay of $model: digest $d DIFFERS from the recorded 0x${expected.get}%016x"
      case None => f"Replay of $model: $steps steps to t = $time%.3f, digest $d (none recorded)"

/** Saving live sessions as manifests and replaying them, without any UI. */
object LabManifests:
  /** Writes the session's manifest (parameters, events, steps, digest) as JSON. */
  def save(live: LiveRun[?, ?], path: Path): Either[String, Manifest] =
    try
      val manifest = live.manifest()
      Files.writeString(path, Manifest.toJson(manifest), StandardCharsets.UTF_8)
      Right(manifest)
    catch case e: java.io.IOException => Left(s"could not write $path: ${e.getMessage}")

  /** Reads a manifest, re-runs it headless and compares the final digest. */
  def replay(path: Path, registry: ModelRegistry): Either[String, ReplayReport] =
    for
      text <-
        try Right(Files.readString(path, StandardCharsets.UTF_8))
        catch case e: java.io.IOException => Left(s"could not read $path: ${e.getMessage}")
      manifest <- Manifest.fromJson(text)
      replay <- Manifest.replay(manifest, registry)
    yield
      val result = replay.execute()
      ReplayReport(
        manifest.model,
        result.acceptedSteps,
        result.finalTime,
        result.finalDigest,
        manifest.digest
      )
