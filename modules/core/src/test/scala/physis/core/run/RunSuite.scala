package physis.core.run

import physis.core.{Probe, Stepping}
import physis.core.testing.{Decay, DecayParams, DecayState}

class RunSuite extends munit.FunSuite:
  private val y = Probe[DecayState]("y")((s, _) => s.y)
  private val params = DecayParams(k = 0.7, y0 = 2.0)

  private def decayRun(dt: Double) =
    Physis
      .model(Decay)
      .withParams(params)
      .stepping(Stepping.Fixed(dt))
      .probe(y)
      .sampleEvery(0.1)
      .build()

  test("samples land exactly on multiples of sampleEvery") {
    val result = decayRun(0.01).runUntil(1.0)
    assertEquals(result.times.size, 11)
    result.times.zipWithIndex.foreach { (t, k) => assertEquals(t, k * 0.1) }
    assertEquals(result.finalTime, 1.0)
  }

  test("fixed steps reproduce the exact solution at every sample") {
    val result = decayRun(0.01).runUntil(1.0)
    result.times.zip(result.column("y")).foreach { (t, v) =>
      assertEqualsDouble(v, Decay.exact(params, t), 1e-10)
    }
    assertEquals(result.acceptedSteps, 100L)
  }

  test("a step size that does not divide the sampling interval still lands on samples") {
    val result = decayRun(0.03).runUntil(1.0)
    assertEquals(result.times.size, 11)
    result.times.zipWithIndex.foreach { (t, k) => assertEquals(t, k * 0.1) }
    assertEqualsDouble(result.last("y"), Decay.exact(params, 1.0), 1e-10)
  }

  test("a final time between samples gets its own sample") {
    val result = decayRun(0.01).runUntil(0.25)
    assertEquals(result.times, Vector(0.0, 0.1, 0.2, 0.25))
  }

  test("runs can be resumed") {
    val run = decayRun(0.01)
    val first = run.runUntil(0.5)
    val second = run.runUntil(1.0)
    assertEquals(first.finalTime, 0.5)
    assertEquals(second.times.head, 0.5)
    assertEquals(second.finalTime, 1.0)
    assertEqualsDouble(second.last("y"), Decay.exact(params, 1.0), 1e-10)
  }

  test("running backwards is rejected") {
    val run = decayRun(0.01)
    run.advanceTo(1.0)
    intercept[IllegalArgumentException](run.advanceTo(0.5))
  }

  test("invalid definitions are rejected at build time") {
    intercept[IllegalArgumentException](
      Physis.model(Decay).stepping(Stepping.Fixed(-1.0)).build()
    )
    intercept[IllegalArgumentException](Physis.model(Decay).sampleEvery(0.0).build())
    intercept[IllegalArgumentException](Physis.model(Decay).probe(y, y).build())
  }

  test("named overrides go through the parameter schema") {
    val run = Physis.model(Decay).overrides(Map("k" -> 3.0)).build()
    assertEquals(run.params, DecayParams(k = 3.0, y0 = 1.0))
    intercept[IllegalArgumentException](Physis.model(Decay).overrides(Map("k" -> -1.0)))
  }

  test("identical definitions give identical digests") {
    assertEquals(decayRun(0.01).runUntil(1.0).finalDigest, decayRun(0.01).runUntil(1.0).finalDigest)
  }
