package physis.verify

/** Checks on quantities that the physics conserves (energy, mass, momentum). */
object Conservation:

  /**
   * Largest relative deviation from the first value: max |x_i - x_0| / |x_0|.
   * Falls back to the absolute deviation when x_0 is zero.
   */
  def drift(series: Seq[Double]): Double =
    require(series.nonEmpty, "series must not be empty")
    val x0 = series.head
    val scale = if x0 == 0.0 then 1.0 else math.abs(x0)
    series.map(x => math.abs(x - x0)).max / scale

  /**
   * True if the deviation from the first value stays bounded instead of growing secularly.
   *
   * Splits the series into `windows` equal parts and compares the largest deviation in the last
   * window with that in the first: a symplectic integrator oscillates (ratio near 1), a drifting
   * one grows roughly linearly (ratio near `windows`).
   *
   * @param factor largest accepted ratio between the last and the first window
   */
  def bounded(series: Seq[Double], windows: Int = 10, factor: Double = 2.0): Boolean =
    require(windows >= 2, "need at least two windows")
    require(series.size >= 2 * windows, s"need at least ${2 * windows} samples")
    val x0 = series.head
    val size = series.size / windows
    def maxDeviation(window: Int): Double =
      series.slice(window * size, (window + 1) * size).map(x => math.abs(x - x0)).max
    val first = maxDeviation(0)
    val last = maxDeviation(windows - 1)
    if first == 0.0 then last == 0.0 else last <= factor * first

  /**
   * True if the series never increases by more than `slack` between neighbours: a quantity that
   * must only decay (the total of a field with Dirichlet boundaries under diffusion).
   */
  def nonIncreasing(series: Seq[Double], slack: Double = 0.0): Boolean =
    series.sliding(2).forall {
      case Seq(a, b) => b <= a + slack
      case _ => true
    }
