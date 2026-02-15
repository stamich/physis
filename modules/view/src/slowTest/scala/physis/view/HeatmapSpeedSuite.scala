package physis.view

class HeatmapSpeedSuite extends munit.FunSuite:

  test("65 536 cells are coloured in under 1 ms (median)") {
    val n = 256
    val values = Array.tabulate(n * n)(k => math.sin(k * 0.001) * 0.5 + 0.5)
    val out = new Array[Int](n * n)
    val lut = HeatmapRaster.lut(Colormap.Viridis)
    def once(): Long =
      val start = System.nanoTime()
      HeatmapRaster.fill(values, 0, n, n, lut, 0.0, 1.0, out)
      System.nanoTime() - start
    for _ <- 1 to 300 do once()
    val times = Array.fill(201)(once()).sorted
    val median = times(100)
    assert(median < 1_000_000L, s"median ${median / 1000} microseconds")
  }
