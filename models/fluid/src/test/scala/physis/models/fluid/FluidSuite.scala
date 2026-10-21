package physis.models.fluid

import physis.verify.Allocation
import FluidSupport.*

class FluidSuite extends munit.FunSuite:
  test("a Taylor-Green vortex decays as exp(-4 ν k² t), within the numerical diffusion") {
    val nu = 0.2
    val n = 32
    val r = run(params(FluidEdge.Periodic, n, n, viscosity = nu))
    taylorGreen(r.state, 0.02)
    val e0 = Fluid.kineticEnergy.measure(r.state, 0.0)
    assert(e0 > 0.0)
    val steps = 100
    r.runSteps(steps)
    val e1 = Fluid.kineticEnergy.measure(r.state, 0.0)
    val k = 2.0 * math.Pi / n
    val expected = 4.0 * nu * k * k * steps * Fluid.Dt
    val measured = math.log(e0 / e1)
    assertEqualsDouble(measured, expected, 0.1 * expected, s"decay $measured vs $expected")
    assert(Fluid.maxDivergence.measure(r.state, 0.0) < 1e-6)
  }

  test("the vortex keeps its shape: the velocity stays proportional to the initial one") {
    val r = run(params(FluidEdge.Periodic, 32, 32, viscosity = 0.2))
    taylorGreen(r.state, 0.02)
    val u0 = r.state.u(5, 9)
    val e0 = Fluid.kineticEnergy.measure(r.state, 0.0)
    r.runSteps(60)
    val ratio = r.state.u(5, 9) / u0
    val energy = Fluid.kineticEnergy.measure(r.state, 0.0) / e0
    assertEqualsDouble(ratio * ratio, energy, 0.01 * energy)
  }

  test("every step leaves the flow free of divergence: closed box with a lid") {
    val r = run(params(FluidEdge.Box, 32, 32, viscosity = 0.3, lid = 1.0))
    for _ <- 1 to 20 do
      r.step()
      assert(Fluid.maxDivergence.measure(r.state, 0.0) < 1e-6)
    assert(Fluid.kineticEnergy.measure(r.state, 0.0) > 0.0, "the lid drives the fluid")
  }

  test("a box at rest stays at rest, with or without dye") {
    val r = run(params(FluidEdge.Box, 24, 24))
    r.runSteps(20)
    assertEquals(Fluid.kineticEnergy.measure(r.state, 0.0), 0.0)
    assertEquals(Fluid.dyeMass.measure(r.state, 0.0), 0.0)
  }

  test("dye is carried by a uniform channel flow and not created") {
    val r = run(params(FluidEdge.Channel, 48, 24, viscosity = 0.0, inflow = 1.0))
    dyeSquare(r.state, 8, 8, 6)
    val mass = Fluid.dyeMass.measure(r.state, 0.0)
    assertEquals(mass, 36.0)
    r.runSteps(8)
    val m1 = Fluid.dyeMass.measure(r.state, 0.0)
    assert(m1 <= mass + 1e-9 && m1 > 0.9 * mass, s"mass $m1")
    // the dye has moved to the right
    var sx = 0.0
    var sm = 0.0
    for j <- 0 until 24; i <- 0 until 48 do
      sx += r.state.dye(i, j) * (i + 0.5)
      sm += r.state.dye(i, j)
    assert(sx / sm > 11.0 + 0.5 * Fluid.Dt * 8 * 1.0, s"centroid ${sx / sm}")
  }

  test("buoyancy lifts the dye: its centre rises") {
    val r = run(params(FluidEdge.Box, 32, 48, viscosity = 0.05, buoyancy = 1.0))
    dyeSquare(r.state, 12, 6, 8)
    def height(): Double =
      var sy = 0.0
      var sm = 0.0
      for j <- 0 until 48; i <- 0 until 32 do
        sy += r.state.dye(i, j) * (j + 0.5)
        sm += r.state.dye(i, j)
      sy / sm
    val h0 = height()
    r.runSteps(40)
    assert(height() > h0 + 1.0, s"${height()} vs $h0")
  }

  test("the check refuses a viscosity the explicit step cannot hold") {
    val ok = params(FluidEdge.Box, 32, 32, viscosity = 0.8)
    assert(Fluid.check(ok, Fluid.stepping).isRight)
    val bad = ok.copy(viscosity = 0.9)
    assert(Fluid.check(bad, Fluid.stepping).isLeft)
    assert(Fluid.check(ok.copy(dyeDiffusion = 0.9), Fluid.stepping).isLeft)
    assert(!Fluid.supports(physis.core.Stepping.Adaptive(1e-6, 1e-9, 0.1, 1.0)))
  }

  test("two runs of the same setup give the same bits") {
    def go(): Long =
      val r = run(params(FluidEdge.Box, 32, 32, viscosity = 0.3, lid = 1.0, buoyancy = 0.5))
      dyeSquare(r.state, 10, 4, 6)
      r.runSteps(50)
      r.digest
    assertEquals(go(), go())
  }

  test("a step allocates nothing: a lid-driven box with dye, diffusion and buoyancy") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val r = run(
      params(FluidEdge.Box, 32, 32, viscosity = 0.3, dyeDiffusion = 0.1, buoyancy = 0.5, lid = 1.0)
    )
    dyeSquare(r.state, 10, 4, 6)
    val bytes = Allocation.perStep(300)(r.step())
    assert(bytes < 3000, s"$bytes bytes over 300 steps")
  }

  test("a step allocates nothing in a channel with a solid block") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val r = run(params(FluidEdge.Channel, 48, 24, viscosity = 0.2, inflow = 1.0))
    for j <- 8 until 16; i <- 10 until 14 do r.state.wall(i, j) = true
    r.state.wallsChanged()
    val bytes = Allocation.perStep(300)(r.step())
    assert(bytes < 3000, s"$bytes bytes over 300 steps")
  }

  test("golden digests: a lid-driven box with buoyant dye, and a channel around a block") {
    val a = run(
      params(FluidEdge.Box, 32, 32, viscosity = 0.3, dyeDiffusion = 0.05, buoyancy = 0.5, lid = 1.0)
    )
    dyeSquare(a.state, 10, 4, 6)
    a.runSteps(100)
    val b = run(params(FluidEdge.Channel, 48, 24, viscosity = 0.2, inflow = 1.0))
    for j <- 8 until 16; i <- 10 until 14 do b.state.wall(i, j) = true
    b.state.wallsChanged()
    dyeSquare(b.state, 2, 10, 4)
    b.runSteps(100)
    // Equal on ubuntu-latest and macos-latest: the step uses plain arithmetic in a fixed order.
    assertEquals(a.digest, 2999162247962409871L)
    assertEquals(b.digest, -5633919091612459141L)
  }
