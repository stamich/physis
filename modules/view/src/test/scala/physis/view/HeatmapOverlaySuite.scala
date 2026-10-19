package physis.view

class HeatmapOverlaySuite extends munit.FunSuite:
  private val lut = HeatmapRaster.lut(Colormap.Viridis)

  test("cells at 1 or more become flat grey, the others keep the field's colours") {
    val field = Array(0.0, 0.5, 1.0, 0.25)
    val mask = Array(0.0, 1.0, 0.0, 1.0)
    val out = new Array[Int](4)
    HeatmapRaster.fill(field, 0, 2, 2, lut, 0.0, 1.0, out)
    val plain = out.clone()
    HeatmapRaster.overlay(mask, 0, 2, 2, out)
    assertEquals(out(0), plain(0))
    assertEquals(out(1), HeatmapRaster.OverlayGrey)
    assertEquals(out(2), plain(2))
    assertEquals(out(3), HeatmapRaster.OverlayGrey)
    assertEquals(HeatmapRaster.OverlayGrey >>> 24, 0xff)
  }

  test("the overlay reads its own slice offset and is opaque over transparent cells") {
    val field = Array(Double.NaN, 0.0)
    val state = Array(9.0, 9.0, 1.0, 0.0)
    val out = new Array[Int](2)
    HeatmapRaster.fill(field, 0, 2, 1, lut, 0.0, 1.0, out)
    assertEquals(out(0), HeatmapRaster.Transparent)
    HeatmapRaster.overlay(state, 2, 2, 1, out)
    assertEquals(out(0), HeatmapRaster.OverlayGrey)
    assertEquals(out(1), lut(0))
  }

  test("a heatmap view has no overlay unless it names one") {
    assertEquals(View.Heatmap("u").overlay, None)
    assertEquals(View.Heatmap("u", overlay = Some("wall")).overlay, Some("wall"))
  }
