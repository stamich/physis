package physis.models.shallow

import physis.core.Stepping
import ShallowSupport.*

/**
 * Flooding against what is known: a lake at rest, the water in a parabolic bowl (Thacker), the
 * run-up of a solitary wave on a beach (Synolakis). The numbers beside the tolerances are those
 * of `tools/shallow_flood_reference.py`, an independent numpy implementation of the scheme.
 */
class ShallowFloodVerificationSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val g = 9.81

  /** A tank 100 m long with cells of 0.25 m and 1 m of water; the beach starts in the middle. */
  private def tank(
      seabed: Int,
      start: Int,
      amp: Double,
      slope: Double,
      edges: Int = Shallow.Basin
  ) =
    run(
      params(
        depth = 1.0,
        dx = 0.25,
        nonlinear = 1,
        flooding = 1,
        seabed = seabed,
        start = start,
        amp = amp,
        slope = slope,
        edges = edges,
        sponge = 20,
        nx = 400,
        ny = 16,
        dryDepth = 1e-4
      ),
      Stepping.Fixed(0.05)
    )

  private val Cot = 19.85

  test("a lake at rest against a beach and against a bay does not move, over 2000 steps") {
    for seabed <- Seq(3, 4) do
      val r = tank(seabed, 0, 0.0, 1.0 / Cot)
      val s = r.state
      val before = s.eta.data.clone()
      r.runSteps(2000)
      assertEquals(s.eta.data.toSeq, before.toSeq, s"seabed $seabed")
      assertEquals(s.u.data.map(math.abs).max, 0.0)
      assertEquals(s.v.data.map(math.abs).max, 0.0)
  }

  /** The highest the water stood on the beach, in m above the sea, over `steps` steps. */
  private def highest(r: ShallowRun, steps: Int): Double =
    val s = r.state
    var top = 0.0
    var step = 0
    while step < steps do
      r.step()
      var j = 0
      while j < s.mac.ny do
        var i = 0
        while i < s.mac.nx do
          val water = s.eta(i, j) + s.shape(i, j) // bed is -shape (the depth is 1 m)
          if water > 1e-4 then top = math.max(top, s.eta(i, j))
          i += 1
        j += 1
      step += 1
    top

  test("the run-up of a wave of H/d = 0.01 on a 1:19.85 beach is Synolakis' law within 10 %") {
    val r = tank(3, 3, 0.01, 1.0 / Cot)
    val top = highest(r, 800)
    val law = 2.831 * math.sqrt(Cot) * math.pow(0.01, 1.25)
    assertEqualsDouble(top / law, 1.0, 0.10, f"run-up $top%.4f against $law%.4f")
    // the numpy implementation of the scheme: 0.03756
    assertEqualsDouble(top, 0.03756, 0.03 * 0.03756)
  }

  test("the run-up of a wave of H/d = 0.0185 is that of the numpy scheme, and below the law") {
    val r = tank(3, 3, 0.0185, 1.0 / Cot)
    val volume = r.state.volume
    val top = highest(r, 800)
    val law = 2.831 * math.sqrt(Cot) * math.pow(0.0185, 1.25)
    // the semi-Lagrangian advection of the flow smooths the steep front and takes 23 % off
    assertEqualsDouble(top, 0.06664, 0.03 * 0.06664)
    assert(top / law > 0.65 && top / law < 1.1, f"ratio ${top / law}%.3f")
    assertEqualsDouble(r.state.volume, volume, 1e-9)
  }

  test("a higher wave runs higher") {
    val low = highest(tank(3, 3, 0.01, 1.0 / Cot), 800)
    val high = highest(tank(3, 3, 0.02, 1.0 / Cot), 800)
    assert(high > 1.3 * low, s"$high against $low")
  }

  test("the sponge of an open sea next to a beach lets the wave go, and the water stays sane") {
    val r = tank(3, 3, 0.0185, 1.0 / Cot, edges = Shallow.OpenSea)
    val s = r.state
    val initial = s.volume
    r.runSteps(1200)
    for j <- 0 until 16; i <- 0 until 400 do
      assert(s.eta(i, j).isFinite)
      assert(s.eta(i, j) >= -s.shape(i, j) - 1e-12, s"below the floor at ($i,$j)")
    assert(s.volume < 0.8 * initial, s"${s.volume} of $initial is left")
  }

  /** The water of a parabolic bowl `z = h0 ((x/a)² - 1)`, moving as a whole (Thacker 1981). */
  private def bowl(dx: Double, periods: Double): (Double, Double, Int, Int, Double) =
    val h0 = 1.0
    val a = 10.0
    val b = 2.0
    val nx = math.round(3.0 * a / dx).toInt
    val r = run(
      params(depth = h0, dx = dx, nonlinear = 1, flooding = 1, nx = nx, ny = 16, dryDepth = 1e-4),
      Stepping.Fixed(0.2 * dx)
    )
    val s = r.state
    def x(i: Int): Double = (i + 0.5) * dx - 1.5 * a
    for j <- 0 until 16; i <- 0 until nx do s.shape(i, j) = 1.0 - (x(i) / a) * (x(i) / a)
    val w = math.sqrt(2.0 * g * h0) / a
    def exact(i: Int, t: Double): Double =
      val c = (x(i) - b * math.cos(w * t)) / a
      math.max(0.0, h0 - h0 * c * c)
    for j <- 0 until 16; i <- 0 until nx do s.eta(i, j) = exact(i, 0.0) - h0 * s.shape(i, j)
    s.settle()
    val volume = s.volume
    val steps = math.round(periods * 2.0 * math.Pi / w / (0.2 * dx)).toInt
    r.runSteps(steps.toLong)
    val t = steps * 0.2 * dx
    var diff = 0.0
    var total = 0.0
    var wetLo = nx
    var wetHi = -1
    var exLo = nx
    var exHi = -1
    for i <- 0 until nx do
      val depth = s.eta(i, 5) - (-(h0 * s.shape(i, 5)))
      val want = exact(i, t)
      diff += math.abs(depth - want)
      total += want
      if depth > 1e-4 then
        wetLo = math.min(wetLo, i)
        wetHi = math.max(wetHi, i)
      if want > 0.0 then
        exLo = math.min(exLo, i)
        exHi = math.max(exHi, i)
    (diff / total, math.abs(s.volume - volume), wetLo - exLo, wetHi - exHi, t)

  test("the water of a parabolic bowl comes back after a period, with its shores in place") {
    val (l1, drift, lo, hi, _) = bowl(0.1, 1.0)
    // numpy: L1 error 0.0447, shores 4 and -2 cells off
    assert(l1 < 0.07, f"L1 error $l1%.4f")
    assert(drift < 1e-8, s"volume drift $drift")
    assert(math.abs(lo) <= 6 && math.abs(hi) <= 6, s"shores off by $lo and $hi cells")
  }

  test("a finer bowl is closer to the exact solution") {
    val coarse = bowl(0.2, 1.0)._1
    val fine = bowl(0.1, 1.0)._1
    assert(fine < coarse, f"$fine%.4f against $coarse%.4f")
  }
