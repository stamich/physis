package physis.fx

import java.util.Locale

/** "Nice" axis ticks: steps of 1, 2 or 5 times a power of ten. */
object Ticks:
  /** Tick step giving about `target` ticks over [min, max]. */
  def step(min: Double, max: Double, target: Int = 5): Double =
    val span = max - min
    if !(span > 0) || span.isInfinite then 1.0
    else
      val raw = span / math.max(1, target)
      val magnitude = math.pow(10, math.floor(math.log10(raw)))
      val f = raw / magnitude
      val nice = if f < 1.5 then 1.0 else if f < 3.5 then 2.0 else if f < 7.5 then 5.0 else 10.0
      nice * magnitude

  /** Tick positions within [min, max]. */
  def positions(min: Double, max: Double, target: Int = 5): Vector[Double] =
    val s = step(min, max, target)
    val first = math.ceil(min / s - 1e-9) * s
    Vector.iterate(first, math.max(0, math.floor((max - first) / s + 1e-9).toInt + 1))(_ + s)
      .map(v => if math.abs(v) < s * 1e-9 then 0.0 else v)

  /** Label of a tick with as many decimals as the step needs. */
  def label(value: Double, step: Double): String =
    val decimals = math.max(0, math.min(10, -math.floor(math.log10(step) + 1e-9).toInt))
    s"%.${decimals}f".formatLocal(Locale.ROOT, value)
