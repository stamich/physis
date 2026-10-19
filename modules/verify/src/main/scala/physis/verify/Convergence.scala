package physis.verify

/** Checks that a scheme's error shrinks at the rate its order promises. */
object Convergence:

  /**
   * Observed order of convergence: the least-squares slope of log(error) against log(step).
   *
   * @param steps  step sizes, at least two, all positive
   * @param errors errors at those step sizes, all positive
   */
  def observedOrder(steps: Seq[Double], errors: Seq[Double]): Double =
    require(steps.size == errors.size, "steps and errors must have the same length")
    require(steps.size >= 2, "need at least two step sizes")
    require(steps.forall(_ > 0.0), "step sizes must be positive")
    require(errors.forall(_ > 0.0), s"errors must be positive: ${errors.mkString(", ")}")
    val xs = steps.map(math.log)
    val ys = errors.map(math.log)
    val mx = xs.sum / xs.size
    val my = ys.sum / ys.size
    val sxy = xs.zip(ys).map((x, y) => (x - mx) * (y - my)).sum
    val sxx = xs.map(x => (x - mx) * (x - mx)).sum
    sxy / sxx

  /** Errors of `solve` against `exact` for each step size. */
  def errors(steps: Seq[Double])(solve: Double => Double)(exact: Double): Seq[Double] =
    steps.map(h => math.abs(solve(h) - exact))

  /** True if `error` is at most `factor` times the tolerance requested from an adaptive solver. */
  def withinTolerance(error: Double, tolerance: Double, factor: Double = 10.0): Boolean =
    error <= factor * tolerance

  /**
   * True if the observed order lies within `tolerance` of `expected`, for example 2.0 +- 0.1 for
   * a second-order stencil. A scheme of the wrong order (a first-order one passed off as second
   * order) fails.
   */
  def hasOrder(
      steps: Seq[Double],
      errors: Seq[Double],
      expected: Double,
      tolerance: Double = 0.1
  ): Boolean =
    math.abs(observedOrder(steps, errors) - expected) <= tolerance
