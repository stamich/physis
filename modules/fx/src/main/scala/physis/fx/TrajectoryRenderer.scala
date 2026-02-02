package physis.fx

import javafx.scene.input.{MouseButton, MouseEvent, ScrollEvent}
import javafx.scene.layout.Region

import physis.core.live.Snapshot
import physis.view.{Bounds3, Camera, Trail, TrailColor, View, ViewBounds}

/**
 * Draws a [[View.Trajectory]]: a trail of the last `trail` points, 2D or projected 3D, coloured
 * in 32 bands by age or by a channel.
 *
 * 3D views refit the camera to the trail's smoothed bounds every frame, so the attractor fills
 * the view whatever the parameters. Drag rotates, scroll zooms, double-click restores the
 * default orientation and zoom.
 */
final class TrajectoryRenderer(view: View.Trajectory) extends Renderer:
  private val pane = new CanvasPane
  val node: Region = pane

  private val trail = new Trail(view.trail)
  private val geometry = new TrajectoryGeometry(view.trail)
  private val colors = Theme.bands(view.colormap, geometry.bands, fade = true)
  // channels: x, y, z, colour value
  private val bounds = new ViewBounds(4, shrink = 0.02)
  private val colorChannel = view.colorBy match
    case TrailColor.By(c) => Some(c)
    case TrailColor.Age => None

  /** The camera of a 3D view (unused in 2D). */
  val camera: Camera = new Camera
  private val cameraProjector = Projector.of(camera)
  private val flat = new FlatProjector
  private var moved = true
  private var dragX, dragY = 0.0

  pane.setOnMousePressed(pressed)
  pane.setOnMouseDragged(dragged)
  pane.setOnMouseClicked(clicked)
  pane.setOnScroll(scrolled)

  private def pressed(e: MouseEvent): Unit =
    dragX = e.getX
    dragY = e.getY

  private def dragged(e: MouseEvent): Unit =
    if view.z.isDefined && e.getButton == MouseButton.PRIMARY then
      camera.rotate((e.getX - dragX) * 0.01, (e.getY - dragY) * 0.01)
      moved = true
    dragX = e.getX
    dragY = e.getY

  private def clicked(e: MouseEvent): Unit =
    if e.getClickCount == 2 then
      camera.orient(Camera.DefaultYaw, Camera.DefaultPitch)
      camera.resetZoom()
      refitBounds()
      moved = true

  private def scrolled(e: ScrollEvent): Unit =
    camera.zoomBy(math.exp(e.getDeltaY * 0.002))
    moved = true

  def accept(s: Snapshot): Unit =
    val x = view.x.read(s)
    val y = view.y.read(s)
    val z = view.z.fold(0.0)(_.read(s))
    val v = colorChannel.fold(0.0)(_.read(s))
    if !x.isNaN && !y.isNaN && !z.isNaN then trail.append(x, y, z, v)

  def dirty: Boolean = moved || pane.takeResized()

  def reset(): Unit =
    trail.clear()
    bounds.reset()
    moved = true

  /** Number of points currently in the trail. */
  def points: Int = trail.size

  def render(): Unit =
    moved = false
    val _ = pane.takeResized()
    val gc = pane.canvas.getGraphicsContext2D
    val w = pane.width
    val h = pane.height
    gc.setFill(Theme.Background)
    gc.fillRect(0, 0, w, h)
    if trail.size >= 2 then
      updateBounds()
      val projector =
        if view.z.isDefined then
          camera.viewport(w, h)
          camera.fit(
            Bounds3(
              bounds.min(0),
              bounds.max(0),
              bounds.min(1),
              bounds.max(1),
              bounds.min(2),
              bounds.max(2)
            )
          )
          cameraProjector
        else
          val pad = 0.05
          val (x0, x1) = (bounds.min(0), bounds.max(0))
          val (y0, y1) = (bounds.min(1), bounds.max(1))
          val dx = (x1 - x0) * pad
          val dy = (y1 - y0) * pad
          flat.set(x0 - dx, x1 + dx, y0 - dy, y1 + dy, w, h)
          flat
      geometry.prepare(trail, projector, colorChannel.isDefined, bounds.min(3), bounds.max(3))
      draw(gc)

  private def updateBounds(): Unit =
    bounds.begin()
    var i = 0
    while i < trail.size do
      bounds.include(0, trail.x(i))
      bounds.include(1, trail.y(i))
      bounds.include(2, trail.z(i))
      bounds.include(3, trail.value(i))
      i += 1
    bounds.end()

  private def refitBounds(): Unit =
    bounds.reset()
    if trail.size > 0 then updateBounds()

  private def draw(gc: javafx.scene.canvas.GraphicsContext): Unit =
    gc.setLineWidth(1.2)
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
    // head of the trail
    val last = geometry.size - 1
    gc.setFill(colors(geometry.bands - 1))
    gc.fillOval(xs(last) - 3, ys(last) - 3, 6, 6)
