package physis.verify

import physis.core.run.{Manifest, ModelRegistry}

/** Checks that runs reproduce from their manifest. */
object Determinism:

  /**
   * Replays `manifest` twice and compares the final digests.
   *
   * @return `Right(true)` if both replays end in the same state, `Left` if the manifest cannot be
   *         replayed
   */
  def replayMatches(manifest: Manifest, registry: ModelRegistry): Either[String, Boolean] =
    for
      first <- Manifest.replay(manifest, registry)
      second <- Manifest.replay(manifest, registry)
    yield first.execute().finalDigest == second.execute().finalDigest

  /** Final digest of a replay, to compare with a digest recorded elsewhere. */
  def replayDigest(manifest: Manifest, registry: ModelRegistry): Either[String, Long] =
    Manifest.replay(manifest, registry).map(_.execute().finalDigest)
