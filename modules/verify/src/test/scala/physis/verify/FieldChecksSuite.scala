package physis.verify

class FieldChecksSuite extends munit.FunSuite:

  private val steps = Seq(0.2, 0.1, 0.05, 0.025)

  private def centralError(h: Double): Double =
    math.abs((math.sin(1.0 + h) - math.sin(1.0 - h)) / (2 * h) - math.cos(1.0))

  private def forwardError(h: Double): Double =
    math.abs((math.sin(1.0 + h) - math.sin(1.0)) / h - math.cos(1.0))

  test("hasOrder accepts the right order and catches a first-order scheme") {
    assert(Convergence.hasOrder(steps, steps.map(centralError), expected = 2.0))
    assert(!Convergence.hasOrder(steps, steps.map(forwardError), expected = 2.0))
    assert(Convergence.hasOrder(steps, steps.map(forwardError), expected = 1.0))
  }

  /** Periodic 1D diffusion on plain arrays; `leak` removes mass at the right edge. */
  private def totals(leak: Double, stepsTaken: Int): Seq[Double] =
    val n = 32
    var u = Array.tabulate(n)(i => if i == 5 || i == 6 then 1.0 else 0.0)
    val totalsBuilder = Seq.newBuilder[Double]
    totalsBuilder += u.sum
    for _ <- 1 to stepsTaken do
      val next = new Array[Double](n)
      for i <- 0 until n do
        val l = u((i + n - 1) % n)
        val r = u((i + 1) % n)
        next(i) = u(i) + 0.2 * (l - 2 * u(i) + r)
      next(n - 1) *= 1.0 - leak
      u = next
      totalsBuilder += u.sum
    totalsBuilder.result()

  test("drift is tiny for a conservative scheme and catches a leaky boundary") {
    assert(Conservation.drift(totals(leak = 0.0, 500)) < 1e-12)
    assert(Conservation.drift(totals(leak = 1e-3, 500)) > 1e-6)
  }

  test("nonIncreasing accepts a decay and catches growth") {
    assert(Conservation.nonIncreasing(Seq(3.0, 2.0, 2.0, 1.5)))
    assert(!Conservation.nonIncreasing(Seq(3.0, 2.0, 2.1, 1.5)))
    assert(Conservation.nonIncreasing(Seq(3.0, 2.0, 2.0 + 1e-15, 1.5), slack = 1e-12))
  }

  test("minimum-image displacement wraps into half the period") {
    assertEquals(Neighbours.displacement(1.0, 9.0, 10.0), -2.0)
    assertEquals(Neighbours.displacement(9.0, 1.0, 10.0), 2.0)
    assertEquals(Neighbours.displacement(2.0, 7.0, 10.0), 5.0)
    assertEquals(Neighbours.displacement(7.0, 2.0, 10.0), -5.0)
  }

  test("bruteForce finds neighbours across the periodic edge, and the radius counts") {
    val x = Array(0.5, 9.5, 5.0, 5.0)
    val y = Array(5.0, 5.0, 1.0, 4.0)
    val found = Neighbours.bruteForce(x, y, 4, width = 10.0, height = 10.0, radius = 1.0)
    assertEquals(found, Vector(Set(1), Set(0), Set.empty[Int], Set.empty[Int]))
    val exact = Neighbours.bruteForce(x, y, 4, 10.0, 10.0, radius = 3.0)
    assertEquals(exact(2), Set(3))
  }

  test("mismatch is silent for equal lists and names a missing neighbour") {
    val x = Array(0.5, 9.5, 5.0)
    val y = Array(5.0, 5.0, 5.0)
    val expected = Neighbours.bruteForce(x, y, 3, 10.0, 10.0, 1.0)
    assertEquals(Neighbours.mismatch(expected, expected), None)
    val broken = expected.updated(1, Set.empty[Int])
    val message = Neighbours.mismatch(expected, broken).getOrElse(fail("no mismatch reported"))
    assert(message.contains("particle 1"), message)
    assert(message.contains("missing [0]"), message)
    val extra = expected.updated(2, Set(0))
    assert(Neighbours.mismatch(expected, extra).exists(_.contains("extra [0]")))
    assert(Neighbours.mismatch(expected, expected.take(2)).isDefined)
  }
