package physis.ode

import physis.core.Model
import physis.core.param.{ParamSchema, ParamSpec}

/** Harmonic oscillator x'' = -omega^2 x in first-order form y = (x, v). */
final case class OscillatorParams(omega: Double)

object OscillatorParams:
  given schema: ParamSchema[OscillatorParams] = ParamSchema.derived[OscillatorParams](
    ParamSpec("omega", default = 1.0, min = 0.0, max = 100.0, unit = "rad/s")
  )

object Oscillator extends OdeSystem[OscillatorParams]:
  val dimension = 2

  def derivative(t: Double, y: Array[Double], p: OscillatorParams, dydt: Array[Double]): Unit =
    dydt(0) = y(1)
    dydt(1) = -p.omega * p.omega * y(0)

  /** Starts at x = 1, v = 0, so x(t) = cos(omega t). */
  val model: Model.Aux[OscillatorParams, OdeState] =
    OdeModel("oscillator", "1", Oscillator, OscillatorParams.schema, (_, _) => Array(1.0, 0.0))

  def exactX(p: OscillatorParams, t: Double): Double = math.cos(p.omega * t)

/** Exponential decay y' = -lambda y. */
final case class DecayRate(lambda: Double)

object DecayRate:
  given schema: ParamSchema[DecayRate] = ParamSchema.derived[DecayRate](
    ParamSpec("lambda", default = 1.0, min = 0.0, max = 1000.0, unit = "1/s")
  )

object ExpDecay extends OdeSystem[DecayRate]:
  val dimension = 1

  def derivative(t: Double, y: Array[Double], p: DecayRate, dydt: Array[Double]): Unit =
    dydt(0) = -p.lambda * y(0)
