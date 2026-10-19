package physis.models.wave

import WaveSupport.*

class WaveVerificationSuite extends munit.FunSuite:
  private val base = WaveParams.schema.defaults.copy(nx = 64, ny = 64, edge = Edge.Periodic)

  test("a standing mode keeps the frequency of the discrete dispersion relation") {
    val kx = 2 * math.Pi * 3 / 64
    val ky = 2 * math.Pi * 2 / 64
    val omega = discreteOmega(1.0, kx, ky)
    val r = run(base)
    standingMode(r, kx, ky, omega)
    val steps = 400
    r.runSteps(steps)
    var worst = 0.0
    for j <- 0 until 64; i <- 0 until 64 do
      val exact = math.cos(omega * steps * Wave.Dt) * math.cos(kx * (i + 0.5)) *
        math.cos(ky * (j + 0.5))
      worst = math.max(worst, math.abs(r.state.u(i, j) - exact))
    assert(worst < 1e-9, s"largest difference $worst")
    val continuum = math.sqrt(kx * kx + ky * ky)
    assert(math.abs(omega / continuum - 1.0) < 0.01, s"$omega vs $continuum")
    assert(omega != continuum)
  }

  test("the error against the continuum solution falls with order 2 under refinement") {
    def error(n: Int): Double =
      val k = 2 * math.Pi / n
      val r = run(base.copy(nx = n, ny = 64))
      val s = r.state
      for j <- 0 until 64; i <- 0 until n do
        val shape = math.cos(k * (i + 0.5))
        s.u(i, j) = shape
        s.previous(i, j) = shape * math.cos(k * Wave.Dt)
      val steps = n / 4
      r.runSteps(steps)
      var worst = 0.0
      for j <- 0 until 64; i <- 0 until n do
        val exact = math.cos(k * steps * Wave.Dt) * math.cos(k * (i + 0.5))
        worst = math.max(worst, math.abs(s.u(i, j) - exact))
      worst
    val errors = Seq(64, 128, 256).map(error)
    val orders = errors.zip(errors.tail).map((a, b) => math.log(a / b) / math.log(2.0))
    orders.foreach(o => assertEqualsDouble(o, 2.0, 0.1, s"errors $errors"))
  }

  test("energy is conserved without damping on periodic, reflective and free grids") {
    for edge <- Seq(Edge.Periodic, Edge.Reflective, Edge.Free) do
      val r = run(base.copy(edge = edge))
      val _ = r.intervene(
        physis.core.run.Intervention.Pulse("u", 20.0, 30.0, 4.0, 1.0)
      )
      r.step()
      val e0 = Wave.energy.measure(r.state, 0.0)
      assert(e0 > 0.0)
      r.runSteps(10000)
      val drift = math.abs(Wave.energy.measure(r.state, 0.0) / e0 - 1.0)
      assert(drift < 1e-9, s"edge $edge: drift $drift")
  }

  test("uniform damping makes the amplitude decay as exp(-gamma t / 2)") {
    val gamma = 0.1
    val kx = 2 * math.Pi * 2 / 64
    val ky = 2 * math.Pi * 1 / 64
    val r = run(base.copy(damping = gamma))
    standingMode(r, kx, ky, discreteOmega(1.0, kx, ky))
    def envelope(): Double =
      // u_n^2 - u_(n-1) u_(n+1) = A^2 r^(2n) sin^2(theta) for a damped sinusoid
      val m0 = projection(r.state, kx, ky)
      r.step()
      val m1 = projection(r.state, kx, ky)
      r.step()
      val m2 = projection(r.state, kx, ky)
      math.sqrt(m1 * m1 - m0 * m2)
    r.runSteps(2)
    val a = envelope()
    r.runSteps(100)
    val b = envelope()
    val elapsed = 102 * Wave.Dt
    assertEqualsDouble(b / a, math.exp(-gamma * elapsed / 2), 0.01 * (b / a))
  }
