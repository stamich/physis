package physis.view

/** How the values of a field map onto a colormap: which value gets the first and the last colour. */
enum ValueRange:
  /** A fixed interval; build it with [[ValueRange.fixed]], which checks that `lo < hi`. */
  case Fixed(lo: Double, hi: Double)

  /** The smallest and largest value of the current frame (NaN ignored). */
  case Auto

  /**
   * The interval for the `length` values of `values` starting at `offset`. An automatic range of
   * constant (or empty, or all-NaN) data is widened to an interval of length 1, so the data sits
   * at the start of the colormap instead of dividing by zero.
   */
  def resolve(values: Array[Double], offset: Int, length: Int): (Double, Double) = this match
    case Fixed(lo, hi) => (lo, hi)
    case Auto =>
      var lo = Double.PositiveInfinity
      var hi = Double.NegativeInfinity
      var i = offset
      val end = offset + length
      while i < end do
        val v = values(i)
        if v < lo then lo = v
        if v > hi then hi = v
        i += 1
      if lo < hi then (lo, hi)
      else if lo == hi then (lo, lo + 1.0)
      else (0.0, 1.0)

object ValueRange:
  /** The interval from `lo` to `hi`; fails unless `lo < hi`. */
  def fixed(lo: Double, hi: Double): ValueRange =
    require(lo < hi, s"empty value range: [$lo, $hi]")
    Fixed(lo, hi)
