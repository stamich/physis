package physis.models.lorenz

import physis.core.{Model, Probe}
import physis.core.param.{ParamSchema, ParamSpec, Scale}
import physis.core.random.SplittableRng
import physis.ode.{OdeModel, OdeState, OdeSystem}

/**
 * Parameters of the Lorenz system.
 *
 * @param sigma   Prandtl number
 * @param rho     Rayleigh number (normalised)
 * @param beta    geometric factor
 * @param epsilon standard deviation of a seeded Gaussian perturbation of the initial state
 */
final case class LorenzParams(sigma: Double, rho: Double, beta: Double, epsilon: Double)

object LorenzParams:
  given schema: ParamSchema[LorenzParams] = ParamSchema.derived[LorenzParams](
    ParamSpec("sigma", default = 10.0, min = 0.0, max = 100.0, doc = "Prandtl number"),
    ParamSpec("rho", default = 28.0, min = 0.0, max = 500.0, doc = "Rayleigh number"),
    ParamSpec("beta", default = 8.0 / 3.0, min = 0.0, max = 20.0, doc = "Geometric factor"),
    ParamSpec(
      "epsilon",
      default = 0.0,
      min = 0.0,
      max = 1.0,
      scale = Scale.Log,
      doc = "Seeded perturbation of the initial state"
    )
  )

/**
 * The Lorenz system (Lorenz 1963), state y = (x, y, z):
 *
 * {{{
 * dx/dt = sigma (y - x)
 * dy/dt = x (rho - z) - y
 * dz/dt = x y - beta z
 * }}}
 */
object LorenzSystem extends OdeSystem[LorenzParams]:
  val dimension = 3

  def derivative(t: Double, y: Array[Double], p: LorenzParams, dydt: Array[Double]): Unit =
    val x = y(0)
    val yy = y(1)
    val z = y(2)
    dydt(0) = p.sigma * (yy - x)
    dydt(1) = x * (p.rho - z) - yy
    dydt(2) = x * yy - p.beta * z

/** The Lorenz attractor as a Physis model: RK4 for fixed steps, Dormand-Prince for adaptive. */
object Lorenz:
  val Id = "lorenz"
  val Version = "0.1.0"

  /** (1, 1, 1), plus a seeded Gaussian perturbation of size `epsilon` when it is positive. */
  def initial(p: LorenzParams, rng: SplittableRng): Array[Double] =
    if p.epsilon == 0.0 then Array(1.0, 1.0, 1.0)
    else
      Array(
        1.0 + p.epsilon * rng.nextGaussian(),
        1.0 + p.epsilon * rng.nextGaussian(),
        1.0 + p.epsilon * rng.nextGaussian()
      )

  val model: Model.Aux[LorenzParams, OdeState] =
    OdeModel(Id, Version, LorenzSystem, LorenzParams.schema, initial)

  val x: Probe[OdeState] = OdeState.component("x", 0)
  val y: Probe[OdeState] = OdeState.component("y", 1)
  val z: Probe[OdeState] = OdeState.component("z", 2)

  /** Default probes: the three coordinates. */
  val probes: Vector[Probe[OdeState]] = Vector(x, y, z)
