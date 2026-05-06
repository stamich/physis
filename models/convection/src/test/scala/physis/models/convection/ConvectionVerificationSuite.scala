package physis.models.convection

import physis.core.run.{Disc, Intervention, Op}
import ConvectionSupport.*

/**
 * Physics checks against theory and against `tools/convection_reference.py` (numpy). The
 * thresholds come from the numbers that script prints.
 */
class ConvectionVerificationSuite extends munit.FunSuite:

  /** A periodic layer two depths wide, the wavelength the first instability prefers (about 2). */
  private def layer(ra: Double, kick: Double = 0.01): ConvectionRun =
    run(params(rayleigh = ra, nx = 48, ny = 24, kick = kick))

  /** Kinetic energy at 3 000 steps over that at 2 000 steps. */
  private def ratio(ra: Double): Double =
    val r = layer(ra)
    r.runSteps(2000)
    val before = Convection.kineticEnergy.measure(r.state, 0.0)
    r.runSteps(1000)
    Convection.kineticEnergy.measure(r.state, 0.0) / before

  test("below the critical Rayleigh number (1708) a perturbation dies away") {
    // reference: 0.064 at Ra 1200 and 0.39 at Ra 1500
    val low = ratio(1200.0)
    val near = ratio(1500.0)
    assert(low < 0.2, s"Ra 1200: $low")
    assert(near < 0.6, s"Ra 1500: $near")
    assert(low < near, "the nearer the critical value, the slower the decay")
  }

  test("above the critical Rayleigh number a perturbation grows") {
    // reference: 15 at Ra 2200
    val grown = ratio(2200.0)
    assert(grown > 5.0, s"Ra 2200: $grown")
  }

  test("the rolls in a box four depths wide have a wavelength of two depths") {
    val r = run(params(rayleigh = 3000.0, nx = 96, ny = 24, kick = 0.01))
    r.runSteps(14000)
    // sign changes of the vertical velocity along the mid-plane, with the sides wrapping
    val w = Vector.tabulate(96)(i => r.state.v(i, 12))
    val changes = w.indices.count(i => (w(i) < 0.0) != (w((i + 1) % 96) < 0.0))
    // linear theory: 2 pi / 3.117 = 2.016 depths, so two pairs of rolls: 4 sign changes
    assertEquals(changes, 4)
    assertEqualsDouble(Convection.nusselt.measure(r.state, 0.0), 1.585, 0.1)
  }

  test("the Nusselt number follows the reference over a range of Rayleigh numbers") {
    // numpy reference, 48 x 24, Pr 1: Nu 2.058 at 5 000 and 2.948 at 20 000
    for (ra, nu, tol) <- Seq((5000.0, 2.058, 0.12), (20_000.0, 2.948, 0.3)) do
      val r = layer(ra)
      r.runSteps(16000)
      val measured = Convection.nusselt.measure(r.state, 0.0)
      assertEqualsDouble(measured, nu, tol, s"Ra $ra: $measured")
  }

  test("a cold patch at the lid sinks, and the signs of buoyancy are the right way round") {
    val r = run(params(rayleigh = 20_000.0, sides = ConvectionSides.Walls, kick = 0.0))
    assertEquals(
      Convection.intervene(
        r.state,
        r.params,
        Intervention.Brush("heat", Disc(24.0, 20.0, 3.0), Op.Assign, -0.5)
      ),
      Right(())
    )
    r.runSteps(30)
    assert(r.state.v(24, 16) < 0.0, s"v below the patch ${r.state.v(24, 16)}")
  }

  test("a layer that has settled is steady: the Nusselt number stops changing") {
    val r = layer(3000.0)
    r.runSteps(14000)
    val a = Convection.nusselt.measure(r.state, 0.0)
    r.runSteps(1000)
    val b = Convection.nusselt.measure(r.state, 0.0)
    assertEqualsDouble(a, b, 0.005 * a)
  }
