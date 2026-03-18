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

  /** Packed value of the flat grey that overlay cells (walls) are drawn in. */
  val OverlayGrey: Int = 0xff707070

  /**
   * Draws the cells of `mask` that are 1 or more in [[OverlayGrey]] over `out`, which holds the
   * pixels of the field of the same size.
   *
   * @param mask   `nx * ny` values starting at `offset`
   * @param out    at least `nx * ny` long
   */
  def overlay(mask: Array[Double], offset: Int, nx: Int, ny: Int, out: Array[Int]): Unit =
    val n = nx * ny
    var k = 0
    while k < n do
      if mask(offset + k) >= 1.0 then out(k) = OverlayGrey
      k += 1

  /**
   * Mixes a dot of `color` into the cell of each of `count` agents whose positions (cell
   * coordinates, `xs` from `xOffset` and `ys` from `yOffset`) are given. A position outside the
   * grid wraps; NaN positions are skipped. A cell with several agents is mixed once per agent,
   * in agent order. Opaque result: the alpha of the cell is set to 255.
   *
   * @param opacity share of `color` that goes into the cell, 0 to 255
   * @param out     the `nx * ny` pixels of the field
   */
  def dots(
      xs: Array[Double],
      xOffset: Int,
      ys: Array[Double],
      yOffset: Int,
      count: Int,
      nx: Int,
      ny: Int,
      color: Int,
      opacity: Int,
      out: Array[Int]
  ): Unit =
    val inv = 255 - opacity
    val sr = (color >>> 16) & 0xff
    val sg = (color >>> 8) & 0xff
    val sb = color & 0xff
    var k = 0
    while k < count do
      val x = xs(xOffset + k)
      val y = ys(yOffset + k)
      if x == x && y == y && !x.isInfinite && !y.isInfinite then
        val wx = x - nx * math.floor(x / nx)
        val wy = y - ny * math.floor(y / ny)
        val cx = math.min(nx - 1, wx.toInt)
        val cy = math.min(ny - 1, wy.toInt)
        val at = cy * nx + cx
        val dst = out(at)
        val r = (((dst >>> 16) & 0xff) * inv + sr * opacity + 127) / 255
        val g = (((dst >>> 8) & 0xff) * inv + sg * opacity + 127) / 255
        val b = ((dst & 0xff) * inv + sb * opacity + 127) / 255
        out(at) = 0xff000000 | (r << 16) | (g << 8) | b
      k += 1
