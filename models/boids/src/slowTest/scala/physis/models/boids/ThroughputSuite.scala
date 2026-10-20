package physis.models.boids

import physis.core.run.Physis
import physis.verify.SpeedReport

/** 2 000 boids must step fast enough for the Lab's 20x speed (1 200 steps/s needs more). */
class ThroughputSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  test("2 000 boids take at least 500 steps per second (best of 7 windows)") {
    val run = Physis
      .model(Boids)
      .stepping(Boids.stepping)
      .overrides(Map("n" -> 2000.0))
      .seed(1L)
      .build()
    run.runSteps(1000) // warm-up: class loading and JIT
    // A shared CI machine can slow down any one window (a median of 384 was seen once, against
    // over 500 in every other run); a real slowdown, such as an allocation in the neighbour
    // search, lowers all windows, so the best of them is what must stay above the bar.
    val rates = Array.fill(7) {
      val steps = 300
      val start = System.nanoTime()
      run.runSteps(steps)
      steps / ((System.nanoTime() - start) / 1e9)
    }
    val best = rates.max
    println(SpeedReport.record("boids 2000 steps", best, "steps/s"))
    assert(
      best >= 500.0,
      f"best $best%.0f steps per second (windows: ${rates.map(r => f"$r%.0f").mkString(", ")})"
    )
  }
