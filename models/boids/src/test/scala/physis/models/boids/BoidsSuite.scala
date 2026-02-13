package physis.models.boids

import physis.core.Stepping
import physis.core.live.Slice
import physis.core.run.Physis

class BoidsSuite extends munit.FunSuite:

  private def run(overrides: Map[String, Double] = Map.empty, seed: Long = 1L) =
    Physis
      .model(Boids)
      .stepping(Boids.stepping)
      .overrides(Map("n" -> 200.0, "width" -> 300.0, "height" -> 200.0) ++ overrides)
      .seed(seed)
      .build()

  test("defaults: 1 500 boids in a 1 200 x 800 world, balanced rules") {
    val p = Boids.params.defaults
    assertEquals((p.n, p.width, p.height), (1500, 1200.0, 800.0))
    assertEquals((p.wSeparation, p.wAlignment, p.wCohesion), (1.5, 1.0, 0.8))
    assertEquals((p.perception, p.separationDist), (40.0, 14.0))
  }

  test("every preset is valid for the schema") {
    for preset <- Boids.presets do
      val result = Boids.params.fromValues(preset.values)
      assert(result.isRight, s"${preset.name}: $result")
    assertEquals(Boids.presets.map(_.name), Vector("Flock", "Swarm", "Vicsek (disordered)"))
  }

  test("boid count and world size need a restart, the rules do not") {
    assertEquals(
      Boids.params.specs.filter(_.restart).map(_.name),
      Vector("n", "width", "height")
    )
    val r = run()
    assertEquals(r.setParams(Map("n" -> 300.0)), Left("parameter 'n' requires a restart"))
    assertEquals(r.setParams(Map("wCohesion" -> 2.0, "wander" -> 0.5)), Right(()))
  }

  test("minSpeed must not exceed maxSpeed") {
    val r = run()
    val result = r.setParams(Map("minSpeed" -> 5.0))
    assert(result.left.exists(_.contains("minSpeed")), result.toString)
    assertEquals(r.setParams(Map("minSpeed" -> 2.0, "maxSpeed" -> 5.0)), Right(()))
    intercept[IllegalArgumentException](run(Map("minSpeed" -> 6.0)))
  }

  test("the initial state is inside the world with speeds between the limits") {
    val s = run().state
    for i <- 0 until s.n do
      assert(s.x(i) >= 0.0 && s.x(i) < 300.0 && s.y(i) >= 0.0 && s.y(i) < 200.0)
      val speed = math.hypot(s.vx(i), s.vy(i))
      assert(speed >= 1.5 - 1e-12 && speed <= 4.0 + 1e-12, s"speed $speed")
  }

  test("speeds and positions stay within bounds for 500 steps, with every rule on") {
    val r = run(Map("wander" -> 0.5, "wCohesion" -> 2.0))
    for _ <- 1 to 5 do
      r.runSteps(100)
      val s = r.state
      for i <- 0 until s.n do
        assert(s.x(i) >= 0.0 && s.x(i) < 300.0, s"x ${s.x(i)}")
        assert(s.y(i) >= 0.0 && s.y(i) < 200.0, s"y ${s.y(i)}")
        val speed = math.hypot(s.vx(i), s.vy(i))
        assert(speed >= 1.5 - 1e-9 && speed <= 4.0 + 1e-9, s"speed $speed")
  }

  test("the result does not depend on the order of the boids") {
    val a = run(Map("wander" -> 0.0), seed = 5L)
    val b = run(Map("wander" -> 0.0), seed = 5L)
    val n = a.state.n
    val perm = new java.util.Random(9L).ints(0, n).distinct().limit(n).toArray
    assertEquals(perm.length, n)
    val sa = a.state
    val sb = b.state
    for k <- 0 until n do
      sb.x(k) = sa.x(perm(k))
      sb.y(k) = sa.y(perm(k))
      sb.vx(k) = sa.vx(perm(k))
      sb.vy(k) = sa.vy(perm(k))
    a.runSteps(25)
    b.runSteps(25)
    for k <- 0 until n do
      assertEqualsDouble(sb.x(k), sa.x(perm(k)), 1e-9)
      assertEqualsDouble(sb.y(k), sa.y(perm(k)), 1e-9)
      assertEqualsDouble(sb.vx(k), sa.vx(perm(k)), 1e-9)
      assertEqualsDouble(sb.vy(k), sa.vy(perm(k)), 1e-9)
  }

  test("a run is reproducible from its seed, and different seeds differ") {
    def digest(seed: Long) =
      val r = run(seed = seed)
      r.runSteps(50)
      r.digest
    assertEquals(digest(3L), digest(3L))
    assertNotEquals(digest(3L), digest(4L))
  }

  test("the random turn is a pure function of key, step and boid, uniform in [0, 1)") {
    assertEquals(Noise.uniform(1L, 2L, 3), Noise.uniform(1L, 2L, 3))
    assertNotEquals(Noise.uniform(1L, 2L, 3), Noise.uniform(1L, 2L, 4))
    assertNotEquals(Noise.uniform(1L, 2L, 3), Noise.uniform(1L, 3L, 3))
    val draws = (0 until 20000).map(i => Noise.uniform(7L, i / 100L, i % 100))
    assert(draws.forall(d => d >= 0.0 && d < 1.0))
    assertEqualsDouble(draws.sum / draws.size, 0.5, 0.01)
  }

  test("the series for the random turn match the library sine and cosine on [-1.5, 1.5]") {
    var worst = 0.0
    for k <- -1500 to 1500 do
      val a = k / 1000.0
      worst = math.max(worst, math.abs(Noise.sin(a) - StrictMath.sin(a)))
      worst = math.max(worst, math.abs(Noise.cos(a) - StrictMath.cos(a)))
    assert(worst < 1e-15, s"largest error $worst")
    assertEquals(Noise.sin(0.0), 0.0)
    assertEquals(Noise.cos(0.0), 1.0)
  }

  test("two boids alone: separation pushes them apart, cohesion pulls them together") {
    def gap(overrides: Map[String, Double]): Double =
      val r = Physis
        .model(Boids)
        .stepping(Boids.stepping)
        .overrides(Map("n" -> 2.0, "wander" -> 0.0) ++ overrides)
        .build()
      val s = r.state
      s.x(0) = 100.0; s.y(0) = 100.0; s.vx(0) = 0.0; s.vy(0) = 1.5
      s.x(1) = 108.0; s.y(1) = 100.0; s.vx(1) = 0.0; s.vy(1) = 1.5
      r.runSteps(5)
      math.abs(s.x(1) - s.x(0))
    val apart = gap(Map("wSeparation" -> 3.0, "wAlignment" -> 0.0, "wCohesion" -> 0.0))
    val together = gap(Map("wSeparation" -> 0.0, "wAlignment" -> 0.0, "wCohesion" -> 3.0))
    assert(apart > 9.0, s"gap with separation: $apart")
    assert(together < 7.0, s"gap with cohesion: $together")
  }

  test("probes: polarization is between 0 and 1, speeds and neighbour counts are plausible") {
    val result = Physis
      .model(Boids)
      .stepping(Boids.stepping)
      .overrides(Map("n" -> 200.0, "width" -> 300.0, "height" -> 200.0))
      .probe(Boids.probes*)
      .build()
      .runSteps(10)
    assertEquals(
      result.series.keys.toList,
      List("polarization", "mean_speed", "mean_neighbours")
    )
    val phi = result.last("polarization")
    assert(phi >= 0.0 && phi <= 1.0, s"polarization $phi")
    assert(result.last("mean_speed") >= 1.5 && result.last("mean_speed") <= 4.0)
    assert(result.last("mean_neighbours") > 1.0)
  }

  test("the snapshot export is four rows of n values with matching slices") {
    val r = run()
    r.runSteps(5)
    val exporter = BoidsState.stateExport
    val out = new Array[Double](exporter.size(r.state))
    exporter.copyInto(r.state, out)
    assertEquals(
      exporter.layout(r.state),
      Vector(
        Slice("x", 0, 200, 1),
        Slice("y", 200, 200, 1),
        Slice("vx", 400, 200, 1),
        Slice("vy", 600, 200, 1)
      )
    )
    assertEquals(out(7), r.state.x(7))
    assertEquals(out(200 + 7), r.state.y(7))
    assertEquals(out(400 + 7), r.state.vx(7))
    assertEquals(out(600 + 7), r.state.vy(7))
  }

  test("a perception change builds a new grid and the run carries on") {
    val r = run()
    r.runSteps(10)
    assertEquals(r.setParams(Map("perception" -> 60.0)), Right(()))
    r.runSteps(10)
    assertEquals(r.params.perception, 60.0)
    assertEquals(r.stepCount, 20L)
  }

  test("the model uses the fixed step of 1/60") {
    assertEquals(Boids.stepping, Stepping.Fixed(1.0 / 60.0))
  }
