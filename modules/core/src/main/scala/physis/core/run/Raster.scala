package physis.core.run

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * A rectangle of values read from a file, for example the height of the ground in metres
 * (positive up), `ny` rows of `nx` values with row 0 at the north.
 */
final class Raster(val nx: Int, val ny: Int, val values: Array[Double]):
  require(nx > 0 && ny > 0, s"raster of $nx x $ny values")
  require(values.length == nx * ny, s"raster of $nx x $ny needs ${nx * ny} values")

  /**
   * The value at `(u, v)`, both in `[0, 1]` across the whole raster (`u` to the east, `v` to the
   * south), interpolated bilinearly between the centres of the raster's cells and constant
   * beyond the outermost centres. Allocates nothing.
   */
  def sample(u: Double, v: Double): Double =
    val x = math.max(0.0, math.min(nx - 1.0, u * nx - 0.5))
    val y = math.max(0.0, math.min(ny - 1.0, v * ny - 0.5))
    val i = math.min(x.toInt, math.max(0, nx - 2))
    val j = math.min(y.toInt, math.max(0, ny - 2))
    val i1 = math.min(i + 1, nx - 1)
    val j1 = math.min(j + 1, ny - 1)
    val fx = x - i
    val fy = y - j
    val top = values(j * nx + i) * (1.0 - fx) + values(j * nx + i1) * fx
    val bottom = values(j1 * nx + i) * (1.0 - fx) + values(j1 * nx + i1) * fx
    top * (1.0 - fy) + bottom * fy

/**
 * The rasters that a process has read from files, by the digest of the file. A run records an
 * intervention that names a file and its digest; whoever starts the run reads the file, checks
 * the digest and registers the raster here, and the model finds it by the digest. Two runs with
 * the same digest therefore see the same values, whatever the file is called.
 */
object Rasters:
  private val table = new ConcurrentHashMap[Long, Raster]()

  /** Makes `raster` available to runs whose interventions carry `digest`. */
  def register(digest: Long, raster: Raster): Unit =
    val _ = table.put(digest, raster)

  /** The raster registered for `digest`, if any. */
  def get(digest: Long): Option[Raster] = Option(table.get(digest))

  /** A 64-bit digest of the bytes of a file: the first 8 bytes of their SHA-256. */
  def digestOf(bytes: Array[Byte]): Long =
    val h = MessageDigest.getInstance("SHA-256").digest(bytes)
    var d = 0L
    var k = 0
    while k < 8 do
      d = (d << 8) | (h(k) & 0xffL)
      k += 1
    d
