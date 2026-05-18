package physis.models.shallow

import ShallowSupport.*

/**
 * Behaviour that comes from the rotation: geostrophic balance, the Kelvin wave along a coast and
 * the Rossby wave of the beta plane. Reference values: `tools/shallow_reference.py`.
 */
class ShallowRotationSuite extends munit.FunSuite:

  test(
    "geostrophic adjustment: a front settles to the Rossby radius c / f and to half its height"
  ) {
    val depth = 200.0
    val dx = 10_000.0
    val f0 = 1e-4
    val ny = 600
    val r = run(
      params(
        depth = depth,
        dx = dx,
        f0 = f0,
        edges = Shallow.Channel,
        start = 1,
        amp = 0.2,
        nx = 4,
        ny = ny
      )
    )
    val mean = new Array[Double](ny)
    val meanU = new Array[Double](ny)
    var n = 0
    for k <- 0 until 12000 do
      r.step()
      if k >= 6000 && k % 10 == 0 then
        for j <- 0 until ny do
          mean(j) += r.state.eta(0, j)
          meanU(j) += 0.5 * (r.state.u(0, j) + r.state.u(1, j))
        n += 1
    for j <- 0 until ny do
      mean(j) /= n
      meanU(j) /= n
    val j0 = ny / 2
    // the height at the front is half the step
    assertEqualsDouble(0.5 * (mean(j0 - 1) + mean(j0)), 0.1, 0.003)
    // away from the front the height falls as exp(-y / R) with R = c / f
    val radius = math.sqrt(9.81 * depth) / f0 / dx
    val rows = (j0 + 6) until (j0 + 60)
    val ys = rows.map(j => (j - j0) * dx)
    val logs = rows.map(j => math.log(mean(j)))
    val my = ys.sum / ys.size
    val ml = logs.sum / logs.size
    val slope =
      ys.zip(logs).map((y, l) => (y - my) * (l - ml)).sum / ys.map(y => (y - my) * (y - my)).sum
    val fitted = -1.0 / slope / dx
    // numpy reference: 44.6 cells against 44.3
    assertEqualsDouble(fitted, radius, 0.04 * radius, s"Rossby radius $fitted cells")
    // the flow is the geostrophic one: f u = g d(eta)/dy (y runs south), in the time mean
    for j <- Seq(j0 - 20, j0 - 8, j0 + 8, j0 + 20) do
      val geostrophic = 9.81 / f0 * (mean(j + 1) - mean(j - 1)) / (2.0 * dx)
      assertEqualsDouble(meanU(j), geostrophic, 0.08 * math.abs(geostrophic), s"u at row $j")
    // the water moves along the front, not across it
    assert(math.abs(meanU(j0)) > 0.015, s"jet ${meanU(j0)} m/s")
  }

  test(
    "a Kelvin wave runs along the coast at c, keeping the coast on its right (south along a west coast)"
  ) {
    val depth = 200.0
    val dx = 10_000.0
    val r = run(params(depth = depth, dx = dx, f0 = 1e-4, nx = 96, ny = 400))
    hump(r.state, 1.0, 200.0, 3.0, 0.1)
    val south = new Array[Double](1100)
    val north = new Array[Double](1100)
    for k <- 0 until 1100 do
      r.step()
      south(k) = r.state.eta(0, 350)
      north(k) = r.state.eta(0, 50)
    val arrival = south.indices.maxBy(south(_)) + 1
    val c = math.sqrt(9.81 * depth)
    val speed = 150 * dx / (arrival * Shallow.Dt)
    // numpy reference: 44.05 m/s against 44.29 m/s, and 0.23 northwards
    assertEqualsDouble(speed, c, 0.03 * c, s"Kelvin speed $speed m/s")
    val northBefore = north.take(arrival).max
    assert(northBefore < 0.4 * south.max, s"north $northBefore south ${south.max}")
    assert(south.max > 0.008, s"south peak ${south.max}")
  }

  test("a Kelvin wave runs the other way when the rotation is reversed") {
    val r = run(params(depth = 200.0, dx = 10_000.0, f0 = -1e-4, nx = 96, ny = 400))
    hump(r.state, 1.0, 200.0, 3.0, 0.1)
    var south = 0.0
    var north = 0.0
    for _ <- 1 to 676 do
      r.step()
      south = math.max(south, r.state.eta(0, 350))
      north = math.max(north, r.state.eta(0, 50))
    assert(north > 2.0 * south, s"north $north south $south")
  }

  test("a Rossby wave drifts west at the speed the dispersion relation gives") {
    val p = params(
      depth = 500.0,
      dx = 50_000.0,
      f0 = 1e-4,
      beta = 2e-11,
      edges = Shallow.Channel,
      start = 2,
      amp = 0.1,
      nx = 64,
      ny = 32
    )
    val r = run(p)
    val s = r.state
    val k = 2.0 * math.Pi / (64 * p.dx)
    val l = math.Pi / (32 * p.dx)
    val omega = -p.beta * k / (k * k + l * l + p.f0 * p.f0 / (p.gravity * p.depth))
    def phase(): Double =
      var a = 0.0
      var b = 0.0
      for j <- 0 until 32; i <- 0 until 64 do
        val x = (i + 0.5) * p.dx
        val y = (j + 0.5) * p.dx
        a += s.eta(i, j) * math.sin(k * x) * math.sin(l * y)
        b += s.eta(i, j) * math.cos(k * x) * math.sin(l * y)
      math.atan2(b, a)
    val p0 = phase()
    for (steps, tolerance) <- Seq(8000 -> 0.05, 12000 -> 0.05) do
      r.runSteps(steps - r.stepCount)
      val drift = phase() - p0
      val expected = -omega * steps * Shallow.Dt
      // numpy reference: 1.6175 against 1.6112, and 2.3976 against 2.4168
      assertEqualsDouble(drift, expected, tolerance * expected, s"phase after $steps steps")
    assert(omega < 0.0, "the phase moves toward negative x: west")
  }

  test("without the beta effect there is no Rossby wave: the balanced pattern stays where it is") {
    val p = params(
      depth = 500.0,
      dx = 50_000.0,
      f0 = 1e-4,
      beta = 0.0,
      edges = Shallow.Channel,
      start = 2,
      amp = 0.1,
      nx = 64,
      ny = 32
    )
    val r = run(p)
    val before = Array.tabulate(64)(i => r.state.eta(i, 16))
    r.runSteps(8000)
    val after = Array.tabulate(64)(i => r.state.eta(i, 16))
    val change = before.zip(after).map((a, b) => math.abs(a - b)).max
    assert(change < 0.01, s"the pattern changed by $change m")
  }
