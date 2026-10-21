package physis.models.fluid

import physis.core.run.{Disc, Intervention, Manifest, ModelRegistry, Op}
import physis.core.run.Intervention.{Brush, Disturb, Pulse}
import FluidSupport.*

class FluidInterventionSuite extends munit.FunSuite:
  private val registry = ModelRegistry.of(Fluid)

  /** Applies a stroke to the state at once (a run applies it at the start of its next step). */
  private def now(r: FluidRun, iv: Intervention): Either[String, Unit] =
    Fluid.intervene(r.state, r.params, iv)

  test("a wall brush makes walls: no flow through them, no dye in them") {
    val r = run(params(FluidEdge.Box, 40, 40, viscosity = 0.1, lid = 1.0))
    dyeSquare(r.state, 15, 15, 8)
    assertEquals(now(r, Brush("wall", Disc(20.0, 20.0, 4.0), Op.Assign, 1.0)), Right(()))
    assert(r.state.wall(20, 20))
    assertEquals(r.state.dye(20, 20), 0.0)
    r.runSteps(10)
    assertEquals(r.state.dye(20, 20), 0.0)
    assert(Fluid.maxDivergence.measure(r.state, 0.0) < 1e-6)
    // the faces in and round the disc are closed
    assertEquals(r.state.u(16, 20), 0.0)
    assertEquals(r.state.u(20, 20), 0.0)
    assertEquals(r.state.v(20, 20), 0.0)
  }

  test("erasing a wall frees the cells again") {
    val r = run(params(FluidEdge.Box, 40, 40))
    val _ = now(r, Brush("wall", Disc(20.0, 20.0, 5.0), Op.Assign, 1.0))
    assert(r.state.wall.count > 30)
    val _ = now(r, Brush("wall", Disc(20.0, 20.0, 6.0), Op.Erase, 0.0))
    assertEquals(r.state.wall.count, 0)
    r.runSteps(3)
    assertEquals(r.state.projection.poisson.activeCells, 40 * 40)
  }

  test("a dye brush sets, adds and erases, and never puts dye inside a wall") {
    val r = run(params(FluidEdge.Box, 40, 40))
    val _ = now(r, Brush("wall", Disc(10.0, 10.0, 3.0), Op.Assign, 1.0))
    val _ = now(r, Brush("dye", Disc(20.0, 20.0, 3.0), Op.Assign, 0.5))
    assertEquals(r.state.dye(20, 20), 0.5)
    val _ = now(r, Brush("dye", Disc(20.0, 20.0, 3.0), Op.Add, 0.25))
    assertEquals(r.state.dye(20, 20), 0.75)
    val _ = now(r, Brush("dye", Disc(10.0, 10.0, 5.0), Op.Assign, 1.0))
    assertEquals(r.state.dye(10, 10), 0.0)
    assert(r.state.dye(14, 10) == 1.0 || r.state.wall(14, 10))
    val _ = now(r, Brush("dye", Disc(20.0, 20.0, 5.0), Op.Erase, 0.0))
    assertEquals(r.state.dye(20, 20), 0.0)
  }

  test("a velocity impulse stirs the fluid, and the projection leaves a swirl without divergence") {
    val r = run(params(FluidEdge.Box, 48, 48, viscosity = 0.05))
    assertEquals(Fluid.kineticEnergy.measure(r.state, 0.0), 0.0)
    assertEquals(now(r, Pulse("u", 24.0, 24.0, 4.0, 1.0)), Right(()))
    assert(Fluid.kineticEnergy.measure(r.state, 0.0) > 1.0)
    assertEqualsDouble(r.state.u(24, 24), math.exp(-0.25 / 32.0), 1e-12)
    r.step()
    assert(Fluid.maxDivergence.measure(r.state, 0.0) < 1e-6)
    assert(Fluid.kineticEnergy.measure(r.state, 0.0) > 0.1)
  }

  test("a vertical impulse and a dye puff land where they were put") {
    val r = run(params(FluidEdge.Periodic, 32, 32))
    val _ = now(r, Pulse("v", 8.0, 1.0, 2.0, 0.5)) // near the bottom: it wraps round the top
    assertEquals(r.state.v(7, 1), r.state.v(8, 1)) // symmetric about x = 8
    assertEqualsDouble(r.state.v(8, 1), 0.5 * math.exp(-0.25 / 8.0), 1e-12)
    assert(r.state.v(8, 30) > 0.0, "the impulse wraps to the top")
    assertEquals(r.state.v(8, 32), r.state.v(8, 0), "periodic faces stay equal")
    val _ = now(r, Pulse("dye", 24.0, 24.0, 2.0, 1.0))
    assertEqualsDouble(Fluid.dyeMass.measure(r.state, 0.0), 2 * math.Pi * 4.0, 0.05)
  }

  test("the fluid refuses what it has no field for, with a message") {
    val r = run(params(FluidEdge.Box, 32, 32))
    val field = r.intervene(Brush("u", Disc(10, 10, 3), Op.Assign, 1.0))
    assert(field.left.exists(_.contains("no field 'u'")), field.toString)
    val pulse = r.intervene(Pulse("wall", 10, 10, 2, 1))
    assert(pulse.left.exists(_.contains("no field 'wall'")), pulse.toString)
    val agents = r.intervene(Disturb(Disc(10, 10, 3), 0.5))
    assert(agents.left.exists(_.contains("no agents")), agents.toString)
    r.step()
    assertEquals(r.interventions, Vector.empty)
  }

  test("a session of strokes and a parameter change replays to the same digest") {
    val r = run(params(FluidEdge.Channel, 64, 32, viscosity = 0.1, inflow = 1.0))
    val _ = r.intervene(Brush("wall", Disc(20.0, 16.0, 4.0), Op.Assign, 1.0))
    val _ = r.intervene(Brush("dye", Disc(8.0, 16.0, 3.0), Op.Assign, 1.0))
    r.runSteps(40)
    val _ = r.setParams(Map("viscosity" -> 0.05, "buoyancy" -> 0.5))
    val _ = r.intervene(Pulse("v", 30.0, 16.0, 3.0, 0.4))
    val _ = r.intervene(Brush("wall", Disc(20.0, 16.0, 2.0), Op.Erase, 0.0))
    r.runSteps(60)
    val manifest = r.manifest(r.time).copy(steps = Some(r.stepCount))
    assertEquals(manifest.interventions.size, 4)
    val text = Manifest.toJson(manifest)
    val replay = Manifest.fromJson(text).flatMap(Manifest.replay(_, registry))
    assertEquals(replay.map(_.execute().finalDigest), Right(r.digest))
  }

  test("the same stroke at another step gives another flow") {
    def go(at: Int): Long =
      val r = run(params(FluidEdge.Box, 32, 32, viscosity = 0.1, lid = 1.0))
      r.runSteps(at)
      val _ = r.intervene(Pulse("u", 16.0, 16.0, 3.0, 1.0))
      r.runSteps(20 - at)
      r.digest
    assertNotEquals(go(5), go(6))
    assertEquals(go(5), go(5))
  }
