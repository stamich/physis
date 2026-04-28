package physis.view

import physis.core.live.Snapshot

/**
 * A picture of a field: packed ARGB pixels, row 0 first, and the value range that gave them
 * their colours.
 */
final class Frame(
    val width: Int,
    val height: Int,
    val pixels: Array[Int],
    val lo: Double,
    val hi: Double
):
  require(pixels.length >= width.toLong * height, "not enough pixels for the stated size")

  /** The picture as a PNG file, enlarged `factor` times (see [[Png.scale]]). */
  def png(factor: Int = 1, compress: Boolean = false): Array[Byte] =
    Png.encode(Png.scale(pixels, width, height, factor), width * factor, height * factor, compress)

/**
 * Draws a heatmap view of a snapshot without any toolkit, with the same colours the Lab shows:
 * the same range, colour table and overlay, in the same order.
 */
object FrameImage:

  /**
   * The picture of `view` in `s`, or `None` when the snapshot has no such slice. An overlay
   * slice of another size is ignored.
   */
  def heatmap(s: Snapshot, view: View.Heatmap, dots: Option[Dots] = None): Option[Frame] =
    s.slice(view.slice).map { slice =>
      val n = slice.width * slice.height
      val values = new Array[Double](n)
      System.arraycopy(s.state, slice.offset, values, 0, n)
      val (lo, hi) = view.range.resolve(values, 0, n)
      val pixels = new Array[Int](n)
      HeatmapRaster.fill(
        values,
        0,
        slice.width,
        slice.height,
        HeatmapRaster.lut(view.colormap),
        lo,
        hi,
        pixels
      )
      view.overlay.foreach { name =>
        s.slice(name) match
          case Some(mask) if mask.width == slice.width && mask.height == slice.height =>
            HeatmapRaster.overlay(s.state, mask.offset, slice.width, slice.height, pixels)
          case _ => ()
      }
      dots.foreach { d =>
        for
          xs <- s.slice(d.x)
          ys <- s.slice(d.y)
        do
          HeatmapRaster.dots(
            s.state,
            xs.offset,
            s.state,
            ys.offset,
            math.min(xs.length, ys.length),
            slice.width,
            slice.height,
            d.color,
            d.opacity,
            pixels
          )
      }
      new Frame(slice.width, slice.height, pixels, lo, hi)
    }

  /** The picture of the heatmap of `view` with its dots. */
  def layers(s: Snapshot, view: View.Layers): Option[Frame] =
    heatmap(s, view.base, Some(view.dots))

  /** The pixels [[withColorBar]] adds to the right of a frame `height` pixels high. */
  def colorBarExtra(height: Int, barWidth: Int = 0): Int =
    val bar = if barWidth > 0 then barWidth else math.max(6, height / 20)
    bar + math.max(2, bar / 2)

  /**
   * `frame` with a colour scale to its right: the first colour of `colormap` at the bottom, the
   * last at the top, after a transparent gap. The scale has no labels (the range is in the
   * frame); its width is a twentieth of the height unless given.
   */
  def withColorBar(frame: Frame, colormap: Colormap, barWidth: Int = 0): Frame =
    val w = frame.width
    val h = frame.height
    val total = w + colorBarExtra(h, barWidth)
    val bar = if barWidth > 0 then barWidth else math.max(6, h / 20)
    val gap = math.max(2, bar / 2)
    val lut = HeatmapRaster.lut(colormap)
    val out = new Array[Int](total * h)
    var y = 0
    while y < h do
      System.arraycopy(frame.pixels, y * w, out, y * total, w)
      val t = 1.0 - (y + 0.5) / h
      val color = lut(math.max(0, math.min(255, (t * 255.0 + 0.5).toInt)))
      var x = w + gap
      while x < total do
        out(y * total + x) = color
        x += 1
      y += 1
    new Frame(total, h, out, frame.lo, frame.hi)
