package physis.view

/**
 * Display ranges of several channels that grow at once and shrink smoothly, so plots and fitted
 * views rescale without jumping.
 *
 * Each frame the renderer calls [[begin]], [[include]]s every value it is about to draw, then
 * [[end]]. A range that the new values exceed expands immediately; a range larger than needed
 * moves a fraction `shrink` of the way towards the new extremes per frame.
 *
 * Owned by the rendering thread; allocates nothing after construction.
 *
 * @param channels number of independent ranges
 * @param shrink   fraction of the excess removed per frame, in (0, 1]
 */
final class ViewBounds(val channels: Int, shrink: Double = 0.05):
  require(channels > 0, "at least one channel")
  require(shrink > 0 && shrink <= 1, "shrink must be in (0, 1]")

  private val lo = Array.fill(channels)(Double.NaN)
  private val hi = Array.fill(channels)(Double.NaN)
  private val frameLo = new Array[Double](channels)
  private val frameHi = new Array[Double](channels)
  begin()

  /** Starts collecting the values of a frame. */
  def begin(): Unit =
    java.util.Arrays.fill(frameLo, Double.PositiveInfinity)
    java.util.Arrays.fill(frameHi, Double.NegativeInfinity)

  /** Includes one value of channel `c` in the current frame; NaN and infinities are ignored. */
  def include(c: Int, v: Double): Unit =
    if !v.isNaN && !v.isInfinite then
      if v < frameLo(c) then frameLo(c) = v
      if v > frameHi(c) then frameHi(c) = v

  /** Updates the display ranges from the frame's values. Channels without values keep theirs. */
  def end(): Unit =
    var c = 0
    while c < channels do
      if frameLo(c) <= frameHi(c) then
        if lo(c).isNaN then
          lo(c) = frameLo(c)
          hi(c) = frameHi(c)
        else
          lo(c) = if frameLo(c) < lo(c) then frameLo(c) else lo(c) + (frameLo(c) - lo(c)) * shrink
          hi(c) = if frameHi(c) > hi(c) then frameHi(c) else hi(c) - (hi(c) - frameHi(c)) * shrink
      c += 1

  /** Whether channel `c` has seen any value yet. */
  def isEmpty(c: Int): Boolean = lo(c).isNaN

  /** Lower end of channel `c`'s range, widened so that the range is never empty. */
  def min(c: Int): Double = if hi(c) - lo(c) > ViewBounds.MinSpan then lo(c) else centre(c) - 0.5

  /** Upper end of channel `c`'s range, widened so that the range is never empty. */
  def max(c: Int): Double = if hi(c) - lo(c) > ViewBounds.MinSpan then hi(c) else centre(c) + 0.5

  /** Combined range of channels `cs`. */
  def union(cs: Seq[Int]): (Double, Double) =
    (cs.map(min).min, cs.map(max).max)

  /** Forgets all ranges. */
  def reset(): Unit =
    java.util.Arrays.fill(lo, Double.NaN)
    java.util.Arrays.fill(hi, Double.NaN)
    begin()

  private def centre(c: Int): Double =
    val m = (lo(c) + hi(c)) / 2
    if m.isNaN then 0.0 else m

object ViewBounds:
  /** Ranges narrower than this are shown as a unit interval around their centre. */
  private val MinSpan = 1e-9
