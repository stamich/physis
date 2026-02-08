package physis.fields

import physis.core.Digest
import physis.core.live.FieldExport

/**
 * A scalar field on a [[Grid2D]]: one `Array[Double]` with a halo.
 *
 * Reductions ([[sum]], [[min]], [[max]]), [[digest]] and the copies to a snapshot look at the
 * interior only, in row-major order, so their results do not depend on what the halo holds.
 * [[apply]] and [[update]] do no bounds check beyond the array's own; callers stay within
 * `-1..nx` and `-1..ny`.
 */
final class Field2D(val grid: Grid2D, val data: Array[Double]):
  require(
    data.length == grid.storageSize,
    s"storage has ${data.length} values, grid needs ${grid.storageSize}"
  )

  def this(grid: Grid2D) = this(grid, new Array[Double](grid.storageSize))

  def nx: Int = grid.nx
  def ny: Int = grid.ny

  def apply(i: Int, j: Int): Double = data(grid.index(i, j))

  def update(i: Int, j: Int, value: Double): Unit = data(grid.index(i, j)) = value

  /** Sets every interior cell; the halo is left alone. */
  def fill(value: Double): Unit =
    var j = 0
    while j < grid.ny do
      val row = grid.index(0, j)
      java.util.Arrays.fill(data, row, row + grid.nx, value)
      j += 1

  /** Sets every value, halo included. */
  def fillAll(value: Double): Unit = java.util.Arrays.fill(data, value)

  /** Copies all values of `other`, halo included; both fields must have the same shape. */
  def copyFrom(other: Field2D): Unit =
    require(other.grid == grid, s"shape mismatch: ${other.grid} vs $grid")
    System.arraycopy(other.data, 0, data, 0, data.length)

  /** Sum of the interior, compensated (Neumaier) so that long sums keep their last digits. */
  def sum: Double =
    var total = 0.0
    var c = 0.0
    var j = 0
    while j < grid.ny do
      var k = grid.index(0, j)
      val end = k + grid.nx
      while k < end do
        val x = data(k)
        val t = total + x
        if math.abs(total) >= math.abs(x) then c += (total - t) + x else c += (x - t) + total
        total = t
        k += 1
      j += 1
    total + c

  /** Mean of the interior. */
  def mean: Double = sum / grid.cells

  /** Smallest interior value. */
  def min: Double =
    var m = Double.PositiveInfinity
    var j = 0
    while j < grid.ny do
      var k = grid.index(0, j)
      val end = k + grid.nx
      while k < end do
        if data(k) < m then m = data(k)
        k += 1
      j += 1
    m

  /** Largest interior value. */
  def max: Double =
    var m = Double.NegativeInfinity
    var j = 0
    while j < grid.ny do
      var k = grid.index(0, j)
      val end = k + grid.nx
      while k < end do
        if data(k) > m then m = data(k)
        k += 1
      j += 1
    m

  /** Copies the interior, row-major, into `out` starting at `offset`. Allocation-free. */
  def copyInteriorInto(out: Array[Double], offset: Int): Unit =
    FieldExport.copyInterior(data, grid.nx, grid.ny, Grid2D.Halo, out, offset)

  /** Bit-exact fingerprint of the interior; equals `Digest.ofDoubles` of the row-major values. */
  def digest: Long = digestInto(Digest.start)

  /** Continues the fingerprint `h` with the interior, to chain several fields. */
  def digestInto(h0: Long): Long =
    var h = h0
    var j = 0
    while j < grid.ny do
      val row = grid.index(0, j)
      h = Digest.fold(h, data, row, row + grid.nx)
      j += 1
    h
