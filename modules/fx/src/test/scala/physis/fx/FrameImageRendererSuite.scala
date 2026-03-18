package physis.fx

import physis.core.live.{Slice, Snapshot}
import physis.view.{Colormap, Dots, FrameImage, ValueRange, View}

/** The picture that is exported is the picture that is shown. */
class FrameImageRendererSuite extends FxSuite:

  private def snapshot(): Snapshot =
    val n = 12 * 9
    val s = new Snapshot(2 * n + 3, 0)
    for k <- 0 until n do
      s.state(3 + k) = math.sin(k * 0.37) * (1 + k % 5) - (if k == 17 then Double.NaN else 0.0)
      s.state(3 + n + k) = if k % 7 == 0 then 1.0 else 0.0
    s.slices = Vector(Slice("u", 3, 12, 9), Slice("wall", 3 + n, 12, 9))
    s

  private def heatmap(range: ValueRange, colormap: Colormap, overlay: Option[String]) =
    View.Heatmap("u", range, colormap, "", overlay) match
      case h: View.Heatmap => h
      case other => fail(s"not a heatmap: $other")

  fxTest("export and renderer agree on pixels and range, for every range, map and overlay") {
    val s = snapshot()
    for
      range <- Seq(ValueRange.Auto, ValueRange.SymmetricAuto, ValueRange.fixed(-2.0, 3.0))
      colormap <- Seq(Colormap.Viridis, Colormap.Diverging)
      overlay <- Seq(None, Some("wall"))
    do
      val view = heatmap(range, colormap, overlay)
      val r = FxTesting.onFx(new HeatmapRenderer(view))
      FxTesting.onFx {
        r.accept(s)
        r.render()
      }
      val frame = FrameImage.heatmap(s, view).get
      assertEquals(FxTesting.onFx(r.lastPixels.toVector), frame.pixels.toVector, s"$view")
      assertEquals(FxTesting.onFx(r.range), (frame.lo, frame.hi), s"$view")
  }

  fxTest("with dots, export and renderer agree too, and the dots are where the agents are") {
    val n = 12 * 9
    val s = new Snapshot(n + 2 * 40, 0)
    for k <- 0 until n do s.state(k) = math.cos(k * 0.21)
    for a <- 0 until 40 do
      s.state(n + a) = (a * 7.3) % 14.0 - 1.0 // some outside the grid: they wrap
      s.state(n + 40 + a) = (a * 3.1) % 11.0
    s.slices = Vector(Slice("u", 0, 12, 9), Slice("x", n, 40, 1), Slice("y", n + 40, 40, 1))
    val base = heatmap(ValueRange.Auto, Colormap.Viridis, None)
    val dots = Dots("x", "y", 0xffff8800, 90)
    val r = FxTesting.onFx(new HeatmapRenderer(base, dots = Some(dots)))
    FxTesting.onFx {
      r.accept(s)
      r.render()
    }
    val frame = FrameImage.layers(s, View.Layers(base, dots)).get
    assertEquals(FxTesting.onFx(r.lastPixels.toVector), frame.pixels.toVector)
    val plain = FrameImage.heatmap(s, base).get
    assertNotEquals(frame.pixels.toVector, plain.pixels.toVector)
  }
