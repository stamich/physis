package physis.view

/** One application of a brush: its centre in cell coordinates (fractional). */
final case class Dab(x: Double, y: Double)

/**
 * Turns the positions of a pointer dragged over a field into dabs of a brush with no gaps.
 *
 * Positions are in cell coordinates. Every position farther than `radius / 4` from the last dab
 * is joined to it by equally spaced dabs, no more than `radius / 2` apart; closer positions are
 * merged into the next one, so a slow drag does not pile up dabs. On a periodic axis the shorter
 * way round is taken, so a drag over the seam continues on the other side, and dab centres are
 * wrapped into the world.
 *
 * The planner is plain data and arithmetic, with no toolkit: the renderer forwards pointer
 * positions and takes one batch per frame with [[take]].
 *
 * @param width  world width in cells
 * @param height world height in cells
 * @param wrapX  whether the x axis is periodic
 * @param wrapY  whether the y axis is periodic
 */
final class StrokePlanner(width: Double, height: Double, wrapX: Boolean, wrapY: Boolean):
  require(width > 0.0 && height > 0.0, s"world must have a size, got $width x $height")

  private var lastX = 0.0
  private var lastY = 0.0
  private var down = false
  private var pending = Vector.empty[Dab]

  /** Brush radius in cells; may change between strokes. */
  var radius: Double = 8.0

  /** Whether a stroke is in progress. */
  def active: Boolean = down

  /** Starts a stroke with a dab at `(x, y)`. */
  def begin(x: Double, y: Double): Unit =
    down = true
    lastX = wrap(x, width, wrapX)
    lastY = wrap(y, height, wrapY)
    pending = pending :+ Dab(lastX, lastY)

  /** Continues the stroke to `(x, y)`; does nothing unless a stroke was begun. */
  def extend(x: Double, y: Double): Unit =
    if down then
      val dx = delta(x - lastX, width, wrapX)
      val dy = delta(y - lastY, height, wrapY)
      val distance = math.sqrt(dx * dx + dy * dy)
      if distance >= radius / 4.0 then
        val k = math.max(1, math.ceil(distance / (radius / 2.0)).toInt)
        val x0 = lastX
        val y0 = lastY
        var s = 1
        while s <= k do
          val f = s.toDouble / k
          pending =
            pending :+ Dab(wrap(x0 + dx * f, width, wrapX), wrap(y0 + dy * f, height, wrapY))
          s += 1
        lastX = pending.last.x
        lastY = pending.last.y

  /** Ends the stroke. Dabs not yet taken stay available. */
  def end(): Unit = down = false

  /** The dabs planned since the last call, in order; empty if there are none. */
  def take(): Vector[Dab] =
    val out = pending
    pending = Vector.empty
    out

  private def delta(d: Double, size: Double, wrapAxis: Boolean): Double =
    if wrapAxis then d - size * math.rint(d / size) else d

  private def wrap(v: Double, size: Double, wrapAxis: Boolean): Double =
    if wrapAxis then
      val w = v - size * math.floor(v / size)
      if w >= size then 0.0 else w
    else v
