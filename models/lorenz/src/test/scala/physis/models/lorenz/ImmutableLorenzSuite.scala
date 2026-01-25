package physis.models.lorenz

import physis.core.{Digest, Simulation, Stepping}
import physis.core.run.Physis

final case class V3(x: Double, y: Double, z: Double)

/**
 * Lorenz with RK4 written as a pure, immutable simulation. Every floating-point operation is in
 * the same order as LorenzSystem.derivative and physis.ode.Rk4, so both must agree bit for bit.
 */
object ImmutableLorenz extends Simulation[V3]:
  private val p = Lorenz.model.params.defaults

  val initial: V3 = V3(1.0, 1.0, 1.0)

  private def f(v: V3): V3 =
    V3(p.sigma * (v.y - v.x), v.x * (p.rho - v.z) - v.y, v.x * v.y - p.beta * v.z)

  def step(v: V3, h: Double): V3 =
    val half = 0.5 * h
    val k1 = f(v)
    val k2 = f(V3(v.x + half * k1.x, v.y + half * k1.y, v.z + half * k1.z))
    val k3 = f(V3(v.x + half * k2.x, v.y + half * k2.y, v.z + half * k2.z))
    val k4 = f(V3(v.x + h * k3.x, v.y + h * k3.y, v.z + h * k3.z))
    val sixth = h / 6.0
    V3(
      v.x + sixth * (k1.x + 2.0 * k2.x + 2.0 * k3.x + k4.x),
      v.y + sixth * (k1.y + 2.0 * k2.y + 2.0 * k3.y + k4.y),
      v.z + sixth * (k1.z + 2.0 * k2.z + 2.0 * k3.z + k4.z)
    )

class ImmutableLorenzSuite extends munit.FunSuite:
  private val immutableModel =
    Simulation.asModel(
      "lorenz-immutable",
      "1",
      ImmutableLorenz,
      v => Digest.ofDoubles(v.x, v.y, v.z)
    )

  test("the pure Simulation[S] Lorenz equals the OdeModel Lorenz bit for bit after 10^4 steps") {
    val pure = Physis.model(immutableModel).stepping(Stepping.Fixed(0.01)).build().runUntil(100.0)
    val mutable = Physis.model(Lorenz.model).stepping(Stepping.Fixed(0.01)).build().runUntil(100.0)
    assertEquals(pure.acceptedSteps, 10000L)
    assertEquals(mutable.acceptedSteps, 10000L)
    assertEquals(pure.finalDigest, mutable.finalDigest)
  }
