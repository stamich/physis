package physis.fx

import javafx.scene.layout.Region

import physis.core.live.Snapshot
import physis.view.{SceneInput, Shape, Trail, View}

/**
 * Draws a [[View.Scene2D]]: the shapes its drawing function builds from the newest snapshot,
 * scaled uniformly so the view's bounds fit, plus an optional fading trail of one point.
 */
final class SceneRenderer(view: View.Scene2D) extends Renderer:
  private val pane = new CanvasPane
  val node: Region = pane

  private val input = new SceneRenderer.Input
  private val flat = new FlatProjector
  private val trail = view.trail.map(spec => new Trail(spec.points))
  private val geometry = view.trail.map(spec => new TrajectoryGeometry(spec.points, bands = 16))
  private val trailColors = view.trail.map { spec =>
    val c = spec.color
    Vector.tabulate(16)(b =>
      new javafx.scene.paint.Color(c.r, c.g, c.b, c.opacity * (b + 1) / 16.0)
    )
  }
  private var current: Snapshot = null

  def accept(s: Snapshot): Unit =
    current = s
    input.snapshot = s
    for spec <- view.trail; t <- trail do
      val x = spec.x(input)
      val y = spec.y(input)
      if !x.isNaN && !y.isNaN then t.append(x, y)

  def dirty: Boolean = pane.takeResized()

  def reset(): Unit = trail.foreach(_.clear())

  /** Shapes drawn in the last frame, for tests. */
  @volatile var lastShapes: Seq[Shape] = Seq.empty

  def render(): Unit =
    val _ = pane.takeResized()
    val gc = pane.canvas.getGraphicsContext2D
    val w = pane.width
    val h = pane.height
    gc.setFill(Theme.Background)
    gc.fillRect(0, 0, w, h)
    val b = view.bounds
    flat.set(b.xMin, b.xMax, b.yMin, b.yMax, w, h, square = true)
    for t <- trail; g <- geometry; colors <- trailColors if t.size >= 2 do
      g.prepare(t, flat)
      BandPainter.stroke(gc, g, colors, 1.5)
    if current != null then
      val shapes = view.draw(input)
      lastShapes = shapes
      shapes.foreach(draw(gc, _))

  private def draw(gc: javafx.scene.canvas.GraphicsContext, shape: Shape): Unit =
    shape match
      case Shape.Line(x1, y1, x2, y2, width, color) =>
        gc.setStroke(Theme.color(color))
        gc.setLineWidth(width)
        gc.strokeLine(
          flat.viewX(x1, y1, 0),
          flat.viewY(x1, y1, 0),
          flat.viewX(x2, y2, 0),
          flat.viewY(x2, y2, 0)
        )
      case Shape.Circle(cx, cy, r, fill) =>
        val pr = r * flat.scaleX
        gc.setFill(Theme.color(fill))
        gc.fillOval(flat.viewX(cx, cy, 0) - pr, flat.viewY(cx, cy, 0) - pr, 2 * pr, 2 * pr)
      case Shape.Polyline(xs, ys, width, color) =>
        val n = math.min(xs.length, ys.length)
        if n >= 2 then
          gc.setStroke(Theme.color(color))
          gc.setLineWidth(width)
          gc.beginPath()
          gc.moveTo(flat.viewX(xs(0), ys(0), 0), flat.viewY(xs(0), ys(0), 0))
          var i = 1
          while i < n do
            gc.lineTo(flat.viewX(xs(i), ys(i), 0), flat.viewY(xs(i), ys(i), 0))
            i += 1
          gc.stroke()

object SceneRenderer:
  /** A scene input over a snapshot that changes from frame to frame; no allocation per frame. */
  private final class Input extends SceneInput:
    var snapshot: Snapshot = null
    def time: Double = snapshot.time
    def state(i: Int): Double = snapshot.state(i)
    def probe(i: Int): Double = snapshot.probes(i)
    def param(name: String): Double = snapshot.param(name)
