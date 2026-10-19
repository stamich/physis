package physis.fx

import javafx.scene.input.{MouseButton, MouseEvent, ScrollEvent}
import javafx.scene.layout.Region
import javafx.scene.paint.Color

import physis.core.live.Snapshot
import physis.view.{StrokePlanner, View}

/**
 * Draws a [[View.Particles]]: every particle of a periodic world as a small triangle pointing
 * along its velocity, coloured by heading.
 *
 * The headings are sorted into 16 bands of equal angle, each with one colour of the map. A frame
 * builds one path with all triangles of a band and fills it once, so it costs at most 16 fill
 * calls however many particles there are. The world is scaled uniformly and centred. A particle
 * close to an edge is drawn a second time on the opposite side, so the flock does not seem to
 * be cut off at the border.
 *
 * Like a heatmap it can be painted on ([[Paintable]]): dabs are in world units, y up, and the
 * brush ring is drawn on the canvas after the particles.
 */
final class ParticlesRenderer(view: View.Particles) extends Renderer with Paintable:
  private val pane = new CanvasPane
  val node: Region = pane

  private val colors: IndexedSeq[Color] = Theme.bands(view.colormap, ParticlesRenderer.Bands, false)

  private var count = 0
  private var xs = new Array[Double](0)
  private var ys = new Array[Double](0)
  private var vxs = new Array[Double](0)
  private var vys = new Array[Double](0)
  private var band = new Array[Int](0)
  private var worldW = 0.0
  private var worldH = 0.0
  private var ready = false

  // ---- pointer tools ----
  radiusRange = (5.0, 400.0)
  private var brushOn = false
  private var brushRadius = 60.0
  private var periodicX = true
  private var periodicY = true
  private var stroke: StrokePlanner = null
  private var strokeSecondary = false
  private var strokeFinished = false
  private var pointer: Option[(Double, Double)] = None
  private var ringMoved = false
  // geometry of the last frame, to turn pixels into world units
  private var left = 0.0
  private var top = 0.0
  private var frameHeight = 0.0
  private var frameScale = 0.0

  def setBrush(enabled: Boolean, radius: Double): Unit =
    brushOn = enabled
    brushRadius = math.max(radiusRange._1, math.min(radiusRange._2, radius))
    ringMoved = true

  def brushEnabled: Boolean = brushOn

  def radius: Double = brushRadius

  def stroking: Boolean = stroke != null && !strokeFinished

  def setWrap(x: Boolean, y: Boolean): Unit =
    periodicX = x
    periodicY = y

  private def worldX(e: MouseEvent): Double = (e.getX - left) / frameScale
  private def worldY(e: MouseEvent): Double = (top + frameHeight - e.getY) / frameScale

  pane.setOnMouseMoved { e =>
    pointer = Some((e.getX, e.getY))
    ringMoved = true
  }
  pane.setOnMouseExited { _ =>
    pointer = None
    ringMoved = true
  }
  pane.setOnMousePressed { e =>
    if brushOn && frameScale > 0.0 && (e.getButton == MouseButton.PRIMARY || e.getButton == MouseButton.SECONDARY)
    then
      stroke = new StrokePlanner(worldW, worldH, periodicX, periodicY)
      stroke.radius = brushRadius
      strokeSecondary = e.getButton == MouseButton.SECONDARY
      strokeFinished = false
      stroke.begin(worldX(e), worldY(e))
  }
  pane.setOnMouseDragged { e =>
    pointer = Some((e.getX, e.getY))
    ringMoved = true
    if frameScale > 0.0 && stroke != null && !strokeFinished then
      stroke.extend(worldX(e), worldY(e))
  }
  pane.setOnMouseReleased { e =>
    if stroke != null && !strokeFinished then
      stroke.extend(worldX(e), worldY(e))
      stroke.end()
      strokeFinished = true
  }
  pane.setOnScroll { (e: ScrollEvent) =>
    if brushOn && e.getDeltaY != 0.0 then
      val step = math.max(1.0, math.round(brushRadius * 0.1).toDouble)
      setBrush(true, if e.getDeltaY > 0.0 then brushRadius + step else brushRadius - step)
      onRadius(brushRadius)
      e.consume()
  }

  private def flushStroke(): Unit =
    if stroke != null then
      val dabs = stroke.take()
      if dabs.nonEmpty || strokeFinished then
        onStroke(StrokeBatch(strokeSecondary, dabs, strokeFinished))
      if strokeFinished then stroke = null

  /** Fill calls made by the last frame (at most [[ParticlesRenderer.Bands]]), for tests. */
  @volatile var lastFillCalls: Int = 0

  /** Triangles drawn by the last frame, wrapped copies included, for tests. */
  @volatile var lastTriangles: Int = 0

  def accept(s: Snapshot): Unit =
    val sx = s.slice(view.x)
    val sy = s.slice(view.y)
    val svx = s.slice(view.vx)
    val svy = s.slice(view.vy)
    val w = s.param(view.widthParam)
    val h = s.param(view.heightParam)
    ready = false
    if sx.isDefined && sy.isDefined && svx.isDefined && svy.isDefined && w > 0.0 && h > 0.0 then
      val n = sx.get.length
      if sy.get.length == n && svx.get.length == n && svy.get.length == n then
        if n != count then
          count = n
          xs = new Array[Double](n)
          ys = new Array[Double](n)
          vxs = new Array[Double](n)
          vys = new Array[Double](n)
          band = new Array[Int](n)
        System.arraycopy(s.state, sx.get.offset, xs, 0, n)
        System.arraycopy(s.state, sy.get.offset, ys, 0, n)
        System.arraycopy(s.state, svx.get.offset, vxs, 0, n)
        System.arraycopy(s.state, svy.get.offset, vys, 0, n)
        worldW = w
        worldH = h
        var i = 0
        while i < n do
          band(i) = ParticlesRenderer.bandOf(vxs(i), vys(i))
          i += 1
        ready = true

  def dirty: Boolean =
    val resized = pane.takeResized()
    val moved = ringMoved
    resized || moved || stroke != null

  def reset(): Unit = ()

  def render(): Unit =
    val _ = pane.takeResized()
    val gc = pane.canvas.getGraphicsContext2D
    val w = pane.width
    val h = pane.height
    gc.setFill(Theme.Background)
    gc.fillRect(0, 0, w, h)
    var fills = 0
    var triangles = 0
    if ready && worldW > 0.0 && worldH > 0.0 then
      val pad = 6.0
      val scale = math.max(1e-9, math.min((w - 2 * pad) / worldW, (h - 2 * pad) / worldH))
      val pw = worldW * scale
      val ph = worldH * scale
      left = (w - pw) / 2
      top = (h - ph) / 2
      frameHeight = ph
      frameScale = scale
      gc.setStroke(Theme.Axis)
      gc.setLineWidth(1)
      gc.strokeRect(left - 0.5, top - 0.5, pw + 1, ph + 1)
      val len = ParticlesRenderer.Length
      val half = ParticlesRenderer.HalfWidth
      val edge = len
      var b = 0
      while b < ParticlesRenderer.Bands do
        var any = false
        var i = 0
        while i < count do
          if band(i) == b then
            if !any then
              gc.beginPath()
              any = true
            val px = left + xs(i) * scale
            // world y points up, the screen's down
            val py = top + ph - ys(i) * scale
            val speed = math.sqrt(vxs(i) * vxs(i) + vys(i) * vys(i))
            val ux = if speed > 0.0 then vxs(i) / speed else 1.0
            val uy = if speed > 0.0 then -vys(i) / speed else 0.0
            val offX = if px - left < edge then pw else if left + pw - px < edge then -pw else 0.0
            val offY = if py - top < edge then ph else if top + ph - py < edge then -ph else 0.0
            var ox = 0
            while ox < 2 do
              var oy = 0
              while oy < 2 do
                val dx = if ox == 0 then 0.0 else offX
                val dy = if oy == 0 then 0.0 else offY
                if (ox == 0 || offX != 0.0) && (oy == 0 || offY != 0.0) then
                  val cx = px + dx
                  val cy = py + dy
                  // tip ahead of the centre, base two corners behind it
                  gc.moveTo(cx + ux * len * 0.6, cy + uy * len * 0.6)
                  gc.lineTo(cx - ux * len * 0.4 - uy * half, cy - uy * len * 0.4 + ux * half)
                  gc.lineTo(cx - ux * len * 0.4 + uy * half, cy - uy * len * 0.4 - ux * half)
                  gc.closePath()
                  triangles += 1
                oy += 1
              ox += 1
          i += 1
        if any then
          gc.setFill(colors(b))
          gc.fill()
          fills += 1
        b += 1
    if brushOn && frameScale > 0.0 then
      pointer.foreach { (px, py) =>
        val rr = brushRadius * frameScale
        gc.setStroke(Color.WHITE)
        gc.setLineWidth(1.5)
        gc.strokeOval(px - rr, py - rr, 2 * rr, 2 * rr)
      }
    ringMoved = false
    flushStroke()
    lastFillCalls = fills
    lastTriangles = triangles

object ParticlesRenderer:
  /** Number of heading bands, and so the most fill calls per frame. */
  val Bands: Int = 16

  /** Length and half width of a triangle, in pixels. */
  private val Length = 9.0
  private val HalfWidth = 3.0

  /** The band of heading `atan2(vy, vx)`: band 0 starts at -pi, and all bands are equal. */
  def bandOf(vx: Double, vy: Double): Int =
    val angle = math.atan2(vy, vx)
    val b = ((angle + math.Pi) / (2 * math.Pi) * Bands).toInt
    if b >= Bands then Bands - 1 else if b < 0 then 0 else b
