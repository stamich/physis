package physis.fx

import java.nio.IntBuffer

import javafx.geometry.Rectangle2D
import javafx.scene.control.Label
import javafx.scene.image.{ImageView, PixelBuffer, PixelFormat, WritableImage}
import javafx.scene.layout.{Background, BackgroundFill, HBox, Pane, Priority, Region, VBox}
import javafx.util.Callback

import physis.core.live.Snapshot
import physis.view.{HeatmapRaster, View}

/**
 * Draws a [[View.Heatmap]]: one slice of the snapshot as an image with one pixel per cell, a
 * colour bar with the value range, and a read-out of the cell under the pointer.
 *
 * Pixels are written through a JavaFX `PixelBuffer` that wraps the array this renderer fills,
 * so a frame is one pass over the cells and one buffer update. When a `PixelBuffer` cannot be
 * created, or `usePixelBuffer` is false, the same pixels go through `PixelWriter.setPixels`.
 * The image keeps the aspect ratio of the grid and is not smoothed: a cell stays a square.
 */
final class HeatmapRenderer(view: View.Heatmap, usePixelBuffer: Boolean = true) extends Renderer:
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
  }
  imageView.setOnMouseExited { _ =>
    hover = None
    onCell(None)
  }

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

  def dirty: Boolean = bar.resized

  def reset(): Unit = ()

  def render(): Unit =
    if fresh then
      fresh = false
      if buffer != null then buffer.updateBuffer(whole)
      else
        writable.getPixelWriter
          .setPixels(0, 0, nx, ny, PixelFormat.getIntArgbPreInstance, pixels, 0, nx)
    if nx > 0 then bar.update(view.colormap, lo, hi)
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
