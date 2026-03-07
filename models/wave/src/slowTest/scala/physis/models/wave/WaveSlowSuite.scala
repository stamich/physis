package physis.models.wave

import physis.core.run.{Disc, Intervention, Op, Physis}

/** Speed and long-run behaviour of the wave model at the Lab's grid size, 256 x 256. */
class WaveSlowSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def run(edge: Int, amplitude: Double = 0.0) =
    Physis
      .model(Wave)
      .withParams(
        WaveParams(
          c = 1.0,
          damping = 0.0,
          edge = edge,
          sponge = 24,
          frequency = 0.2,
          amplitude = amplitude,
          nx = 256,
          ny = 256
        )
      )
      .stepping(Wave.stepping)
      .build()

  test("the wave takes at least 40 million cell updates per second (median of 5)") {
    val r = run(Edge.Reflective, amplitude = 0.5)
    val pulse = Intervention.Pulse("u", 128.0, 128.0, 6.0, 1.0)
    assertEquals(r.intervene(pulse), Right(()))
    r.runSteps(300) // warm-up: class loading and JIT
    val cells = 256.0 * 256.0
    val rates = Array.fill(5) {
      val steps = 400
      val start = System.nanoTime()
      r.runSteps(steps)
      steps * cells / ((System.nanoTime() - start) / 1e9)
    }.sorted
    val median = rates(2)
    assert(median >= 40e6, f"median ${median / 1e6}%.1f million cell updates per second")
  }

  test("one brush dab on 256 x 256 takes under 0.2 ms (median)") {
    val r = run(Edge.Reflective)
    val params = r.params
    val state = r.state
    val wall = Intervention.Brush("wall", Disc(0.0, 0.0, 8.0), Op.Assign, 1.0)
    val bump = Intervention.Pulse("u", 100.0, 100.0, 4.0, 0.1)
    for _ <- 1 to 3000 do // warm-up
      val _ = Wave.intervene(state, params, wall)
      val _ = Wave.intervene(state, params, bump)
    val n = 2001
    val times = Array.tabulate(n) { k =>
      val iv =
        if k % 3 == 0 then bump
        else if k % 3 == 1 then
          Intervention.Brush("wall", Disc(20.0 + k % 200, 100.0, 8.0), Op.Assign, 1.0)
        else Intervention.Brush("wall", Disc(20.0 + k % 200, 100.0, 8.0), Op.Erase, 0.0)
      val start = System.nanoTime()
      val _ = Wave.intervene(state, params, iv)
      (System.nanoTime() - start) / 1e6
    }.sorted
    val median = times(n / 2)
    assert(median < 0.2, f"median $median%.4f ms per dab")
  }

  test("an absorbing edge swallows a pulse; a reflective one keeps all its energy") {
    def retained(edge: Int): Double =
      val r = run(edge)
      assertEquals(r.intervene(Intervention.Pulse("u", 128.0, 128.0, 6.0, 1.0)), Right(()))
      r.runSteps(1)
      val e0 = r.state.energy
      r.runSteps(700)
      r.state.energy / e0
    val closed = retained(Edge.Reflective)
    val open = retained(Edge.Absorbing)
    assert(math.abs(closed - 1.0) < 1e-3, s"reflective kept $closed")
    assert(open < 0.05, s"absorbing kept $open")
  }
