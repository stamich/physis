package physis.fx

import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color

/** Strokes prepared trail geometry: one path per colour band. */
object BandPainter:
  /** Strokes every band of `geometry` with `colors(band)` and the given line width. */
  def stroke(
      gc: GraphicsContext,
      geometry: TrajectoryGeometry,
      colors: IndexedSeq[Color],
      width: Double
  ): Unit =
    gc.setLineWidth(width)
    val xs = geometry.xs
    val ys = geometry.ys
    var b = 0
    while b < geometry.bands do
      val start = geometry.bandStart(b)
      val end = geometry.bandStart(b + 1)
      if end > start then
        gc.setStroke(colors(b))
        gc.beginPath()
        var previous = -2
        var k = start
        while k < end do
          val i = geometry.order(k)
          if i - 1 != previous then gc.moveTo(xs(i - 1), ys(i - 1))
          gc.lineTo(xs(i), ys(i))
          previous = i
          k += 1
        gc.stroke()
      b += 1
