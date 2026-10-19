package physis.fx

import java.nio.IntBuffer

import javafx.geometry.Rectangle2D
import javafx.scene.control.Label
import javafx.scene.image.{ImageView, PixelBuffer, PixelFormat, WritableImage}
import javafx.scene.Node
import javafx.scene.input.{MouseButton, MouseEvent, ScrollEvent}
import javafx.scene.layout.{Background, BackgroundFill, HBox, Pane, Priority, Region, VBox}
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.util.Callback

import physis.core.live.Snapshot
import physis.view.{HeatmapRaster, StrokePlanner, View}

/**
 * Draws a [[View.Heatmap]]: one slice of the snapshot as an image with one pixel per cell, a
 * colour bar with the value range, and a read-out of the cell under the pointer.
 *
 * Pixels are written through a JavaFX `PixelBuffer` that wraps the array this renderer fills,
 * so a frame is one pass over the cells and one buffer update. When a `PixelBuffer` cannot be
 * created, or `usePixelBuffer` is false, the same pixels go through `PixelWriter.setPixels`.
 * The image keeps the aspect ratio of the grid and is not smoothed: a cell stays a square.
 *
 * With an `overlay` slice in the view, a second pass over the same pixel array paints the cells
 * of that slice that are 1 or more in flat grey (walls), so both pixel paths show the same
 * image. A symmetric value range puts zero at the middle colour, and the colour bar then reads
 * from -a to a.
 *
 * Pointer tools: while a brush is enabled ([[setBrush]]) the left button starts a stroke, the
 * right button a secondary stroke (the Lab uses it to erase), and the wheel changes the radius.
 * The renderer only turns pointer events into dabs in fractional cell coordinates (see
 * [[StrokePlanner]]) and hands them to [[onStroke]], one batch per frame from [[render]]; what a
 * dab means is up to the receiver. A ring under the pointer shows the brush; it is a separate
 * node over the image, never part of the pixels.
 */
