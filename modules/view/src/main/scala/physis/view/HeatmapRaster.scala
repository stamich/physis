package physis.view

/**
 * The conversion from field values to pixels, without any toolkit: each value is mapped through a
 * 256-entry table of packed ARGB colours into an `Array[Int]` that a renderer copies to an image.
 *
 * Row 0 of the field is the top row of the image. A value maps to table entry
 * `round(255 * (v - lo) / (hi - lo))`, clamped to 0..255 (so values outside the range take the
 * end colours, infinities included); NaN becomes fully transparent. With `hi <= lo` every
 * value takes the first colour.
 */
object HeatmapRaster:

  private lazy val tables: Array[Array[Int]] = Colormap.values.map(build)

  private def build(cm: Colormap): Array[Int] =
    cm.table.map(pack).toArray

  /** The table of `cm` as packed ARGB (alpha in the top byte). Shared: do not modify it. */
  def lut(cm: Colormap): Array[Int] = tables(cm.ordinal)

  /** `c` as packed ARGB, 8 bits per component. */
  def pack(c: Rgb): Int =
    def byte(v: Double) = math.round(v * 255.0).toInt
    (byte(c.opacity) << 24) | (byte(c.r) << 16) | (byte(c.g) << 8) | byte(c.b)

  /** Packed value of a fully transparent pixel. */
  val Transparent: Int = 0

  /**
   * Colours `nx * ny` values of `values`, starting at `offset`, into `out`.
   *
   * @param lut table of 256 packed colours, see [[lut]]
   * @param lo  value that gets the first colour
   * @param hi  value that gets the last colour
   * @param out at least `nx * ny` long
   */
  def fill(
      values: Array[Double],
      offset: Int,
      nx: Int,
      ny: Int,
      lut: Array[Int],
      lo: Double,
      hi: Double,
      out: Array[Int]
  ): Unit =
    val n = nx * ny
    val scale = if hi > lo then 255.0 / (hi - lo) else 0.0
    var k = 0
    while k < n do
      val v = values(offset + k)
      if v != v then out(k) = Transparent
      else
        val x = (v - lo) * scale + 0.5
        out(k) = lut(if x <= 0.0 then 0 else if x >= 255.0 then 255 else x.toInt)
      k += 1
