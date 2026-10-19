package physis.core.sweep

import java.util.concurrent.atomic.AtomicBoolean

import physis.core.{Probe, Stepping}
import physis.core.testing.{Decay, DecayParams, DecayState}

class SweepSuite extends munit.FunSuite:
  private val setup = SweepSetup[DecayParams, DecayState](
    Decay,
    _ => Vector(Probe[DecayState]("y")((s, _) => s.y)),
    Stepping.Fixed(0.01),
    until = 1.0,
    sampleEvery = 1.0
  )

  private val grid = ParamGrid
    .parse(Seq("k=0:2:5", "y0=1,2", "seed=1..3"), DecayParams.schema)
    .fold(e => fail(e), identity)

  private def results(threads: Int) =
    Sweep.run(setup, grid, threads)((run, _) => run.seed)

  test("30 points come back in grid order with the values the model gives") {
    val all = results(1)
    assertEquals(all.map(_.point.index), (0 until 30).toVector)
    assertEquals(all.size, 30)
    for r <- all do
      val out = r.outcome.fold(e => fail(e), identity)
      val k = r.point.values("k")
      val y0 = r.point.values("y0")
      assertEqualsDouble(out.finalProbes("y"), y0 * math.exp(-k), 1e-9)
      assertEquals(out.extra, r.point.seed.get)
      assertEqualsDouble(out.finalTime, 1.0, 1e-12)
      assertEquals(out.acceptedSteps, 100L)
  }

  test("1, 4 and 8 threads give identical results") {
    val one = results(1)
    assertEquals(results(4), one)
    assertEquals(results(8), one)
  }

  test("parameters and seed that the grid does not set come from the setup") {
    val own = setup.copy(params = Map("y0" -> 5.0), seed = 42L)
    val small = ParamGrid.parse(Seq("k=1,2"), DecayParams.schema).fold(e => fail(e), identity)
    val all = Sweep.run(own, small, 2)((run, _) => run.seed)
    for r <- all do
      val out = r.outcome.fold(e => fail(e), identity)
      assertEquals(out.extra, 42L)
      assertEqualsDouble(out.finalProbes("y"), 5.0 * math.exp(-r.point.values("k")), 1e-9)
  }

  test("a point that fails is reported and the others finish") {
    val all = Sweep.run(setup, grid, 4) { (run, _) =>
      if run.params.k > 1.5 then throw new IllegalStateException("k too large")
      run.seed
    }
    val failed = all.filter(_.outcome.isLeft)
    val good = all.filter(_.outcome.isRight)
    assertEquals(failed.size + good.size, 30)
    assert(failed.nonEmpty && good.nonEmpty)
    assert(failed.forall(_.point.values("k") > 1.5))
    assert(failed.forall(_.outcome.left.exists(_.contains("k too large"))))
  }

  test("a stop request leaves later points not run") {
    val stop = new AtomicBoolean(false)
    val all = Sweep.run(setup, grid, 1, stop) { (run, _) =>
      if run.params.k >= 0.5 then stop.set(true)
      run.seed
    }
    val ran = all.takeWhile(_.outcome.isRight)
    assert(ran.nonEmpty && ran.size < 30, s"ran ${ran.size}")
    assert(all.drop(ran.size).forall(_.outcome.left.exists(_.contains("stopped"))))
    assertEquals(ran.size + all.drop(ran.size).size, 30)
  }

  test("a sweep without axes is one run, and at least one thread is needed") {
    val one = Sweep.run(setup, ParamGrid.single)((_, _) => ())
    assertEquals(one.size, 1)
    assert(one.head.outcome.isRight)
    intercept[IllegalArgumentException](Sweep.run(setup, ParamGrid.single, 0)((_, _) => ()))
  }
