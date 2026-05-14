package physis.models.shallow

import ShallowSupport.*

class ShallowPlaneSuite extends munit.FunSuite:

  test("the Coriolis parameter is f0 in the middle and grows by beta dx per row toward the north") {
    val p = params(f0 = 1e-4, beta = 2e-11, dx = 50_000.0, ny = 40)
    assertEqualsDouble(Shallow.coriolis(p, 20.0), 1e-4, 1e-18)
    assertEqualsDouble(Shallow.coriolis(p, 17.0) - 1e-4, 3.0 * 2e-11 * 50_000.0, 1e-18)
    assertEqualsDouble(Shallow.coriolis(p, 30.0) - 1e-4, -10.0 * 2e-11 * 50_000.0, 1e-18)
    assertEqualsDouble(Shallow.maxCoriolis(p), 1e-4 + 20.0 * 2e-11 * 50_000.0, 1e-15)
  }

  test("a beta so large that f dt is too big is refused") {
    val p = params(f0 = 1e-4, beta = 1e-9, dx = 100_000.0, ny = 512, depth = 100.0)
    val refused = Shallow.check(p, Shallow.stepping)
    assert(refused.left.exists(_.contains("f dt")), refused.toString)
    assertEquals(Shallow.check(params(f0 = 1e-4, beta = 2e-11), Shallow.stepping), Right(()))
  }

  test(
    "friction slows a uniform current by the factor (1 - r dt) per step, and nothing else does"
  ) {
    val r = 2e-5
    val run0 = run(params(edges = Shallow.Channel, friction = r, nx = 20, ny = 10))
    val s = run0.state
    for j <- 0 until 10; i <- 0 to 20 do s.u(i, j) = 0.3
    run0.runSteps(200)
    val expected = 0.3 * math.pow(1.0 - r * Shallow.Dt, 200.0)
    for j <- 0 until 10; i <- 0 to 20 do assertEqualsDouble(s.u(i, j), expected, 1e-12)
    assertEquals(s.eta.max, 0.0)
  }

  test("a channel wraps left and right: a hump on the seam spreads to both sides alike") {
    val r = run(params(edges = Shallow.Channel, nx = 40, ny = 30))
    hump(r.state, 0.0, 15.0, 2.5, 1.0)
    r.runSteps(60)
    val s = r.state
    for i <- 0 until 20 do assertEqualsDouble(s.eta(i, 15), s.eta(39 - i, 15), 1e-9, s"column $i")
    assert(s.eta(39, 15) > 0.0 && s.eta(0, 15) > 0.0)
    assertEquals(s.u(40, 12), s.u(0, 12))
  }

  test("a channel has walls above and below: nothing flows through them, and the volume stays") {
    val r = run(params(edges = Shallow.Channel, f0 = 1e-4, nx = 40, ny = 30))
    hump(r.state, 10.0, 15.0, 3.0, 1.0)
    val before = volume(r.state)
    r.runSteps(300)
    val s = r.state
    for i <- 0 until 40 do
      assertEquals(s.v(i, 0), 0.0)
      assertEquals(s.v(i, 30), 0.0)
    assertEqualsDouble(volume(s), before, 1e-11 * before)
  }

  test("a closed basin keeps the waves, an open sea lets them go") {
    def left(edges: Int): Double =
      val r = run(params(edges = edges, nx = 80, ny = 80))
      hump(r.state, 40.0, 40.0, 3.0, 1.0)
      r.runSteps(400)
      math.max(r.state.eta.max, -r.state.eta.min)
    assert(left(Shallow.Basin) > 0.05, "the basin still sloshes")
    assert(left(Shallow.OpenSea) < 0.01, "the open sea has absorbed the waves")
  }

  test("outside an open sea the sponge width does nothing") {
    val a = run(params(edges = Shallow.Basin, sponge = 0, nx = 30, ny = 30))
    val b = run(params(edges = Shallow.Basin, sponge = 20, nx = 30, ny = 30))
    hump(a.state, 15.0, 15.0, 3.0, 1.0)
    hump(b.state, 15.0, 15.0, 3.0, 1.0)
    a.runSteps(100)
    b.runSteps(100)
    assertEquals(Shallow.digest(a.state), Shallow.digest(b.state))
  }

  test("a Rossby wave starts in geostrophic balance") {
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
    val s = run(p).state
    assertEqualsDouble(s.eta.max, 0.1, 2e-3)
    assertEqualsDouble(s.eta.min, -0.1, 2e-3)
    for (i, j) <- Seq((10, 8), (30, 16), (50, 24), (20, 12)) do
      val f = Shallow.coriolis(p, j + 0.5)
      val uMean = 0.5 * (s.u(i, j) + s.u(i + 1, j))
      val geo = p.gravity / f * (s.eta(i, j + 1) - s.eta(i, j - 1)) / (2.0 * p.dx)
      assertEqualsDouble(uMean, geo, 0.03 * math.abs(geo) + 1e-6, s"u at ($i,$j)")
    for j <- 0 until 32 do assertEqualsDouble(s.u(64, j), s.u(0, j), 1e-12)
    for i <- 0 until 64 do
      assertEquals(s.v(i, 0), 0.0)
      assertEquals(s.v(i, 32), 0.0)
  }
