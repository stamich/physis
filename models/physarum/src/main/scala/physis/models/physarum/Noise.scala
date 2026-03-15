package physis.models.physarum

/**
 * Random numbers from a counter instead of a stream, and the sine and cosine of small angles,
 * both in plain arithmetic: a pure function of its arguments, the same on every platform, and
 * free of allocation (unlike `StrictMath`'s trigonometry).
 */
object Noise:
  /** `sin(a)` for `|a| <= 2` (error below 1e-15), from the Taylor series. */
  def sin(a: Double): Double =
    val a2 = a * a
    a * (1.0 - a2 / 6.0 * (1.0 - a2 / 20.0 * (1.0 - a2 / 42.0 * (1.0 - a2 / 72.0 *
      (1.0 - a2 / 110.0 * (1.0 - a2 / 156.0 * (1.0 - a2 / 210.0 * (1.0 - a2 / 272.0 *
        (1.0 - a2 / 342.0 * (1.0 - a2 / 420.0))))))))))

  /** `cos(a)` for `|a| <= 2` (error below 1e-15); see [[sin]]. */
  def cos(a: Double): Double =
    val a2 = a * a
    1.0 - a2 / 2.0 * (1.0 - a2 / 12.0 * (1.0 - a2 / 30.0 * (1.0 - a2 / 56.0 *
      (1.0 - a2 / 90.0 * (1.0 - a2 / 132.0 * (1.0 - a2 / 182.0 * (1.0 - a2 / 240.0 *
        (1.0 - a2 / 306.0 * (1.0 - a2 / 380.0)))))))))

  private def mix(z0: Long): Long =
    var z = z0 + 0x9e3779b97f4a7c15L
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)

  /** Uniform in [0, 1), a pure function of (key, step, index): integer arithmetic only. */
  def uniform(key: Long, step: Long, index: Int): Double =
    val bits = mix(key ^ mix(step * 0x632be59bd9b4e019L + index))
    (bits >>> 11).toDouble * (1.0 / 9007199254740992.0)
