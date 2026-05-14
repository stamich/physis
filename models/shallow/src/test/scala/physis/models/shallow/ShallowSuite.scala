package physis.models.shallow

import physis.core.Stepping
import physis.verify.Allocation
import ShallowSupport.*

class ShallowSuite extends munit.FunSuite:

  test("water at rest stays at rest, with and without rotation") {
    for f0 <- Seq(0.0, 1e-4) do
      val r = run(params(f0 = f0))
      r.runSteps(50)
      assertEquals(r.state.eta.max, 0.0)
      assertEquals(r.state.eta.min, 0.0)
      assertEquals(r.state.u.max, 0.0)
      assertEquals(r.state.v.min, 0.0)
  }

  test("the wave speed is the square root of g times the depth") {
    val p = params(depth = 4000.0)
    assertEqualsDouble(Shallow.waveSpeed(p), math.sqrt(9.81 * 4000.0), 1e-12)
    assertEqualsDouble(Shallow.waveSpeed(p) * 3.6, 713.1, 0.1)
  }

  test("the Courant number is checked: a step that outruns the grid is refused") {
    val fine = params(depth = 4000.0, dx = 20_000.0)
    assertEquals(Shallow.check(fine, Shallow.stepping), Right(()))
    val deep = params(depth = 7000.0, dx = 5_000.0)
    val refused = Shallow.check(deep, Shallow.stepping)
    assert(refused.isLeft, "deep water on small cells needs a shorter step")
    assert(refused.left.exists(_.contains("cells per step")), refused.toString)
    assertEquals(Shallow.check(deep, Stepping.Fixed(5.0)), Right(()))
  }

  test("a run refuses parameters that would make the step unstable") {
    val built = scala.util.Try(run(params(depth = 7000.0, dx = 5_000.0)))
    assert(built.isFailure, "the run must not be built")
  }

  test("the volume of water is conserved to rounding in a closed basin") {
    val r = run(params(f0 = 1e-4, nx = 48, ny = 36))
    hump(r.state, 20.0, 15.0, 3.0, 1.0)
    val before = volume(r.state)
    r.runSteps(400)
    val after = volume(r.state)
    assertEqualsDouble(after, before, 1e-11 * math.abs(before))
    assert(r.state.eta.max < 1.0, "the hump has spread")
  }

  test("a hump spreads as a ring: the height is the same on all four sides") {
    val r = run(params(nx = 61, ny = 61))
    hump(r.state, 30.5, 30.5, 2.5, 1.0)
    r.runSteps(60)
    val s = r.state
    for d <- Seq(5, 12, 20) do
      val e = s.eta(30 + d, 30)
      assertEqualsDouble(s.eta(30 - d, 30), e, 1e-9, s"x at $d")
      assertEqualsDouble(s.eta(30, 30 + d), e, 1e-9, s"y at $d")
      assertEqualsDouble(s.eta(30, 30 - d), e, 1e-9, s"y at -$d")
  }

  test("the front starts as a step across the middle and the water is at rest") {
    val r = run(params(start = 1, amp = 0.5, nx = 8, ny = 40))
    val s = r.state
    assertEqualsDouble(s.eta(3, 0), 0.5, 1e-5)
    assertEqualsDouble(s.eta(3, 39), 0.0, 1e-5)
    assert(s.eta(3, 19) > s.eta(3, 20), "the step falls from the lower half to the upper")
    assertEquals(s.u.max, 0.0)
    assertEquals(s.v.max, 0.0)
  }

  test("rotation turns the flow to the right: a hump gets a clockwise swirl, and none without f") {
    def swirl(f0: Double): Double =
      val r = run(params(f0 = f0, nx = 41, ny = 41))
      hump(r.state, 20.5, 20.5, 3.0, 1.0)
      r.runSteps(60)
      (0 until 41).map(j => r.state.v(26, j)).sum
    assertEqualsDouble(swirl(0.0), 0.0, 1e-12)
    assert(swirl(1e-3) > 1e-4, s"east of the hump the water runs south (v > 0): ${swirl(1e-3)}")
  }

  test("two runs from the same parameters agree to the last bit") {
    val p = params(f0 = 1e-4, start = 1, amp = 1.0)
    val a = run(p)
    val b = run(p)
    a.runSteps(120)
    b.runSteps(120)
    assertEquals(Shallow.digest(a.state), Shallow.digest(b.state))
  }

  test("a step allocates nothing") {
    assume(Allocation.supported)
    val r = run(params(f0 = 1e-4, start = 1, amp = 1.0, nx = 64, ny = 48))
    r.runSteps(5)
    val bytes = Allocation.perStep(100)(r.step())
    assert(bytes < 3000L, s"$bytes bytes over 100 steps")
  }
