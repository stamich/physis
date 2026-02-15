package physis.fx

import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage

import physis.core.live.{Slice, Snapshot}
import physis.view.View

class ParticlesBandSuite extends munit.FunSuite:
  test("headings fall into 16 equal bands starting at -pi") {
    assertEquals(ParticlesRenderer.bandOf(1.0, 0.0), 8)
    assertEquals(ParticlesRenderer.bandOf(0.0, 1.0), 12)
    assertEquals(ParticlesRenderer.bandOf(0.0, -1.0), 4)
    assertEquals(ParticlesRenderer.bandOf(-1.0, -1e-12), 0)
    assertEquals(ParticlesRenderer.bandOf(-1.0, 0.0), 15)
    val bands = (0 until 16).map { k =>
      val a = -math.Pi + (k + 0.5) * 2 * math.Pi / 16
      ParticlesRenderer.bandOf(math.cos(a), math.sin(a))
    }
    assertEquals(bands, 0 until 16)
  }

  test("a particle at rest is in the band of heading 0") {
    assertEquals(ParticlesRenderer.bandOf(0.0, 0.0), 8)
  }

class ParticlesRendererSuite extends FxSuite:

  /** A world of 100 x 100 with particles at the given positions and velocities. */
  private def world(x: Array[Double], y: Array[Double], vx: Array[Double], vy: Array[Double]) =
    val n = x.length
    val s = new Snapshot(4 * n, 0)
    for (_, k, values) <- Seq(("x", 0, x), ("y", 1, y), ("vx", 2, vx), ("vy", 3, vy)) do
      System.arraycopy(values, 0, s.state, k * n, n)
    s.slices = Vector(
      Slice("x", 0, n, 1),
      Slice("y", n, n, 1),
      Slice("vx", 2 * n, n, 1),
      Slice("vy", 3 * n, n, 1)
    )
    s.paramNames = Vector("width", "height")
    s.paramValues = Vector(100.0, 100.0)
    s

  private def spread(n: Int): Snapshot =
    val angles = Array.tabulate(n)(i => -math.Pi + (i + 0.5) * 2 * math.Pi / n)
    world(
      Array.tabulate(n)(i => 10.0 + (i * 37) % 80),
      Array.tabulate(n)(i => 10.0 + (i * 53) % 80),
      angles.map(math.cos),
      angles.map(math.sin)
    )

  private def particles: View.Particles = View.Particles() match
    case p: View.Particles => p
    case other => fail(s"not particles: $other")

  /** Runs `body` with a renderer in a shown window of 400 x 300, laid out. */
  private def shown(body: ParticlesRenderer => Unit): Unit =
    val r = FxTesting.onFx(new ParticlesRenderer(particles))
    val stage = FxTesting.onFx {
      val st = new Stage()
      st.setScene(new Scene(new StackPane(r.node), 400, 300))
      st.show()
      st.getScene.getRoot.layout()
      st
    }
    try body(r)
    finally FxTesting.onFx(stage.close())

  private def draw(r: ParticlesRenderer, s: Snapshot): Unit = FxTesting.onFx {
    r.accept(s)
    r.render()
  }

  fxTest("a frame makes 16 fill calls however many particles there are") {
    shown { r =>
      draw(r, spread(200))
      assertEquals(FxTesting.onFx(r.lastFillCalls), 16)
      draw(r, spread(5000))
      assertEquals(FxTesting.onFx(r.lastFillCalls), 16)
      assertEquals(FxTesting.onFx(r.lastTriangles) >= 5000, true)
    }
  }

  fxTest("particles with one heading share one fill") {
    shown { r =>
      val n = 50
      draw(
        r,
        world(Array.fill(n)(50.0), Array.fill(n)(50.0), Array.fill(n)(1.0), Array.fill(n)(0.0))
      )
      assertEquals(FxTesting.onFx(r.lastFillCalls), 1)
      assertEquals(FxTesting.onFx(r.lastTriangles), n)
    }
  }

  fxTest("a particle near an edge is drawn on both sides, near a corner four times") {
    shown { r =>
      def triangles(x: Double, y: Double): Int =
        draw(r, world(Array(x), Array(y), Array(1.0), Array(0.0)))
        FxTesting.onFx(r.lastTriangles)
      assertEquals(triangles(50.0, 50.0), 1)
      assertEquals(triangles(0.2, 50.0), 2)
      assertEquals(triangles(99.8, 50.0), 2)
      assertEquals(triangles(50.0, 0.2), 2)
      assertEquals(triangles(0.2, 0.2), 4)
    }
  }

  fxTest("without the slices or the world size nothing is drawn") {
    shown { r =>
      val s = spread(10)
      s.paramNames = Vector.empty
      draw(r, s)
      assertEquals(FxTesting.onFx(r.lastFillCalls), 0)
      val t = spread(10)
      t.slices = t.slices.take(3)
      draw(r, t)
      assertEquals(FxTesting.onFx(r.lastFillCalls), 0)
    }
  }

  fxTest("the flock is visible on the canvas") {
    shown { r =>
      draw(r, spread(300))
      val lit = FxTesting.onFx {
        val image = r.node.snapshot(null, null)
        val reader = image.getPixelReader
        val bg = Theme.Background
        val pixels =
          for
            x <- 0 until image.getWidth.toInt
            y <- 0 until image.getHeight.toInt
            c = reader.getColor(x, y)
            if math.abs(c.getRed - bg.getRed) + math.abs(c.getBlue - bg.getBlue) > 0.1
          yield 1
        pixels.size
      }
      assert(lit > 300, s"lit = $lit")
    }
  }
