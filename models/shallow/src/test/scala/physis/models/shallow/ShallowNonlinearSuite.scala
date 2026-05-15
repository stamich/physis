package physis.models.shallow

import physis.verify.Allocation
import ShallowSupport.*

class ShallowNonlinearSuite extends munit.FunSuite:

  test("the shelf is as deep as the sea over the first half and a twentieth of it at the end") {
    val s = run(params(seabed = 1, nx = 100, ny = 8)).state
    for j <- 0 until 8 do
      assertEquals(s.shape(0, j), 1.0)
      assertEquals(s.shape(54, j), 1.0)
      assertEqualsDouble(s.shape(99, j), Shallow.ShelfDepth, 1e-12)
    for i <- 55 until 99 do assert(s.shape(i, 3) <= s.shape(i - 1, 3), s"not rising at $i")
  }

  test("the seamount takes most of the depth away at the top and none far from it") {
    val s = run(params(seabed = 2, nx = 60, ny = 60)).state
    assertEqualsDouble(s.shape(29, 29), 0.2, 0.02)
    assertEqualsDouble(s.shape(0, 0), 1.0, 1e-6)
    assert(s.shape(29, 29) < s.shape(29, 20))
  }

  test("a flat floor has the depth everywhere") {
    val s = run(params(seabed = 0)).state
    assertEquals(s.shape.min, 1.0)
    assertEquals(s.shape.max, 1.0)
  }

  test("small waves in nonlinear mode are the linear waves") {
    def height(nl: Int): Double =
      val r = run(params(nonlinear = nl, f0 = 1e-4, nx = 50, ny = 50))
      hump(r.state, 25.0, 25.0, 3.0, 0.01)
      r.runSteps(120)
      r.state.eta(35, 28)
    val linear = height(0)
    assert(math.abs(linear) > 1e-6)
    assertEqualsDouble(height(1), linear, 1e-3 * math.abs(linear))
  }

  test("a tall wave outruns the linear one: the crest travels at about c (1 + 3 η / 2h)") {
    def crest(nl: Int): Int =
      val r = run(params(depth = 500.0, nonlinear = nl, nx = 300, ny = 4))
      hump(r.state, 50.0, 2.0, 8.0, 100.0)
      r.runSteps(300)
      (60 until 280).maxBy(i => r.state.eta(i, 1))
    val linear = crest(0)
    val tall = crest(1)
    assert(tall >= linear + 2, s"crest at $tall against $linear")
  }

  test("the volume is conserved to rounding with the full equations, land and a shelf") {
    val r =
      run(params(nonlinear = 1, f0 = 1e-4, seabed = 1, edges = Shallow.Basin, nx = 60, ny = 40))
    for j <- 15 until 20; i <- 30 until 34 do r.state.land(i, j) = true
    r.state.landChanged()
    hump(r.state, 15.0, 20.0, 3.0, 5.0)
    r.state.clearLand()
    val before = volume(r.state)
    r.runSteps(500)
    assertEqualsDouble(volume(r.state), before, 1e-10 * math.abs(before))
    assert(r.state.eta.max > 0.0)
  }

  test("a nonlinear step allocates nothing") {
    assume(Allocation.supported)
    val r = run(params(nonlinear = 1, f0 = 1e-4, seabed = 2, nx = 64, ny = 48, edges = 2))
    hump(r.state, 32.0, 24.0, 4.0, 1.0)
    r.runSteps(5)
    val bytes = Allocation.perStep(100)(r.step())
    assert(bytes < 3000L, s"$bytes bytes over 100 steps")
  }
