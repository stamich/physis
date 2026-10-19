package physis.fx

import javafx.geometry.VPos
import javafx.scene.canvas.GraphicsContext
import javafx.scene.layout.Region
import javafx.scene.text.{Font, TextAlignment}

import physis.core.live.Snapshot
import physis.view.{Trail, View, ViewBounds}

/**
 * Draws a [[View.Plot]]: the channels over the last `window` simulated seconds, one polyline
 * each, with axes, ticks and a legend. The value range follows the visible data through
 * [[ViewBounds]], so it grows at once and shrinks smoothly.
 *
 * @param capacity samples kept per channel (one per snapshot)
 */
final class PlotRenderer(view: View.Plot, capacity: Int = 8192) extends Renderer:
  private val pane = new CanvasPane
  val node: Region = pane

  private val channels = view.channels.toVector
  private val series = channels.map(_ => new Trail(capacity))
  private val bounds = new ViewBounds(1, shrink = 0.05)
  private val font = Font.font("monospace", 11)
  private var now = 0.0
  private var fresh = false

  def accept(s: Snapshot): Unit =
    now = s.time
    var c = 0
    while c < channels.size do
      series(c).append(s.time, channels(c).read(s))
      c += 1
    fresh = true

  def dirty: Boolean = pane.takeResized()

  def reset(): Unit =
    series.foreach(_.clear())
    bounds.reset()

  /** The value range currently shown. */
  def yRange: (Double, Double) = (bounds.min(0), bounds.max(0))

  def render(): Unit =
    val _ = pane.takeResized()
    val gc = pane.canvas.getGraphicsContext2D
    val w = pane.width
    val h = pane.height
    gc.setFill(Theme.Background)
    gc.fillRect(0, 0, w, h)
    val left = 56.0
    val right = 8.0
    val top = if view.title.nonEmpty then 20.0 else 8.0
    val bottom = 20.0
    val pw = w - left - right
    val ph = h - top - bottom
    if pw > 10 && ph > 10 then
      val t0 = now - view.window
      if fresh then
        updateBounds(t0)
        fresh = false
      val y0 = bounds.min(0)
      val y1 = bounds.max(0)
      val pad = (y1 - y0) * 0.05
      val (lo, hi) = (y0 - pad, y1 + pad)
      def px(t: Double) = left + (t - t0) / view.window * pw
      def py(v: Double) = top + (hi - v) / (hi - lo) * ph
      axes(gc, t0, lo, hi, left, top, pw, ph, px, py)
      gc.save()
      gc.beginPath()
      gc.rect(left, top, pw, ph)
      gc.clip()
      gc.setLineWidth(1.5)
      var c = 0
      while c < series.size do
        val trail = series(c)
        gc.setStroke(Theme.Series(c % Theme.Series.size))
        gc.beginPath()
        var started = false
        var i = firstVisible(trail, t0)
        while i < trail.size do
          val v = trail.y(i)
          if v.isNaN then started = false
          else if started then gc.lineTo(px(trail.x(i)), py(v))
          else
            gc.moveTo(px(trail.x(i)), py(v))
            started = true
          i += 1
        gc.stroke()
        c += 1
      gc.restore()
      legend(gc, left, top)
    if view.title.nonEmpty then
      gc.setFill(Theme.Text)
      gc.setFont(font)
      gc.setTextAlign(TextAlignment.CENTER)
      gc.setTextBaseline(VPos.TOP)
      gc.fillText(view.title, w / 2, 4)

  private def updateBounds(t0: Double): Unit =
    bounds.begin()
    for trail <- series do
      var i = firstVisible(trail, t0)
      while i < trail.size do
        bounds.include(0, trail.y(i))
        i += 1
    bounds.end()

  /** Index of the first sample at or after `t0` (binary search; times increase). */
  private def firstVisible(trail: Trail, t0: Double): Int =
    var lo = 0
    var hi = trail.size
    while lo < hi do
      val mid = (lo + hi) >>> 1
      if trail.x(mid) < t0 then lo = mid + 1 else hi = mid
    math.max(0, lo - 1)

  private def axes(
      gc: GraphicsContext,
      t0: Double,
      lo: Double,
      hi: Double,
      left: Double,
      top: Double,
      pw: Double,
      ph: Double,
      px: Double => Double,
      py: Double => Double
  ): Unit =
    gc.setFont(font)
    gc.setLineWidth(1)
    val ys = Ticks.positions(lo, hi, 5)
    val yStep = Ticks.step(lo, hi, 5)
    gc.setTextAlign(TextAlignment.RIGHT)
    gc.setTextBaseline(VPos.CENTER)
    for v <- ys do
      val y = py(v)
      gc.setStroke(Theme.Grid)
      gc.strokeLine(left, y, left + pw, y)
      gc.setFill(Theme.Text)
      gc.fillText(Ticks.label(v, yStep), left - 4, y)
    val ts = Ticks.positions(t0, t0 + view.window, 6)
    val tStep = Ticks.step(t0, t0 + view.window, 6)
    gc.setTextAlign(TextAlignment.CENTER)
    gc.setTextBaseline(VPos.TOP)
    for t <- ts do
      val x = px(t)
      gc.setStroke(Theme.Grid)
      gc.strokeLine(x, top, x, top + ph)
      gc.setFill(Theme.Text)
      gc.fillText(Ticks.label(t, tStep), x, top + ph + 3)
    gc.setStroke(Theme.Axis)
    gc.strokeRect(left, top, pw, ph)

  private def legend(gc: GraphicsContext, left: Double, top: Double): Unit =
    gc.setFont(font)
    gc.setTextAlign(TextAlignment.LEFT)
    gc.setTextBaseline(VPos.TOP)
    var x = left + 6
    for (c, i) <- channels.zipWithIndex do
      gc.setFill(Theme.Series(i % Theme.Series.size))
      gc.fillRect(x, top + 8, 10, 3)
      gc.setFill(Theme.Text)
      gc.fillText(c.label, x + 14, top + 3)
      x += 24 + 7 * c.label.length
