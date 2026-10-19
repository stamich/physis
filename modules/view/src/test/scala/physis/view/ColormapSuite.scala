package physis.view

class ColormapSuite extends munit.FunSuite:
  test("endpoints match the published palettes") {
    assertEquals(Colormap.Viridis(0.0).hex, "#440154")
    assertEquals(Colormap.Viridis(1.0).hex, "#fde725")
    assertEquals(Colormap.Cividis(0.0).hex, "#00204d")
    assertEquals(Colormap.Cividis(1.0).hex, "#ffea46")
    val mid = Colormap.Diverging(0.5)
    assert(mid.r > 0.95 && mid.g > 0.95 && mid.b > 0.95, mid.hex)
  }

  test("every table has 256 entries; out-of-range input is clamped") {
    for cm <- Colormap.values do
      assertEquals(cm.table.size, Colormap.Size)
      assertEquals(cm(-3.0), cm(0.0))
      assertEquals(cm(7.0), cm(1.0))
      assertEquals(cm(Double.NaN), cm(0.0))
  }

  test("viridis and cividis lightness increases monotonically") {
    for cm <- Seq(Colormap.Viridis, Colormap.Cividis) do
      val l = cm.table.map(Colormap.lightness)
      for i <- 1 until l.size do assert(l(i) > l(i - 1), s"$cm at $i: ${l(i - 1)} -> ${l(i)}")
      assert(l.last - l.head > 70, s"$cm lightness range ${l.head}..${l.last}")
  }

  test("the diverging map is lightest in the middle; the cyclic map closes") {
    val d = Colormap.Diverging.table.map(Colormap.lightness)
    assert(Set(127, 128).contains(d.indexOf(d.max)), d.indexOf(d.max).toString)
    assertEquals(Colormap.Cyclic(0.0), Colormap.Cyclic(1.0))
  }

  test("lookups return shared table entries") {
    assert(Colormap.Viridis(0.3) eq Colormap.Viridis(0.3))
  }
