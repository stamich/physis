package physis.models.grayscott

import physis.core.sweep.{ParamGrid, Sweep, SweepSetup}
import physis.verify.SpeedReport

/**
 * Sweeps share nothing, so four threads should be much faster than one. The bar is 1.5: a
 * 4-processor CI runner shares its cores and memory bandwidth and measured 2.05 and 2.07 (a
 * development machine with four free cores gets over 3), while threads that wait for each other
 * give about 1. The check is skipped with fewer than four processors.
 */
class SweepScalingSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def seconds(grid: ParamGrid, threads: Int): (Double, Vector[Long]) =
    val setup = SweepSetup[GrayScottParams, GrayScottState](
      GrayScott,
      _ => GrayScott.probes,
      GrayScott.stepping,
      until = 600.0,
      sampleEvery = 600.0,
      params = Map("nx" -> 128.0, "ny" -> 128.0),
      seed = 1L
    )
    val start = System.nanoTime()
    val results = Sweep.run(setup, grid, threads)((_, _) => ())
    val elapsed = (System.nanoTime() - start) / 1e9
    (elapsed, results.map(_.outcome.fold(e => fail(e), _.digest)))

  test("4 threads are at least 1.5 times faster than 1, with the same results") {
    assume(Runtime.getRuntime.availableProcessors >= 4, "needs 4 processors")
    val grid = ParamGrid
      .parse(Seq("F=0.02:0.06:8"), GrayScott.params)
      .fold(e => fail(e), identity)
    val _ = seconds(grid, 4) // warm-up
    val (one, digests1) = seconds(grid, 1)
    val (four, digests4) = seconds(grid, 4)
    println(f"sweep: 1 thread $one%.2f s, 4 threads $four%.2f s")
    println(SpeedReport.record("sweep scaling 4 threads", one / four, "times"))
    assertEquals(digests4, digests1)
    assert(one / four >= 1.5, f"only ${one / four}%.2f times faster")
  }
