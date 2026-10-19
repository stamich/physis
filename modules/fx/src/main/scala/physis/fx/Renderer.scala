package physis.fx

import javafx.scene.canvas.Canvas
import javafx.scene.layout.{Region, StackPane}

import physis.core.live.Snapshot

/**
 * Draws one view. Lives on the FX application thread and only ever reads snapshots.
 *
 * Per frame the [[RenderLoop]] calls [[accept]] with each new snapshot, then [[render]] if a
 * snapshot arrived or the renderer reports itself [[dirty]] (resized, camera moved).
 */
trait Renderer:
  /** The node to place in the scene graph. */
  def node: Region

  /** Takes in a new snapshot: append to trails, update ranges. Valid only during the call. */
  def accept(snapshot: Snapshot): Unit

  /** Redraws from the data accepted so far. */
  def render(): Unit

  /** Whether a redraw is needed without new data. */
  def dirty: Boolean

  /** Forgets accumulated history (trails, ranges), e.g. after the run was reset. */
  def reset(): Unit

/**
 * A resizable region holding one canvas that always fills it. Canvas itself is not resizable,
 * so renderers draw on this and get marked dirty when the size changes.
 */
final class CanvasPane extends StackPane:
  val canvas: Canvas = new Canvas(1, 1)
  private var resized = true
  getChildren.add(canvas)
  setMinSize(0, 0)
  setPrefSize(400, 300)

  override protected def layoutChildren(): Unit =
    val w = math.max(1.0, snapSizeX(getWidth - snappedLeftInset - snappedRightInset))
    val h = math.max(1.0, snapSizeY(getHeight - snappedTopInset - snappedBottomInset))
    if canvas.getWidth != w || canvas.getHeight != h then
      canvas.setWidth(w)
      canvas.setHeight(h)
      resized = true
    super.layoutChildren()

  /** Whether the canvas changed size since the last call; clears the flag. */
  def takeResized(): Boolean =
    val r = resized
    resized = false
    r

  def width: Double = canvas.getWidth
  def height: Double = canvas.getHeight
