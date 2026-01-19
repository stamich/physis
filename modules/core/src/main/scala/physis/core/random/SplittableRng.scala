package physis.core.random

import java.util.SplittableRandom

/**
 * Seeded random number generator with keyed, reproducible streams.
 *
 * `fork(key)` derives an independent generator from this generator's seed and a key (a step
 * index, a tile id, an agent block). The result depends only on `(seed, key)`, never on how many
 * numbers were drawn before or on which thread asks, so parallel and serial runs see the same
 * streams.
 *
 * Instances are not thread-safe; fork one per thread or per unit of work.
 */
final class SplittableRng private (val seed: Long):
  private val underlying = new SplittableRandom(seed)

  /** An independent generator for `key`. Same seed and key always give the same stream. */
  def fork(key: Long): SplittableRng =
    new SplittableRng(SplittableRng.mix64(seed + SplittableRng.Gamma * (key + 1L)))

  /** Uniform in [0, 1). */
  def nextDouble(): Double = underlying.nextDouble()

  /** Standard normal deviate. */
  def nextGaussian(): Double = underlying.nextGaussian()

  def nextLong(): Long = underlying.nextLong()

  /** Uniform in [0, bound). */
  def nextInt(bound: Int): Int = underlying.nextInt(bound)

object SplittableRng:
  def apply(seed: Long): SplittableRng = new SplittableRng(seed)

  /** Golden-ratio increment used by SplitMix64. */
  private[random] val Gamma: Long = 0x9e3779b97f4a7c15L

  /** SplitMix64 finaliser (Stafford variant 13). */
  private[random] def mix64(z0: Long): Long =
    var z = z0
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)
