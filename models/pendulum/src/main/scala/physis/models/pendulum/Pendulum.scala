package physis.models.pendulum

import physis.core.{Model, Probe}
import physis.core.param.{ParamSchema, ParamSpec}
import physis.core.random.SplittableRng
import physis.core.units.*
import physis.ode.{OdeModel, OdeState, OdeSystem}

/**
 * Parameters of the double pendulum: point masses on massless rigid rods.
 *
 * Initial angles are given in degrees (measured from the downward vertical), initial angular
 * velocities in radians per second.
 */
final case class PendulumParams(
    m1: Double,
    m2: Double,
    l1: Double,
    l2: Double,
    g: Double,
    theta1: Double,
    theta2: Double,
    omega1: Double,
    omega2: Double
)

object PendulumParams:
  given schema: ParamSchema[PendulumParams] = ParamSchema.derived[PendulumParams](
    ParamSpec("m1", default = 1.0, min = 1e-6, max = 1e6, unit = "kg", doc = "Inner mass"),
    ParamSpec("m2", default = 1.0, min = 1e-6, max = 1e6, unit = "kg", doc = "Outer mass"),
    ParamSpec("l1", default = 1.0, min = 1e-6, max = 1e6, unit = "m", doc = "Inner rod length"),
    ParamSpec("l2", default = 1.0, min = 1e-6, max = 1e6, unit = "m", doc = "Outer rod length"),
    ParamSpec("g", default = 9.81, min = 0.0, max = 1e3, unit = "m/s^2", doc = "Gravity"),
    ParamSpec(
      "theta1",
      default = 120.0,
      min = -360.0,
      max = 360.0,
      unit = "deg",
      doc = "Inner angle"
    ),
    ParamSpec(
      "theta2",
      default = -10.0,
      min = -360.0,
      max = 360.0,
      unit = "deg",
      doc = "Outer angle"
    ),
    ParamSpec(
      "omega1",
      default = 0.0,
      min = -1e3,
      max = 1e3,
      unit = "rad/s",
      doc = "Inner velocity"
    ),
    ParamSpec(
      "omega2",
      default = 0.0,
      min = -1e3,
      max = 1e3,
      unit = "rad/s",
      doc = "Outer velocity"
    )
  )

  /** Chaotic default: 120 deg / -10 deg at rest. */
  val chaotic: PendulumParams = schema.defaults

  /** Small oscillations: 10 deg / 10 deg at rest. */
  val nearLinear: PendulumParams = chaotic.copy(theta1 = 10.0, theta2 = 10.0)

/**
 * Double pendulum equations of motion, state y = (theta1, theta2, omega1, omega2) in radians.
 *
 * With delta = theta1 - theta2 and D = 2 m1 + m2 - m2 cos(2 delta):
 *
 * {{{
 * omega1' = (-g (2 m1 + m2) sin theta1 - m2 g sin(theta1 - 2 theta2)
 *            - 2 sin delta m2 (omega2^2 l2 + omega1^2 l1 cos delta)) / (l1 D)
 * omega2' = 2 sin delta (omega1^2 l1 (m1 + m2) + g (m1 + m2) cos theta1
 *            + omega2^2 l2 m2 cos delta) / (l2 D)
 * }}}
 *
 * The accelerations depend on the angular velocities, so the system is not separable and
 * velocity Verlet does not apply; energy is monitored as an accuracy check instead.
 */
object PendulumSystem extends OdeSystem[PendulumParams]:
  val dimension = 4

  def derivative(t: Double, y: Array[Double], p: PendulumParams, dydt: Array[Double]): Unit =
    val theta1 = y(0)
    val theta2 = y(1)
    val omega1 = y(2)
    val omega2 = y(3)
    val delta = theta1 - theta2
    val sinDelta = math.sin(delta)
    val cosDelta = math.cos(delta)
    val m1 = p.m1
    val m2 = p.m2
    val d = 2.0 * m1 + m2 - m2 * math.cos(2.0 * delta)
    val numerator1 =
      -p.g * (2.0 * m1 + m2) * math.sin(theta1) -
        m2 * p.g * math.sin(theta1 - 2.0 * theta2) -
        2.0 * sinDelta * m2 * (omega2 * omega2 * p.l2 + omega1 * omega1 * p.l1 * cosDelta)
    val numerator2 =
      2.0 * sinDelta * (
        omega1 * omega1 * p.l1 * (m1 + m2) +
          p.g * (m1 + m2) * math.cos(theta1) +
          omega2 * omega2 * p.l2 * m2 * cosDelta
      )
    dydt(0) = omega1
    dydt(1) = omega2
    dydt(2) = numerator1 / (p.l1 * d)
    dydt(3) = numerator2 / (p.l2 * d)

/** The double pendulum as a Physis model, with energy and position probes. */
object Pendulum:
  val Id = "double-pendulum"
  val Version = "0.1.0"

  /** Initial state from parameters; angles converted from degrees. Deterministic. */
  def initial(p: PendulumParams, rng: SplittableRng): Array[Double] =
    Array(p.theta1.deg.toDouble, p.theta2.deg.toDouble, p.omega1, p.omega2)

  val model: Model.Aux[PendulumParams, OdeState] =
    OdeModel(Id, Version, PendulumSystem, PendulumParams.schema, initial)

  /**
   * Total mechanical energy:
   * {{{
   * E = 1/2 (m1 + m2) l1^2 omega1^2 + 1/2 m2 l2^2 omega2^2 + m2 l1 l2 omega1 omega2 cos(delta)
   *     - (m1 + m2) g l1 cos(theta1) - m2 g l2 cos(theta2)
   * }}}
   */
  def energyOf(p: PendulumParams, y: Array[Double]): Double =
    val theta1 = y(0)
    val theta2 = y(1)
    val omega1 = y(2)
    val omega2 = y(3)
    val m = p.m1 + p.m2
    val kinetic =
      0.5 * m * p.l1 * p.l1 * omega1 * omega1 +
        0.5 * p.m2 * p.l2 * p.l2 * omega2 * omega2 +
        p.m2 * p.l1 * p.l2 * omega1 * omega2 * math.cos(theta1 - theta2)
    val potential = -m * p.g * p.l1 * math.cos(theta1) - p.m2 * p.g * p.l2 * math.cos(theta2)
    kinetic + potential

  /** Total energy, in joules. */
  def energy(p: PendulumParams): Probe[OdeState] =
    Probe[OdeState]("energy", "J")((s, _) => energyOf(p, s.y))

  /** Energy error relative to the initial energy (absolute when the initial energy is zero). */
  def energyDrift(p: PendulumParams): Probe[OdeState] =
    val e0 = energyOf(p, initial(p, SplittableRng(0L)))
    val scale = if e0 == 0.0 then 1.0 else math.abs(e0)
    Probe[OdeState]("energyDrift")((s, _) => (energyOf(p, s.y) - e0) / scale)

  /** Horizontal position of the outer bob. */
  def x2(p: PendulumParams): Probe[OdeState] =
    Probe[OdeState]("x2", "m")((s, _) => p.l1 * math.sin(s.y(0)) + p.l2 * math.sin(s.y(1)))

  /** Vertical position of the outer bob (pivot at 0, positive up). */
  def y2(p: PendulumParams): Probe[OdeState] =
    Probe[OdeState]("y2", "m")((s, _) => -p.l1 * math.cos(s.y(0)) - p.l2 * math.cos(s.y(1)))

  /** Default probes for given parameters. */
  def probes(p: PendulumParams): Vector[Probe[OdeState]] =
    Vector(energy(p), energyDrift(p), x2(p), y2(p))
