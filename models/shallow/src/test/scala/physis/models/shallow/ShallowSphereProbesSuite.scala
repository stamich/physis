package physis.models.shallow

import physis.core.Stepping
import ShallowSupport.*

class ShallowSphereProbesSuite extends munit.FunSuite:
  private val dt = Stepping.Fixed(50.0)

  private def globe(watchLon: Double, level: Double = 0.01): ShallowParams =
    params(
      geometry = 1,
      nx = 72,
      ny = 32,
      depth = 4000.0,
      watchLon = watchLon,
      watchLat = 0.0,
      arrivalLevel = level
    )

  private def slice(s: ShallowState, name: String): Array[Double] =
    val out = new Array[Double](ShallowState.stateExport.size(s))
    ShallowState.stateExport.copyInto(s, out)
    val sl = ShallowState.stateExport.layout(s).find(_.name == name).get
    out.slice(sl.offset, sl.offset + sl.width * sl.height)

  private def arrivalAt(lon: Double): Double =
    val r = run(globe(lon), dt)
    // a hump on the equator at longitude 0 (column 36, rows 15 and 16 straddle the equator)
    hump(r.state, 36.0, 16.0, 3.0, 1.0)
    val probe = Shallow.arrival
    var t = -1.0
    var steps = 0
    while t < 0.0 && steps < 3000 do
      r.runSteps(10)
      steps += 10
      t = probe.measure(r.state, 0.0)
    t

  test("the arrival probe is -1 until the wave comes, then the time it came") {
    val r = run(globe(90.0), dt)
    hump(r.state, 36.0, 16.0, 3.0, 1.0)
    assertEquals(Shallow.arrival.measure(r.state, 0.0), -1.0)
    r.runSteps(10)
    assertEquals(Shallow.arrival.measure(r.state, 0.0), -1.0)
    assertEquals(Shallow.probes.last.name, "arrival")
  }

  test("a wave reaches places in the order of the distance, at the speed of the waves") {
    val t90 = arrivalAt(90.0)
    val t150 = arrivalAt(150.0)
    assert(t90 > 0.0 && t150 > t90, s"$t90 then $t150")
    val c = math.sqrt(9.81 * 4000.0)
    val gap = (150.0 - 90.0).toRadians * 6.371e6 / c
    val ratio = (t150 - t90) / gap
    assert(ratio > 0.9 && ratio < 1.1, f"the wave covered 60 degrees at $ratio%.3f of sqrt(g h)")
  }

  test("the arrival slice agrees with the probe, and the plane records nothing") {
    val r = run(globe(90.0), dt)
    hump(r.state, 36.0, 16.0, 3.0, 1.0)
    r.runSteps(1500)
    val map = slice(r.state, "arrival")
    val g = Shallow.sphereOf(r.params).toOption.get
    assertEquals(map(g.rowOf(0.0) * 72 + g.columnOf(90.0)), Shallow.arrival.measure(r.state, 0.0))
    val plane = run(params(), dt)
    plane.runSteps(5)
    assert(slice(plane.state, "arrival").forall(_ == -1.0))
    assertEquals(Shallow.arrival.measure(plane.state, 0.0), -1.0)
  }

  test("the graticule is drawn on the meridians and parallels every 30 degrees") {
    val r = run(globe(0.0), dt)
    val grat = slice(r.state, "graticule")
    // the cell column west of 0 degrees holds that meridian, in every row
    assert((0 until 32).forall(j => grat(j * 72 + 35) == 1.0))
    // the cell row between 30 and 25 degrees north holds that parallel, in every column
    assert((0 until 72).forall(i => grat(10 * 72 + i) == 1.0))
    assertEquals(grat(0 * 72 + 10), 0.0)
    assert(slice(run(params(), dt).state, "graticule").forall(_ == 0.0))
  }
