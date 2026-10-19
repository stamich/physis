package physis.fx

import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage

import physis.core.live.{Slice, Snapshot}
import physis.core.run.Physis
import physis.models.grayscott.{GrayScott, GrayScottState, GrayScottViews}
import physis.view.{Colormap, HeatmapRaster, ValueRange, View}

class HeatmapRendererSuite extends FxSuite:

  private def heatmap(
      slice: String,
      range: ValueRange = ValueRange.Auto,
      colormap: Colormap = Colormap.Viridis
  ): View.Heatmap =
    View.Heatmap(slice, range, colormap) match
      case h: View.Heatmap => h
      case other => fail(s"not a heatmap: $other")

  private def field(values: Array[Double], nx: Int, ny: Int, offset: Int = 0): Snapshot =
    val s = new Snapshot(offset + values.length, 0)
    System.arraycopy(values, 0, s.state, offset, values.length)
    s.slices = Vector(Slice("f", offset, nx, ny))
    s

  fxTest("a field is coloured cell by cell, row 0 first, NaN transparent") {
    val view = heatmap("f", ValueRange.fixed(0.0, 1.0), Colormap.Viridis)
    val r = FxTesting.onFx(new HeatmapRenderer(view))
    val lut = HeatmapRaster.lut(Colormap.Viridis)
    val values = Array(0.0, 1.0, 0.5, Double.NaN, 2.0, -1.0)
    FxTesting.onFx {
      r.accept(field(values, 3, 2, offset = 4))
      r.render()
    }
    val expected = Vector(lut(0), lut(255), lut(128), 0, lut(255), lut(0))
    assertEquals(FxTesting.onFx(r.lastPixels.toVector), expected)
    assertEquals(FxTesting.onFx(r.gridSize), (3, 2))
    assertEquals(FxTesting.onFx(r.range), (0.0, 1.0))
    val shown = FxTesting.onFx {
      val reader = r.image.getPixelReader
      for y <- 0 until 2; x <- 0 until 3 yield reader.getArgb(x, y)
    }
    assertEquals(shown.toVector, expected)
    val value: Option[Double] = FxTesting.onFx(r.cellAt(2, 0).map(_.value))
    assertEquals(value, Option(0.5))
    val column: Option[Int] = FxTesting.onFx(r.cellAt(0, 1).map(_.x))
    assertEquals(column, Option(0))
    val outside: Boolean = FxTesting.onFx(r.cellAt(3, 0).isEmpty)
    assert(outside)
  }

  fxTest("a changed grid size gets a new image") {
    val r = FxTesting.onFx(new HeatmapRenderer(heatmap("f")))
    FxTesting.onFx {
      r.accept(field(Array.tabulate(6)(_.toDouble), 3, 2))
      r.render()
    }
    assertEquals(FxTesting.onFx(r.range), (0.0, 5.0))
    FxTesting.onFx {
      r.accept(field(Array.tabulate(20)(_.toDouble), 5, 4))
      r.render()
    }
    assertEquals(FxTesting.onFx(r.gridSize), (5, 4))
    assertEquals(FxTesting.onFx(r.image.getWidth), 5.0)
  }

  fxTest("a missing slice draws nothing") {
    val r = FxTesting.onFx(new HeatmapRenderer(heatmap("nope")))
    FxTesting.onFx {
      r.accept(field(Array(1.0, 2.0), 2, 1))
      r.render()
    }
    assertEquals(FxTesting.onFx(r.gridSize), (0, 0))
  }

  fxTest("Gray-Scott for 120 frames: both pixel paths show the same image") {
    val run = Physis
      .model(GrayScott)
      .stepping(GrayScott.stepping)
      .overrides(Map("nx" -> 64.0, "ny" -> 64.0))
      .seed(1L)
      .build()
    val layout = GrayScottState.stateExport
    val snap = new Snapshot(layout.size(run.state), 0)
    snap.slices = layout.layout(run.state)
    val view = GrayScottViews.v match
      case h: View.Heatmap => h
      case other => fail(s"not a heatmap: $other")
    val fast = FxTesting.onFx(new HeatmapRenderer(view, usePixelBuffer = true))
    val slow = FxTesting.onFx(new HeatmapRenderer(view, usePixelBuffer = false))
    val stage = FxTesting.onFx {
      val st = new Stage()
      st.setScene(new Scene(new StackPane(fast.node), 400, 320))
      st.show()
      st
    }
    try
      for _ <- 1 to 120 do
        run.runSteps(10)
        layout.copyInto(run.state, snap.state)
        FxTesting.onFx {
          fast.accept(snap)
          slow.accept(snap)
          fast.render()
          slow.render()
        }
      assert(FxTesting.onFx(fast.pixelBufferPath), "no PixelBuffer")
      assert(!FxTesting.onFx(slow.pixelBufferPath))
      val (a, b) = FxTesting.onFx {
        def read(r: HeatmapRenderer) =
          val reader = r.image.getPixelReader
          for y <- 0 until 64; x <- 0 until 64 yield reader.getArgb(x, y)
        (read(fast), read(slow))
      }
      assertEquals(a, b)
      assert(a.distinct.size > 10, s"only ${a.distinct.size} colours")
      // the node itself shows more than its background
      val lit = FxTesting.onFx {
        val image = fast.node.snapshot(null, null)
        val reader = image.getPixelReader
        val bg = Theme.Background
        val pixels =
          for
            x <- 0 until image.getWidth.toInt by 3
            y <- 0 until image.getHeight.toInt by 3
            c = reader.getColor(x, y)
            if math.abs(c.getRed - bg.getRed) + math.abs(c.getBlue - bg.getBlue) > 0.1
          yield 1
        pixels.size
      }
      assert(lit > 300, s"lit = $lit")
    finally FxTesting.onFx(stage.close())
  }
