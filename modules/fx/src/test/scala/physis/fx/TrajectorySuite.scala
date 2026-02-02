package physis.fx

import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage

import physis.core.live.Snapshot
import physis.view.{Channel, Trail, TrailColor, View}

class TrajectoryGeometrySuite extends munit.FunSuite:
  private val identity = new Projector:
    def viewX(x: Double, y: Double, z: Double): Double = x
    def viewY(x: Double, y: Double, z: Double): Double = y

  test("segments are binned by age, oldest in band 0, each exactly once") {
    val trail = new Trail(100)
    for i <- 0 until 100 do trail.append(i, -i)
    val g = new TrajectoryGeometry(100, bands = 4)
    g.prepare(trail, identity)
    assertEquals((0 until 4).map(g.segments).sum, 99)
    assertEquals(g.order.take(99).sorted.toVector, (1 to 99).toVector)
    assertEquals(g.order(g.bandStart(0)), 1)
    assertEquals(g.order(g.bandStart(4) - 1), 99)
    for b <- 0 until 4 do
      val ids = (g.bandStart(b) until g.bandStart(b + 1)).map(g.order)
      assertEquals(ids, ids.sorted, s"band $b keeps segment order")
    assertEquals((g.xs(10), g.ys(10)), (10.0, -10.0))
  }

  test("colouring by value uses the given range and clamps") {
    val trail = new Trail(5)
    Seq(-1.0, 0.0, 0.3, 0.7, 2.0).foreach(v => trail.append(0, 0, 0, v))
    val g = new TrajectoryGeometry(5, bands = 2)
    g.prepare(trail, identity, byValue = true, vMin = 0.0, vMax = 1.0)
    assertEquals(g.segments(0), 2) // 0.0 and 0.3
    assertEquals(g.segments(1), 2) // 0.7 and 2.0 (clamped)
  }

  test("the flat projector maps bounds onto the view, y up") {
    val p = new FlatProjector
    p.set(-1, 1, 0, 10, width = 200, height = 100)
    assertEquals(p.viewX(-1, 0, 0), 0.0)
    assertEquals(p.viewX(1, 0, 0), 200.0)
    assertEquals(p.viewY(0, 0, 0), 100.0)
    assertEquals(p.viewY(0, 10, 0), 0.0)
    p.set(-1, 1, -1, 1, width = 200, height = 100, square = true)
    assertEquals(p.viewX(1, 0, 0), 150.0)
  }

class TrajectoryRendererSuite extends FxSuite:
  fxTest("draws a 3D trajectory and reacts to resets") {
    // enum case constructors widen to View unless the expected type is the case
    val renderer = new TrajectoryRenderer(
      View.Trajectory(
        Channel.StateAt(0, "x"),
        Channel.StateAt(1, "y"),
        Some(Channel.StateAt(2, "z")),
        trail = 500,
        colorBy = TrailColor.By(Channel.StateAt(2, "z"))
      )
    )
    val s = new Snapshot(3, 0)
    val stage = FxTesting.onFx {
      val stage = new Stage()
      stage.setScene(new Scene(new StackPane(renderer.node), 300, 200))
      stage.show()
      stage
    }
    try
      FxTesting.onFx {
        for i <- 0 until 400 do
          val a = i * 0.05
          s.state(0) = math.cos(a)
          s.state(1) = math.sin(a)
          s.state(2) = a / 10
          renderer.accept(s)
        renderer.render()
      }
      assertEquals(FxTesting.onFx(renderer.points), 400)
      val lit = FxTesting.onFx {
        val image = renderer.node.snapshot(null, null)
        val reader = image.getPixelReader
        val bg = Theme.Background
        (0 until image.getWidth.toInt by 3).count { x =>
          (0 until image.getHeight.toInt by 3).exists { y =>
            val c = reader.getColor(x, y)
            math.abs(c.getRed - bg.getRed) + math.abs(c.getGreen - bg.getGreen) > 0.1
          }
        }
      }
      assert(lit > 20, s"only $lit columns drawn")
      FxTesting.onFx {
        renderer.camera.rotate(0.5, 0.2)
        renderer.render()
        renderer.reset()
      }
      assertEquals(FxTesting.onFx(renderer.points), 0)
    finally FxTesting.onFx(stage.close())
  }
