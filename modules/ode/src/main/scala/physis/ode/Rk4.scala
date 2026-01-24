package physis.ode

/**
 * Classic fourth-order Runge-Kutta step for an [[OdeSystem]].
 *
 * Owns its stage buffers; `step` allocates nothing. Not thread-safe.
 */
final class Rk4[P](system: OdeSystem[P]):
  private val n = system.dimension
  private val k1 = new Array[Double](n)
  private val k2 = new Array[Double](n)
  private val k3 = new Array[Double](n)
  private val k4 = new Array[Double](n)
  private val tmp = new Array[Double](n)

  /** Advances `y` in place from `t` to `t + h`. */
  def step(t: Double, y: Array[Double], p: P, h: Double): Unit =
    val half = 0.5 * h
    system.derivative(t, y, p, k1)
    var i = 0
    while i < n do
      tmp(i) = y(i) + half * k1(i)
      i += 1
    system.derivative(t + half, tmp, p, k2)
    i = 0
    while i < n do
      tmp(i) = y(i) + half * k2(i)
      i += 1
    system.derivative(t + half, tmp, p, k3)
    i = 0
    while i < n do
      tmp(i) = y(i) + h * k3(i)
      i += 1
    system.derivative(t + h, tmp, p, k4)
    val sixth = h / 6.0
    i = 0
    while i < n do
      y(i) = y(i) + sixth * (k1(i) + 2.0 * k2(i) + 2.0 * k3(i) + k4(i))
      i += 1
