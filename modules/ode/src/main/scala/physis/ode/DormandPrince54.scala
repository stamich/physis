package physis.ode

/**
 * Dormand-Prince 5(4) embedded Runge-Kutta step with error control.
 *
 * The fifth-order solution is propagated; the difference to the embedded fourth-order solution
 * estimates the local error. Step-size control follows Hairer, Norsett and Wanner, "Solving
 * Ordinary Differential Equations I", section II.4:
 *
 *   err = sqrt(mean_i (e_i / (atol + rtol * max(|y_i|, |ynew_i|)))^2)
 *
 * A step is accepted when err <= 1; the next step is scaled by
 * min(5, max(0.2, 0.9 * err^(-1/5))), and by at most 1 after a rejection.
 *
 * The last stage of an accepted step is the first stage of the next one (FSAL); it is reused
 * only when the next step starts exactly where the previous one ended.
 *
 * Owns its buffers; `step` allocates nothing. Not thread-safe.
 */
final class DormandPrince54[P](system: OdeSystem[P]):
  import DormandPrince54.*

  private val n = system.dimension
  private val k1 = new Array[Double](n)
  private val k2 = new Array[Double](n)
  private val k3 = new Array[Double](n)
  private val k4 = new Array[Double](n)
  private val k5 = new Array[Double](n)
  private val k6 = new Array[Double](n)
  private val k7 = new Array[Double](n)
  private val tmp = new Array[Double](n)
  private val yNew = new Array[Double](n)
  private val err = new Array[Double](n)

  private var fsalValid = false
  private var fsalT = 0.0
  private val fsalY = new Array[Double](n)

  /** Attempts rejected during the last call of [[step]]. */
  var lastRejected: Int = 0

  /** Step size proposed after the last accepted step. */
  var lastSuggested: Double = 0.0

  /**
   * Advances `y` in place from `t` by an accepted step of at most `h`.
   *
   * @return the step actually taken
   */
  def step(t: Double, y: Array[Double], p: P, h: Double, rtol: Double, atol: Double): Double =
    if fsalValid && t == fsalT && sameAsFsal(y) then System.arraycopy(k7, 0, k1, 0, n)
    else system.derivative(t, y, p, k1)

    var size = h
    var rejected = 0
    var error = attempt(t, y, p, size, rtol, atol)
    while !(error <= 1.0) do
      rejected += 1
      size *= math.min(1.0, math.max(MinFactor, Safety * math.pow(error, -0.2)))
      if size < MinRelativeStep * math.max(1.0, math.abs(t)) || size.isNaN then
        throw new IllegalStateException(
          s"Dormand-Prince step size underflow at t = $t (error norm $error)"
        )
      error = attempt(t, y, p, size, rtol, atol)

    System.arraycopy(yNew, 0, y, 0, n)
    fsalValid = true
    fsalT = t + size
    System.arraycopy(y, 0, fsalY, 0, n)

    val growth =
      if error == 0.0 then MaxFactor
      else math.min(MaxFactor, math.max(MinFactor, Safety * math.pow(error, -0.2)))
    lastRejected = rejected
    lastSuggested = size * growth
    size

  /** Computes stages 2..7, the fifth-order solution and the error norm for step size `h`. */
  private def attempt(
      t: Double,
      y: Array[Double],
      p: P,
      h: Double,
      rtol: Double,
      atol: Double
  ): Double =
    var i = 0
    while i < n do
      tmp(i) = y(i) + h * (A21 * k1(i))
      i += 1
    system.derivative(t + C2 * h, tmp, p, k2)

    i = 0
    while i < n do
      tmp(i) = y(i) + h * (A31 * k1(i) + A32 * k2(i))
      i += 1
    system.derivative(t + C3 * h, tmp, p, k3)

    i = 0
    while i < n do
      tmp(i) = y(i) + h * (A41 * k1(i) + A42 * k2(i) + A43 * k3(i))
      i += 1
    system.derivative(t + C4 * h, tmp, p, k4)

    i = 0
    while i < n do
      tmp(i) = y(i) + h * (A51 * k1(i) + A52 * k2(i) + A53 * k3(i) + A54 * k4(i))
      i += 1
    system.derivative(t + C5 * h, tmp, p, k5)

    i = 0
    while i < n do
      tmp(i) = y(i) + h * (A61 * k1(i) + A62 * k2(i) + A63 * k3(i) + A64 * k4(i) + A65 * k5(i))
      i += 1
    system.derivative(t + h, tmp, p, k6)

    i = 0
    while i < n do
      yNew(i) = y(i) + h * (B1 * k1(i) + B3 * k3(i) + B4 * k4(i) + B5 * k5(i) + B6 * k6(i))
      i += 1
    system.derivative(t + h, yNew, p, k7)

    var sum = 0.0
    i = 0
    while i < n do
      err(i) = h * (E1 * k1(i) + E3 * k3(i) + E4 * k4(i) + E5 * k5(i) + E6 * k6(i) + E7 * k7(i))
      val scale = atol + rtol * math.max(math.abs(y(i)), math.abs(yNew(i)))
      val r = err(i) / scale
      sum += r * r
      i += 1
    math.sqrt(sum / n)

  private def sameAsFsal(y: Array[Double]): Boolean =
    var i = 0
    var same = true
    while same && i < n do
      same = y(i) == fsalY(i)
      i += 1
    same

object DormandPrince54:
  private val Safety = 0.9
  private val MinFactor = 0.2
  private val MaxFactor = 5.0
  private val MinRelativeStep = 1e-14

  // Nodes.
  private val C2 = 1.0 / 5.0
  private val C3 = 3.0 / 10.0
  private val C4 = 4.0 / 5.0
  private val C5 = 8.0 / 9.0

  // Runge-Kutta matrix.
  private val A21 = 1.0 / 5.0
  private val A31 = 3.0 / 40.0
  private val A32 = 9.0 / 40.0
  private val A41 = 44.0 / 45.0
  private val A42 = -56.0 / 15.0
  private val A43 = 32.0 / 9.0
  private val A51 = 19372.0 / 6561.0
  private val A52 = -25360.0 / 2187.0
  private val A53 = 64448.0 / 6561.0
  private val A54 = -212.0 / 729.0
  private val A61 = 9017.0 / 3168.0
  private val A62 = -355.0 / 33.0
  private val A63 = 46732.0 / 5247.0
  private val A64 = 49.0 / 176.0
  private val A65 = -5103.0 / 18656.0

  // Fifth-order weights (also the last row of the matrix, hence FSAL).
  private val B1 = 35.0 / 384.0
  private val B3 = 500.0 / 1113.0
  private val B4 = 125.0 / 192.0
  private val B5 = -2187.0 / 6784.0
  private val B6 = 11.0 / 84.0

  // Fifth-order minus fourth-order weights: the local error estimate.
  private val E1 = 71.0 / 57600.0
  private val E3 = -71.0 / 16695.0
  private val E4 = 71.0 / 1920.0
  private val E5 = -17253.0 / 339200.0
  private val E6 = 22.0 / 525.0
  private val E7 = -1.0 / 40.0
