package physis.ode

import physis.core.Probe

/**
 * A first-order ODE system dy/dt = f(t, y; p).
 *
 * Higher-order equations are written in first-order form (positions and velocities in `y`).
 *
 * @tparam P parameter type
 */
trait OdeSystem[P]:
  /** Length of the state vector. */
  def dimension: Int

  /**
   * Writes f(t, y; p) into `dydt`. Called several times per step, so it must not allocate and
   * must not modify `y`.
   */
  def derivative(t: Double, y: Array[Double], p: P, dydt: Array[Double]): Unit

/** State of an ODE model: the state vector, mutated in place by the integrators. */
final class OdeState(val y: Array[Double]):
  def dimension: Int = y.length
  def apply(i: Int): Double = y(i)
  override def toString: String = y.mkString("OdeState(", ", ", ")")

object OdeState:
  /** Probe reading component `index` of the state vector. */
  def component(name: String, index: Int, unit: String = "1"): Probe[OdeState] =
    Probe[OdeState](name, unit)((s, _) => s.y(index))
