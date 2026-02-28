package physis.view

class ValueRangeSuite extends munit.FunSuite:
  private val lut = HeatmapRaster.lut(Colormap.Diverging)

  test("a symmetric range is centred on zero") {
    assertEquals(ValueRange.symmetric(2.5).resolve(Array(1.0), 0, 1), (-2.5, 2.5))
    assertEquals(ValueRange.Symmetric(1.0), ValueRange.symmetric(1.0))
  }

  test("an empty or infinite symmetric range is refused") {
    intercept[IllegalArgumentException](ValueRange.symmetric(0.0))
    intercept[IllegalArgumentException](ValueRange.symmetric(-1.0))
    intercept[IllegalArgumentException](ValueRange.symmetric(Double.PositiveInfinity))
  }

  test("the automatic symmetric range follows the largest absolute value") {
    val values = Array(0.5, -3.0, 2.0, Double.NaN, 1.0)
    assertEquals(ValueRange.SymmetricAuto.resolve(values, 0, 5), (-3.0, 3.0))
    assertEquals(ValueRange.SymmetricAuto.resolve(values, 2, 1), (-2.0, 2.0))
  }

  test("zero data gets the range [-1, 1], never a division by zero") {
    assertEquals(ValueRange.SymmetricAuto.resolve(Array(0.0, 0.0), 0, 2), (-1.0, 1.0))
    assertEquals(ValueRange.SymmetricAuto.resolve(Array.empty[Double], 0, 0), (-1.0, 1.0))
    assertEquals(ValueRange.SymmetricAuto.resolve(Array(Double.NaN), 0, 1), (-1.0, 1.0))
  }

  test("zero maps to the middle colour, the extremes to the end colours") {
    val values = Array(-4.0, 0.0, 4.0, 2.0)
    val (lo, hi) = ValueRange.SymmetricAuto.resolve(values, 0, values.length)
    val out = new Array[Int](values.length)
    HeatmapRaster.fill(values, 0, values.length, 1, lut, lo, hi, out)
    assertEquals(out(0), lut(0))
    assertEquals(out(1), lut(128))
    assertEquals(out(2), lut(255))
    assertEquals(out(3), lut(191))
  }
