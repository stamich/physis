package physis.view

import physis.core.live.{Slice, Snapshot}

class DotsSuite extends munit.FunSuite:
  private val white = 0xffffffff

  private def blank(nx: Int, ny: Int, fill: Int = 0xff000000): Array[Int] =
    Array.fill(nx * ny)(fill)

  private def draw(
      pixels: Array[Int],
      nx: Int,
      ny: Int,
      xs: Array[Double],
      ys: Array[Double],
      opacity: Int = 255,
      color: Int = white
  ): Unit =
    HeatmapRaster.dots(xs, 0, ys, 0, xs.length, nx, ny, color, opacity, pixels)

  test("a dot lands in the cell that contains the position, row 0 on top") {
    val out = blank(4, 3)
    draw(out, 4, 3, Array(2.7), Array(1.2))
    for k <- out.indices do
      assertEquals(out(k), if k == 1 * 4 + 2 then white else 0xff000000, s"cell $k")
  }

  test("positions outside the grid wrap, so a dot at the seam appears on the other side") {
    val out = blank(4, 3)
    draw(out, 4, 3, Array(-0.2, 4.3, 3.9999999999), Array(0.5, -0.5, 2.5))
    assertEquals(out(0 * 4 + 3), white) // x = -0.2 wraps to 3.8
    assertEquals(out(2 * 4 + 0), white) // x = 4.3 wraps to 0.3, y = -0.5 wraps to 2.5
    assertEquals(out(2 * 4 + 3), white)
    assertEquals(out.count(_ == white), 3)
  }

  test("opacity mixes the colour into the cell and a transparent dot changes nothing") {
    val out = blank(2, 2, 0xff0000ff)
    draw(out, 2, 2, Array(0.5), Array(0.5), opacity = 0)
    assertEquals(out.toVector, Vector.fill(4)(0xff0000ff))
    draw(out, 2, 2, Array(0.5), Array(0.5), opacity = 128)
    // (0 * 127 + 255 * 128 + 127) / 255 = 128 for red and green, blue (255 * 127 + 255 * 128 + 127) / 255 = 255
    assertEquals(out(0), 0xff8080ff)
    assertEquals(out(1), 0xff0000ff)
  }

  test("a transparent cell becomes opaque under a dot") {
    val out = blank(2, 2, HeatmapRaster.Transparent)
    draw(out, 2, 2, Array(1.5), Array(1.5), opacity = 255, color = 0xff123456)
    assertEquals(out(3), 0xff123456)
    assertEquals(out(0), HeatmapRaster.Transparent)
  }

  test("NaN and infinite positions are skipped, and cells with several agents mix once per agent") {
    val out = blank(2, 2)
    draw(
      out,
      2,
      2,
      Array(Double.NaN, 0.5, Double.PositiveInfinity, 0.5, 0.5),
      Array(0.5, Double.NaN, 0.5, 0.5, 0.5),
      opacity = 128
    )
    // two agents in cell 0: 255 * 128 / 255 = 128, then (128 * 127 + 255 * 128 + 127) / 255 = 192
    assertEquals(out(0), 0xffc0c0c0)
    assertEquals(out.count(_ != 0xff000000), 1)
  }

  test("the frame of a layered view has the dots over the heatmap, and a missing slice adds none") {
    val n = 6
    val s = new Snapshot(n + 4, 0)
    s.state(n) = 0.5 // x of agent 0
    s.state(n + 1) = 2.5 // x of agent 1
    s.state(n + 2) = 0.5 // y
    s.state(n + 3) = 1.5
    s.slices = Vector(Slice("u", 0, 3, 2), Slice("x", n, 2, 1), Slice("y", n + 2, 2, 1))
    val base = View.Heatmap("u", ValueRange.fixed(0.0, 1.0)) match
      case h: View.Heatmap => h
      case other => fail(s"not a heatmap: $other")
    val plain = FrameImage.heatmap(s, base).get
    val layered = FrameImage.layers(s, View.Layers(base, Dots("x", "y", white, 255))).get
    assertEquals(layered.pixels(0), white)
    assertEquals(layered.pixels(1 * 3 + 2), white)
    assertEquals(layered.pixels.count(_ == white), 2)
    for k <- plain.pixels.indices if k != 0 && k != 5 do
      assertEquals(layered.pixels(k), plain.pixels(k), s"cell $k")
    assertEquals((layered.lo, layered.hi), (plain.lo, plain.hi))
    val missing = FrameImage.layers(s, View.Layers(base, Dots("nope", "y"))).get
    assertEquals(missing.pixels.toVector, plain.pixels.toVector)
  }

  test("opacity must be a byte") {
    intercept[IllegalArgumentException](Dots(opacity = 256))
    intercept[IllegalArgumentException](Dots(opacity = -1))
  }
