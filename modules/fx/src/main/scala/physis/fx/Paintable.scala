package physis.fx

import physis.view.Dab

/**
 * The dabs of one stroke planned during one frame.
 *
 * @param secondary whether the stroke was made with the right button
 * @param dabs      brush centres in the renderer's coordinates (cells for a field, world units
 *                  for particles), in order; may be empty on the last batch
 * @param finished  true on the last batch of the stroke (the button was released)
 */
final case class StrokeBatch(secondary: Boolean, dabs: Vector[Dab], finished: Boolean)

/**
 * A renderer the pointer can paint on. It only turns pointer events into dabs and hands them to
 * [[onStroke]], once per rendered frame; what a dab means is up to the receiver.
 */
trait Paintable:
  /** Receives the dabs of the strokes, at most one call per rendered frame. */
  var onStroke: StrokeBatch => Unit = _ => ()

  /** Called with the new radius when the wheel changes it. */
  var onRadius: Double => Unit = _ => ()

  /** Smallest and largest brush radius, in the renderer's units. */
  var radiusRange: (Double, Double) = (1.0, 64.0)

  /** Turns the brush on or off and sets its radius, kept within [[radiusRange]]. */
  def setBrush(enabled: Boolean, radius: Double): Unit

  /** Whether the brush is on. */
  def brushEnabled: Boolean

  /** The brush radius. */
  def radius: Double

  /** Which axes are periodic, so that strokes wrap across the seam. */
  def setWrap(x: Boolean, y: Boolean): Unit
