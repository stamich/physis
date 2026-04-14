package physis.models.fluid

import physis.verify.Allocation
import FluidSupport.*

class FluidTracerSuite extends munit.FunSuite:
  private def xs(r: FluidRun): Array[Double] = r.state.particles.get.column("x")
  private def ys(r: FluidRun): Array[Double] = r.state.particles.get.column("y")

  test("there are no particles without tracers, and the requested number with them") {
    assertEquals(run(params(FluidEdge.Box, 32, 32)).state.particles, None)
    val r = run(params(FluidEdge.Box, 32, 32, tracers = 50))
    assertEquals(xs(r).length, 50)
    assert(xs(r).forall(x => x >= 0.0 && x < 32.0))
    assert(ys(r).distinct.length > 40, "spread over the box")
  }

  test("tracers move with a uniform periodic flow, one cell per unit speed and time") {
    val r = run(params(FluidEdge.Periodic, 32, 32, viscosity = 0.0, tracers = 5))
    for j <- 0 to 32; i <- 0 to 32 do
      if j < 32 then r.state.u(i, j) = 1.0
    for k <- 0 until 5 do
      xs(r)(k) = 3.0 + 5.0 * k
      ys(r)(k) = 4.0 + k
    r.runSteps(40) // ten time units
    for k <- 0 until 5 do
      assertEqualsDouble(xs(r)(k), (3.0 + 5.0 * k + 10.0) % 32.0, 1e-6)
      assertEqualsDouble(ys(r)(k), 4.0 + k, 1e-6)
  }

  test("tracers follow the streamlines of a Taylor-Green vortex") {
    val n = 32
    val r = run(params(FluidEdge.Periodic, n, n, viscosity = 0.0, tracers = 20))
    taylorGreen(r.state, 0.3)
    val k = 2.0 * math.Pi / n
    def psi(x: Double, y: Double) = math.sin(k * x) * math.sin(k * y)
    for q <- 0 until 20 do
      xs(r)(q) = 2.0 + 1.3 * q
      ys(r)(q) = 3.0 + 1.1 * q
    val before = Array.tabulate(20)(q => psi(xs(r)(q), ys(r)(q)))
    r.runSteps(120) // 30 time units: a few circuits
    for q <- 0 until 20 do
      val now = psi(xs(r)(q), ys(r)(q))
      assertEqualsDouble(now, before(q), 0.05, s"tracer $q")
  }

  test("in a closed box tracers stay inside and out of the walls") {
    val r = run(params(FluidEdge.Box, 48, 48, viscosity = 0.3, lid = 1.0, tracers = 400))
    val _ = r.intervene(
      physis.core.run.Intervention.Brush(
        "wall",
        physis.core.run.Disc(24.0, 24.0, 6.0),
        physis.core.run.Op.Assign,
        1.0
      )
    )
    r.runSteps(300)
    for q <- xs(r).indices do
      val x = xs(r)(q)
      val y = ys(r)(q)
      assert(x >= 0.0 && x < 48.0 && y >= 0.0 && y < 48.0, s"tracer $q at ($x, $y)")
      assert(!r.state.wall(x.toInt, y.toInt), s"tracer $q in a wall")
    val moved = xs(r).indices.count(q => ys(r)(q) > 1.0)
    assert(moved > 300)
  }

  test("in a channel tracers that leave on the right start again on the left") {
    val r = run(params(FluidEdge.Channel, 64, 32, viscosity = 0.1, inflow = 1.0, tracers = 300))
    r.runSteps(400) // a hundred time units: the flow crosses the channel more than once
    for q <- xs(r).indices do
      assert(xs(r)(q) >= 0.0 && xs(r)(q) < 64.0, s"tracer $q at ${xs(r)(q)}")
    val near = xs(r).count(_ < 16.0)
    assert(near > 30 && near < 150, s"$near of 300 in the first quarter")
  }

  test("tracers are part of the digest, and a run with them repeats exactly") {
    def go(shift: Double): Long =
      val r = run(params(FluidEdge.Box, 32, 32, lid = 1.0, viscosity = 0.3, tracers = 30))
      xs(r)(0) += shift
      r.runSteps(20)
      r.digest
    assertEquals(go(0.0), go(0.0))
    assertNotEquals(go(0.0), go(0.5))
  }

  test("the export lists the tracer positions after the fields") {
    val r = run(params(FluidEdge.Box, 16, 16, tracers = 7))
    val exported = FluidState.stateExport
    val slices = exported.layout(r.state)
    assertEquals(slices.map(_.name), Vector("dye", "vorticity", "speed", "wall", "x", "y"))
    val out = new Array[Double](exported.size(r.state))
    exported.copyInto(r.state, out)
    assertEquals(out.length, 4 * 256 + 14)
    assertEquals(out(4 * 256 + 3), xs(r)(3))
    assertEquals(out(4 * 256 + 7 + 3), ys(r)(3))
  }

  test("a step allocates nothing with tracers in a channel with a block") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val r = run(params(FluidEdge.Channel, 48, 24, viscosity = 0.2, inflow = 1.0, tracers = 500))
    for j <- 8 until 16; i <- 10 until 14 do r.state.wall(i, j) = true
    r.state.wallsChanged()
    val bytes = Allocation.perStep(300)(r.step())
    assert(bytes < 3000, s"$bytes bytes over 300 steps")
  }
