package physis.fx

import javafx.scene.layout.Region
import javafx.scene.text.Font

import physis.view.Colormap

/**
 * A vertical colour scale with tick labels: the first colour of the map at the bottom, the last
 * at the top. Redraws only when the colormap, the range or the size changed.
 */
final class ColorBar:
  private val pane = new CanvasPane
  pane.setPrefSize(ColorBar.Width, 200)
  pane.setMinWidth(ColorBar.Width)
  pane.setMaxWidth(ColorBar.Width)

  val node: Region = pane

  private var map: Colormap = Colormap.Viridis
  private var lo = Double.NaN
  private var hi = Double.NaN
  private var stale = true

  /** Whether the bar needs a redraw because it was resized. */
  def resized: Boolean =
    if pane.takeResized() then stale = true
    stale

  /** Redraws if `colormap`, `lo` or `hi` differ from the last call, or the size changed. */
  def update(colormap: Colormap, newLo: Double, newHi: Double): Unit =
    if pane.takeResized() then stale = true
    if stale || (colormap ne map) || newLo != lo || newHi != hi then
      map = colormap
      lo = newLo
      hi = newHi
      stale = false
      draw()

  private def draw(): Unit =
    val gc = pane.canvas.getGraphicsContext2D
    val w = pane.width
    val h = pane.height
    gc.setFill(Theme.Background)
    gc.fillRect(0, 0, w, h)
    val top = ColorBar.Margin
    val bottom = h - ColorBar.Margin
    val span = bottom - top
    if span >= 2.0 then
      var y = 0
      val rows = span.toInt
      while y < rows do
        val t = 1.0 - (y + 0.5) / rows
        gc.setFill(Theme.color(map(t)))
        gc.fillRect(0, top + y, ColorBar.Strip, 1.0)
        y += 1
      gc.setStroke(Theme.Axis)
      gc.setLineWidth(1)
      gc.setFill(Theme.Text)
      gc.setFont(Font.font(10))
      val step = Ticks.step(lo, hi, 5)
      for v <- Ticks.positions(lo, hi, 5) do
        val py = bottom - (v - lo) / (hi - lo) * span
        if py >= top - 0.5 && py <= bottom + 0.5 then
          gc.strokeLine(ColorBar.Strip, py, ColorBar.Strip + 4, py)
          gc.fillText(Ticks.label(v, step), ColorBar.Strip + 6, py + 3.5)

object ColorBar:
  val Width: Double = 62.0
  private val Strip = 12.0
  private val Margin = 8.0
