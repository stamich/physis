package physis.models.shallow

import ShallowSupport.*

class ShallowProbesSuite extends munit.FunSuite:

  test("the probes are in the order the views read them") {
    assertEquals(
      Shallow.probes.map(_.name),
      Vector(
        "mass",
        "energy",
        "max_height",
        "min_height",
        "max_speed",
        "wet_fraction",
        "flooded_area",
        "runup",
        "max_runup",
        "max_inundation"
      )
    )
  }

  test("at rest every probe is zero") {
    val r = run(params(f0 = 1e-4))
    r.runSteps(5)
    for probe <- Shallow.probes do assertEquals(probe.measure(r.state, 0.0), 0.0, probe.name)
  }

  test("the mass probe is the volume above rest and stays constant") {
    val r = run(params(f0 = 1e-4, nx = 60, ny = 50))
    hump(r.state, 30.0, 25.0, 4.0, 1.0)
    val m0 = Shallow.mass.measure(r.state, 0.0)
    assertEqualsDouble(m0, volume(r.state), 1e-6)
    assertEqualsDouble(m0, 2.0 * math.Pi * 16.0 * 20_000.0 * 20_000.0, 0.01 * m0)
    r.runSteps(400)
    assertEqualsDouble(Shallow.mass.measure(r.state, 0.0), m0, 1e-11 * m0)
  }

  test("the energy of waves in a closed basin stays within a few percent of the start") {
    for f0 <- Seq(0.0, 1e-4) do
      val r = run(params(f0 = f0, nx = 60, ny = 50))
      hump(r.state, 30.0, 25.0, 4.0, 1.0)
      val e0 = Shallow.energy.measure(r.state, 0.0)
      // all potential at the start: ½ g Σ η² dx²
      assertEqualsDouble(e0, 0.5 * 9.81 * r.state.eta.data.map(x => x * x).sum * 4.0e8, 1e-6 * e0)
      var lo = 1.0
      var hi = 1.0
      for _ <- 1 to 1500 do
        r.step()
        val e = Shallow.energy.measure(r.state, 0.0) / e0
        lo = math.min(lo, e)
        hi = math.max(hi, e)
      assert(lo > 0.92 && hi < 1.08, s"f0 $f0: energy between $lo and $hi of the start")
  }

  test("friction takes energy away") {
    def last(friction: Double): Double =
      val r = run(params(friction = friction, nx = 60, ny = 50))
      hump(r.state, 30.0, 25.0, 4.0, 1.0)
      r.runSteps(800)
      Shallow.energy.measure(r.state, 0.0)
    assert(last(2e-4) < 0.9 * last(0.0))
  }

  test("the extremes and the speed follow a hump") {
    val r = run(params(nx = 60, ny = 50))
    hump(r.state, 30.0, 25.0, 4.0, 2.0)
    assertEqualsDouble(Shallow.maxHeight.measure(r.state, 0.0), 2.0, 0.05)
    assertEqualsDouble(Shallow.minHeight.measure(r.state, 0.0), 0.0, 1e-6) // the tails of the hump
    assertEquals(Shallow.maxSpeed.measure(r.state, 0.0), 0.0)
    r.runSteps(20)
    assert(Shallow.maxSpeed.measure(r.state, 0.0) > 1e-3)
    assert(Shallow.maxHeight.measure(r.state, 0.0) < 2.0)
    val lowered = run(params(nx = 60, ny = 50))
    hump(lowered.state, 30.0, 25.0, 4.0, -1.0)
    assertEqualsDouble(Shallow.minHeight.measure(lowered.state, 0.0), -1.0, 0.05)
  }
