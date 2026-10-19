package physis.models.grayscott

import physis.core.run.{Disc, Manifest, ModelRegistry, Op, Physis}
import physis.core.run.Intervention.{Brush, Disturb, Pulse}

class GrayScottBrushSuite extends munit.FunSuite:
  private def build() =
    Physis
      .model(GrayScott)
      .stepping(GrayScott.stepping)
      .overrides(Map("nx" -> 96.0, "ny" -> 96.0, "noise" -> 0.0))
      .probe(GrayScott.probes*)
      .build()

  private def inRange(state: GrayScottState): Boolean =
    state.u.min >= 0.0 && state.u.max <= 1.0 && state.v.min >= 0.0 && state.v.max <= 1.0

  test("strokes keep u and v within [0, 1], whatever the value") {
    val r = build()
    val state = r.state
    val ops = Seq(Op.Assign, Op.Add, Op.Erase)
    for (op, k) <- ops.zipWithIndex; value <- Seq(-5.0, 0.3, 7.0) do
      val u = Brush("u", Disc(20.0 + 10 * k, 30.0, 9.0), op, value)
      val v = Brush("v", Disc(60.0, 20.0 + 10 * k, 9.0), op, value)
      assertEquals(GrayScott.intervene(state, r.params, u), Right(()))
      assertEquals(GrayScott.intervene(state, r.params, v), Right(()))
      assert(inRange(state), s"after $op $value")
    // queued through the run, the same strokes are recorded
    for (op, k) <- ops.zipWithIndex; value <- Seq(-5.0, 0.3, 7.0) do
      val _ = r.intervene(Brush("u", Disc(20.0 + 10 * k, 30.0, 9.0), op, value))
      val _ = r.intervene(Brush("v", Disc(60.0, 20.0 + 10 * k, 9.0), op, value))
    r.step()
    assertEquals(r.interventions.size, 18)
  }

  test("a stroke sets, adds and erases inside the disc only") {
    val r = build()
    val _ = r.intervene(Brush("v", Disc(10.5, 10.5, 3.0), Op.Assign, 0.6))
    val _ = r.intervene(Brush("v", Disc(10.5, 10.5, 1.0), Op.Add, 0.3))
    val _ = r.intervene(Brush("u", Disc(10.5, 10.5, 1.0), Op.Erase, 0.0))
    r.step()
    // after one step the changes are still local: far cells are untouched
    assertEquals(r.state.v(70, 70), 0.0)
    assertEquals(r.state.u(70, 70), 1.0)
    assert(r.state.v(10, 10) > 0.7 && r.state.v(10, 10) <= 1.0)
    assert(r.state.u(10, 10) < 0.3)
  }

  test("a stroke into a dead field seeds a pattern") {
    val r = build()
    val centre = Disc(48.0, 48.0, 60.0)
    val _ = r.intervene(Brush("v", centre, Op.Erase, 0.0))
    val _ = r.intervene(Brush("u", centre, Op.Assign, 1.0))
    r.step()
    assertEquals(r.state.v.max, 0.0)
    assertEquals(r.state.coverage(0.2), 0.0)
    val _ = r.intervene(Brush("u", Disc(30.0, 30.0, 6.0), Op.Assign, 0.5))
    val _ = r.intervene(Brush("v", Disc(30.0, 30.0, 6.0), Op.Assign, 0.25))
    r.runSteps(2000)
    assert(r.state.coverage(0.2) > 0.0)
    assert(inRange(r.state))
  }

  test("strokes wrap around the periodic edges") {
    val r = build()
    val _ = r.intervene(Brush("v", Disc(0.0, 48.5, 3.0), Op.Assign, 0.9))
    r.step()
    assert(r.state.v(95, 48) > 0.5 && r.state.v(0, 48) > 0.5)
  }

  test("a session with strokes replays to the same digest") {
    val r = build()
    val _ = r.intervene(Brush("v", Disc(40.0, 40.0, 5.0), Op.Assign, 0.7))
    r.runSteps(120)
    val _ = r.setParams(Map("F" -> 0.0545, "k" -> 0.0620))
    val _ = r.intervene(Brush("u", Disc(60.0, 20.0, 8.0), Op.Add, -0.4))
    r.runSteps(180)
    val manifest = r.manifest(r.time).copy(steps = Some(r.stepCount))
    val replay = Manifest
      .fromJson(Manifest.toJson(manifest))
      .flatMap(Manifest.replay(_, ModelRegistry.of(GrayScott)))
    assertEquals(replay.map(_.execute().finalDigest), Right(r.digest))
  }

  test("other interventions are refused with a message") {
    val r = build()
    assert(r.intervene(Brush("w", Disc(1, 1, 2), Op.Assign, 1.0)).left.exists(_.contains("'w'")))
    assert(r.intervene(Pulse("u", 1, 1, 2, 1)).isLeft)
    assert(r.intervene(Disturb(Disc(1, 1, 2), 0.3)).isLeft)
  }
