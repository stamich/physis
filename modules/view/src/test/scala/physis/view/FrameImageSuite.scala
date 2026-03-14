package physis.view

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

import physis.core.live.{Slice, Snapshot}

class FrameImageSuite extends munit.FunSuite:

  /** A 3 x 2 field "u" at offset 2, and a 3 x 2 mask "wall" after it. */
  private def snapshot(u: Array[Double], wall: Array[Double]): Snapshot =
    val s = new Snapshot(2 + 6 + 6, 0)
    System.arraycopy(u, 0, s.state, 2, 6)
    System.arraycopy(wall, 0, s.state, 8, 6)
    s.slices = Vector(Slice("u", 2, 3, 2), Slice("wall", 8, 3, 2))
    s

  private val none = Array.fill(6)(0.0)

  private def heatmap(
      range: ValueRange,
      overlay: Option[String] = None,
      slice: String = "u"
  ): View.Heatmap =
    View.Heatmap(slice, range, Colormap.Viridis, "", overlay) match
      case h: View.Heatmap => h
      case other => fail(s"not a heatmap: $other")

  test("values are coloured through the table, row 0 first, NaN transparent") {
    val lut = HeatmapRaster.lut(Colormap.Viridis)
    val s = snapshot(Array(0.0, 1.0, 0.5, Double.NaN, 2.0, -1.0), none)
    val frame = FrameImage.heatmap(s, heatmap(ValueRange.fixed(0.0, 1.0))).get
    assertEquals((frame.width, frame.height), (3, 2))
    assertEquals(
      frame.pixels.take(6).toVector,
      Vector(lut(0), lut(255), lut(128), 0, lut(255), lut(0))
    )
    assertEquals((frame.lo, frame.hi), (0.0, 1.0))
  }

  test("an automatic range follows the data of the frame") {
    val s = snapshot(Array(2.0, 4.0, 6.0, 8.0, 10.0, 12.0), none)
    val frame = FrameImage.heatmap(s, heatmap(ValueRange.Auto)).get
    assertEquals((frame.lo, frame.hi), (2.0, 12.0))
    val lut = HeatmapRaster.lut(Colormap.Viridis)
    assertEquals(frame.pixels(0), lut(0))
    assertEquals(frame.pixels(5), lut(255))
  }

  test("the overlay is drawn in flat grey where the mask is 1 or more") {
    val s = snapshot(Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), Array(1.0, 0.0, 0.0, 0.0, 2.0, 0.0))
    val frame = FrameImage.heatmap(s, heatmap(ValueRange.SymmetricAuto, Some("wall"))).get
    assertEquals(frame.pixels(0), HeatmapRaster.OverlayGrey)
    assertEquals(frame.pixels(4), HeatmapRaster.OverlayGrey)
    assertNotEquals(frame.pixels(1), HeatmapRaster.OverlayGrey)
    assertEquals((frame.lo, frame.hi), (-5.0, 5.0))
  }

  test("a missing slice gives no picture, and an overlay of another size is ignored") {
    val s = snapshot(Array.fill(6)(1.0), Array.fill(6)(1.0))
    assertEquals(FrameImage.heatmap(s, heatmap(ValueRange.Auto, slice = "nope")), None)
    s.slices = Vector(Slice("u", 2, 3, 2), Slice("wall", 8, 2, 3))
    val frame = FrameImage.heatmap(s, heatmap(ValueRange.Auto, Some("wall"))).get
    assert(frame.pixels.forall(_ != HeatmapRaster.OverlayGrey))
  }

  test("a colour bar goes to the right, first colour at the bottom") {
    val s = snapshot(Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), none)
    val base = FrameImage.heatmap(s, heatmap(ValueRange.Auto)).get
    val bar = FrameImage.withColorBar(base, Colormap.Viridis, barWidth = 4)
    val gap = 2
    assertEquals((bar.width, bar.height), (3 + gap + 4, 2))
    val lut = HeatmapRaster.lut(Colormap.Viridis)
    for y <- 0 until 2 do
      assertEquals(
        bar.pixels.slice(y * bar.width, y * bar.width + 3).toVector,
        base.pixels.slice(y * 3, y * 3 + 3).toVector
      )
      assertEquals(bar.pixels.slice(y * bar.width + 3, y * bar.width + 5).toVector, Vector(0, 0))
    // two rows: the top one is above the middle of the scale, the bottom one below
    assert(bar.pixels(bar.width - 1) != bar.pixels(2 * bar.width - 1))
    assertEquals(bar.pixels(bar.width - 1), lut(((0.75) * 255 + 0.5).toInt))
    assertEquals(bar.pixels(2 * bar.width - 1), lut(((0.25) * 255 + 0.5).toInt))
    assertEquals((bar.lo, bar.hi), (base.lo, base.hi))
  }

  test("the default bar of a tall frame is a twentieth of the height wide") {
    val tall = new Frame(10, 200, Array.fill(2000)(0xff102030), 0.0, 1.0)
    val bar = FrameImage.withColorBar(tall, Colormap.Viridis)
    assertEquals(bar.width, 10 + 5 + 10)
    assertEquals(bar.pixels(0), 0xff102030)
    assertEquals(bar.pixels(10), 0)
  }

  test("a frame becomes a PNG that decodes to its pixels, also enlarged") {
    val s = snapshot(Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), none)
    val frame = FrameImage.heatmap(s, heatmap(ValueRange.Auto)).get
    for factor <- Seq(1, 4) do
      val image = ImageIO.read(new ByteArrayInputStream(frame.png(factor)))
      assertEquals((image.getWidth, image.getHeight), (3 * factor, 2 * factor))
      for y <- 0 until 2 * factor; x <- 0 until 3 * factor do
        assertEquals(image.getRGB(x, y), frame.pixels((y / factor) * 3 + x / factor), s"($x, $y)")
  }
