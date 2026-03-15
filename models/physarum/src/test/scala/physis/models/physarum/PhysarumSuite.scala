package physis.models.physarum

import physis.core.live.Slice
import physis.core.run.Physis
import physis.verify.Allocation

class PhysarumSuite extends munit.FunSuite:

  private def run(overrides: Map[String, Double] = Map.empty, seed: Long = 1L) =
    Physis
      .model(Physarum)
      .stepping(Physarum.stepping)
      .overrides(Map("n" -> 800.0, "nx" -> 64.0, "ny" -> 64.0) ++ overrides)
      .seed(seed)
      .build()

  test("defaults: 40 000 agents on a 256 x 256 grid") {
    val p = Physarum.params.defaults
    assertEquals((p.n, p.nx, p.ny), (40000, 256, 256))
    assertEquals((p.sensorAngle, p.sensorDist, p.turnAngle, p.stepSize), (0.7, 9.0, 0.6, 1.0))
  }

  test("agents, grid size need a restart; the rules do not") {
    assertEquals(
      Physarum.params.specs.filter(_.restart).map(_.name),
      Vector("n", "nx", "ny")
    )
    val r = run()
    assertEquals(r.setParams(Map("n" -> 900.0)), Left("parameter 'n' requires a restart"))
    assertEquals(r.setParams(Map("decay" -> 0.2, "sensorDist" -> 12.0)), Right(()))
  }

  test("a step must not outrun the sensors") {
    val r = run()
    val tooLong = r.setParams(Map("stepSize" -> 3.0, "sensorDist" -> 2.0))
    assert(tooLong.left.exists(_.contains("stepSize")), tooLong.toString)
    assertEquals(r.setParams(Map("stepSize" -> 2.0, "sensorDist" -> 2.0)), Right(()))
  }

  test("the initial state is inside the world with unit headings and no trail") {
    val s = run().state
    for i <- 0 until s.n do
      assert(s.x(i) >= 0.0 && s.x(i) < 64.0 && s.y(i) >= 0.0 && s.y(i) < 64.0)
      assertEqualsDouble(math.hypot(s.dx(i), s.dy(i)), 1.0, 1e-12)
    assertEquals(s.mass, 0.0)
  }

  test("agents stay in the world, headings stay unit length and the trail is never negative") {
    val r = run()
    for _ <- 1 to 5 do
      r.runSteps(60)
      val s = r.state
      for i <- 0 until s.n do
        assert(s.x(i) >= 0.0 && s.x(i) < 64.0 && s.y(i) >= 0.0 && s.y(i) < 64.0)
        assertEqualsDouble(math.hypot(s.dx(i), s.dy(i)), 1.0, 1e-9)
      assert(s.trail.min >= 0.0)
  }

  test("every step the trail changes by what is deposited, less what fades") {
    val r = run(Map("foodWeight" -> 0.0))
    val p = Physarum.params.defaults.copy(n = 800)
    for _ <- 1 to 60 do
      val before = r.state.mass
      r.step()
      val expected = (before + 800 * p.deposit) * (1.0 - p.decay)
      assertEqualsDouble(r.state.mass, expected, 1e-9 * expected)
  }

  test("the same seed gives the same run, another seed a different one") {
    val a = run(seed = 3L)
    val b = run(seed = 3L)
    val c = run(seed = 4L)
    a.runSteps(100)
    b.runSteps(100)
    c.runSteps(100)
    assertEquals(a.digest, b.digest)
    assertNotEquals(a.digest, c.digest)
  }

  test("the exported state is the trail, the food and four rows of agents") {
    val s = run().state
    val ex = PhysarumState.stateExport
    assertEquals(ex.size(s), 2 * 64 * 64 + 4 * 800)
    assertEquals(
      ex.layout(s),
      Vector(
        Slice("trail", 0, 64, 64),
        Slice("food", 4096, 64, 64),
        Slice("x", 8192, 800, 1),
        Slice("y", 8992, 800, 1),
        Slice("dx", 9792, 800, 1),
        Slice("dy", 10592, 800, 1)
      )
    )
  }

  test("food draws agents: with a food stripe the trail ends up on it") {
    val r = run(Map("foodWeight" -> 10.0, "n" -> 2000.0))
    val s = r.state
    for j <- 0 until 64; i <- 28 until 36 do s.food(i, j) = 10.0
    r.runSteps(300)
    var inside = 0.0
    var total = 0.0
    for j <- 0 until 64; i <- 0 until 64 do
      total += s.trail(i, j)
      if i >= 28 && i < 36 then inside += s.trail(i, j)
    // the stripe is an eighth of the world
    assert(inside / total > 0.3, s"${inside / total} of the trail on the stripe")
  }

  test("a step does not allocate") {
    assume(Allocation.supported)
    val r = run()
    val bytes = Allocation.perStep(1000)(r.step())
    assert(bytes < 1000L, s"allocated $bytes bytes in 1000 steps")
  }
