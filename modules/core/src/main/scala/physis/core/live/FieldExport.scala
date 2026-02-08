package physis.core.live

/**
 * Copies for exporting field data from model state into a snapshot's flat vector.
 *
 * Fields are stored row-major with a halo of `halo` cells on every side (see the `fields`
 * module); snapshots carry only the interior, so the UI never sees a boundary cell.
 */
object FieldExport:

  /**
   * Copies the `nx * ny` interior of a haloed field into `out`, starting at `offset`, row by
   * row with one `System.arraycopy` each. Allocation-free.
   *
   * @param src  storage of `(nx + 2 * halo) * (ny + 2 * halo)` values
   * @param out  destination, at least `offset + nx * ny` long
   */
  def copyInterior(
      src: Array[Double],
      nx: Int,
      ny: Int,
      halo: Int,
      out: Array[Double],
      offset: Int
  ): Unit =
    val stride = nx + 2 * halo
    var j = 0
    while j < ny do
      System.arraycopy(src, (j + halo) * stride + halo, out, offset + j * nx, nx)
      j += 1
