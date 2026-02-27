package physis.models.boids

import physis.core.run.{Disc, Manifest, ModelRegistry, Op, Physis}
import physis.core.run.Intervention.{Brush, Disturb}

class BoidsDisturbSuite extends munit.FunSuite:
  private val params = Boids.params.defaults.copy(n = 400)

  private def build() =
    Physis.model(Boids).withParams(params).stepping(Boids.stepping).seed(5L).build()

  private def place(s: BoidsState, i: Int, x: Double, y: Double, vx: Double, vy: Double): Unit =
    s.x(i) = x
    s.y(i) = y
    s.vx(i) = vx
    s.vy(i) = vy

  private def disturb(s: BoidsState, centre: (Double, Double), r: Double, strength: Double) =
    Boids.intervene(s, params, Disturb(Disc(centre._1, centre._2, r), strength))

  test("headings inside the disc turn away from its centre by the stated angle") {
    val s = build().state
    place(s, 0, 110.0, 100.0, 0.0, 1.0) // right of the centre, heading up
    place(s, 1, 90.0, 100.0, 0.0, 1.0) // left of the centre, heading up
    place(s, 2, 110.0, 100.0, 2.0, 0.0) // already pointing away
    place(s, 3, 110.0, 100.0, -1.0, 0.0) // heading straight in
    assertEquals(disturb(s, (100.0, 100.0), 20.0, 0.5), Right(()))
    assertEqualsDouble(s.vx(0), math.sin(0.5), 1e-14)
    assertEqualsDouble(s.vy(0), math.cos(0.5), 1e-14)
    assertEqualsDouble(s.vx(1), -math.sin(0.5), 1e-14)
    assertEqualsDouble(s.vy(1), math.cos(0.5), 1e-14)
    assertEquals(s.vx(2), 2.0)
    assertEquals(s.vy(2), 0.0)
    assertEqualsDouble(s.vx(3), -math.cos(0.5), 1e-14)
    assertEqualsDouble(s.vy(3), -math.sin(0.5), 1e-14)
  }

  test("a heading closer than the turn is set onto the outward direction; speed is kept") {
    val s = build().state
    place(s, 0, 100.0, 120.0, 0.1, 3.0)
    val speed = math.hypot(0.1, 3.0)
    disturb(s, (100.0, 100.0), 30.0, 1.0)
    assertEqualsDouble(s.vx(0), 0.0, 1e-14)
    assertEqualsDouble(s.vy(0), speed, 1e-14)
    place(s, 1, 105.0, 100.0, 1.0, 1.0)
    disturb(s, (100.0, 100.0), 30.0, 0.2)
    assertEqualsDouble(math.hypot(s.vx(1), s.vy(1)), math.sqrt(2.0), 1e-14)
  }

  test("only boids inside the disc change, distance wraps around the world") {
    val r = build()
    val s = r.state
    place(s, 0, 5.0, 100.0, 0.0, 1.0)
    place(s, 1, params.width - 5.0, 100.0, 0.0, 1.0)
    place(s, 2, 300.0, 300.0, 1.0, 0.0)
    val before = (0 until params.n).map(i => (s.vx(i), s.vy(i)))
    disturb(s, (0.0, 100.0), 12.0, 0.4)
    assert((s.vx(0), s.vy(0)) != before(0))
    assert((s.vx(1), s.vy(1)) != before(1))
    for i <- 0 until params.n if i != 0 && i != 1 do
      val inside =
        var dx = s.x(i) - 0.0
        var dy = s.y(i) - 100.0
        dx -= params.width * math.rint(dx / params.width)
        dy -= params.height * math.rint(dy / params.height)
        dx * dx + dy * dy <= 144.0
      if !inside then assertEquals((s.vx(i), s.vy(i)), before(i), s"boid $i")
  }

  test("a boid at the exact centre is left alone") {
    val s = build().state
    place(s, 0, 50.0, 50.0, 1.0, 2.0)
    disturb(s, (50.0, 50.0), 5.0, 0.5)
    assertEquals((s.vx(0), s.vy(0)), (1.0, 2.0))
  }

  test("strength outside (0, 2] and anything but Disturb are refused with a message") {
    val r = build()
    assert(r.intervene(Disturb(Disc(1, 1, 5), 0.0)).left.exists(_.contains("strength")))
    assert(r.intervene(Disturb(Disc(1, 1, 5), 2.5)).left.exists(_.contains("strength")))
    assert(r.intervene(Brush(
      "x",
      Disc(1, 1, 5),
      Op.Assign,
      1.0
    )).left.exists(_.contains("only take")))
    assertEquals(r.intervene(Disturb(Disc(1, 1, 5), 2.0)), Right(()))
  }

  test("a session with disturbances replays to the same digest") {
    val r = build()
    r.runSteps(30)
    val _ = r.intervene(Disturb(Disc(600.0, 400.0, 150.0), 1.2))
    r.runSteps(20)
    val _ = r.setParams(Map("wander" -> 0.4))
    val _ = r.intervene(Disturb(Disc(100.0, 700.0, 90.0), 0.7))
    r.runSteps(50)
    val manifest = r.manifest(r.time).copy(steps = Some(r.stepCount))
    val replay = Manifest
      .fromJson(Manifest.toJson(manifest))
      .flatMap(Manifest.replay(_, ModelRegistry.of(Boids)))
    assertEquals(replay.map(_.execute().finalDigest), Right(r.digest))
    assertEquals(manifest.interventions.size, 2)
  }
