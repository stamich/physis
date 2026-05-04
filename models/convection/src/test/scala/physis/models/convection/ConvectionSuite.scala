package physis.models.convection

import physis.core.run.{Disc, Intervention, Op}
import physis.verify.Allocation
import ConvectionSupport.*

class ConvectionSuite extends munit.FunSuite:

  test("the diffusions follow the Prandtl number and the larger one is the explicit limit") {
    val max = Convection.MaxDiffusion
    assertEqualsDouble(max, 0.8, 1e-12)
    for pr <- Seq(0.1, 0.5, 1.0, 4.0, 10.0) do
      val p = params(prandtl = pr)
      assertEqualsDouble(Convection.viscosity(p) / Convection.diffusivity(p), pr, 1e-12)
      assertEqualsDouble(
        math.max(Convection.viscosity(p), Convection.diffusivity(p)),
        max,
        1e-12
      )
  }

  test("the buoyancy gives the asked Rayleigh number: Ra = a H³ / (ν κ)") {
    for (ra, pr, ny) <- Seq((1708.0, 1.0, 24), (50_000.0, 0.7, 48), (3.0e5, 7.0, 32)) do
      val p = params(rayleigh = ra, prandtl = pr, ny = ny)
      val back =
        Convection.acceleration(p) * math.pow(ny.toDouble, 3) /
          (Convection.viscosity(p) * Convection.diffusivity(p))
      assertEqualsDouble(back, ra, 1e-9 * ra)
  }

  test("a layer with no perturbation stays at rest, whatever the Rayleigh number") {
    for ra <- Seq(500.0, 50_000.0) do
      val r = run(params(rayleigh = ra, kick = 0.0))
      r.runSteps(40)
      assertEquals(Convection.kineticEnergy.measure(r.state, 0.0), 0.0)
      assertEquals(r.state.deviation.max, 0.0)
      assertEquals(r.state.deviation.min, 0.0)
  }

  test("every step leaves the flow free of divergence, with periodic and with insulated sides") {
    for sides <- Seq(ConvectionSides.Periodic, ConvectionSides.Walls) do
      val r = run(params(rayleigh = 20_000.0, sides = sides, kick = 0.05))
      for _ <- 1 to 30 do
        r.step()
        assert(Convection.maxDivergence.measure(r.state, 0.0) < 1e-6)
      assert(Convection.kineticEnergy.measure(r.state, 0.0) > 0.0, "the perturbation moves")
  }

  test("a warm patch at the floor rises: the fluid above it moves up") {
    val r = run(params(rayleigh = 20_000.0, sides = ConvectionSides.Walls, kick = 0.0))
    val ok = Convection.intervene(
      r.state,
      r.params,
      Intervention.Brush("heat", Disc(24.0, 4.0, 3.0), Op.Assign, 0.5)
    )
    assertEquals(ok, Right(()))
    r.runSteps(30)
    assert(r.state.v(24, 8) > 0.0, s"v above the patch ${r.state.v(24, 8)}")
    assert(Convection.kineticEnergy.measure(r.state, 0.0) > 0.0)
  }

  test("the temperature is 1 at the floor and 0 at the lid when nothing has moved") {
    val r = run(params(kick = 0.0, ny = 20))
    assertEqualsDouble(r.state.temperature(3, 0), 1.0 - 0.5 / 20, 1e-12)
    assertEqualsDouble(r.state.temperature(3, 19), 0.5 / 20, 1e-12)
  }

  test("only walls and heat can be painted") {
    assert(Convection.accepts(Intervention.Brush("wall", Disc(1, 1, 1), Op.Assign, 1.0)).isRight)
    assert(Convection.accepts(Intervention.Pulse("heat", 1, 1, 2.0, 0.3)).isRight)
    assert(Convection.accepts(Intervention.Brush("dye", Disc(1, 1, 1), Op.Assign, 1.0)).isLeft)
    assert(Convection.accepts(Intervention.Pulse("u", 1, 1, 2.0, 0.3)).isLeft)
  }

  test("the check refuses a larger step and adaptive stepping is not supported") {
    assert(Convection.check(params(), Convection.stepping).isRight)
    assert(Convection.check(params(), physis.core.Stepping.Fixed(0.5)).isLeft)
    assert(!Convection.supports(physis.core.Stepping.Adaptive(1e-6, 1e-9, 0.1, 1.0)))
  }

  test("two runs of the same setup give the same bits") {
    def go(): Long =
      val r = run(params(rayleigh = 30_000.0, sides = ConvectionSides.Walls, kick = 0.05))
      r.runSteps(60)
      r.digest
    assertEquals(go(), go())
  }

  test("a step allocates nothing, with walls inside") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val r = run(params(rayleigh = 30_000.0, kick = 0.05))
    for j <- 8 until 12; i <- 10 until 14 do r.state.wall(i, j) = true
    r.state.wallsChanged()
    val bytes = Allocation.perStep(300)(r.step())
    assert(bytes < 3000, s"$bytes bytes over 300 steps")
  }
