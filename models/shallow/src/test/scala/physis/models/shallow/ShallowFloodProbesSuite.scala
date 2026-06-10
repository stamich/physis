package physis.models.shallow

import ShallowSupport.*

class ShallowFloodProbesSuite extends munit.FunSuite:
  private def beach(rise: Double = 0.05) =
    val r = run(
      params(depth = 10.0, dx = 100.0, nonlinear = 1, flooding = 1, nx = 60, ny = 8),
      physis.core.Stepping.Fixed(5.0)
    )
    val s = r.state
    for j <- 0 until 8; i <- 0 until 60 do
      s.shape(i, j) = if i < 30 then 1.0 else 1.0 - rise * (i - 29)
    s.settle()
    r

  test("without flooding the shore probes read 0") {
    val r = run(params(nx = 30, ny = 20))
    hump(r.state, 15.0, 10.0, 3.0, 1.0)
    r.runSteps(10)
    for name <- Seq("wet_fraction", "flooded_area", "runup", "max_runup", "max_inundation") do
      val probe = Shallow.probes.find(_.name == name).get
      assertEquals(probe.measure(r.state, 0.0), 0.0, name)
  }

  test("a lake at rest on a beach: part of the cells are dry and nothing is flooded") {
    val r = beach()
    val s = r.state
    // the floor reaches the sea level at i = 49: cells 49 to 59 hold no water, 49 of 60 do
    assertEqualsDouble(Shallow.wetFraction.measure(s, 0.0), 49.0 / 60.0, 1e-12)
    assertEquals(Shallow.floodedArea.measure(s, 0.0), 0.0)
    assertEquals(Shallow.runup.measure(s, 0.0), 0.0)
    r.runSteps(50)
    assertEquals(Shallow.maxRunup.measure(s, 0.0), 0.0)
    assertEquals(Shallow.maxInundation.measure(s, 0.0), 0.0)
  }

  test("a wave floods the shore: the maxima keep what the run has seen") {
    val r = beach()
    val s = r.state
    ridge(s, 10.0, 3.0, 2.0)
    s.settle()
    var seen = 0.0
    var area = 0.0
    for _ <- 1 to 60 do
      r.runSteps(10)
      seen = math.max(seen, Shallow.runup.measure(s, 0.0))
      area = math.max(area, Shallow.floodedArea.measure(s, 0.0))
      assert(Shallow.maxRunup.measure(s, 0.0) >= Shallow.runup.measure(s, 0.0))
    assert(Shallow.maxRunup.measure(s, 0.0) >= seen - 1e-12)
    assert(Shallow.maxRunup.measure(s, 0.0) > 0.0, "the wave should reach the shore")
    assert(Shallow.maxInundation.measure(s, 0.0) > 0.0)
    assert(area > 0.0)
    assertEquals(area % (100.0 * 100.0), 0.0)
  }

  test("the water and dry slices tell the sea from the shore") {
    val r = beach()
    val s = r.state
    val g = s.mac.cells
    val out = new Array[Double](ShallowState.stateExport.size(s))
    ShallowState.stateExport.copyInto(s, out)
    val water = out.slice(5 * g.cells, 6 * g.cells)
    val dry = out.slice(6 * g.cells, 7 * g.cells)
    assertEqualsDouble(water(0), 10.0, 1e-12) // open sea, 10 m deep
    assertEquals(water(g.nx - 1), 0.0) // on the shore
    assertEquals(dry(0), 0.0)
    assertEquals(dry(g.nx - 1), 1.0)
    assertEquals(dry.sum, 8.0 * 11.0)
  }
