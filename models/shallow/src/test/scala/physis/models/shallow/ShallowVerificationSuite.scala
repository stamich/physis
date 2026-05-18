package physis.models.shallow

import physis.core.Stepping
import physis.verify.Convergence
import ShallowSupport.*

/**
 * Checks of the scheme against what the equations promise. The reference values come from
 * `tools/shallow_reference.py`, a separate numpy implementation of the same scheme.
 */
class ShallowVerificationSuite extends munit.FunSuite:

  /** The period of the first seiche of a closed basin 2 000 km long, from the zero crossings. */
  private def seichePeriod(nx: Int, dt: Double): Double =
    val length = 2.0e6
    val depth = 4000.0
    val period = 2.0 * length / math.sqrt(9.81 * depth)
    val r = run(params(depth = depth, dx = length / nx, nx = nx, ny = 4), Stepping.Fixed(dt))
    val mode = Array.tabulate(nx)(i => math.cos(math.Pi * (i + 0.5) / nx))
    for j <- 0 until 4; i <- 0 until nx do r.state.eta(i, j) = mode(i)
    def amplitude(): Double = (0 until nx).map(i => r.state.eta(i, 1) * mode(i)).sum
    val crossings = scala.collection.mutable.ArrayBuffer.empty[Double]
    var before = amplitude()
    val steps = math.round(4.2 * period / dt).toInt
    for k <- 1 to steps do
      r.step()
      val now = amplitude()
      if before * now < 0.0 then crossings += (k - 1 + before / (before - now)) * dt
      before = now
    assert(crossings.size >= 6, s"only ${crossings.size} crossings")
    2.0 * (crossings.last - crossings.head) / (crossings.size - 1)

  private val exactPeriod = 2.0 * 2.0e6 / math.sqrt(9.81 * 4000.0)

  test("the first seiche of a closed basin has the period 2 L / c") {
    val measured = seichePeriod(100, 50.0)
    // numpy reference: 20 193.4 s against 20 192.8 s
    assertEqualsDouble(measured, exactPeriod, 0.001 * exactPeriod, s"period $measured s")
  }

  test("the period converges at second order when dx and dt are halved together") {
    val dxs = Seq(80_000.0, 40_000.0, 20_000.0, 10_000.0)
    val periods = Seq(25 -> 200.0, 50 -> 100.0, 100 -> 50.0, 200 -> 25.0).map(seichePeriod)
    val errors = periods.map(p => math.abs(p - exactPeriod) / exactPeriod)
    val order = Convergence.observedOrder(dxs, errors)
    assert(Convergence.hasOrder(dxs, errors, 2.0, 0.15), s"observed order $order, errors $errors")
  }

  test("a gravity wave travels at the square root of g times the depth") {
    val dx = 20_000.0
    val r = run(params(depth = 4000.0, dx = dx, nx = 400, ny = 4))
    ridge(r.state, 100.0, 6.0, 1.0)
    r.runSteps(300)
    val crest = (150 until 399).maxBy(i => r.state.eta(i, 1))
    val speed = (crest - 100) * dx / (300 * Shallow.Dt)
    val c = Shallow.waveSpeed(r.params)
    // numpy reference: 196.0 m/s against 198.1 m/s; the crest sits on a cell, so +-0.7%
    assertEqualsDouble(speed, c, 0.02 * c, s"crest speed $speed m/s")
    // the hump splits in two, each half as high
    assertEqualsDouble(r.state.eta(crest, 1), 0.5, 0.02)
  }

  test("a tsunami crosses the ocean at 713 km/h: the crest needs 4.2 hours for 3 000 km") {
    val dx = 20_000.0
    val r = run(params(depth = 4000.0, dx = dx, nx = 400, ny = 4))
    ridge(r.state, 30.0, 5.0, 1.0)
    var arrival = 0
    var highest = 0.0
    for step <- 1 to 400 do
      r.step()
      val e = r.state.eta(180, 1)
      if e > highest then
        highest = e
        arrival = step
    val seconds = arrival * Shallow.Dt
    val expected = (180 - 30) * dx / math.sqrt(9.81 * 4000.0)
    assertEqualsDouble(seconds, expected, 0.03 * expected, s"the crest arrived after $seconds s")
    assertEqualsDouble(highest, 0.5, 0.03)
  }

  test("the volume is conserved to rounding over thousands of steps, with everything switched on") {
    val r = run(
      params(
        nonlinear = 1,
        f0 = 1e-4,
        beta = 2e-11,
        seabed = 2,
        edges = Shallow.Basin,
        nx = 60,
        ny = 50,
        depth = 1000.0
      )
    )
    for j <- 20 until 24; i <- 40 until 45 do r.state.land(i, j) = true
    r.state.landChanged()
    hump(r.state, 20.0, 25.0, 4.0, 3.0)
    hump(r.state, 50.0, 10.0, 3.0, -2.0)
    r.state.clearLand()
    val before = volume(r.state)
    r.runSteps(3000)
    assertEqualsDouble(volume(r.state), before, 1e-11 * math.abs(before) + 1e-3)
  }

  test("on a shelf a wave grows as the depth to the power -1/4 (Green's law)") {
    val nx = 400
    val dx = 20_000.0
    val r = run(params(depth = 4000.0, dx = dx, nx = nx, ny = 4))
    // depth: 4000 m to x = 120, then rising linearly to 1000 m at x = 320
    for j <- 0 until 4; i <- 0 until nx do
      val x = i + 0.5
      val d = if x < 120.0 then 4000.0
      else if x < 320.0 then 4000.0 - (x - 120.0) / 200.0 * 3000.0 else 1000.0
      r.state.shape(i, j) = d / 4000.0
    ridge(r.state, 60.0, 6.0, 1.0)
    var deep = 0.0
    var shallow = 0.0
    for _ <- 1 to 2200 do
      r.step()
      deep = math.max(deep, r.state.eta(100, 1))
      shallow = math.max(shallow, r.state.eta(300, 1))
    val green = math.pow(4000.0 / 1292.5, 0.25)
    // numpy reference: 1.311 against 1.326
    assertEqualsDouble(shallow / deep, green, 0.03 * green, s"ratio ${shallow / deep}")
  }

  test(
    "an open sea lets a wave go: a hundredth of the energy is left, a closed basin keeps it all"
  ) {
    def left(edges: Int): Double =
      val r = run(params(edges = edges, nx = 80, ny = 80))
      hump(r.state, 40.0, 40.0, 3.0, 1.0)
      val e0 = Shallow.energy.measure(r.state, 0.0)
      r.runSteps(400)
      Shallow.energy.measure(r.state, 0.0) / e0
    // numpy reference: 8e-4 and 0.99
    assert(left(Shallow.OpenSea) < 0.01)
    assert(left(Shallow.Basin) > 0.9)
  }
