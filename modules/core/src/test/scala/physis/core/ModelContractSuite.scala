package physis.core

import physis.core.random.SplittableRng
import physis.core.testing.{Decay, DecayParams}

class ModelContractSuite extends munit.FunSuite:

  test("a toy model initialises and advances through the contracts") {
    val p = Decay.params.defaults
    val state = Decay.init(p, SplittableRng(1L))
    val ctx = StepContext()
    Decay.advance(state, p, 0.5, ctx)
    assertEquals(ctx.taken, 0.5)
    assertEqualsDouble(state.y, Decay.exact(p, 0.5), 1e-15)
  }

  test("only fixed stepping is supported by default") {
    assert(Decay.supports(Stepping.Fixed(0.1)))
    assert(!Decay.supports(Stepping.Adaptive(1e-6, 1e-9, 1e-3, 0.1)))
  }

  test("stepping validation") {
    assertEquals(Stepping.Fixed(0.1).validate, None)
    assert(Stepping.Fixed(0.0).validate.isDefined)
    assert(Stepping.Fixed(Double.NaN).validate.isDefined)
    assertEquals(Stepping.Adaptive(1e-6, 1e-9, 1e-3, 0.1).validate, None)
    assert(Stepping.Adaptive(0.0, 1e-9, 1e-3, 0.1).validate.isDefined)
    assert(Stepping.Adaptive(1e-6, 1e-9, 0.5, 0.1).validate.isDefined)
  }

  test("step random streams depend only on seed and step index") {
    val a = StepContext()
    val b = StepContext()
    a.seedWith(SplittableRng(9L))
    b.seedWith(SplittableRng(9L))
    a.step = 3L
    b.step = 3L
    assertEquals(a.rng.nextLong(), b.rng.nextLong())
  }

  test("probes measure state and time") {
    val probe = Probe[physis.core.testing.DecayState]("y")((s, _) => s.y)
    val state = Decay.init(DecayParams(1.0, 2.0), SplittableRng(0L))
    assertEquals(probe.name, "y")
    assertEquals(probe.measure(state, 0.0), 2.0)
  }

  test("digest is bit exact") {
    assertEquals(Digest.ofDoubles(1.0, 2.0), Digest.ofDoubles(Array(1.0, 2.0)))
    assertNotEquals(Digest.ofDoubles(0.0), Digest.ofDoubles(-0.0))
    assertNotEquals(Digest.ofDoubles(1.0, 2.0), Digest.ofDoubles(2.0, 1.0))
  }
