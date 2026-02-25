package physis.models.wave

import physis.core.run.{Disc, Manifest, ModelRegistry, Op}
import physis.core.run.Intervention.{Brush, Disturb, Pulse}
import WaveSupport.*

class WaveInterventionSuite extends munit.FunSuite:
  private val p = WaveParams.schema.defaults.copy(nx = 96, ny = 96, edge = Edge.Reflective)
  private val registry = ModelRegistry.of(Wave)

  test("a session with walls, pulses and a parameter change replays to the same digest") {
    val r = run(p.copy(damping = 0.01))
    val _ = r.intervene(Brush("wall", Disc(60.5, 40.5, 5.0), Op.Assign, 1.0))
    val _ = r.intervene(Pulse("u", 30.5, 40.5, 4.0, 1.0))
    r.runSteps(60)
    val _ = r.setParams(Map("c" -> 0.8, "amplitude" -> 0.7))
    val _ = r.intervene(Brush("u", Disc(30.0, 60.0, 6.0), Op.Add, -0.5))
    val _ = r.intervene(Brush("wall", Disc(60.5, 40.5, 2.0), Op.Erase, 0.0))
    r.runSteps(140)
    val manifest = r.manifest(r.time).copy(steps = Some(r.stepCount))
    assertEquals(manifest.interventions.size, 4)
    val text = Manifest.toJson(manifest)
    val replay = Manifest.fromJson(text).flatMap(Manifest.replay(_, registry))
    val result = replay.map(_.execute())
    assertEquals(result.map(_.finalDigest), Right(r.digest))
  }

  test("wave interventions refuse unknown targets and agents with a message") {
    val r = run(p)
    val unknown = r.intervene(Brush("v", Disc(10, 10, 3), Op.Assign, 1.0))
    assert(unknown.left.exists(_.contains("no field 'v'")), unknown.toString)
    val agents = r.intervene(Disturb(Disc(10, 10, 3), 0.5))
    assert(agents.left.exists(_.contains("no agents")), agents.toString)
    val pulse = r.intervene(Pulse("wall", 10, 10, 2, 1))
    assert(pulse.isLeft)
    r.step()
    assertEquals(r.interventions, Vector.empty)
  }

  test("a brush on u is added to both levels, so it starts at rest") {
    val r = run(p)
    val _ = r.intervene(Brush("u", Disc(40.5, 40.5, 3.0), Op.Assign, 0.5))
    r.step()
    // with no velocity the first step relaxes the bump's rim instead of launching it
    assertEquals(r.state.u(40, 40), 0.5)
    assertEqualsDouble(r.state.u(43, 40), 0.125, 1e-15)
    assertEquals(r.state.previous(43, 40), 0.5)
  }
