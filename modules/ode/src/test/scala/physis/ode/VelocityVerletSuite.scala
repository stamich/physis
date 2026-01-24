package physis.ode

import physis.core.{Model, Probe, Stepping}
import physis.core.param.{ParamSchema, ParamSpec}
import physis.core.random.SplittableRng
import physis.core.run.Physis
import physis.verify.{Conservation, Convergence}

/** Kepler problem with GM = 1: a = -q / |q|^3. */
final case class KeplerParams(eccentricity: Double)

object KeplerParams:
  given schema: ParamSchema[KeplerParams] = ParamSchema.derived[KeplerParams](
    ParamSpec("eccentricity", default = 0.5, min = 0.0, max = 0.95)
  )

object Kepler extends SeparableSystem[KeplerParams]:
  val dof = 2

  def acceleration(q: Array[Double], p: KeplerParams, a: Array[Double]): Unit =
    val x = q(0)
    val y = q(1)
    val r2 = x * x + y * y
    val inv = 1.0 / (r2 * math.sqrt(r2))
    a(0) = -x * inv
    a(1) = -y * inv

  /** Perihelion of an orbit with semi-major axis 1: r = 1 - e, v = sqrt((1 + e) / (1 - e)). */
  def perihelion(p: KeplerParams, rng: SplittableRng): Array[Double] =
    val e = p.eccentricity
    Array(1.0 - e, 0.0, 0.0, math.sqrt((1.0 + e) / (1.0 - e)))

  val model: Model.Aux[KeplerParams, OdeState] =
    SeparableModel("kepler", "1", Kepler, KeplerParams.schema, perihelion)

  val energy: Probe[OdeState] = Probe[OdeState]("energy") { (s, _) =>
    val y = s.y
    0.5 * (y(2) * y(2) + y(3) * y(3)) - 1.0 / math.sqrt(y(0) * y(0) + y(1) * y(1))
  }

/** Harmonic oscillator as a separable system. */
object SpringSystem extends SeparableSystem[OscillatorParams]:
  val dof = 1

  def acceleration(q: Array[Double], p: OscillatorParams, a: Array[Double]): Unit =
    a(0) = -p.omega * p.omega * q(0)

  val model: Model.Aux[OscillatorParams, OdeState] =
    SeparableModel("spring", "1", SpringSystem, OscillatorParams.schema, (_, _) => Array(1.0, 0.0))

class VelocityVerletSuite extends munit.FunSuite:

  test("Kepler energy stays bounded over 10^6 steps (about 160 orbits)") {
    val result = Physis
      .model(Kepler.model)
      .withParams(KeplerParams(eccentricity = 0.5))
      .stepping(Stepping.Fixed(1e-3))
      .sampleEvery(1.0)
      .probe(Kepler.energy)
      .build()
      .runUntil(1000.0)
    val energy = result.column("energy")
    assertEqualsDouble(energy.head, -0.5, 1e-12)
    assert(Conservation.bounded(energy, windows = 10), "energy error grows secularly")
    assert(Conservation.drift(energy) < 1e-4, s"drift = ${Conservation.drift(energy)}")
  }

  test("velocity Verlet is second order") {
    val p = OscillatorParams(omega = 1.0)
    val x = OdeState.component("x", 0)
    def xAt(h: Double): Double =
      Physis
        .model(SpringSystem.model)
        .withParams(p)
        .stepping(Stepping.Fixed(h))
        .sampleEvery(1.0)
        .probe(x)
        .build()
        .runUntil(1.0)
        .last("x")
    val steps = Seq(0.1, 0.05, 0.025, 0.0125)
    val order = Convergence.observedOrder(steps, Convergence.errors(steps)(xAt)(math.cos(1.0)))
    assertEqualsDouble(order, 2.0, 0.2)
  }

  test("adaptive stepping is refused") {
    intercept[IllegalArgumentException](
      Physis.model(Kepler.model).stepping(Stepping.Adaptive(1e-8, 1e-8, 1e-3, 0.1)).build()
    )
  }
