package physis.fx

import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage

import physis.core.live.{Slice, Snapshot}
import physis.view.{Colormap, HeatmapRaster, ValueRange, View}

class HeatmapOverlayRendererSuite extends FxSuite:

  private val view: View.Heatmap =
    View.Heatmap(
      "u",
      ValueRange.SymmetricAuto,
      Colormap.Diverging,
      "wave",
      overlay = Some("wall")
    ) match
      case h: View.Heatmap => h
      case other => throw new IllegalStateException(s"not a heatmap: $other")

  /** A 6 x 4 field of values -3..2.. with the left half masked. */
  private def snapshot(): Snapshot =
    val n = 24
    val s = new Snapshot(2 * n, 0)
    for k <- 0 until n do
      s.state(k) = (k % 6) - 3.0 + (if k == 23 then 1.0 else 0.0)
      s.state(n + k) = if k % 6 < 3 then 1.0 else 0.0
    s.slices = Vector(Slice("u", 0, 6, 4), Slice("wall", n, 6, 4))
    s

  fxTest("masked cells are flat grey, the rest follows the symmetric range") {
    val r = FxTesting.onFx(new HeatmapRenderer(view))
    FxTesting.onFx {
      r.accept(snapshot())
      r.render()
    }
    val lut = HeatmapRaster.lut(Colormap.Diverging)
    val pixels = FxTesting.onFx(r.lastPixels.toVector)
    assertEquals(pixels.size, 24)
    for k <- 0 until 24 do
      if k % 6 < 3 then assertEquals(pixels(k), HeatmapRaster.OverlayGrey, s"cell $k")
      else assertNotEquals(pixels(k), HeatmapRaster.OverlayGrey, s"cell $k")
    // value 0 never occurs on the open half (values 0..2 and 3), 2 is within +-3
    assertEquals(FxTesting.onFx(r.range), (-3.0, 3.0))
    assertEquals(pixels(3), lut(128))
    assertEquals(pixels(4), lut(170))
  }

  fxTest("a missing or differently sized overlay slice is ignored") {
    val r = FxTesting.onFx(new HeatmapRenderer(view))
    val s = snapshot()
    s.slices = Vector(Slice("u", 0, 6, 4))
    FxTesting.onFx {
      r.accept(s)
      r.render()
    }
    assert(FxTesting.onFx(r.lastPixels.forall(_ != HeatmapRaster.OverlayGrey)))
    s.slices = Vector(Slice("u", 0, 6, 4), Slice("wall", 24, 3, 4))
    FxTesting.onFx {
      r.accept(s)
      r.render()
    }
    assert(FxTesting.onFx(r.lastPixels.forall(_ != HeatmapRaster.OverlayGrey)))
  }

  fxTest("both pixel paths show the same image with an overlay") {
    val fast = FxTesting.onFx(new HeatmapRenderer(view, usePixelBuffer = true))
    val slow = FxTesting.onFx(new HeatmapRenderer(view, usePixelBuffer = false))
    val stage = FxTesting.onFx {
      val st = new Stage()
      st.setScene(new Scene(new StackPane(fast.node), 300, 240))
      st.show()
      st
    }
    try
      FxTesting.onFx {
        val s = snapshot()
        fast.accept(s)
        slow.accept(s)
        fast.render()
        slow.render()
      }
      assert(FxTesting.onFx(fast.pixelBufferPath))
      val (a, b) = FxTesting.onFx {
        def read(r: HeatmapRenderer) =
          val reader = r.image.getPixelReader
          for y <- 0 until 4; x <- 0 until 6 yield reader.getArgb(x, y)
        (read(fast), read(slow))
      }
      assertEquals(a, b)
      assertEquals(a(0), HeatmapRaster.OverlayGrey)
    finally FxTesting.onFx(stage.close())
  }
