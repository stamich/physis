package physis.models.shallow

import physis.core.run.{Disc, Intervention, Manifest, ModelRegistry, Op}
import physis.core.run.Intervention.{Brush, Disturb, Pulse}
import ShallowSupport.*

class ShallowInterventionSuite extends munit.FunSuite:
  private val registry = ModelRegistry.of(Shallow)

  /** Applies a stroke to the state at once (a run applies it at the start of its next step). */
  private def now(r: ShallowRun, iv: Intervention): Either[String, Unit] =
    Shallow.intervene(r.state, r.params, iv)

  test("only land and height can be painted") {
    assert(Shallow.accepts(Brush("land", Disc(1, 1, 1), Op.Assign, 1.0)).isRight)
    assert(Shallow.accepts(Brush("height", Disc(1, 1, 1), Op.Add, 0.5)).isRight)
    assert(Shallow.accepts(Pulse("height", 1, 1, 2.0, 0.3)).isRight)
    val bad = Shallow.accepts(Brush("dye", Disc(1, 1, 1), Op.Assign, 1.0))
    assert(bad.left.exists(_.contains("no field 'dye'")), bad.toString)
    assert(Shallow.accepts(Pulse("u", 1, 1, 2.0, 0.3)).isLeft)
    assert(Shallow.accepts(Disturb(Disc(1, 1, 1), 0.5)).isLeft)
  }

  test("land takes the water of its cells away and closes the faces round it") {
    val r = run(params(nx = 40, ny = 40))
    hump(r.state, 20.0, 20.0, 5.0, 1.0)
    val before = volume(r.state)
    assertEquals(now(r, Brush("land", Disc(20.0, 20.0, 4.0), Op.Assign, 1.0)), Right(()))
    val s = r.state
    assert(s.land(20, 20))
    assertEquals(s.eta(20, 20), 0.0)
    assert(volume(s) < before)
    assertEquals(s.openU(20, 20), 0.0)
    assertEquals(s.openV(20, 20), 0.0)
    r.runSteps(100)
    assertEquals(s.eta(20, 20), 0.0)
    assertEquals(s.u(21, 20), 0.0)
    assertEquals(s.v(20, 21), 0.0)
  }

  test("land is a wall: the volume of the sea cells does not change once it is painted") {
    val r = run(params(f0 = 1e-4, nx = 50, ny = 40))
    assertEquals(now(r, Brush("land", Disc(25.0, 20.0, 3.0), Op.Assign, 1.0)), Right(()))
    hump(r.state, 10.0, 20.0, 3.0, 1.0)
    r.state.clearLand()
    val before = volume(r.state)
    r.runSteps(400)
    assertEqualsDouble(volume(r.state), before, 1e-11 * before)
  }

  test("erasing land makes sea again: the cells are level and the faces open") {
    val r = run(params(nx = 40, ny = 40))
    val _ = now(r, Brush("land", Disc(20.0, 20.0, 4.0), Op.Assign, 1.0))
    val _ = now(r, Brush("land", Disc(24.0, 20.0, 3.0), Op.Erase, 0.0))
    val s = r.state
    assert(!s.land(22, 20), "erased")
    assert(s.land(20, 20), "left alone")
    assertEquals(s.openU(22, 20), 1.0)
    assertEquals(s.eta(22, 20), 0.0)
    hump(s, 10.0, 20.0, 2.0, 1.0)
    s.clearLand()
    r.runSteps(200)
    assert(math.abs(s.eta(22, 20)) > 0.0, "the water reaches the cleared cells")
    assertEquals(s.eta(20, 20), 0.0)
  }

  test("a height brush sets the surface and a pulse adds a Gaussian") {
    val r = run(params(nx = 40, ny = 40))
    assertEquals(now(r, Brush("height", Disc(20.0, 20.0, 3.0), Op.Assign, 2.5)), Right(()))
    assertEquals(r.state.eta(20, 20), 2.5)
    assertEquals(r.state.eta(5, 5), 0.0)
    val _ = now(r, Pulse("height", 10.5, 10.5, 2.0, 1.0))
    assertEqualsDouble(r.state.eta(10, 10), 1.0, 1e-12)
    assertEqualsDouble(r.state.eta(11, 10), math.exp(-1.0 / 8.0), 1e-12)
    val _ = now(r, Pulse("height", 30.5, 30.5, 2.0, -1.0))
    assert(r.state.eta(30, 30) < -0.9)
  }

  test("height painted onto land is dropped") {
    val r = run(params(nx = 40, ny = 40))
    val _ = now(r, Brush("land", Disc(20.0, 20.0, 4.0), Op.Assign, 1.0))
    val _ = now(r, Pulse("height", 20.0, 20.0, 3.0, 1.0))
    assertEquals(r.state.eta(20, 20), 0.0)
    assert(r.state.eta(20, 26) > 0.0)
  }

  test("a channel's pulse wraps round the side") {
    val r = run(params(edges = Shallow.Channel, nx = 40, ny = 30))
    val _ = now(r, Pulse("height", 0.0, 15.0, 2.0, 1.0))
    assert(r.state.eta(39, 15) > 0.5)
    assert(r.state.eta(0, 15) > 0.5)
  }

  test("the model refuses what it has no field for, with a message") {
    val r = run(params())
    val field = r.intervene(Brush("u", Disc(10, 10, 3), Op.Assign, 1.0))
    assert(field.left.exists(_.contains("no field 'u'")), field.toString)
    r.step()
    assertEquals(r.interventions, Vector.empty)
  }

  test("a session of strokes and a parameter change replays to the same digest") {
    val r = run(params(f0 = 1e-4, edges = Shallow.OpenSea, nonlinear = 1, nx = 64, ny = 48))
    val _ = r.intervene(Brush("land", Disc(40.0, 24.0, 4.0), Op.Assign, 1.0))
    val _ = r.intervene(Pulse("height", 20.0, 24.0, 3.0, 2.0))
    r.runSteps(40)
    val _ = r.setParams(Map("f0" -> 5e-5, "friction" -> 1e-5))
    val _ = r.intervene(Pulse("height", 30.0, 10.0, 3.0, -1.0))
    val _ = r.intervene(Brush("land", Disc(40.0, 24.0, 2.0), Op.Erase, 0.0))
    r.runSteps(60)
    val manifest = r.manifest(r.time).copy(steps = Some(r.stepCount))
    assertEquals(manifest.interventions.size, 4)
    val text = Manifest.toJson(manifest)
    val replay = Manifest.fromJson(text).flatMap(Manifest.replay(_, registry))
    assertEquals(replay.map(_.execute().finalDigest), Right(r.digest))
  }
