package physis.models.physarum

import physis.core.run.{Disc, Manifest, ModelRegistry, Op, Physis}
import physis.core.run.Intervention.{Brush, Disturb, Pulse}

class PhysarumInterventionSuite extends munit.FunSuite:

  private def run(overrides: Map[String, Double] = Map.empty, seed: Long = 1L) =
    Physis
      .model(Physarum)
      .stepping(Physarum.stepping)
      .overrides(Map("n" -> 800.0, "nx" -> 64.0, "ny" -> 64.0) ++ overrides)
      .seed(seed)
      .build()

  test("a session with food, trail and a parameter change replays to the same digest") {
    val r = run()
    val _ = r.intervene(Brush("food", Disc(20.5, 30.5, 6.0), Op.Assign, 2.0))
    val _ = r.intervene(Brush("trail", Disc(50.0, 10.0, 4.0), Op.Add, 30.0))
    r.runSteps(40)
    val _ = r.setParams(Map("decay" -> 0.2, "foodWeight" -> 4.0))
    val _ = r.intervene(Pulse("food", 40.0, 40.0, 3.0, 5.0))
    val _ = r.intervene(Brush("food", Disc(20.5, 30.5, 3.0), Op.Erase, 0.0))
    r.runSteps(60)
    val manifest = r.manifest(r.time).copy(steps = Some(r.stepCount))
    assertEquals(manifest.interventions.size, 4)
    val text = Manifest.toJson(manifest)
    val replay = Manifest.fromJson(text).flatMap(Manifest.replay(_, ModelRegistry.of(Physarum)))
    assertEquals(replay.map(_.execute().finalDigest), Right(r.digest))
  }

  test("unknown targets, agents and negative pulses are refused with a message") {
    val r = run()
    val unknown = r.intervene(Brush("u", Disc(10, 10, 3), Op.Assign, 1.0))
    assert(unknown.left.exists(_.contains("no field 'u'")), unknown.toString)
    val agents = r.intervene(Disturb(Disc(10, 10, 3), 0.5))
    assert(agents.left.exists(_.contains("not disturbed")), agents.toString)
    val negative = r.intervene(Pulse("trail", 10, 10, 2, -1))
    assert(negative.left.exists(_.contains("negative")), negative.toString)
    val badPulse = r.intervene(Pulse("wall", 10, 10, 2, 1))
    assert(badPulse.left.exists(_.contains("no field 'wall'")), badPulse.toString)
    r.step()
    assertEquals(r.interventions, Vector.empty)
  }

  test("food and trail never go below zero, whatever the stroke") {
    val r = run()
    r.runSteps(10)
    val _ = r.intervene(Brush("trail", Disc(32.0, 32.0, 40.0), Op.Add, -1000.0))
    val _ = r.intervene(Brush("food", Disc(32.0, 32.0, 10.0), Op.Assign, -3.0))
    r.step()
    assert(r.state.trail.min >= 0.0)
    assert(r.state.food.min >= 0.0)
  }

  test("a food stroke lands in the food, also across the seam") {
    val r = run(Map("n" -> 10.0))
    val _ = r.intervene(Brush("food", Disc(0.0, 0.0, 3.0), Op.Assign, 1.0))
    r.step()
    val s = r.state
    assertEquals(s.food(0, 0), 1.0)
    assertEquals(s.food(63, 63), 1.0)
    assertEquals(s.food(32, 32), 0.0)
  }
