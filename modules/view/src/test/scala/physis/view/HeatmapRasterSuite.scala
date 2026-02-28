package physis.view

class HeatmapRasterSuite extends munit.FunSuite:

  private val lut = HeatmapRaster.lut(Colormap.Viridis)

  private def raster(values: Array[Double], lo: Double, hi: Double, nx: Int = 0): Array[Int] =
    val out = new Array[Int](values.length)
    HeatmapRaster.fill(values, 0, if nx == 0 then values.length else nx, 1, lut, lo, hi, out)
    out

  test("the table is the colormap as packed opaque ARGB") {
    assertEquals(lut.length, 256)
    assertEquals(lut(0), 0xff440154)
    assertEquals(lut(255), 0xfffde725)
    assert(lut.forall(c => (c >>> 24) == 0xff))
    assert(HeatmapRaster.lut(Colormap.Cyclic) ne lut)
  }

  test("pack rounds each component to 8 bits and keeps the opacity") {
    assertEquals(HeatmapRaster.pack(Rgb.bytes(18, 52, 86)), 0xff123456)
    assertEquals(HeatmapRaster.pack(Rgb(1.0, 0.0, 0.0, 0.0)), 0x00ff0000)
  }

  test("the ends of the range get the end colours, the middle the middle one") {
    val out = raster(Array(0.0, 0.5, 1.0), 0.0, 1.0)
    assertEquals(out(0), lut(0))
    assertEquals(out(1), lut(128))
    assertEquals(out(2), lut(255))
  }

  test("values outside the range clamp, infinities too") {
    val out = raster(Array(-3.0, 4.0, Double.NegativeInfinity, Double.PositiveInfinity), 0.0, 1.0)
    assertEquals(out.toVector, Vector(lut(0), lut(255), lut(0), lut(255)))
  }

  test("NaN is transparent") {
    val out = raster(Array(Double.NaN, 0.25), 0.0, 1.0)
    assertEquals(out(0), HeatmapRaster.Transparent)
    assertEquals(out(0) >>> 24, 0)
    assertEquals(out(1), lut(64))
  }

  test("a range that is not increasing gives the first colour everywhere") {
    assertEquals(raster(Array(0.0, 5.0), 1.0, 1.0).toVector, Vector(lut(0), lut(0)))
    assertEquals(raster(Array(0.0, 5.0), 2.0, 1.0).toVector, Vector(lut(0), lut(0)))
  }

  test("fill reads nx * ny values from the offset and writes row-major, row 0 first") {
    val values = Array(9.0, 9.0, 0.0, 1.0, 0.0, 1.0, 9.0)
    val out = Array.fill(6)(-1)
    HeatmapRaster.fill(values, 2, 2, 2, lut, 0.0, 1.0, out)
    assertEquals(out.toVector, Vector(lut(0), lut(255), lut(0), lut(255), -1, -1))
  }

  test("automatic range uses the smallest and largest value, ignoring NaN") {
    val values = Array(9.0, 2.0, Double.NaN, 6.0, 4.0, 9.0)
    assertEquals(ValueRange.Auto.resolve(values, 1, 4), (2.0, 6.0))
    assertEquals(ValueRange.Auto.resolve(values, 0, 6), (2.0, 9.0))
  }

  test("an automatic range of constant, empty or NaN data is widened to length 1") {
    assertEquals(ValueRange.Auto.resolve(Array(3.0, 3.0), 0, 2), (3.0, 4.0))
    assertEquals(ValueRange.Auto.resolve(Array(3.0), 0, 0), (0.0, 1.0))
    assertEquals(ValueRange.Auto.resolve(Array(Double.NaN), 0, 1), (0.0, 1.0))
  }

  test("a fixed range is returned as it is, and must be increasing") {
    assertEquals(ValueRange.fixed(0.0, 1.0).resolve(Array(5.0), 0, 1), (0.0, 1.0))
    intercept[IllegalArgumentException](ValueRange.fixed(1.0, 1.0))
    intercept[IllegalArgumentException](ValueRange.fixed(2.0, 1.0))
  }

  test("the heatmap and particle views have usable defaults") {
    View.Heatmap("u") match
      case View.Heatmap(slice, range, colormap, title, _) =>
        assertEquals((slice, range, colormap, title), ("u", ValueRange.Auto, Colormap.Viridis, ""))
      case other => fail(s"expected a heatmap, got $other")
    View.Particles() match
      case View.Particles(x, y, vx, vy, widthParam, heightParam, colormap, _) =>
        assertEquals((x, y, vx, vy), ("x", "y", "vx", "vy"))
        assertEquals((widthParam, heightParam, colormap), ("width", "height", Colormap.Cyclic))
      case other => fail(s"expected particles, got $other")
  }
