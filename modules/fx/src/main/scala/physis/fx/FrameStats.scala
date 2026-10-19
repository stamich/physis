package physis.fx

/**
 * Frame-time statistics over the last `window` frames: frames per second from the median frame
 * interval, and the 99th-percentile frame time.
 *
 * Fed with the pulse timestamps of the render loop. Allocates nothing after construction.
 */
final class FrameStats(val window: Int = 120):
  require(window >= 2, "window must hold at least two frames")

  private val intervals = new Array[Long](window)
  private val scratch = new Array[Long](window)
  private var next = 0
  private var filled = 0
  private var last = Long.MinValue
  private var total = 0L

  /** Records a frame that started at `nanos` (monotonic clock). */
  def record(nanos: Long): Unit =
    if last != Long.MinValue then
      intervals(next) = nanos - last
      next = (next + 1) % window
      if filled < window then filled += 1
    last = nanos
    total += 1

  /** Number of frames recorded so far. */
  def frames: Long = total

  /** Median frame time in milliseconds; NaN before the second frame. */
  def medianMillis: Double = percentile(0.5) / 1e6

  /** 99th-percentile frame time in milliseconds; NaN before the second frame. */
  def p99Millis: Double = percentile(0.99) / 1e6

  /** Frames per second from the median frame time; NaN before the second frame. */
  def fps: Double = 1000.0 / medianMillis

  /** Forgets all frames. */
  def reset(): Unit =
    next = 0
    filled = 0
    last = Long.MinValue
    total = 0L

  /** Nearest-rank percentile of the recorded intervals, in nanoseconds. */
  private def percentile(q: Double): Double =
    if filled == 0 then Double.NaN
    else
      System.arraycopy(intervals, 0, scratch, 0, filled)
      java.util.Arrays.sort(scratch, 0, filled)
      val rank = math.ceil(q * filled).toInt
      scratch(math.max(0, math.min(filled - 1, rank - 1))).toDouble
