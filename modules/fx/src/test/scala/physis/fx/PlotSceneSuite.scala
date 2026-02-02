package physis.fx

import javafx.scene.Scene
import javafx.scene.layout.{Region, StackPane}
import javafx.stage.Stage

import physis.core.live.Snapshot
import physis.view.{Bounds, Channel, Rgb, Shape, TrailSpec, View}

class TicksSuite extends munit.FunSuite:
  test("nice steps of 1, 2 and 5 times a power of ten") {
    assertEquals(Ticks.step(0, 10, 5), 2.0)
    assertEquals(Ticks.step(0, 1, 5), 0.2)
    assertEquals(Ticks.step(-30, 30, 5), 10.0)
    assertEqualsDouble(Ticks.step(0, 0.003, 6), 0.0005, 1e-15)
    assertEquals(Ticks.step(1, 1), 1.0)
  }

  test("positions cover the range on multiples of the step") {
    assertEquals(Ticks.positions(-0.9, 3.1, 4), Vector(0.0, 1.0, 2.0, 3.0))
    assertEquals(Ticks.positions(-20, 20, 4), Vector(-20.0, -10.0, 0.0, 10.0, 20.0))
  }

  test("labels use the step's precision") {
    assertEquals(Ticks.label(0.30000000000000004, 0.1), "0.3")
    assertEquals(Ticks.label(20.0, 10.0), "20")
    assertEquals(Ticks.label(-0.0005, 0.0005), "-0.0005")
  }

class PlotSceneSuite extends FxSuite:
  private def show(node: Region): Stage = FxTesting.onFx {
    val stage = new Stage()
    stage.setScene(new Scene(new StackPane(node), 320, 200))
    stage.show()
    stage
  }

  private def litPixels(node: Region): Int = FxTesting.onFx {
    val image = node.snapshot(null, null)
    val reader = image.getPixelReader
    val bg = Theme.Background
    val pixels =
      for
        x <- 0 until image.getWidth.toInt by 2
        y <- 0 until image.getHeight.toInt by 2
        c = reader.getColor(x, y)
        if math.abs(c.getRed - bg.getRed) + math.abs(c.getBlue - bg.getBlue) > 0.1
      yield 1
    pixels.size
  }

  fxTest("the plot draws its channels and rescales smoothly") {
    val plot = new PlotRenderer(View.Plot(
      Seq(Channel.StateAt(0, "a"), Channel.ProbeAt(0, "b")),
      5.0,
      "Test"
    ))
    val s = new Snapshot(1, 1)
    val stage = show(plot.node)
    try
      FxTesting.onFx {
        for i <- 0 until 300 do
          s.time = i * 0.02
          s.state(0) = 10 * math.sin(s.time)
          s.probes(0) = 5.0
          plot.accept(s)
          plot.render()
      }
      assert(litPixels(plot.node) > 100)
      val (lo, hi) = FxTesting.onFx(plot.yRange)
      assert(lo < -9 && hi > 9, s"range $lo..$hi")
      // the data shrinks to a small band: the range follows gradually, not at once
      FxTesting.onFx {
        for i <- 300 until 310 do
          s.time = i * 0.02
          s.state(0) = 0.1
          s.probes(0) = 0.2
          plot.accept(s)
          plot.render()
      }
      val (_, hi2) = FxTesting.onFx(plot.yRange)
      assert(hi2 > 5, s"upper bound jumped to $hi2")
      FxTesting.onFx(plot.reset())
    finally FxTesting.onFx(stage.close())
  }

  fxTest("the scene draws its shapes and a trail") {
    val view: View.Scene2D = View.Scene2D(
      Bounds.square(2),
      in =>
        Seq(
          Shape.Line(0, 0, in.state(0), in.state(1), 2, Rgb.White),
          Shape.Circle(in.state(0), in.state(1), 0.2, Rgb.bytes(220, 40, 40)),
          Shape.Polyline(Array(-1.0, 0.0, 1.0), Array(-1.5, -1.0, -1.5), 1, Rgb.Grey)
        ),
      Some(TrailSpec(200, _.state(0), _.state(1), Rgb.White))
    )
    val scene = new SceneRenderer(view)
    val s = new Snapshot(2, 0)
    val stage = show(scene.node)
    try
      FxTesting.onFx {
        for i <- 0 until 150 do
          s.state(0) = math.cos(i * 0.05)
          s.state(1) = math.sin(i * 0.05)
          scene.accept(s)
          scene.render()
      }
      assertEquals(FxTesting.onFx(scene.lastShapes.size), 3)
      assert(litPixels(scene.node) > 50)
    finally FxTesting.onFx(stage.close())
  }
