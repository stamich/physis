package physis.fx

import physis.core.live.{Slice, Snapshot}
import physis.view.{Colormap, FrameImage, ValueRange, View}

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
