package physis.models.convection

import ConvectionSupport.*

class ConvectionProbesSuite extends munit.FunSuite:

  /** A periodic layer two depths wide, run until its rolls have settled. */
  private def settled(ra: Double, steps: Int = 16000): ConvectionRun =
    val r = run(params(rayleigh = ra, nx = 48, ny = 24, kick = 0.01))
    r.runSteps(steps)
    r

  test("at rest the Nusselt numbers are 1 and nothing rises") {
    val r = run(params(kick = 0.0))
    r.runSteps(10)
    for probe <- Seq(Convection.nusselt, Convection.nusseltFloor, Convection.nusseltLid) do
      assertEqualsDouble(probe.measure(r.state, 0.0), 1.0, 1e-12, probe.name)
    assertEquals(Convection.maxUp.measure(r.state, 0.0), 0.0)
  }

  test("the probes are in the order the views read them") {
    assertEquals(
      Convection.probes.map(_.name),
      Vector(
        "kinetic_energy",
        "max_divergence",
        "nusselt",
        "nusselt_floor",
        "nusselt_lid",
        "max_up"
      )
    )
  }

  test("the heat leaving the floor, the heat entering the lid and the heat carried agree") {
    val r = settled(3000.0)
    val vol = Convection.nusselt.measure(r.state, 0.0)
    val floor = Convection.nusseltFloor.measure(r.state, 0.0)
    val lid = Convection.nusseltLid.measure(r.state, 0.0)
    // numpy reference at the same grid: 1.585 / 1.580 / 1.580
    assertEqualsDouble(vol, 1.585, 0.1, s"volume $vol")
    assertEqualsDouble(floor, lid, 0.02 * floor, s"floor $floor lid $lid")
    assertEqualsDouble(vol, floor, 0.05 * floor, s"volume $vol floor $floor")
    assert(Convection.maxUp.measure(r.state, 0.0) > 0.0)
  }

  test("a harder heating carries more heat") {
    val low = Convection.nusselt.measure(settled(3000.0).state, 0.0)
    val high = Convection.nusselt.measure(settled(10_000.0).state, 0.0)
    // numpy reference: 1.585 and 2.618
    assert(high > low + 0.5, s"$low -> $high")
    assertEqualsDouble(high, 2.618, 0.25, s"Ra 10 000: $high")
  }