final class HeatmapRenderer(view: View.Heatmap, usePixelBuffer: Boolean = true) extends Renderer
    with Paintable:
  import HeatmapRenderer.*

  private val imageView = new ImageView()
  imageView.setSmooth(false)
  imageView.setPreserveRatio(false)
  private val holder = new Holder(imageView)
  private val bar = new ColorBar
  private val title = new Label(view.title)
  title.setTextFill(Theme.Text)
  title.setManaged(view.title.nonEmpty)
  title.setVisible(view.title.nonEmpty)
  private val body = new HBox(4, holder, bar.node)
  HBox.setHgrow(holder, Priority.ALWAYS)
  private val column = new VBox(2, title, body)
  VBox.setVgrow(body, Priority.ALWAYS)
  column.setBackground(new Background(new BackgroundFill(Theme.Background, null, null)))
  column.setMinSize(0, 0)
  val node: Region = column

  private val lut = HeatmapRaster.lut(view.colormap)
  private val whole: Callback[PixelBuffer[IntBuffer], Rectangle2D] = _ => null

  private var nx = 0
  private var ny = 0
  private var values = new Array[Double](0)
  private var pixels = new Array[Int](0)
  private var writable: WritableImage = null
  private var buffer: PixelBuffer[IntBuffer] = null
  private var fresh = false
  private var lo = 0.0
  private var hi = 1.0
  private var hover: Option[(Int, Int)] = None

  /** Called with the cell under the pointer on every frame, and with `None` when it leaves. */
  var onCell: Option[Cell] => Unit = _ => ()

  imageView.setOnMouseMoved { e =>
    if nx > 0 && imageView.getFitWidth > 0 && imageView.getFitHeight > 0 then
      val ix = math.min(nx - 1, math.max(0, (e.getX / imageView.getFitWidth * nx).toInt))
      val iy = math.min(ny - 1, math.max(0, (e.getY / imageView.getFitHeight * ny).toInt))
      hover = Some((ix, iy))
      onCell(cellAt(ix, iy))
      pointer = Some((cellX(e), cellY(e)))
      placeRing()
  }
  imageView.setOnMouseExited { _ =>
    hover = None
    onCell(None)
    pointer = None
    placeRing()
  }

  // ---- pointer tools ----
  private val ring = new Circle()
  ring.setFill(null)
  ring.setStroke(Color.WHITE)
  ring.setStrokeWidth(1.5)
  ring.setMouseTransparent(true)
  ring.setManaged(false)
  ring.setVisible(false)
  holder.getChildren.add(ring)

  private var brushOn = false
  private var brushRadius = 8.0
  private var periodicX = false
  private var periodicY = false
  private var stroke: StrokePlanner = null
  private var strokeSecondary = false
  private var strokeFinished = false
  private var pointer: Option[(Double, Double)] = None

  /** Turns the brush on or off and sets its radius in cells (1 to 64 unless changed). */
  def setBrush(enabled: Boolean, radius: Double): Unit =
    brushOn = enabled
    brushRadius = math.max(radiusRange._1, math.min(radiusRange._2, radius))
    if !enabled then ring.setVisible(false)
    placeRing()

  def brushEnabled: Boolean = brushOn

  def radius: Double = brushRadius

  def stroking: Boolean = stroke != null && !strokeFinished

  def setWrap(x: Boolean, y: Boolean): Unit =
    periodicX = x
    periodicY = y

  /** The node that receives the pointer, for tests that send synthetic events. */
  def pointerTarget: Node = imageView

  /** The ring under the pointer. */
  def ringNode: Node = ring

  private def scale: Double = if nx > 0 then imageView.getFitWidth / nx else 0.0

  private def cellX(e: MouseEvent): Double = e.getX / scale
  private def cellY(e: MouseEvent): Double = e.getY / scale

  private def placeRing(): Unit =
    pointer match
      case Some((cx, cy)) if brushOn && scale > 0.0 =>
        ring.setCenterX(imageView.getLayoutX + cx * scale)
        ring.setCenterY(imageView.getLayoutY + cy * scale)
        ring.setRadius(brushRadius * scale)
        ring.setVisible(true)
      case _ => ring.setVisible(false)

  imageView.setOnMousePressed { e =>
    if brushOn && scale > 0.0 && (e.getButton == MouseButton.PRIMARY || e.getButton == MouseButton.SECONDARY)
    then
      stroke = new StrokePlanner(nx.toDouble, ny.toDouble, periodicX, periodicY)
      stroke.radius = brushRadius
      strokeSecondary = e.getButton == MouseButton.SECONDARY
      strokeFinished = false
      stroke.begin(cellX(e), cellY(e))
      pointer = Some((cellX(e), cellY(e)))
      placeRing()
  }
  imageView.setOnMouseDragged { e =>
    if scale > 0.0 then
      pointer = Some((cellX(e), cellY(e)))
      if stroke != null && !strokeFinished then stroke.extend(cellX(e), cellY(e))
      placeRing()
  }
  imageView.setOnMouseReleased { e =>
    if stroke != null && !strokeFinished then
      stroke.extend(cellX(e), cellY(e))
      stroke.end()
      strokeFinished = true
  }
  imageView.setOnScroll { (e: ScrollEvent) =>
    if brushOn && e.getDeltaY != 0.0 then
      val step = math.max(1.0, math.round(brushRadius * 0.1).toDouble)
      val next = if e.getDeltaY > 0.0 then brushRadius + step else brushRadius - step
      setBrush(true, next)
      onRadius(brushRadius)
      e.consume()
  }

  /** Hands the dabs planned since the last frame to [[onStroke]]. */
  private def flushStroke(): Unit =
    if stroke != null then
      val dabs = stroke.take()
      if dabs.nonEmpty || strokeFinished then
        onStroke(StrokeBatch(strokeSecondary, dabs, strokeFinished))
      if strokeFinished then stroke = null

  /** Name of the slice this renderer shows. */
  def sliceName: String = view.slice

  /** Whether frames go through a `PixelBuffer` (otherwise through `PixelWriter`). */
  def pixelBufferPath: Boolean = buffer != null

  /** The colours of the last frame, packed ARGB, row 0 first; valid until the next frame. */
  def lastPixels: Array[Int] = pixels

  /** The value range of the last frame. */
  def range: (Double, Double) = (lo, hi)

  /** Number of columns and rows of the displayed field; (0, 0) before the first frame. */
  def gridSize: (Int, Int) = (nx, ny)

  /** The cell at column `ix`, row `iy` of the last frame, if it exists. */
  def cellAt(ix: Int, iy: Int): Option[Cell] =
    if ix >= 0 && ix < nx && iy >= 0 && iy < ny then Some(Cell(ix, iy, values(iy * nx + ix)))
    else None

  /** The displayed image; null before the first frame. */
  def image: WritableImage = writable

  def accept(s: Snapshot): Unit =
    s.slice(view.slice) match
      case None => ()
      case Some(slice) =>
        if slice.width != nx || slice.height != ny then resize(slice.width, slice.height)
        System.arraycopy(s.state, slice.offset, values, 0, slice.length)
        val (l, h) = view.range.resolve(values, 0, slice.length)
        lo = l
        hi = h
        HeatmapRaster.fill(values, 0, nx, ny, lut, lo, hi, pixels)
        view.overlay.foreach { name =>
          s.slice(name) match
            case Some(mask) if mask.width == nx && mask.height == ny =>
              HeatmapRaster.overlay(s.state, mask.offset, nx, ny, pixels)
            case _ => ()
        }
        fresh = true

  private def resize(w: Int, h: Int): Unit =
    nx = w
    ny = h
    values = new Array[Double](w * h)
    pixels = new Array[Int](w * h)
    buffer = null
    if usePixelBuffer then
      try
        buffer = new PixelBuffer[IntBuffer](
          w,
          h,
          IntBuffer.wrap(pixels),
          PixelFormat.getIntArgbPreInstance
        )
      catch case _: Throwable => buffer = null
    writable = if buffer != null then new WritableImage(buffer) else new WritableImage(w, h)
    imageView.setImage(writable)
    holder.setGrid(w, h)
    hover = None

  def dirty: Boolean = bar.resized || stroke != null

  def reset(): Unit = ()

  def render(): Unit =
    if fresh then
      fresh = false
      if buffer != null then buffer.updateBuffer(whole)
      else
        writable.getPixelWriter
          .setPixels(0, 0, nx, ny, PixelFormat.getIntArgbPreInstance, pixels, 0, nx)
    if nx > 0 then bar.update(view.colormap, lo, hi)
    flushStroke()
    placeRing()
    hover.foreach((ix, iy) => onCell(cellAt(ix, iy)))

object HeatmapRenderer:
  /** A cell of the field and its value. */
  final case class Cell(x: Int, y: Int, value: Double):
    /** For a status line, for example `u[12, 40] = 0.4812`. */
    def text(name: String): String = f"$name[$x, $y] = $value%.4g"

  /** Fits the image into the available area with square cells, centred. */
  private final class Holder(image: ImageView) extends Pane:
    private var gx = 0
    private var gy = 0
    getChildren.add(image)
    setMinSize(0, 0)
    setPrefSize(320, 320)

    def setGrid(nx: Int, ny: Int): Unit =
      gx = nx
      gy = ny
      requestLayout()

    override protected def layoutChildren(): Unit =
      if gx > 0 && gy > 0 && getWidth > 0 && getHeight > 0 then
        val scale = math.min(getWidth / gx, getHeight / gy)
        val w = math.max(1.0, math.floor(gx * scale))
        val h = math.max(1.0, math.floor(gy * scale))
        image.setFitWidth(w)
        image.setFitHeight(h)
        image.relocate(math.floor((getWidth - w) / 2), math.floor((getHeight - h) / 2))
