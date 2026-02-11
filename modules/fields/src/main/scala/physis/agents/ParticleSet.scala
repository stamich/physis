package physis.agents

import physis.core.Digest
import physis.core.live.Slice

/**
 * A fixed number of particles stored as one array per attribute (structure of arrays).
 *
 * Loops over one attribute run over contiguous memory, the digest is one pass per column, and
 * a column is exactly the shape of a snapshot slice. Attributes are named once, in order; their
 * arrays are handed out as they are, so hot loops index them directly without any lookup.
 *
 * @param count number of particles
 * @param names attribute names, distinct and non-empty
 */
final class ParticleSet(val count: Int, val names: IndexedSeq[String]):
  require(count >= 1, s"a particle set needs at least one particle, got $count")
  require(names.nonEmpty, "a particle set needs at least one attribute")
  require(names.distinct.size == names.size, s"duplicate attribute names: ${names.mkString(", ")}")
  require(names.forall(_.nonEmpty), "attribute names must not be empty")

  private val columns: Array[Array[Double]] = Array.fill(names.size)(new Array[Double](count))

  /** Position of attribute `name`, or -1. */
  def indexOf(name: String): Int = names.indexOf(name)

  /** Values of attribute `name`; fails for an unknown name. Look it up once, not per step. */
  def column(name: String): Array[Double] =
    val k = indexOf(name)
    require(k >= 0, s"no attribute '$name' (have ${names.mkString(", ")})")
    columns(k)

  /** Values of the `k`-th attribute. */
  def columnAt(k: Int): Array[Double] = columns(k)

  /** Total number of values: `count` per attribute. */
  def size: Int = count * names.size

  /** Copies all values of `other`, which must have the same shape. */
  def copyFrom(other: ParticleSet): Unit =
    require(other.count == count && other.names == names, "particle sets differ in shape")
    var k = 0
    while k < columns.length do
      System.arraycopy(other.columns(k), 0, columns(k), 0, count)
      k += 1

  /** Bit-exact fingerprint of every column, in attribute order. */
  def digest: Long =
    var h = Digest.start
    var k = 0
    while k < columns.length do
      h = Digest.fold(h, columns(k), 0, count)
      k += 1
    h

  /**
   * Copies every column into `out`, one after another starting at `offset` (see [[slices]]).
   * Allocation-free.
   */
  def copyInto(out: Array[Double], offset: Int): Unit =
    var k = 0
    while k < columns.length do
      System.arraycopy(columns(k), 0, out, offset + k * count, count)
      k += 1

  /** Snapshot slices that describe what [[copyInto]] writes at `offset`: one row per attribute. */
  def slices(offset: Int): IndexedSeq[Slice] =
    names.indices.map(k => Slice(names(k), offset + k * count, count, 1))

object ParticleSet:
  def apply(count: Int, names: String*): ParticleSet = new ParticleSet(count, names.toVector)
