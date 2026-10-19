package physis.models.boids

import physis.core.run.Physis

/** 2 000 boids must step fast enough for the Lab's 20x speed (1 200 steps/s needs more). */
class ThroughputSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  test("2 000 boids take at least 500 steps per second (median of 5)") {
    val run = Physis
      .model(Boids)
      .stepping(Boids.stepping)
      .overrides(Map("n" -> 2000.0))
      .seed(1L)
      .build()
    run.runSteps(300) // warm-up: class loading and JIT
    val rates = Array.fill(5) {
      val steps = 300
      val start = System.nanoTime()
      run.runSteps(steps)
      steps / ((System.nanoTime() - start) / 1e9)
    }.sorted
    val median = rates(2)
    assert(median >= 500.0, f"median $median%.0f steps per second")
  }
